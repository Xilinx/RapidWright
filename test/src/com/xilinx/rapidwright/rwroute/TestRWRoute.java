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

import com.xilinx.rapidwright.util.VivadoToolsHelper;
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
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;

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
        Assertions.assertTrue(Long.parseLong(System.getProperty("rapidwright.rwroute.nodesPopped")) <= nodesPoppedLimit);
    }

    @ParameterizedTest
    @CsvSource({
            // One SLR crossing
            // (Too) Close
            "SLICE_X9Y299,SLICE_X9Y300,500",    // On Laguna column
            "SLICE_X9Y300,SLICE_X9Y299,400",
            "SLICE_X0Y299,SLICE_X0Y300,200",    // Far from Laguna column
            "SLICE_X0Y300,SLICE_X0Y299,200",
            "SLICE_X53Y299,SLICE_X53Y300,200",  // Equidistant from two Laguna columns
            "SLICE_X53Y300,SLICE_X53Y299,700",
            // Perfect
            "SLICE_X9Y241,SLICE_X9Y300,200",
            "SLICE_X9Y300,SLICE_X9Y241,100",
            "SLICE_X9Y358,SLICE_X9Y299,100",
            "SLICE_X9Y299,SLICE_X9Y358,200",
            "SLICE_X53Y241,SLICE_X69Y300,500",
            "SLICE_X53Y358,SLICE_X69Y299,500",
            // Far
            "SLICE_X9Y240,SLICE_X9Y359,100",    // On Laguna
            "SLICE_X9Y359,SLICE_X9Y240,200",
            "SLICE_X162Y240,SLICE_X162Y430,200",
            "SLICE_X162Y430,SLICE_X162Y240,300",
            "SLICE_X0Y240,SLICE_X12Y430,400",   // Far from Laguna
            "SLICE_X0Y430,SLICE_X12Y240,200",

            // Two SLR crossings
            "SLICE_X162Y299,SLICE_X162Y599,200",
            "SLICE_X162Y599,SLICE_X162Y299,100",

            // Three SLR crossings
            "SLICE_X79Y0,SLICE_X79Y899,200",    // Straight up: next to Laguna column
            "SLICE_X0Y0,SLICE_X0Y899,600",      // Straight up: far from Laguna column
            "SLICE_X168Y0,SLICE_X168Y899,400",  // Straight up: far from Laguna column
            "SLICE_X9Y0,SLICE_X162Y899,1000",   // Up and right
            "SLICE_X168Y162,SLICE_X9Y899,600",  // Up and left
    })
    public void testSLRCrossingNonTimingDriven(String srcSiteName, String dstSiteName, long nodesPoppedLimit) {
        testSingleConnectionHelper(Device.AWS_F1, srcSiteName, "AQ", dstSiteName, "A1", nodesPoppedLimit);
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
            "xcvu3p,IOB_X0Y47,I,SLICE_X77Y122,FX,600",

            // 240 CLB height SLR, no LAG tiles on Y0 (since HBM on bottom edge)
            "xcu50,SLICE_X38Y239,AQ,SLICE_X38Y240,A1,500"
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
}
