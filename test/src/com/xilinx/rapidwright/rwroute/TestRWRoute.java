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

package com.xilinx.rapidwright.rwroute;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.CodeGenerator;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestRWRoute {
    private static void assertAllPinsRouted(Net net) {
        Map<SitePinInst, Boolean> sourceRouted = new HashMap<>();
        for (SitePinInst spi : net.getPins()) {
            if (spi.isOutPin()) {
                sourceRouted.put(spi, spi.isRouted());
            } else {
                Assertions.assertTrue(spi.isOutPin() || spi.isRouted());
            }
        }

        // Re-compute the isRouted() state by analyzing from PIPs
        DesignTools.updatePinsIsRouted(net);

        for (SitePinInst spi : net.getPins()) {
            if (spi.isOutPin()) {
                Assertions.assertEquals(sourceRouted.get(spi), spi.isRouted());
            } else {
                Assertions.assertTrue(spi.isRouted());
            }
        }
    }

    public static void assertAllPinsRouted(Design design) {
        for (Net net : design.getNets()) {
            if (net.getSource() == null && !net.isStaticNet()) {
                // Source-less nets may exist in out-of-context design
                continue;
            }
            assertAllPinsRouted(net);
        }
    }

    public static void assertAllSourcesRoutedFlagSet(Design design) {
        for (Net net : design.getNets()) {
            if (net.getSource() == null) {
                // Source-less nets may exist in out-of-context design
                continue;
            }
            for (SitePinInst src : new SitePinInst[] { net.getSource(), net.getAlternateSource() }) {
                if (src == null)
                    continue;
                Assertions.assertTrue(src.isRouted() == isSourceUsed(src));
            }
        }
    }

    private static boolean isSourceUsed(SitePinInst src) {
        Node srcNode = src.getConnectedNode();
        for (PIP p : src.getNet().getPIPs()) {
            if (p.getStartNode().equals(srcNode))
                return true;
        }
        return false;
    }

    /**
     * Tests the non-timing driven full routing, i.e., RWRoute running in its wirelength-driven mode.
     * The bnn design from Rosetta benchmarks is used.
     * It is a small heterogeneous design with CLBs, DSPs and BRAMs.
     * The bnn design does not have any clock nets.
     * This test takes around 15s on a machine with a CPU @ 2.5GHz.
     */
    @Test
    @LargeTest
    public void testNonTimingDrivenFullRouting() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        RWRoute.routeDesignFullNonTimingDriven(design);
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the non-timing driven full routing with LUT pin swapping enabled.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",
            "optical-flow.dcp"
    })
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenFullRoutingWithLutPinSwapping(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--lutPinSwapping"});
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the timing-driven full routing with LUT pin swapping enabled. Both designs have LUTs that are
     * routethrus into a CARRY8, whose connections have alternate sinks but whose timing edges go to the
     * CARRY8 rather than to a LUT input. bnn.dcp also has distributed RAMs on A-G LUTs whose address pins are
     * also fed (as their WA) by H site pins, so the connections to their own site pins must be given their timing
     * edges rather than those of the H LUTs.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",
            "optical-flow.dcp"
    })
    @LargeTest(max_memory_gb = 8)
    public void testTimingDrivenFullRoutingWithLutPinSwapping(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--lutPinSwapping"});
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",
            "optical-flow.dcp"
    })
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenFullRoutingWithLutRoutethru(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--lutRoutethru"});
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "bnn.dcp",          // does not activate HUS
            "optical-flow.dcp"  // activates HUS
    })
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenFullRoutingWithHUS(String path) {
        Design design = RapidWrightDCP.loadDCP(path);
        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--hus"});
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @CsvSource({
            "bnn.dcp,false,false",
            "bnn.dcp,false,true",
            "bnn.dcp,true,false",
            "optical-flow.dcp,false,false",
            "optical-flow.dcp,true,true"
    })
    @LargeTest(max_memory_gb = 8)
    public void testFullRoutingWithCUFR(String path, boolean timingDriven, boolean enlargeBoundingBox) {
        Design design = RapidWrightDCP.loadDCP(path);
        CUFR.routeDesignWithUserDefinedArguments(design, new String[]{
                timingDriven ? "--timingDriven" : "--nonTimingDriven",
                enlargeBoundingBox ? "--enlargeBoundingBox" : "--fixBoundingBox",
                "--verbose"
        });
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @Test
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenPartialCUFR() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        design.setTrackNetChanges(true);

        boolean softPreserve = false;
        PartialCUFR.routeDesignWithUserDefinedArguments(design, new String[]{
                "--fixBoundingBox",
                "--useUTurnNodes",
                "--nonTimingDriven",
        }, null, softPreserve);

        Assertions.assertFalse(design.getModifiedNets().isEmpty());
        for (Net net : design.getModifiedNets()) {
            assertAllPinsRouted(net);
        }
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the timing driven full routing, i.e., RWRoute running in timing-driven mode.
     * The bnn design from Rosetta benchmarks is used.
     * It is a small heterogeneous design with CLBs, DSPs and BRAMs.
     * The bnn design does not have any clock nets.
     * In this test, the default {@link RWRouteConfig} options are used. We do not provide DSP logic delays
     * for the timing-driven routing to test the fallback when DSP timing data is missing.
     * This test takes around 20s on a machine with a CPU @ 2.5GHz.
     */
    @Test
    @LargeTest
    public void testTimingDrivenFullRouting() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        RWRoute.routeDesignFullTimingDriven(design);
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the non-timing driven full routing with a design that has a global net.
     * The optical-flow design from Rosetta benchmarks is used.
     * It is the largest heterogeneous design from the Rosetta benchmark set, and has
     * a global enable net.
     * This test takes around 3 minutes on a machine with a CPU @ 2.5GHz.
     */
    @Test
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenFullRoutingWithGlobalNet() {
        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");
        RWRoute.routeDesignFullNonTimingDriven(design);
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests the non-timing driven partial routing, i.e., RWRoute running in its wirelength-driven partial routing mode.
     * The picoblaze design is from one of the RapidWright tutorials with nets between computing kernels not routed.
     * Other nets within each kernel are fully routed.
     * This test takes around 40s on a machine with a CPU @ 2.5GHz.
     */
    @Test
    @LargeTest(max_memory_gb = 8)
    public void testNonTimingDrivenPartialRouting() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        design.setTrackNetChanges(true);

        // Pseudo-randomly unroute some pins from a multi-pin net
        Random random = new Random(0);
        for (Net net : design.getNets()) {
            if (!net.getName().endsWith("/processor/t_state_0")) {
                continue;
            }

            List<SitePinInst> sinkPins = net.getSinkPins();
            Assertions.assertTrue(sinkPins.size() > 1);
            SitePinInst spi = sinkPins.get(random.nextInt(sinkPins.size()));
            DesignTools.unroutePins(net, Collections.singletonList(spi));
        }

        boolean softPreserve = false;
        Design routed = PartialRouter.routeDesignPartialNonTimingDriven(design, null, softPreserve);

        Assertions.assertFalse(routed.getModifiedNets().isEmpty());
        for (Net net : routed.getModifiedNets()) {
            assertAllPinsRouted(net);
        }
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testNonTimingDrivenRoutingOnVersalDevice(boolean partial) {
        // Note: there are no global clocks in this design, just a local clock that doesn't use a BUFG
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        design.setTrackNetChanges(true);

        Net Z_NET = design.createNet(Net.Z_NET);
        int expectedNetsModified = 291;
        if (partial) {
            // Pseudo-randomly unroute at least one pin from each net
            Random random = new Random(0);
            for (Net net : design.getNets()) {
                List<SitePinInst> sinkPins = net.getSinkPins();
                if (sinkPins.isEmpty()) {
                    continue;
                }

                if (net.getName().equals("processor/address_loop[6].output_data.pc_vector_mux_lut/O6")) {
                    // For one hand chosen net, unroute it entirely and block
                    // all of its primary output's downhill PIPs such that 
                    // the alternate output must be used
                    net.unroute();
                    Node sourcePinNode = net.getSource().getConnectedNode();        // CLE_W_CORE_X50Y6/CLE_SLICEL_TOP_0_D_O_PIN
                    Node sourceNode = sourcePinNode.getAllDownhillNodes().get(0);   // CLE_W_CORE_X50Y6/CLE_SLICEL_TOP_0_D_O
                    design.setTrackingChanges(false);
                    for (PIP pip : sourceNode.getAllDownhillPIPs()) {
                        Z_NET.addPIP(pip);
                    }
                    design.setTrackingChanges(true);
                }

                Collections.shuffle(sinkPins, random);
                int numPinsToUnroute = random.nextInt(sinkPins.size()) + 1;
                List<SitePinInst> sinkPinsToUnroute = sinkPins.subList(0, numPinsToUnroute);
                DesignTools.unroutePins(net, sinkPinsToUnroute);
            }

            boolean softPreserve = false;
            PartialRouter.routeDesignPartialNonTimingDriven(design, null, softPreserve);
        } else {
            RWRoute.routeDesignFullNonTimingDriven(design);
        }

        Assertions.assertEquals(expectedNetsModified, design.getModifiedNets().size());
        for (Net net : design.getModifiedNets()) {
            assertAllPinsRouted(net);
        }

        if (FileTools.isVivadoOnPath()) {
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(290, rrs.fullyRoutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
            Assertions.assertEquals(8, rrs.unroutedNets); // There are 8 nets driven from a blackbox cell;
                                                                    // these are marked as routable despite not being so
        }
    }

    /**
     * Tests timing driven partial routing.
     * The picoblaze design is from one of the RapidWright tutorials with nets between computing kernels not routed.
     * Other nets within each kernel are fully routed.
     */
    @Test
    @LargeTest
    @Disabled("Blocked on TimingGraph.build() being able to build partial graphs")
    public void testTimingDrivenPartialRouting() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        design.setTrackNetChanges(true);

        boolean softPreserve = false;
        Design routed = PartialRouter.routeDesignPartialTimingDriven(design, null, softPreserve);

        Assertions.assertFalse(routed.getModifiedNets().isEmpty());
        for (Net net : routed.getModifiedNets()) {
            assertAllPinsRouted(net);
        }
        VivadoToolsHelper.assertFullyRouted(design);
    }

    Design testSingleConnectionHelper(String partName,
                                    String srcSiteName, String srcPinName,
                                    String dstSiteName, String dstPinName,
                                    long nodesPoppedLimit) {
        Design design = new Design("top", partName);

        Net net = design.createNet("net");
        SiteInst srcSi = design.createSiteInst(srcSiteName);
        SitePinInst srcSpi = net.createPin(srcPinName, srcSi);

        SiteInst dstSi = design.createSiteInst(dstSiteName);
        SitePinInst dstSpi = net.createPin(dstPinName, dstSi);

        List<SitePinInst> pinsToRoute = new ArrayList<>();
        pinsToRoute.add(dstSpi);
        boolean softPreserve = false;
        PartialRouter.routeDesignPartialNonTimingDriven(design, pinsToRoute, softPreserve);

        Assertions.assertTrue(srcSpi.isRouted());
        Assertions.assertTrue(dstSpi.isRouted());
        long nodesPopped = Long.parseLong(System.getProperty("rapidwright.rwroute.nodesPopped"));
        Assertions.assertTrue(nodesPopped >= (nodesPoppedLimit - 100) && nodesPopped <= nodesPoppedLimit);

        return design;
    }

    @ParameterizedTest
    @CsvSource({
            // Versal
            // One SLR crossing
            // (Too) close
            "xcv80,SLICE_X54Y331,SLICE_X54Y332,1000",           // Source adjacent to crossing SLL (east)
            "xcv80,SLICE_X53Y332,SLICE_X53Y331,900",            // Source adjacent to crossing SLL (west)
            "xcv80,SLICE_X51Y331,SLICE_X51Y332,400",            // Close to crossing SLL
            // Perfect
            "xcv80,SLICE_X54Y331,SLICE_X54Y406,700",            // Source adjacent to crossing SLL (east, north)
            "xcv80,SLICE_X53Y331,SLICE_X53Y406,200",            // Source adjacent to crossing SLL (west, north)
            "xcv80,SLICE_X54Y406,SLICE_X54Y331,600",            // Sink adjacent to crossing SLL (east, south)
            "xcv80,SLICE_X53Y406,SLICE_X53Y331,100",            // Sink adjacent to crossing SLL (west, south)
            "xcv80,SLICE_X51Y331,SLICE_X51Y406,400",            // Source close to crossing SLL
            "xcv80,SLICE_X0Y331,SLICE_X49Y406,2700",            // Source far from crossing SLL

            // US+
            // One SLR crossing
            // (Too) Close
            Device.AWS_F1 + ",SLICE_X9Y299,SLICE_X9Y300,100",    // On Laguna column
            Device.AWS_F1 + ",SLICE_X9Y300,SLICE_X9Y299,200",
            Device.AWS_F1 + ",SLICE_X0Y299,SLICE_X0Y300,200",    // Far from Laguna column
            Device.AWS_F1 + ",SLICE_X0Y300,SLICE_X0Y299,200",
            Device.AWS_F1 + ",SLICE_X54Y299,SLICE_X56Y300,200",  // Slight closer to one Laguna column that is further from sink
            Device.AWS_F1 + ",SLICE_X54Y300,SLICE_X56Y299,200",
            Device.AWS_F1 + ",SLICE_X50Y299,SLICE_X65Y300,200",
            Device.AWS_F1 + ",SLICE_X50Y300,SLICE_X65Y299,300",
            Device.AWS_F1 + ",SLICE_X55Y299,SLICE_X55Y300,200",  // Equidistant from two Laguna columns
            Device.AWS_F1 + ",SLICE_X55Y300,SLICE_X55Y299,200",
            // Perfect
            Device.AWS_F1 + ",SLICE_X9Y241,SLICE_X9Y300,200",
            Device.AWS_F1 + ",SLICE_X9Y300,SLICE_X9Y241,200",
            Device.AWS_F1 + ",SLICE_X9Y358,SLICE_X9Y299,200",
            Device.AWS_F1 + ",SLICE_X9Y299,SLICE_X9Y358,200",
            Device.AWS_F1 + ",SLICE_X53Y241,SLICE_X69Y300,500",
            Device.AWS_F1 + ",SLICE_X53Y358,SLICE_X69Y299,500",
            // Far
            Device.AWS_F1 + ",SLICE_X9Y240,SLICE_X9Y359,200",    // On Laguna
            Device.AWS_F1 + ",SLICE_X9Y359,SLICE_X9Y240,200",
            Device.AWS_F1 + ",SLICE_X162Y240,SLICE_X162Y430,100",

            Device.AWS_F1 + ",SLICE_X162Y430,SLICE_X162Y240,200",
            Device.AWS_F1 + ",SLICE_X0Y240,SLICE_X12Y430,300",   // Far from Laguna
            Device.AWS_F1 + ",SLICE_X0Y430,SLICE_X12Y240,300",

            // Two SLR crossings
            Device.AWS_F1 + ",SLICE_X162Y299,SLICE_X162Y599,100",
            Device.AWS_F1 + ",SLICE_X162Y599,SLICE_X162Y299,300",

            // Three SLR crossings
            Device.AWS_F1 + ",SLICE_X79Y0,SLICE_X79Y899,200",    // Straight up: on Laguna column (opposite side of Laguna)
            Device.AWS_F1 + ",SLICE_X78Y60,SLICE_X78Y839,400",   // Straight up: on Laguna column (same side as Laguna)
            Device.AWS_F1 + ",SLICE_X0Y0,SLICE_X0Y899,200",      // Straight up: far from Laguna column
            Device.AWS_F1 + ",SLICE_X168Y0,SLICE_X168Y899,300",  // Straight up: far from Laguna column
            Device.AWS_F1 + ",SLICE_X9Y0,SLICE_X162Y899,300",    // Up and right
            Device.AWS_F1 + ",SLICE_X168Y162,SLICE_X9Y899,400",  // Up and left
    })
    public void testSLRCrossingNonTimingDriven(String deviceName, String srcSiteName, String dstSiteName, long nodesPoppedLimit) {
        testSingleConnectionHelper(deviceName, srcSiteName, "AQ", dstSiteName, "A1", nodesPoppedLimit);
    }

    @ParameterizedTest
    @CsvSource({
            // xcvp1902 has no CLE_BC_CORE tiles: the BNODEs/CNODEs/CTRL wires of its CLE pairs are hosted
            // by SLL2 tiles; it also has mirrored SLRs, each with its own tile coordinates
            "SLICE_S0X100Y100,AQ,SLICE_S0X104Y110,A1,186",
            "SLICE_S1X100Y100,AQ,SLICE_S1X104Y110,A1,186",      // Mirrored SLR
            "SLICE_S2X300Y500,AQ,SLICE_S2X310Y520,A1,277",
            "SLICE_S3X300Y500,AQ,SLICE_S3X290Y480,A1,47",
            "SLICE_S0X100Y100,AQ,SLICE_S0X101Y100,CKEN1,35",    // CTRL sink in an SLL2 tile
            "SLICE_S1X201Y301,AQ,SLICE_S1X200Y300,CKEN2,129",
    })
    public void testSingleConnectionXcvp1902(String srcSiteName, String srcPinName,
                                             String dstSiteName, String dstPinName,
                                             long nodesPoppedLimit) {
        testSingleConnectionHelper("xcvp1902", srcSiteName, srcPinName, dstSiteName, dstPinName, nodesPoppedLimit);
    }

    @ParameterizedTest
    @CsvSource({
            // The CTRL wires of the CE/CLK/RST inputs of xcvc1902's IRI_QUAD sites (at the
            // bottom edge of the fabric) are in CLE_BC_CORE_MX tiles
            "SLICE_X60Y0,AQ,IRI_QUAD_X38Y12,CE0,71",
            "SLICE_X60Y0,AQ,IRI_QUAD_X38Y12,RST,73",
    })
    public void testSingleConnectionXcvc1902CleBcCoreMx(String srcSiteName, String srcPinName,
                                                        String dstSiteName, String dstPinName,
                                                        long nodesPoppedLimit) {
        testSingleConnectionHelper("xcvc1902", srcSiteName, srcPinName, dstSiteName, dstPinName, nodesPoppedLimit);
    }

    @ParameterizedTest
    @EnumSource(Series.class)
    public void testRWRouteDeviceSupport(Series series) {
        for (Part part : PartNameTools.getAllParts(series)) {
            Design design = new Design("test", part.getName());
            if (!RWRoute.SUPPORTED_SERIES.contains(series)) {
                RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                        () -> RWRoute.routeDesignFullNonTimingDriven(design),
                        "Expected RuntimeException() but was not thrown.");
                Assertions.assertEquals(e.getMessage(), RWRoute.getUnsupportedSeriesMessage(part));
            }
            // Only test one part per series
            break;
        }
    }

    @Test
    public void testBug701() {
        Design design = RapidWrightDCP.loadDCP("bug701.dcp");

        RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven", "--noInvertGndToVccForLutInputs"});

        Net vcc = design.getVccNet();
        Assertions.assertEquals(1, vcc.getPins().size());
        Assertions.assertTrue(vcc.getPins().stream().allMatch(SitePinInst::isRouted));

        Net gnd = design.getGndNet();
        List<SitePinInst> sinks = gnd.getSinkPins();
        Assertions.assertEquals(31, sinks.size());
        Assertions.assertTrue(sinks.stream().allMatch(SitePinInst::isRouted));

        if (FileTools.isVivadoOnPath()) {
            // Testcase has a number of undriven nets, so just check for unrouted nets
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(0, rrs.unroutedNets);
        }
    }

    private Design generateSmallPlacedDesign() {
        Design d = new Design("HelloWorld", Device.KCU105);
        Cell and2 = d.createAndPlaceCell("and2", Unisim.AND2, "SLICE_X100Y100/A6LUT");
        Net net0 = d.createNet("button0_IBUF");
        net0.connect(and2, "O");
        net0.connect(and2, "I0");
        net0.connect(and2, "I1");

        // Route site internal nets
        d.routeSites();

        EDIFTools.ensureCorrectPartInEDIF(d.getNetlist(), d.getPartName());
        return d;
    }

    @Test
    public void testRWRouteInterchange(@TempDir Path dir) {
        Path rootFile = dir.resolve("interchange-design");
        Interchange.writeDesignToInterchange(generateSmallPlacedDesign(), rootFile.toString());
        Path outputFile = dir.resolve("output.dcp");
        RWRoute.main(new String[] { 
                rootFile.toString() + Interchange.PHYS_NETLIST_EXT, 
                outputFile.toString(),
                "--nonTimingDriven"
                });
        Assertions.assertTrue(Files.exists(outputFile));
    }

    @ParameterizedTest
    @CsvSource({
            "picoblaze_ooc_X10Y235.dcp,false",
            "picoblaze_ooc_X10Y235.dcp,true",
            "gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp,false",
            "gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp,true",
            "optical-flow.dcp,false",
            "optical-flow.dcp,true",
    })
    public void testTimingAndWirelengthReport(String dcpShortPath, boolean verbose) {
        String dcp = RapidWrightDCP.getString(dcpShortPath);
        String[] args = verbose ? new String[]{dcp, "--verbose"} : new String[]{dcp};

        ByteArrayOutputStream capture = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(capture, true));
            TimingAndWirelengthReport.main(args);
        } finally {
            System.setOut(originalOut);
        }

        String output = capture.toString();
        System.out.print(output);
        Assertions.assertTrue(output.contains("Critical path delay (ps):"));
        if (verbose) {
            // Every critical path ends on a flop input, so it must cross into that flop's site
            Assertions.assertTrue(output.contains("(intrasite)"));
            // Each intra-site hop must be attributable to a specific pair of BEL pins
            Assertions.assertFalse(output.contains("(BEL pins not recoverable)"));
        }
    }

    @ParameterizedTest
    @CsvSource({
            // Dedicated connections, hence no nodes popped
            "xcvu3p,GTYE4_CHANNEL_X0Y12,TXOUTCLK_INT,BUFG_GT_SYNC_X0Y46,CLK_IN,0",
            "xcvu3p,GTYE4_CHANNEL_X0Y12,TXOUTCLK_INT,BUFG_GT_X0Y78,CLK_IN,0", // (dst pin can be projected to INT but not src pin)

            // Non-dedicated connections
            "xcvu3p,IOB_X0Y47,I,SLICE_X77Y122,FX,100",

            // 240 CLB height SLR, no LAG tiles on Y0 (since HBM on bottom edge)
            "xcu50,SLICE_X38Y239,AQ,SLICE_X38Y240,A1,100",

            // SLR crossing whose bounding box, before the SLL height check, is exactly one row too
            // short to hold an SLL strictly inside it
            "xcvu13p,SLICE_X117Y218,D_O,SLICE_X116Y250,CX,200"
    })
    public void testSingleConnection(String partName,
                                     String srcSiteName, String srcPinName,
                                     String dstSiteName, String dstPinName,
                                     int nodesPoppedLimit) {
        testSingleConnectionHelper(partName,
                srcSiteName, srcPinName,
                dstSiteName, dstPinName,
                nodesPoppedLimit);
    }

    @Test
    public void testDedicatedClockInputsSharingFabricInput() {
        // Two clock-capable HDIO inputs, each driving a BUFGCE_HDIO of the same RCLK_HDIO tile through its
        // dedicated path. Both BUFGCE_HDIOs can also be reached from the fabric, but only through a single
        // shared INT input: these connections must be routed through their dedicated paths
        Design design = new Design("top", "xcku9p-ffve900-2-e");
        List<SitePinInst> pinsToRoute = new ArrayList<>();
        List<Net> nets = new ArrayList<>();
        String[][] ioAndBuffer = {{"IOB_X0Y164", "BUFGCE_HDIO_X0Y2"}, {"IOB_X0Y182", "BUFGCE_HDIO_X0Y3"}};
        for (String[] pair : ioAndBuffer) {
            Net net = design.createNet(pair[0] + "_I");
            net.createPin("I", design.createSiteInst(pair[0]));
            pinsToRoute.add(net.createPin("CLK_IN", design.createSiteInst(pair[1])));
            nets.add(net);
        }
        PartialRouter.routeDesignPartialNonTimingDriven(design, pinsToRoute, false);

        for (Net net : nets) {
            assertAllPinsRouted(net);
            Assertions.assertFalse(net.getPIPs().isEmpty());
            for (PIP pip : net.getPIPs()) {
                Assertions.assertNotEquals(TileTypeEnum.INT, pip.getTile().getTileTypeEnum(), pip.toString());
            }
        }
    }

    /**
     * Creates and routes a net from a clock-capable HDIO input to both a BUFGCE_HDIO, which it drives through a
     * dedicated path, and the AX and BX pins of the SLICEL nearest to its input's projection onto the fabric.
     */
    private static Net createAndRouteNetWithDedicatedClockInput(Design design) {
        Net net = design.createNet("IOB_X0Y164_I");
        SitePinInst source = net.createPin("I", design.createSiteInst("IOB_X0Y164"));
        net.createPin("CLK_IN", design.createSiteInst("BUFGCE_HDIO_X0Y2"));
        SiteInst slice = design.createSiteInst(getNearestSLICELs(design, source, 1).get(0));
        net.createPin("AX", slice);
        net.createPin("BX", slice);
        PartialRouter.routeDesignPartialNonTimingDriven(design, net.getSinkPins(), false);
        assertAllPinsRouted(net);
        return net;
    }

    private static List<Site> getNearestSLICELs(Design design, SitePinInst source, int count) {
        Tile intTile = RouterHelper.projectOutputPinToINTNode(source).getTile();
        List<Site> slices = new ArrayList<>(Arrays.asList(design.getDevice().getAllSitesOfType(SiteTypeEnum.SLICEL)));
        slices.sort(Comparator.comparingInt((s) -> s.getTile().getManhattanDistance(intTile)));
        return slices.subList(0, count);
    }

    private static SitePinInst getSinkPin(Net net, String name) {
        return net.getSinkPins().stream().filter((spi) -> spi.getName().equals(name)).findFirst().get();
    }

    @Test
    public void testPartialRoutingPreservesDedicatedClockInput() {
        // Routing another sink of a net must keep the (preserved) routing of its sink driven through a
        // dedicated path
        Design design = new Design("top", "xcku9p-ffve900-2-e");
        Net net = createAndRouteNetWithDedicatedClockInput(design);
        SitePinInst ax = getSinkPin(net, "AX");
        DesignTools.unroutePins(net, Collections.singletonList(ax));
        Set<PIP> preservedPIPs = new HashSet<>(net.getPIPs());

        PartialRouter.routeDesignPartialNonTimingDriven(design, Collections.singletonList(ax), false);

        assertAllPinsRouted(net);
        Assertions.assertTrue(net.getPIPs().containsAll(preservedPIPs));
    }

    @Test
    public void testUnpreserveNetWithDedicatedClockInput() {
        // Move a sink pin from a fully routed net, with a sink driven through a dedicated path, onto another net
        // without ripping up its routing: that routing blocks the other net's sink, so the first net gets
        // unpreserved, which must cope with its direct connection to the clock buffer
        Design design = new Design("top", "xcku9p-ffve900-2-e");
        Net net = createAndRouteNetWithDedicatedClockInput(design);
        SitePinInst bx = getSinkPin(net, "BX");
        List<PIP> pips = new ArrayList<>(net.getPIPs());
        Assertions.assertTrue(net.removePin(bx));
        net.setPIPs(pips);
        Net other = design.createNet("other");
        other.createPin("AQ", design.createSiteInst(getNearestSLICELs(design, net.getSource(), 2).get(1)));
        other.addPin(bx);

        PartialRouter.routeDesignPartialNonTimingDriven(design, Collections.singletonList(bx), false);

        Set<Node> nodes = new HashSet<>();
        for (PIP pip : net.getPIPs()) {
            nodes.add(pip.getStartNode());
            nodes.add(pip.getEndNode());
        }
        for (PIP pip : other.getPIPs()) {
            Assertions.assertFalse(nodes.contains(pip.getStartNode()), pip.toString());
            Assertions.assertFalse(nodes.contains(pip.getEndNode()), pip.toString());
        }
        assertAllPinsRouted(net);
        assertAllPinsRouted(other);
    }

    @Test
    public void testDiscussion1245_20250805() {
        // Adapted from https://github.com/Xilinx/RapidWright/discussions/1245#discussioncomment-14003055
        Design test_place = new Design("test_design", "vp1202");

        Cell cell_1 = test_place.createAndPlaceCell("my_test_cell_1", Unisim.LUT6, "SLICE_X342Y0/A6LUT");
        LUTTools.configureLUT(cell_1, "O=I1");
        cell_1.fixCell(true);

        Cell cell_2 = test_place.createAndPlaceCell("my_test_cell_2", Unisim.LUT6, "SLICE_X342Y0/C6LUT");
        LUTTools.configureLUT(cell_2, "O=I1");
        cell_2.fixCell(true);

        Net net = test_place.createNet("my_test_net");
        ECOTools.connectNet(test_place, cell_1, "O", net);
        ECOTools.connectNet(test_place, cell_2, "I1", net);

        Design test_route = test_place;

        test_route.routeSites();

        RWRoute.routeDesignFullNonTimingDriven(test_route);
    }

    private static int getCriticalPathDelay(String report) {
        Matcher matcher = Pattern.compile("Critical path delay \\(ps\\):\\s+(\\d+)").matcher(report);
        Assertions.assertTrue(matcher.find());
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Tests timing-driven routing with LUT pin swapping on a minimal design: a flop drives a LUT input
     * placed on the LUT's slowest pin (A1->O6 has a much larger logic delay than A6->O6), and the LUT
     * drives the flop back. Routing must weigh each candidate pin's LUT logic delay, rather than route
     * delay alone, so the input should move to one of the fastest pins; and the timing graph must
     * follow the pin that the connection is routed to, so that RWRoute's own critical path matches a
     * fresh timing analysis of the routed design.
     */
    @Test
    public void testTimingDrivenLutPinSwapping() {
        Design design = new Design("top", "xcvu3p");
        Cell lut = design.createAndPlaceCell("lut", Unisim.LUT1, "SLICE_X40Y61/A6LUT");
        LUTTools.configureLUT(lut, "O=I0");
        lut.removePinMapping(lut.getPhysicalPinMapping("I0"));
        lut.addPinMapping("A1", "I0");
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDCE, "SLICE_X40Y60/AFF");

        Net q = design.createNet("q");
        ECOTools.connectNet(design, ff, "Q", q);
        ECOTools.connectNet(design, lut, "I0", q);
        Net o = design.createNet("o");
        ECOTools.connectNet(design, lut, "O", o);
        ECOTools.connectNet(design, ff, "D", o);
        design.routeSites();

        ByteArrayOutputStream routeCapture = new ByteArrayOutputStream();
        ByteArrayOutputStream reportCapture = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(routeCapture, true));
            RWRoute.routeDesignWithUserDefinedArguments(design, new String[] {"--lutPinSwapping"});
            System.setOut(new PrintStream(reportCapture, true));
            RWRouteConfig config = new RWRouteConfig(new String[0]);
            config.setTimingDriven(true);
            new TimingAndWirelengthReport(design, config, false).computeStatisticsAndReport();
        } finally {
            System.setOut(originalOut);
        }
        System.out.print(routeCapture);
        System.out.print(reportCapture);

        // The A1..A6 -> O6 logic delays are 150/125/100/90/50/35 ps, whereas routing to the different
        // input pins of the same LUT differs by much less (e.g. the A1..A6 site pin delays are 74..43 ps),
        // so the input must move to one of the two fastest pins
        String pin = lut.getPhysicalPinMapping("I0");
        Assertions.assertTrue(pin.equals("A5") || pin.equals("A6"), "I0 routed to pin " + pin);
        Assertions.assertEquals(getCriticalPathDelay(reportCapture.toString()),
                getCriticalPathDelay(routeCapture.toString()));
    }

    /**
     * Tests LUT pin swapping when one net drives two inputs of the same LUT: each of the two
     * connections may be routed to any free pin of the LUT, but not to the pin used by the other
     * (each LUT input needs its own pin), even though routing normally lets connections of the
     * same net share nodes.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testLutPinSwappingSameNetTwoLutInputs(boolean timingDriven) {
        Design design = new Design("top", "xcvu3p");
        Cell lut = design.createAndPlaceCell("lut", Unisim.LUT2, "SLICE_X40Y61/A6LUT");
        LUTTools.configureLUT(lut, "O=I0&I1");
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDCE, "SLICE_X40Y60/AFF");

        Net q = design.createNet("q");
        ECOTools.connectNet(design, ff, "Q", q);
        ECOTools.connectNet(design, lut, "I0", q);
        ECOTools.connectNet(design, lut, "I1", q);
        Net o = design.createNet("o");
        ECOTools.connectNet(design, lut, "O", o);
        ECOTools.connectNet(design, ff, "D", o);
        design.routeSites();

        RWRoute.routeDesignWithUserDefinedArguments(design, timingDriven
                ? new String[] {"--lutPinSwapping"}
                : new String[] {"--nonTimingDriven", "--lutPinSwapping"});

        assertAllPinsRouted(design);
        String i0 = lut.getPhysicalPinMapping("I0");
        String i1 = lut.getPhysicalPinMapping("I1");
        Assertions.assertNotNull(i0);
        Assertions.assertNotNull(i1);
        Assertions.assertNotEquals(i0, i1);
        Assertions.assertEquals(3, q.getPins().size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testDiscussion1245_20250807(boolean forceLagPin) {
        // Adapted from https://github.com/Xilinx/RapidWright/discussions/1245#discussioncomment-14035707

        Design test_place = new Design("test_design", "vp1202");

        Cell cell_1 = test_place.createAndPlaceCell("my_test_cell_1", Unisim.LUT6, "SLICE_X100Y0/A6LUT");
        LUTTools.configureLUT(cell_1, "O=I1");
        cell_1.fixCell(true);

        Cell cell_2 = test_place.createAndPlaceCell("my_test_cell_2", Unisim.LUT6, "SLICE_X100Y0/H6LUT");
        LUTTools.configureLUT(cell_2, "O=I1");
        cell_2.fixCell(true);

        Cell ff = test_place.createAndPlaceCell("flipflop", Unisim.FDCE, "SLICE_X100Y0/AFF");
        ff.fixCell(true);

        Net net = test_place.createNet("my_test_net_0");
        ECOTools.connectNet(test_place, cell_1, "O", net);
        ECOTools.connectNet(test_place, cell_2, "I1", net);

        Net outer_net_1 = test_place.createNet("my_test_net_1");
        ECOTools.connectNet(test_place, cell_2, "O", outer_net_1);
        ECOTools.connectNet(test_place, ff, "D", outer_net_1);

        Net outer_net_2 = test_place.createNet("my_test_net_2");
        ECOTools.connectNet(test_place, ff, "Q", outer_net_2);
        ECOTools.connectNet(test_place, cell_1, "I1", outer_net_2);

        Design test_route = test_place;

        test_route.routeSites();

        Assertions.assertEquals("SLICE_X100Y0.AX", ff.getSitePinFromLogicalPin("D", null).getSitePinName());

        // Test for intra-SLR connections to the LAG input
        if (forceLagPin) {
            SiteInst si = ff.getSiteInst();
            SitePinInst spi = si.getSitePinInst("AX");
            Assertions.assertTrue(si.unrouteIntraSiteNet(spi.getBELPin(), ff.getBEL().getPin("D")));
            Assertions.assertTrue(spi.movePin("LAG_E2"));
            Assertions.assertTrue(si.routeIntraSiteNet(spi.getNet(), spi.getBELPin(), ff.getBEL().getPin("D")));
        }

        RWRoute.routeDesignFullNonTimingDriven(test_route);

        VivadoToolsHelper.assertFullyRouted(test_route);
    }

    @Test
    public void testRWRouteSubstituteBlackBoxFlow(@TempDir Path dir) {
        // Load an example design, make sure RWRoute can route it
        Path dcp = RapidWrightDCP.getPath("bnn.dcp");
        Design design = Design.readCheckpoint(dcp);
        RWRoute.routeDesignFullNonTimingDriven(design);
        // VivadoToolsHelper.assertFullyRouted(design);
        
        // Pick a cell to blackbox
        String instToBlackBox = "bd_0_i/hls_inst/inst/grp_bin_dense_fu_523";
        EDIFHierCellInst inst = design.getNetlist().getHierCellInstFromName(instToBlackBox);
        
        // Create example external library, store the guts of this netlist in external lib
        String cellType = "bd_0_hls_inst_0_bin_dense";
        EDIFNetlist external = EDIFTools.createNewNetlist(inst.getInst());
        Path externalEDIF = dir.resolve(cellType + ".edn");
        external.exportEDIF(externalEDIF);
        
        inst.getCellType().makePrimitive(); // makeBlackBox
        
        // Write out top EDIF that now has a black boxed cell
        Path topEDIF = dir.resolve("bnn.edf");
        design.getNetlist().collapseMacroUnisims(design.getSeries());
        design.getNetlist().exportEDIF(topEDIF);
        
        // Re-load DCP with black boxed netlist
        Design designWithBlackBox = Design.readCheckpoint(dcp, topEDIF);

        // Load external lib, set it as the netlist's external library
        EDIFNetlist externalLib = EDIFTools.readEdifFile(externalEDIF);
        EDIFCell cell = designWithBlackBox.getNetlist().getCellInstFromHierName(instToBlackBox).getCellType();
        Assertions.assertTrue(cell.isLeafCellOrBlackBox() && !cell.isPrimitive());
        designWithBlackBox.getNetlist().setExternalLibrary(externalLib.getLibrary("work"));
        cell = designWithBlackBox.getNetlist().getCellInstFromHierName(instToBlackBox).getCellType();
        Assertions.assertFalse(cell.isLeafCellOrBlackBox());
        
        // Run RWRoute
        RWRoute.routeDesignFullNonTimingDriven(designWithBlackBox);

        // Sanity check on routing
        for (Net net : design.getNets()) {
            Net other = designWithBlackBox.getNet(net.getName());
            Assertions.assertNotNull(other);
            Assertions.assertEquals(net.getPins().size(), other.getPins().size());
            Assertions.assertEquals(net.hasPIPs(), other.hasPIPs());
        }

        // Return the netlist back to its original state for Vivado to read it correctly
        designWithBlackBox.getNetlist().blackBoxExternalCells();
        designWithBlackBox.getNetlist().setExternalLibrary(null);
        EDIFCell guts = externalLib.getCell(cellType);
        EDIFHierCellInst instToRestore = designWithBlackBox.getNetlist()
                .getHierCellInstFromName(instToBlackBox);
        designWithBlackBox.getNetlist().getWorkLibrary().removeCell(instToRestore.getCellType());
        designWithBlackBox.getNetlist().copyCellAndSubCells(guts);
        guts = designWithBlackBox.getNetlist().getWorkLibrary().getCell(cellType);
        instToRestore.getInst().setCellType(guts);

        VivadoToolsHelper.assertFullyRouted(designWithBlackBox);
    }

    @Test
    public void testRWRouteSubstituteNetlist(@TempDir Path dir) {
        if (FileTools.isVivadoOnPath() && FileTools.isVivadoAtLeastVersion(2024, 1)) {
            Path dcp = RapidWrightDCP.getPath("multiply_ip.dcp");
            Path outputLog = dir.resolve("output.log");
            Path tclScript = dir.resolve("run.tcl");
            Path subNetlist = dir.resolve("sub.edf");

            List<String> tclCmds = new ArrayList<>();
            tclCmds.add("open_checkpoint " + dcp.toString());
            tclCmds.add("source " + FileTools.getRapidWrightPath() + "/tcl/rapidwright.tcl");
            tclCmds.add("set cell_to_write [get_cells [lsort -unique [get_cells [get_property PARENT "
                    + "[get_cells -hierarchical -filter {is_du_within_envelope==1}]]]] "
                    + "-filter {is_du_within_envelope!=1}]");
            tclCmds.add("write_cell_to_edif $cell_to_write " + subNetlist.toString());
            FileTools.writeLinesToTextFile(tclCmds, tclScript.toString());
            VivadoTools.runTcl(outputLog, tclScript, true);

            Design d = Design.readCheckpoint(dcp);
            EDIFNetlist externalLib = EDIFTools.readEdifFile(subNetlist);
            d.getNetlist().setExternalLibrary(externalLib.getHDIPrimitivesLibrary());

            RWRoute.routeDesignFullNonTimingDriven(d);

            d.getNetlist().blackBoxExternalCells();
            d.getNetlist().setExternalLibrary(null);

            VivadoToolsHelper.assertFullyRouted(d);

        }
    }

    @Test
    public void testRWRouteVersalSLRCrossing() {
        Path dcp = RapidWrightDCP.getPath("versal_slr_crossing.dcp");

        Design design = Design.readCheckpoint(dcp);
        RWRoute.routeDesignFullNonTimingDriven(design);
        assertAllSourcesRoutedFlagSet(design);
        assertAllPinsRouted(design);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    @ParameterizedTest
    @CsvSource({
            // Check that no routethru-s are used when terminating at the LAG pin to reach the FF inside the slice
            "SLICE_X95Y621,SLICE_X93Y546,LAG_N,3,false",
            "SLICE_X95Y621,SLICE_X92Y546,LAG_N,3, false",

            // Connecting to a non-LAG pin requires routethru
            "SLICE_X95Y621,SLICE_X93Y456,FX,700,true",
            "SLICE_X95Y621,SLICE_X92Y456,EX,500,true"
    })
    public void testRWRouteVersalSLRCrossingStraightIntoFlop(String srcSiteName, String dstSiteName, String dstPinName, int nodesPoppedLimit, boolean expectRoutethru) {
        Design design = testSingleConnectionHelper("xcv80",
                srcSiteName, "CQ",
                dstSiteName, dstPinName,
                nodesPoppedLimit);
        Net net = design.getNet("net");
        if (!expectRoutethru) {
            Assertions.assertEquals(6, net.getPIPs().size());
        }
        boolean routethruFound = false;
        for (PIP pip : net.getPIPs()) {
            routethruFound |= pip.isRouteThru();
        }
        Assertions.assertEquals(expectRoutethru, routethruFound);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPartialRouterEnsureSinkRoutabilityStatic(boolean preserveInner) {
        Design design = new Design("top", "xcv80");

        Net net = design.createNet("net");
        SiteInst si = design.createSiteInst("SLICE_X95Y621");
        SitePinInst srcSpi = net.createPin("A_O", si);
        SitePinInst dstSpi = net.createPin("A_I", si);

        // Attach either the inner or outer connected node 
        // of the dstSpi above to the gnd net
        Net gndNet = design.getGndNet();
        Node preserveNode = dstSpi.getConnectedNode();
        if (!preserveInner) {
            List<Node> uphillNodes = preserveNode.getAllUphillNodes();
            Assertions.assertEquals(1, uphillNodes.size());
            preserveNode = uphillNodes.get(0);
        }
        List<PIP> uphillPIPs = preserveNode.getAllUphillPIPs();
        gndNet.addPIP(uphillPIPs.get(0));

        List<SitePinInst> pinsToRoute = new ArrayList<>();
        pinsToRoute.add(dstSpi);
        PartialRouter.routeDesignPartialNonTimingDriven(design, pinsToRoute);

        // Check that dstSpi does not get routed because its
        // inner or outer node is preserved for the ground net.
        // Check also that an assertion doesn't fire, but this test
        // is not able to verify that an "ERROR" message gets emitted.
        Assertions.assertFalse(dstSpi.isRouted());
        Assertions.assertFalse(net.hasPIPs());
    }

    @Test
    public void testPartialRouterPreservesOuterNode() {
        Design design = new Design("top", "xcv80");

        // This is the signal sink, the node(s) to which should not be claimed by static routing
        Net signalNet = design.createNet("net");
        SiteInst signalSi = design.createSiteInst("SLICE_X94Y621");
        SitePinInst signalSpi = signalNet.createPin("DX", signalSi);

        Device device = design.getDevice();
        Net Z_NET = design.createNet(Net.Z_NET);

        // Block these nodes so that there's only one path
        // Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_119_INT_OUT1").getAllUphillPIPs().get(0)); // Force signal through this node
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_122_INT_OUT0").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_125_INT_OUT0").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_53_INT_OUT1").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_57_INT_OUT1").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y625/INT_NODE_IMUX_ATOM_60_INT_OUT0").getAllUphillPIPs().get(0));

        Z_NET.addPIP(device.getNode("SLL_X29Y625/BNODE_OUTS_E0").getAllUphillPIPs().get(0));
        // Z_NET.addPIP(device.getNode("INT_X29Y625/BOUNCE_W2").getAllUphillPIPs().get(0)); // Force signal through this node (which is signalSpi)
        Z_NET.addPIP(device.getNode("INT_X28Y625/OUT_EE1_E_BEG12").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y626/OUT_SS4_W_BEG6").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X29Y626/OUT_SS1_W_BEG12").getAllUphillPIPs().get(0));
        Z_NET.addPIP(device.getNode("INT_X30Y625/OUT_WW2_W_BEG6").getAllUphillPIPs().get(0));

        Net gndNet = design.getGndNet();
        SiteInst gndSi = design.createSiteInst("SLICE_X95Y621");
        SitePinInst gndSpi = gndNet.createPin("HX", gndSi);
        PartialRouter.routeDesignPartialNonTimingDriven(design, Collections.singletonList(gndSpi));

        // Make sure the ground sink is not routed, since INT_X29Y625/BOUNCE_W2
        // is now reserved for signalSpi
        Assertions.assertFalse(gndSpi.isRouted());
        Assertions.assertFalse(gndNet.hasPIPs());
    }

    /**
     * Checks that PartialRouter preserves a signal net that Vivado's high-fanout net
     * optimization has routed through a leaf clock buffer (BUFCE_LEAF). Such a net leaves
     * the interconnect for the RCLK_INT_L tile, passes through the buffer's CE input
     * (NODE_PINFEED) onto its clock output (NODE_GLOBAL_LEAF), and re-enters the
     * interconnect on a GCLK node.
     *
     * This covers the NODE_PINFEED and NODE_GLOBAL_LEAF cases of
     * RouteNode.setBaseCost(); see testPartialRouterPreservesNodeGlobalHdistr() for the
     * third.
     *
     * RCLK tiles are excluded from the routing graph, so nodes inside them exist only to
     * carry the previous pointers of an already-routed net. Without the excluded-tile early
     * return in RouteNodeInfo.getType(), the NODE_PINFEED node falls through to an
     * eastWestWires lookup that has no entry for RCLK_INT_L and throws a
     * NullPointerException; without those two intent codes in RouteNode.setBaseCost(), the
     * same nodes hit its default case and throw
     * "ERROR: Unexpected IntentCode: NODE_PINFEED"/"NODE_GLOBAL_LEAF".
     *
     * The net below is taken verbatim from a real routed design.
     */
    @Test
    public void testPartialRouterPreservesNodeGlobalLeaf() {
        Design design = new Design("top", "xczu3eg");

        // Source and sink are both on this site: the net loops out through the leaf
        // clock buffer and back into the same SLICE
        SiteInst si = design.createSiteInst("SLICE_X9Y90");
        Net net = CodeGenerator.createTestNet(design, "net", new String[]{
                "INT_X6Y90/INT.LOGIC_OUTS_W7->INT_NODE_SDQ_55_INT_OUT1",
                "INT_X6Y90/INT.INT_NODE_SDQ_55_INT_OUT1->>SS1_W_BEG2",
                "RCLK_INT_L_X6Y89/RCLK_INT_L.SOUTHBUSOUT_FT0_27->>INT_NODE_IMUX_13_INT_OUT0",
                "RCLK_INT_L_X6Y89/RCLK_INT_L.INT_NODE_IMUX_13_INT_OUT0->>CLK_LEAF_SITES_30_CE_INT",     // NODE_PINFEED (BUFCE_LEAF.CE_INT)
                "RCLK_INT_L_X6Y89/RCLK_INT_L.CLK_LEAF_SITES_30_CE_INT->>CLK_LEAF_SITES_30_CLK_LEAF",    // NODE_GLOBAL_LEAF
                "INT_X6Y90/INT.GCLK_B_0_15->>INT_NODE_GLOBAL_12_INT_OUT0",
                "INT_X6Y90/INT.INT_NODE_GLOBAL_12_INT_OUT0->>INT_NODE_IMUX_61_INT_OUT1",
                "INT_X6Y90/INT.INT_NODE_IMUX_61_INT_OUT1->>BOUNCE_W_13_FT0"
        });
        net.createPin("EQ2", si);
        SitePinInst routedSpi = net.createPin("F_I", si);
        routedSpi.setRouted(true);

        // An additional, unrouted sink so that the router must also expand (and thus
        // exercise the routing graph) rather than only recover the existing routing
        SiteInst unroutedSi = design.createSiteInst("SLICE_X9Y92");
        SitePinInst unroutedSpi = net.createPin("A1", unroutedSi);

        assertPartialRouterAugmentsRouting(design, net, routedSpi, unroutedSpi);
    }

    /**
     * As testPartialRouterPreservesNodeGlobalLeaf(), but for a net that Vivado has
     * additionally routed through a horizontal distribution buffer (BUFCE_ROW_FSR), whose
     * output node is a NODE_GLOBAL_HDISTR in RCLK_BRAM_INTF_L. This net crosses all three
     * of the intent codes that RouteNode.setBaseCost() has to accept on preserved routing,
     * and is the only one of the two to reach NODE_GLOBAL_HDISTR.
     *
     * The net below is the source-to-sink path of one connection of a real routed net.
     */
    @Test
    public void testPartialRouterPreservesNodeGlobalHdistr() {
        Design design = new Design("top", "xczu3eg");

        SiteInst srcSi = design.createSiteInst("SLICE_X36Y149");
        SiteInst dstSi = design.createSiteInst("SLICE_X37Y148");
        Net net = CodeGenerator.createTestNet(design, "net", new String[]{
                "INT_X23Y149/INT.LOGIC_OUTS_E30->SDQNODE_E_93_FT0",
                "INT_X23Y150/INT.SDQNODE_E_BLN_93_FT1->>WW2_E_BEG0",
                "INT_X22Y149/INT.WW2_E_BLS_0_FT0->INT_NODE_SDQ_47_INT_OUT0",
                "INT_X22Y149/INT.INT_NODE_SDQ_47_INT_OUT0->>EE1_E_BEG7",
                "INT_X23Y149/INT.EE1_E_END7->INT_NODE_SDQ_93_INT_OUT0",
                "INT_X23Y149/INT.INT_NODE_SDQ_93_INT_OUT0->>NN1_W_BEG7",
                "RCLK_INT_L_X23Y149/RCLK_INT_L.NORTHBUSOUT_FT0_28->>INT_NODE_IMUX_23_INT_OUT1",
                "RCLK_INT_L_X23Y149/RCLK_INT_L.INT_NODE_IMUX_23_INT_OUT1->>INT_RCLK_TO_CLK_LEFT_0_0",                           // NODE_PINFEED (BUFCE_ROW_FSR.CE_PRE_OPTINV)
                "RCLK_BRAM_INTF_L_X23Y149/RCLK_BRAM_INTF_L.CLK_BUFCE_ROW_FSR_0_CE_PRE_OPTINV->>CLK_BUFCE_ROW_FSR_0_CLK_OUT",    // NODE_GLOBAL_HDISTR
                "RCLK_BRAM_INTF_L_X23Y149/RCLK_BRAM_INTF_L.CLK_BUFCE_ROW_FSR_0_CLK_OUT->>CLK_TEST_BUF_SITE_1_CLK_IN",           // NODE_GLOBAL_HDISTR
                "RCLK_INT_L_X24Y149/RCLK_INT_L.CLK_HDISTR_FT0_7->>CLK_LEAF_SITES_15_CLK_IN",                                    // INTENT_DEFAULT (BUFCE_LEAF.CLK_IN)
                "RCLK_INT_L_X24Y149/RCLK_INT_L.CLK_LEAF_SITES_15_CLK_IN->>CLK_LEAF_SITES_15_CLK_LEAF",                          // NODE_GLOBAL_LEAF
                "INT_X24Y149/INT.GCLK_B_0_9->>INT_NODE_GLOBAL_13_INT_OUT0",
                "INT_X24Y149/INT.INT_NODE_GLOBAL_13_INT_OUT0->>INT_NODE_IMUX_62_INT_OUT1",
                "INT_X24Y149/INT.INT_NODE_IMUX_62_INT_OUT1->>BYPASS_W1",
                "INT_X24Y149/INT.BYPASS_W1->>INT_NODE_IMUX_38_INT_OUT0",
                "INT_X24Y149/INT.INT_NODE_IMUX_38_INT_OUT0->>BYPASS_W6",
                "INT_X24Y149/INT.BYPASS_W6->>INODE_W_9_FT1",
                "INT_X24Y148/INT.INODE_W_BLS_9_FT0->>BOUNCE_W_15_FT0"
        });
        net.createPin("EQ", srcSi);
        SitePinInst routedSpi = net.createPin("H_I", dstSi);
        routedSpi.setRouted(true);

        // An additional, unrouted sink so that the router must also expand (and thus
        // exercise the routing graph) rather than only recover the existing routing
        SiteInst unroutedSi = design.createSiteInst("SLICE_X37Y151");
        SitePinInst unroutedSpi = net.createPin("A1", unroutedSi);

        assertPartialRouterAugmentsRouting(design, net, routedSpi, unroutedSpi);
    }

    /**
     * Asks PartialRouter to route the unrouted sink of an otherwise already-routed net, and
     * checks that the routing it hands back is a strict superset of the routing it was
     * given: every existing PIP must be preserved -- thus the clock buffer routethru that
     * the already-routed sink is reached through -- and new PIPs must have been added to
     * reach the unrouted sink.
     * @param design The design holding the net.
     * @param net The partially-routed net.
     * @param routedSpi The net's already-routed sink pin, not given to the router.
     * @param unroutedSpi The net's unrouted sink pin; the only pin given to the router.
     */
    private void assertPartialRouterAugmentsRouting(Design design, Net net, SitePinInst routedSpi,
                                                    SitePinInst unroutedSpi) {
        List<PIP> expectedPIPs = new ArrayList<>(net.getPIPs());
        Assertions.assertFalse(expectedPIPs.isEmpty());
        Assertions.assertTrue(routedSpi.isRouted());
        Assertions.assertFalse(unroutedSpi.isRouted());

        PartialRouter.routeDesignPartialNonTimingDriven(design, Collections.singletonList(unroutedSpi));

        for (SitePinInst spi : net.getSinkPins()) {
            Assertions.assertTrue(spi.isRouted());
        }
        // The router is free to hand the routing back in a different order
        List<PIP> actualPIPs = net.getPIPs();
        Assertions.assertTrue(new HashSet<>(actualPIPs).containsAll(expectedPIPs));
        Assertions.assertTrue(actualPIPs.size() > expectedPIPs.size());

        // Note that the newly-routed sink is not expected to share this net's routing through
        // the clock buffer, no matter where it is placed. The buffer's output node (a
        // NODE_GLOBAL_LEAF, or a NODE_GLOBAL_HDISTR for the row distribution buffer) spans the
        // full height/width that it distributes to -- e.g. RCLK_INT_L_X6Y89's
        // CLK_LEAF_SITES_30_CLK_LEAF ends at INT_X6Y119, the top of its clock region -- and
        // RouteNode.isInConnectionBoundingBox() tests the end tile coordinate, which thus lies
        // far outside the bounding box of any connection that a sink near the buffer could
        // create. Reaching the buffer is not necessary here: this test only requires that such
        // routing be preserved, not extended.
    }

    @Test
    public void testClockNetsDrivenByBUFGGTRoutedFirst() {
        // A BUFG_GT reaches a single clock routing track, fixed by its site: its clock is routed before the
        // clocks of other buffers, which can avoid that track
        Design design = new Design("clock_order", "xcvu3p-ffvc1517-2-e");
        List<Net> clks = new ArrayList<>();
        int i = 0;
        for (String bufg : new String[] {"BUFGCE_X0Y48", "BUFG_GT_X0Y29", "BUFGCE_X0Y49"}) {
            Net clk = design.createNet("clk" + i);
            Unisim type = bufg.startsWith("BUFG_GT") ? Unisim.BUFG_GT : Unisim.BUFGCE;
            clk.connect(design.createAndPlaceCell("bufg" + i, type, bufg + "/" + (type == Unisim.BUFG_GT ? "BUFG_GT" : "BUFCE")), "O");
            Cell ff = design.createAndPlaceCell("ff" + i, Unisim.FDRE, "SLICE_X40Y" + (100 + i) + "/AFF");
            clk.connect(ff, "C");
            clks.add(clk);
            i++;
        }
        RWRoute router = new RWRoute(design, new RWRouteConfig(new String[] {"--nonTimingDriven"}));
        router.preprocess();
        router.initialize();
        List<Net> order = router.getClkNetsInRoutingOrder();
        Assertions.assertEquals(3, order.size());
        Assertions.assertEquals(clks.get(1), order.get(0));
    }

    @Test
    public void testSinksSharingINTNode() {
        // The same input of the two BITSLICE_CONTROLs of a byte of the XIPHY is fed from a single INT tile node:
        // the connections to both sinks share it
        Design design = new Design("shared_sink", "xcvu3p-ffvc1517-2-e");
        Device device = design.getDevice();
        Cell ff = design.createAndPlaceCell("ff", Unisim.FDRE, "SLICE_X35Y23/GFF");
        Net net = design.createNet("riu_addr");
        net.connect(ff, "Q");
        List<SitePinInst> sinks = new ArrayList<>();
        for (String site : new String[] {"BITSLICE_CONTROL_X0Y6", "BITSLICE_CONTROL_X0Y7"}) {
            SiteInst si = design.createSiteInst(device.getSite(site));
            sinks.add(net.createPin("CLB2RIU_ADDR2", si));
        }
        Node intNode = RouterHelper.projectInputPinToINTNode(sinks.get(0));
        Assertions.assertNotNull(intNode);
        Assertions.assertEquals(intNode, RouterHelper.projectInputPinToINTNode(sinks.get(1)));

        RWRoute.routeDesignFullNonTimingDriven(design);
        Assertions.assertEquals(0, DesignTools.updatePinsIsRouted(net));
        for (SitePinInst sink : sinks) {
            Assertions.assertTrue(sink.isRouted(), sink.toString());
        }
    }
}
