/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.NetWrapper;
import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.SlrModel;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.sta.FamilyArcProvider;
import com.xilinx.rapidwright.timing.sta.StaGraph;

/**
 * Checks that, with the fitted UltraScale+ model and fitted arcs, the router and the timing
 * analysis charge identical route delays: the route delays that the arc provider computes from a
 * routed design's PIPs equal those that RWRoute sets through
 * {@link TimingManager#setRouteDelay(Connection, float)} for the same routes (the rnode delays and
 * extra delays of {@link Connection#getRouteDelay(DelayEstimator)}, plus the fanout delays of
 * RWRoute's timing update, plus the pin delays the TimingManager adds).
 */
public class TestRouteDelayAgreement {

    /** The route delay that RWRoute charges a connection routed through these nodes (sink first) */
    private static float routerRouteDelay(DelayEstimator estimator, List<Node> rnodes, Map<Node, Set<Node>> children,
                                          Map<Node, Integer> loads) {
        float delay = RouterHelper.computeNodeDelay(estimator, rnodes.get(rnodes.size() - 1));
        for (int i = rnodes.size() - 2; i >= 0; i--) {
            Node node = rnodes.get(i);
            delay += RouterHelper.computeNodeDelay(estimator, node) + estimator.getExtraDelay(rnodes.get(i + 1), node);
        }
        // The fanout delays of RWRoute.updateRouteDelaysWithFanout(): of the branches and of the loads
        float fanout = 0;
        for (int i = 0; i < rnodes.size(); i++) {
            Set<Node> c = i == 0 ? null : children.get(rnodes.get(i));
            if (c != null && c.size() > 1) {
                fanout += estimator.getFanoutDelay(rnodes.get(i), c.size());
            }
            Integer n = loads.get(rnodes.get(i));
            if (n != null && n > 1) {
                fanout += estimator.getFanoutLoadDelay(rnodes.get(i), n);
            }
        }
        return delay + fanout;
    }

    /** The rnodes of a routed connection, as RWRoute holds them: from the sink's INT node to the source's */
    private static List<Node> rnodesOf(Connection c, Map<Node, Node> nodeToDriver) {
        Node sinkINT = RouterHelper.projectInputPinToINTNode(c.getSink());
        Node sourceINT = RouterHelper.projectOutputPinToINTNode(c.getSource());
        if (sinkINT == null || sourceINT == null) {
            return null;
        }
        List<Node> nodes = new ArrayList<>();
        Node n = sinkINT;
        while (n != null) {
            nodes.add(n);
            if (n.equals(sourceINT)) {
                return nodes;
            }
            n = nodeToDriver.get(n);
        }
        return null;
    }

    @Test
    public void testFittedUltraScalePlusRouteDelays() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235_2022_1.dcp");
        DelayEstimator estimator = DelayEstimator.create(d, false);
        TimingManager tm = new TimingManager(d, null, new RWRouteConfig(new String[0]), d.getNets(),
                false, estimator);
        StaGraph g = tm.getStaGraph();
        int compared = 0;
        int id = 0;
        for (Net net : d.getNets()) {
            if (net.isClockNet() || net.isStaticNet() || net.getSource() == null || !net.hasPIPs()) continue;
            Map<Node, Node> nodeToDriver = NetTools.getNodeToDriver(net);
            Map<Node, Set<Node>> children = new HashMap<>();
            for (Map.Entry<Node, Node> e : nodeToDriver.entrySet()) {
                children.computeIfAbsent(e.getValue(), k -> Collections.newSetFromMap(new IdentityHashMap<>()))
                        .add(e.getKey());
            }
            NetWrapper netWrapper = new NetWrapper(id, net);
            List<Connection> conns = new ArrayList<>();
            for (SitePinInst sink : net.getSinkPins()) {
                conns.add(new Connection(id++, net.getSource(), sink, netWrapper));
            }
            tm.setTimingEdgesOfConnections(conns);
            // The sink pins reached through each node
            Map<Node, Integer> loads = new HashMap<>();
            for (SitePinInst sink : net.getSinkPins()) {
                for (Node n = sink.getConnectedNode(); n != null; n = nodeToDriver.get(n)) {
                    loads.merge(n, 1, Integer::sum);
                }
            }
            for (Connection c : conns) {
                int conn = c.getStaConnId();
                // The connections whose site pin is that of their timing graph connection
                if (conn < 0 || g.getConnectionSink(conn) != c.getSink()) continue;
                List<Node> rnodes = rnodesOf(c, nodeToDriver);
                if (rnodes == null) continue;
                float sta = g.getConnectionRouteMax(conn);
                tm.setRouteDelay(c, routerRouteDelay(estimator, rnodes, children, loads));
                Assertions.assertEquals(sta, g.getConnectionRouteMax(conn), 1e-3f, c.toString());
                compared++;
            }
        }
        Assertions.assertTrue(compared > 400, "compared " + compared);
    }

    /** The number of super long lines on the route of a connection, walking from its sink */
    private static int countSlls(Map<Node, Node> nodeToDriver, SitePinInst sink) {
        int n = 0;
        for (Node node = sink.getConnectedNode(); node != null; node = nodeToDriver.get(node)) {
            if (SlrModel.isSll(node)) n++;
        }
        return n;
    }

    /**
     * The routed SLR crossings of a Versal design: each is charged at least the cheapest SLL of the
     * device (the router's admissible SLR crossing delay, see {@link SlrModel}).
     */
    @Test
    public void testVersalSlrCrossingRouteDelays() {
        Design d = RapidWrightDCP.loadDCP("versal_slr_crossing.dcp");
        FamilyArcProvider provider = new FamilyArcProvider(d, Corner.SLOW_MAX, Corner.FAST_MIN);
        DelayEstimator estimator = DelayEstimator.create(d, false);
        SlrModel slr = TimingFamilies.of(d.getDevice()).getSlrModel(d.getDevice(), estimator);
        Assertions.assertTrue(slr.getSllDelay() > 0, slr.toString());
        int crossings = 0;
        for (Net net : d.getNets()) {
            if (net.isClockNet() || net.isStaticNet() || net.getSource() == null || !net.hasPIPs()) continue;
            Map<Node, Node> nodeToDriver = NetTools.getNodeToDriver(net);
            for (SitePinInst sink : net.getSinkPins()) {
                int slls = countSlls(nodeToDriver, sink);
                if (slls == 0) continue;
                float[] delays = provider.getRoutedDelays(net, net.getSource(), sink);
                if (delays == null) continue;
                Assertions.assertTrue(delays[0] >= slls * slr.getSllDelay(), sink + " " + delays[0] + " " + slr);
                Assertions.assertTrue(delays[1] > 0 && delays[1] < delays[0], sink + " " + delays[1]);
                crossings++;
            }
        }
        Assertions.assertTrue(crossings > 10, "crossings " + crossings);
    }

    /**
     * A routed UltraScale+ SLR crosser module (registered crossings from Laguna TX to RX flip-flops), not in
     * RapidWrightDCP: the DCP path from the system property {@value #US_SLR_CROSSER_PROPERTY};
     * testUltraScalePlusRegisteredSlrCrossing is skipped without it.
     */
    public static final String US_SLR_CROSSER_PROPERTY = "rapidwright.timing.usSlrCrosserDcp";

    /**
     * The registered SLR crossings of UltraScale+ (Laguna TX flip-flop, super long line, RX
     * flip-flop) with the fitted model: within the band of Vivado's delays of such crossings at -2
     * (SLOW_MAX 650-690 ps, FAST_MIN 175-185 ps).
     */
    @Test
    public void testUltraScalePlusRegisteredSlrCrossing() {
        String dcp = System.getProperty(US_SLR_CROSSER_PROPERTY);
        Assumptions.assumeTrue(dcp != null && !dcp.isEmpty(), US_SLR_CROSSER_PROPERTY + " not set");
        Assertions.assertTrue(Files.exists(Paths.get(dcp)), dcp + " not found");
        Design d = Design.readCheckpoint(dcp);
        FamilyArcProvider provider = new FamilyArcProvider(d, Corner.SLOW_MAX, Corner.FAST_MIN);
        int crossings = 0;
        for (Net net : d.getNets()) {
            if (net.isClockNet() || net.isStaticNet() || net.getSource() == null || !net.hasPIPs()) continue;
            if (!net.getSource().getName().startsWith("TXQ")) continue;
            Map<Node, Node> nodeToDriver = NetTools.getNodeToDriver(net);
            for (SitePinInst sink : net.getSinkPins()) {
                Assertions.assertEquals(1, countSlls(nodeToDriver, sink));
                float[] delays = provider.getRoutedDelays(net, net.getSource(), sink);
                Assertions.assertNotNull(delays);
                Assertions.assertTrue(delays[0] > 620 && delays[0] < 720, sink + " " + delays[0]);
                Assertions.assertTrue(delays[1] > 160 && delays[1] < 200, sink + " " + delays[1]);
                crossings++;
            }
        }
        Assertions.assertTrue(crossings > 0);
    }
}
