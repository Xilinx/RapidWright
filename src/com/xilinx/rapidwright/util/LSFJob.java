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

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A batch job to be run on an LSF cluster.
 * 
 * Created on: Jan 26, 2018
 */
public class LSFJob extends Job {
	
	public static final String LSF_RESOURCE = "select[osver=ws7]";//"select[type=X86_64 && osdistro=rhel && (osver=ws6 || osver=sv6)] rusage[mem=6000]";
	
	public static final String LSF_PROJECT = "RapidWright";
	
	public static final String LSF_QUEUE = "medium";

	private String lsfResource = LSF_RESOURCE;
	private String lsfProject = LSF_PROJECT;
	private String lsfQueue = LSF_QUEUE;


	public String getLsfResource() {
		return lsfResource;
	}

	public void setLsfResource(String lsfResource) {
		this.lsfResource = lsfResource;
	}

	/**
	 * Appends a memory liimt to this Job's LSF Resource.
	 */
	public void setLsfResourceMemoryLimit(int memLimitMb) {
		this.lsfResource += " rusage[mem="+memLimitMb+"]";
	}

	public String getLsfProject() {
		return lsfProject;
	}

	public void setLsfProject(String lsfProject) {
		this.lsfProject = lsfProject;
	}

	public String getLsfQueue() {
		return lsfQueue;
	}

	public void setLsfQueue(String lsfQueue) {
		this.lsfQueue = lsfQueue;
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#launchJob()
	 */
	@Override
	public long launchJob() {
		Pair<String,String> launchScriptNames = createLaunchScript();
		String[] cmd = new String[]{
				"bsub","-R",
				lsfResource,
				"-J",
				getRunDir()==null? System.getProperty("user.dir") : getRunDir(),
				"-oo",
				launchScriptNames.getSecond().replace(DEFAULT_LOG_EXTENSION, "_lsf_%J" + DEFAULT_LOG_EXTENSION),
				"-P",
				lsfProject +"-"+ System.getenv("USER"),
				"-q", 
				lsfQueue,
				FileTools.isWindows() ? "cmd.exe" : "/bin/bash",
				launchScriptNames.getFirst()};

		ArrayList<String> commandOutput = FileTools.getCommandOutput(cmd);
		try {
			if (commandOutput.size() != 1) {
				throw new RuntimeException("not one line");
			}
			String line = commandOutput.get(0);
			int startIdx = line.indexOf('<');
			int endIdx = line.indexOf('>');
			if (startIdx == -1 || endIdx == -1) {
				throw new RuntimeException("did not find < or >");
			}
			String jobID = line.substring(startIdx+1, endIdx);
			setJobNumber(Integer.parseInt(jobID));
			return getJobNumber();
		} catch (RuntimeException e) {
			throw new RuntimeException("unexpected output when starting lsf job:\n"+String.join("\n", commandOutput), e);
		}
	}

	Integer savedExitCode = null;

	/**
	 * Get the Job's status
	 * @return Pair of (Finished, exit code)
	 */
	private Pair<Boolean, Integer> getStatus() {
		if (savedExitCode != null) {
			return new Pair<>(true, savedExitCode);
		}
		List<String> cmdOutput = FileTools.getCommandOutput(new String[]{"bjobs", "-o", "jobid stat exit_code exit_reason", "-json", Long.toString(getJobNumber())});
		String outputString = String.join("\n", cmdOutput);
		try {
			JSONObject rootObject = new JSONObject(outputString);
			JSONArray records = rootObject.getJSONArray("RECORDS");
			if (records.length() != 1) {
				throw new RuntimeException("did not get info of exactly one job");
			}
			JSONObject jobInfo = records.getJSONObject(0);
			long jobid = jobInfo.getLong("JOBID");
			if (jobid != getJobNumber()) {
				throw new RuntimeException("Unexpected job id " + jobid + ", expected " + getJobNumber());
			}

			if (jobInfo.has("ERROR")) {
				String error = jobInfo.getString("ERROR");
				if (error.contains("is not found")) {
					//We assume the job has not yet started
					return new Pair<>(false, 0);
				} else {
					throw new RuntimeException("LSF Error: "+error);
				}
			}

			String status = jobInfo.getString("STAT");

			//Finished without error?
			boolean isDone = status.equals("DONE");
			if (isDone) {
				savedExitCode = 0;
				return new Pair<>(true, 0);
			}

			//Finished with error?
			boolean isExited = status.equals("EXIT");
			if (!isExited) {
				//Not finished yet
				return new Pair<>(false, 0);
			}

			String exitcode = jobInfo.getString("EXIT_CODE");
			int exitInt = Integer.parseInt(exitcode);
			if (exitInt == 0) {
				throw new RuntimeException("Status claims exit with error, but exitCode is 0!");
			}
			savedExitCode = exitInt;
			return new Pair<>(true, exitInt);
		} catch (RuntimeException e) {
			throw new RuntimeException("Failed getting status. cmd Output: \n"+outputString, e);
		}
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#isFinished()
	 */
	@Override
	public boolean isFinished() {
		return getStatus().getFirst();
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#jobWasSuccessful()
	 */
	@Override
	public boolean jobWasSuccessful() {
		int exitCode = getStatus().getSecond();
		return exitCode == 0;
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#killJob()
	 */
	@Override
	public void killJob() {
		for(String line : FileTools.getCommandOutput(new String[]{"bkill " + getJobNumber()})){
			System.out.println(line);
		}
	}

}
