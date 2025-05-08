/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ClockTools;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockGenerator;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.PerformanceExplorer;
import com.xilinx.rapidwright.util.VivadoTools;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * A Tool to optimize, place and route a kernel and then replicate its
 * implementation in an array across the fabric.
 */
public class ArrayBuilder {

    private static final List<String> INPUT_DESIGN_OPTS = Arrays.asList("i", "input");
    private static final List<String> OUTPUT_DESIGN_OPTS = Arrays.asList("o", "output");
    private static final List<String> INPUT_EDIF_OPTS = Arrays.asList("e", "edif");
    private static final List<String> UTILIZATION_OPTS = Arrays.asList("u", "utilization");
    private static final List<String> SHAPES_OPTS = Arrays.asList("s", "shapes");
    private static final List<String> PART_OPTS = Arrays.asList("p", "part");
    private static final List<String> PBLOCK_OPTS = Arrays.asList("b", "pblock");
    private static final List<String> HELP_OPTS = Arrays.asList("?", "h", "help");
    private static final List<String> TARGET_CLK_PERIOD_OPTS = Arrays.asList("c", "clk-period");
    private static final List<String> TARGET_CLK_NAME_OPTS = Arrays.asList("n", "clk-name");
    private static final List<String> REUSE_RESULTS_OPTS = Arrays.asList("r", "reuse");
    private static final List<String> SKIP_IMPL_OPTS = Arrays.asList("k", "skip-impl");

    private Design design;

    private List<PBlock> pblocks;

    private double clkPeriod;

    private String clkName;

    private boolean skipImpl;

    private String outputName;

    private CodePerfTracker t;

    public static final double DEFAULT_CLK_PERIOD_TARGET = 2.0;

    private OptionParser createOptionParser() {

        OptionParser p = new OptionParser() {
            {
                acceptsAll(INPUT_DESIGN_OPTS, "Input Kernel Design (*.dcp or *.edf)").withRequiredArg();
                acceptsAll(OUTPUT_DESIGN_OPTS, "Output Array Design (default is 'array.dcp')").withRequiredArg();
                acceptsAll(PBLOCK_OPTS, "PBlock Constraint(s), separated with ';'").withRequiredArg();
                acceptsAll(INPUT_EDIF_OPTS, "Companion EDIF for DCP  (*.edf)").withRequiredArg();
                acceptsAll(UTILIZATION_OPTS, "Vivado Generated Utilization Report").withRequiredArg();
                acceptsAll(SHAPES_OPTS, "Vivado Generated Shapes").withRequiredArg();
                acceptsAll(PART_OPTS, "Target AMD Part").withRequiredArg();
                acceptsAll(TARGET_CLK_PERIOD_OPTS, "Target Clock Period (ns)").withRequiredArg();
                acceptsAll(TARGET_CLK_NAME_OPTS, "Target Clock Name").withRequiredArg();
                acceptsAll(REUSE_RESULTS_OPTS, "Reuse Previous Implementation Results");
                acceptsAll(SKIP_IMPL_OPTS, "Skip Implementation of the Kernel");
                acceptsAll(HELP_OPTS, "Print this help message").forHelp();
            }
        };

        return p;
    }

    public ArrayBuilder() {

    }

    public ArrayBuilder(CodePerfTracker t) {
        this.t = t;
    }

    private static void printHelp(OptionParser p) {
        MessageGenerator.printHeader("ArrayBuilder");
        System.out.println("Generates an optimized, implemented array of the provided kernel.");
        try {
            p.printHelpOn(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Device getDevice() {
        return getDesign().getDevice();
    }

    public void setDesign(Design design) {
        this.design = design;
    }

    public Design getDesign() {
        return design;
    }

    public void setPBlocks(List<PBlock> pblocks) {
        this.pblocks = pblocks;
    }

    public List<PBlock> getPBlocks() {
        return pblocks == null ? Collections.emptyList() : pblocks;
    }

    public void setClockPeriod(double clkPeriod) {
        this.clkPeriod = clkPeriod;
    }

    public double getClockPeriod() {
        return clkPeriod;
    }

    public void setClockName(String clkName) {
        this.clkName = clkName;
    }

    public String getClockName() {
        return clkName;
    }

    public boolean isSkipImpl() {
        return skipImpl;
    }

    public void setSkipImpl(boolean skipImpl) {
        this.skipImpl = skipImpl;
    }

    /**
     * @return the outputName
     */
    public String getOutputName() {
        return outputName;
    }

    /**
     * @param outputName the outputName to set
     */
    public void setOutputName(String outputName) {
        this.outputName = outputName;
    }

    private void initializeArrayBuilder(OptionSet options) {
        Path inputFile = null;

        if (options.has(SKIP_IMPL_OPTS.get(0))) {
            this.skipImpl = true;
        }

        if (options.has(INPUT_DESIGN_OPTS.get(0))) {
            inputFile = Paths.get((String)options.valueOf(INPUT_DESIGN_OPTS.get(0)));
            if (inputFile.toString().endsWith(".dcp")) {
                if (options.has(INPUT_EDIF_OPTS.get(0))) {
                    Path companionEDIF = Paths.get((String)options.valueOf(INPUT_EDIF_OPTS.get(0)));
                    setDesign(Design.readCheckpoint(inputFile, companionEDIF, CodePerfTracker.SILENT));
                } else {
                    setDesign(Design.readCheckpoint(inputFile, CodePerfTracker.SILENT));
                }

            } else if (inputFile.toString().endsWith(".edf")) {
                EDIFNetlist netlist = EDIFTools.readEdifFile(inputFile);
                if (options.has(PART_OPTS.get(0))) {
                    Part part = PartNameTools.getPart((String) options.valueOf(PART_OPTS.get(0)));
                    EDIFTools.ensureCorrectPartInEDIF(netlist, part.toString());
                }
                setDesign(new Design(netlist));
            }
        } else {
            throw new RuntimeException("No input design found. "
                    + "Please specify an input kernel (*.dcp or *.edf) using options "
                    + INPUT_DESIGN_OPTS);
        }
        assert (getDesign() != null);

        if (options.has(PBLOCK_OPTS.get(0))) {
            String pblockString = (String) options.valueOf(PBLOCK_OPTS.get(0));
            String[] pblockStrings = pblockString.split(";");
            List<PBlock> pblocks = new ArrayList<PBlock>();
            for (String str : pblockStrings) {
                pblocks.add(new PBlock(getDevice(), str));
            }
            setPBlocks(pblocks);
        } else if (!skipImpl) {
            PBlockGenerator pb = new PBlockGenerator();
            Path utilReport = null;
            Path shapesReport = null;
            if (options.has(UTILIZATION_OPTS.get(0)) && options.has(SHAPES_OPTS.get(0))) {
                utilReport = Paths.get((String) options.valueOf(UTILIZATION_OPTS.get(0)));
                shapesReport = Paths.get((String) options.valueOf(SHAPES_OPTS.get(0)));
            } else {
                utilReport = Paths.get("utilization.report");
                shapesReport = Paths.get("shapes.report");
                if (!inputFile.toString().endsWith(".dcp")) {
                    throw new RuntimeException(
                            "TODO - Implement support for util/shapes file " + "generation with EDIF input file.");
                }
                VivadoTools.getUtilizationAndShapesReport(inputFile, utilReport, shapesReport);
            }

            List<String> pblocks = pb.generatePBlockFromReport(utilReport.toString(), shapesReport.toString());
            List<PBlock> pblockObjects = new ArrayList<PBlock>();
            for (String s : pblocks) {
                PBlock pblock = new PBlock();
                for (String range : s.split(" ")) {
                    pblock.add(new PBlockRange(getDevice(), range));
                }
                pblockObjects.add(pblock);
            }
            setPBlocks(pblockObjects);
        }

        for (int i = 0; i < getPBlocks().size(); i++) {
            System.out.println("[INFO] PBlocks Set [" + i + "]: " + getPBlocks().get(i));
        }

        if (options.has(TARGET_CLK_PERIOD_OPTS.get(0))) {
            setClockPeriod(Double.parseDouble((String) options.valueOf(TARGET_CLK_PERIOD_OPTS.get(0))));
        } else {
            setClockPeriod(DEFAULT_CLK_PERIOD_TARGET);
            System.out.println("[INFO] No clock period set, defaulting to: " + getClockPeriod() + "ns");
        }

        if (options.has(TARGET_CLK_NAME_OPTS.get(0))) {
            setClockName(((String) options.valueOf(TARGET_CLK_NAME_OPTS.get(0))));
        } else {
            setClockName(ClockTools.getClockFromDesign(getDesign()).toString());
        }

    }

    public static void removeBUFGs(Design design) {
        // Find BUFGs in the design and remove them
        List<Cell> bufgs = new ArrayList<>();
        for (Cell c : design.getCells()) {
            if (c.getType().equals("BUFG") || c.getType().equals("BUFGCE")) {
                bufgs.add(c);
            }
        }

        for (Cell bufg : bufgs) {
            SiteInst si = bufg.getSiteInst();
            String inputSiteWire = bufg.getSiteWireNameFromLogicalPin("I");
            Net input = si.getNetFromSiteWire(inputSiteWire);
            String outputSiteWire = bufg.getSiteWireNameFromLogicalPin("O");
            Net output = si.getNetFromSiteWire(outputSiteWire);

            // Remove BUFG
            design.removeCell(bufg);

            design.removeSiteInst(bufg.getSiteInst());
            EDIFCellInst bufgInst = design.getTopEDIFCell().removeCellInst(bufg.getName());
            for (EDIFPortInst portInst : bufgInst.getPortInsts()) {
                portInst.getNet().removePortInst(portInst);
            }
            EDIFNet clkin = design.getTopEDIFCell().getNet(input.getName());
            EDIFNet clk = design.getTopEDIFCell().getNet(output.getName());
            for (EDIFPortInst portInst : clkin.getPortInsts()) {
                clk.addPortInst(portInst);
            }
            design.getTopEDIFCell().removeNet(clkin);
        }
    }

    public static void main(String[] args) {
        CodePerfTracker t = new CodePerfTracker(ArrayBuilder.class.getName());
        t.start("Init");
        ArrayBuilder ab = new ArrayBuilder(t);
        OptionParser p = ab.createOptionParser();
        OptionSet options = p.parse(args);

        if (options.has(HELP_OPTS.get(0))) {
            printHelp(p);
            return;
        }

        // Load design and options
        ab.initializeArrayBuilder(options);

        // Create a working directory for PerformanceExplorer
        Path workDir = Paths.get("ArrayBuilder-" + FileTools.getTimeStamp().replace(" ", "-"));
        boolean reuseResults = false;
        if (options.has(REUSE_RESULTS_OPTS.get(0))) {
            workDir = Paths.get((String) options.valueOf(REUSE_RESULTS_OPTS.get(0)));
            reuseResults = true;
        }
        t.stop().start("Implement Kernel");

        List<Module> modules = new ArrayList<>();
        boolean unrouteStaticNets = false;
        if (!ab.isSkipImpl()) {
            FileTools.makeDirs(workDir.toString());
            System.out.println("[INFO] Created work directory: " + workDir.toString());

            // Initialize PerformanceExplorer
            PerformanceExplorer pe = new PerformanceExplorer(ab.getDesign(), workDir.toString(),
                    ab.getClockName(), ab.getClockPeriod());

            // Set PBlocks
            Map<PBlock, String> pblocks = new HashMap<>();
            for (PBlock pb : ab.getPBlocks()) {
                pblocks.put(pb, null);
            }
            pe.setPBlocks(pblocks);
            pe.setAddEDIFAndMetadata(true);
            pe.setReusePreviousResults(reuseResults);
            pe.explorePerformance();

            List<Pair<Path, Float>> results = pe.getBestResultsPerPBlock();
            for (int i = 0; i < results.size(); i++) {
                Pair<Path, Float> result = results.get(i);
                Path dcpPath = result.getFirst().resolve("routed.dcp");
                if (Files.exists(dcpPath)) {
                    System.out.println("Reading... " + dcpPath);
                    Design d = Design.readCheckpoint(dcpPath);
                    d.setName(d.getName() + "_" + i);
                    Module m = new Module(d, unrouteStaticNets);
                    modules.add(m);
                    m.setPBlock(pe.getPBlock(i));
                    m.calculateAllValidPlacements(d.getDevice());
                } else {
                    System.err.println("Missing DCP Result: " + dcpPath);
                }
                System.out.println(result.getFirst() + " " + result.getSecond());
            }
        } else /* skipImpl==true */ {
            // Just use the design we loaded and replicate it
            removeBUFGs(ab.getDesign());
            Module m = new Module(ab.getDesign(), unrouteStaticNets);
            m.calculateAllValidPlacements(ab.getDevice());
            if (ab.getPBlocks().size() > 0) {
                m.setPBlock(ab.getPBlocks().get(0));
            }
            modules.add(m);
        }
        t.stop().start("Place Instances");

        Design array = new Design("array", ab.getDesign().getPartName());

        ModuleInst curr = null;
        int placed = 0;
        int i = 0;
        outer: for (Module module : modules) {
            for (Site anchor : module.getAllValidPlacements()) {
                if (curr == null) {
                    curr = array.createModuleInst("inst_" + i++, module);
                }
                if (curr.place(anchor, true, false)) {
                    curr = null;
                    placed++;
                    System.out.println("  ** PLACED: " + placed + " " + anchor + " " + module.getName());
                }
            }
        }

        List<Net> unrouted = NetTools.unrouteNetsWithOverlappingNodes(array);
        if (unrouted.size() > 0) {
            System.out.println("Found " + unrouted.size() + " overlapping nets, that were unrouted.");
        }

        array.getNetlist().consolidateAllToWorkLibrary();
        t.stop().start("Write DCP");
        array.writeCheckpoint("array.dcp", CodePerfTracker.SILENT);
        t.stop().printSummary();
    }
}
