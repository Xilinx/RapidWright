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
