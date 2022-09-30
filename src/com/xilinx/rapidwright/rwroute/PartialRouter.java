/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.timing.ClkRouteTiming;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.RuntimeTracker;

/**
 * A class extending {@link RWRoute} for partial routing.
 * In partial routing mode, nets that are already fully- or partially- routed
 * will be preserved and only the unrouted connections (as specified by the
 * pinsToRoute parameter in the constructor) are tackled.
 * Enabling soft preserve allows preserved routing that may be the cause of any
 * unroutable connections to be ripped up and re-routed.
 */
public class PartialRouter extends RWRoute{

    final protected boolean softPreserve;

    protected Set<NetWrapper> partiallyPreservedNets;

    protected Map<Net, List<SitePinInst>> netToPins;

    protected class RouteNodeGraphPartial extends RouteNodeGraph {

        public RouteNodeGraphPartial(RuntimeTracker setChildrenTimer, Design design) {
            super(setChildrenTimer, design);
        }

        @Override
        protected boolean mustInclude(boolean forward, Node head, Node tail) {
            return isPartOfExistingRoute(forward, head, tail);
        }
    }

    protected class RouteNodeGraphPartialTimingDriven extends RouteNodeGraphTimingDriven {
        public RouteNodeGraphPartialTimingDriven(RuntimeTracker rnodesTimer, Design design, DelayEstimatorBase delayEstimator, boolean maskNodesCrossRCLK) {
            super(rnodesTimer, design, delayEstimator, maskNodesCrossRCLK);
        }

        @Override
        protected boolean mustInclude(boolean forward, Node head, Node tail) {
            return isPartOfExistingRoute(forward, head, tail);
        }
    }

    public PartialRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        super(design, config);
        this.softPreserve = softPreserve;
        partiallyPreservedNets = new HashSet<>();
        netToPins = pinsToRoute.stream()
                .filter((spi) -> !spi.isOutPin())
                .collect(Collectors.groupingBy(SitePinInst::getNet));
    }

    public PartialRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute) {
        this(design, config, pinsToRoute, false);
    }

    /**
     * Checks whether this arc is part of an existing route.
     * For Nets containing at least one Connection to be routed, all fully routed
     * Connections and their associated Nodes (if any) are preserved. Any such
     * Nodes can (and are encouraged) to be used as part of routing such incomplete
     * Connections. In these cases, the RouteNode.prev member is used to restrict
     * incoming arcs to just the RouteNode already used by the Net; this method
     * detects this case and allows the preserved state to be masked.
     * Note that this method must only be called once for each end Node, since
     * RouteNode.prev (which is also used to track its "visited" state) is erased
     * upon masking.
     * @param start Start Node of arc.
     * @param end End Node of arc.
     * @return True if arc is part of an existing route.
     */
    private boolean isPartOfExistingRoute(boolean forward, Node start, Node end) {
        if (!forward) {
            // Cannot determine if part of existing route when routing backwards
            return false;
        }

        if (!routingGraph.isPreserved(end))
            return false;

        // If preserved, check if end node has been created already
        RouteNode endRnode = routingGraph.getNode(end);
        if (endRnode == null)
            return false;

        // If so, get its prev pointer
        RouteNode prev = endRnode.getPrev();
        // Presence means that the only arc allowed to enter this end node
        // is if it came from prev
        if (prev != null) {
            assert((prev.getNode() == start) == prev.getNode().equals(start));
            return prev.getNode() == start;
        }

        return false;
    }

    @Override
    protected RouteNodeGraph createRouteNodeGraph() {
        if (config.isTimingDriven()) {
            /* An instantiated delay estimator that is used to calculate delay of routing resources */
            DelayEstimatorBase estimator = new DelayEstimatorBase(design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
            return new RouteNodeGraphPartialTimingDriven(rnodesTimer, design, estimator, config.isMaskNodesCrossRCLK());
        } else {
            return new RouteNodeGraphPartial(rnodesTimer, design);
        }
    }

    @Override
    protected TimingManager createTimingManager(ClkRouteTiming clkTiming, Collection<Net> timingNets) {
        final boolean isPartialRouting = true;
        return new TimingManager(design, routerTimer, config, clkTiming, timingNets, isPartialRouting);
    }

    @Override
    protected int getNumIndirectConnectionPins() {
        int totalSitePins = 0;
        for (Connection connection : indirectConnections) {
            totalSitePins += (connection.getSink().isRouted() && !connection.isCongested()) ? 0 : 1;
        }
        return totalSitePins;
    }

    @Override
    protected int getNumConnectionsCrossingSLRs() {
        int numCrossingSLRs = 0;
        for (Connection c : indirectConnections) {
            numCrossingSLRs += (!c.isCrossSLR() || (c.getSink().isRouted() && !c.isCongested())) ? 0 : 1;
        }
        return numCrossingSLRs;
    }

    @Override
    protected void routeStaticNets() {
        if (staticNetAndRoutingTargets.isEmpty())
            return;

        Net gnd = design.getGndNet();
        Net vcc = design.getVccNet();

        // Copy existing PIPs
        List<PIP> gndPips = (staticNetAndRoutingTargets.containsKey(gnd)) ? new ArrayList<>(gnd.getPIPs()) : Collections.emptyList();
        List<PIP> vccPips = (staticNetAndRoutingTargets.containsKey(vcc)) ? new ArrayList<>(vcc.getPIPs()) : Collections.emptyList();

        // Perform static net routing (which does no rip-up)
        super.routeStaticNets();

        // Since super.routeStaticNets() clobbers the PIPs list,
        // re-insert those existing PIPs
        gnd.getPIPs().addAll(gndPips);
        vcc.getPIPs().addAll(vccPips);
    }

    @Override
    protected void determineRoutingTargets() {
        super.determineRoutingTargets();

        // Go through all nets to be routed
        for (Map.Entry<Net, NetWrapper> e : nets.entrySet()) {
            Net net = e.getKey();
            NetWrapper netWrapper = e.getValue();

            // Create all nodes used by this net and set its previous pointer so that:
            // (a) the routing for each connection can be recovered by
            //      finishRouteConnection()
            // (b) RouteNode.setChildren() will know to only allow this incoming
            //     arc on these nodes
            for (PIP pip : net.getPIPs()) {
                Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
                Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();
                RouteNode rstart = getOrCreateRouteNode(start, RouteNodeType.WIRE);
                RouteNode rend = getOrCreateRouteNode(end, RouteNodeType.WIRE);
                assert (rend.getPrev() == null);
                rend.setPrev(rstart);
            }

            // Erase the prev pointer for all RouteNode-s upstream of projected
            // output INT node, otherwise finishRouteConnection() will complain that
            // backtracking doesn't terminate at Net's source
            for (SitePinInst spi : Arrays.asList(net.getSource(), net.getAlternateSource())) {
                if (spi == null) continue;
                Node n = RouterHelper.projectOutputPinToINTNode(spi);
                RouteNode rn = routingGraph.getNode(n);
                if (rn != null) {
                    rn.setPrev(null);
                }
            }

            // Use the prev pointers to update the routing for each connection
            for (Connection connection : netWrapper.getConnections()) {
                if (connection.getSink().isRouted()) {
                    finishRouteConnection(connection, connection.getSinkRnode());
                }
            }
        }

        // Mark each static sink node -- if it exists -- as being used, unpreserving any nets
        // using those nodes (likely bounce points) as needed
        for (Map.Entry<Net,List<SitePinInst>> e : staticNetAndRoutingTargets.entrySet()) {
            Net staticNet = e.getKey();
            List<SitePinInst> netRouteTargetPins = e.getValue();
            for (SitePinInst sink : netRouteTargetPins) {
                Node node = sink.getConnectedNode();
                Net preservedNet = routingGraph.getPreservedNet(node);
                if (preservedNet != null && !preservedNet.equals(staticNet)) {
                    unpreserveNet(preservedNet);
                }

                RouteNode rnode = routingGraph.getNode(node);
                if (rnode != null) {
                    rnode.incrementUser(null);
                }
            }
        }
    }

    @Override
    protected void addGlobalClkRoutingTargets(Net clk) {
        if (!clk.hasPIPs()) {
            super.addGlobalClkRoutingTargets(clk);
        } else {
            preserveNet(clk);
            increaseNumPreservedClks();
        }
    }

    @Override
    protected void addStaticNetRoutingTargets(Net staticNet) {
        preserveNet(staticNet);

        List<SitePinInst> sinks = staticNet.getSinkPins();
        if (sinks.size() > 0) {
            sinks.removeIf((spi) -> spi.isRouted());
            if (sinks.isEmpty()) {
                increaseNumPreservedStaticNets();
            } else {
                addStaticNetRoutingTargets(staticNet, sinks);
            }

        } else {// internally routed (sinks.size = 0)
            increaseNumNotNeedingRouting();
        }
    }

    @Override
    protected void addNetConnectionToRoutingTargets(Net net) {
        List<SitePinInst> sinkPins = net.getSinkPins();
        List<SitePinInst> pinsToRoute = netToPins.get(net);
        final boolean partiallyPreserved = (pinsToRoute != null && pinsToRoute.size() < sinkPins.size());
        if (pinsToRoute != null) {
            assert(!pinsToRoute.isEmpty());

            if (partiallyPreserved) {
                // Mark all pins as being routed, then unmark those that need routing
                sinkPins.forEach((spi) -> spi.setRouted(true));
            }
            pinsToRoute.forEach((spi) -> spi.setRouted(false));
        }

        if (net.hasPIPs()) {
            // NOTE: SitePinInst.isRouted() must be finalized before this method is
            //       called as it may operate asynchronously
            preserveNet(net);
            increaseNumPreservedWireNets();
        }

        if (pinsToRoute == null) {
            return;
        }

        NetWrapper netWrapper = createNetWrapperAndConnections(net);
        if (partiallyPreserved) {
            partiallyPreservedNets.add(netWrapper);
        }
    }

    /**
     * Return preserved nets that are using resources immediately downhill of the source and
     * immediately uphill of the sink of the connection.
     * @param connection The connection in question.
     * @return Collection of nets.
     */
    protected Collection<Net> pickNetsToUnpreserve(Connection connection) {
        Set<Net> unpreserveNets = new HashSet<>();

        Node sourceNode = connection.getSourceRnode().getNode();
        Node sinkNode = connection.getSinkRnode().getNode();

        List<Node> candidateNodes = new ArrayList<>();
        // Consider the cases of [A-H](X|_I) site pins which are accessed through a bounce node,
        // meaning this connection may be unroutable because another net is preserving this node
        candidateNodes.add(sinkNode);
        // Find those reserved signals that are using uphill nodes of the target pin node
        candidateNodes.addAll(sinkNode.getAllUphillNodes());
        // Find those preserved nets that are using downhill nodes of the source pin node
        candidateNodes.addAll(sourceNode.getAllDownhillNodes());

        for(Node node : candidateNodes) {
            Net toRoute = routingGraph.getPreservedNet(node);
            if(toRoute == null) continue;
            if(toRoute.isClockNet() || toRoute.isStaticNet()) continue;
            unpreserveNets.add(toRoute);
        }

        unpreserveNets.removeIf((net) -> {
            NetWrapper netWrapper = nets.get(net);
            if (netWrapper == null)
                return false;
            if (partiallyPreservedNets.contains(netWrapper))
                return false;
            // Net already seen and is fully unpreserved
            return true;
        });

        return unpreserveNets;
    }

    /**
     * Unpreserves nets to release routing resource to resolve congestion that blocks the
     * routablity of a connection.
     * The {@link #pickNetsToUnpreserve} method is called to get which nets are to be
     * unpreserved and its resources released for consideration by others.
     * @param connection The connection in question.
     * @return The number of unrouted nets.
     */
    protected int unpreserveNetsAndReleaseResources(Connection connection) {
        Collection<Net> unpreserveNets = pickNetsToUnpreserve(connection);
        if (unpreserveNets.isEmpty()) {
            return 0;
        }

        System.out.println("INFO: Unpreserving " + unpreserveNets.size() + " nets due to unroutable connection");
        for (Net net : unpreserveNets) {
            System.out.println("\t" + net);
            unpreserveNet(net);
        }

        return unpreserveNets.size();
    }

    protected void unpreserveNet(Net net) {
        Set<RouteNode> rnodes = new HashSet<>();
        NetWrapper netWrapper = nets.get(net);
        if (netWrapper != null) {
            // Net already exists -- any unrouted connection will cause the
            // net to exist, but already routed connections will already have
            // been preserved

            boolean removed = partiallyPreservedNets.remove(netWrapper);
            assert(removed);

            // Collect all nodes used by this net
            for (PIP pip : net.getPIPs()) {
                Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
                Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();

                // Since net already exists, all the nodes it uses must already
                // have been created
                RouteNode rstart = routingGraph.getNode(start);
                assert (rstart != null);
                boolean rstartAdded = rnodes.add(rstart);
                boolean startPreserved = routingGraph.unpreserve(start);
                assert(rstartAdded == startPreserved);

                RouteNode rend = routingGraph.getNode(end);
                assert (rend != null);
                boolean rendAdded = rnodes.add(rend);
                boolean endPreserved = routingGraph.unpreserve(end);
                assert(rendAdded == endPreserved);

                // Check the prev pointer consistent with PIP
                // (it may be null because isPartOfExistingRoute() will erase prev once rend was created)
                assert(rend.getPrev() == null || rend.getPrev().equals(rstart));
            }
        } else {
            // Net needs to be created
            netWrapper = createNetWrapperAndConnections(net);

            // Collect all nodes used by this net
            for (PIP pip : net.getPIPs()) {
                Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
                Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();
                boolean startPreserved = routingGraph.unpreserve(start);
                boolean endPreserved = routingGraph.unpreserve(end);

                RouteNode rstart = getOrCreateRouteNode(start, RouteNodeType.WIRE);
                RouteNode rend = getOrCreateRouteNode(end, RouteNodeType.WIRE);
                boolean rstartAdded = rnodes.add(rstart);
                boolean rendAdded = rnodes.add(rend);
                assert(rstartAdded == startPreserved);
                assert(rendAdded == endPreserved);

                // Also set the prev pointer according to the PIP
                assert (rend.getPrev() == null);
                rend.setPrev(rstart);
            }

            // Erase the prev pointer for all RouteNode-s upstream of projected
            // INT node, otherwise finishRouteConnection() will complain that
            // backtracking doesn't terminate at Net's source
            for (SitePinInst spi : Arrays.asList(net.getSource(), net.getAlternateSource())) {
                if (spi == null) continue;
                Node n = RouterHelper.projectOutputPinToINTNode(spi);
                RouteNode rn = routingGraph.getNode(n);
                if (rn != null) {
                    rn.setPrev(null);
                }
            }

            // Use the prev pointers to update the routing for each connection
            for (Connection netnewConnection : netWrapper.getConnections()) {
                if (netnewConnection.getSink().isRouted()) {
                    finishRouteConnection(netnewConnection, netnewConnection.getSinkRnode());
                }
            }

            // Update the timing graph
            if (config.isTimingDriven()) {
                timingManager.getTimingGraph().addNetDelayEdges(net);
                timingManager.setTimingEdgesOfConnections(netWrapper.getConnections());
                for (Connection netnewConnection : netWrapper.getConnections()) {
                    netnewConnection.updateRouteDelay();
                }
            }
        }

        for (RouteNode rnode : rnodes) {
            Node toBuild = rnode.getNode();
            // Check already unpreserved above
            assert(!routingGraph.isPreserved(toBuild));

            // Each rnode should be added as a child to all of its parents
            // that already exist
            for (Node uphill : toBuild.getAllUphillNodes()) {
                RouteNode parent = routingGraph.getNode(uphill);
                if (parent == null)
                    continue;

                // Reset its list of children so that they may be regenerated to include newly unpreserved node
                parent.resetChildren();
            }

            // Clear the prev pointer (as it is also used to track
            // whether a node has been visited during expansion)
            rnode.setPrev(null);
        }

        numPreservedWire--;
        numPreservedRoutableNets--;
    }

    @Override
    protected boolean handleUnroutableConnection(Connection connection) {
        boolean hasAltOutput = super.handleUnroutableConnection(connection);
        if (hasAltOutput)
            return true;
        if (softPreserve) {
            if (routeIteration == 2) {
                unpreserveNetsAndReleaseResources(connection);
                return true;
            }
        }
        return false;
    }

    private static Design routeDesign(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute) {
        if (config.isMaskNodesCrossRCLK()) {
            System.out.println("WARNING: Masking nodes across RCLK for partial routing could result in routability problems.");
        }

        return routeDesign(design, new PartialRouter(design, config, pinsToRoute));
    }

    /**
     * Routes a design in the partial non-timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed.
     */
    public static Design routeDesignPartialNonTimingDriven(Design design, Collection<SitePinInst> pinsToRoute) {
        return routeDesign(design, new RWRouteConfig(new String[] {
                "--fixBoundingBox",
                // use U-turn nodes and no masking of nodes cross RCLK
                // Pros: maximum routability
                // Con: might result in delay optimism and a slight increase in runtime
                "--useUTurnNodes",
                "--nonTimingDriven",
                "--verbose"}),
                pinsToRoute);
    }

    /**
     * Routes a design in the partial timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed.
     */
    public static Design routeDesignPartialTimingDriven(Design design, Collection<SitePinInst> pinsToRoute) {
        return routeDesign(design, new RWRouteConfig(new String[] {
                "--fixBoundingBox",
                // use U-turn nodes and no masking of nodes cross RCLK
                // Pros: maximum routability
                // Con: might result in delay optimism and a slight increase in runtime
                "--useUTurnNodes",
                "--verbose"}),
                pinsToRoute);
    }
}
