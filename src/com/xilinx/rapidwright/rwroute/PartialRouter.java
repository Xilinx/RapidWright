/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.ClkRouteTiming;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * A class extending {@link RWRoute} for partial routing.
 * In partial routing mode, nets that are already fully- or partially- routed
 * will be preserved and only the unrouted connections (as specified by the
 * pinsToRoute parameter in the constructor) are tackled.
 * Enabling soft preserve allows preserved routing that may be the cause of any
 * unroutable connections to be ripped up and re-routed.
 */
public class PartialRouter extends RWRoute {

    protected final boolean softPreserve;

    protected Set<NetWrapper> partiallyPreservedNets;

    protected Map<Net, List<SitePinInst>> netToPins;

    protected class RouteNodeGraphPartial extends RouteNodeGraph {

        public RouteNodeGraphPartial(RuntimeTracker setChildrenTimer, Design design) {
            super(setChildrenTimer, design);
        }

        @Override
        protected boolean mustInclude(Node parent, Node child) {
            return isPartOfExistingRoute(parent, child);
        }
    }

    protected class RouteNodeGraphPartialTimingDriven extends RouteNodeGraphTimingDriven {
        public RouteNodeGraphPartialTimingDriven(RuntimeTracker rnodesTimer, Design design, DelayEstimatorBase delayEstimator, boolean maskNodesCrossRCLK) {
            super(rnodesTimer, design, delayEstimator, maskNodesCrossRCLK);
        }

        @Override
        protected boolean mustInclude(Node parent, Node child) {
            return isPartOfExistingRoute(parent, child);
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

    @Override
    protected void preprocess() {
        // By default, preprocessing is expected to be performed manually and added to pinsToRoute
        // ahead of constructing this PartialRouter class.
        // Preprocessing can be invoked manually with RWRoute.preprocess(Design), as done by
        // routeDesignWithUserDefinedArguments() which needs to infer pinsToRoute.
    }

    @Override
    protected Collection<Net> getTimingNets() {
        return Collections.unmodifiableSet(netToPins.keySet());
    }

    /**
     * Checks whether this arc is part of an existing route.
     * For Nets containing at least one Connection to be routed, all fully routed
     * Connections and their associated Nodes (if any) are preserved. Any such
     * Nodes can (and are encouraged) to be used as part of routing such incomplete
     * Connections. In these cases, the RouteNode.prev member is used to restrict
     * incoming arcs to just the RouteNode already used by the Net; this method
     * detects this case and allows the preserved state to be masked.
     * @param start Start Node of arc.
     * @param end End Node of arc.
     * @return True if arc is part of an existing route.
     */
    private boolean isPartOfExistingRoute(Node start, Node end) {
        // End node can only be part of existing route if it is in the graph already
        RouteNode endRnode = routingGraph.getNode(end);
        if (endRnode == null)
            return false;

        // If end node has been visited already
        if (endRnode.isVisited(connectionsRouted)) {
            // Visited possibly from a different arc uphill of end, or possibly from
            // the same start -> end arc during prepareRouteConnection()
            return false;
        }

        // Presence of a prev pointer means that only that arc is allowed to enter this end node
        RouteNode prev = endRnode.getPrev();
        if (prev != null) {
            assert((prev.getNode() == start) == prev.getNode().equals(start));
            if (prev.getNode() == start && routingGraph.isPreserved(end)) {
                // Arc matches start node and end node is preserved
                // This implies that both start and end nodes must be preserved for the same net
                // (which assumedly is the net we're currently routing, and is asserted upstream)
                assert(routingGraph.getPreservedNet(start) == routingGraph.getPreservedNet(end));
                return true;
            }
        }

        // No presence means that it cannot be a preserved node belonging to the current net's routing
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
    protected NodeStatus getGlobalRoutingNodeStatus(Net net, Node node) {
        // In softPreserve mode, allow global router to use all nodes -- including
        // those already preserved by another net -- unless it is a must-have node
        // (e.g. sink node on a hierarchical net; Vivado will complain otherwise)

        Net preservedNet = routingGraph.getPreservedNet(node);
        if (preservedNet != null) {
            // Unavailable only if it isn't carrying the net undergoing routing
            return (preservedNet == net) ? NodeStatus.INUSE :
                    (softPreserve && routingGraph.getNode(node) == null) ? NodeStatus.AVAILABLE :
                    NodeStatus.UNAVAILABLE;
        }

        // A RouteNode will only be created if the node is necessary for
        // a to-be-routed connection
        return softPreserve || routingGraph.getNode(node) == null ? NodeStatus.AVAILABLE
                                                                  : NodeStatus.UNAVAILABLE;
    }

    @Override
    protected void routeGlobalClkNets() {
        if (clkNets.isEmpty())
            return;

        super.routeGlobalClkNets();

        if (softPreserve) {
            // Even though routeGlobalClkNets() has called preserveNet() for all clkNets,
            // it will not overwrite those nodes which have already been preserved by other
            // nets.
            // Discover such occurrences so that the entire 'other' net can be correctly
            // unpreserved (thus re-routed) and re-preserve clk.
            List<Net> unpreserveNets = new ArrayList<>();
            for (Net clk : clkNets) {
                for (PIP pip : clk.getPIPs()) {
                    for (Node node : Arrays.asList(pip.getStartNode(), pip.getEndNode())) {
                        Net preservedNet = routingGraph.getPreservedNet(node);
                        if (preservedNet != clk) {
                            if (preservedNet != null) {
                                unpreserveNet(preservedNet);
                                unpreserveNets.add(preservedNet);
                            }
                            // Redo preserving clk
                            Net oldNet = routingGraph.preserve(node, clk);
                            assert(oldNet == null);

                            // Clear preservedNode's prev pointer so that it doesn't get misinterpreted
                            // by RouteNodeGraph.mustInclude as being part of an existing route
                            RouteNode rnode = routingGraph.getNode(node);
                            assert(rnode.getPrev() != null);
                            rnode.clearPrev();
                        }
                    }
                }
            }

            if (!unpreserveNets.isEmpty()) {
                System.out.println("INFO: Unpreserving " + unpreserveNets.size() + " nets due to clock congestion");
                for (Net net : unpreserveNets) {
                    System.out.println("\t" + net);
                }
            }
        }
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

                // Do not include arcs that the router wouldn't explore
                // e.g. those that leave the INT tile, since we project pins to their INT tile
                if (routingGraph.isExcluded(start, end))
                    continue;

                RouteNode rstart = getOrCreateRouteNode(start, RouteNodeType.WIRE);
                RouteNode rend = getOrCreateRouteNode(end, RouteNodeType.WIRE);
                assert (rend.getPrev() == null);
                rend.setPrev(rstart);
            }

            // Use the prev pointers to update the routing for each connection
            for (Connection connection : netWrapper.getConnections()) {
                if (connection.getSink().isRouted()) {
                    finishRouteConnection(connection, connection.getSinkRnode());
                    assert(connection.getSink().isRouted());
                }
            }
        }

        routingGraph.resetExpansion();

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
            preserveNet(clk, true);
            numPreservedClks++;

            List<SitePinInst> clkPins = netToPins.get(clk);
            if (clkPins != null && !clkPins.isEmpty()) {
                clkNets.add(clk);
                numPreservedRoutableNets++;
            } else {
                numNotNeedingRoutingNets++;
            }
        }
    }

    @Override
    protected void addStaticNetRoutingTargets(Net staticNet) {
        if (staticNet.hasPIPs()) {
            preserveNet(staticNet, true);
            numPreservedStaticNets++;
        }

        List<SitePinInst> staticPins = netToPins.get(staticNet);
        if (staticPins == null || staticPins.isEmpty()) {
            if (staticNet.hasPIPs()) {
                numPreservedRoutableNets++;
            } else {
                numNotNeedingRoutingNets++;
            }
            return;
        }

        addStaticNetRoutingTargets(staticNet, staticPins);
    }

    @Override
    protected void addNetConnectionToRoutingTargets(Net net) {
        List<SitePinInst> sinkPins = net.getSinkPins();
        List<SitePinInst> pinsToRoute = netToPins.get(net);
        if (pinsToRoute != null) {
            assert(!pinsToRoute.isEmpty());

            boolean partiallyPreserved = (pinsToRoute.size() < sinkPins.size());
            if (partiallyPreserved) {
                // Mark all pins as being routed, then unmark those that need routing
                sinkPins.forEach((spi) -> spi.setRouted(true));
            }
            pinsToRoute.forEach((spi) -> spi.setRouted(false));

            NetWrapper netWrapper = createNetWrapperAndConnections(net);
            if (partiallyPreserved) {
                partiallyPreservedNets.add(netWrapper);
            }
        }

        if (net.hasPIPs()) {
            preserveNet(net, true);
            numPreservedWire++;
            numPreservedRoutableNets++;
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

                // Do not include arcs that the router wouldn't explore
                // e.g. those that leave the INT tile, since we project pins to their INT tile
                if (routingGraph.isExcluded(start, end))
                    continue;

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

                // Check the prev pointer is consistent with PIP
                assert(rend.getPrev() == rstart);
            }
        } else {
            // Net needs to be created
            netWrapper = createNetWrapperAndConnections(net);

            // Collect all nodes used by this net
            for (PIP pip : net.getPIPs()) {
                Node start = (pip.isReversed()) ? pip.getEndNode() : pip.getStartNode();
                Node end = (pip.isReversed()) ? pip.getStartNode() : pip.getEndNode();

                // Do not include arcs that the router wouldn't explore
                // e.g. those that leave the INT tile, since we project pins to their INT tile
                if (routingGraph.isExcluded(start, end))
                    continue;

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

            // Use the prev pointers to update the routing for each connection
            for (Connection netnewConnection : netWrapper.getConnections()) {
                if (netnewConnection.getSink().isRouted()) {
                    finishRouteConnection(netnewConnection, netnewConnection.getSinkRnode());
                    assert(netnewConnection.getSink().isRouted());
                }
            }

            // Update the timing graph
            if (timingManager != null) {
                timingManager.getTimingGraph().addNetDelayEdges(net);
                timingManager.setTimingEdgesOfConnections(netWrapper.getConnections());
                for (Connection netnewConnection : netWrapper.getConnections()) {
                    netnewConnection.updateRouteDelay();
                }
            }
        }

        routingGraph.resetExpansion();

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

                // Reset its list of children so that they may be regenerated to include the
                // newly unpreserved node
                parent.resetChildren();
            }
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

    private static Design routeDesign(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        if (config.isMaskNodesCrossRCLK()) {
            System.out.println("WARNING: Masking nodes across RCLK for partial routing could result in routability problems.");
        }

        return routeDesign(design, new PartialRouter(design, config, pinsToRoute, softPreserve));
    }

    /**
     * Partially routes a {@link Design} instance; specifically, all nets with no routing PIPs already present.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design, String[] args) {
        boolean softPreserve = false;

        // Instantiates a RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        return routeDesignWithUserDefinedArguments(design, args, softPreserve);
    }

    /**
     * Partially routes a {@link Design} instance; specifically, all nets with no routing PIPs already present.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @param softPreserve Allow routed nets to be unrouted and subsequently rerouted in order to improve routability.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design, String[] args, boolean softPreserve) {
        RWRoute.preprocess(design);

        List<SitePinInst> pinsToRoute = getUnroutedPins(design);

        // Instantiates a RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        return routeDesign(design, new RWRouteConfig(args), pinsToRoute, softPreserve);
    }

    /**
     * Return all SitePinInst objects belonging to fully unrouted nets (containing no routing PIPs).
     * @param design The {@link Design} instance to be examined.
     * @return A list of unrouted SitePinInst objects.
     */
    private static List<SitePinInst> getUnroutedPins(Design design) {
        List<SitePinInst> pinsToRoute = new ArrayList<>();
        for (Net net : design.getNets()) {
            if (net.getSource() == null && !net.isStaticNet()) {
                // Source-less nets may exist since this is an out-of-context design
                continue;
            }
            if (!net.hasPIPs()) {
                pinsToRoute.addAll(net.getSinkPins());
            }
        }
        return pinsToRoute;
    }

    /**
     * Routes a design in the partial non-timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed. If null, route all nets with no routing PIPs already present.
     * @param softPreserve Allow routed nets to be unrouted and subsequently rerouted in order to improve routability.
     */
    public static Design routeDesignPartialNonTimingDriven(Design design, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        if (pinsToRoute == null) {
            RWRoute.preprocess(design);
            pinsToRoute = getUnroutedPins(design);
        }

        return routeDesign(design, new RWRouteConfig(new String[] {
                "--fixBoundingBox",
                // use U-turn nodes and no masking of nodes cross RCLK
                // Pros: maximum routability
                // Con: might result in delay optimism and a slight increase in runtime
                "--useUTurnNodes",
                "--nonTimingDriven",
                "--verbose"}),
                pinsToRoute, softPreserve);
    }

    /**
     * Routes a design in the partial timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed. If null, route all nets with no routing PIPs already present.
     * @param softPreserve Allow routed nets to be unrouted and subsequently rerouted in order to improve routability.
     */
    public static Design routeDesignPartialTimingDriven(Design design, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        if (pinsToRoute == null) {
            RWRoute.preprocess(design);
            pinsToRoute = getUnroutedPins(design);
        }

        return routeDesign(design, new RWRouteConfig(new String[] {
                "--fixBoundingBox",
                // use U-turn nodes and no masking of nodes cross RCLK
                // Pros: maximum routability
                // Con: might result in delay optimism and a slight increase in runtime
                "--useUTurnNodes",
                "--verbose"}),
                pinsToRoute, softPreserve);
    }

    /**
     * The main interface of {@link PartialRouter} that reads in a {@link Design} checkpoint,
     * and parses the arguments for the {@link RWRouteConfig} object of the router.
     * Specifically, all nets with no routing PIPs already present will be partially routed.
     * @param args An array of strings that are used to create a {@link RWRouteConfig} object for the router.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.dcp> <output.dcp>");
            return;
        }
        // Reads the output directory and set the output design checkpoint file name
        String routedDCPfileName = args[1];

        CodePerfTracker t = new CodePerfTracker("PartialRouter", true);

        // Reads in a design checkpoint and routes it
        String[] rwrouteArgs = Arrays.copyOfRange(args, 2, args.length);
        Design routed = routeDesignWithUserDefinedArguments(Design.readCheckpoint(args[0]), rwrouteArgs);

        // Writes out the routed design checkpoint
        routed.writeCheckpoint(routedDCPfileName,t);
        System.out.println("\nINFO: Write routed design\n " + routedDCPfileName + "\n");
    }
}
