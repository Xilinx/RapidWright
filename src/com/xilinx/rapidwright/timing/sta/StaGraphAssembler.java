/*
 *
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

package com.xilinx.rapidwright.timing.sta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;

/**
 * Assembles a {@link StaGraph} from pins, edges and connections given in any order, e.g. by
 * {@link StaGraphBuilder} or by hand in tests. {@link #build()} then:
 * <ol>
 * <li>merges duplicate edges (same source and sink pins) into the last one added, with the largest of
 * their max delays and the smallest of their min delays (e.g. a LUT RAM port mapped to two BEL pins,
 * such as A1 and WA1, gives two arcs between the same pins);</li>
 * <li>cuts the combinational cycles, removing the back edges of an iterative depth-first search
 * (roots and out-edges in the order they were added), and reports them;</li>
 * <li>marks the startpoints and endpoints (see {@link #markEndpoint(int)},
 * {@link #markEndpointIfNoFanout(int)} and {@link #forceEndpoint(int)});</li>
 * <li>levelizes the graph (Kahn's algorithm) and renumbers the pins in levelized order;</li>
 * <li>builds the CSR arrays.</li>
 * </ol>
 * Pin ids given by {@link #addPin(long, byte)} are temporary: use {@link #getFinalPinId(int)}
 * after {@link #build()} to translate them.
 */
class StaGraphAssembler {

    /** Build flag: an endpoint if, after cutting cycles, it has in-edges and no out-edges */
    private static final byte CAND_END = 1;
    /** Build flag: as {@link #CAND_END}, provided it had no out-edges before cutting cycles */
    private static final byte CAND_END_IF_NO_FANOUT = 2;
    /** Build flag: an endpoint unless it is a startpoint */
    private static final byte FORCE_END = 4;

    private int numPins;
    private long[] pinKey = new long[1024];
    private byte[] pinKind = new byte[1024];
    private byte[] buildFlags = new byte[1024];

    private int numEdges;
    private int[] eFrom = new int[1024];
    private int[] eTo = new int[1024];
    private byte[] eKind = new byte[1024];
    private float[] eFixedMax = new float[1024];
    private float[] eFixedMin = new float[1024];
    /** The fixed delays at the second min corner, null unless enabled */
    private float[] eFixedMin2;
    private float[] eIntraMax = new float[1024];
    private int[] eConn = new int[1024];

    private int numConns;
    /** See {@link #keepConnectionsApart()} */
    private boolean keepConnectionsApart;
    private float[] connRouteMax = new float[256];
    private float[] connRouteMin = new float[256];
    /** The route delays at the second min corner, null unless enabled */
    private float[] connRouteMin2;
    private SitePinInst[] connSink = new SitePinInst[256];

    private EDIFHierCellInst[] cells;
    private Map<Long, String> pseudoPinNames = new HashMap<>();

    /** After {@link #build()}: the final id of each pin, by temporary id */
    private int[] finalId;

    /**
     * Adds a pin.
     * @param key Its key (see {@link StaGraph#makePinKey(int, int)}); not checked for uniqueness.
     * @param kind Its kind flags (e.g. {@link StaGraph#PIN_INPUT}).
     * @return Its temporary id.
     */
    int addPin(long key, byte kind) {
        if (numPins == pinKey.length) {
            int n = grow(numPins);
            pinKey = Arrays.copyOf(pinKey, n);
            pinKind = Arrays.copyOf(pinKind, n);
            buildFlags = Arrays.copyOf(buildFlags, n);
        }
        pinKey[numPins] = key;
        pinKind[numPins] = kind;
        return numPins++;
    }

    /**
     * Adds a pseudo pin, which has no port instance, such as a hub between two buses of a cell.
     * @param cellIdx Index of its cell.
     * @param pseudoIdx Its index among the pseudo pins of that cell.
     * @param name Its name (without the cell's).
     * @return Its temporary id.
     */
    int addPseudoPin(int cellIdx, int pseudoIdx, String name) {
        long key = StaGraph.makePinKey(cellIdx, StaGraph.PSEUDO_PORT_BASE + pseudoIdx);
        pseudoPinNames.put(key, name);
        return addPin(key, StaGraph.PIN_PSEUDO);
    }

    int getNumPins() {
        return numPins;
    }

    int getNumEdges() {
        return numEdges;
    }

    int getNumConnections() {
        return numConns;
    }

    /** Adds kind flags to a pin. */
    void addPinKind(int pin, byte kind) {
        pinKind[pin] |= kind;
    }

    /**
     * Marks a pin as an endpoint if, once the cycles are cut, it has in-edges but no out-edges
     * (e.g. the data input of a flip-flop).
     */
    void markEndpoint(int pin) {
        buildFlags[pin] |= CAND_END;
    }

    /**
     * Marks a pin as an endpoint if it has in-edges but no out-edges, both before and after cutting
     * the cycles (e.g. an input of a sequential cell that no timing arc continues from).
     */
    void markEndpointIfNoFanout(int pin) {
        buildFlags[pin] |= CAND_END_IF_NO_FANOUT;
    }

    /** Marks a pin as an endpoint whatever its out-edges, unless it is a startpoint. */
    void forceEndpoint(int pin) {
        buildFlags[pin] |= FORCE_END;
    }

    /**
     * Adds a connection (to a sink site pin), whose route delay the edges going through it share.
     * @param sink Its sink site pin, or null.
     * @param routeMax Its route delay at the max corner, in ps.
     * @param routeMin Its route delay at the min corner, in ps.
     * @return The connection id.
     */
    int addConnection(SitePinInst sink, float routeMax, float routeMin) {
        return addConnection(sink, routeMax, routeMin, routeMin);
    }

    /**
     * Adds a connection with its route delay at the second min corner too (see {@link #enableSecondMinCorner()};
     * ignored if not enabled).
     * @param sink Its sink site pin, or null.
     * @param routeMax Its route delay at the max corner, in ps.
     * @param routeMin Its route delay at the min corner, in ps.
     * @param routeMin2 Its route delay at the second min corner, in ps.
     * @return The connection id.
     */
    int addConnection(SitePinInst sink, float routeMax, float routeMin, float routeMin2) {
        if (numConns == connRouteMax.length) {
            int n = grow(numConns);
            connRouteMax = Arrays.copyOf(connRouteMax, n);
            connRouteMin = Arrays.copyOf(connRouteMin, n);
            if (connRouteMin2 != null) {
                connRouteMin2 = Arrays.copyOf(connRouteMin2, n);
            }
            connSink = Arrays.copyOf(connSink, n);
        }
        connRouteMax[numConns] = routeMax;
        connRouteMin[numConns] = routeMin;
        if (connRouteMin2 != null) {
            connRouteMin2[numConns] = routeMin2;
        }
        connSink[numConns] = sink;
        return numConns++;
    }

    /**
     * Adds an edge.
     * @param from Temporary id of its source pin.
     * @param to Temporary id of its sink pin.
     * @param kind Its kind (e.g. {@link StaGraph#CELL_COMB}).
     * @param fixedMax Its fixed delay at the max corner, in ps.
     * @param fixedMin Its fixed delay at the min corner, in ps.
     * @param intraMax The intra-site part of fixedMax, for reports.
     * @param conn The connection it goes through, or {@link StaGraph#NO_CONN}.
     */
    void addEdge(int from, int to, byte kind, float fixedMax, float fixedMin, float intraMax, int conn) {
        addEdge(from, to, kind, fixedMax, fixedMin, fixedMin, intraMax, conn);
    }

    /**
     * Does not merge the edges of different connections between the same two pins (see {@link #build()}): a
     * logical pin wired to several site pins, each its own connection (see {@link StaGraphBuilder}), keeps an
     * edge per site pin, so that the slowest and the fastest of them are timed.
     */
    void keepConnectionsApart() {
        keepConnectionsApart = true;
    }

    /**
     * Keeps the delays of a second min corner (see {@link StaArcProvider#hasSecondMinCorner()}); call before
     * adding edges.
     */
    void enableSecondMinCorner() {
        if (eFixedMin2 == null) {
            eFixedMin2 = Arrays.copyOf(eFixedMin, eFixedMin.length);
            connRouteMin2 = Arrays.copyOf(connRouteMin, connRouteMin.length);
        }
    }

    /**
     * Adds an edge with its fixed delay at the second min corner too.
     * @param from Temporary id of its source pin.
     * @param to Temporary id of its sink pin.
     * @param kind Its kind (e.g. {@link StaGraph#CELL_COMB}).
     * @param fixedMax Its fixed delay at the max corner, in ps.
     * @param fixedMin Its fixed delay at the min corner, in ps.
     * @param fixedMin2 Its fixed delay at the second min corner, in ps (kept if enabled).
     * @param intraMax The intra-site part of fixedMax, for reports.
     * @param conn The connection it goes through, or {@link StaGraph#NO_CONN}.
     */
    void addEdge(int from, int to, byte kind, float fixedMax, float fixedMin, float fixedMin2, float intraMax,
                 int conn) {
        if (numEdges == eFrom.length) {
            int n = grow(numEdges);
            eFrom = Arrays.copyOf(eFrom, n);
            eTo = Arrays.copyOf(eTo, n);
            eKind = Arrays.copyOf(eKind, n);
            eFixedMax = Arrays.copyOf(eFixedMax, n);
            eFixedMin = Arrays.copyOf(eFixedMin, n);
            if (eFixedMin2 != null) {
                eFixedMin2 = Arrays.copyOf(eFixedMin2, n);
            }
            eIntraMax = Arrays.copyOf(eIntraMax, n);
            eConn = Arrays.copyOf(eConn, n);
        }
        eFrom[numEdges] = from;
        eTo[numEdges] = to;
        eKind[numEdges] = kind;
        eFixedMax[numEdges] = fixedMax;
        eFixedMin[numEdges] = fixedMin;
        if (eFixedMin2 != null) {
            eFixedMin2[numEdges] = fixedMin2;
        }
        eIntraMax[numEdges] = intraMax;
        eConn[numEdges] = conn;
        numEdges++;
    }

    /** Sets the table of cells that the pin keys index. */
    void setCells(EDIFHierCellInst[] cells) {
        this.cells = cells;
    }

    /**
     * Gets the final id of a pin, once built.
     * @param tempId Its id as returned by {@link #addPin(long, byte)}.
     * @return Its id in the built graph.
     */
    int getFinalPinId(int tempId) {
        return finalId[tempId];
    }

    private static int grow(int n) {
        long m = Math.max(16L, (long) n + (n >> 1));
        if (m > Integer.MAX_VALUE - 8) {
            throw new OutOfMemoryError("StaGraphAssembler: too many elements");
        }
        return (int) m;
    }

    /**
     * Builds the graph (see the class description). The assembler must not be used afterwards,
     * other than through {@link #getFinalPinId(int)}.
     * @return The graph.
     */
    StaGraph build() {
        final int n = numPins;

        // 1. Group the edges by sink pin (stably), and merge duplicate edges into the last one added:
        // the max of their max delays, the min of their min delays (whatever the order they came in)
        int[] byTo = stableOrder(eTo, numEdges, n);
        boolean[] keep = new boolean[numEdges];
        int[] stamp = new int[n];
        int[] keptOf = new int[n];
        Arrays.fill(stamp, -1);
        int[] toStart = countStarts(eTo, numEdges, n);
        int numKept = 0;
        // (with keepConnectionsApart, the edges of another connection than the first one kept from the same pin: a
        // logical pin with several site pins, each its own connection; kept apart, so the slowest and the fastest of
        // them are timed)
        List<Integer> otherConns = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            otherConns.clear();
            for (int i = toStart[p + 1] - 1; i >= toStart[p]; i--) {
                int e = byTo[i];
                int f = eFrom[e];
                if (stamp[f] != p) {
                    stamp[f] = p;
                    keptOf[f] = e;
                    keep[e] = true;
                    numKept++;
                    continue;
                }
                int k = keptOf[f];
                if (keepConnectionsApart && eConn[e] != StaGraph.NO_CONN && eConn[k] != StaGraph.NO_CONN &&
                        eConn[e] != eConn[k]) {
                    k = -1;
                    for (int o : otherConns) {
                        if (eFrom[o] == f && eConn[o] == eConn[e]) {
                            k = o;
                            break;
                        }
                    }
                    if (k < 0) {
                        otherConns.add(e);
                        keep[e] = true;
                        numKept++;
                        continue;
                    }
                }
                if (eFixedMax[e] > eFixedMax[k]) {
                    eFixedMax[k] = eFixedMax[e];
                    eIntraMax[k] = eIntraMax[e];
                }
                eFixedMin[k] = Math.min(eFixedMin[k], eFixedMin[e]);
                if (eFixedMin2 != null) {
                    eFixedMin2[k] = Math.min(eFixedMin2[k], eFixedMin2[e]);
                }
            }
        }
        stamp = null;
        keptOf = null;
        byTo = null;
        toStart = null;

        // 2. Out-adjacency of the kept edges (stable in insertion order), and pre-cut degrees
        int[] outCount = new int[n + 1];
        int[] inDeg = new int[n];
        for (int e = 0; e < numEdges; e++) {
            if (keep[e]) {
                outCount[eFrom[e] + 1]++;
                inDeg[eTo[e]]++;
            }
        }
        for (int p = 0; p < n; p++) {
            outCount[p + 1] += outCount[p];
        }
        int[] rawOutStart = outCount;
        int[] rawOut = new int[numKept];
        int[] fill = Arrays.copyOf(rawOutStart, n);
        for (int e = 0; e < numEdges; e++) {
            if (keep[e]) {
                rawOut[fill[eFrom[e]]++] = e;
            }
        }
        fill = null;

        // Endpoint candidates that depend on the degrees before cutting cycles
        for (int p = 0; p < n; p++) {
            if ((buildFlags[p] & CAND_END_IF_NO_FANOUT) != 0 && rawOutStart[p + 1] == rawOutStart[p]
                    && inDeg[p] > 0) {
                buildFlags[p] |= CAND_END;
            }
        }

        // 3. Cut cycles: back edges of an iterative DFS
        int numCut = 0;
        int[] cutEdges = new int[16];
        {
            byte[] state = new byte[n]; // 0: unvisited, 1: on the stack, 2: done
            int[] stackPin = new int[64];
            int[] stackPos = new int[64];
            for (int root = 0; root < n; root++) {
                if (state[root] != 0) continue;
                int sp = 0;
                stackPin[0] = root;
                stackPos[0] = rawOutStart[root];
                state[root] = 1;
                while (sp >= 0) {
                    int p = stackPin[sp];
                    int pos = stackPos[sp];
                    if (pos < rawOutStart[p + 1]) {
                        stackPos[sp] = pos + 1;
                        int e = rawOut[pos];
                        if (!keep[e]) continue;
                        int q = eTo[e];
                        if (state[q] == 0) {
                            state[q] = 1;
                            sp++;
                            if (sp == stackPin.length) {
                                stackPin = Arrays.copyOf(stackPin, sp * 2);
                                stackPos = Arrays.copyOf(stackPos, sp * 2);
                            }
                            stackPin[sp] = q;
                            stackPos[sp] = rawOutStart[q];
                        } else if (state[q] == 1) {
                            keep[e] = false;
                            numKept--;
                            inDeg[q]--;
                            if (numCut == cutEdges.length) {
                                cutEdges = Arrays.copyOf(cutEdges, numCut * 2);
                            }
                            cutEdges[numCut++] = e;
                        }
                    } else {
                        state[p] = 2;
                        sp--;
                    }
                }
            }
        }

        // Post-cut out-degrees
        int[] outDeg = new int[n];
        for (int e = 0; e < numEdges; e++) {
            if (keep[e]) {
                outDeg[eFrom[e]]++;
            }
        }

        // 4. Startpoints and endpoints
        for (int p = 0; p < n; p++) {
            byte f = buildFlags[p];
            if (inDeg[p] == 0 && outDeg[p] > 0) {
                pinKind[p] |= StaGraph.PIN_START;
            } else if (((f & CAND_END) != 0 && outDeg[p] == 0 && inDeg[p] > 0) || (f & FORCE_END) != 0) {
                pinKind[p] |= StaGraph.PIN_END;
            }
        }
        buildFlags = null;

        // 5. Levelize (Kahn), numbering the pins in levelized order
        finalId = new int[n];
        int[] order = new int[n];
        int[] levelStarts = new int[16];
        int numLevels = 0;
        int head = 0;
        int tail = 0;
        for (int p = 0; p < n; p++) {
            if (inDeg[p] == 0) {
                order[tail++] = p;
            }
        }
        while (head < tail) {
            if (numLevels + 1 >= levelStarts.length) {
                levelStarts = Arrays.copyOf(levelStarts, levelStarts.length * 2);
            }
            levelStarts[numLevels++] = head;
            int levelEnd = tail;
            for (; head < levelEnd; head++) {
                int p = order[head];
                for (int i = rawOutStart[p]; i < rawOutStart[p + 1]; i++) {
                    int e = rawOut[i];
                    if (keep[e] && --inDeg[eTo[e]] == 0) {
                        order[tail++] = eTo[e];
                    }
                }
            }
        }
        if (tail != n) {
            throw new IllegalStateException("StaGraphAssembler: cycles left after cutting");
        }
        levelStarts[numLevels] = n;
        int[] levelStart = Arrays.copyOf(levelStarts, numLevels + 1);
        for (int i = 0; i < n; i++) {
            finalId[order[i]] = i;
        }
        rawOut = null;
        rawOutStart = null;
        inDeg = null;
        outDeg = null;

        long[] fPinKey = new long[n];
        byte[] fPinKind = new byte[n];
        for (int i = 0; i < n; i++) {
            fPinKey[i] = pinKey[order[i]];
            fPinKind[i] = pinKind[order[i]];
        }
        order = null;
        pinKey = null;
        pinKind = null;

        // 6. Edges sorted by final sink pin (stably, i.e. in insertion order for each sink)
        final int m = numKept;
        int[] inStart = new int[n + 1];
        for (int e = 0; e < numEdges; e++) {
            if (keep[e]) {
                inStart[finalId[eTo[e]] + 1]++;
            }
        }
        for (int p = 0; p < n; p++) {
            inStart[p + 1] += inStart[p];
        }
        int[] fFrom = new int[m];
        int[] fTo = new int[m];
        byte[] fKind = new byte[m];
        float[] fFixedMax = new float[m];
        float[] fFixedMin = new float[m];
        float[] fFixedMin2 = eFixedMin2 == null ? null : new float[m];
        float[] fIntraMax = new float[m];
        int[] fConn = new int[m];
        int[] pos = Arrays.copyOf(inStart, n);
        for (int e = 0; e < numEdges; e++) {
            if (!keep[e]) continue;
            int to = finalId[eTo[e]];
            int k = pos[to]++;
            fFrom[k] = finalId[eFrom[e]];
            fTo[k] = to;
            fKind[k] = eKind[e];
            fFixedMax[k] = eFixedMax[e];
            fFixedMin[k] = eFixedMin[e];
            if (fFixedMin2 != null) {
                fFixedMin2[k] = eFixedMin2[e];
            }
            fIntraMax[k] = eIntraMax[e];
            fConn[k] = eConn[e];
        }
        pos = null;
        int[] cutFrom = new int[numCut];
        int[] cutTo = new int[numCut];
        for (int i = 0; i < numCut; i++) {
            cutFrom[i] = finalId[eFrom[cutEdges[i]]];
            cutTo[i] = finalId[eTo[cutEdges[i]]];
        }
        eFrom = null;
        eTo = null;
        eKind = null;
        eFixedMax = null;
        eFixedMin = null;
        eFixedMin2 = null;
        eIntraMax = null;
        eConn = null;
        keep = null;

        // 7. Out-edges, and edges of the connections
        int[] outStart = countStarts(fFrom, m, n);
        int[] outEdge = stableOrder(fFrom, m, n);

        int nc = numConns;
        int[] connEdgeStart = new int[nc + 1];
        for (int e = 0; e < m; e++) {
            if (fConn[e] >= 0) {
                connEdgeStart[fConn[e] + 1]++;
            }
        }
        for (int c = 0; c < nc; c++) {
            connEdgeStart[c + 1] += connEdgeStart[c];
        }
        int[] connEdges = new int[connEdgeStart[nc]];
        int[] cpos = Arrays.copyOf(connEdgeStart, nc);
        for (int e = 0; e < m; e++) {
            if (fConn[e] >= 0) {
                connEdges[cpos[fConn[e]]++] = e;
            }
        }

        StaGraph g = new StaGraph(n, m, nc, fPinKey, fPinKind, fFrom, fTo, fKind, inStart, outStart,
                outEdge, fFixedMax, fFixedMin, fIntraMax, fConn,
                Arrays.copyOf(connRouteMax, nc), Arrays.copyOf(connRouteMin, nc), connEdgeStart,
                connEdges, Arrays.copyOf(connSink, nc), levelStart, cutFrom, cutTo, cells,
                pseudoPinNames.isEmpty() ? null : pseudoPinNames);
        if (fFixedMin2 != null) {
            g.eFixedMin2 = fFixedMin2;
            g.connRouteMin2 = Arrays.copyOf(connRouteMin2, nc);
        }
        connRouteMax = null;
        connRouteMin = null;
        connRouteMin2 = null;
        connSink = null;
        return g;
    }

    /**
     * Counts the elements of each key and returns the start of each key's range in an order
     * grouped by key.
     */
    private static int[] countStarts(int[] keys, int count, int numKeys) {
        int[] start = new int[numKeys + 1];
        for (int i = 0; i < count; i++) {
            start[keys[i] + 1]++;
        }
        for (int k = 0; k < numKeys; k++) {
            start[k + 1] += start[k];
        }
        return start;
    }

    /** Gets the indices 0..count-1 stably sorted by key (counting sort). */
    private static int[] stableOrder(int[] keys, int count, int numKeys) {
        int[] start = countStarts(keys, count, numKeys);
        int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[start[keys[i]]++] = i;
        }
        return order;
    }
}
