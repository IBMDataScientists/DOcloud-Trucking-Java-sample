package com.ibm.optim.oaas.sample.trucking;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.impl.client.CloseableHttpClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ibm.optim.oaas.client.http.HttpClientFactory;
import com.ibm.optim.oaas.client.job.AbstractJobCallback;
import com.ibm.optim.oaas.client.job.JobClient;
import com.ibm.optim.oaas.client.job.JobClientFactory;
import com.ibm.optim.oaas.client.job.JobExecutor;
import com.ibm.optim.oaas.client.job.JobExecutorFactory;
import com.ibm.optim.oaas.client.job.JobLimitException;
import com.ibm.optim.oaas.client.job.JobOutput;
import com.ibm.optim.oaas.client.job.JobRequest;
import com.ibm.optim.oaas.client.job.JobResponse;
import com.ibm.optim.oaas.sample.trucking.model.Problem;
import com.ibm.optim.oaas.sample.trucking.model.ResultDeserializerContext;
import com.ibm.optim.oaas.sample.trucking.model.Solution;

/**
 * This controller creates multiple job requests from the same model, but with 
 * different data for each. The jobs are all created and sent to the server 
 * asynchronously. Depending on the subscription type and the current number of 
 * existing jobs, a job may fail because the maximum job limit has been reached. 
 * In this case, additional jobs will be created to re-submit corresponding
 * problems (up to a maximum number of trials).
 */
public class ControllerMultiJobs {

  public static Logger LOG = Logger.getLogger(ControllerMultiJobs.class.getName());
  
  JobExecutor executor;
  JobClient jobclient;
  URL modFile;
  
  // Create mapper instance for Java --> JSON serialization
  ObjectMapper mapper = new ObjectMapper(); 

	private static final String PARAM_PROBLEM_ID = "oaas.client.problem.id";

  /**
   * Utility class to log jobs and optionally to update a latch.
   */
  public static class LogJobCallBack extends AbstractJobCallback {
    private Problem problem;
    private String problemId;
    private CountDownLatch latch;
    private List<Problem> jobLimitProblemInstances;
    private List<JobResponse> responses;

    public LogJobCallBack(Problem problem, List<Problem> jobLimitProblemInstances, CountDownLatch latch, List<JobResponse> responses) {
      this.problem = problem;
      this.problemId = problem.getProblemId();
      this.latch=latch;
      this.jobLimitProblemInstances=jobLimitProblemInstances;
      this.responses=responses;
    }

    @Override
    public void created(JobResponse response) {
    }
    @Override
    public void submitted(JobResponse response) {
    }
    @Override
    public void running(JobResponse response) {
    }
    @Override
    public void processed(JobResponse response) {
    }
    @Override
    public void failed(JobResponse response) {
    }
    @Override
    public void interruption(JobResponse response) {
    }
    
    @Override
    public void exception(JobResponse response, Exception e) {
      if (e instanceof JobLimitException) {
        LOG.log(Level.WARNING, "The maximum job limit has been reached for the job associated with the problem: " + problemId);        
      } else {
        LOG.severe("EXCEPTION: " + problemId + " - " + response.getJobId() + " : " + e.getLocalizedMessage());
      }
      jobLimitProblemInstances.add(problem);
      if (latch!=null) latch.countDown();
    }
    @Override
    public void completed(JobResponse response) {      
      LOG.info("COMPLETED: " + problemId + " - " + response.getJobId());
      responses.add(response);
      if (latch!=null) latch.countDown();
    }
  }

  /**
	 * Constructor.
	 * @param baseURL
	 * @param apiKeyClientId
	 * @param nbJobs
	 */
  public ControllerMultiJobs(String baseURL, String apiKeyClientId, int nbJobs) {

    // Create a client
    jobclient = createJobClient(baseURL, apiKeyClientId, nbJobs);

    // Configure a job executor that can submit all jobs in parallel
    executor = JobExecutorFactory.custom()
        .threads(Math.min(nbJobs,10))
        .interval(1, TimeUnit.SECONDS).timeout(nbJobs, TimeUnit.MINUTES)
        .retry(4).retryDelay(5,TimeUnit.SECONDS)
        .build();

    // Get the OPL model file
    modFile = ControllerMultiJobs.class.getResource("opl/truck.mod");
    if (modFile == null) {
			throw new RuntimeException("The OPL model file cannot be found");
    }
  }

  /**
   * Asynchronously submit a single job.
   * @param pb
   * @param responses
   * @param latch
   */
  public void submitJob(Problem pb, List<JobResponse> responses, List<Problem> jobLimitProblemInstances, CountDownLatch latch) {
    
    // Create a deserializer that uses the current problem instance as a context 
    // for resolving references to input data
    ObjectMapper deserialize = new ObjectMapper(null, null, new ResultDeserializerContext(pb));

    // Configure a job request
    JobRequest request = jobclient.newRequest()
        .input("model.mod", modFile)
        .input("model.json", mapper, pb)
        .parameter(PARAM_PROBLEM_ID, pb.getProblemId())
        .log(new File("results.log"))
        .output(deserialize, Solution.class)
        .deleteOnCompletion(true)
        .build();
    
    // Submit the jobs asynchronously. The callback will be called when a job has 
    // completed to decrement the latch
    Future<JobResponse> response;
    try {
      response = executor.execute(request, new LogJobCallBack(pb, jobLimitProblemInstances, latch, responses));      
    } catch (Exception e) {
      LOG.log(Level.WARNING, "Error while executing a job",e);
    }
  }

  /**
   * Handle the response from a job that has been submitted asynchronously.
   * @param response
   */
  public Solution processResponse(JobResponse response) {
   
        String problemId = response.getData().getParameters().getAsString(PARAM_PROBLEM_ID);
        
        switch(response.getJob().getExecutionStatus()){
        case PROCESSED:        	
          List<? extends JobOutput> output = response.getOutput();
          Solution solution = (Solution) output.get(0).getContent();
          if (solution==null){
        	  LOG.info(response.getJobId()+": "+problemId + " solve status is "+response.getJob().getSolveStatus());
          } else {	         
	          LOG.info(response.getJobId()+": "+problemId + " --> TOTAL COST = " + solution.getResult().getTotalCost());	         
          }
          return solution;

        case FAILED:
          String message = "";
          if (response.getJob().getFailureInfo()!=null){
            message = response.getJob().getFailureInfo().getMessage();
          }
          LOG.warning(response.getJobId()+": "+"Failed "+message);
          break;
        default:
          break;        
        }
        
    return null;
  }

  /**
   * Create a job client that can handle multiple connections.
   * @param baseURL
	 * @param apiKeyClientId
   * @param maxConnections
   * @return The job client
   */
  public JobClient createJobClient(String baseURL, String apiKeyClientId, int maxConnections) {
    CloseableHttpClient httpclient = 
      HttpClientFactory.create(HttpClientFactory.DEFAULT_CONNECTION_TIMEOUT, 
                               maxConnections, 
                               HttpClientFactory.DEFAULT_SOCKET_TIMEOUT);
    JobClient client = JobClientFactory.custom(baseURL, apiKeyClientId).http(httpclient).build();
    return client;
  }

  public void shutdown(){
    executor.shutdown();
  }

  /**
   * Main.
   * @param args
   */
  public static void main(String[] args){
	  if (args.length<2){
			System.out.println("The base URL and API key are missing");
			System.exit(1);
		}
		
		// Get base URL and API key from the given arguments
		String baseURL = args[0];
		String apiKeyClientId = args[1];

    // Number of jobs to be submitted in parallel
    final int NB_JOBS = 10;

    // Create the controller
    ControllerMultiJobs ctrl = new ControllerMultiJobs(baseURL, apiKeyClientId, NB_JOBS);

    // Create all problem instances
    ProblemFactory pbFactory = new ProblemFactory();
    List<Problem> problemInstances = new ArrayList<Problem>();
    for (int i = 0; i < NB_JOBS; i++) {
      // Create a problem instance
      String problemId = "Problem #" + i;
      Problem pb = pbFactory.createProblemWithRandomShipments(1, 300 + i * 50, 100 + i * 20);
      pb.setProblemId(problemId);
      problemInstances.add(pb);
    }

    // List to hold the results of completed jobs
    List<JobResponse> responses = new ArrayList<JobResponse>();
    
    // Send all jobs to the server by blocks, so that if job limit is reached,
    // jobs will be retried in a next round.
    final int MAX_NB_TRIALS = NB_JOBS;
    int counterTrials = 0;
    while (!problemInstances.isEmpty() && (counterTrials++ < MAX_NB_TRIALS)) {
    	
      final CountDownLatch latch = new CountDownLatch(problemInstances.size());

      // Send all jobs to the server
      List<Problem> problemsToRetry = new ArrayList<Problem>();
      for (Problem pb : problemInstances) {
        ctrl.submitJob(pb, responses, problemsToRetry, latch);
      }

      // Wait for all jobs to compelete or raise exception
      try {
        latch.await();
      } catch (InterruptedException e) {
        LOG.log(Level.WARNING, "An error was encountered while waiting for job completion",e);
      }

      problemInstances = problemsToRetry;
    }

    // Check the results
    for (JobResponse response : responses){
      ctrl.processResponse(response);
    }
    
    // Shut down the controller
    ctrl.shutdown();
  }
}
