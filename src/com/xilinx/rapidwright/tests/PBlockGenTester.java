/* 
 * Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.tests;

import java.io.File;

import com.xilinx.rapidwright.design.blocks.PBlockGenerator;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Tests the {@link PBlockGenerator} with a set of designs created in a runs directory.
 * 
 * Created on: Jun 20, 2016
 */
public class PBlockGenTester {
	public static void main(String[] args) {
		if(args.length != 1){
			MessageGenerator.briefMessageAndExit("USAGE: <dir_to_project_runs>");
		}
		
		File dir = new File(args[0]);
		if(dir.exists() && dir.isDirectory()){
			for(File child : dir.listFiles()){
				if(!child.isDirectory()) continue;
				String shapeFile = null;
				String utilReportFile = null;
				for(File file : child.listFiles()){
					if(file.getAbsoluteFile().getName().endsWith("_shapes.txt")){
						shapeFile = file.toString();
					}else if(file.getAbsoluteFile().getName().endsWith("_utilization.report")){
						utilReportFile = file.toString();
					}
				}
				if(shapeFile != null && utilReportFile != null){
					System.out.println(child.getName() + " " + utilReportFile + " " + shapeFile);
					PBlockGenerator.main(new String[]{
							"-u", utilReportFile, 
							"-s", shapeFile,
							"-c", "1",
						//	"-a", "0.25",
						//	"-o", "1.5",
							});
				}
			}
		}
	}
}
