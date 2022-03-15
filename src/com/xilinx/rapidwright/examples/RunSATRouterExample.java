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
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.router.SATRouter;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Small example that illustrates how to invoke the SAT router 
 * to route a small area of a design by providing a fully placed DCP
 * and a pblock area constraint to the router.  
 * @author clavin
 *
 */
public class RunSATRouterExample {

	public static void main(String[] args) {
		// Check args
		if(args.length != 3){
			System.out.println("USAGE: java " + RunSATRouterExample.class.getCanonicalName() + " " 
							+ "<placed_dcp_filename> <pblock_area_constraint> <output_dcp>");
			return;
		}
		// Check for Vivado
		String vivadoPath = FileTools.getVivadoPath();
		if(vivadoPath == null || vivadoPath.length() == 0){
			throw new RuntimeException("ERROR: Couldn't find vivado, please set PATH environment variable accordingly.");
		}
		
		// Read checkpoint and create pblock from args
		Design design = Design.readCheckpoint(args[0]);
		PBlock pblock = new PBlock(design.getDevice(), args[1]); // Example: "SLICE_X68Y134:SLICE_X72Y149 DSP48E2_X12Y54:DSP48E2_X12Y59"
		
		design.unrouteDesign();
		
		// Create and invoke SAT router
		SATRouter satRouter = new SATRouter(design, pblock);
		satRouter.route();
		
		// Write out the results
		design.writeCheckpoint(args[2]);
	}
}
