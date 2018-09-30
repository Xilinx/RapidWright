/* 
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.tests;

import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Simple test to measure compressed file loading times.
 * @author clavin
 *
 */
public class CheckFileLoadingSpeed {

	
	public static void main(String[] args) {
		if(args.length > 2 || args.length == 0){
			MessageGenerator.briefMessageAndExit(
				"USAGE: [-c | --compressed] <serializedFileName>");
		}
		if(args[0].contains("-c")){
			long start = System.currentTimeMillis();
			Object o = FileTools.loadFromCompressedFile(args[1]);
			long stop = System.currentTimeMillis();
			System.out.println("Loaded compressed file " + args[1] + " in " +
					(stop-start) + " ms (" + o.getClass().getCanonicalName() + ")");
		}
		else{
			long start = System.currentTimeMillis();
			Object o = FileTools.loadFromFile(args[0]);
			long stop = System.currentTimeMillis();
			System.out.println("Loaded file " + args[0] + " in " +
					(stop-start) + " ms (" + o.getClass().getCanonicalName() + ")");
		}
	}
}
