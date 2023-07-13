/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestRWRoute {
    private static void assertAllSinksRouted(List<SitePinInst> pins) {
        for (SitePinInst spi : pins) {
            if (spi.isOutPin())
                continue;
            Assertions.assertTrue(spi.isRouted());
        }
    }

    public static void assertVivadoFullyRouted(Design design) {
        if (!FileTools.isVivadoOnPath()) {
            return;
        }

        ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
        Assertions.assertTrue(rrs.isFullyRouted());
    }

    private static void assertAllSinksRouted(Design design) {
        for (Net net : design.getNets()) {
            if (net.getSource() == null && !net.isStaticNet()) {
                // Source-less nets may exist in out-of-context design
                continue;
            }
            assertAllSinksRouted(net.getPins());
        }
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
        String dcpPath = RapidWrightDCP.getString("bnn.dcp");
        Design design = Design.readCheckpoint(dcpPath);
        RWRoute.routeDesignFullNonTimingDriven(design);
        assertAllSinksRouted(design);
        assertVivadoFullyRouted(design);
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
        String dcpPath = RapidWrightDCP.getString("bnn.dcp");
        Design design = Design.readCheckpoint(dcpPath);
        RWRoute.routeDesignFullTimingDriven(design);
        assertAllSinksRouted(design);
        assertVivadoFullyRouted(design);
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
        // Sporadically failing due to OutOfMemoryException (see #439)
        long maxMemoryNeeded = 1024L*1024L*1024L*8L;
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= maxMemoryNeeded);
        String dcpPath = RapidWrightDCP.getString("optical-flow.dcp");
        Design design = Design.readCheckpoint(dcpPath);
        RWRoute.routeDesignFullNonTimingDriven(design);
        assertAllSinksRouted(design);
        assertVivadoFullyRouted(design);
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
        // Sporadically failing due to OutOfMemoryException (see #439)
        long maxMemoryNeeded = 1024L*1024L*1024L*8L;
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= maxMemoryNeeded);

        Design design = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");
        design.setTrackNetChanges(true);

        boolean softPreserve = false;
        Design routed = PartialRouter.routeDesignPartialNonTimingDriven(design, null, softPreserve);

        Assertions.assertFalse(routed.getModifiedNets().isEmpty());
        for (Net net : routed.getModifiedNets()) {
            assertAllSinksRouted(net.getPins());
        }
        assertVivadoFullyRouted(design);
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
        Design routed = PartialRouter.routeDesignPartialTimingDriven(design, null, false);

        Assertions.assertFalse(routed.getModifiedNets().isEmpty());
        for (Net net : routed.getModifiedNets()) {
            assertAllSinksRouted(net.getPins());
        }
        assertVivadoFullyRouted(design);
    }

    @ParameterizedTest
    @CsvSource({
            // One SLR crossing
            // (Too) Close
            "SLICE_X9Y299,SLICE_X9Y300,500",    // On Laguna column
            "SLICE_X9Y300,SLICE_X9Y299,400",
            "SLICE_X0Y299,SLICE_X0Y300,200",    // Far from Laguna column
            "SLICE_X0Y300,SLICE_X0Y299,200",
            "SLICE_X53Y299,SLICE_X53Y300,300",  // Equidistant from two Laguna columns
            "SLICE_X53Y300,SLICE_X53Y299,600",
            // Perfect
            "SLICE_X9Y241,SLICE_X9Y300,200",
            "SLICE_X9Y300,SLICE_X9Y241,200",
            "SLICE_X9Y358,SLICE_X9Y299,100",
            "SLICE_X9Y299,SLICE_X9Y358,200",
            "SLICE_X53Y241,SLICE_X69Y300,500",
            "SLICE_X53Y358,SLICE_X69Y299,1000",
            // Far
            "SLICE_X9Y240,SLICE_X9Y359,100",    // On Laguna
            "SLICE_X9Y359,SLICE_X9Y240,100",
            "SLICE_X162Y240,SLICE_X162Y430,300",
            "SLICE_X162Y430,SLICE_X162Y240,100",
            "SLICE_X0Y240,SLICE_X12Y430,1400",  // Far from Laguna
            "SLICE_X0Y430,SLICE_X12Y240,300",

            // Two SLR crossings
            "SLICE_X162Y299,SLICE_X162Y599,300",
            "SLICE_X162Y599,SLICE_X162Y299,100",

            // Three SLR crossings
            "SLICE_X79Y0,SLICE_X79Y899,300",    // Straight up: next to Laguna column
            "SLICE_X0Y0,SLICE_X0Y899,300",      // Straight up: far from Laguna column
            "SLICE_X168Y0,SLICE_X168Y899,300",  // Straight up: far from Laguna column
            "SLICE_X9Y0,SLICE_X162Y899,500",    // Up and right
            "SLICE_X168Y162,SLICE_X9Y899,1900", // Up and left
    })
    public void testSLRCrossingNonTimingDriven(String srcSiteName, String dstSiteName, long nodesPoppedLimit) {
        Design design = new Design("top", Device.AWS_F1);

        Net net = design.createNet("net");
        SiteInst srcSi = design.createSiteInst(srcSiteName);
        net.createPin("AQ", srcSi);

        SiteInst dstSi = design.createSiteInst(dstSiteName);
        SitePinInst dstSpi = net.createPin("A1", dstSi);

        List<SitePinInst> pinsToRoute = new ArrayList<>();
        pinsToRoute.add(dstSpi);
        boolean softPreserve = false;
        PartialRouter.routeDesignPartialNonTimingDriven(design, pinsToRoute, softPreserve);

        Assertions.assertTrue(pinsToRoute.stream().allMatch(SitePinInst::isRouted));
        Assertions.assertTrue(Long.parseLong(System.getProperty("rapidwright.rwroute.nodesPopped")) <= nodesPoppedLimit);
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

        RWRoute.routeDesignFullNonTimingDriven(design);

        Net vcc = design.getVccNet();
        Assertions.assertEquals(1, vcc.getPins().size());
        Assertions.assertTrue(vcc.getPins().stream().allMatch(SitePinInst::isRouted));

        Net gnd = design.getGndNet();
        Assertions.assertEquals(31, gnd.getPins().size());
        Assertions.assertTrue(gnd.getPins().stream().allMatch(SitePinInst::isRouted));

        if (FileTools.isVivadoOnPath()) {
            // Testcase has a number of undriven nets, so just check for unrouted nets
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(0, rrs.unroutedNets);
        }
    }

}
