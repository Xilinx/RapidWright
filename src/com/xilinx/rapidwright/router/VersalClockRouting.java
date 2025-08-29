/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.router;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;

import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.rwroute.NodeStatus;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.rwroute.RouterHelper.NodeWithPrev;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A collection of utility methods for routing clocks on
 * the Versal architecture.
 *
 * Created on: Nov 1, 2024
 */
public class VersalClockRouting {
    public static class NodeWithPrevAndCost extends NodeWithPrev implements Comparable<NodeWithPrevAndCost> {
        protected int cost;
        public NodeWithPrevAndCost(Node node) {
            super(node);
            setCost(0);
        }
        public NodeWithPrevAndCost(Node node, NodeWithPrev prev, int cost) {
            super(node, prev);
            setCost(cost);
        }

        public void setCost(int cost) {
            this.cost = cost;
        }

        @Override
        public int compareTo(NodeWithPrevAndCost that) {
            return Integer.compare(this.cost, that.cost);
        }
    }

    public static Node routeBUFGToNearestRoutingTrack(Net clk) {
        Queue<NodeWithPrev> q = new ArrayDeque<>();
        q.add(new NodeWithPrev(clk.getSource().getConnectedNode()));
        int watchDog = 300;
        while (!q.isEmpty()) {
            NodeWithPrev curr = q.poll();
            IntentCode c = curr.getIntentCode();
            if (c == IntentCode.NODE_GLOBAL_HROUTE_HSR || c == IntentCode.NODE_GLOBAL_HROUTE) {
                List<Node> path = curr.getPrevPath();
                clk.getPIPs().addAll(RouterHelper.getPIPsFromNodes(path));
                return curr;
            }
            for (Node downhill: curr.getAllDownhillNodes()) {
                q.add(new NodeWithPrev(downhill, curr));
            }
            if (watchDog-- == 0) {
                break;
            }
        }
        return null;
    }

    /**
     * Routes a clock from a routing track to a transition point where the clock.
     * fans out and transitions from clock routing tracks to clock distribution.
     * @param clk The current clock net to contribute routing.
     * @param startingNode The intermediate start point of the clock route.
     * @param clockRegion The center clock region or the clock region that is one row above or below the center.
     * @param findCentroidHroute The flag to indicate the returned Node should be HROUTE in the center or VROUTE going up or down.
     */
    public static Node routeToCentroid(Net clk, Node startingNode, ClockRegion clockRegion, boolean findCentroidHroute) {
        Queue<NodeWithPrevAndCost> q = new PriorityQueue<>();
        q.add(new NodeWithPrevAndCost(startingNode));
        int watchDog = 10000;
        Set<Node> visited = new HashSet<>();
        Tile crApproxCenterTile = clockRegion.getApproximateCenter();

        // In Vivado solutions, we can always find the pattern:
        // ... -> NODE_GLOBAL_GCLK -> NODE_GLOBAL_VROUTE -> NODE_GLOBAL_VDISTR_LVL2 -> ...
        // and this is how we locate the VROUTE node

        while (!q.isEmpty()) {
            NodeWithPrevAndCost curr = q.poll();
            boolean possibleCentroid = false;
            NodeWithPrev parent = curr.getPrev();
            if (parent != null) {
                IntentCode parentIntentCode = parent.getIntentCode();
                IntentCode currIntentCode = curr.getIntentCode();
                if (parentIntentCode == IntentCode.NODE_GLOBAL_VROUTE &&
                    currIntentCode == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                    // Disallow ability to go from VROUTE back to HROUTE
                    continue;
                }
                if (currIntentCode == IntentCode.NODE_GLOBAL_GCLK 
                        && clockRegion.equals(curr.getTile().getClockRegion()) 
                        && clockRegion.equals(parent.getTile().getClockRegion())) {
                    if (parentIntentCode == IntentCode.NODE_GLOBAL_VROUTE && parent.getWireName().contains("BOT")) {
                        possibleCentroid = true;
                    } else if (parentIntentCode == IntentCode.NODE_GLOBAL_GCLK && parent.getPrev() != null) {
                        // Check grandparent
                        NodeWithPrev grandParent = parent.getPrev();
                        if ( grandParent.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE 
                                && clockRegion.equals(grandParent.getTile().getClockRegion()) 
                                && grandParent.getWireName().contains("BOT")) { 
                            possibleCentroid = true;                            
                        }
                    }

                }
            }
            for (Node downhill : curr.getAllDownhillNodes()) {
                IntentCode downhillIntentCode = downhill.getIntentCode();
                // Only using routing lines to get to centroid
                if (!downhillIntentCode.isVersalClocking()) {
                    continue;
                }

                if (possibleCentroid && downhillIntentCode == IntentCode.NODE_GLOBAL_VDISTR_LVL2) {
                    NodeWithPrev centroidHRouteNode  = curr.getPrev();
                    if (findCentroidHroute) {
                        while (centroidHRouteNode.getIntentCode() != IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                            centroidHRouteNode = centroidHRouteNode.getPrev();
                        }
                    }
                    List<Node> path = centroidHRouteNode.getPrevPath();
                    clk.getPIPs().addAll(RouterHelper.getPIPsFromNodes(path));
                    return centroidHRouteNode;
                }

                if (!findCentroidHroute && downhillIntentCode == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                    continue;
                }
                if (!visited.add(downhill)) {
                    continue;
                }

                int cost = downhill.getTile().getManhattanDistance(crApproxCenterTile);
                q.add(new NodeWithPrevAndCost(downhill, curr, cost));
            }
            if (watchDog-- == 0) {
                throw new RuntimeException("ERROR: Could not route from " + startingNode + " to clock region " + clockRegion);
            }
        }

        return null;
    }

    public static Map<ClockRegion, Node> routeVrouteToVerticalDistributionLines(Net clk,
                                                                                Node vroute,
                                                                                Collection<ClockRegion> clockRegions,
                                                                                Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, Node> crToVdist = new HashMap<>();
        Queue<NodeWithPrevAndCost> q = new PriorityQueue<>();
        Set<Node> visited = new HashSet<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<NodeWithPrevAndCost> startingPoints = new HashSet<>();
        startingPoints.add(new NodeWithPrevAndCost(vroute));
        // Pattern: NODE_GLOBAL_VROUTE -> ... -> NODE_GLOBAL_VDISTR_LVL2 -> ... -> NODE_GLOBAL_VDISTR_LVL1 -> ... -> NODE_GLOBAL_VDISTR
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            IntentCode.NODE_GLOBAL_VDISTR,
            IntentCode.NODE_GLOBAL_VDISTR_LVL1,
            IntentCode.NODE_GLOBAL_VDISTR_LVL2,
            IntentCode.NODE_GLOBAL_GCLK
        );
        nextClockRegion: for (ClockRegion cr : clockRegions) {
            q.clear();
            visited.clear();
            q.addAll(startingPoints);
            Tile crApproxCenterTile = cr.getApproximateCenter();
            while (!q.isEmpty()) {
                NodeWithPrevAndCost curr = q.poll();
                IntentCode c = curr.getIntentCode();
                ClockRegion currCR = curr.getTile().getClockRegion();
                if (currCR != null && cr.getRow() == currCR.getRow() && c == IntentCode.NODE_GLOBAL_VDISTR) {
                    // Only consider base wires
                    if (getNodeStatus.apply(curr) == NodeStatus.INUSE) {
                        startingPoints.add(curr);
                    } else {
                        List<Node> path = curr.getPrevPath();
                        for (Node node : path) {
                            startingPoints.add(new NodeWithPrevAndCost(node));
                        }
                        allPIPs.addAll(RouterHelper.getPIPsFromNodes(path));
                    }
                    crToVdist.put(cr, curr);
                    continue nextClockRegion;
                }

                for (Node downhill : curr.getAllDownhillNodes()) {
                    if (!allowedIntentCodes.contains(downhill.getIntentCode())) {
                        continue;
                    }
                    if (!visited.add(downhill)) {
                        continue;
                    }
                    int cost = downhill.getTile().getManhattanDistance(crApproxCenterTile);
                    q.add(new NodeWithPrevAndCost(downhill, curr, cost));
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + cr);
        }
        clk.getPIPs().addAll(allPIPs);
        return crToVdist;
    }

    /**
     * For each target clock region, route from the provided vertical distribution line to a
     * horizontal distribution line that has a GLOBAL_GLK child node in this clock region.
     * This simulates the behavior of Vivado.
     * @param clk The current clock net
     * @param crMap A map of target clock regions and their respective vertical distribution lines
     * @return The map of target clock regions and their respective horizontal distribution lines.
     */
    public static Map<ClockRegion, Node> routeVerticalToHorizontalDistributionLines(Net clk,
                                                                                    Map<ClockRegion, Node> crMap,
                                                                                    Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, Node> distLines = new HashMap<>();
        Queue<NodeWithPrev> q = new ArrayDeque<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<Node> visited = new HashSet<>();
        nextClockRegion: for (Entry<ClockRegion,Node> e : crMap.entrySet()) {
            q.clear();
            Node vertDistLine = e.getValue();
            q.add(new NodeWithPrev(vertDistLine));
            ClockRegion targetCR = e.getKey();
            visited.clear();
            visited.add(vertDistLine);
            
            while (!q.isEmpty()) {
                NodeWithPrev curr = q.poll();
                NodeWithPrev parent = curr.getPrev();
                if (targetCR.equals(curr.getTile().getClockRegion()) &&
                    curr.getIntentCode() == IntentCode.NODE_GLOBAL_GCLK &&
                    parent.getIntentCode() == IntentCode.NODE_GLOBAL_HDISTR_LOCAL) {
                    List<Node> path = curr.getPrevPath();
                    for (int i = 1; i < path.size(); i++) {
                        Node node = path.get(i);
                        NodeStatus status = getNodeStatus.apply(node);
                        if (status == NodeStatus.INUSE) {
                            break;
                        }
                        assert(status == NodeStatus.AVAILABLE);
                        if (i > 1) {
                            allPIPs.add(PIP.getArbitraryPIP(node, path.get(i-1)));
                        }
                    }
                    distLines.put(targetCR, parent);
                    continue nextClockRegion;
                }

                for (Node downhill: curr.getAllDownhillNodes()) {
                    IntentCode intentCode = downhill.getIntentCode();
                    if (intentCode != IntentCode.NODE_PINFEED && !intentCode.isVersalClocking()) {
                        continue;
                    }
                    if (!visited.add(downhill)) {
                        continue;
                    }
                    q.add(new NodeWithPrev(downhill, curr));
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + targetCR);
        }
        clk.getPIPs().addAll(allPIPs);
        return distLines;
    }

    /**
     * Routes from distribution lines to the leaf clock buffers (LCBs)
     * @param clk The current clock net
     * @param distLines A map of target clock regions and their respective horizontal distribution lines
     * @param lcbTargets The target LCB nodes to route the clock
     */
    public static void routeDistributionToLCBs(Net clk, Map<ClockRegion, Node> distLines, Set<Node> lcbTargets) {
        Map<ClockRegion, Set<NodeWithPrevAndCost>> startingPoints = getStartingPoints(distLines);
        routeToLCBs(clk, startingPoints, lcbTargets);
    }

    public static Map<ClockRegion, Set<NodeWithPrevAndCost>> getStartingPoints(Map<ClockRegion, Node> distLines) {
        Map<ClockRegion, Set<NodeWithPrevAndCost>> startingPoints = new HashMap<>();
        for (Entry<ClockRegion, Node> e : distLines.entrySet()) {
            ClockRegion cr = e.getKey();
            Node distLine = e.getValue();
            startingPoints.computeIfAbsent(cr, k -> new HashSet<>())
                    .add(new NodeWithPrevAndCost(distLine));
        }
        return startingPoints;
    }

    public static void routeToLCBs(Net clk, Map<ClockRegion, Set<NodeWithPrevAndCost>> startingPoints, Set<Node> lcbTargets) {
        Queue<NodeWithPrevAndCost> q = new PriorityQueue<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<Node> visited = new HashSet<>();

        nextLCB: for (Node lcb : lcbTargets) {
            q.clear();
            visited.clear();
            Tile lcbTile = lcb.getTile();
            ClockRegion currCR = lcbTile.getClockRegion();
            Set<NodeWithPrevAndCost> starts = startingPoints.getOrDefault(currCR, Collections.emptySet());
            for (NodeWithPrev n : starts) {
                assert(n.getPrev() == null);
            }
            q.addAll(starts);
            while (!q.isEmpty()) {
                NodeWithPrevAndCost curr = q.poll();
                if (lcb.equals(curr)) {
                    List<Node> path = curr.getPrevPath();
                    allPIPs.addAll(RouterHelper.getPIPsFromNodes(path));

                    Set<NodeWithPrevAndCost> s = startingPoints.get(currCR);
                    for (Node n : path) {
                        s.add(new NodeWithPrevAndCost(n));
                    }

                    continue nextLCB;
                }
                for (Node downhill : curr.getAllDownhillNodes()) {
                    // Stay in this clock region
                    if (!currCR.equals(downhill.getTile().getClockRegion())) {
                        continue;
                    }
                    IntentCode intentCode = downhill.getIntentCode();
                    if (intentCode != IntentCode.NODE_PINFEED && !intentCode.isVersalClocking()) {
                        continue;
                    }
                    if (downhill.getWireName().endsWith("_I_CASC_PIN") || downhill.getWireName().endsWith("_CLR_B_PIN")) {
                        continue;
                    }
                    if (!visited.add(downhill)) {
                        continue;
                    }
                    int cost = downhill.getTile().getManhattanDistance(lcbTile);
                    q.add(new NodeWithPrevAndCost(downhill, curr, cost));
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to leaf clock buffer " + lcb);
        }
        clk.getPIPs().addAll(allPIPs);
    }

    /**
     * Routes from a GLOBAL_VERTICAL_ROUTE to horizontal distribution lines.
     * @param clk The clock net to be routed.
     * @param vroute The node to start the route.
     * @param clockRegions Target clock regions.
     * @param down To indicate if it is routing to the group of top clock regions.
     * @return The map of target clock regions and their respective horizontal distribution lines.
     */
    public static Map<ClockRegion, Node> routeToHorizontalDistributionLines(Net clk,
                                                                            Node vroute,
                                                                            Collection<ClockRegion> clockRegions,
                                                                            boolean down,
                                                                            Function<Node, NodeStatus> getNodeStatus) {
        // First step: map each clock region to a VDISTR node. 
        // The clock region of this VDISTR node should be in the same column of the centroid (X) and the same row of the target clock region (Y). 
        Map<ClockRegion, Node> vertDistLines = routeVrouteToVerticalDistributionLines(clk, vroute, clockRegions, getNodeStatus);

        // Second step: start from the VDISTR node and try to find a HDISTR node in the target clock region.
        return routeVerticalToHorizontalDistributionLines(clk, vertDistLines, getNodeStatus);
    }

    /**
     * Routes a partially routed clock.
     * It will examine the clock net for SitePinInsts and assumes any present are already routed. It
     * then invokes {@link DesignTools#createMissingSitePinInsts(Design, Net)} to discover those not
     * yet routed.
     * @param design  The current design
     * @param clkNet The partially routed clock net to make fully routed
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void incrementalClockRouter(Design design,
                                              Net clkNet,
                                              Function<Node,NodeStatus> getNodeStatus) {
        // TODO:
        throw new RuntimeException("ERROR: Incremental clock routing not yet supported for Versal devices.");
    }

    /**
     * Routes a list of unrouted pins from a partially routed clock.
     * @param clkNet The partially routed clock net to make fully routed
     * @param clkPins A list of unrouted pins on the clock net to route
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void incrementalClockRouter(Net clkNet,
                                              List<SitePinInst> clkPins,
                                              Function<Node,NodeStatus> getNodeStatus) {
        // TODO:
        throw new RuntimeException("ERROR: Incremental clock routing not yet supported for Versal devices.");
    }

    public static Map<Node, List<SitePinInst>> routeLCBsToSinks(Net clk,
                                                                Function<Node,NodeStatus> getNodeStatus) {
        Map<Node, List<SitePinInst>> lcbMappings = new HashMap<>();
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            IntentCode.NODE_CLE_CNODE,
            IntentCode.NODE_INTF_CNODE,
            IntentCode.NODE_INODE,
            IntentCode.NODE_PINBOUNCE,
            IntentCode.NODE_CLE_BNODE,
            IntentCode.NODE_INTF_BNODE,
            IntentCode.NODE_IMUX,
            IntentCode.NODE_CLE_CTRL,
            IntentCode.NODE_INTF_CTRL,
            IntentCode.NODE_IRI,
            IntentCode.NODE_PINFEED,
            IntentCode.NODE_GLOBAL_LEAF
        );
        Set<Node> visited = new HashSet<>();
        Queue<NodeWithPrev> q = new ArrayDeque<>();
        Predicate<Node> isNodeUnavailable = (node) -> getNodeStatus.apply(node) == NodeStatus.UNAVAILABLE;
        RouteThruHelper routeThruHelper = new RouteThruHelper(clk.getDesign().getDevice());

        nextPin: for (SitePinInst p: clk.getPins()) {
            if (p.isOutPin()) {
                continue;
            }
            NodeWithPrev sink = new NodeWithPrev(p.getConnectedNode());
            ClockRegion cr = p.getTile().getClockRegion();

            q.clear();
            q.add(sink);

            while (!q.isEmpty()) {
                NodeWithPrev curr = q.poll();
                for (Node uphill : curr.getAllUphillNodes()) {
                    if (!uphill.getTile().getClockRegion().equals(cr)) {
                        continue;
                    }
                    IntentCode uphillIntentCode = uphill.getIntentCode();
                    if (!allowedIntentCodes.contains(uphillIntentCode)) {
                        continue;
                    }
                    if (!visited.add(uphill)) {
                        continue;
                    }
                    if (routeThruHelper.isRouteThru(uphill, curr) && curr.getIntentCode() != IntentCode.NODE_IRI) {
                        continue;
                    }
                    if (isNodeUnavailable.test(uphill)) {
                        continue;
                    }
                    NodeWithPrev node = new NodeWithPrev(uphill, curr);
                    if (uphillIntentCode == IntentCode.NODE_GLOBAL_LEAF) {
                        List<Node> path = node.getPrevPath();
                        boolean srcToSinkOrder = true;
                        clk.getPIPs().addAll(RouterHelper.getPIPsFromNodes(path, srcToSinkOrder));
                        lcbMappings.computeIfAbsent(uphill, (k) -> new ArrayList<>()).add(p);
                        visited.clear();
                        continue nextPin;
                    }
                    q.add(node);
                }
            }
            throw new RuntimeException("ERROR: Couldn't route pin " + sink + " to any LCB");
        }

        return lcbMappings;
    }
}
