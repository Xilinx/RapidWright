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
import java.util.List;

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
    /** Per node: whether a PIP leads from it in the direction it is used (not just backwards through a bidirectional PIP) */
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

    RouteGraph(Net net) {
        List<PIP> pips = net.getPIPs();
        int n = pips.size();
        long[] startKeys = new long[n];
        long[] endKeys = new long[n];
        Node[] startNodes = new Node[n];
        Node[] endNodes = new Node[n];
        boolean[] isStub = new boolean[n];
        boolean[] isStartStub = new boolean[n];
        boolean[] isRoutethru = new boolean[n];
        int edges = 0;
        boolean[] isBidir = new boolean[n];
        boolean[] isReversed = new boolean[n];
        int numBidir = 0;
        // PIPs are mostly stored in routing order, so a PIP often starts at the tile wire where
        // the previous one ends: reuse that node rather than looking it up again
        Tile prevTile = null;
        int prevEndWire = -1;
        Node prevEnd = null;
        for (int i = 0; i < n; i++) {
            PIP pip = pips.get(i);
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
            startNodes[edges] = start;
            endNodes[edges] = end;
            startKeys[edges] = nodeKey(start);
            endKeys[edges] = nodeKey(end);
            isStub[edges] = pip.isStub();
            isStartStub[edges] = pip.isStartStub();
            isRoutethru[edges] = pip.isRouteThru();
            isBidir[edges] = pip.isBidirectional();
            isReversed[edges] = pip.isReversed();
            if (isBidir[edges]) {
                numBidir++;
            }
            edges++;
        }
        index = new LongIndex(2 * edges);
        int[] startIdx = new int[edges];
        int[] endIdx = new int[edges];
        // Routing is mostly stored in order, each PIP starting where the previous one ends
        long prevKey = Long.MIN_VALUE;
        int prevIdx = -1;
        for (int e = 0; e < edges; e++) {
            startIdx[e] = (startKeys[e] == prevKey) ? prevIdx : index.add(startKeys[e]);
            endIdx[e] = index.add(endKeys[e]);
            prevKey = endKeys[e];
            prevIdx = endIdx[e];
        }
        nodes = index.keys();
        int distinct = nodes.length;
        // A START_STUB makes all the routing from its start (e.g. the other output pin of a net
        // driven through two site pins) a stub, not only the PIP it annotates
        boolean[] stubRoot = new boolean[distinct];
        for (int e = 0; e < edges; e++) {
            if (isStartStub[e]) {
                stubRoot[startIdx[e]] = true;
            }
        }
        for (int e = 0; e < edges; e++) {
            if (stubRoot[startIdx[e]]) {
                isStub[e] = true;
            }
        }
        nodeObjects = new Node[distinct];
        driven = new boolean[distinct];
        stubEnd = new boolean[distinct];
        routethruEnd = new boolean[distinct];
        hasForwardFanout = new boolean[distinct];
        fanoutStart = new int[distinct + 1];
        for (int e = 0; e < edges; e++) {
            nodeObjects[startIdx[e]] = startNodes[e];
            nodeObjects[endIdx[e]] = endNodes[e];
            fanoutStart[startIdx[e] + 1]++;
            hasForwardFanout[startIdx[e]] = true;
            if (driven[endIdx[e]] && !isReversed[e]) {
                if (drivenAgain == null) {
                    drivenAgain = new boolean[distinct];
                }
                drivenAgain[endIdx[e]] = true;
            }
            driven[endIdx[e]] = true;
            if (isBidir[e]) {
                // Bidirectional PIPs connect their nodes either way (and in clock routing,
                // either way may be the direction they are used in, unless explicitly stored
                // as reversed)
                fanoutStart[endIdx[e] + 1]++;
                driven[startIdx[e]] = true;
                if (!isReversed[e] && endNodes[e].getIntentCode().toString().contains("GLOBAL")) {
                    hasForwardFanout[endIdx[e]] = true;
                }
            }
            if (isStub[e]) {
                // Stubs are deliberately dangling and not considered antennas
                stubEnd[endIdx[e]] = true;
            }
            if (isRoutethru[e]) {
                routethruEnd[endIdx[e]] = true;
                if (routethruNodes == null) {
                    routethruNodes = new ArrayList<>();
                }
                routethruNodes.add(startNodes[e]);
                routethruNodes.add(endNodes[e]);
            }
        }
        for (int i = 0; i < distinct; i++) {
            fanoutStart[i + 1] += fanoutStart[i];
        }
        fanouts = new int[edges + numBidir];
        int[] fill = Arrays.copyOf(fanoutStart, distinct);
        for (int e = 0; e < edges; e++) {
            fanouts[fill[startIdx[e]]++] = endIdx[e];
            if (isBidir[e]) {
                fanouts[fill[endIdx[e]]++] = startIdx[e];
            }
        }
        // Everything downstream of a stub PIP is part of the stub
        int[] stack = null;
        int top = 0;
        for (int i = 0; i < distinct; i++) {
            if (!stubEnd[i]) {
                continue;
            }
            if (stack == null) {
                stack = new int[distinct];
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

    private int indexOf(Node node) {
        if (node == null) {
            return -1;
        }
        int i = index.get(nodeKey(node));
        return (i >= 0) ? i : -1;
    }

    /**
     * @param aliases The other physical nets of the flat net (named after other segments), whose
     * site pins and site routing count too, or null.
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
        int distinct = nodes.length;
        boolean sourceUnrouted = false;
        boolean[] isSink = new boolean[distinct];
        boolean[] visited = new boolean[distinct];
        int[] queue = new int[distinct];
        // Per node: the node it was first reached from (or -1)
        int[] parent = new int[distinct];
        Arrays.fill(parent, -1);
        int head = 0;
        int tail = 0;
        // Sinks are the input site pins of the net, whether or not they have a SitePinInst
        // (those of unrouted sinks are not stored in a DCP), as found in its site routing
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
            if (spi.isOutPin()) {
                if (!isStatic && !isDrivenBySiteRouting(owner, spi)) {
                    // An output pin the site routing of the net does not lead to (e.g. one
                    // inferred from routing that starts there) drives nothing
                    undrivenSource = true;
                    continue;
                }
                int i = indexOf(node);
                if (i >= 0 && !visited[i]) {
                    visited[i] = true;
                    queue[tail++] = i;
                } else if (i < 0 && !isStatic && spi == owner.getSource()) {
                    // The source pin is not routed (which only matters if no other output pin
                    // of the net is, see below)
                    sourceUnrouted = true;
                }
            } else if (node != null) {
                sinkKeys[numSinks++] = nodeKey(node);
            }
        }
        if (extra > 0) {
            System.arraycopy(sitePinSinkKeys, 0, sinkKeys, numSinks, extra);
            numSinks += extra;
        }
        boolean[] isStale = null;
        if (staleKeys != null) {
            for (long key : staleKeys) {
                int idx = index.get(key);
                if (idx >= 0) {
                    if (isStale == null) {
                        isStale = new boolean[distinct];
                    }
                    isStale[idx] = true;
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
        sinkKeys = Arrays.copyOf(sinkKeys, numSinks);
        Arrays.sort(sinkKeys);
        for (int i = 0; i < sinkKeys.length; i++) {
            if (i > 0 && sinkKeys[i] == sinkKeys[i - 1]) {
                continue;
            }
            sinks++;
            int idx = index.get(sinkKeys[i]);
            if (idx >= 0) {
                isSink[idx] = true;
            }
        }
        for (int i = 0; i < distinct; i++) {
            if (driven[i] || visited[i] || fanoutStart[i] == fanoutStart[i + 1]) {
                continue;
            }
            Node node = nodeObjects[i];
            if (isStatic ? node.isTied() : isSiteRoutedOutputPinNode(net, aliases, node)) {
                // Tied nodes of static nets, and site output pins (driven by the site routing
                // of the net) without a SitePinInst
                visited[i] = true;
                queue[tail++] = i;
            } else if (!isStatic && node.getSitePin() != null && !node.getSitePin().isInput()
                    && isSitePinUsedBy(net, node)) {
                undrivenSource = true;
            }
        }
        boolean rootedAtSource = tail > 0;
        if (sourceUnrouted && !rootedAtSource) {
            // Vivado does not tell the source from the alternate source of a net (e.g. the xMUX
            // and x_O outputs of a LUT, both site routed): the routing may start at either
            unroutedSource = true;
        }
        boolean[] isPartitionPin = null;
        if (partitionPinKeys.length > 0) {
            for (int i = 0; i < distinct; i++) {
                if (Arrays.binarySearch(partitionPinKeys, nodes[i]) < 0) {
                    continue;
                }
                if (isPartitionPin == null) {
                    isPartitionPin = new boolean[distinct];
                }
                isPartitionPin[i] = true;
                if (!driven[i] && !visited[i] && hasForwardFanout[i]) {
                    // Routing from another partition
                    visited[i] = true;
                    queue[tail++] = i;
                }
            }
        }
        rooted = tail > 0;
        if (rooted && !rootedAtSource && placedSource && !isStatic) {
            // Routing from another partition, but none from the placed source
            unroutedSource = true;
        }
        // Explicit gaps connect the routing to the pins and routing they lead to (any routing
        // may be joined through them), but a gap after a node used by nothing else is an island
        boolean gapMarkers = gapStarts != null;
        if (gapMarkers) {
            explicitGaps = true;
            for (Node node : gapStarts) {
                if (index.get(nodeKey(node)) < 0 && !isSitePinNode(node)
                        && node.getIntentCode() != IntentCode.NODE_PINFEED && !node.getAllDownhillNodes().isEmpty()) {
                    antennas = true;
                }
            }
        }
        for (int i = 0; i < distinct; i++) {
            if (driven[i] || visited[i] || !hasForwardFanout[i]) {
                continue;
            }
            if (gapMarkers && nodeObjects[i].getIntentCode() == IntentCode.NODE_PINFEED) {
                // Routing from a pin feed, as is stored (with explicit gaps) for clocks routed
                // only at their ends
                visited[i] = true;
                queue[tail++] = i;
            }
        }
        boolean changed = true;
        while (changed) {
            while (head < tail) {
                int i = queue[head++];
                int from = fanoutStart[i];
                int to = fanoutStart[i + 1];
                // (a 7-series global clock node only leading back where it was reached from,
                // through a bidirectional PIP, leads nowhere)
                boolean onlyBack = from < to && parent[i] >= 0 && nodeObjects[i].getIntentCode() == IntentCode.GLOBAL;
                for (int f = from; f < to && onlyBack; f++) {
                    onlyBack = fanouts[f] == parent[i];
                }
                if (onlyBack || (!hasForwardFanout[i] && (from == to || (isStale != null && isStale[i])
                        || !(isSitePinUsedBy(net, nodeObjects[i]) || nodeObjects[i].getIntentCode().toString().contains("GLOBAL"))))) {
                    // (a site pin used by the site routing of the net, unless that leads to no
                    // placed cell, or global clock routing, reached backwards through a
                    // bidirectional PIP may lead on)
                    // Leads nowhere, unless to a load. The site pins that loads of static nets
                    // are tied to are not always in their site routing. Stubs are deliberately
                    // dangling, but only on nets with loads.
                    // Routing that ends where an explicit gap follows (to some pin) leads on
                    // and so does routing to another partition
                    if (!isSink[i] && !(stubEnd[i] && sinks > 0) && !(isStatic && isSitePinNode(nodeObjects[i]))
                            && !gapMarkers && !(isPartitionPin != null && isPartitionPin[i])
                            && !isLeafClockClearPin(nodeObjects[i]) && !routethruEnd[i]) {
                        antennas = true;
                    } else if (isSink[i] && !isStatic && from == to && staleKeys == null) {
                        if (leafSinks == null) {
                            leafSinks = new ArrayList<>();
                        }
                        leafSinks.add(nodeObjects[i]);
                    }
                }
                for (int f = from; f < to; f++) {
                    int next = fanouts[f];
                    if (!visited[next]) {
                        visited[next] = true;
                        parent[next] = i;
                        queue[tail++] = next;
                    }
                }
            }
            // Unreached global (clock) routing may still be joined to the rest by a
            // bidirectional PIP that is not stored with the net (e.g. between the halves of a
            // clock distribution row). Other bidirectional PIPs only connect if stored.
            changed = false;
            for (int i = 0; i < distinct; i++) {
                if (visited[i] || !nodeObjects[i].getIntentCode().toString().contains("GLOBAL")) {
                    continue;
                }
                for (PIP pip : nodeObjects[i].getAllUphillPIPs()) {
                    if (!pip.isBidirectional()) {
                        continue;
                    }
                    Node start = pip.getStartNode();
                    Node other = (start != null && nodeKey(start) == nodes[i]) ? pip.getEndNode() : start;
                    int o = indexOf(other);
                    if (o >= 0 && visited[o]) {
                        visited[i] = true;
                        queue[tail++] = i;
                        changed = true;
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < distinct; i++) {
            if (!visited[i] && fanoutStart[i] != fanoutStart[i + 1] && !gapMarkers) {
                // Island: routing not reachable from the source (unless through explicit gaps)
                antennas = true;
            }
            if (drivenAgain != null && drivenAgain[i] && !gapMarkers && !isSink[i] && !(stubEnd[i] && sinks > 0)
                    && !(isStatic && isSitePinNode(nodeObjects[i]))) {
                // A node driven again leads nowhere the second time, unless it is a load (as
                // are the site pins that static nets tie, which need not be in their site routing)
                antennas = true;
            }
            if (isSink[i] && (visited[i] || rooted)) {
                // Reached by routing (even if that routing is an island, or merely starts
                // there, unless no routing leaves the source at all)
                reachedSinks++;
            } else if (isSink[i]) {
                islandSinks++;
            }
        }
    }
}
