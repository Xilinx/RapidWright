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

import static com.xilinx.rapidwright.util.route_status.CellInfo.BLACK_BOX;
import static com.xilinx.rapidwright.util.route_status.CellInfo.BLACK_BOX_INOUT;
import static com.xilinx.rapidwright.util.route_status.CellInfo.BLACK_BOX_OUTPUT;
import static com.xilinx.rapidwright.util.route_status.CellInfo.CHILD_OUTPUT;
import static com.xilinx.rapidwright.util.route_status.CellInfo.DANGLING;
import static com.xilinx.rapidwright.util.route_status.CellInfo.DANGLING_INOUT;
import static com.xilinx.rapidwright.util.route_status.CellInfo.HIER;
import static com.xilinx.rapidwright.util.route_status.CellInfo.KIND_MASK;
import static com.xilinx.rapidwright.util.route_status.CellInfo.LEAF_INPUT;
import static com.xilinx.rapidwright.util.route_status.CellInfo.LEAF_OUTPUT;
import static com.xilinx.rapidwright.util.route_status.CellInfo.OWN_PORT;
import static com.xilinx.rapidwright.util.route_status.CellInfo.PORT_ONLY;
import static com.xilinx.rapidwright.util.route_status.FlatNet.NAMED_BY_DANGLING_INOUT;
import static com.xilinx.rapidwright.util.route_status.FlatNet.NAMED_BY_DANGLING_OUTPUT;
import static com.xilinx.rapidwright.util.route_status.FlatNet.NAMED_BY_DRIVER;
import static com.xilinx.rapidwright.util.route_status.FlatNet.NAMED_BY_INOUT;
import static com.xilinx.rapidwright.util.route_status.FlatNet.NAMED_BY_SEGMENT;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.PartitionPin;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.ReportRouteStatus;

/**
 * A flattened view of a design's logical netlist, as needed by
 * {@link ReportRouteStatus#reportRouteStatus(Design, boolean)}: every flat (parent) net with
 * the counts of its drivers and loads, the placement of the leaf cells it connects to, and the top
 * level ports it connects to. Built in one top-down pass over the hierarchy (in parallel over
 * subtrees), without materializing hierarchical net objects.
 *
 * {@link EDIFNetlist#getParentNetMap()} and {@link EDIFNetlist#getPhysicalPins(EDIFHierNet)} are not
 * used: their maps are cached, and go stale after logical edits (e.g. disconnecting a driver), they
 * cannot represent the nets with several drivers or none that the report must classify, and they
 * build an object per hierarchical net, which costs time and memory on large designs. Neither do
 * they give the counts and the naming that Vivado's report uses.
 */
final class RouteStatusNetlist {

    /**
     * Gets the input site pin feeding a BEL input pin directly or through a routing BEL (e.g. an
     * optional inverter), or null.
     */
    static String getSitePinFeeding(SiteInst si, BELPin belPin) {
        for (BELPin p : si.getSiteWirePins(belPin.getSiteWireName())) {
            if (p.isSitePort() && p.isOutput()) {
                return p.getName();
            }
            if (p.isOutput() && p.getBEL().getBELClass() == BELClass.RBEL) {
                for (BELPin in : p.getBEL().getPins()) {
                    if (!in.isInput()) {
                        continue;
                    }
                    for (BELPin q : si.getSiteWirePins(in.getSiteWireName())) {
                        if (q.isSitePort() && q.isOutput()) {
                            return q.getName();
                        }
                    }
                }
            }
        }
        return null;
    }

    static boolean hasPhysicalPin(Cell cell, String logicalPin) {
        if (cell == null) {
            // Top level port
            return true;
        }
        return !getPhysicalPins(cell, logicalPin).isEmpty();
    }

    /** Gets the physical pins a logical pin of a cell maps to (none if unmapped). */
    static Set<String> getPhysicalPins(Cell cell, String logicalPin) {
        Set<String> physPins;
        // The pin mappings of a cell are built lazily, which is not thread safe
        synchronized (cell) {
            physPins = cell.getAllPhysicalPinMappings(logicalPin);
        }
        return (physPins == null) ? Collections.emptySet() : physPins;
    }

    /** The names of all primitives */
    private static final Set<String> UNISIM_NAMES = new HashSet<>();

    static {
        for (Unisim u : Unisim.values()) {
            UNISIM_NAMES.add(u.name());
        }
    }

    /**
     * Checks whether a cell is a primitive: one of the primitives library, or (as Vivado also takes
     * it, e.g. in netlists written by Yosys) a leaf cell of another library named after a primitive.
     */
    static boolean isPrimitive(EDIFCell cell) {
        return cell.isPrimitive() || (cell.getCellInsts().isEmpty() && UNISIM_NAMES.contains(cell.getName()));
    }

    /**
     * Gets the segments (hierarchical nets) of the flat net a segment is part of, following the
     * current connectivity through the ports of hierarchical cells. Unlike
     * {@link EDIFNetlist#getPhysicalPins(EDIFHierNet)}, nothing is cached, and nets with no or
     * several drivers are fine.
     */
    static List<EDIFHierNet> getSegments(EDIFHierNet start) {
        List<EDIFHierNet> segments = new ArrayList<>();
        findSegment(start, (hn) -> {
            segments.add(hn);
            return false;
        });
        return segments;
    }

    /**
     * Gets the static type of the flat net a segment is part of (see {@link #getSegments(EDIFHierNet)}),
     * e.g. of a pin tied to VCC through the output port of a hierarchical cell.
     * @return GND or VCC if a segment is a static net, else null.
     */
    static NetType getStaticType(EDIFHierNet start) {
        EDIFHierNet tie = findSegment(start, (hn) -> hn.getNet().isGND() || hn.getNet().isVCC());
        return (tie == null) ? null : tie.getNet().isVCC() ? NetType.VCC : NetType.GND;
    }

    /**
     * Visits the segments of the flat net a segment is part of (see {@link #getSegments(EDIFHierNet)})
     * until one matches.
     * @return The first segment that matches, or null.
     */
    private static EDIFHierNet findSegment(EDIFHierNet start, Predicate<EDIFHierNet> match) {
        Deque<EDIFHierNet> queue = new ArrayDeque<>();
        Set<EDIFHierNet> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            EDIFHierNet hn = queue.poll();
            if (!visited.add(hn)) {
                continue;
            }
            if (match.test(hn)) {
                return hn;
            }
            for (EDIFHierPortInst p : hn.getPortInsts()) {
                EDIFCellInst eci = p.getPortInst().getCellInst();
                if (eci == null) {
                    if (hn.getHierarchicalInst().isTopLevelInst()) {
                        // A top level port
                        continue;
                    }
                    EDIFHierPortInst up = p.getPortInParent();
                    if (up != null && up.getNet() != null) {
                        queue.add(up.getHierarchicalNet());
                    }
                } else if (!eci.getCellType().isLeafCellOrBlackBox()) {
                    EDIFHierNet internal = p.getInternalNet();
                    if (internal != null) {
                        queue.add(internal);
                    }
                }
            }
        }
        return null;
    }

    /** Gets the pins on leaf cells (or black boxes) of a segment. */
    static List<EDIFHierPortInst> getLeafPortInsts(EDIFHierNet segment) {
        List<EDIFHierPortInst> pins = new ArrayList<>();
        for (EDIFHierPortInst p : segment.getPortInsts()) {
            EDIFCellInst eci = p.getPortInst().getCellInst();
            if (eci != null && eci.getCellType().isLeafCellOrBlackBox()) {
                pins.add(p);
            }
        }
        return pins;
    }

    /** The connections of one net segment, tallied before being added to its flat net. */
    static class Tally extends Connections {
        int namedBy;
        /** Whether fed by an output port of a child that has nothing but the port inside */
        boolean portOnlyOutput;
        /** Whether connected to nothing but an output port of its cell */
        boolean ownPortOnlyOutput;
        /** Whether fed by an output port of a child, but not connected to an output port of its cell */
        boolean childOutputOnly;
        /** Whether inside a macro primitive */
        boolean insideMacro;

    }

    private final Design design;

    /** Flat nets created, in one list per task to avoid contention */
    private final Queue<List<FlatNet>> flatNets = new ConcurrentLinkedQueue<>();

    private final Queue<FlatNet[]> pendingMerges = new ConcurrentLinkedQueue<>();

    private final Map<EDIFCell, Map<EDIFPort, Integer>> portOffsets = new ConcurrentHashMap<>();

    private final Map<EDIFCell, CellInfo> cellInfos = new ConcurrentHashMap<>();

    /** Subtrees with fewer instances than this are visited by the thread visiting the parent */
    private static final long MIN_PARALLEL_INSTANCES = 4096;

    /** Cells with more nets or children than this have them visited in parallel chunks */
    static final int CHUNK = 8192;

    /** Hash codes of the names of interest (physical net names), or null */
    private final IntHashSet namesOfInterest;

    /** Full names of the black box instances */
    private final Set<String> blackBoxes = ConcurrentHashMap.newKeySet();

    /** Checks whether a hierarchical name is inside a black box instance. */
    boolean isInsideBlackBox(String name) {
        if (blackBoxes.isEmpty()) {
            return false;
        }
        for (int i = name.indexOf('/'); i >= 0; i = name.indexOf('/', i + 1)) {
            if (blackBoxes.contains(name.substring(0, i))) {
                return true;
            }
        }
        return false;
    }

    /** Net segments whose full names have the hash code of a name of interest (in one list per task) */
    private final Queue<List<Segment>> segmentsOfInterest = new ConcurrentLinkedQueue<>();

    /**
     * Open addressing table of the segments of interest by hash code (as in {@link CellIndex}, to look
     * them up without building their full names)
     */
    private Segment[] segmentTable;

    static class Segment {
        final int hash;
        final String prefix;
        final EDIFNet net;
        final FlatNet flat;

        Segment(int hash, String prefix, EDIFNet net, FlatNet flat) {
            this.hash = hash;
            this.prefix = prefix;
            this.net = net;
            this.flat = flat;
        }

        /** Checks whether this is the segment naming the given flat net. */
        boolean names(FlatNet flat) {
            return flat.net == net && flat.prefix.equals(prefix);
        }
    }

    /**
     * @param namesOfInterest Hash codes of names (e.g. of physical nets) to be looked up with
     * {@link #getSegment(String)}, or null.
     * @param countUnconnected Whether to only count the nets without any connection.
     */
    RouteStatusNetlist(Design design, IntHashSet namesOfInterest, boolean countUnconnected) {
        this.design = design;
        this.namesOfInterest = namesOfInterest;
        this.countUnconnected = countUnconnected;
        for (PartitionPin pp : design.getPartitionPins()) {
            if (pp.isPort() && pp.getTerminalName() != null) {
                partitionPinPorts.add(pp.getTerminalName());
            }
        }
        build();
    }

    /** Names of the (bits of) top level ports with partition pins */
    private final Set<String> partitionPinPorts = new HashSet<>();

    /**
     * If true, nets without any connection (which are flat nets of their own, internal to a site)
     * are only counted (see {@link #getUnconnectedNets()}), unless their names are of interest.
     */
    private final boolean countUnconnected;

    private final LongAdder unconnectedNets = new LongAdder();

    /** Gets the number of nets without any connection that were only counted. */
    long getUnconnectedNets() {
        return unconnectedNets.sum();
    }

    /** Prepares {@link #getSegment(String)} (which is then thread safe). */
    void prepareSegmentLookup() {
        if (segmentTable != null) {
            return;
        }
        List<Segment> segs = new ArrayList<>();
        for (List<Segment> list : segmentsOfInterest) {
            segs.addAll(list);
        }
        int capacity = Integer.highestOneBit(Math.max(4, segs.size() * 2) - 1) << 1;
        Segment[] table = new Segment[capacity];
        for (Segment seg : segs) {
            int i = IntHashSet.spread(seg.hash) & (capacity - 1);
            while (table[i] != null) {
                i = (i + 1) & (capacity - 1);
            }
            table[i] = seg;
        }
        segmentTable = table;
    }

    /** Gets the net segment with the given full hierarchical name, if its name was of interest. */
    Segment getSegment(String name) {
        prepareSegmentLookup();
        int h = name.hashCode();
        int mask = segmentTable.length - 1;
        Segment seg;
        for (int i = IntHashSet.spread(h) & mask; (seg = segmentTable[i]) != null; i = (i + 1) & mask) {
            if (seg.hash != h) {
                continue;
            }
            String netName = seg.net.getName();
            if (name.length() == seg.prefix.length() + netName.length() && name.startsWith(seg.prefix)
                    && name.endsWith(netName)) {
                return seg;
            }
        }
        return null;
    }

    /**
     * Gets the flat nets, in lists (as created by the tasks building them), including those
     * merged into others (see {@link FlatNet#mergedInto}).
     */
    List<List<FlatNet>> getFlatNetLists() {
        return new ArrayList<>(flatNets);
    }

    Map<EDIFPort, Integer> getPortOffsets(EDIFCell cell) {
        Map<EDIFPort, Integer> offsets = portOffsets.get(cell);
        if (offsets != null) {
            return offsets;
        }
        offsets = new IdentityHashMap<>();
        int offset = 0;
        for (EDIFPort port : cell.getPorts()) {
            offsets.put(port, offset);
            offset += Math.max(1, port.getWidth());
        }
        offsets.put(null, offset);
        portOffsets.put(cell, offsets);
        return offsets;
    }

    CellInfo getCellInfo(EDIFCell cell) {
        CellInfo info = cellInfos.get(cell);
        if (info == null) {
            info = new CellInfo(cell, this);
            cellInfos.put(cell, info);
        }
        return info;
    }

    /** Computes the size of the subtree of every cell definition used, bottom up. */
    private long computeInstanceCount(CellInfo info) {
        if (info.leafCount > 0) {
            return info.leafCount;
        }
        long count = 1;
        for (int c = 0; c < info.children.length; c++) {
            count += info.childIsLeaf[c] ? 1 : computeInstanceCount(getCellInfo(info.children[c].getCellType()));
        }
        info.leafCount = count;
        return count;
    }

    /** Marks the definitions of macro primitives (expanded by RapidWright) and of cells inside them. */
    private void markMacros(CellInfo info, boolean insideMacro) {
        if (info.insideMacro || (!insideMacro && info.macroVisited)) {
            return;
        }
        info.insideMacro = insideMacro;
        info.macroVisited = true;
        for (int c = 0; c < info.children.length; c++) {
            if (info.childIsLeaf[c]) {
                continue;
            }
            EDIFCell type = info.children[c].getCellType();
            boolean macro = insideMacro
                    || type.getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
            markMacros(getCellInfo(type), macro);
        }
    }

    private static final int[] POW31 = new int[1024];

    static {
        POW31[0] = 1;
        for (int i = 1; i < POW31.length; i++) {
            POW31[i] = POW31[i - 1] * 31;
        }
    }

    /** Gets 31^n (with int overflow), to combine String hash codes of concatenated parts. */
    static int pow31(int n) {
        if (n < POW31.length) {
            return POW31[n];
        }
        int result = 1;
        int base = 31;
        while (n > 0) {
            if ((n & 1) != 0) {
                result *= base;
            }
            base *= base;
            n >>= 1;
        }
        return result;
    }

    /** Checks whether the name is that of a bit of a bus, e.g. "data[7]". */
    static boolean isBusBit(String name) {
        int len = name.length();
        if (len < 4 || name.charAt(len - 1) != ']') {
            return false;
        }
        int i = len - 2;
        while (i > 0 && name.charAt(i) >= '0' && name.charAt(i) <= '9') {
            i--;
        }
        return i < len - 2 && name.charAt(i) == '[';
    }

    /** Checks whether the name starts with a bus range, e.g. "[7:0]data". */
    static boolean isBusRangeDeclaration(String name) {
        if (name.isEmpty() || name.charAt(0) != '[') {
            return false;
        }
        int colon = name.indexOf(':');
        int close = name.indexOf(']');
        if (colon < 2 || close < colon + 2) {
            return false;
        }
        for (int i = 1; i < close; i++) {
            char c = name.charAt(i);
            if (i != colon && (c < '0' || c > '9')) {
                return false;
            }
        }
        return true;
    }

    static int slot(Map<EDIFPort, Integer> offsets, EDIFPortInst epi) {
        return offsets.get(epi.getPort()) + Math.max(0, epi.getIndex());
    }

    /**
     * The cells of the design by the hash codes of their names, to look up leaf cells without
     * building their full names (the hash code of a full name is combined from those of its parts,
     * see {@link #pow31(int)}): a map by name would need the full name of each of the millions of
     * leaf cells visited.
     */
    private static class CellIndex {
        private final int[] hashes;
        private final Cell[] cells;
        private final int mask;

        CellIndex(Collection<Cell> all) {
            int capacity = Integer.highestOneBit(Math.max(4, all.size() * 2) - 1) << 1;
            hashes = new int[capacity];
            cells = new Cell[capacity];
            mask = capacity - 1;
            for (Cell cell : all) {
                int h = cell.getName().hashCode();
                int i = IntHashSet.spread(h) & mask;
                while (cells[i] != null) {
                    i = (i + 1) & mask;
                }
                hashes[i] = h;
                cells[i] = cell;
            }
        }

        /** Gets the cell named prefix + name, given the hash code of the prefix. */
        Cell get(String prefix, int prefixHash, String name) {
            int h = prefixHash * pow31(name.length()) + name.hashCode();
            for (int i = IntHashSet.spread(h) & mask; cells[i] != null; i = (i + 1) & mask) {
                if (hashes[i] != h) {
                    continue;
                }
                String cellName = cells[i].getName();
                if (cellName.length() == prefix.length() + name.length() && cellName.startsWith(prefix)
                        && cellName.endsWith(name)) {
                    return cells[i];
                }
            }
            return null;
        }
    }

    private CellIndex cellIndex;

    private void build() {
        cellIndex = new CellIndex(design.getCells());
        EDIFCell top = design.getNetlist().getTopCell();
        if (ParallelismTools.getParallel()) {
            // Precompute the connectivity of all hierarchical cell definitions in parallel
            List<EDIFCell> cells = new ArrayList<>();
            for (EDIFLibrary lib : design.getNetlist().getLibraries()) {
                for (EDIFCell cell : lib.getCells()) {
                    if (!cell.isLeafCellOrBlackBox()) {
                        cells.add(cell);
                    }
                }
            }
            ForkJoinPool.commonPool().submit(() -> cells.parallelStream().forEach(this::getCellInfo)).join();
        }
        CellInfo topInfo = getCellInfo(top);
        computeInstanceCount(topInfo);
        markMacros(topInfo, false);
        VisitTask root = new VisitTask(topInfo, "", 0, null);
        if (ParallelismTools.getParallel()) {
            ForkJoinPool.commonPool().invoke(root);
        } else {
            root.compute();
        }
        // Merge flat nets connected by feedthroughs (a net connecting two ports of its cell)
        for (FlatNet[] merge : pendingMerges) {
            FlatNet a = merge[0].find();
            FlatNet b = merge[1].find();
            if (a != b) {
                a.absorb(b);
            }
        }
        List<List<FlatNet>> lists = getFlatNetLists();
        Consumer<List<FlatNet>> finish = (nets) -> {
            for (FlatNet net : nets) {
                if (net.mergedInto == null) {
                    net.finishNaming();
                }
            }
        };
        if (ParallelismTools.getParallel()) {
            ForkJoinPool.commonPool().submit(() -> lists.parallelStream().forEach(finish)).join();
        } else {
            lists.forEach(finish);
        }
    }

    private interface RangeTask {
        void run(int from, int to);
    }

    /** Visits one hierarchical instance, with the flat nets bound to its cell's ports. */
    private class VisitTask extends RecursiveAction {
        final CellInfo info;
        final String prefix;
        /** The hash code of the prefix, computed from the parent's (as String.hashCode() would) */
        final int prefixHash;
        final FlatNet[] portBinding;

        VisitTask(CellInfo info, String prefix, int prefixHash, FlatNet[] portBinding) {
            this.info = info;
            this.prefix = prefix;
            this.prefixHash = prefixHash;
            this.portBinding = portBinding;
        }

        @Override
        protected void compute() {
            // Look up the placement of leaf cells and prepare the port bindings of hierarchical children
            int numChildren = info.children.length;
            SiteInst[] leafSites = new SiteInst[numChildren];
            Cell[] leafCells = new Cell[numChildren];
            FlatNet[][] childBindings = new FlatNet[numChildren][];
            forRange(numChildren, (from, to) -> {
                for (int c = from; c < to; c++) {
                    EDIFCellInst eci = info.children[c];
                    if (info.childIsBlackBox[c]) {
                        blackBoxes.add(prefix + eci.getName());
                    } else if (info.childIsLeaf[c]) {
                        Cell cell = cellIndex.get(prefix, prefixHash, eci.getName());
                        if (cell != null && cell.isPlaced()) {
                            leafSites[c] = cell.getSiteInst();
                            leafCells[c] = cell;
                        }
                    } else {
                        childBindings[c] = new FlatNet[getCellInfo(eci.getCellType()).numSlots];
                    }
                }
            });

            forRange(info.nets.length, (from, to) -> visitNets(from, to, leafSites, leafCells, childBindings));

            List<VisitTask> forked = null;
            for (int c = 0; c < numChildren; c++) {
                if (childBindings[c] == null) {
                    continue;
                }
                EDIFCellInst eci = info.children[c];
                CellInfo childInfo = getCellInfo(eci.getCellType());
                String name = eci.getName();
                int childPrefixHash = (prefixHash * pow31(name.length()) + name.hashCode()) * 31 + '/';
                VisitTask task = new VisitTask(childInfo, prefix + name + "/", childPrefixHash, childBindings[c]);
                if (childInfo.leafCount >= MIN_PARALLEL_INSTANCES && getPool() != null) {
                    if (forked == null) {
                        forked = new ArrayList<>();
                    }
                    forked.add(task);
                } else {
                    task.compute();
                }
            }
            if (forked != null) {
                invokeAll(forked);
            }
        }

        /** Runs the task over [0, n), in parallel chunks if n is large and running in a pool. */
        private void forRange(int n, RangeTask task) {
            if (n <= 2 * CHUNK || getPool() == null) {
                task.run(0, n);
                return;
            }
            List<RecursiveAction> chunks = new ArrayList<>();
            for (int from = 0; from < n; from += CHUNK) {
                final int f = from;
                final int to = Math.min(n, from + CHUNK);
                chunks.add(new RecursiveAction() {
                    @Override
                    protected void compute() {
                        task.run(f, to);
                    }
                });
            }
            invokeAll(chunks);
        }

        /** Visits the nets [from, to) of the instance: creates or extends their flat nets. */
        private void visitNets(int from, int to, SiteInst[] leafSites, Cell[] leafCells,
                               FlatNet[][] childBindings) {
            List<FlatNet> created = new ArrayList<>(to - from);
            List<Segment> segments = null;
            int unconnected = 0;
            Tally t = new Tally();
            for (int n = from; n < to; n++) {
                EDIFNet net = info.nets[n];
                int hash = prefixHash * info.namePows[n] + info.nameHashes[n];
                FlatNet flat = findBoundFlatNet(n);
                boolean local = flat == null;
                if (local) {
                    if (info.start[n] == info.start[n + 1]) {
                        if (info.notNets[n]) {
                            // Placeholder nets written by Vivado to declare the range of a bus net
                            // (e.g. "[7:0]data") are not nets, and neither are unconnected nets
                            // (unless they are bits of a bus, which Vivado keeps as a whole)
                            continue;
                        }
                        if (countUnconnected && (namesOfInterest == null || !namesOfInterest.contains(hash))) {
                            // A net without connections, which is only counted (not inside a macro,
                            // where Vivado ties it to a constant)
                            if (!info.insideMacro) {
                                unconnected++;
                            }
                            continue;
                        }
                    }
                    flat = new FlatNet(prefix, net);
                    flat.insideMacro = info.insideMacro;
                    created.add(flat);
                }
                if (namesOfInterest != null && namesOfInterest.contains(hash)) {
                    if (segments == null) {
                        segments = new ArrayList<>();
                    }
                    segments.add(new Segment(hash, prefix, net, flat));
                }
                tally(t, n, flat, leafSites, leafCells, childBindings);
                if (local) {
                    flat.addUnsynchronized(t, prefix, net);
                } else {
                    flat.add(t, prefix, net);
                }
            }
            if (!created.isEmpty()) {
                flatNets.add(created);
            }
            if (unconnected > 0) {
                unconnectedNets.add(unconnected);
            }
            if (segments != null) {
                segmentsOfInterest.add(segments);
            }
        }

        /**
         * Gets the flat net bound to the ports of the cell that a net connects to (noting feedthroughs
         * that connect several, to be merged), or null.
         */
        private FlatNet findBoundFlatNet(int n) {
            if (portBinding == null) {
                return null;
            }
            FlatNet flat = null;
            for (int p = info.start[n]; p < info.start[n + 1]; p++) {
                if (info.kinds[p] != OWN_PORT) {
                    continue;
                }
                FlatNet bound = portBinding[info.slots[p]];
                if (bound == null) {
                    continue;
                }
                if (flat == null) {
                    flat = bound;
                } else if (flat != bound) {
                    // Feedthrough connecting two nets of the parent
                    pendingMerges.add(new FlatNet[]{flat, bound});
                }
            }
            return flat;
        }

        /** Tallies the connections of net n of the instance (a segment of a flat net) into t. */
        private void tally(Tally t, int n, FlatNet flat, SiteInst[] leafSites, Cell[] leafCells,
                           FlatNet[][] childBindings) {
            int first = info.start[n];
            int last = info.start[n + 1];
            byte[] kinds = info.kinds;
            int[] slots = info.slots;
            t.resetConnections();
            t.staticType = info.staticTypes[n];
            t.namedBy = NAMED_BY_SEGMENT;
            t.portOnlyOutput = false;
            t.insideMacro = info.insideMacro;
            t.ownPortOnlyOutput = last == first + 1 && kinds[first] == OWN_PORT && info.ownOutputs[slots[first]];
            boolean childOutput = false;
            boolean ownOutput = false;
            for (int p = first; p < last; p++) {
                byte kind = kinds[p];
                if ((kind & CHILD_OUTPUT) != 0) {
                    childOutput = true;
                } else if (kind == OWN_PORT && info.ownOutputs[slots[p]]) {
                    ownOutput = true;
                }
                switch (kind & KIND_MASK) {
                    case OWN_PORT:
                        if (portBinding == null) {
                            addTopPort(t, info.nets[n].getPortInsts().get(p - first));
                        }
                        break;
                    case BLACK_BOX:
                        t.blackBoxLoads++;
                        break;
                    case BLACK_BOX_INOUT:
                        t.blackBoxLoads++;
                        t.blackBoxInouts++;
                        break;
                    case BLACK_BOX_OUTPUT:
                        t.blackBoxDrivers++;
                        t.namedBy = NAMED_BY_DRIVER;
                        break;
                    case HIER:
                        childBindings[info.childIdx[p]][slots[p]] = flat;
                        tallyChildPort(t, kind);
                        break;
                    default:
                        tallyLeafPin(t, kind, p, leafSites, leafCells);
                }
            }
            t.childOutputOnly = childOutput && !ownOutput;
        }

        /** Tallies how a port of a hierarchical child names the net. */
        private void tallyChildPort(Tally t, byte kind) {
            if ((kind & DANGLING) != 0) {
                // An output port left unconnected inside its cell names the net, unless there is a
                // real driver
                t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_OUTPUT);
            } else if ((kind & DANGLING_INOUT) != 0) {
                // So does an inout port left unconnected inside its cell, even over a top level
                // inout port
                t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_INOUT);
            } else if ((kind & PORT_ONLY) != 0) {
                t.portOnlyOutput = true;
            }
        }

        /** Tallies the pin (port instance p) of a leaf cell. */
        private void tallyLeafPin(Tally t, byte kind, int p, SiteInst[] leafSites, Cell[] leafCells) {
            int c = info.childIdx[p];
            if (kind == LEAF_OUTPUT) {
                t.leafDrivers++;
                if (!info.childIsWeakDriver[c]) {
                    t.namedBy = NAMED_BY_DRIVER;
                }
            } else if (kind == LEAF_INPUT) {
                t.leafLoads++;
            } else {
                t.leafInouts++;
                // A leaf's inout pin names its net over a top level inout port
                t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_INOUT);
            }
            SiteInst si = leafSites[c];
            if (si != null) {
                t.addPin(si, leafCells[c], info.leafPinNames[p]);
            } else {
                t.unplacedLeafPins++;
                if (kind == LEAF_OUTPUT) {
                    t.unplacedLeafDrivers++;
                }
            }
        }

        private void addTopPort(Tally t, EDIFPortInst epi) {
            Cell portCell = design.getCell(epi.getName());
            // (an input port with a partition pin, e.g. of a reconfigurable module, is not placed)
            boolean partitionPin = partitionPinPorts.contains(epi.getName());
            if (partitionPin) {
                t.partitionPinPorts++;
            }
            boolean placed = portCell != null && portCell.isPlaced() && !(!epi.isOutput() && partitionPin);
            if (placed) {
                t.addPin(portCell.getSiteInst(), null, null);
            }
            // A top level input (or inout) port drives the net; an output (or inout) port is a load
            if (!epi.isOutput()) {
                if (placed) {
                    t.placedPortDrivers++;
                } else {
                    t.unplacedPortDrivers++;
                }
                t.namedBy = Math.max(t.namedBy, epi.isInput() ? NAMED_BY_DRIVER : NAMED_BY_INOUT);
            }
            if (!epi.isInput()) {
                if (placed) {
                    t.placedPortLoads++;
                } else {
                    t.unplacedPortLoads++;
                }
            }
        }
    }
}
