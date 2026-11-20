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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.util.ParallelismTools;

/**
 * Builds the {@link StaGraph} of a {@link Design}, with the arcs and delays of a
 * {@link FamilyArcProvider}:
 * <ol>
 * <li>One pass over the placed leaf cells, which form the cell table that the pin keys index, adds
 * the cells' timing arcs, in parallel chunks. Pins are only created when an edge touches them, their
 * ids coming from a temporary array indexed by cell and port instance that is freed once built.</li>
 * <li>One pass over the nets adds, for each net that is neither a clock nor a static net (but for the
 * clock nets of the clock buffers used as data, see {@link DataClockBuffers}), an edge from its source
 * pin to each of its sink pins (the leaf cell pins that it connects, see
 * {@link EDIFNetlist#getPhysicalPins(Net)}) but its clock pins, which start the timing paths through
 * the clock-to-output arcs of their cells. The edges whose sink pin is routed from a site pin go
 * through the connection to that site pin, all the edges to the same site pin sharing it; every
 * site pin of a block RAM pin or LUT RAM address that is on several site pins gets its own. A net
 * that has no site pins at all (e.g. in a placed design whose nets were never routed, as Vivado
 * writes it) goes through the site pins that its site routing uses all the same: the graph gets
 * site pins of its own for them, which are not added to the design, and their connections the
 * estimated route delays of unrouted connections.</li>
 * <li>The assembly then cuts cycles, levelizes and builds the CSR arrays.</li>
 * </ol>
 */
public class StaGraphBuilder {

    /** Number of cells per chunk of the parallel cell pass */
    private static final int CELL_CHUNK = 4096;
    /** Number of nets per chunk of the parallel net pass */
    private static final int NET_CHUNK = 1024;

    private final Design design;
    private final StaArcProvider provider;
    private boolean parallel = ParallelismTools.getParallel();
    /** The nets to time, null for all */
    private Collection<Net> timedNets;
    /** Whether only the cells of the unrouted timed nets get cell arcs */
    private boolean onlyCellsOfTimedNets;
    /** Which cells get cell arcs, by cell index; null for all */
    private boolean[] arcCells;

    /** Runtime of the phases of the last build, in ns */
    private long cellPassNanos;
    private long netPassNanos;
    private long netArcNanos;
    private long assembleNanos;

    // Build state, freed after build()
    private StaGraphAssembler asm;
    private EDIFHierCellInst[] cells;
    private Cell[] physCells;
    private int[] portBase;
    private int[] portPin;
    private Map<Long, Integer> pseudoPin;
    /** The clock ports of the endpoint marks that have one, by the index in their mark */
    private final List<String> markClocks = new ArrayList<>();
    /** The cells of the table by their instance, compared by identity */
    private Map<InstKey, Integer> cellIndex;
    /** The output nets of the clock buffers used as data, see {@link DataClockBuffers} */
    private Set<Net> dataClockBufferNets;
    /** Whether each cell is a clock buffer used as data, by cell index; null if none */
    private boolean[] dataClockBuffers;
    /** The site pins of the graph's own for the nets without site pins, by site instance and name */
    private final Map<SiteInst, Map<String, SitePinInst>> detachedSitePins = new ConcurrentHashMap<>();

    /**
     * A cell instance (a leaf instance and its parent instance), hashed and compared by the identity
     * of the instances along its hierarchy, as EDIFCellInst.equals() and hashCode() compare names
     * and contents, which is slow and collides a lot in designs reusing hierarchical cells.
     */
    private static final class InstKey {
        private final EDIFCellInst leaf;
        private final List<EDIFCellInst> parent;
        private final int hash;

        InstKey(EDIFHierCellInst parent, EDIFCellInst leaf) {
            this.leaf = leaf;
            this.parent = parent.getFullHierarchy();
            int h = System.identityHashCode(leaf);
            for (EDIFCellInst inst : this.parent) {
                h = 31 * h + System.identityHashCode(inst);
            }
            hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof InstKey)) return false;
            InstKey k = (InstKey) o;
            if (k.hash != hash || k.leaf != leaf || k.parent.size() != parent.size()) return false;
            // The deepest instances differ most
            for (int i = parent.size() - 1; i >= 0; i--) {
                if (k.parent.get(i) != parent.get(i)) return false;
            }
            return true;
        }
    }

    /**
     * Finds the cell of the table that a cell pin belongs to, as design.getCell() of its name would,
     * without building that name.
     * @return The cell index, or -1 if the pin's cell is not in the table.
     */
    private int getCellIndex(EDIFHierPortInst hport) {
        EDIFCellInst leaf = hport.getPortInst().getCellInst();
        if (leaf == null) {
            return -1;
        }
        Integer idx = cellIndex.get(new InstKey(hport.getHierarchicalInst(), leaf));
        return idx == null ? -1 : idx;
    }

    /**
     * Creates the builder of a design's timing graph.
     * @param design The design.
     * @param provider The arcs and delays of the graph.
     */
    public StaGraphBuilder(Design design, FamilyArcProvider provider) {
        this.design = design;
        this.provider = provider;
    }

    /**
     * Restricts the timing graph to some nets, e.g. when partially routing: only these nets get
     * edges, and, if asked, only the cells that they connect get cell arcs.
     * @param nets The nets to time, or null for all (the default).
     * @param onlyCellsOfTimedNets True to only give cell arcs to the cells that these nets
     *                             connect: those being routed (with or without routing yet; a
     *                             net that PartialRouter routes keeps its routing until it is
     *                             done), those that never leave their site and the clock nets
     *                             of clock buffers used as data, which are timed as data nets.
     */
    public void setTimedNets(Collection<Net> nets, boolean onlyCellsOfTimedNets) {
        this.timedNets = nets;
        this.onlyCellsOfTimedNets = nets != null && onlyCellsOfTimedNets;
    }

    /** Sets whether the cell pass may run in parallel. */
    void setParallel(boolean parallel) {
        this.parallel = parallel;
    }

    /**
     * Gets a summary of the runtime of the phases of the last build.
     * @return The runtimes of the cell pass, the net pass and the assembly, in seconds.
     */
    String getBuildTimes() {
        return String.format("cells %.3fs, nets %.3fs (net delays %.3fs), assemble %.3fs",
                cellPassNanos * 1e-9, netPassNanos * 1e-9, netArcNanos * 1e-9, assembleNanos * 1e-9);
    }

    /**
     * Gets the sorted port instances of a cell instance as a list.
     * @param inst The cell instance.
     * @return Its port instances, sorted by name.
     */
    static List<EDIFPortInst> getPortInstList(EDIFCellInst inst) {
        Collection<EDIFPortInst> portInsts = inst.getPortInsts();
        return portInsts instanceof List ? (List<EDIFPortInst>) portInsts : new ArrayList<>(portInsts);
    }

    /**
     * Finds a port instance by name in the sorted port instances of a cell instance.
     * @return Its index, or -1 if none.
     */
    static int getPortIndex(List<EDIFPortInst> ports, String name) {
        int lo = 0;
        int hi = ports.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int c = ports.get(mid).getName().compareTo(name);
            if (c < 0) {
                lo = mid + 1;
            } else if (c > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        // Not found by binary search: the list may not be sorted
        for (int i = 0; i < ports.size(); i++) {
            if (ports.get(i).getName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Builds the graph.
     * @return The graph.
     */
    public StaGraph build() {
        asm = new StaGraphAssembler();
        if (provider.hasSecondMinCorner()) {
            asm.enableSecondMinCorner();
        }
        asm.keepConnectionsApart();
        pseudoPin = new HashMap<>();

        long t0 = System.nanoTime();
        netArcNanos = 0;
        List<EDIFHierCellInst> cellList = new ArrayList<>();
        List<Cell> physList = new ArrayList<>();
        for (Cell c : design.getCells()) {
            if (c.isRoutethru()) continue;
            EDIFHierCellInst inst = c.getEDIFHierCellInst();
            if (inst == null || !inst.getCellType().isPrimitive()) continue;
            cellList.add(inst);
            physList.add(c);
        }
        int numCells = cellList.size();
        cells = cellList.toArray(new EDIFHierCellInst[0]);
        physCells = physList.toArray(new Cell[0]);
        cellList = null;
        physList = null;
        cellIndex = new HashMap<>(numCells * 2);
        portBase = new int[numCells + 1];
        for (int i = 0; i < numCells; i++) {
            EDIFHierCellInst parent = cells[i].getParent();
            EDIFCellInst leaf = cells[i].getInst();
            cellIndex.put(new InstKey(parent, leaf), i);
            portBase[i + 1] = portBase[i] + cells[i].getInst().getPortInsts().size();
        }
        portPin = new int[portBase[numCells]];
        Arrays.fill(portPin, -1);
        // (The arc providers may look up the physical nets of cell pins, from several threads)
        design.getNetlist().getParentNetMap();
        findDataClockBuffers();

        // 1. Cell arcs
        arcCells = null;
        if (onlyCellsOfTimedNets) {
            arcCells = new boolean[numCells];
            for (Net net : timedNets) {
                // As the net edges below: a clock net only if it is driven by a clock buffer used as data
                if (net.isStaticNet() || net.isClockNet() && !dataClockBufferNets.contains(net)) continue;
                // A net that never leaves its site has no sink site pins (e.g. a LUT driving the
                // flip-flop beside it), but its cells' arcs are as needed as those of one that does
                if (!net.getSinkPins().isEmpty() && !RouterHelper.isRoutableNetWithSourceSinks(net)) continue;
                List<EDIFHierPortInst> hports = design.getNetlist().getPhysicalPins(net);
                if (hports == null) continue;
                for (EDIFHierPortInst hport : hports) {
                    int cellIdx = getCellIndex(hport);
                    if (cellIdx >= 0) {
                        arcCells[cellIdx] = true;
                    }
                }
            }
        }
        List<int[]> marks = new ArrayList<>();
        int numChunks = (numCells + CELL_CHUNK - 1) / CELL_CHUNK;
        if (parallel && numChunks > 1) {
            List<Future<ArcBuffer>> futures = new ArrayList<>();
            for (int k = 0; k < numChunks; k++) {
                final int from = k * CELL_CHUNK;
                final int to = Math.min(numCells, from + CELL_CHUNK);
                futures.add(ParallelismTools.submit(() -> collectCellArcs(from, to)));
            }
            for (Future<ArcBuffer> f : futures) {
                commit(ParallelismTools.get(f), marks);
            }
        } else {
            for (int k = 0; k < numChunks; k++) {
                int from = k * CELL_CHUNK;
                commit(collectCellArcs(from, Math.min(numCells, from + CELL_CHUNK)), marks);
            }
        }
        long t1 = System.nanoTime();
        cellPassNanos = t1 - t0;

        // 2. Net edges, gathered per chunk of nets (in parallel) and added in net order
        List<Net> nets = new ArrayList<>();
        for (Net net : timedNets != null ? timedNets : design.getNets()) {
            if (net.isStaticNet() || net.isClockNet() && !dataClockBufferNets.contains(net)) continue;
            nets.add(net);
        }
        Map<SitePinInst, Integer> connOfSink = new IdentityHashMap<>();
        EDIFNetlist netlist = design.getNetlist();
        int numNetChunks = (nets.size() + NET_CHUNK - 1) / NET_CHUNK;
        if (parallel && numNetChunks > 1) {
            List<Future<NetEdges>> futures = new ArrayList<>();
            for (int k = 0; k < numNetChunks; k++) {
                final int from = k * NET_CHUNK;
                final int to = Math.min(nets.size(), from + NET_CHUNK);
                futures.add(ParallelismTools.submit(() -> collectNetEdges(nets, from, to, netlist)));
            }
            for (int k = 0; k < numNetChunks; k++) {
                commit(ParallelismTools.get(futures.get(k)), connOfSink);
                // (Its edges are in the graph now)
                futures.set(k, null);
            }
        } else {
            for (int k = 0; k < numNetChunks; k++) {
                int from = k * NET_CHUNK;
                commit(collectNetEdges(nets, from, Math.min(nets.size(), from + NET_CHUNK), netlist), connOfSink);
            }
        }
        connOfSink = null;
        dataClockBufferNets = null;
        dataClockBuffers = null;
        long t2 = System.nanoTime();
        netPassNanos = t2 - t1;

        // Endpoint marks of the cells, on the pins that have edges
        Map<Integer, String> checkClocks = new HashMap<>();
        for (int[] m : marks) {
            int pin = existingPin(m[0], m[1]);
            if (pin < 0) continue;
            if (m[2] == ArcBuffer.MARK_IF_NO_FANOUT) {
                asm.markEndpointIfNoFanout(pin);
            } else {
                asm.forceEndpoint(pin);
                if (m[3] >= 0) {
                    checkClocks.put(pin, markClocks.get(m[3]));
                }
            }
        }
        markClocks.clear();

        asm.setCells(cells);
        StaGraph g = asm.build();
        for (Map.Entry<Integer, String> e : checkClocks.entrySet()) {
            g.checkClockPorts.put(asm.getFinalPinId(e.getKey()), e.getValue());
        }
        assembleNanos = System.nanoTime() - t2;

        asm = null;
        cells = null;
        physCells = null;
        portBase = null;
        portPin = null;
        pseudoPin = null;
        cellIndex = null;
        return g;
    }

    /** Finds the clock buffers used as data among the drivers of the timed clock nets, see {@link DataClockBuffers} */
    private void findDataClockBuffers() {
        dataClockBufferNets = Collections.newSetFromMap(new IdentityHashMap<>());
        dataClockBuffers = null;
        EDIFNetlist netlist = design.getNetlist();
        for (Net net : timedNets != null ? timedNets : design.getNets()) {
            if (!net.isClockNet() || net.isStaticNet()) continue;
            EDIFHierCellInst buffer = DataClockBuffers.getDataClockBuffer(netlist, net);
            if (buffer == null) continue;
            Integer cellIdx = cellIndex.get(new InstKey(buffer.getParent(), buffer.getInst()));
            if (cellIdx == null) continue;
            dataClockBufferNets.add(net);
            if (dataClockBuffers == null) {
                dataClockBuffers = new boolean[cells.length];
            }
            dataClockBuffers[cellIdx] = true;
        }
    }

    /** Gets (creating it if need be) the pin of a port instance of a cell. */
    private int portPin(int cellIdx, int portIdx) {
        int d = portBase[cellIdx] + portIdx;
        int pin = portPin[d];
        if (pin < 0) {
            EDIFPortInst portInst = getPortInstList(cells[cellIdx].getInst()).get(portIdx);
            byte kind = 0;
            if (portInst.isInput()) kind |= StaGraph.PIN_INPUT;
            if (portInst.isOutput()) kind |= StaGraph.PIN_OUTPUT;
            pin = asm.addPin(StaGraph.makePinKey(cellIdx, portIdx), kind);
            portPin[d] = pin;
        }
        return pin;
    }

    /** Gets the pin of a cell for a port index or pseudo pin handle, -1 if it was not created. */
    private int existingPin(int cellIdx, int handle) {
        if (handle >= 0) {
            return portPin[portBase[cellIdx] + handle];
        }
        Integer pin = pseudoPin.get(StaGraph.makePinKey(cellIdx, StaGraph.PSEUDO_PORT_BASE - handle - 1));
        return pin == null ? -1 : pin;
    }

    private int pinOf(int cellIdx, int handle) {
        if (handle >= 0) {
            return portPin(cellIdx, handle);
        }
        Integer pin = pseudoPin.get(StaGraph.makePinKey(cellIdx, StaGraph.PSEUDO_PORT_BASE - handle - 1));
        if (pin == null) {
            throw new IllegalStateException("Unknown pseudo pin handle " + handle);
        }
        return pin;
    }

    /** Adds the arcs gathered from a chunk of cells to the graph, in order. */
    private void commit(ArcBuffer b, List<int[]> marks) {
        for (int i = 0; i < b.numPseudo; i++) {
            int cellIdx = b.pseudoCell[i];
            int idx = b.pseudoIdx[i];
            int pin = asm.addPseudoPin(cellIdx, idx, b.pseudoName.get(i));
            pseudoPin.put(StaGraph.makePinKey(cellIdx, StaGraph.PSEUDO_PORT_BASE + idx), pin);
        }
        for (int i = 0; i < b.numArcs; i++) {
            int cellIdx = b.cell[i];
            int from = pinOf(cellIdx, b.from[i]);
            int to = pinOf(cellIdx, b.to[i]);
            asm.addEdge(from, to, b.kind[i], b.dMax[i], b.dMin[i], b.dMin2[i], 0f, StaGraph.NO_CONN);
        }
        for (int i = 0; i < b.numMarks; i++) {
            String clock = b.markClock.get(i);
            int ci = -1;
            if (clock != null) {
                ci = markClocks.size();
                markClocks.add(clock);
            }
            marks.add(new int[]{b.markCell[i], b.markPin[i], b.markType[i], ci});
        }
    }

    /** Gathers the edges of a chunk of nets */
    private NetEdges collectNetEdges(List<Net> nets, int from, int to, EDIFNetlist netlist) {
        NetEdges edges = new NetEdges();
        NetPins np = new NetPins();
        StaArcProvider.NetArc arc = new StaArcProvider.NetArc();
        for (int i = from; i < to; i++) {
            addNetEdges(nets.get(i), netlist, np, arc, edges);
        }
        return edges;
    }

    /** Adds the edges gathered from a chunk of nets to the graph, in order. */
    private void commit(NetEdges b, Map<SitePinInst, Integer> connOfSink) {
        for (int i = 0; i < b.numEdges; i++) {
            int srcPin = portPin(b.srcCell[i], b.srcPort[i]);
            int snkPin = portPin(b.snkCell[i], b.snkPort[i]);
            if ((b.flags[i] & NetEdges.FLIP_FLOP_SINK) != 0) {
                asm.markEndpoint(snkPin);
            }
            SitePinInst sinkSitePin = b.sinkSitePin[i];
            int conn = StaGraph.NO_CONN;
            byte kind = StaGraph.NET_INTRASITE;
            if (sinkSitePin != null) {
                kind = StaGraph.NET;
                Integer c = connOfSink.get(sinkSitePin);
                if (c == null) {
                    c = asm.addConnection(sinkSitePin, b.routeMax[i], b.routeMin[i], b.routeMin2[i]);
                    connOfSink.put(sinkSitePin, c);
                    if (sinkSitePin instanceof DetachedSitePin) {
                        ((DetachedSitePin) sinkSitePin).source = b.sourceSitePin[i];
                    }
                }
                conn = c;
            }
            asm.addEdge(srcPin, snkPin, kind, b.fixedMax[i], b.fixedMin[i], b.fixedMin2[i], b.intraSiteMax[i], conn);
        }
        netArcNanos += b.netArcNanos;
    }

    /** The net edges of a chunk of nets, before they enter the graph: their pins and delays */
    private static final class NetEdges {
        static final byte FLIP_FLOP_SINK = 1;

        int numEdges;
        int[] srcCell = new int[256];
        int[] srcPort = new int[256];
        int[] snkCell = new int[256];
        int[] snkPort = new int[256];
        byte[] flags = new byte[256];
        SitePinInst[] sinkSitePin = new SitePinInst[256];
        SitePinInst[] sourceSitePin = new SitePinInst[256];
        float[] fixedMax = new float[256];
        float[] fixedMin = new float[256];
        float[] fixedMin2 = new float[256];
        float[] intraSiteMax = new float[256];
        float[] routeMax = new float[256];
        float[] routeMin = new float[256];
        float[] routeMin2 = new float[256];
        long netArcNanos;

        /** Adds an edge with the pins and delays of a net arc (second min delays NaN: those of the min corner) */
        void add(int srcCellIdx, int srcPortIdx, int snkCellIdx, int snkPortIdx, byte flag,
                 StaArcProvider.NetArc arc) {
            int i = numEdges;
            if (i == srcCell.length) {
                int n = i * 2;
                srcCell = Arrays.copyOf(srcCell, n);
                srcPort = Arrays.copyOf(srcPort, n);
                snkCell = Arrays.copyOf(snkCell, n);
                snkPort = Arrays.copyOf(snkPort, n);
                flags = Arrays.copyOf(flags, n);
                sinkSitePin = Arrays.copyOf(sinkSitePin, n);
                sourceSitePin = Arrays.copyOf(sourceSitePin, n);
                fixedMax = Arrays.copyOf(fixedMax, n);
                fixedMin = Arrays.copyOf(fixedMin, n);
                fixedMin2 = Arrays.copyOf(fixedMin2, n);
                intraSiteMax = Arrays.copyOf(intraSiteMax, n);
                routeMax = Arrays.copyOf(routeMax, n);
                routeMin = Arrays.copyOf(routeMin, n);
                routeMin2 = Arrays.copyOf(routeMin2, n);
            }
            srcCell[i] = srcCellIdx;
            srcPort[i] = srcPortIdx;
            snkCell[i] = snkCellIdx;
            snkPort[i] = snkPortIdx;
            flags[i] = flag;
            sinkSitePin[i] = arc.sinkSitePin;
            sourceSitePin[i] = arc.sourceSitePin;
            fixedMax[i] = arc.fixedMax;
            fixedMin[i] = arc.fixedMin;
            fixedMin2[i] = Float.isNaN(arc.fixedMin2) ? arc.fixedMin : arc.fixedMin2;
            intraSiteMax[i] = arc.intraSiteMax;
            routeMax[i] = arc.routeMax;
            routeMin[i] = arc.routeMin;
            routeMin2[i] = Float.isNaN(arc.routeMin2) ? arc.routeMin : arc.routeMin2;
            numEdges++;
        }
    }

    private ArcBuffer collectCellArcs(int from, int to) {
        ArcBuffer buffer = new ArcBuffer();
        CellContext ctx = new CellContext(buffer);
        for (int i = from; i < to; i++) {
            ctx.reset(i, physCells[i], cells[i], arcCells == null || arcCells[i]);
            provider.addCellArcs(ctx);
        }
        return buffer;
    }

    /** The arcs, pseudo pins and endpoint marks of a chunk of cells, before they become edges */
    private static final class ArcBuffer {
        static final byte MARK_IF_NO_FANOUT = 0;
        static final byte MARK_FORCE = 1;

        int numArcs;
        int[] cell = new int[256];
        int[] from = new int[256];
        int[] to = new int[256];
        byte[] kind = new byte[256];
        float[] dMax = new float[256];
        float[] dMin = new float[256];
        float[] dMin2 = new float[256];

        int numPseudo;
        int[] pseudoCell = new int[16];
        int[] pseudoIdx = new int[16];
        List<String> pseudoName = new ArrayList<>();

        int numMarks;
        int[] markCell = new int[64];
        int[] markPin = new int[64];
        byte[] markType = new byte[64];
        List<String> markClock = new ArrayList<>();

        void addArc(int c, int f, int t, byte k, float max, float min, float min2) {
            if (numArcs == cell.length) {
                int n = numArcs * 2;
                cell = Arrays.copyOf(cell, n);
                from = Arrays.copyOf(from, n);
                to = Arrays.copyOf(to, n);
                kind = Arrays.copyOf(kind, n);
                dMax = Arrays.copyOf(dMax, n);
                dMin = Arrays.copyOf(dMin, n);
                dMin2 = Arrays.copyOf(dMin2, n);
            }
            cell[numArcs] = c;
            from[numArcs] = f;
            to[numArcs] = t;
            kind[numArcs] = k;
            dMax[numArcs] = max;
            dMin[numArcs] = min;
            dMin2[numArcs] = min2;
            numArcs++;
        }

        void addPseudo(int c, int idx, String name) {
            if (numPseudo == pseudoCell.length) {
                pseudoCell = Arrays.copyOf(pseudoCell, numPseudo * 2);
                pseudoIdx = Arrays.copyOf(pseudoIdx, numPseudo * 2);
            }
            pseudoCell[numPseudo] = c;
            pseudoIdx[numPseudo] = idx;
            pseudoName.add(name);
            numPseudo++;
        }

        void addMark(int c, int pin, byte type) {
            addMark(c, pin, type, null);
        }

        void addMark(int c, int pin, byte type, String clock) {
            if (numMarks == markCell.length) {
                markCell = Arrays.copyOf(markCell, numMarks * 2);
                markPin = Arrays.copyOf(markPin, numMarks * 2);
                markType = Arrays.copyOf(markType, numMarks * 2);
            }
            markCell[numMarks] = c;
            markPin[numMarks] = pin;
            markType[numMarks] = type;
            markClock.add(clock);
            numMarks++;
        }
    }

    /** The cell given to {@link StaArcProvider#addCellArcs}, recording into an {@link ArcBuffer} */
    private static final class CellContext implements StaArcProvider.CellArcs {
        private final ArcBuffer buffer;
        private final Map<String, Integer> pseudoByName = new HashMap<>();
        private int cellIdx;
        private Cell cell;
        private EDIFHierCellInst inst;
        private List<EDIFPortInst> ports;
        private String name;
        /** Whether the arcs of the cell are kept (its endpoint marks always are) */
        private boolean arcsKept;

        CellContext(ArcBuffer buffer) {
            this.buffer = buffer;
        }

        void reset(int cellIdx, Cell cell, EDIFHierCellInst inst, boolean arcsKept) {
            this.cellIdx = cellIdx;
            this.arcsKept = arcsKept;
            this.cell = cell;
            this.inst = inst;
            ports = null;
            name = null;
            pseudoByName.clear();
        }

        @Override
        public Cell getCell() {
            return cell;
        }

        @Override
        public EDIFHierCellInst getHierCellInst() {
            return inst;
        }

        @Override
        public EDIFCellInst getCellInst() {
            return inst.getInst();
        }

        @Override
        public String getName() {
            if (name == null) {
                name = inst.getFullHierarchicalInstName();
            }
            return name;
        }

        @Override
        public List<EDIFPortInst> getPortInsts() {
            if (ports == null) {
                ports = getPortInstList(inst.getInst());
            }
            return ports;
        }

        @Override
        public int getPortIndex(String portName) {
            return StaGraphBuilder.getPortIndex(getPortInsts(), portName);
        }

        @Override
        public int getPseudoPin(String pseudoName) {
            Integer idx = pseudoByName.get(pseudoName);
            if (idx == null) {
                idx = pseudoByName.size();
                if (idx >= StaGraph.PSEUDO_PORT_BASE) {
                    throw new IllegalStateException("Too many pseudo pins on cell " + getName());
                }
                pseudoByName.put(pseudoName, idx);
                if (arcsKept) {
                    buffer.addPseudo(cellIdx, idx, pseudoName);
                }
            }
            return -idx - 1;
        }

        @Override
        public void addArc(int from, int to, byte kind, float delayMax, float delayMin) {
            addArc(from, to, kind, delayMax, delayMin, delayMin);
        }

        @Override
        public void addArc(int from, int to, byte kind, float delayMax, float delayMin, float delayMin2) {
            if (arcsKept) {
                buffer.addArc(cellIdx, from, to, kind, delayMax, delayMin, delayMin2);
            }
        }

        @Override
        public void markEndpointIfNoFanout(int pin) {
            buffer.addMark(cellIdx, pin, ArcBuffer.MARK_IF_NO_FANOUT);
        }

        @Override
        public void forceEndpoint(int pin) {
            buffer.addMark(cellIdx, pin, ArcBuffer.MARK_FORCE);
        }

        @Override
        public void forceEndpoint(int pin, String clockPort) {
            buffer.addMark(cellIdx, pin, ArcBuffer.MARK_FORCE, clockPort);
        }
    }

    /** Scratch lists of the source and sink pins of a net */
    private static final class NetPins {
        final List<EDIFHierPortInst> srcPort = new ArrayList<>();
        final List<SitePinInst> srcSitePins = new ArrayList<>();
        int[] srcCell = new int[4];
        int[] srcPortIdx = new int[4];
        final List<EDIFHierPortInst> snkPort = new ArrayList<>();
        final List<Cell> snkCell = new ArrayList<>();
        final List<BELPin> snkBELPin = new ArrayList<>();
        final List<SitePinInst> snkSitePin = new ArrayList<>();
        int[] snkCellIdx = new int[16];
        int[] snkPortIdx = new int[16];

        void clear() {
            srcPort.clear();
            srcSitePins.clear();
            snkPort.clear();
            snkCell.clear();
            snkBELPin.clear();
            snkSitePin.clear();
        }

        void addSource(EDIFHierPortInst port, int cellIdx, int portIdx) {
            int i = srcPort.size();
            if (i == srcCell.length) {
                srcCell = Arrays.copyOf(srcCell, i * 2);
                srcPortIdx = Arrays.copyOf(srcPortIdx, i * 2);
            }
            srcCell[i] = cellIdx;
            srcPortIdx[i] = portIdx;
            srcPort.add(port);
        }

        void addSink(EDIFHierPortInst port, int cellIdx, int portIdx, Cell cell, BELPin belPin,
                     SitePinInst sitePin) {
            int i = snkPort.size();
            if (i == snkCellIdx.length) {
                snkCellIdx = Arrays.copyOf(snkCellIdx, i * 2);
                snkPortIdx = Arrays.copyOf(snkPortIdx, i * 2);
            }
            snkCellIdx[i] = cellIdx;
            snkPortIdx[i] = portIdx;
            snkPort.add(port);
            snkCell.add(cell);
            snkBELPin.add(belPin);
            snkSitePin.add(sitePin);
        }
    }

    /**
     * Checks if an input pin of a placed cell is a clock pin: its BEL pin is a clock pin. The registers of the
     * Versal XPHY bus logic interface (BEL type BLI_DFF in the BLI_* sites) are clocked through their CLK BEL pin,
     * which the device data does not mark as a clock pin. The graph has no net edge into the clock pins of cells
     * (but those of clock buffers used as data): their timing paths start at their clock-to-output arcs.
     * @param belPin The BEL pin of the cell pin, or null.
     * @return True if the pin is a clock pin.
     */
    public static boolean isClockBELPin(BELPin belPin) {
        if (belPin == null) {
            return false;
        }
        return belPin.isClock() || belPin.getName().equals("CLK") && belPin.getBEL().getBELType().equals("BLI_DFF");
    }

    private static boolean isFlipFlopType(String type) {
        return type.equals("FDRE") || type.equals("FDSE") || type.equals("FDPE") || type.equals("FDCE");
    }

    /**
     * Adds a sink for each other site pin, on the same net, of a RAM cell's logical pin: the input pins of a RAMB36
     * (address, write enable, ...) are wired to a site pin of each of its RAMB18 halves, and Vivado times every one
     * of them (the slowest at the max corner, the fastest at the min corners); the address of a LUT RAM is on the
     * read address of its LUT and on the write address of its site, which come from different site pins. Each gets
     * its own connection (see {@link StaGraphAssembler#keepConnectionsApart()}).
     */
    private void addOtherSitePins(EDIFHierPortInst hport, Net net, int cellIdx, int portIdx, Cell cell,
                                  BELPin belPin, SitePinInst spi, NetPins np, boolean noSitePins) {
        String type = cell.getType();
        boolean blockRam = type != null && type.startsWith("RAMB");
        if (!blockRam && (cell.getBEL() == null || !cell.getBEL().isLUT())) {
            return;
        }
        Set<String> phys = cell.getAllPhysicalPinMappings(hport.getPortInst().getName());
        if (phys == null || phys.size() < 2) {
            return;
        }
        SiteInst si = cell.getSiteInst();
        BEL bel = si.getBEL(cell.getBELName());
        if (bel == null) {
            return;
        }
        List<String> sorted = new ArrayList<>(phys);
        Collections.sort(sorted);
        for (String pp : sorted) {
            BELPin bp = bel.getPin(pp.replace("[", "").replace("]", ""));
            if (bp == null || bp == belPin) {
                continue;
            }
            // (A LUT's write address goes through the site to a site pin of another LUT)
            String sp = blockRam ? bp.getConnectedSitePinName()
                    : DesignTools.getRoutedSitePinFromPhysicalPin(cell, net, bp.getName());
            SitePinInst other = sp == null ? null : si.getSitePinInst(sp);
            if (other == null && sp != null && noSitePins && si.getNetFromSiteWire(bp.getSiteWireName()) == net) {
                other = getDetachedSitePin(si, sp, false);
            } else if (other != null && other.getNet() != net) {
                continue;
            }
            if (other == null || other == spi) {
                continue;
            }
            np.addSink(hport, cellIdx, portIdx, cell, bp, other);
        }
    }

    /**
     * Gets a site pin of the graph's own, for a net without site pins (see the class description): a pin of the site
     * instance that is neither added to it nor to a net (see {@link DetachedSitePin}).
     * @param si The site instance.
     * @param name The name of the site pin.
     * @param output Whether it is an output pin.
     * @return The site pin, the same for the same site instance and name.
     */
    private SitePinInst getDetachedSitePin(SiteInst si, String name, boolean output) {
        return detachedSitePins.computeIfAbsent(si, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, k -> new DetachedSitePin(si, name, output));
    }

    /**
     * A site pin of a site instance that is not part of the site instance (nor of a net): it has the site, tile and
     * node of the site pin, but placing a {@link SitePinInst} on a site instance would add it there and change the
     * site routing of the design.
     */
    static final class DetachedSitePin extends SitePinInst {
        private static final long serialVersionUID = 1L;
        private final transient SiteInst siteInst;
        /** The source site pin of the connection to this (sink) pin, for want of a net */
        private transient SitePinInst source;

        DetachedSitePin(SiteInst siteInst, String name, boolean output) {
            this.siteInst = siteInst;
            setPinName(name);
            setIsOutputPin(output);
        }

        /** @return The source site pin of the connection to this sink pin, or null. */
        SitePinInst getSource() {
            return source;
        }

        @Override
        public SiteInst getSiteInst() {
            return siteInst;
        }

        @Override
        public String getSiteInstName() {
            return siteInst.getName();
        }

        @Override
        public Site getSite() {
            return siteInst.getSite();
        }

        @Override
        public Tile getTile() {
            return siteInst.getTile();
        }

        @Override
        public SiteTypeEnum getSiteTypeEnum() {
            return siteInst.getSiteTypeEnum();
        }

        @Override
        public Node getConnectedNode() {
            return siteInst.getSite().getConnectedNode(getName());
        }

        @Override
        public int getConnectedWireIndex() {
            return siteInst.getSite().getTileWireIndexFromPinName(getName());
        }

        @Override
        public BELPin getBELPin() {
            return siteInst.getSite().getBELPin(getName());
        }

        @Override
        public String getSitePinName() {
            return siteInst.getName() + "." + getName();
        }

        @Override
        public String toString() {
            return (isOutPin() ? "OUT " : "IN ") + getSitePinName();
        }

        @Override
        public boolean equals(Object o) {
            return this == o;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /** Adds the edges of a net, from its source pin to each of its sink pins, to a buffer. */
    private void addNetEdges(Net net, EDIFNetlist netlist, NetPins np, StaArcProvider.NetArc arc,
                             NetEdges edges) {
        List<EDIFHierPortInst> hports = netlist.getPhysicalPins(net);
        if (hports == null) {
            return;
        }
        np.clear();
        boolean noSitePins = net.getPins().isEmpty();
        // Per source (in the order of np.srcPort): its cell, BEL pin and site pin
        List<Cell> srcCells = new ArrayList<>(1);
        List<BELPin> srcBELPins = new ArrayList<>(1);
        List<SitePinInst> srcSitePins = new ArrayList<>(1);
        for (EDIFHierPortInst hport : hports) {
            String portName = hport.getPortInst().getName();
            int cellIdx = getCellIndex(hport);
            if (cellIdx < 0) {
                continue;
            }
            Cell cell = physCells[cellIdx];
            if (cell.getSiteInst() == null) {
                continue;
            }
            String physPinName = cell.getPhysicalPinMapping(portName);
            String sitePinName = DesignTools.getRoutedSitePin(cell, net, portName);
            SitePinInst spi = sitePinName == null ? null : cell.getSiteInst().getSitePinInst(sitePinName);
            SiteInst si = cell.getSiteInst();
            if (spi == null && sitePinName != null && noSitePins) {
                spi = getDetachedSitePin(si, sitePinName, si.getSite().isOutputPin(sitePinName));
            }
            BEL bel = si.getBEL(cell.getBELName());
            BELPin belPin = null;
            if (bel != null && physPinName != null) {
                belPin = bel.getPin(physPinName.replace("[", "").replace("]", ""));
            }
            boolean clockPin = isClockBELPin(belPin) && hport.isInput();
            if (clockPin && dataClockBuffers != null && dataClockBuffers[cellIdx]
                    && DataClockBuffers.isDataInput(portName)) {
                // The data input of a clock buffer used as data
                clockPin = false;
            }
            if (clockPin) {
                // Clock pins start timing paths through the clock-to-output arcs of their cell
                continue;
            }
            int portIdx = getPortIndex(getPortInstList(cells[cellIdx].getInst()), portName);
            if (portIdx < 0) {
                continue;
            }
            if (hport.isOutput() || (spi != null && spi.isOutPin())) {
                SitePinInst netSource = net.getSource();
                if (spi != null && netSource != null && spi != netSource && netSource.getSiteInst() == si
                        && DesignTools.getAllRoutedSitePinsFromPhysicalPin(cell, net, physPinName)
                        .contains(netSource.getName())) {
                    // The output leaves its site through several site pins (e.g. a LUT output through its
                    // site pin and the bypass of a register): the net's source, which a router routes from
                    spi = netSource;
                }
                if (spi != null) {
                    np.srcSitePins.add(spi);
                }
                np.addSource(hport, cellIdx, portIdx);
                srcCells.add(cell);
                srcBELPins.add((cell.getBEL() == null || physPinName == null) ? null : cell.getBEL().getPin(physPinName));
                srcSitePins.add(spi);
            } else {
                np.addSink(hport, cellIdx, portIdx, cell, belPin, spi);
                if (spi != null && belPin != null) {
                    addOtherSitePins(hport, net, cellIdx, portIdx, cell, belPin, spi, np, noSitePins);
                }
            }
        }
        if (np.snkPort.isEmpty() || np.srcPort.isEmpty()) {
            return;
        }
        boolean intraSiteNet = net.getSinkPins().isEmpty();
        if (intraSiteNet && noSitePins) {
            // Leaves its site if the site routing of a sink pin goes through a site pin
            for (SitePinInst spi : np.snkSitePin) {
                if (spi != null) {
                    intraSiteNet = false;
                    break;
                }
            }
        }

        // With several sources: the one of smallest name, with its own cell, BEL pin and site pin
        int src = 0;
        if (np.srcPort.size() > 1) {
            String best = null;
            for (int i = 0; i < np.srcPort.size(); i++) {
                EDIFHierPortInst port = np.srcPort.get(i);
                String name = port.getFullHierarchicalInstName() + "/" + port.getPortInst().getName();
                if (best == null || name.compareTo(best) < 0) {
                    best = name;
                    src = i;
                }
            }
        }
        Cell sourceCell = srcCells.get(src);
        BELPin sourceBELPin = srcBELPins.get(src);
        SitePinInst sourceSitePin = srcSitePins.get(src) != null ? srcSitePins.get(src)
                : (!np.srcSitePins.isEmpty() ? np.srcSitePins.get(0) : net.getSource());

        for (int i = 0; i < np.snkPort.size(); i++) {
            Cell sinkCell = np.snkCell.get(i);
            arc.net = net;
            arc.intraSiteNet = intraSiteNet;
            arc.sourcePin = np.srcPort.get(src);
            arc.sourceCell = sourceCell;
            arc.sourceBELPin = sourceBELPin;
            arc.sourceSitePin = sourceSitePin;
            arc.sinkPin = np.snkPort.get(i);
            arc.sinkCell = sinkCell;
            arc.sinkBELPin = np.snkBELPin.get(i);
            arc.sinkSitePin = np.snkSitePin.get(i);
            arc.clearDelays();
            long t0 = System.nanoTime();
            boolean timed = provider.getNetArc(arc);
            edges.netArcNanos += System.nanoTime() - t0;
            if (!timed) {
                continue;
            }
            byte flag = isFlipFlopType(sinkCell.getType()) ? NetEdges.FLIP_FLOP_SINK : 0;
            edges.add(np.srcCell[src], np.srcPortIdx[src], np.snkCellIdx[i], np.snkPortIdx[i], flag, arc);
        }
    }
}
