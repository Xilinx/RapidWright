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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;


import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFValueType;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * Generates a placed and routed adder or subtractor using
 * CLB LUTs, CARRY8s and flip flops.  
 * @author clavin
 *
 */
public class AddSubGenerator extends ArithmeticGenerator {
	
	private static final String SLICE_SITES_OPT = "s";
	private static final String VERBOSE_OPT = "v";
	private static final String HELP_OPT = "h";
	private static final String IS_SUBTRACT_OPT = "m";
	private static final String INPUT_PIPE_FLOP_OPT = "f";
	

	
	private static void connectFDRECtrl(Net clk, Net rst, Net ce, Cell ff){
		clk.getLogicalNet().createPortInst("C", ff);
		rst.getLogicalNet().createPortInst("R", ff);
		ce.getLogicalNet().createPortInst("CE", ff);

		char letter = ff.getBELName().charAt(0);
		boolean isFF2 = ff.getBELName().endsWith("2");
		boolean isLowerSlice = 'A' <= letter && letter <= 'D'; 
		String clkPinName = isLowerSlice ? "CLK1" : "CLK2";
		String rstPinName = isLowerSlice ? "SRST1" : "SRST2";
		String cePinName = "CKEN" + (isLowerSlice ? (isFF2 ? "2" : "1") : (isFF2 ? "4" : "3")); 
		if(ff.getSiteInst().getSitePinInst(clkPinName) == null){
			clk.createPin(false, clkPinName, ff.getSiteInst());
			ff.getSiteInst().addSitePIP(clkPinName + "INV","CLK");
		}
		if(ff.getSiteInst().getSitePinInst(rstPinName) == null){
			rst.createPin(false, rstPinName, ff.getSiteInst());
			ff.getSiteInst().addSitePIP("RST_"+(isLowerSlice ? "ABCD" : "EFGH")+"INV","RST");
		}
		if(ff.getSiteInst().getSitePinInst(cePinName) == null){
			ce.createPin(false, cePinName, ff.getSiteInst());
		}
	}
	
	public static PBlock createAddSub(Design d, Site origin, int width, boolean isSubtract, boolean inputFlop, boolean route){
		EDIFCell top = d.getNetlist().getTopCell();
		Set<Site> used = new HashSet<>();
		String bus = "["+(width-1)+":0]";
		
		EDIFPort aPort = top.createPort(INPUT_A_NAME + bus, EDIFDirection.INPUT, width);
		EDIFPort bPort = top.createPort(INPUT_B_NAME + bus, EDIFDirection.INPUT, width);
		EDIFPort outPort = top.createPort(RESULT_NAME + bus, EDIFDirection.OUTPUT, width);
		EDIFPort clkPort = top.createPort("clk", EDIFDirection.INPUT, 1);
		EDIFPort cePort = top.createPort("ce", EDIFDirection.INPUT, 1);
		EDIFPort rstPort = top.createPort("rst", EDIFDirection.INPUT, 1);
		EDIFNet clk = top.createNet(clkPort.getName());
		EDIFNet rst = top.createNet(rstPort.getName());
		EDIFNet ce = top.createNet(cePort.getName());
		
		clk.createPortInst(clkPort);
		rst.createPortInst(rstPort);
		ce.createPortInst(cePort);
		Net clkNet = d.createNet(clk);
		Net rstNet = d.createNet(rst);
		Net ceNet = d.createNet(ce);
		EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, top, d.getNetlist());
		EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, top, d.getNetlist());
		
		Cell carryCell = null;
		int carryCLEs = ((width+BITS_PER_CLE-1) / BITS_PER_CLE); // Ceiling divide
		// Create LUT2s & FFs
		for(int i=0; i < width; i++){
			Site currSlice = origin.getNeighborSite(0, i / BITS_PER_CLE);
			used.add(currSlice);
			String letter = Character.toString((char)('A'+i%8));
			BEL lut = currSlice.getBEL(letter + "6LUT");
			BEL ff = currSlice.getBEL(letter + "FF");
			Cell lutCell = d.createAndPlaceCell(top, "add" + i, Unisim.LUT2, currSlice, lut);
			lutCell.addProperty("INIT", isSubtract ? "4'h9" : "4'h6", EDIFValueType.STRING);
			Cell ffCell = d.createAndPlaceCell(top, "sum" + i, Unisim.FDRE, currSlice, ff);
			ffCell.addProperty("INIT", "1'b0", EDIFValueType.STRING);
			SiteInst si = ffCell.getSiteInst();
						
			
			if(letter.equals("A")){
				BEL carry = currSlice.getBEL("CARRY8");
				carryCell = d.createAndPlaceCell(top, "carry" + i, Unisim.CARRY8, currSlice, carry);
				carryCell.addProperty("CARRY_TYPE","SINGLE_CY8", EDIFValueType.STRING);
				gnd.createPortInst("CI_TOP", carryCell);
				for(int j=0; j < BITS_PER_CLE; j++){
					carryCell.removePinMapping("DI" + j);
					String physName = Character.toString((char)('A' + j)) +"X";
					carryCell.addPinMapping(physName, "DI[" + j +"]");
				}

				if(i>0){
					EDIFNet c = top.getNet("c" + (i-1));
					c.createPortInst("CI", carryCell);
					Net cNet = d.getNet(c.getName());
					cNet.createPin(false, "CIN",si);
					
					if(si.getSiteTypeEnum() == SiteTypeEnum.SLICEL){
						cNet.addPIP(new PIP(si.getTile(), "CLE_CLE_L_SITE_0_CIN","CLE_CLE_L_SITE_0_CIN_PIN"));
					}else{
						cNet.addPIP(new PIP(si.getTile(), "CLE_CLE_M_SITE_0_CIN","CLE_CLE_M_SITE_0_CIN_PIN"));
					}
				}else {
					EDIFNet cinSrc = isSubtract ? vcc : gnd;
					cinSrc.createPortInst("CI", carryCell);
					carryCell.removePinMapping("CIN");
				}
			}

			// Logical Nets
			int cleIndex = (i % BITS_PER_CLE);
			int edifIndex = (BITS_PER_CLE-1) - cleIndex;
			String index = "[" + i + "]";
			EDIFNet a = top.createNet(INPUT_A_NAME + index);
			EDIFNet b = top.createNet(INPUT_B_NAME + index);
			EDIFNet aInt = inputFlop ? top.createNet(INPUT_A_NAME +"_int"+ index) : null;
			EDIFNet bInt = inputFlop ? top.createNet(INPUT_B_NAME +"_int"+ index) : null;
			EDIFNet p = top.createNet("p" + index);
			EDIFNet s = top.createNet("s" + index);
			EDIFNet so = top.createNet("so" + index);
			(inputFlop ? aInt: a).createPortInst("I0", lutCell);
			a.createPortInst(aPort,i);
			(inputFlop ? aInt: a).createPortInst("DI", edifIndex, carryCell);
			(inputFlop ? bInt: b).createPortInst("I1", lutCell);
			b.createPortInst(bPort, i);
			p.createPortInst("O", lutCell);
			p.createPortInst("S", edifIndex, carryCell);
			s.createPortInst("O", edifIndex, carryCell);
			s.createPortInst("D", ffCell);
			so.createPortInst("Q", ffCell);
			so.createPortInst(outPort, i);
			
			connectFDRECtrl(clkNet, rstNet, ceNet, ffCell);
			
			// Physical Nets
			Net aNet = d.createNet(a);
			Net bNet = d.createNet(b);
			Net aNetInt = inputFlop ? d.createNet(aInt) : null;
			Net bNetInt = inputFlop ? d.createNet(bInt) : null;
			Net pNet = d.createNet(p);
			Net sNet = d.createNet(s);
			Net soNet = d.createNet(so);
			(inputFlop ? aNetInt : aNet).createPin(false,letter +"5",si);
			(inputFlop ? aNetInt : aNet).createPin(false,letter +"X",si);
			(inputFlop ? bNetInt : bNet).createPin(false,letter +"6",si);
			
			BELPin src = lut.getPin("O6");
			BELPin snk = carryCell.getBEL().getPin("S" + cleIndex);
			si.routeIntraSiteNet(pNet, src, snk);
			si.routeIntraSiteNet(sNet, carryCell.getBEL().getPin("O"+cleIndex), ff.getPin("D"));
			si.addSitePIP("FFMUX"+letter+"1", "XORIN");
			
			soNet.createPin(true,letter +"Q",si);
			
			if(inputFlop){
				Site inputFFSite = null;
				// Decided if we pack flop into FF2s of carry-using CLEs or put flops above
				/*if((i+BITS_PER_CLE-1) / BITS_PER_CLE < carryCLEs){
					int yOffset = i / BITS_PER_CLE;
					inputFFSite = origin.getNeighborSite(0, yOffset);
				}else{*/
					inputFFSite = currSlice.getNeighborSite(0, carryCLEs);
				//}
				used.add(inputFFSite);
				
				BEL ff2 = inputFFSite.getBEL(letter + "FF2");
				Cell aFFCell = d.createAndPlaceCell(top, "inA" + i, Unisim.FDRE, inputFFSite, ff);
				Cell bFFCell = d.createAndPlaceCell(top, "inB" + i, Unisim.FDRE, inputFFSite, ff2);	
				connectFDRECtrl(clkNet, rstNet, ceNet, aFFCell);
				connectFDRECtrl(clkNet, rstNet, ceNet, bFFCell);
				
				a.createPortInst("D", aFFCell);
				b.createPortInst("D", bFFCell);
				aInt.createPortInst("Q", aFFCell);
				bInt.createPortInst("Q", bFFCell);
				
				SiteInst siNeighbor = d.getSiteInstFromSite(inputFFSite);
				aNet.createPin(false, letter +"X", siNeighbor);
				bNet.createPin(false, letter +"_I", siNeighbor);
				aNetInt.createPin(true, letter +"Q", siNeighbor);
				bNetInt.createPin(true, letter +"Q2", siNeighbor);
				siNeighbor.addSitePIP("FFMUX" + letter +"1", "BYP");
				siNeighbor.addSitePIP("FFMUX" + letter +"2", "BYP");
			}
			
			
			if(i%8==7 && width > i+1){
				EDIFNet c = top.createNet("c" + i);
				c.createPortInst("CO", edifIndex, carryCell);
				Net cNet = d.createNet(c);
				cNet.createPin(true,"COUT",si);
			}
		}
		
		// Find rectangular area consumed
		PBlock footprint = new PBlock(d.getDevice(),used);
		
		if(route){			
			Router r = new Router(d);
			r.setRoutingPblock(footprint);
			r.setSupressWarningsErrors(true);
			r.routeDesign();			
		}
		
		return footprint;
	}
	
	private static OptionParser createOptionParser(){
		// Defaults
		String partName = "xcvu9p-flgb2104-2-i";
		String designName = "addsub";
		String outputDCPFileName = System.getProperty("user.dir") + File.separator + designName +".dcp";
		String clkName = "clk";
		double clkPeriodConstraint = 1.291; // 775 MHz
		int width = 16;
		String sliceSite = "SLICE_X101Y480";
		boolean verbose = true;
		boolean isSubtractor = true;
		boolean addInputFlop = true;
		
		OptionParser p = new OptionParser() {{
			accepts(PART_OPT).withOptionalArg().defaultsTo(partName).describedAs("UltraScale+ Part Name");
			accepts(DESIGN_NAME_OPT).withOptionalArg().defaultsTo(designName).describedAs("Design Name");
			accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo(outputDCPFileName).describedAs("Output DCP File Name");
			accepts(CLK_NAME_OPT).withOptionalArg().defaultsTo(clkName).describedAs("Clk net name");
			accepts(CLK_CONSTRAINT_OPT).withOptionalArg().ofType(Double.class).defaultsTo(clkPeriodConstraint).describedAs("Clk period constraint (ns)");
			accepts(WIDTH_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(width).describedAs("Operand width");
			accepts(SLICE_SITES_OPT).withOptionalArg().defaultsTo(sliceSite).describedAs("Lower left slice to be used for adder/subtracter");
			accepts(IS_SUBTRACT_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(isSubtractor).describedAs("Subtraction instead of addition");
			accepts(INPUT_PIPE_FLOP_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(addInputFlop).describedAs("Adds input pipeline flop");
			accepts(VERBOSE_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(verbose).describedAs("Print verbose output");
			acceptsAll( Arrays.asList(HELP_OPT, "?"), "Print Help" ).forHelp();
		}};
		
		return p;
	}
	
	private static void printHelp(OptionParser p){
		MessageGenerator.printHeader("Adder/Subtractor Generator");
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
		CodePerfTracker t = verbose ? new CodePerfTracker(AddSubGenerator.class.getSimpleName(),true).start("Init") : null;
		
		String partName = (String) opts.valueOf(PART_OPT);
		String designName = (String) opts.valueOf(DESIGN_NAME_OPT);
		String outputDCPFileName = (String) opts.valueOf(OUT_DCP_OPT);
		String clkName = (String) opts.valueOf(CLK_NAME_OPT);
		double clkPeriodConstraint = (double) opts.valueOf(CLK_CONSTRAINT_OPT);
		int width = (int) opts.valueOf(WIDTH_OPT);		
		String sliceName = (String) opts.valueOf(SLICE_SITES_OPT);
		boolean isSubtract = (boolean) opts.valueOf(IS_SUBTRACT_OPT);
		boolean inputFlop = (boolean) opts.valueOf(INPUT_PIPE_FLOP_OPT);
		
		// Perform some error checking on inputs
		Part part = PartNameTools.getPart(partName);
		if(part == null || part.isSeries7()){
			MessageGenerator.briefErrorAndExit("ERROR: Invalid/unsupport part " + partName + ".");
		}
		
		Design d = new Design(designName,partName);
		d.setAutoIOBuffers(false);
		Device dev = d.getDevice();
		
		t.stop().start("Create Add/Sub");
		Site slice = dev.getSite(sliceName);
		createAddSub(d, slice, width, isSubtract, inputFlop, true);			
				
		// Add a clock constraint
		String tcl = "create_clock -name "+clkName+" -period "+clkPeriodConstraint+" [get_ports "+clkName+"]";
		d.addXDCConstraint(ConstraintGroup.LATE,tcl);
		d.setAutoIOBuffers(false);
		
		t.stop();
		d.writeCheckpoint(outputDCPFileName, t);
		if(verbose) System.out.println("Wrote final DCP: " + outputDCPFileName);
	}
}
