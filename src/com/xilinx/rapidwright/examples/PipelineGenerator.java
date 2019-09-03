/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Neely, Xilinx Research Labs.
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

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.edif.*;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * Generates a delay of <depth> cycles for a bus of <width> w
 * using flip flops.  Can specify <distance> in tiles and set a <direction>.
 * @author cneely
 *
 */
public class PipelineGenerator {
	protected static final String PART_OPT = "p";
	protected static final String DESIGN_NAME_OPT = "d";
	protected static final String OUT_DCP_OPT = "o";
	protected static final String CLK_NAME_OPT = "c";
	protected static final String CLK_CONSTRAINT_OPT = "x";

	protected static final String WIDTH_OPT = "n";
	protected static final String DEPTH_OPT = "m";
	protected static final String DISTANCE_OPT = "l";

	protected static final String VERBOSE_OPT = "v";
	protected static final String HELP_OPT = "h";


	public static final int BITS_PER_CLE = 8;

	private static final String SLICE_SITES_OPT = "s";

	public static String INPUT_NAME = "IN";
	public static String OUTPUT_NAME = "OUT";

	enum direction {vertical, horizontal;}


	public static PBlock createPipeline(Design d, Site startingPoint, int width, int depth, int distance, direction dir, boolean route){

		if (dir == direction.vertical && (distance < Math.ceil(width/8))) {
			System.err.println("Error: the width (="+width+") and distance (="+distance+") parameters conflict in a way "+
					"that would result in an overlap in the veritical direction.  "+
					"Please choose different parameters or modify this example.");

			/* Note: Sites is the term for the locations on the device for placing instances.
			When getting neighboring sites, e.g. when using adjacent slices, the function takes in a dx and dy as parameters.
			This is also noted further below within the nested for loops.  The dx and dy can be modified to help control placement.
			 */
			System.exit(1);
		}

		EDIFCell top = d.getNetlist().getTopCell();
		Set<Site> used = new HashSet<>();
		String bus = "["+(width-1)+":0]";

		/* Note: some of the first objects below are the EDIFNet objects, having to do with the logical connections
		 * of modules.  In general, the EDIF-related objects are used for capturing the logical representation.
		 * We are creating the I/O for this module below.
		 *
		 * Some of the conditions below were added in case the pipeline is later instantiated within a larger design.
		 * */
		EDIFPort inputPort = top.createPort(INPUT_NAME + bus, EDIFDirection.INPUT, width);
		EDIFPort outputPort = top.createPort(OUTPUT_NAME + bus, EDIFDirection.OUTPUT, width);
		EDIFPort clkPort;
		EDIFPort cePort;
		EDIFPort rstPort;

		EDIFPort test = top.getPort("clk");
		boolean alreadyHasClk = test != null;
		if (!alreadyHasClk)
			clkPort = top.createPort("clk", EDIFDirection.INPUT, 1);
		else
			clkPort = test;

		test = top.getPort("ce");
		boolean alreadyHasCE = test != null;
		if (!alreadyHasCE)
			cePort = top.createPort("ce", EDIFDirection.INPUT, 1);
        else
            cePort = test;

		test = top.getPort("rst");
		boolean alreadyHasRst = test != null;
		if (!alreadyHasRst)
			rstPort = top.createPort("rst", EDIFDirection.INPUT, 1);
		else
			rstPort = test;

		EDIFNet clk;
		EDIFNet ce;
		EDIFNet rst;

		if (!alreadyHasClk) {
			clk = top.createNet(clkPort.getName());
			clk.createPortInst(clkPort);
		} else {
			clk = top.getNet(clkPort.getName());
		}

		if (!alreadyHasCE) {
			ce = top.createNet(cePort.getName());
			ce.createPortInst(cePort);
		} else {
			ce = top.getNet(cePort.getName());
		}

		if (!alreadyHasRst) {
			rst = top.createNet(rstPort.getName());
			rst.createPortInst(rstPort);
		} else {
			rst = top.getNet(rstPort.getName());
		}

		/* The "Net" objects below are used later for representing the physical connections in the implementation of the design. */
		Net clkNet = d.createNet(clk);
		Net rstNet = d.createNet(rst);
		Net ceNet = d.createNet(ce);


		EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, top, d.getNetlist());
		EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, top, d.getNetlist());


		EDIFNet[][] ic = new EDIFNet[depth+1][width];
		boolean[][] ic_net_created = new boolean[depth+1][width];

		for(int j=0; j <= depth; j++) {
			for (int i = width - 1; i >= 0; i--) {
				String index = "[" + i + "]";
				String ic_name;
				if (j == 0) {
					ic_name = INPUT_NAME + index;
				} else if (j == depth) {
					ic_name = OUTPUT_NAME + index;
				} else {
					ic_name = "bus" + j + "_" + index;
				}
				ic[j][i] = top.createNet(ic_name);
			}
		}

		/* The starting point was passed into the constructor for this object as slice coordinates.  It was set in the CreateOptionParser() method. */
		Site prevSite = startingPoint;
		Site newSite = startingPoint;


		// Note: the outer loop replicates flops for having multiple cycles
		for(int j=0; j < depth; j++) {

			int newSiteRow = newSite.getTile().getRow();
			int newSiteCol = newSite.getTile().getColumn();

			int newDistance = distance;

			if (j > 0) {
				int tmpDistance = distance;
				Tile testTile = null;

				if (dir == direction.vertical)
					testTile = d.getDevice().getTile(newSiteRow-tmpDistance, newSiteCol);
				else if (dir == direction.horizontal)
					testTile = d.getDevice().getTile(newSiteRow, newSiteCol+tmpDistance);

				boolean invalid = testTile.getSites().length == 0;
				while (invalid) {
						tmpDistance++;

					if (dir == direction.vertical)
						testTile = d.getDevice().getTile(newSiteRow-tmpDistance, newSiteCol);
					else if (dir == direction.horizontal)
						testTile = d.getDevice().getTile(newSiteRow, newSiteCol+tmpDistance);

					// here we are checking that we are selecting a valid site type containing flops.
					invalid = testTile.getSites().length == 0;
					invalid = invalid || !(testTile.getSites()[0].isCompatibleSiteType(SiteTypeEnum.SLICEL) ||
							testTile.getSites()[0].isCompatibleSiteType(SiteTypeEnum.SLICEM));

					newDistance = tmpDistance;
				}
				if (newDistance > distance) {
					System.out.println("Info: please note the tile distance for row "+j +" was adjusted to "+newDistance+
							" tiles from previous row.  This example did this to find the next occurring slice.");
				}
				newSite = testTile.getSites()[0];
			}
			prevSite = newSite;

			// the inner for loop creates flops for one cycle, having the width of the bus
			for (int i = width - 1; i >= 0; i--) {

				EDIFNet inputNet = ic[j][i];
				EDIFNet outputNet = ic[j + 1][i];

				/*
				 *  Note: as mentioned above, when we get the next "neighbor site", we pass it a dx and dy each time.
				 */
				Site currSlice = prevSite.getNeighborSite(0, i / BITS_PER_CLE);

				/* Below is a check in case we have reached some site type that doesn't contain flops
				* */
				int v = 0;
				while (!currSlice.isCompatibleSiteType(SiteTypeEnum.SLICEL) && !currSlice.isCompatibleSiteType(SiteTypeEnum.SLICEM)) {
					v++;
					int dy = (i+v) / BITS_PER_CLE;
					currSlice = prevSite.getNeighborSite(0, dy);
					if (currSlice == null) {
						System.err.println("Unable to find a suitable site for placing flop ["+j+"]["+i+"] " +
								"based on the current set of parameters with the starting location: "+startingPoint.toString()+".");
						System.exit(1);
					}
				}

				used.add(currSlice);

				/* Below picks the letter site containing pairs of flops.  The first flop is called "FF".  The second flop is called "FF2".
				 */
				String letter = Character.toString((char) ('A' + i % 8));
				BEL ff = currSlice.getBEL(letter + "FF");
				char[] letter_char = new char[1];
				letter.getChars(0,1,letter_char,0);
				boolean isFF2 = ff.getName().endsWith("2");
				boolean isLowerSlice = 'A' <= letter_char[0] && letter_char[0] <= 'D';
				String clkPinName = isLowerSlice ? "CLK1" : "CLK2";
				String rstPinName = isLowerSlice ? "SRST1" : "SRST2";
				String cePinName = "CKEN" + (isLowerSlice ? (isFF2 ? "2" : "1") : (isFF2 ? "4" : "3"));

				// note line below "places" the flop
				Cell ffCell = d.createAndPlaceCell(top, "d_" + j + "_" + i, Unisim.FDRE, currSlice, ff);
				ffCell.addProperty("INIT", "1'b0", EDIFValueType.STRING);
				SiteInst ff_si = ffCell.getSiteInst();

				// connect the flop I/O to the physical nets
				inputNet.createPortInst("D", ffCell);
				outputNet.createPortInst("Q", ffCell);

				if (i ==0) prevSite = currSlice;

				if (j==0) inputNet.createPortInst(inputPort, (width - 1) - i);
				if (j==depth-1) outputNet.createPortInst(outputPort, (width - 1) - i);

				clkNet.getLogicalNet().createPortInst("C", ffCell);
				rstNet.getLogicalNet().createPortInst("R", ffCell);
				ceNet.getLogicalNet().createPortInst("CE", ffCell);

				if(ff_si.getSitePinInst(clkPinName) == null){
					clkNet.createPin(false, clkPinName, ff_si);
					ff_si.addSitePIP(clkPinName + "INV","CLK");
				}
				if(ff_si.getSitePinInst(rstPinName) == null){
					rstNet.createPin(false, rstPinName, ff_si);
					ff_si.addSitePIP("RST_"+(isLowerSlice ? "ABCD" : "EFGH")+"INV","RST");
				}
				if(ff_si.getSitePinInst(cePinName) == null){
					ceNet.createPin(false, cePinName, ff_si);
				}
			}
		}

		/* In the case that the design uses "intra-sites", for example this design uses the "letter sites" inside of a slice,
		* then it becomes necessary to call the routeSites() method below.  */
		d.routeSites();

		// Find rectangular area consumed
		PBlock footprint = new PBlock(d.getDevice(),used);

		if(route){
			Router r = new Router(d); // the Manhattan distance router
			r.setRoutingPblock(footprint);
			r.setSupressWarningsErrors(false);
			r.routeDesign();
		}

		return footprint;
	}

	private static OptionParser createOptionParser(){

		// Defaults, please modify these to experiment
		String partName = "xcvu3p-ffvc1517-2-e";
		String designName = "pipeline";
		String outputDCPFileName = System.getProperty("user.dir") + File.separator + designName +".dcp";
		String clkName = "clk";
		double clkPeriodConstraint = 1.291; // 775 MHz

		/**** SOME DEFAULTS ****/
		int width = 10;
		int depth = 3;
		int distance = 10;
		String sliceSite = "SLICE_X42Y70";
		boolean verbose = true;

		// example code for command
		OptionParser p = new OptionParser() {{
			accepts(PART_OPT).withOptionalArg().defaultsTo(partName).describedAs("Ultrascale/UltraScale+ Part Name");
			accepts(DESIGN_NAME_OPT).withOptionalArg().defaultsTo(designName).describedAs("Design Name");
			accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo(outputDCPFileName).describedAs("Output DCP File Name");
			accepts(CLK_NAME_OPT).withOptionalArg().defaultsTo(clkName).describedAs("Clk net name");
			accepts(CLK_CONSTRAINT_OPT).withOptionalArg().ofType(Double.class).defaultsTo(clkPeriodConstraint).describedAs("Clk period constraint (ns)");
			accepts(WIDTH_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(width).describedAs("width");
			accepts(DEPTH_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(depth).describedAs("depth");
			accepts(DISTANCE_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(distance).describedAs("distance");
			accepts(SLICE_SITES_OPT).withOptionalArg().defaultsTo(sliceSite).describedAs("Lower left slice to be used for pipeline");
			accepts(VERBOSE_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(verbose).describedAs("Print verbose output");
			acceptsAll( Arrays.asList(HELP_OPT, "?"), "Print Help" ).forHelp();
		}};

		return p;
	}

	private static void printHelp(OptionParser p){
		MessageGenerator.printHeader("Pipeline Generator");
		System.out.println("This RapidWright program creates an example pipelined bus as a placed and routed DCP. \n"
			+ "See the RapidWright documentation for more information.\n");
		try {
			p.accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo("pipeline.dcp").describedAs("Output DCP File Name");
			p.printHelpOn(System.out);

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static void main(String[] args) {
		// Extract program options
		OptionParser p = createOptionParser();
		OptionSet opts = p.parse(args);
		boolean verbose = (boolean) opts.valueOf(VERBOSE_OPT);
		if(opts.has(HELP_OPT)){
			printHelp(p);
			return;
		}
		CodePerfTracker t = verbose ? new CodePerfTracker(PipelineGenerator.class.getSimpleName(),true).start("Init") : null;

		String partName = (String) opts.valueOf(PART_OPT);
		String designName = (String) opts.valueOf(DESIGN_NAME_OPT);
		String outputDCPFileName = (String) opts.valueOf(OUT_DCP_OPT);
		String clkName = (String) opts.valueOf(CLK_NAME_OPT);
		double clkPeriodConstraint = (double) opts.valueOf(CLK_CONSTRAINT_OPT);

		int width = (int) opts.valueOf(WIDTH_OPT);
		int depth = (int) opts.valueOf(DEPTH_OPT);
		int distance = (int) opts.valueOf(DISTANCE_OPT);

		/******** SET THE DIRECTION HERE ********/
		direction dir = direction.horizontal;

		String sliceName = (String) opts.valueOf(SLICE_SITES_OPT);

		// Perform some error checking on inputs
		Part part = PartNameTools.getPart(partName);
		if(part == null || part.isSeries7()){
			MessageGenerator.briefErrorAndExit("ERROR: Invalid/unsupported part " + partName + ".  This example was coded "+
							  "for UltraScale or UltraScale+ devices.");
		}
		
		Design d = new Design(designName,partName);
		d.setAutoIOBuffers(false);
		Device dev = d.getDevice();
		
		t.stop().start("Create Pipeline");
		Site slice = dev.getSite(sliceName);
		createPipeline(d, slice, width, depth, distance, dir,true);
				
		// Add a clock constraint
		String tcl = "create_clock -name "+clkName+" -period "+clkPeriodConstraint+" [get_ports "+clkName+"]";
		d.addXDCConstraint(ConstraintGroup.LATE,tcl);
		d.setAutoIOBuffers(false);
		
		t.stop();
		d.writeCheckpoint(outputDCPFileName, t);
		if(verbose) System.out.println("Wrote final DCP: " + outputDCPFileName);
	}
}
