/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

package com.xilinx.rapidwright.design;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.tools.RelocationTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class TestRelocationTools {

    private void relocateModuleInstsAndCompare(int colOffset, int rowOffset, boolean expectSuccess, Design design1, Collection<ModuleInst> moduleInsts) {
        List<Pair<ModuleInst,Site>> newSite = new ArrayList<>();
        for (ModuleInst mi : moduleInsts) {
            Assertions.assertTrue(mi.isPlaced());

            SiteInst si = mi.getAnchor();
            Site ss = si.getSite();
            Tile st = si.getTile();
            Tile dt = st.getTileXYNeighbor(colOffset, rowOffset);
            Site ds = ss.getCorrespondingSite(si.getSiteTypeEnum(), dt);
            newSite.add(new Pair<>(mi, ds));

            if (!mi.getModule().isValidPlacement(ds, null /* Do not check existing placements */)) {
                Assertions.assertFalse(expectSuccess);
            }
        }

        for (Pair<ModuleInst,Site> e : newSite) {
            ModuleInst mi = e.getFirst();
            Site ds = e.getSecond();

            if (expectSuccess) {
                Assertions.assertEquals(mi.place(ds), expectSuccess);

                for (SiteInst si : mi.getSiteInsts()) {
                    for (Cell c2 : si.getCells()) {
                        Cell c1 = design1.getCell(c2.getName());
                        if (c1 == null && c2.getName().startsWith(mi.getName() + EDIFTools.EDIF_HIER_SEP)) {
                            // Retry without ModuleInst hierarchy in case it was flattened
                            c1 = design1.getCell(c2.getName().substring(mi.getName().length() + 1));
                        }
                        Assertions.assertNotNull(c1);
                        Assertions.assertEquals(c1.getSite(), c2.getSite());
                    }
                }
            }

            for (Net n2 : mi.getNets()) {
                Net n1 = design1.getNet(n2.getName());
                if (n1 == null && n2.getName().startsWith(mi.getName() + EDIFTools.EDIF_HIER_SEP)) {
                    // Retry without ModuleInst hierarchy in case it was flattened
                    n1 = design1.getNet(n2.getName().substring(mi.getName().length() + 1));
                }
                Assertions.assertNotNull(n1);

                if (n1.isClockNet() || n1.hasGapRouting()) {
                    // Module relocation unroutes all clock nets?
                    Assertions.assertFalse(n2.hasPIPs());
                    continue;
                }

                Set<PIP> p1 = new HashSet<>(n1.getPIPs());
                Set<PIP> p2 = new HashSet<>(n2.getPIPs());
                if (!n1.isStaticNet()) {
                    Assertions.assertEquals(p1, p2);
                } else {
                    // For static nets, ModuleInst.place() will merge its
                    // static PIPs into the parent Design's static net, so
                    // check that it is contained within
                    Assertions.assertTrue(p1.containsAll(p2));
                }
            }
        }
    }

    @ParameterizedTest(name = "Relocate PicoBlaze OOC ''{0}'' ({1},{2})")
    @MethodSource()
    public void testPicoblazeOOC(String instanceName, int colOffset, int rowOffset, boolean expectSuccess) {
        String dcpPath = RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp");
        Design design1 = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);

        Assertions.assertEquals(RelocationTools.relocate(design1, instanceName, colOffset, rowOffset),
                expectSuccess);

        String metaPath = RapidWrightDCP.getString("picoblaze_ooc_X10Y235.metadata");
        if (instanceName.isEmpty()) {
            Design design2 = new Design("design2", design1.getPartName());
            Module module = new Module(Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT), metaPath, false);
            ModuleInst mi = design2.createModuleInst("inst", module);
            mi.placeOnOriginalAnchor();
            Collection<ModuleInst> moduleInsts = Arrays.asList(mi);

            relocateModuleInstsAndCompare(colOffset, rowOffset, expectSuccess, design1, moduleInsts);
        }
    }

    public static Stream<Arguments> testPicoblazeOOC() {
        return Stream.of(
                Arguments.of("", 0, 5, true)
                , Arguments.of("", 0, -5, true)
                , Arguments.of("", 9, -5, true)
                , Arguments.of("", 0, 0, true)
                , Arguments.of("", 1, 0, false)     // Incompatible tile
                , Arguments.of("", 0, 1, false)     // Incompatible tile
                , Arguments.of("", -100, 0, false)  // Out of X range
                , Arguments.of("", 0, 200, false)   // Out of Y range
                , Arguments.of("processor", 9, 0, false) // SiteInsts contains matching and non-matching cells
        );
    }


    private static final String Picoblaze4OOCdcp = RapidWrightDCP.getString("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");

    @ParameterizedTest(name = "Relocate PicoBlaze4 OOC ''{0}'' ({1},{2})")
    @MethodSource()
    public void testPicoblaze4OOC(String instanceName, int colOffset, int rowOffset, boolean expectSuccess) {

        // NOTE: Picoblaze4OOC, unlike PicoblazeOOC, has already had its static nets
        //       unrouted on creation.

        Design design1 = Design.readCheckpoint(Picoblaze4OOCdcp, CodePerfTracker.SILENT);

        Assertions.assertEquals(RelocationTools.relocate(design1, instanceName, colOffset, rowOffset),
                expectSuccess);

        Design design2 = Design.readCheckpoint(Picoblaze4OOCdcp, CodePerfTracker.SILENT);
        Collection<ModuleInst> moduleInsts;
        if (instanceName.isEmpty()) {
            moduleInsts = design2.getModuleInsts();
        } else {
            ModuleInst mi = design2.getModuleInst(instanceName);
            Assertions.assertNotNull(mi);
            moduleInsts = Arrays.asList(mi);
        }

        relocateModuleInstsAndCompare(colOffset, rowOffset, expectSuccess, design1, moduleInsts);
    }

    public static Stream<Arguments> testPicoblaze4OOC() {
        return Stream.of(
                  Arguments.of("", 0, 5, true)
                , Arguments.of("picoblaze_0_13", 0, 5, true)
                , Arguments.of("picoblaze_0_12", 0, 5, false) // placement conflict
        );
    }

    @ParameterizedTest(name = "Relocate PicoBlaze4 OOC PBlock ''{0}'' ({1},{2})")
    @MethodSource()
    public void testPicoblaze4OOC_PBlock(PBlock pblock, int colOffset, int rowOffset, boolean expectSuccess) {
        Design design1 = Design.readCheckpoint(Picoblaze4OOCdcp, CodePerfTracker.SILENT);

        Assertions.assertEquals(RelocationTools.relocate(design1, pblock, colOffset, rowOffset),
                expectSuccess);

        Design design2 = Design.readCheckpoint(Picoblaze4OOCdcp, CodePerfTracker.SILENT);

        // Find the set of ModuleInsts
        Set<ModuleInst> moduleInsts1 = new HashSet<>();
        for (Site s : pblock.getAllSites(null)) {
            SiteInst si = design1.getSiteInstFromSite(s);
            if (si != null) {
                ModuleInst mi = si.getModuleInst();
                if (mi != null) {
                    moduleInsts1.add(mi);
                }
            }
        }

        Collection<ModuleInst> moduleInsts2 = moduleInsts1.stream().map((mi) -> design2.getModuleInst(mi.getName()))
                .collect(Collectors.toList());
        relocateModuleInstsAndCompare(colOffset, rowOffset, expectSuccess, design1, moduleInsts2);
    }

    public static Stream<Arguments> testPicoblaze4OOC_PBlock() {
        final String partName = Design.getPartNameFromDCP(Picoblaze4OOCdcp);
        return Stream.of(
                  Arguments.of(new PBlock(Device.getDevice(partName), "SLICE_X8Y65:SLICE_X11Y69 RAMB18_X0Y26:RAMB18_X0Y27 RAMB36_X0Y13:RAMB36_X0Y13"),
                        0, 5, true)
                , Arguments.of(new PBlock(Device.getDevice(partName), "SLICE_X8Y60:SLICE_X11Y64 RAMB18_X0Y24:RAMB18_X0Y25 RAMB36_X0Y12:RAMB36_X0Y12"),
                        0, 5, false) // placement conflict
        );
    }

    @ParameterizedTest(name = "Relocate MicroBlazeAndILA ''{0}'' ({1},{2})")
    @MethodSource()
    public void testMicroBlazeAndILA(String instanceName, int colOffset, int rowOffset, boolean expectSuccess, int expectedNetsWithRoutingErrors) {
        String dcpPath = RapidWrightDCP.getString("microblazeAndILA_3pblocks.dcp");

        Design design1 = Design.readCheckpoint(dcpPath, CodePerfTracker.SILENT);

        Assertions.assertEquals(RelocationTools.relocate(design1, instanceName, colOffset, rowOffset),
                expectSuccess);

        if (expectSuccess && FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design1);
            Assertions.assertEquals(expectedNetsWithRoutingErrors, rrs.netsWithRoutingErrors);
        }
    }

    public static Stream<Arguments> testMicroBlazeAndILA() {
        return Stream.of(
                  Arguments.of("", 0, 5, true, 0)
                , Arguments.of("", 0, 60, true, 0)
                , Arguments.of("base_mb_i", 0, 10, true, 26 /* unrouted pins and resource conflicts */ )
                , Arguments.of("dbg_hub", 0, 20, true, 19 /* unrouted pins */)
                , Arguments.of("u_ila_0", 0, 30, true, 0)
                , Arguments.of("dbg_hub", 16, 0, false, -1) // placement conflict
        );
    }

    @Test
    public void testRelocationTools(@TempDir Path dir) {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235_2022_1.dcp");
        d.getNet("clk").unroute();
        Path dcpName = dir.resolve("picoblaze_unrouted_clk.dcp");
        d.writeCheckpoint(dcpName);
        
        int xOffset = 0;
        int yOffset = 5;
        
        Path outputDCP = dir.resolve("output.dcp");

        // Smoke test for valid location report mode
        RelocationTools.main(new String[] { dcpName.toString() });

        RelocationTools.main(new String[] {dcpName.toString(), outputDCP.toString(), 
                        Integer.toString(xOffset), Integer.toString(yOffset)});
        
        Design testOutput = Design.readCheckpoint(outputDCP);
        
        for (SiteInst s : d.getSiteInsts()) {
            Tile origin = s.getTile();
            Tile relocated = origin.getTileXYNeighbor(xOffset, yOffset);
            Site relocatedSite = relocated.getSites()[s.getSite().getSiteIndexInTile()];
            SiteInst relocatedSiteInst = testOutput.getSiteInstFromSite(relocatedSite);
            Assertions.assertNotNull(relocatedSiteInst);
            Assertions.assertEquals(relocatedSiteInst.getCells().size(), s.getCells().size());
        }
    }

    @Test
    public void testGetValidRelocationOptions() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235_2022_1.dcp");
        d.getNet("clk").unroute();
        
        Pair<Site, Map<Integer, Site>> relocOptions = RelocationTools.getValidRelocationOptions(d);

        Assertions.assertNotNull(relocOptions);
        Assertions.assertEquals(215, relocOptions.getSecond().size());
    }
}
