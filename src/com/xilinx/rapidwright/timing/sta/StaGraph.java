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

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.rwroute.Connection;

/**
 * The timing graph of the array-based static timing analysis engine (see {@link StaClockAnalysis}): a
 * struct-of-arrays, compressed sparse row (CSR) directed acyclic graph with int ids, whose topology
 * is fixed once built (see {@link StaGraphAssembler} and {@link StaGraphBuilder}). Only the delays
 * of its edges change afterwards.
 * <p>
 * <b>Pins</b> (vertices) are numbered in levelized (topological) order: all the pins of level l
 * come before those of level l+1, the pins of level l being {@code levelStart[l]} to
 * {@code levelStart[l+1]-1}. So the topological order is the identity, and a forward pass is a
 * linear sweep. A pin is identified by {@code pinKey = cellIdx << 20 | portIdx}, where cellIdx
 * indexes the {@link #getCells()} table and portIdx the sorted port instances of that cell
 * ({@link EDIFCellInst#getPortInsts()}); port indices from {@link #PSEUDO_PORT_BASE} up stand for
 * the pseudo pins (e.g. the hubs joining two buses of a hard block) that have no port instance.
 * Names are only built on demand, for reports.
 * <p>
 * <b>Edges</b> are sorted by their sink pin, so that the in-edges of pin p are the edges
 * {@code inStart[p]} to {@code inStart[p+1]-1}; the out-edges are listed by {@code outStart} and
 * {@code outEdge}. The delay of edge e at the max corner is
 * <pre>
 *   eFixedMax[e] + (eConn[e] &lt; 0 ? 0 : connRouteMax[eConn[e]])
 * </pre>
 * (likewise at the min corner): a fixed part (logic delay, intra-site delays) and the route delay
 * of the routing connection the edge goes through, if any, which the router updates. The intra-site
 * part of the fixed delay is kept apart for reports ({@code eIntraMax}).
 * <p>
 * <b>Connections</b> (routing connections to a sink site pin) map to the edges going through them
 * via {@code connEdgeStart} and {@code connEdges}.
 * <p>
 * <b>Multi-clock</b>: {@link StaClockAnalysis} keeps the times per tag (launch clock edge and
 * exception state) of each pin in flat arrays next to this graph, and {@link StaClockBinding} the
 * clocks and timing checks, so that neither the layout of this graph nor the propagation kernels
 * change.
 */
public final class StaGraph {

    /** Pin kind flag: an input pin of its cell */
    public static final byte PIN_INPUT = 1;
    /** Pin kind flag: an output pin of its cell */
    public static final byte PIN_OUTPUT = 2;
    /** Pin kind flag: a pseudo pin with no port instance, such as a hub between two buses of a cell */
    public static final byte PIN_PSEUDO = 4;
    /** Pin kind flag: a timing startpoint, with out-edges but no in-edges; its arrival time is 0 */
    public static final byte PIN_START = 16;
    /** Pin kind flag: a timing endpoint, whose required time is the timing requirement */
    public static final byte PIN_END = 32;

    /** Edge kind: an edge through a routing connection, from a source pin to a sink pin of a net */
    public static final byte NET = 0;
    /** Edge kind: an edge of a net that does not leave its site (no routing connection) */
    public static final byte NET_INTRASITE = 1;
    /** Edge kind: a combinational arc of a cell, or an arc to or from a pseudo pin */
    public static final byte CELL_COMB = 2;
    /** Edge kind: a clock-to-output arc of a sequential cell */
    public static final byte CLK_TO_Q = 3;

    /** Number of bits of the port index in a pin key */
    static final int PORT_BITS = 20;
    /** Mask of the port index in a pin key */
    static final long PORT_MASK = (1L << PORT_BITS) - 1;
    /** Port indices from this one up are those of pseudo pins */
    static final int PSEUDO_PORT_BASE = 1 << (PORT_BITS - 1);

    /** Value of {@code eConn} for an edge without a routing connection */
    public static final int NO_CONN = -1;

    final int numPins;
    final int numEdges;
    final int numConns;

    final long[] pinKey;
    final byte[] pinKind;

    final int[] eFrom;
    final int[] eTo;
    final byte[] eKind;
    /** In-edges of pin p: edges inStart[p] .. inStart[p+1]-1 (the edges are sorted by eTo) */
    final int[] inStart;
    /** Out-edges of pin p: outEdge[outStart[p]] .. outEdge[outStart[p+1]-1] */
    final int[] outStart;
    final int[] outEdge;

    final float[] eFixedMax;
    final float[] eFixedMin;
    /** Intra-site part of eFixedMax, for reports */
    final float[] eIntraMax;
    final int[] eConn;

    final float[] connRouteMax;
    final float[] connRouteMin;
    /**
     * The fixed and route delays at a second min corner (e.g. SLOW_MIN, for the slow-corner hold analysis),
     * null if the arc provider gives none (see {@link StaArcProvider#hasSecondMinCorner()})
     */
    float[] eFixedMin2;
    float[] connRouteMin2;
    /** Edges of connection c: connEdges[connEdgeStart[c]] .. connEdges[connEdgeStart[c+1]-1] */
    final int[] connEdgeStart;
    final int[] connEdges;
    /** The sink site pin of each connection, null if unknown */
    final SitePinInst[] connSink;

    /** Pins of level l: levelStart[l] .. levelStart[l+1]-1 */
    final int[] levelStart;

    /** The edges cut to break the combinational cycles, by their (final) source and sink pins */
    final int[] cutFrom;
    final int[] cutTo;

    /** Cells that the pin keys index */
    final EDIFHierCellInst[] cells;
    /** Names (relative to their cell) of the pseudo pins, by pin key */
    final Map<Long, String> pseudoPinNames;

    /** The clock ports of the endpoints that the arc provider gave one, by pin */
    Map<Integer, String> checkClockPorts = new HashMap<>();

    /** Index of the connections by sink site pin, built on demand */
    private Map<SitePinInst, Integer> connIndex;
    /** Index of the pins by name, built on demand */
    private Map<String, Integer> pinIndex;

    StaGraph(int numPins, int numEdges, int numConns, long[] pinKey, byte[] pinKind,
             int[] eFrom, int[] eTo, byte[] eKind, int[] inStart, int[] outStart, int[] outEdge,
             float[] eFixedMax, float[] eFixedMin, float[] eIntraMax, int[] eConn,
             float[] connRouteMax, float[] connRouteMin, int[] connEdgeStart, int[] connEdges,
             SitePinInst[] connSink, int[] levelStart, int[] cutFrom, int[] cutTo,
             EDIFHierCellInst[] cells, Map<Long, String> pseudoPinNames) {
        this.numPins = numPins;
        this.numEdges = numEdges;
        this.numConns = numConns;
        this.pinKey = pinKey;
        this.pinKind = pinKind;
        this.eFrom = eFrom;
        this.eTo = eTo;
        this.eKind = eKind;
        this.inStart = inStart;
        this.outStart = outStart;
        this.outEdge = outEdge;
        this.eFixedMax = eFixedMax;
        this.eFixedMin = eFixedMin;
        this.eIntraMax = eIntraMax;
        this.eConn = eConn;
        this.connRouteMax = connRouteMax;
        this.connRouteMin = connRouteMin;
        this.connEdgeStart = connEdgeStart;
        this.connEdges = connEdges;
        this.connSink = connSink;
        this.levelStart = levelStart;
        this.cutFrom = cutFrom;
        this.cutTo = cutTo;
        this.cells = cells;
        this.pseudoPinNames = pseudoPinNames == null ? Collections.emptyMap() : pseudoPinNames;
    }

    public int getNumPins() {
        return numPins;
    }

    public int getNumEdges() {
        return numEdges;
    }

    public int getNumConnections() {
        return numConns;
    }

    int getNumLevels() {
        return levelStart.length - 1;
    }

    /**
     * Gets the first pin of a level; the pins of level l are getLevelStart(l) to
     * getLevelStart(l+1)-1, and there are {@link #getNumLevels()} levels.
     * @param level The level, from 0 to {@link #getNumLevels()} included.
     * @return The id of its first pin.
     */
    int getLevelStart(int level) {
        return levelStart[level];
    }

    long getPinKey(int pin) {
        return pinKey[pin];
    }

    public byte getPinKind(int pin) {
        return pinKind[pin];
    }

    public boolean isPinKind(int pin, byte flag) {
        return (pinKind[pin] & flag) != 0;
    }

    static int getCellIndex(long pinKey) {
        return (int) (pinKey >>> PORT_BITS);
    }

    static int getPortIndex(long pinKey) {
        return (int) (pinKey & PORT_MASK);
    }

    static long makePinKey(int cellIdx, int portIdx) {
        return ((long) cellIdx << PORT_BITS) | portIdx;
    }

    public int getEdgeFrom(int edge) {
        return eFrom[edge];
    }

    public int getEdgeTo(int edge) {
        return eTo[edge];
    }

    public byte getEdgeKind(int edge) {
        return eKind[edge];
    }

    public int getEdgeConnection(int edge) {
        return eConn[edge];
    }

    public float getEdgeFixedMax(int edge) {
        return eFixedMax[edge];
    }

    public float getEdgeFixedMin(int edge) {
        return eFixedMin[edge];
    }

    /** @return The fixed delay of an edge at the second min corner, or at the min corner if the graph has none. */
    public float getEdgeFixedMin2(int edge) {
        return eFixedMin2 == null ? eFixedMin[edge] : eFixedMin2[edge];
    }

    public float getEdgeIntraSiteMax(int edge) {
        return eIntraMax[edge];
    }

    /**
     * Sets the intra-site part of an edge's fixed delay at the max corner, for reports: e.g. along with its fixed
     * delays (see {@link StaClockAnalysis#setEdgeFixedDelays(int, float, float, float)}).
     * @param edge The edge.
     * @param delay The intra-site delay in ps.
     */
    public void setEdgeIntraSiteMax(int edge, float delay) {
        eIntraMax[edge] = delay;
    }

    /**
     * Gets the delay of an edge at the max corner: its fixed part plus the route delay of its
     * connection, if it has one.
     */
    public float getEdgeDelayMax(int edge) {
        int c = eConn[edge];
        return c < 0 ? eFixedMax[edge] : eFixedMax[edge] + connRouteMax[c];
    }

    /** @return Whether the graph has the delays of a second min corner (see {@link #getEdgeDelayMin2(int)}). */
    public boolean hasSecondMinCorner() {
        return eFixedMin2 != null;
    }

    /** Gets the delay of an edge at the second min corner, e.g. SLOW_MIN (see {@link #hasSecondMinCorner()}). */
    public float getEdgeDelayMin2(int edge) {
        int c = eConn[edge];
        return c < 0 ? eFixedMin2[edge] : eFixedMin2[edge] + connRouteMin2[c];
    }

    public float getConnectionRouteMin2(int conn) {
        return connRouteMin2[conn];
    }

    /** Gets the delay of an edge at the min corner (see {@link #getEdgeDelayMax(int)}). */
    public float getEdgeDelayMin(int edge) {
        int c = eConn[edge];
        return c < 0 ? eFixedMin[edge] : eFixedMin[edge] + connRouteMin[c];
    }

    public int getInEdgeStart(int pin) {
        return inStart[pin];
    }

    public int getInEdgeEnd(int pin) {
        return inStart[pin + 1];
    }

    public int getInDegree(int pin) {
        return inStart[pin + 1] - inStart[pin];
    }

    public int getOutDegree(int pin) {
        return outStart[pin + 1] - outStart[pin];
    }

    /**
     * Gets the i-th out-edge of a pin.
     * @param pin The pin.
     * @param i From 0 to {@link #getOutDegree(int)} excluded.
     * @return The edge id.
     */
    public int getOutEdge(int pin, int i) {
        return outEdge[outStart[pin] + i];
    }

    public float getConnectionRouteMax(int conn) {
        return connRouteMax[conn];
    }

    public float getConnectionRouteMin(int conn) {
        return connRouteMin[conn];
    }

    public int getConnectionNumEdges(int conn) {
        return connEdgeStart[conn + 1] - connEdgeStart[conn];
    }

    public int getConnectionEdge(int conn, int i) {
        return connEdges[connEdgeStart[conn] + i];
    }

    public SitePinInst getConnectionSink(int conn) {
        return connSink[conn];
    }

    /**
     * Gets the id of the connection whose edges go through a site pin.
     * @param sitePin The sink site pin that the edges' sink pins are routed from.
     * @return The connection id, or {@link #NO_CONN} if no edge goes through that site pin.
     */
    synchronized int getConnectionIdOfSitePin(SitePinInst sitePin) {
        if (connIndex == null) {
            connIndex = new IdentityHashMap<>(numConns * 2);
            for (int c = 0; c < numConns; c++) {
                if (connSink[c] != null) {
                    connIndex.put(connSink[c], c);
                }
            }
        }
        Integer c = connIndex.get(sitePin);
        return c == null ? NO_CONN : c;
    }

    /**
     * Gets the id of the connection that a routing connection to a sink site pin drives: the
     * connection of that site pin if the graph has one (e.g. each site pin of a block RAM pin that
     * is on several site pins, or the data input of a clock buffer used as data), else that of
     * the site pin that the first cell pin the site pin drives
     * ({@link DesignTools#getPortInstsFromSitePinInst(SitePinInst)}) is routed from (e.g. the
     * LUT input that carries a LUT RAM's write address). Only in the second case can different
     * site pins map to the same connection.
     * @param sink The sink site pin of a routing connection.
     * @return The connection id, or {@link #NO_CONN} if the connection is not timed.
     */
    public int getConnectionId(SitePinInst sink) {
        int own = getConnectionIdOfSitePin(sink);
        if (own != NO_CONN) {
            return own;
        }
        List<EDIFHierPortInst> hports = DesignTools.getPortInstsFromSitePinInst(sink);
        if (hports.isEmpty() || sink.getSiteInst() == null) {
            return NO_CONN;
        }
        EDIFHierPortInst hport = hports.get(0);
        Design design = sink.getSiteInst().getDesign();
        Net net = sink.getNet();
        Cell cell = design == null ? null : design.getCell(hport.getFullHierarchicalInstName());
        if (cell == null || cell.getSiteInst() == null || net == null) {
            return NO_CONN;
        }
        String portName = hport.getPortInst().getName();
        if (cell.getBEL() != null && hport.isInput()) {
            String physPin = cell.getPhysicalPinMapping(portName);
            BELPin belPin = physPin == null ? null : cell.getBEL().getPin(physPin);
            if (StaGraphBuilder.isClockBELPin(belPin)) {
                // Clock pins start timing paths rather than end them
                return NO_CONN;
            }
        }
        String sitePinName = DesignTools.getRoutedSitePin(cell, net, portName);
        SitePinInst routed = sitePinName == null ? null : cell.getSiteInst().getSitePinInst(sitePinName);
        return routed == null ? NO_CONN : getConnectionIdOfSitePin(routed);
    }

    /**
     * Gets the id of the connection of a RWRoute {@link Connection}, by its sink site pin (see
     * {@link #getConnectionId(SitePinInst)}).
     * @param connection The connection.
     * @return The connection id, or {@link #NO_CONN} if not timed.
     */
    public int getConnectionId(Connection connection) {
        return getConnectionId(connection.getSink());
    }

    /**
     * Frees the indices built by {@link #getConnectionIdOfSitePin(SitePinInst)} and {@link #getPin(String)},
     * e.g. once all the router's connections were mapped.
     */
    public synchronized void releaseIndices() {
        connIndex = null;
        pinIndex = null;
    }

    public int getNumCutEdges() {
        return cutFrom.length;
    }

    int getCutEdgeFrom(int i) {
        return cutFrom[i];
    }

    int getCutEdgeTo(int i) {
        return cutTo[i];
    }

    /**
     * Gets the table of cells that the pin keys index.
     * @return The cells, by cell index.
     */
    EDIFHierCellInst[] getCells() {
        return cells;
    }

    /**
     * Gets the cell of a pin.
     * @param pin The pin.
     * @return Its cell, or null if the graph has no cell table.
     */
    public EDIFHierCellInst getCell(int pin) {
        return cells == null ? null : cells[getCellIndex(pinKey[pin])];
    }

    /**
     * Gets the port instance of a pin.
     * @param pin The pin.
     * @return Its port instance, or null for a pseudo pin or a graph without a cell table.
     */
    public EDIFPortInst getPortInst(int pin) {
        int port = getPortIndex(pinKey[pin]);
        EDIFHierCellInst cell = getCell(pin);
        if (cell == null || port >= PSEUDO_PORT_BASE) {
            return null;
        }
        return StaGraphBuilder.getPortInstList(cell.getInst()).get(port);
    }

    /**
     * Gets the hierarchical port instance of a pin.
     * @param pin The pin.
     * @return Its port instance, or null for a pseudo pin or a graph without a cell table.
     */
    public EDIFHierPortInst getHierPortInst(int pin) {
        EDIFPortInst portInst = getPortInst(pin);
        return portInst == null ? null : new EDIFHierPortInst(getCell(pin).getParent(), portInst);
    }

    /**
     * Gets the name of a pin: the hierarchical name of its cell, '/', and its port instance name
     * (or the name of the pseudo pin).
     * @param pin The pin.
     * @return Its name.
     */
    public String getPinName(int pin) {
        long key = pinKey[pin];
        int port = getPortIndex(key);
        if (cells == null) {
            String pseudo = pseudoPinNames.get(key);
            return pseudo != null ? pseudo : (getCellIndex(key) + "/" + port);
        }
        EDIFHierCellInst cell = cells[getCellIndex(key)];
        String portName = port >= PSEUDO_PORT_BASE ? pseudoPinNames.get(key)
                : StaGraphBuilder.getPortInstList(cell.getInst()).get(port).getName();
        return cell.getFullHierarchicalInstName() + "/" + portName;
    }

    /**
     * Gets the clock port that the arc provider checks an endpoint against (see
     * {@link StaArcProvider.CellArcs#forceEndpoint(int, String)}).
     * @param pin The endpoint pin.
     * @return The name of the clock port instance of its cell, or null if the provider gave none.
     */
    String getCheckClockPort(int pin) {
        return checkClockPorts.get(pin);
    }

    /**
     * Finds a pin by name (see {@link #getPinName(int)}); the first call builds an index of all the
     * names (see {@link #releaseIndices()}).
     * @param name The pin name.
     * @return The pin id, or -1 if there is no such pin.
     */
    public synchronized int getPin(String name) {
        if (pinIndex == null) {
            pinIndex = new HashMap<>(numPins * 2);
            for (int p = 0; p < numPins; p++) {
                pinIndex.put(getPinName(p), p);
            }
        }
        Integer p = pinIndex.get(name);
        return p == null ? -1 : p;
    }

    /**
     * Gets the number of bytes of the arrays indexed by pin: keys, kinds, CSR starts and level
     * starts. Analysis arrays (see {@link StaClockAnalysis#getMemoryBytes()}) are not included.
     */
    long getPinArrayBytes() {
        return 8L * pinKey.length + pinKind.length + 4L * (inStart.length + outStart.length
                + levelStart.length);
    }

    /**
     * Gets the number of bytes of the arrays indexed by edge, including the edge lists of the
     * connections.
     */
    long getEdgeArrayBytes() {
        return 4L * (eFrom.length + eTo.length + outEdge.length + eConn.length + connEdges.length
                + cutFrom.length + cutTo.length)
                + eKind.length + 4L * (eFixedMax.length + eFixedMin.length + eIntraMax.length)
                + (eFixedMin2 == null ? 0 : 4L * eFixedMin2.length);
    }

    /**
     * Gets the number of bytes of the arrays indexed by connection, excluding their edge lists
     * (see {@link #getEdgeArrayBytes()}) and the site pins they refer to, but including the
     * references to them.
     */
    long getConnectionArrayBytes() {
        return 4L * (connRouteMax.length + connRouteMin.length + connEdgeStart.length)
                + (connRouteMin2 == null ? 0 : 4L * connRouteMin2.length) + 8L * connSink.length;
    }

    /**
     * Gets the number of bytes of the cell table (references only).
     */
    long getCellArrayBytes() {
        return cells == null ? 0 : 8L * cells.length;
    }

    @Override
    public String toString() {
        return "StaGraph{pins=" + numPins + ", edges=" + numEdges + ", connections=" + numConns
                + ", levels=" + getNumLevels() + ", cut=" + cutFrom.length + "}";
    }
}
