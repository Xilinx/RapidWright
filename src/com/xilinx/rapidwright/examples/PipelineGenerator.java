/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
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

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.edif.*;
//import com.xilinx.rapidwright.router.FFExpRouter1;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
	protected static final String DISTANCE_X_OPT = "t";
	protected static final String DISTANCE_Y_OPT = "u";

	protected static final String VERBOSE_OPT = "v";
	protected static final String HELP_OPT = "h";


	public static final int BITS_PER_CLE = 8;

	private static final String SLICE_SITES_OPT = "s";

	public static String INPUT_NAME = "IN";
	public static String OUTPUT_NAME = "OUT";

	enum direction {vertical, horizontal;}


	public static PBlock createPipeline(Design d, Site startingPoint, int width, int depth, int distanceX, int distanceY, direction dir, boolean route){

		if (dir == direction.vertical && (distanceY < Math.ceil(width/8))) {
			System.err.println("Error: the width (="+width+") and distance (="+distanceY+") parameters conflict in a way "+
					"that would result in an overlap in the vertical direction.  "+
					"Please choose different parameters or modify this example.");
			return new PBlock(); //System.exit(1);
		}

		EDIFCell top = d.getNetlist().getTopCell();
		Set<Site> used = new HashSet<>();
		String bus = "["+(width-1)+":0]";

		// Declare the I/O

		EDIFPort inputPort = top.createPort(INPUT_NAME + (width > 1 ? bus : ""), EDIFDirection.INPUT, width);
		EDIFPort outputPort = top.createPort(OUTPUT_NAME + (width > 1 ? bus : ""), EDIFDirection.OUTPUT, width);
		EDIFPort clkPort;
		EDIFPort cePort;
		EDIFPort rstPort;


		String filename = "pipeline_"+width+"w_"+depth+"d_dx"+(distanceX >= 0 ? ""+distanceX : "neg"+((int)Math.abs(distanceX)))+
				"_dy"+(distanceY >= 0 ? ""+distanceY : "neg"+((int)Math.abs(distanceY)))+"_org"+startingPoint+".txt";

		File logFile = new File(filename);
		//FileOutputStream logFOS = null;
		PrintStream ps = null;
		try {
			//logFOS = new FileOutputStream(logFile);
			ps = new PrintStream(logFile);
		} catch (Exception e) {
			e.printStackTrace();
			return new PBlock();//System.exit(1);
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
					ic_name = INPUT_NAME + (width > 1 ?  index : "");
				} else if (j == depth) {
					ic_name = OUTPUT_NAME + (width > 1 ?  index : "");
				} else {
					ic_name = "bus" + j + "_" + (width > 1 ?  index : "");
				}
				ic[j][i] = top.createNet(ic_name);
			}
		}

		Site endSite = null;
		Site prevSite = startingPoint;
		Site newSite = startingPoint;

		// Create one row of registers
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
						tmpDistanceX++;

					testTile = d.getDevice().getTile(newSiteRow-tmpDistanceY, newSiteCol+tmpDistanceX);

					invalid = testTile.getSites().length == 0;
					invalid = invalid || !(testTile.getSites()[0].isCompatibleSiteType(SiteTypeEnum.SLICEL) ||
							testTile.getSites()[0].isCompatibleSiteType(SiteTypeEnum.SLICEM));

					newDistanceX = tmpDistanceX;
					newDistanceY = tmpDistanceY;
				}
				newSite = testTile.getSites()[0];
				if (newDistanceX > distanceX) {
					//System.out.println("Info: please note the tile distance for row "+j +" was adjusted from "+distanceX+" to "+newDistanceX+
					//		" tiles from previous row.  This example did this to find the next occurring slice.");
					ps.println("Info: please note the tile distance for row "+j +" was adjusted from "+distanceX+" to "+newDistanceX+
							" tiles from previous row.  This example did this to find the next occurring slice.");
				}
				//System.out.println("Connecting prev<c"+prevSite.getTile().getColumn()+"r"+prevSite.getTile().getRow()+">:"+prevSite.getSiteTypeEnum() +
				//		" with new<c"+newSite.getTile().getColumn()+"r"+newSite.getTile().getRow()+">:"+newSite.getSiteTypeEnum());
				ps.println("Connecting prev<c"+prevSite.getTile().getColumn()+"r"+prevSite.getTile().getRow()+">:"+prevSite.getSiteTypeEnum() +
						" with new<c"+newSite.getTile().getColumn()+"r"+newSite.getTile().getRow()+">:"+newSite.getSiteTypeEnum());
				endSite = newSite;
			}

			prevSite = newSite;

			for (int i = width - 1; i >= 0; i--) {

				EDIFNet inputNet = ic[j][i];
				EDIFNet outputNet = ic[j + 1][i];


				Site currSlice = prevSite.getNeighborSite(0, i / BITS_PER_CLE);

				int v = 0;
				while (!currSlice.isCompatibleSiteType(SiteTypeEnum.SLICEL) && !currSlice.isCompatibleSiteType(SiteTypeEnum.SLICEM)) {
					v++;
					int dy = (i+v) / BITS_PER_CLE;
					currSlice = prevSite.getNeighborSite(0, dy);
					if (currSlice == null) {
							//System.err.println("Unable to find a suitable site for placing flop ["+j+"]["+i+"] " +
							//		"based on the current set of parameters with the starting location: "+startingPoint.toString()+".");
							ps.println("Unable to find a suitable site for placing flop [" + j + "][" + i + "] " +
									"based on the current set of parameters with the starting location: " + startingPoint.toString() + ".");
							return new PBlock();

					}
				}

				used.add(currSlice);
				String letter = Character.toString((char) ('A' + i % 8));
				BEL ff = currSlice.getBEL(letter + "FF");
				char[] letter_char = new char[1];
				letter.getChars(0,1,letter_char,0);
				boolean isFF2 = ff.getName().endsWith("2");
				boolean isLowerSlice = 'A' <= letter_char[0] && letter_char[0] <= 'D';
				String clkPinName = isLowerSlice ? "CLK1" : "CLK2";
				String rstPinName = isLowerSlice ? "SRST1" : "SRST2";
				String cePinName = "CKEN" + (isLowerSlice ? (isFF2 ? "2" : "1") : (isFF2 ? "4" : "3"));

				if(j == 1) currSlice = currSlice.getNeighborSite(-1, 0);
				Cell ffCell = d.createAndPlaceCell(top, "d_" + j + "_" + i, Unisim.FDRE, currSlice, ff);
				ffCell.addProperty("INIT", "1'b0", EDIFValueType.STRING);
				SiteInst ff_si = ffCell.getSiteInst();

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

		d.routeSites();

		// Find rectangular area consumed
		PBlock footprint = new PBlock(d.getDevice(),used);

		/*if(route){
			FFExpRouter1 r = new FFExpRouter1(d);
			r.setRoutingPblock(footprint);
			r.setSupressWarningsErrors(true);
			r.routeDesign();
		}*/


		ps.println();

		HashMap<String, Net> valid_netHash = new LinkedHashMap<>();
		HashMap<String, Net> issue_netHash = new LinkedHashMap<>();

		Collection<Net> nets = d.getNets();
		HashMap<String, Net> netsByName = new LinkedHashMap<>();
		for (Net n : d.getNets()) {
			netsByName.put(n.getName(), n);
		}

		String[] netsArray = netsByName.keySet().toArray(new String[netsByName.size()]);
		Arrays.sort(netsArray);

		int numP = 0;
		for (String netName : netsArray) {
			if (netName.startsWith("bus1")) {
				Net net = netsByName.get(netName);

				if (net.getPins().size() == 0) {
					//net_issue_cntr++;
					//System.out.println("net issue "+net_issue_cntr+":"+net+" has NO_SITE_PINS");
					issue_netHash.put(net.getName(), net);
				} else {
					//net_with_sp_cntr++;
					//System.out.println("net okay  "+net_with_sp_cntr+":"+net+" ");
					valid_netHash.put(net.getName(), net);
				}

				if (net.getPins().size() > 1) {
					//System.out.println("n:" + net + " contains " + net.getPIPs().size() + " pips, dist:" + net.getPins().get(0).getTile().getManhattanDistance(net.getPins().get(1).getTile()));
					RouteNode start = net.getPins().get(0).getNodeFromPin();
					RouteNode end = net.getPins().get(1).getNodeFromPin();
					int nPips = net.getPIPs().size();
					//RouteNode end = (nPips > 0) ? start.getEndNode(net.getPIPs().get(net.getPIPs().size()-1)) : null;//net.getPins().get(1).getNodeFromPin();
					//List<Wire> wirelist = start.;
					for (PIP pip : end.getPIPsBackToSource()) {
						//System.out.println("\tpip_back_to_src: " + pip);
					}
				}
				for (SitePinInst pin : net.getPins()) {
					if (pin.getSiteInst() == null) {
						//no_sp_cntr++;
						//System.out.println("\tpin " +no_sp_cntr + ": " + pin + ", has no site pin:" + pin.getSiteInst());
					} else {
						//System.out.println("\tpin:" + pin + ", siteInst:" + pin.getSiteInst());
					}

				}
				numP = net.getPIPs().size();
				//System.out.println("n:"+n+" contains "+net.getPIPs().size()+" pips");
				ps.println("Net:" + net.getName() + ", n_pips:" + net.getPIPs().size());
				PIP prevPip = null;
				int PIPdistance = 0;

				int wireCntr = 0;
				List<PIP> reordered = reorderPIPs(net.getPIPs());

				HashMap<String, Wire> wires = new LinkedHashMap<>();
				HashMap<String, Node> nodes = new LinkedHashMap<>();
				ArrayList<IntentCode> intentCodes = new ArrayList<>();

				int pCntr = 0;
				for (PIP pip : net.getPIPs()) {
					pCntr++;

					Node startNode = pip.getStartWire().getNode();
					Node endNode = pip.getEndWire().getNode();


					ps.println("\tpip: "+pCntr+", type:"+pip.getPIPType());
					ps.println("\t\tstart_node:" + startNode + ", sz:" + startNode.getAllWiresInNode().length);
					ps.println("\t\tend_node:" + endNode + ", sz:" + endNode.getAllWiresInNode().length);
					//ps.println();
					//ps.println("\tpip: "+pCntr+", type:"+pip.getPIPType());
					//ps.println("\t\tstart_wire:" + pip.getStartWire() + ", type:" + pip.getStartWire().getIntentCode());
					//ps.println("\t\tend_wire:" + pip.getEndWire() + ", type:" + pip.getEndWire().getIntentCode());

					//	if (wires.get(pip.getStartWire().getWireName()) == null)
					//		wires.put(pip.getStartWire().getWireName(), pip.getStartWire());
					//if (wires.get(pip.getEndWire().getWireName()) == null)
					//	wires.put(pip.getEndWire().getWireName(), pip.getEndWire());



					//if (pCntr == 1) {
						wires.put("p" + pCntr + ":" + pip.getStartWire().getWireName(), pip.getStartWire());
						nodes_put(nodes,"p" + pCntr + ":" + pip.getStartWire().getWireName(), pip.getStartWire().getNode());
					//}
					wires.put("p"+pCntr+":"+pip.getEndWire().getWireName(), pip.getEndWire());
					nodes_put(nodes,"p" + pCntr + ":" + pip.getEndWire().getWireName(), pip.getEndWire().getNode());


					//System.out.println("\t\tstart_wire:" + pip.getStartWire() + ", type:" + pip.getStartWire().getIntentCode());
					//System.out.println("\t\tend_wire:" + pip.getEndWire() + ", type:" + pip.getEndWire().getIntentCode());
					if (prevPip != null) {
						PIPdistance = prevPip.getTile().getManhattanDistance(pip.getTile());
					}
					//System.out.println("\tpip " + net.getPIPs().indexOf(pip) + ":" + pip + ",   dist:" + PIPdistance + ",   fo:" + net.getFanOut());

					wireCntr++;
					//System.out.println("\t\twire "+wireCntr+":"+pip.getStartWire().getIntentCode());

					prevPip = pip;
				}

				List<Wire> node_start_wires = new LinkedList<>();
				for (Node node : nodes.values()) {
					node_start_wires.add(node.getAllWiresInNode()[0]);
				}

				HashMap<PIPType, Integer> pipTypes = new LinkedHashMap<>();
				for(PIP p : net.getPIPs()) {
					int tmp = 0;
					if (pipTypes.get(p.getPIPType()) != null) {
						tmp = pipTypes.get(p.getPIPType());
						pipTypes.put(p.getPIPType(), ++tmp);
					}
					else {
						pipTypes.put(p.getPIPType(), 1);
					}
				}

				String[] ptkeys = new String[pipTypes.size()];
				int ptkCntr = 0;
				for (PIPType ptk : pipTypes.keySet()) {
					ptkeys[ptkCntr++] = ""+ptk;
				}
				Arrays.sort(ptkeys);

				ps.println("PIP types:");
				for (PIPType pt : pipTypes.keySet()) {
					ps.println("\tpt:"+pt+", sz:"+pipTypes.get(pt));
				}

				ps.println();
				ps.println("Node summary:" +nodes.size());
				for (Wire w : node_start_wires) {
					//for (Wire w : wires.values()) {
					IntentCode code = w.getIntentCode();
					if (!intentCodes.contains(code)) {
						intentCodes.add(code);
					}
				}
				IntentCode[] codes = intentCodes.toArray(new IntentCode[intentCodes.size()]);
				HashMap<IntentCode, Integer> intentHash = new LinkedHashMap<>();
				Arrays.sort(codes);
				for (IntentCode c : codes) {
					List<String> intentList = new LinkedList<>();
					for (Wire w: node_start_wires) {
						//for (String s : wires.keySet()) {
						//Wire w = wires.get(s);
						if (w.getIntentCode() == c) {
							intentList.add(w.getWireName());
						}
					}
					ps.println("\t" + c + ": " + intentList.size());
					intentHash.put(c, intentList.size());
					int wCntr = 0;
					for (String ilWire : intentList) {
						wCntr++;
						ps.println("\t\tw "+wCntr+":" + ilWire);
					}
				}
				ps.println();
				ps.println();
				//System.out.println("\n");

				String site = startingPoint.getName();

				int startRow = startingPoint.getTile().getRow();
				int startCol = startingPoint.getTile().getColumn();
				String startType = ""+startingPoint.getSiteTypeEnum();

				int endRow = endSite.getTile().getRow();
				int endCol = endSite.getTile().getColumn();
				String endType = ""+endSite.getSiteTypeEnum();

				int dx = endCol - startCol;
				int dy = startRow - endRow;

				int pips = numP;

				/* DIRECTIONAL_NOT_BUFFERED21 =1,
						2. ->>
						DIRECTIONAL_BUFFERED21 = 2,
				*/

				String piptypes = "";
				if (pipTypes.get(PIPType.DIRECTIONAL_BUFFERED21) != null)
					piptypes += pipTypes.get(PIPType.DIRECTIONAL_BUFFERED21);
				else
					piptypes += "0";
				piptypes += "\t";
				if (pipTypes.get(PIPType.DIRECTIONAL_NOT_BUFFERED21) != null)
					piptypes += pipTypes.get(PIPType.DIRECTIONAL_NOT_BUFFERED21);
				else
					piptypes += "0";


				String nodetypes = "";
				if (intentHash.get(IntentCode.INTENT_DEFAULT) != null)
					nodetypes += intentHash.get(IntentCode.INTENT_DEFAULT);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_PINBOUNCE) != null)
					nodetypes += intentHash.get(IntentCode.NODE_PINBOUNCE);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_HLONG) != null)
					nodetypes += intentHash.get(IntentCode.NODE_HLONG);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_LOCAL) != null)
					nodetypes += intentHash.get(IntentCode.NODE_LOCAL);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_SINGLE) != null)
					nodetypes += intentHash.get(IntentCode.NODE_SINGLE);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_DOUBLE) != null)
					nodetypes += intentHash.get(IntentCode.NODE_DOUBLE);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_HQUAD) != null)
					nodetypes += intentHash.get(IntentCode.NODE_HQUAD);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_VQUAD) != null)
					nodetypes += intentHash.get(IntentCode.NODE_VQUAD);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_VLONG) != null)
					nodetypes += intentHash.get(IntentCode.NODE_VLONG);
				else
					nodetypes += "0";
				nodetypes += "\t";
				if (intentHash.get(IntentCode.NODE_CLE_OUTPUT) != null)
					nodetypes += intentHash.get(IntentCode.NODE_CLE_OUTPUT);
				else
					nodetypes += "0";

				String startSliceType = ""+(startingPoint.getSiteTypeEnum()==SiteTypeEnum.SLICEL ? 1: 0);

				System.out.println(d.getName()+"\t"+site+"\t"+startType+"\t"+startCol+"\t"+startRow+"\t"+endType+"\t"+endCol+"\t"+endRow+"\t"+dx+"\t"+dy+"\t"+pips+"\t"+piptypes+"\t"+nodetypes+"\t"+startSliceType);


				/*
				        INTENT_DEFAULT,
						NODE_PINBOUNCE,
						NODE_LOCAL,
						NODE_HLONG,
						NODE_SINGLE,
						NODE_DOUBLE,
						NODE_HQUAD,
						NODE_VLONG,
						NODE_VQUAD,
						NODE_CLE_OUTPUT,
*/
			}
		}

		int no_sp_cntr = 0;
		int net_issue_cntr = 0;
		int net_with_sp_cntr = 0;


		String[] sarray = (String[])valid_netHash.keySet().toArray(new String[valid_netHash.size()]);
		Arrays.sort(sarray);
		for (String net : sarray) {
			net_with_sp_cntr++;
			//System.out.println("net okay  "+net_with_sp_cntr+":"+net+" ");
		}
		sarray = (String[])issue_netHash.keySet().toArray(new String[issue_netHash.size()]);
		Arrays.sort(sarray);
		//System.out.println("\n");
		for (String net : sarray) {
			boolean foundValid = valid_netHash.get(net) != null;
			net_issue_cntr++;
			//System.out.println("net issue "+net_issue_cntr+":"+net+" "+foundValid);
		}

ps.close();

		return footprint;
	}

	static boolean nodes_put(HashMap<String, Node> nodes, String s, Node node) {
		boolean did_insertion = false;
		boolean found = false;
		boolean same = true;

		//for (Node n : nodes.values()) {
		for (String s1 : nodes.keySet()) {
			Node n = nodes.get(s1);
			Wire[] wiresForInputNode = node.getAllWiresInNode();
			Wire[] wiresForN = n.getAllWiresInNode();
			String[] wiresForInputNodeNames = new String[wiresForInputNode.length];
			String[] wiresForNNames = new String[wiresForN.length];
			for (int i = 0; i<wiresForInputNode.length; i++) {
				wiresForInputNodeNames[i] = wiresForInputNode[i].getWireName();
			}
			for (int i = 0; i<wiresForN.length; i++) {
				wiresForNNames[i] = wiresForN[i].getWireName();
			}
			Arrays.sort(wiresForInputNodeNames);
			Arrays.sort(wiresForNNames);

			boolean sameLength = wiresForInputNode.length == wiresForN.length;
			if (n.getTile() == node.getTile() && sameLength) {
				same = true;
				for (int i = 0; i < wiresForInputNode.length; i++) {
					if (wiresForInputNodeNames[i] != wiresForNNames[i]) {
						same = false;
					}
				}
			} else {
				same = false;
			}

			found |= same;
		}

		if (!found) {
			did_insertion = nodes.put(s, node) != null;
		}
		return did_insertion;
	}


	static String designName;
	static String outputDCPFileName;

	private static OptionParser createOptionParser(){

		// Defaults, please modify these to experiment
		String partName = "xcvu3p-ffvc1517-2-e";
		String clkName = "clk";
		double clkPeriodConstraint = 1.000; // 775 MHz

		/**** SOME DEFAULTS ****/
		int width = 1;
		int depth = 2;
		int distanceX = 0;
		int distanceY = 9;
		String sliceSite = "SLICE_X2Y4";
		boolean verbose = true;

		designName = "pipeline_"+width+"w_"+depth+"d_dx"+(distanceX >= 0 ? ""+distanceX : "neg"+distanceX)+
				"_dy"+(distanceY >= 0 ? ""+distanceY : "neg"+distanceY)+"_org"+sliceSite;

		outputDCPFileName = System.getProperty("user.dir") + File.separator + designName +".dcp";

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
			accepts(VERBOSE_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(verbose).describedAs("Print verbose output");
			acceptsAll( Arrays.asList(HELP_OPT, "?"), "Print Help" ).forHelp();
		}};
		
		return p;
	}

	static List<PIP> reorderPIPs(List<PIP> pips) {
		List<PIP> result = new LinkedList<>();
		HashMap<String, PIP> startsWith = new LinkedHashMap<>();
		HashMap<String, PIP> endsWith = new LinkedHashMap<>();

		for (PIP p : pips) {
			startsWith.put(p.getStartWireName(), p);
			endsWith.put(p.getStartWireName(), p);
		}
		if (pips.size() == 0)
			return result;

		PIP current = pips.get(0); // start with the first item in the list
		PIP prev = null;
		while (current != null && current != prev) {
			prev = current;
			current = endsWith.get(current.getStartWireName());
		}
		current = prev; // current will be null at above loop exit
		while (current != null) {
			result.add(startsWith.get(current.getStartWireName()));
			current = startsWith.get(current.getEndWireName());
		}

		for (PIP p : pips) {
			if (!result.contains(p)) {
				//System.err.println("reordering pips doesn't contain:"+p+", startW:"+p.getStartWireName()+", endW:"+p.getEndWireName()+", inx:"+pips.indexOf(p));
			}
		}

		return result;
	}



	private static void printHelp(OptionParser p){
		//MessageGenerator.printHeader("Pipeline Generator");
		//System.out.println("This RapidWright program creates an example pipelined bus as a placed and routed DCP. \n"
		//	+ "See \n"
		//	+ "RapidWright documentation for more information.\n");
		try {
			p.accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo("pipeline_w_d_dist_.dcp").describedAs("Output DCP File Name");
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
		int distanceX = (int) opts.valueOf(DISTANCE_X_OPT);
		int distanceY = (int) opts.valueOf(DISTANCE_Y_OPT);

		/******** SET THE DIRECTION HERE ********/
		direction dir = direction.horizontal;

		String sliceName = (String) opts.valueOf(SLICE_SITES_OPT);

		// Perform some error checking on inputs
		Part part = PartNameTools.getPart(partName);
		if(part == null || part.isSeries7()){
			MessageGenerator.briefErrorAndExit("ERROR: Invalid/unsupport part " + partName + ".");
		}
		//int[] b = {4};
		//int[] a = {84};
		//int[] b = {4};
		//int[] a = {10, 42,84};
		//int[] b = {4, 68, 137};
		//int[] a = {0,1,2,3,4,5,6,10};
		int[] a = {120};
		int[] b = {105};
		//int[] a = {120, 16, 84};
		//int[] b = {105, 68, 97};
		//int[] a = {0,1};
		int[] dx_ = {-1};
		int[] dy_ = {0};
		//int[] dx_ = {1, 2, 8, 32, 64, 96};
		//int[] dy_ = {0, -1, -4, 32, -32, -64};
		//int[] dx_ = {1, 2, 4, 8, 12, 16};
		//int[] dy_ = {0, 2, 4, 16};
		for (int dy: dy_) {
			for (int dx : dx_) {
				distanceX = dx;
				distanceY = dy;
				//for (distanceY=1; distanceY<25; distanceY++) {
				for (int i : a) {
					for (int j : b) {
						sliceName = "SLICE_X" + i + "Y" + j;

						designName = "pipeline_" + width + "w_" + depth + "d_dx" + (distanceX >= 0 ? "" + distanceX : "neg" + ((int)Math.abs(distanceX))) +
								"_dy" + (distanceY >= 0 ? "" + distanceY : "neg" + ((int)Math.abs(distanceY))) + "_org" + sliceName;

						outputDCPFileName = System.getProperty("user.dir") + File.separator + designName + ".dcp";

						Design d = new Design(designName, partName);
						d.setAutoIOBuffers(false);
						Device dev = d.getDevice();

						t.stop().start("Create Pipeline");

						Site slice = dev.getSite(sliceName);

						createPipeline(d, slice, width, depth, distanceX, distanceY, dir, true);

						// Add a clock constraint
						String tcl = "create_clock -name " + clkName + " -period " + clkPeriodConstraint + " [get_ports " + clkName + "]";
						d.addXDCConstraint(ConstraintGroup.LATE, tcl);
						d.setAutoIOBuffers(false);

						t.stop();
						d.writeCheckpoint(outputDCPFileName, t);
						if (verbose) System.out.println("Wrote final DCP: " + outputDCPFileName);
					}
				}
			}
		}
	}
}
