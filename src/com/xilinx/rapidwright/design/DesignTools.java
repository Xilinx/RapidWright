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
package com.xilinx.rapidwright.design;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.blocks.UtilizationType;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.StringTools;
import com.xilinx.rapidwright.util.Utils;

/**
 * A collection of methods to operate on {@link Design} objects.
 * 
 * Created on: Dec 7, 2015
 */
public class DesignTools {

	private static int uniqueBlackBoxCount = 0;

	/**
	 * Tries to identify the clock pin source for the given user signal output by
	 * tracing back to a FF within a SLICE.  TODO - This method is not very robust.
	 * @param netSource The site pin output from which to start the clock search
	 * @return The source clock pin for the clock net or null if unable to determine one.
	 */
	public static SitePinInst identifyClockSource(SitePinInst netSource){
		if(!netSource.isOutPin()) return null;
		BELPin p = netSource.getBELPin();
		if(p == null) return null;
		BELPin src = p.getSiteConns().get(0);
		if(src.getBELName().contains("FF")){
			BELPin clk = src.getBEL().getPin("CLK");
			Net n = netSource.getSiteInst().getNetFromSiteWire(clk.getSiteWireName());
			if(n == null) return null;
			return n.getSource();
		}
		return null;
	}

	public static Net getClockDomain(Design d, String edifNet){
		// TODO - WIP
		String tokens[] = edifNet.split("/");
		EDIFCellInst curr = d.getNetlist().getTopCellInst();
		for(int i=0; i < tokens.length; i++){
			if(i == tokens.length-1){
				for(EDIFPortInst port : curr.getPortInsts()){
					System.out.println(port.getPort().getName());
					if(port.getNet().getName().equals(tokens[i])){
						
					}
				}
			}else{
				curr = curr.getCellType().getCellInst(tokens[i]);
			}
			
		}
		
		return null;
	}
	
	/**
	 * Tries to determine the driving cell within the site from the output site pin.
	 * @param netSource The output site pin from which to start searching
	 * @return The corresponding driving cell, or null if none could be found.
	 */
	public static Cell getDrivingCell(SitePinInst netSource){
		BELPin src = getDrivingBELPin(netSource);
		Cell c = netSource.getSiteInst().getCell(src.getBELName());
		return c;
	}
	
	/**
	 * Gets the originating driving element pin that corresponds to the given site pin. 
	 * @param netSource The source pin 
	 * @return The corresponding element pin or null if none could be found.
	 */
	public static BELPin getDrivingBELPin(SitePinInst netSource){
		if(!netSource.isOutPin()) return null;
		return getDrivingBELPin(netSource.getBELPin());
	}

	/**
	 * Gets the driving element pin of either a site port output or an element input pin.
	 * @param elementPin The element pin of interest.  Cannot be a element output pin. 
	 * @return The source element pin within the site for the given element pin.
	 */
	public static BELPin getDrivingBELPin(BELPin elementPin){
		if(elementPin == null) return null;
		if(elementPin.isOutput() && elementPin.getBEL().getBELClass() != BELClass.PORT) return null;
		return elementPin.getSiteConns().get(0);
	}
	
	/**
	 * Gets the driven element pins of either a site port input or an element output pin.
	 * @param elementPin The element pin of interest. Cannot be an element input pin.
	 * @return A list of sink element pins within the site for the given element pin.
	 */
	public static ArrayList<BELPin> getDrivenBELPins(BELPin elementPin){
		if(elementPin == null) return null;
		if(elementPin.isInput() && elementPin.getBEL().getBELClass() == BELClass.PORT) return null;
		return elementPin.getSiteConns();
	}
	
	/**
	 * Uses SitePIP information in the site instance to determine the driving input of the RBEL element
	 * output pin.  There should only be one input that can affect the output and this method
	 * returns that input pin.
	 * @param outputPin The output pin on the RBEL element of interest.
	 * @param inst The corresponding site instance where the pin resides.
	 * @return The driving input element pin on the RBEL, or null if none could be found.
	 */
	public static BELPin getCorrespondingRBELInputPin(BELPin outputPin, SiteInst inst){
		BEL element = outputPin.getBEL();
		if(element.getHighestInputIndex() == 0){
			return element.getPin(0);
		}
		SitePIP pip = inst.getUsedSitePIP(outputPin);
		if(pip == null) return null;
		return pip.getInputPin();
	}
	
	/**
	 * Returns the element's output pin corresponding that is being driven by the provided input pin.
	 * @param inputPin The input pin of interest
	 * @param inst The site instance corresponding to the element of interest
	 * @return The element's output pin that is driven by the provided input pin.
	 */
	public static BELPin getCorrespondingRBELOutputPin(BELPin inputPin, SiteInst inst){
		int idx = inputPin.getBEL().getHighestInputIndex() + 1;
		if(inputPin.getBEL().getPins().length > idx + 1){
			throw new RuntimeException("ERROR: False assumption, this routing BEL has more than one output: " +
					inputPin.getBELName());
		}
		return inputPin.getBEL().getPin(idx);
	}
	
	/**
	 * TODO - Work in progress
	 * @param outputPin
	 * @param inst
	 * @return
	 */
	public static ArrayList<BELPin> getCorrespondingBELInputPins(BELPin outputPin, SiteInst inst){
		ArrayList<BELPin> inputs = new ArrayList<BELPin>();
		BEL element = outputPin.getBEL();
		// Check if element only has one input
		if(element.getHighestInputIndex() == 0){
			inputs.add(element.getPin(0));
			return inputs;
		}
		
		Cell cell = inst.getCell(element.getName());
		/*if(!element.getName().equals("BUFCE")){
			for(Entry<String,Property> entry : cell.getEdifCellInst().getPropertyList().entrySet()){
				System.out.println(entry.getKey() + ": " + entry.getValue().getName());
			}// TODO			
		}*/
		switch(element.getName()){
			case "BUFCE":{
				inputs.add(element.getPin("I"));
				break;
			}
			
			case "A5LUT":
			case "B5LUT":
			case "C5LUT":
			case "D5LUT":
			case "E5LUT":
			case "F5LUT":
			case "G5LUT":
			case "H5LUT":
			case "A6LUT":
			case "B6LUT":
			case "C6LUT":
			case "D6LUT":
			case "E6LUT":
			case "F6LUT":
			case "G6LUT":
			case "H6LUT":
			case "CARRY8":{				
				for(int i=0; i <= element.getHighestInputIndex(); i++){
					BELPin pin = element.getPin(i);
					String siteWireName = pin.getSiteWireName();
					Net net = inst.getNetFromSiteWire(siteWireName);
					if(net == null) continue;
					if(net.isStaticNet()) continue;
					if(!cell.getPinMappingsP2L().containsKey(pin.getName())) continue;
					inputs.add(pin);
				}
				break;
			}
			default:{
				throw new RuntimeException("ERROR: Problem tracing through " + element.getName() + " in " + inst.getName());
			}
				
		}
		
		
		return inputs;
	}
	
	/**
	 * TODO - Work in progress
	 * @param inputPin
	 * @param inst
	 * @return
	 */
	public static ArrayList<BELPin> getCorrespondingBELOutputPins(BELPin inputPin, SiteInst inst){
		ArrayList<BELPin> outputs = new ArrayList<BELPin>();
		BEL element = inputPin.getBEL();
		
		Cell cell = inst.getCell(element.getName());
		if(cell == null){
			return outputs;
		}
		for(int i=element.getHighestInputIndex()+1; i < element.getPins().length; i++){
			BELPin pin = element.getPin(i);
			String siteWireName = pin.getSiteWireName();
			Net net = inst.getNetFromSiteWire(siteWireName);
			if(net == null) continue;
			if(net.isStaticNet()) continue;
			if(net.getName().equals(Net.USED_NET)) continue;
			if(!cell.getPinMappingsP2L().containsKey(pin.getName())) continue;
			outputs.add(pin);
		}
		
		return outputs;
	}
	
	private static HashSet<String> stopElements;
	private static HashSet<String> lutElements;
	private static HashSet<String> regElements;
	static {
		stopElements = new HashSet<String>();
		// CONFIG
		stopElements.add("BSCAN1");
		stopElements.add("BSCAN2");
		stopElements.add("BSCAN3");
		stopElements.add("BSCAN4");
		stopElements.add("DCIRESET");
		stopElements.add("DNAPORT");
		stopElements.add("EFUSE_USR");
		stopElements.add("FRAME_ECC");
		stopElements.add("ICAP_BOT");
		stopElements.add("ICAP_TOP");
		stopElements.add("MASTER_JTAG");
		stopElements.add("STARTUP");
		stopElements.add("USR_ACCESS");
		
		// BRAM
		stopElements.add("RAMBFIFO36E2");
		
		// IOB
		stopElements.add("IBUFCTRL");
		stopElements.add("INBUF");
		stopElements.add("OUTBUF");
		stopElements.add("PADOUT");
		
		// MMCM
		stopElements.add("MMCME3_ADV");
		
		// DSP
		stopElements.add("DSP_PREADD_DATA");
		stopElements.add("DSP_A_B_DATA");
		stopElements.add("DSP_C_DATA");
		stopElements.add("DSP_OUTPUT");
		
		lutElements = new HashSet<String>();
		regElements = new HashSet<String>();

		for(String letter : Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H")){
			regElements.add(letter +"FF");
			regElements.add(letter +"FF2");
			for(String size : Arrays.asList("5", "6")){
				lutElements.add(letter + size + "LUT");
			}
		}		
	}
	
	public static boolean isBELALut(String elementName){
		return lutElements.contains(elementName);
	}
	
	public static boolean isBELAReg(String elementName){
		return regElements.contains(elementName);
	}
	
	public static boolean isPinStateBEL(BELPin pin){
		BEL element = pin.getBEL();
		if(stopElements.contains(element.getName())) return true;
		
		// TODO - This only finds flops that exist in SLICEs. Need IOs/BRAM/DSP/etc
		return element.getName().contains("FF");
	}
	
	public static int invertBit(int i, int col){
		long tmpRow = (long)i;
		long tmpCol = 1 << col;
		tmpRow = tmpRow ^ tmpCol;
		return (int)tmpRow;
	}
	
	public static long getCurrVal(long lutValue, int i){
		long out = 0;
		long tmpVal = 1 << i;
		out = lutValue & tmpVal;
		return out;
	}
	
	public static long moveValToNewRow(long lutValue, int i, int newRow){
		long out = 0;
		int moveIndex = Math.abs(i-newRow);
		if (i > newRow){
			out = lutValue >> moveIndex;
		}else {
			out = lutValue << moveIndex;
		}
		return out;
	}
	
	public static int getInvertCol(String logicalPinName){
		int result = -1;
		switch (logicalPinName) {
			case "0" : result = 0;
			case "1" : result = 1;
			case "2" : result = 2;
			case "3" : result = 3;
			case "4" : result = 4;
			case "5" : result = 5;
		}
		return result;
	}
	
	public static String invertLutInput (Cell lut, String physicalPinName){
		String lutValue = lut.getEDIFCellInst().getProperty("INIT").getValue();
		//String lutValue = "4'hE";
		String numLutRowsStr = lutValue.substring(0, lutValue.indexOf("'"));
		String hexValueStr = lutValue.substring(lutValue.indexOf("h")+1, lutValue.length());
		//long oldVal = Long.parseLong(hexValueStr);
		long oldVal = new BigInteger(hexValueStr, 16).longValue();
		int numLutRows = Integer.parseInt(numLutRowsStr);
		int numInput = (int)(Math.log(numLutRows)/Math.log(2));
		String logicalPinName = lut.getPinMappingsP2L().get(physicalPinName);
		int invertCol = getInvertCol(logicalPinName.substring(logicalPinName.length()-1));
		if (invertCol == -1){
			System.err.println("Inverted Column is -1 is Function DesignTools.invertLutInput");
		}
		long outHex = 0;
		
		for (int i = 0; i < 1<<numInput; i++){
			int newRow = invertBit(i, invertCol);
			System.out.println("old_Row = " + i + " new_Row = " + newRow);
			long currVal = getCurrVal(oldVal, i);
			currVal = moveValToNewRow(currVal, i, newRow);
			outHex |= currVal;
		}
		String hexOutput = numLutRowsStr + "'h";
		hexOutput = hexOutput + Long.toHexString(outHex);
		System.out.println("output INIT = "+ hexOutput);
		//System.out.println("output = " + outHex);
		return hexOutput;
	}
	
	/**
	 * Determines if all the pins connected to this net connect to only LUTs
	 * @return True if all pins connect only to LUTs, false otherwise.
	 */
	public static boolean areAllPinsConnectedToALUT(Net n){
		for(SitePinInst p : n.getPins()){
			Set<Cell> connectedCells = getConnectedCells(p);
			if(connectedCells == null || connectedCells.size() == 0) return false;
			for(Cell lut : connectedCells){
				if(!lut.getType().contains("LUT")){
					return false;
				}
			}
		}
		return true;
	}
	
	public static void optimizeLUT1Inverters(Design design){
		ArrayList<Cell> lut1Cells = new ArrayList<Cell>();
		for(Cell c : design.getCells()){
			if(c.getType().equals("LUT1")){
				lut1Cells.add(c);
			}
		}
		
		for(Cell c : lut1Cells){
			
			// 1. Determine if this LUT can be merged into its source or sink
			String lutInputSiteWire = c.getSiteWireNameFromLogicalPin("I0");
			Net inputNet = c.getSiteInst().getNetFromSiteWire(lutInputSiteWire);
			String lutOutputSiteWire = c.getSiteWireNameFromLogicalPin("O");
			Net outputNet = c.getSiteInst().getNetFromSiteWire(lutOutputSiteWire);

			if(inputNet == null || outputNet == null || inputNet.getPins().size() == 0 || outputNet.getPins().size() == 0) continue;
			
			SitePinInst lut1InputPin = null;
			for(SitePinInst p : inputNet.getPins()){
				if(p.isOutPin()) continue;
				if(p.getName().equals(lutInputSiteWire)){
					lut1InputPin = p;
					break;
				}
			}
			//invertLutInput(c, lutInputSiteWire);
			boolean pushInverterForward = true;
			if(areAllPinsConnectedToALUT(outputNet)){
				pushInverterForward = true;
			}else if(areAllPinsConnectedToALUT(inputNet)){
				// We can push the inverter backwards
				// TODO - I don't think this will happen for now.
				pushInverterForward = false;
			}else{
				// We can't do it
				continue;
			}			
			
			// 2. Modify LUT equation of neighboring LUT TODO TODO TODO
			if(pushInverterForward){
				
			}else{
				
			}

			// 3. Remove LUT1 from logical netlist
			EDIFPortInst toRemove = null;
			for(EDIFPortInst portInst : inputNet.getLogicalNet().getPortInsts()){
				if(portInst.getCellInst().equals(c.getEDIFCellInst())){
					toRemove = portInst;
					break;
				}
			}
			if(toRemove != null) inputNet.getLogicalNet().removePortInst(toRemove);
			for(EDIFPortInst portInst : outputNet.getLogicalNet().getPortInsts()){
				if(portInst.getCellInst() != null && portInst.getCellInst().equals(c.getEDIFCellInst())) continue;
				inputNet.getLogicalNet().addPortInst(portInst);
			}
			outputNet.getLogicalNet().getParentCell().removeNet(outputNet.getLogicalNet());
			c.getEDIFCellInst().getParentCell().removeCellInst(c.getEDIFCellInst());
			
			// 4. Remove the LUT1, reconnect nets
			design.removeCell(c);
			inputNet.removePin(lut1InputPin);
			outputNet.removeSource();
			design.movePinsToNewNetDeleteOldNet(outputNet, inputNet, false);
			
			// 5. Detach module instance if it exists
			c.getSiteInst().detachFromModule();
		}
			
		
	}

	/**
	 * Creates a new pin on a site connected to the cell pin and also adds it to the 
	 * provided net. Updates both logical and physical netlist representations. Note: If the 
	 * site pin being added is an output it will displace any existing output source on
	 *  the given net.
	 * @param cell The placed source or sink BEL instance from which to trace to a site pin.  
	 * Must have a direct connection to a site pin. 
	 * @param cellPinName Name of the logical pin name on the cell.
	 * @param net The net to add the pin to.
	 * @return The newly created pin that has been added to net
	 */
	public static SitePinInst createPinAndAddToNet(Cell cell, String cellPinName, Net net){
		// Cell must be placed
		if(cell.getSiteInst() == null || cell.getSiteInst().isPlaced() == null) {
			throw new RuntimeException("ERROR: Cannot create pin for cell " + cell.getName() + " that is unplaced.");
		}
		// Get the cell pin
		BELPin cellPin = cell.getBEL().getPin(cell.getPhysicalPinMapping(cellPinName));
		if(cellPin == null){
			throw new RuntimeException("ERROR: Couldn't find " + cellPinName + " on element " + cell.getBELName() + ".");
		}
		// Get the connected site pin from cell pin 
		String sitePinName = cellPin.getConnectedSitePinName();
		if(sitePinName == null){
			sitePinName = cell.getCorrespondingSitePinName(cellPinName);
		}
		if(sitePinName == null){
			throw new RuntimeException("ERROR: Couldn't find corresponding site pin for element pin " + cellPin + ".");
		}
		if(!cell.getSiteInst().getSite().hasPin(sitePinName)){
			throw new RuntimeException("ERROR: Site pin mismatch.");
		}
		// Create the pin
		SitePinInst sitePin = new SitePinInst(cellPin.isOutput(), sitePinName, cell.getSiteInst());
		if(sitePin.isOutPin()){
			SitePinInst oldSource = net.replaceSource(sitePin);
			if(oldSource != null)
				oldSource.detachSiteInst();
		}else{
			net.addPin(sitePin);			
		}
		
		EDIFNetlist e = cell.getSiteInst().getDesign().getNetlist();
		EDIFNet logicalNet = net.getLogicalNet() == null ? e.getTopCell().getNet(net.getName()) : net.getLogicalNet();
		if(logicalNet == null){
			throw new RuntimeException("ERROR: Unable to determine logical net for physical net: " + net.getName());
		}
		
		// Remove logical sources
		if(sitePin.isOutPin()){
			boolean includeTopPorts = true;
			Collection<EDIFPortInst> sources = logicalNet.getSourcePortInsts(includeTopPorts);
			for(EDIFPortInst epr : sources){
				logicalNet.removePortInst(epr);
			}
		}
		
		if(cell.getEDIFCellInst() == null){
			throw new RuntimeException("ERROR: Couldn't identify logical cell from cell " + cell.getName());
		}
		EDIFCellInst eCellInst = cell.getEDIFCellInst();
		EDIFPort ePort = eCellInst.getPort(cellPinName);
		
		EDIFPortInst newPortInst = new EDIFPortInst(ePort, logicalNet, eCellInst);
		logicalNet.addPortInst(newPortInst);
		
		return sitePin;
	}
	
	public static HashMap<UtilizationType,Integer> calculateUtilization(Design d){
		HashMap<UtilizationType,Integer> map = new HashMap<UtilizationType,Integer>();
		
		for(UtilizationType ut : UtilizationType.values()){
			map.put(ut, 0);
		}
		
		for(SiteInst si : d.getSiteInsts()){
			SiteTypeEnum s = si.getSite().getSiteTypeEnum();
			if(Utils.isSLICE(si)){
				incrementUtilType(map, UtilizationType.CLBS);
				if(s == SiteTypeEnum.SLICEL){
					incrementUtilType(map, UtilizationType.CLBLS);
				}else if(s == SiteTypeEnum.SLICEM){
					incrementUtilType(map, UtilizationType.CLBMS);
				}
			} else if(Utils.isDSP(si)){
				incrementUtilType(map, UtilizationType.DSPS);
			} else if(Utils.isBRAM(si)){
				if(s == SiteTypeEnum.RAMBFIFO36){
					incrementUtilType(map, UtilizationType.RAMB36S_FIFOS);
				}else if(s == SiteTypeEnum.RAMB181 || s == SiteTypeEnum.RAMBFIFO18){
					incrementUtilType(map, UtilizationType.RAMB18S);
				}
			} else if(Utils.isURAM(si)){
				incrementUtilType(map, UtilizationType.URAMS);
			}
			for(Cell c : si.getCells()){
				/*
				CLB_LUTS("CLB LUTs"),
				LUTS_AS_LOGIC("LUTs as Logic"),
				LUTS_AS_MEMORY("LUTs as Memory"),
				CLB_REGS("CLB Regs"),
				REGS_AS_FFS("Regs as FF"),
				REGS_AS_LATCHES("Regs as Latch"),
				CARRY8S("CARRY8s"),
				F7_MUXES("F7 Muxes"),
				F8_MUXES("F8 Muxes"),
				F9_MUXES("F9 Muxes"),
				CLBS("CLBs"),
				CLBLS("CLBLs"),
				CLBMS("CLBMs"),
				LUT_FF_PAIRS("Lut/FF Pairs"),
				RAMB36S_FIFOS("RAMB36s/FIFOs"),
				RAMB18S("RAMB18s"),
				DSPS("DSPs");
				*/
 
				if(isBELAReg(c.getBELName())){
					incrementUtilType(map, UtilizationType.CLB_REGS);
					incrementUtilType(map, UtilizationType.REGS_AS_FFS);
				} else if(c.getBELName().contains("CARRY")){
					incrementUtilType(map, UtilizationType.CARRY8S);
				}
				
			}
			for(String letter : Arrays.asList("A", "B", "C", "D", "E", "F", "G", "H")){
				Cell c5 = si.getCell(letter +"5LUT");
				Cell c6 = si.getCell(letter +"6LUT");
				if(c5 != null || c6 != null){
					incrementUtilType(map, UtilizationType.CLB_LUTS);
					
					if(isCellLutMemory(c5) || isCellLutMemory(c6)){
						incrementUtilType(map, UtilizationType.LUTS_AS_MEMORY);
					}else {
						incrementUtilType(map, UtilizationType.LUTS_AS_LOGIC);
					}
				}
			}		
		}
		
		
		
		return map;
	}
	
	private static boolean isCellLutMemory(Cell c){
		if (c == null) return false;
		if (c.getType().contains("SRL") || c.getType().contains("RAM")) return true;
		return false;
	}
	
	private static void incrementUtilType(HashMap<UtilizationType,Integer> map, UtilizationType ut){
		Integer val = map.get(ut);
		val++;
		map.put(ut, val);
	}
	
	/**
	 * Creates a verilog wrapper file for this design by examining the 
	 * top level netlist.
	 * @param fileName Name of the desired verilog file.
	 */
	public static void writeVerilogStub(Design design, String fileName){
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(fileName);
			RTLStubGenerator.createVerilogStub(design, fos);
			fos.close();
		} catch (IOException e) {
			MessageGenerator.briefError("ERROR: Failed to write verilog stub " + fileName);
			e.printStackTrace();
		} 	
	}
	
	/**
	 * Creates two CSV files based on this design, one for instances and one 
	 * for nets.
	 * @param fileName
	 */
	public static void toCSV(String fileName, Design design){
		String nl = System.getProperty("line.separator");
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(fileName + ".instances.csv"));
			bw.write("\"Name\",\"Type\",\"Site\",\"Tile\",\"#Pins\"" + nl);
			
			for(SiteInst i : design.getSiteInsts()){
				bw.write("\"" + i.getName() + "\",\"" + 
								i.getSiteTypeEnum() + "\",\"" + 
								i.getSiteName() + "\",\"" + 
								i.getTile()+ "\",\"" +
								i.getSitePinInstMap().size()+ "\"" + nl);
			}
			bw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(fileName + ".nets.csv"));
			bw.write("\"Name\",\"Type\",\"Fanout\"" + nl);
			
			for(Net n : design.getNets()){
				bw.write("\"" + n.getName() + "\",\"" + 
								n.getType() + "\",\"" + 
								n.getFanOut()+ "\"" + nl);
			}
			bw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	/**
	 * Demonstrates a rudimentary path expansion for finding a routing path in the 
	 * interconnect.
	 * @param start Desired start node
	 * @param end Desired end node
	 * @return A list of PIPs that configure a path from start to end nodes, or null if a path could not be found.
	 */
	public static List<PIP> findRoutingPath(Node start, Node end){
		return findRoutingPath(new RouteNode(start), new RouteNode(end));
	}
	
	/**
	 * Demonstrates a rudimentary path expansion for finding a routing path in the 
	 * interconnect.
	 * @param start Desired start node
	 * @param end Desired end node
	 * @return A list of PIPs that configure a path from start to end nodes, or null if a path could not be found.
	 */
	public static List<PIP> findRoutingPath(RouteNode start, RouteNode end){
		PriorityQueue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
			public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
		q.add(start);
		HashSet<Wire> visited = new HashSet<>();
		visited.add(new Wire(start.getTile(), start.getWire()));
		
		while(!q.isEmpty()){
			RouteNode curr = q.remove();
			if(curr.equals(end)){
				return curr.getPIPsBackToSource();
			}
			if(visited.size() > 100000) return null;
			for(Wire w : curr.getConnections()){
				if(visited.contains(w)) continue;
				visited.add(w);
				RouteNode rn = new RouteNode(w,curr);
				rn.setCost((rn.getManhattanDistance(end) << 1) + rn.getLevel());
				q.add(rn);
			}
		}
		return null;
	}
	
	/**
	 * Examines a site wire in a populated site inst for all the connected BELPins for
	 * cells occupying those BELs.  It attempts to lookup the net attached to the cell pin
	 * in order to find the hierarchical parent net name of the net and returns its name.
	 * @param inst The site instance where the site wire in question resides.
	 * @param siteWire The site wire index in the site where the site inst resides.
	 * @return The hierarchical parent net name using the site wire or null if none could be found.
	 */
	public static String resolveNetNameFromSiteWire(SiteInst inst, int siteWire){
		String parentNetName = null;
		Map<String,String> parentNetMap = inst.getDesign().getNetlist().getParentNetMapNames();
		BELPin[] pins = inst.getSite().getBELPins(siteWire);
		for(BELPin pin : pins){
			if(pin.isSitePort()) continue;
			Cell c = inst.getCell(pin.getBELName());
			if(c == null || c.getEDIFCellInst() == null) {
				Net currNet = inst.getNetFromSiteWire(pin.getSiteWireName());
				if(currNet == null) {
					continue;
				} else {
					return parentNetMap.get(currNet.getName());
				}
			} 
			String logPinName = c.getLogicalPinMapping(pin.getName());
			EDIFPortInst portInst = c.getEDIFCellInst().getPortInst(logPinName);
			if(portInst == null) continue;
			EDIFNet net =  portInst.getNet();
			String netName = c.getParentHierarchicalInstName() + EDIFTools.EDIF_HIER_SEP + net.getName(); 
			parentNetName = parentNetMap.get(netName);
		}
		return parentNetName;
	}

	/**
	 * NOTE: This method is not fully tested.  
	 * Populates a black box in a netlist with the provided design. This method
	 * most closely resembles the Vivado command 'read_checkpoint -cell <cell name> <DCP Name>. 
	 * @param design The top level design
	 * @param hierarchicalCellName Name of the black box in the design netlist.
	 * @param cell The 'guts' to be inserted into the black box
	 */
	public static void populateBlackBox(Design design, String hierarchicalCellName, Design cell){
		EDIFNetlist netlist = design.getNetlist();
		
		// Populate Logical Netlist into cell
		EDIFCellInst inst = netlist.getCellInstFromHierName(hierarchicalCellName);
		if(!inst.isBlackBox()) {
			System.err.println("ERROR: The cell instance " + hierarchicalCellName + " is not a black box.");
			return;
		}
		inst.getCellType().getLibrary().removeCell(inst.getCellType());
		netlist.migrateCellAndSubCells(cell.getTopEDIFCell(), true);
		inst.updateCellType(cell.getTopEDIFCell());
		netlist.removeUnusedCellsFromAllWorkLibraries();
		
		// Add placement information
		// We need to prefix all cell and net names with the hierarchicalCellName as a prefix
		for(SiteInst si : cell.getSiteInsts()){
			for(Cell c : new ArrayList<Cell>(si.getCells())){
				c.updateName(hierarchicalCellName + "/" + c.getName());
				if(!c.isRoutethru())
					design.addCell(c);
			}
			design.addSiteInst(si);
		}
		
		// Add routing information
		for(Net net : cell.getNets()){
			if(net.getName().equals(Net.USED_NET)) continue;
			if(net.isStaticNet()){
				Net staticNet = design.getStaticNet(net.getType());
				staticNet.addPins((ArrayList<SitePinInst>)net.getPins());
				HashSet<PIP> uniquePIPs = new HashSet<PIP>(net.getPIPs());
				uniquePIPs.addAll(staticNet.getPIPs());
				staticNet.setPIPs(uniquePIPs);
			}else{
				net.updateName(hierarchicalCellName + "/" + net.getName());
				design.addNet(net);				
			}
		}

		// Rectify boundary nets 
		netlist.resetParentNetMap();
		
		postBlackBoxCleanup(hierarchicalCellName, design);
		
		List<String> encryptedCells = cell.getNetlist().getEncryptedCells();
		if(encryptedCells != null && encryptedCells.size() > 0) {
			design.getNetlist().addEncryptedCells(encryptedCells);
		}
	}

	/**
	 * Attempts to rename boundary nets around the previous blackbox to follow naming convention 
	 * (net is named after source).
	 * @param hierCellName The hierarchical cell instance that was previously a black box
	 * @param design The current design.
	 */
	public static void postBlackBoxCleanup(String hierCellName, Design design) {		
		EDIFNetlist netlist = design.getNetlist();
		EDIFHierCellInst inst = netlist.getHierCellInstFromName(hierCellName);
		final EDIFHierCellInst parentInst = inst.getParent();
		
		// for each port on the black box, 
		//   iterate over all the nets and regularize on the proper net name for the physical
		//   net.  Put all physical pins on the correct physical net once the black box has been
		//   updated.
		for(EDIFPortInst portInst : inst.getInst().getPortInstMap().values()) {
			EDIFNet net = portInst.getNet();
			EDIFHierNet netName = new EDIFHierNet(parentInst, net);
			EDIFHierNet parentNetName = netlist.getParentNet(netName);
			Net parentNet = design.getNet(parentNetName.getHierarchicalNetName());
			if(parentNet == null) {
				if(parentNetName == null) parentNetName = netName;
				parentNet = new Net(parentNetName.getHierarchicalInstName(),parentNetName.getNet());
			}
			for(EDIFHierNet netAlias : netlist.getNetAliases(netName)) {
				if(parentNet.equals(netAlias)) continue;
				Net alias = design.getNet(netAlias.getHierarchicalNetName());
				if(alias != null) {
					// Move this non-parent net physical information to the parent
					for(SiteInst si : alias.getSiteInsts()) {
						if(si.getNetList().remove(alias)) {
							si.getNetList().add(parentNet);
						}
						Set<String> siteWires = si.getSiteWiresFromNet(alias);
						if(siteWires != null) {
							for(String siteWire : new ArrayList<>(siteWires)) {
								BELPin belPin = si.getSite().getBELPins(siteWire)[0];
								si.unrouteIntraSiteNet(belPin, belPin);
								si.routeIntraSiteNet(parentNet, belPin, belPin);
							}							
						}
					}
					for(SitePinInst pin : new ArrayList<SitePinInst>(alias.getPins())) {
						alias.removePin(pin);
						parentNet.addPin(pin);
					}
					alias.unroute();
				}
			}
			parentNet.unroute();
		}
	}
	
	/**
	 * Creates a map from Node to a list of PIPs for a given list of PIPs 
	 * (likely from the routing of a net).
	 * @param route The list of PIPs to create the map from.
	 * @return The map of all involved nodes to their respectively connected PIPs.
	 */
	public static Map<Node, ArrayList<PIP>> getNodePIPMap(List<PIP> route){
		Map<Node,ArrayList<PIP>> conns = new HashMap<>();
		// Create a map from nodes to PIPs
		for(PIP pip : route){
			for(int wireIndex : new int[]{pip.getStartWireIndex(), pip.getEndWireIndex()}){
				Node curr = Node.getNode(pip.getTile(), wireIndex);
				ArrayList<PIP> pips = conns.get(curr);
				if(pips == null){
					pips = new ArrayList<>();
					conns.put(curr, pips);
				}
				pips.add(pip);
			}	
		}
		return conns;
	}
	
	/**
	 * Examines the routing of a net and will remove all parts of the routing
	 * that connect to the provided node.  This is most useful when attempting to
	 * unroute parts of a static (VCC/GND) net that have multiple sources.  
	 * @param net The net with potential disjoint routing trees
	 * @param node Node belonging to the routing tree to remove.
	 * @return True if PIPs were removed, false otherwise
	 */
	public static boolean removeConnectedRouting(Net net, Node node){
		HashSet<PIP> toRemove = new HashSet<>();
		Map<Node,ArrayList<PIP>> conns = getNodePIPMap(net.getPIPs());

		// Traverse the connected set of PIPs starting from the node
		Queue<Node> q = new LinkedList<>();
		q.add(node);
		while(!q.isEmpty()){
			Node curr = q.poll();
			ArrayList<PIP> pips = conns.get(curr);
			if(pips == null) continue;
			for(PIP p : pips){
				// Be careful to detect a cycle
				if(!toRemove.contains(p)){
					toRemove.add(p);
					Node startNode = p.getStartNode(); 
					q.add(curr.equals(startNode) ? startNode : p.getEndNode());
				}
			}
		}
		
		if(toRemove.size() == 0) return false;
		
		// Update net with new PIPs
		ArrayList<PIP> keep = new ArrayList<>();
		for(PIP p : net.getPIPs()){
			if(toRemove.contains(p)) continue;
			keep.add(p);
		}
		net.setPIPs(keep);
		
		return true;
	}

	/**
	 * Turns the cell named hierarchicalCellName into a blackbox and removes any
	 * associated placement and routing information associated with that instance. In Vivado,
	 * this can be accomplished by running: (1) 'update_design -cells <name> -black_box' or (2)
	 * by deleting all of the cells and nets insides of a cell instance.  Method (2) is
	 * more likely to have complications.
	 * @param d The current design
	 * @param hierarchicalCellName The name of the hierarchical cell to become a black box.
	 */
	public static void makeBlackBox(Design d, String hierarchicalCellName) {
		makeBlackBox(d, d.getNetlist().getHierCellInstFromName(hierarchicalCellName));
	}
	/**
	 * Turns the cell named hierarchicalCell into a blackbox and removes any
	 * associated placement and routing information associated with that instance. In Vivado,
	 * this can be accomplished by running: (1) 'update_design -cells <name> -black_box' or (2)
	 * by deleting all of the cells and nets insides of a cell instance.  Method (2) is 
	 * more likely to have complications.  
	 * @param d The current design 
	 * @param hierarchicalCell The hierarchical cell to become a black box.
	 */
	public static void makeBlackBox(Design d, EDIFHierCellInst hierarchicalCell){
		CodePerfTracker t = CodePerfTracker.SILENT;//  new CodePerfTracker("makeBlackBox", true);
		t.start("Init");
		EDIFCellInst futureBlackBox = hierarchicalCell.getInst();
		if(futureBlackBox == null) throw new RuntimeException("ERROR: Couldn't find cell " + hierarchicalCell + " in source design " + d.getName());
		
		Set<SiteInst> touched = new HashSet<>();
		Map<String,String> boundaryNets = new HashMap<>();
		
		t.stop().start("Find border nets");
		// Find all the nets that connect to the cell (keep them)
		for(EDIFPortInst portInst : futureBlackBox.getPortInsts()){
			EDIFNet net = portInst.getNet();
			EDIFHierCellInst hierParentName =hierarchicalCell.getParent();
			EDIFHierNet hierNetName = new EDIFHierNet(hierParentName, net);
			EDIFHierNet parentNetName = d.getNetlist().getParentNet(hierNetName);
			boundaryNets.put(parentNetName.getHierarchicalNetName(), portInst.isOutput() ? hierNetName.getHierarchicalNetName() : null);

			// Remove parts of routed GND/VCC nets exiting the black box
			if(portInst.isInput()) continue;
			NetType netType = NetType.getNetTypeFromNetName(parentNetName.getHierarchicalNetName());
			if(netType.isStaticNetType()){
				// Black box is supplying VCC/GND, we must unroute connected tree
				EDIFHierNet hierNet = new EDIFHierNet(hierParentName, net);
				List<EDIFHierPortInst> sinks = d.getNetlist().getSinksFromNet(hierNet);
				// extract site wire and site pins and nodes to unroute
				for(EDIFHierPortInst sink : sinks){
					Cell c = d.getCell(sink.getFullHierarchicalInstName());
					if(c == null || !c.isPlaced()) continue;
					SiteInst i = c.getSiteInst();
					String logicalPinName = sink.getPortInst().getName();
					String sitePinName = c.getCorrespondingSitePinName(logicalPinName);
					SitePinInst pin = i.getSitePinInst(sitePinName);
					Net staticNet = d.getStaticNet(netType);
					Site site = i.getSite();
					BELPin snk = c.getBEL().getPin(c.getPhysicalPinMapping(logicalPinName));
					if(pin == null && netType == NetType.GND){
						// GND post inside the site, let's unroute the site wires
						i.unrouteIntraSiteNet(site.getBELPin("HARD0GND", "0"), snk);
						continue;
					}
					removeConnectedRouting(staticNet, Node.getNode(pin.getTile(),pin.getConnectedTileWire()));
					i.unrouteIntraSiteNet(site.getBELPin(sitePinName), snk);
				}
			}
		}
		
		t.stop().start("Remove p&r");

		List<EDIFHierCellInst> allLeafs = d.getNetlist().getAllLeafDescendants(hierarchicalCell);

		// Remove all placement and routing information related to the cell to be blackboxed
		for(EDIFHierCellInst i : allLeafs){
			// Get the physical cell, make sure we can unplace/unroute it first 
			Cell c = d.getCell(i.getFullHierarchicalInstName());
			if(c == null) {
				continue;
			}
			BEL bel = c.getBEL();
			SiteInst si = c.getSiteInst();
			
			// Remove all physical nets first
			for(String logPin : c.getPinMappingsP2L().values()){
				SitePinInst pin = c.getSitePinFromLogicalPin(logPin, null);
				if(pin == null) continue;
				if(pin.getNet() == null) continue;
				Net net = pin.getNet();
				net.removePin(pin, true);
				if(boundaryNets.containsKey(net.getName())) continue;
				if(net.isStaticNet()) continue;
				d.removeNet(net);
				
				// Unroute site connections 
				String physPinName = c.getPhysicalPinMapping(logPin);
				if(physPinName != null){
					BELPin belPin = c.getBEL().getPin(physPinName);
					si.unrouteIntraSiteNet(belPin, belPin); 					
				}
			}
			touched.add(c.getSiteInst());
			
			c.unplace();
			d.removeCell(c.getName());
			si.removeCell(bel);
		}
		
		t.stop().start("cleanup t-prims");
		
		// Clean up any cells from Transformed Prims
		for(SiteInst si : d.getSiteInsts()){
			for(Cell c : si.getCells()){
				if(c.getName().startsWith(hierarchicalCell.getFullHierarchicalInstName() + EDIFTools.EDIF_HIER_SEP)){
					touched.add(si);
				}
			}
		}
		
		t.stop().start("new net names");
		
		Map<Net, String> netsToUpdate = new HashMap<>();
		// Update black box output nets with new net names (those with sinks inside the black box)
		for(Net n : d.getNets()){
			String newName = boundaryNets.get(n.getName());
			if(newName != null){
				netsToUpdate.put(n, newName);
			}
		}
		
		for(Entry<Net, String> e : netsToUpdate.entrySet()){
			EDIFHierNet newSource = d.getNetlist().getHierNetFromName(e.getValue());
			DesignTools.updateNetName(d, e.getKey(), newSource.getNet(), e.getValue());
		}
		
		t.stop().start("cleanup siteinsts");
		
		// Clean up SiteInst objects
		for(SiteInst siteInst : touched){
			d.removeSiteInst(siteInst);
		}
		
		t.stop().start("create bbox");
		
		// Make EDIFCell blackbox
		EDIFCell blackBox = new EDIFCell(futureBlackBox.getCellType().getLibrary(),"black_box" + 
				uniqueBlackBoxCount++);
		for(EDIFPort port : futureBlackBox.getCellType().getPorts()){
			blackBox.addPort(port);
		}
		futureBlackBox.setCellType(blackBox);
		futureBlackBox.addProperty(EDIFCellInst.BLACK_BOX_PROP, true);
		
		t.stop().printSummary();
	}

	/**
	 * Helper method for makeBlackBox().  When cutting out nets that used
	 * to be source'd from something inside a black box, the net names
	 * need to be updated.
	 * @param d The current design 
	 * @param currNet Current net that requires a name change
	 * @param newSource The source net (probably a pin on the black box) 
	 * @param newName New name for the net
	 * @return True if the operation succeeded, false otherwise.
	 */
	private static Net updateNetName(Design d, Net currNet, EDIFNet newSource, String newName){
		List<PIP> pips = currNet.getPIPs();
		List<SitePinInst> pins = currNet.getPins();
		
		d.removeNet(currNet);
		
		Net newNet = d.createNet(newName, newSource);
		newNet.setPIPs(pips);
		for(SitePinInst pin : pins){
			newNet.addPin(pin);
		}
		
		return newNet;
	}

	/**
	 * Gets or creates the corresponding SiteInst from the prototype orig from a module.
	 * @param design The current design from which to get the corresponding site instance.
	 * @param orig The original site instance (from the module)
	 * @param newAnchor The new anchor location of the module.
	 * @param module The Module to use as the template.
	 * @return The corresponding SiteInst from design if it exists, 
	 * or a newly created one in the translated location. If the new location
	 * cannot be determined or is invalid, null is returned.
	 */
	public static SiteInst getCorrespondingSiteInst(Design design, SiteInst orig, Site newAnchor, Module module){
		Tile newTile = Module.getCorrespondingTile(orig.getTile(), newAnchor.getTile(), module.getAnchor().getTile());
		Site newSite = newTile.getSites()[orig.getSite().getSiteIndexInTile()];
		SiteInst newSiteInst = design.getSiteInstFromSite(newSite);
		if(newSiteInst == null){
			newSiteInst = design.createSiteInst(newSite.getName(), orig.getSiteTypeEnum(), newSite);
		}
		return newSiteInst; 
	}

	/**
	 * Given a design with multiple identical cell instances, place
	 * each of those instances using the stamp module template
	 * at the anchored site locations provided in instPlacements. 
	 * @param design The top level design with identical multiple cell instances.
	 * @param stamp The prototype stamp (or stencil) to use for replicated placement and routing.
	 * This must match identically with the named instances in instPlacements
	 * @param instPlacements
	 * @return True if the procedure completed successfully, false otherwise.
	 */
	public static boolean stampPlacement(Design design, Module stamp, Map<String,Site> instPlacements){
		for(Entry<String,Site> e : instPlacements.entrySet()){
			String instName = e.getKey();
			String prefix = instName + "/";
			Site newAnchor = e.getValue();
			Site anchor = stamp.getAnchor().getSite();
			
			// Create New Nets
			for(Net n : stamp.getNets()){
				Net newNet = null;
				if(n.isStaticNet()){
					newNet = n.getName().equals(Net.GND_NET) ? design.getGndNet() : design.getVccNet();
				}else{
					String newNetName = prefix + n.getName();
					EDIFNet newEDIFNet = design.getNetlist().getNetFromHierName(newNetName);
					newNet = design.createNet(newNetName, newEDIFNet);					
				}
				
				for(SitePinInst p : n.getPins()){
					SiteInst newSiteInst = getCorrespondingSiteInst(design, p.getSiteInst(), newAnchor, stamp);
					if(newSiteInst == null) 
						return false;
					SitePinInst newPin = new SitePinInst(p.isOutPin(), p.getName(), newSiteInst);
					newNet.addPin(newPin);
				}
				
				for(PIP p : n.getPIPs()){
					Tile newTile = Module.getCorrespondingTile(p.getTile(), newAnchor.getTile(), anchor.getTile());
					if(newTile == null){
						return false;
					}
					PIP newPIP = new PIP(newTile, p.getStartWireIndex(), p.getEndWireIndex());
					newNet.addPIP(newPIP);
				}
			}	
	
			// Create SiteInst & New Cells
			for(SiteInst si : stamp.getSiteInsts()){
				SiteInst newSiteInst = getCorrespondingSiteInst(design, si, newAnchor, stamp);
				if(newSiteInst == null) 
					return false;
				for(Cell c : si.getCells()){
					String newCellName = prefix + c.getName();
					EDIFCellInst cellInst = design.getNetlist().getCellInstFromHierName(newCellName);
					if(cellInst == null && c.getEDIFCellInst() != null) {
						System.out.println("WARNING: Stamped cell not found: " + newCellName);
						continue;
					}
					
					Cell newCell = c.copyCell(newCellName, cellInst); 
					design.placeCell(newCell, newSiteInst.getSite(), c.getBEL(), c.getPinMappingsP2L());
				}
				
				for(SitePIP sitePIP : si.getUsedSitePIPs()){
					newSiteInst.addSitePIP(sitePIP);
				}
				
				for(Entry<String,Net> e2 : si.getNetSiteWireMap().entrySet()){
					String siteWire = e2.getKey();
					String netName = e2.getValue().getName();
					Net newNet = null;
					if(e2.getValue().isStaticNet()){
						newNet = netName.equals(Net.GND_NET) ? design.getGndNet() : design.getVccNet(); 
					}else if(netName.equals(Net.USED_NET)){
						newNet = design.getNet(Net.USED_NET);
						if(newNet == null){
							newNet = new Net(Net.USED_NET);
						}
					}else{
						newNet = design.getNet(prefix + netName);
					}
					
					BELPin[] belPins = newSiteInst.getSite().getBELPins(siteWire);
					newSiteInst.routeIntraSiteNet(newNet, belPins[0], belPins[0]);
				}
			}
		}
		return true;
	}
	
	/**
	 * Looks in the site instance for cells connected to this site pin.
	 * @return List of connected cells to this pin
	 */
	public static Set<Cell> getConnectedCells(SitePinInst pin){
		HashSet<Cell> cells = new HashSet<Cell>();
		SiteInst si = pin.getSiteInst();
		if(si == null) return cells;
		for(BELPin p : pin.getBELPin().getSiteConns()){
			if(p.getBEL().getBELClass() == BELClass.RBEL){
				SitePIP pip = si.getUsedSitePIP(p.getBELName());
				if(pip == null) continue;
				if(p.isOutput()){
					p = pip.getInputPin().getSiteConns().get(0);
					Cell c = si.getCell(p.getBELName());
					if(c != null) cells.add(c);
				}else{
					for(BELPin snk : pip.getOutputPin().getSiteConns()){
						Cell c = si.getCell(snk.getBELName());
						if(c != null) cells.add(c);
					}
				}
			}else{
				Cell c = si.getCell(p.getBELName());
				if(c != null && c.getLogicalPinMapping(p.getName()) != null) {
					cells.add(c);				
				}
			}
		}
		return cells;
	}
	
	/**
	 * Quick and dumb placement of a cell.  Does not attempt
	 * any optimization and will not change the placement
	 * of other cells.  Currently it will only place a cell in an empty site. 
	 * If the cell is already placed, it will leave it as is.
	 * TODO - implement basic optimizations    
	 * @param c The cell to place
	 * @return True if the cell is successfully placed, false otherwise.
	 */
	public static boolean placeCell(Cell c, Design design) {
		if(c.isPlaced()) {
			// Don't move cell if already placed
			return true;
		}
		Map<SiteTypeEnum, Set<String>> compatTypes = c.getCompatiblePlacements();
		
		for(Entry<SiteTypeEnum, Set<String>> e : compatTypes.entrySet()) {
			for(Site s : design.getDevice().getAllSitesOfType(e.getKey())) {
				SiteInst i = design.getSiteInstFromSite(s);
				if(i == null) {
					for(String bel : e.getValue()) {
						boolean success = design.placeCell(c, s, s.getBEL(bel));
						if (success) return true;
					}
				}
			}
		}
		return false;
	}
	
	
	/**
	 * Creates any and all missing SitePinInsts for this net.  This is common as a placed
	 * DCP will not have SitePinInsts annotated and this information is generally necessary
	 * for routing to take place.  
	 * @param design The current design of this net.
	 * @return The list of pins that were created or an empty list if none were created.
	 */
	public static List<SitePinInst> createMissingSitePinInsts(Design design, Net net) {
		EDIFNetlist n = design.getNetlist();
		List<EDIFHierPortInst> physPins = n.getPhysicalPins(net);
		if(physPins == null) {
		    // Perhaps net is not a parent net name
		    final EDIFHierNet hierNet = n.getHierNetFromName(net.getName());
		    if(hierNet != null) {
	            final EDIFHierNet parentHierNet = n.getParentNet(hierNet);
	            if(!hierNet.equals(parentHierNet)) {
	                physPins = n.getPhysicalPins(parentHierNet);
	                if(physPins != null) {
	                    System.out.println("WARNING: Physical net '" + net.getName() +
	                            "' is not the parent net but is treated as such." );
	                }
	            }		        
		    }
		}
		List<SitePinInst> newPins = new ArrayList<>();
		if(physPins == null) {
			// Likely net inside encrypted IP, let's see if we can infer anything from existing
			// physical description
			for(SiteInst siteInst : new ArrayList<>(net.getSiteInsts())) {
				for(String siteWire : new ArrayList<>(siteInst.getSiteWiresFromNet(net))) {
					for(BELPin pin : siteInst.getSiteWirePins(siteWire)) {
						if(pin.isSitePort()) {
							SitePinInst currPin = siteInst.getSitePinInst(pin.getName());
							if(currPin == null) {
								boolean isOutput = siteInst.isSitePinOutput(pin.getName());
								if(isOutput && net.getSource() != null) {
									currPin = new SitePinInst(pin.getName(), siteInst);
									net.setAlternateSource(currPin);
								}else {
									currPin = net.createPin(isOutput, pin.getName(), siteInst);
								}
								newPins.add(currPin);
							}
						}
					}
				}
			}
			
			return newPins;
		}

		for(EDIFHierPortInst p :  physPins) {
			Cell c = design.getCell(p.getFullHierarchicalInstName());
			if(c == null || c.getBEL() == null) continue;
			String logicalPinName = p.getPortInst().getName();
			String sitePinName = getRoutedSitePin(c, net, logicalPinName);
			if(sitePinName == null) continue;
			//TODO NOTE: The following if clause adds some unexpected pins to GND, e.g. SLICE.CIN
			/*if(sitePinName == null) {
				if(net.equals(design.getGndNet())) {
					sitePinName = c.getCorrespondingSitePinName(logicalPinName);
				}
				if(sitePinName == null) {
					continue;					
				}
				if(c.getPhysicalPinMapping(logicalPinName) == null) {
					String physPinMapping = c.getDefaultPinMapping(logicalPinName);
					if(physPinMapping != null) {
						c.addPinMapping(physPinMapping, logicalPinName);
					}					
				}
			}*/
			SiteInst si = c.getSiteInst();
			SitePinInst newPin = si.getSitePinInst(sitePinName);
			if(newPin != null) continue;
			newPin = net.createPin(p.isOutput(), sitePinName, c.getSiteInst());
			if(newPin != null) newPins.add(newPin);
			Set<String> physPinMappings = c.getAllPhysicalPinMappings(logicalPinName); 
			// BRAMs can have two (or more) physical pin mappings for a logical pin
			String existing = c.getPhysicalPinMapping(logicalPinName);
			if(physPinMappings != null && physPinMappings.size() > 1) {
				for(String physPin : physPinMappings) {
					if(existing.equals(physPin)) continue;
					sitePinName = getRoutedSitePinFromPhysicalPin(c, net, physPin);
					if(sitePinName == null) continue;
					newPin = si.getSitePinInst(sitePinName);
					if(newPin != null) continue;
					newPin = net.createPin(p.isOutput(), sitePinName, c.getSiteInst());
					if(newPin != null) newPins.add(newPin);					
				}
			}
		}
		return newPins;
	}

	/**
	 * Gets the site pin that is currently routed to the specified cell pin.  If 
	 * the site instance is not routed, it will return null. 
	 * Side Effect: It will set alternative source site pins on the net if present.
	 * @param cell The cell with the pin of interest.
	 * @param net The physical net to which this pin belongs
	 * @param logicalPinName The logical pin name of the cell to query.
	 * @return The name of the site pin on the cell's site to which the pin is routed.
	 */
	public static String getRoutedSitePin(Cell cell, Net net, String logicalPinName) {
		String belPinName = cell.getPhysicalPinMapping(logicalPinName);
		return getRoutedSitePinFromPhysicalPin(cell, net, belPinName);
	}
	
	/**
	 * Gets the site pin that is currently routed to the specified cell pin.  If 
	 * the site instance is not routed, it will return null. 
	 * Side Effect: It will set alternative source site pins on the net if present.
	 * @param cell The cell with the pin of interest.
	 * @param net The physical net to which this pin belongs
	 * @param belPinName The physical pin name of the cell
	 * @return The name of the site pin on the cell's site to which the pin is routed.
	 */
	public static String getRoutedSitePinFromPhysicalPin(Cell cell, Net net, String belPinName) {
	    SiteInst inst = cell.getSiteInst();
	    if(belPinName == null) return null;
	    Set<String> siteWires = inst.getSiteWiresFromNet(net);
	    String toReturn = null;
	    Queue<BELPin> queue = new LinkedList<>();
	    queue.add(cell.getBEL().getPin(belPinName));
	    while(!queue.isEmpty()) {
		    BELPin curr = queue.remove();
		    String siteWireName = curr.getSiteWireName();
	        if(!siteWires.contains(siteWireName)) {
	        	// Allow dedicated paths to pass without site routing
	        	if(siteWireName.equals("CIN") || siteWireName.equals("COUT")) {
	        		return siteWireName;
	        	}
	        	return null;
	        }
	        if(curr.isInput()) {
	            BELPin source = curr.getSourcePin();
	            if(source == null) return null;
	            if(source.isSitePort()) {
	                return source.getName();
	            } else if(source.getBEL().getBELClass() == BELClass.RBEL){
	                SitePIP sitePIP = inst.getUsedSitePIP(source.getBELName());
	                if(sitePIP == null) return null;
	                queue.add(sitePIP.getInputPin());
	            } else if(source.getBELName().contains("LUT")) {
	            	Cell possibleRouteThru = inst.getCell(source.getBEL());
	            	if(possibleRouteThru != null && possibleRouteThru.isRoutethru()) {
	            		String routeThru = possibleRouteThru.getPinMappingsP2L().keySet().iterator().next();
	            		return source.getBEL().getPin(routeThru).getSourcePin().getName();
	            	}
	            } else {
	                return null;
	            }
	        }else { // output
	            for(BELPin sink : curr.getSiteConns()) {
	                if(!siteWires.contains(sink.getSiteWireName())) continue;
	                if(sink.isSitePort()) {
	                	// Check if there is a dual output scenario
	                	if(toReturn != null) {
	                		SitePinInst source = net.getSource();
	                		if(source != null && source.getName().equals(sink.getName())) {
		                		net.setAlternateSource(new SitePinInst(true, toReturn, inst));
		                		toReturn = sink.getName();
	                		}else {
		                		net.setAlternateSource(new SitePinInst(true, sink.getName(), inst));	                			
	                		}
	                		// We'll return the first one we found, store the 2nd in the alternate
	                		// reference on the net
	                		return toReturn;
	                	}else {
			                toReturn = sink.getName();	                		
	                	}
	                } else if(sink.getBEL().getBELClass() == BELClass.RBEL){
	                	// Check if the SitePIP is being used
	                    SitePIP sitePIP = inst.getUsedSitePIP(sink.getBELName());
	                    if(sitePIP == null) continue;
	                    // Don't proceed if its configured for a different pin
	                    if(!sitePIP.getInputPinName().equals(sink.getName())) continue;
	                    // Make this the new source to search from and keep looking...
	                    queue.add(sitePIP.getOutputPin());
	                } else if(sink.getBELName().contains("FF")) {
	                	// FF pass thru option (not a site PIP)
	                	siteWireName = sink.getBEL().getPin("Q").getSiteWireName();
	                	if(siteWires.contains(siteWireName)) {
	                		return siteWireName;
	                	}
	                }
	            }
	        }
	    }
	    return toReturn;
	}
	
	/**
	 * Creates all missing SitePinInsts in a design. See also {@link #createMissingSitePinInsts(Design, Net)}
	 * @param design The current design
	 */
	public static void createMissingSitePinInsts(Design design) {
		for(Net net : design.getNets()) {
			createMissingSitePinInsts(design,net);
		}
	}
	
	private static HashSet<String> muxPins; 
	
	static {
		muxPins = new HashSet<String>();
		for(char c = 'A' ; c <= 'H' ; c++) {
			muxPins.add(c + "MUX");
		}
	}
	
	/**
	 * In Series7 and UltraScale architectures, there are dual output site pin scenarios where an 
	 * optional additional output can be used to drive out of the SLICE using the OUTMUX routing 
	 * BEL.  When unrouting a design, some site routing can be left "dangling".  This method will
	 * remove those unnecessary sitePIPs and site routing for the *MUX output.  It will also remove
	 * the output source pin if it is the *MUX output.
	 * @param design The design from which to remove the unnecessary site routing 
	 */
	public static void unrouteDualOutputSitePinRouting(Design design) {
		boolean isSeries7 = design.getDevice().getSeries() == Series.Series7;
		for(SiteInst siteInst : design.getSiteInsts()) {
			if(Utils.isSLICE(siteInst)) {
				ArrayList<String> toRemove = null;
				for(Entry<String,Net> e : siteInst.getNetSiteWireMap().entrySet()) {
					if(muxPins.contains(e.getKey())) {
						// MUX output is used, is the same net also driving the direct output?
						String directPin = e.getKey().charAt(0) + (isSeries7 ? "" : "_O");
						Net net = siteInst.getNetFromSiteWire(directPin);
						if(e.getValue().equals(net)) {
							if(toRemove == null) {
								toRemove = new ArrayList<String>();
							}
							toRemove.add(e.getKey());
						}
					}
				}
				if(toRemove == null) continue;
				for(String name : toRemove) {
					Net net = siteInst.getNetFromSiteWire(name);
					BELPin belPin = siteInst.getBEL(name).getPin(name);
					BELPin muxOutput = belPin.getSourcePin();
					SitePIP sitePIP = siteInst.getUsedSitePIP(muxOutput.getBELName());
					BELPin srcPin = sitePIP.getInputPin().getSourcePin();
					boolean success = siteInst.unrouteIntraSiteNet(srcPin, belPin);
					if(!success) throw new RuntimeException("ERROR: Failed to unroute dual output "
							+ "net/pin scenario: " + net + " on pin " + name);
					siteInst.routeIntraSiteNet(net, srcPin, srcPin);
					if(net.getSource() != null && net.getSource().getName().equals(belPin.getName())) {
						net.removePin(net.getSource());						
					}
				}
			}
		}
	}
	
	/**
	 * Finds a legal/available alternative output site pin for the given net.  The most common case 
	 * is the SLICE.  It depends on the existing output pin of the net to be routed within the site 
	 * and checks if site routing resource are available to use the alternative output site pin.
	 * @param net The net of interest.
	 * @return A new potential site pin inst that could be added/routed to.
	 */
	public static SitePinInst getLegalAlternativeOutputPin(Net net) {
		SitePinInst alt = net.getAlternateSource();
		if(alt != null) return alt;
		SitePinInst src = net.getSource();
		if(src == null) return null;
		SiteInst siteInst = src.getSiteInst();
		// Currently only support SLICE scenarios
		if(!Utils.isSLICE(siteInst)) return null;
		
		// Series 7: AMUX <-> A, BMUX <-> B, CMUX <-> C, DMUX <-> D
		// UltraScale/+: AMUX <-> A_O, BMUX <-> B_O, ... HMUX <-> H_O 
		Queue<BELPin> q = new LinkedList<>();
		BELPin srcPin = src.getBELPin();
		
		// Find the logical source
		BELPin logicalSource = getLogicalBELPinDriver(src);
		if(logicalSource == null) return null;
		q.add(logicalSource);

		// Fan out from logical source to all site pins
		BELPin alternateExit = null;
		while(!q.isEmpty()) {
			BELPin currOutPin = q.poll();
			Net currNet = siteInst.getNetFromSiteWire(currOutPin.getSiteWireName());
			// Skip any resources used by another net
			if(currNet != null && !currNet.equals(net)) continue;
			for(BELPin pin : currOutPin.getSiteConns()) {
				if(pin.getBEL().getBELClass() == BELClass.RBEL) {
					SitePIP pip = src.getSiteInst().getSitePIP(pin);
					q.add(pip.getOutputPin());
				}else if(pin.isSitePort() && !pin.equals(srcPin)) {
					Net currNet2 = siteInst.getNetFromSiteWire(pin.getSiteWireName());
					if(currNet2 == null || currNet2.equals(net)) {
						alternateExit = pin;
					}
				}
			}
		}
		if(alternateExit != null) {
			// Create the pin in such a way as it is not put in the SiteInst map
			SitePinInst sitePinInst = new SitePinInst();
			sitePinInst.setSiteInst(src.getSiteInst());
			sitePinInst.setPinName(alternateExit.getName());
			sitePinInst.setIsOutputPin(true);
			return sitePinInst;
		}
		return null;
	}
	
	/**
	 * Looks backwards from a SitePinInst output pin and finds the corresponding BELPin of the 
	 * driver.
	 * @param sitePinInst The output site pin instance from which to find the logical driver.
	 * @return The logical driver's BELPin of the provided sitePinInst.
	 */
	public static BELPin getLogicalBELPinDriver(SitePinInst sitePinInst) {
		if(!sitePinInst.isOutPin()) return null;
		SiteInst siteInst = sitePinInst.getSiteInst();
		for(BELPin pin : sitePinInst.getBELPin().getSiteConns()) {
			if(pin.isInput()) continue;
			if(pin.getBEL().getBELClass() == BELClass.RBEL) {
				SitePIP p = siteInst.getUsedSitePIP(pin.getBELName());
				for(BELPin pin2 : p.getInputPin().getSiteConns()) {
					if(pin2.isOutput()) {
						return pin2;
					}
				}
			}
			return pin;
		}
		return null;
	}
	
	/**
	 * Routes (within the site) the alternate site output pin for SLICE dual-output scenarios. 
	 * @param net The current net of interest to be routed
	 * @param sitePinInst The alternate site output pin to be routed
	 * @return True if the routing was successful, false otherwise
	 */
	public static boolean routeAlternativeOutputSitePin(Net net, SitePinInst sitePinInst) {
		if(sitePinInst == null) return false;
		net.setAlternateSource(sitePinInst);
		sitePinInst.setNet(net);

		BELPin driver = getLogicalBELPinDriver(net.getSource());
		BELPin belPinPort = sitePinInst.getBELPin();
		
		return sitePinInst.getSiteInst().routeIntraSiteNet(net, driver, belPinPort);
	}
	
	/**
	 * Un-routes (within the site) the alternate source pin on the provided net.  This is in 
	 * reference to dual-output site pin scenarios for SLICEs.  
	 * @param net The relevant net that has the populated alternative site pin.
	 * @return True if the alternate source was unrouted successfully, false otherwise.
	 */
	public static boolean unrouteAlternativeOutputSitePin(Net net) {
		SitePinInst altPin = net.getAlternateSource();
		if(altPin == null) return false;
		SiteInst siteInst = altPin.getSiteInst();
		
		BELPin driver = getLogicalBELPinDriver(net.getSource());
		BELPin belPinPort = altPin.getBELPin();
		
		boolean result = siteInst.unrouteIntraSiteNet(driver, belPinPort);
		// Re-route the driver site wire
		siteInst.routeIntraSiteNet(net, driver, driver);
		
		return result;
	}
	/**
	 * Given a SitePinInst, this method will find any return hierarchical logical cell pins within
	 * the site directly connected to the site pin.
	 * @param sitePin The site pin to query.
	 * @return A list of hierarchical port instances that connect to the site pin.
	 */
	public static ArrayList<EDIFHierPortInst> getPortInstsFromSitePinInst(SitePinInst sitePin) {
		SiteInst siteInst = sitePin.getSiteInst();
		BELPin[] belPins = siteInst.getSiteWirePins(sitePin.getName());
		ArrayList<EDIFHierPortInst> portInsts = new ArrayList<EDIFHierPortInst>();
		for(BELPin belPin : belPins) {
			if(belPin.isOutput() == sitePin.isOutPin()) {
				if(belPin.getBEL().getBELClass() == BELClass.RBEL) {
					// Routing BEL, lets look ahead/behind it
					SitePIP sitePIP = siteInst.getUsedSitePIP(belPin);
					if(sitePIP != null) {
						BELPin otherPin = belPin.isOutput() ? sitePIP.getInputPin() : sitePIP.getOutputPin();
						for(BELPin belPin2 : otherPin.getSiteConns()) {
							if(belPin2.equals(otherPin)) continue;
							EDIFHierPortInst portInst = getPortInstFromBELPin(siteInst, belPin2);
							if(portInst != null) portInsts.add(portInst);
						}				
					}
				}else {
					EDIFHierPortInst portInst = getPortInstFromBELPin(siteInst, belPin);
					if(portInst != null) portInsts.add(portInst);					
				}
			}
		}
		return portInsts;
	}

	private static EDIFHierPortInst getPortInstFromBELPin(SiteInst siteInst, BELPin belPin) {
		Cell targetCell = siteInst.getCell(belPin.getBEL());
		if(targetCell == null) {
			// Is it routing through a FF? (Series 7 / UltraScale)
			if(belPin.getName().equals("Q")) {
				Net net = siteInst.getNetFromSiteWire(belPin.getSiteWireName());
				BELPin d = belPin.getBEL().getPin("D");
				Net otherNet = siteInst.getNetFromSiteWire(d.getSiteWireName());
				if(net == otherNet) {
					BELPin muxOut = d.getSourcePin();
					SitePIP pip = siteInst.getUsedSitePIP(muxOut);
					if(pip != null) {
						BELPin src = pip.getInputPin().getSourcePin();
						return getPortInstFromBELPin(siteInst, src);
					}
				}
			}
			
			return null;
		}
		String logPinName = targetCell.getLogicalPinMapping(belPin.getName());
		if(logPinName == null) return null;
		EDIFPortInst portInst = targetCell.getEDIFCellInst().getPortInst(logPinName);
		final EDIFNetlist netlist = targetCell.getSiteInst().getDesign().getNetlist();
		EDIFHierPortInst hierPortInst = 
				new EDIFHierPortInst(netlist.getHierCellInstFromName(targetCell.getParentHierarchicalInstName()), portInst);
		return hierPortInst;
	}
	
	/**
	 * Creates a map that contains pin names for keys that map to the Unisim Verilog parameter that
	 * can invert a pins value.  
	 * @param series The series of interest.
	 * @param unisim The unisim of interest.
	 * @return A map of invertible pins that are that are mapped to their respective parameter name
	 * that controls inversion. 
	 */
	public static Map<String,String> getInvertiblePinMap(Series series, Unisim unisim) {
		Map<String,String> invertPinMap = new HashMap<String, String>();
		for(Entry<String, VivadoProp> e : Design.getDefaultCellProperties(series, unisim.name()).entrySet()) {
			String propName = e.getKey(); 
			if(propName.startsWith("IS_") && propName.endsWith("_INVERTED")){
				String pinName = propName.substring(propName.indexOf('_')+1, propName.lastIndexOf('_'));
				invertPinMap.put(pinName, propName);
			}
		}
		return invertPinMap;
	}

	/**
	 * Copies the logic and implementation of a set of cells from one design to another.
	 * @param src The source design (with partial or full implementation)
	 * @param dest The destination design (with matching cell instance interfaces) 
	 * @param lockPlacement Flag indicating if the destination implementation copy should have the 
	 * 	placement locked
	 * @param lockRouting Flag indicating if the destination implementation copy should have the 
	 * 	routing locked
	 * @param srcToDestInstNames A map of source (key) to destination (value) pairs of cell 
	 * instances from which to copy the implementation
	 */
	public static void copyImplementation(Design src, Design dest, boolean lockPlacement, 
			boolean lockRouting, Map<String,String> srcToDestInstNames) {
		// Removing existing logic in target cells in destination design
		EDIFNetlist destNetlist = dest.getNetlist();
		for(Entry<String,String> e : srcToDestInstNames.entrySet()) {
			DesignTools.makeBlackBox(dest, e.getValue());
			destNetlist.removeUnusedCellsFromAllWorkLibraries();
		}
		
		// Populate black boxes with existing logical netlist cells
		HashSet<String> instsWithSeparator = new HashSet<>();
		for(Entry<String,String> e : srcToDestInstNames.entrySet()) {
			EDIFHierCellInst cellInst = src.getNetlist().getHierCellInstFromName(e.getKey());
			destNetlist.migrateCellAndSubCells(cellInst.getCellType());
			EDIFHierCellInst bbInst = destNetlist.getHierCellInstFromName(e.getValue());
			bbInst.getInst().setCellType(cellInst.getCellType());
            for(EDIFPortInst portInst : bbInst.getInst().getPortInsts()) {
            	portInst.getPort().setParentCell(cellInst.getCellType());
            }
			instsWithSeparator.add(e.getKey() + EDIFTools.EDIF_HIER_SEP);
		}
		destNetlist.resetParentNetMap();
		
		Map<String,String> prefixes = new HashMap<>();
		for(String srcPrefix : srcToDestInstNames.keySet()) {
			prefixes.put(srcPrefix + "/", srcPrefix);
		}
		
		// Identify cells to copy placement
		for(Cell cell : src.getCells()) {
			String cellName = cell.getName();
			
			String prefixMatch = null;
			if((prefixMatch = StringTools.startsWithAny(cellName, prefixes.keySet())) != null) {
				SiteInst dstSiteInst = dest.getSiteInstFromSite(cell.getSite());
				SiteInst srcSiteInst = cell.getSiteInst();
				if(dstSiteInst == null) {
					dstSiteInst = dest.createSiteInst(srcSiteInst.getName(), 
									srcSiteInst.getSiteTypeEnum(), srcSiteInst.getSite());
				}
				String newCellPrefix = srcToDestInstNames.get(prefixes.get(prefixMatch));
				String newCellName = newCellPrefix + cell.getName().substring(prefixMatch.length()-1);
				Cell copy = cell.copyCell(newCellName, cell.getEDIFCellInst(), dstSiteInst);
				dstSiteInst.addCell(copy);
				copy.setBELFixed(lockPlacement);
				copy.setSiteFixed(lockPlacement);
				
				// Preserve site routing from cell pins to site pins
				copySiteRouting(copy, cell, srcToDestInstNames, prefixes); 
			}
		}
		
		// Identify nets to copy routing
		for(Net net : src.getNets()) {
			if(net.isStaticNet()) continue;
			List<EDIFHierPortInst> pins = src.getNetlist().getPhysicalPins(net);
			if(pins == null) continue;
			// Identify the kinds of routes to preserve:
			//  - Has the source in the preservation zone
			//  - Has at least one sink inside preservation zone
			boolean srcInside = false;
			List<EDIFHierPortInst> outside = new ArrayList<EDIFHierPortInst>();
			for(EDIFHierPortInst portInst : pins) {
				String portInstName = portInst.getFullHierarchicalInstName();
				String prefixMatch = StringTools.startsWithAny(portInstName, prefixes.keySet());
				if(portInst.isOutput() && prefixMatch != null) {
					srcInside = true;
				}
				if(prefixMatch == null) {
					outside.add(portInst);
				}
			}
			// Don't keep routing if source is not in preservation zone
			if(!srcInside) continue;
			if((outside.size() + 1) >= pins.size()) continue;
			
			Set<PIP> pipsToRemove = new HashSet<>();
			// Net is partially inside, preserve only portions inside
			for(EDIFHierPortInst removeMe : outside) {
				for(SitePinInst sitePin : removeMe.getAllRoutedSitePinInsts(src)) {
					pipsToRemove.addAll(unroutePin(sitePin, net));					
				}
			}

			String newNetName = net.getName();
			String prefixMatch = null;
			if((prefixMatch = StringTools.startsWithAny(net.getName(), prefixes.keySet())) != null) {
				String noSeparator = prefixes.get(prefixMatch);
				newNetName = srcToDestInstNames.get(noSeparator) + newNetName.substring(noSeparator.length());
			}
			EDIFNet logicalNet = destNetlist.getNetFromHierName(net.getName());
			Net copiedNet = dest.createNet(newNetName, logicalNet);
			for(PIP p : net.getPIPs()) {
				if(pipsToRemove.contains(p)) continue;
				copiedNet.addPIP(p);
				if(lockRouting) {
					p.setIsPIPFixed(true);
				}
			}
		}		
	}
	
	/**
	 * Copies the logic and implementation of a set of cells from one design to another.
	 * @param src The source design (with partial or full implementation)
	 * @param dest The destination design (with matching cell instance interfaces) 
	 * @param instNames Names of the cell instances to copy
	 */
	public static void copyImplementation(Design src, Design dest, String... instNames) {
		copyImplementation(src, dest, false, false, instNames);
	}

	/**
	 * Copies the logic and implementation of a set of cells from one design to another.
	 * @param src The source design (with partial or full implementation)
	 * @param dest The destination design (with matching cell instance interfaces) 
	 * @param lockPlacement Flag indicating if the destination implementation copy should have the 
	 * 	placement locked
	 * @param lockRouting Flag indicating if the destination implementation copy should have the 
	 * 	routing locked
	 * @param instNames Names of the cell instances to copy
	 */
	public static void copyImplementation(Design src, Design dest, boolean lockPlacement, 
			boolean lockRouting, String... instNames) {
		Map<String,String> map = new HashMap<>();
		for(String instName : instNames) {
			map.put(instName, instName);
		}
		copyImplementation(src, dest, lockPlacement, lockRouting, map);
	}
		
	/**
	 * Copies the site routing for all nets connected to the copied cell based on the original 
	 * cell.  This method will use destination netlist net names to be consistent with source-named
	 * net names as used in Vivado.
	 * @param copy The destination cell context to receive the site routing
	 * @param orig The original cell with the blueprint of site routing that should be copied
	 * @param srcToDestNames Map of source to destination cell instance name prefixes that should be
	 * copied. 
	 * @param prefixes Map of prefixes with '/' at the end (keys) that map to the same String 
	 * without the '/'
	 */
	private static void copySiteRouting(Cell copy, Cell orig, Map<String,String> srcToDestNames, 
			Map<String,String> prefixes) {
		Design dest = copy.getSiteInst().getDesign();
		EDIFNetlist destNetlist = dest.getNetlist();
		SiteInst dstSiteInst = copy.getSiteInst();
		SiteInst origSiteInst = orig.getSiteInst();
		// Ensure A6 has VCC if driven in original (dual LUT usage scenarios)
		if(orig.getBELName().contains("LUT")) {
			BEL lut6 = origSiteInst.getBEL(orig.getBELName().replace("5", "6"));
			BELPin a6 = lut6.getPin("A6");
			Net net = origSiteInst.getNetFromSiteWire(a6.getSiteWireName());
			if(net != null && net.getName().equals(Net.VCC_NET)) {
				dstSiteInst.routeIntraSiteNet(dest.getVccNet(), a6, a6);
			}
		}
		
		EDIFHierCellInst cellInst = destNetlist.getHierCellInstFromName(copy.getName());
		for(Entry<String,String> e : copy.getPinMappingsP2L().entrySet()) {
			EDIFPortInst portInst = cellInst.getInst().getPortInst(e.getValue());
			if(portInst == null) continue;
			EDIFNet edifNet = portInst.getNet();

			String netName = new EDIFHierNet(cellInst.getParent(), edifNet).getHierarchicalNetName();

			String siteWireName = orig.getSiteWireNameFromPhysicalPin(e.getKey());
			Net origNet = origSiteInst.getNetFromSiteWire(siteWireName);
			if(origNet == null) continue;
			Net net = null;
			if(origNet.isStaticNet()) {
				net = origNet;
			}else {
				String parentNetName = destNetlist.getParentNetName(netName);
				if(parentNetName == null) {
					parentNetName = netName;
				}
				net = dest.getNet(parentNetName);
				if(net == null) {
					net = dest.createNet(parentNetName, edifNet);
				}				
			}
			
			BELPin curr = copy.getBEL().getPin(e.getKey());
			dstSiteInst.routeIntraSiteNet(net, curr, curr);
			boolean routingForward = curr.isOutput();
			Queue<BELPin> q = new LinkedList<BELPin>();
			q.add(curr);
			while(!q.isEmpty()) {
				curr = q.poll();
				if(routingForward) {
					for(BELPin pin : curr.getSiteConns()) {
						if(pin == curr) continue;
						SitePIP sitePIP = origSiteInst.getUsedSitePIP(pin);
						if(sitePIP != null) {
							String currSiteWireName = sitePIP.getOutputPin().getSiteWireName();
							Net test = origSiteInst.getNetFromSiteWire(currSiteWireName);
							if(origNet.equals(test)) {
								dstSiteInst.addSitePIP(sitePIP);
								curr = sitePIP.getOutputPin();
								dstSiteInst.routeIntraSiteNet(net, curr, curr);
								q.add(curr);
							}
						}
					}
				}else {
					curr = curr.getSourcePin();
					if(curr.isSitePort()) continue;
					String belName = curr.getBELName();
					Cell tmpCell = origSiteInst.getCell(belName);
					if(tmpCell != null) {
						if(tmpCell.isRoutethru()) {
							String cellName = tmpCell.getName();
							String prefixMatch = StringTools.startsWithAny(cellName, prefixes.keySet());
							if(prefixMatch == null){
								throw new RuntimeException("ERROR: Unable to find appropriate "
									+ "translation name for cell: " + tmpCell);
							}
							String newPrefix = srcToDestNames.get(prefixes.get(prefixMatch));
							String newCellName = newPrefix+cellName.substring(prefixMatch.length()-1);
							Cell rtCopy = tmpCell
									.copyCell(newCellName, tmpCell.getEDIFCellInst(), dstSiteInst);
							dstSiteInst.getCellMap().put(belName, rtCopy);
							for(String belPinName : rtCopy.getPinMappingsP2L().keySet()) {
								BELPin tmp = rtCopy.getBEL().getPin(belPinName);
								if(tmp.isInput()) {
									curr = tmp;
									break;
								}
							}	
						} else {
							// We found the source
							break;
						}
					} else if(net.isStaticNet() && (belName.contains("LUT") ||
							curr.getBEL().isStaticSource())){
						// LUT used as a static source
						dstSiteInst.routeIntraSiteNet(net, curr, curr);
						break;
					} else {
						SitePIP sitePIP = origSiteInst.getUsedSitePIP(curr);
						if(sitePIP != null) {
							dstSiteInst.addSitePIP(sitePIP);
							curr = sitePIP.getInputPin();								
						} else {
							continue;
						}
					}
					dstSiteInst.routeIntraSiteNet(net, curr, curr);
					q.add(curr);
				}
			}
		}
	}
	
	private static Set<PIP> unroutePin(SitePinInst pin, Net net){
		Node sink = pin.getConnectedNode();
		List<PIP> pips = net.getPIPs();
		Map<Node,ArrayList<PIP>> reverseConns = new HashMap<>();
		Map<Node,ArrayList<PIP>> reverseConnsStart = new HashMap<>();
		Map<Node,Integer> fanout = new HashMap<>();
		for(PIP pip : pips){
			Node endNode = pip.getEndNode();
			Node startNode = pip.getStartNode();
			
			ArrayList<PIP> rPips = reverseConns.get(endNode);
			if(rPips == null){
				rPips = new ArrayList<>();
				reverseConns.put(endNode, rPips);
			}
			rPips.add(pip);

			if (pip.isBidirectional()) {
				rPips = reverseConnsStart.get(startNode);
				if (rPips == null) {
					rPips = new ArrayList<>();
					reverseConnsStart.put(startNode, rPips);
				}
				rPips.add(pip);
			}

			Integer count = fanout.get(startNode);
			if(count == null){
				fanout.put(startNode, 1);
			}else{
				fanout.put(startNode, count+1);
			}
		}
		ArrayList<PIP> curr = reverseConns.get(sink);
		Integer fanoutCount = fanout.get(sink);
		fanoutCount = fanoutCount == null ? 0 : fanoutCount;
		boolean atReversedBidirectionalPip = false;
		if (curr == null) {
			// must be at a reversed bidirectional PIP
			curr = reverseConnsStart.get(sink);
			fanoutCount--;
			atReversedBidirectionalPip = true;
		}
		HashSet<PIP> toRemove = new HashSet<>();
		while(curr != null && curr.size() == 1 && fanoutCount < 2){
			PIP pip = curr.get(0);
			toRemove.add(pip);
			if (new Node(pip.getTile(), pip.getStartWireIndex()).equals(sink)) {
				// reached the source and there is another branch starting with a reversed
				// bidirectional PIP ... don't traverse it
				break;
			}
			sink = new Node(pip.getTile(), atReversedBidirectionalPip ? pip.getEndWireIndex() :
					pip.getStartWireIndex());
			atReversedBidirectionalPip = false;
			curr = reverseConns.get(sink);
			fanoutCount = fanout.get(sink);
			fanoutCount = fanoutCount == null ? 0 : fanoutCount;
			SitePin sitePin = sink.getSitePin();
			if (curr == null && !(sitePin != null || sink.getWireName().contains(Net.VCC_WIRE_NAME) ||
					sink.getWireName().contains(Net.GND_WIRE_NAME))) {
				// curr should only be null when we're at the source site, so we've hit a reversed bidirectional PIP
				// on our linear path
				curr = reverseConnsStart.get(sink);
				curr.remove(pip);
				fanoutCount--;
				atReversedBidirectionalPip = true;
			}
			if(sitePin != null && sitePin.isInput()){
				SiteInst si = net.getSource().getSiteInst().getDesign().getSiteInstFromSite(sitePin.getSite());
				if(si != null){
					if(net.equals(si.getNetFromSiteWire(sitePin.getPinName()))){
						fanoutCount = 2;
					}
				}
			}
		}
		if(curr == null && fanout.size() == 1 && !net.isStaticNet()){
			// We got all the way back to the source site. It is likely that 
			// the net is using dual exit points from the site as is common in
			// SLICEs -- we should unroute the sitenet
			SitePin sPin = sink.getSitePin();
			SiteInst si = net.getSource().getSiteInst();
			if(!si.unrouteIntraSiteNet(sPin.getBELPin(), sPin.getBELPin())){
				throw new RuntimeException("ERROR: Improperly routed net state while unrouting pin " +
						" of net " + net.getName());
			}
		}
		
		return toRemove;
	}
	
	public static void printSiteInstInfo(SiteInst siteInst, PrintStream ps) {
		ps.println("=====================================================================");
		ps.println(siteInst.getSiteName() + " :");
		for(BEL bel : siteInst.getSite().getBELs()) {
			Cell cell = siteInst.getCell(bel);
			ps.println("  BEL: " + bel.getName() + " : " + cell);
			for(BELPin pin : bel.getPins()) {
				String isPinFixed = cell != null && cell.isPinFixed(pin.getName()) ? " [*]" : "";
				String logPin = (cell != null ? cell.getLogicalPinMapping(pin.getName()) : "null");
				ps.println("    Pin: " + pin.getName() + " : " + logPin + isPinFixed);
			}
		}
		
		for(String siteWireName : siteInst.getSite().getSiteWireNames()) {
			ps.println("  SiteWire: " + siteWireName + " " + siteInst.getNetFromSiteWire(siteWireName));
		}
		
		for(SitePIP pip : siteInst.getSite().getSitePIPs()) {
			ps.println("  SitePIP: " + pip + " : " + siteInst.getSitePIPStatus(pip));
		}
	}

	public static void makePhysNetNamesConsistent(Design design) {
	    Map<EDIFHierNet, EDIFHierNet> netParentMap = design.getNetlist().getParentNetMap();
	    EDIFNetlist netlist = design.getNetlist();
	    for(Net net : new ArrayList<>(design.getNets())) {
	        if(net.isStaticNet()) continue;
	        EDIFHierNet hierNet = netlist.getHierNetFromName(net.getName());
	        if(hierNet == null) {
	            // Likely an encrypted cell
	            continue;
	        }
	        EDIFHierNet parentHierNet = netParentMap.get(hierNet);
	        if(!hierNet.equals(parentHierNet)) {
	            Net parentPhysNet = design.getNet(parentHierNet.getHierarchicalNetName());
	            if(parentPhysNet != null) {
	                // Merge both physical nets together
	                for(SiteInst si : net.getSiteInsts()) {
	                    List<String> siteWires = new ArrayList<>(si.getSiteWiresFromNet(net));
	                    for(String siteWire : siteWires) {
	                        BELPin[] pins = si.getSiteWirePins(siteWire);
	                        si.unrouteIntraSiteNet(pins[0], pins[0]);
	                        si.routeIntraSiteNet(parentPhysNet, pins[0], pins[0]);
	                    }
	                }
                    design.removeNet(net);
	            } else if(!net.rename(parentHierNet.getHierarchicalNetName())) {
	                System.out.println("WARNING: Failed to adjust physical net name " + net.getName());
	            }
	        }
	    }
	}

	public static void createPossiblePinsToStaticNets(Design design) {
		createA1A6ToStaticNets(design);
		createCeClkOfRoutethruFFToVCC(design);
		createCeSrRstPinsToVCC(design);
	}
	
	public static void createCeClkOfRoutethruFFToVCC(Design design) {
		Net vcc = design.getVccNet();
        Net gnd = design.getGndNet();
        for(SiteInst si : design.getSiteInsts()) {
            if(!Utils.isSLICE(si)) continue;
            for(BEL bel : si.getBELs()) {
                if(si.getCell(bel) != null) continue;
                BELPin q = bel.getPin("Q");
                if(q != null) {
                    Net netQ = si.getNetFromSiteWire(q.getSiteWireName());
                    if(netQ == null) continue;
                    BELPin dPin = bel.getPin("D");
                    if(dPin != null) {
                        Net netD = si.getNetFromSiteWire(dPin.getSiteWireName());
                        if(netQ == netD) {
                            //System.out.println(si.getSiteName() + "/" + bel + ": " + netQ);
                            // Need VCC at CE
                            BELPin ceInput = bel.getPin("CE");
                            String ceInputSitePinName = ceInput.getConnectedSitePinName();
                            SitePinInst ceSitePin = si.getSitePinInst(ceInputSitePinName);
                            if(ceSitePin == null) {
                                ceSitePin = vcc.createPin(ceInputSitePinName, si);
                            }
                            si.routeIntraSiteNet(vcc, ceSitePin.getBELPin(), ceInput);
                            // ...and GND at CLK
                            BELPin clkInput = bel.getPin("CLK");
                            BELPin clkInvOut = clkInput.getSourcePin();
                            si.routeIntraSiteNet(gnd, clkInvOut, clkInput);
                            BELPin clkInvIn = clkInvOut.getBEL().getPin(0);
                            String clkInputSitePinName = clkInvIn.getConnectedSitePinName();
                            SitePinInst clkInputSitePin = si.getSitePinInst(clkInputSitePinName);
                            if(clkInputSitePin == null) {
                                clkInputSitePin = vcc.createPin(clkInputSitePinName, si);
                            }
                            si.routeIntraSiteNet(vcc, clkInputSitePin.getBELPin(), clkInvIn);
                        }
                    }
                }
            }
        }
	}

	public static void createA1A6ToStaticNets(Design design) {
		for(SiteInst si : design.getSiteInsts()) {
			for(Cell cell : si.getCells()) {
				BEL bel = cell.getBEL();
				if(bel == null || !bel.getName().contains("LUT")) continue;
				if(bel.getName().contains("5LUT")) {
					bel = si.getBEL(bel.getName().replace("5", "6"));
				}
				for(String belPinName : lut6BELPins) {
					if(belPinName.equals("A1") && !"SRL16E".equals(cell.getType()) && !"SRLC32E".equals(cell.getType())) continue;
					BELPin belPin = bel.getPin(belPinName);
					if(belPin != null) {
						createMissingStaticSitePins(belPin, si, cell);
					}
				}
			}
		}
	}

	public static void createCeSrRstPinsToVCC(Design design) {
		for(Cell cell : design.getCells()) {
			if(isUnisimFlipFlopType(cell.getType())) {
				BEL bel = cell.getBEL();
				Pair<String, String> sitePinNames = belSitePinNameMapping.get(bel.getBELType());
				String[] pins = new String[] {"CE", "SR"};
				for(String pin : pins) {
					BELPin belPin = cell.getBEL().getPin(pin);
					SiteInst si = cell.getSiteInst();
					Net net = si.getNetFromSiteWire(belPin.getSiteWireName());
					if(net == null) {
						String sitePinName;
						if(pin.equals("CE")){ // CKEN
							sitePinName = sitePinNames.getFirst();
						}else { //SRST
							sitePinName = sitePinNames.getSecond();
						}
						net = design.getVccNet();
						if(!si.getSitePinInstNames().contains(sitePinName)) net.createPin(sitePinName, si);
					}
				}
			}else if(cell.getType().equals("RAMB36E2") && cell.getAllPhysicalPinMappings("RSTREGB") == null) {
				//cell.getEDIFCellInst().getProperty("DOB_REG")): integer(0)
				String siteWire = cell.getSiteWireNameFromLogicalPin("RSTREGB");
				Net net = cell.getSiteInst().getNetFromSiteWire(siteWire);
				if(net == null) {
					net = design.getVccNet();
					SiteInst si = cell.getSiteInst();
					if(!si.getSitePinInstNames().contains("RSTREGBU")) net.createPin("RSTREGBU", si);
					if(!si.getSitePinInstNames().contains("RSTREGBL")) net.createPin("RSTREGBL", si);
				}
		    }else if(cell.getType().equals("RAMB18E2") && cell.getAllPhysicalPinMappings("RSTREGB") == null) {
		    	SiteInst si = cell.getSiteInst();
		    	// type RAMB180: L_O, type RAMB181: U_O
		    	// TODO Type should be consistent with getPrimarySiteTypeEnum()?
		    	// System.out.println(cell.getAllPhysicalPinMappings("RSTREGB") + ", " + si + ", " + cell.getSiteWireNameFromLogicalPin("RSTREGB") + ", " + si.getPrimarySiteTypeEnum());
		    	// [RSTREGB], SiteInst(name="RAMB18_X5Y64", type="RAMB180", site="RAMB18_X5Y64"), OPTINV_RSTREGB_L_O, RAMBFIFO18
		    	// [RSTREGB], SiteInst(name="RAMB18_X5Y31", type="RAMB181", site="RAMB18_X5Y31"), OPTINV_RSTREGB_U_O, RAMB181
		    	// null, SiteInst(name="RAMB18_X6Y43", type="RAMB181", site="RAMB18_X6Y43"), null, RAMB181
		    	// null, SiteInst(name="RAMB18_X5Y22", type="RAMB180", site="RAMB18_X5Y22"), null, RAMBFIFO18
		    	// The following workaround solves the RAMB18 RSTREGB pin issue
		    	String siteWire = cell.getBEL().getPin("RSTREGB").getSiteWireName();
		    	Net net = si.getNetFromSiteWire(siteWire);
		    	if(net == null) {
		    		net = design.getVccNet();
		    		String pinName = null;
		    		if(siteWire.endsWith("L_O")) {
		    			pinName = "RSTREGBL";
		    		}else {
		    			pinName = "RSTREGBU";
		    		}
		    		if(si.getSitePinInstNames().isEmpty() || !si.getSitePinInstNames().contains(pinName)) {
		    			net.createPin(pinName, si);
		    		}
		    	}
		    }
		}
	}

	public static void createMissingStaticSitePins(BELPin belPin, SiteInst si, Cell cell) {
        // SiteWire and SitePin Name are the same for LUT inputs
	    String siteWireName = belPin.getSiteWireName();
        // VCC returned based on the site wire, site pins are not stored in dcp
		Net net = si.getNetFromSiteWire(siteWireName);
		if(net == null) {
		    net = si.getDesign().getVccNet();
		}
        if(net.isStaticNet()) {
            // SRL16Es that have been transformed from SRLC32E require GND on their A6 pin
            if(cell.getType().equals("SRL16E") && siteWireName.endsWith("6")) {
                EDIFPropertyValue val = cell.getProperty("XILINX_LEGACY_PRIM");
                if(val != null && val.getValue().equals("SRLC32E")) {
                    net = si.getDesign().getGndNet();
                }
            }
            SitePinInst pin = si.getSitePinInst(siteWireName); 
            if(pin == null) {
                net.createPin(siteWireName, si);
            } else if(!pin.getNet().equals(net)){
                pin.getNet().removePin(pin);
                net.addPin(pin);
            }
        }
	}

	//NOTE: SRL16E (reference name SRL16E, EDIFCell in RW) uses A2-A5, so we need to connect A1 & A6 to VCC,
	//however, when SitePinInsts (e.g. A3) are already in GND, adding those again will cause problems to A1
	static String[] lut6BELPins = new String[] {"A1", "A6"};
	static HashSet<String> unisimFlipFlopTypes;
	static {
        unisimFlipFlopTypes = new HashSet<>();
        unisimFlipFlopTypes.add("FDSE");//S CE, logical cell
        unisimFlipFlopTypes.add("FDPE");//PRE CE
        unisimFlipFlopTypes.add("FDRE");//R and CE
        unisimFlipFlopTypes.add("FDCE");//CLR CE
	}

	private static boolean isUnisimFlipFlopType(String cellType) {
        return unisimFlipFlopTypes.contains(cellType);
    }

	static Map<String, Pair<String, String>> belSitePinNameMapping;
	static{
		belSitePinNameMapping = new HashMap<>();

		belSitePinNameMapping.put("AFF", new Pair<>("CKEN1", "SRST1"));
		belSitePinNameMapping.put("BFF", new Pair<>("CKEN1", "SRST1"));
		belSitePinNameMapping.put("CFF", new Pair<>("CKEN1", "SRST1"));
		belSitePinNameMapping.put("DFF", new Pair<>("CKEN1", "SRST1"));
		belSitePinNameMapping.put("AFF2", new Pair<>("CKEN2", "SRST1"));
		belSitePinNameMapping.put("BFF2", new Pair<>("CKEN2", "SRST1"));
		belSitePinNameMapping.put("CFF2", new Pair<>("CKEN2", "SRST1"));
		belSitePinNameMapping.put("DFF2", new Pair<>("CKEN2", "SRST1"));

		belSitePinNameMapping.put("EFF", new Pair<>("CKEN3", "SRST2"));
		belSitePinNameMapping.put("FFF", new Pair<>("CKEN3", "SRST2"));
		belSitePinNameMapping.put("GFF", new Pair<>("CKEN3", "SRST2"));
		belSitePinNameMapping.put("HFF", new Pair<>("CKEN3", "SRST2"));
		belSitePinNameMapping.put("EFF2", new Pair<>("CKEN4", "SRST2"));
		belSitePinNameMapping.put("FFF2", new Pair<>("CKEN4", "SRST2"));
		belSitePinNameMapping.put("GFF2", new Pair<>("CKEN4", "SRST2"));
		belSitePinNameMapping.put("HFF2", new Pair<>("CKEN4", "SRST2"));
	}
	
	/**
	 * Finds the essential PIPs which connect the provided sink pin to its source.
	 * @param sinkPin A sink pin from a routed net.
	 * @return The list of PIPs that for the routing connection from the sink to the source.
	 */
    public static List<PIP> getConnectionPIPs(SitePinInst sinkPin) {
        if(sinkPin.isOutPin() || sinkPin.getNet() == null) return Collections.emptyList();
        Map<Node, PIP> reverseNodeToPIPMap = new HashMap<>(); 
        for(PIP p : sinkPin.getNet().getPIPs()) {
            reverseNodeToPIPMap.put(p.getEndNode(), p);
        }
        
        Node sinkNode = sinkPin.getConnectedNode();
        Node srcNode = sinkPin.getNet().getSource().getConnectedNode();
        Node curr = sinkNode;
        
        List<PIP> path = new ArrayList<>();
        while(!curr.equals(srcNode)) {
            PIP pip = reverseNodeToPIPMap.get(curr);
            path.add(pip);
            curr = pip.getStartNode();
        }
        
        return path;
    }

	/**
	 * Create a Job running Vivado to create a readable version of
	 * the EDIF inside the checkpoint to a separate file.
	 * @param checkpoint the input checkpoint
	 * @param edif the output EDIF
	 * @return the created Job
	 */
	public static Job generateReadableEDIFJob(Path checkpoint, Path edif) {
		try {

			final Job job = new LocalJob();
			job.setCommand(FileTools.getVivadoPath() + " -mode batch -source readable.tcl");

			final Path runDir = Files.createTempDirectory(edif.toAbsolutePath().getParent(),edif.getFileName()+"_readable_edif_");
			job.setRunDir(runDir.toString());

			Files.write(runDir.resolve("readable.tcl"), Arrays.asList(
					"open_checkpoint " + checkpoint.toAbsolutePath(),
					"write_edif " + edif.toAbsolutePath()
			));


			return job;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Use Vivado to create a readable version of the EDIF file inside a Checkpoint.
	 * @param dcp the checkpoint
	 * @param edfFileName filename to use or null if we should select a filename
	 * @return the output edif filename
	 */
	public static Path generateReadableEDIF(Path dcp, Path edfFileName) {
		if (edfFileName == null) {
			edfFileName = FileTools.replaceExtension(dcp, ".edf");
		}
		JobQueue queue = new JobQueue();
		Job job = generateReadableEDIFJob(dcp, edfFileName);
		queue.addJob(job);
		if (!queue.runAllToCompletion()) {
			throw new RuntimeException("Generating Readable EDIF job failed");
		}
		FileTools.deleteFolder(job.getRunDir());
		return edfFileName;
	}
}
