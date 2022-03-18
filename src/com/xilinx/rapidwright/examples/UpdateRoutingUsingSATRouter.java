/* 
 * Copyright (c) 2019 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.router.SATRouter;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * Example of how to invoke SAT Router to replace failed routing run.
 * @author clavin
 */
public class UpdateRoutingUsingSATRouter {

	public static void main(String[] args) {
		// Check args
		if(args.length != 3){
			System.out.println("USAGE: java " + UpdateRoutingUsingSATRouter.class.getCanonicalName() + " " 
							+ "<failed_routed_dcp> <pblock_area_constraint> <output_dcp>");
			return;
		}
		
		Design d = Design.readCheckpoint(args[0]);

		for(Net n : d.getNets()){
			if(n.isClockNet() || n.isStaticNet()) continue;
			n.unroute();
		}
		
		CodePerfTracker t = new CodePerfTracker("SAT Router");
		t.start("SAT Router");
		PBlock pblock = new PBlock(d.getDevice(), args[1]);
		SATRouter satRouter = new SATRouter(d,pblock);
		
		satRouter.route();
		t.stop().printSummary();
		
		
		d.writeCheckpoint(args[2]);
	}
}
