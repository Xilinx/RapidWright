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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.Pair;

/**
 * An example to report the critical path delay and total wirelength of a routed design.
 * It is able to reproduce the same statistics as a {@link RWRoute} Object reports after routing a design.
 */
public class TimingAndWirelengthReport{
    private Design design;
    private long wirelength;
    private long usedNodes;
    private int numWireNetsToRoute;
    private int numConnectionsToRoute;
    private TimingManager timingManager;
    private DelayEstimatorBase estimator;
    private Map<IntentCode, Long> nodeTypeUsage ;
    private Map<IntentCode, Long> nodeTypeLength;
    private RouteNodeGraph routingGraph;

    public TimingAndWirelengthReport(Design design, RWRouteConfig config, boolean isPartialRouting) {
        this.design = design;
        timingManager = new TimingManager(design, null, config, RWRoute.createClkTimingData(config), design.getNets(), isPartialRouting);
        estimator = new DelayEstimatorBase(design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
        routingGraph = new RouteNodeGraphTimingDriven(design, config, estimator);
        wirelength = 0;
        usedNodes = 0;
        nodeTypeUsage = new HashMap<>();
        nodeTypeLength = new HashMap<>();
    }

    /**
     * Computes the wirelength and delay for each net and reports the total wirelength and critical path delay.
     */
    private void computeStatisticsAndReport() {
        computeNetsWirelengthAndDelay();

        Pair<Float, TimingVertex> maxDelayAndTimingVertex = timingManager.calculateArrivalRequiredTimes();
        System.out.println();
        timingManager.getCriticalPathInfo(maxDelayAndTimingVertex, false, routingGraph);

        System.out.println("\n");
        System.out.println("Total nodes: " + usedNodes);
        System.out.println("Total wirelength: " + wirelength);
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
                int wl = RouteNode.getLength(node);
                wirelength += wl;
                RouterHelper.addNodeTypeLengthToMap(node, wl, nodeTypeUsage, nodeTypeLength);
            }
            timingManager.setTimingEdgesOfConnections(netplus.getConnections());
            setAccumulativeDelayOfEachNetNode(netplus);
        }
    }

    /**
     * Creates a {@link NetWrapper} Object that consists of a list of {@link Connection} Objects, based on a net.
     * @param net
     * @return
     */
    private NetWrapper createNetWrapper(Net net) {
        NetWrapper netWrapper = new NetWrapper(numWireNetsToRoute++, net);
        SitePinInst source = net.getSource();
        Node sourceINTNode = null;
        for (SitePinInst sink:net.getSinkPins()) {
            if (RouterHelper.isExternalConnectionToCout(source, sink)) {
                source = net.getAlternateSource();
                if (source == null) {
                    String errMsg = "Null alternate source is for COUT-CIN connection: " + net.toStringFull();
                     throw new IllegalArgumentException(errMsg);
                }
            }
            Connection connection = new Connection(numConnectionsToRoute++, source, sink, netWrapper);
            Node sinkINTNode = RouterHelper.projectInputPinToINTNode(sink);
            if (sinkINTNode == null) {
                connection.setDirect(true);
            } else {
                connection.setSinkRnode(routingGraph.getOrCreate(sinkINTNode, RouteNodeType.PINFEED_I));
                if (sourceINTNode == null) {
                    sourceINTNode = RouterHelper.projectOutputPinToINTNode(source);
                }
                connection.setSourceRnode(routingGraph.getOrCreate(sourceINTNode, RouteNodeType.PINFEED_O));
                connection.setDirect(false);
            }
        }
        return netWrapper;
    }

    /**
     * Using PIPs to calculate and set accumulative delay for each used node of a routed net that is represented by a {@link NetWrapper} Object.
     * The delay of each node is the total route delay from the source to the node (inclusive).
     * @param netWrapper
     */
    private void setAccumulativeDelayOfEachNetNode(NetWrapper netWrapper) {
        Map<SitePinInst, Pair<Node,Short>> sourceToSinkINTNodeDelays =
                RouterHelper.getSourceToSinkINTNodeDelays(netWrapper.getNet(), estimator);

        for (Connection connection : netWrapper.getConnections()) {
            if (connection.isDirect()) {
                continue;
            }
            Pair<Node,Short> sinkINTNodeDelay = sourceToSinkINTNodeDelays.get(connection.getSink());
            short connectionDelay = sinkINTNodeDelay.getSecond();
            if (connection.getTimingEdges() == null) {
                continue;
            }
            connection.setTimingEdgesDelay(connectionDelay);
        }
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("USAGE:\n <input.dcp>");
        }
        Design design = Design.readCheckpoint(args[0]);
        //design manipulations are necessary, otherwise there will be problems in associating timing edges with connections.
        DesignTools.makePhysNetNamesConsistent(design);
        DesignTools.createMissingSitePinInsts(design);
        RWRouteConfig config = new RWRouteConfig(new String[0]);
        config.setTimingDriven(true);
        final boolean isPartialRouting = false;
        TimingAndWirelengthReport reporter = new TimingAndWirelengthReport(design, config, isPartialRouting);
        reporter.computeStatisticsAndReport();
    }

}
