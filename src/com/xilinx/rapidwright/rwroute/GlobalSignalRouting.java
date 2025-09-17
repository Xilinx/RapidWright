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
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.placer.blockplacer.SmallestEnclosingCircle;
import com.xilinx.rapidwright.router.RouteNode;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.router.UltraScaleClockRouting;
import com.xilinx.rapidwright.router.VersalClockRouting;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.Utils;

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
     * Routes a clock net by dividing the target clock regions into two groups and
     * routes to the two groups with different centroid nodes.
     * 
     * @param clk           The clock to be routed.
     * @param device        The design device.
     * @param getNodeStatus Lambda for indicating the status of a Node: available,
     *                      in-use (preserved for same net as we're routing), or
     *                      unavailable (preserved for other net).
     * @param A             map to keep track of which routing tracks have already
     *                      been used for each clock region.
     */
    public static void symmetricClkRouting(Net clk, Device device,
            Function<Node, NodeStatus> getNodeStatus,
            Map<Integer, Set<ClockRegion>> usedRoutingTracks) {
        switch (device.getSeries()) {
            case UltraScale:
            case UltraScalePlus:
                symmetricClockRoutingUltraScales(clk, device, getNodeStatus);
                break;
            case Versal:
                symmetricClockRoutingVersal(clk, device, getNodeStatus, usedRoutingTracks);
                break;
            default:
                throw new RuntimeException("ERROR: GlobalSignalRouting.symmetricClkRouting() does not support the " + device.getSeries() + " series.");
        }

        Set<PIP> clkPIPsWithoutDuplication = new HashSet<>(clk.getPIPs());
        clk.setPIPs(clkPIPsWithoutDuplication);
    }

    private static void symmetricClockRoutingUltraScales(Net clk, Device device, Function<Node, NodeStatus> getNodeStatus) {
        // Clock routing on UltraScale/UltraScale+ devices
        assert(device.getSeries() == Series.UltraScale || device.getSeries() == Series.UltraScalePlus);

        Set<ClockRegion> clockRegions = getFabricClockRegionsOfNet(clk).getFirst();

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
    }

    private static void symmetricClockRoutingVersal(Net clk, Device device,
            Function<Node, NodeStatus> getNodeStatus,
            Map<Integer, Set<ClockRegion>> usedRoutingTracks) {
        // Clock routing on Versal devices
        assert(device.getSeries() == Series.Versal);

        Pair<Set<ClockRegion>, List<SitePinInst>> usedCRsAndNonLCBPinsTuple = getFabricClockRegionsOfNet(clk);
        SitePinInst source = clk.getSource();
        SiteTypeEnum sourceTypeEnum = source.getSiteTypeEnum();
        // In US/US+ clock routing, we use two VROUTE nodes to reach the clock regions above and below the centroid.
        // However, we can see that Vivado only uses one VROUTE node in the centroid clock region for Versal clock routing,
        // and reach the above and below clock regions by VDISTR nodes.

        // Identify invalid tracks to use by looking at used tracks and checking for
        // overlapping clock regions
        Set<Integer> unavailableTracks = new HashSet<Integer>();
        if (usedRoutingTracks != null) {
            for (ClockRegion cr : usedCRsAndNonLCBPinsTuple.getFirst()) {
                for (Entry<Integer, Set<ClockRegion>> e : usedRoutingTracks.entrySet()) {
                    if (e.getValue().contains(cr)) {
                        unavailableTracks.add(e.getKey());
                    }
                }
            }
        }

        ClockRegion centroid = findCentroid(clk, device);
        Node centroidHRouteNode;

        if (sourceTypeEnum == SiteTypeEnum.BUFG_FABRIC) {
            // These source sites are located in the middle of the device. The path from the output pin to VROUTE matches the following pattern:
            // NODE_GLOBAL_BUFG (the output node with a suffix "_O") ->
            // NODE_GLOBAL_BUFG (has a suffix "_O_PIN") ->
            // NODE_GLOBAL_GCLK ->
            // NODE_GLOBAL_VROUTE (located in the same clock region of the source site)

            // Notice that Vivado always uses the above VROUTE node, there is no need to find a centroid clock region to route to.
            centroidHRouteNode = source.getConnectedNode();
        } else if (sourceTypeEnum == SiteTypeEnum.BUFGCE) {
            // Assume that these source sites are located in the bottom of the device (Y=0).
            // The path from the output pin to VROUTE matches the following pattern:
            //   NODE_GLOBAL_BUFG -> NODE_GLOBAL_BUFG -> NODE_GLOBAL_GCLK ->  NODE_GLOBAL_HROUTE_HSR -> NODE_GLOBAL_VROUTE
            // which is similar to US/US+ clock routing.
            // Notice that we have to quickly reach a NODE_GLOBAL_HROUTE_HSR node, and if we allow the Y coordinate of centroid to be bigger than 1,
            // we may fail to do so. Thus, we need to force the Y-coordinate of centroid to be 1.
            Node clkRoutingLine = VersalClockRouting.routeBUFGToNearestRoutingTrack(clk, getNodeStatus);// first HROUTE
            centroidHRouteNode = VersalClockRouting.routeToCentroid(clk, clkRoutingLine, centroid,
                    true, getNodeStatus, unavailableTracks);
        } else if (sourceTypeEnum == SiteTypeEnum.BUFG_PS) {
            // These source sites are located in the middle of the device. The path from the
            // output pin to HROUTE matches the following pattern:
            // NODE_GLOBAL_BUFG (has a suffix "_O_PIN") ->
            // NODE_GLOBAL_BUFG (the output node with a suffix "_O") ->
            // NODE_GLOBAL_HROUTE (located in the same clock region)
            centroidHRouteNode = VersalClockRouting.routeBUFGToNearestRoutingTrack(clk, getNodeStatus);// first HROUTE
        } else {
            throw new RuntimeException("ERROR: Routing clock net with source type " + sourceTypeEnum + " not supported.");
        }

        // If the source and centroid are in the same row, we do not need to traverse vertical routing tracks
        boolean noVrouteNeeded = centroidHRouteNode.getTile().getClockRegion().getRow() == centroid.getRow();
        Node vroute = VersalClockRouting.routeToCentroid(clk, centroidHRouteNode, centroid,
                noVrouteNeeded, getNodeStatus, unavailableTracks);

        if (vroute == null) {
            // Try neighboring CR
            ClockRegion neighbor = centroid.getNeighborClockRegion(0, 1);
            centroid = neighbor == null ? centroid.getNeighborClockRegion(0, -1) : neighbor;
            vroute = VersalClockRouting.routeToCentroid(clk, centroidHRouteNode, centroid,
                    noVrouteNeeded, getNodeStatus, unavailableTracks);
            assert (vroute != null);
        }

        Map<ClockRegion, Node> upDownDistLines = VersalClockRouting
                .routeToHorizontalDistributionLines(clk, vroute, usedCRsAndNonLCBPinsTuple.getFirst(),
                        false, getNodeStatus);

        // Route non-LCB driven pins
        VersalClockRouting.routeNonLCBPins(clk, usedCRsAndNonLCBPinsTuple.getSecond(), getNodeStatus);

        Map<Node, List<SitePinInst>> lcbMappings = VersalClockRouting.routeLCBsToSinks(clk, getNodeStatus);
        VersalClockRouting.routeDistributionToLCBs(clk, upDownDistLines, lcbMappings.keySet(), getNodeStatus);

        // Populate used routing track for any other clocks being routed
        if (usedRoutingTracks != null) {
            Integer track = VersalClockRouting.getRoutingTrackIndex(vroute);
            if (track == null) {
                System.err.println("WARNING: Unable to identify clock track for " + clk);
            } else {
                Set<ClockRegion> collision = usedRoutingTracks.put(track,
                        new HashSet<>(usedCRsAndNonLCBPinsTuple.getFirst()));
                assert (collision == null);
            }
        }
    }

    /**
     * Gets clock regions of a net's sink pins.
     * @param clk The net in question.
     * @return A list of clock regions of the net's sink pins.
     */
    private static Pair<Set<ClockRegion>, List<SitePinInst>> getFabricClockRegionsOfNet(Net clk) {
        Set<ClockRegion> clockRegions = new HashSet<>();
        List<SitePinInst> offFabricClkSinks = new ArrayList<>();
        for (SitePinInst pin : clk.getPins()) {
            if (pin.isOutPin()) continue;
            Tile t = pin.getTile();
            ClockRegion cr = t.getClockRegion();
            if (Utils.isIOB(pin.getSiteInst())) {
                offFabricClkSinks.add(pin);
            } else if (Utils.isPS(pin.getSiteInst())) {
                // PS clock input will be driven by East CR neighbor
                clockRegions.add(cr.getNeighborClockRegion(0, 1));
            } else {
                clockRegions.add(cr);
            }
        }
        return new Pair<>(clockRegions, offFabricClkSinks);
    }

    private static void divideClockRegions(Set<ClockRegion> clockRegions, ClockRegion centroid, List<ClockRegion> upClockRegions,
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
            Node intNode = RouterHelper.projectInputPinToINTNode(p);
            if (intNode == null) {
                throw new RuntimeException("Unable to get INT tile for pin " + p);
            }

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

    private static boolean centroidEligible(ClockRegion candidate) {
        // Check if we can use this clock region
        Tile lowerLeft = candidate.getLowerLeft();
        Tile upperLeft = candidate.getUpperLeft();
        int tileHeight = 0;
        if (lowerLeft != null && upperLeft != null) {
            tileHeight = Math.abs(lowerLeft.getRow() - upperLeft.getRow());
        }
        return tileHeight > 40;
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
        ClockRegion centroid = device.getClockRegion(center.y, center.x);
        // Check if we can use this clock region
        if (!centroidEligible(centroid)) {
            // Snap to the closest legal clock region
            if (centroid.getRow() == 0) {
                centroid = centroid.getNeighborClockRegion(1, 0);
            } else if (centroid.getRow() == device.getNumOfClockRegionRows() - 1) {
                centroid = centroid.getNeighborClockRegion(-1, 0);
            }
        }
        return centroid;
    }

    /**
     * Routes pins from a static net (GND or VCC).
     * @param pins A list of static pins to be routed (must all be on the same net).
     * @param getNodeState Lambda to get a node's status (available, unavailable, already in-use).
     * @param design The {@link Design} instance to use.
     * @param routeThruHelper The {@link RouteThruHelper} instance to use.
     */
    public static void routeStaticNet(List<SitePinInst> pins,
                                      Function<Node,NodeStatus> getNodeState,
                                      Design design, RouteThruHelper routeThruHelper) {
        Queue<Node> q = new ArrayDeque<>();
        Set<Node> usedRoutingNodes = new HashSet<>();
        Map<Node, Node> prevNode = new HashMap<>();
        List<Node> pathNodes = new ArrayList<>();
        List<SitePin> sitePinsToCreate = new ArrayList<>();
        final Node INVALID_NODE = new Node(null, Integer.MAX_VALUE);
        assert(INVALID_NODE.isInvalidNode());

        // VCC wires are not expected to leave its tile
        EnumSet<IntentCode> assertIntentCodeOfPoppedNodesOnVcc;
        Series series = design.getDevice().getSeries();
        assertIntentCodeOfPoppedNodesOnVcc = EnumSet.of(
                IntentCode.NODE_PINFEED,
                IntentCode.NODE_PINBOUNCE,
                IntentCode.INTENT_DEFAULT,
                IntentCode.NODE_GLOBAL_GCLK // e.g. MMCM_CLKIN2, MMCM_CLKFBIN
        );
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
            assertIntentCodeOfPoppedNodesOnVcc.add(IntentCode.NODE_OPTDELAY); // e.g. INTF_PSS_TL_TILE_X15Y56/IF_COE_IMUX93 on vp1202
        } else {
            throw new RuntimeException("ERROR: Unsupported series " + series);
        }

        // Collect all node-sink pairs to be routed
        Net currNet = null;
        Map<Node,SitePinInst> nodeToRouteToSink = new HashMap<>();
        for (SitePinInst sink : pins) {
            if (currNet == null) {
                currNet = sink.getNet();
                assert(currNet != null);
            } else {
                assert(currNet == sink.getNet());
            }
            assert(!sink.isOutPin());
            if (sink.isRouted()) {
                continue;
            }

            Node node = sink.getConnectedNode();
            if (getNodeState.apply(node) != NodeStatus.INUSE) {
                throw new RuntimeException("ERROR: Site pin " + sink + " is not available for net " + currNet);
            } else if (node.getIntentCode() == IntentCode.NODE_DEDICATED) {
                // Skip dedicated nodes that don't reach the INT tile
                // e.g. XPIO_NIBBLE_SC_5_X0Y0/XPIO_IOBPAIR_5_IBUF_DISABLE_M_PIN on vp1202
                assert(isVersal);
                continue;
            }
            nodeToRouteToSink.put(node, sink);
        }

        // Sort them by their tile, ensuring that sinks in the same tile are routed in sequence
        // to maximize sharing
        List<Node> nodesToRoute = new ArrayList<>(nodeToRouteToSink.keySet());
        nodesToRoute.sort(Comparator.comparing((n) -> n.getTile().getUniqueAddress()));

        NetType netType = currNet.getType();
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
                    // On Versal, only allow IRI routethrus on BLI_CLE_BOT_CORE* tile types
                    boolean notIriRoutethru = !isVersal ||
                            (intentCode != IntentCode.NODE_IRI &&
                            tileTypeEnum != TileTypeEnum.BLI_CLE_BOT_CORE &&
                            tileTypeEnum != TileTypeEnum.BLI_CLE_BOT_CORE_MY);
                    for (Node uphillNode : node.getAllUphillNodes()) {
                        if (routeThruHelper.isRouteThru(uphillNode, node) && notIriRoutethru) {
                            continue;
                        }

                        IntentCode uphillIntentCode = uphillNode.getIntentCode();
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
                            case NODE_CLE_CNODE:
                                // Only allow PIPs from NODE_{CLE,INTF}_CNODE to NODE_{CLE,INTF}_CTRL intent codes
                                // (NODE_CLE_NODEs can also be used to re-enter the INT tile --- do not allow this
                                // so that these precious resources are not consumed by the static router thereby
                                // blocking the signal router from using them)
                                if (intentCode != IntentCode.NODE_CLE_CTRL) {
                                    continue;
                                }
                                break;
                            case NODE_INTF_CNODE:
                                if (intentCode != IntentCode.NODE_INTF_CTRL) {
                                    continue;
                                }
                                break;

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
                    for (PIP pip : RouterHelper.getPIPsFromNodes(pathNodes, true)) {
                        currNet.addPIP(pip);
                    }

                    pathNodes.clear();
                    sink.setRouted(true);
                }
                assert(pathNodes.isEmpty());
                q.clear();
                prevNode.clear();
            }
        }

        assert(sitePinsToCreate.stream().distinct().count() == sitePinsToCreate.size());
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
