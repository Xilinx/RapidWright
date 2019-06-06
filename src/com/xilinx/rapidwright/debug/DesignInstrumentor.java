/* 
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleImpls;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Port;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.ipi.BlockCreator;
import com.xilinx.rapidwright.placer.blockplacer.BlockPlacer;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;


/**
 * This class serves as the main entry point for the BYU/Xilinx debug
 * instrumentation flow within RapidWright.  
 * 
 * Created on: Nov 30, 2015
 */
public class DesignInstrumentor {
	
	public static final String SAMPLE_DEPTH_KEYWORD = "SAMPLE_DEPTH";
	
	private int sampleDepth = -1;
	
	private TreeMap<String,String> netNames = new TreeMap<String, String>(); 
	
	private Design design = null; 
	
	private Net clockNet = null;
	
	private EDIFNet edifClockNet = null;
	
	private ArrayList<SitePinInst> pinsToRoute = null;
	
	public static final String DEBUG_CORE_PATH = FileTools.getRapidWrightPath() + "/debug";
	
	/**
	 * This should load the instrumentation file details into
	 * class members.  The file will likely be small, so the function
	 * is written for ease of implementation, not speed.  
	 * @param fileName Name of the instrumentation details file.
	 */
	public void loadInstrumentationDetailsFile(String fileName){
		for(String line : FileTools.getLinesFromTextFile(fileName)){
			// Skip comments and empty lines
			if(line.startsWith("#")) continue;
			if(line.trim().equals("")) continue;
			
			// Every line should be a key value pair
			String[] tokens = line.split("\\s+");
			if(line.startsWith(SAMPLE_DEPTH_KEYWORD)){
				sampleDepth = Integer.parseInt(tokens[1]);
			}else{
				netNames.put(tokens[0], tokens[1]);
			}
		}
	}
	
	/**
	 * Stitches in the debug unit probes and clock into the user design.
	 * Specifically, it add the input pin ports of the debug block onto
	 * the user design nets of interest.
	 * @return All the pins that will need routing 
	 */
	public void stitchProbesOnILA(ModuleInst mi, EDIFCellInst debugCore){
		EDIFCell topCell = design.getNetlist().getTopCell();
		Map<String, EDIFPortInst> debugPorts = debugCore.getPortInstMap();
		HashMap<EDIFCell, ArrayList<EDIFCellInst>> instMap = EDIFTools.generateCellInstMap(design.getNetlist().getTopCellInst());
		
		pinsToRoute = new ArrayList<SitePinInst>();
		// For each net, add the probe input pin as a sink on the net
		HashSet<String> alreadyConnected = new HashSet<String>();
		int probeCount = 0;
		Module m = mi.getModule();
		SitePinInst clockPin = null;
		String portPrefix = "probe0"; // TODO - This should be data driven
		for(Entry<String,String> netNamePair : netNames.entrySet()){
			String netName = netNamePair.getKey();
			String routedNetName = netNamePair.getValue();
			// Watch out for two nets with equivalent physical routed nets
			if(alreadyConnected.contains(routedNetName)) continue;
			
			Net n = design.getNet(routedNetName);
			if(n.getPins().size() < 2){
				MessageGenerator.briefError("WARNING: Net "+ netName +" has no source, routing probe to GND.  (" +
						routedNetName + " is the routing net alias)");
				n = design.getNet(Net.GND_NET);
			}
			
			// Identify clock net
			if(clockPin == null && n.getSource() != null){
				clockPin = DesignTools.identifyClockSource(n.getSource());
			}
			
			String portName = portPrefix + "[" + probeCount + "]";
			Port p = m.getPort(portName);
			Net portNet = mi.getCorrespondingNet(p);
			if(portNet == null || portNet.getPins().size() != 1){
				MessageGenerator.briefError("WARNING: Couldn't get probe net for port " + portName);
			}
			
			// Move pin over 
			pinsToRoute.addAll(portNet.getPins());
			design.movePinsToNewNetDeleteOldNet(portNet, n, true);
			
			// Connect EDIF in same way
			EDIFPortInst currDebugPort = debugPorts.get(portName);
			if(n.isStaticNet()){
				EDIFNet debugNet = EDIFTools.getStaticNet(n.getType(), topCell, design.getNetlist());
				EDIFTools.addDebugPort(debugNet, topCell, currDebugPort, debugCore);
			}else{
				String debugNetName = portPrefix + "_debug_net_" + probeCount;
				EDIFNet debugNet = EDIFTools.addDebugPortAndNet(debugNetName, topCell, currDebugPort, debugCore);
				String debugPortName = portPrefix + "_debug_port_" + probeCount;
				EDIFHierCellInst topInst = new EDIFHierCellInst("", design.getNetlist().getTopCellInst());
				EDIFTools.connectDebugProbe(debugNet, routedNetName, debugPortName, topInst, design.getNetlist(), instMap);				
			}

			alreadyConnected.add(routedNetName);
			probeCount++;
		}
		
		// TODO ASSUMPTION: All probed signals are on the same clock
		if(clockPin == null){
			MessageGenerator.briefError("WARNING: Couldn't definitely identify clock net for probe signals! Choosing clock with largest fanout...");
			int largestFanout = 0;
			for(Net clkNet : design.getNets()){
				if(clkNet.isClockNet() && clkNet.getFanOut() > largestFanout){
					clockPin = clkNet.getSource();
					largestFanout = clkNet.getFanOut();
				}				
			}
		}
		
		if(clockPin != null){
			String clkDebugNet = "clk_probe_net";
			String clkDebugPort = "clk_probe_port";
			EDIFPortInst clkPort = debugPorts.get("clk");
			edifClockNet = EDIFTools.addDebugPortAndNet(clkDebugNet, topCell, clkPort, debugCore);
			clockNet = clockPin.getNet();
			String routedClkNetName = clockNet.getName();
			EDIFHierCellInst topInst = new EDIFHierCellInst("", design.getNetlist().getTopCellInst());
			EDIFTools.connectDebugProbe(edifClockNet, routedClkNetName, clkDebugPort, topInst, design.getNetlist(), instMap);
			
			// Connect clock
			Port ilaClockPort = mi.getModule().getPort("clk");
			Net ilaClockNet = mi.getCorrespondingNet(ilaClockPort);
			design.movePinsToNewNetDeleteOldNet(ilaClockNet, clockNet, true);
			
		}else{
			MessageGenerator.briefErrorAndExit("ERROR: Couldn't find any clock nets in design. Exiting...");
		}
	}
	
	public void stitchDebugHubToILA(ModuleInst ilaCorePhysical, EDIFCellInst ilaCoreLogical, 
									ModuleInst dhCorePhysical, EDIFCellInst dhCoreLogical){
		String dhInput = "sl_oport0_i";
		String dhOutput = "sl_iport0_o";
		String ilaInput = "sl_iport0";
		String ilaOutput = "sl_oport0";
		int iportWidth = 17;
		int oportWidth = 37;
		
		EDIFCell topCell = design.getNetlist().getTopCell();
		Map<String,EDIFPortInst> ilaPorts = ilaCoreLogical.getPortInstMap();
		Map<String,EDIFPortInst> dhPorts = dhCoreLogical.getPortInstMap();
		
		for(int i=0; i < oportWidth; i++){
			String suffix = "["+i+"]";
			String dhPortName = dhOutput + suffix;
			String ilaPortName = ilaInput + suffix; 
			// Physical stitching
			Port ilaPort = ilaCorePhysical.getModule().getPort(ilaPortName);
			Net ilaNet = ilaCorePhysical.getCorrespondingNet(ilaPort);
			Port dhPort = dhCorePhysical.getModule().getPort(dhPortName); 
			Net dhNet = dhCorePhysical.getCorrespondingNet(dhPort);
			design.movePinsToNewNetDeleteOldNet(ilaNet, dhNet, false);
			for(SitePinInst p : dhNet.getPins()){
				if(p.isOutPin()) continue;
				pinsToRoute.add(p);
			}
		}
		EDIFTools.connectPortBus(topCell, dhCoreLogical, ilaCoreLogical, dhOutput, ilaInput, oportWidth, dhPorts, ilaPorts);
		
		for(int i=0; i < iportWidth; i++){
			String suffix = "["+i+"]";
			String dhPortName = dhInput + suffix;
			String ilaPortName = ilaOutput + suffix; 
			// Physical stitching
			Port ilaPort = ilaCorePhysical.getModule().getPort(ilaPortName);
			Net ilaNet = ilaCorePhysical.getCorrespondingNet(ilaPort);
			Port dhPort = dhCorePhysical.getModule().getPort(dhPortName); 
			Net dhNet = dhCorePhysical.getCorrespondingNet(dhPort);
			design.movePinsToNewNetDeleteOldNet(dhNet, ilaNet, false);
			for(SitePinInst p : ilaNet.getPins()){
				if(p.isOutPin()) continue;
				pinsToRoute.add(p);
			}
		}
		EDIFTools.connectPortBus(topCell, ilaCoreLogical, dhCoreLogical, ilaOutput, dhInput, iportWidth, ilaPorts, dhPorts);

		// Connect clock
		Port dhClockPort = dhCorePhysical.getModule().getPort("clk");
		Net dhClockNet = dhCorePhysical.getCorrespondingNet(dhClockPort);
		design.movePinsToNewNetDeleteOldNet(dhClockNet, clockNet, true);
		
		EDIFPortInst clkPortInst = dhPorts.get("clk");
		EDIFPortInst clkPort = new EDIFPortInst(clkPortInst.getPort(), edifClockNet,dhCoreLogical);
		edifClockNet.addPortInst(clkPort);
	}
	
	/**
	 * Meat of the instrumentation process.  This function will:
	 *   (1) Choose which pre-compiled block to load based on requested parameters
	 *   (2) Load the blocks(2)
	 *   (3) Stitch blocks into user design
	 *   (4) Place blocks in empty locations within user design
	 *   (5) Route updated nets
	 */
	public void instrumentDesign(){
		// Step 1 - Choose which pre-compiled block to load based on requested parameters
		// TODO - For now, we'll just load one of the ILA cores
		String ilaCoreFileName = null;
		String debugHubCoreFileName = "design_1_xsdbm_0_0_opt_routed_routed";
		if(netNames.size() > 32){
			ilaCoreFileName = "design_1_ila_1_0_opt_routed_routed";
		}else{
			ilaCoreFileName = "design_1_ila_0_0_opt_routed_routed";
		}
	
		// Step 2 - Load block(s)
		ModuleImpls ilaModuleImpls = BlockCreator.readStoredModule(DEBUG_CORE_PATH + "/" + ilaCoreFileName, null);
		ModuleImpls dhModuleImpls = BlockCreator.readStoredModule(DEBUG_CORE_PATH + "/" + debugHubCoreFileName, null);
		
		// Step 3 - Stitch blocks into user design (both physical and logical netlists)
		ModuleInst ilaCorePhysical = design.createModuleInst(ilaModuleImpls.getNetlist().getTopCellInst().getName(), ilaModuleImpls.get(0));
		EDIFCellInst ilaCoreLogical = design.addModuleInstNetlist(ilaCorePhysical, ilaModuleImpls.getNetlist());
		stitchProbesOnILA(ilaCorePhysical, ilaCoreLogical);
		
		ModuleInst dhCorePhysical = design.createModuleInst(dhModuleImpls.getNetlist().getTopCellInst().getName(), dhModuleImpls.get(0));
		EDIFCellInst dhCoreLogical = design.addModuleInstNetlist(dhCorePhysical, dhModuleImpls.getNetlist());
		stitchDebugHubToILA(ilaCorePhysical, ilaCoreLogical, dhCorePhysical, dhCoreLogical);
		
		EDIFTools.consolidateLibraries(design.getNetlist());
		
		// Step 4 - Place blocks in empty locations within the user design
		BlockPlacer placer = new BlockPlacer();
		placer.placeDesign(design,true);
		int unplacedInsts = 0;
		for(SiteInst si : design.getSiteInsts()){
			if(!si.isPlaced()){
				unplacedInsts++;
			}
		}
		if(unplacedInsts > 0){
			throw new RuntimeException("ERROR: " + unplacedInsts + "Unplaced instances!");
		}
		
		// Step 5 - Route updated nets
		Router router = new Router(design);
		router.routePinsReEntrant(pinsToRoute, true);
	}
	
	/**
	* Uses list of signals marked for debug to produce debug netlist (.ltx) file for debugging.
	*/
	public void createLTX(String name){
		// Path for template files.
		String templatePath = FileTools.getRapidWrightPath() + "/ltx_templates/";
		
		// 1. Create list of strings that will make up .ltx, get header into it.
		ArrayList<String> ltx_strings = FileTools.getLinesFromTextFile(templatePath + "header.ltx");

		// 2. Pull in and modify the probe template with list of signals marked for debug.
		ArrayList<String> probe_template = FileTools.getLinesFromTextFile(templatePath + "probe.ltx");
		
		/** 
		* Most lines will just be added right to the ltx; some need modding;
		* some need adding (additional nets). See assumptions...may need to add more special cases.
		*
		* Assumptions made:
		*	One ILA (created with "setup debug" in standard vivado flow)
		*	One probe port
		*	base_microblaze example design used
		*	We want the actual net name probed for debug, not the "parent" name. --> This assumption looks correct.
		**/
		for(String probeLine : probe_template){
			// Ensure "busType" is "net" if there's only one net, "bus" for >1 net.
			if(probeLine.trim().startsWith("<probe t")){
				if(netNames.size() > 1)
					ltx_strings.add("    <probe type=\"ila\" busType=\"bus\" source=\"netlist\" spec=\"ILA_V2_RT\">");
				else
					ltx_strings.add("    <probe type=\"ila\" busType=\"net\" source=\"netlist\" spec=\"ILA_V2_RT\">");
			}
			// Make sure port bit count is accurate on the PROBE_PORT_BIT_COUNT line.
			else if(probeLine.trim().startsWith("<Option Id=\"PROBE_PORT_BIT_")){
				ltx_strings.add("        <Option Id=\"PROBE_PORT_BIT_COUNT\" value=\"" + netNames.size() + "\"/>");
			}
			// Iterate through and add all signals marked for debug if we've reached the list of nets.
			else if(probeLine.trim().startsWith("<net n")){
				//System.out.println("Adding nets marked for debug to .ltx!");
				int count = netNames.size()-1;
				for(Entry<String,String> netNamePair : netNames.entrySet()){
					String netName = netNamePair.getKey().split("\\[")[0];
					ltx_strings.add("        <net name=\"" + netName + "[" + count + "]\"/>");
					count--;
				}
			}
			// The rest of the lines can be directly added from the template without modification.
			else{
				ltx_strings.add(probeLine);
			}
		}		
		
		// 3. Pull in and add the footer.
		ltx_strings.addAll(FileTools.getLinesFromTextFile(templatePath + "footer.ltx"));

		// 4. Write it out. I'm hoping this will end up in the same location as the xpn and edf.
		FileTools.writeLinesToTextFile(ltx_strings, name);
	}
	
	
	/**
	 * This is the main entry point for Vivado design debug instrumentation.
	 * @param args There are 3 arguments to this function: 
	 *   (0) Name of the file containing instrumentation details
	 *   (1) Name of the XPN file for the design
	 *   (2) Name of the EDIF file for the design
	 * 
	 *  The program, under normal operation will output two file names separated by a space
	 *  These are the instrumented XPN and EDIF files respectively.
	 */
	public static void main(String[] args) {
		if(args.length != 3){
			MessageGenerator.briefMessageAndExit
				("USAGE: <instrumentation file name> <XPN file name> <EDIF file name>");
		}
		
		long[] runtimes = new long[6];
		runtimes[0] = runtimes[1] = System.currentTimeMillis();
		
		String insFileName = args[0];
		String xpnFileName = args[1];
		String edfFileName = args[2];
		String xpnOutFileName = xpnFileName.replace(".xpn", "_ins.xpn");
		String edfOutFileName = edfFileName.replace(".edf", "_ins.edf");
		String ltxOutFileName = xpnOutFileName.replace(".xpn", "_debug_netlist.ltx");
		
		// Check that files exist
		FileTools.errorIfFileDoesNotExist(insFileName);
		FileTools.errorIfFileDoesNotExist(xpnFileName);
		FileTools.errorIfFileDoesNotExist(edfFileName);
		
		// Load files
		DesignInstrumentor d = new DesignInstrumentor();
		d.loadInstrumentationDetailsFile(insFileName);
		
		runtimes[1] = System.currentTimeMillis() - runtimes[1];
		runtimes[2] = System.currentTimeMillis();
		
		//d.design = Design.loadDesign(edfFileName, xpnFileName);
		
		runtimes[2] = System.currentTimeMillis() - runtimes[2];
		runtimes[3] = System.currentTimeMillis();
		
		d.instrumentDesign();
		d.createLTX(ltxOutFileName);	
		
		runtimes[3] = System.currentTimeMillis() - runtimes[3];
		runtimes[4] = System.currentTimeMillis();
		
		// Write instrumented design out
		//d.design.saveDesign(edfOutFileName, xpnOutFileName);
		
		// Print out names to communicate back to the Tcl script
		System.out.println("OUTPUT_DESIGN: " + edfOutFileName + " " + xpnOutFileName);
		
		runtimes[4] = System.currentTimeMillis() - runtimes[4];
		runtimes[0] = System.currentTimeMillis() - runtimes[0];

		System.out.println();
		System.out.println("----------------- DesignInstrumentor Runtime --------------------");
		System.out.printf("Loading Instrumentation Details : %8.3fs \n", runtimes[1]/1000.0);
		System.out.printf("            Loading Design Time : %8.3fs \n", runtimes[2]/1000.0);
		System.out.printf("           Instrumentation Time : %8.3fs \n", runtimes[3]/1000.0);
		System.out.printf("             Saving Design Time : %8.3fs \n", runtimes[4]/1000.0);
	    System.out.println("-----------------------------------------------------------------");
		System.out.printf("                  Total Runtime : %8.3fs \n", runtimes[0]/1000.0);
		
	}
}
