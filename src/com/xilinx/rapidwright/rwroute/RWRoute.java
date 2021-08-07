/*
 * 
 * Copyright (c) 2021 Ghent University. 
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class RWRoute {

	public static void main(String[] args) {
		if(args.length < 2){
			System.out.println("BASIC USAGE:\n <input.dcp>\n <directory for routed_input.dcp>");
			return;
		}
		// Read the design checkpoint file name
		String dcpName = args[0].substring(args[0].lastIndexOf("/")+1);
		// Read the output directory and set the output design checkpoint file name
		String routedDCPfileName = args[1].endsWith("/")?args[1] + "routed_" + dcpName : args[1] + "/routed_" + dcpName;
		// Instantiate the configuration
		Configuration config = new Configuration();
		// Parse the arguments, using the default configuration if basic usage only
		config.parseArguments(2, args);
		// Read in a design checkpoint 
		Design design = Design.readCheckpoint(args[0]);
		DesignTools.makePhysNetNamesConsistent(design);
		if(!config.isPartialRouting() || (!design.getVccNet().hasPIPs() && !design.getGndNet().hasGapRouting())) {
			DesignTools.createPossiblePinsToStaticNets(design);//TODO 0731 YZ: results in site pin conflicts of optical-flow-post-place dcp
		}
		DesignTools.createMissingSitePinInsts(design);
		CodePerfTracker t = new CodePerfTracker("RWRoute", true);
		t.start("RWRoute");
		// Instantiate the router
		RouterBase router;
		if(config.isPartialRouting()) {
			router = new PartialRouter(design, config);
		}else {
			router = new RouterBase(design, config);
		}
		// Print the design and configuration info, if "--verbose" is configured
		router.printDesignNetsInfoAndConfiguration();
		// Route the design
		router.doRouting();
		t.stop();
		// Write out the routed design checkpoint
		router.getDesign().writeCheckpoint(routedDCPfileName,t);
		System.out.println("\nINFO: Write routed design\n " + routedDCPfileName + "\n");
		// Print routing statistics, e.g. total wirelength, runtime and timing report	
		router.printRoutingStatistics();
	}
}
