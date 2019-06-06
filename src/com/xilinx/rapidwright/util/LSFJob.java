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

/**
 * A batch job to be run on an LSF cluster.
 * 
 * Created on: Jan 26, 2018
 */
public class LSFJob extends Job {
	
	public static String LSF_RESOURCE = "select[osver=ws7]";//"select[type=X86_64 && osdistro=rhel && (osver=ws6 || osver=sv6)] rusage[mem=6000]";
	
	public static String LSF_PROJECT = "RapidWright";
	
	public static String LSF_QUEUE = "medium";
	
	
	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#launchJob()
	 */
	@Override
	public long launchJob() {
		Pair<String,String> launchScriptNames = createLaunchScript();
		String[] cmd = new String[]{
				"bsub","-R",
				LSF_RESOURCE,
				"-J",
				getRunDir()==null? System.getProperty("user.dir") : getRunDir(),
				"-oo",
				launchScriptNames.getSecond().replace(DEFAULT_LOG_EXTENSION, "_lsf_%J" + DEFAULT_LOG_EXTENSION),
				"-P",
				LSF_PROJECT +"-"+ System.getenv("USER"), 
				"-q", 
				LSF_QUEUE,
				FileTools.isWindows() ? "cmd.exe" : "/bin/bash",
				launchScriptNames.getFirst()};
		
		for(String line : FileTools.getCommandOutput(cmd)){
			String jobID = line.substring(line.indexOf('<')+1, line.indexOf('>'));
			setJobNumber(Integer.parseInt(jobID));
	    	return getJobNumber();
		}
		return -1;
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#isFinished()
	 */
	@Override
	public boolean isFinished() {
		for(String line : FileTools.getCommandOutput(new String[]{"bjobs"})){
			if(line.contains(Long.toString(getJobNumber()))) return false;
			if(line.contains("No unfinished job found")){
				return true;
			}
	    }
		return true;
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#jobWasSuccessful()
	 */
	@Override
	public boolean jobWasSuccessful() {
		for(String line : FileTools.getCommandOutput(new String[]{"bjobs", "-l", Long.toString(getJobNumber())})){
			if(line.contains("Exited with exit code")) return false;
		}
		return true;
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
