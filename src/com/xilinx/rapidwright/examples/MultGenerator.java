/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.StringTools;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 *
 * @author clavin
 *
 */
public class MultGenerator extends ArithmeticGenerator {
    private static final String DSP_SITE_OPT = "s";

    public static final String RESULT_NAME = "P";

    private static final String OPMODE_VALUE = "000000101";

    public static EDIFCellInst createDSP48E2CellInstance(Design d, EDIFCell parent, String name) {
        EDIFCell dsp48e2 = Design.getMacroPrimitives(d.getDevice().getSeries()).getCell("DSP48E2");
        if (parent.getNetlist().getCell("DSP48E2") == null) {
            EDIFLibrary lib = parent.getNetlist().getHDIPrimitivesLibrary();
            EDIFCell copy = new EDIFCell(lib, dsp48e2, dsp48e2.getName());
            lib.addCell(copy);

            for (EDIFCellInst childInst : copy.getCellInsts()) {
                EDIFCell child = childInst.getCellType();
                EDIFCell deepCopy = new EDIFCell(lib, child, child.getName());
                childInst.setCellType(deepCopy);
            }
            dsp48e2 = copy;
        }
        EDIFCellInst i = dsp48e2.createCellInst(name, parent);

        i.addProperty("USE_MULT","MULTIPLY");
        i.addProperty("SEL_PATTERN","PATTERN");
        i.addProperty("USE_SIMD","ONE48");
        i.addProperty("USE_PATTERN_DETECT","NO_PATDET");
        i.addProperty("XORSIMD","XOR24_48_96");
        i.addProperty("USE_WIDEXOR","FALSE");
        i.addProperty("ACASCREG",1);
        i.addProperty("ADREG",1);
        i.addProperty("ALUMODEREG",0);
        i.addProperty("AMULTSEL","A");
        i.addProperty("AREG",1);
        i.addProperty("AUTORESET_PATDET","NO_RESET");
        i.addProperty("AUTORESET_PRIORITY","RESET");
        i.addProperty("A_INPUT","DIRECT");
        i.addProperty("BCASCREG",1);
        i.addProperty("BMULTSEL","B");
        i.addProperty("BREG",1);
        i.addProperty("B_INPUT","DIRECT");
        i.addProperty("CARRYINREG",0);
        i.addProperty("CARRYINSELREG",0);
        i.addProperty("CREG",0);
        i.addProperty("DREG",1);
        i.addProperty("INMODEREG",0);
        i.addProperty("MASK","48'h3FFFFFFFFFFF");
        i.addProperty("MREG",1);
        i.addProperty("OPMODEREG",0);
        i.addProperty("PATTERN","48'h000000000000");
        i.addProperty("PREADDINSEL","A");
        i.addProperty("PREG",1);
        i.addProperty("RND","48'h000000000000");
        i.addProperty("SEL_MASK","MASK");

        return i;
    }

    public static PBlock createMult(Design d, Site origin, int width, String designName, String clkName) {
        EDIFCell top = d.getNetlist().getTopCell();

        EDIFPort clkPort = top.createPort(clkName, EDIFDirection.INPUT, 1);
        EDIFNet clk = top.createNet(clkName);
        clk.createPortInst(clkPort);

        String[] dspCells = new String[]{
                "DSP_PREADD_DATA", "DSP_A_B_DATA", "DSP_C_DATA", "DSP_MULTIPLIER",
                "DSP_ALU", "DSP_M_DATA", "DSP_OUTPUT", "DSP_PREADD",
                };
        SiteInst si = null;
        for (String elem : dspCells) {
            Cell c = d.createAndPlaceCell(null, designName+"/"+elem +"_INST",
                    Unisim.valueOf(elem), origin,origin.getBEL(elem));
            si = c.getSiteInst();
        }
        EDIFCellInst inst = createDSP48E2CellInstance(d,top,designName);

        String[] gndPins = new String[]{
                "CEA1","CEAD","CEALUMODE","CEB1","CEC",
                "CECARRYIN","CECTRL","CED","CEINMODE",
        };
        String[] vccPins = new String[]{"CARRYIN","CEA2","CEB2","CEM","CEP","RSTA",
                "RSTALLCARRYIN","RSTALUMODE","RSTB","RSTC","RSTCTRL","RSTD",
                "RSTINMODE","RSTM","RSTP"};
        String[] gndBusses = new String[]{
                "A","B","CARRYINSEL","C","D"
        };
        String[] vccBusses = new String[]{
                "ALUMODE","INMODE","OPMODE"
        };

        // Setup GND/VCC inputs
        EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, top, d.getNetlist());
        EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, top, d.getNetlist());
        Net logic0 = d.getStaticNet(NetType.GND);
        Net logic1 = d.getStaticNet(NetType.VCC);

        for (NetType type : new NetType[]{NetType.GND,NetType.VCC}) {
            EDIFNet logicSrc = type == NetType.GND ? gnd : vcc;
            Net physNet = type == NetType.GND ? logic0 : logic1;
            String[] pins = type == NetType.GND ? gndPins : vccPins;
            for (String pin : pins) {
                logicSrc.createPortInst(pin, inst);
                physNet.createPin(pin, si);
            }
            String[] busPins = type == NetType.GND ? gndBusses : vccBusses;

            for (String bus : busPins) {
                EDIFPort p = inst.getCellType().getPort(bus);
                int stop = p.getWidth();
                boolean isAorB = bus.equals("A") || bus.equals("B");
                if (isAorB) {
                    // Don't gnd the inputs
                    stop = p.getWidth() - width;
                }
                for (int i=0; i < stop; i++) {
                    logicSrc.createPortInst(p, i, inst);
                    if (bus.equals("D")) bus = "DIN";
                    physNet.createPin(bus + (isAorB ? i + width : i), si);
                }
            }
        }

        // Connect logical outside connections/ports
        clk.createPortInst("CLK",inst);
        Net physClk = d.createNet(clk.getName());
        physClk.createPin("CLK", si);

        int aWidth = inst.getPort(INPUT_A_NAME).getWidth();
        int bWidth = inst.getPort(INPUT_B_NAME).getWidth();
        int pWidth = inst.getPort(RESULT_NAME).getWidth();

        String suffix = "["+(width-1)+":0]";
        EDIFPort a = top.createPort(INPUT_A_NAME + suffix, EDIFDirection.INPUT, width);
        EDIFPort b = top.createPort(INPUT_B_NAME + suffix, EDIFDirection.INPUT, width);
        EDIFPort r = top.createPort(RESULT_NAME + "["+(pWidth-1)+":0]", EDIFDirection.OUTPUT, pWidth);

        for (int i=0; i < width; i++) {
            suffix = "["+i+"]";
            EDIFNet aNet = top.createNet(INPUT_A_NAME + suffix);
            EDIFNet bNet = top.createNet(INPUT_B_NAME + suffix);
            EDIFNet rNet = top.createNet(RESULT_NAME + suffix);

            aNet.createPortInst(a,width-i-1);
            bNet.createPortInst(b,width-i-1);
            rNet.createPortInst(r,pWidth-i-1);

            aNet.createPortInst(INPUT_A_NAME, aWidth-i-1, inst);
            bNet.createPortInst(INPUT_B_NAME, bWidth-i-1, inst);
            rNet.createPortInst(RESULT_NAME, pWidth-i-1, inst);


            Net physA = d.createNet(inst + "/" + aNet.getName());
            Net physB = d.createNet(inst + "/" + bNet.getName());
            Net physR = d.createNet(inst + "/" + rNet.getName());

            physA.createPin(INPUT_A_NAME + i, si);
            physB.createPin(INPUT_B_NAME + i, si);
            physR.createPin(RESULT_NAME + i, si);
        }
        for (int i=width; i < pWidth; i++) {
            suffix = "["+i+"]";
            EDIFNet rNet = top.createNet(RESULT_NAME + suffix);
            rNet.createPortInst(r,pWidth-i-1);
            rNet.createPortInst(RESULT_NAME, pWidth-i-1, inst);
            Net physR = d.createNet(inst + "/" + rNet.getName());
            physR.createPin(RESULT_NAME + i, si);
        }

        // ADD SitePIPs
        String[] sitePIPElements = new String[]{
                "ALUMODE0INV","ALUMODE1INV","ALUMODE2INV","ALUMODE3INV",
                "CARRYININV","CLKINV","INMODE0INV","INMODE1INV","INMODE2INV",
                "INMODE3INV","INMODE4INV","OPMODE0INV","OPMODE1INV","OPMODE2INV",
                "OPMODE3INV","OPMODE4INV","OPMODE5INV","OPMODE6INV","OPMODE7INV",
                "OPMODE8INV","RSTAINV","RSTALLCARRYININV","RSTALUMODEINV","RSTBINV",
                "RSTCINV","RSTCTRLINV","RSTDINV","RSTINMODEINV","RSTMINV","RSTPINV"
        };
        for (String element : sitePIPElements) {
            String pinName = element.substring(0, element.length()-3);
            Net net = null;
            if (element.equals("CLKINV")) {
                net = physClk;
            } else if (element.startsWith("OPMODE")) {
                int idx = element.charAt(6) - 48;
                char c = OPMODE_VALUE.charAt(OPMODE_VALUE.length()-idx-1);
                net = c == 1 ? logic1 : logic0;
            } else {
                net = logic0;
            }
            si.routeIntraSiteNet(net, si.getSite().getBELPin(pinName), si.getBEL(element).getPin("D"));
            si.addSitePIP(element, "D");
        }

        for (EDIFPort port : inst.getCellType().getPorts()) {
            if (!port.isOutput()) continue;
            if (port.getBusName().equals(RESULT_NAME)) continue;
            for (int i=0; i < port.getWidth(); i++) {
                EDIFNet net = top.createNet(port.getBusName() + (port.getWidth() > 1 ? "[" + i + "]" : ""));
                int ii = port.getWidth() - 1 - i;
                if (port.isBus()) {
                    net.createPortInst(port.getBusName(), ii, inst);
                } else {
                    net.createPortInst(port, inst);
                }

                Net physNet = d.createNet(inst + "/" + net.getName());

                // Correct differences in physical pin names
                String busName = port.getBusName();
                if (busName.equals("ACOUT") || busName.equals("BCOUT")) {
                    busName = busName.replace("COUT", "COUT_B");
                } else if (busName.equals("PATTERNDETECT")) {
                    busName = "PATTERN_DETECT";
                } else if (busName.equals("PATTERNBDETECT")) {
                    busName = "PATTERN_B_DETECT";
                }

                physNet.createPin(busName + (port.getWidth() > 1 ? i : ""), si);
            }
        }

        Set<String> specialCases = new HashSet<>(Arrays.asList("ALUMODE10","AMULT26","BMULT17","P_FDBK_47","INMODE_2"));


        for (String dspCell : dspCells) {
            BEL elem = si.getSite().getBEL(dspCell);
            next_pin : for (int i=elem.getHighestInputIndex()+1; i < elem.getPins().length; i++) {
                BELPin outpin = elem.getPin(i);
                for (BELPin conn : outpin.getSiteConns()) {
                    if (conn.isSitePort()) continue next_pin;
                }
                String pinName = null;
                if (specialCases.contains(outpin.getName())) {
                    pinName = outpin.getName();
                } else if (outpin.getName().startsWith("A2A1")) {
                    pinName = outpin.getName().replace("A2A1", "A2A1<") + ">";
                } else if (outpin.getName().startsWith("B2B1")) {
                    pinName = outpin.getName().replace("B2B1", "B2B1<") + ">";
                } else {
                    pinName = StringTools.addIndexingAngleBrackets(outpin.getName());
                }

                Net n = d.createNet(designName + "/" + outpin.getBEL().getName() + "." + pinName);
                si.routeIntraSiteNet(n, outpin, outpin.getSiteConns().get(0));
            }
        }

        return new PBlock(d.getDevice(),origin.getName() +":"+origin.getName());
    }

    private static OptionParser createOptionParser() {
        // Defaults
        String partName = Device.AWS_F1;
        String designName = "mult";
        String outputDCPFileName = System.getProperty("user.dir") + File.separator + designName +".dcp";
        String clkName = "clk";
        double clkPeriodConstraint = 1.291; // 775 MHz
        int width = 16;//*30*5;
        String dspSite = "DSP48E2_X9Y60";
        boolean verbose = true;


        OptionParser p = new OptionParser() {{
            accepts(PART_OPT).withOptionalArg().defaultsTo(partName).describedAs("UltraScale+ Part Name");
            accepts(DESIGN_NAME_OPT).withOptionalArg().defaultsTo(designName).describedAs("Design Name");
            accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo(outputDCPFileName).describedAs("Output DCP File Name");
            accepts(CLK_NAME_OPT).withOptionalArg().defaultsTo(clkName).describedAs("Clk net name");
            accepts(CLK_CONSTRAINT_OPT).withOptionalArg().ofType(Double.class).defaultsTo(clkPeriodConstraint).describedAs("Clk period constraint (ns)");
            accepts(WIDTH_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(width).describedAs("Operand width");
            accepts(DSP_SITE_OPT).withOptionalArg().defaultsTo(dspSite).describedAs("DSP48 to be used");
            accepts(VERBOSE_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(verbose).describedAs("Print verbose output");
            acceptsAll( Arrays.asList(HELP_OPT, "?"), "Print Help" ).forHelp();
        }};

        return p;
    }

    private static void printHelp(OptionParser p) {
        MessageGenerator.printHeader("Multiplier Generator");
        System.out.println("This RapidWright program creates a placed and routed DCP that can be \n"
            + "imported into UltraScale+ designs that will perform integer multiplication.  See \n"
            + "RapidWright documentation for more information.\n");
        try {
            p.accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo("mult.dcp").describedAs("Output DCP File Name");
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
        if (opts.has(HELP_OPT)) {
            printHelp(p);
            return;
        }
        CodePerfTracker t = verbose ? new CodePerfTracker(MultGenerator.class.getSimpleName(),true).start("Init") : null;

        String partName = (String) opts.valueOf(PART_OPT);
        String designName = (String) opts.valueOf(DESIGN_NAME_OPT);
        String outputDCPFileName = (String) opts.valueOf(OUT_DCP_OPT);
        String clkName = (String) opts.valueOf(CLK_NAME_OPT);
        double clkPeriodConstraint = (double) opts.valueOf(CLK_CONSTRAINT_OPT);
        int width = (int) opts.valueOf(WIDTH_OPT);
        String dspName = (String) opts.valueOf(DSP_SITE_OPT);

        // Perform some error checking on inputs
        Part part = PartNameTools.getPart(partName);
        if (part == null || part.isSeries7()) {
            throw new RuntimeException("ERROR: Invalid/unsupport part " + partName + ".");
        }

        Design d = new Design(designName,partName);
        d.setAutoIOBuffers(false);
        Device dev = d.getDevice();

        t.stop().start("Create Multiplier");
        Site dsp = dev.getSite(dspName);
        createMult(d, dsp, width, designName, clkName);

        // Add a clock constraint
        String tcl = "create_clock -name "+clkName+" -period "+clkPeriodConstraint+" [get_ports "+clkName+"]";
        d.addXDCConstraint(ConstraintGroup.LATE, tcl);
        d.setAutoIOBuffers(false);

        t.stop();

        d.writeCheckpoint(outputDCPFileName, t);
        if (verbose) System.out.println("Wrote final DCP: " + outputDCPFileName);
    }
}
