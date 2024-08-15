/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.handplacer.HandPlacer;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.StringTools;

public class PolynomialGenerator {

    private static Map<String,Module> operators;

    private static String CLK_NAME = "clk";
    private static String RESULT_NAME = "result";
    private static String MULT_NAME = "mult";
    private static int instanceCount = 0;

    public static int dspx = 12;
    public static int dspy = 72;
    public static int slicex = 101;
    public static int slicey = 180;

    public static String partName = "xcvu3p-ffvc1517-2-e";

    public static void buildOperatorTree(String[] tokens, Design d, EDIFPortInst[] resultPortInsts) {
        int width = resultPortInsts.length;
        if (tokens.length == 1) {
            String name = tokens[0];
            EDIFCell top = d.getTopEDIFCell();
            if (StringTools.isInteger(name)) {
                int value = Integer.parseInt(name);
                EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, top, d.getNetlist());
                EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, top, d.getNetlist());
                for (int i=0; i < width; i++) {
                    int bit = (value >> i) & 0x1;
                    EDIFNet src = bit == 1 ? vcc : gnd;
                    src.addPortInst(resultPortInsts[width - 1 - i]);
                }
                return;
            }
            EDIFPortInst[] srcs = null;
            if (top.getPort(name) == null) {
                srcs = EDIFTools.createPortInsts(top, name, EDIFDirection.INPUT, resultPortInsts.length);
            }
            for (int i=0; i < width; i++) {
                String netName = name + "[" + i + "]";
                EDIFNet net = top.getNet(netName);
                if (net == null) {
                    net = top.createNet(netName);
                    net.addPortInst(srcs[i]);
                }
                net.addPortInst(resultPortInsts[i]);
            }
            return;
        }

        ArrayList<Integer> addSubOps = new ArrayList<>();
        ArrayList<Integer> multOps = new ArrayList<>();
        for (int i=0; i < tokens.length; i++) {
            if (tokens[i].equals("+") || tokens[i].equals("-")) {
                addSubOps.add(i);
            } else if (tokens[i].equals("*")) {
                multOps.add(i);
            }
        }

        int middleOp = addSubOps.size() == 0 ? multOps.get(multOps.size() / 2) : addSubOps.get(addSubOps.size() / 2);
        String[] left = Arrays.copyOfRange(tokens, 0, middleOp);
        String[] right = Arrays.copyOfRange(tokens, middleOp+1, tokens.length);

        ModuleInst mi = instantiateOperator(d, tokens[middleOp], width);
        boolean isMult = tokens[middleOp].equals("*");
        String inA = isMult ? MultGenerator.INPUT_A_NAME : AddSubGenerator.INPUT_A_NAME;
        String inB = isMult ? MultGenerator.INPUT_B_NAME : AddSubGenerator.INPUT_B_NAME;
        String outResult = isMult ? MultGenerator.RESULT_NAME : AddSubGenerator.RESULT_NAME;
        EDIFCell top = d.getNetlist().getTopCell();
        EDIFPortInst[] inputA = EDIFTools.createPortInsts(inA, EDIFDirection.INPUT, width, mi.getCellInst());
        EDIFPortInst[] inputB = EDIFTools.createPortInsts(inB, EDIFDirection.INPUT, width, mi.getCellInst());
        for (int i=0; i < width; i++) {
            int ii = width - 1 - i;
            String netName = RESULT_NAME + "_" + (instanceCount++) + "[" + ii + "]";
            EDIFNet net = top.createNet(netName);
            net.addPortInst(resultPortInsts[i]);
            net.createPortInst(outResult, i, mi.getCellInst());
        }
        if (!isMult) {
            EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, top, d.getNetlist());
            vcc.createPortInst("rst", mi.getCellInst());
            vcc.createPortInst("ce",mi.getCellInst());
        }


        buildOperatorTree(left,d,inputA);
        buildOperatorTree(right,d,inputB);
    }

    private static void ensureCellTypesSet(Module module) {
        for (SiteInst si : module.getSiteInsts()) {
            for (Cell c : si.getCells()) {
                if (c.isRoutethru())
                    continue;
                c.setType(c.getType());
            }
        }
    }


    public static Map<String,Module> initializeOperators(Design d, int width) {
        operators = new HashMap<>();
        CodePerfTracker silent = new CodePerfTracker("", false, false);
        silent.setVerbose(false);

        Design multDesign = new Design(MULT_NAME, d.getPartName());
        Site origin = d.getDevice().getSite("DSP48E2_X" + dspx + "Y" + dspy);
        MultGenerator.createMult(multDesign, origin, width, MULT_NAME, CLK_NAME);

        Module mult = new Module(multDesign);
        ensureCellTypesSet(mult);
        mult.setNetlist(multDesign.getNetlist());

        operators.put("*", mult);

        Design multDesign2 = new Design(MULT_NAME+2, d.getPartName());
        origin = d.getDevice().getSite("DSP48E2_X" + dspx + "Y" + (dspy+1));
        MultGenerator.createMult(multDesign2, origin, width, MULT_NAME, CLK_NAME);
        Module mult2 = new Module(multDesign2);
        ensureCellTypesSet(mult2);
        mult2.setNetlist(multDesign2.getNetlist());

        operators.put("*o", mult2);

        Design adderDesign = new Design("add" + width, d.getPartName());
        origin = d.getDevice().getSite("SLICE_X"+slicex+"Y"+slicey);
        AddSubGenerator.createAddSub(adderDesign, origin, width, false, true, true);
        Module add = new Module(adderDesign);
        add.setNetlist(adderDesign.getNetlist());
        operators.put("+", add);

        Design adderDesign2 = new Design("add2" + width, d.getPartName());
        origin = d.getDevice().getSite("SLICE_X"+(slicex-1)+"Y"+slicey);
        AddSubGenerator.createAddSub(adderDesign2, origin, width, false, true, false);
        Module add2 = new Module(adderDesign2);
        add2.setNetlist(adderDesign2.getNetlist());
        operators.put("+o", add2);

        Design subDesign = new Design("sub" + width, d.getPartName());
        origin = d.getDevice().getSite("SLICE_X"+slicex+"Y"+slicey);
        AddSubGenerator.createAddSub(subDesign, origin, width, true, true, true);
        Module sub = new Module(subDesign);
        sub.setNetlist(subDesign.getNetlist());
        operators.put("-", sub);

        Design subDesign2 = new Design("sub2" + width, d.getPartName());
        origin = d.getDevice().getSite("SLICE_X"+(slicex-1)+"Y"+slicey);
        AddSubGenerator.createAddSub(subDesign2, origin, width, true, true, false);
        Module sub2 = new Module(subDesign2);
        sub.setNetlist(subDesign2.getNetlist());
        operators.put("-o", sub2);

        return operators;
    }

    public static void releaseOperators() {
        operators = null;
    }

    public static int sliceyOther;
    public static boolean setSliceY = false;

    private static void placeModuleInst(ModuleInst mi, String type, int bitWidth) {
        if (!setSliceY) {
            sliceyOther = slicey;
            setSliceY = true;
        }
        String siteName = null;
        SiteTypeEnum sType = null;
        if (type.startsWith("*")) {
            siteName = "DSP48E2_X" + dspx + "Y" + dspy;
            dspy += /*(dspx % 2 == 1) ? -2 :*/ 1;
            if (dspy == 204) {
                dspx++;
                dspy = 202;
            }
            if (dspy < 0) {
                dspx++;
                dspy+=2;
            }

            sType = SiteTypeEnum.DSP48E2;
        } else {
            siteName = "SLICE_X" + slicex + "Y" + (type.endsWith("o") ? sliceyOther : slicey);
            if (type.endsWith("o"))
                sliceyOther += sliceHeights[bitWidth];
            else
                slicey += sliceHeights[bitWidth];
            sType = SiteTypeEnum.SLICEL;
        }
        Site site = mi.getDesign().getDevice().getSite(siteName);
        mi.placeMINearTile(site.getTile(), sType);
    }

    private static int[] sliceHeights = new int[] { 0, 2, 2, 3, 3, 3, 4, 4, 4, 4, 4, 4, 5, 5, 5, 5, 5, 6, 6 };

    public static boolean oddDSP = true;
    public static boolean oddAddSub = true;

    private static ModuleInst instantiateOperator(Design d, String type, int width) {
        if (type.equals("*")) {
            oddDSP = !oddDSP;
            type = type + (oddDSP ? "o" : "");
        } else {
            oddAddSub = !oddAddSub;
            type = type + (oddAddSub ? "o" : "");
        }
        Module m = operators.get(type);
        EDIFCell top = d.getNetlist().getTopCell();
        String name = m.getName() + "_" + instanceCount;
        EDIFCellInst ci = top.createChildCellInst(name, m.getNetlist().getTopCell());
        ModuleInst mi = d.createModuleInst(name, m);
        mi.setCellInst(ci);
        placeModuleInst(mi, type, width);
        instanceCount++;


        top.getNet(CLK_NAME).createPortInst(CLK_NAME, ci);
        if (!type.startsWith("*")) {
            EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, top, d.getNetlist());
            vcc.createPortInst("rst", ci);
            vcc.createPortInst("ce", ci);
        } else {
            EDIFCellInst dsp = mi.getCellInst().getCellType().getCellInst(MULT_NAME);
            SiteInst si = mi.getSiteInsts().get(0);
            for (EDIFPortInst p : dsp.getPortInsts()) {
                if (p.getName().startsWith("ACIN") || p.getName().startsWith("BCIN") || p.getName().startsWith("PCIN")) continue;
                if (p.getName().startsWith("CARRYCASCIN") || p.getName().startsWith("MULTSIGNIN") ) continue;
                if (p.getNet().getName().equals(EDIFTools.LOGICAL_VCC_NET_NAME)) {
                    String portName = p.getName().replace("[", "").replace("]", "");
                    if (si.getSitePinInst(portName) == null) {
                        d.getVccNet().createPin(portName, si);
                    }
                }
            }
        }

        return mi;
    }

    private static String convPowerToMultiply(String s) {
        String[] parts = s.split("\\^");
        String var = parts[0];
        int power = Integer.parseInt(parts[1]);
        if (power == 0) return "";
        StringBuilder sb = new StringBuilder(var);
        for (int i=1; i < power; i++) {
            sb.append("*");
            sb.append(var);
        }
        return sb.toString();
    }

    public static String[] parsePolynomial(String p) {
        if (!Character.isDigit(p.charAt(0)) && !Character.isLetter(p.charAt(0))) {
            throw new RuntimeException("ERROR: First character must be alphanumeric (don't start with negatives)");
        }

        // Replace ^ with n*
        ArrayList<String> powers = new ArrayList<>();
        boolean inExponent = false;
        int startExponent = 0;
        boolean lastWasAlpha = false;
        for (int i=0; i < p.length(); i++) {
            char ch = p.charAt(i);
            if (Character.isLetter(ch)) {
                if (lastWasAlpha)
                    throw new RuntimeException("ERROR: Only supports variables names of length one");
                else
                    lastWasAlpha = true;
            } else {
                lastWasAlpha = false;
            }
            if (inExponent && !Character.isDigit(ch)) {
                powers.add(p.substring(startExponent, i));
                inExponent = false;
            }
            if (p.charAt(i) == '^') {
                inExponent = true;
                startExponent = i-1;
            }
        }
        for (String power : powers) {
            p = p.replace(power, convPowerToMultiply(power));
        }

        String delimiters = "((?<=%1$s)|(?=%1$s))";
        String delimiter_list = "[\\+\\-\\*]";
        return p.split(String.format(delimiters, delimiter_list));
    }

    public static Design generatePolynomial(String polynomial, int width, boolean route) {
        return generatePolynomial(polynomial, "polygen", width, route, "out.dcp", null, false);
    }

    public static Design generatePolynomial(String polynomial, String name, int width, boolean route, String outputDCP,
            PBlock pblock, boolean invokeHandPlacer) {
        int init_dspx = dspx;
        int init_dspy = dspy;
        int init_slicex = slicex;
        int init_slicey = slicey;


        CodePerfTracker t = new CodePerfTracker("Polynomial Generator", true);
        t.start("Load Device");

        String[] p = parsePolynomial(polynomial);

        Design d = new Design(name,partName);
        d.setAutoIOBuffers(false);

        t.stop().start("Init Operators");

        initializeOperators(d, width);
        EDIFCell top = d.getNetlist().getTopCell();
        EDIFNetlist n = d.getNetlist();

        EDIFPort clkPort = top.createPort(CLK_NAME, EDIFDirection.INPUT, 1);
        EDIFNet clk = top.createNet(CLK_NAME);
        clk.createPortInst(clkPort);

        EDIFPortInst[] results = EDIFTools.createPortInsts(top, RESULT_NAME, EDIFDirection.OUTPUT, width);

        t.stop().start("Build Operator Tree");

        buildOperatorTree(p, d, results);

        releaseOperators();

        d.addXDCConstraint(ConstraintGroup.LATE, "create_clock -name "+CLK_NAME+" -period 1.291 [get_ports "+CLK_NAME+"]");
        d.addXDCConstraint(ConstraintGroup.LATE, "set_property HD.CLK_SRC BUFGCE_X0Y18 [get_ports "+CLK_NAME+"]");

        if (invokeHandPlacer) {
            t.stop().start("Hand Placer");
            HandPlacer.openDesign(d);
        }

        if (route) {
            t.stop().start("RWRoute");
            d.flattenDesign();
            RWRoute.routeDesignFullNonTimingDriven(d);
        }

        t.stop();
        if (outputDCP != null) {
            t.start("Write DCP");
            CodePerfTracker tt = new CodePerfTracker("",false,false);
            d.writeCheckpoint(outputDCP,tt);
            t.stop().printSummary();
            System.out.println("Wrote DCP: " + outputDCP);
        }

        // Reset
        dspx = init_dspx;
        dspy = init_dspy;
        slicex = init_slicex;
        slicey = init_slicey;

        return d;
    }

    private static final String HAND_PLACER_OPT = "--hand-placer";

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            System.out.println("USAGE: <polynomial> <bit width 1 to 18> [" + HAND_PLACER_OPT + "]");
            return;
        }
        String polynomial = args[0];
        int bitWidth = Integer.parseInt(args[1]);
        boolean invokeHandPlacer = false;
        if (args.length == 3) {
            if (args[2].equals(HAND_PLACER_OPT)) {
                invokeHandPlacer = true;
            } else {
                throw new RuntimeException("Unrecognized option: " + args[2]);
            }
        }
        if (bitWidth < 1 || bitWidth > 18) {
            throw new RuntimeException("ERROR: Bit width " + bitWidth
                    + " is unsupported, please choose a bit width between 1 and 18 bits.");
        }

        generatePolynomial(polynomial, "polynomial", bitWidth, true, "polynomial.dcp", null, invokeHandPlacer);
    }
}
