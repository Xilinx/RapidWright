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
import java.io.IOException;
import java.lang.reflect.Field;



/**
 * A batch job to be run locally on the current host. 
 * 
 * Created on: Jan 26, 2018
 */
public class LocalJob extends Job {

	private Process p;
	
	private static int jobCount = 0;
	
	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#launchJob()
	 */
	@Override
	public long launchJob() {
		Pair<String,String> launchScriptNames = createLaunchScript();
		
		try {
			ProcessBuilder pb = new ProcessBuilder();
			pb.redirectErrorStream(true);
			pb.redirectOutput(new File(launchScriptNames.getSecond()));
			pb.command(launchScriptNames.getFirst());
			p = pb.start();
		} catch (IOException e) {
			e.printStackTrace();
		}
		setJobNumber(getProcessID());
		return getJobNumber();
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#isFinished()
	 */
	@Override
	public boolean isFinished() {
		return !p.isAlive();
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#jobWasSuccessful()
	 */
	@Override
	public boolean jobWasSuccessful() {
		return p.exitValue() == 0;
	}

	
	public long getProcessID(){
		/* -- This technique uses reflective access to private members of protected JDK classes
		 * -- and causes warnings and potentially future errors.  We will just use a running
		 * -- count for unique IDs.
		String className = p.getClass().getName();
		if(className.equals("java.lang.UNIXProcess")){
			try{
				Field f = p.getClass().getDeclaredField("pid");
				f.setAccessible(true);
				return f.getInt(p);
			}catch(Exception e){
				return -1;
			}
		}else if(className.equals("java.lang.ProcessImpl") || className.equals("java.lang.Win32Process")){
			try{
				Field f = p.getClass().getDeclaredField("handle");
				f.setAccessible(true);
				return (int)f.getLong(p);
			}catch(Exception e){
				return -1;
			}
		}
		*/
		return ++jobCount;
	}

	/* (non-Javadoc)
	 * @see com.xilinx.rapidwright.util.Job#killJob()
	 */
	@Override
	public void killJob() {
		p.destroyForcibly();
	}
}
