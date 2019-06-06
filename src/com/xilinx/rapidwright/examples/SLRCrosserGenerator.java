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

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * Highly parameterizable SLR bridge crossing circuit generator for
 * UltraScale+ devices.  Will write out a placed and routed DCP with
 * SLR crossings already realized.
 * 
 * Created on: Jan 31, 2018
 */
public class SLRCrosserGenerator {

	public static final int LAGUNA_FLOPS_PER_SITE = 6;
	public static final int LAGUNA_SITES_PER_TILE = 4;
	public static final int LAGUNA_TILES_PER_FSR = 60;

	/**
	 * Routes the current incomplete net to its corresponding RX site.  It
	 * will add the necessary PIPs to the net to route it, but will not add
	 * the site pin. 
	 * @param n The net with a Laguna TX pin source.
	 * @return The RX site pin.
	 */
	public static SitePin routeToLagunaRx(Net n){
		Queue<RouteNode> q = new LinkedList<RouteNode>();
		q.add(new RouteNode(n.getSource()));
		String targetPinName = n.getSource().getName().replace("TXQ", "RXD");
		int watchDog = 300;
		while(!q.isEmpty()){
			RouteNode curr = q.poll();
			SitePin check = new Node(curr.getTile(),curr.getWire()).getSitePin();
			if(check != null && check.getPinName().equals(targetPinName) && !check.getSite().equals(n.getSource().getSite())){
				n.setPIPs(curr.getPIPsBackToSource());
				return check;
			}
			for(Wire w : curr.getWireConnections()){
				q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
			}
			if(watchDog-- == 0) return null;
		}
		return null;
	}
	
	/**
	 * Routes the control signals (CLK,CE,RST) for a laguna flop within its site.
	 * @param c The laguna flop cell to route signals to
	 * @param clk The physical clock net to route
	 * @param rst The physical reset net to route
	 * @param ce The physical ce net to route
	 */
	public static void routeControlSignalsInLagunaSite(Cell c, Net clk, Net rst, Net ce){
		String rxOrTx = (c.getBELName().startsWith("RX") ? "RX" : "TX");
		SiteInst si = c.getSiteInst();		
		for(Net n : new Net[]{clk,rst}){
			String name = n.equals(clk) ? "CLK" : "SR";
			BELPin pin = c.getBEL().getPin(name);
			Net existingNet = si.getNetFromSiteWire(pin.getSiteWireName());
			if(existingNet == null){
				if(n.getType() == NetType.GND){
					Net vcc = c.getSiteInst().getDesign().getVccNet();
					vcc.addPin(new SitePinInst(false,rxOrTx +"_" + name,si));
				}else{
					n.addPin(new SitePinInst(false,rxOrTx +"_" + name,si));
				}
				si.addSitePIP(rxOrTx + "_OPTINV_" + name, "I");
			}else if(!existingNet.equals(n)){
				throw new RuntimeException("ERROR: Incompatible control nets in "
					+ "Laguna site, currently: " + existingNet + ", failed to add " + n);
			}
		}
		
		BELPin pin = c.getBEL().getPin("CE");
		Net existingNet = si.getNetFromSiteWire(pin.getSiteWireName());
		if(existingNet == null){
			ce.addPin(new SitePinInst(false,rxOrTx +"_CE",si));
		}else if(!existingNet.equals(ce)){
			throw new RuntimeException("ERROR: Incompatible control nets in "
				+ "Laguna site, currently: " + existingNet + ", failed to add " + ce);
		}
	}
	
	/** 
	 * Given a logical net and site/bel site, this method will perform the placement and routing 
	 * of two laguna flops and their inter-site super-long-line routing.
	 * @param d The current design.
	 * @param path The logical net to implement as an SLR crossing signal
	 * @param txSite The laguna site onto which to place the TX flop
	 * @param txElementName The element/bel site to place the TX flop 
	 * @return The physical net routed across the super long line
	 */
	public static Net placeAndRouteLagunaFlopPair(Design d, EDIFHierNet path, Site txSite, String txElementName){
		if(path.getNet().getPortInsts().size() != 2) 
			throw new RuntimeException("ERROR: Bad net for SLR crossing: " + path);
		Cell txCell = null;		
		Cell rxCell = null;
		EDIFNetlist n = d.getNetlist();
		
		// Get/Create cells
		for(EDIFPortInst p : path.getNet().getPortInsts()){
			String cellName = path.getHierarchicalInstName(p);
			Cell cell = d.getCell(cellName);
			if(cell == null){
				cell = d.createCell(cellName, p.getCellInst());
			}
			if(p.getPort().getName().equals("Q")) txCell = cell;
			else rxCell = cell;
		}
		d.placeCell(txCell, txSite, txSite.getBEL(txElementName));
		
		EDIFCellInst i = txCell.getEDIFCellInst();
		Net clk = n.getPhysicalNetFromPin(path.getHierarchicalInstName(), i.getPortInst("C"), d);
		Net rst = n.getPhysicalNetFromPin(path.getHierarchicalInstName(), i.getPortInst("R"), d);
		Net ce = n.getPhysicalNetFromPin(path.getHierarchicalInstName(), i.getPortInst("CE"), d);
		routeControlSignalsInLagunaSite(txCell, clk, rst, ce);
		
		// Add the TX output pin
		String sitePinName = txCell.getBEL().getPin("Q").getConnectedSitePinName();
		Net physNet = d.getNet(path.getHierarchicalNetName());
		if(physNet == null){
			 physNet = d.createNet(path.getHierarchicalNetName());
		}
		physNet.addPin(new SitePinInst(true,sitePinName,txCell.getSiteInst()));
		
		EDIFNet logicalNetIn = txCell.getEDIFCellInst().getPortInst("D").getNet();
		Net physNetIn = d.getNet(logicalNetIn.getName());
		if(physNetIn == null){
			physNetIn = d.createNet(logicalNetIn);
		}
		physNetIn.addPin(new SitePinInst(false, txCell.getBELName().replace("_REG", "D"), txCell.getSiteInst()));
		
		// Route TX output to RX site
		SitePin snk = routeToLagunaRx(physNet);
		String pinName = snk.getPinName();
		d.placeCell(rxCell, snk.getSite(), snk.getSite().getBEL("RX_REG" + pinName.substring(pinName.length()-1)));
		physNet.addPin(new SitePinInst(false,pinName,rxCell.getSiteInst()));
		
		EDIFNet logicalNetOut = rxCell.getEDIFCellInst().getPortInst("Q").getNet();
		Net physNetOut = d.getNet(logicalNetOut.getName());
		if(physNetOut == null){
			physNetOut = d.createNet(logicalNetOut);
		}
		physNetOut.addPin(new SitePinInst(true, rxCell.getBELName().replace("_REG", "Q"), rxCell.getSiteInst()));
		
		routeControlSignalsInLagunaSite(rxCell, clk, rst, ce);
		
		return physNet;
	}
	
	/**
	 * Places a BUFGCE present in the netlist.
	 * @param d Design containing BUFGCE
	 * @param s Site onto which to place the BUFGCE
	 * @param bufName Full hierarchical instance name of the BUFGCE 
	 */
	public static Cell placeBUFGCE(Design d, Site s, String bufName){
		EDIFNetlist n = d.getNetlist();
		Cell c = d.getCell(bufName);
		if(c == null){
			c = d.createCell(bufName, d.getNetlist().getCellInstFromHierName(bufName));
		}
		d.placeCell(c, s, s.getBEL("BUFCE"));
		
		Net ce = n.getPhysicalNetFromPin("", c.getEDIFCellInst().getPortInst("CE"), d);
		ce.addPin(new SitePinInst(false,"CE_PRE_OPTINV",c.getSiteInst()));
        c.getSiteInst().addSitePIP("IINV", "I_PREINV");
        Net clk_in = n.getPhysicalNetFromPin("", c.getEDIFCellInst().getPortInst("I"), d);
        Net clk = n.getPhysicalNetFromPin("", c.getEDIFCellInst().getPortInst("O"), d);
        
		c.getSiteInst().routeIntraSiteNet(ce, s.getBELPin("CE_PRE_OPTINV"), s.getBELPin("BUFCE", "CE"));
		c.getSiteInst().routeIntraSiteNet(clk_in, s.getBELPin("CLK_IN"), s.getBELPin("BUFCE", "I"));
        clk.addPin(new SitePinInst(true,"CLK_OUT",c.getSiteInst()));	
        
        return c;
	}
	
	/**
	 * Places and routes an SLR crossing given a north and south bus of size width.  
	 * @param d The current design
	 * @param northStart The starting Laguna site to start placement (placement moves north) 
	 * 	for the north traveling bus
	 * @param northBusName Full hierarchical net name of the bus to cross SLR in north direction 
	 * @param southBusName Full hierarchical net name of the bus to cross SLR in south direction
	 * @param width Width of both north and south buses crossing SLR
	 */
	public static void placeAndRouteSLRCrossing(Design d, Site northStart, String northBusName, String southBusName, int width){
		int yStart = northStart.getInstanceY() + ((LAGUNA_SITES_PER_TILE * LAGUNA_TILES_PER_FSR * 3) / 4);
		Site southStart = d.getDevice().getSite("LAGUNA_X"+northStart.getInstanceX()+"Y" + yStart);
		
		for(String busName : new String[]{northBusName,southBusName}){
			Site start = busName.equals(northBusName) ? northStart : southStart;
			int lagunaStartX = start.getInstanceX();
			int lagunaStartY = start.getInstanceY();
			for(int i=0; i < width; i++){
				EDIFNet net = d.getNetlist().getNetFromHierName(busName + "[" + i + "]");
				int x = ((i / 12) % 2) + lagunaStartX;
				int y = lagunaStartY + ((i/(LAGUNA_FLOPS_PER_SITE*LAGUNA_SITES_PER_TILE))*2) 
									 + ((i/LAGUNA_FLOPS_PER_SITE % 2) == 1 ? 1 : 0);

				Site txSite = d.getDevice().getSite("LAGUNA_X" + x + "Y" + y);
				String txElementName = "TX_REG" + (i % LAGUNA_FLOPS_PER_SITE);
				placeAndRouteLagunaFlopPair(d, new EDIFHierNet("", net), txSite, txElementName);
			}
		}
		
	}
	
	/**
	 * Separates clock sinks by direction in a half clock region so
	 * they can be driven by independent leaf clock buffers (LCBs). 
	 * @param clk The physical clock net 
	 * @param txClkWire The INT tile wire name to use for TX-based clocks
	 * @param rxClkWire The INT tile wire name to use for RX-based clocks
	 * @return A map from LCB node to a list of all clk sinks to be driven by the LCB.
	 */
	public static Map<RouteNode,ArrayList<SitePinInst>> getLCBPinMappings(Net clk, String txClkWire, String rxClkWire){
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = new HashMap<>();
		for(SitePinInst p : clk.getPins()){
			if(p.isOutPin()) continue;
			String wireName = p.getName().startsWith("TX") ? txClkWire : rxClkWire;
			Node n = new Node(p.getSite().getIntTile(), p.getSite().getIntTile().getWireIndex(wireName));
			RouteNode rn = new RouteNode(n.getTile(), n.getWire());
			ArrayList<SitePinInst> sinks = lcbMappings.get(rn);
			if(sinks == null){
				sinks = new ArrayList<>();
				lcbMappings.put(rn, sinks);
			}
			sinks.add(p);
		}
		return lcbMappings;
	}
	
	
	public static ClockRegion findCentroid(String[] lagunaStarts, Device dev){
		HashSet<Point> lagunaPoints = new HashSet<>();
		for(String laguna : lagunaStarts){
			Tile t = dev.getSite(laguna).getTile();
			lagunaPoints.add(new Point(t.getColumn(),t.getRow()));
		}
		Point center = SmallestEnclosingCircle.getCenterPoint(lagunaPoints);
		Tile c = dev.getTile(center.y, center.x);
		int i=1;
		int dir = -1;
		int count = 0;
		// Some tiles don't belong to a clock region, we need to wiggle around 
		// until we find one that is
		while(c.getClockRegion() == null){
			int neighborOffset = (count % 2 == 0) ? dir*i : i; 
			c = c.getTileNeighbor(neighborOffset, 0);
			count++;
			if(count % 2 == 0) i++;
		}
		return c.getClockRegion().getNeighborClockRegion(0, 1);
	}
	
	
	public static void customRouteSLRCrossingClock(Design d, String clkName, String[] lagunaStarts, String txClkWire, String rxClkWire, boolean useCommonCentroid){
		Net clk = d.getNet(clkName);
		Device dev = d.getDevice();
		
		List<ClockRegion> clockRegions = new ArrayList<>();
		for(String laguna : lagunaStarts){
			ClockRegion cr = dev.getSite(laguna).getTile().getClockRegion();
			clockRegions.add(cr);
			clockRegions.add(dev.getClockRegion(cr.getRow()+1, cr.getColumn()));				
		}
		
		// Route from BUFG to Clock Routing Tracks
		RouteNode clkRoutingLine = UltraScaleClockRouting.routeBUFGToNearestRoutingTrack(clk);
		
		// Route from Routing track to Centroid
		Set<ClockRegion> centroids = new HashSet<>();
		if(useCommonCentroid){
			// Use a conventional centroid that attempts to minimize global skew
			centroids.add(findCentroid(lagunaStarts, dev));
		}else{
			// Use each Laguna start CR as a centroid
			for(String laguna : lagunaStarts){
				ClockRegion cr = dev.getSite(laguna).getTile().getClockRegion();
				centroids.add(cr);
			}
		}

		List<RouteNode> distLines = new ArrayList<>();
		for(ClockRegion centroid : centroids){
			RouteNode centroidRouteNode = UltraScaleClockRouting.routeToCentroid(clk, clkRoutingLine, centroid);
			
			// Transition centroid from routing track to vertical distribution track
			RouteNode centroidDistNode = UltraScaleClockRouting.transitionCentroidToDistributionLine(clk,centroidRouteNode);

			// Route from Centroid to Clock distribution 
			if(!useCommonCentroid){
				clockRegions.clear();
				clockRegions.add(centroid);
				clockRegions.add(centroid.getNeighborClockRegion(1, 0));
			}
			Map<ClockRegion, RouteNode> vertDistLines = UltraScaleClockRouting.routeCentroidToVerticalDistributionLines(clk,centroidDistNode, clockRegions);
			
			distLines.addAll(UltraScaleClockRouting.routeCentroidToHorizontalDistributionLines(clk, centroidDistNode, vertDistLines)); 			
		}
		
		
		// Separate sinks by RX/TX LCBs
		Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = getLCBPinMappings(clk, txClkWire, rxClkWire);
		
		// Route from clock distribution to all 4 LCBs
		UltraScaleClockRouting.routeDistributionToLCBs(clk, distLines, lcbMappings.keySet());		
		
		// Route from each LCB to laguna sites
		UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings);

		// Update clocking delays to improve SLR crossing hold issues
		clk.improveSLRClockingDelay(txClkWire, rxClkWire);
	}
	
	/**
	 * Creates/instantiates a BUFGCE in the design
	 * @param d The current design
	 * @param clkName Name of the clock net
	 * @param clkInName Name of the clock in port
	 * @param clkOutName Name of the clock out port, or null for none
	 * @param bufgceInstName Name of the BUFGCE instance
	 */
	public static void createBUFGCE(Design d, String clkName, String clkInName, String clkOutName, String bufgceInstName){
		EDIFNetlist n = d.getNetlist();
		EDIFCell parent = n.getTopCell();
		
		// Create BUFGCE in netlist and connect it
		EDIFCellInst bufgce = Design.createUnisimInst(parent, bufgceInstName, Unisim.BUFGCE);
		EDIFNet clkInNet = parent.createNet(clkInName);
		clkInNet.createPortInst(parent.createPort(clkInName, EDIFDirection.INPUT, 1));
		
		clkInNet.createPortInst("I", bufgce);
		EDIFNet clkNet = parent.createNet(clkName);
		clkNet.createPortInst("O", bufgce);
		if(clkOutName != null){
			clkNet.createPortInst(parent.createPort(clkOutName, EDIFDirection.OUTPUT, 1));
		}
		EDIFNet vccNet = EDIFTools.getStaticNet(NetType.VCC, parent, n);
		vccNet.createPortInst("CE", bufgce);
	}
	
	/**
	 * Creates the logical netlist of the SLR crosser design.
	 * @param d Current design 
	 * @param busWidth Width of the buses to create
	 * @param busPrefixes Prefixes to use for bus names
	 * @param clkName Name of the clock net
	 * @param clkInName Name of the clock in port
	 * @param clkOutName Name of the clock out port
	 * @param bufgceInstName Name of the BUFGCE instance
	 */
	public static void createBUFGCEAndFlops(Design d, int busWidth, List<String> busPrefixes, String clkName, String clkInName, String clkOutName, String bufgceInstName){
		EDIFNetlist n = d.getNetlist();
		EDIFCell parent = n.getTopCell();
		
		// Create BUFGCE in netlist and connect it
		createBUFGCE(d, clkName, clkInName, clkOutName, bufgceInstName);

		EDIFNet clkNet = parent.getNet(clkName);
		EDIFNet vccNet = EDIFTools.getStaticNet(NetType.VCC, parent, n);
		EDIFNet gndNet = EDIFTools.getStaticNet(NetType.GND, parent, n);
		
		// Create register pairs
		for(String busPrefix : busPrefixes){
			String busSuffix = "[" + (busWidth-1) + ":0]";
			String[] parts = busPrefix.split(",");
			EDIFPort input = parent.createPort(parts[0]+busSuffix, EDIFDirection.INPUT, busWidth);
			EDIFPort output = parent.createPort(parts[1]+busSuffix, EDIFDirection.OUTPUT, busWidth);
			for(int i=0; i < busWidth; i++){
				String suffix = "[" + i + "]";
				EDIFCellInst reg0 = Design.createUnisimInst(parent, parts[0] + "_reg0" + suffix, Unisim.FDRE);
				EDIFCellInst reg1 = Design.createUnisimInst(parent, parts[1] + "_reg1" + suffix, Unisim.FDRE);
				
				EDIFNet inputNet = parent.createNet(parts[0] + suffix);
				inputNet.createPortInst(input, i);
				inputNet.createPortInst("D", reg0);
				
				EDIFNet outputNet = parent.createNet(parts[1] + suffix);
				outputNet.createPortInst(output, i);
				outputNet.createPortInst("Q", reg1);
				
				clkNet.createPortInst("C", reg0);
				clkNet.createPortInst("C", reg1);
				
				vccNet.createPortInst("CE", reg0);
				vccNet.createPortInst("CE", reg1);
				
				gndNet.createPortInst("R", reg0);
				gndNet.createPortInst("R", reg1);
				
				EDIFNet connNet = parent.createNet(parts[0] + "_" + parts[1] + suffix);
				connNet.createPortInst("Q",reg0);
				connNet.createPortInst("D",reg1);
			}			
		}
	}
	
	private static final String PART_OPT = "p";
	private static final String DESIGN_NAME_OPT = "d";
	private static final String TX_CLK_WIRE_OPT = "t";
	private static final String RX_CLK_WIRE_OPT = "r";
	private static final String OUT_DCP_OPT = "o";
	private static final String BUFGCE_LOC_OPT = "b";
	private static final String BUFGCE_NAME_OPT = "y";
	private static final String CLK_NAME_OPT = "c";
	private static final String CLK_IN_NAME_OPT = "a";
	private static final String CLK_OUT_NAME_OPT = "u";
	private static final String CLK_CONSTRAINT_OPT = "x";
	private static final String BUS_WIDTH_OPT = "w";
	private static final String INPUT_PREFIX_OPT = "i";
	private static final String OUTPUT_PREFIX_OPT = "q";
	private static final String NORTH_SUFFIX_OPT = "n";
	private static final String SOUTH_SUFFIX_OPT = "s";
	private static final String LAGUNA_SITES_OPT = "l";
	private static final String VERBOSE_OPT = "v";
	private static final String HELP_OPT = "h";
	private static final String COMMON_CENTROID_OPT = "z";
	
	private static OptionParser createOptionParser(){
		// Defaults
		String partName = "xcvu9p-flgb2104-2-i";
		String designName = "slr_crosser";
		String txClkWire = "GCLK_B_0_0";
		String rxClkWire = "GCLK_B_0_1";
		String outputDCPFileName = System.getProperty("user.dir") + File.separator + designName +".dcp";
		String bufgceSiteName = "BUFGCE_X0Y218";
		String bufgceInstName = "BUFGCE_inst";
		String clkName = "clk";
		String clkInName = "clk_in";
		String clkOutName = "clk_out";
		double clkPeriodConstraint = 1.333;
		int busWidth = 512;
		String inputPrefix = "input";
		String outputPrefix= "output";
		String northSuffix = "_north";
		String southSuffix = "_south";
		String lagunaSites = "LAGUNA_X2Y120";
		boolean verbose = true;
		boolean useCommonCentroid = false;
		
		OptionParser p = new OptionParser() {{
			accepts(PART_OPT).withOptionalArg().defaultsTo(partName).describedAs("UltraScale+ Part Name");
			accepts(DESIGN_NAME_OPT).withOptionalArg().defaultsTo(designName).describedAs("Design Name");
			accepts(TX_CLK_WIRE_OPT).withOptionalArg().defaultsTo(txClkWire).describedAs("INT clk Laguna TX flops");
			accepts(RX_CLK_WIRE_OPT).withOptionalArg().defaultsTo(rxClkWire).describedAs("INT clk Laguna RX flops");
			accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo(outputDCPFileName).describedAs("Output DCP File Name");
			accepts(BUFGCE_LOC_OPT).withOptionalArg().defaultsTo(bufgceSiteName).describedAs("Clock BUFGCE site name");
			accepts(BUFGCE_NAME_OPT).withOptionalArg().defaultsTo(bufgceInstName).describedAs("BUFGCE cell instance name");
			accepts(CLK_NAME_OPT).withOptionalArg().defaultsTo(clkName).describedAs("Clk net name");
			accepts(CLK_IN_NAME_OPT).withOptionalArg().defaultsTo(clkInName).describedAs("Clk input net name");
			accepts(CLK_OUT_NAME_OPT).withOptionalArg().defaultsTo(clkOutName).describedAs("Clk output net name");
			accepts(CLK_CONSTRAINT_OPT).withRequiredArg().ofType(Double.class).describedAs("Clk period constraint (ns)");
			accepts(BUS_WIDTH_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(busWidth).describedAs("SLR crossing bus width");
			accepts(INPUT_PREFIX_OPT).withOptionalArg().defaultsTo(inputPrefix).describedAs("Input bus name prefix");
			accepts(OUTPUT_PREFIX_OPT).withOptionalArg().defaultsTo(outputPrefix).describedAs("Output bus name prefix");
			accepts(NORTH_SUFFIX_OPT).withOptionalArg().defaultsTo(northSuffix).describedAs("North bus name suffix");
			accepts(SOUTH_SUFFIX_OPT).withOptionalArg().defaultsTo(southSuffix).describedAs("South bus name suffix");
			accepts(LAGUNA_SITES_OPT).withOptionalArg().defaultsTo(lagunaSites).describedAs("Comma separated list of Laguna sites for each SLR crossing");
			accepts(VERBOSE_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(verbose).describedAs("Print verbose output");
			accepts(COMMON_CENTROID_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(useCommonCentroid).describedAs("Use common centroid");
			acceptsAll( Arrays.asList(HELP_OPT, "?"), "Print Help" ).forHelp();
		}};
		
		return p;
	}
	
	private static void printHelp(OptionParser p){
		MessageGenerator.printHeader("SLR Crossing DCP Generator");
		System.out.println("This RapidWright program creates a placed and routed DCP that can be \n"
			+ "imported into UltraScale+ designs to aid in high speed SLR crossings.  See \n"
			+ "RapidWright documentation for more information.\n");
		try {
			p.accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo("slr_crosser.dcp").describedAs("Output DCP File Name");
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
		CodePerfTracker t = verbose ? new CodePerfTracker(SLRCrosserGenerator.class.getSimpleName(),true).start("Init") : null;
		
		String partName = (String) opts.valueOf(PART_OPT);
		String designName = (String) opts.valueOf(DESIGN_NAME_OPT);
		String txClkWire = (String) opts.valueOf(TX_CLK_WIRE_OPT);
		String rxClkWire = (String) opts.valueOf(RX_CLK_WIRE_OPT);
		String outputDCPFileName = (String) opts.valueOf(OUT_DCP_OPT);
		String bufgceSiteName = (String) opts.valueOf(BUFGCE_LOC_OPT);
		String bufgceInstName = (String) opts.valueOf(BUFGCE_NAME_OPT);
		String clkName = (String) opts.valueOf(CLK_NAME_OPT);
		String clkInName = (String) opts.valueOf(CLK_IN_NAME_OPT);
		String clkOutName = (String) opts.valueOf(CLK_OUT_NAME_OPT);
		int busWidth = (int) opts.valueOf(BUS_WIDTH_OPT);		
		String inputPrefix = (String) opts.valueOf(INPUT_PREFIX_OPT);
		String outputPrefix= (String) opts.valueOf(OUTPUT_PREFIX_OPT);
		String northSuffix = (String) opts.valueOf(NORTH_SUFFIX_OPT);
		String southSuffix = (String) opts.valueOf(SOUTH_SUFFIX_OPT);
		String[] lagunaNames = ((String) opts.valueOf(LAGUNA_SITES_OPT)).split(",");
		boolean commonCentroid = (boolean) opts.valueOf(COMMON_CENTROID_OPT);
		
		Double clkPeriodConstraint = null;
		if(opts.hasArgument(CLK_CONSTRAINT_OPT)){
			clkPeriodConstraint = (double) opts.valueOf(CLK_CONSTRAINT_OPT);
		}
		// Perform some error checking on inputs
		Part part = PartNameTools.getPart(partName);
		if(part == null || !part.isUltraScalePlus()){
			MessageGenerator.briefErrorAndExit("ERROR: Invalid/unsupport part " + partName + ".");
		}
		
		Design d = new Design(designName,partName);
		d.setAutoIOBuffers(false);
		Device dev = d.getDevice();
		
		if(dev.getSite(bufgceSiteName) == null){
			MessageGenerator.briefErrorAndExit("ERROR: BUFGCE site '" +
					bufgceSiteName + "' not found on part " + partName);
		}
		for(String lagunaSite : lagunaNames){
			Site s = dev.getSite(lagunaSite);
			if(s == null){
				MessageGenerator.briefErrorAndExit("ERROR: LAGUNA site '" + 
					lagunaSite + "' not found on part " + partName);
			}
			ClockRegion curr = s.getTile().getClockRegion();
			ClockRegion below = s.getNeighborSite(0, -1).getTile().getClockRegion();
			if(curr.equals(below) || (curr.getRow() - below.getRow() == 1) || s.getInstanceX() % 2 != 0) 
				MessageGenerator.briefErrorAndExit("ERROR: Laguna site '" + s + "' is not a bottom row LAGUNA site.");
		}
		
		List<String> busNames = new ArrayList<>();
		for(int i=0; i < lagunaNames.length; i++){
			busNames.add(inputPrefix + i + northSuffix + "," + outputPrefix + i + northSuffix);
			busNames.add(inputPrefix + i + southSuffix + "," + outputPrefix + i + southSuffix);
		}
		

		if(verbose) t.stop().start("Create Netlist");		
		createBUFGCEAndFlops(d, busWidth, busNames, clkName, clkInName, clkOutName, bufgceInstName);
		placeBUFGCE(d,dev.getSite(bufgceSiteName),bufgceInstName);		
		
		if(verbose) t.stop().start("Place SLR Crossings");
		int j = 0;
		for(String lagunaStart : lagunaNames){
			Site northLagunaStart = dev.getSite(lagunaStart);
			String northBusName = busNames.get(j+0).replace(",", "_");
			String southBusName = busNames.get(j+1).replace(",", "_");
			placeAndRouteSLRCrossing(d, northLagunaStart, northBusName, southBusName, busWidth);
			j+=2;
		}
		if(verbose) t.stop().start("Custom Clock Route");
		customRouteSLRCrossingClock(d, clkName, lagunaNames, txClkWire, rxClkWire, commonCentroid);

		if(verbose) t.stop().start("Route VCC/GND");		
		Router r = new Router(d);
		r.routeStaticNets();
		t.stop();
		
		// Add a clock constraint
		if(clkPeriodConstraint != null){
			d.addXDCConstraint(ConstraintGroup.LATE, "create_clock -name "+clkName+" -period "+clkPeriodConstraint+" [get_nets "+clkName+"]");
			d.addXDCConstraint(ConstraintGroup.LATE, "create_property MAX_PROG_DELAY net"); 
			d.addXDCConstraint(ConstraintGroup.LATE, "set_property MAX_PROG_DELAY 0 [get_nets "+clkName+"]");			
		}
		
		d.writeCheckpoint(outputDCPFileName, t);
		if(verbose) System.out.println("Wrote final DCP: " + outputDCPFileName);
	}
}
