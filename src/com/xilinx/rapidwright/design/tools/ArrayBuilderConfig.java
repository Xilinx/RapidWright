/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
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

package com.xilinx.rapidwright.design.tools;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A collection of customizable parameters for a {@link ArrayBuilder} Object.
 * Modifications of default parameter values can be done by adding corresponding
 * options with values to the arguments or by calling the applicable setter method.
 */
public class ArrayBuilderConfig {

    private Design kernelDesign;

    private Design topDesign;

    private double clkPeriod;

    private String kernelClkName;

    private String topClkName;

    private boolean skipImpl;

    private int instCountLimit;

    private String outputPlacementFileName;

    private String inputPlacementFileName;

    private String outputPlacementLocsFileName;

    private boolean outOfContext;

    private boolean exactPlacement;

    private boolean unrouteStaticNets;

    private boolean routeClock;

    private boolean routeDesign;

    private String sideMapFile;

    private String workDir;

    private boolean reuseResults;

    private Part part;

    private String[] pblockStrings;

    private String utilReport;

    private String shapesReport;

    private static final double DEFAULT_CLK_PERIOD_TARGET = 2.0;

    private static final List<String> KERNEL_DESIGN_OPTS = Arrays.asList("i", "input");
    private static final List<String> OUTPUT_DESIGN_OPTS = Arrays.asList("o", "output");
    private static final List<String> INPUT_EDIF_OPTS = Arrays.asList("e", "edif");
    private static final List<String> UTILIZATION_OPTS = Arrays.asList("u", "utilization");
    private static final List<String> SHAPES_OPTS = Arrays.asList("s", "shapes");
    private static final List<String> PART_OPTS = Arrays.asList("p", "part");
    private static final List<String> PBLOCK_OPTS = Arrays.asList("b", "pblock");
    private static final List<String> HELP_OPTS = Arrays.asList("?", "h", "help");
    private static final List<String> TARGET_CLK_PERIOD_OPTS = Arrays.asList("c", "clk-period");
    private static final List<String> KERNEL_CLK_NAME_OPTS = Arrays.asList("n", "kernel-clk-name");
    private static final List<String> TOP_CLK_NAME_OPTS = Arrays.asList("m", "top-clk-name");
    private static final List<String> REUSE_RESULTS_OPTS = Arrays.asList("r", "reuse");
    private static final List<String> SKIP_IMPL_OPTS = Arrays.asList("k", "skip-impl");
    private static final List<String> LIMIT_INSTS_OPTS = Arrays.asList("l", "limit-inst-count");
    private static final List<String> TOP_LEVEL_DESIGN_OPTS = Arrays.asList("t", "top-design");
    private static final List<String> EXACT_PLACEMENT_OPTS = Collections.singletonList("exact-placement");
    private static final List<String> WRITE_PLACEMENT_OPTS = Collections.singletonList("write-placement");
    private static final List<String> PLACEMENT_FILE_OPTS = Collections.singletonList("read-placement");
    private static final List<String> PLACEMENT_GRID_OPTS = Collections.singletonList("write-placement-grid");
    private static final List<String> OUT_OF_CONTEXT_OPTS = Collections.singletonList("out-of-context");
    private static final List<String> UNROUTE_STATIC_NETS_OPTS = Collections.singletonList("unroute-static-nets");
    private static final List<String> ROUTE_CLOCK_OPTS = Collections.singletonList("route-clock-only");
    private static final List<String> ROUTE_DESIGN_OPTS = Collections.singletonList("route");
    private static final List<String> SIDE_MAP_OPTS = Collections.singletonList("kernel-side-map");

    private ArrayBuilderConfig() {
        clkPeriod = DEFAULT_CLK_PERIOD_TARGET;
        skipImpl = true;
        instCountLimit = Integer.MAX_VALUE;
        outOfContext = true;
        exactPlacement = false;
        unrouteStaticNets = false;
        routeClock = true;
        routeDesign = false;
        reuseResults = false;
        pblockStrings = null;
        workDir = "ArrayBuilder-" + FileTools.getTimeStamp().replace(" ", "-");
    }

    public ArrayBuilderConfig(Design kernelDesign, Design topDesign) {
        this();
        setKernelDesign(kernelDesign);
        setTopDesign(topDesign);
    }

    public ArrayBuilderConfig(String[] arguments) {
        this();
        parseArguments(arguments);
    }

    public static OptionParser createOptionParser() {
        return new OptionParser() {
            {
                acceptsAll(KERNEL_DESIGN_OPTS, "Input Kernel Design (*.dcp or *.edf)").withRequiredArg();
                acceptsAll(OUTPUT_DESIGN_OPTS, "Output Array Design (default is 'array.dcp')").withRequiredArg();
                acceptsAll(PBLOCK_OPTS, "PBlock Constraint(s), separated with ';'").withRequiredArg();
                acceptsAll(INPUT_EDIF_OPTS, "Companion EDIF for DCP  (*.edf)").withRequiredArg();
                acceptsAll(UTILIZATION_OPTS, "Vivado Generated Utilization Report").withRequiredArg();
                acceptsAll(SHAPES_OPTS, "Vivado Generated Shapes").withRequiredArg();
                acceptsAll(PART_OPTS, "Target AMD Part").withRequiredArg();
                acceptsAll(TARGET_CLK_PERIOD_OPTS, "Target Clock Period (ns)").withRequiredArg();
                acceptsAll(KERNEL_CLK_NAME_OPTS, "Kernel Clock Name").withRequiredArg();
                acceptsAll(TOP_CLK_NAME_OPTS, "Top Clock Name").withRequiredArg();
                acceptsAll(REUSE_RESULTS_OPTS, "Reuse Previous Implementation Results");
                acceptsAll(SKIP_IMPL_OPTS, "Skip Implementation of the Kernel");
                acceptsAll(LIMIT_INSTS_OPTS, "Limit number of instance copies").withRequiredArg();
                acceptsAll(WRITE_PLACEMENT_OPTS, "Write the chosen placement to the specified file").withRequiredArg();
                acceptsAll(PLACEMENT_FILE_OPTS, "Use placement specified in file").withRequiredArg();
                acceptsAll(PLACEMENT_GRID_OPTS, "Write grid of possible placement locations to specified file").withRequiredArg();
                acceptsAll(TOP_LEVEL_DESIGN_OPTS, "Top level design with blackboxes/kernel insts").withRequiredArg();
                acceptsAll(EXACT_PLACEMENT_OPTS, "Use exact module overlap calculation instead of the faster bounding-box method");
                acceptsAll(OUT_OF_CONTEXT_OPTS, "Specifies that the array will be compiled out of context");
                acceptsAll(UNROUTE_STATIC_NETS_OPTS, "Unroute static (GND/VCC) nets to potentially help with routability");
                acceptsAll(ROUTE_CLOCK_OPTS, "Route clock using RWRoute");
                acceptsAll(ROUTE_DESIGN_OPTS, "Route the built array using RWRoute");
                acceptsAll(SIDE_MAP_OPTS, "Provide a text file specifying which side of the pblock each " +
                        "top-level port routes to. Used to place array optimally for routability.").withRequiredArg();
                acceptsAll(HELP_OPTS, "Print this help message").forHelp();
            }
        };
    }

    public static void printHelp() {
        OptionParser p = createOptionParser();
        MessageGenerator.printHeader("ArrayBuilder");
        System.out.println("Generates an optimized, implemented array of the provided kernel.");
        try {
            p.printHelpOn(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void parseArguments(String[] arguments) {
        OptionParser p = createOptionParser();
        OptionSet options = p.parse(arguments);

        setSkipImpl(options.has(SKIP_IMPL_OPTS.get(0)));
        setOutOfContext(options.has(OUT_OF_CONTEXT_OPTS.get(0)));
        setExactPlacement(options.has(EXACT_PLACEMENT_OPTS.get(0)));
        setUnrouteStaticNets(options.has(UNROUTE_STATIC_NETS_OPTS.get(0)));
        setRouteClock(options.has(ROUTE_CLOCK_OPTS.get(0)));
        setRouteDesign(options.has(ROUTE_DESIGN_OPTS.get(0)));
        setSideMapFile((String) options.valueOf(SIDE_MAP_OPTS.get(0)));

        if (options.has(PART_OPTS.get(0))) {
            setPart(PartNameTools.getPart((String) options.valueOf(PART_OPTS.get(0))));
        }

        String kernelDesignPath;
        if (options.has(KERNEL_DESIGN_OPTS.get(0))) {
            kernelDesignPath = (String) options.valueOf(KERNEL_DESIGN_OPTS.get(0));
        } else {
            throw new RuntimeException("No input design found. "
                    + "Please specify an input kernel (*.dcp or *.edf) using options "
                    + KERNEL_DESIGN_OPTS);
        }

        Path inputFile = Paths.get(kernelDesignPath);
        if (inputFile.toString().endsWith(".dcp")) {
            if (options.has(INPUT_EDIF_OPTS.get(0))) {
                Path companionEDIF = Paths.get((String) options.valueOf(INPUT_EDIF_OPTS.get(0)));
                setKernelDesign(Design.readCheckpoint(inputFile, companionEDIF, CodePerfTracker.SILENT));
            } else {
                setKernelDesign(Design.readCheckpoint(inputFile));
                EDIFTools.removeVivadoBusPreventionAnnotations(getKernelDesign().getNetlist());
                if (!kernelDesign.getNetlist().getEncryptedCells().isEmpty()) {
                    System.out.println("Design has encrypted cells");
                } else {
                    System.out.println("Design does not have encrypted cells");
                }
            }
        } else if (inputFile.toString().endsWith(".edf")) {
            EDIFNetlist netlist = EDIFTools.readEdifFile(inputFile);
            if (options.has(PART_OPTS.get(0))) {
                EDIFTools.ensureCorrectPartInEDIF(netlist, getPart().toString());
            }
            setKernelDesign(new Design(netlist));
        }

        if (options.has(TOP_LEVEL_DESIGN_OPTS.get(0))) {
            Design d = Design.readCheckpoint((String) options.valueOf(TOP_LEVEL_DESIGN_OPTS.get(0)));
            setTopDesign(d);
        }

        if (options.has(TARGET_CLK_PERIOD_OPTS.get(0))) {
            setClockPeriod(Double.parseDouble((String) options.valueOf(TARGET_CLK_PERIOD_OPTS.get(0))));
        } else {
            setClockPeriod(DEFAULT_CLK_PERIOD_TARGET);
            System.out.println("[INFO] No clock period set, defaulting to: " + getClockPeriod() + "ns");
        }

        if (options.has(LIMIT_INSTS_OPTS.get(0))) {
            setInstCountLimit(Integer.parseInt((String) options.valueOf(LIMIT_INSTS_OPTS.get(0))));
        }

        if (options.has(WRITE_PLACEMENT_OPTS.get(0))) {
            setOutputPlacementFileName((String) options.valueOf(WRITE_PLACEMENT_OPTS.get(0)));
        }

        if (options.has(PLACEMENT_FILE_OPTS.get(0))) {
            setInputPlacementFileName((String) options.valueOf(PLACEMENT_FILE_OPTS.get(0)));
        }

        if (options.has(PLACEMENT_GRID_OPTS.get(0))) {
            setOutputPlacementLocsFileName((String) options.valueOf(PLACEMENT_GRID_OPTS.get(0)));
        }

        if (options.has(PBLOCK_OPTS.get(0))) {
            String pblockString = (String) options.valueOf(PBLOCK_OPTS.get(0));
            setPBlockStrings(pblockString.split(";"));
        }

        if (options.has(UTILIZATION_OPTS.get(0)) && options.has(SHAPES_OPTS.get(0))) {
            setUtilReport((String) options.valueOf(UTILIZATION_OPTS.get(0)));
            setShapesReport((String) options.valueOf(SHAPES_OPTS.get(0)));
        }

        if (options.has(KERNEL_CLK_NAME_OPTS.get(0))) {
            setKernelClockName(((String) options.valueOf(KERNEL_CLK_NAME_OPTS.get(0))));
        }

        if (options.has(TOP_CLK_NAME_OPTS.get(0))) {
            setTopClockName(((String) options.valueOf(TOP_CLK_NAME_OPTS.get(0))));
        }

        if (options.has(REUSE_RESULTS_OPTS.get(0))) {
            setWorkDir((String) options.valueOf(REUSE_RESULTS_OPTS.get(0)));
        }
    }

    public static boolean hasHelpArg(String[] arguments) {
        OptionParser p = createOptionParser();
        OptionSet options = p.parse(arguments);
        return options.has(HELP_OPTS.get(0));
    }

    public static String getOutputName(String[] args) {
        OptionParser p = createOptionParser();
        OptionSet options = p.parse(args);
        if (options.has(OUTPUT_DESIGN_OPTS.get(0))) {
            return (String) options.valueOf(OUTPUT_DESIGN_OPTS.get(0));
        }

        return "array.dcp";
    }


    public void setKernelDesign(Design kernelDesign) {
        this.kernelDesign = kernelDesign;
    }

    public Design getKernelDesign() {
        return kernelDesign;
    }

    public Design getTopDesign() {
        return topDesign;
    }

    public void setTopDesign(Design topDesign) {
        this.topDesign = topDesign;
    }

    public void setClockPeriod(double clkPeriod) {
        this.clkPeriod = clkPeriod;
    }

    public double getClockPeriod() {
        return clkPeriod;
    }

    public void setKernelClockName(String clkName) {
        this.kernelClkName = clkName;
    }

    public String getKernelClockName() {
        return kernelClkName;
    }

    public void setTopClockName(String clkName) {
        this.topClkName = clkName;
    }

    public String getTopClockName() {
        return topClkName;
    }

    public boolean isSkipImpl() {
        return skipImpl;
    }

    public void setSkipImpl(boolean skipImpl) {
        this.skipImpl = skipImpl;
    }

    public int getInstCountLimit() {
        return instCountLimit;
    }

    public void setInstCountLimit(int instCountLimit) {
        this.instCountLimit = instCountLimit;
    }

    public String getOutputPlacementFileName() {
        return outputPlacementFileName;
    }

    public void setOutputPlacementFileName(String outputPlacementFileName) {
        this.outputPlacementFileName = outputPlacementFileName;
    }

    public String getInputPlacementFileName() {
        return inputPlacementFileName;
    }

    public void setInputPlacementFileName(String inputPlacementFileName) {
        this.inputPlacementFileName = inputPlacementFileName;
    }

    public String getOutputPlacementLocsFileName() {
        return outputPlacementLocsFileName;
    }

    public void setOutputPlacementLocsFileName(String outputPlacementLocsFileName) {
        this.outputPlacementLocsFileName = outputPlacementLocsFileName;
    }

    public boolean isOutOfContext() {
        return outOfContext;
    }

    public void setOutOfContext(boolean outOfContext) {
        this.outOfContext = outOfContext;
    }

    public boolean isExactPlacement() {
        return exactPlacement;
    }

    public void setExactPlacement(boolean exactPlacement) {
        this.exactPlacement = exactPlacement;
    }

    public boolean shouldUnrouteStaticNets() {
        return unrouteStaticNets;
    }

    public void setUnrouteStaticNets(boolean unrouteStaticNets) {
        this.unrouteStaticNets = unrouteStaticNets;
    }

    public boolean isRouteClock() {
        return routeClock;
    }

    public void setRouteClock(boolean routeClock) {
        this.routeClock = routeClock;
    }

    public boolean isRouteDesign() {
        return routeDesign;
    }

    public void setRouteDesign(boolean routeDesign) {
        this.routeDesign = routeDesign;
    }

    public String getSideMapFile() {
        return sideMapFile;
    }

    public void setSideMapFile(String sideMapFile) {
        this.sideMapFile = sideMapFile;
    }

    public Part getPart() {
        return part;
    }

    public void setPart(Part part) {
        this.part = part;
    }

    public String[] getPBlockStrings() {
        return pblockStrings;
    }

    public void setPBlockStrings(String[] pblockStrings) {
        this.pblockStrings = pblockStrings;
    }

    public String getUtilReport() {
        return utilReport;
    }

    public void setUtilReport(String utilReport) {
        this.utilReport = utilReport;
    }

    public String getShapesReport() {
        return shapesReport;
    }

    public void setShapesReport(String shapesReport) {
        this.shapesReport = shapesReport;
    }

    public String getWorkDir() {
        return workDir;
    }

    public void setWorkDir(String workDir) {
        this.workDir = workDir;
    }

    public boolean isReuseResults() {
        return reuseResults;
    }

    public void setReuseResults(boolean reuseResults) {
        this.reuseResults = reuseResults;
    }
}
