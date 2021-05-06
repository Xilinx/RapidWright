/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *
 * This file is part of RapidWright. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
/**
 * 
 */
package com.xilinx.rapidwright.util;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Used to manage a batch of task jobs to run in parallel. 
 * 
 * Created on: Jan 26, 2018
 */
public class JobQueue {

	public static int MAX_LOCAL_CONCURRENT_JOBS = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
	
	public static int MAX_LSF_CONCURRENT_JOBS = 120;
	
	public static boolean USE_LSF_IF_AVAILABLE = true; 
	
	private Queue<Job> waitingToRun;
	
	private ConcurrentLinkedQueue<Job> running;
	
	private Queue<Job> finished;

	public static final String LSF_AVAILABLE_OPTION = "-lsf_available";
	public static final String LSF_RESOURCE_OPTION = "-lsf_resource";
	public static final String LSF_QUEUE_OPTION = "-lsf_queue";
	
	
	public JobQueue(){
		waitingToRun = new LinkedList<>();
		running = new ConcurrentLinkedQueue<>();
		finished = new LinkedList<>();
	}
	
	public boolean addJob(Job j){
		return waitingToRun.add(Objects.requireNonNull(j));
	}
	
	public boolean addRunningJob(Job j){
		return running.add(j);
	}

	public boolean runAllToCompletion() {
		return runAllToCompletion(isLSFAvailable() ? JobQueue.MAX_LSF_CONCURRENT_JOBS : JobQueue.MAX_LOCAL_CONCURRENT_JOBS);
	}
	
	public boolean runAllToCompletion(int maxNumRunningJobs){
		while(!waitingToRun.isEmpty() || !running.isEmpty()){
			boolean launched = false;
			while(!waitingToRun.isEmpty() && maxNumRunningJobs > running.size()){
				Job j = waitingToRun.poll();
				long pid = j.launchJob();
				running.add(j);
				System.out.println("Running job [" + pid + "] " + j.getCommand() + " in " + j.getRunDir());
				launched = true;
			}

			if(!launched){
				try {
					System.out.println("Waiting on " + running.size() + " jobs still running, "+waitingToRun.size()+" not yet started...");
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					killAllRunningJobs();
					throw new RuntimeException("ERROR: Jobs killed due to InterruptedException");
				}
			}
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			for(Job j : running){
				if(j.isFinished()) running.remove(j);
				finished.add(j);
			}
		}
		int failedCount = 0;
		boolean success = true;
		for(Job j : finished){
			boolean curr = j.jobWasSuccessful();
			if(!curr){
				if(failedCount == 0){
					// Let's just print the first error output
					String logFileName = j.getRunDir() + File.separator + Job.DEFAULT_COMMAND_LOG_FILE;
					if(logFileName != null && new File(logFileName).exists()){
						ArrayList<String> lines = FileTools.getLinesFromTextFile(logFileName);
						System.err.println("***************************************************************************");
						System.err.println("* ERROR: Job " + j.getJobNumber() + " failed");
						System.err.println("* LOG FILE: " + logFileName);
						System.err.println("*  Here are the last few lines of the log:");
						int start = lines.size() >= 8 ? lines.size()-8 : 0; 
						for(int i=start; i < lines.size(); i++){
							System.err.println(lines.get(i));
						}
						System.err.println("***************************************************************************");
					}
				}
				failedCount++;
			}
			success &= curr;
		}
		//if(failedCount > 0) System.err.println("Failed Job Count: " + failedCount);
		return success;
	}
	
	
	public boolean killAllRunningJobs(){
		for(Job j : running) {
			j.killJob();
		}
		MessageGenerator.briefError("Killing all running jobs...");
		long watchdog = System.currentTimeMillis(); 
		while(!running.isEmpty() && (System.currentTimeMillis() - watchdog < 5000)){
			Job j = running.poll();
			if(j.isFinished()) finished.add(j);
			else {
				running.add(j);
				try{Thread.sleep(200);} catch (InterruptedException e) {break;}
			}
		}
		if(!running.isEmpty()){
			MessageGenerator.briefError("ERROR: Couldn't kill all running jobs, still running are pid=" + running);
			return false;
		}
		MessageGenerator.briefError("All Jobs Killed.");
		return true;
	}

	private static Boolean lsfAvailable = null;
	public static boolean isLSFAvailable(){
		if (lsfAvailable == null) {
			if(FileTools.isExecutableOnPath("bsub")){
				lsfAvailable = JobQueue.USE_LSF_IF_AVAILABLE;
			} else {
				lsfAvailable = false;
			}
		}
		return lsfAvailable;
	}

	/**
	 * Create a new Job. Will use LSF if available. If not, a local job will be created.
	 * @return the new Job
	 */
	public static Job createJob() {
		if (isLSFAvailable()) {
			return new LSFJob();
		}
		return new LocalJob();
	}
	
	public static void main(String[] args) throws InterruptedException {
		JobQueue q = new JobQueue();
		
		// Run a test if no arguments
		if(args.length == 0){
			String mainDir = System.getenv("HOME") + File.separator+ "JobQueueTest" + File.separator;
			for(int i=0; i < 10; i++){
				Job job = createJob();
				job.setCommand("vivado -version");
				job.setRunDir(mainDir + i);
				q.addJob(job);
			}
		}else{
			if(args[0].equalsIgnoreCase(LSF_AVAILABLE_OPTION)){
				System.out.println(Boolean.toString(isLSFAvailable()));
				return;
			}else if(args[0].equalsIgnoreCase(LSF_RESOURCE_OPTION)){
				System.out.println(LSFJob.LSF_RESOURCE);
				return;
			}else if(args[0].equalsIgnoreCase(LSF_QUEUE_OPTION)){
				System.out.println(LSFJob.LSF_QUEUE);
				return;
			}
			// Read a file in where each line is a job, command is first token, run directory is second
			// separated by '#'
			for(String line : FileTools.getLinesFromTextFile(args[0])){
				String[] parts = line.split("#");
				Job j = createJob();
				j.setCommand(parts[0].trim());
				j.setRunDir(parts[1].trim());
				q.addJob(j);
			}
		}
		boolean success = q.runAllToCompletion();
		if(success) System.out.println("Runs completed successfully");
		else System.err.println("One or more runs failed");
	}

	public int getCount() {
		return waitingToRun.size() + running.size() + finished.size();
	}
}
