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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
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

        int expectedNetsModified = 290;
        if (partial) {
            // Pseudo-randomly unroute at least one pin from each net
            Random random = new Random(0);
            Net Z_NET = design.createNet(Net.Z_NET);
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

    void testSingleConnectionHelper(String partName,
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

    @Test
    public void testTimingAndWirelengthReport() {
        String dcp = RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp");
        TimingAndWirelengthReport.main(new String[]{dcp});
    }

    @ParameterizedTest
    @CsvSource({
            // Dedicated connections, hence no nodes popped
            "xcvu3p,GTYE4_CHANNEL_X0Y12,TXOUTCLK_INT,BUFG_GT_SYNC_X0Y46,CLK_IN,0",
            "xcvu3p,GTYE4_CHANNEL_X0Y12,TXOUTCLK_INT,BUFG_GT_X0Y78,CLK_IN,0", // (dst pin can be projected to INT but not src pin)

            // Non-dedicated connections
            "xcvu3p,IOB_X0Y47,I,SLICE_X77Y122,FX,100",

            // 240 CLB height SLR, no LAG tiles on Y0 (since HBM on bottom edge)
            "xcu50,SLICE_X38Y239,AQ,SLICE_X38Y240,A1,100"
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
        Path dcp = RapidWrightDCP.getPath("optical-flow.dcp");
        Design design = Design.readCheckpoint(dcp);
        RWRoute.routeDesignFullNonTimingDriven(design);
        // VivadoToolsHelper.assertFullyRouted(design);
        
        // Pick a cell to blackbox
        String instToBlackBox = "bd_0_i/hls_inst/inst/Loop_FRAMES_CP_OUTER_U0";
        EDIFHierCellInst inst = design.getNetlist().getHierCellInstFromName(instToBlackBox);
        
        // Create example external library, store the guts of this netlist in external lib
        String cellType = "bd_0_hls_inst_0_Loop_FRAMES_CP_OUTER";
        EDIFNetlist external = EDIFTools.createNewNetlist(inst.getInst());
        Path externalEDIF = dir.resolve(cellType + ".edn");
        external.exportEDIF(externalEDIF);
        
        inst.getCellType().makePrimitive(); // makeBlackBox
        
        // Write out top EDIF that now has a black boxed cell
        Path opticalFlowTopEDIF = dir.resolve("optical-flow.edf");
        design.getNetlist().collapseMacroUnisims(design.getSeries());
        design.getNetlist().exportEDIF(opticalFlowTopEDIF);
        
        // Re-load DCP with black boxed netlist
        Design designWithBlackBox = Design.readCheckpoint(dcp, opticalFlowTopEDIF);

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
}
