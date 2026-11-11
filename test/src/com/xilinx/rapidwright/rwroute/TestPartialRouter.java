/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2026, Advanced Micro Devices, Inc.
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

import static com.xilinx.rapidwright.rwroute.TestRWRoute.assertModifiedNetsRouted;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.CodeGenerator;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

/**
 * Tests of PartialRouter's partial routing: routing only some of a design's pins while preserving the routing that it
 * already has. Tests that merely use PartialRouter to route a single connection, as a harness for RWRoute's search,
 * are in TestRWRoute.
 */
public class TestPartialRouter {

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
        assertModifiedNetsRouted(design);
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
        assertModifiedNetsRouted(routed);
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
        assertModifiedNetsRouted(routed);
        VivadoToolsHelper.assertFullyRouted(design);
    }

    /**
     * Tests LUT pin swapping in partial routing when a connection whose existing route PartialRouter recovers loses
     * its only alternate sink to another net only after it was computed: its sink, already counted by the recovered
     * route, becomes exclusive but must still be counted only once, after promotion and after routing. (On Versal: a
     * lone 5LUT cell's input on A5, whose only alternate sink is A6.)
     */
    @Test
    public void testLutPinSwappingRecoveredSinkMadeExclusiveCountedOnce() {
        Design design = TestRWRoute.createLutLaneDesign("xcvc1902", "SLICE_X40Y61", 'A', null, "LUT1 O=I0 e@A5", false);
        RWRoute.routeDesignFullNonTimingDriven(design);
        Net net = design.getNet("e");
        TestRWRoute.assertAllPinsRouted(net);
        // A new sink, so that the net is partially routed: its existing route to A5 is recovered
        SitePinInst newSink = net.createPin("A_I", design.createSiteInst(design.getDevice().getSite("SLICE_X40Y63")));

        Net other = design.createNet("other");
        Node a6IMUX = design.getDevice().getSite("SLICE_X40Y61").getConnectedNode("A6").getAllUphillNodes().get(0);
        Connection[] a5Connection = new Connection[1];
        RWRouteConfig config = new RWRouteConfig(new String[] {"--nonTimingDriven", "--lutPinSwapping"});
        PartialRouter router = new PartialRouter(design, config, Collections.singletonList(newSink)) {
            @Override
            protected NetWrapper createNetWrapperAndConnections(Net net) {
                NetWrapper netWrapper = super.createNetWrapperAndConnections(net);
                for (Connection connection : netWrapper.getConnections()) {
                    if (connection.getSink().getName().equals("A5")) {
                        Assertions.assertEquals(1, connection.getAltSinkRnodes().size());
                        a5Connection[0] = connection;
                        routingGraph.preserve(a6IMUX, other);
                    }
                }
                return netWrapper;
            }

            @Override
            protected void determineRoutingTargets() {
                super.determineRoutingTargets();
                Connection connection = a5Connection[0];
                Assertions.assertTrue(connection.isRouted());
                Assertions.assertFalse(connection.hasAltSinks());
                RouteNode sinkRnode = connection.getSinkRnode();
                Assertions.assertTrue(sinkRnode.getType().isAnyExclusiveSink());
                Assertions.assertEquals(1, sinkRnode.countConnectionsOfUser(connection.getNetWrapper()));
            }
        };
        RWRoute.routeDesign(router);

        TestRWRoute.assertAllPinsRouted(net);
        Connection connection = a5Connection[0];
        Assertions.assertEquals(1, connection.getSinkRnode().countConnectionsOfUser(connection.getNetWrapper()));
        Assertions.assertEquals("A5", design.getCell("lut5").getPhysicalPinMapping("I0"));
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

    @TempDir
    static Path tempDir;

    /** bnn routed timing-driven, as a DCP (see getRoutedBnnDcp()) */
    private static Path routedBnnDcp;

    /**
     * Gets bnn routed timing-driven, written to a DCP on first use (and shared thereafter), so that each user reads
     * its own copy. The round trip also orders each net's PIPs: RWRoute leaves them in no particular order, and
     * NetTools.getNodeTrees() then flags nodes as multiply driven when a PIP leaving a node comes before the one
     * entering it, which PartialRouter asserts against.
     * @return The DCP's path.
     */
    private static synchronized Path getRoutedBnnDcp() {
        if (routedBnnDcp == null) {
            Design design = RapidWrightDCP.loadDCP("bnn.dcp");
            RWRoute.routeDesignFullTimingDriven(design);
            routedBnnDcp = tempDir.resolve("bnn_routed.dcp");
            design.writeCheckpoint(routedBnnDcp);
        }
        return routedBnnDcp;
    }

    private static Map<String, Set<PIP>> getPIPs(Design design) {
        Map<String, Set<PIP>> pips = new HashMap<>();
        for (Net net : design.getNets()) {
            pips.put(net.getName(), new HashSet<>(net.getPIPs()));
        }
        return pips;
    }

    /**
     * Timing-driven PartialRouter, given as pins to route the (already routed) sinks of every routed signal net, has
     * nothing to route: every connection is recovered from its net's routing, which must be kept as it is. Previously,
     * PartialRouter.isPartOfExistingRoute() excluded a net's own preserved arc from a node whenever both nodes had been
     * visited by the same search -- including before any search, when neither had (both 0) -- and
     * RouteNode.getChildren() caches that: so every source's own arcs were excluded by estimateDelayOfConnections()
     * (sources with no other free arc were swapped to their alternate output, and their connections, rerouted, became
     * unroutable), and a sink's arc from its own driver could also be excluded when computed in
     * prepareRouteConnection(); unroutable connections were abandoned, and setPIPsOfNets() then rewrote their nets
     * without their routing.
     * @param softPreserve Whether PartialRouter may rip up preserved routing.
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @LargeTest
    public void testRoutedPinsKeepRouting(boolean softPreserve) {
        Design design = Design.readCheckpoint(getRoutedBnnDcp());
        Map<String, Set<PIP>> before = getPIPs(design);

        List<SitePinInst> pinsToRoute = new ArrayList<>();
        for (Net net : design.getNets()) {
            if (NetTools.isGlobalClock(net) || net.getType() != NetType.WIRE ||
                    !RouterHelper.isRoutableNetWithSourceSinks(net) || !net.hasPIPs()) {
                continue;
            }
            pinsToRoute.addAll(net.getSinkPins());
        }
        Assertions.assertFalse(pinsToRoute.isEmpty());

        PartialRouter.routeDesignWithUserDefinedArguments(design, new String[] {}, pinsToRoute, softPreserve);

        Assertions.assertEquals(before, getPIPs(design));
    }

    /**
     * Tests that PartialDFXRouter can route a sink behind a locked path (of fixed PIPs) that begins with a horizontal
     * wire. That wire becomes the connection's sink, and, unlike a sink pin, ends in a different column from the one
     * that it begins in.
     */
    @Test
    public void testPartialDFXRouterLockedPathFromHorizontalWire() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_partial.dcp");

        // Find a routed sink pin whose route includes a horizontal quad or long wire, and the PIPs from that wire on
        Net net = null;
        SitePinInst sinkPin = null;
        List<PIP> lockedPath = new ArrayList<>();
        search:
        for (Net n : design.getNets()) {
            if (n.isStaticNet() || n.isClockNet() || !n.hasPIPs()) {
                continue;
            }
            Map<Node, PIP> nodeToDriverPIP = new HashMap<>();
            for (PIP pip : n.getPIPs()) {
                if (!pip.isReversed()) {
                    nodeToDriverPIP.put(pip.getEndNode(), pip);
                }
            }
            for (SitePinInst spi : n.getSinkPins()) {
                lockedPath.clear();
                Node node = spi.getConnectedNode();
                PIP pip;
                while ((pip = nodeToDriverPIP.get(node)) != null) {
                    lockedPath.add(pip);
                    node = pip.getStartNode();
                    IntentCode ic = node.getIntentCode();
                    if (ic == IntentCode.NODE_HQUAD || ic == IntentCode.NODE_HLONG) {
                        net = n;
                        sinkPin = spi;
                        break search;
                    }
                }
            }
        }
        Assertions.assertNotNull(net);

        // Keep only the locked path to that sink pin, as fixed PIPs
        net.unroute();
        for (PIP pip : lockedPath) {
            pip.setIsPIPFixed(true);
        }
        net.setPIPs(lockedPath);

        PartialDFXRouter.routeDesignWithUserDefinedArguments(design, new String[] {"--nonTimingDriven"},
                Collections.singletonList(sinkPin), /* softPreserve */ false);

        // The sink pin is reached from the source pin, through the locked path
        Assertions.assertTrue(net.getPIPs().containsAll(lockedPath));
        Map<Node, PIP> nodeToDriverPIP = new HashMap<>();
        for (PIP pip : net.getPIPs()) {
            nodeToDriverPIP.put(pip.getEndNode(), pip);
        }
        Node sourceNode = net.getSource().getConnectedNode();
        Node node = sinkPin.getConnectedNode();
        PIP pip;
        while (!node.equals(sourceNode) && (pip = nodeToDriverPIP.remove(node)) != null) {
            node = pip.getStartNode();
        }
        Assertions.assertEquals(sourceNode, node);
    }
}
