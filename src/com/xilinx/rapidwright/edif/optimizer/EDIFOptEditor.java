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

package com.xilinx.rapidwright.edif.optimizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.optimizer.EDIFConstPropEngine.Lut;
import com.xilinx.rapidwright.edif.optimizer.EDIFLeafIndex.IntList;

/**
 * Applies the decisions of an {@link EDIFConstPropEngine} to the hierarchical netlist. Decisions
 * are first translated into edits local to each hierarchical instance (expressed with names
 * relative to its cell definition). Cell definitions shared by instances that need different
 * edits are then uniquified top-down, and each resulting definition is edited exactly once.
 * Finally, dangling nets, unused static sources and empty hierarchical instances are cleaned up.
 */
class EDIFOptEditor {

    /**
     * A local edit inside one hierarchical cell definition, with names relative to it. Its key
     * identifies it, so that instances with the same edits can share a definition.
     */
    abstract static class Op implements Comparable<Op> {
        private String key;

        /** Appends the fields that identify this edit. */
        abstract void describe(StringBuilder sb);

        static void field(StringBuilder sb, Object o) {
            sb.append('\u0001').append(o);
        }

        String key() {
            if (key == null) {
                StringBuilder sb = new StringBuilder(getClass().getSimpleName());
                describe(sb);
                key = sb.toString();
            }
            return key;
        }

        @Override
        public int compareTo(Op o) {
            return key().compareTo(o.key());
        }
    }

    /** Removes a leaf instance, disconnecting it. */
    static final class RemoveCell extends Op {
        final String inst;

        RemoveCell(String inst) {
            this.inst = inst;
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, inst);
        }
    }

    /** Rewrites a LUT: cell type, INIT and all its connections. */
    static final class RebuildLut extends Op {
        final String inst;
        final String cellType;
        final String init;
        /** Nets of inputs I0, I1, ... (null: unconnected). */
        final String[] inputNets;
        /** Output pins, and their nets (null: unconnected). */
        final String[] outputPins;
        final String[] outputNets;

        RebuildLut(String inst, String cellType, String init, List<String> inputNets,
                   List<String> outputPins, List<String> outputNets) {
            this.inst = inst;
            this.cellType = cellType;
            this.init = init;
            this.inputNets = inputNets.toArray(new String[0]);
            this.outputPins = outputPins.toArray(new String[0]);
            this.outputNets = outputNets.toArray(new String[0]);
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, inst);
            field(sb, cellType);
            field(sb, init);
            for (String n : inputNets) {
                field(sb, n);
            }
            for (int i = 0; i < outputPins.length; i++) {
                field(sb, outputPins[i]);
                field(sb, outputNets[i]);
            }
        }
    }

    /** Sets a property of a leaf instance. */
    static final class SetProperty extends Op {
        final String inst;
        final String property;
        final String value;

        SetProperty(String inst, String property, String value) {
            this.inst = inst;
            this.property = property;
            this.value = value;
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, inst);
            field(sb, property);
            field(sb, value);
        }
    }

    /** Reconnects a pin of a leaf instance to another net. */
    static final class MovePin extends Op {
        final String inst;
        final String pin;
        final String net;

        MovePin(String inst, String pin, String net) {
            this.inst = inst;
            this.pin = pin;
            this.net = net;
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, inst);
            field(sb, pin);
            field(sb, net);
        }
    }

    /** Moves everything connected to net 'from' to net 'into', and removes 'from'. */
    static final class MergeNets extends Op {
        final String from;
        final String into;

        MergeNets(String from, String into) {
            this.from = from;
            this.into = into;
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, from);
            field(sb, into);
        }
    }

    /** Drives a net with a new GND or VCC instance. */
    static final class AddStaticSource extends Op {
        final String net;
        final boolean vcc;

        AddStaticSource(String net, boolean vcc) {
            this.net = net;
            this.vcc = vcc;
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, net);
            field(sb, vcc);
        }
    }

    /** Adds a port to the cell, connected inside to a net (created if needed). */
    static final class NewPort extends Op {
        final String port;
        final EDIFDirection dir;
        final String net;

        NewPort(String port, EDIFDirection dir, String net) {
            this.port = port;
            this.dir = dir;
            this.net = net;
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, port);
            field(sb, dir);
            field(sb, net);
        }
    }

    /** Connects a port of a child (hierarchical) instance to a net (created if needed). */
    static final class ConnectChild extends Op {
        final String inst;
        final String port;
        final String net;

        ConnectChild(String inst, String port, String net) {
            this.inst = inst;
            this.port = port;
            this.net = net;
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, inst);
            field(sb, port);
            field(sb, net);
        }
    }

    /** Removes a net, disconnecting everything on it. */
    static final class RemoveNet extends Op {
        final String net;

        RemoveNet(String net) {
            this.net = net;
        }

        @Override
        void describe(StringBuilder sb) {
            field(sb, net);
        }
    }

    private final EDIFConstPropEngine e;
    private final EDIFLeafIndex idx;
    private final EDIFNetlist netlist;
    private final EDIFOptOptions opts;
    private final EDIFOptStats stats;
    private final Map<Integer, List<Op>> ops = new HashMap<>();

    /** Receives the edits made at the level of cell definitions, null if no report. */
    private EDIFOptReport report;

    void setReport(EDIFOptReport report) {
        this.report = report;
    }

    EDIFOptEditor(EDIFConstPropEngine e, EDIFOptStats stats) {
        this.e = e;
        this.idx = e.idx;
        this.netlist = idx.getNetlist();
        this.opts = e.opts;
        this.stats = stats;
    }

    private void add(int h, Op op) {
        ops.computeIfAbsent(h, k -> new ArrayList<>()).add(op);
    }

    private static String netName(EDIFPortInst pi) {
        EDIFNet n = pi.getNet();
        return n == null ? null : n.getName();
    }

    static String initString(int size, long table) {
        int bits = 1 << size;
        int digits = Math.max(1, bits / 4);
        String hex = Long.toHexString(table & EDIFConstPropEngine.mask(size)).toUpperCase();
        StringBuilder sb = new StringBuilder();
        sb.append(bits).append("'h");
        for (int i = hex.length(); i < digits; i++) {
            sb.append('0');
        }
        return sb.append(hex).toString();
    }

    /**
     * Finds the name of a local net in hierarchical instance h that belongs to physical net n
     * itself. Nets aliased into n by the analysis are deliberately not considered: they are only
     * joined to n by the merges this lookup serves to create, so using one could merge a net into
     * itself (or into another net not yet joined to n) instead of connecting it to n.
     */
    private String ownLocalNet(int n, int h) {
        int start = idx.getNetSegStart(n);
        int end = idx.getNetSegEnd(n);
        if (end - start <= SMALL_NET_SEGMENTS) {
            for (int i = start; i < end; i++) {
                int s = idx.getNetSeg(i);
                if (idx.getSegHier(s) == h) {
                    return idx.getSegEDIFNet(s).getName();
                }
            }
            return null;
        }
        // A net spanning many hierarchical instances: index its segments once
        Map<Integer, Integer> byHier = segmentsByHier.computeIfAbsent(n, net -> {
            Map<Integer, Integer> m = new HashMap<>();
            for (int i = start; i < end; i++) {
                int seg = idx.getNetSeg(i);
                m.putIfAbsent(idx.getSegHier(seg), seg);
            }
            return m;
        });
        Integer seg = byHier.get(h);
        return seg == null ? null : idx.getSegEDIFNet(seg).getName();
    }

    /** Nets with at most this many segments are searched rather than indexed. */
    private static final int SMALL_NET_SEGMENTS = 16;

    /** Per physical net with many segments: the first segment in each hierarchical instance. */
    private final Map<Integer, Map<Integer, Integer>> segmentsByHier = new HashMap<>();

    /**
     * @return The name of the net that leaf pin p connects to after optimization, in the cell of
     * its leaf: its own net if it is still in the same alias group (the merges join it to the
     * group root), else a net of the root of the group it was moved to, routed into the cell if
     * needed. Null if unconnected.
     */
    private String currentNetName(int p) {
        int n = e.pinNet(p);
        if (n < 0) {
            return null;
        }
        int orig = idx.getPinNet(p);
        if (orig >= 0 && e.resolve(orig) == n) {
            return netName(idx.getPinPortInst(p));
        }
        return netIn(n, idx.getLeafHier(idx.getPinLeaf(p)));
    }

    /** Local net names of physical nets routed through new hierarchical ports, by (net, hier). */
    private final Map<Long, String> routed = new HashMap<>();

    private String available(int n, int h) {
        String name = routed.get(((long) n << 32) | h);
        return name != null ? name : ownLocalNet(n, h);
    }

    private static int depth(EDIFLeafIndex idx, int h) {
        int d = 0;
        for (int a = h; a > 0; a = idx.getHierParent(a)) {
            d++;
        }
        return d;
    }

    /**
     * Returns the name of a net in hierarchical instance h that is connected to physical net n
     * (an alias group root) in the edited netlist: a segment of n itself, or a net routed from
     * one through new ports and nets.
     */
    String netIn(int n, int h) {
        String local = available(n, h);
        if (local != null) {
            return local;
        }
        // Find a hierarchical instance where n exists, closest to h's ancestors
        int src = -1;
        for (int i = idx.getNetSegStart(n); i < idx.getNetSegEnd(n) && src < 0; i++) {
            src = idx.getSegHier(idx.getNetSeg(i));
        }
        if (src < 0) {
            return null;
        }
        // Lowest common ancestor of src and h
        int a = src;
        int b = h;
        int da = depth(idx, a);
        int db = depth(idx, b);
        while (da > db) {
            a = idx.getHierParent(a);
            da--;
        }
        while (db > da) {
            b = idx.getHierParent(b);
            db--;
        }
        while (a != b) {
            a = idx.getHierParent(a);
            b = idx.getHierParent(b);
        }
        int lca = a;
        // Up from src to the LCA through new output ports
        for (int c = src; c != lca; c = idx.getHierParent(c)) {
            int parent = idx.getHierParent(c);
            if (available(n, parent) != null) {
                continue;
            }
            String inner = available(n, c);
            String port = uniqueName(c, inner + "_opt_o");
            add(c, new NewPort(port, EDIFDirection.OUTPUT, inner));
            String outer = uniqueName(parent, idx.getHierInst(c).getName() + "_" + port);
            add(parent, new ConnectChild(idx.getHierInst(c).getName(), port, outer));
            routed.put(((long) n << 32) | parent, outer);
        }
        // Down from the LCA to h through new input ports
        List<Integer> path = new ArrayList<>();
        for (int c = h; c != lca; c = idx.getHierParent(c)) {
            path.add(c);
        }
        Collections.reverse(path);
        for (int c : path) {
            if (available(n, c) != null) {
                continue;
            }
            int parent = idx.getHierParent(c);
            String outer = available(n, parent);
            String port = uniqueName(c, outer + "_opt_i");
            add(c, new NewPort(port, EDIFDirection.INPUT, port));
            add(parent, new ConnectChild(idx.getHierInst(c).getName(), port, outer));
            routed.put(((long) n << 32) | c, port);
        }
        return available(n, h);
    }

    /** Port and net names generated per hierarchical instance (lower case). */
    private final Map<Integer, Set<String>> generatedNames = new HashMap<>();
    /** Next suffix to try per hierarchical instance and base name. */
    private final Map<String, Integer> nextSuffix = new HashMap<>();

    /**
     * @return A new port or net name for hierarchical instance h, based on base, that is not the
     * name of a port (or bus) or net of its cell, nor (ignoring case) a name already generated
     * for h.
     */
    private String uniqueName(int h, String base) {
        EDIFCell cell = idx.getHierCell(h);
        Set<String> generated = generatedNames.computeIfAbsent(h, x -> new HashSet<>());
        // Suffixes already tried for this base are skipped (many ports can share a base name)
        String key = h + "\u0000" + base;
        int k = nextSuffix.getOrDefault(key, 0);
        String name = k == 0 ? base : base + "_" + k;
        while (cell.getPort(name) != null || cell.getNet(name) != null
                || !generated.add(name.toLowerCase())) {
            name = base + "_" + ++k;
        }
        nextSuffix.put(key, k + 1);
        return name;
    }

    private static String propValue(EDIFCellInst inst, String key) {
        EDIFPropertyValue v = inst.getProperty(key);
        return v == null ? null : v.getValue();
    }

    static String appendMark(String cur, String mark) {
        if (cur == null || cur.isEmpty()) {
            return mark;
        }
        return (" " + cur + " ").contains(" " + mark + " ") ? cur : cur + " " + mark;
    }

    private String markValue(EDIFCellInst inst, String mark) {
        return appendMark(propValue(inst, "OPT_MODIFIED"), mark);
    }

    /** Translates the engine decisions into local edits. */
    void generate() {
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            int h = idx.getLeafHier(l);
            EDIFCellInst inst = idx.getLeafInst(l);
            if (e.removed[l] != EDIFConstPropEngine.ALIVE) {
                add(h, new RemoveCell(inst.getName()));
                continue;
            }
            Lut s = e.lut[l];
            if (s != null && s.changed) {
                List<String> inputs = new ArrayList<>();
                for (int i = 0; i < s.size; i++) {
                    inputs.add(currentNetName(s.srcPin[i]));
                }
                List<String> outPins = new ArrayList<>();
                List<String> outNets = new ArrayList<>();
                String type;
                if (s.dual) {
                    // Same multi-output cell type: keep all its outputs
                    type = inst.getCellType().getName();
                    for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                        if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                            EDIFPortInst pi = idx.getPinPortInst(p);
                            outPins.add(pi.getName());
                            outNets.add(netName(pi));
                        }
                    }
                } else {
                    type = "LUT" + s.size;
                    int out = e.outputPin(l);
                    outPins.add("O");
                    outNets.add(out < 0 ? null : netName(idx.getPinPortInst(out)));
                }
                String init = initString(s.bits, s.table);
                add(h, new RebuildLut(inst.getName(), type, init, inputs, outPins, outNets));
            }
            Map<String, String> pm = e.props.get(l);
            if (pm != null) {
                for (Map.Entry<String, String> p : pm.entrySet()) {
                    add(h, new SetProperty(inst.getName(), p.getKey(), p.getValue()));
                }
            }
            String mark = e.marks.get(l);
            if (mark != null) {
                String val = null;
                for (String m : mark.split(" ")) {
                    val = appendMark(val != null ? val : propValue(inst, "OPT_MODIFIED"), m);
                }
                add(h, new SetProperty(inst.getName(), "OPT_MODIFIED", val));
            }
            if (s != null && s.changed) {
                // Connections are part of the rebuilt LUT
                continue;
            }
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                int orig = idx.getPinNet(p);
                if (e.pinMovedTo[p] < 0 || e.resolve(orig) == e.pinNet(p)) {
                    continue;
                }
                add(h, new MovePin(inst.getName(), idx.getPinPortInst(p).getName(),
                        currentNetName(p)));
            }
        }
        for (int i = 0; i < e.aliasLeaf.size(); i++) {
            int l = e.aliasLeaf.get(i);
            int h = idx.getLeafHier(l);
            // The output net merges into the input's own net if that was already in the target
            // group when bypassed, else into a net of the target group's root
            EDIFPortInst out = idx.getPinPortInst(e.aliasOutPin.get(i));
            int inPin = e.aliasInPin.get(i);
            String into = inPin >= 0 ? netName(idx.getPinPortInst(inPin))
                    : netIn(e.resolve(e.aliasTarget.get(i)), h);
            add(h, new MergeNets(netName(out), into));
            byte why = e.removed[l];
            if (why == EDIFConstPropEngine.RM_ALIAS || why == EDIFConstPropEngine.RM_INV) {
                markBufferLoads(h, e.aliasOutPin.get(i), EDIFConstPropEngine.MARK_PROPCONST);
            } else if (why == EDIFConstPropEngine.RM_INV_PP) {
                markBufferLoads(h, e.aliasOutPin.get(i), EDIFConstPropEngine.MARK_POST_PROCESS);
            } else if (why == EDIFConstPropEngine.RM_PULLBACK && isBufferCollapse(l)) {
                markBufferLoads(h, e.aliasOutPin.get(i), EDIFConstPropEngine.MARK_POST_PROCESS);
            }
        }
        // Nets that lost all their loads are removed (their driver stays)
        IntList rmNets = new IntList();
        for (int i = 0; i < e.orphanNets.size(); i++) {
            rmNets.add(e.orphanNets.get(i));
        }
        for (int i = 0; i < rmNets.size(); i++) {
            int n = rmNets.get(i);
            for (int k = idx.getNetSegStart(n); k < idx.getNetSegEnd(n); k++) {
                int seg = idx.getNetSeg(k);
                add(idx.getSegHier(seg), new RemoveNet(idx.getSegEDIFNet(seg).getName()));
            }
        }
        // Nets that became constant need a static source. Per hierarchical instance and value,
        // one such net gets its own source; the others are merged into it, which reconnects
        // (and so modifies) their loads
        Map<Long, List<int[]>> constNets = new HashMap<>();
        for (int n = 0; n < idx.getNumNets(); n++) {
            if (e.alias[n] >= 0 || e.netVal[n] == EDIFConstPropEngine.UNK) {
                continue;
            }
            int dp = e.netDriver[n];
            if (dp < 0) {
                continue;
            }
            int dl = idx.getPinLeaf(dp);
            if (dl < 0) {
                continue;
            }
            byte k = e.kind[dl];
            if ((k == EDIFConstPropEngine.K_GND || k == EDIFConstPropEngine.K_VCC)
                    && e.removed[dl] == EDIFConstPropEngine.ALIVE) {
                continue;
            }
            if (!e.hasLoads(n)) {
                continue;
            }
            long key = ((long) idx.getLeafHier(dl) << 1) | (e.netVal[n] == EDIFConstPropEngine.C1
                    ? 1 : 0);
            constNets.computeIfAbsent(key, x -> new ArrayList<>()).add(new int[] {n, dp});
        }
        for (Map.Entry<Long, List<int[]>> en : constNets.entrySet()) {
            List<int[]> list = en.getValue();
            // The net with the most connections in this cell is kept (ties: driver name order)
            list.sort((x, y) -> {
                int cx = idx.getPinPortInst(x[1]).getNet().getPortInsts().size();
                int cy = idx.getPinPortInst(y[1]).getNet().getPortInsts().size();
                if (cx != cy) {
                    return cy - cx;
                }
                return idx.getLeafInst(idx.getPinLeaf(x[1])).getName().compareTo(
                        idx.getLeafInst(idx.getPinLeaf(y[1])).getName());
            });
            int h = (int) (en.getKey() >> 1);
            boolean vcc = (en.getKey() & 1) != 0;
            String first = netName(idx.getPinPortInst(list.get(0)[1]));
            add(h, new AddStaticSource(first, vcc));
            for (int i = 1; i < list.size(); i++) {
                int dp = list.get(i)[1];
                add(h, new MergeNets(netName(idx.getPinPortInst(dp)), first));
                markBufferLoads(h, dp, EDIFConstPropEngine.MARK_PROPCONST);
            }
        }
    }

    /** @return True if leaf l was removed as a buffer (rather than as an inverter). */
    private boolean isBufferCollapse(int l) {
        Lut s = e.lut[l];
        return s != null && !s.dual && s.size == 1 && s.table == 2L;
    }

    /** Loads of a removed buffer inside its own cell are reconnected, hence marked. */
    private void markBufferLoads(int h, int outPin, String mark) {
        EDIFNet net = idx.getPinPortInst(outPin).getNet();
        int n = idx.getPinNet(outPin);
        if (net == null || n < 0) {
            return;
        }
        for (int i = idx.getNetPinStart(n); i < idx.getNetPinEnd(n); i++) {
            int p = idx.getNetPin(i);
            int l = idx.getPinLeaf(p);
            if (l < 0 || idx.getPinPortInst(p).getNet() != net || !e.isLiveSink(p)
                    || !opts.isMarkModified()) {
                continue;
            }
            if (e.marks.containsKey(l)) {
                continue;
            }
            EDIFCellInst inst = idx.getLeafInst(l);
            add(h, new SetProperty(inst.getName(), "OPT_MODIFIED", markValue(inst, mark)));
            e.marks.put(l, mark);
        }
    }

    // ---------------------------------------------------------------- uniquification

    private int[] sigId;

    /** Assigns cell definitions so that instances with different edits have their own copy. */
    /** Hierarchical cell definitions of the edited netlist (they stay so even if emptied). */
    private final Set<EDIFCell> hierCells = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * @return True if cell is a leaf (primitive or black box), as opposed to a hierarchical cell,
     * possibly emptied by the optimization.
     */
    private boolean isLeaf(EDIFCell cell) {
        return !hierCells.contains(cell) && EDIFLeafIndex.isLeafCell(cell);
    }

    EDIFCell[] uniquify() {
        int numHier = idx.getNumHierInsts();
        List<List<Integer>> children = new ArrayList<>();
        for (int h = 0; h < numHier; h++) {
            children.add(null);
        }
        for (int h = 1; h < numHier; h++) {
            int p = idx.getHierParent(h);
            if (children.get(p) == null) {
                children.set(p, new ArrayList<>());
            }
            children.get(p).add(h);
        }
        sigId = new int[numHier];
        Map<String, Integer> intern = new HashMap<>();
        intern.put("", 0);
        // Hierarchical instances are numbered breadth-first: children after their parents
        for (int h = numHier - 1; h >= 0; h--) {
            StringBuilder sb = new StringBuilder();
            List<Op> local = ops.get(h);
            if (local != null) {
                Collections.sort(local);
                for (Op op : local) {
                    sb.append(op.key()).append('\u0002');
                }
            }
            if (children.get(h) != null) {
                List<String> cs = new ArrayList<>();
                for (int c : children.get(h)) {
                    if (sigId[c] != 0) {
                        cs.add(idx.getHierInst(c).getName() + "\u0003" + sigId[c]);
                    }
                }
                Collections.sort(cs);
                for (String c : cs) {
                    sb.append(c).append('\u0004');
                }
            }
            String key = sb.toString();
            Integer id = intern.get(key);
            if (id == null) {
                id = intern.size();
                intern.put(key, id);
            }
            sigId[h] = id;
        }
        // Count signatures per original cell definition
        Map<EDIFCell, Map<Integer, Integer>> sigCounts = new IdentityHashMap<>();
        for (int h = 1; h < numHier; h++) {
            sigCounts.computeIfAbsent(idx.getHierCell(h), k -> new HashMap<>())
                    .merge(sigId[h], 1, Integer::sum);
        }
        Map<EDIFCell, Map<Integer, EDIFCell>> defs = new IdentityHashMap<>();
        EDIFCell[] newCell = new EDIFCell[numHier];
        newCell[0] = idx.getHierCell(0);
        int copies = 0;
        for (int h = 1; h < numHier; h++) {
            EDIFCell orig = idx.getHierCell(h);
            Map<Integer, EDIFCell> m = defs.get(orig);
            if (m == null) {
                m = new HashMap<>();
                defs.put(orig, m);
                Map<Integer, Integer> counts = sigCounts.get(orig);
                int keeper = -1;
                if (counts.containsKey(0)) {
                    keeper = 0;
                } else {
                    int best = -1;
                    for (Map.Entry<Integer, Integer> c : counts.entrySet()) {
                        if (c.getValue() > best || (c.getValue() == best && c.getKey() < keeper)) {
                            best = c.getValue();
                            keeper = c.getKey();
                        }
                    }
                }
                m.put(keeper, orig);
            }
            EDIFCell def = m.get(sigId[h]);
            if (def == null) {
                EDIFLibrary lib = orig.getLibrary();
                int k = 1;
                String name;
                do {
                    name = orig.getName() + "_HD" + k++;
                } while (lib.containsCell(name));
                def = new EDIFCell(lib, orig, name);
                m.put(sigId[h], def);
                copies++;
                if (report != null) {
                    report.add("DEFINITION_COPIED", name, orig.getName(), "copy of "
                            + orig.getName() + " for instances that need different edits, e.g. "
                            + idx.getHierName(h));
                }
            }
            EDIFCellInst inst = newCell[idx.getHierParent(h)].getCellInst(
                    idx.getHierInst(h).getName());
            if (inst.getCellType() != def) {
                inst.setCellType(def);
            }
            newCell[h] = def;
        }
        stats.addCellsUniquified(copies);
        hierCells.addAll(Arrays.asList(newCell));
        return newCell;
    }

    // ---------------------------------------------------------------- application

    /** Batches port instance removals and additions on nets to avoid quadratic list updates. */
    static class NetBatch {
        private final Map<EDIFNet, Set<EDIFPortInst>> removals = new IdentityHashMap<>();
        private final Set<EDIFNet> added = Collections.newSetFromMap(new IdentityHashMap<>());

        void remove(EDIFPortInst pi) {
            EDIFNet net = pi.getNet();
            if (net != null) {
                removals.computeIfAbsent(net, k -> Collections.newSetFromMap(
                        new IdentityHashMap<>())).add(pi);
            }
        }

        void flushRemovals() {
            for (Map.Entry<EDIFNet, Set<EDIFPortInst>> en : removals.entrySet()) {
                Set<EDIFPortInst> set = en.getValue();
                en.getKey().getPortInsts().removeIf(set::contains);
                EDIFCell parent = en.getKey().getParentCell();
                for (EDIFPortInst pi : set) {
                    if (pi.getCellInst() == null && parent != null) {
                        parent.removeInternalPortMapEntry(pi.getName());
                    }
                    pi.setParentNet(null);
                }
            }
            removals.clear();
        }

        void add(EDIFNet net, EDIFPortInst pi) {
            net.addPortInst(pi, true);
            added.add(net);
        }

        void flushAdds() {
            // Concludes the additions with deferred sorting (see EDIFNet.addPortInst)
            for (EDIFNet net : added) {
                net.getPortInsts().sort(null);
            }
            added.clear();
        }
    }

    private EDIFCell[] luts;
    /** When sweeping, nets without loads are removed even if a cell drives them. */
    private boolean removeUnloadedNets;
    private final Map<EDIFCell, Integer> staticCounter = new IdentityHashMap<>();

    /** Applies all local edits, once per (possibly uniquified) cell definition. */
    void apply(EDIFCell[] newCell) {
        luts = new EDIFCell[7];
        for (int k = 1; k <= 6; k++) {
            luts[k] = netlist.getHDIPrimitive(Unisim.valueOf("LUT" + k));
        }
        netlist.getHDIPrimitive(Unisim.LUT6_2);
        netlist.getHDIPrimitive(Unisim.GND);
        netlist.getHDIPrimitive(Unisim.VCC);
        // New ports first, so that parents can connect to them
        Set<EDIFCell> done = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<Integer, List<Op>> en : ops.entrySet()) {
            EDIFCell cell = newCell[en.getKey()];
            if (!done.add(cell)) {
                continue;
            }
            for (Op op : en.getValue()) {
                if (!(op instanceof NewPort)) {
                    continue;
                }
                NewPort np = (NewPort) op;
                if (cell.getPort(np.port) == null) {
                    EDIFPort port = cell.createPort(np.port, np.dir, 1);
                    EDIFNet net = cell.getNet(np.net);
                    if (net == null) {
                        net = cell.createNet(np.net);
                    }
                    net.createPortInst(port);
                }
            }
        }
        done.clear();
        for (Map.Entry<Integer, List<Op>> en : ops.entrySet()) {
            EDIFCell cell = newCell[en.getKey()];
            if (done.add(cell)) {
                applyOps(cell, en.getValue());
            }
        }
    }

    private void applyOps(EDIFCell cell, List<Op> list) {
        NetBatch batch = new NetBatch();
        // Phase A: removals and disconnections
        for (Op op : list) {
            if (op instanceof RemoveCell) {
                EDIFCellInst inst = cell.getCellInst(((RemoveCell) op).inst);
                if (inst != null) {
                    for (EDIFPortInst pi : inst.getPortInsts()) {
                        batch.remove(pi);
                    }
                    cell.removeCellInst(inst);
                }
            } else if (op instanceof RebuildLut) {
                EDIFCellInst inst = cell.getCellInst(((RebuildLut) op).inst);
                for (EDIFPortInst pi : new ArrayList<>(inst.getPortInsts())) {
                    batch.remove(pi);
                    inst.removePortInst(pi);
                }
            } else if (op instanceof MovePin) {
                MovePin mp = (MovePin) op;
                EDIFPortInst pi = cell.getCellInst(mp.inst).getPortInst(mp.pin);
                if (pi != null) {
                    batch.remove(pi);
                }
            } else if (op instanceof RemoveNet) {
                EDIFNet rm = cell.getNet(((RemoveNet) op).net);
                if (rm != null) {
                    for (EDIFPortInst rpi : new ArrayList<>(rm.getPortInsts())) {
                        rm.removePortInst(rpi);
                        if (rpi.getCellInst() != null) {
                            rpi.getCellInst().removePortInst(rpi);
                        } else {
                            cell.removeInternalPortMapEntry(rpi.getName());
                        }
                    }
                    cell.removeNet(rm);
                }
            }
        }
        batch.flushRemovals();
        // Nets of new connections to children, which merges may refer to
        for (Op op : list) {
            if (op instanceof ConnectChild && cell.getNet(((ConnectChild) op).net) == null) {
                cell.createNet(((ConnectChild) op).net);
            }
        }
        // Phase B: merges. The final net of each merged net is resolved first, so that every pin
        // moves once (along a chain of merges, pins would otherwise move repeatedly)
        Map<EDIFNet, EDIFNet> merged = new IdentityHashMap<>();
        Map<String, EDIFNet> byName = new HashMap<>();
        for (Op op : list) {
            if (op instanceof MergeNets) {
                MergeNets m = (MergeNets) op;
                EDIFNet from = m.from == null ? null : cell.getNet(m.from);
                EDIFNet into = m.into == null ? null : cell.getNet(m.into);
                if (from != null && into != null) {
                    union(merged, from, into);
                }
            }
        }
        for (EDIFNet from : new ArrayList<>(merged.keySet())) {
            EDIFNet into = rootOf(merged, from);
            for (EDIFPortInst pi : new ArrayList<>(from.getPortInsts())) {
                batch.add(into, pi);
            }
            from.getPortInsts().clear();
            cell.removeNet(from);
            byName.put(from.getName(), from);
        }
        // Phase C: additions (new hierarchical connections first)
        for (Op op : list) {
            if (op instanceof ConnectChild) {
                ConnectChild cc = (ConnectChild) op;
                EDIFCellInst child = cell.getCellInst(cc.inst);
                EDIFNet cn = net(cell, byName, merged, cc.net);
                if (child != null && child.getPortInst(cc.port) == null) {
                    batch.add(cn, new EDIFPortInst(child.getCellType().getPort(cc.port), null,
                            child));
                }
            }
        }
        for (Op op : list) {
            if (op instanceof RebuildLut) {
                rebuildLut(cell, (RebuildLut) op, batch, byName, merged);
            } else if (op instanceof SetProperty) {
                SetProperty sp = (SetProperty) op;
                EDIFCellInst inst = cell.getCellInst(sp.inst);
                if (inst != null) {
                    inst.addProperty(sp.property, sp.value);
                }
            } else if (op instanceof MovePin) {
                MovePin mp = (MovePin) op;
                EDIFPortInst pi = cell.getCellInst(mp.inst).getPortInst(mp.pin);
                EDIFNet target = mp.net == null ? null : net(cell, byName, merged, mp.net);
                if (pi != null && target != null) {
                    batch.add(target, pi);
                }
            } else if (op instanceof AddStaticSource) {
                AddStaticSource as = (AddStaticSource) op;
                EDIFNet net = net(cell, byName, merged, as.net);
                if (net != null) {
                    addStaticSource(cell, net, as.vcc);
                }
            }
        }
        batch.flushAdds();
    }

    private void rebuildLut(EDIFCell cell, RebuildLut op, NetBatch batch,
                            Map<String, EDIFNet> byName, Map<EDIFNet, EDIFNet> merged) {
        EDIFCellInst inst = cell.getCellInst(op.inst);
        EDIFCell type = op.cellType.matches("LUT[1-6]") ? luts[op.cellType.charAt(3) - '0']
                : inst.getCellType();
        inst.setCellTypeRaw(type);
        inst.addProperty("INIT", op.init);
        for (int i = 0; i < op.inputNets.length + op.outputPins.length; i++) {
            boolean input = i < op.inputNets.length;
            String pin = input ? "I" + i : op.outputPins[i - op.inputNets.length];
            String netName = input ? op.inputNets[i] : op.outputNets[i - op.inputNets.length];
            EDIFNet net = netName == null ? null : net(cell, byName, merged, netName);
            if (net != null) {
                batch.add(net, new EDIFPortInst(type.getPort(pin), null, inst));
            }
        }
    }

    /** Looks up a net by name, following merges (including nets removed by a merge). */
    private static EDIFNet net(EDIFCell cell, Map<String, EDIFNet> byName,
                               Map<EDIFNet, EDIFNet> merged, String name) {
        EDIFNet n = cell.getNet(name);
        if (n == null) {
            n = byName.get(name);
        }
        return n == null ? null : rootOf(merged, n);
    }

    /**
     * @return The representative of the set of n in a union-find forest of nets (parent links;
     * roots are absent), compressing the path to it.
     */
    private static EDIFNet rootOf(Map<EDIFNet, EDIFNet> parent, EDIFNet n) {
        EDIFNet r = n;
        EDIFNet next;
        while ((next = parent.get(r)) != null) {
            r = next;
        }
        while ((next = parent.get(n)) != null && next != r) {
            parent.put(n, r);
            n = next;
        }
        return r;
    }

    /** Joins the set of 'from' into the set of 'into' (whose representative stays). */
    private static void union(Map<EDIFNet, EDIFNet> parent, EDIFNet from, EDIFNet into) {
        EDIFNet rf = rootOf(parent, from);
        EDIFNet ri = rootOf(parent, into);
        if (rf != ri) {
            parent.put(rf, ri);
        }
    }

    private void addStaticSource(EDIFCell cell, EDIFNet net, boolean vcc) {
        EDIFCell src = netlist.getHDIPrimitive(vcc ? Unisim.VCC : Unisim.GND);
        String type = vcc ? "VCC" : "GND";
        int k = staticCounter.getOrDefault(cell, 0);
        String name;
        do {
            name = type + "_" + (++k);
        } while (cell.getCellInst(name) != null);
        staticCounter.put(cell, k);
        EDIFCellInst inst = cell.createChildCellInst(name, src);
        net.createPortInst(vcc ? "P" : "G", inst);
        if (report != null) {
            report.add("SOURCE_ADDED", cell.getName() + ":" + name, type, "drives net "
                    + net.getName() + ", which became constant " + (vcc ? 1 : 0));
        }
    }

    // ---------------------------------------------------------------- cleanup

    /**
     * Removes empty hierarchical instances, then nets that no longer connect a driver to a load
     * (in every instance of their cell definition), then unused static sources.
     * @return The number of hierarchical instances removed.
     */
    int cleanup(boolean removeEmptyHier) {
        removeUnloadedNets = removeEmptyHier;
        cleanupNets();
        int removedHier = 0;
        if (removeEmptyHier) {
            removedHier = removeEmptyHierInsts();
        }
        return removedHier;
    }

    /**
     * Removes nets that do not connect a driver to a load (unless driven by a non-static leaf
     * cell or preserved), in every instance of their cell definition, and static sources left
     * without a net.
     */
    private void cleanupNets() {
        // Ports of preserved hierarchical instances count as loads/drivers
        EDIFLeafIndex fresh = new EDIFLeafIndex(netlist, EDIFConstPropEngine::hasPreserveProp);
        boolean[] keepNet = new boolean[fresh.getNumNets()];
        boolean[] dtNet = new boolean[fresh.getNumNets()];
        // Nets inside preserved hierarchical instances are left untouched
        boolean[] hierDT = new boolean[fresh.getNumHierInsts()];
        for (int h = 1; h < fresh.getNumHierInsts(); h++) {
            hierDT[h] = hierDT[fresh.getHierParent(h)]
                    || EDIFConstPropEngine.hasPreserveProp(fresh.getHierInst(h));
        }
        for (int h = 0; h < fresh.getNumHierInsts(); h++) {
            EDIFNet[] nets = fresh.getHierNets(h);
            int base = fresh.getHierSegBase(h);
            for (int i = 0; i < nets.length; i++) {
                if (hierDT[h] || EDIFConstPropEngine.hasPreserveProp(nets[i])) {
                    dtNet[fresh.getSegNet(base + i)] = true;
                }
            }
        }
        for (int n = 0; n < fresh.getNumNets(); n++) {
            boolean sink = false;
            boolean driver = false;
            boolean realDriver = false;
            boolean boundary = false;
            for (int i = fresh.getNetPinStart(n); i < fresh.getNetPinEnd(n); i++) {
                int p = fresh.getNetPin(i);
                byte dir = fresh.getPinDir(p);
                // The interface of a preserved hierarchical instance is left untouched
                boundary |= fresh.getPinLeaf(p) == EDIFLeafIndex.BOUNDARY;
                if (dir != EDIFLeafIndex.DRIVER) {
                    sink = true;
                }
                if (dir != EDIFLeafIndex.SINK) {
                    driver = true;
                    int l = fresh.getPinLeaf(p);
                    if (l >= 0 && !fresh.getLeafInst(l).getCellType().isStaticSource()) {
                        realDriver = true;
                    }
                }
            }
            keepNet[n] = dtNet[n] || boundary || (sink && driver)
                    || (realDriver && !removeUnloadedNets);
        }
        // A net is removed only if it is removable in every instance of its cell definition
        Map<EDIFCell, boolean[]> removableByCell = new IdentityHashMap<>();
        for (int h = 0; h < fresh.getNumHierInsts(); h++) {
            EDIFNet[] nets = fresh.getHierNets(h);
            int base = fresh.getHierSegBase(h);
            boolean[] rm = removableByCell.get(fresh.getHierCell(h));
            if (rm == null) {
                rm = new boolean[nets.length];
                java.util.Arrays.fill(rm, true);
                removableByCell.put(fresh.getHierCell(h), rm);
            }
            for (int i = 0; i < nets.length; i++) {
                if (keepNet[fresh.getSegNet(base + i)]) {
                    rm[i] = false;
                }
            }
        }
        Map<EDIFNet, Boolean> removable = new IdentityHashMap<>();
        for (int h = 0; h < fresh.getNumHierInsts(); h++) {
            boolean[] rm = removableByCell.remove(fresh.getHierCell(h));
            if (rm == null) {
                continue;
            }
            EDIFNet[] nets = fresh.getHierNets(h);
            for (int i = 0; i < nets.length; i++) {
                if (rm[i]) {
                    removable.put(nets[i], Boolean.TRUE);
                }
            }
        }
        NetBatch batch = new NetBatch();
        Set<EDIFCell> touched = Collections.newSetFromMap(new IdentityHashMap<>());
        List<EDIFPortInst> disconnected = new ArrayList<>();
        for (Map.Entry<EDIFNet, Boolean> en : removable.entrySet()) {
            if (!en.getValue()) {
                continue;
            }
            EDIFNet net = en.getKey();
            for (EDIFPortInst pi : net.getPortInsts()) {
                batch.remove(pi);
                disconnected.add(pi);
            }
            touched.add(net.getParentCell());
        }
        batch.flushRemovals();
        for (EDIFPortInst pi : disconnected) {
            if (pi.getCellInst() != null) {
                pi.getCellInst().removePortInst(pi);
            }
        }
        for (Map.Entry<EDIFNet, Boolean> en : removable.entrySet()) {
            if (en.getValue()) {
                EDIFNet net = en.getKey();
                net.getParentCell().removeNet(net);
            }
        }
        // Static sources left without a net (unless preserved, as in Vivado)
        for (EDIFCell cell : touched) {
            for (EDIFCellInst inst : new ArrayList<>(cell.getCellInsts())) {
                if (inst.getCellType().isStaticSource()
                        && !EDIFConstPropEngine.hasPreserveProp(inst)) {
                    boolean connected = false;
                    for (EDIFPortInst pi : inst.getPortInsts()) {
                        if (pi.getNet() != null) {
                            connected = true;
                        }
                    }
                    if (!connected) {
                        cell.removeCellInst(inst);
                    }
                }
            }
        }
    }

    /** Per cell definition: does it (transitively) contain leaves, and which ports connect. */
    private static class DefCleanup {
        boolean hasLeaves;
        boolean preserved;
        /** Port name to a representative of the group of ports it is connected to inside. */
        Map<String, String> portGroup;
        boolean feedthrough;
    }

    private DefCleanup defCleanup(EDIFCell cell, Map<EDIFCell, DefCleanup> memo) {
        DefCleanup d = memo.get(cell);
        if (d != null) {
            return d;
        }
        d = new DefCleanup();
        memo.put(cell, d);
        // Union-find over nets of this cell, joined through children that pass signals through
        Map<EDIFNet, EDIFNet> uf = new IdentityHashMap<>();
        for (EDIFCellInst child : cell.getCellInsts()) {
            EDIFCell t = child.getCellType();
            if (isLeaf(t)) {
                // Static sources still connected (e.g. to a constant output) count as well
                d.hasLeaves = true;
                continue;
            }
            DefCleanup cd = defCleanup(t, memo);
            d.hasLeaves |= cd.hasLeaves || cd.preserved;
            if (EDIFConstPropEngine.hasPreserveProp(child)) {
                d.preserved = true;
            }
            Map<String, EDIFNet> groupNet = new HashMap<>();
            for (EDIFPortInst pi : child.getPortInsts()) {
                String g = cd.portGroup.get(pi.getName());
                if (g == null || pi.getNet() == null) {
                    continue;
                }
                EDIFNet prev = groupNet.putIfAbsent(g, pi.getNet());
                if (prev != null) {
                    union(uf, prev, pi.getNet());
                }
            }
        }
        // Group the (bit-level) ports of this cell by the internal net they connect to
        d.portGroup = new HashMap<>();
        Map<EDIFNet, String> rootPort = new IdentityHashMap<>();
        for (EDIFNet net : cell.getNets()) {
            for (EDIFPortInst pi : net.getPortInsts()) {
                if (pi.getCellInst() != null) {
                    continue;
                }
                EDIFNet r = rootOf(uf, net);
                String g = rootPort.putIfAbsent(r, pi.getName());
                if (g != null) {
                    d.feedthrough = true;
                }
                d.portGroup.put(pi.getName(), g != null ? g : pi.getName());
            }
        }
        return d;
    }

    /**
     * Removes hierarchical instances that contain no leaf cells and pass no signal from one port
     * to another, then nets left without connections in their parents.
     * @return The number of hierarchical instances removed, including nested ones.
     */
    private int removeEmptyHierInsts() {
        Map<EDIFCell, DefCleanup> memo = new IdentityHashMap<>();
        int count = 0;
        NetBatch batch = new NetBatch();
        Set<EDIFCell> parents = Collections.newSetFromMap(new IdentityHashMap<>());
        List<EDIFCell> todo = new ArrayList<>();
        Set<EDIFCell> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        todo.add(netlist.getTopCell());
        visited.add(netlist.getTopCell());
        for (int i = 0; i < todo.size(); i++) {
            EDIFCell cell = todo.get(i);
            for (EDIFCellInst child : new ArrayList<>(cell.getCellInsts())) {
                EDIFCell t = child.getCellType();
                if (isLeaf(t)) {
                    continue;
                }
                DefCleanup cd = defCleanup(t, memo);
                if (!cd.hasLeaves && !cd.preserved && !cd.feedthrough
                        && !EDIFConstPropEngine.hasPreserveProp(child)) {
                    for (EDIFPortInst pi : child.getPortInsts()) {
                        batch.remove(pi);
                    }
                    cell.removeCellInst(child);
                    parents.add(cell);
                    int nested = countHierInsts(t);
                    count += 1 + nested;
                    if (report != null) {
                        report.add("HIERARCHY_REMOVED", cell.getName() + ":" + child.getName(),
                                t.getName(), "no leaf cells left and no signal passing through"
                                + (nested > 0 ? " (with " + nested + " nested instances)" : ""));
                    }
                } else if (visited.add(t)) {
                    todo.add(t);
                }
            }
        }
        batch.flushRemovals();
        // Nets left connecting nothing (at most one port of their own cell) are removed
        for (EDIFCell cell : parents) {
            for (EDIFNet net : new ArrayList<>(cell.getNets())) {
                int ports = 0;
                boolean inst = false;
                for (EDIFPortInst pi : net.getPortInsts()) {
                    if (pi.getCellInst() == null) {
                        ports++;
                    } else {
                        inst = true;
                    }
                }
                if (!inst && ports <= 1 && !EDIFConstPropEngine.hasPreserveProp(net)) {
                    for (EDIFPortInst pi : new ArrayList<>(net.getPortInsts())) {
                        net.removePortInst(pi);
                        cell.removeInternalPortMapEntry(pi.getName());
                    }
                    cell.removeNet(net);
                }
            }
        }
        return count;
    }

    private int countHierInsts(EDIFCell cell) {
        int n = 0;
        for (EDIFCellInst child : cell.getCellInsts()) {
            if (!isLeaf(child.getCellType())) {
                n += 1 + countHierInsts(child.getCellType());
            }
        }
        return n;
    }
}
