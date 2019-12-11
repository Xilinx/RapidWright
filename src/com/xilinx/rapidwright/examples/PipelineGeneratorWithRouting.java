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


import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFValueType;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.GroupDistance;
import com.xilinx.rapidwright.timing.TimingDirection;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingGroup;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingModel;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.util.MessageGenerator;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import org.jgrapht.GraphPath;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.PriorityQueue;
import java.util.Arrays;

import static com.xilinx.rapidwright.timing.GroupDistance.*;
import static com.xilinx.rapidwright.timing.TimingDirection.*;

/**
 * Generates a delay of "depth" cycles for a bus of "width" w
 * using flip flops.  Can specify "distance" in tiles and set a "direction".
 * @author cneely
 *
 */
public class PipelineGeneratorWithRouting {
	protected static final String PART_OPT = "p";
	protected static final String DESIGN_NAME_OPT = "d";
	protected static final String OUT_DCP_OPT = "o";
	protected static final String CLK_NAME_OPT = "c";
	protected static final String CLK_CONSTRAINT_OPT = "x";
	protected static final String WIDTH_OPT = "n";
	protected static final String DEPTH_OPT = "m";
	protected static final String DISTANCE_X_OPT = "t";
	protected static final String DISTANCE_Y_OPT = "u";
	protected static final String VERBOSE_OPT = "v";
	protected static final String HELP_OPT = "h";
	public static final int BITS_PER_CLE = 8;
	private static final String SLICE_SITES_OPT = "s";
	public static String INPUT_NAME = "IN";
	public static String OUTPUT_NAME = "OUT";

	enum direction {vertical, horizontal}

	/**
	 * Generates the circuit for the pipeline.
	 * @param d RW Design object.
	 * @param startingPoint Site for placing the first/starting flop.
	 * @param width Bit width of the bus signal.
	 * @param depth Number of cycles to pipeline the bus.
	 * @param distanceX Change in the absolute tile coordinates in the x dimension for placing the 
	 * next flop.
	 * @param distanceY Change in the absolute tile coordinates in the y dimension for placing the 
	 * next flop.
	 * @param dir General direction of the pipeline as either horizontal or vertical.  This does not 
	 * need to be modified for this routing example.
	 * @param route Boolean specifying whether to route the design or not.  This does not need to be
	 * modified for this routing example.
	 * @return
	 */
	public static PBlock createPipeline(Design d, Site startingPoint, int width, int depth, 
                                        int distanceX, int distanceY, direction dir, boolean route){

		if (dir == direction.vertical && (distanceY < Math.ceil(width/8))) {
			System.err.println("Error: the width (="+width+") and distance (="+distanceY+") "
					+ "parameters conflict in a way "+
					"that would result in an overlap in the vertical direction.  "+
					"Please choose different parameters or modify this example.");
			return new PBlock();
		}

		EDIFCell top = d.getNetlist().getTopCell();
		Set<Site> used = new HashSet<>();
		String bus = width > 1 ? "["+(width-1)+":0]" : "";

		// Declare the I/O
		EDIFPort inputPort = top.createPort(INPUT_NAME + bus, EDIFDirection.INPUT, width);
		EDIFPort outputPort = top.createPort(OUTPUT_NAME + bus, EDIFDirection.OUTPUT, width);
		EDIFPort clkPort;
		EDIFPort cePort;
		EDIFPort rstPort;

		String filename = "pipeline_"+width+"w_"+depth+"d_dx"+
                (distanceX >= 0 ?""+distanceX : "neg"+ Math.abs(distanceX))+"_dy"+
                (distanceY >= 0 ?""+distanceY : "neg"+ Math.abs(distanceY))+"_org"+
                startingPoint+".txt";

		File logFile = new File(filename);
		PrintStream ps = null;
		try {
			ps = new PrintStream(logFile);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return new PBlock();
		}

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

		// The "Net" objects below are used later for representing the physical connections in the 
		// implementation of the design. 
		Net clkNet = d.createNet(clk);
		Net rstNet = d.createNet(rst);
		Net ceNet = d.createNet(ce);
		EDIFNet[][] ic = new EDIFNet[depth+1][width];
		Net[][] icNet = new Net[depth+1][width];
		Cell[][] ffs = new Cell[depth][width];

		for(int j=0; j <= depth; j++) {
			for (int i = width - 1; i >= 0; i--) {
				String index = "[" + i + "]";
				String ic_name;
				if (j == 0) {
					ic_name = INPUT_NAME + (width > 1 ?  index : "");
				} else if (j == depth) {
					ic_name = OUTPUT_NAME + (width > 1 ?  index : "");
				} else {
					ic_name = "bus" + j + "_" + (width > 1 ?  index : "");
				}
				ic[j][i] = top.createNet(ic_name);
				icNet[j][i] = d.createNet(ic_name);
			}
		}

		Site prevSite = startingPoint;
		Site newSite = startingPoint;

		// Note: the outer loop replicates flops for having multiple cycles
		for(int j=0; j < depth; j++) {

			int newSiteRow = newSite.getTile().getRow();
			int newSiteCol = newSite.getTile().getColumn();

			int newDistanceX = distanceX;
			int newDistanceY = distanceY;

			if (j > 0) {
				int tmpDistanceX = distanceX;
				int tmpDistanceY = distanceY;
				Tile testTile = null;

				testTile = d.getDevice().getTile(newSiteRow-tmpDistanceY, newSiteCol+tmpDistanceX);

				boolean invalid = testTile.getSites().length == 0;
				while (invalid) {
					if (distanceX >0)
						tmpDistanceX++;
					else
						tmpDistanceX--;

					testTile = d.getDevice().getTile(newSiteRow-tmpDistanceY, newSiteCol+tmpDistanceX);

					// here we are checking that we are selecting a valid site type containing flops.
					invalid = testTile.getSites().length == 0;
					invalid = invalid || !(testTile.getSites()[0].isCompatibleSiteType(SiteTypeEnum.SLICEL) ||
										   testTile.getSites()[0].isCompatibleSiteType(SiteTypeEnum.SLICEM));

					newDistanceX = tmpDistanceX;
					newDistanceY = tmpDistanceY;
				}
				newSite = testTile.getSites()[0];
				if (newDistanceX > distanceX) {
					ps.println("Info: please note the tile distance for row "+j +
							" was adjusted from "+distanceX+" to "+newDistanceX+
							" tiles from previous row.  This example did this to find the next "
							+ "occurring slice.");
				}
				ps.println("Connecting prev<c"+prevSite.getTile().getColumn()+
                        "r"+prevSite.getTile().getRow()+">:"+prevSite.getSiteTypeEnum() +
                        " with new<c"+newSite.getTile().getColumn()+"r"+newSite.getTile().getRow()+
                        ">:"+newSite.getSiteTypeEnum());
			}
			prevSite = newSite;

			// the inner for loop creates flops for one cycle, having the width of the bus
			for (int i = width - 1; i >= 0; i--) {
				EDIFNet inputNet = ic[j][i];
				EDIFNet outputNet = ic[j + 1][i];

				// Note: as mentioned above, when we get the next "neighbor site", we pass it a dx 
				// and dy each time.
				Site currSlice = prevSite.getNeighborSite(0, i / BITS_PER_CLE);

				// Below is a check in case we have reached some site type that doesn't contain flops
				int v = 0;
				while (!currSlice.isCompatibleSiteType(SiteTypeEnum.SLICEL) && 
                       !currSlice.isCompatibleSiteType(SiteTypeEnum.SLICEM)) {
					v++;
					int dy = (i+v) / BITS_PER_CLE;
					currSlice = prevSite.getNeighborSite(0, dy);
					if (currSlice == null) {
							ps.println("Unable to find a suitable site for placing flop "
									+ "[" + j + "][" + i + "] " +
									"based on the current set of parameters with the starting "
									+ "location: " + startingPoint.toString() + ".");
							return new PBlock();

					}
				}
				used.add(currSlice);

				// Below picks the letter site containing pairs of flops.  
				// The first flop is called "FF".  The second flop is called "FF2".
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
				ffs[j][i] = ffCell;

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
					clkNet.createPin(true, clkPinName, ff_si);
					ff_si.addSitePIP(clkPinName + "INV","CLK");
				}
				if(ff_si.getSitePinInst(rstPinName) == null){
					rstNet.createPin(true, rstPinName, ff_si);
					ff_si.addSitePIP("RST_"+(isLowerSlice ? "ABCD" : "EFGH")+"INV","RST");
				}
				if(ff_si.getSitePinInst(cePinName) == null){
					ceNet.createPin(true, cePinName, ff_si);
				}
			}
		}

		//
		// Below we will route intrasites first, and then intersites
		//
		
		// In the case that the design uses "intrasites", for example this design uses the 
		// "letter sites" inside of a slice, then it becomes necessary to call the routeSites() 
		// method below.  
		d.routeSites();
		d.setAutoIOBuffers(false);
		d.setDesignOutOfContext(true);

		// Find rectangular area consumed
		PBlock footprint = new PBlock(d.getDevice(),used);

		boolean useDistanceBasedRouter = false;

		// Route intersites
		if(useDistanceBasedRouter){
			Router r = new Router(d); // the non-timing driven router from RW library
			r.setRoutingPblock(footprint);
			r.setSupressWarningsErrors(false);
			r.routeDesign();
		} else {
			TimingManager tm = new TimingManager(d);
			TimingModel dm1 = tm.getTimingModel();

			for (Net n : d.getNets()) {
				for (SitePinInst sink : n.getSinkPins()) {
					SitePinInst source = n.getSource();
					/* Here is where we call our example findRoute router method */
					List<PIP> pList = findRoute(source, sink, dm1);
                    if (pList != null)
                        n.setPIPs(pList);
                    else
                    	System.err.println("Couldn't route net:"+n);
				}
			}
		}
		return footprint;
	}


	private static float currDelayCost;
	private static TimingGroup currG;
	private static HashMap<TimingGroup, TimingGroup> prevG;
	private static HashMap<TimingGroup, Float> delayCostTable;
	private static HashMap<TimingGroup, Float> visited;
	private static PriorityQueue<TimingGroup> queue;
	private static final int DISTANCE_WEIGHT = 70;
	private static int tileX2B;
	private static int tileY2B;

	public static List<PIP> findRoute(SitePinInst source, SitePinInst sink, TimingModel model) {
		List<PIP> result = new LinkedList<>();
		queue = new PriorityQueue<>();
		PriorityQueue<TimingGroup> solutions = new PriorityQueue<>();

		TimingGroup startG = new TimingGroup(source, model);
		startG.cost = startG.delay + DISTANCE_WEIGHT*source.getTile().getManhattanDistance(sink.getTile());
		queue.add(startG);

		// create a watchdog timer
		visited = new LinkedHashMap<>();
		delayCostTable = new LinkedHashMap<>();
		prevG = new LinkedHashMap<>();
		int watchdog = 500000;

		// while we still have nodes to look at, keep expanding
		currDelayCost = startG.delay;
		delayCostTable.put(startG, currDelayCost);
		boolean currGIsSolution = false;

		boolean success = false;
		while (!queue.isEmpty()) {
			currGIsSolution = false;
			currG = queue.poll();

			// check if we've reached our final sink destination
			Node currNode = currG.getLastNode();
			Wire [] currNodeWires = currNode.getAllWiresInNode();
			Wire currNodeStartWire = currNodeWires[0];
			PIP currPIP = currG.hasPIPs()? currG.getLastPIP() : null;
			currDelayCost = delayCostTable.get(currG);

			int tileX1 = sink.getTile().getTileXCoordinate();
			int tileY1 = sink.getTile().getTileYCoordinate();
			int tileX2 = currG.hasPIPs() ? currPIP.getTile().getTileXCoordinate() :
				currNodeStartWire.getTile().getTileXCoordinate();
			int tileY2 = currG.hasPIPs() ? currPIP.getTile().getTileYCoordinate() : 
				currNodeStartWire.getTile().getTileYCoordinate();
			int tileX3 = sink.getSite().getIntTile().getTileXCoordinate();
			int tileY3 = sink.getSite().getIntTile().getTileYCoordinate();

			int tileDx, tileDy, siteDx, siteDy;
			tileDx =  tileX1 - tileX2;
			tileDy = tileY1 - tileY2;

			int tileDxb =  tileX3 - tileX2;
			int tileDyb = tileY3 - tileY2;

			int siteX1 = sink.getSite().getInstanceX();
			int siteY1 = sink.getSite().getInstanceY();
			int siteX2 = currNode.getSitePin() != null ? 
					currNode.getSitePin().getSite().getInstanceX() : 
					currNodeStartWire.getTile().getTileXCoordinate();
			int siteY2 = currNode.getSitePin() != null ? 
					currNode.getSitePin().getSite().getInstanceY() : 
					currNodeStartWire.getTile().getTileYCoordinate();
			siteDx = siteX1 - siteX2;
			siteDy = siteY1 - siteY2;
			tileDx *=2;

			findRouteHelperSetTileX2BY2B(currG);
			tileDx -= tileX2B;
			tileDy -= tileY2B;

			if ((tileDx ==0 && tileDy == 0) || (siteDx ==0 && siteDy == 0) || (tileDxb == 0 && tileDyb == 0)) {
				Site checkSite = currNode.getSitePin() != null ? currNode.getSitePin().getSite() : null;
				if (checkSite == sink.getSite()) {
					String check = currNode.getWireName();
					String target = sink.getConnectedNode().getWireName();

					if (check.equals(target)) {
						success = true;
						float cost = delayCostTable.get(currG);
						currG.cost = cost;
						currGIsSolution = true;
						solutions.add(currG);
						if (solutions.size()>10000)
							break;
					}
				}
			}
			visited.put(currG, currG.cost);

			watchdog--;
			if (watchdog < 0) {
				System.err.println("Watchdog expired!");
				break;
			}

			if (!currGIsSolution && currG.getDelayType() == GroupDelayType.PINFEED)
				continue;


			findRouteCostFunction(tileDx, tileDy, model, sink);
		}

		if (success) {
			TimingGroup currG = solutions.poll();
			List<TimingGroup> reverseTGOrder = new LinkedList<>();
			System.out.println("Visited Wire Count:" + visited.size());
			System.out.println("Selected Delay is: " + delayCostTable.get(currG)+ 
								", artificial cost is: "+currG.cost);

			// We've found the sink, recover our trail of used PIPs
			TimingGroup tmpG = currG;
			System.out.println("Timing groups are:");
			while (tmpG != null) {
				reverseTGOrder.add(tmpG);
				tmpG = prevG.get(tmpG);
			}
			for (int i=reverseTGOrder.size()-1; i>= 0; i--) {
				tmpG = reverseTGOrder.get(i);
				System.out.println("\t" + tmpG + ", delay:" + tmpG.delay +", cost: "+
									delayCostTable.get(tmpG)+", artificial cost: "+tmpG.cost);
				for (Node n : tmpG.getNodes()) {
					System.out.println("\t\t node:" + n);
					for (Wire w : n.getAllWiresInNode()) {
						System.out.println("\t\t\t wire:" + w);
					}
				}
				result.addAll(tmpG.getPIPs());
			}

			return result;
		} else {
			return null;
		}
	}


	/**
	 * This helper function checks the timing group and adds a wire length distance to tileX2B and 
	 * tileY2B member variables. The side effect is setting these variables.
	 * @param tg
	 */
	private static void findRouteHelperSetTileX2BY2B(TimingGroup tg) {
		switch (tg.getDelayType() ) {
			case SINGLE:
				switch (tg.getDirection()) {
					case EAST:
						tileX2B = 1;
						tileY2B = 0;
						break;
					case WEST:
						tileX2B = -1;
						tileY2B = 0;
						break;
					case NORTH:
						tileX2B = 0;
						tileY2B = 1;
						break;
					case SOUTH:
						tileX2B = 0;
						tileY2B = -1;
						break;
					default:
						tileX2B = 0;
						tileY2B = 0;
						break;
				}
				break;

			case QUAD:
				switch (tg.getDirection()) {
					case EAST:
						tileX2B = 4;
						tileY2B = 0;
						break;
					case WEST:
						tileX2B = -4;
						tileY2B = 0;
						break;
					case NORTH:
						tileX2B = 0;
						tileY2B = 4;
						break;
					case SOUTH:
						tileX2B = 0;
						tileY2B = -4;
						break;
					default:
						tileX2B = 0;
						tileY2B = 0;
						break;
				}
				break;

			case LONG:
				switch (tg.getDirection()) {
					case EAST:
						tileX2B = 12;
						tileY2B = 0;
						break;
					case WEST:
						tileX2B = -12;
						tileY2B = 0;
						break;
					case NORTH:
						tileX2B = 0;
						tileY2B = 12;
						break;
					case SOUTH:
						tileX2B = 0;
						tileY2B = -12;
						break;
					default:
						tileX2B = 0;
						tileY2B = 0;
						break;
				}
				break;

			case DOUBLE:
				switch (tg.getDirection()) {
					case EAST:
						tileX2B = 2;
						tileY2B = 0;
						break;
					case WEST:
						tileX2B = -2;
						tileY2B = 0;
						break;
					case NORTH:
						tileX2B = 0;
						tileY2B = 2;
						break;
					case SOUTH:
						tileX2B = 0;
						tileY2B = -2;
						break;
					default:
						tileX2B = 0;
						tileY2B = 0;
						break;
				}
				break;

			default:
				tileX2B = 0;
				tileY2B = 0;
				break;
		}

	}

	/**
	 * Router cost function method.
	 * @param tileDx Difference between coordinates in x dimension.
	 * @param tileDy Difference between coordinates in y dimension.
	 * @param model Reference to the TimingModel created by the TimingManager.
	 * @param sink SitePinInst for the sink of the physical Net object.
	 */
	public static void findRouteCostFunction(int tileDx, int tileDy, TimingModel model, SitePinInst sink) {
		TimingGroup[] unfilteredNextTGs = currG.getNextTimingGroups();
		{ // cost function as inline for now
			TimingDirection direction;
			GroupDistance distanceBand;

			// select the direction with most distance to cover
			int distanceInChosenDirection;

			if (Math.abs(tileDx)==0 && Math.abs(tileDy)==0) {
				direction = NULL;
				distanceInChosenDirection = 0;
			} else if ((Math.abs(tileDx) > Math.abs(tileDy))) {
				if ((Math.abs(tileDx) >= 0 && tileDx >= 0))
					direction = EAST;
				else
					direction = WEST;
				distanceInChosenDirection =  Math.abs(tileDx);
			} else {
				if ((Math.abs(tileDy) >= 0 && tileDy >= 0))
					direction = NORTH;
				else
					direction = SOUTH;
				distanceInChosenDirection =  Math.abs(tileDy);
			}

			// select the filter for the next hop based on the magnitude
			if ((tileDx == 0 && tileDy == 0)) {
				distanceBand = SAME;
			} else if ( // falls exactly within the chosen range:
					(distanceInChosenDirection >= model.NEAR_MIN && distanceInChosenDirection <= model.NEAR_MAX) ||
							// or closer to min than middle
							Math.abs(distanceInChosenDirection - model.NEAR_MAX) < Math.abs(distanceInChosenDirection - model.MID_MIN)) {
				distanceBand = NEAR;
			} else if ( // falls exactly within the chosen range:
					(distanceInChosenDirection >= model.MID_MIN && distanceInChosenDirection <= model.MID_MAX) ||
							// or closer to min than middle
							Math.abs(distanceInChosenDirection - model.MID_MAX) <  Math.abs(distanceInChosenDirection - model.FAR_MIN)) {
				distanceBand = MID;
			}  else {
				if (currG.getDelayType() == GroupDelayType.QUAD || currG.getDelayType() == GroupDelayType.LONG)
					distanceBand = FAR;
				else
					distanceBand = MID;
			}

			// select the smallest distance
			TimingGroup[] filteredNextTGs = model.filter(distanceBand, direction, unfilteredNextTGs);
			for (TimingGroup nextG : filteredNextTGs) {
				if (visited.containsKey(nextG))
					continue;

				int mDist;
				Node nextLastNode = nextG.getLastNode();
				Wire [] nextLastNodeWires = nextLastNode.getAllWiresInNode();
				Wire nextLastNodeEndWire = nextLastNodeWires[nextLastNodeWires.length-1];
				PIP nextLastPIP = nextG.hasPIPs()? nextG.getLastPIP() : null;


				if (!nextG.hasPIPs()) {
					mDist = sink.getTile().getTileManhattanDistance(nextLastNode.getTile());
				} else {
					mDist = sink.getTile().getTileManhattanDistance(nextLastPIP.getTile());
				}

				TileTypeEnum checkType = nextLastNode.getTile().getTileTypeEnum();
				if (
						checkType == TileTypeEnum.INT ||
								checkType == TileTypeEnum.CLEL_L ||
								checkType == TileTypeEnum.CLEL_R ||
								checkType == TileTypeEnum.CLEM ||
								checkType == TileTypeEnum.CLEM_R
				) {
					findRouteHelperSetTileX2BY2B(nextG);

					if (nextG.dist ==0 && currG.dist == 0)
						nextG.sameSpotCounter = currG.sameSpotCounter+1;

					boolean removeLastBounce = currG.getDelayType() == GroupDelayType.PIN_BOUNCE && 
												nextG.getNodeType(0) == IntentCode.NODE_CLE_OUTPUT;

					float nextCost = currDelayCost + nextG.delay + 
							(removeLastBounce? -1*model.BOUNCE_DELAY:0)+
							(mDist > 1 ? DISTANCE_WEIGHT * (mDist-Math.abs(tileX2B)-Math.abs(tileY2B)) : 0) +
							((nextG.getDelayType() == GroupDelayType.GLOBAL)? 200 : 0);
					nextG.cost = nextCost;

					delayCostTable.put(nextG, currDelayCost + nextG.delay + 
										(removeLastBounce? -1*model.BOUNCE_DELAY:0) );
					prevG.put(nextG, currG);

					if (nextG.sameSpotCounter < 4)
						queue.add(nextG);
				}
			}
		}
	}



	static String designName;
	static String outputDCPFileName;

	private static OptionParser createOptionParser(){

		// Defaults, please modify these to experiment
		String partName = "xcvu3p-ffvc1517-2-e";
		String clkName = "clk";

		/**** SOME DEFAULTS ****/
		int width = 1;
		int depth = 2;
		double frequencyMHz = 775;

		int distanceX = 4;
		int distanceY = 16;
		String sliceSite = "SLICE_X10Y4";

		//int distanceX = 8;
		//int distanceY = 0;
		//String sliceSite = "SLICE_X84Y68";


		boolean verbose1 = true;

		double clkPeriodConstraint = Math.pow(frequencyMHz, -1)*1000;

		designName = "pipeline_"+width+"w_"+depth+"d_dx"+(distanceX >= 0 ? ""+distanceX : "neg"+distanceX)+
				"_dy"+(distanceY >= 0 ? ""+distanceY : "neg"+distanceY)+"_org"+sliceSite;
		outputDCPFileName = System.getProperty("user.dir") + File.separator + designName +".dcp";
		outputDCPFileName = System.getProperty("user.dir") + File.separator + "pipeline.dcp";


		// example code for command
		OptionParser p = new OptionParser() {{
			accepts(PART_OPT).withOptionalArg().defaultsTo(partName).describedAs("Ultrascale/UltraScale+ Part Name");
			accepts(DESIGN_NAME_OPT).withOptionalArg().defaultsTo(designName).describedAs("Design Name");
			accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo(outputDCPFileName).describedAs("Output DCP File Name");
			accepts(CLK_NAME_OPT).withOptionalArg().defaultsTo(clkName).describedAs("Clk net name");
			accepts(CLK_CONSTRAINT_OPT).withOptionalArg().ofType(Double.class).defaultsTo(clkPeriodConstraint).describedAs("Clk period constraint (ns)");
			accepts(WIDTH_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(width).describedAs("width");
			accepts(DEPTH_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(depth).describedAs("depth");
			accepts(DISTANCE_X_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(distanceX).describedAs("distance X");
			accepts(DISTANCE_Y_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(distanceY).describedAs("distance Y");
			accepts(SLICE_SITES_OPT).withOptionalArg().defaultsTo(sliceSite).describedAs("Lower left slice to be used for pipeline");
			accepts(VERBOSE_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(verbose1).describedAs("Print verbose output");
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
		boolean verbose1 = (boolean) opts.valueOf(VERBOSE_OPT);
		boolean verbose2 = true;  // extra verbose messages, modify this to remove some messages
		if(opts.has(HELP_OPT)){
			printHelp(p);
			return;
		}
		String className = PipelineGeneratorWithRouting.class.getSimpleName();
		CodePerfTracker t = verbose1 ? new CodePerfTracker(className,true).start("Init") : null;

		String partName = (String) opts.valueOf(PART_OPT);
		String designName = (String) opts.valueOf(DESIGN_NAME_OPT);
		String outputDCPFileName = (String) opts.valueOf(OUT_DCP_OPT);
		String clkName = (String) opts.valueOf(CLK_NAME_OPT);
		double clkPeriodConstraint = (double) opts.valueOf(CLK_CONSTRAINT_OPT);

		int width = (int) opts.valueOf(WIDTH_OPT);
		int depth = (int) opts.valueOf(DEPTH_OPT);
		int distanceX = (int) opts.valueOf(DISTANCE_X_OPT);
		int distanceY = (int) opts.valueOf(DISTANCE_Y_OPT);

		/******** SET THE DIRECTION HERE ********/
		direction dir = direction.horizontal;

		String sliceName = (String) opts.valueOf(SLICE_SITES_OPT);

		// Perform some error checking on inputs
		Part part = PartNameTools.getPart(partName);
		if(part == null || part.isSeries7()){
			MessageGenerator.briefErrorAndExit("ERROR: Invalid/unsupported part " + partName + 
												".  This example was coded "+
							  					"for UltraScale or UltraScale+ devices.");
		}
		
		Design d = new Design(designName,partName);
		d.setAutoIOBuffers(false);
		Device dev = d.getDevice();
		
		t.stop().start("Create Pipeline");
		Site slice = dev.getSite(sliceName);

		System.out.println ("DistanceX:"+distanceX);
		System.out.println ("DistanceY:"+distanceY+"" +
				"\n");


		createPipeline(d, slice, width, depth, distanceX, distanceY, dir, true);
				
		// Add a clock constraint
		String tcl = "create_clock -name "+clkName+" -period "+clkPeriodConstraint+
					 " [get_ports "+clkName+"]";
		d.addXDCConstraint(ConstraintGroup.LATE,tcl);
		d.setAutoIOBuffers(false);

		float clkPeriodPs = (float)clkPeriodConstraint*1000;

		if (verbose1){
			///////////////////////
			// Reporting timing on the routed design
			TimingManager tm = new TimingManager(d);
			TimingModel dm1 = tm.getTimingModel();
			TimingGraph tg = tm.getTimingGraph();

			GraphPath<TimingVertex, TimingEdge> maxPath = tg.getMaxDelayPath();
			double maxDelay = maxPath.getWeight();
			tg.setTimingRequirement(clkPeriodPs);

			System.out.println("\nRequested frequency:" + Math.round(Math.pow(clkPeriodConstraint, -1) * 1000) +
					" MHz for a period of " + Math.round(clkPeriodPs) + " ps");

			System.out.println("\nMax net delay:" + Math.round(maxDelay) + " ps");

			System.out.println("\nWorst slack:" + Math.round(tg.getWorstSlack()) + " ps \n");

			if (verbose2) {
				System.out.println("The following is a print out of the TimingGroups for the critical path:");

				for (TimingEdge edge : maxPath.getEdgeList()) {
					dm1.debug = true;
					dm1.verbose = true;
					tg.debug = true;

					if (edge.getNet() != null) { // skip below if the net is null, e.g. edges from/to the timing graph's
						// "SuperSource" and "SuperSink"
						dm1.calcDelay(edge.getNet().getSource(), edge.getNet().getSinkPins().get(0), edge.getNet());

						System.out.println("Critical path TimingEdge/physical net name: " + edge.getNet() +
								"\n\t net_delay:" + Math.round(edge.getNetDelay()) + " ps," +
								"\n\t logic_delay:" + Math.round(edge.getLogicDelay()) + " ps," +
								"\n\t --------------------" +
								"\n\t total_delay:" + Math.round(edge.getDelay()) + " ps\n\n\n");
					}
				}
			}
			tg.generateGraphvizDotVisualization("pipeline.dot");
		}

		///////////////////////
		t.stop();
		d.writeCheckpoint(outputDCPFileName, t);
		if(verbose1) System.out.println("Wrote final DCP: " + outputDCPFileName);
	}
}
