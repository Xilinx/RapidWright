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
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.rwroute.NodeStatus;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.rwroute.RouterHelper.NodeWithPrev;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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

    public static Node routeBUFGToNearestRoutingTrack(Net clk) {
        Queue<NodeWithPrev> q = new LinkedList<>();
        q.add(new NodeWithPrev(clk.getSource().getConnectedNode()));
        int watchDog = 300;
        while (!q.isEmpty()) {
            NodeWithPrev curr = q.poll();
            IntentCode c = curr.getIntentCode();
            if (c == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
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
     * @param findCentroidHroute The flag to indicate the returned RouteNode should be HROUTE in the center or VROUTE going up or down.
     */
    public static Node routeToCentroid(Net clk, Node startingNode, ClockRegion clockRegion, boolean findCentroidHroute) {
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        q.add(new RouteNode(startingNode));
        int watchDog = 10000000;
        RouteNode centroidHRouteNode;
        Set<Node> visited = new HashSet<>();

        // In Vivado solutions, we can always find the pattern:
        // ... -> NODE_GLOBAL_GCLK -> NODE_GLOBAL_VROUTE -> NODE_GLOBAL_VDISTR_LVL2 -> ...
        // and this is how we locate the VROUTE node

        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            Node currNode = Node.getNode(curr);
            RouteNode parent = curr.getParent();
            for (Node downhill : currNode.getAllDownhillNodes()) {
                IntentCode intentCode = downhill.getIntentCode();
                if (parent != null) {
                    Node parentNode = Node.getNode(parent);
                    if (parentNode.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE &&
                        currNode.getIntentCode() == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                        // Disallow ability to go from VROUTE back to HROUTE
                        continue;
                    }
                    if (intentCode == IntentCode.NODE_GLOBAL_VDISTR_LVL2 &&
                       currNode.getIntentCode()   == IntentCode.NODE_GLOBAL_GCLK &&
                       parentNode.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE &&
                       clockRegion.equals(currNode.getTile().getClockRegion()) &&
                       clockRegion.equals(parentNode.getTile().getClockRegion()) &&
                       parentNode.getWireName().contains("BOT")) {
                        if (findCentroidHroute) {
                            centroidHRouteNode = curr.getParent();
                            while (centroidHRouteNode.getIntentCode() != IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                                centroidHRouteNode = centroidHRouteNode.getParent();
                            }
                            clk.getPIPs().addAll(centroidHRouteNode.getPIPsBackToSourceByNodes());
                            return Node.getNode(centroidHRouteNode);
                        }
                        // assign PIPs based on which RouteNode returned, instead of curr
                        clk.getPIPs().addAll(parent.getPIPsBackToSourceByNodes());
                        return Node.getNode(parent);
                    }
                }

                // Only using routing lines to get to centroid
                if (!intentCode.isVersalClocking()) {
                    continue;
                }
                if (!findCentroidHroute && intentCode == IntentCode.NODE_GLOBAL_HROUTE_HSR) {
                    continue;
                }
                if (!visited.add(downhill)) continue;
                RouteNode rn = new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1);
                
                // The clockRegion.getApproximateCenter() may return an INVALID_* tile with huge coordinates.
                // Here we use the Manhattan distance to the target clock region as the cost.
                ClockRegion rnClockRegion = rn.getTile().getClockRegion();
                int cost = Math.abs(rnClockRegion.getColumn() - clockRegion.getColumn()) + Math.abs(rnClockRegion.getRow() - clockRegion.getRow());
                rn.setCost(cost);
                q.add(rn);
            }
            if (watchDog-- == 0) {
                throw new RuntimeException("ERROR: Could not route from " + startingNode + " to clock region " + clockRegion);
            }
        }

        return null;
    }

    /**
     * Routes the vertical distribution path and generates a map between each target clock region and the vertical distribution line to
     * start from.
     * @param clk The clock net.
     * @param centroidDistNode Starting point vertical distribution line
     * @param clockRegions The target clock regions.
     * @return A map of target clock regions and their respective vertical distribution lines
     */
    public static Map<ClockRegion, RouteNode> routeCentroidToVerticalDistributionLines(Net clk,
                                                                                       RouteNode centroidDistNode,
                                                                                       Collection<ClockRegion> clockRegions,
                                                                                       Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, RouteNode> crToVdist = new HashMap<>();
        centroidDistNode.setParent(null);
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        HashSet<RouteNode> visited = new HashSet<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<RouteNode> startingPoints = new HashSet<>();
        startingPoints.add(centroidDistNode);
        assert(centroidDistNode.getParent() == null);
        nextClockRegion: for (ClockRegion cr : clockRegions) {
            q.clear();
            visited.clear();
            q.addAll(startingPoints);
            Tile crTarget = cr.getApproximateCenter();
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                visited.add(curr);
                IntentCode c = curr.getIntentCode();
                ClockRegion currCR = curr.getTile().getClockRegion();
                if (currCR != null && cr.equals(currCR) && c == IntentCode.NODE_GLOBAL_VDISTR) {
                    // Only consider base wires
                    Node currNode = Node.getNode(curr);
                    if (getNodeStatus.apply(currNode) == NodeStatus.INUSE) {
                        startingPoints.add(curr);
                    } else {
                        List<PIP> pips = curr.getPIPsBackToSource();
                        allPIPs.addAll(pips);
                        for (PIP p : pips) {
                            startingPoints.add(p.getStartRouteNode());
                            startingPoints.add(p.getEndRouteNode());
                        }
                    }
                    RouteNode currBase = new RouteNode(currNode);
                    currBase.setParent(null);
                    crToVdist.put(cr, currBase);
                    continue nextClockRegion;
                }
                for (Wire w : curr.getWireConnections()) {
                    if (w.getIntentCode() != IntentCode.NODE_GLOBAL_VDISTR) continue;
                    Node n = Node.getNode(w);
                    RouteNode rn = new RouteNode(n.getTile(), n.getWireIndex(), curr, curr.getLevel()+1);
                    if (visited.contains(rn)) continue;
                    rn.setCost(w.getTile().getManhattanDistance(crTarget));
                    q.add(rn);
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + cr);
        }
        clk.getPIPs().addAll(allPIPs);
        centroidDistNode.setParent(null);
        return crToVdist;
    }

    public static Map<ClockRegion, Node> routeVrouteToVerticalDistributionLines(Net clk,
                                                                                Node vroute,
                                                                                Collection<ClockRegion> clockRegions,
                                                                                Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, Node> crToVdist = new HashMap<>();
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        HashSet<Node> visited = new HashSet<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<RouteNode> startingPoints = new HashSet<>();
        startingPoints.add(new RouteNode(vroute));
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
            Tile crTarget = cr.getApproximateCenter();
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                Node currNode = Node.getNode(curr);
                IntentCode c = currNode.getIntentCode();
                ClockRegion currCR = currNode.getTile().getClockRegion();
                if (currCR != null && cr.getRow() == currCR.getRow() && c == IntentCode.NODE_GLOBAL_VDISTR) {
                    // Only consider base wires
                    if (getNodeStatus.apply(currNode) == NodeStatus.INUSE) {
                        startingPoints.add(curr);
                    } else {
                        List<PIP> pips = curr.getPIPsBackToSourceByNodes();
                        allPIPs.addAll(pips);
                        for (PIP p : pips) {
                            startingPoints.add(p.getStartRouteNode());
                            startingPoints.add(p.getEndRouteNode());
                        }
                    }
                    crToVdist.put(cr, currNode);
                    continue nextClockRegion;
                }

                for (Node downhill : currNode.getAllDownhillNodes()) {
                    if (!allowedIntentCodes.contains(downhill.getIntentCode())) {
                        continue;
                    }
                    if (visited.contains(downhill)) continue;
                    RouteNode rn = new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1);
                    rn.setCost(downhill.getTile().getManhattanDistance(crTarget));
                    q.add(rn);
                    visited.add(downhill);
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + cr);
        }
        clk.getPIPs().addAll(allPIPs);
        return crToVdist;
    }

    /**
     * Routes from a vertical distribution centroid to destination horizontal distribution lines
     * in the clock regions provided.
     * @param clk The current clock net
     * @param crMap A map of target clock regions and their respective vertical distribution lines
     * @return The List of nodes from the centroid to the horizontal distribution line.
     */
    public static Map<ClockRegion, Node> routeVerticalToHorizontalDistributionLines(Net clk,
                                                                                    Map<ClockRegion, Node> crMap,
                                                                                    Function<Node, NodeStatus> getNodeStatus) {
        Map<ClockRegion, Node> distLines = new HashMap<>();
        Queue<RouteNode> q = new LinkedList<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<Node> visited = new HashSet<>();
        nextClockRegion: for (Entry<ClockRegion,Node> e : crMap.entrySet()) {
            q.clear();
            Node vertDistLine = e.getValue();
            q.add(new RouteNode(vertDistLine));
            ClockRegion targetCR = e.getKey();
            visited.clear();
            visited.add(vertDistLine);
            
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                IntentCode c = curr.getIntentCode();
                Node currNode = Node.getNode(curr);
                RouteNode parent = curr.getParent();
                if (targetCR.equals(curr.getTile().getClockRegion()) && c == IntentCode.NODE_GLOBAL_GCLK &&
                        parent.getIntentCode() == IntentCode.NODE_GLOBAL_HDISTR_LOCAL) {
                    List<PIP> pips = parent.getPIPsBackToSourceByNodes();
                    for (PIP pip : pips) {
                        allPIPs.add(pip);
                        NodeStatus status = getNodeStatus.apply(pip.getStartNode());
                        if (status == NodeStatus.INUSE) {
                            break;
                        }
                        assert(status == NodeStatus.AVAILABLE);
                    }

                    parent.setParent(null);
                    distLines.put(targetCR, Node.getNode(parent));
                    continue nextClockRegion;
                }

                for (Node downhill: currNode.getAllDownhillNodes()) {
                    IntentCode intentCode = downhill.getIntentCode();
                    if (intentCode != IntentCode.NODE_PINFEED && !intentCode.isVersalClocking()) continue;
                    if (!visited.add(downhill)) continue;
                    q.add(new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1));
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
    public static void routeDistributionToLCBs(Net clk, Map<ClockRegion, Node> distLines, Set<RouteNode> lcbTargets) {
        Map<ClockRegion, Set<Node>> startingPoints = getStartingPoints(distLines);
        routeToLCBs(clk, startingPoints, lcbTargets);
    }

    public static Map<ClockRegion, Set<Node>> getStartingPoints(Map<ClockRegion, Node> distLines) {
        Map<ClockRegion, Set<Node>> startingPoints = new HashMap<>();
        for (Entry<ClockRegion, Node> e : distLines.entrySet()) {
            ClockRegion cr = e.getKey();
            Node distLine = e.getValue();
            startingPoints.computeIfAbsent(cr, k -> new HashSet<>()).add(distLine);
        }
        return startingPoints;
    }

    public static void routeToLCBs(Net clk, Map<ClockRegion, Set<Node>> startingPoints, Set<RouteNode> lcbTargets) {
        Queue<RouteNode> q = RouteNode.createPriorityQueue();
        Set<PIP> allPIPs = new HashSet<>();
        HashSet<RouteNode> visited = new HashSet<>();

        nextLCB: for (RouteNode lcb : lcbTargets) {
            q.clear();
            visited.clear();
            ClockRegion currCR = lcb.getTile().getClockRegion();
            Set<Node> starts = startingPoints.getOrDefault(currCR, Collections.emptySet());
            for (Node n : starts) {
                q.add(new RouteNode(n));
            }
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                visited.add(curr);
                if (lcb.equals(curr)) {
                    List<PIP> pips = curr.getPIPsBackToSource();
                    allPIPs.addAll(pips);

                    Set<Node> s = startingPoints.get(currCR);
                    for (PIP p : pips) {
                        s.add(p.getStartNode());
                        s.add(p.getEndNode());
                    }
                    continue nextLCB;
                }
                Node currNode = Node.getNode(curr);
                for (Node downhill : currNode.getAllDownhillNodes()) {
                    // Stay in this clock region
                    if (!currCR.equals(downhill.getTile().getClockRegion())) continue;
                    IntentCode intentCode = downhill.getIntentCode();
                    if (intentCode != IntentCode.NODE_PINFEED && !intentCode.isVersalClocking()) continue;
                    RouteNode rn = new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1);
                    if (visited.contains(rn)) continue;
                    if (rn.getWireName().endsWith("_I_CASC_PIN")) continue;
                    if (rn.getWireName().endsWith("_CLR_B_PIN")) continue;
                    rn.setCost(rn.getManhattanDistance(lcb));
                    q.add(rn);
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + lcb);
        }
        clk.getPIPs().addAll(allPIPs);
    }

    /**
     * @param clk
     * @param lcbMappings
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void routeLCBsToSinks(Net clk, Map<RouteNode, List<SitePinInst>> lcbMappings,
                                        Function<Node, NodeStatus> getNodeStatus) {
        Set<Node> used = new HashSet<>();
        Set<Node> visited = new HashSet<>();
        Queue<RouteNode> q = new LinkedList<>();
        
        Predicate<Node> isNodeUnavailable = (node) -> getNodeStatus.apply(node) == NodeStatus.UNAVAILABLE;
        Set<IntentCode> allowedIntentCodes = EnumSet.of(
            IntentCode.NODE_CLE_CNODE,
            IntentCode.NODE_INTF_CNODE,
            IntentCode.NODE_CLE_CTRL,
            IntentCode.NODE_INTF_CTRL,
            IntentCode.NODE_IRI,
            IntentCode.NODE_INODE,
            IntentCode.NODE_PINBOUNCE,
            IntentCode.NODE_CLE_BNODE,
            IntentCode.NODE_IMUX,
            IntentCode.NODE_PINFEED
        );

        RouteThruHelper routeThruHelper = new RouteThruHelper(clk.getDesign().getDevice());

        for (Entry<RouteNode,List<SitePinInst>> e : lcbMappings.entrySet()) {
            Set<PIP> currPIPs = new HashSet<>();
            RouteNode lcb = e.getKey();
            assert(lcb.getParent() == null);

            nextPin: for (SitePinInst sink : e.getValue()) {
                RouteNode target = sink.getRouteNode();
                Node targetNode = Node.getNode(target);
                q.clear();
                q.add(lcb);

                while (!q.isEmpty()) {
                    RouteNode curr = q.poll();
                    Node currNode = Node.getNode(curr);
                    if (targetNode.equals(currNode)) {
                        boolean inuse = false;
                        for (PIP pip : curr.getPIPsBackToSourceByNodes()) {
                            if (inuse) {
                                assert(getNodeStatus.apply(pip.getStartNode()) == NodeStatus.INUSE);
                                continue;
                            }
                            currPIPs.add(pip);
                            NodeStatus status = getNodeStatus.apply(pip.getStartNode());
                            if (status == NodeStatus.INUSE) {
                                inuse = true;
                                continue;
                            }
                            assert(status == NodeStatus.AVAILABLE);
                        }
                        sink.setRouted(true);
                        visited.clear();
                        continue nextPin;
                    }

                    for (Node downhill : currNode.getAllDownhillNodes()) {
                        if (!allowedIntentCodes.contains(downhill.getIntentCode())) continue;
                        if (!visited.add(downhill)) continue;
                        if (used.contains(downhill)) continue;
                        // have to allow those routethru-s NODE_IRI -> *
                        if (routeThruHelper.isRouteThru(currNode, downhill) && downhill.getIntentCode() != IntentCode.NODE_IRI) continue;
                        if (isNodeUnavailable.test(downhill)) continue;
                        q.add(new RouteNode(downhill.getTile(), downhill.getWireIndex(), curr, curr.getLevel()+1));
                    }
                }
                throw new RuntimeException("ERROR: Couldn't route LCB " + e.getKey() + " to Pin " + sink);
            }

            List<PIP> clkPIPs = clk.getPIPs();
            for (PIP p : currPIPs) {
                used.add(p.getStartNode());
                used.add(p.getEndNode());
                clkPIPs.add(p);
            }
        }
    }

    /**
     * Routes from a GLOBAL_VERTICAL_ROUTE to horizontal distribution lines.
     * @param clk The clock net to be routed.
     * @param vroute The node to start the route.
     * @param clockRegions Target clock regions.
     * @param down To indicate if it is routing to the group of top clock regions.
     * @return A list of RouteNodes indicating the reached horizontal distribution lines.
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
        throw new RuntimeException("ERROR: incrementalClockRouter not yet support on Versal devices.");
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
        throw new RuntimeException("ERROR: incrementalClockRouter not yet support on Versal devices.");
    }

    public static Map<RouteNode, List<SitePinInst>> routeLCBsToSinks(Net clk,
                                                                     Function<Node,NodeStatus> getNodeStatus) {
        Map<RouteNode, List<SitePinInst>> lcbMappings = new HashMap<>();
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
        Queue<RouteNode> q = new LinkedList<>();
        Predicate<Node> isNodeUnavailable = (node) -> getNodeStatus.apply(node) == NodeStatus.UNAVAILABLE;
        RouteThruHelper routeThruHelper = new RouteThruHelper(clk.getDesign().getDevice());

        nextPin: for (SitePinInst p: clk.getPins()) {
            if (p.isOutPin()) continue;
            Node sinkNode = p.getConnectedNode();
            RouteNode sinkRouteNode = new RouteNode(sinkNode.getTile(), sinkNode.getWireIndex(), null, 0);
            ClockRegion cr = p.getTile().getClockRegion();

            q.clear();
            q.add(sinkRouteNode);

            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                Node currNode = Node.getNode(curr);

                for (Node uphill : currNode.getAllUphillNodes()) {
                    if (!uphill.getTile().getClockRegion().equals(cr)) continue;
                    if (!allowedIntentCodes.contains(uphill.getIntentCode())) continue;
                    if (!visited.add(uphill)) continue;
                    if (routeThruHelper.isRouteThru(uphill, currNode) && currNode.getIntentCode() != IntentCode.NODE_IRI) continue;
                    if (isNodeUnavailable.test(uphill)) continue;
                    if (uphill.getIntentCode() == IntentCode.NODE_GLOBAL_LEAF) {
                        RouteNode rn = new RouteNode(uphill.getTile(), uphill.getWireIndex(), curr, curr.getLevel()+1);
                        clk.getPIPs().addAll(rn.getPIPsForwardToSinkByNodes());
                        rn.setParent(null);
                        rn.setLevel(0);
                        lcbMappings.computeIfAbsent(rn, (k) -> new ArrayList<>()).add(p);
                        visited.clear();
                        continue nextPin;
                    }
                    q.add(new RouteNode(uphill.getTile(), uphill.getWireIndex(), curr, curr.getLevel()+1));
                }
            }
            throw new RuntimeException("ERROR: Couldn't map Pin " + p + " to LCB.");
        }

        return lcbMappings;
    }
}
