/*
 * Copyright (c) 2019-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.Port;
import com.xilinx.rapidwright.design.PortType;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import static com.xilinx.rapidwright.examples.ArithmeticGenerator.INPUT_A_NAME;
import static com.xilinx.rapidwright.examples.ArithmeticGenerator.INPUT_B_NAME;
import static com.xilinx.rapidwright.examples.ArithmeticGenerator.RESULT_NAME;

/**
 * A class that implements a counter using the
 */
public class ParamCounterModule {

    protected static final String PART_OPT = "p";
    protected static final String DESIGN_NAME_OPT = "d";
    protected static final String ADDER_NAME_OPT = "a";
    protected static final String OUT_DCP_OPT = "o";
    protected static final String CLK_NAME_OPT = "c";
    protected static final String CLK_CONSTRAINT_OPT = "x";
    protected static final String WIDTH_OPT = "w";
    protected static final String VERBOSE_OPT = "v";
    protected static final String HELP_OPT = "h";
    private static final String SLICE_SITES_OPT = "s";
    private static final String COUNT_DOWN_OPT = "m";
    private static final String STEP_OPT = "t";
    private static final String INIT_OPT = "i";

    private static OptionParser createOptionParser() {
        // Defaults
        String partName = "xczu3eg-sbva484-1-i";
        String designName = "counter";
        String adderName = "adder";
        String outputDCPFileName = System.getProperty("user.dir") + File.separator + designName +".dcp";
        String clkName = "clk";
        double clkPeriodConstraint = 1.291; // 775 MHz
        int width = 32;
        String sliceSite = "SLICE_X3Y3";
        boolean verbose = true;
        boolean countDown = false;
        long initValue = 0;
        long step = 1;

        OptionParser p = new OptionParser() {{
            accepts(PART_OPT).withOptionalArg().defaultsTo(partName).describedAs("UltraScale+ Part Name");
            accepts(DESIGN_NAME_OPT).withOptionalArg().defaultsTo(designName).describedAs("Design Name");
            accepts(DESIGN_NAME_OPT).withOptionalArg().defaultsTo(designName).describedAs("Name of counter design");
            accepts(ADDER_NAME_OPT).withOptionalArg().defaultsTo(adderName).describedAs("Name of adder submodule");
            accepts(OUT_DCP_OPT).withOptionalArg().defaultsTo(outputDCPFileName).describedAs("Output DCP File Name");
            accepts(CLK_NAME_OPT).withOptionalArg().defaultsTo(clkName).describedAs("Clk net name");
            accepts(CLK_CONSTRAINT_OPT).withOptionalArg().ofType(Double.class).defaultsTo(clkPeriodConstraint).describedAs("Clk period constraint (ns)");
            accepts(WIDTH_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(width).describedAs("Operand width");
            accepts(SLICE_SITES_OPT).withOptionalArg().defaultsTo(sliceSite).describedAs("Lower left slice to be used for adder/subtracter");
            accepts(COUNT_DOWN_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(countDown).describedAs("Counts down instead of up");
            accepts(STEP_OPT).withOptionalArg().ofType(Long.class).defaultsTo(step).describedAs("Counts down instead of up");
            accepts(INIT_OPT).withOptionalArg().ofType(Long.class).defaultsTo(initValue).describedAs("Counts down instead of up");
            accepts(VERBOSE_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(verbose).describedAs("Print verbose output");
            acceptsAll( Arrays.asList(HELP_OPT, "?"), "Print Help" ).forHelp();
        }};

        return p;
    }

    private static void printHelp(OptionParser p) {
        MessageGenerator.printHeader("Adder/Subtractor Generator");
        System.out.println("This RapidWright program creates a placed and routed DCP for UltraScale+ devices that contains \n"
                + "imported into UltraScale+ designs to perform addition or subtraction.  See \n"
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
        if (opts.has(HELP_OPT)) {
            printHelp(p);
            return;
        }

        CodePerfTracker t = verbose ? new CodePerfTracker(AddSubGenerator.class.getSimpleName(),true).start("Init") : null;

        String partName = (String) opts.valueOf(PART_OPT);
        String designName = (String) opts.valueOf(DESIGN_NAME_OPT);
        String adderName = (String) opts.valueOf(ADDER_NAME_OPT);
        String outputDCPFileName = (String) opts.valueOf(OUT_DCP_OPT);
        String clkName = (String) opts.valueOf(CLK_NAME_OPT);
        double clkPeriodConstraint = (double) opts.valueOf(CLK_CONSTRAINT_OPT);
        int width = (int) opts.valueOf(WIDTH_OPT);
        String sliceName = (String) opts.valueOf(SLICE_SITES_OPT);
        boolean countDown = (boolean) opts.valueOf(COUNT_DOWN_OPT);
        long step = (long) opts.valueOf(STEP_OPT);
        long initValue = (long) opts.valueOf(INIT_OPT);


        // Perform some error checking on inputs
        Part part = PartNameTools.getPart(partName);
        if (part == null || part.isSeries7()) {
            throw new RuntimeException("ERROR: Invalid/unsupport part " + partName + ".");
        }

        if (verbose) t.stop().start("Create Add/Sub Module");

        Design adderDesign = new Design(adderName, partName);

        adderDesign.setAutoIOBuffers(false);
        Device dev = adderDesign.getDevice();

        Site slice = dev.getSite(sliceName);
        AddSubGenerator.createAddSub(adderDesign, slice, width, countDown, false, true, false);

        //set init value on counter
        String initBin = Long.toBinaryString(initValue);
        String initFormatStr = "%0"+(width-initBin.length()) + "d%s";
        initBin = String.format(initFormatStr,  0, initBin);
        String invInitBin = new StringBuilder(initBin).reverse().toString();
        for (int i = 0; i < width; i++) {
            EDIFCellInst ff = adderDesign.getTopEDIFCell().getCellInst("sum"+i);
            ff.addProperty("INIT", "1'b" + invInitBin.charAt(i));
        }

        // Add a clock constraint
        String tcl = "create_clock -name "+clkName+" -period "+clkPeriodConstraint+" [get_ports "+clkName+"]";
        adderDesign.addXDCConstraint(ConstraintGroup.LATE,tcl);
        adderDesign.setAutoIOBuffers(false);

        // Create module for adder and populate ports
        Module adderModule = new Module(adderDesign);
        for (Net net : adderModule.getNets()) {
            EDIFNet edifNet = net.getLogicalNet();
            for(EDIFPortInst portInst : edifNet.getPortInsts()) {
                if (portInst.isTopLevelPort()) {
                    Port port = new Port(portInst.getName(), net.getPins());
                    port.setType(PortType.SIGNAL);
                    adderModule.addPort(port);
                }
            }
        }

        if (verbose) t.stop().start("Create Counter");

        // Create design for counter
        Design cntrDesign = new Design(designName, partName);
        cntrDesign.setAutoIOBuffers(false);

        // Add adder as submodule and replace.
        ModuleInst mi = cntrDesign.createModuleInst(adderName, adderModule);
        mi.placeOnOriginalAnchor();

        EDIFCell cntrTop = cntrDesign.getTopEDIFCell();
        String bus = "["+(width-1)+":0]";

        // Create port for the output of the counter.
        EDIFPort outPort = cntrTop.createPort("cntrOut" + bus, EDIFDirection.OUTPUT, width);

        // Get gnd and static nets to connect to b port of adder
        EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, cntrTop, cntrDesign.getNetlist());
        EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, cntrTop, cntrDesign.getNetlist());

        //busNames for adder ports
        String aBusName = adderDesign.getTopEDIFCell().getPort(INPUT_A_NAME + "[").getBusName();
        String bBusName = adderDesign.getTopEDIFCell().getPort(INPUT_B_NAME + "[").getBusName();
        String resultBusName = adderDesign.getTopEDIFCell().getPort(RESULT_NAME + "[").getBusName();

        String stepBin = Long.toBinaryString(step);
        String stepFormatStr = "%0"+(width-stepBin.length()) + "d%s";
        stepBin = String.format(stepFormatStr,  0, stepBin);
        EDIFCellInst adderCell = cntrTop.getCellInst(adderName);

        //connect ports of adder modules
        for(int i = 0; i<width; i++) {
            mi.connect(resultBusName, i, mi, aBusName, i); // Connect output of adder to one of the input ports of adder
            EDIFNet net = cntrTop.getNet(mi.getNewNetName(resultBusName, i, mi, aBusName, i));
            net.createPortInst(outPort, i); // Attach output port to newly created top-level net

            // Connect this bit of b port on adder to either vcc or gnd depending on current bit of the step parameter
            EDIFNet staticNet = stepBin.charAt(i) == '1' ? vcc : gnd;
            staticNet.createPortInst(bBusName, i, adderCell);
        }

        // Create top level ports for clk, ce, and rst lines and connect to equivalent adder ports
        EDIFPort clkPort = cntrTop.createPort(clkName, EDIFDirection.INPUT, 1);
        EDIFPort cePort = cntrTop.createPort("ce", EDIFDirection.INPUT, 1);
        EDIFPort rstPort = cntrTop.createPort("rst", EDIFDirection.INPUT, 1);
        mi.connect(clkName,  mi, clkName);
        mi.connect("ce",  mi, "ce");
        mi.connect("rst",  mi, "rst");
        // The names of the newly created, top level nets can are simply the module name + "_" + the port name.
        cntrTop.getNet(adderName + "_" + clkName).createPortInst(clkPort);
        cntrTop.getNet(adderName + "_ce").createPortInst(cePort);
        cntrTop.getNet(adderName + "_rst").createPortInst(rstPort);

        if (verbose) t.stop().start("Route Design");

        cntrDesign = RWRoute.routeDesignFullNonTimingDriven(cntrDesign);

        if (verbose) t.stop();
        cntrDesign.writeCheckpoint(outputDCPFileName);
        if (verbose) System.out.println("Wrote final DCP: " + outputDCPFileName);
    }
}
