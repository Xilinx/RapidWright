/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.design.PartitionPin;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.tests.CodePerfTracker;

import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A class extending {@link PartialRouter} for partial routing of DFX designs.
 * Similar to PartialRouter, in partial routing mode, nets that are already fully- or partially- routed
 * will be preserved and only the unrouted connections (as specified by the
 * pinsToRoute parameter in the constructor) are tackled.
 * Enabling soft preserve allows preserved routing that may be the cause of any
 * unroutable connections to be ripped up and re-routed.
 * Nets that route from the static region into the dynamic region must be locked and have a {@link PartitionPin}
 * attached to indicate the branch point to be used to connect unrouted sinks.
 */
public class PartialDFXRouter extends PartialRouter {
    protected final Map<Connection,RouteNode> connectionToTrueSinkRnodeBehindLockedPath = new IdentityHashMap<>();

    protected final Map<NetWrapper,RouteNode> netToTrueSourceRnodeBehindLockedPath = new IdentityHashMap<>();

    public PartialDFXRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        super(design, config, pinsToRoute, softPreserve);

        if (config.isTimingDriven()) {
            throw new RuntimeException("ERROR: Timing-driven partial DFX routing not currently supported.");
        }
    }

    public PartialDFXRouter(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute) {
        this(design, config, pinsToRoute, /* softPreserve */ false);
    }

    @Override
    protected void determineRoutingTargets() {
        super.determineRoutingTargets();

        for (Map.Entry<Net,NetWrapper> e : nets.entrySet()) {
            Net net = e.getKey();
            if (!net.hasPIPs()) {
                continue;
            }

            NetWrapper netWrapper = e.getValue();
            for (Connection connection : netWrapper.getConnections()) {
                if (connection.isDirect() || connection.isRouted()) {
                    continue;
                }

                RouteNode sinkRnode = connection.getSinkRnode();
                if (!sinkRnode.isArcLocked()) {
                    continue;
                }

                // For connections where routing could not be fully recovered, but which have a locked arc
                // to its sink, move the connection target back along all locked arcs
                RouteNode beginOfLockedPath = sinkRnode;
                while ((beginOfLockedPath = beginOfLockedPath.getPrev()) != null && beginOfLockedPath.isArcLocked()) {}
                assert(beginOfLockedPath != sinkRnode);
                RouteNode oldValue = connectionToTrueSinkRnodeBehindLockedPath.put(connection, sinkRnode);
                assert(oldValue == null);

                assert(!connection.hasAltSinks());
                assert(sinkRnode.countConnectionsOfUser(netWrapper) > 0);
                assert(!sinkRnode.isOverUsed());

                // Replace connection's sink node with the first node on the locked path to the sink
                connection.setSinkRnode(beginOfLockedPath);
                beginOfLockedPath.incrementUser(netWrapper);

                switch (beginOfLockedPath.getType()) {
                    case NON_LOCAL:
                        beginOfLockedPath.setType(RouteNodeType.EXCLUSIVE_SINK_NON_LOCAL);
                        break;
                    case EXCLUSIVE_SINK_NON_LOCAL:
                        break;
                    default:
                        throw new RuntimeException("TODO: Failed to make " + beginOfLockedPath.getNode() + " into a routing sink");
                }

                Net preservedNet;
                assert((preservedNet = routingGraph.getPreservedNet(connection.getSourceRnode())) == null || preservedNet == net);
                preservedNet = routingGraph.getPreservedNet(sinkRnode);
                if (preservedNet == net) {
                    // Sink is exclusively preserved for this unrouted connection => must be an inadvertently-preserved
                    // rnode of a locked path; unpreserve it so that it is not excluded when building out the routing graph
                    routingGraph.unpreserve(beginOfLockedPath);
                }
            }
        }

        // Examine all partition pins and discover those that are the end rnode of locked paths
        // back to the source
        for (PartitionPin pp : design.getPartitionPins()) {
            Node node = pp.getNode();
            if (node.isInvalidNode()) {
                continue;
            }
            RouteNode rnode = routingGraph.getNode(node);
            if (rnode == null) {
                continue;
            }
            if (rnode.getType().isAnyExclusiveSink()) {
                // Ignore partition pins that lead to a sink
                continue;
            }

            Net net = design.getNetFromPartitionPin(pp);
            NetWrapper netWrapper = nets.get(net);
            if (netWrapper == null) {
                continue;
            }
            assert(netWrapper.getAltSourceRnode() == null);

            // Trace locked path back from partition pin to the source
            RouteNode endOfLockedPath = rnode;
            assert(endOfLockedPath.isArcLocked());
            assert(routingGraph.getPreservedNet(endOfLockedPath) == net);
            RouteNode sourceRnode = endOfLockedPath;
            while ((sourceRnode = sourceRnode.getPrev()) != null && sourceRnode.isArcLocked()) {}
            assert(sourceRnode == netWrapper.getSourceRnode());
            netToTrueSourceRnodeBehindLockedPath.put(netWrapper, netWrapper.getSourceRnode());
            for (Connection connection : netWrapper.getConnections()) {
                assert(connection.getSourceRnode() == netWrapper.getSourceRnode());
                connection.setSourceRnode(endOfLockedPath);
            }
        }
    }

    @Override
    protected boolean isValidSink(Connection connection, RouteNode rnode) {
        if (super.isValidSink(connection, rnode)) {
            return true;
        }

        RouteNode beginOfLockedPath = connectionToTrueSinkRnodeBehindLockedPath.get(connection);
        if (beginOfLockedPath == rnode) {
            // This is the rnode of a locked path that does lead to this connection's sink
            return true;
        }

        return false;
    }

    @Override
    protected boolean saveRouting(Connection connection, RouteNode rnode) {
        if (super.saveRouting(connection, rnode)) {
            return true;
        }

        List<RouteNode> rnodes = connection.getRnodes();
        RouteNode sourceRnode = rnodes.get(rnodes.size() - 1);
        if (sourceRnode == netToTrueSourceRnodeBehindLockedPath.get(connection.getNetWrapper())) {
            // Backtracked to the endRnode of a locked path that goes all the way back to the true
            // net source -- routing is complete
            return true;
        }

        return false;
    }

    @Override
    protected void finishRouteConnection(Connection connection, RouteNode rnode) {
        RouteNode beginOfLockedPath = connectionToTrueSinkRnodeBehindLockedPath.get(connection);
        if (beginOfLockedPath != null) {
            // rnode is the beginning of a locked path to the real sink; start routing recovery from that real sink instead
            assert(connection.getSinkRnode() == rnode);
            rnode = beginOfLockedPath;
        }

        super.finishRouteConnection(connection, rnode);
    }

    @Override
    protected void assignNodesToConnections() {
        // Now that we've finished routing, revert all source/sink rnodes back to their original
        for (Connection connection : indirectConnections) {
            RouteNode trueSource = netToTrueSourceRnodeBehindLockedPath.get(connection.getNetWrapper());
            if (trueSource != null) {
                connection.setSourceRnode(trueSource);
            }
            RouteNode trueSink = connectionToTrueSinkRnodeBehindLockedPath.get(connection);
            if (trueSink != null) {
                connection.setSinkRnode(trueSink);
            }
        }

        super.assignNodesToConnections();
    }

    /**
     * Partially routes all unrouted sinks in a {@link Design} instance; fully-routed sinks will have their routing preserved.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design, String[] args) {
        boolean softPreserve = false;
        List<SitePinInst> pinsToRoute = null;

        // Instantiates a RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        return routeDesignWithUserDefinedArguments(design, args, pinsToRoute, softPreserve);
    }

    /**
     * Partially routes all given sinks in a {@link Design} instance; fully-routed sinks will have their routing preserved
     * if "softPreserve" is false, otherwise such sinks may be lazily-rerouted when attempting to route other congested sinks.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed. If null, route all unrouted pins in the design.
     * @param softPreserve Allow routed nets to be unrouted and subsequently rerouted in order to improve routability.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design,
                                                             String[] args,
                                                             Collection<SitePinInst> pinsToRoute,
                                                             boolean softPreserve) {
        // Instantiates an RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        RWRouteConfig config = new RWRouteConfig(args);
        if (pinsToRoute == null) {
            preprocess(design);
            pinsToRoute = getUnroutedPins(design);
        }

        if (config.isMaskNodesCrossRCLK()) {
            System.out.println("WARNING: Masking nodes across RCLK for partial routing could result in routability problems.");
        }

        return routeDesign(new PartialDFXRouter(design, config, pinsToRoute, softPreserve));
    }

    /**
     * Routes a design in the partial non-timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed. If null, route all unrouted pins in the design.
     */
    public static Design routeDesignPartialNonTimingDriven(Design design, Collection<SitePinInst> pinsToRoute) {
        boolean softPreserve = false;
        return routeDesignPartialNonTimingDriven(design, pinsToRoute, softPreserve);
    }

    /**
     * Routes a design in the partial non-timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed. If null, route all unrouted pins in the design.
     * @param softPreserve Allow routed nets to be unrouted and subsequently rerouted in order to improve routability.
     */
    public static Design routeDesignPartialNonTimingDriven(Design design, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        return routeDesignWithUserDefinedArguments(design, new String[] {
                        "--fixBoundingBox",
                        // use U-turn nodes and no masking of nodes cross RCLK
                        // Pros: maximum routability
                        // Con: might result in delay optimism and a slight increase in runtime
                        "--useUTurnNodes",
                        "--nonTimingDriven",
                        "--verbose"},
                pinsToRoute, softPreserve);
    }

    /**
     * Routes a design in the partial timing-driven routing mode.
     * @param design The {@link Design} instance to be routed.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed. If null, route all unrouted pins in the design.
     * @param softPreserve Allow routed nets to be unrouted and subsequently rerouted in order to improve routability.
     */
    public static Design routeDesignPartialTimingDriven(Design design, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        return routeDesignWithUserDefinedArguments(design, new String[] {
                        "--fixBoundingBox",
                        // use U-turn nodes and no masking of nodes cross RCLK
                        // Pros: maximum routability
                        // Con: might result in delay optimism and a slight increase in runtime
                        "--useUTurnNodes",
                        "--verbose"},
                pinsToRoute, softPreserve);
    }

    /**
     * The main interface of {@link PartialDFXRouter} that reads in a {@link Design} checkpoint,
     * and parses the arguments for the {@link RWRouteConfig} object of the router.
     * Similar to {@link PartialRouter}, only unrouted sinks will be tackled; all routed sinks will have their routing
     * preserved and not be re-routed.
     * Nets that route from the static region into the dynamic region must be locked and have a {@link PartitionPin}
     * attached to indicate the branch point to be used to connect unrouted sinks.
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
        System.out.println("\nINFO: Wrote routed design\n " + routedDCPfileName + "\n");
    }
}
