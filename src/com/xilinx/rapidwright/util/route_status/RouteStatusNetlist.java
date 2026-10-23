/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.PartitionPin;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
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
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * A flattened view of a design's logical netlist, as needed by
 * {@link ReportRouteStatus#reportVivadoRouteStatus(Design, boolean)}: every flat (parent) net with
 * the counts of its drivers and loads, the placement of the leaf cells it connects to, and the top
 * level ports it connects to. Built in one top-down pass over the hierarchy (in parallel over
 * subtrees), without materializing hierarchical net objects.
 */
class RouteStatusNetlist {

    /** How a flat net got its name, in increasing order of precedence */
    private static final int NAMED_BY_SEGMENT = 0;
    private static final int NAMED_BY_DANGLING_OUTPUT = 1;
    private static final int NAMED_BY_INOUT = 2;
    private static final int NAMED_BY_DANGLING_INOUT = 3;
    private static final int NAMED_BY_DRIVER = 4;

    /**
     * Tracks whether pins are placed in more than one site. Pins without a physical pin (e.g.
     * hardwired between sites) need no routing, so should not count; as checking that is costly,
     * the pins found in the first two sites are kept so that it can be checked later, if needed
     * (see {@link FlatNet#hasPinsInMultipleSites(Design)}).
     */
    static class SiteTracker {
        SiteInst firstSite;
        /** The cells and logical pins found in the first two sites (null cells for top level ports) */
        Cell firstCell;
        String firstPin;
        Cell secondCell;
        String secondPin;
        boolean multipleSites;

        void addPin(SiteInst si, Cell cell, String pin) {
            if (multipleSites || si == firstSite) {
                return;
            }
            if (firstSite == null) {
                firstSite = si;
                firstCell = cell;
                firstPin = pin;
                return;
            }
            secondCell = cell;
            secondPin = pin;
            multipleSites = true;
        }

        void addAll(SiteTracker other) {
            if (other.multipleSites && !multipleSites) {
                firstSite = other.firstSite;
                firstCell = other.firstCell;
                firstPin = other.firstPin;
                secondCell = other.secondCell;
                secondPin = other.secondPin;
                multipleSites = true;
            } else if (other.firstSite != null) {
                addPin(other.firstSite, other.firstCell, other.firstPin);
            }
        }

        void resetSites() {
            firstSite = null;
            firstCell = null;
            firstPin = null;
            secondCell = null;
            secondPin = null;
            multipleSites = false;
        }
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

    /**
     * Gets the segments (hierarchical nets) of the flat net a segment is part of, following the
     * current connectivity through the ports of hierarchical cells. Unlike
     * {@link EDIFNetlist#getPhysicalPins(EDIFHierNet)}, nothing is cached, and nets with no or
     * several drivers are fine.
     */
    static List<EDIFHierNet> getSegments(EDIFHierNet start) {
        List<EDIFHierNet> segments = new ArrayList<>();
        Deque<EDIFHierNet> queue = new ArrayDeque<>();
        Set<EDIFHierNet> visited = new HashSet<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            EDIFHierNet hn = queue.poll();
            if (!visited.add(hn)) {
                continue;
            }
            segments.add(hn);
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
        return segments;
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

    /**
     * Gets the pins on leaf cells (or black boxes) of the flat net a segment is part of, from the
     * current connectivity (see {@link #getSegments(EDIFHierNet)}).
     */
    static List<EDIFHierPortInst> getFlatLeafPortInsts(EDIFHierNet segment) {
        List<EDIFHierPortInst> pins = new ArrayList<>();
        for (EDIFHierNet hn : getSegments(segment)) {
            pins.addAll(getLeafPortInsts(hn));
        }
        return pins;
    }

    /** A pin on a leaf cell, with its cell (null if there is none). */
    static class LeafPin {
        final EDIFHierPortInst pin;
        final Cell cell;

        LeafPin(EDIFHierPortInst pin, Cell cell) {
            this.pin = pin;
            this.cell = cell;
        }

        boolean isOutput() {
            return pin.isOutput();
        }

        boolean isPlaced() {
            return cell != null && cell.isPlaced();
        }

        String getName() {
            return pin.getPortInst().getName();
        }

        Set<String> getPhysicalPins() {
            return RouteStatusNetlist.getPhysicalPins(cell, getName());
        }

        boolean hasPhysicalPin() {
            return !getPhysicalPins().isEmpty();
        }
    }

    /** The connections of a net (segment, or flat net): its pins by kind, and their sites. */
    static class Connections extends SiteTracker {
        int leafDrivers;
        int leafLoads;
        int leafInouts;
        int unplacedLeafPins;
        int unplacedLeafDrivers;
        int placedPortDrivers;
        int placedPortLoads;
        int unplacedPortDrivers;
        int unplacedPortLoads;
        /** Output pins of black boxes, which are not counted as pins */
        int blackBoxDrivers;
        /** Input (or inout) pins of black boxes, which are not counted as pins either */
        int blackBoxLoads;
        /** Top level ports with partition pins */
        int partitionPinPorts;
        /** GND or VCC if driven by a static source primitive */
        NetType staticType;

        /** Adds the connections of another net (segment). */
        void addConnections(Connections other) {
            leafDrivers += other.leafDrivers;
            leafLoads += other.leafLoads;
            leafInouts += other.leafInouts;
            unplacedLeafPins += other.unplacedLeafPins;
            unplacedLeafDrivers += other.unplacedLeafDrivers;
            addAll(other);
            placedPortDrivers += other.placedPortDrivers;
            placedPortLoads += other.placedPortLoads;
            unplacedPortDrivers += other.unplacedPortDrivers;
            unplacedPortLoads += other.unplacedPortLoads;
            blackBoxDrivers += other.blackBoxDrivers;
            blackBoxLoads += other.blackBoxLoads;
            partitionPinPorts += other.partitionPinPorts;
            if (staticType == null) {
                staticType = other.staticType;
            }
        }

        /** Removes all connections. */
        void resetConnections() {
            leafDrivers = 0;
            leafLoads = 0;
            leafInouts = 0;
            unplacedLeafPins = 0;
            unplacedLeafDrivers = 0;
            resetSites();
            placedPortDrivers = 0;
            placedPortLoads = 0;
            unplacedPortDrivers = 0;
            unplacedPortLoads = 0;
            blackBoxDrivers = 0;
            blackBoxLoads = 0;
            partitionPinPorts = 0;
            staticType = null;
        }
    }

    /** A flat (parent) net, i.e. a set of electrically connected net segments. */
    static class FlatNet extends Connections {
        /** Hierarchical prefix (instance path plus '/', or empty) and net naming this flat net */
        String prefix;
        EDIFNet net;
        /** Precedence of the name (see NAMED_BY_*) */
        int namedBy;
        int segments;
        /** Set when merged into another flat net by a feedthrough */
        FlatNet mergedInto;
        /** True if created inside a macro primitive (e.g. DSP58), whose internals are expanded */
        boolean insideMacro;
        /** Index of this flat net's entry in the route status engine, if any */
        int entry = -1;
        /**
         * A segment connected to nothing but an output port of its cell (as left by removing a
         * driver), which names a net without a driver
         */
        String undrivenPrefix;
        EDIFNet undrivenNet;

        FlatNet(String prefix, EDIFNet net) {
            this.prefix = prefix;
            this.net = net;
        }

        String getName() {
            return prefix.isEmpty() ? net.getName() : prefix + net.getName();
        }

        /**
         * Names the flat net after the given segment if it takes precedence over the current name.
         * Ties (e.g. multiple drivers) are broken by name to be independent of traversal order.
         */
        private void name(int namedBy, String prefix, EDIFNet net) {
            if (namedBy < this.namedBy) {
                return;
            }
            if (namedBy == this.namedBy && (prefix + net.getName()).compareTo(getName()) >= 0) {
                return;
            }
            this.namedBy = namedBy;
            this.prefix = prefix;
            this.net = net;
        }

        /**
         * Segment fed by an output port of a child with nothing but the port inside; it names a
         * flat net without any leaf pin (unless something else does)
         */
        String portOnlyPrefix;
        EDIFNet portOnlyNet;

        private void namePortOnly(String prefix, EDIFNet net) {
            if (net == null) {
                return;
            }
            if (portOnlyNet == null || (prefix + net.getName()).compareTo(portOnlyPrefix + portOnlyNet.getName()) < 0) {
                portOnlyPrefix = prefix;
                portOnlyNet = net;
            }
        }

        /** Applies the naming by a port only output, once all segments have been added. */
        void finishNaming() {
            if (portOnlyNet != null && namedBy == NAMED_BY_SEGMENT && leafDrivers + leafLoads + leafInouts == 0) {
                name(NAMED_BY_DANGLING_OUTPUT, portOnlyPrefix, portOnlyNet);
            } else if (undrivenNet != null && namedBy == NAMED_BY_SEGMENT && leafLoads + leafInouts > 0 && leafDrivers == 0
                    && placedPortDrivers + unplacedPortDrivers + blackBoxDrivers == 0) {
                prefix = undrivenPrefix;
                net = undrivenNet;
            }
            portOnlyPrefix = null;
            portOnlyNet = null;
            undrivenPrefix = null;
            undrivenNet = null;
        }

        private void nameUndriven(String prefix, EDIFNet net) {
            if (net == null) {
                return;
            }
            if (undrivenNet == null || (prefix + net.getName()).compareTo(undrivenPrefix + undrivenNet.getName()) < 0) {
                undrivenPrefix = prefix;
                undrivenNet = net;
            }
        }

        /** Adds the tallies of one net segment (shared by concurrently visited instances). */
        synchronized void add(Tally t, String prefix, EDIFNet net) {
            addUnsynchronized(t, prefix, net);
        }

        /** Adds the tallies of one net segment, for a flat net not yet shared with other tasks. */
        void addUnsynchronized(Tally t, String prefix, EDIFNet net) {
            if (t.portOnlyOutput) {
                namePortOnly(prefix, net);
            }
            if (t.ownPortOnlyOutput) {
                nameUndriven(prefix, net);
            }
            segments++;
            addConnections(t);
            if (t.namedBy > NAMED_BY_SEGMENT) {
                name(t.namedBy, prefix, net);
            }
        }

        void absorb(FlatNet other) {
            segments += other.segments;
            addConnections(other);
            insideMacro &= other.insideMacro;
            name(other.namedBy, other.prefix, other.net);
            namePortOnly(other.portOnlyPrefix, other.portOnlyNet);
            nameUndriven(other.undrivenPrefix, other.undrivenNet);
            other.mergedInto = this;
        }

        FlatNet find() {
            FlatNet n = this;
            while (n.mergedInto != null) {
                n = n.mergedInto;
            }
            return n;
        }

        /**
         * Checks whether the placed pins with a physical pin are in more than one site, i.e. the
         * net needs routing between sites.
         */
        boolean hasPinsInMultipleSites(Design design) {
            if (!multipleSites) {
                return false;
            }
            if (hasPhysicalPin(firstCell, firstPin) && hasPhysicalPin(secondCell, secondPin)) {
                return true;
            }
            // Rare: look at all the pins
            SiteInst site = null;
            for (LeafPin pin : getLeafPins(design)) {
                if (!pin.isPlaced() || !pin.hasPhysicalPin()) {
                    continue;
                }
                if (site == null) {
                    site = pin.cell.getSiteInst();
                } else if (site != pin.cell.getSiteInst()) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Gets the sites of the placed pins with a physical pin, adding them to the given set
         * until it has more than one.
         */
        void addPhysicalPinSites(Design design, Set<SiteInst> sites) {
            if (firstSite == null || sites.size() > 1) {
                return;
            }
            if (hasPhysicalPin(firstCell, firstPin) && (!multipleSites || hasPhysicalPin(secondCell, secondPin))) {
                sites.add(firstSite);
                if (multipleSites) {
                    // Any other site will do
                    sites.add(null);
                }
                return;
            }
            for (LeafPin pin : getLeafPins(design)) {
                if (pin.isPlaced() && pin.hasPhysicalPin()) {
                    sites.add(pin.cell.getSiteInst());
                    if (sites.size() > 1) {
                        return;
                    }
                }
            }
        }

        /**
         * Checks whether a placed leaf load is in another site than the (first) placed leaf driver.
         */
        boolean hasLoadOutsideDriverSite(Design design) {
            List<LeafPin> pins = getLeafPins(design);
            SiteInst site = null;
            for (LeafPin pin : pins) {
                if (pin.isOutput() && pin.isPlaced()) {
                    site = pin.cell.getSiteInst();
                    break;
                }
            }
            if (site == null) {
                return false;
            }
            for (LeafPin pin : pins) {
                if (!pin.isOutput() && pin.isPlaced() && pin.cell.getSiteInst() != site) {
                    return true;
                }
            }
            return false;
        }

        /** Control pin kinds, see {@link #getControlPinLoads(Design)} */
        static final int ENABLE_PIN = 1;
        static final int RESET_PIN = 2;

        /**
         * Gets the kinds of control pins (clock or write enable, set/reset) among the placed leaf
         * loads, whose site pins follow from the placement alone.
         * @return A combination of {@link #ENABLE_PIN} and {@link #RESET_PIN}.
         */
        int getControlPinLoads(Design design) {
            int kinds = 0;
            for (LeafPin pin : getLeafPins(design)) {
                if (pin.isOutput() || !pin.isPlaced()) {
                    continue;
                }
                boolean ff = pin.cell.getBEL().isFF();
                for (String physPin : pin.getPhysicalPins()) {
                    if (ff ? physPin.equals("CE") : physPin.startsWith("WE")) {
                        kinds |= ENABLE_PIN;
                    } else if (ff ? physPin.equals("SR") : physPin.startsWith("RST")) {
                        kinds |= RESET_PIN;
                    }
                }
            }
            return kinds;
        }

        /** Checks whether any load pin (placed or not) has a physical pin, i.e. needs a connection. */
        boolean hasLoadWithPhysicalPin(Design design) {
            for (LeafPin pin : getLeafPins(design)) {
                if (!pin.isOutput() && (!pin.isPlaced() || pin.hasPhysicalPin())) {
                    return true;
                }
            }
            return false;
        }

        /** Checks whether any placed load is on a LUT input. */
        boolean hasLoadOnLutInput(Design design) {
            for (LeafPin pin : getLeafPins(design)) {
                if (!pin.isOutput() && pin.isPlaced() && pin.cell.getBEL().isLUT()) {
                    return true;
                }
            }
            return false;
        }

        /** Checks whether any placed load pin is on a BEL pin that is fed from a site pin. */
        boolean hasLoadOnSitePin(Design design) {
            for (LeafPin pin : getLeafPins(design)) {
                if (pin.isOutput() || !pin.isPlaced()) {
                    continue;
                }
                for (String physPin : pin.getPhysicalPins()) {
                    BELPin belPin = pin.cell.getBEL().getPin(physPin);
                    if (belPin != null && belPin.getConnectedSitePinName() != null) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Gets the segments (hierarchical nets) of this flat net. */
        List<EDIFHierNet> getSegments(Design design) {
            EDIFNetlist netlist = design.getNetlist();
            String instName = prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1);
            EDIFHierCellInst inst;
            // Looking up hierarchical objects by name is not thread safe
            synchronized (netlist) {
                inst = instName.isEmpty() ? netlist.getTopHierCellInst() : netlist.getHierCellInstFromName(instName);
            }
            return RouteStatusNetlist.getSegments(new EDIFHierNet(inst, net));
        }

        /** Gets the pins on leaf cells of all the segments of this flat net, with their cells. */
        List<LeafPin> getLeafPins(Design design) {
            List<LeafPin> pins = new ArrayList<>();
            for (EDIFHierNet hn : getSegments(design)) {
                for (EDIFHierPortInst p : getLeafPortInsts(hn)) {
                    pins.add(new LeafPin(p, p.getPhysicalCell(design)));
                }
            }
            return pins;
        }

        boolean hasTopPorts() {
            return placedPortDrivers + placedPortLoads + unplacedPortDrivers + unplacedPortLoads > 0;
        }
    }

    /** The connections of one net segment, tallied before being added to its flat net. */
    private static class Tally extends Connections {
        int namedBy;
        /** Whether fed by an output port of a child that has nothing but the port inside */
        boolean portOnlyOutput;
        /** Whether connected to nothing but an output port of its cell */
        boolean ownPortOnlyOutput;
    }

    /** Connection kinds of a port instance on a net, as precomputed per cell definition */
    private static final byte OWN_PORT = 0;
    private static final byte LEAF_OUTPUT = 1;
    private static final byte LEAF_INPUT = 2;
    private static final byte LEAF_INOUT = 3;
    private static final byte HIER = 4;
    /** Pins of black boxes (leaf cells that are not primitives) are not counted */
    private static final byte BLACK_BOX = 5;
    private static final byte BLACK_BOX_OUTPUT = 6;

    /** Flags of a port instance on a net, combined with its connection kind */
    private static final byte KIND_MASK = 0x0f;
    /** A hierarchical output port that dangles inside the child */
    private static final byte DANGLING = 0x10;
    /** A hierarchical output port that only has a net inside the child */
    private static final byte PORT_ONLY = 0x20;
    /** A hierarchical inout port that dangles inside the child */
    private static final byte DANGLING_INOUT = 0x40;

    /** The connectivity of a (non-leaf) cell definition, precomputed once for all its instances. */
    private static class CellInfo {
        final int numSlots;
        final EDIFNet[] nets;
        /** Per net: the hash code of its name, and 31^(length of its name) */
        final int[] nameHashes;
        final int[] namePows;
        /**
         * Per net: whether it is not a net, but a placeholder declaring the range of a bus (see
         * isBusRangeDeclaration()), or a net without connections that is not the bit of a bus
         */
        final boolean[] notNets;
        /** Per net: the range of its port instances in the arrays below */
        final int[] start;
        /** Per port instance: connection kind and flags, child index, and port slot */
        final byte[] kinds;
        final int[] childIdx;
        final int[] slots;
        /** Per port instance: the name of the pin of a leaf cell (null otherwise) */
        final String[] leafPinNames;
        /** Per net, the static source type driving it from within this cell, if any */
        final NetType[] staticTypes;
        final EDIFCellInst[] children;
        final boolean[] childIsLeaf;
        /** Total number of instances in this cell's subtree */
        long leafCount;
        /** True if the cell is a macro primitive, or inside one */
        boolean insideMacro;
        boolean macroVisited;
        /** Per child: whether it only weakly drives its nets (e.g. a pull-up), so does not name them */
        final boolean[] childIsWeakDriver;
        /** Per child: whether it is a black box (a leaf cell that is not a primitive) */
        final boolean[] childIsBlackBox;

        /** Per port slot: whether a net inside connects to it, and whether that net connects to nothing else */
        final boolean[] ownConnected;
        final boolean[] ownPortOnly;
        /** Per port slot: whether it is (part of) an output port */
        final boolean[] ownOutputs;
        private final Map<EDIFPort, Integer>[] childOffsets;
        private final boolean[][] childConnected;
        private final boolean[][] childPortOnly;

        @SuppressWarnings("unchecked")
        CellInfo(EDIFCell cell, RouteStatusNetlist netlist) {
            Map<EDIFPort, Integer> offsets = netlist.getPortOffsets(cell);
            numSlots = offsets.get(null);
            ownConnected = new boolean[numSlots];
            ownPortOnly = new boolean[numSlots];
            ownOutputs = new boolean[numSlots];
            for (EDIFPort port : cell.getPorts()) {
                if (port.isOutput()) {
                    int offset = offsets.get(port);
                    Arrays.fill(ownOutputs, offset, offset + Math.max(1, port.getWidth()), true);
                }
            }
            boolean parallel = ForkJoinTask.inForkJoinPool();
            children = toArray(cell.getCellInsts(), new EDIFCellInst[0], parallel);
            childIsLeaf = new boolean[children.length];
            Map<EDIFCellInst, Integer> childIndex = new IdentityHashMap<>(children.length);
            // Per hierarchical child: the port slots of its cell, and which are connected inside
            childOffsets = new Map[children.length];
            childConnected = new boolean[children.length][];
            childPortOnly = new boolean[children.length][];
            childIsWeakDriver = new boolean[children.length];
            childIsBlackBox = new boolean[children.length];
            for (int i = 0; i < children.length; i++) {
                childIndex.put(children[i], i);
                EDIFCell type = children[i].getCellType();
                childIsLeaf[i] = type.isLeafCellOrBlackBox();
                String typeName = type.getName();
                childIsWeakDriver[i] = childIsLeaf[i] && (typeName.equals("PULLUP") || typeName.equals("PULLDOWN")
                        || typeName.equals("KEEPER"));
                childIsBlackBox[i] = childIsLeaf[i] && !type.isPrimitive();
                if (!childIsLeaf[i]) {
                    childOffsets[i] = netlist.getPortOffsets(type);
                    CellInfo childInfo = netlist.getCellInfo(type);
                    childConnected[i] = childInfo.ownConnected;
                    childPortOnly[i] = childInfo.ownPortOnly;
                }
            }
            nets = toArray(cell.getNets(), new EDIFNet[0], parallel);
            nameHashes = new int[nets.length];
            namePows = new int[nets.length];
            notNets = new boolean[nets.length];
            start = new int[nets.length + 1];
            staticTypes = new NetType[nets.length];
            // Each chunk of nets is tallied into its own arrays, then they are concatenated
            int numChunks = Math.max(1, (nets.length + CHUNK - 1) / CHUNK);
            Chunk[] chunks = new Chunk[numChunks];
            if (numChunks > 4 && parallel) {
                List<RecursiveAction> tasks = new ArrayList<>();
                for (int c = 0; c < numChunks; c++) {
                    final int i = c;
                    tasks.add(new RecursiveAction() {
                        @Override
                        protected void compute() {
                            chunks[i] = initNets(i * CHUNK, Math.min(nets.length, (i + 1) * CHUNK), offsets, childIndex);
                        }
                    });
                }
                ForkJoinTask.invokeAll(tasks);
            } else {
                for (int c = 0; c < numChunks; c++) {
                    chunks[c] = initNets(c * CHUNK, Math.min(nets.length, (c + 1) * CHUNK), offsets, childIndex);
                }
            }
            int total = 0;
            for (Chunk chunk : chunks) {
                total += chunk.size;
            }
            kinds = new byte[total];
            childIdx = new int[total];
            slots = new int[total];
            leafPinNames = new String[total];
            int offset = 0;
            for (int c = 0; c < numChunks; c++) {
                Chunk chunk = chunks[c];
                System.arraycopy(chunk.kinds, 0, kinds, offset, chunk.size);
                System.arraycopy(chunk.childIdx, 0, childIdx, offset, chunk.size);
                System.arraycopy(chunk.slots, 0, slots, offset, chunk.size);
                System.arraycopy(chunk.leafPinNames, 0, leafPinNames, offset, chunk.size);
                for (int n = c * CHUNK, end = Math.min(nets.length, (c + 1) * CHUNK); n < end; n++) {
                    start[n + 1] += offset;
                }
                offset += chunk.size;
            }
        }

        /** The port instances of a range of nets, before concatenation. */
        private static class Chunk {
            int size;
            byte[] kinds;
            int[] childIdx;
            int[] slots;
            String[] leafPinNames;

            Chunk(int capacity) {
                kinds = new byte[capacity];
                childIdx = new int[capacity];
                slots = new int[capacity];
                leafPinNames = new String[capacity];
            }

            void ensureCapacity(int capacity) {
                if (capacity > kinds.length) {
                    int newCapacity = Math.max(capacity, kinds.length * 2);
                    kinds = Arrays.copyOf(kinds, newCapacity);
                    childIdx = Arrays.copyOf(childIdx, newCapacity);
                    slots = Arrays.copyOf(slots, newCapacity);
                    leafPinNames = Arrays.copyOf(leafPinNames, newCapacity);
                }
            }
        }

        /**
         * Tallies the port instances of nets [from, to). Sets start[n + 1] to the end of net n's
         * port instances within the chunk (offset by the chunk's start later).
         */
        private Chunk initNets(int from, int to, Map<EDIFPort, Integer> offsets, Map<EDIFCellInst, Integer> childIndex) {
            Chunk chunk = new Chunk(Math.max(16, (to - from) * 4));
            for (int n = from; n < to; n++) {
                String name = nets[n].getName();
                nameHashes[n] = name.hashCode();
                namePows[n] = pow31(name.length());
                List<EDIFPortInst> epis = nets[n].getPortInsts();
                int count = epis.size();
                notNets[n] = count == 0 && (isBusRangeDeclaration(name) || !isBusBit(name));
                int base = chunk.size;
                chunk.ensureCapacity(base + count);
                byte[] kinds = chunk.kinds;
                int[] childIdx = chunk.childIdx;
                int[] slots = chunk.slots;
                for (int p = 0; p < count; p++) {
                    int i = base + p;
                    EDIFPortInst epi = epis.get(p);
                    EDIFCellInst eci = epi.getCellInst();
                    if (eci == null) {
                        kinds[i] = OWN_PORT;
                        slots[i] = slot(offsets, epi);
                        // (concurrent writes of the same value are harmless)
                        ownConnected[slots[i]] = true;
                        if (count == 1) {
                            ownPortOnly[slots[i]] = true;
                        }
                        continue;
                    }
                    int c = childIndex.get(eci);
                    childIdx[i] = c;
                    if (childIsLeaf[c] && !eci.getCellType().isPrimitive()) {
                        kinds[i] = epi.isOutput() ? BLACK_BOX_OUTPUT : BLACK_BOX;
                    } else if (childIsLeaf[c]) {
                        chunk.leafPinNames[i] = epi.getName();
                        if (epi.isOutput()) {
                            kinds[i] = LEAF_OUTPUT;
                            EDIFCell type = eci.getCellType();
                            String typeName = type.getName();
                            if ((typeName.equals("GND") || typeName.equals("VCC")) && type.isStaticSource()) {
                                staticTypes[n] = type.isGNDSource() ? NetType.GND : NetType.VCC;
                            }
                        } else {
                            kinds[i] = epi.isInput() ? LEAF_INPUT : LEAF_INOUT;
                        }
                    } else {
                        int slot = slot(childOffsets[c], epi);
                        slots[i] = slot;
                        byte kind = HIER;
                        if (epi.isOutput()) {
                            if (!childConnected[c][slot]) {
                                kind |= DANGLING;
                            }
                            if (childPortOnly[c][slot]) {
                                kind |= PORT_ONLY;
                            }
                        } else if (!epi.isInput() && !childConnected[c][slot]) {
                            kind |= DANGLING_INOUT;
                        }
                        kinds[i] = kind;
                    }
                }
                chunk.size = base + count;
                start[n + 1] = chunk.size;
            }
            return chunk;
        }
    }

    /** Copies a (large) collection into an array, in parallel if in a fork join pool. */
    private static <T> T[] toArray(Collection<T> collection, T[] empty, boolean parallel) {
        if (parallel && collection.size() > 4 * CHUNK) {
            return collection.parallelStream().toArray((n) -> Arrays.copyOf(empty, n));
        }
        return collection.toArray(empty);
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
    private static final int CHUNK = 8192;

    /** Hash codes of the names of interest (physical net names), or null */
    private final RouteStatusEngine.IntHashSet namesOfInterest;
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
    /** Open addressing table of the segments of interest by hash code */
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

    RouteStatusNetlist(Design design) {
        this(design, null, false);
    }

    /**
     * @param namesOfInterest Hash codes of names (e.g. of physical nets) to be looked up with
     * {@link #getSegment(String)}, or null.
     * @param countUnconnected Whether to only count the nets without any connection.
     */
    RouteStatusNetlist(Design design, RouteStatusEngine.IntHashSet namesOfInterest, boolean countUnconnected) {
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
            int i = RouteStatusEngine.IntHashSet.spread(seg.hash) & (capacity - 1);
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
        for (int i = RouteStatusEngine.IntHashSet.spread(h) & mask; (seg = segmentTable[i]) != null; i = (i + 1) & mask) {
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

    private Map<EDIFPort, Integer> getPortOffsets(EDIFCell cell) {
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

    private CellInfo getCellInfo(EDIFCell cell) {
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
            boolean macro = insideMacro || type.getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
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

    private static int slot(Map<EDIFPort, Integer> offsets, EDIFPortInst epi) {
        return offsets.get(epi.getPort()) + Math.max(0, epi.getIndex());
    }

    /**
     * The cells of the design by the hash codes of their names, to look up leaf cells without
     * building their full names.
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
                int i = RouteStatusEngine.IntHashSet.spread(h) & mask;
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
            for (int i = RouteStatusEngine.IntHashSet.spread(h) & mask; cells[i] != null; i = (i + 1) & mask) {
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

        private void visitNets(int from, int to, SiteInst[] leafSites, Cell[] leafCells,
                               FlatNet[][] childBindings) {
            boolean isTop = portBinding == null;
            byte[] kinds = info.kinds;
            int[] slots = info.slots;
            int[] childIdx = info.childIdx;
            String[] leafPinNames = info.leafPinNames;
            List<FlatNet> created = new ArrayList<>(to - from);
            List<Segment> segments = null;
            int unconnected = 0;
            Tally t = new Tally();
            for (int n = from; n < to; n++) {
                EDIFNet net = info.nets[n];
                int hash = prefixHash * info.namePows[n] + info.nameHashes[n];
                int first = info.start[n];
                int last = info.start[n + 1];
                FlatNet flat = null;
                boolean local = false;
                if (!isTop) {
                    for (int p = first; p < last; p++) {
                        if (kinds[p] != OWN_PORT) {
                            continue;
                        }
                        FlatNet bound = portBinding[slots[p]];
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
                }
                if (flat == null) {
                    if (first == last) {
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
                    local = true;
                    flat.insideMacro = info.insideMacro;
                    created.add(flat);
                }
                if (namesOfInterest != null && namesOfInterest.contains(hash)) {
                    if (segments == null) {
                        segments = new ArrayList<>();
                    }
                    segments.add(new Segment(hash, prefix, net, flat));
                }

                t.resetConnections();
                t.staticType = info.staticTypes[n];
                t.namedBy = NAMED_BY_SEGMENT;
                t.portOnlyOutput = false;
                t.ownPortOnlyOutput = last == first + 1 && kinds[first] == OWN_PORT && info.ownOutputs[slots[first]];
                for (int p = first; p < last; p++) {
                    byte kind = kinds[p];
                    switch (kind & KIND_MASK) {
                        case OWN_PORT:
                            if (isTop) {
                                addTopPort(t, net.getPortInsts().get(p - first));
                            }
                            break;
                        case BLACK_BOX:
                            t.blackBoxLoads++;
                            break;
                        case BLACK_BOX_OUTPUT:
                            t.blackBoxDrivers++;
                            t.namedBy = NAMED_BY_DRIVER;
                            break;
                        case HIER:
                            childBindings[childIdx[p]][slots[p]] = flat;
                            if ((kind & DANGLING) != 0) {
                                // An output port left unconnected inside its cell names the net,
                                // unless there is a real driver
                                t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_OUTPUT);
                            } else if ((kind & DANGLING_INOUT) != 0) {
                                // So does an inout port left unconnected inside its cell, even over
                                // a top level inout port
                                t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_INOUT);
                            } else if ((kind & PORT_ONLY) != 0) {
                                t.portOnlyOutput = true;
                            }
                            break;
                        default:
                            if (kind == LEAF_OUTPUT) {
                                t.leafDrivers++;
                                if (!info.childIsWeakDriver[childIdx[p]]) {
                                    t.namedBy = NAMED_BY_DRIVER;
                                }
                            } else if (kind == LEAF_INPUT) {
                                t.leafLoads++;
                            } else {
                                t.leafInouts++;
                                // A leaf's inout pin names its net over a top level inout port
                                t.namedBy = Math.max(t.namedBy, NAMED_BY_DANGLING_INOUT);
                            }
                            SiteInst si = leafSites[childIdx[p]];
                            if (si != null) {
                                t.addPin(si, leafCells[childIdx[p]], leafPinNames[p]);
                            } else {
                                t.unplacedLeafPins++;
                                if (kind == LEAF_OUTPUT) {
                                    t.unplacedLeafDrivers++;
                                }
                            }
                    }
                }
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
