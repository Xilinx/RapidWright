/*
 * 
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

/**
 * Reports the runtime and memory usage on a handful of devices.
 * Created on: Dec 19, 2017
 */
public class ReportDevicePerformance {

	public static void main(String[] args) {
		String[] partNames = new String[]{
				"xc7a12t",
				"xc7s100",
				"xc7z020",
				"xc7v2000t",
				"xcku040",
				"xcvu440",
				"xcvu9p",
				"xczu19eg"
		};
		String deviceLoad = "Device Load";
		for(String partName : partNames){
			CodePerfTracker p = new CodePerfTracker(partName,false);
			p.start(deviceLoad);
			Device d = Device.getDevice(partName);
			p.stop();
			
			System.out.printf("%12s: %2.3fs %7.3fMBs\n", 
					d.getDeviceName(), 
					(p.getRuntime(deviceLoad))/1000000000.0,
					(p.getMemUsage(deviceLoad))/(1024.0*1024.0));
			
			//System.out.println(d.getDeviceName() + " " +  + " " + );
		}
	}
}
