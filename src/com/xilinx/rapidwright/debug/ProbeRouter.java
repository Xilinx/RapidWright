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

package com.xilinx.rapidwright.debug;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;

public class ProbeRouter {

	public static Map<String,String> readProbeRequestFile(String fileName){
		Map<String,String> map = new TreeMap<>();
		for(String line : FileTools.getLinesFromTextFile(fileName)){
			if(line.trim().startsWith("#")) continue;
			String[] parts = line.split(" ");
			map.put(parts[0], parts[1]);
		}
		return map;
	}
	
	/**
	 * Updates a design containing an ILA (integrated logic analyzer) probe connections
	 * that already exist in a design.  
	 * @param d The existing placed and routed design with an ILA.
	 * @param probeToTargetNets A map from probe names to desired net names (full hierarchical names).
	 * @param pblock An optional pblock (area constraint) to contain routing within a certain area.
	 */	
	public static void updateProbeConnections(Design d, Map<String,String> probeToTargetNets){
		updateProbeConnections(d, probeToTargetNets, null);
	}
	
	/**
	 * Updates a design containing an ILA (integrated logic analyzer) probe connections
	 * that already exist in a design.  
	 * @param d The existing placed and routed design with an ILA.
	 * @param probeToTargetNets A map from probe names to desired net names (full hierarchical names).
	 * @param pblock An optional pblock (area constraint) to contain routing within a certain area.
	 */
	public static void updateProbeConnections(Design d, Map<String,String> probeToTargetNets, PBlock pblock){
		ArrayList<SitePinInst> pinsToRoute = new ArrayList<>(); 
		for(Entry<String,String> e : probeToTargetNets.entrySet()){
			String hierPinName = e.getKey();
			String cellInstName = EDIFTools.getHierarchicalRootFromPinName(hierPinName);
			EDIFCellInst i = d.getNetlist().getCellInstFromHierName(cellInstName);
			String pinName = hierPinName.substring(hierPinName.lastIndexOf(EDIFTools.EDIF_HIER_SEP)+1);
			EDIFPortInst portInst = i.getPortInst(pinName);
			EDIFNet net = portInst.getNet();
			String parentCellInstName = cellInstName.contains(EDIFTools.EDIF_HIER_SEP) ? cellInstName.substring(0,cellInstName.lastIndexOf(EDIFTools.EDIF_HIER_SEP)) : "";			
			Net oldPhysNet = d.getNetlist().getPhysicalNetFromPin(parentCellInstName, portInst, d);
			
			// Find the sink flop
			String hierInstName = cellInstName.contains(EDIFTools.EDIF_HIER_SEP) ? cellInstName.substring(0, cellInstName.lastIndexOf('/')) : ""; 
			EDIFHierPortInst startingPoint = new EDIFHierPortInst(hierInstName, portInst);
			ArrayList<EDIFHierPortInst> sinks = EDIFTools.findSinks(startingPoint);
			if(sinks.size() != 1) {
				System.err.println("ERROR: Currently we only support a single flip flop "
						+ "sink for probe re-routes, found " + sinks.size() + " on " + e.getKey() + ", skipping...");
				continue;
			}
				
			EDIFHierPortInst sinkFlop = sinks.get(0);
			Cell c = d.getCell(sinkFlop.getFullHierarchicalInstName());
			SitePinInst physProbeInPin = c.unrouteLogicalPinInSite(sinkFlop.getPortInst().getName());
			
			// Disconnect probe from current net
			net.removePortInst(portInst);
			// Unroute the portion of physical route to old probe net
			if(physProbeInPin != null) 
				oldPhysNet.removePin(physProbeInPin,true);
			
			// Connect probe to new net
			String newPortName = "rw_"+ pinName;
			EDIFNet newNet = net.getParentCell().createNet(newPortName);
			newNet.addPortInst(portInst);

			EDIFCellInst parent = d.getNetlist().getCellInstFromHierName(parentCellInstName);
			EDIFHierCellInst parentInst = new EDIFHierCellInst(parentCellInstName, parent);
			EDIFTools.connectDebugProbe(newNet, e.getValue(), newPortName, parentInst, d.getNetlist(), null);
			
			String parentNet = d.getNetlist().getParentNetName(e.getValue());
			Net destPhysNet = d.getNet(parentNet);
			
			// Route the site appropriately
			
			/*String siteWire = c.getSiteWireNameFromLogicalPin(sinkFlop.getPortInst().getName());
			c.getSiteInst().addCTag(destPhysNet, siteWire);
			BELPin inPin = c.getBEL().getPin(c.getPhysicalPinMapping(sinkFlop.getPortInst().getName()));
			BELPin rbel = inPin.getSiteConns().get(0);
			c.getSiteInst().addSitePIP(rbel.getBEL().getName(), "BYP", rbel.getName());
			String siteWireName = rbel.getBEL().getPin("BYP").getSiteWireName();
			c.getSiteInst().addCTag(destPhysNet, siteWireName);*/
			
			String sitePinName = c.getBELName().charAt(0) + "X";
			BELPin inPin = c.getBEL().getPin(c.getPhysicalPinMapping(sinkFlop.getPortInst().getName()));
			c.getSiteInst().routeIntraSiteNet(destPhysNet, c.getSite().getBELPin(sitePinName), inPin);
			
			if(physProbeInPin == null){
				// Previous connection was internal to site, need to route out to site pin
				physProbeInPin = new SitePinInst(false, sitePinName, c.getSiteInst());
			}
			destPhysNet.addPin(physProbeInPin);
			pinsToRoute.add(physProbeInPin);
		}
		
		// Attempt route new net to probe
		// TODO - Should we add a flop?
		Router r = new Router(d);
		if(pblock != null) r.setRoutingPblock(pblock);
		r.routePinsReEntrant(pinsToRoute, false);
	}
	
	public static List<EDIFHierCellInst> findILAs(Design d){
		List<EDIFHierCellInst> candidates = d.getNetlist().getAllDescendants("", "u_ila_*", false);
		ArrayList<EDIFHierCellInst> ilas = new ArrayList<EDIFHierCellInst>();
		nextInst: for(EDIFHierCellInst i : candidates){
			if(i.getCellName().contains("u_ila_")){
				for(EDIFPort p : i.getCellType().getPorts()){
					if(p.getName().contains("SL_IPORT_")){
						ilas.add(i);
						continue nextInst;
					}
				}
			}
		}
		return ilas;
	}
	
	private static final String PBLOCK_SWITCH = "--pblock";
	
	private static void printHelp(){
		MessageGenerator.briefMessageAndExit("USAGE: <input.dcp> <probes.txt> <output.dcp> ["+
			PBLOCK_SWITCH+" 'CLOCKREGION_X0Y10:CLOCKREGION_X5Y14 CLOCKREGION_X0Y0:CLOCKREGION_X3Y9']");
	}
	
	public static void main(String[] args) {
		if(args.length < 3 || args.length > 5){
			printHelp();
		}
		String inputDCP = args[0];
		String probesFile = args[1];
		String outputDCP = args[2];
		PBlock pblock = null;
		
		CodePerfTracker t = new CodePerfTracker("Probe Router");
		Design d = Design.readCheckpoint(inputDCP,t);
		
		if(args.length == 5){
			if(!args[3].equals(PBLOCK_SWITCH)) printHelp();
			pblock = new PBlock(d.getDevice(), args[4]);
		}
		
		t.start("Unroute/route probes");
		Map<String,String> probeMap = readProbeRequestFile(probesFile);		
		updateProbeConnections(d, probeMap, pblock);
		t.stop();
		d.writeCheckpoint(outputDCP,t);
	}
}
