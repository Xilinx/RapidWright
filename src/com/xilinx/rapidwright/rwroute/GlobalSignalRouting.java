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
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;
import com.xilinx.rapidwright.util.Utils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
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
            for (char pin : LUTTools.lutLetters) {
                // UltraScale/UltraScale+
                lutOutputPinNames.add("CLE_CLE_" + cle + "_SITE_0_" + pin + "_O");
                lutOutputPinNames.add("CLE_CLE_" + cle + "_SITE_0_" + pin + "MUX");
                // Versal
                for (int siteIndex = 0; siteIndex < 2; siteIndex++) {
                    lutOutputPinNames.add("CLE_SLICE" + cle + "_TOP_" + siteIndex + "_" + pin + "_O_PIN");
                    lutOutputPinNames.add("CLE_SLICE" + cle + "_TOP_" + siteIndex + "_" + pin + "Q_PIN");
                    // Q2 pin cannot be used otherwise leads to "Invalid Programming for Site"
                }
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

        // VCC wires are not expected to leave its tile
        EnumSet<IntentCode> assertIntentCodeOfPoppedNodesOnVcc;
        Series series = design.getDevice().getSeries();
        assertIntentCodeOfPoppedNodesOnVcc = EnumSet.of(
                IntentCode.NODE_PINFEED,
                IntentCode.NODE_PINBOUNCE,
                IntentCode.INTENT_DEFAULT);
        boolean isVersal = false;
        if (series == Series.UltraScale) {
            // On UltraScale, certain site pins (e.g. SLICE/CKEN_B1[1-4], SLICE/SRST_B[12])
            // do not have an uphill PIP to VCC_WIRE (instead they have one to GND_WIRE, which
            // Vivado itself chooses not to use)

            // INT_NODE_GLOBAL_\d+_OUT[01], uphill of CTRL_[EW]_B[0-9]
            // corresponding to CKEN_B[1-4] and SRST_B[12] site pins
            assertIntentCodeOfPoppedNodesOnVcc.add(IntentCode.NODE_LOCAL);
            // INT_INT_SINGLE_\d+_INT_OUT uphill of INT_NODE_GLOBAL_\d+_OUT[01]
            assertIntentCodeOfPoppedNodesOnVcc.add(IntentCode.NODE_SINGLE);
        } else if (series == Series.UltraScalePlus) {
            // No new intent codes
        } else if (series == Series.Versal) {
            isVersal = true;
            assertIntentCodeOfPoppedNodesOnVcc.add(IntentCode.NODE_IMUX);
            assertIntentCodeOfPoppedNodesOnVcc.add(IntentCode.NODE_CLE_CTRL);
            assertIntentCodeOfPoppedNodesOnVcc.add(IntentCode.NODE_INTF_CTRL);
            assertIntentCodeOfPoppedNodesOnVcc.add(IntentCode.NODE_IRI);
        } else {
            throw new RuntimeException("ERROR: Unsupported series " + series);
        }

        // Collect all node-sink pairs to be routed
        Map<Node,SitePinInst> nodeToRouteToSink = new HashMap<>();
        for (SitePinInst sink : currNet.getPins()) {
            if (sink.isRouted() || sink.isOutPin()) {
                continue;
            }
            nodeToRouteToSink.put(sink.getConnectedNode(), sink);
        }

        // Sort them by their tile, ensuring that sinks in the same tile are routed in sequence
        // to maximize sharing
        List<Node> nodesToRoute = new ArrayList<>(nodeToRouteToSink.keySet());
        nodesToRoute.sort(Comparator.comparing((n) -> n.getTile().getUniqueAddress()));

        for (Node node : nodesToRoute) {
            int watchdog = 10000;
            SitePinInst sink = nodeToRouteToSink.get(node);
            if (usedRoutingNodes.contains(node)) {
                sink.setRouted(true);
            } else {
                assert(prevNode.isEmpty());
                // Use an invalid node as the sink's prev node, as that's what we'll be looking for
                // during trace-back. This is necessary because `null` cannot be used since `null`
                // is what Map uses internally to indicate key is not present.
                prevNode.put(node, INVALID_NODE);
                assert(q.isEmpty());
                q.add(node);
                search: while ((node = q.poll()) != null) {
                    assert(!usedRoutingNodes.contains(node));
                    assert(!node.isTied());
                    IntentCode intentCode = node.getIntentCode();
                    assert(netType != NetType.VCC || assertIntentCodeOfPoppedNodesOnVcc.contains(intentCode));

                    SitePin sitePin = getStaticSourceSitePin(design, node, netType);
                    if (sitePin != null) {
                        // Unused LUT source found, terminate search
                        sitePinsToCreate.add(sitePin);
                        break;
                    }

                    TileTypeEnum tileTypeEnum = node.getTile().getTileTypeEnum();
                    // On Versal, only allow IRI -> BLI_CLE_BOT_CORE* routethrus
                    boolean notIriRoutethru = !isVersal ||
                            (intentCode != IntentCode.NODE_IRI &&
                            tileTypeEnum != TileTypeEnum.BLI_CLE_BOT_CORE &&
                            tileTypeEnum != TileTypeEnum.BLI_CLE_BOT_CORE_MY);
                    for (Node uphillNode : node.getAllUphillNodes()) {
                        if (routeThruHelper.isRouteThru(uphillNode, node) && notIriRoutethru) {
                            continue;
                        }

                        IntentCode uphillIntentCode = uphillNode.getIntentCode();
                        if (uphillIntentCode == IntentCode.NODE_CLE_CNODE && intentCode != IntentCode.NODE_CLE_CTRL) {
                            assert(isVersal);
                            // Only allow PIPs from NODE_CLE_CNODE to NODE_CLE_CTRL intent codes
                            // (NODE_CLE_NODEs can also be used to re-enter the INT tile --- do not allow this
                            // so that these precious resources are not consumed by the static router thereby
                            // blocking the signal router from using them)
                            continue;
                        }

                        switch(uphillIntentCode) {
                            case NODE_GLOBAL_VDISTR:
                            case NODE_GLOBAL_HROUTE:
                            case NODE_GLOBAL_HDISTR:
                            case NODE_HLONG:
                            case NODE_VLONG:
                            case NODE_GLOBAL_VROUTE:
                            case NODE_GLOBAL_LEAF:
                            case NODE_GLOBAL_BUFG:
                                continue;
                            // Versal
                            case NODE_HLONG6:
                            case NODE_HLONG10:
                            case NODE_VLONG7:
                            case NODE_VLONG12:
                                continue;

                            // VCC net should never need to use S/D/Q nodes ...
                            case NODE_SINGLE:
                            case NODE_DOUBLE:
                            case NODE_HQUAD:
                            case NODE_VQUAD:
                            // Versal
                            case NODE_HSINGLE:
                            case NODE_VSINGLE:
                            case NODE_HDOUBLE:
                            case NODE_VDOUBLE:
                                if (netType == NetType.VCC) {
                                    assert(series == Series.UltraScale);
                                    if (uphillIntentCode == IntentCode.NODE_SINGLE) {
                                        // ... except for UltraScale where certain site pins have no direct connection to VCC_WIRE
                                        // and even then, only consider INT_INT_SINGLE_\d+_INT_OUT "singles" that stay within the
                                        // same tile
                                        if (uphillNode.getAllWiresInNode().length > 1) {
                                            continue;
                                        }
                                        assert(uphillNode.getWireName().matches("INT_INT_SINGLE_\\d+_INT_OUT"));
                                        break;
                                    }
                                    continue;
                                }
                                break;
                        }

                        if (prevNode.putIfAbsent(uphillNode, node) != null) {
                            continue;
                        }

                        if (usedRoutingNodes.contains(uphillNode)) {
                            // uphillNode is known to be already part of this net's routing
                            node = uphillNode;
                            break search;
                        }

                        boolean tiedToVcc = uphillNode.isTiedToVcc();
                        boolean tiedToGnd = uphillNode.isTiedToGnd();
                        if (tiedToVcc || tiedToGnd) {
                            if ((netType == NetType.VCC && tiedToVcc) ||
                                (netType == NetType.GND && tiedToGnd)) {
                                // We've found the correct new VCC/GND source so terminate the search here
                                node = uphillNode;
                                break search;
                            }
                            // Wrong VCC/GND source, do not put in queue
                            continue;
                        }

                        NodeStatus status = getNodeState.apply(uphillNode);
                        if (status == NodeStatus.UNAVAILABLE) {
                            continue;
                        }
                        if (status == NodeStatus.INUSE) {
                            // uphillNode is just discovered to be already part of this net's routing
                            SitePinInst uphillSink = nodeToRouteToSink.get(uphillNode);
                            if (uphillSink == null || uphillSink.isRouted()) {
                                // uphillNode is not a sink to be routed, or is one that's already been routed, terminate
                                node = uphillNode;
                                break search;
                            }

                            // uphillNode must be a sink to be routed; preserve only the current routing, clear the queue,
                            // and restart routing from this new sink to encourage reuse
                            assert(!uphillSink.isRouted());
                            do {
                                usedRoutingNodes.add(node);
                                node = prevNode.get(node);
                            } while (node != INVALID_NODE);
                            prevNode.keySet().removeIf((n) -> !usedRoutingNodes.contains(n) && !n.equals(uphillNode));
                            q.clear();
                            q.add(uphillNode);
                            continue search;
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

                    pathNodes.clear();
                    sink.setRouted(true);
                }
                assert(pathNodes.isEmpty());
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
     * @return {@link SitePin} if a valid source is found, null otherwise.
     */
    private static SitePin getStaticSourceSitePin(Design design,
                                                  Node node,
                                                  NetType type) {
        if (node.getIntentCode() != IntentCode.NODE_CLE_OUTPUT) {
            return null;
        }

        // Look for unused LUT Outputs ([A-H]_O, [A-H]MUX)
        String wireName = node.getWireName();
        if (!lutOutputPinNames.contains(wireName)) {
            return null;
        }

        Tile tile = node.getTile();
        assert(Utils.isCLB(tile.getTileTypeEnum()));
        Site[] sites = tile.getSites();
        boolean isVersal = design.getDevice().getSeries() == Series.Versal;
        int siteIndex;
        if (isVersal) {
            assert(sites.length == 2);
            // Site index is in wire name: e.g. CLE_SLICEL_TOP_0_A_O_PIN
            siteIndex = wireName.charAt(15) - '0';
        } else {
            assert(sites.length == 1);
            siteIndex = 0;
        }
        Site slice = sites[siteIndex];
        SiteInst si = design.getSiteInstFromSite(slice);

        String sitePinName;
        if (isVersal) {
            sitePinName = wireName.substring(17, wireName.length() - 4);

            // For [A-H]Q only
            if (si != null && sitePinName.endsWith("Q")) {
                char lutLetter = sitePinName.charAt(0);
                Net o6Net = si.getNetFromSiteWire(lutLetter + "_O");
                if (o6Net != null && o6Net.getType() != type) {
                    // 6LUT is occupied
                    return null;
                }
            }
        } else {
            if (wireName.endsWith("_O")) {
                sitePinName = wireName.substring(wireName.length() - 3);
            } else if (wireName.endsWith("MUX")) {
                sitePinName = wireName.substring(wireName.length() - 4);

                if (si != null) {
                    char lutLetter = sitePinName.charAt(0);
                    Net o6Net = si.getNetFromSiteWire(lutLetter + "_O");
                    if (o6Net != null && o6Net.getType() != type) {
                        // 6LUT is occupied; play it safe and do not consider fracturing as that can require modifying the intra-site routing
                        return null;
                    }

                    Net o5Net = si.getNetFromSiteWire(lutLetter + "5LUT_O5");
                    if (o5Net != null && o5Net.getType() != type) {
                        // 5LUT is occupied
                        return null;
                    }
                }
            } else {
                throw new RuntimeException(wireName);
            }
        }

        if (si != null) {
            Net sitePinNet = si.getNetFromSiteWire(sitePinName);
            if (sitePinNet != null && sitePinNet.getType() != type) {
                return null;
            }
        }

        return new SitePin(slice, sitePinName);
    }
}
