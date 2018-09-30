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

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Simple tool to measure device load time.
 * 
 * Created on: Jun 29, 2016
 */
public class DeviceLoader {

	public static void main(String[] args) {
		if(args.length != 1){
			MessageGenerator.briefMessageAndExit("USAGE: <partname>");
		}
		Part p = PartNameTools.getPart(args[0]);
		if(p == null){
			MessageGenerator.briefErrorAndExit("The partname " + args[0] + " is invalid or unrecognized, cannot load device.");
		}
		CodePerfTracker track = new CodePerfTracker("Load Device for Part " + p.getName());
		track.start("Load file");
		Device d = Device.getDevice(p);
		track.stop().printSummary();
		
	}	
}
