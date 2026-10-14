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

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPropertyObject;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.optimizer.EDIFLeafIndex.IntList;

/**
 * Analysis half of the netlist optimizer: on a flat {@link EDIFLeafIndex} it computes, without
 * modifying the netlist, which leaf cells are removed or rewritten by constant propagation
 * (including LUT simplification and inverter absorption), sweep and post-processing. The resulting
 * decisions are applied to the hierarchical netlist by {@link EDIFOptEditor}.
 * <p>
 * The rules follow the observed behavior of Vivado's opt_design -propconst -sweep:
 * <ul>
 * <li>Constant propagation (to a fixpoint): a LUT's function is simplified by substituting
 * constant inputs, merging inputs on the same net and dropping inputs it does not depend on.
 * The result is a constant (cell removed, loads tied), a buffer (cell removed, loads connected to
 * its input) or a smaller LUT with the remaining pins in their original order. A LUT6_2 is only
 * rewritten (as a single LUT) when one of its outputs is unused, constant or a buffer. INV/BUF
 * behave like LUT1s. A flip-flop or latch whose only reachable value is its INIT (given CE, the
 * clock, its set/reset and IS_*_INVERTED) is removed as a constant; an asserted asynchronous
 * clear/preset makes it constant regardless of INIT. A shift register whose data input is a
 * constant equal to all its INIT bits is constant. Carry (LUT6CY/LOOKAHEAD8), memories, DSPs and
 * other cells are not simplified.</li>
 * <li>Inverter absorption: an inverter (LUT1 or a LUT reduced to one) is pushed into its LUT,
 * LUT6_2 and LUT6CY loads (INIT) and into pins with programmable inversion (IS_[pin]_INVERTED,
 * e.g. flip-flop C/R/S/CLR/PRE, BRAM enables, DSP resets), but never when it drives a top-level
 * or preserved-hierarchy port. It is removed when all loads absorb it.</li>
 * <li>Sweep: cells that do not reach a top-level output, a preserved (DONT_TOUCH/MARK_DEBUG)
 * cell, net or hierarchical instance, or a black box are removed (loops included). A LOOKAHEAD8
 * fed by a live LUT6CY is kept. Hierarchical instances left empty are removed.</li>
 * <li>Post-processing: a remaining inverter whose driver is a single-output LUT, all of whose
 * other loads are LUTs, is removed by inverting that LUT. Without constant propagation, it also
 * absorbs inverters (trying this pull-back first); without sweep, it removes combinational cells
 * that constant propagation left without loads.</li>
 * <li>DONT_TOUCH/MARK_DEBUG on a cell or net freezes it; on a hierarchical instance it makes its
 * ports an optimization boundary and protects its contents from sweep. KEEP and
 * KEEP_HIERARCHY are ignored.</li>
 * </ul>
 * Modified cells get the OPT_MODIFIED property (PROPCONST, SWEEP or POST_PROCESS_NETLIST).
 * <p>
 * Connectivity model: every pin is on exactly one net at a time, the net it was moved to by
 * inverter absorption or else its original net ({@link #pinMovedTo}). Nets merged by removing a
 * buffer or an absorbed inverter form an alias group represented by its root net
 * ({@link #resolve}). Connectivity is only queried through {@link #pinNet} and
 * {@link #forEachGroupPin}, and only changed through {@link #movePin}, {@link #dropPin},
 * {@link #restorePin}, {@link #setRemoved} and {@link #addAlias}, which keep the number of live
 * loads of each group up to date ({@link #hasLoads}).
 */
class EDIFConstPropEngine {

    static final byte UNK = 0;
    static final byte C0 = 1;
    static final byte C1 = 2;

    // Leaf kinds
    static final byte K_OTHER = 0;
    static final byte K_GND = 1;
    static final byte K_VCC = 2;
    static final byte K_LUT = 3;
    static final byte K_LUT62 = 4;
    static final byte K_INV = 5;
    static final byte K_BUF = 6;
    static final byte K_FD = 7;
    static final byte K_LD = 8;
    static final byte K_LUTCY = 9;
    static final byte K_SRL = 10;

    // Removal reasons
    static final byte ALIVE = 0;
    static final byte RM_CONST = 1;
    static final byte RM_ALIAS = 2;
    static final byte RM_INV = 3;
    static final byte RM_SWEEP = 4;
    static final byte RM_PULLBACK = 5;
    /** Inverter absorbed by its loads during post-processing (without constant propagation). */
    static final byte RM_INV_PP = 6;
    /** Combinational cell left without loads by constant propagation (post-processing). */
    static final byte RM_DANGLING_PP = 7;

    static final String MARK_PROPCONST = "PROPCONST";
    static final String MARK_POST_PROCESS = "POST_PROCESS_NETLIST";
    static final String MARK_SWEEP = "SWEEP";

    /** Pins that can absorb an inverter through an IS_[pin]_INVERTED property, per cell type. */
    static final Map<String, Set<String>> INVERTIBLE_PINS = new HashMap<>();

    private static void invertible(String type, String... pins) {
        INVERTIBLE_PINS.put(type, new HashSet<>(Arrays.asList(pins)));
    }

    static {
        invertible("FDRE", "C", "R");
        invertible("FDSE", "C", "S");
        invertible("FDCE", "C", "CLR");
        invertible("FDPE", "C", "PRE");
        invertible("RAMB36E5_INT", "ARST_A_L", "ARST_A_U", "ARST_B_L", "ARST_B_U", "CLKARDCLKL",
                "CLKARDCLKU", "CLKBWRCLKL", "CLKBWRCLKU", "ENARDENL", "ENARDENU", "ENBWRENL",
                "ENBWRENU", "RSTRAMARSTRAML", "RSTRAMARSTRAMU", "RSTRAMBL", "RSTRAMBU",
                "RSTREGARSTREGL", "RSTREGARSTREGU", "RSTREGBL", "RSTREGBU");
        invertible("RAMB18E5_INT", "ARST_A", "ARST_B", "CLKARDCLK", "CLKBWRCLK", "ENARDEN",
                "ENBWREN", "RSTRAMARSTRAM", "RSTRAMB", "RSTREGARSTREG", "RSTREGB");
        invertible("RAMB36E5", "ARST_A", "ARST_B", "CLKARDCLK", "CLKBWRCLK", "ENARDEN",
                "ENBWREN", "RSTRAMARSTRAM", "RSTRAMB", "RSTREGARSTREG", "RSTREGB");
        invertible("RAMB18E5", "ARST_A", "ARST_B", "CLKARDCLK", "CLKBWRCLK", "ENARDEN",
                "ENBWREN", "RSTRAMARSTRAM", "RSTRAMB", "RSTREGARSTREG", "RSTREGB");
        invertible("DSP58", "ASYNC_RST", "CARRYIN", "CLK", "NEGATE", "RSTALLCARRYIN",
                "RSTALUMODE", "RSTA", "RSTB", "RSTCTRL", "RSTC", "RSTD", "RSTINMODE", "RSTM",
                "RSTP");
        invertible("DSP58C", "ASYNC_RST", "CARRYIN", "CLK", "NEGATE", "RSTAD", "RSTALLCARRYIN",
                "RSTALUMODE", "RSTA", "RSTB", "RSTCTRL", "RSTC", "RSTD", "RSTINMODE", "RSTM",
                "RSTP");
        invertible("DSPFP32", "ASYNC_RST", "CLK", "RSTA", "RSTB", "RSTC", "RSTD", "RSTFPA",
                "RSTFPINMODE", "RSTFPMPIPE", "RSTFPM", "RSTFPOPMODE");
    }

    /** Current state of a LUT (or a LUT6_2 that is kept as a dual-output LUT). */
    static class Lut {
        /** Number of inputs. */
        int size;
        /** Number of address bits of the truth table (more than size for multi-output cells). */
        int bits;
        /** Truth table over the inputs (address bit i = input i). */
        long table;
        /** Pin (index into the leaf index) providing each input; its net is pinNet(srcPin[i]). */
        int[] srcPin;
        /** True if this is a LUT6_2 kept as is (both outputs used). */
        boolean dual;
        /** For a reduced LUT6_2: the original output pin that remains (O5 or O6). */
        int outPin = -1;
        /** True if differs from the original cell. */
        boolean changed;
    }

    final EDIFLeafIndex idx;
    final EDIFOptOptions opts;
    final int numLeaves;
    final int numNets;

    final byte[] kind;
    final boolean[] frozen;
    final byte[] removed;
    final byte[] netVal;
    final int[] alias;
    final boolean[] netOpaque;
    final boolean[] netDT;
    /** The single driver pin of each net, -1 if none, -2 if several. */
    final int[] netDriver;
    final boolean[] pinDropped;
    /** Target net of a pin moved by inverter absorption, -1 if not moved. */
    final int[] pinMovedTo;
    /** Pins moved onto each net (pinMovedTo[p] == n), allocated on the first move. */
    private IntList[] movedIn;
    /** Position of each moved pin in its movedIn list. */
    private int[] movedPos;
    /** Number of live loads (see isLiveSink) of each alias group, indexed by its root. */
    private final int[] loadCount;
    /** Number of pins (of any kind) of each alias group, indexed by its root. */
    private final int[] groupPins;
    final Lut[] lut;
    final long[] origInit;
    final IntList[] aliasChildren;
    /** Property changes (key -> value) per leaf. */
    final Map<Integer, Map<String, String>> props = new HashMap<>();
    /** OPT_MODIFIED marks per leaf. */
    final Map<Integer, String> marks = new HashMap<>();
    /**
     * Alias records (see bypass): the leaf bypassed, its output pin, and the alias group root its
     * output net merges into. The input pin is recorded only if its original net was already in
     * that group (-1 otherwise): the output net can then merge into that net directly.
     */
    final IntList aliasLeaf = new IntList();
    final IntList aliasInPin = new IntList();
    final IntList aliasOutPin = new IntList();
    final IntList aliasTarget = new IntList();
    /** Nets whose loads were all removed while their (non-static) driver survives. */
    final IntList orphanNets = new IntList();

    private final int[] queue;
    private int qHead;
    private int qTail;
    private final boolean[] queued;

    EDIFConstPropEngine(EDIFLeafIndex idx, EDIFOptOptions opts) {
        this.idx = idx;
        this.opts = opts;
        numLeaves = idx.getNumLeaves();
        numNets = idx.getNumNets();
        kind = new byte[numLeaves];
        frozen = new boolean[numLeaves];
        removed = new byte[numLeaves];
        netVal = new byte[numNets];
        alias = new int[numNets];
        Arrays.fill(alias, -1);
        netOpaque = new boolean[numNets];
        netDT = new boolean[numNets];
        netDriver = new int[numNets];
        pinDropped = new boolean[idx.getNumPins()];
        pinMovedTo = new int[idx.getNumPins()];
        Arrays.fill(pinMovedTo, -1);
        lut = new Lut[numLeaves];
        origInit = new long[numLeaves];
        aliasChildren = new IntList[numNets];
        loadCount = new int[numNets];
        groupPins = new int[numNets];
        queue = new int[numLeaves + 1];
        queued = new boolean[numLeaves];
        classify();
        for (int p = 0; p < idx.getNumPins(); p++) {
            int n = idx.getPinNet(p);
            if (n >= 0) {
                groupPins[n]++;
                if (isLiveSink(p)) {
                    loadCount[n]++;
                }
            }
        }
    }

    static boolean isTrue(EDIFPropertyValue v) {
        return v != null && isTrue(v.getValue());
    }

    static boolean isTrue(String value) {
        return VerilogLiterals.isTrue(value);
    }

    static boolean hasPreserveProp(EDIFPropertyObject o) {
        Map<String, EDIFPropertyValue> m = o.getPropertiesMap();
        if (m == null || m.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, EDIFPropertyValue> e : m.entrySet()) {
            String k = e.getKey();
            if ((k.equalsIgnoreCase("DONT_TOUCH") || k.equalsIgnoreCase("MARK_DEBUG"))
                    && isTrue(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static byte kindOf(EDIFCellInst inst) {
        EDIFCell cell = inst.getCellType();
        EDIFLibrary lib = cell.getLibrary();
        if (lib == null || !lib.isHDIPrimitivesLibrary()) {
            return K_OTHER;
        }
        String name = cell.getName();
        switch (name) {
            case "GND":
                return K_GND;
            case "VCC":
                return K_VCC;
            case "LUT1":
            case "LUT2":
            case "LUT3":
            case "LUT4":
            case "LUT5":
            case "LUT6":
                return K_LUT;
            case "LUT6_2":
                return K_LUT62;
            case "INV":
                return K_INV;
            case "BUF":
                return K_BUF;
            case "FDRE":
            case "FDSE":
            case "FDCE":
            case "FDPE":
                return K_FD;
            case "LDCE":
            case "LDPE":
                return K_LD;
            case "LUT6CY":
            case "LUTCY1":
            case "LUTCY2":
                return K_LUTCY;
            case "SRL16E":
            case "SRLC32E":
                return K_SRL;
            default:
                return K_OTHER;
        }
    }

    private void classify() {
        for (int s = 0; s < idx.getNumSegs(); s++) {
            EDIFNet net = idx.getSegEDIFNet(s);
            if (hasPreserveProp(net)) {
                netDT[idx.getSegNet(s)] = true;
            }
        }
        Arrays.fill(netDriver, -1);
        for (int p = 0; p < idx.getNumPins(); p++) {
            int n = idx.getPinNet(p);
            if (n < 0) {
                continue;
            }
            byte dir = idx.getPinDir(p);
            if (dir == EDIFLeafIndex.BIDIR) {
                netOpaque[n] = true;
            } else if (dir == EDIFLeafIndex.DRIVER) {
                netDriver[n] = netDriver[n] == -1 ? p : -2;
            }
        }
        for (int n = 0; n < numNets; n++) {
            if (netDriver[n] == -2 || netDT[n]) {
                netOpaque[n] = true;
            }
        }
        for (int l = 0; l < numLeaves; l++) {
            EDIFCellInst inst = idx.getLeafInst(l);
            kind[l] = idx.isLeafBlackBox(l) ? K_OTHER : kindOf(inst);
            boolean f = idx.isLeafBlackBox(l) || hasPreserveProp(inst);
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER && idx.getPinNet(p) >= 0
                        && netOpaque[idx.getPinNet(p)]) {
                    f = true;
                }
            }
            frozen[l] = f;
            if (kind[l] == K_LUT || kind[l] == K_LUT62 || kind[l] == K_LUTCY) {
                EDIFPropertyValue init = inst.getProperty("INIT");
                if (init == null) {
                    frozen[l] = true;
                } else {
                    origInit[l] = LUTTools.getInitValue(init.getValue());
                }
            }
            if (kind[l] == K_GND || kind[l] == K_VCC) {
                for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                    int n = idx.getPinNet(p);
                    if (n >= 0 && !netOpaque[n]) {
                        netVal[n] = kind[l] == K_GND ? C0 : C1;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- connectivity

    int resolve(int n) {
        int r = n;
        while (alias[r] >= 0) {
            r = alias[r];
        }
        while (alias[n] >= 0 && alias[n] != r) {
            int next = alias[n];
            alias[n] = r;
            n = next;
        }
        return r;
    }

    byte valueOf(int n) {
        return n < 0 ? UNK : netVal[resolve(n)];
    }

    /** @return The net pin p is on, before resolving aliases (-1 if unconnected). */
    private int homeNet(int p) {
        int t = pinMovedTo[p];
        return t >= 0 ? t : idx.getPinNet(p);
    }

    /** @return The current net (alias group root) of pin p, -1 if unconnected. */
    int pinNet(int p) {
        int n = homeNet(p);
        return n < 0 ? -1 : resolve(n);
    }

    /** @return True if pin p currently loads its net (leaf alive and pin not dropped). */
    boolean isLiveSink(int p) {
        if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER || pinDropped[p]) {
            return false;
        }
        int l = idx.getPinLeaf(p);
        return l < 0 || removed[l] == ALIVE;
    }

    /** @return True if the alias group of net n has a live load. */
    boolean hasLoads(int n) {
        return n >= 0 && loadCount[resolve(n)] > 0;
    }

    private void countLoad(int p, int delta) {
        int n = pinNet(p);
        if (n >= 0 && isLiveSink(p)) {
            loadCount[n] += delta;
        }
    }

    /** Merges the alias group of root 'from' into the one of root 'to'. */
    private void addAlias(int from, int to) {
        alias[from] = to;
        if (aliasChildren[to] == null) {
            aliasChildren[to] = new IntList();
        }
        aliasChildren[to].add(from);
        loadCount[to] += loadCount[from];
        loadCount[from] = 0;
        groupPins[to] += groupPins[from];
        groupPins[from] = 0;
    }

    /** Moves pin p onto net target. */
    private void movePin(int p, int target) {
        countLoad(p, -1);
        if (homeNet(p) >= 0) {
            groupPins[pinNet(p)]--;
        }
        if (movedIn == null) {
            movedIn = new IntList[numNets];
            movedPos = new int[idx.getNumPins()];
        }
        int old = pinMovedTo[p];
        if (old >= 0) {
            // Constant-time removal: the last pin of the list takes its place
            IntList list = movedIn[old];
            int last = list.pop();
            if (last != p) {
                list.set(movedPos[p], last);
                movedPos[last] = movedPos[p];
            }
        }
        if (target == idx.getPinNet(p)) {
            pinMovedTo[p] = -1;
        } else {
            pinMovedTo[p] = target;
            if (movedIn[target] == null) {
                movedIn[target] = new IntList();
            }
            movedPos[p] = movedIn[target].size();
            movedIn[target].add(p);
        }
        groupPins[pinNet(p)]++;
        countLoad(p, 1);
    }

    /** Disconnects input pin p of a surviving leaf (an input its function no longer uses). */
    private void dropPin(int p) {
        countLoad(p, -1);
        pinDropped[p] = true;
    }

    /** Reconnects a dropped input pin that a LUT's simplified function uses again. */
    private void restorePin(int p) {
        pinDropped[p] = false;
        countLoad(p, 1);
    }

    private void setRemoved(int l, byte reason) {
        if (removed[l] == ALIVE) {
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                countLoad(p, -1);
            }
        }
        removed[l] = reason;
    }

    /** Visits pins; returns false to stop the visit. */
    interface PinVisitor {
        boolean visit(int pin);
    }

    /**
     * Calls the visitor for every pin currently on a net of the alias group rooted at root
     * (pins originally there and not moved away, and pins moved there).
     * @return False if the visitor stopped the visit.
     */
    boolean forEachGroupPin(int root, PinVisitor v) {
        IntList stack = new IntList();
        stack.add(root);
        while (stack.size() > 0) {
            int n = stack.pop();
            for (int i = idx.getNetPinStart(n); i < idx.getNetPinEnd(n); i++) {
                int p = idx.getNetPin(i);
                if (pinMovedTo[p] < 0 && !v.visit(p)) {
                    return false;
                }
            }
            IntList moved = movedIn == null ? null : movedIn[n];
            if (moved != null) {
                for (int i = 0; i < moved.size(); i++) {
                    if (!v.visit(moved.get(i))) {
                        return false;
                    }
                }
            }
            if (aliasChildren[n] != null) {
                for (int i = 0; i < aliasChildren[n].size(); i++) {
                    stack.add(aliasChildren[n].get(i));
                }
            }
        }
        return true;
    }

    /** Collects the live load pins of the alias group of root. */
    IntList liveLoads(int root) {
        IntList loads = new IntList();
        forEachGroupPin(root, p -> {
            if (isLiveSink(p)) {
                loads.add(p);
            }
            return true;
        });
        return loads;
    }

    /**
     * Checks the connectivity invariants: each connected pin is in exactly one alias group, on
     * its current net, and the live load counts match.
     * @return True (throws if an invariant is violated).
     */
    boolean checkConsistency() {
        int[] seen = new int[idx.getNumPins()];
        for (int n = 0; n < numNets; n++) {
            if (alias[n] >= 0) {
                if (loadCount[n] != 0 || groupPins[n] != 0) {
                    throw new IllegalStateException("Load count on aliased net " + n);
                }
                continue;
            }
            int root = n;
            int[] loads = new int[2];
            forEachGroupPin(n, p -> {
                seen[p]++;
                loads[1]++;
                if (pinNet(p) != root) {
                    throw new IllegalStateException("Pin " + idx.getPinName(p) + " visited in "
                            + "the group of net " + root + " but is on net " + pinNet(p));
                }
                if (isLiveSink(p)) {
                    loads[0]++;
                }
                return true;
            });
            if (loads[0] != loadCount[n] || loads[1] != groupPins[n]) {
                throw new IllegalStateException("Net " + n + " has " + loads[0]
                        + " live loads and " + loads[1] + " pins, counted " + loadCount[n]
                        + " and " + groupPins[n]);
            }
        }
        for (int p = 0; p < idx.getNumPins(); p++) {
            int t = pinMovedTo[p];
            if (t >= 0 && movedIn[t].get(movedPos[p]) != p) {
                throw new IllegalStateException("Pin " + idx.getPinName(p) + " misplaced in the "
                        + "pins moved onto net " + t);
            }
            if (homeNet(p) >= 0 && seen[p] != 1) {
                throw new IllegalStateException("Pin " + idx.getPinName(p) + " visited "
                        + seen[p] + " times");
            }
        }
        return true;
    }

    private void enqueue(int l) {
        if (l >= 0 && !queued[l] && removed[l] == ALIVE && !frozen[l]) {
            queued[l] = true;
            queue[qTail] = l;
            qTail = qTail == queue.length - 1 ? 0 : qTail + 1;
        }
    }

    private void enqueueSinks(int root) {
        forEachGroupPin(root, p -> {
            if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER) {
                enqueue(idx.getPinLeaf(p));
            }
            return true;
        });
    }

    /**
     * Bypasses leaf l from input pin inPin to output pin outPin: the output's net joins the alias
     * group of target, the net inPin is on now. The target is recorded with the bypass, as inPin
     * may be moved later (e.g. when l survives through another output).
     * @param reevaluate If true (constant propagation), the loads that may now have duplicate
     * inputs (those with pins in both groups) are re-evaluated.
     */
    private void bypass(int l, int inPin, int outPin, int target, boolean reevaluate) {
        int from = resolve(idx.getPinNet(outPin));
        int to = resolve(target);
        int inNet = idx.getPinNet(inPin);
        aliasLeaf.add(l);
        aliasInPin.add(inNet >= 0 && resolve(inNet) == to ? inPin : -1);
        aliasOutPin.add(outPin);
        aliasTarget.add(to);
        if (reevaluate) {
            // A load with pins in both groups has one in the smaller group
            if (groupPins[from] <= groupPins[to]) {
                addAlias(from, to);
                enqueueSinks(from);
                return;
            }
            enqueueSinks(to);
        }
        addAlias(from, to);
    }

    private void setConst(int n, byte v) {
        if (n < 0) {
            return;
        }
        int r = resolve(n);
        if (netOpaque[r] || netVal[r] != UNK) {
            return;
        }
        netVal[r] = v;
        enqueueSinks(r);
    }

    private void removeLeaf(int l, byte reason) {
        setRemoved(l, reason);
        // A LUT6_2 driving one of the inputs may now have an unused output
        for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
            if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER) {
                int n = pinNet(p);
                if (n >= 0 && netDriver[n] >= 0) {
                    int d = idx.getPinLeaf(netDriver[n]);
                    if (d >= 0 && kind[d] == K_LUT62) {
                        enqueue(d);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- pin lookup

    int findPin(int l, String name) {
        for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
            if (idx.getPinPortInst(p).getName().equals(name)) {
                return p;
            }
        }
        return -1;
    }

    private boolean inverted(int l, String pin) {
        EDIFCellInst inst = idx.getLeafInst(l);
        String key = "IS_" + pin + "_INVERTED";
        Map<String, String> pm = props.get(l);
        if (pm != null && pm.containsKey(key)) {
            return isTrue(pm.get(key));
        }
        return isTrue(inst.getProperty(key));
    }

    /** Effective (inversion applied) constant value of a pin: UNK, C0 or C1. */
    private byte effVal(int l, String pin) {
        int p = findPin(l, pin);
        if (p < 0) {
            return UNK;
        }
        byte v = valueOf(pinNet(p));
        if (v != UNK && inverted(l, pin)) {
            v = v == C0 ? C1 : C0;
        }
        return v;
    }

    // ---------------------------------------------------------------- evaluation

    private static final int SET0 = 1;
    private static final int SET1 = 2;

    private void evalFD(int l) {
        String type = idx.getLeafInst(l).getCellType().getName();
        boolean isLatch = kind[l] == K_LD;
        String rstPin;
        int rstVal;
        boolean async;
        switch (type) {
            case "FDRE":
                rstPin = "R";
                rstVal = SET0;
                async = false;
                break;
            case "FDSE":
                rstPin = "S";
                rstVal = SET1;
                async = false;
                break;
            case "FDCE":
            case "LDCE":
                rstPin = "CLR";
                rstVal = SET0;
                async = true;
                break;
            default:
                rstPin = "PRE";
                rstVal = SET1;
                async = true;
                break;
        }
        EDIFPropertyValue initProp = idx.getLeafInst(l).getProperty("INIT");
        int init;
        if (initProp == null) {
            init = rstVal;
        } else {
            init = isTrue(initProp) ? SET1 : SET0;
        }
        byte rst = effVal(l, rstPin);
        int q = findPin(l, "Q");
        if (q < 0) {
            return;
        }
        int reach;
        if (async && rst == C1) {
            reach = rstVal;
        } else {
            reach = init;
            if (rst != C0) {
                reach |= rstVal;
            }
            boolean loads;
            if (isLatch) {
                byte g = effVal(l, "G");
                byte ge = effVal(l, "GE");
                loads = g != C0 && ge != C0;
            } else {
                byte c = valueOf(pinNet0(l, "C"));
                byte ce = effVal(l, "CE");
                loads = c == UNK && ce != C0 && (async || rst != C1);
            }
            if (loads) {
                byte d = effVal(l, "D");
                reach |= d == C0 ? SET0 : d == C1 ? SET1 : (SET0 | SET1);
            }
        }
        if (reach == SET0 || reach == SET1) {
            removeLeaf(l, RM_CONST);
            setConst(idx.getPinNet(q), reach == SET0 ? C0 : C1);
        }
    }

    private int pinNet0(int l, String pin) {
        int p = findPin(l, pin);
        return p < 0 ? -1 : pinNet(p);
    }

    /** Result of simplifying a single-output function. */
    static class Fn {
        /** UNK if not constant, else C0/C1. */
        byte constVal;
        /** Input nets after simplification. */
        int[] net;
        int[] srcPin;
        int size;
        long table;
        boolean frozen;

        boolean isBuffer() {
            return constVal == UNK && size == 1 && table == 2L;
        }

        boolean isInverter() {
            return constVal == UNK && size == 1 && table == 1L;
        }
    }

    /**
     * Simplifies a function given by an INIT table over pins (constants substituted, duplicate
     * nets merged, unused inputs removed).
     */
    Fn simplify(long init, int[] pins) {
        Fn f = new Fn();
        int k = pins.length;
        int[] var = new int[k];
        int[] varNet = new int[k];
        int[] varPin = new int[k];
        int nv = 0;
        int constMask = 0;
        int constBits = 0;
        for (int i = 0; i < k; i++) {
            if (pins[i] < 0) {
                f.frozen = true;
                return f;
            }
            int n = pinNet(pins[i]);
            if (n < 0) {
                f.frozen = true;
                return f;
            }
            byte v = netVal[n];
            if (v != UNK) {
                constMask |= 1 << i;
                if (v == C1) {
                    constBits |= 1 << i;
                }
                var[i] = -1;
                continue;
            }
            int j = 0;
            while (j < nv && varNet[j] != n) {
                j++;
            }
            if (j == nv) {
                varNet[nv] = n;
                varPin[nv] = pins[i];
                nv++;
            }
            var[i] = j;
        }
        long table = 0;
        for (int row = 0; row < (1 << nv); row++) {
            int addr = constBits;
            for (int i = 0; i < k; i++) {
                if (var[i] >= 0 && ((row >> var[i]) & 1) != 0) {
                    addr |= 1 << i;
                }
            }
            if (((init >>> addr) & 1L) != 0) {
                table |= 1L << row;
            }
        }
        // Remove inputs the function does not depend on
        int size = nv;
        for (int j = 0; j < size; ) {
            if (cofactor(table, size, j, 0) == cofactor(table, size, j, 1)) {
                table = cofactor(table, size, j, 0);
                for (int m = j; m < size - 1; m++) {
                    varNet[m] = varNet[m + 1];
                    varPin[m] = varPin[m + 1];
                }
                size--;
            } else {
                j++;
            }
        }
        if (size == 0) {
            f.constVal = (table & 1L) != 0 ? C1 : C0;
            return f;
        }
        f.size = size;
        f.table = table;
        f.net = Arrays.copyOf(varNet, size);
        f.srcPin = Arrays.copyOf(varPin, size);
        return f;
    }

    /** Removes variable j from a truth table over size variables by fixing it to value v. */
    static long cofactor(long table, int size, int j, int v) {
        long r = 0;
        int out = 0;
        for (int row = 0; row < (1 << size); row++) {
            if (((row >> j) & 1) != v) {
                continue;
            }
            int newRow = (row & ((1 << j) - 1)) | ((row >> (j + 1)) << j);
            if (((table >>> row) & 1L) != 0) {
                r |= 1L << newRow;
            }
            out++;
        }
        return r;
    }

    static long mask(int size) {
        return size >= 6 ? -1L : (1L << (1 << size)) - 1;
    }

    private int[] lutPins(int l, int k) {
        int[] pins = new int[k];
        for (int i = 0; i < k; i++) {
            pins[i] = findPin(l, "I" + i);
        }
        return pins;
    }

    private static int lutSizeOf(EDIFCellInst inst) {
        return inst.getCellType().getName().charAt(3) - '0';
    }

    private void evalLut(int l) {
        int k = lutSizeOf(idx.getLeafInst(l));
        int[] pins = lutPins(l, k);
        Fn f = simplify(origInit[l], pins);
        if (f.frozen) {
            return;
        }
        int out = findPin(l, "O");
        applyFn(l, f, out, -1);
    }

    /** Applies a simplified function to a (single output) LUT. */
    private void applyFn(int l, Fn f, int outPin, int origOutPin) {
        int outNet = outPin < 0 ? -1 : idx.getPinNet(outPin);
        if (f.constVal != UNK) {
            removeLeaf(l, RM_CONST);
            setConst(outNet, f.constVal);
            return;
        }
        if (f.isBuffer() && outNet >= 0 && resolve(outNet) != f.net[0]) {
            removeLeaf(l, RM_ALIAS);
            bypass(l, f.srcPin[0], outPin, f.net[0], true);
            return;
        }
        Lut s = new Lut();
        s.size = f.size;
        s.bits = f.size;
        s.table = f.table;
        s.srcPin = f.srcPin;
        s.outPin = origOutPin;
        s.changed = !sameAsOriginal(l, s);
        lut[l] = s;
        if (s.changed) {
            mark(l, MARK_PROPCONST);
        }
        updateDropped(l, s);
    }

    private boolean sameAsOriginal(int l, Lut s) {
        if (kind[l] != K_LUT) {
            return false;
        }
        int k = lutSizeOf(idx.getLeafInst(l));
        if (s.size != k || (s.table & mask(k)) != (origInit[l] & mask(k))) {
            return false;
        }
        for (int i = 0; i < k; i++) {
            if (s.srcPin[i] != findPin(l, "I" + i)) {
                return false;
            }
        }
        return true;
    }

    private void updateDropped(int l, Lut s) {
        for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
            if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                continue;
            }
            boolean used = false;
            for (int i = 0; i < s.size; i++) {
                if (s.srcPin[i] == p) {
                    used = true;
                }
            }
            if (used && pinDropped[p]) {
                // Merged inputs can make a pin dropped earlier represent a variable again
                restorePin(p);
            } else if (!used && !pinDropped[p]) {
                dropPin(p);
                // The driver of this pin may be a LUT6_2 that lost a load
                int n = pinNet(p);
                if (n >= 0 && netDriver[n] >= 0) {
                    int d = idx.getPinLeaf(netDriver[n]);
                    if (d >= 0 && kind[d] == K_LUT62) {
                        enqueue(d);
                    }
                }
            }
        }
    }

    private void evalLut62(int l) {
        int[] pins = lutPins(l, 6);
        int o5 = findPin(l, "O5");
        int o6 = findPin(l, "O6");
        long init = origInit[l];
        Fn f6 = simplify(init, pins);
        Fn f5 = simplify(init & 0xFFFFFFFFL, Arrays.copyOf(pins, 5));
        if (f6.frozen || f5.frozen) {
            return;
        }
        int n5 = o5 < 0 ? -1 : idx.getPinNet(o5);
        int n6 = o6 < 0 ? -1 : idx.getPinNet(o6);
        boolean used5 = n5 >= 0 && hasLoads(resolve(n5));
        boolean used6 = n6 >= 0 && hasLoads(resolve(n6));
        boolean elim5 = !used5 || f5.constVal != UNK || f5.isBuffer();
        boolean elim6 = !used6 || f6.constVal != UNK || f6.isBuffer();
        if (!elim5 && !elim6) {
            return;
        }
        if (elim5 && elim6) {
            if (!used5 && !used6) {
                // Entirely unused: left to sweep
                return;
            }
            removeLeaf(l, RM_CONST);
            if (used5) {
                eliminateOutput(l, f5, o5);
            }
            if (used6) {
                eliminateOutput(l, f6, o6);
            }
            return;
        }
        // One output remains: becomes a single-output LUT
        if (elim5 && used5) {
            eliminateOutput(l, f5, o5);
        }
        if (elim6 && used6) {
            eliminateOutput(l, f6, o6);
        }
        Fn keep = elim5 ? f6 : f5;
        int keepPin = elim5 ? o6 : o5;
        applyFn(l, keep, keepPin, keepPin);
        if (lut[l] != null) {
            lut[l].changed = true;
            mark(l, MARK_PROPCONST);
        }
    }

    /** Handles a constant or buffer output of a LUT6_2 whose remaining output is kept. */
    private void eliminateOutput(int l, Fn f, int outPin) {
        int outNet = idx.getPinNet(outPin);
        if (f.constVal != UNK) {
            setConst(outNet, f.constVal);
        } else if (f.isBuffer() && resolve(outNet) != f.net[0]) {
            bypass(l, f.srcPin[0], outPin, f.net[0], true);
        }
    }

    private void evalInvBuf(int l) {
        int in = findPin(l, "I");
        int out = findPin(l, "O");
        if (in < 0 || out < 0) {
            return;
        }
        int n = pinNet(in);
        if (n < 0) {
            return;
        }
        byte v = netVal[n];
        int outNet = idx.getPinNet(out);
        if (v != UNK) {
            removeLeaf(l, RM_CONST);
            setConst(outNet, kind[l] == K_INV ? (v == C0 ? C1 : C0) : v);
        } else if (kind[l] == K_BUF && outNet >= 0 && resolve(outNet) != n) {
            removeLeaf(l, RM_ALIAS);
            bypass(l, in, out, n, true);
        }
    }

    /** A shift register whose data input is constant and equal to all its INIT bits. */
    private void evalSRL(int l) {
        byte d = effVal(l, "D");
        if (d == UNK) {
            return;
        }
        EDIFPropertyValue initProp = idx.getLeafInst(l).getProperty("INIT");
        long init = initProp == null ? 0L : LUTTools.getInitValue(initProp.getValue());
        int bits = idx.getLeafInst(l).getCellType().getName().equals("SRL16E") ? 16 : 32;
        long all = (1L << bits) - 1;
        if ((d == C0 && (init & all) != 0) || (d == C1 && (init & all) != all)) {
            return;
        }
        removeLeaf(l, RM_CONST);
        for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
            if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                setConst(idx.getPinNet(p), d);
            }
        }
    }

    private void evaluate(int l) {
        switch (kind[l]) {
            case K_SRL:
                evalSRL(l);
                break;
            case K_LUT:
                evalLut(l);
                break;
            case K_LUT62:
                evalLut62(l);
                break;
            case K_FD:
            case K_LD:
                evalFD(l);
                break;
            case K_INV:
            case K_BUF:
                evalInvBuf(l);
                break;
            default:
                break;
        }
    }

    /** Runs constant propagation and LUT simplification to a fixpoint. */
    void propagate() {
        for (int l = 0; l < numLeaves; l++) {
            if (kind[l] != K_OTHER && kind[l] != K_GND && kind[l] != K_VCC) {
                enqueue(l);
            }
        }
        while (qHead != qTail) {
            int l = queue[qHead];
            qHead = qHead == queue.length - 1 ? 0 : qHead + 1;
            queued[l] = false;
            if (removed[l] == ALIVE && !frozen[l]) {
                evaluate(l);
            }
        }
    }

    // ---------------------------------------------------------------- inverter handling

    /** @return The input net of leaf l if it currently is an inverter, else -1. */
    int inverterInput(int l) {
        if (removed[l] != ALIVE || frozen[l]) {
            return -1;
        }
        if (kind[l] == K_INV) {
            int in = findPin(l, "I");
            int n = in < 0 ? -1 : pinNet(in);
            return n >= 0 && netVal[n] == UNK ? n : -1;
        }
        if (kind[l] == K_LUT || kind[l] == K_LUT62) {
            Lut s = getLut(l);
            if (s != null && !s.dual && s.size == 1 && s.table == 1L) {
                return pinNet(s.srcPin[0]);
            }
        }
        return -1;
    }

    /** @return The output pin of a single-output LUT/INV leaf. */
    int outputPin(int l) {
        if (kind[l] == K_LUT62) {
            Lut s = lut[l];
            return s == null ? -1 : s.outPin;
        }
        return findPin(l, "O");
    }

    /** Gets the current LUT state, creating it from the original cell if unchanged. */
    Lut getLut(int l) {
        if (lut[l] != null) {
            return lut[l];
        }
        if (frozen[l]) {
            return null;
        }
        int inputs;
        int bits;
        boolean dual;
        switch (kind[l]) {
            case K_LUT62:
                inputs = 6;
                bits = 6;
                dual = true;
                break;
            case K_LUT:
                inputs = lutSizeOf(idx.getLeafInst(l));
                bits = inputs;
                dual = false;
                break;
            case K_LUTCY:
                // O51/O52 (or O) and PROP share the inputs I0-I4
                inputs = 5;
                bits = idx.getLeafInst(l).getCellType().getName().equals("LUT6CY") ? 6 : 5;
                dual = true;
                break;
            default:
                return null;
        }
        int[] pins = lutPins(l, inputs);
        for (int p : pins) {
            if (p < 0 || pinNet(p) < 0) {
                return null;
            }
        }
        Lut s = new Lut();
        s.size = inputs;
        s.bits = bits;
        s.table = origInit[l] & mask(bits);
        s.dual = dual;
        s.srcPin = pins;
        lut[l] = s;
        return s;
    }

    /** Flips input i of a truth table over size inputs. */
    static long flipInput(long table, int size, int i) {
        long r = 0;
        for (int row = 0; row < (1 << size); row++) {
            if (((table >>> row) & 1L) != 0) {
                r |= 1L << (row ^ (1 << i));
            }
        }
        return r;
    }

    private void mark(int l, String m) {
        if (l < 0 || !opts.isMarkModified()) {
            return;
        }
        String cur = marks.get(l);
        if (cur == null) {
            marks.put(l, m);
        } else if (!(" " + cur + " ").contains(" " + m + " ")) {
            marks.put(l, cur + " " + m);
        }
    }

    private void toggleInverted(int l, String pin) {
        String key = "IS_" + pin + "_INVERTED";
        boolean cur = inverted(l, pin);
        EDIFPropertyValue orig = idx.getLeafInst(l).getProperty(key);
        String v = cur ? "1'b0" : "1'b1";
        if (orig == null && !cur) {
            v = "1'b1";
        }
        Map<String, String> m = props.computeIfAbsent(l, k -> new HashMap<>());
        if (orig == null && cur) {
            // Back to the (absent) default
            m.remove(key);
            if (m.isEmpty()) {
                props.remove(l);
            }
        } else {
            m.put(key, v);
        }
    }

    /** @return True if load pin p can absorb an inverter. */
    boolean canAbsorb(int p) {
        int l = idx.getPinLeaf(p);
        if (l < 0 || frozen[l] || removed[l] != ALIVE) {
            return false;
        }
        if (kind[l] == K_LUT || kind[l] == K_LUT62) {
            Lut s = getLut(l);
            if (s == null) {
                return false;
            }
            // Inverting I5 of a dual-output LUT6_2 would also change O5
            return !(s.dual && idx.getPinPortInst(p).getName().equals("I5"));
        }
        if (kind[l] == K_LUTCY) {
            // The PROP/GE outputs do not depend on I4
            String pin = idx.getPinPortInst(p).getName();
            return getLut(l) != null && !pin.equals("I4") && pin.startsWith("I");
        }
        Set<String> pins = INVERTIBLE_PINS.get(idx.getLeafInst(l).getCellType().getName());
        return pins != null && pins.contains(idx.getPinPortInst(p).getName());
    }

    /** Inverts the input of load pin p, whose net is (or will be) replaced by newNet. */
    private void absorb(int p, int oldNet, int newNet, String m, boolean move) {
        int l = idx.getPinLeaf(p);
        if (kind[l] == K_LUT || kind[l] == K_LUT62 || kind[l] == K_LUTCY) {
            Lut s = getLut(l);
            for (int i = 0; i < s.size; i++) {
                if (s.srcPin[i] == p && pinNet(p) == oldNet) {
                    s.table = flipInput(s.table, s.bits, i);
                    if (move) {
                        movePin(p, newNet);
                    }
                    s.changed = true;
                }
            }
            mark(l, m);
        } else {
            toggleInverted(l, idx.getPinPortInst(p).getName());
            if (move) {
                movePin(p, newNet);
            }
            mark(l, m);
        }
    }

    /** Absorbs inverters into the LUTs and invertible pins they drive (constant propagation). */
    void pushInverters() {
        pushInverters(MARK_PROPCONST, RM_INV, false);
    }

    /**
     * Absorbs inverters into the LUTs and invertible pins they drive.
     * @param pushMark OPT_MODIFIED mark of modified cells.
     * @param reason Removal reason of absorbed inverters.
     * @param pullBackFirst If true, an inverter that cannot be fully absorbed is first pulled
     * back into its driver (post-processing without constant propagation).
     */
    void pushInverters(String pushMark, byte reason, boolean pullBackFirst) {
        for (int l = 0; l < numLeaves; l++) {
            int a = inverterInput(l);
            if (a < 0) {
                continue;
            }
            int out = outputPin(l);
            if (out < 0 || idx.getPinNet(out) < 0) {
                continue;
            }
            int b = resolve(idx.getPinNet(out));
            if (b == a || netOpaque[b] || netOpaque[a]) {
                continue;
            }
            IntList loads = liveLoads(b);
            boolean all = true;
            boolean port = false;
            int nAbsorb = 0;
            boolean[] absorbable = new boolean[loads.size()];
            for (int i = 0; i < loads.size(); i++) {
                if (canAbsorb(loads.get(i))) {
                    absorbable[i] = true;
                    nAbsorb++;
                } else {
                    all = false;
                    port |= idx.getPinLeaf(loads.get(i)) < 0;
                }
            }
            if (nAbsorb == 0 || port) {
                continue;
            }
            if (pullBackFirst && tryPullBack(l)) {
                continue;
            }
            if (all) {
                for (int i = 0; i < loads.size(); i++) {
                    absorb(loads.get(i), b, a, pushMark, false);
                }
                removeLeaf(l, reason);
                bypass(l, inputPinOf(l), out, a, false);
                collapseBuffers(loads, reason);
            } else {
                IntList absorbed = new IntList();
                for (int i = 0; i < loads.size(); i++) {
                    if (absorbable[i]) {
                        absorb(loads.get(i), b, a, pushMark, true);
                        absorbed.add(loads.get(i));
                    }
                }
                // The smaller group of loads is the one reconnected: when that is the group left
                // on the inverter, those loads are modified as well
                int remaining = loads.size() - absorbed.size();
                if (remaining <= absorbed.size()) {
                    for (int i = 0; i < loads.size(); i++) {
                        if (!absorbable[i]) {
                            mark(idx.getPinLeaf(loads.get(i)), pushMark);
                        }
                    }
                }
                collapseBuffers(absorbed, reason);
            }
        }
    }

    int inputPinOf(int l) {
        if (kind[l] == K_INV) {
            return findPin(l, "I");
        }
        Lut s = lut[l];
        return s.srcPin[0];
    }

    /** LUT1 loads that became buffers after absorbing an inverter are removed. */
    private void collapseBuffers(IntList loads, byte reason) {
        for (int i = 0; i < loads.size(); i++) {
            int l = idx.getPinLeaf(loads.get(i));
            if (l < 0 || removed[l] != ALIVE || (kind[l] != K_LUT && kind[l] != K_LUT62)) {
                continue;
            }
            Lut s = lut[l];
            if (s == null || s.dual || s.size != 1 || s.table != 2L) {
                continue;
            }
            int out = outputPin(l);
            int outNet = out < 0 ? -1 : idx.getPinNet(out);
            if (outNet < 0) {
                continue;
            }
            int r = resolve(outNet);
            int inPin = s.srcPin[0];
            int in = pinNet(inPin);
            if (r == in || netOpaque[r]) {
                continue;
            }
            removeLeaf(l, reason);
            bypass(l, inPin, out, in, false);
        }
    }

    /**
     * Post-processing: an inverter that remains is removed by inverting the LUT that drives it,
     * when all other loads of that LUT can absorb the inversion.
     */
    void pullBackInverters() {
        // Candidates are visited in hierarchical name order
        java.util.List<Integer> cands = new java.util.ArrayList<>();
        for (int l = 0; l < numLeaves; l++) {
            if (inverterInput(l) >= 0) {
                cands.add(l);
            }
        }
        String[] names = new String[numLeaves];
        for (int l : cands) {
            names[l] = idx.getLeafName(l);
        }
        cands.sort((x, y) -> names[x].compareTo(names[y]));
        for (int l : cands) {
            tryPullBack(l);
        }
    }

    /**
     * Removes inverter l by inverting the LUT that drives it, if all other loads of that LUT
     * are LUTs that can absorb the inversion.
     * @return True if the inverter was removed.
     */
    private boolean tryPullBack(int l) {
        int a = inverterInput(l);
        if (a < 0 || netOpaque[a]) {
            return false;
        }
        int out = outputPin(l);
        if (out < 0 || idx.getPinNet(out) < 0) {
            return false;
        }
        int b = resolve(idx.getPinNet(out));
        if (b == a || netOpaque[b]) {
            return false;
        }
        int dp = netDriver[a];
        if (dp < 0) {
            return false;
        }
        int x = idx.getPinLeaf(dp);
        if (x < 0 || x == l || removed[x] != ALIVE || frozen[x]
                || (kind[x] != K_LUT && kind[x] != K_LUT62)) {
            return false;
        }
        Lut xs = getLut(x);
        if (xs == null || xs.dual || outputPin(x) != dp) {
            return false;
        }
        IntList loads = liveLoads(a);
        boolean ok = true;
        int ivPins = 0;
        for (int i = 0; i < loads.size() && ok; i++) {
            int p = loads.get(i);
            int pl = idx.getPinLeaf(p);
            if (pl == l) {
                ivPins++;
            } else if (pl < 0 || (kind[pl] != K_LUT && kind[pl] != K_LUT62
                    && kind[pl] != K_LUTCY) || !canAbsorb(p)) {
                // Only LUT loads absorb the inversion of a pulled-back inverter
                ok = false;
            }
        }
        if (!ok || ivPins != 1) {
            return false;
        }
        xs.table = ~xs.table & mask(xs.size);
        xs.changed = true;
        mark(x, MARK_POST_PROCESS);
        IntList others = new IntList();
        for (int i = 0; i < loads.size(); i++) {
            int p = loads.get(i);
            if (idx.getPinLeaf(p) != l) {
                absorb(p, a, a, MARK_POST_PROCESS, false);
                others.add(p);
            }
        }
        removeLeaf(l, RM_PULLBACK);
        bypass(l, inputPinOf(l), out, a, false);
        collapseBuffers(others, RM_PULLBACK);
        return true;
    }

    // ---------------------------------------------------------------- sweep

    /** Removes leaves that do not (transitively) drive an observable sink. */
    void sweep() {
        boolean[] live = new boolean[numLeaves];
        IntList stack = new IntList();
        for (int p = idx.getNumLeafPins(); p < idx.getNumPins(); p++) {
            // Top-level output (or inout) ports
            if (idx.getPinLeaf(p) < 0 && idx.getPinDir(p) != EDIFLeafIndex.DRIVER) {
                markDriverLive(pinNet(p), live, stack);
            }
        }
        for (int n = 0; n < numNets; n++) {
            if (netDT[n]) {
                markDriverLive(n, live, stack);
                // Loads of a preserved net are kept as well
                for (int i = idx.getNetPinStart(n); i < idx.getNetPinEnd(n); i++) {
                    int l = idx.getPinLeaf(idx.getNetPin(i));
                    if (l >= 0 && !live[l] && removed[l] == ALIVE) {
                        live[l] = true;
                        stack.add(l);
                    }
                }
            }
        }
        // Leaves under a preserved (DONT_TOUCH) hierarchical instance are never swept
        boolean[] hierDT = new boolean[idx.getNumHierInsts()];
        for (int h = 1; h < idx.getNumHierInsts(); h++) {
            hierDT[h] = hierDT[idx.getHierParent(h)] || hasPreserveProp(idx.getHierInst(h));
        }
        for (int l = 0; l < numLeaves; l++) {
            if (removed[l] != ALIVE || live[l]) {
                continue;
            }
            if (frozen[l] || !hasOutputPort(l) || hierDT[idx.getLeafHier(l)]) {
                live[l] = true;
                stack.add(l);
            }
        }
        while (stack.size() > 0) {
            int l = stack.pop();
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                    if (kind[l] == K_LUTCY) {
                        keepLookaheadLoads(p, live, stack);
                    }
                    continue;
                }
                if (pinDropped[p]) {
                    continue;
                }
                markDriverLive(pinNet(p), live, stack);
            }
        }
        for (int l = 0; l < numLeaves; l++) {
            if (removed[l] == ALIVE && !live[l] && kind[l] != K_GND && kind[l] != K_VCC) {
                setRemoved(l, RM_SWEEP);
            }
        }
        // Surviving cells whose output lost all its loads to the sweep are modified
        for (int l = 0; l < numLeaves; l++) {
            if (removed[l] != RM_SWEEP) {
                continue;
            }
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER) {
                    continue;
                }
                int n = pinNet(p);
                if (n < 0 || netVal[n] != UNK || netDriver[n] < 0) {
                    continue;
                }
                int d = idx.getPinLeaf(netDriver[n]);
                if (d >= 0 && removed[d] == ALIVE && !hasLoads(n)) {
                    mark(d, MARK_SWEEP);
                }
            }
        }
    }

    /** A LOOKAHEAD8 is kept as long as a LUTCY/LUT6CY feeding it is live. */
    private void keepLookaheadLoads(int p, boolean[] live, IntList stack) {
        int n = idx.getPinNet(p);
        if (n < 0) {
            return;
        }
        for (int i = idx.getNetPinStart(n); i < idx.getNetPinEnd(n); i++) {
            int q = idx.getNetPin(i);
            int ql = idx.getPinLeaf(q);
            if (ql >= 0 && !live[ql] && removed[ql] == ALIVE
                    && idx.getPinDir(q) == EDIFLeafIndex.SINK
                    && idx.getLeafInst(ql).getCellType().getName().equals("LOOKAHEAD8")) {
                live[ql] = true;
                stack.add(ql);
            }
        }
    }

    private boolean hasOutputPort(int l) {
        for (EDIFPort port : idx.getLeafInst(l).getCellType().getPorts()) {
            if (port.getDirection() != EDIFDirection.INPUT) {
                return true;
            }
        }
        return false;
    }

    private void markDriverLive(int n, boolean[] live, IntList stack) {
        if (n < 0) {
            return;
        }
        int r = resolve(n);
        if (netVal[r] != UNK) {
            return;
        }
        int dp = netDriver[r];
        if (dp >= 0) {
            markLive(idx.getPinLeaf(dp), live, stack);
        }
        if (dp == -2 || netOpaque[r]) {
            // Several drivers, or bidirectional pins (which may drive it): keep all of them
            for (int i = idx.getNetPinStart(r); i < idx.getNetPinEnd(r); i++) {
                int q = idx.getNetPin(i);
                if (idx.getPinDir(q) != EDIFLeafIndex.SINK) {
                    markLive(idx.getPinLeaf(q), live, stack);
                }
            }
        }
    }

    private void markLive(int l, boolean[] live, IntList stack) {
        if (l >= 0 && !live[l] && removed[l] == ALIVE) {
            live[l] = true;
            stack.add(l);
        }
    }

    // ---------------------------------------------------------------- driver

    /** Runs the enabled analyses, in Vivado's phase order. */
    void run(EDIFOptStats stats) {
        long t = System.nanoTime();
        if (opts.isPropConst()) {
            propagate();
            if (opts.isPushInverters()) {
                pushInverters();
            }
        }
        stats.addTime("Constant propagation", System.nanoTime() - t);
        t = System.nanoTime();
        if (opts.isSweep()) {
            sweep();
        }
        stats.addTime("Sweep", System.nanoTime() - t);
        t = System.nanoTime();
        if (opts.isPostProcess()) {
            if (!opts.isPropConst() && opts.isPushInverters()) {
                // Without constant propagation, inverters are absorbed during post-processing
                pushInverters(MARK_POST_PROCESS, RM_INV_PP, true);
            }
            pullBackInverters();
            if (opts.isPropConst() && !opts.isSweep()) {
                removeDanglingLogic();
            }
        }
        stats.addTime("Post processing", System.nanoTime() - t);
        assert checkConsistency();
        findOrphanNets();
        countStats(stats);
    }

    /** @return True if leaf l survives with a different cell type. */
    boolean isRetyped(int l) {
        Lut s = lut[l];
        if (s == null || removed[l] != ALIVE) {
            return false;
        }
        if (kind[l] == K_LUT62) {
            return !s.dual;
        }
        return kind[l] == K_LUT && s.size != lutSizeOf(idx.getLeafInst(l));
    }

    /**
     * Without sweep, post-processing removes combinational cells that constant propagation left
     * without any load (and, in turn, the cells that only fed them).
     */
    private void removeDanglingLogic() {
        IntList stack = new IntList();
        for (int l = 0; l < numLeaves; l++) {
            if (isDangling(l)) {
                stack.add(l);
            }
        }
        while (stack.size() > 0) {
            int l = stack.pop();
            if (removed[l] != ALIVE || !isDangling(l)) {
                continue;
            }
            removeLeaf(l, RM_DANGLING_PP);
            for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
                if (idx.getPinDir(p) == EDIFLeafIndex.DRIVER || pinDropped[p]) {
                    continue;
                }
                int n = pinNet(p);
                if (n >= 0 && netDriver[n] >= 0 && idx.getPinLeaf(netDriver[n]) >= 0) {
                    stack.add(idx.getPinLeaf(netDriver[n]));
                }
            }
        }
    }

    /** @return True if l is a combinational cell whose outputs had loads and now have none. */
    private boolean isDangling(int l) {
        if (removed[l] != ALIVE || frozen[l]
                || (kind[l] != K_LUT && kind[l] != K_LUT62 && kind[l] != K_INV)) {
            return false;
        }
        boolean hadLoads = false;
        for (int p = idx.getLeafPinStart(l); p < idx.getLeafPinEnd(l); p++) {
            int n = idx.getPinNet(p);
            if (idx.getPinDir(p) != EDIFLeafIndex.DRIVER || n < 0) {
                continue;
            }
            for (int i = idx.getNetPinStart(n); i < idx.getNetPinEnd(n); i++) {
                if (idx.getPinDir(idx.getNetPin(i)) != EDIFLeafIndex.DRIVER) {
                    hadLoads = true;
                }
            }
            int r = resolve(n);
            if (netOpaque[r] || hasLoads(r)) {
                return false;
            }
        }
        return hadLoads;
    }

    /** Finds nets that had loads, lost all of them, and whose driver survives. */
    private void findOrphanNets() {
        for (int n = 0; n < numNets; n++) {
            if (alias[n] >= 0 || netVal[n] != UNK || netOpaque[n]) {
                continue;
            }
            int dp = netDriver[n];
            if (dp < 0) {
                continue;
            }
            int d = idx.getPinLeaf(dp);
            if (d < 0 || removed[d] != ALIVE || kind[d] == K_GND || kind[d] == K_VCC) {
                continue;
            }
            boolean hadSinks = false;
            for (int i = idx.getNetPinStart(n); i < idx.getNetPinEnd(n) && !hadSinks; i++) {
                hadSinks = idx.getPinDir(idx.getNetPin(i)) != EDIFLeafIndex.DRIVER;
            }
            if (!hadSinks) {
                continue;
            }
            if (!hasLoads(n)) {
                // The output of a single-output LUT that was rewritten is dropped with it
                Lut s = lut[d];
                if (s != null && !s.dual && s.outPin >= 0 && s.outPin != dp) {
                    continue;
                }
                orphanNets.add(n);
            }
        }
    }

    private void countStats(EDIFOptStats stats) {
        int lutsModified = 0;
        int inverted = 0;
        for (int l = 0; l < numLeaves; l++) {
            switch (removed[l]) {
                case RM_CONST:
                case RM_ALIAS:
                case RM_INV:
                    stats.addRemoved(EDIFOptStats.Phase.CONSTANT_PROPAGATION, 1);
                    break;
                case RM_SWEEP:
                    stats.addRemoved(EDIFOptStats.Phase.SWEEP, 1);
                    break;
                case RM_PULLBACK:
                case RM_INV_PP:
                case RM_DANGLING_PP:
                    stats.addRemoved(EDIFOptStats.Phase.POST_PROCESSING_NETLIST, 1);
                    break;
                default:
                    if (isRetyped(l)) {
                        stats.addCreated(EDIFOptStats.Phase.CONSTANT_PROPAGATION, 1);
                        stats.addRemoved(EDIFOptStats.Phase.CONSTANT_PROPAGATION, 1);
                    } else if (lut[l] != null && lut[l].changed) {
                        lutsModified++;
                    }
                    break;
            }
        }
        for (Map<String, String> m : props.values()) {
            inverted += m.size();
        }
        stats.addLutsModified(lutsModified);
        stats.addPinsInverted(inverted);
    }
}
