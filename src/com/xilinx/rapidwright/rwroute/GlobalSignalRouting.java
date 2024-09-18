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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;

/**
 * A collection of methods for routing global signals, i.e. GLOBAL_CLOCK, VCC and GND.
 * Adapted from RapidWright APIs.
 */
public class GlobalSignalRouting {
    private static final HashSet<String> lutOutputPinNames;
    static {
        lutOutputPinNames = new HashSet<>();
        for (String cle : new String[]{"L", "M"}) {
            for (String pin : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}) {
                lutOutputPinNames.add("CLE_CLE_" + cle + "_SITE_0_" + pin + "_O");
                lutOutputPinNames.add("CLE_CLE_" + cle + "_SITE_0_" + pin + "MUX");
            }
        }
    }

    /**
     * Routes a clk enable net with input data.
     * @param clk The net to be routed.
     * @param routesToSinkINTTiles A map storing routes from CLK_OUT to different INT tiles that
     * connect to sink pins of a global clock net.
     * @param device The target device needed to get routing path representation with nodes from names.
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void routeClkWithPartialRoutes(Net clk,
                                                 Map<String, List<String>> routesToSinkINTTiles,
                                                 Device device,
                                                 Function<Node, NodeStatus> getNodeStatus) {
        Map<String, List<Node>> dstINTtilePaths = getListOfNodesFromRoutes(device, routesToSinkINTTiles);
        // Not import path after HDSTR
        Set<PIP> clkPIPs = new HashSet<>();
        Map<String, RouteNode> horDistributionLines = new HashMap<>();

        for (List<Node> nodes : dstINTtilePaths.values()) {
            clkPIPs.addAll(RouterHelper.getPIPsFromNodes(nodes));

            Node hDistr = nodes.get(nodes.size() - 1);
            RouteNode hdistr = new RouteNode(hDistr);
            horDistributionLines.put(getDominateClockRegionOfNode(hDistr), hdistr);
        }
        clk.setPIPs(clkPIPs);

        Map<RouteNode, List<SitePinInst>> lcbMappings = getLCBPinMappings(clk.getPins(), getNodeStatus);

        UltraScaleClockRouting.routeToLCBs(clk, getStartingPoint(horDistributionLines, device), lcbMappings.keySet());

        // route LCBs to sink pins
        UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings, getNodeStatus);

        Set<PIP> clkPIPsWithoutDuplication = new HashSet<>(clk.getPIPs());
        clk.setPIPs(clkPIPsWithoutDuplication);
    }

    private static Map<ClockRegion, Set<RouteNode>> getStartingPoint(Map<String, RouteNode> crDistLines, Device dev) {
        Map<ClockRegion, Set<RouteNode>> startingPoints = new HashMap<>();
        for (Entry<String, RouteNode> crRouteNode : crDistLines.entrySet()) {
            String crName = crRouteNode.getKey();
            ClockRegion cr = dev.getClockRegion(crName);
            startingPoints.computeIfAbsent(cr, (k) -> new HashSet<>()).add(crRouteNode.getValue());
        }
        return startingPoints;
    }

    private static String getDominateClockRegionOfNode(Node node) {
        // This is needed because a HDISTR for clock region X3Y2 can have a base tile in clock region X2Y2,
        // observed with clock routing of the optical-flow design.
        Map<String, Integer> crCounts = new HashMap<>();
        for (Wire wire : node.getAllWiresInNode()) {
            ClockRegion cr = wire.getTile().getClockRegion();
            if (cr == null) {
                continue;
            }
            crCounts.merge(cr.getName(), 1, Integer::sum);
        }

        String dominate = null;
        int max = 0;
        for (Entry<String, Integer> crCount : crCounts.entrySet()) {
            String cr = crCount.getKey();
            Integer count = crCount.getValue();
            if (count > max) {
                max = count;
                dominate = cr;
            }
        }

        return dominate;
    }

    /**
     * Gets a list of nodes for each destination, e.g. each clock region or sink INT tile, based on a list of the node names.
     * @param device The target device.
     * @param routes The given routes consisting of node names.
     * @return A map storing a list of nodes for each destination.
     */
    private static Map<String, List<Node>> getListOfNodesFromRoutes(Device device, Map<String, List<String>> routes) {
        Map<String, List<Node>> dstPaths = new HashMap<>();
        for (Entry<String, List<String>> dstRoute : routes.entrySet()) {
            String dst = dstRoute.getKey();
            List<Node> pathNodes = new ArrayList<>();
            for (String nodeName : dstRoute.getValue()) {
                Node node = Node.getNode(nodeName, device);
                if (node != null) {
                    pathNodes.add(node);
                } else {
                    System.err.println("ERROR: Null Node found under name: " + nodeName);
                }
            }
            dstPaths.put(dst, pathNodes);
        }
        return dstPaths;
    }

    /**
     * Routes a clock net by dividing the target clock regions into two groups and routes to the two groups with different centroid nodes.
     * @param clk The clock to be routed.
     * @param device The design device.
     * @param getNodeStatus Lambda for indicating the status of a Node: available, in-use (preserved
     *                      for same net as we're routing), or unavailable (preserved for other net).
     */
    public static void symmetricClkRouting(Net clk, Device device, Function<Node,NodeStatus> getNodeStatus) {
        List<ClockRegion> clockRegions = getClockRegionsOfNet(clk);
        ClockRegion centroid = findCentroid(clk, device);

        List<ClockRegion> upClockRegions = new ArrayList<>();
        List<ClockRegion> downClockRegions = new ArrayList<>();
        // divides clock regions into two groups
        divideClockRegions(clockRegions, centroid, upClockRegions, downClockRegions);

        RouteNode clkRoutingLine = UltraScaleClockRouting.routeBUFGToNearestRoutingTrack(clk);// first HROUTE
        RouteNode centroidHRouteNode = UltraScaleClockRouting.routeToCentroid(clk, clkRoutingLine, centroid, true, true);

        RouteNode vrouteUp = null;
        RouteNode vrouteDown;
        // Two VROUTEs going up and down
        ClockRegion aboveCentroid = upClockRegions.isEmpty() ? null : centroid.getNeighborClockRegion(1, 0);
        if (aboveCentroid != null) {
            vrouteUp = UltraScaleClockRouting.routeToCentroid(clk, centroidHRouteNode, aboveCentroid, true, false);
        }
        vrouteDown = UltraScaleClockRouting.routeToCentroid(clk, centroidHRouteNode, centroid.getNeighborClockRegion(0, 0), true, false);

        List<RouteNode> upDownDistLines = new ArrayList<>();
        if (aboveCentroid != null) {
            List<RouteNode> upLines = UltraScaleClockRouting.routeToHorizontalDistributionLines(clk, vrouteUp, upClockRegions, false, getNodeStatus);
            if (upLines != null) upDownDistLines.addAll(upLines);
        }

        List<RouteNode> downLines = UltraScaleClockRouting.routeToHorizontalDistributionLines(clk, vrouteDown, downClockRegions, true, getNodeStatus);//TODO this is where the antenna node shows up
        if (downLines != null) upDownDistLines.addAll(downLines);

        Map<RouteNode, List<SitePinInst>> lcbMappings = getLCBPinMappings(clk.getPins(), getNodeStatus);
        UltraScaleClockRouting.routeDistributionToLCBs(clk, upDownDistLines, lcbMappings.keySet());

        UltraScaleClockRouting.routeLCBsToSinks(clk, lcbMappings, getNodeStatus);

        Set<PIP> clkPIPsWithoutDuplication = new HashSet<>(clk.getPIPs());
        clk.setPIPs(clkPIPsWithoutDuplication);
    }

    /**
     * Gets clock regions of a net's sink pins.
     * @param clk The net in question.
     * @return A list of clock regions of the net's sink pins.
     */
    private static List<ClockRegion> getClockRegionsOfNet(Net clk) {
        List<ClockRegion> clockRegions = new ArrayList<>();
        for (SitePinInst pin : clk.getPins()) {
            if (pin.isOutPin()) continue;
            Tile t = pin.getTile();
            ClockRegion cr = t.getClockRegion();
            if (!clockRegions.contains(cr)) clockRegions.add(cr);
        }
        return clockRegions;
    }

    private static void divideClockRegions(List<ClockRegion> clockRegions, ClockRegion centroid, List<ClockRegion> upClockRegions,
            List<ClockRegion> downClockRegions) {
        for (ClockRegion cr : clockRegions) {
            if (cr.getInstanceY() > centroid.getInstanceY()) {
                upClockRegions.add(cr);
            } else {
                downClockRegions.add(cr);
            }
        }
    }

    /**
     * Maps each sink SitePinInsts of a clock net to a leaf clock buffer node.
     * @param clkPins List of clock pins in question.
     * @return A map between leaf clock buffer nodes and sink SitePinInsts.
     */
    public static Map<RouteNode, List<SitePinInst>> getLCBPinMappings(List<SitePinInst> clkPins,
                                                                      Function<Node,NodeStatus> getNodeStatus) {
        Map<RouteNode, List<SitePinInst>> lcbMappings = new HashMap<>();
        List<Node> lcbCandidates = new ArrayList<>();
        Set<Node> usedLcbs = new HashSet<>();
        for (SitePinInst p : clkPins) {
            if (p.isOutPin()) continue;
            assert(lcbCandidates.isEmpty());
            List<Node> intNodes = RouterHelper.projectInputPinToINTNode(p);
            if (intNodes == null || intNodes.isEmpty()) {
                throw new RuntimeException("Unable to get INT tile for pin " + p);
            }
            Node intNode = intNodes.get(0);

            outer: for (Node prev : intNode.getAllUphillNodes()) {
                NodeStatus prevNodeStatus = getNodeStatus.apply(prev);
                if (prevNodeStatus == NodeStatus.UNAVAILABLE) {
                    continue;
                }

                for (Node prevPrev : prev.getAllUphillNodes()) {
                    if (prevPrev.getIntentCode() != IntentCode.NODE_GLOBAL_LEAF) {
                        continue;
                    }

                    NodeStatus prevPrevNodeStatus = getNodeStatus.apply(prevPrev);
                    if (prevPrevNodeStatus == NodeStatus.UNAVAILABLE) {
                        continue;
                    }

                    if (usedLcbs.contains(prevPrev) || prevPrevNodeStatus == NodeStatus.INUSE) {
                        lcbCandidates.clear();
                        lcbCandidates.add(prevPrev);
                        break outer;
                    }

                    assert(prevPrevNodeStatus == NodeStatus.AVAILABLE);
                    lcbCandidates.add(prevPrev);
                }
            }

            if (lcbCandidates.isEmpty()) {
                throw new RuntimeException("ERROR: No mapped LCB to SitePinInst " + p);
            }
            Node n = lcbCandidates.get(0);
            RouteNode rn = new RouteNode(n.getTile(), n.getWireIndex());
            lcbMappings.computeIfAbsent(rn, (k) -> new ArrayList<>()).add(p);
            usedLcbs.add(n);
            lcbCandidates.clear();
        }

        return lcbMappings;
    }

    /**
     * Finds the centroid clock region of a clock net.
     * @param clk The clock net of a design.
     * @param device The device of the design.
     * @return The centroid clock region of a clock net.
     */
    private static ClockRegion findCentroid(Net clk, Device device) {
        HashSet<Point> sitePinInstTilePoints = new HashSet<>();
        for (SitePinInst spi : clk.getPins()) {
            if (spi.isOutPin()) continue;
            ClockRegion c = spi.getTile().getClockRegion();
            sitePinInstTilePoints.add(new Point(c.getColumn(),c.getRow()));
        }
        Point center = SmallestEnclosingCircle.getCenterPoint(sitePinInstTilePoints);
        return device.getClockRegion(center.y, center.x);
    }

    /**
     * Routes a static net (GND or VCC).
     * @param currNet The current static net to be routed.
     * @param getNodeState Lambda to get a node's status (available, unavailable, already in-use).
     * @param design The {@link Design} instance to use.
     * @param routeThruHelper The {@link RouteThruHelper} instance to use.
     */
    public static void routeStaticNet(Net currNet,
                                      Function<Node,NodeStatus> getNodeState,
                                      Design design, RouteThruHelper routeThruHelper) {
        NetType netType = currNet.getType();
        Set<PIP> netPIPs = new HashSet<>(currNet.getPIPs());
        Queue<Node> q = new ArrayDeque<>();
        Set<Node> usedRoutingNodes = new HashSet<>();
        Map<Node, Node> prevNode = new HashMap<>();
        List<Node> pathNodes = new ArrayList<>();
        Set<SitePin> sitePinsToCreate = new HashSet<>();
        final Node INVALID_NODE = new Node(null, Integer.MAX_VALUE);
        assert(INVALID_NODE.isInvalidNode());
        for (SitePinInst sink : currNet.getPins()) {
            if (sink.isRouted() || sink.isOutPin()) {
                continue;
            }
            int watchdog = 10000;
            Node node = sink.getConnectedNode();
            if (usedRoutingNodes.contains(node)) {
                sink.setRouted(true);
            } else {
                assert(prevNode.isEmpty());
                prevNode.put(node, INVALID_NODE);
                assert(q.isEmpty());
                q.add(node);
                search: while ((node = q.poll()) != null) {
                    assert(!usedRoutingNodes.contains(node));
                    assert(!(netType == NetType.VCC && node.isTiedToVcc()) || (netType == NetType.GND && node.isTiedToGnd()));

                    if (isThisOurStaticSource(design, node, netType)) {
                        break;
                    }

                    for (Node uphillNode : node.getAllUphillNodes()) {
                        if (routeThruHelper.isRouteThru(uphillNode, node)) {
                            continue;
                        }

                        switch(uphillNode.getIntentCode()) {
                            case NODE_GLOBAL_VDISTR:
                            case NODE_GLOBAL_HROUTE:
                            case NODE_GLOBAL_HDISTR:
                            case NODE_HLONG:
                            case NODE_VLONG:
                            case NODE_GLOBAL_VROUTE:
                            case NODE_GLOBAL_LEAF:
                            case NODE_GLOBAL_BUFG:
                                continue;
                        }

                        if (prevNode.putIfAbsent(uphillNode, node) != null) {
                            continue;
                        }

                        NodeStatus status = getNodeState.apply(uphillNode);
                        if (status == NodeStatus.UNAVAILABLE) {
                            continue;
                        }

                        if (status == NodeStatus.INUSE ||
                            (netType == NetType.VCC && uphillNode.isTiedToVcc()) ||
                            (netType == NetType.GND && uphillNode.isTiedToGnd()) ||
                            usedRoutingNodes.contains(uphillNode)) {
                            usedRoutingNodes.add(uphillNode);
                            pathNodes.add(uphillNode);
                            break search;
                        }

                        q.add(uphillNode);
                    }
                    watchdog--;
                    if (watchdog < 0) {
                        break;
                    }
                }
                if (node == null) {
                    System.err.println("ERROR: Failed to route " + currNet.getName() + " pin " + sink);
                } else {
                    // trace back for a complete path
                    do {
                        usedRoutingNodes.add(node);
                        pathNodes.add(node);
                        node = prevNode.get(node);
                    } while (node != INVALID_NODE);

                    // Note that the static net router goes backward from sinks to sources,
                    // requiring the srcToSinkOrder parameter to be set to true below
                    netPIPs.addAll(RouterHelper.getPIPsFromNodes(pathNodes, true));

                    // If the source is an output site pin, put it aside for consideration
                    // to add as a new source pin
                    Node sourceNode = pathNodes.get(0);
                    if (((netType == NetType.GND && !sourceNode.isTiedToGnd()) ||
                         (netType == NetType.VCC && !sourceNode.isTiedToVcc()))) {
                        SitePin sitePin = sourceNode.getSitePin();
                        if (sitePin != null && !sitePin.isInput()) {
                            sitePinsToCreate.add(sitePin);
                        }
                    }
                    pathNodes.clear();

                    sink.setRouted(true);
                }
                q.clear();
                prevNode.clear();
            }
        }

        for (SitePin sitePin : sitePinsToCreate) {
            Site site = sitePin.getSite();
            SiteInst si = design.getSiteInstFromSite(site);
            String pinName = sitePin.getPinName();
            if (si == null) {
                // Create a dummy TIEOFF SiteInst
                String name = SiteInst.STATIC_SOURCE + "_" + site.getName();
                si = new SiteInst(name, site.getSiteTypeEnum());
                si.place(site);
                // Ensure it is not attached to the design
                assert (si.getDesign() == null);
            } else {
                SitePinInst spi = si.getSitePinInst(pinName);
                if (spi != null) {
                    if (spi.getNet() == currNet) {
                        continue;
                    }
                    throw new RuntimeException("ERROR: Site pin " + spi.getSitePinName() + " cannot be attached to " +
                            "net '" + currNet.getName() + "' as it's already connected to " +
                            "net '" + spi.getNet().getName() + "'");
                }
            }
            SitePinInst spi = new SitePinInst(pinName, si);
            boolean updateSiteRouting = false;
            currNet.addPin(spi, updateSiteRouting);
            spi.setRouted(true);
        }

        currNet.setPIPs(netPIPs);
    }

    /**
     * Determines if the given {@link Node} instance can serve as our sink.
     * @param node The {@link Node} instance in question.
     * @param type The net type to designate the static source type.
     * @return true if this sources is usable, false otherwise.
     */
    private static boolean isThisOurStaticSource(Design design,
                                                 Node node,
                                                 NetType type) {
        // Look for unused LUT Outputs ([A-H]_O, [A-H]MUX)
        if ((type == NetType.VCC && node.isTiedToVcc()) ||
            (type == NetType.GND && node.isTiedToGnd())) {
            return true;
        }
        String wireName = node.getWireName();
        if (lutOutputPinNames.contains(wireName)) {
            Site[] sites = node.getTile().getSites();
            assert(sites.length == 1);
            Site slice = sites[0];
            SiteInst si = design.getSiteInstFromSite(slice);
            if (si == null) {
                // Site is not used
                return true;
            }

            String sitePinName;
            if (wireName.endsWith("_O")) {
                sitePinName = wireName.substring(wireName.length() - 3);
            } else if (wireName.endsWith("MUX")) {
                char lutLetter = wireName.charAt(wireName.length() - 4);
                Net o6Net = si.getNetFromSiteWire(lutLetter + "_O");
                if (o6Net != null && o6Net.getType() != type) {
                    // 6LUT is occupied; play it safe and do not consider fracturing as that can require modifying the intra-site routing
                    return false;
                }

                Net o5Net = si.getNetFromSiteWire(lutLetter + "5LUT_O5");
                if (o5Net != null && o5Net.getType() != type) {
                    // 5LUT is occupied
                    return false;
                }

                sitePinName = wireName.substring(wireName.length() - 4);
            } else {
                throw new RuntimeException(wireName);
            }

            Net sitePinNet = si.getNetFromSiteWire(sitePinName);
            return sitePinNet == null || sitePinNet.getType() == type;
        }
        return false;
    }

}
