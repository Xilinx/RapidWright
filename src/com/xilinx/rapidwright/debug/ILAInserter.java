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
package com.xilinx.rapidwright.debug;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.StringTools;


/**
 * Example application in RapidWright for adding an ILA core within 
 * an implemented (placed and routed) design.
 * Created on: May 3, 2017
 */
public class ILAInserter {
	
	private static final String ETV_true = "true";
	private static final String ETV_TRUE = "TRUE";
	private static final String EDIF_MARK_DEBUG = "mark_debug";
	private static final String XDC_MARK_DEBUG = "MARK_DEBUG"; 
	private static final String XDC_SET_PROPERTY = "set_property";
	
	/**
	 * This method will examine a design for any nets marked for debug
	 * and return the list of names of those nets. 
	 * @param design The design to examine
	 * @return A list of net names marked for debug.
	 */
	public static List<String> getNetsMarkedForDebug(Design design){
		// Nets can be marked for debug as a netlist property
		ArrayList<String> debugNets = new ArrayList<>();
		for(Entry<String,EDIFNet> e : design.getNetlistNetMap().entrySet()){
			EDIFPropertyValue p = e.getValue().getProperty(EDIF_MARK_DEBUG);
			if(p == null) continue;
			String etv = p.getValue();
			if(etv.equals(ETV_true) || etv.equals(ETV_TRUE)){
				debugNets.add(e.getKey());
			}
		}

		// Nets can also be marked for debug in XDC
		
		List<List<String>> xdcLines = new ArrayList<List<String>>();
		for(ConstraintGroup cg : ConstraintGroup.values()){
			List<String> lines = design.getXDCConstraints(cg);
			if(!lines.isEmpty()){
				xdcLines.add(lines);
			}
		}
		for(List<String> file : xdcLines){
			for(String line : file){
				if(line.equals("") || line.startsWith("#")) continue;
				if(line.contains(XDC_MARK_DEBUG) && line.contains(XDC_SET_PROPERTY)){
					String[] tokens = line.split("\\s+");
					if(tokens[0].equals(XDC_SET_PROPERTY) && tokens[1].equals(XDC_MARK_DEBUG) && tokens[2].equals("true") && tokens[3].equals("[get_nets")){
						String netName = tokens[4].substring(tokens[4].indexOf('{')+1, tokens[4].indexOf('}'));
						debugNets.add(netName);
					}
				}
			}
		}
		
		
		
		return debugNets;
	}
	
	/**
	 * This method will go outside and invoke Vivado to create a 
	 * stand-alone project with an ILA and Debug Hub and created a 
	 * synthesized DCP that can be imported to RapidWright.
	 * @param probeCount The number of probes desired on the ILA
	 * @param probeDepth The depth of capture for the ILA
	 * @param part The part to target the ILA
	 * @return The design consisting of a synthesized ILA and Debug Hub
	 */
	public static Design createILADesign(int probeCount, int probeDepth, Part part){
		List<String> tclCommands = new ArrayList<>();
		String projFolder = ".ila";
		String tclFileName = projFolder + "/create_ila_" + FileTools.getUniqueProcessAndHostID() + ".tcl";
		
		FileTools.makeDir(projFolder);
		String dcpFileName = projFolder+"/ila.dcp";
		tclCommands.add("source " + FileTools.getRapidWrightPath() + File.separator + FileTools.TCL_FOLDER_NAME + File.separator + "rapidwright.tcl");
		tclCommands.add("create_preimplemented_ila_dcp "+part+" "+probeCount+" "+probeDepth+" " +dcpFileName +"\n");
		
		FileTools.writeLinesToTextFile(tclCommands, tclFileName);
		FileTools.runCommand("vivado -mode batch -log "+projFolder+"/vivado.log -journal "+
				projFolder+"/vivado.jou -source " + tclFileName, true);
		
		Design ilaDesign = Design.readCheckpoint(dcpFileName);
		
		return ilaDesign;
	}
	
	/**
	 * This inserts the ILA design into the original design such that it can
	 * be placed and routed on top of the original design inside Vivado.
	 * @param original The original design (design to be debugged)
	 * @param ila The ILA+Debug Hub design (likely to be created with createILADesign())
	 * @return True if the ILA+Debug Hub insertion was successful, false otherwise
	 */
	public static boolean applyILAToDesign(Design original, Design ila, String clkName) {
		// Turn ILA into a module and instantiate it into the design
		Module m = new Module(ila);
		ModuleInst mi = original.createModuleInst(ila.getName(), m);
		original.getNetlist().migrateCellAndSubCells(ila.getTopEDIFCell());
		if(m.getAnchor() != null) {
			mi.place(m.getAnchor().getSite());
		}
		

		// Logical netlist
		EDIFNetlist netlist = original.getNetlist();
		EDIFCell top = netlist.getTopCell();
		
		// Need to bring out clk net to top level
		EDIFNet clk = null;
		
		String ilaClkPort = "ila_clk_out";
		int lastSep = clkName.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
		if(lastSep > -1) {

			// Find clock net closes to the top level cell
			int minNum = Integer.MAX_VALUE;
			String minAlias = null;
			for(String clkNetAlias : netlist.getNetAliases(clkName)) {
				int level = StringTools.countOccurrences(clkNetAlias, EDIFTools.EDIF_HIER_SEP.charAt(0));
				if(level < minNum) {
					minNum = level;
					minAlias = clkNetAlias;
				}
			}
			
			// Create new ports to wire clock to top level in order to connect it to the ILA
			lastSep = minAlias.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
			if(lastSep > -1) {
				clk = netlist.getNetFromHierName(minAlias); 
				EDIFCellInst currInst = null;
				String currInstName = minAlias.substring(0, lastSep);
				// Drill up to the top level cell
				do {
					currInst = netlist.getCellInstFromHierName(currInstName);
					EDIFPort port = null;
					// Create port on the instance
					port = currInst.getCellType().createPort(ilaClkPort, EDIFDirection.OUTPUT, 1);
					// Connect the net to the port
					clk.createPortInst(port);
					// Pop up to next level
					lastSep = currInstName.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
					currInstName = lastSep > -1 ? currInstName.substring(0, lastSep) : "";
					EDIFCellInst prevInst = currInst;
					currInst = netlist.getCellInstFromHierName(currInstName);
					// Create a new net in the parent cell, connect it to the port
					clk = currInst.getCellType().createNet(ilaClkPort);
					clk.createPortInst(port, prevInst);
				} while(!currInst.getCellType().equals(top));
			} else {
				clk = top.getNet(minAlias);
			}
		} else {
			clk = top.getNet(clkName);
		}
		
		// Connect the clock (assumes all probed signals are synchronous)
		clk.createPortInst("clk", mi.getCellInst());
		
		for(String c : ila.getXDCConstraints(ConstraintGroup.NORMAL)){
			if(c.contains("current_instance ")){
				if(!c.contains("-quiet")){
					c = c.replace("current_instance ", "current_instance " + ila.getNetlist().getTopCellInst().getName() + "/");
				}
				
			}
			original.addXDCConstraint(c);
		}
		
		return true;
	}
	
	public static void main(String[] args) {
		if(args.length < 4){
			MessageGenerator.briefMessageAndExit("USAGE: <input.dcp> <output.dcp> probe_count probe_depth clk_net [ila dcp]");
		}
		String inputDcpFileName = args[0];
		String outputDcpFileName = args[1];
		int probeCount = Integer.parseInt(args[2]);
		int probeDepth = Integer.parseInt(args[3]);
		String clkNet = args[4];
		boolean lockPlacement = false;
		boolean lockRouting = false;
		
		if(probeCount < 0 || probeCount > 1024){
			throw new RuntimeException("ERROR: Unsupported probe count of " + probeCount + ", must be between 1 and 1024.");
		}
		int[] allowedDepths = new int[] {1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072};
		boolean isDepthValid = false;
		for(int depth : allowedDepths){
			if(depth == probeDepth) isDepthValid = true;
		}
		if(!isDepthValid){
			throw new RuntimeException("ERROR: Unsupported probe depth of " + probeDepth +", must be one of " + Arrays.toString(allowedDepths));
		}
		
		Design originalDesign = Design.readCheckpoint(inputDcpFileName);
		
		EDIFNet clk = originalDesign.getNetlistNetMap().get(clkNet);
		if(clk == null){
			throw new RuntimeException("ERROR: Couldn't find the clk net named " + clkNet + " in the original design provided");
		}
		
		// TODO - Auto identify clock from probed signals
		/*List<String> netsToDebug = getNetsMarkedForDebug(originalDesign);
		Net clk = null;
		for(String net : netsToDebug){
			clk = DesignTools.getClockDomain(originalDesign, net);
			if(clk != null) break;
		}*/ 
		
		if(lockPlacement){
			for(Cell c : originalDesign.getCells()){
				c.setBELFixed(true);
				c.setSiteFixed(true);
			}			
		}
		if(lockRouting){
			for(Net n : originalDesign.getNets()){
				for(PIP p : n.getPIPs()){
					p.setIsPIPFixed(true);
				}
			}			
		}
		
		Design ilaDesign = null;
		if(args.length == 6){
			ilaDesign = Design.readCheckpoint(args[5]);
		}else {
			ilaDesign = createILADesign(probeCount, probeDepth, originalDesign.getPart());
		}
		
		applyILAToDesign(originalDesign, ilaDesign, clkNet);
		originalDesign.writeCheckpoint(outputDcpFileName);
	}
}
