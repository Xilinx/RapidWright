/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.rwroute.GlobalSignalRouting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

/**
 * A collection of utility methods for routing clocks on
 * the UltraScale architecture.
 *
 * Created on: Feb 1, 2018
 */
public class UltraScaleClockRouting {

    public static RouteNode routeBUFGToNearestRoutingTrack(Net clk) {
        Queue<RouteNode> q = new LinkedList<RouteNode>();
        q.add(new RouteNode(clk.getSource()));
        int watchDog = 300;
        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            IntentCode c = curr.getIntentCode();
            if (c == IntentCode.NODE_GLOBAL_HROUTE) {
                clk.getPIPs().addAll(curr.getPIPsBackToSource());
                return curr;
            }
            for (Wire w : curr.getWireConnections()) {
                q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
            }
            if (watchDog-- == 0) break;
        }
        return null;
    }

    /**
     * Routes a clock from a routing track to a transition point called the centroid
     * where the clock fans out and transitions from clock routing tracks to clock distribution
     * tracks
     * @param clk The current clock net to contribute routing
     * @param clkRoutingLine The intermediate start point of the clock route
     * @param centroid ClockRegion/FSR considered to be the centroid target
     */
    public static RouteNode routeToCentroid(Net clk, RouteNode clkRoutingLine, ClockRegion centroid) {
        return routeToCentroid(clk, clkRoutingLine, centroid, false, false);
    }
    /**
     * Routes a clock from a routing track to a transition point where the clock.
     * fans out and transitions from clock routing tracks to clock distribution.
     * @param clk The current clock net to contribute routing.
     * @param startingRouteNode The intermediate start point of the clock route.
     * @param clockRegion The center clock region or the clock region that is one row above or below the center.
     * @param adjusted A flag to guard the default functionality when routing to centroid clock region.
     * @param findCentroidHroute The flag to indicate the returned RouteNode should be HROUTE in the center or VROUTE going up or down.
     */
    public static RouteNode routeToCentroid(Net clk, RouteNode startingRouteNode, ClockRegion clockRegion, boolean adjusted, boolean findCentroidHroute) {
        Queue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
            public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
        HashSet<RouteNode> visited = new HashSet<>();
        startingRouteNode.setParent(null);
        q.add(startingRouteNode);
        Tile approxTarget = clockRegion.getApproximateCenter();
        int watchDog = 10000;

        RouteNode centroidHRouteNode = null;

        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            visited.add(curr);

            for (Wire w : curr.getWireConnections()) {
                RouteNode parent = curr.getParent();
                if (parent != null) {
                    if (w.getIntentCode()      == IntentCode.NODE_GLOBAL_VDISTR &&
                       curr.getIntentCode()   == IntentCode.NODE_GLOBAL_VROUTE &&
                       parent.getIntentCode() == IntentCode.NODE_GLOBAL_VROUTE &&
                       clockRegion.equals(w.getTile().getClockRegion()) &&
                       clockRegion.equals(curr.getTile().getClockRegion()) &&
                       clockRegion.equals(parent.getTile().getClockRegion()) &&
                       parent.getWireName().contains("BOT")) {
                        if (adjusted) {
                            if (findCentroidHroute) {
                                centroidHRouteNode = curr.getParent();
                                while (centroidHRouteNode.getIntentCode() != IntentCode.NODE_GLOBAL_HROUTE) {
                                    centroidHRouteNode = centroidHRouteNode.getParent();
                                }
                                clk.getPIPs().addAll(centroidHRouteNode.getPIPsBackToSource());
                                return centroidHRouteNode;
                            }
                            // assign PIPs based on which RouteNode returned, instead of curr
                            clk.getPIPs().addAll(parent.getPIPsBackToSource());
                            return parent;
                        } else {
                            clk.getPIPs().addAll(curr.getPIPsBackToSource());
                            return curr;
                        }
                    }
                }

                // Only using routing lines to get to centroid
                if (!w.getIntentCode().isUltraScaleClockRouting()) continue;
                if (adjusted && !findCentroidHroute && w.getIntentCode() == IntentCode.NODE_GLOBAL_HROUTE) {
                    continue;
                }
                RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
                if (visited.contains(rn)) continue;
                rn.setCost(rn.getTile().getManhattanDistance(approxTarget));
                q.add(rn);
            }
            if (watchDog-- == 0) {
                throw new RuntimeException("ERROR: Could not route from " + startingRouteNode + " to clock region " + clockRegion);
            }
        }
        return null;
    }

    /**
     * Routes a clock from a routing track to a given transition point called the centroid
     * @param clk The clock net to be routed
     * @param startingRouteNode The starting routing track
     * @param centroid The given centroid node
     * @return
     */
    public static RouteNode routeToCentroidNode(Net clk, RouteNode startingRouteNode, Node centroid) {
        Queue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
            public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
        HashSet<RouteNode> visited = new HashSet<>();

        startingRouteNode.setParent(null);
        q.add(startingRouteNode);
        Tile tileTarget = centroid.getTile();

        int watchDog = 10000;
        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            visited.add(curr);

            for (Wire w : curr.getWireConnections()) {
                // Only using clk routing network to reach centroid
                if (!w.getIntentCode().isUltraScaleClocking()) continue;

                if (w.getWireName().equals(centroid.getWireName())
                        && w.getTile().equals(centroid.getTile())) {
                    // curr is not the target, build the target
                    RouteNode routeNodeTarget = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
                    clk.getPIPs().addAll(routeNodeTarget.getPIPsBackToSource());
                    return routeNodeTarget;
                }

                RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
                if (visited.contains(rn)) continue;
                // using column & row based distance is more accurate than tile x/y coordinate based distance
                int md = Math.abs(rn.getTile().getColumn() - tileTarget.getColumn()) + Math.abs(rn.getTile().getRow() - tileTarget.getRow());
                rn.setCost(md);
                q.add(rn);
            }
            if (watchDog-- == 0) {
                throw new RuntimeException("ERROR: Could not route from " + startingRouteNode + "\n       to the given centroid: " + centroid
                                            + ".\n       Please check if BUFGCE is correctly placed in line with the reference.");
            }
        }
        return null;
    }

    /**
     * Routes the centroid route track to a vertical distribution track to realize
     * the centroid and root of the clock.
     * @param clk Clock net to route
     * @param centroidRouteLine The current routing track found in the centroid
     * @return The vertical distribution track for the centroid clock region
     */
    public static RouteNode transitionCentroidToDistributionLine(Net clk, RouteNode centroidRouteLine) {
        centroidRouteLine.setParent(null);
        if (centroidRouteLine.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR) {
            return centroidRouteLine;
        }
        ClockRegion currCR = centroidRouteLine.getTile().getClockRegion();
        return transitionCentroidToDistributoinLine(clk, centroidRouteLine, currCR);
    }

    public static RouteNode transitionCentroidToVerticalDistributionLine(Net clk, RouteNode centroidRouteLine, boolean down) {
        centroidRouteLine.setParent(null);
        if (centroidRouteLine.getIntentCode() == IntentCode.NODE_GLOBAL_VDISTR) {
            return centroidRouteLine;
        }

        ClockRegion currCR = centroidRouteLine.getTile().getClockRegion();
        if (down) currCR = currCR.getNeighborClockRegion(-1, 0);
        return transitionCentroidToDistributoinLine(clk, centroidRouteLine, currCR);
    }

    public static RouteNode transitionCentroidToDistributoinLine(Net clk, RouteNode centroidRouteLine, ClockRegion cr) {
        Queue<RouteNode> q = new LinkedList<RouteNode>();
        q.add(centroidRouteLine);
        ClockRegion currCR = cr;
        int watchDog = 1000;
        while (!q.isEmpty()) {
            RouteNode curr = q.poll();
            IntentCode c = curr.getIntentCode();
            if (curr.getTile().getClockRegion().equals(currCR) && c == IntentCode.NODE_GLOBAL_VDISTR) {
                clk.getPIPs().addAll(curr.getPIPsBackToSource());
                return curr;
            }
            for (Wire w : curr.getWireConnections()) {
                // Stay in this clock region to transition from
                if (!currCR.equals(w.getTile().getClockRegion())) continue;
                if (!w.getIntentCode().isUltraScaleClocking()) continue;
                q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
            }
            if (watchDog-- == 0) break;
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
    public static Map<ClockRegion, RouteNode> routeCentroidToVerticalDistributionLines(Net clk,    RouteNode centroidDistNode, List<ClockRegion> clockRegions) {
        Map<ClockRegion, RouteNode> crToVdist = new HashMap<>();
        centroidDistNode.setParent(null);
        Queue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
            public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
        HashSet<RouteNode> visited = new HashSet<>();
        Set<PIP> allPIPs = new HashSet<>();
        Set<RouteNode> startingPoints = new HashSet<>();
        startingPoints.add(centroidDistNode);
        nextClockRegion: for (ClockRegion cr : clockRegions) {
            q.clear();
            visited.clear();
            q.addAll(startingPoints);
            //q.add(centroidDistNode);
            Tile crTarget = cr.getApproximateCenter();
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                visited.add(curr);
                IntentCode c = curr.getIntentCode();
                ClockRegion currCR = curr.getTile().getClockRegion();
                if (currCR != null && cr.getRow() == currCR.getRow() && c == IntentCode.NODE_GLOBAL_VDISTR) {
                    List<PIP> pips = curr.getPIPsBackToSource();
                    allPIPs.addAll(pips);
                    for (PIP p : pips) {
                        startingPoints.add(new RouteNode(p.getTile(),p.getStartWireIndex()));
                        startingPoints.add(new RouteNode(p.getTile(),p.getEndWireIndex()));
                    }
                    crToVdist.put(cr, curr);
                    continue nextClockRegion;
                }
                for (Wire w : curr.getWireConnections()) {
                    if (w.getIntentCode() != IntentCode.NODE_GLOBAL_VDISTR) continue;
                    RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
                    if (visited.contains(rn)) continue;
                    rn.setCost(w.getTile().getManhattanDistance(crTarget));
                    q.add(rn);
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
     * @param centroidDistLine The current centroid
     * @param crMap A map that provides a RouteNode reference for each ClockRegion
     * @return The List of nodes from the centroid to the horizontal distribution line.
     */
    public static List<RouteNode> routeCentroidToHorizontalDistributionLines(Net clk, RouteNode centroidDistLine, Map<ClockRegion,RouteNode> crMap) {
        List<RouteNode> distLines = new ArrayList<>();
        centroidDistLine.setParent(null);
        Queue<RouteNode> q = new LinkedList<RouteNode>();
        Set<PIP> allPIPs = new HashSet<>();
        nextClockRegion: for (Entry<ClockRegion,RouteNode> e : crMap.entrySet()) {
            q.clear();
            q.add(e.getValue());
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                IntentCode c = curr.getIntentCode();
                if (e.getKey().equals(curr.getTile().getClockRegion()) && c == IntentCode.NODE_GLOBAL_HDISTR) {
                    List<PIP> pips = curr.getPIPsBackToSource();
                    allPIPs.addAll(pips);
                    distLines.add(curr);
                    continue nextClockRegion;
                }
                for (Wire w : curr.getWireConnections()) {
                    if (!w.getIntentCode().isUltraScaleClocking()) continue;
                    q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
                }
            }
            throw new RuntimeException("ERROR: Couldn't route to distribution line in clock region " + e.getKey());
        }
        clk.getPIPs().addAll(allPIPs);
        return distLines;
    }

    /**
     * Routes from distribution lines to the leaf clock buffers (LCBs)
     * @param clk The current clock net
     * @param lcbTargets The target LCB nodes to route the clock
     */
    public static void routeDistributionToLCBs(Net clk, List<RouteNode> distLines, Set<RouteNode> lcbTargets) {
        Map<ClockRegion, Set<RouteNode>> startingPoints = getStartingPoints(distLines);
        routeToLCBs(clk, startingPoints, lcbTargets);
    }

    public static Map<ClockRegion, Set<RouteNode>> getStartingPoints(List<RouteNode> distLines) {
        Map<ClockRegion, Set<RouteNode>> startingPoints = new HashMap<>();
        for (RouteNode rn : distLines) {
            ClockRegion cr = rn.getTile().getClockRegion();
            Set<RouteNode> routeNodes = startingPoints.get(cr);
            if (routeNodes == null) {
                routeNodes = new HashSet<>();
                startingPoints.put(cr, routeNodes);
            }
            routeNodes.add(rn);
        }
        return startingPoints;
    }

    public static void routeToLCBs(Net clk, Map<ClockRegion, Set<RouteNode>> startingPoints, Set<RouteNode> lcbTargets) {
        Queue<RouteNode> q = new PriorityQueue<RouteNode>(16, new Comparator<RouteNode>() {
            public int compare(RouteNode i, RouteNode j) {return i.getCost() - j.getCost();}});
        Set<PIP> allPIPs = new HashSet<>();
        HashSet<RouteNode> visited = new HashSet<>();

        nextLCB: for (RouteNode lcb : lcbTargets) {
            q.clear();
            visited.clear();
            ClockRegion currCR = lcb.getTile().getClockRegion();
            q.addAll(startingPoints.get(currCR));
            while (!q.isEmpty()) {
                RouteNode curr = q.poll();
                visited.add(curr);
                if (lcb.equals(curr)) {
                    List<PIP> pips = curr.getPIPsBackToSource();
                    allPIPs.addAll(pips);

                    Set<RouteNode> s = startingPoints.get(currCR);
                    for (PIP p : pips) {
                        s.add(new RouteNode(p.getTile(),p.getStartWireIndex()));
                        s.add(new RouteNode(p.getTile(),p.getEndWireIndex()));
                    }
                    continue nextLCB;
                }
                for (Wire w : curr.getWireConnections()) {
                    // Stay in this clock region
                    if (!currCR.equals(w.getTile().getClockRegion())) continue;
                    if (!w.getIntentCode().isUltraScaleClocking()) {
                        // Final node will not be clocking intent code
                        SitePin p = w.getSitePin();
                        if (p == null) continue;
                        if (p.getSite().getSiteTypeEnum() != SiteTypeEnum.BUFCE_LEAF) continue;
                    }
                    RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1);
                    if (visited.contains(rn)) continue;
                    if (rn.getWireName().endsWith("_CLK_CASC_OUT")) continue;
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
     */
    public static void routeLCBsToSinks(Net clk, Map<RouteNode, ArrayList<SitePinInst>> lcbMappings) {
        Set<Wire> used = new HashSet<>();
        Queue<RouteNode> q = new LinkedList<RouteNode>();

        for (Entry<RouteNode,ArrayList<SitePinInst>> e : lcbMappings.entrySet()) {
            Set<PIP> currPIPs = new HashSet<>();

            nextPin: for (SitePinInst sink : e.getValue()) {
                RouteNode target = sink.getRouteNode();
                q.clear();
                q.add(e.getKey());

                while (!q.isEmpty()) {
                    RouteNode curr = q.poll();
                    if (target.equals(curr)) {
                        List<PIP> pips = curr.getPIPsBackToSource();
                        currPIPs.addAll(pips);
                        sink.setRouted(true);
                        continue nextPin;
                    }
                    for (Wire w : curr.getWireConnections()) {
                        if (used.contains(w)) continue;
                        q.add(new RouteNode(w.getTile(), w.getWireIndex(), curr, curr.getLevel()+1));
                    }
                }
                throw new RuntimeException("ERROR: Couldn't route LCB " + e.getKey() + "to Pin " + sink);
            }

            List<PIP> clkPIPs = clk.getPIPs();
            for (PIP p : currPIPs) {
                used.add(new Wire(p.getTile(),p.getStartWireIndex()));
                used.add(new Wire(p.getTile(),p.getEndWireIndex()));
                clkPIPs.add(p);
            }
        }
    }

    /**
     * Routes from a GLOBAL_VERTICAL_ROUTE to horizontal distribution lines.
     * @param clk The clock net to be routed.
     * @param vroute The node to start the route.
     * @param clockRegions Target clock regions.
     * @param down To indicate if is is routing to the group of top clock regions.
     * @return A list of RouteNodes indicating the reached horizontal distribution lines.
     */
    public static List<RouteNode> routeToHorizontalDistributionLines(Net clk, RouteNode vroute, List<ClockRegion> clockRegions, boolean down) {
        boolean verbose = false;
        RouteNode centroidDistNode = UltraScaleClockRouting.transitionCentroidToVerticalDistributionLine(clk, vroute, down);
        if (verbose) System.out.println(" transition distribution node is \n \t = " + centroidDistNode);

        if (centroidDistNode == null) return null;

        Map<ClockRegion, RouteNode> vertDistLines = routeCentroidToVerticalDistributionLines(clk, centroidDistNode, clockRegions);
        if (verbose) {
            System.out.println(" clock region - vertical distribution node ");
            for (ClockRegion cr : vertDistLines.keySet()) System.out.println(" \t" + cr + " \t " + vertDistLines.get(cr));
        }

        List<RouteNode> distLines = new ArrayList<>();
        distLines.addAll(routeCentroidToHorizontalDistributionLines(clk, centroidDistNode, vertDistLines));
        if (verbose) System.out.println(" dist lines are \n \t" + distLines);

        return distLines;
    }

    /**
     * Routes a partially routed clock that already has its horizontal distribution lines routed.
     * It will examine the clock net for SitePinInsts and assumes any present are already routed. It
     * then invokes {@link DesignTools#createMissingSitePinInsts(Design, Net)} to discover those not
     * yet routed.
     * @param design  The current design
     * @param clkNet The partially routed clock net to make fully routed
     */
    public static void incrementalClockRouter(Design design, Net clkNet) {
        // Assume all existing site pins are already routed
        for (SitePinInst pin : clkNet.getSinkPins()) {
            pin.setRouted(true);
        }
        // Find any missing site pins, to be used as target, routable sinks
        DesignTools.createMissingSitePinInsts(design, clkNet);

        // Find all horizontal distribution lines to be used as starting points and create a map
        // lookup by clock region
        Map<ClockRegion,Set<RouteNode>> startingPoints = new HashMap<>();
        for (PIP p : clkNet.getPIPs()) {
            for (Node node : new Node[] {p.getStartNode(), p.getEndNode()}) {
                if (node == null) continue;
                if (node.getIntentCode() != IntentCode.NODE_GLOBAL_HDISTR) continue;
                for (Wire w : node.getAllWiresInNode()) {
                    RouteNode rn = new RouteNode(w.getTile(), w.getWireIndex());
                    Set<RouteNode> crNodes = startingPoints.computeIfAbsent(w.getTile().getClockRegion(), n -> new HashSet<>());
                    crNodes.add(rn);
                }
            }
        }

        // Find the target leaf clock buffers (LCBs), route from horizontal dist lines to those
        Map<RouteNode, ArrayList<SitePinInst>> lcbMappings = GlobalSignalRouting.getLCBPinMappings(clkNet);
        UltraScaleClockRouting.routeToLCBs(clkNet, startingPoints, lcbMappings.keySet());
        // Last mile routing from LCBs to SLICEs
        UltraScaleClockRouting.routeLCBsToSinks(clkNet, lcbMappings);

        // Remove duplicates
        Set<PIP> uniquePIPs = new HashSet<>();
        uniquePIPs.addAll(clkNet.getPIPs());
        clkNet.setPIPs(uniquePIPs);
    }
}
