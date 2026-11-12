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
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
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
 * state is checked to equal it. Observations that depend on a combinational loop, or on a net
 * with several drivers some of which are simulated or constant (their resolution is not
 * modeled), are not checked: with no mismatch, the result is then inconclusive (see
 * {@link #getVerdict}).
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
        /**
         * Source pins (leaf outputs, bidirectional pins, top-level inputs) of each net n:
         * srcPins[srcStart[n]] to srcPins[srcStart[n + 1] - 1]. Usually one, several for e.g.
         * tri-state buffers (simulated as the XOR of their values).
         */
        final int[] srcStart;
        final int[] srcPins;
        /** Combinational cells turned into cut points because they are on a loop. */
        boolean[] loopCut;
        /**
         * Nets whose simulated value is not reliable: they depend, through simulated logic, on a
         * loop cut or on a multi-driven net not resolved by XOR (see {@link #isResolvedByXor}).
         * Observations of them are not checked.
         */
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
                if (inst.getProperty("INIT") != null
                        && (kind[l] == C_LUT || kind[l] == C_LUT62 || kind[l] == C_LUTCY)) {
                    // At the width of the INIT parameter: a LUT2's 2'hF is 4'h3
                    Long v = PrimitiveDefaults.getInitBits(inst);
                    if (v == null) {
                        // Not a number: not simulated, compared as a cut cell
                        kind[l] = C_CUT;
                    } else {
                        init[l] = v;
                    }
                }
                cutType[l] = kind[l] == C_CUT;
            }
            // Found once per net: looking them up per load is quadratic in the fanout
            int numNets = idx.getNumNets();
            srcStart = new int[numNets + 1];
            for (int p = 0; p < idx.getNumPins(); p++) {
                if (idx.getPinDir(p) != EDIFLeafIndex.SINK && idx.getPinNet(p) >= 0) {
                    srcStart[idx.getPinNet(p) + 1]++;
                }
            }
            for (int net = 0; net < numNets; net++) {
                srcStart[net + 1] += srcStart[net];
            }
            srcPins = new int[srcStart[numNets]];
            int[] fill = Arrays.copyOf(srcStart, numNets);
            for (int p = 0; p < idx.getNumPins(); p++) {
                if (idx.getPinDir(p) != EDIFLeafIndex.SINK && idx.getPinNet(p) >= 0) {
                    srcPins[fill[idx.getPinNet(p)]++] = p;
                }
            }
            driven = new boolean[numNets];
            for (int net = 0; net < numNets; net++) {
                driven[net] = srcStart[net + 1] > srcStart[net];
            }
            loopCut = new boolean[n];
            order = topoOrder();
            val = new long[idx.getNumNets()];
            computeTaint();
        }

        /** Computes {@link #tainted}. */
        private void computeTaint() {
            tainted = new boolean[idx.getNumNets()];
            for (int l = 0; l < idx.getNumLeaves(); l++) {
                if (loopCut[l]) {
                    taintOutputs(l);
                }
            }
            // Multi-driven nets whose drivers' XOR may cancel (not a resolution of them)
            for (int n = 0; n < idx.getNumNets(); n++) {
                if (!isResolvedByXor(n)) {
                    tainted[n] = true;
                }
            }
            // The GE output of LUTCY2/LUT6CY is not modeled (the unisim models leave it
            // undriven): it is not simulated
            for (int l = 0; l < idx.getNumLeaves(); l++) {
                if (kind[l] == C_LUTCY) {
                    int n = netOfPin(l, "GE");
                    if (n >= 0) {
                        tainted[n] = true;
                    }
                }
            }
            for (int node : order) {
                int l = node / 4;
                for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                    int n = idx.getPinNet(p);
                    if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER && n >= 0 && tainted[n]
                            && dependsOn(l, node % 4, idx.getPinPortInst(p).getName())) {
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
            if (pin.startsWith("GE")) {
                // GEA-GEH are not used by the LOOKAHEAD8 unisim model
                return false;
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
         * Orders evaluation nodes (one per combinational leaf, four per LOOKAHEAD8) so all the
         * drivers of a net come first; leaves in true loops become cuts.
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
                    int net = idx.getPinNet(p);
                    String pin = idx.getPinPortInst(p).getName();
                    // All the drivers of a multi-driven net contribute to its value
                    for (int i = srcStart[net]; i < srcStart[net + 1]; i++) {
                        int dp = srcPins[i];
                        int d = idx.getPinLeaf(dp);
                        if (d < 0 || !isComb(d)) {
                            continue;
                        }
                        int dn = nodeOf(d, idx.getPinPortInst(dp).getName());
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

        /** States of a net in {@link #fixedNets}. */
        static final byte NOT_FIXED = 0;
        static final byte FIXED_0 = 1;
        static final byte FIXED_1 = 2;
        static final byte FIXED_UNKNOWN = 3;

        /**
         * @return For each net, whether it never changes (and its value if known) when the removed
         * sequential cells in forced hold their values. Nets driven by static sources or forced
         * cells never change. So does the output of a LUT that, with its fixed inputs at their
         * values, does not depend on its other inputs (e.g. a clock gate whose enable is
         * constant 0), the output of an INV or BUF whose input never changes (with its value),
         * and the outputs of other simulated cells whose inputs all never change. Cells cut to
         * break loops are not included, nor are nets with several sources (see
         * {@link #isResolvedByXor}).
         */
        byte[] fixedNets(Map<Integer, Long> forced) {
            byte[] fixed = new byte[idx.getNumNets()];
            for (int l = 0; l < idx.getNumLeaves(); l++) {
                Long f = forced.get(l);
                if (kind[l] == C_GND || f != null && f == 0L) {
                    markOutputs(l, fixed, FIXED_0);
                } else if (kind[l] == C_VCC || f != null && f == -1L) {
                    markOutputs(l, fixed, FIXED_1);
                } else if (f != null) {
                    markOutputs(l, fixed, FIXED_UNKNOWN);
                }
            }
            for (int node : order) {
                int l = node / 4;
                if (kind[l] == C_LUT) {
                    byte out = fixedLut(l, fixed);
                    if (out != NOT_FIXED) {
                        markOutputs(l, fixed, out);
                    }
                    continue;
                }
                if (kind[l] == C_INV || kind[l] == C_BUF) {
                    // Known values pass through (e.g. VCC -> INV -> clock gate enable)
                    int n = netOfPin(l, "I");
                    byte in = n < 0 ? FIXED_0 : fixed[n];
                    if (in != NOT_FIXED) {
                        markOutputs(l, fixed, kind[l] == C_BUF || in == FIXED_UNKNOWN ? in
                                : in == FIXED_0 ? FIXED_1 : FIXED_0);
                    }
                    continue;
                }
                // Each node of a LOOKAHEAD8 marks its own output, once, from its own inputs
                int sub = node % 4;
                boolean all = true;
                for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l) && all; p++) {
                    int n = idx.getPinNet(p);
                    all = idx.getPinDir(p) == EDIFLeafIndex.DRIVER || n < 0
                            || !dependsOn(l, sub, idx.getPinPortInst(p).getName())
                            || fixed[n] != NOT_FIXED;
                }
                if (all) {
                    markOutputs(l, kind[l] == C_LOOKAHEAD ? sub : -1, fixed, FIXED_UNKNOWN);
                }
            }
            return fixed;
        }

        /** @return The number of sources of net n (see {@link #srcPins}). */
        int numSources(int n) {
            return srcStart[n + 1] - srcStart[n];
        }

        /**
         * @return True if the value of net n is modeled faithfully: it has at most one source,
         * or all its sources are top-level ports or cut cells (e.g. tri-state buffers), whose
         * values are independent pseudo-random values, so their XOR changes with any change in
         * the sources. With a simulated or constant source, sources can be correlated and their
         * XOR cancel (two buffers of a give 0, not a), so the net is not.
         */
        boolean isResolvedByXor(int n) {
            if (numSources(n) <= 1) {
                return true;
            }
            for (int i = srcStart[n]; i < srcStart[n + 1]; i++) {
                int d = idx.getPinLeaf(srcPins[i]);
                if (d >= 0 && kind[d] != C_CUT) {
                    return false;
                }
            }
            return true;
        }

        /** @return True if leaf l drives a net with several sources. */
        boolean drivesMultiSourceNet(int l) {
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                int n = idx.getPinNet(p);
                if (idx.getPinDir(p) != EDIFLeafIndex.SINK && n >= 0 && numSources(n) > 1) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @return The mask of the inputs of LUT l that its output depends on when the inputs on
         * nets fixed to a known value (unconnected ones are 0) hold that value.
         */
        int lutSupport(int l, byte[] fixed) {
            int k = idx.getLeafInst(l).getCellType().getName().charAt(3) - '0';
            int known = 0;
            int knownVal = 0;
            for (int i = 0; i < k; i++) {
                int n = netOfPin(l, "I" + i);
                byte f = n < 0 ? FIXED_0 : fixed[n];
                if (f == FIXED_0 || f == FIXED_1) {
                    known |= 1 << i;
                    knownVal |= f == FIXED_1 ? 1 << i : 0;
                }
            }
            int support = 0;
            for (int row = 0; row < (1 << k); row++) {
                if ((row & known) != knownVal) {
                    continue;
                }
                for (int i = 0; i < k; i++) {
                    int bit = 1 << i;
                    if ((known & bit) == 0 && (support & bit) == 0
                            && ((init[l] >>> row) & 1L) != ((init[l] >>> (row ^ bit)) & 1L)) {
                        support |= bit;
                    }
                }
            }
            return support;
        }

        /** @return The fixed state of the output of LUT l (NOT_FIXED if it may change). */
        private byte fixedLut(int l, byte[] fixed) {
            int k = idx.getLeafInst(l).getCellType().getName().charAt(3) - '0';
            int known = 0;
            int knownVal = 0;
            boolean allFixed = true;
            for (int i = 0; i < k; i++) {
                int n = netOfPin(l, "I" + i);
                byte f = n < 0 ? FIXED_0 : fixed[n];
                allFixed &= f != NOT_FIXED;
                if (f == FIXED_0 || f == FIXED_1) {
                    known |= 1 << i;
                    knownVal |= f == FIXED_1 ? 1 << i : 0;
                }
            }
            // Output over all values of the inputs not known
            int first = -1;
            for (int row = 0; row < (1 << k); row++) {
                if ((row & known) != knownVal) {
                    continue;
                }
                int bit = (int) ((init[l] >>> row) & 1L);
                if (first < 0) {
                    first = bit;
                } else if (bit != first) {
                    return allFixed ? FIXED_UNKNOWN : NOT_FIXED;
                }
            }
            return first == 1 ? FIXED_1 : FIXED_0;
        }

        private int netOfPin(int l, String pin) {
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                if (idx.getPinPortInst(p).getName().equals(pin)) {
                    return idx.getPinNet(p);
                }
            }
            return -1;
        }

        /**
         * Marks the nets driven by leaf l as fixed in the given state, except nets with several
         * sources, which are never fixed (see {@link #isResolvedByXor}).
         */
        private void markOutputs(int l, byte[] fixed, byte state) {
            markOutputs(l, -1, fixed, state);
        }

        /** As {@link #markOutputs(int, byte[], byte)}, only output sub of a LOOKAHEAD8 if >= 0. */
        private void markOutputs(int l, int sub, byte[] fixed, byte state) {
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                int n = idx.getPinNet(p);
                if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER || n < 0 || numSources(n) > 1
                        || sub >= 0 && nodeOf(l, idx.getPinPortInst(p).getName()) != l * 4 + sub) {
                    continue;
                }
                fixed[n] = state;
            }
        }

        /** @return The first source pin of a net (see {@link #srcPins}), -1 if none. */
        int driverPin(int net) {
            return srcStart[net + 1] > srcStart[net] ? srcPins[srcStart[net]] : -1;
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
                        val[n] ^= v;
                    }
                }
            }
        }

        /** @return True if a property of leaf l (or its primitive default) is true. */
        boolean attrTrue(int l, String key) {
            return VerilogLiterals.isTrue(PrimitiveDefaults.getValue(idx.getLeafInst(l), key));
        }

        /**
         * @return True if a one-bit parameter of leaf l (or its primitive default), e.g. a
         * flip-flop's INIT or an IS_*_INVERTED, is 1: only the least significant bit of a number
         * counts (2'b10 is 0).
         */
        boolean attrBit(int l, String key) {
            return VerilogLiterals.isBitSet(PrimitiveDefaults.getValue(idx.getLeafInst(l), key));
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

    /**
     * Assigns sources (ports, constants, cut outputs) and evaluates the logic. A net with several
     * drivers (e.g. tri-state buffers) gets the XOR of their values: not their resolution, but
     * any change in its set of drivers changes its value if they are independent (see
     * {@link Model#isResolvedByXor}; other multi-driven nets are tainted).
     */
    private static void simulate(Model m, int round, Map<Integer, Long> forced) {
        Arrays.fill(m.val, 0L);
        EDIFLeafIndex idx = m.idx;
        for (int p = idx.getNumLeafPins(); p < idx.getNumPins(); p++) {
            if (idx.getPinDir(p) != EDIFLeafIndex.SINK && idx.getPinNet(p) >= 0) {
                m.val[idx.getPinNet(p)] ^= rand("PORT:" + idx.getPinPortInst(p).getName(), round);
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
                m.val[n] ^= gnd ? 0L : vcc ? -1L : f != null ? f
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

    /**
     * @return Observations skipped because they depend on a combinational loop or on a
     * multi-driven net with simulated or constant drivers (see {@link #getVerdict}).
     */
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
                return m.attrBit(l, "IS_" + pin + "_INVERTED") ? ~v : v;
            }
        }
        return null;
    }

    /**
     * Checks equivalence of the original and optimized netlists.
     * @param orig The original netlist.
     * @param opt The optimized netlist.
     * @param rounds Number of 64-pattern simulation rounds (at least 1).
     * @return The number of mismatching observations. Zero proves nothing about the
     * observations not checked (see {@link #getVerdict}).
     */
    public int check(EDIFNetlist orig, EDIFNetlist opt, int rounds) {
        if (rounds < 1) {
            throw new IllegalArgumentException("At least one simulation round is needed: " + rounds);
        }
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
            if (!observed[l] || b.byName.containsKey(a.names[l]) || !EDIFLeafNetlistComparator.isPrimitive(cell)) {
                continue;
            }
            if (a.drivesMultiSourceNet(l)) {
                // A constant would be XORed with the other drivers: not assumed, so it keeps
                // pseudo-random values
                removedNonConstant++;
                continue;
            }
            if (type.matches("FD[RSCP]E|LD[CP]E")) {
                boolean init1 = a.attrBit(l, "INIT");
                forced.put(l, init1 ? -1L : 0L);
                removedFF.add(l);
            } else if (type.matches("SRL16E|SRLC32E")) {
                // A removed shift register can only be constant if all its initial bits are
                // equal (then D must equal them); otherwise it keeps pseudo-random values
                long all = type.equals("SRL16E") ? 0xFFFFL : 0xFFFFFFFFL;
                Long init = PrimitiveDefaults.getInitBits(a.idx.getLeafInst(l));
                if (init != null && (init == 0L || init == all)) {
                    forced.put(l, init == 0L ? 0L : -1L);
                    removedFF.add(l);
                } else {
                    removedNonConstant++;
                }
            }
        }
        // An asynchronous clear/preset that is always active determines the constant regardless
        // of INIT. It must never change (a sampled value alone is no proof): this is checked
        // again in every round below, as the nets that never change depend on the assumptions
        simulate(a, 0, forced);
        Set<Integer> asyncActive = new java.util.HashSet<>();
        byte[] fixed0 = a.fixedNets(forced);
        for (int l : removedFF) {
            if (isAsyncAlwaysActive(a, l, fixed0)) {
                String type = a.idx.getLeafInst(l).getCellType().getName();
                forced.put(l, type.equals("FDCE") || type.equals("LDCE") ? 0L : -1L);
                asyncActive.add(l);
            }
        }
        // Removed sequential cells that are not inductively constant were removed as dead
        // logic: they keep pseudo-random values (any dependence on them shows as a mismatch).
        // Dropping one can invalidate others (e.g. cells it clocks): repeat until none is dropped
        while (true) {
            Set<Integer> notConst = new java.util.HashSet<>();
            byte[] fixed = a.fixedNets(forced);
            for (int round = 0; round < rounds; round++) {
                simulate(a, round, forced);
                for (int l : removedFF) {
                    if (!isInductivelyConstant(a, l, forced.get(l), fixed)
                            || asyncActive.contains(l) && !isAsyncAlwaysActive(a, l, fixed)) {
                        if (DEBUG && notConst.add(l)) {
                            System.out.println("NOT_CONSTANT " + a.names[l] + " "
                                    + a.idx.getLeafInst(l).getCellType().getName() + " assumed="
                                    + Long.toHexString(forced.get(l)) + describePins(a, l, fixed));
                        }
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
            removedFF.removeIf(notConst::contains);
            removedNonConstant += notConst.size();
        }
        compareCutCells(a, b);
        for (int round = 0; round < rounds; round++) {
            simulate(a, round, forced);
            simulate(b, round, new HashMap<>());
            compareRound(a, b, round, forced, removedFF);
        }
        return mismatches;
    }

    /** @return The asynchronous clear/preset pin of a flip-flop or latch, null if none. */
    private static String asyncControl(String type) {
        switch (type) {
            case "FDCE":
            case "LDCE":
                return "CLR";
            case "FDPE":
            case "LDPE":
                return "PRE";
            default:
                return null;
        }
    }

    /**
     * @return True if the asynchronous clear/preset of leaf l is active in every pattern of the
     * last simulation and is on a net that never changes, hence always active.
     */
    private static boolean isAsyncAlwaysActive(Model m, int l, byte[] fixed) {
        String pin = asyncControl(m.idx.getLeafInst(l).getCellType().getName());
        if (pin == null) {
            return false;
        }
        for (int p = m.idx.getLeafPinStart(l); p < m.idx.getLeafPinEnd(l); p++) {
            if (m.idx.getPinPortInst(p).getName().equals(pin)) {
                int n = m.idx.getPinNet(p);
                Long v = pinValue(m, l, pin);
                return n >= 0 && fixed[n] != Model.NOT_FIXED && v != null && v == -1L;
            }
        }
        return false;
    }

    /** Prints the removed sequential cells found not to be constant (and why). */
    private static final boolean DEBUG = System.getProperty("rapidwright.equiv.debug") != null;

    private static String describePins(Model m, int l, byte[] fixed) {
        StringBuilder sb = new StringBuilder();
        for (int p = m.idx.getLeafPinStart(l); p < m.idx.getLeafPinEnd(l); p++) {
            int n = m.idx.getPinNet(p);
            if (m.idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                continue;
            }
            int dp = n < 0 ? -1 : m.driverPin(n);
            sb.append(' ').append(m.idx.getPinPortInst(p).getName()).append('=')
                    .append(n < 0 ? "-" : Long.toHexString(m.val[n]))
                    .append(n >= 0 && fixed[n] != Model.NOT_FIXED ? "(fixed)" : "")
                    .append('<').append(dp < 0 ? "none" : m.idx.getPinName(dp)).append('>');
        }
        return sb.toString();
    }

    /**
     * Properties a surviving cut cell may change: OPT_MODIFIED, and the inversion of a single-bit
     * input pin (IS_[pin]_INVERTED), which is part of the simulation. Inversion masks of bus pins
     * (e.g. a DSP's IS_ALUMODE_INVERTED) and of outputs are not simulated, so they are compared.
     */
    private static boolean isIgnoredProperty(EDIFCell cell, String key) {
        String k = key.toUpperCase();
        if (k.equals("OPT_MODIFIED")) {
            return true;
        }
        if (!k.startsWith("IS_") || !k.endsWith("_INVERTED")) {
            return false;
        }
        EDIFPort port = cell.getPort(key.substring(3, key.length() - 9));
        return port != null && !port.isBus() && port.getDirection() == EDIFDirection.INPUT;
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

    /** @return The declaration (type, default) of a property of a primitive cell, or null. */
    private VivadoProp declaredProperty(EDIFCell cell, String key) {
        if (series == null || !EDIFLeafNetlistComparator.isPrimitive(cell)) {
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
            if (!isIgnoredProperty(cell, e.getKey())) {
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
            String ta = EDIFLeafNetlistComparator.typeName(a.idx.getLeafInst(la).getCellType());
            String tb = EDIFLeafNetlistComparator.typeName(b.idx.getLeafInst(lb).getCellType());
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
     * removed sequential cells. The inputs of a LUT that its output does not depend on, with its
     * inputs fed by constants (GND/VCC, through simulated logic) at their values, are not
     * followed: e.g. a swept chain of flip-flops loading only a LUT made constant is not
     * observed, so is not assumed constant and checked (which can take one simulation pass per
     * flip-flop, as each one found not constant can only invalidate the next).
     */
    private static boolean[] observedCone(Model a, Model b) {
        EDIFLeafIndex idx = a.idx;
        // Constants that hold whatever is assumed about the removed sequential cells
        byte[] fixed = a.fixedNets(new HashMap<>());
        boolean[] seen = new boolean[idx.getNumLeaves()];
        List<Integer> stack = new ArrayList<>();
        for (int p = idx.getNumLeafPins(); p < idx.getNumPins(); p++) {
            if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER && idx.getPinNet(p) >= 0) {
                pushDrivers(a, idx.getPinNet(p), seen, stack);
            }
        }
        for (int l = 0; l < idx.getNumLeaves(); l++) {
            if (a.kind[l] == C_CUT && b.byName.containsKey(a.names[l])) {
                pushInputs(a, l, fixed, seen, stack);
            }
        }
        while (!stack.isEmpty()) {
            int l = stack.remove(stack.size() - 1);
            boolean keptCut = a.kind[l] == C_CUT && b.byName.containsKey(a.names[l]);
            if (!keptCut) {
                pushInputs(a, l, fixed, seen, stack);
            }
        }
        return seen;
    }

    private static void pushInputs(Model a, int l, byte[] fixed, boolean[] seen,
                                   List<Integer> stack) {
        int support = a.kind[l] == C_LUT ? a.lutSupport(l, fixed) : -1;
        for (int p = a.idx.getLeafPinStart(l); p < a.idx.getLeafPinEnd(l); p++) {
            if (a.idx.getPinDir(p) == EDIFLeafIndex.DRIVER || a.idx.getPinNet(p) < 0) {
                continue;
            }
            if (support != -1) {
                // LUT inputs are I0 to I5
                int i = a.idx.getPinPortInst(p).getName().charAt(1) - '0';
                if ((support & (1 << i)) == 0) {
                    continue;
                }
            }
            pushDrivers(a, a.idx.getPinNet(p), seen, stack);
        }
    }

    private static void pushDrivers(Model a, int n, boolean[] seen, List<Integer> stack) {
        for (int i = a.srcStart[n]; i < a.srcStart[n + 1]; i++) {
            int d = a.idx.getPinLeaf(a.srcPins[i]);
            if (d >= 0 && !seen[d]) {
                seen[d] = true;
                stack.add(d);
            }
        }
    }

    private void compareRound(Model a, Model b, int round, Map<Integer, Long> forced,
                              List<Integer> removedFF) {
        // Top-level outputs
        Map<String, Long> bOut = new HashMap<>();
        Set<String> bTainted = new java.util.HashSet<>();
        Set<String> bDriven = new java.util.HashSet<>();
        for (int p = b.idx.getNumLeafPins(); p < b.idx.getNumPins(); p++) {
            if (b.idx.getPinDir(p) != EDIFLeafIndex.DRIVER && b.idx.getPinNet(p) >= 0) {
                String name = b.idx.getPinPortInst(p).getName();
                bOut.put(name, b.val[b.idx.getPinNet(p)]);
                if (b.tainted[b.idx.getPinNet(p)]) {
                    bTainted.add(name);
                }
                if (b.driven[b.idx.getPinNet(p)]) {
                    bDriven.add(name);
                }
            }
        }
        for (int p = a.idx.getNumLeafPins(); p < a.idx.getNumPins(); p++) {
            if (a.idx.getPinDir(p) != EDIFLeafIndex.DRIVER && a.idx.getPinNet(p) >= 0) {
                String name = a.idx.getPinPortInst(p).getName();
                Long v = bOut.remove(name);
                if (a.tainted[a.idx.getPinNet(p)] || bTainted.contains(name)) {
                    unchecked++;
                    continue;
                }
                checked++;
                // An undriven output simulates as 0 but is not a constant 0
                if (v == null || v != a.val[a.idx.getPinNet(p)]
                        || a.driven[a.idx.getPinNet(p)] != bDriven.contains(name)) {
                    fail("round " + round + " port " + name);
                }
            }
        }
        // Outputs connected only in the optimized netlist
        for (String name : bOut.keySet()) {
            if (bTainted.contains(name)) {
                unchecked++;
                continue;
            }
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
    private static boolean isInductivelyConstant(Model a, int l, long q, byte[] fixed) {
        // Unreliable input values prove nothing
        for (int p = a.idx.getLeafPinStart(l); p < a.idx.getLeafPinEnd(l); p++) {
            int n = a.idx.getPinNet(p);
            if (a.idx.getPinDir(p) != EDIFLeafIndex.DRIVER && n >= 0 && a.tainted[n]) {
                return false;
            }
        }
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
            if (clk != null && isConstNet(a, l, "C", fixed)) {
                // No clock edges: only the asynchronous inputs can change it
                ce = 0L;
            }
        }
        String rstPin = type.equals("FDRE") ? "R" : type.equals("FDSE") ? "S"
                : type.equals("FDCE") || type.equals("LDCE") ? "CLR" : "PRE";
        Long r = pinValue(a, l, rstPin);
        if (!latch && ce != null && ce == 0L && (type.equals("FDRE") || type.equals("FDSE"))
                && isConstNet(a, l, "C", fixed)) {
            r = 0L;
        }
        long rv = type.equals("FDRE") || type.equals("FDCE") || type.equals("LDCE") ? 0L : -1L;
        long dv = d == null ? q : d;
        long cev = ce == null ? -1L : ce;
        long rst = r == null ? 0L : r;
        long next = (rst & rv) | (~rst & ((cev & dv) | (~cev & q)));
        return next == q;
    }

    /**
     * @return True if a pin of leaf l is unconnected or on a net that never changes (see
     * {@link Model#fixedNets}: the removed sequential cells are assumed constant together, and
     * the assumption is checked for all of them, so e.g. a stopped ripple counter is constant).
     */
    private static boolean isConstNet(Model m, int l, String pin, byte[] fixed) {
        for (int p = m.idx.getLeafPinStart(l); p < m.idx.getLeafPinEnd(l); p++) {
            if (m.idx.getPinPortInst(p).getName().equals(pin)) {
                int n = m.idx.getPinNet(p);
                return n < 0 || fixed[n] != Model.NOT_FIXED;
            }
        }
        return true;
    }

    public int getChecked() {
        return checked;
    }

    /**
     * @return The result of the last check: NOT EQUIVALENT if there were mismatches, else
     * INCONCLUSIVE if some observations could not be checked (see {@link #getUnchecked}), else
     * EQUIVALENT (no mismatch in the simulated patterns: not a proof; more rounds give more
     * confidence).
     */
    public String getVerdict() {
        return mismatches > 0 ? "NOT EQUIVALENT" : unchecked > 0 ? "INCONCLUSIVE" : "EQUIVALENT";
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
        System.out.println("Observations depending on combinational loops or unresolved"
                + " multi-driven nets (unchecked): " + eq.getUnchecked());
        System.out.println("Removed sequential cells not constant (dead logic): "
                + eq.getRemovedNonConstant());
        System.out.println("Mismatches: " + bad);
        for (String s : eq.getExamples()) {
            System.out.println("  " + s);
        }
        System.out.println(eq.getVerdict());
        System.out.println(String.format("TIME=%.1fs", (System.nanoTime() - t0) / 1e9));
    }
}
