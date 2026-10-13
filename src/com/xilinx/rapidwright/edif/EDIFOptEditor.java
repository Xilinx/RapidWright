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

package com.xilinx.rapidwright.edif;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFConstPropEngine.Lut;
import com.xilinx.rapidwright.edif.EDIFLeafIndex.IntList;

/**
 * Applies the decisions of an {@link EDIFConstPropEngine} to the hierarchical netlist. Decisions
 * are first translated into edits local to each hierarchical instance (expressed with names
 * relative to its cell definition). Cell definitions shared by instances that need different
 * edits are then uniquified top-down, and each resulting definition is edited exactly once.
 * Finally, dangling nets, unused static sources and empty hierarchical instances are cleaned up.
 */
class EDIFOptEditor {

    static final byte OP_REMOVE = 0;
    static final byte OP_LUT = 1;
    static final byte OP_PROP = 2;
    static final byte OP_MOVE = 3;
    static final byte OP_MERGE = 4;
    static final byte OP_STATIC = 5;
    /** Adds a port (b = direction) named a to the cell, connected inside to net c. */
    static final byte OP_NEWPORT = 6;
    /** Connects port b of child instance a to net c (created if needed). */
    static final byte OP_CONNECT = 7;
    /** Removes net a, disconnecting everything on it. */
    static final byte OP_RMNET = 8;

    /** Separates an output pin name from its net name in LUT edits. */
    static final char OUT_SEP = '\u0005';

    /** A local edit inside one hierarchical cell definition. */
    static class Op implements Comparable<Op> {
        final byte type;
        /** Leaf instance name, or net name for OP_MERGE/OP_STATIC. */
        final String a;
        /** Cell type / property key / pin name / merge target net / static type. */
        final String b;
        /** INIT / property value / target net. */
        final String c;
        /** LUT pins nets (inputs I0.., then outputs as "O=net", "O5=net", "O6=net"). */
        final String[] nets;
        private String key;

        Op(byte type, String a, String b, String c, String[] nets) {
            this.type = type;
            this.a = a;
            this.b = b;
            this.c = c;
            this.nets = nets;
        }

        String key() {
            if (key == null) {
                StringBuilder sb = new StringBuilder();
                sb.append(type).append('\u0001').append(a).append('\u0001').append(b)
                        .append('\u0001').append(c);
                if (nets != null) {
                    for (String n : nets) {
                        sb.append('\u0001').append(n);
                    }
                }
                key = sb.toString();
            }
            return key;
        }

        @Override
        public int compareTo(Op o) {
            return key().compareTo(o.key());
        }
    }

    private final EDIFConstPropEngine e;
    private final EDIFLeafIndex idx;
    private final EDIFNetlist netlist;
    private final EDIFOptOptions opts;
    private final EDIFOptStats stats;
    private final Map<Integer, List<Op>> ops = new HashMap<>();

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

    /** Finds the name of a local net in hierarchical instance h that belongs to physical net n. */
    private String localNet(int n, int h) {
        for (int i = idx.getNetSegStart(n); i < idx.getNetSegEnd(n); i++) {
            int s = idx.getNetSeg(i);
            if (idx.getSegHier(s) == h) {
                return idx.getSegEDIFNet(s).getName();
            }
        }
        if (e.aliasChildren[n] != null) {
            for (int i = 0; i < e.aliasChildren[n].size(); i++) {
                String name = localNet(e.aliasChildren[n].get(i), h);
                if (name != null) {
                    return name;
                }
            }
        }
        return null;
    }

    private String inputNetName(int l, Lut s, int i) {
        int p = s.srcPin[i];
        int orig = idx.getPinNet(p);
        if (!s.moved[i] || (orig >= 0 && e.resolve(orig) == e.resolve(s.net[i]))) {
            return netName(idx.getPinPortInst(p));
        }
        return netIn(e.resolve(s.net[i]), idx.getLeafHier(l));
    }

    /** Local net names of physical nets routed through new hierarchical ports, by (net, hier). */
    private final Map<Long, String> routed = new HashMap<>();

    private String available(int n, int h) {
        String name = routed.get(((long) n << 32) | h);
        return name != null ? name : localNet(n, h);
    }

    private static int depth(EDIFLeafIndex idx, int h) {
        int d = 0;
        for (int a = h; a > 0; a = idx.getHierParent(a)) {
            d++;
        }
        return d;
    }

    /**
     * Returns the name of a net in hierarchical instance h that belongs to physical net n,
     * creating ports and nets through the hierarchy if n does not reach h yet.
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
            String port = uniquePort(idx.getHierCell(c), inner + "_opt_o");
            add(c, new Op(OP_NEWPORT, port, "OUTPUT", inner, null));
            String outer = idx.getHierInst(c).getName() + "_" + port;
            add(parent, new Op(OP_CONNECT, idx.getHierInst(c).getName(), port, outer, null));
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
            String port = uniquePort(idx.getHierCell(c), outer + "_opt_i");
            add(c, new Op(OP_NEWPORT, port, "INPUT", port, null));
            add(parent, new Op(OP_CONNECT, idx.getHierInst(c).getName(), port, outer, null));
            routed.put(((long) n << 32) | c, port);
        }
        return available(n, h);
    }

    private static String uniquePort(EDIFCell cell, String base) {
        String name = base;
        int k = 1;
        while (cell.getPort(name) != null || cell.getNet(name) != null) {
            name = base + "_" + k++;
        }
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
                add(h, new Op(OP_REMOVE, inst.getName(), null, null, null));
                continue;
            }
            Lut s = e.lut[l];
            if (s != null && s.changed) {
                List<String> nets = new ArrayList<>();
                for (int i = 0; i < s.size; i++) {
                    nets.add(inputNetName(l, s, i));
                }
                String type;
                if (s.dual) {
                    // Same multi-output cell type: keep all its outputs
                    type = inst.getCellType().getName();
                    for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                        if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                            EDIFPortInst pi = idx.getPinPortInst(p);
                            nets.add(pi.getName() + OUT_SEP + netName(pi));
                        }
                    }
                } else {
                    type = "LUT" + s.size;
                    int out = e.outputPin(l);
                    nets.add("O" + OUT_SEP + (out < 0 ? null : netName(idx.getPinPortInst(out))));
                }
                String init = initString(s.bits, s.table);
                add(h, new Op(OP_LUT, inst.getName(), type, init, nets.toArray(new String[0])));
            }
            Map<String, String> pm = e.props.get(l);
            if (pm != null) {
                for (Map.Entry<String, String> p : pm.entrySet()) {
                    add(h, new Op(OP_PROP, inst.getName(), p.getKey(), p.getValue(), null));
                }
            }
            String mark = e.marks.get(l);
            if (mark != null) {
                String val = null;
                for (String m : mark.split(" ")) {
                    val = appendMark(val != null ? val : propValue(inst, "OPT_MODIFIED"), m);
                }
                add(h, new Op(OP_PROP, inst.getName(), "OPT_MODIFIED", val, null));
            }
            boolean rebuilt = s != null && s.changed;
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                int t = e.pinMovedTo[p];
                if (t < 0 || rebuilt) {
                    continue;
                }
                int target = e.resolve(t);
                if (e.resolve(idx.getPinNet(p)) == target) {
                    continue;
                }
                String net = netIn(target, h);
                add(h, new Op(OP_MOVE, inst.getName(), idx.getPinPortInst(p).getName(), net,
                        null));
            }
        }
        for (int i = 0; i < e.aliasLeaf.size(); i++) {
            int l = e.aliasLeaf.get(i);
            int h = idx.getLeafHier(l);
            EDIFPortInst in = idx.getPinPortInst(e.aliasInPin.get(i));
            EDIFPortInst out = idx.getPinPortInst(e.aliasOutPin.get(i));
            add(h, new Op(OP_MERGE, netName(out), netName(in), null, null));
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
                add(idx.getSegHier(seg), new Op(OP_RMNET, idx.getSegEDIFNet(seg).getName(), null,
                        null, null));
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
            String type = (en.getKey() & 1) == 0 ? "GND" : "VCC";
            String first = netName(idx.getPinPortInst(list.get(0)[1]));
            add(h, new Op(OP_STATIC, first, type, null, null));
            for (int i = 1; i < list.size(); i++) {
                int dp = list.get(i)[1];
                add(h, new Op(OP_MERGE, netName(idx.getPinPortInst(dp)), first, null, null));
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
            add(h, new Op(OP_PROP, inst.getName(), "OPT_MODIFIED", markValue(inst, mark), null));
            e.marks.put(l, mark);
        }
    }

    // ---------------------------------------------------------------- uniquification

    private int[] sigId;

    /** Assigns cell definitions so that instances with different edits have their own copy. */
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
            }
            EDIFCellInst inst = newCell[idx.getHierParent(h)].getCellInst(
                    idx.getHierInst(h).getName());
            if (inst.getCellType() != def) {
                inst.setCellType(def);
            }
            newCell[h] = def;
        }
        stats.addCellsUniquified(copies);
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
                EDIFPortInstList list = en.getKey().getEDIFPortInstList();
                Set<EDIFPortInst> set = en.getValue();
                if (list != null) {
                    list.removeIf(set::contains);
                }
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
            for (EDIFNet net : added) {
                EDIFPortInstList list = net.getEDIFPortInstList();
                if (list != null) {
                    list.reSortList();
                }
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
                if (op.type == OP_NEWPORT && cell.getPort(op.a) == null) {
                    EDIFPort port = cell.createPort(op.a, op.b.equals("INPUT")
                            ? EDIFDirection.INPUT : EDIFDirection.OUTPUT, 1);
                    EDIFNet net = cell.getNet(op.c);
                    if (net == null) {
                        net = cell.createNet(op.c);
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
        // Phase A: removals
        for (Op op : list) {
            EDIFCellInst inst;
            switch (op.type) {
                case OP_REMOVE:
                    inst = cell.getCellInst(op.a);
                    if (inst != null) {
                        for (EDIFPortInst pi : inst.getPortInsts()) {
                            batch.remove(pi);
                        }
                        cell.removeCellInst(inst);
                    }
                    break;
                case OP_LUT:
                    inst = cell.getCellInst(op.a);
                    for (EDIFPortInst pi : new ArrayList<>(inst.getPortInsts())) {
                        batch.remove(pi);
                        inst.removePortInst(pi);
                    }
                    break;
                case OP_MOVE:
                    inst = cell.getCellInst(op.a);
                    EDIFPortInst pi = inst.getPortInst(op.b);
                    if (pi != null) {
                        batch.remove(pi);
                    }
                    break;
                case OP_RMNET: {
                    EDIFNet rm = cell.getNet(op.a);
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
                    break;
                }
                default:
                    break;
            }
        }
        batch.flushRemovals();
        // Phase B: merges
        Map<EDIFNet, EDIFNet> merged = new IdentityHashMap<>();
        Map<String, EDIFNet> byName = new HashMap<>();
        for (Op op : list) {
            if (op.type != OP_MERGE || op.a == null || op.b == null) {
                continue;
            }
            EDIFNet from = net(cell, byName, merged, op.a);
            EDIFNet into = net(cell, byName, merged, op.b);
            if (from == null || into == null || from == into) {
                continue;
            }
            for (EDIFPortInst pi : new ArrayList<>(from.getPortInsts())) {
                batch.add(into, pi);
            }
            from.getEDIFPortInstList().clear();
            cell.removeNet(from);
            merged.put(from, into);
            byName.put(from.getName(), from);
        }
        // Phase C: additions (new hierarchical connections first, they may create nets)
        List<Op> ordered = new ArrayList<>(list.size());
        for (Op op : list) {
            if (op.type == OP_CONNECT) {
                ordered.add(op);
            }
        }
        for (Op op : list) {
            if (op.type != OP_CONNECT) {
                ordered.add(op);
            }
        }
        for (Op op : ordered) {
            EDIFCellInst inst;
            switch (op.type) {
                case OP_LUT:
                    inst = cell.getCellInst(op.a);
                    EDIFCell type = op.b.matches("LUT[1-6]") ? luts[op.b.charAt(3) - '0']
                            : inst.getCellType();
                    inst.setCellTypeRaw(type);
                    inst.addProperty("INIT", op.c);
                    int in = 0;
                    for (String n : op.nets) {
                        String pin;
                        String netName;
                        int eq = n == null ? -1 : n.indexOf(OUT_SEP);
                        if (eq > 0) {
                            pin = n.substring(0, eq);
                            netName = n.substring(eq + 1);
                        } else {
                            pin = "I" + in++;
                            netName = n;
                        }
                        if (netName == null || netName.equals("null")) {
                            continue;
                        }
                        EDIFNet net = net(cell, byName, merged, netName);
                        if (net == null) {
                            continue;
                        }
                        EDIFPortInst npi = new EDIFPortInst(type.getPort(pin), null, inst, true);
                        batch.add(net, npi);
                    }
                    inst.getEDIFPortInstList().reSortList();
                    break;
                case OP_PROP:
                    inst = cell.getCellInst(op.a);
                    if (inst != null) {
                        inst.addProperty(op.b, op.c);
                    }
                    break;
                case OP_MOVE:
                    inst = cell.getCellInst(op.a);
                    EDIFPortInst pi = inst.getPortInst(op.b);
                    EDIFNet target = op.c == null ? null : net(cell, byName, merged, op.c);
                    if (pi != null && target != null) {
                        batch.add(target, pi);
                    }
                    break;
                case OP_CONNECT: {
                    EDIFCellInst child = cell.getCellInst(op.a);
                    EDIFNet cn = net(cell, byName, merged, op.c);
                    if (cn == null) {
                        cn = cell.createNet(op.c);
                    }
                    if (child != null && child.getPortInst(op.b) == null) {
                        batch.add(cn, new EDIFPortInst(child.getCellType().getPort(op.b), null,
                                child, true));
                    }
                    break;
                }
                case OP_STATIC:
                    EDIFNet net = net(cell, byName, merged, op.a);
                    if (net != null) {
                        addStaticSource(cell, net, op.b);
                    }
                    break;
                default:
                    break;
            }
        }
        batch.flushAdds();
    }

    /** Looks up a net by name, following merges (including nets removed by a merge). */
    private static EDIFNet net(EDIFCell cell, Map<String, EDIFNet> byName,
                               Map<EDIFNet, EDIFNet> merged, String name) {
        EDIFNet n = cell.getNet(name);
        if (n == null) {
            n = byName.get(name);
        }
        return find(merged, n);
    }

    private static EDIFNet find(Map<EDIFNet, EDIFNet> merged, EDIFNet n) {
        if (n == null) {
            return null;
        }
        EDIFNet r = n;
        EDIFNet next;
        while ((next = merged.get(r)) != null) {
            r = next;
        }
        return r;
    }

    private void addStaticSource(EDIFCell cell, EDIFNet net, String type) {
        EDIFCell src = netlist.getHDIPrimitive(type.equals("GND") ? Unisim.GND : Unisim.VCC);
        int k = staticCounter.getOrDefault(cell, 0);
        String name;
        do {
            name = type + "_" + (++k);
        } while (cell.getCellInst(name) != null);
        staticCounter.put(cell, k);
        EDIFCellInst inst = cell.createChildCellInst(name, src);
        net.createPortInst(type.equals("GND") ? "G" : "P", inst);
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
        // Static sources left without a net, and nets left without any connection
        for (EDIFCell cell : touched) {
            for (EDIFCellInst inst : new ArrayList<>(cell.getCellInsts())) {
                if (inst.getCellType().isStaticSource()) {
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
            if (EDIFLeafIndex.isLeafCell(t)) {
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
                EDIFNet r = findNet(uf, net);
                String g = rootPort.putIfAbsent(r, pi.getName());
                if (g != null) {
                    d.feedthrough = true;
                }
                d.portGroup.put(pi.getName(), g != null ? g : pi.getName());
            }
        }
        return d;
    }

    private static EDIFNet findNet(Map<EDIFNet, EDIFNet> uf, EDIFNet n) {
        EDIFNet r = n;
        EDIFNet next;
        while ((next = uf.get(r)) != null && next != r) {
            r = next;
        }
        return r;
    }

    private static void union(Map<EDIFNet, EDIFNet> uf, EDIFNet a, EDIFNet b) {
        EDIFNet ra = findNet(uf, a);
        EDIFNet rb = findNet(uf, b);
        if (ra != rb) {
            uf.put(ra, rb);
        }
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
                if (EDIFLeafIndex.isLeafCell(t)) {
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
                    count += 1 + countHierInsts(t);
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

    private static int countHierInsts(EDIFCell cell) {
        int n = 0;
        for (EDIFCellInst child : cell.getCellInsts()) {
            if (!EDIFLeafIndex.isLeafCell(child.getCellType())) {
                n += 1 + countHierInsts(child.getCellType());
            }
        }
        return n;
    }
}
