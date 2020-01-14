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
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.SitePIP;
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
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Utils;

/**
 * A collection of methods to operate on {@link Design} objects.
 * 
 * Created on: Dec 7, 2015
 */
public class DesignTools {


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
		if(src.getBEL().getName().contains("FF")){
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
		Cell c = netSource.getSiteInst().getCell(src.getBEL().getName());
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
					inputPin.getBEL().getName());
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
	 * @param outputPin
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
			throw new RuntimeException("ERROR: Couldn't find " + cellPinName + " on element " + cell.getBEL().getName() + ".");
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
 
				if(isBELAReg(c.getBEL().getName())){
					incrementUtilType(map, UtilizationType.CLB_REGS);
					incrementUtilType(map, UtilizationType.REGS_AS_FFS);
				} else if(c.getBEL().getName().contains("CARRY")){
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
		Map<String,String> parentNetMap = inst.getDesign().getNetlist().getParentNetMap();
		BELPin[] pins = inst.getSite().getBELPins(siteWire);
		for(BELPin pin : pins){
			if(pin.isSitePort()) continue;
			Cell c = inst.getCell(pin.getBEL().getName());
			if(c == null || c.getEDIFCellInst() == null) continue;
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
		// Populate Logical Netlist into cell
		EDIFCellInst i = design.getNetlist().getCellInstFromHierName(hierarchicalCellName);
		if(!i.isBlackBox()) {
			System.err.println("ERROR: The cell instance " + hierarchicalCellName + " is not a black box.");
			return;
		}
		i.setCellType(cell.getTopEDIFCell());
		design.getNetlist().migrateCellAndSubCells(cell.getTopEDIFCell());
		
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
		for(Net n : cell.getNets()){
			if(n.getName().equals(Net.USED_NET)) continue;
			if(n.isStaticNet()){
				Net staticNet = design.getNet(n.getName());
				staticNet.addPins((ArrayList<SitePinInst>)n.getPins());
				for(PIP p : n.getPIPs()){
					staticNet.addPIP(p);
				}
			}else{
				n.updateName(hierarchicalCellName + "/" + n.getName());
				design.addNet(n);				
			}
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
				Node curr = new Node(pip.getTile(), wireIndex);
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
					Node startNode = new Node(p.getTile(), p.getStartWireIndex()); 
					q.add(curr.equals(startNode) ? startNode : new Node(p.getTile(), p.getEndWireIndex()));
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
	public static void makeBlackBox(Design d, String hierarchicalCellName){
		CodePerfTracker t = CodePerfTracker.SILENT;//  new CodePerfTracker("makeBlackBox", true);
		t.start("Init");
		EDIFCellInst futureBlackBox = d.getNetlist().getCellInstFromHierName(hierarchicalCellName);
		if(futureBlackBox == null) throw new RuntimeException("ERROR: Couldn't find cell " + hierarchicalCellName + " in source design " + d.getName());
		
		Set<SiteInst> touched = new HashSet<>();
		Map<String,String> boundaryNets = new HashMap<>();
		
		t.stop().start("Find border nets");
		// Find all the nets that connect to the cell (keep them)
		for(EDIFPortInst portInst : futureBlackBox.getPortInsts()){		
			EDIFNet net = portInst.getNet();
			String hierParentName = EDIFTools.getHierarchicalRootFromPinName(hierarchicalCellName);
			String hierNetName = hierParentName.length() == 0 ? net.getName() : hierParentName + EDIFTools.EDIF_HIER_SEP + net.getName();
			String parentNetName = d.getNetlist().getParentNetName(hierNetName);
			boundaryNets.put(parentNetName, portInst.isOutput() ? hierNetName : null);

			// Remove parts of routed GND/VCC nets exiting the black box
			if(portInst.isInput()) continue;
			NetType netType = NetType.getNetTypeFromNetName(parentNetName);
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
					removeConnectedRouting(staticNet, new Node(pin.getTile(),pin.getConnectedTileWire()));
					i.unrouteIntraSiteNet(site.getBELPin(sitePinName), snk);
				}
			}
		}
		
		t.stop().start("Remove p&r");

		List<EDIFHierCellInst> allLeafs = d.getNetlist().getAllLeafDescendants(hierarchicalCellName);

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
				if(c.getName().startsWith(hierarchicalCellName + EDIFTools.EDIF_HIER_SEP)){
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
		EDIFCell blackBox = new EDIFCell(futureBlackBox.getCellType().getLibrary(),"black_box");
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
				SitePIP pip = si.getUsedSitePIP(p.getBEL().getName());
				if(pip == null) continue;
				if(p.isOutput()){
					p = pip.getInputPin().getSiteConns().get(0);
					Cell c = si.getCell(p.getBEL().getName());
					if(c != null) cells.add(c);
				}else{
					for(BELPin snk : pip.getOutputPin().getSiteConns()){
						Cell c = si.getCell(snk.getBEL().getName());
						if(c != null) cells.add(c);
					}
				}
			}else{
				Cell c = si.getCell(p.getBEL().getName());
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
}
