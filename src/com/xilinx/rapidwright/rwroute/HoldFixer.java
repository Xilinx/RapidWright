/*
 *
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt
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
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetTools;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.ClockRegion;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import static com.xilinx.rapidwright.rwroute.RouterHelper.projectInputPinToINTNode;

public class HoldFixer {
    private final Design design;
    private final ClockRegion clockRoot;
    private long wirelength;
    private int numWireNetsToRoute;
    private int numConnectionsToRoute;
    private long usedNodes;
    private Map<IntentCode, Long> nodeTypeUsage ;
    private Map<IntentCode, Long> nodeTypeLength;
    private final RouteNodeGraph routingGraph;
    private Map<Connection, Long> connectionWireLengths;
    private final int MIN_WIRE_LENGTH = 300;
    private static final Set<SiteTypeEnum> VALID_CENTROID_SITE_TYPES =
            new HashSet<>(Arrays.asList(SiteTypeEnum.SLICEL, SiteTypeEnum.SLICEM));

    public HoldFixer(Design design, String clockNetName) {
        this.design = design;
        Node clockRootNode = NetTools.findClockRootVRoute(design.getNet(clockNetName));
        clockRoot = clockRootNode.getTile().getClockRegion();
        RWRouteConfig config = new RWRouteConfig(new String[0]);
        config.setTimingDriven(false);
        routingGraph = new RouteNodeGraph(design, config);
        wirelength = 0;
        usedNodes = 0;
        nodeTypeUsage = new HashMap<>();
        nodeTypeLength = new HashMap<>();
        connectionWireLengths = new HashMap<>();
    }

    private List<Connection> getConnectionsThatAreStillTooShort(List<Connection> connections) {
        List<Connection> newConnections = new ArrayList<>();
        for (Connection connection : connections) {
            if (!connection.getSink().isRouted()) {
                System.out.println("Failed to get net " + connection.getNet() + " to meet hold time");
                continue;
            }
            // Update wirelength
            NetWrapper netWrapper = createNetWrapper(connection.getNet());
            Map<SitePinInst, Pair<Node, Long>> sourceToSinkINTNodeWireLengths =
                    getSourceToSinkINTNodeWireLengths(netWrapper.getNet());
            long wirelength = sourceToSinkINTNodeWireLengths.get(connection.getSink()).getSecond();
            if (wirelength < MIN_WIRE_LENGTH) {
                newConnections.add(connection);
            }
        }
        System.out.println("Connections remaining to fix: " + newConnections.size());
        return newConnections;
    }

    private static class RouteThru {
        private final SiteInst siteInst;
        private final SitePinInst inPin;
        private final SitePinInst outPin;

        RouteThru(SiteInst siteInst, SitePinInst inPin, SitePinInst outPin) {
            this.siteInst = siteInst;
            this.inPin = inPin;
            this.outPin = outPin;
        }
    }

    private static RouteThru nextAvailRouteThru(Design design, Iterator<Site> itr) {
        while (itr.hasNext()) {
            Site curr = itr.next();
            SiteInst candidate = design.getSiteInstFromSite(curr);
            if (candidate == null) {
                SiteInst siteInst = design.createSiteInst(curr);
                SitePinInst a6 = new SitePinInst("A6", siteInst);
                SitePinInst ao = new SitePinInst("A_O", siteInst);
                return new RouteThru(siteInst, a6, ao);
            }
        }
        return null;
    }

    private void rerouteNets(List<Net> nets) {
        List<SitePinInst> pinsToRoute = new ArrayList<>();
        Map<Net, RouteThru> routeThruMap = new HashMap<>();
        Map<Net, Net> newNetMap = new HashMap<>();

        // Split physical nets to ensure routeThru
        for (Net net : nets) {
            net.unroute();
            List<Point> points = new ArrayList<>();
            for (SitePinInst pinInst : net.getPins()) {
                Tile t = pinInst.getTile();
                Point p = new Point(t.getColumn(), t.getRow());
                points.add(p);
            }

            Site centroid = ECOPlacementHelper.getCentroidOfPoints(design.getDevice(), points,
                    VALID_CENTROID_SITE_TYPES);
            Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(centroid).iterator();
            RouteThru routeThru = nextAvailRouteThru(design, siteItr);
            if (routeThru == null) {
                throw new RuntimeException("Failed to find valid route thru for net: " + net);
            }
            routeThruMap.put(net, routeThru);
            Net newNet = design.createNet(net.getName() + "_routeThru");
            newNet.addPin(routeThru.outPin, false);
            newNetMap.put(net, newNet);
            for (SitePinInst pinInst : net.getSinkPins()) {
                net.removePin(pinInst);
                newNet.addPin(pinInst, false);
            }
            net.addPin(routeThru.inPin);
            pinsToRoute.addAll(net.getPins());
            pinsToRoute.addAll(newNet.getPins());
        }

        // Create the PartialRouter
        RWRouteConfig config = new RWRouteConfig(new String[]{
                "--fixBoundingBox",
                "--useUTurnNodes",
                "--nonTimingDriven"});
        HoldFixRouter router = new HoldFixRouter(design, config, pinsToRoute);

        // Initialize router object
        router.initialize();

        // Routes the design
        router.route();

        // Merge physical nets to give final routes
        for (Net net : nets) {
            RouteThru routeThru = routeThruMap.get(net);
            SiteInst siteInst = routeThru.siteInst;
            Tile t = siteInst.getTile();
            int inIndex = routeThru.inPin.getConnectedWireIndex();
            int outIndex = routeThru.outPin.getConnectedWireIndex();
            PIP routeThruPIP = t.getPIP(inIndex, outIndex);
            net.addPIP(routeThruPIP);

            Net routeThruNet = newNetMap.get(net);
            Set<PIP> finalPIPs = new HashSet<>();
            finalPIPs.addAll(net.getPIPs());
            finalPIPs.addAll(routeThruNet.getPIPs());

            net.removePin(net.getSinkPins().get(0));
            for (SitePinInst p : routeThruNet.getSinkPins()) {
                routeThruNet.removePin(p);
                net.addPin(p);
            }

            for (PIP pip : finalPIPs) {
                net.addPIP(pip);
            }
            design.removeNet(routeThruNet);
        }
    }

    /**
     * Computes the wirelength and delay for each net and reports the total wirelength and critical path delay.
     */
    private void computeStatisticsAndReport() {
        nodeTypeUsage = new HashMap<>();
        nodeTypeLength = new HashMap<>();
        wirelength = 0;
        usedNodes = 0;
        computeNetsWirelength();

        System.out.println("\n");
        System.out.println("Total nodes: " + usedNodes);
        System.out.println("Total wirelength: " + wirelength);
        RWRoute.printNodeTypeUsageAndWirelength(true, nodeTypeUsage, nodeTypeLength, design.getSeries());

        PriorityQueue<Pair<Connection, Long>> maxHeap = new PriorityQueue<>((a, b) -> Long.compare(b.getSecond(), a.getSecond()));
        PriorityQueue<Pair<Connection, Long>> minHeap = new PriorityQueue<>(
                (a, b) -> {
                    if (a.getSecond().equals(b.getSecond())) {
                        return a.getFirst().getNet().getName().compareTo(b.getFirst().getNet().getName());
                    }
                    return Long.compare(a.getSecond(), b.getSecond());
                });

        for (Map.Entry<Connection, Long> entry : connectionWireLengths.entrySet()) {
            if (entry.getValue() != 0) {
                maxHeap.add(new Pair<>(entry.getKey(), entry.getValue()));
                if (couldHaveHoldViolation(entry.getKey().getSource(), entry.getKey().getSink())) {
                    minHeap.add(new Pair<>(entry.getKey(), entry.getValue()));
                }
            }
        }

        System.out.println("Setup Time: ");
        for (int i = 0; i < 10; i++) {
            Pair<Connection, Long> curr = maxHeap.poll();
            if (curr != null) {
                System.out.println(curr.getFirst().getNet() + ", " + curr.getSecond());
            }
        }

        System.out.println();
        System.out.println("Hold Time: ");
        for (int i = 0; i < 10; i++) {
            Pair<Connection, Long> curr = minHeap.poll();
            if (curr != null) {
                System.out.println(curr.getFirst().getNet() + ", " + curr.getFirst().getSource() + ", " + curr.getFirst().getSink() + ", " + curr.getSecond());
            }
        }
    }

    private void fixHoldViolations() {
        computeNetsWirelength();

        PriorityQueue<Pair<Connection, Long>> minHeap = new PriorityQueue<>(
                (a, b) -> {
                    if (a.getSecond().equals(b.getSecond())) {
                        return a.getFirst().getNet().getName().compareTo(b.getFirst().getNet().getName());
                    }
                    return Long.compare(a.getSecond(), b.getSecond());
                });

        for (Map.Entry<Connection, Long> entry : connectionWireLengths.entrySet()) {
            if (entry.getValue() != 0) {
                if (couldHaveHoldViolation(entry.getKey().getSource(), entry.getKey().getSink())) {
                    minHeap.add(new Pair<>(entry.getKey(), entry.getValue()));
                }
            }
        }

        List<Connection> connections = new ArrayList<>();
        while (!minHeap.isEmpty()) {
            Pair<Connection, Long> curr = minHeap.poll();
            if (curr != null) {
                if (curr.getSecond() >= MIN_WIRE_LENGTH) {
                    break;
                }
                connections.add(curr.getFirst());
            }
        }
        Set<Net> nets = new HashSet<>();
        for (Connection c : connections) {
            nets.add(c.getNet());
        }
        System.out.println("Connection count: " + connections.size());
        rerouteNets(new ArrayList<>(nets));
    }

    public static Tile getRealTile(Device dev, int initialRow, int initialCol) {
        for (int row = initialRow; row >= 0; row--) {
            Tile t = dev.getTile(row, initialCol);
            TileTypeEnum tt = t.getTileTypeEnum();
            if (tt.toString().contains("CLK_REBUF_VERT_SSIT")) {
                continue;
            }
            if (!tt.equals(TileTypeEnum.NULL)) {
                return t;
            }
        }
        throw new RuntimeException("Did not find base tile");
    }

    private boolean crossesWideColumn(SitePinInst sourcePin, SitePinInst sinkPin) {
        Site source = sourcePin.getSite();
        Site sink = sinkPin.getSite();
        Device device = source.getDevice();
        int sourceCol = source.getTile().getColumn();
        int sourceRow = source.getTile().getRow();
        int sinkCol = sink.getTile().getColumn();

        for (int i = sourceCol; i < sinkCol; i++) {
            Tile t = getRealTile(device, sourceRow, i);
            if (t.getName().contains("NOC")) {
                return true;
            }
            if (t.getName().contains("URAM")) {
                return true;
            }
        }

        return false;
    }

    private boolean couldHaveHoldViolation(SitePinInst sourcePin, SitePinInst sinkPin) {
        Site source = sourcePin.getSite();
        Site sink = sinkPin.getSite();
        boolean differentClockRegionColumns = source.getTile().getClockRegion().getColumn() != sink.getTile().getClockRegion().getColumn();
        boolean connectionFacingEast = source.getTile().getColumn() < sink.getTile().getColumn();
        boolean rightOfClockRoot = source.getTile().getClockRegion().getColumn() > clockRoot.getColumn();
        return differentClockRegionColumns && connectionFacingEast && rightOfClockRoot && !crossesWideColumn(sourcePin, sinkPin);
    }

    /**
     * Computes the wirelength for each net.
     */
    private void computeNetsWirelength() {
        connectionWireLengths = new HashMap<>();
        for (Net net : design.getNets()) {
            if (net.getType() != NetType.WIRE) continue;
            if (!RouterHelper.isRoutableNetWithSourceSinks(net)) continue;
            if (net.getSource().toString().contains("CLK")) continue;
            if (net.getSource().toString().contains("BUFG")) continue;
            NetWrapper netplus = createNetWrapper(net);
            for (Node node : RouterHelper.getNodesOfNet(net)) {
                if (RouteNodeGraph.isExcludedTile(node)) {
                    continue;
                }
                usedNodes++;
                int wl = RouteNode.getLength(node, routingGraph);
                wirelength += wl;
                RouterHelper.addNodeTypeLengthToMap(node, wl, nodeTypeUsage, nodeTypeLength);
            }
            setAccumulativeWireLengthOfEachNetNode(netplus);
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
            Node sinkINTNode = projectInputPinToINTNode(sink);
            if (sinkINTNode == null) {
                connection.setDirect(true);
            } else {
                if (sourceINTNode == null) {
                    sourceINTNode = RouterHelper.projectOutputPinToINTNode(source);
                }
                connection.setSourceRnode(routingGraph.getOrCreate(sourceINTNode, RouteNodeType.EXCLUSIVE_SOURCE));
                connection.setSinkRnode(routingGraph.getOrCreate(sinkINTNode));
                connection.setDirect(false);
            }
        }
        return netWrapper;
    }

    public long computeNodeWirelength(Node node) {
        int wirelength = RouteNode.getLength(node, routingGraph) * 10;
        if (wirelength == 0) {
            wirelength = 3;
        }
        return wirelength;
    }

    /**
     * Gets a map containing net wirelength for each sink pin paired with an INT tile node of a routed net.
     * @param net The target routed net.
     * @return The map containing net wirelength for each sink pin paired with an INT tile node of a routed net.
     */
    public Map<SitePinInst, Pair<Node,Long>> getSourceToSinkINTNodeWireLengths(Net net) {
        Map<Node, Long> wirelengthMap = new HashMap<>();
        Node sourceNode = net.getSource().getConnectedNode();
        Set<PIP> pips = new HashSet<>(net.getPIPs());
        Queue<Node> queue = new LinkedList<>();
        queue.add(sourceNode);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (PIP pip : node.getAllDownhillPIPs()) {
                if (!pips.contains(pip)) {
                    continue;
                }
                Node startNode = pip.getStartNode();
                long upstreamWirelength = wirelengthMap.getOrDefault(startNode, 0L);

                Node endNode = pip.getEndNode();
                long wirelength = 0;
                if (endNode.getTile().getTileTypeEnum() == TileTypeEnum.INT) {//device independent?
                    wirelength = computeNodeWirelength(endNode);
                }
                wirelengthMap.put(endNode, upstreamWirelength + wirelength);
                queue.add(endNode);
            }
        }

        Map<SitePinInst, Pair<Node,Long>> sinkNodeWirelength = new HashMap<>();
        for (SitePinInst sink : net.getSinkPins()) {
            Node sinkNode = sink.getConnectedNode();
            if (sinkNode.getTile().getTileTypeEnum() != TileTypeEnum.INT) {
                Node sinkINTNode = projectInputPinToINTNode(sink);
                if (sinkINTNode != null) {
                    sinkNode = sinkINTNode;
                } else {
                    // Must be a direct connection (e.g. COUT -> CIN)
                }
            }

            if (wirelengthMap.containsKey(sinkNode)) {
                long routeWirelength = wirelengthMap.get(sinkNode).longValue();
                sinkNodeWirelength.put(sink, new Pair<>(sinkNode,routeWirelength));
            } else {
                System.out.println("WARNING: net " + net.getName() + " not fully routed");
            }
        }

        return sinkNodeWirelength;
    }

    /**
     * Using PIPs to calculate and set accumulative delay for each used node of a routed net that is represented by a {@link NetWrapper} Object.
     * The delay of each node is the total route delay from the source to the node (inclusive).
     * @param netWrapper
     */
    private void setAccumulativeWireLengthOfEachNetNode(NetWrapper netWrapper) {
        Map<SitePinInst, Pair<Node,Long>> sourceToSinkINTNodeWireLengths =
                getSourceToSinkINTNodeWireLengths(netWrapper.getNet());

        for (Connection connection : netWrapper.getConnections()) {
            if (connection.isDirect()) {
                continue;
            }
            Pair<Node,Long> sinkINTNodeWireLengths = sourceToSinkINTNodeWireLengths.get(connection.getSink());
            if (sinkINTNodeWireLengths != null) {
                long connectionWirelength = sinkINTNodeWireLengths.getSecond();
                connectionWireLengths.put(connection, connectionWirelength);
            }
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("USAGE:\n <input.dcp> <output.dcp> <clock net name>");
            return;
        }
        Design design = Design.readCheckpoint(args[0]);

        DesignTools.makePhysNetNamesConsistent(design);
        DesignTools.createMissingSitePinInsts(design);
        HoldFixer holdFixer = new HoldFixer(design, args[2]);

        holdFixer.computeStatisticsAndReport();
        holdFixer.fixHoldViolations();
        design.writeCheckpoint(args[1]);
    }
}
