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
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.AbstractModuleInst;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleImpls;
import com.xilinx.rapidwright.design.ModuleImplsInst;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.ModulePlacement;
import com.xilinx.rapidwright.design.RelocatableTileRectangle;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.helper.TileColumnPattern;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.placer.blockplacer.BlockPlacer2;
import com.xilinx.rapidwright.placer.blockplacer.BlockPlacer2Impls;
import com.xilinx.rapidwright.placer.blockplacer.BlockPlacer2Module;
import com.xilinx.rapidwright.placer.handplacer.HandPlacer;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class PicoBlazeArray {

    private static final String CLK = "clk";
    private static final String RST = "reset";

    private static final String PBLOCK_DCP_PREFIX = "pblock";
    private static final String PICOBLAZE_PREFIX = "picoblaze_";
    private static final String TOP_INPUT_PREFIX = "top_input_";
    private static final String TOP_OUTPUT_PREFIX = "top_output_";

    private static final String[] PICOBLAZE_INPUTS = new String[]{
            "input_port_a","input_port_b", "input_port_c", "input_port_d"
        };
    private static final String[] PICOBLAZE_OUTPUTS = new String[]{
            "output_port_w","output_port_x", "output_port_y", "output_port_z"
        };
    private static final int[] CONN_ARRAY = new int[]{-2,-1,1,2};
    private static final int PICOBLAZE_BUS_WIDTH = 8;
    private static final int BRAMS_IN_CLOCK_REGION_HEIGHT = 12;

    /**
     * To make it easier to specify placement, we change
     * the anchor to the BRAM instance
     * @param m The module whose anchor should be updated
     */
    public static void updateAnchorToBRAM(Module m) {
        for (SiteInst i : m.getSiteInsts()) {
            if (i.getSite().getSiteTypeEnum() == SiteTypeEnum.RAMBFIFO36) {
                m.setAnchor(i.getSite());
                m.calculateAllValidPlacements(m.getDevice());
            }
        }
    }

    public static abstract class PicoBlazeArrayCreator<T extends AbstractModuleInst<?, ?, T>> {

        public List<T> getInstances() {
            return instances;
        }

        private final List<T> instances = new ArrayList<>();

        private String makeName(int x, int y) {
            return PICOBLAZE_PREFIX + x + "_" + y;
        }

        protected abstract T createInstance(Design design, String name, Module impl, ModuleImpls impls);
        public Design createDesign(File srcDir, String deviceName, CodePerfTracker t) {

            t.start("Creating design");
            // Create a new design with references to device and netlist
            Design design = new Design("top", deviceName);
            Device device = design.getDevice();
            EDIFNetlist netlist = design.getNetlist();
            EDIFCell top = netlist.getTopCell();

            // Load pre-implemented modules
            FilenameFilter ff = FileTools.getFilenameFilter(PBLOCK_DCP_PREFIX+"[0-9]+.dcp");
            int implementationCount = srcDir.list(ff).length;
            ModuleImpls picoBlazeImpls = new ModuleImpls();

            EDIFNetlist moduleNetlist = null;
            for (int i=0; i < implementationCount; i++) {
                String dcpName = srcDir + File.separator + PBLOCK_DCP_PREFIX+i+".dcp";
                String metaName = srcDir + File.separator + PBLOCK_DCP_PREFIX+i+"_"+i+"_metadata.txt";
                t.stop().start("Loading " + PBLOCK_DCP_PREFIX+i+".dcp");

                Design d;
                //Make sure to only read the netlist once
                if (moduleNetlist == null) {
                    d = Design.readCheckpoint(dcpName,CodePerfTracker.SILENT);
                    moduleNetlist = d.getNetlist();
                } else {
                    d = new Design(moduleNetlist);
                    d.updateDesignWithCheckpointPlaceAndRoute(dcpName);
                }

                Module mod = new Module(d, metaName);
                updateAnchorToBRAM(mod);
                picoBlazeImpls.add(mod);
            }

            t.stop().start("Place PicoBlaze modules");


            // Specify placement of picoblaze modules
            TileColumnPattern bramPattern = TileColumnPattern.createTileColumnPattern(Arrays.asList(TileTypeEnum.BRAM));
            int bramColumns = TileColumnPattern.genColumnPatternMap(device).get(bramPattern).size();
            int bramRows = design.getDevice().getNumOfClockRegionRows() * BRAMS_IN_CLOCK_REGION_HEIGHT;

            Map<String, T> instances = new HashMap<>();

            for (int x=0; x < bramColumns; x++) {
                // we will skip top and bottom clock region rows to avoid laguna tiles and U-turn routing
                for (int y=BRAMS_IN_CLOCK_REGION_HEIGHT; y < bramRows-BRAMS_IN_CLOCK_REGION_HEIGHT; y++) {
                    Site bram = device.getSite("RAMB36_X" + x + "Y" + y);
                    Module impl = null;
                    for (Module m : picoBlazeImpls) {
                        if (canCreateModuleAtSite(design, bram, m, instances.values())) {
                            impl = m;
                            break;
                        }
                    }
                    if (impl == null) continue; // Laguna site

                    T mi = createInstance(design, makeName(x,y), impl, picoBlazeImpls);

                    instances.put(mi.getName(), mi);
                    mi.getCellInst().setCellType(impl.getNetlist().getTopCell());

                    placeInArray(mi, bram, impl);

                    this.instances.add(mi);
                }
            }

            t.stop().start("Stitch design");

            // Create clk and rst
            String bufgInstName = "bufgce_inst";
            SLRCrosserGenerator.createBUFGCE(design, CLK, CLK + "in", CLK + "out", bufgInstName);
            SLRCrosserGenerator.placeBUFGCE(design, device.getSite("BUFGCE_X0Y8"), bufgInstName);
            EDIFNet clk = top.getNet(CLK);
            top.createPort(RST, EDIFDirection.INPUT, 1);

            // Connect pre-implemented modules together
            String busRange = "["+(PICOBLAZE_BUS_WIDTH-1)+":0]";
            for (int x=0; x < bramColumns; x++) {
                top.createPort(TOP_INPUT_PREFIX + x + busRange, EDIFDirection.INPUT, PICOBLAZE_BUS_WIDTH);
                top.createPort(TOP_OUTPUT_PREFIX + x + busRange, EDIFDirection.OUTPUT, PICOBLAZE_BUS_WIDTH);
                // we will skip top and bottom clock region rows to avoid laguna tiles and U-turn routing
                for (int y=BRAMS_IN_CLOCK_REGION_HEIGHT; y < bramRows-BRAMS_IN_CLOCK_REGION_HEIGHT; y++) {
                    T curr = instances.get(makeName(x, y));
                    if (curr==null) continue;

                    clk.createPortInst(CLK, curr.getCellInst());
                    curr.connect(RST, RST);

                    for (int i=0; i < PICOBLAZE_BUS_WIDTH; i++) {
                        for (int j=0; j < CONN_ARRAY.length; j++) {
                            T other = instances.get(makeName(x, y + CONN_ARRAY[j]));
                            if (other == null) {
                                curr.connect(PICOBLAZE_INPUTS [j], TOP_INPUT_PREFIX  + x, i);
                                if (y == bramRows-BRAMS_IN_CLOCK_REGION_HEIGHT-1 && j==3) {
                                    curr.connect(PICOBLAZE_OUTPUTS[j], TOP_OUTPUT_PREFIX + x, i);
                                }
                            } else {
                                curr.connect(PICOBLAZE_INPUTS [j], other, PICOBLAZE_OUTPUTS[CONN_ARRAY.length-j-1], i);
                            }
                        }
                    }
                }
            }
            return design;
        }

        protected boolean canCreateModuleAtSite(Design design, Site anchor, Module m, Collection<T> instances) {
            //This already checks overlaps with other Module Instances. But not if we are using ModuleImplsInsts.
            return m.isValidPlacement(anchor, design);
        }

        protected abstract void placeInArray(T mi, Site bram, Module impl);
        public abstract BlockPlacer2<?, ? extends T, ?, ?> createPlacer(Design design, Path graphDataFile);

        public abstract void lowerToModules(Design design, CodePerfTracker t);
    }

    /**
     * Part of an example tutorial of how to build an array of picoblaze modules.
     * @param args
     */
    public static void main(String[] args) {
        OptionParser optionParser = new OptionParser();
        NonOptionArgumentSpec<String> nonOptions = optionParser.nonOptions();
        OptionSpec<?> blockPlacerOption = optionParser.accepts("block_placer", "Place Instances via Block Placer");
        OptionSpec<?> handPlacerOption = optionParser.accepts("no_hand_placer", "Disable Hand Placer");
        OptionSpec<?> implsOption = optionParser.accepts("impls", "Use Impls instead of Modules");


        OptionSet options;
        List<String> nonOptionValues;
        try {
            options = optionParser.parse(args);
            nonOptionValues = options.valuesOf(nonOptions);
            if (nonOptionValues.size()!=3) {
                throw new RuntimeException("We need exactly three non-option values: modules Input Dir, Part, and output checkpoint filename. "+nonOptionValues.size()+" were given.");
            }
        } catch (RuntimeException e) {
            try {
                System.out.println("Usage: [options] [--] <input dir> <part> <output checkpoint>");
                optionParser.printHelpOn(System.out);
            } catch (IOException ioException) {
                throw new UncheckedIOException(ioException);
            }
            throw e;
        }


        String srcDirName = nonOptionValues.get(0);
        File srcDir = new File(srcDirName);
        if (!srcDir.isDirectory()) {
                        throw new RuntimeException("ERROR: Couldn't read directory: " + srcDir);
        }
        String part = nonOptionValues.get(1);
        Path outName = Paths.get(nonOptionValues.get(2));
        boolean handPlacer = options.has(handPlacerOption);
        boolean useImpls = options.has(implsOption);
        CodePerfTracker t = new CodePerfTracker("PicoBlaze Array", true);
        t.useGCToTrackMemory(true);


        PicoBlazeArrayCreator<?> creator;

        if (useImpls) {
            creator = makeImplsCreator();
        } else {
            creator = makeModuleCreator();
        }

        Design design = creator.createDesign(srcDir, part, t);

        if (options.has(blockPlacerOption)) {
            t.stop().start("BlockPlacer");
            Path graphDataFile = FileTools.replaceExtension(outName, "_graph.tsv");
            creator.createPlacer(design, graphDataFile).placeDesign(false);
        }

        creator.lowerToModules(design, t);

        if (!handPlacer) {
            t.stop().start("Hand Placer");
            System.out.println("start hand placer");
            HandPlacer.openDesign(design);
            System.out.println("finish hand placer");
        }

        t.stop().start("Write DCP");

        design.setAutoIOBuffers(false);
        design.addXDCConstraint("create_clock -name " + CLK + " -period 2.850 [get_nets " + CLK + "]");
        design.writeCheckpoint(outName, CodePerfTracker.SILENT);
        t.stop().printSummary();
    }

    public static PicoBlazeArrayCreator<ModuleInst> makeModuleCreator() {
        return new PicoBlazeArrayCreator<ModuleInst>() {

            private BlockPlacer2Module placer;

            @Override
            protected ModuleInst createInstance(Design design, String name, Module impl, ModuleImpls impls) {
                return design.createModuleInst(name, impl);
            }

            @Override
            protected void placeInArray(ModuleInst mi, Site bram, Module impl) {
                mi.place(bram);
            }

            @Override
            public BlockPlacer2<?, ? extends ModuleInst, ?, ?> createPlacer(Design design, Path graphDataFile) {
                placer = new BlockPlacer2Module(design, true, graphDataFile, BlockPlacer2.DEFAULT_DENSE, BlockPlacer2.DEFAULT_EFFORT, BlockPlacer2.DEFAULT_FOCUS_ON_WORST, null);
                return placer;
            }

            @Override
            public void lowerToModules(Design design, CodePerfTracker t) {
                //Nothing to do
            }
        };
    }

    public static PicoBlazeArrayCreator<ModuleImplsInst> makeImplsCreator() {
        return new PicoBlazeArrayCreator<ModuleImplsInst>() {

            private BlockPlacer2Impls placer;

            @Override
            protected ModuleImplsInst createInstance(Design design, String name, Module impl, ModuleImpls impls) {
                return DesignTools.createModuleImplsInst(design, name, impls);
            }

            @Override
            protected void placeInArray(ModuleImplsInst mi, Site bram, Module impl) {
                mi.place(new ModulePlacement(impl.getImplementationIndex(), bram));
            }

            @Override
            public BlockPlacer2<?, ModuleImplsInst, ?, ?> createPlacer(Design design, Path graphDataFile) {
                placer = new BlockPlacer2Impls(design, getInstances(), true, graphDataFile, BlockPlacer2.DEFAULT_DENSE, BlockPlacer2.DEFAULT_EFFORT, BlockPlacer2.DEFAULT_FOCUS_ON_WORST, null);
                return placer;
            }

            @Override
            public void lowerToModules(Design design, CodePerfTracker t) {
                t.stop().start("Lower to Modules");
                if (placer == null) {
                    createPlacer(design, null);
                    placer.initializePlacer(false);
                }
                DesignTools.createModuleInstsFromModuleImplsInsts(design, getInstances(), placer.getPaths());
            }

            @Override
            protected boolean canCreateModuleAtSite(Design design, Site anchor, Module m, Collection<ModuleImplsInst> instances) {
                //Only checks if placement is possible at all, not taking overlaps into account
                if (!super.canCreateModuleAtSite(design, anchor, m, instances)) {
                    return false;
                }
                //Check for overlaps
                RelocatableTileRectangle bb = m.getBoundingBox().getCorresponding(anchor.getTile(), m.getAnchor().getTile());
                return instances.stream().noneMatch(other -> other.getBoundingBox().overlaps(bb));
            }
        };
    }

}
