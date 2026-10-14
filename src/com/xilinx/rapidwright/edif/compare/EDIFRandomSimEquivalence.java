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

package com.xilinx.rapidwright.edif.compare;

import java.math.BigInteger;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.VivadoProp;
import com.xilinx.rapidwright.design.VivadoPropType;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.edif.EDIFValueType;
import com.xilinx.rapidwright.edif.optimizer.EDIFLeafIndex;
import com.xilinx.rapidwright.edif.optimizer.PrimitiveDefaults;
import com.xilinx.rapidwright.edif.optimizer.VerilogLiterals;

/**
 * Checks that an optimized netlist is functionally equivalent to its original by bit-parallel
 * random simulation. Combinational primitives (LUTs, LUT6_2, INV, BUF, LUTCY1/LUTCY2/LUT6CY,
 * LOOKAHEAD8) are simulated; all other cells are cut points whose outputs receive identical
 * pseudo-random values in both netlists (keyed by instance name). The observed points are the
 * top-level outputs and the (inversion-corrected) input pins of every cut cell present in both
 * netlists. Since cut cells are not simulated, a cut cell present in both netlists must also
 * have the same type, the same properties (except OPT_MODIFIED and the IS_*_INVERTED inversions,
 * which are simulated; values are compared exactly unless the EDIF value or, for a library
 * primitive of a known part, its declared parameter type is numeric or boolean) and the same
 * connected inputs (a pin on an undriven net counts as
 * unconnected); any difference is a mismatch. Flip-flops that exist only in the original netlist
 * are assumed to have been removed as constants: they take their constant value and their next
 * state is checked to equal it.
 */
public class EDIFRandomSimEquivalence {

    private static final int C_CUT = 0;
    private static final int C_LUT = 1;
    private static final int C_LUT62 = 2;
    private static final int C_INV = 3;
    private static final int C_BUF = 4;
    private static final int C_LUTCY = 5;
    private static final int C_LOOKAHEAD = 6;
    private static final int C_GND = 7;
    private static final int C_VCC = 8;

    /** Simulation model of one netlist. */
    private static class Model {
        final EDIFLeafIndex idx;
        final int[] kind;
        final long[] init;
        final int[] order;
        final long[] val;
        final Map<String, Integer> byName = new HashMap<>();
        final String[] names;
        /** Cells not simulated because of their type (black boxes included). */
        final boolean[] cutType;
        /** Nets with a driver (a leaf output, bidirectional pin or top-level input). */
        final boolean[] driven;
        /** Combinational cells turned into cut points because they are on a loop. */
        boolean[] loopCut;
        /** Nets whose value depends on a loop cut through simulated logic. */
        boolean[] tainted;

        Model(EDIFNetlist nl) {
            idx = new EDIFLeafIndex(nl);
            int n = idx.getNumLeaves();
            kind = new int[n];
            init = new long[n];
            names = new String[n];
            cutType = new boolean[n];
            for (int l = 0; l < n; l++) {
                names[l] = idx.getLeafName(l);
                byName.put(names[l], l);
                EDIFCellInst inst = idx.getLeafInst(l);
                kind[l] = idx.isLeafBlackBox(l) ? C_CUT : kindOf(inst.getCellType().getName());
                EDIFPropertyValue v = inst.getProperty("INIT");
                if (v != null && (kind[l] == C_LUT || kind[l] == C_LUT62 || kind[l] == C_LUTCY)) {
                    init[l] = LUTTools.getInitValue(v.getValue());
                }
                cutType[l] = kind[l] == C_CUT;
            }
            driven = new boolean[idx.getNumNets()];
            for (int p = 0; p < idx.getNumPins(); p++) {
                if (idx.getPinDir(p) != EDIFLeafIndex.SINK && idx.getPinNet(p) >= 0) {
                    driven[idx.getPinNet(p)] = true;
                }
            }
            loopCut = new boolean[n];
            order = topoOrder();
            val = new long[idx.getNumNets()];
            computeTaint();
        }

        /** Marks nets fed (through simulated logic) by cells cut to break loops. */
        private void computeTaint() {
            tainted = new boolean[idx.getNumNets()];
            for (int l = 0; l < idx.getNumLeaves(); l++) {
                if (loopCut[l]) {
                    taintOutputs(l);
                }
            }
            for (int node : order) {
                int l = node / 4;
                for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                    int n = idx.getPinNet(p);
                    if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER && n >= 0 && tainted[n]) {
                        taintOutputs(l);
                        break;
                    }
                }
            }
        }

        private void taintOutputs(int l) {
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                int n = idx.getPinNet(p);
                if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER && n >= 0) {
                    tainted[n] = true;
                }
            }
        }

        private static int kindOf(String type) {
            if (type.matches("LUT[1-6]")) {
                return C_LUT;
            }
            switch (type) {
                case "LUT6_2":
                    return C_LUT62;
                case "INV":
                    return C_INV;
                case "BUF":
                    return C_BUF;
                case "LUT6CY":
                case "LUTCY1":
                case "LUTCY2":
                    return C_LUTCY;
                case "LOOKAHEAD8":
                    return C_LOOKAHEAD;
                case "GND":
                    return C_GND;
                case "VCC":
                    return C_VCC;
                default:
                    return C_CUT;
            }
        }

        boolean isComb(int l) {
            return kind[l] != C_CUT && kind[l] != C_GND && kind[l] != C_VCC;
        }

        /** @return The number of evaluation nodes of a leaf (one per LOOKAHEAD8 output). */
        private int subs(int l) {
            return kind[l] == C_LOOKAHEAD ? 4 : 1;
        }

        /** @return True if node (l, sub) depends on input pin of leaf l. */
        private boolean dependsOn(int l, int sub, String pin) {
            if (kind[l] != C_LOOKAHEAD || pin.equals("CIN")) {
                return true;
            }
            char c = pin.charAt(pin.length() - 1);
            return c - 'A' <= 2 * sub + 1;
        }

        private int nodeOf(int l, String outPin) {
            if (kind[l] != C_LOOKAHEAD) {
                return l * 4;
            }
            return l * 4 + "BDFH".indexOf(outPin.charAt(outPin.length() - 1));
        }

        /**
         * Orders evaluation nodes (one per combinational leaf, four per LOOKAHEAD8) so drivers
         * come first; leaves in true loops become cuts.
         */
        private int[] topoOrder() {
            int n = idx.getNumLeaves();
            int[] indeg = new int[4 * n];
            List<List<Integer>> succ = new ArrayList<>();
            for (int i = 0; i < 4 * n; i++) {
                succ.add(null);
            }
            for (int l = 0; l < n; l++) {
                if (!isComb(l)) {
                    continue;
                }
                for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                    if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER || idx.getPinNet(p) < 0) {
                        continue;
                    }
                    int dp = driverPin(idx.getPinNet(p));
                    int d = dp < 0 ? -1 : idx.getPinLeaf(dp);
                    if (d < 0 || !isComb(d)) {
                        continue;
                    }
                    int dn = nodeOf(d, idx.getPinPortInst(dp).getName());
                    String pin = idx.getPinPortInst(p).getName();
                    for (int sub = 0; sub < subs(l); sub++) {
                        if (dependsOn(l, sub, pin)) {
                            indeg[l * 4 + sub]++;
                            if (succ.get(dn) == null) {
                                succ.set(dn, new ArrayList<>());
                            }
                            succ.get(dn).add(l * 4 + sub);
                        }
                    }
                }
            }
            int[] q = new int[4 * n];
            int head = 0;
            int tail = 0;
            for (int l = 0; l < n; l++) {
                if (isComb(l)) {
                    for (int sub = 0; sub < subs(l); sub++) {
                        if (indeg[l * 4 + sub] == 0) {
                            q[tail++] = l * 4 + sub;
                        }
                    }
                }
            }
            while (head < tail) {
                int v = q[head++];
                if (succ.get(v) != null) {
                    for (int s : succ.get(v)) {
                        if (--indeg[s] == 0) {
                            q[tail++] = s;
                        }
                    }
                }
            }
            for (int l = 0; l < n; l++) {
                if (isComb(l)) {
                    for (int sub = 0; sub < subs(l); sub++) {
                        if (indeg[l * 4 + sub] > 0) {
                            kind[l] = C_CUT;
                            loopCut[l] = true;
                        }
                    }
                }
            }
            int k = 0;
            int[] order = new int[tail];
            for (int i = 0; i < tail; i++) {
                if (isComb(q[i] / 4)) {
                    order[k++] = q[i];
                }
            }
            return Arrays.copyOf(order, k);
        }

        int driverPin(int net) {
            for (int i = idx.getNetPinStart(net); i < idx.getNetPinEnd(net); i++) {
                int q = idx.getNetPin(i);
                if (idx.getPinDir(q) == EDIFLeafIndex.DRIVER) {
                    return q;
                }
            }
            return -1;
        }

        int driverLeaf(int net) {
            for (int i = idx.getNetPinStart(net); i < idx.getNetPinEnd(net); i++) {
                int q = idx.getNetPin(i);
                if (idx.getPinDir(q) == EDIFLeafIndex.DRIVER) {
                    return idx.getPinLeaf(q);
                }
            }
            return -1;
        }

        long in(int l, String pin) {
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                if (idx.getPinPortInst(p).getName().equals(pin)) {
                    int n = idx.getPinNet(p);
                    return n < 0 ? 0L : val[n];
                }
            }
            return 0L;
        }

        void out(int l, String pin, long v) {
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                if (idx.getPinPortInst(p).getName().equals(pin)) {
                    int n = idx.getPinNet(p);
                    if (n >= 0) {
                        val[n] = v;
                    }
                }
            }
        }

        /** @return True if a property of leaf l (or its primitive default) is true. */
        boolean attrTrue(int l, String key) {
            return VerilogLiterals.isTrue(PrimitiveDefaults.getValue(idx.getLeafInst(l), key));
        }
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    private static long rand(String key, int round) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < key.length(); i++) {
            h = (h ^ key.charAt(i)) * 0x100000001b3L;
        }
        return mix(h + 0x9e3779b97f4a7c15L * (round + 1));
    }

    /** Evaluates a function given by a truth table on bit-parallel inputs. */
    private static long lut(long table, long[] in, int k) {
        long r = 0;
        for (int row = 0; row < (1 << k); row++) {
            if (((table >>> row) & 1L) == 0) {
                continue;
            }
            long m = -1L;
            for (int i = 0; i < k; i++) {
                m &= ((row >> i) & 1) != 0 ? in[i] : ~in[i];
            }
            r |= m;
        }
        return r;
    }

    private static void evalComb(Model m, int l) {
        long[] in = new long[6];
        switch (m.kind[l]) {
            case C_LUT: {
                int k = m.idx.getLeafInst(l).getCellType().getName().charAt(3) - '0';
                for (int i = 0; i < k; i++) {
                    in[i] = m.in(l, "I" + i);
                }
                m.out(l, "O", lut(m.init[l], in, k));
                break;
            }
            case C_LUT62:
                for (int i = 0; i < 6; i++) {
                    in[i] = m.in(l, "I" + i);
                }
                m.out(l, "O6", lut(m.init[l], in, 6));
                m.out(l, "O5", lut(m.init[l] & 0xFFFFFFFFL, in, 5));
                break;
            case C_INV:
                m.out(l, "O", ~m.in(l, "I"));
                break;
            case C_BUF:
                m.out(l, "O", m.in(l, "I"));
                break;
            case C_LUTCY: {
                for (int i = 0; i < 5; i++) {
                    in[i] = m.in(l, "I" + i);
                }
                String type = m.idx.getLeafInst(l).getCellType().getName();
                long init = m.init[l];
                long prop = lut(init & 0xFFFFL, in, 4);
                if (type.equals("LUT6CY")) {
                    m.out(l, "O51", lut(init & 0xFFFFFFFFL, in, 5));
                    m.out(l, "O52", lut(init >>> 32, in, 5));
                    m.out(l, "PROP", prop);
                } else {
                    m.out(l, "O", lut(init & 0xFFFFFFFFL, in, 5));
                    if (type.equals("LUTCY1")) {
                        m.out(l, "PROP", prop);
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private static void evalLookahead(Model m, int l, int sub) {
        long cin = m.in(l, "CIN");
        long[] cy = new long[8];
        long[] pr = new long[8];
        String abc = "ABCDEFGH";
        for (int i = 0; i < 8; i++) {
            cy[i] = m.in(l, "CY" + abc.charAt(i));
            pr[i] = m.in(l, "PROP" + abc.charAt(i));
        }
        long lb = m.attrTrue(l, "LOOKB") ? -1L : 0L;
        long ld = m.attrTrue(l, "LOOKD") ? -1L : 0L;
        long lf = m.attrTrue(l, "LOOKF") ? -1L : 0L;
        long lh = m.attrTrue(l, "LOOKH") ? -1L : 0L;
        long propAB = pr[0] & pr[1] & lb;
        long propABF = pr[0] & pr[1] & ~lb;
        long propCD = pr[2] & pr[3] & ld;
        long propEF = pr[4] & pr[5] & lf;
        long propGH = pr[6] & pr[7] & lh;
        long coutB = ((~lb | ~pr[1]) & cy[1])
                | ((lb & pr[1]) & ((~pr[0] & cy[0]) | (pr[0] & cin)));
        long coutD = (propAB & propCD & cin) | ((~ld | ~pr[3]) & cy[3])
                | ((ld & pr[3]) & ((~pr[2] & cy[2]) | (pr[2] & ~propAB & coutB)
                | (pr[2] & propAB & cin)));
        long coutF = ((~lf | ~pr[5]) & cy[5])
                | ((lf & pr[5]) & ((~pr[4] & cy[4]) | (pr[4] & ~(propAB & propCD) & coutD)
                | (pr[4] & propAB & propCD & cin)));
        long coutH = (propAB & propCD & propEF & propGH & cin) | ((~lh | ~pr[7]) & cy[7])
                | ((lh & pr[7]) & ((~pr[6] & cy[6])
                | (pr[6] & ~(propAB & propCD & propEF) & coutF)
                | (pr[6] & ~(propAB & propCD) & pr[4] & pr[5] & coutD)
                | (propABF & propCD & propEF & propGH & cin)));
        long[] couts = {coutB, coutD, coutF, coutH};
        m.out(l, "COUT" + "BDFH".charAt(sub), couts[sub]);
    }

    /** Assigns sources (ports, constants, cut outputs) and evaluates the logic. */
    private static void simulate(Model m, int round, Map<Integer, Long> forced) {
        Arrays.fill(m.val, 0L);
        EDIFLeafIndex idx = m.idx;
        for (int p = idx.getNumLeafPins(); p < idx.getNumPins(); p++) {
            if (idx.getPinDir(p) != EDIFLeafIndex.SINK && idx.getPinNet(p) >= 0) {
                m.val[idx.getPinNet(p)] = rand("PORT:" + idx.getPinPortInst(p).getName(), round);
            }
        }
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            if (m.kind[l] == C_COMB_SKIP) {
                continue;
            }
            boolean gnd = m.kind[l] == C_GND;
            boolean vcc = m.kind[l] == C_VCC;
            boolean cut = m.kind[l] == C_CUT;
            if (!gnd && !vcc && !cut) {
                continue;
            }
            Long f = forced.get(l);
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                int n = idx.getPinNet(p);
                if (idx.getPinDir(p) == EDIFLeafIndex.SINK || n < 0) {
                    continue;
                }
                m.val[n] = gnd ? 0L : vcc ? -1L : f != null ? f
                        : rand(m.names[l] + "/" + idx.getPinPortInst(p).getName(), round);
            }
        }
        for (int node : m.order) {
            int l = node / 4;
            if (m.kind[l] == C_LOOKAHEAD) {
                evalLookahead(m, l, node % 4);
            } else {
                evalComb(m, l);
            }
        }
    }

    private static final int C_COMB_SKIP = -1;

    private int mismatches;
    private int checked;
    private final List<String> examples = new ArrayList<>();
    private int maxExamples = 20;

    private int unchecked;

    /** @return Observations skipped because they depend on a combinational loop. */
    public int getUnchecked() {
        return unchecked;
    }

    private static boolean isTainted(Model m, int l, String pin) {
        for (int p = m.idx.getLeafPinStart(l); p < m.idx.getLeafPinEnd(l); p++) {
            if (m.idx.getPinPortInst(p).getName().equals(pin)) {
                int n = m.idx.getPinNet(p);
                return n >= 0 && m.tainted[n];
            }
        }
        return false;
    }

    private void fail(String msg) {
        mismatches++;
        if (examples.size() < maxExamples) {
            examples.add(msg);
        }
    }

    /**
     * Effective value of an input pin of a cut cell: inversion attribute applied. Null if the pin
     * is unconnected or on an undriven net.
     */
    private static Long pinValue(Model m, int l, String pin) {
        for (int p = m.idx.getLeafPinStart(l); p < m.idx.getLeafPinEnd(l); p++) {
            if (m.idx.getPinPortInst(p).getName().equals(pin)) {
                int n = m.idx.getPinNet(p);
                if (n < 0 || !m.driven[n]) {
                    return null;
                }
                long v = m.val[n];
                return m.attrTrue(l, "IS_" + pin + "_INVERTED") ? ~v : v;
            }
        }
        return null;
    }

    /**
     * Checks equivalence of the original and optimized netlists.
     * @param orig The original netlist.
     * @param opt The optimized netlist.
     * @param rounds Number of 64-pattern simulation rounds.
     * @return The number of mismatching observations.
     */
    public int check(EDIFNetlist orig, EDIFNetlist opt, int rounds) {
        mismatches = 0;
        checked = 0;
        unchecked = 0;
        removedNonConstant = 0;
        examples.clear();
        Series s = seriesOf(orig);
        if (s != series) {
            declared.clear();
            series = s;
        }
        // The top-level interfaces must match
        for (String d : EDIFLeafNetlistComparator.interfaceDiffs(orig, opt)) {
            fail(d);
        }
        Model a = new Model(orig);
        Model b = new Model(opt);
        // Sequential cells removed by the optimization that still influence an observed point
        // must have been constant: they are assumed constant in the original and checked
        Map<Integer, Long> forced = new HashMap<>();
        List<Integer> removedFF = new ArrayList<>();
        boolean[] observed = observedCone(a, b);
        for (int l = 0; l < a.idx.getNumLeaves(); l++) {
            EDIFCell cell = a.idx.getLeafInst(l).getCellType();
            String type = cell.getName();
            if (!observed[l] || b.byName.containsKey(a.names[l]) || !isPrimitive(cell)) {
                continue;
            }
            if (type.matches("FD[RSCP]E|LD[CP]E")) {
                boolean init1 = a.attrTrue(l, "INIT");
                forced.put(l, init1 ? -1L : 0L);
                removedFF.add(l);
            } else if (type.matches("SRL16E|SRLC32E")) {
                // A removed shift register can only be constant if all its initial bits are
                // equal (then D must equal them); otherwise it keeps pseudo-random values
                long all = type.equals("SRL16E") ? 0xFFFFL : 0xFFFFFFFFL;
                long init = LUTTools.getInitValue(PrimitiveDefaults.getValue(
                        a.idx.getLeafInst(l), "INIT")) & all;
                if (init == 0L || init == all) {
                    forced.put(l, init == 0L ? 0L : -1L);
                    removedFF.add(l);
                } else {
                    removedNonConstant++;
                }
            }
        }
        // Async clear/preset tied active determine the constant regardless of INIT
        simulate(a, 0, forced);
        for (int l : removedFF) {
            String type = a.idx.getLeafInst(l).getCellType().getName();
            String rst = type.equals("FDRE") ? "R" : type.equals("FDSE") ? "S"
                    : type.equals("FDCE") || type.equals("LDCE") ? "CLR" : "PRE";
            Long r = type.startsWith("SRL") ? null : pinValue(a, l, rst);
            if ((type.equals("FDCE") || type.equals("FDPE") || type.startsWith("LD"))
                    && r != null && r == -1L) {
                forced.put(l, type.equals("FDCE") || type.equals("LDCE") ? 0L : -1L);
            }
        }
        // Removed sequential cells that are not inductively constant were removed as dead
        // logic: they keep pseudo-random values (any dependence on them shows as a mismatch)
        for (int iter = 0; iter < 20; iter++) {
            List<Integer> notConst = new ArrayList<>();
            for (int round = 0; round < rounds; round++) {
                simulate(a, round, forced);
                for (int l : removedFF) {
                    if (forced.containsKey(l) && !isInductivelyConstant(a, l, forced.get(l))) {
                        notConst.add(l);
                    }
                }
            }
            if (notConst.isEmpty()) {
                break;
            }
            for (int l : notConst) {
                forced.remove(l);
            }
            removedFF.removeAll(notConst);
            removedNonConstant += new java.util.HashSet<>(notConst).size();
        }
        compareCutCells(a, b);
        for (int round = 0; round < rounds; round++) {
            simulate(a, round, forced);
            simulate(b, round, new HashMap<>());
            compareRound(a, b, round, forced, removedFF);
        }
        return mismatches;
    }

    /** Properties a surviving cut cell may change (inversions are part of the simulation). */
    private static boolean isIgnoredProperty(String key) {
        String k = key.toUpperCase();
        return k.equals("OPT_MODIFIED") || (k.startsWith("IS_") && k.endsWith("_INVERTED"));
    }

    /** Series of the original netlist (null if unknown), for the declared property types. */
    private Series series;
    /** Declared properties per primitive type. */
    private final Map<String, Map<String, VivadoProp>> declared = new HashMap<>();

    /**
     * @return The series of the part of a netlist, null if it has no part or the part is not in
     * the part database (property types are then not available).
     */
    private static Series seriesOf(EDIFNetlist netlist) {
        String partName = EDIFTools.getPartName(netlist);
        if (partName == null) {
            return null;
        }
        try {
            Part part = PartNameTools.getPart(partName);
            return part == null ? null : part.getSeries();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** @return True if a cell is a library primitive (not a user-defined cell of that name). */
    private static boolean isPrimitive(EDIFCell cell) {
        EDIFLibrary lib = cell.getLibrary();
        return lib != null && lib.isHDIPrimitivesLibrary();
    }

    /** @return The type of a cell: its name, qualified by its library unless a primitive. */
    private static String typeName(EDIFCell cell) {
        if (isPrimitive(cell) || cell.getLibrary() == null) {
            return cell.getName();
        }
        return cell.getLibrary().getName() + ":" + cell.getName();
    }

    /** @return The declaration (type, default) of a property of a primitive cell, or null. */
    private VivadoProp declaredProperty(EDIFCell cell, String key) {
        if (series == null || !isPrimitive(cell)) {
            return null;
        }
        Map<String, VivadoProp> props = declared.computeIfAbsent(cell.getName(), t -> {
            Map<String, VivadoProp> m = Design.getDefaultCellProperties(series, t);
            return m == null ? new HashMap<>() : m;
        });
        VivadoProp p = props.get(key);
        return p != null ? p : props.get(key.toUpperCase());
    }

    /**
     * @return The value of a property in a canonical form. Only properties known to be numbers or
     * booleans (by their declared type for the primitive, or their EDIF value type) are
     * normalized; anything else, e.g. a file name, is kept exactly. Integers are compared by
     * value, bit vectors (HEX/BINARY) by the bits they assign at the parameter's width.
     */
    private String normalize(EDIFCell cell, String key, EDIFPropertyValue pv) {
        String value = pv.getValue();
        VivadoProp decl = declaredProperty(cell, key);
        VivadoPropType type = decl == null ? null : decl.getType();
        boolean bool = type == VivadoPropType.BOOL || pv.getType() == EDIFValueType.BOOLEAN;
        boolean num = type == VivadoPropType.HEX || type == VivadoPropType.BINARY
                || type == VivadoPropType.INT || pv.getType() == EDIFValueType.INTEGER;
        String v = value.trim();
        if (bool) {
            Boolean b = VerilogLiterals.parseBoolean(v);
            if (b != null) {
                return b.toString();
            }
        } else if (num) {
            BigInteger n = VerilogLiterals.parseNumber(v);
            if (n != null) {
                if (type == VivadoPropType.HEX || type == VivadoPropType.BINARY) {
                    // A bit vector: the bits assigned to the parameter (of its declared width,
                    // else of the literal's width), so 36'shfffffffff == 36'hfffffffff
                    Integer width = VerilogLiterals.parseWidth(decl.getValue());
                    if (width == null || width <= 0) {
                        width = VerilogLiterals.parseWidth(v);
                    }
                    if (width > 0) {
                        n = VerilogLiterals.toWidth(n, width);
                    }
                }
                return n.toString(16);
            }
        } else if (type == VivadoPropType.DOUBLE) {
            try {
                return Double.toString(Double.parseDouble(v));
            } catch (NumberFormatException e) {
                // Kept exactly
            }
        }
        return value;
    }

    private Map<String, String> properties(Model m, int l) {
        EDIFCellInst inst = m.idx.getLeafInst(l);
        EDIFCell cell = inst.getCellType();
        Map<String, String> props = new TreeMap<>();
        for (Map.Entry<String, EDIFPropertyValue> e : inst.getPropertiesMap().entrySet()) {
            if (!isIgnoredProperty(e.getKey())) {
                props.put(e.getKey().toUpperCase(), normalize(cell, e.getKey(), e.getValue()));
            }
        }
        return props;
    }

    private static Set<String> inputPins(Model m, int l) {
        Set<String> pins = new TreeSet<>();
        for (int p = m.idx.getLeafPinStart(l); p < m.idx.getLeafPinEnd(l); p++) {
            if (m.idx.getPinDir(p) != EDIFLeafIndex.DRIVER) {
                pins.add(m.idx.getPinPortInst(p).getName());
            }
        }
        return pins;
    }

    /**
     * Checks that cells not simulated because of their type and present in both netlists have
     * the same type, behavior-defining properties and connected inputs.
     */
    private void compareCutCells(Model a, Model b) {
        for (int la = 0; la < a.idx.getNumLeaves(); la++) {
            Integer lb = b.byName.get(a.names[la]);
            if (lb == null || (!a.cutType[la] && !b.cutType[lb])) {
                continue;
            }
            String name = a.names[la];
            String ta = typeName(a.idx.getLeafInst(la).getCellType());
            String tb = typeName(b.idx.getLeafInst(lb).getCellType());
            checked++;
            if (!ta.equals(tb) || a.cutType[la] != b.cutType[lb]) {
                fail("cell " + name + " type " + ta + " -> " + tb);
                continue;
            }
            Map<String, String> pa = properties(a, la);
            Map<String, String> pb = properties(b, lb);
            if (!pa.equals(pb)) {
                fail("cell " + name + " properties " + pa + " -> " + pb);
            }
            Set<String> pins = inputPins(a, la);
            pins.addAll(inputPins(b, lb));
            for (String pin : pins) {
                boolean ca = pinValue(a, la, pin) != null;
                boolean cb = pinValue(b, lb, pin) != null;
                if (ca != cb) {
                    fail("pin " + name + "/" + pin + (ca ? " disconnected" : " connected"));
                }
            }
        }
    }

    /**
     * Marks the leaves of the original netlist whose outputs reach an observed point (top-level
     * output or input of a cut cell present in both netlists) through simulated logic or
     * removed sequential cells.
     */
    private static boolean[] observedCone(Model a, Model b) {
        EDIFLeafIndex idx = a.idx;
        boolean[] seen = new boolean[idx.getNumLeaves()];
        List<Integer> stack = new ArrayList<>();
        for (int p = idx.getNumLeafPins(); p < idx.getNumPins(); p++) {
            if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER && idx.getPinNet(p) >= 0) {
                pushDriver(a, idx.getPinNet(p), seen, stack);
            }
        }
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            if (a.kind[l] == C_CUT && b.byName.containsKey(a.names[l])) {
                pushInputs(a, l, seen, stack);
            }
        }
        while (!stack.isEmpty()) {
            int l = stack.remove(stack.size() - 1);
            boolean keptCut = a.kind[l] == C_CUT && b.byName.containsKey(a.names[l]);
            if (!keptCut) {
                pushInputs(a, l, seen, stack);
            }
        }
        return seen;
    }

    private static void pushInputs(Model a, int l, boolean[] seen, List<Integer> stack) {
        for (int p = a.idx.getLeafPinStart(l); p < a.idx.getLeafPinEnd(l); p++) {
            if (a.idx.getPinDir(p) != EDIFLeafIndex.DRIVER && a.idx.getPinNet(p) >= 0) {
                pushDriver(a, a.idx.getPinNet(p), seen, stack);
            }
        }
    }

    private static void pushDriver(Model a, int n, boolean[] seen, List<Integer> stack) {
        int dp = a.driverPin(n);
        int d = dp < 0 ? -1 : a.idx.getPinLeaf(dp);
        if (d >= 0 && !seen[d]) {
            seen[d] = true;
            stack.add(d);
        }
    }

    private void compareRound(Model a, Model b, int round, Map<Integer, Long> forced,
                              List<Integer> removedFF) {
        // Top-level outputs
        Map<String, Long> bOut = new HashMap<>();
        for (int p = b.idx.getNumLeafPins(); p < b.idx.getNumPins(); p++) {
            if (b.idx.getPinDir(p) != EDIFLeafIndex.DRIVER && b.idx.getPinNet(p) >= 0) {
                bOut.put(b.idx.getPinPortInst(p).getName(), b.val[b.idx.getPinNet(p)]);
            }
        }
        for (int p = a.idx.getNumLeafPins(); p < a.idx.getNumPins(); p++) {
            if (a.idx.getPinDir(p) != EDIFLeafIndex.DRIVER && a.idx.getPinNet(p) >= 0) {
                String name = a.idx.getPinPortInst(p).getName();
                Long v = bOut.remove(name);
                if (a.tainted[a.idx.getPinNet(p)]) {
                    unchecked++;
                    continue;
                }
                checked++;
                if (v == null || v != a.val[a.idx.getPinNet(p)]) {
                    fail("round " + round + " port " + name);
                }
            }
        }
        // Outputs connected only in the optimized netlist
        for (String name : bOut.keySet()) {
            checked++;
            fail("round " + round + " port " + name + " connected only in the optimized netlist");
        }
        // Inputs of cut cells present in both netlists
        for (int la = 0; la < a.idx.getNumLeaves(); la++) {
            if (a.kind[la] != C_CUT) {
                continue;
            }
            Integer lb = b.byName.get(a.names[la]);
            if (lb == null || b.kind[lb] != C_CUT) {
                continue;
            }
            for (int p = a.idx.getLeafPinStart(la); p < a.idx.getLeafPinEnd(la); p++) {
                if (a.idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                    continue;
                }
                String pin = a.idx.getPinPortInst(p).getName();
                Long va = pinValue(a, la, pin);
                Long vb = pinValue(b, lb, pin);
                if (va == null || vb == null) {
                    continue;
                }
                if (a.loopCut[la] || b.loopCut[lb] || isTainted(a, la, pin)
                        || isTainted(b, lb, pin)) {
                    unchecked++;
                    continue;
                }
                checked++;
                if (!va.equals(vb)) {
                    fail("round " + round + " pin " + a.names[la] + "/" + pin + " diff=0x"
                            + Long.toHexString(va ^ vb));
                }
            }
        }
    }

    private int removedNonConstant;

    /** @return Removed sequential cells found not to be constant (removed as dead logic). */
    public int getRemovedNonConstant() {
        return removedNonConstant;
    }

    /** Checks that the next state of a removed sequential cell equals its assumed constant. */
    private static boolean isInductivelyConstant(Model a, int l, long q) {
        String type = a.idx.getLeafInst(l).getCellType().getName();
        if (type.startsWith("SRL")) {
            Long d = pinValue(a, l, "D");
            return d != null && d == q;
        }
        Long d = pinValue(a, l, "D");
        boolean latch = type.startsWith("LD");
        Long clk = pinValue(a, l, latch ? "G" : "C");
        Long ce;
        if (latch) {
            Long ge = pinValue(a, l, "GE");
            ce = (clk == null ? -1L : clk) & (ge == null ? -1L : ge);
        } else {
            ce = pinValue(a, l, "CE");
            if (clk != null && isConstNet(a, l, "C")) {
                // No clock edges: only the asynchronous inputs can change it
                ce = 0L;
            }
        }
        String rstPin = type.equals("FDRE") ? "R" : type.equals("FDSE") ? "S"
                : type.equals("FDCE") || type.equals("LDCE") ? "CLR" : "PRE";
        Long r = pinValue(a, l, rstPin);
        if (!latch && ce != null && ce == 0L && (type.equals("FDRE") || type.equals("FDSE"))
                && isConstNet(a, l, "C")) {
            r = 0L;
        }
        long rv = type.equals("FDRE") || type.equals("FDCE") || type.equals("LDCE") ? 0L : -1L;
        long dv = d == null ? q : d;
        long cev = ce == null ? -1L : ce;
        long rst = r == null ? 0L : r;
        long next = (rst & rv) | (~rst & ((cev & dv) | (~cev & q)));
        return next == q;
    }

    private static boolean isConstNet(Model m, int l, String pin) {
        for (int p = m.idx.getLeafPinStart(l); p < m.idx.getLeafPinEnd(l); p++) {
            if (m.idx.getPinPortInst(p).getName().equals(pin)) {
                int n = m.idx.getPinNet(p);
                int d = n < 0 ? -1 : m.driverLeaf(n);
                return n < 0 || (d >= 0 && (m.kind[d] == C_GND || m.kind[d] == C_VCC));
            }
        }
        return true;
    }

    public int getChecked() {
        return checked;
    }

    public List<String> getExamples() {
        return examples;
    }

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <original.edf> <optimized.edf> [rounds=4]");
            return;
        }
        int rounds = args.length > 2 ? Integer.parseInt(args[2]) : 4;
        long t0 = System.nanoTime();
        EDIFNetlist orig = EDIFTools.readEdifFile(Paths.get(args[0]));
        EDIFNetlist opt = EDIFTools.readEdifFile(Paths.get(args[1]));
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        int bad = eq.check(orig, opt, rounds);
        System.out.println("Observations checked: " + eq.getChecked() + " x 64 patterns");
        System.out.println("Observations depending on combinational loops (unchecked): "
                + eq.getUnchecked());
        System.out.println("Removed sequential cells not constant (dead logic): "
                + eq.getRemovedNonConstant());
        System.out.println("Mismatches: " + bad);
        for (String s : eq.getExamples()) {
            System.out.println("  " + s);
        }
        System.out.println(bad == 0 ? "EQUIVALENT" : "NOT EQUIVALENT");
        System.out.println(String.format("TIME=%.1fs", (System.nanoTime() - t0) / 1e9));
    }
}
