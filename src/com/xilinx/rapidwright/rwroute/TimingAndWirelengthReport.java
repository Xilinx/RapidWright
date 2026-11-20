/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;

/**
 * An example to report the critical path delay and total wirelength of a routed design.
 * It is able to reproduce the same statistics as a {@link RWRoute} Object reports after routing a design.
 */
public class TimingAndWirelengthReport{
    private Design design;
    /** In half INT tiles (see {@link RouteNodeInfo#halfTileWirelength}) */
    private long wirelength;
    private long usedNodes;
    private int numWireNetsToRoute;
    private int numConnectionsToRoute;
    private TimingManager timingManager;
    private DelayEstimator estimator;
    private Map<IntentCode, Long> nodeTypeUsage ;
    private Map<IntentCode, Long> nodeTypeLength;

    public TimingAndWirelengthReport(Design design, RWRouteConfig config, boolean isPartialRouting) {
        this.design = design;
        estimator = DelayEstimator.create(design, config.isUseUTurnNodes());
        timingManager = new TimingManager(design, null, config, design.getNets(), isPartialRouting, estimator);
        wirelength = 0;
        usedNodes = 0;
        nodeTypeUsage = new HashMap<>();
        nodeTypeLength = new HashMap<>();
    }

    /**
     * Computes the wirelength and delay for each net and reports the total wirelength and critical path delay.
     */
    public void computeStatisticsAndReport() {
        computeNetsWirelengthAndDelay();

        timingManager.computeArrivalRequiredTimes();
        System.out.println();
        timingManager.getCriticalPathInfo();

        System.out.println();
        System.out.println("Total nodes: " + usedNodes);
        System.out.printf("Total wirelength: %.1f\n", wirelength / 2.0);
        RWRoute.printNodeTypeUsageAndWirelength(true, nodeTypeUsage, nodeTypeLength, design.getSeries());
    }

    /**
     * Computes the wirelength and delay for each net.
     */
    private void computeNetsWirelengthAndDelay() {
        for (Net net : design.getNets()) {
            if (net.getType() != NetType.WIRE) continue;
            if (!RouterHelper.isRoutableNetWithSourceSinks(net)) continue;
            if (net.getSource().toString().contains("CLK")) continue;
            NetWrapper netplus = createNetWrapper(net);
            for (Node node : RouterHelper.getNodesOfNet(net)) {
                if (RouteNodeGraph.isExcludedTile(node)) {
                    continue;
                }
                usedNodes++;
                int wl = RouteNodeInfo.get(node, null).halfTileWirelength;
                wirelength += wl;
                RouterHelper.addNodeTypeLengthToMap(node, wl, nodeTypeUsage, nodeTypeLength);
            }
            // (The timing analysis times the routed connections from their PIPs)
            timingManager.setTimingEdgesOfConnections(netplus.getConnections());
        }
    }

    /**
     * Creates a {@link NetWrapper} Object that consists of a list of {@link Connection} Objects, based on a net.
     * @param net
     * @return
     */
    private NetWrapper createNetWrapper(Net net) {
        NetWrapper netWrapper = new NetWrapper(numWireNetsToRoute++, net);
        for (SitePinInst sink:net.getSinkPins()) {
            SitePinInst source = net.getSource();
            if (RouterHelper.isExternalConnectionToCout(source, sink)) {
                SitePinInst altSource = net.getAlternateSource();
                if (altSource == null) {
                    altSource = DesignTools.getLegalAlternativeOutputPin(net);
                    if (altSource == null) {
                        String errMsg = "Null alternate source is for COUT-CIN connection: " + net.toStringFull();
                        throw new IllegalArgumentException(errMsg);
                    }
                    net.addPin(altSource);
                    DesignTools.routeAlternativeOutputSitePin(net, altSource);
                }
                source = altSource;
            }
            Connection connection = new Connection(numConnectionsToRoute++, source, sink, netWrapper);
            Node sinkINTNode = RouterHelper.projectInputPinToINTNode(sink);
            connection.setDirect(sinkINTNode == null);
        }
        return netWrapper;
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("USAGE: <input.dcp>");
            System.exit(1);
        }
        Design design = Design.readCheckpoint(args[0]);
        if (design.getNets().isEmpty()) {
            // A placed-only checkpoint can contain no physical nets at all; recover them (along with
            // their intra-site routing) from the logical netlist so that a timing graph can be built.
            design.routeSites();
        }
        //design manipulations are necessary, otherwise there will be problems in associating timing edges with connections.
        DesignTools.makePhysNetNamesConsistent(design);
        DesignTools.createMissingSitePinInsts(design);
        RWRouteConfig config = new RWRouteConfig(Arrays.copyOfRange(args, 1, args.length));
        config.setTimingDriven(true);
        final boolean isPartialRouting = false;
        TimingAndWirelengthReport reporter = new TimingAndWirelengthReport(design, config, isPartialRouting);
        reporter.computeStatisticsAndReport();
    }

}
