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
import java.util.*;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ClockTools;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PBlockGenerator;
import com.xilinx.rapidwright.design.blocks.PBlockRange;
import com.xilinx.rapidwright.device.*;
import com.xilinx.rapidwright.edif.*;
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
    private static final List<String> WRITE_PLACEMENT_OPTS = List.of("write-placement");
    private static final List<String> PLACEMENT_FILE_OPTS = List.of("placement-file");
    private static final List<String> PLACEMENT_LOCS_OPTS = List.of("write-placement-locs");
    private static final List<String> OUT_OF_CONTEXT_OPTS = List.of("out-of-context");

    private Design design;

    private Design topDesign;

    private List<PBlock> pblocks;

    private double clkPeriod;

    private String kernelClkName;

    private String topClkName;

    private boolean skipImpl;

    private String outputName;

    private CodePerfTracker t;

    private int instCountLimit = Integer.MAX_VALUE;

    private String outputPlacementFileName;

    private String inputPlacementFileName;

    private String outputPlacementLocsFileName;

    private boolean outOfContext;

    public static final double DEFAULT_CLK_PERIOD_TARGET = 2.0;

    private OptionParser createOptionParser() {

        OptionParser p = new OptionParser() {
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
                acceptsAll(PLACEMENT_LOCS_OPTS, "Write grid of possible placement locations to specified file").withRequiredArg();
                acceptsAll(TOP_LEVEL_DESIGN_OPTS, "Top level design with blackboxes/kernel insts").withRequiredArg();
                acceptsAll(OUT_OF_CONTEXT_OPTS, "Specifies that the array will be compiled out of context");
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
        return getKernelDesign().getDevice();
    }

    public void setKernelDesign(Design design) {
        this.design = design;
    }

    public Design getKernelDesign() {
        return design;
    }

    public Design getTopDesign() {
        return topDesign;
    }

    public void setTopDesign(Design topDesign) {
        this.topDesign = topDesign;
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

    public String getOutputName() {
        return outputName;
    }

    public void setOutputName(String outputName) {
        this.outputName = outputName;
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

    public boolean getOutOfContext() {
        return outOfContext;
    }

    public void setOutOfContext(boolean outOfContext) {
        this.outOfContext = outOfContext;
    }

    private void initializeArrayBuilder(OptionSet options) {
        Path inputFile = null;

        setSkipImpl(options.has(SKIP_IMPL_OPTS.get(0)));
        setOutOfContext(options.has(OUT_OF_CONTEXT_OPTS.get(0)));

        if (options.has(KERNEL_DESIGN_OPTS.get(0))) {
            inputFile = Paths.get((String) options.valueOf(KERNEL_DESIGN_OPTS.get(0)));
            if (inputFile.toString().endsWith(".dcp")) {
                if (options.has(INPUT_EDIF_OPTS.get(0))) {
                    Path companionEDIF = Paths.get((String) options.valueOf(INPUT_EDIF_OPTS.get(0)));
                    setKernelDesign(Design.readCheckpoint(inputFile, companionEDIF, CodePerfTracker.SILENT));
                } else {
                    setKernelDesign(Design.readCheckpoint(inputFile));
                    if (!design.getNetlist().getEncryptedCells().isEmpty()) {
                        System.out.println("Design has encrypted cells");
                    } else {
                        System.out.println("Design does not have encrypted cells");
                    }
                }

            } else if (inputFile.toString().endsWith(".edf")) {
                EDIFNetlist netlist = EDIFTools.readEdifFile(inputFile);
                if (options.has(PART_OPTS.get(0))) {
                    Part part = PartNameTools.getPart((String) options.valueOf(PART_OPTS.get(0)));
                    EDIFTools.ensureCorrectPartInEDIF(netlist, part.toString());
                }
                setKernelDesign(new Design(netlist));
            }
        } else {
            throw new RuntimeException("No input design found. "
                    + "Please specify an input kernel (*.dcp or *.edf) using options "
                    + KERNEL_DESIGN_OPTS);
        }
        assert (getKernelDesign() != null);

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

        if (options.has(KERNEL_CLK_NAME_OPTS.get(0))) {
            setKernelClockName(((String) options.valueOf(KERNEL_CLK_NAME_OPTS.get(0))));
        } else {
            setKernelClockName(ClockTools.getClockFromDesign(getKernelDesign()).toString());
        }

        if (options.has(OUTPUT_DESIGN_OPTS.get(0))) {
            setOutputName((String) options.valueOf(OUTPUT_DESIGN_OPTS.get(0)));
        } else {
            setOutputName("array.dcp");
        }

        if (options.has(LIMIT_INSTS_OPTS.get(0))) {
            setInstCountLimit(Integer.parseInt((String) options.valueOf(LIMIT_INSTS_OPTS.get(0))));
        }

        if (options.has(TOP_LEVEL_DESIGN_OPTS.get(0))) {
            Design d = Design.readCheckpoint((String) options.valueOf(TOP_LEVEL_DESIGN_OPTS.get(0)));
            setTopDesign(d);
        }

        if (options.has(TOP_CLK_NAME_OPTS.get(0))) {
            setTopClockName(((String) options.valueOf(TOP_CLK_NAME_OPTS.get(0))));
        } else {
            setTopClockName(ClockTools.getClockFromDesign(getTopDesign()).toString());
        }

        if (options.has(WRITE_PLACEMENT_OPTS.get(0))) {
            setOutputPlacementFileName((String) options.valueOf(WRITE_PLACEMENT_OPTS.get(0)));
        }

        if (options.has(PLACEMENT_FILE_OPTS.get(0))) {
            setInputPlacementFileName((String) options.valueOf(PLACEMENT_FILE_OPTS.get(0)));
        }

        if (options.has(PLACEMENT_LOCS_OPTS.get(0))) {
            setOutputPlacementLocsFileName((String) options.valueOf(PLACEMENT_LOCS_OPTS.get(0)));
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

    public static List<String> getMatchingModuleInstanceNames(Module m, Design array) {
        List<String> instNames = new ArrayList<>();
        EDIFCell modCellType = m.getNetlist().getTopCell();
        EDIFHierCellInst top = array.getNetlist().getTopHierCellInst();
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        q.add(top);
        while (!q.isEmpty()) {
            EDIFHierCellInst curr = q.poll();
            if (curr.getCellType().matchesInterface(modCellType)) {
                instNames.add(curr.getFullHierarchicalInstName());
            } else {
                for (EDIFCellInst child : curr.getCellType().getCellInsts()) {
                    q.add(curr.getChild(child));
                }
            }
        }
        return instNames;
    }

    public static void writePlacementToFile(Map<ModuleInst, Site> placementMap, String fileName) {
        List<String> lines = new ArrayList<>();
        Comparator<Site> comparator = new Comparator<Site>() {
            @Override
            public int compare(Site o1, Site o2) {
                if (o1.getInstanceY() > o2.getInstanceY()) {
                    return -1;
                }

                if (o1.getInstanceY() < o2.getInstanceY()) {
                    return 1;
                }

                return Integer.compare(o1.getInstanceX(), o2.getInstanceX());
            }
        };
        Map<ModuleInst, Site> sortedMap = placementMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(comparator))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
        for (Map.Entry<ModuleInst, Site> entry : sortedMap.entrySet()) {
            lines.add(entry.getKey() + " " + entry.getValue());
        }
        FileTools.writeLinesToTextFile(lines, fileName);
    }

    public static Map<String, String> readPlacementFromFile(String fileName) {
        Map<String, String> placementMap = new HashMap<>();
        List<String> lines = FileTools.getLinesFromTextFile(fileName);

        for (String line : lines) {
            String[] splitLine = line.split("\\s+");
            placementMap.put(splitLine[0], splitLine[1]);
        }

        return placementMap;
    }

    public static void writePlacementLocsToFile(List<Module> modules, String fileName) {
        List<String> lines = new ArrayList<>();
        Comparator<Site> comparator = (o1, o2) -> {
            if (o1.getInstanceY() > o2.getInstanceY()) {
                return -1;
            }

            if (o1.getInstanceY() < o2.getInstanceY()) {
                return 1;
            }

            return Integer.compare(o1.getInstanceX(), o2.getInstanceX());
        };
        for (Module module : modules) {
            lines.add(module.getName() + ":");
            List<Site> validPlacements = module.getAllValidPlacements().stream().sorted(comparator).toList();
            for (Site anchor : validPlacements) {
                lines.add(anchor.getName());
            }
        }
        FileTools.writeLinesToTextFile(lines, fileName);
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

        List<Module> modules = new ArrayList<>();
        boolean unrouteStaticNets = false;
        if (!ab.isSkipImpl()) {
            t.stop().start("Implement Kernel");
            FileTools.makeDirs(workDir.toString());
            System.out.println("[INFO] Created work directory: " + workDir.toString());

            // Initialize PerformanceExplorer
            PerformanceExplorer pe = new PerformanceExplorer(ab.getKernelDesign(), workDir.toString(),
                    ab.getKernelClockName(), ab.getClockPeriod());

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
            t.stop().start("Calculate Valid Placements");
            removeBUFGs(ab.getKernelDesign());
            Module m = new Module(ab.getKernelDesign(), unrouteStaticNets);
            m.getNet(ab.getKernelClockName()).unroute();
            if (ab.getInputPlacementFileName() == null) {
                m.calculateAllValidPlacements(ab.getDevice());
            }
            if (!ab.getPBlocks().isEmpty()) {
                m.setPBlock(ab.getPBlocks().get(0));
            }
            modules.add(m);
        }
        t.stop().start("Place Instances");

        Design array = null;
        List<String> modInstNames = null;
        if (ab.getTopDesign() == null) {
            array = new Design("array", ab.getKernelDesign().getPartName());
        } else {
            array = ab.getTopDesign();
            // Find instances in existing design
            modInstNames = getMatchingModuleInstanceNames(modules.get(0), array);
            ab.setInstCountLimit(modInstNames.size());
        }

        if (ab.getOutputPlacementLocsFileName() != null) {
            writePlacementLocsToFile(modules, ab.getOutputPlacementLocsFileName());
        }

        // Add encrypted cells from modules to array
        for (Module module : modules) {
            // Merge encrypted cells
            List<String> encryptedCells = module.getNetlist().getEncryptedCells();
            if (!encryptedCells.isEmpty()) {
                System.out.println("Encrypted cells merged");
                array.getNetlist().addEncryptedCells(encryptedCells);
            }
        }

        int placed = 0;
        Map<ModuleInst, Site> newPlacementMap = new HashMap<>();
        if (ab.getInputPlacementFileName() != null) {
            Map<String, String> placementMap = readPlacementFromFile(ab.inputPlacementFileName);
            System.out.println("Placing from specified file");
            for (Map.Entry<String, String> entry : placementMap.entrySet()) {
                String instName = entry.getKey();
                String anchorName = entry.getValue();
                // TODO - Remove after createModuleInst() fix
                EDIFHierCellInst hierInst = array.getNetlist().getHierCellInstFromName(instName);
                if (hierInst == null) {
                    throw new RuntimeException("Instance name " + instName + " is invalid");
                }
                Module module = null;
                for (Module m : modules) {
                    if (m.getName().equals(hierInst.getCellName())) {
                        module = m;
                    }
                }
                assert module != null;
                if (hierInst.getCellType().isLeafCellOrBlackBox()) {
                    EDIFCell bb = hierInst.getCellType();
                    if (bb.getLibrary() != null) {
                        bb.getLibrary().removeCell(bb);
                    }
                    EDIFCell modCell = module.getNetlist().getTopCell();
                    EDIFCell currCell = array.getNetlist().getWorkLibrary().getCell(modCell.getName());
                    if (currCell == null) {
                        array.getNetlist().copyCellAndSubCells(modCell);
                    }
                } // END TODO
                ModuleInst curr = array.createModuleInst(instName, module);
                // TODO - Remove after createModuleInst() fix
                EDIFHierCellInst hierCellInst = array.getNetlist().getHierCellInstFromName(instName);
                hierCellInst.getInst().removeBlackBoxProperty();
                // END TODO

                Site anchor = array.getDevice().getSite(anchorName);

                boolean wasPlaced = curr.place(anchor, true, false);
                if (!wasPlaced) {
                    throw new RuntimeException("Unable to place cell " + instName + " at site " + anchor);
                }

                if (straddlesClockRegion(curr)) {
                    curr.unplace();
                    throw new RuntimeException("Chosen site anchor " + anchor + " straddles multiple clock regions");
                }

                newPlacementMap.put(curr, anchor);
                placed++;
                System.out.println("  ** PLACED: " + placed + " " + anchor + " " + curr.getName());
            }
        } else {
            ModuleInst curr = null;
            int i = 0;
            outer:
            for (Module module : modules) {
                for (Site anchor : module.getAllValidPlacements()) {
                    if (curr == null) {
                        String instName = modInstNames == null ? ("inst_" + i) : modInstNames.get(i);
                        // TODO - Remove after createModuleInst() fix
                        EDIFHierCellInst hierInst = array.getNetlist().getHierCellInstFromName(instName);
                        if (hierInst != null && hierInst.getCellType().isLeafCellOrBlackBox()) {
                            EDIFCell bb = hierInst.getCellType();
                            if (bb.getLibrary() != null) {
                                bb.getLibrary().removeCell(bb);
                            }
                            EDIFCell modCell = module.getNetlist().getTopCell();
                            EDIFCell currCell = array.getNetlist().getWorkLibrary().getCell(modCell.getName());
                            if (currCell == null) {
                                array.getNetlist().copyCellAndSubCells(modCell);
                            }
                        } // END TODO
                        curr = array.createModuleInst(instName, module);
                        // TODO - Remove after createModuleInst() fix
                        EDIFHierCellInst hierCellInst = array.getNetlist().getHierCellInstFromName(instName);
                        hierCellInst.getInst().removeBlackBoxProperty();
                        // END TODO
                        i++;
                    }
                    if (curr.place(anchor, true, false)) {
                        if (straddlesClockRegion(curr)) {
                            curr.unplace();
                            continue;
                        }

                        List<Net> overlapping = NetTools.getNetsWithOverlappingNodes(array);
                        if (!overlapping.isEmpty()) {
                            curr.unplace();
                            continue;
                        }

                        placed++;
                        newPlacementMap.put(curr, anchor);
                        System.out.println("  ** PLACED: " + placed + " " + anchor + " " + curr.getName());
                        curr = null;
                        if (placed >= ab.getInstCountLimit()) {
                            break outer;
                        }
                    }
                }
            }
        }

        if (ab.getOutputPlacementFileName() != null) {
            writePlacementToFile(newPlacementMap, ab.outputPlacementFileName);
        }

        List<Net> unrouted = NetTools.unrouteNetsWithOverlappingNodes(array);
        if (!unrouted.isEmpty()) {
            System.out.println("Found " + unrouted.size() + " overlapping nets, that were unrouted.");
        }

        if (ab.isSkipImpl() && ab.getTopDesign() == null) {
            EDIFCell top = array.getTopEDIFCell();
            EDIFHierNet clkNet = array.getNetlist().getHierNetFromName(ab.getKernelClockName());
            if (clkNet == null) {
                // Create BUFG and clock net, then connect to all instances
                Cell bufg = createBUFGCE(array, top, "bufg", array.getDevice().getSite("BUFGCE_X2Y0"));
                Net clk = array.createNet(ab.getKernelClockName());
                clk.connect(bufg, "O");
                Net clkIn = array.createNet(ab.getKernelClockName() + "_in");
                clkIn.connect(bufg, "I");
                EDIFPort clkInPort = top.createPort(ab.getKernelClockName(), EDIFDirection.INPUT, 1);
                clkIn.getLogicalNet().createPortInst(clkInPort);
                EDIFNet logClkNet = clk.getLogicalNet();
                for (EDIFCellInst inst : top.getCellInsts()) {
                    EDIFPort port = inst.getPort(ab.getKernelClockName());
                    if (port != null) {
                        logClkNet.createPortInst(port, inst);
                    }
                }
            }

            // Port up unconnected inputs
            for (EDIFPort topPort : modules.get(0).getNetlist().getTopCell().getPorts()) {
                if (topPort.isInput()) {
                    if (top.getPort(topPort.getName()) == null) {
                        EDIFPort port = top.createPort(topPort);
                        if (port.isBus()) {
                            for (int j = 0; j < port.getWidth(); j++) {
                                EDIFNet net = top.createNet(port.getPortInstNameFromPort(j));
                                net.createPortInst(port, j);
                                for (ModuleInst mi : array.getModuleInsts()) {
                                    net.createPortInst(port, j, mi.getCellInst());
                                }
                            }
                        } else {
                            EDIFNet net = top.createNet(port.getName());
                            net.createPortInst(port);
                            for (ModuleInst mi : array.getModuleInsts()) {
                                net.createPortInst(port, mi.getCellInst());
                            }
                        }
                    }
                }
            }

            PerformanceExplorer.updateClockPeriodConstraint(array, ab.getKernelClockName(), ab.getClockPeriod());
            array.setDesignOutOfContext(true);
            array.setAutoIOBuffers(false);
        }

        Net gndNet = array.getNet(Net.GND_NET);
        gndNet.unroute();
        Net vccNet = array.getNet(Net.VCC_NET);
        vccNet.unroute();
        array.getNetlist().consolidateAllToWorkLibrary();

        if (ab.getOutOfContext()) {
            // Automatically find bounding PBlock based on used Slices, DSPs, and BRAMs
            Set<Site> usedSites = array.getUsedSites().stream().filter(
                    (Site s) -> Arrays.asList(SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM, SiteTypeEnum.DSP58_PRIMARY,
                                    SiteTypeEnum.RAMB36, SiteTypeEnum.RAMB18_L, SiteTypeEnum.RAMB18_U)
                            .contains(s.getPrimarySiteType().getTypeEnum())).collect(Collectors.toSet());
            PBlock pBlock = new PBlock(array.getDevice(), usedSites);
            InlineFlopTools.createAndPlaceFlopsInlineOnTopPortsNearPins(array, ab.getTopClockName(), pBlock);
        }

        t.stop().start("Write DCP");
        array.writeCheckpoint(ab.getOutputName());
        t.stop().printSummary();
    }

    public static Cell createBUFGCE(Design design, EDIFCell parent, String name, Site location) {
        Cell bufgce = design.createAndPlaceCell(parent, name, Unisim.BUFGCE, location, location.getBEL("BUFCE"));

        bufgce.addProperty("CE_TYPE", "ASYNC", EDIFValueType.STRING);

        // Ensure a VCC cell source in the current cell
        EDIFTools.getStaticNet(NetType.VCC, parent, design.getNetlist());

        bufgce.getSiteInst().addSitePIP("CEINV", "CE_PREINV");
        bufgce.getSiteInst().addSitePIP("IINV", "I_PREINV");

        if (design.getSeries() == Series.Versal) {
            BEL ceinv = bufgce.getSite().getBEL("CEINV");
            bufgce.getSiteInst().routeIntraSiteNet(design.getVccNet(), ceinv.getPin("CE"), ceinv.getPin("CE_PREINV"));
            design.getVccNet().addPin(new SitePinInst(false, "CE", bufgce.getSiteInst()));
        } else if (design.getSeries() == Series.UltraScalePlus) {
            // TODO
        }
        // Remove CE:VCC entry for CE:CE
        bufgce.removePinMapping("CE");
        bufgce.addPinMapping("CE", "CE");

        return bufgce;
    }

    private static boolean straddlesClockRegion(ModuleInst mi) {
        ClockRegion cr = mi.getAnchor().getSite().getClockRegion();
        for (SiteInst si : mi.getSiteInsts()) {
            if (si.getSite().getClockRegion() != cr) {
                return true;
            }
        }
        return false;
    }

    private static boolean straddlesClockRegionOrRCLK(ModuleInst mi) {
        ClockRegion cr = mi.getAnchor().getSite().getClockRegion();
        int centerRow = getRCLKRowIndex(cr);
        boolean inTop = false;
        boolean inBot = false;
        for (SiteInst si : mi.getSiteInsts()) {
            inTop |= si.getTile().getRow() > centerRow;
            inBot |= si.getTile().getRow() < centerRow;
            if ((inTop && inBot) || si.getSite().getClockRegion() != cr) {
                return true;
            }
        }
        return false;
    }

    private static int getRCLKRowIndex(ClockRegion cr) {
        Tile center = cr.getApproximateCenter();
        int searchGridDim = 0;
        outer:
        while (!center.getName().startsWith("RCLK_")) {
            searchGridDim++;
            for (int row = -searchGridDim; row < searchGridDim; row++) {
                for (int col = -searchGridDim; col < searchGridDim; col++) {
                    Tile neighbor = center.getTileNeighbor(col, row);
                    if (neighbor != null) {
                        neighbor.getName().startsWith("RCLK_");
                        center = neighbor;
                        break outer;
                    }
                }
            }
        }
        return center.getRow();
    }
}
