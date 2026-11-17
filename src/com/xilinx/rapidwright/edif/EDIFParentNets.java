/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.edif;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.util.ParallelismTools;

/**
 * Finds the parent nets, the aliases and the physical pins of the hierarchical nets of a netlist.
 * <p>
 * Nothing is built for the netlist as a whole. The parent net of a hierarchical net is found from
 * what its nets connect to within their cells ({@link EDIFNetConnectivity}, kept per net and
 * computed on first use): up the instances of the net while it is connected to a port, then down
 * to its source. The aliases and the physical pins of a net are found by a search of the nets
 * connected to it, and the physical pins are kept until the netlist changes. Only iterating over
 * {@link #getParentNetMap()}, {@link #getParentNetNameMap()} or {@link #getPhysicalPinMap()} (or
 * asking for their size) finds them for all nets of the netlist.
 * <p>
 * All methods can be called from several threads at the same time, as long as no thread changes
 * the netlist meanwhile.
 */
final class EDIFParentNets {

    private static final String MULTIPLE_SOURCES = "Multiple sources!";

    private static final EDIFCellInst[] NO_CELL_INSTS = new EDIFCellInst[0];

    private final EDIFNetlist netlist;

    /** Connectivity recorded for nets is only used if it is from this epoch */
    private volatile int epoch = EDIFNetConnectivity.newEpoch();

    /** What is kept until a netlist changes, null if nothing */
    private volatile Results results;

    private final Map<EDIFHierNet, EDIFHierNet> parentNetMap = new ParentNetMap();

    private final Map<String, String> parentNetNameMap = new ParentNetNameMap();

    private final Map<EDIFHierNet, List<EDIFHierPortInst>> physicalPinMap = new PhysicalPinMap();

    EDIFParentNets(EDIFNetlist netlist) {
        this.netlist = netlist;
    }

    /**
     * What was found for a netlist, until a netlist changes: any netlist, as the cells and nets
     * of one can be used in another (see {@link EDIFNetConnectivity#netlistChanged()}).
     */
    private static final class Results {
        /** The top cell inst of the netlist these are for */
        final EDIFCellInst top;
        /** What {@link EDIFNetConnectivity#keepResults()} returned when these were created */
        final int changeCount;
        /** The physical pins of the parent nets asked for */
        final Map<EDIFHierNet, List<EDIFHierPortInst>> pins = new ConcurrentHashMap<>();
        volatile StaticPins staticPins;
        volatile AllNets allNets;
        volatile Map<String, String> allNames;

        Results(EDIFCellInst top, int changeCount) {
            this.top = top;
            this.changeCount = changeCount;
        }

        boolean isFor(EDIFCellInst top) {
            return this.top == top && EDIFNetConnectivity.isCurrent(changeCount);
        }
    }

    /** The physical pins of all GND and of all VCC nets. */
    private static final class StaticPins {
        final List<EDIFHierPortInst> gnd = new ArrayList<>();
        final List<EDIFHierPortInst> vcc = new ArrayList<>();

        void add(EDIFHierPortInst source, List<EDIFHierPortInst> pins) {
            NetType type = EDIFNetlist.identifyNetType(source);
            if (type == NetType.GND) {
                gnd.addAll(pins);
            } else if (type == NetType.VCC) {
                vcc.addAll(pins);
            }
        }
    }

    /** The parent net of every net of the netlist that has one, and their physical pins. */
    private static final class AllNets {
        final Map<EDIFHierNet, EDIFHierNet> parents = new HashMap<>();
        final Map<EDIFHierNet, List<EDIFHierPortInst>> pins = new HashMap<>();
        final StaticPins staticPins = new StaticPins();
    }

    /**
     * Drops everything found so far, including what is recorded for the nets of the netlist.
     */
    void reset() {
        epoch = EDIFNetConnectivity.newEpoch();
        results = null;
    }

    /** True if the parent nets of all nets of the netlist are found and kept. */
    boolean isComplete() {
        Results r = results;
        return r != null && r.isFor(netlist.getTopCellInst()) && r.allNets != null;
    }

    private int getEpoch() {
        if (!EDIFNetConnectivity.active) {
            EDIFNetConnectivity.active = true;
        }
        int e = epoch;
        if (e < EDIFNetConnectivity.getMinEpoch()) {
            e = EDIFNetConnectivity.newEpoch();
            epoch = e;
        }
        return e;
    }

    private Results getResults() {
        if (!EDIFNetConnectivity.active) {
            EDIFNetConnectivity.active = true;
        }
        EDIFCellInst top = netlist.getTopCellInst();
        Results r = results;
        if (r == null || !r.isFor(top)) {
            synchronized (this) {
                r = results;
                if (r == null || !r.isFor(top)) {
                    r = new Results(top, EDIFNetConnectivity.keepResults());
                    results = r;
                }
            }
        }
        return r;
    }

    /**
     * Finds the parent net of a net: the net of its source, which is a leaf cell output pin, a
     * top-level input port or, if it has neither, a top-level inout port.
     * @param net A hierarchical net of the netlist.
     * @return The parent net, or null if the net has no source.
     * @throws RuntimeException If the net has more than one source.
     */
    EDIFHierNet getParentNet(EDIFHierNet net) {
        if (net == null) {
            return null;
        }
        try {
            return findParentNet(net);
        } catch (EDIFNetConnectivity.NotRecordedException e) {
            // The nets do not agree on which of them are connected: those found from this one
            EDIFHierPortInst source = search(net, null, null);
            return (source != null) ? source.getHierarchicalNet() : null;
        }
    }

    /**
     * Finds the parent net of a net from what is recorded for the nets of the netlist.
     * @throws EDIFNetConnectivity.NotRecordedException If that cannot be recorded for a net.
     */
    private EDIFHierNet findParentNet(EDIFHierNet net) {
        final int epoch = getEpoch();
        EDIFHierCellInst hierInst = net.getHierarchicalInst();
        EDIFCellInst[] insts = hierInst.getCellInstArray();
        final int netLevel = insts.length - 1;

        // Up, to the highest instance that the net is connected in
        int level = netLevel;
        EDIFNetConnectivity c = EDIFNetConnectivity.get(net.getNet(), epoch);
        while (level > 0) {
            EDIFNetConnectivity above = c.getAbove(insts[level], epoch);
            if (above == null) {
                break;
            }
            c = above;
            level--;
        }

        // All its sources are there or below, except for the top-level ports
        int sourceCount = c.getSourceCount();
        EDIFPortInst topSource = null;
        int portCount = (level == 0) ? c.getPortCount() : 0;
        if (portCount > 0 && EDIFHierCellInst.isToplevelInst(insts[0])) {
            EDIFPortInst inout = null;
            boolean multipleInouts = false;
            for (int i = 0; i < portCount; i++) {
                EDIFPortInst port = c.getPort(i);
                if (port.isInput()) {
                    sourceCount++;
                    topSource = port;
                } else if (!port.isOutput()) {
                    if (inout != null) {
                        multipleInouts = true;
                    }
                    inout = port;
                }
            }
            if (sourceCount == 0) {
                // A top-level inout port is the source if there is no other, and if it is the only one
                if (multipleInouts) {
                    throw new RuntimeException(MULTIPLE_SOURCES);
                }
                topSource = inout;
            }
        }
        if (sourceCount > 1) {
            throw new RuntimeException(MULTIPLE_SOURCES);
        }
        if (topSource != null) {
            EDIFNet sourceNet = topSource.getNet();
            if (sourceNet == null) {
                return null;
            }
            if (netLevel == 0) {
                return (sourceNet == net.getNet()) ? net : new EDIFHierNet(hierInst, sourceNet);
            }
            return new EDIFHierNet(EDIFHierCellInst.createRelative(Arrays.copyOf(insts, 1)), sourceNet);
        }
        if (sourceCount == 0) {
            return null;
        }

        // Down to the source. Often it is in an instance of the net, or in that of the net itself
        int sourceLevel = level;
        boolean isAbove = true;
        for (EDIFNetConnectivity s = c; s.getSourceBelow() != null; s = s.getSourceBelow()) {
            sourceLevel++;
            if (isAbove && (sourceLevel > netLevel || insts[sourceLevel] != s.getSourceInst())) {
                isAbove = false;
            }
        }
        EDIFNetConnectivity s = c;
        EDIFCellInst[] sourceInsts = null;
        if (!isAbove) {
            sourceInsts = Arrays.copyOf(insts, sourceLevel + 1);
            for (int i = level + 1; i <= sourceLevel; i++) {
                sourceInsts[i] = s.getSourceInst();
                s = s.getSourceBelow();
            }
        } else {
            while (s.getSourceBelow() != null) {
                s = s.getSourceBelow();
            }
            if (sourceLevel < netLevel) {
                sourceInsts = Arrays.copyOf(insts, sourceLevel + 1);
            }
        }
        EDIFNet sourceNet = s.getSourceNet();
        if (sourceNet == null) {
            return null;
        }
        if (sourceInsts == null) {
            return (sourceNet == net.getNet()) ? net : new EDIFHierNet(hierInst, sourceNet);
        }
        return new EDIFHierNet(EDIFHierCellInst.createRelative(sourceInsts), sourceNet);
    }

    /**
     * Gets all nets that are connected to a net, including the net itself.
     * @param net A hierarchical net of the netlist.
     * @return The nets, the given net first.
     * @throws RuntimeException If the net has more than one source, or has connections but no
     * source.
     */
    List<EDIFHierNet> getNetAliases(EDIFHierNet net) {
        List<EDIFHierNet> aliases = new ArrayList<>();
        EDIFHierPortInst source = search(net, aliases, null);
        if (source == null && net.getNet().getPortInstCount() != 0) {
            throw new RuntimeException("ERROR: Couldn't identify parent net, no output pins (or top level output port) found.");
        }
        return aliases;
    }

    /**
     * Gets the leaf cell pins on a parent net and its aliases.
     * @param parentNet A parent net, as returned by {@link #getParentNet(EDIFHierNet)}.
     * @return The pins, in the order a search from the parent net finds them, or null if the net
     * is not a parent net. The list is kept, and must not be changed.
     */
    List<EDIFHierPortInst> getPhysicalPins(EDIFHierNet parentNet) {
        if (parentNet == null) {
            return null;
        }
        Results r = getResults();
        AllNets all = r.allNets;
        List<EDIFHierPortInst> pins = (all != null) ? all.pins.get(parentNet) : null;
        if (pins == null) {
            pins = r.pins.get(parentNet);
        }
        if (pins == null) {
            if (!parentNet.equals(getParentNet(parentNet))) {
                return null;
            }
            ArrayList<EDIFHierPortInst> found = new ArrayList<>();
            search(parentNet, null, found);
            found.trimToSize();
            pins = r.pins.putIfAbsent(parentNet, found);
            if (pins == null) {
                pins = found;
            }
        }
        return pins;
    }

    List<EDIFHierPortInst> getPhysicalGndPins() {
        return getStaticPins().gnd;
    }

    List<EDIFHierPortInst> getPhysicalVccPins() {
        return getStaticPins().vcc;
    }

    private StaticPins getStaticPins() {
        Results r = getResults();
        StaticPins pins = r.staticPins;
        if (pins == null) {
            synchronized (r) {
                pins = r.staticPins;
                if (pins == null) {
                    AllNets all = r.allNets;
                    pins = (all != null) ? all.staticPins : findStaticPins();
                    r.staticPins = pins;
                }
            }
        }
        return pins;
    }

    /** What the search from a possible source of the netlist found, or the exception it threw. */
    private static final class Found {
        /** The net of the possible source, which the search started from */
        final EDIFHierNet net;
        EDIFHierPortInst source;
        List<EDIFHierNet> aliases;
        ArrayList<EDIFHierPortInst> pins;
        RuntimeException exception;

        Found(EDIFHierNet net) {
            this.net = net;
        }
    }

    /**
     * Searches the nets connected to the net of a possible source.
     * @param queued A top-level port or a leaf cell output pin.
     * @param withAliases True to also return the nets found.
     * @return The source of the nets and their physical pins, in the order found from their
     * parent net, or the exception thrown by the search.
     */
    private static Found find(EDIFHierPortInst queued, boolean withAliases) {
        Found found = new Found(queued.getHierarchicalNet());
        try {
            found.aliases = withAliases ? new ArrayList<>() : null;
            found.pins = new ArrayList<>();
            found.source = search(found.net, found.aliases, found.pins);
            if (found.source != null) {
                EDIFHierNet parentNet = found.source.getHierarchicalNet();
                if (!parentNet.equals(found.net)) {
                    // The queued port is not the source (a top-level inout port is only the
                    // source of a net that has no other)
                    found.pins.clear();
                    search(parentNet, null, found.pins);
                }
                found.pins.trimToSize();
            }
        } catch (RuntimeException e) {
            found.exception = e;
        }
        return found;
    }

    /**
     * Finds the physical pins of the nets with a GND or VCC cell as their source, in the order of
     * these cells in a breadth-first walk of the netlist. The nets are searched in parallel (if
     * parallel processing is enabled, see {@link ParallelismTools}).
     */
    private StaticPins findStaticPins() {
        // Only cell instances with such a cell in or below them are walked
        Map<EDIFCell, EDIFCellInst[]> staticInsts = new IdentityHashMap<>();
        List<EDIFHierPortInst> sources = new ArrayList<>();
        Queue<EDIFHierCellInst> instQueue = new ArrayDeque<>();
        instQueue.add(netlist.getTopHierCellInst());
        while (!instQueue.isEmpty()) {
            EDIFHierCellInst currInst = instQueue.poll();
            for (EDIFCellInst eci : getStaticInsts(currInst.getCellType(), staticInsts)) {
                if (!eci.getCellType().isLeafCellOrBlackBox()) {
                    instQueue.add(currInst.getChild(eci));
                    continue;
                }
                for (EDIFPortInst portInst : eci.getPortInsts()) {
                    if (portInst.isOutput() && portInst.getNet() != null) {
                        sources.add(new EDIFHierPortInst(currInst, portInst));
                    }
                }
            }
        }
        StaticPins staticPins = new StaticPins();
        for (Found found : ParallelismTools.invokeAll(sources, source -> find(source, false))) {
            if (found.exception != null) {
                throw found.exception;
            }
            staticPins.add(found.source, found.pins);
        }
        return staticPins;
    }

    private static boolean isStaticSource(EDIFCell cell) {
        String name = cell.getName();
        return name.equals("GND") || name.equals("VCC");
    }

    /**
     * Gets the instances in a cell that are a GND or VCC cell, or have one below them.
     */
    private static EDIFCellInst[] getStaticInsts(EDIFCell cell, Map<EDIFCell, EDIFCellInst[]> staticInsts) {
        EDIFCellInst[] insts = staticInsts.get(cell);
        if (insts == null) {
            List<EDIFCellInst> list = new ArrayList<>();
            for (EDIFCellInst eci : cell.getCellInsts()) {
                EDIFCell type = eci.getCellType();
                if (type.isLeafCellOrBlackBox() ? isStaticSource(type) : getStaticInsts(type, staticInsts).length > 0) {
                    list.add(eci);
                }
            }
            insts = list.isEmpty() ? NO_CELL_INSTS : list.toArray(new EDIFCellInst[0]);
            staticInsts.put(cell, insts);
        }
        return insts;
    }

    private AllNets getAllNets() {
        Results r = getResults();
        AllNets all = r.allNets;
        if (all == null) {
            synchronized (r) {
                all = r.allNets;
                if (all == null) {
                    all = findAllNets();
                    if (r.staticPins == null) {
                        r.staticPins = all.staticPins;
                    }
                    r.allNets = all;
                }
            }
        }
        return all;
    }

    /**
     * Finds the aliases and the physical pins of every net with a source, from the sources of the
     * netlist: its top-level input and inout ports, then the leaf cell outputs in the order of a
     * breadth-first walk of the netlist. The nets are searched in parallel (if parallel processing
     * is enabled, see {@link ParallelismTools}).
     */
    private AllNets findAllNets() {
        AllNets all = new AllNets();
        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        List<EDIFHierPortInst> sources = new ArrayList<>();
        for (EDIFNet net : netlist.getTopCell().getNets()) {
            for (EDIFPortInst portInst : net.getPortInsts()) {
                if (portInst.isTopLevelPort() && !portInst.isOutput()) {
                    sources.add(new EDIFHierPortInst(topInst, portInst));
                }
            }
        }
        Queue<EDIFHierCellInst> instQueue = new ArrayDeque<>();
        instQueue.add(topInst);
        while (!instQueue.isEmpty()) {
            EDIFHierCellInst currInst = instQueue.poll();
            for (EDIFCellInst eci : currInst.getCellType().getCellInsts()) {
                if (!eci.getCellType().isLeafCellOrBlackBox()) {
                    instQueue.add(currInst.getChild(eci));
                    continue;
                }
                for (EDIFPortInst portInst : eci.getPortInsts()) {
                    if (portInst.isOutput() && portInst.getNet() != null) {
                        sources.add(new EDIFHierPortInst(currInst, portInst));
                    }
                }
            }
        }

        // The searches read the netlist only, and run in parallel (if enabled). What they found
        // is recorded in the order of the sources, as when searching them one after the other
        for (Found found : ParallelismTools.invokeAll(sources, source -> find(source, true))) {
            if (all.parents.containsKey(found.net)) {
                // Found from another possible source of the same net, whatever this search did
                continue;
            }
            if (found.exception != null) {
                throw found.exception;
            }
            if (found.source == null) {
                throw new RuntimeException("ERROR: Couldn't identify parent net, no output pins (or top level output port) found.");
            }
            EDIFHierNet parentNet = found.source.getHierarchicalNet();
            all.staticPins.add(found.source, found.pins);
            all.pins.put(parentNet, found.pins);
            for (EDIFHierNet alias : found.aliases) {
                all.parents.put(alias, parentNet);
            }
        }
        return all;
    }

    private Map<String, String> getAllNames() {
        Results r = getResults();
        Map<String, String> names = r.allNames;
        if (names == null) {
            Map<EDIFHierNet, EDIFHierNet> parents = getAllNets().parents;
            synchronized (r) {
                names = r.allNames;
                if (names == null) {
                    names = new HashMap<>((int) (parents.size() / 0.75f) + 1);
                    for (Map.Entry<EDIFHierNet, EDIFHierNet> e : parents.entrySet()) {
                        String alias = e.getKey().getHierarchicalNetName();
                        if (names.put(alias, e.getValue().getHierarchicalNetName()) != null) {
                            throw new IllegalStateException("Duplicate key " + alias);
                        }
                    }
                    r.allNames = names;
                }
            }
        }
        return names;
    }

    /**
     * Gets the parent net of every net, as a map that only finds what it is asked for: looking up
     * a net finds its parent net, and only iterating over the map (or asking for its size) finds
     * the parent nets of all nets of the netlist. The map cannot be changed, and follows the
     * changes of the netlist.
     */
    Map<EDIFHierNet, EDIFHierNet> getParentNetMap() {
        return parentNetMap;
    }

    /** Same as {@link #getParentNetMap()}, with the hierarchical names of the nets. */
    Map<String, String> getParentNetNameMap() {
        return parentNetNameMap;
    }

    /**
     * Gets the physical pins of every parent net, as a map that only finds what it is asked for,
     * like {@link #getParentNetMap()}.
     */
    Map<EDIFHierNet, List<EDIFHierPortInst>> getPhysicalPinMap() {
        return physicalPinMap;
    }

    /** A map that looks up its values one at a time, unless all of its entries are asked for. */
    private abstract static class LazyMap<K, V> extends AbstractMap<K, V> {

        /** Gets all entries. */
        abstract Map<K, V> all();

        @Override
        public abstract V get(Object key);

        @Override
        public boolean containsKey(Object key) {
            return get(key) != null;
        }

        @Override
        public int size() {
            return all().size();
        }

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            return Collections.unmodifiableMap(all()).entrySet();
        }

        @Override
        public Set<K> keySet() {
            return Collections.unmodifiableMap(all()).keySet();
        }

        @Override
        public Collection<V> values() {
            return Collections.unmodifiableMap(all()).values();
        }
    }

    private final class ParentNetMap extends LazyMap<EDIFHierNet, EDIFHierNet> {
        @Override
        Map<EDIFHierNet, EDIFHierNet> all() {
            return getAllNets().parents;
        }

        @Override
        public EDIFHierNet get(Object key) {
            return (key instanceof EDIFHierNet) ? getParentNet((EDIFHierNet) key) : null;
        }
    }

    private final class ParentNetNameMap extends LazyMap<String, String> {
        @Override
        Map<String, String> all() {
            return getAllNames();
        }

        @Override
        public String get(Object key) {
            if (!(key instanceof String)) {
                return null;
            }
            EDIFHierNet parentNet = getParentNet(netlist.getHierNetFromName((String) key));
            return (parentNet != null) ? parentNet.getHierarchicalNetName() : null;
        }
    }

    private final class PhysicalPinMap extends LazyMap<EDIFHierNet, List<EDIFHierPortInst>> {
        @Override
        Map<EDIFHierNet, List<EDIFHierPortInst>> all() {
            return getAllNets().pins;
        }

        @Override
        public List<EDIFHierPortInst> get(Object key) {
            return (key instanceof EDIFHierNet) ? getPhysicalPins((EDIFHierNet) key) : null;
        }
    }

    /**
     * A hierarchical cell instance that a search has reached. There is one object per instance,
     * so that the nets a search has visited are known without hashing their hierarchical names.
     */
    private static final class SearchInst {
        /** Null (until asked for) for an instance reached from the one above it */
        private EDIFHierCellInst hierInst;
        /** Null (until asked for) for the instance the search started in, and those above it */
        private SearchInst parent;
        private final EDIFCellInst inst;
        private final boolean isBelowStart;
        private boolean isTop;
        private boolean isTopKnown;
        /** The first net visited in this instance, and the others if any */
        private EDIFNet visited;
        private Set<EDIFNet> moreVisited;
        /** The first instance reached below this one, and all of them if more than one */
        private SearchInst child;
        private Map<EDIFCellInst, SearchInst> children;

        SearchInst(EDIFHierCellInst hierInst) {
            this.hierInst = hierInst;
            this.inst = hierInst.getInst();
            this.isBelowStart = false;
        }

        private SearchInst(SearchInst parent, EDIFCellInst inst) {
            this.parent = parent;
            this.inst = inst;
            this.isBelowStart = true;
        }

        EDIFHierCellInst getHierInst() {
            EDIFHierCellInst h = hierInst;
            if (h == null) {
                h = parent.getHierInst().getChild(inst);
                hierInst = h;
            }
            return h;
        }

        boolean isTop() {
            if (isBelowStart) {
                return false;
            }
            if (!isTopKnown) {
                isTop = hierInst.isTopLevelInst();
                isTopKnown = true;
            }
            return isTop;
        }

        /** Gets the instance above this one, or null if there is none. */
        SearchInst getParent() {
            SearchInst p = parent;
            if (p == null && !isBelowStart) {
                EDIFHierCellInst parentInst = hierInst.getParent();
                if (parentInst != null) {
                    p = new SearchInst(parentInst);
                    p.child = this;
                    parent = p;
                }
            }
            return p;
        }

        SearchInst getChild(EDIFCellInst childInst) {
            SearchInst c = child;
            if (c == null) {
                c = new SearchInst(this, childInst);
                child = c;
                return c;
            }
            if (children == null) {
                if (c.inst == childInst) {
                    return c;
                }
                children = new IdentityHashMap<>();
                children.put(c.inst, c);
            } else {
                c = children.get(childInst);
                if (c != null) {
                    return c;
                }
            }
            c = new SearchInst(this, childInst);
            children.put(childInst, c);
            return c;
        }

        boolean isVisited(EDIFNet net) {
            return visited == net || (moreVisited != null && moreVisited.contains(net));
        }

        /**
         * Marks a net of this instance as visited.
         * @return True if it was not visited before.
         */
        boolean visit(EDIFNet net) {
            if (visited == null) {
                visited = net;
                return true;
            }
            if (visited == net) {
                return false;
            }
            if (moreVisited == null) {
                moreVisited = Collections.newSetFromMap(new IdentityHashMap<>());
            }
            return moreVisited.add(net);
        }
    }

    /**
     * Searches the nets connected to a net, breadth-first: those inside the cell instances on the
     * net, and for a net on a port of its cell, the one outside its instance.
     * @param initialNet The net to start from.
     * @param aliases If not null, all nets found (the initial one first) are added to it.
     * @param leafCellPins If not null, all leaf cell pins on these nets are added to it.
     * @return The source of the nets: a leaf cell output pin, a top-level input port or, if there
     * is neither, a top-level inout port. Null if the nets have no source.
     * @throws RuntimeException If the nets have more than one source.
     */
    static EDIFHierPortInst search(EDIFHierNet initialNet, List<EDIFHierNet> aliases,
            List<EDIFHierPortInst> leafCellPins) {
        ArrayList<SearchInst> instQueue = new ArrayList<>();
        ArrayList<EDIFNet> netQueue = new ArrayList<>();
        SearchInst start = new SearchInst(initialNet.getHierarchicalInst());
        start.visit(initialNet.getNet());
        instQueue.add(start);
        netQueue.add(initialNet.getNet());

        EDIFHierPortInst source = null;
        EDIFHierPortInst fallbackSource = null;
        boolean multipleFallbackSources = false;
        for (int head = 0; head < netQueue.size(); head++) {
            SearchInst searchInst = instQueue.get(head);
            EDIFNet net = netQueue.get(head);
            if (aliases != null) {
                aliases.add(head == 0 ? initialNet : new EDIFHierNet(searchInst.getHierInst(), net));
            }
            EDIFPortInst[] portInsts = net.getPortInstArray();
            if (portInsts == null) {
                continue;
            }
            for (EDIFPortInst portInst : portInsts) {
                if (portInst == null) {
                    // The rest of the array is unused capacity
                    break;
                }
                EDIFCellInst cellInst = portInst.getCellInst();
                if (cellInst == null) {
                    String name = EDIFNetConnectivity.getPortInstName(portInst);
                    if (!EDIFNetConnectivity.isConnected(searchInst.inst.getCellType(), name, net)) {
                        // Another net is the one on this port
                        continue;
                    }
                    if (searchInst.isTop()) {
                        if (portInst.isInput()) {
                            if (source != null) {
                                throw new RuntimeException(MULTIPLE_SOURCES);
                            }
                            source = new EDIFHierPortInst(searchInst.getHierInst(), portInst);
                        } else if (!portInst.isOutput()) {
                            // A top-level inout port may be the source. Whether more than one is
                            // an error depends on whether any other source is found, so that is
                            // only decided once all nets are visited
                            if (fallbackSource != null) {
                                multipleFallbackSources = true;
                            } else {
                                fallbackSource = new EDIFHierPortInst(searchInst.getHierInst(), portInst);
                            }
                        }
                        continue;
                    }
                    // Up in the hierarchy
                    SearchInst parent = searchInst.getParent();
                    if (parent == null) {
                        continue;
                    }
                    // The port inst outside is found by the name it was created with, and may be
                    // for another bit by now
                    EDIFPortInst outer = searchInst.inst.getPortInst(name);
                    EDIFNet outerNet = (outer != null && EDIFNetConnectivity.getPortInstName(outer).equals(name))
                            ? outer.getNet() : null;
                    if (outerNet != null && parent.visit(outerNet)) {
                        instQueue.add(parent);
                        netQueue.add(outerNet);
                    }
                    continue;
                }
                EDIFCell cellType = cellInst.getCellType();
                if (cellType.isLeafCellOrBlackBox()) {
                    EDIFHierPortInst pin = null;
                    if (leafCellPins != null) {
                        pin = new EDIFHierPortInst(searchInst.getHierInst(), portInst);
                        leafCellPins.add(pin);
                    }
                    if (portInst.isOutput()) {
                        if (source != null) {
                            throw new RuntimeException(MULTIPLE_SOURCES);
                        }
                        source = (pin != null) ? pin : new EDIFHierPortInst(searchInst.getHierInst(), portInst);
                    }
                }
                // Down in the hierarchy
                // A cell without nets on its ports (as a leaf cell) needs no name. A net whose
                // port inst was removed may still be the one that the cell has for the port
                String name = cellType.getInternalNetMap().isEmpty() ? null
                        : EDIFNetConnectivity.getPortInstName(portInst);
                EDIFNet inner = (name != null) ? cellType.getInternalNet(name) : null;
                if (inner != null) {
                    SearchInst child = searchInst.getChild(cellInst);
                    if (!child.isVisited(inner) && EDIFNetConnectivity.hasPort(inner, name)) {
                        child.visit(inner);
                        instQueue.add(child);
                        netQueue.add(inner);
                    }
                }
            }
        }

        if (source != null) {
            return source;
        }
        // No other source was found, so the source is the top-level inout port, if it is the only one
        if (multipleFallbackSources) {
            throw new RuntimeException(MULTIPLE_SOURCES);
        }
        return fallbackSource;
    }
}
