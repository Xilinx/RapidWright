/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util.route_status;

import static com.xilinx.rapidwright.util.route_status.NodeKeys.nodeKey;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isDrivenBySiteRouting;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isSitePinNode;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isSitePinUsedBy;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.isSiteRoutedOutputPinNode;
import static com.xilinx.rapidwright.util.route_status.SiteRouting.storedPins;
import static com.xilinx.rapidwright.util.route_status.VersalRules.isLeafClockClearPin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;

/**
 * The routing tree of a net as a graph of node keys, examined to find which sink pins are
 * reachable from its source pins (or for static nets, from tied nodes) through the net's PIPs,
 * and whether it has antennas or islands.
 */
final class RouteGraph {

    /** Sorted distinct keys of the nodes used by the PIPs */
    final long[] nodes;
    final Node[] nodeObjects;
    /** Fanouts of each node, in compressed sparse row form */
    final int[] fanoutStart;
    final int[] fanouts;
    final boolean[] driven;
    final boolean[] stubEnd;
    /** Per node: whether a routethru PIP (through a site) drives it; routing ending there is no antenna */
    final boolean[] routethruEnd;
    /** The start and end nodes of the routethru PIPs, or null if none */
    List<Node> routethruNodes;
    /** Index of each node key in {@link #nodes} */
    LongIndex index;
    /**
     * Per node: whether a PIP leads from it in the direction it is used (not just backwards through a
     * bidirectional PIP)
     */
    final boolean[] hasForwardFanout;
    boolean locked;
    /** Results of {@link #analyze}: the sinks, and how many of them the routing reaches */
    int sinks;
    int reachedSinks;
    /** Routing that does not lead to a load, or is disconnected from the rest */
    boolean antennas;
    /** Whether some routing starts at a pin feed, leaving an explicit gap before it */
    boolean explicitGaps;
    /** The start nodes of the PIPs marking explicit gaps (PIPs to nowhere), or null */
    List<Node> gapStarts;
    boolean unroutedSource;
    /** Whether an output site pin of the net was not taken as a source, as its site routing does not drive it */
    boolean undrivenSource;
    /** Whether any routing starts at a source of the net (or comes from another partition) */
    boolean rooted;
    /** The sinks that the routing leads to without being reached from a source (e.g. on islands) */
    int islandSinks;
    /** The nodes of the sinks where the routing ends (without stale site pins excluded), or null */
    List<Node> leafSinks;
    /**
     * Per node: whether more than one PIP (or the same PIP more than once) drives it. Vivado
     * gets such a node once more in the route tree, as a leaf: a conflict with itself.
     */
    boolean[] drivenAgain;

    /** Per node: see {@link #getBidirectionalPartners(int)} (null until needed) */
    private int[][] bidirectionalPartners;

    /** The intent codes of global (clock) routing */
    private static final Set<IntentCode> GLOBAL_INTENT_CODES = EnumSet.noneOf(IntentCode.class);

    static {
        for (IntentCode ic : IntentCode.values()) {
            if (ic.name().contains("GLOBAL")) {
                GLOBAL_INTENT_CODES.add(ic);
            }
        }
    }

    RouteGraph(Net net) {
        Edges edges = readEdges(net.getPIPs());
        index = new LongIndex(2 * edges.count);
        int[] startIdx = new int[edges.count];
        int[] endIdx = new int[edges.count];
        // Routing is mostly stored in order, each PIP starting where the previous one ends
        long prevKey = Long.MIN_VALUE;
        int prevIdx = -1;
        for (int e = 0; e < edges.count; e++) {
            startIdx[e] = (edges.startKeys[e] == prevKey) ? prevIdx : index.add(edges.startKeys[e]);
            endIdx[e] = index.add(edges.endKeys[e]);
            prevKey = edges.endKeys[e];
            prevIdx = endIdx[e];
        }
        nodes = index.keys();
        int distinct = nodes.length;
        markStartStubs(edges, startIdx, distinct);
        nodeObjects = new Node[distinct];
        driven = new boolean[distinct];
        stubEnd = new boolean[distinct];
        routethruEnd = new boolean[distinct];
        hasForwardFanout = new boolean[distinct];
        fanoutStart = new int[distinct + 1];
        addEdges(edges, startIdx, endIdx);
        for (int i = 0; i < distinct; i++) {
            fanoutStart[i + 1] += fanoutStart[i];
        }
        fanouts = new int[edges.count + edges.numBidir];
        int[] fill = Arrays.copyOf(fanoutStart, distinct);
        for (int e = 0; e < edges.count; e++) {
            fanouts[fill[startIdx[e]]++] = endIdx[e];
            if (edges.isBidir[e]) {
                fanouts[fill[endIdx[e]]++] = startIdx[e];
            }
        }
        extendStubs();
    }

    /** The PIPs of a net (between two nodes), as edges from their start node to their end node */
    private static final class Edges {
        final long[] startKeys;
        final long[] endKeys;
        final Node[] startNodes;
        final Node[] endNodes;
        final boolean[] isStub;
        final boolean[] isStartStub;
        final boolean[] isRoutethru;
        final boolean[] isBidir;
        final boolean[] isReversed;
        int count;
        int numBidir;

        Edges(int capacity) {
            startKeys = new long[capacity];
            endKeys = new long[capacity];
            startNodes = new Node[capacity];
            endNodes = new Node[capacity];
            isStub = new boolean[capacity];
            isStartStub = new boolean[capacity];
            isRoutethru = new boolean[capacity];
            isBidir = new boolean[capacity];
            isReversed = new boolean[capacity];
        }
    }

    /**
     * Reads the PIPs of a net as edges (in the direction they are used), noting whether any is locked,
     * and the PIPs that mark explicit gaps.
     */
    private Edges readEdges(List<PIP> pips) {
        Edges edges = new Edges(pips.size());
        // PIPs are mostly stored in routing order, so a PIP often starts at the tile wire where
        // the previous one ends: reuse that node rather than looking it up again
        Tile prevTile = null;
        int prevEndWire = -1;
        Node prevEnd = null;
        for (PIP pip : pips) {
            if (pip.isPIPFixed()) {
                locked = true;
            }
            Node start;
            Node end;
            if (pip.isReversed()) {
                start = pip.getEndNode();
                end = pip.getStartNode();
                prevTile = null;
            } else {
                Tile tile = pip.getTile();
                start = (tile == prevTile && pip.getStartWireIndex() == prevEndWire) ? prevEnd : pip.getStartNode();
                end = pip.getEndNode();
                prevTile = tile;
                prevEndWire = pip.getEndWireIndex();
                prevEnd = end;
            }
            if (start == null || end == null) {
                if (end == null && start != null) {
                    // A PIP to nowhere marks an explicit gap after its start node
                    if (gapStarts == null) {
                        gapStarts = new ArrayList<>();
                    }
                    gapStarts.add(start);
                }
                continue;
            }
            int e = edges.count++;
            edges.startNodes[e] = start;
            edges.endNodes[e] = end;
            edges.startKeys[e] = nodeKey(start);
            edges.endKeys[e] = nodeKey(end);
            edges.isStub[e] = pip.isStub();
            edges.isStartStub[e] = pip.isStartStub();
            edges.isRoutethru[e] = pip.isRouteThru();
            edges.isBidir[e] = pip.isBidirectional();
            edges.isReversed[e] = pip.isReversed();
            if (edges.isBidir[e]) {
                edges.numBidir++;
            }
        }
        return edges;
    }

    /**
     * A START_STUB makes all the routing from its start (e.g. the other output pin of a net driven
     * through two site pins) a stub, not only the PIP it annotates.
     */
    private static void markStartStubs(Edges edges, int[] startIdx, int distinct) {
        boolean[] stubRoot = new boolean[distinct];
        for (int e = 0; e < edges.count; e++) {
            if (edges.isStartStub[e]) {
                stubRoot[startIdx[e]] = true;
            }
        }
        for (int e = 0; e < edges.count; e++) {
            if (stubRoot[startIdx[e]]) {
                edges.isStub[e] = true;
            }
        }
    }

    /**
     * Records the nodes of the edges, what drives them, the number of fanouts of each node (in
     * {@link #fanoutStart}, not yet summed), stubs and routethrus.
     */
    private void addEdges(Edges edges, int[] startIdx, int[] endIdx) {
        for (int e = 0; e < edges.count; e++) {
            nodeObjects[startIdx[e]] = edges.startNodes[e];
            nodeObjects[endIdx[e]] = edges.endNodes[e];
            fanoutStart[startIdx[e] + 1]++;
            hasForwardFanout[startIdx[e]] = true;
            if (driven[endIdx[e]] && !edges.isReversed[e]) {
                if (drivenAgain == null) {
                    drivenAgain = new boolean[nodes.length];
                }
                drivenAgain[endIdx[e]] = true;
            }
            driven[endIdx[e]] = true;
            if (edges.isBidir[e]) {
                // Bidirectional PIPs connect their nodes either way (and in clock routing, either way
                // may be the direction they are used in, unless explicitly stored as reversed)
                fanoutStart[endIdx[e] + 1]++;
                driven[startIdx[e]] = true;
                if (!edges.isReversed[e] && isGlobal(edges.endNodes[e])) {
                    hasForwardFanout[endIdx[e]] = true;
                }
            }
            if (edges.isStub[e]) {
                // Stubs are deliberately dangling and not considered antennas
                stubEnd[endIdx[e]] = true;
            }
            if (edges.isRoutethru[e]) {
                routethruEnd[endIdx[e]] = true;
                if (routethruNodes == null) {
                    routethruNodes = new ArrayList<>();
                }
                routethruNodes.add(edges.startNodes[e]);
                routethruNodes.add(edges.endNodes[e]);
            }
        }
    }

    /** Marks everything downstream of a stub PIP as part of the stub. */
    private void extendStubs() {
        int[] stack = null;
        int top = 0;
        for (int i = 0; i < nodes.length; i++) {
            if (!stubEnd[i]) {
                continue;
            }
            if (stack == null) {
                stack = new int[nodes.length];
            }
            stack[top++] = i;
            while (top > 0) {
                int node = stack[--top];
                for (int f = fanoutStart[node]; f < fanoutStart[node + 1]; f++) {
                    int next = fanouts[f];
                    if (!stubEnd[next]) {
                        stubEnd[next] = true;
                        stack[top++] = next;
                    }
                }
            }
        }
    }

    /** Checks whether a node is part of global (clock) routing. */
    private static boolean isGlobal(Node node) {
        return GLOBAL_INTENT_CODES.contains(node.getIntentCode());
    }

    private int indexOf(Node node) {
        if (node == null) {
            return -1;
        }
        int i = index.get(nodeKey(node));
        return (i >= 0) ? i : -1;
    }

    /**
     * Analyzes which sinks of the net the routing reaches from its sources, and whether it has
     * antennas or islands (see the results above).
     * @param aliases The other physical nets of the flat net (named after other segments), whose
     * site pins and site routing count too, or null.
     * @param sitePinSinkKeys The keys of the nodes of further sinks: input site pins of the net's site
     * routing (see {@link Entries#sitePinKeys}), or null.
     * @param staleKeys The sorted keys of the nodes of site pins that are not loads, or null.
     * @param partitionPinKeys Sorted keys of the nodes of the design's partition pins, where
     * routing continues in (or comes from) another partition.
     * @param placedSource Whether the net has a placed source (which routing from a partition
     * pin does not connect).
     */
    void analyze(Net net, List<Net> aliases, boolean isStatic, long[] sitePinSinkKeys, long[] staleKeys,
                 long[] partitionPinKeys, boolean placedSource) {
        // (the results of any previous analysis are replaced)
        sinks = 0;
        reachedSinks = 0;
        antennas = false;
        explicitGaps = false;
        unroutedSource = false;
        undrivenSource = false;
        islandSinks = 0;
        leafSinks = null;
        Search search = new Search(nodes.length);
        long[] sinkKeys = seedSourcePins(search, net, aliases, isStatic, sitePinSinkKeys);
        markSinks(search, sinkKeys, staleKeys);
        seedUnstoredSourcePins(search, net, aliases, isStatic);
        boolean rootedAtSource = search.tail > 0;
        if (search.sourceUnrouted && !rootedAtSource) {
            // Vivado does not tell the source from the alternate source of a net (e.g. the xMUX and
            // x_O outputs of a LUT, both site routed): the routing may start at either
            unroutedSource = true;
        }
        seedPartitionPins(search, partitionPinKeys);
        rooted = search.tail > 0;
        if (rooted && !rootedAtSource && placedSource && !isStatic) {
            // Routing from another partition, but none from the placed source
            unroutedSource = true;
        }
        boolean gapMarkers = seedExplicitGaps(search);
        do {
            propagate(search, net, isStatic, staleKeys != null, gapMarkers);
        } while (joinUnstoredBidirectional(search));
        countReached(search, isStatic, gapMarkers);
    }

    /** The state of a search of the routing from its sources, by node index */
    private static final class Search {
        final boolean[] isSink;
        final boolean[] visited;
        final int[] queue;
        /** Per node: the node it was first reached from (or -1) */
        final int[] parent;
        int head;
        int tail;
        /** Per node: whether it is that of a site pin that is not a load (null if none) */
        boolean[] isStale;
        /** Per node: whether it is that of a partition pin (null if none) */
        boolean[] isPartitionPin;
        /** Whether the source pin of the net is not routed */
        boolean sourceUnrouted;

        Search(int n) {
            isSink = new boolean[n];
            visited = new boolean[n];
            queue = new int[n];
            parent = new int[n];
            Arrays.fill(parent, -1);
        }

        /** Adds a node to search from. */
        void add(int i) {
            visited[i] = true;
            queue[tail++] = i;
        }
    }

    /**
     * Starts the search from the output site pins of the net (and of its aliases) that its site routing
     * drives, and gets the sinks: the input site pins of the net, whether or not they have a
     * SitePinInst (those of unrouted sinks are not stored in a DCP), as found in its site routing.
     * @return The keys of the nodes of the sinks.
     */
    private long[] seedSourcePins(Search search, Net net, List<Net> aliases, boolean isStatic, long[] sitePinSinkKeys) {
        List<SitePinInst> pins = net.getPins();
        if (aliases != null) {
            pins = new ArrayList<>(pins);
            for (Net alias : aliases) {
                pins.addAll(storedPins(alias));
            }
        }
        int extra = (sitePinSinkKeys == null) ? 0 : sitePinSinkKeys.length;
        long[] sinkKeys = new long[pins.size() + extra];
        int numSinks = 0;
        for (SitePinInst spi : pins) {
            Node node = spi.getConnectedNode();
            // (the net, or an alias)
            Net owner = spi.getNet();
            if (!spi.isOutPin()) {
                if (node != null) {
                    sinkKeys[numSinks++] = nodeKey(node);
                }
                continue;
            }
            if (!isStatic && !isDrivenBySiteRouting(owner, spi)) {
                // An output pin the site routing of the net does not lead to (e.g. one inferred from
                // routing that starts there) drives nothing
                undrivenSource = true;
                continue;
            }
            int i = indexOf(node);
            if (i >= 0 && !search.visited[i]) {
                search.add(i);
            } else if (i < 0 && !isStatic && spi == owner.getSource()) {
                // The source pin is not routed (which only matters if no other output pin of the net
                // is, see analyze())
                search.sourceUnrouted = true;
            }
        }
        if (extra > 0) {
            System.arraycopy(sitePinSinkKeys, 0, sinkKeys, numSinks, extra);
            numSinks += extra;
        }
        return Arrays.copyOf(sinkKeys, numSinks);
    }

    /** Counts and marks the (distinct) sinks, except the site pins that are not loads. */
    private void markSinks(Search search, long[] sinkKeys, long[] staleKeys) {
        int numSinks = sinkKeys.length;
        if (staleKeys != null) {
            for (long key : staleKeys) {
                int idx = index.get(key);
                if (idx >= 0) {
                    if (search.isStale == null) {
                        search.isStale = new boolean[nodes.length];
                    }
                    search.isStale[idx] = true;
                }
            }
            int kept = 0;
            for (int i = 0; i < numSinks; i++) {
                if (Arrays.binarySearch(staleKeys, sinkKeys[i]) < 0) {
                    sinkKeys[kept++] = sinkKeys[i];
                }
            }
            numSinks = kept;
        }
        Arrays.sort(sinkKeys, 0, numSinks);
        for (int i = 0; i < numSinks; i++) {
            if (i > 0 && sinkKeys[i] == sinkKeys[i - 1]) {
                continue;
            }
            sinks++;
            int idx = index.get(sinkKeys[i]);
            if (idx >= 0) {
                search.isSink[idx] = true;
            }
        }
    }

    /**
     * Starts the search from the undriven nodes that are sources without a SitePinInst: tied nodes of
     * static nets, and site output pins driven by the site routing of the net.
     */
    private void seedUnstoredSourcePins(Search search, Net net, List<Net> aliases, boolean isStatic) {
        for (int i = 0; i < nodes.length; i++) {
            if (driven[i] || search.visited[i] || fanoutStart[i] == fanoutStart[i + 1]) {
                continue;
            }
            Node node = nodeObjects[i];
            if (isStatic ? node.isTied() : isSiteRoutedOutputPinNode(net, aliases, node)) {
                search.add(i);
            } else if (!isStatic && node.getSitePin() != null && !node.getSitePin().isInput()
                    && isSitePinUsedBy(net, node)) {
                undrivenSource = true;
            }
        }
    }

    /** Starts the search from the partition pins that routing from another partition comes from. */
    private void seedPartitionPins(Search search, long[] partitionPinKeys) {
        if (partitionPinKeys.length == 0) {
            return;
        }
        for (int i = 0; i < nodes.length; i++) {
            if (Arrays.binarySearch(partitionPinKeys, nodes[i]) < 0) {
                continue;
            }
            if (search.isPartitionPin == null) {
                search.isPartitionPin = new boolean[nodes.length];
            }
            search.isPartitionPin[i] = true;
            if (!driven[i] && !search.visited[i] && hasForwardFanout[i]) {
                // Routing from another partition
                search.add(i);
            }
        }
    }

    /**
     * Explicit gaps connect the routing to the pins and routing they lead to (any routing may be joined
     * through them), but a gap after a node used by nothing else is an island. Starts the search from
     * the pin feeds that routing starts from, as is stored (with explicit gaps) for clocks routed only
     * at their ends.
     * @return Whether the net has explicit gaps.
     */
    private boolean seedExplicitGaps(Search search) {
        if (gapStarts == null) {
            return false;
        }
        explicitGaps = true;
        for (Node node : gapStarts) {
            if (index.get(nodeKey(node)) < 0 && !isSitePinNode(node)
                    && node.getIntentCode() != IntentCode.NODE_PINFEED && !node.getAllDownhillNodes().isEmpty()) {
                antennas = true;
            }
        }
        for (int i = 0; i < nodes.length; i++) {
            if (!driven[i] && !search.visited[i] && hasForwardFanout[i]
                    && nodeObjects[i].getIntentCode() == IntentCode.NODE_PINFEED) {
                search.add(i);
            }
        }
        return true;
    }

    /** Continues the search through the fanouts of the nodes reached, finding where the routing ends. */
    private void propagate(Search search, Net net, boolean isStatic, boolean hasStaleKeys, boolean gapMarkers) {
        while (search.head < search.tail) {
            int i = search.queue[search.head++];
            int from = fanoutStart[i];
            int to = fanoutStart[i + 1];
            if (isDeadEnd(search, i, net)) {
                // Leads nowhere, unless to a load. The site pins that loads of static nets are tied to
                // are not always in their site routing. Stubs are deliberately dangling, but only on
                // nets with loads. Routing that ends where an explicit gap follows (to some pin) leads
                // on and so does routing to another partition.
                if (!search.isSink[i] && !(stubEnd[i] && sinks > 0) && !(isStatic && isSitePinNode(nodeObjects[i]))
                        && !gapMarkers && !(search.isPartitionPin != null && search.isPartitionPin[i])
                        && !isLeafClockClearPin(nodeObjects[i]) && !routethruEnd[i]) {
                    antennas = true;
                } else if (search.isSink[i] && !isStatic && from == to && !hasStaleKeys) {
                    if (leafSinks == null) {
                        leafSinks = new ArrayList<>();
                    }
                    leafSinks.add(nodeObjects[i]);
                }
            }
            for (int f = from; f < to; f++) {
                int next = fanouts[f];
                if (!search.visited[next]) {
                    search.parent[next] = i;
                    search.add(next);
                }
            }
        }
    }

    /**
     * Checks whether the routing leads nowhere from a node reached: it has no fanout in the direction
     * it is used in (a site pin used by the site routing of the net, unless that leads to no placed
     * cell, or global clock routing, reached backwards through a bidirectional PIP may lead on), or it
     * is a 7-series global clock node only leading back where it was reached from, through a
     * bidirectional PIP.
     */
    private boolean isDeadEnd(Search search, int i, Net net) {
        int from = fanoutStart[i];
        int to = fanoutStart[i + 1];
        boolean onlyBack = from < to && search.parent[i] >= 0 && nodeObjects[i].getIntentCode() == IntentCode.GLOBAL;
        for (int f = from; f < to && onlyBack; f++) {
            onlyBack = fanouts[f] == search.parent[i];
        }
        return onlyBack || (!hasForwardFanout[i] && (from == to || (search.isStale != null && search.isStale[i])
                || !(isSitePinUsedBy(net, nodeObjects[i]) || isGlobal(nodeObjects[i]))));
    }

    /**
     * Joins unreached global (clock) routing to the routing reached through a bidirectional PIP that
     * is not stored with the net (e.g. between the halves of a clock distribution row). Other
     * bidirectional PIPs only connect if stored.
     * @return Whether any routing was joined (and is to be searched from).
     */
    private boolean joinUnstoredBidirectional(Search search) {
        boolean joined = false;
        for (int i = 0; i < nodes.length; i++) {
            if (search.visited[i] || !isGlobal(nodeObjects[i])) {
                continue;
            }
            for (int o : getBidirectionalPartners(i)) {
                if (search.visited[o]) {
                    search.add(i);
                    joined = true;
                    break;
                }
            }
        }
        return joined;
    }

    /**
     * Gets the nodes of the routing that bidirectional PIPs (stored or not) connect a node to, found
     * once (for the nodes that searches need them for).
     */
    private int[] getBidirectionalPartners(int i) {
        if (bidirectionalPartners == null) {
            bidirectionalPartners = new int[nodes.length][];
        }
        int[] partners = bidirectionalPartners[i];
        if (partners != null) {
            return partners;
        }
        partners = new int[0];
        for (PIP pip : nodeObjects[i].getAllUphillPIPs()) {
            if (!pip.isBidirectional()) {
                continue;
            }
            Node start = pip.getStartNode();
            Node other = (start != null && nodeKey(start) == nodes[i]) ? pip.getEndNode() : start;
            int o = indexOf(other);
            if (o >= 0) {
                partners = Arrays.copyOf(partners, partners.length + 1);
                partners[partners.length - 1] = o;
            }
        }
        bidirectionalPartners[i] = partners;
        return partners;
    }

    /** Counts the sinks reached, and finds islands and nodes driven again. */
    private void countReached(Search search, boolean isStatic, boolean gapMarkers) {
        for (int i = 0; i < nodes.length; i++) {
            if (!search.visited[i] && fanoutStart[i] != fanoutStart[i + 1] && !gapMarkers) {
                // Island: routing not reachable from the source (unless through explicit gaps)
                antennas = true;
            }
            if (drivenAgain != null && drivenAgain[i] && !gapMarkers && !search.isSink[i] && !(stubEnd[i] && sinks > 0)
                    && !(isStatic && isSitePinNode(nodeObjects[i]))) {
                // A node driven again leads nowhere the second time, unless it is a load (as are the
                // site pins that static nets tie, which need not be in their site routing)
                antennas = true;
            }
            if (search.isSink[i] && (search.visited[i] || rooted)) {
                // Reached by routing (even if that routing is an island, or merely starts there,
                // unless no routing leaves the source at all)
                reachedSinks++;
            } else if (search.isSink[i]) {
                islandSinks++;
            }
        }
    }
}
