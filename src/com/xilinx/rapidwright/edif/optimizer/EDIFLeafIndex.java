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
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * A flat, integer-indexed view of the leaf-level connectivity of a hierarchical
 * {@link EDIFNetlist}. Hierarchical instances, leaf instances, leaf pins and physical (flattened)
 * nets are each numbered densely, which allows whole-netlist analyses (such as constant
 * propagation and sweep) to run on primitive arrays instead of hierarchical name strings. Any cell
 * from the hdi_primitives library is an atomic leaf (macros are not entered); cells elsewhere
 * without contents are black boxes. The index is a snapshot: it must be rebuilt after the netlist
 * is modified.
 */
public class EDIFLeafIndex {

    /** Pin direction, as seen from the net: the pin drives the net. */
    public static final byte DRIVER = 1;
    /** Pin direction, as seen from the net: the pin loads the net. */
    public static final byte SINK = 2;
    /** Pin direction, as seen from the net: the pin is bidirectional. */
    public static final byte BIDIR = 3;

    private final EDIFNetlist netlist;
    private final Predicate<EDIFCellInst> boundary;

    // Hierarchical (non-leaf) instances; index 0 is the top
    private int numHier;
    private EDIFCellInst[] hInst;
    private int[] hParent;
    private EDIFCell[] hCell;
    private int[] hSegBase;

    // Net segments (a net within a hierarchical instance), numbered hSegBase[h] + local net index
    private int numSegs;
    private int[] segNet;

    // Leaf instances
    private int numLeaves;
    private EDIFCellInst[] lInst;
    private int[] lHier;
    private int[] lPinStart;
    private boolean[] lBlackBox;

    // Pins: leaf pins first (grouped by leaf), then top-level port pins
    private int numPins;
    private EDIFPortInst[] pPortInst;
    private int[] pLeaf;
    private int[] pNet;
    private byte[] pDir;

    // Physical nets
    private int numNets;
    private int[] nPinStart;
    private int[] nPins;
    private int[] nSegStart;
    private int[] nSegs;

    /**
     * Builds the index for the provided netlist.
     * @param netlist The netlist to index.
     */
    public EDIFLeafIndex(EDIFNetlist netlist) {
        this(netlist, null);
    }

    /**
     * Builds the index for the provided netlist, treating the ports of the hierarchical
     * instances selected by boundary as opaque: nets are not joined across them. Instead, each
     * port of such an instance becomes port pins (leaf index {@link #BOUNDARY}), one on the outer
     * net (a load for an input port, a driver for an output port) and one on the inner net (the
     * reverse), just like top-level ports. A side not connected to a net has no pin, while the
     * other side keeps its own.
     * @param netlist The netlist to index.
     * @param boundary Selects hierarchical instances whose ports are opaque (may be null).
     */
    public EDIFLeafIndex(EDIFNetlist netlist, Predicate<EDIFCellInst> boundary) {
        this.netlist = netlist;
        this.boundary = boundary;
        build();
    }

    /** Leaf index of a pin on the port of a boundary hierarchical instance. */
    public static final int BOUNDARY = -2;

    public EDIFNetlist getNetlist() {
        return netlist;
    }

    /**
     * Checks if the given cell is treated as a leaf by this index.
     * @param cell The cell type.
     * @return True if the cell is a primitive from the hdi_primitives library or has no contents.
     */
    public static boolean isLeafCell(EDIFCell cell) {
        EDIFLibrary lib = cell.getLibrary();
        return (lib != null && lib.isHDIPrimitivesLibrary()) || cell.isLeafCellOrBlackBox();
    }

    private static boolean isBlackBox(EDIFCellInst inst) {
        EDIFCell cell = inst.getCellType();
        EDIFLibrary lib = cell.getLibrary();
        if (lib != null && lib.isHDIPrimitivesLibrary()) {
            return inst.isBlackBox();
        }
        return true;
    }

    private EDIFNet[] getNets(EDIFCell cell) {
        return defs.get(cell).nets;
    }

    private int seg(int h, EDIFNet net) {
        return hSegBase[h] + defs.get(hCell[h]).netIdx.get(net);
    }

    private static final boolean PROFILE =
            System.getProperty("rapidwright.leafindex.profile") != null;

    private long lastT;

    private void lap(String what) {
        if (PROFILE) {
            long t = System.nanoTime();
            System.out.println(String.format("  [EDIFLeafIndex] %-28s %.3fs", what,
                    (t - lastT) / 1e9));
            lastT = t;
        }
    }

    /** Per cell definition data, computed once and shared by all its instances. */
    private static class DefInfo {
        EDIFNet[] nets;
        IdentityHashMap<EDIFNet, Integer> netIdx;
        EDIFCellInst[] children;
        boolean[] childIsLeaf;
        int[] childPinStart;
        EDIFPortInst[] pins;
        /** Local net of each child pin in this definition, -1 if unconnected. */
        int[] pinOuter;
        /** For pins of hierarchical children: local net inside the child's definition. */
        int[] pinInner;
    }

    private final Map<EDIFCell, DefInfo> defs = new IdentityHashMap<>();

    private DefInfo def(EDIFCell cell) {
        return defs.get(cell);
    }

    private static DefInfo computeDef(EDIFCell cell) {
        DefInfo d = new DefInfo();
        Collection<EDIFNet> nets = cell.getNets();
        d.nets = nets.toArray(new EDIFNet[nets.size()]);
        d.netIdx = new IdentityHashMap<>(d.nets.length * 2);
        for (int i = 0; i < d.nets.length; i++) {
            d.netIdx.put(d.nets[i], i);
        }
        Collection<EDIFCellInst> insts = cell.getCellInsts();
        d.children = insts.toArray(new EDIFCellInst[insts.size()]);
        d.childIsLeaf = new boolean[d.children.length];
        d.childPinStart = new int[d.children.length + 1];
        int count = 0;
        for (int i = 0; i < d.children.length; i++) {
            d.childPinStart[i] = count;
            count += d.children[i].getPortInsts().size();
            d.childIsLeaf[i] = isLeafCell(d.children[i].getCellType());
        }
        d.childPinStart[d.children.length] = count;
        d.pins = new EDIFPortInst[count];
        d.pinOuter = new int[count];
        d.pinInner = new int[count];
        int p = 0;
        for (int i = 0; i < d.children.length; i++) {
            for (EDIFPortInst pi : d.children[i].getPortInsts()) {
                d.pins[p] = pi;
                EDIFNet net = pi.getNet();
                Integer idx = net == null ? null : d.netIdx.get(net);
                d.pinOuter[p] = idx == null ? -1 : idx;
                d.pinInner[p] = -1;
                p++;
            }
        }
        return d;
    }

    /** Resolves, for each pin of a hierarchical child, the net inside the child definition. */
    private void computeInner(DefInfo d) {
        for (int i = 0; i < d.children.length; i++) {
            if (d.childIsLeaf[i]) {
                continue;
            }
            EDIFCell childType = d.children[i].getCellType();
            DefInfo cd = defs.get(childType);
            for (int p = d.childPinStart[i]; p < d.childPinStart[i + 1]; p++) {
                EDIFNet inner = childType.getInternalNet(d.pins[p]);
                Integer idx = inner == null ? null : cd.netIdx.get(inner);
                d.pinInner[p] = idx == null ? -1 : idx;
            }
        }
    }

    private void build() {
        lastT = System.nanoTime();
        // Pass 0: per-definition data for all hierarchical (non-leaf) cells in use, in parallel
        EDIFCell topCell = netlist.getTopCell();
        List<EDIFCell> defList = new ArrayList<>();
        java.util.Set<EDIFCell> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(topCell);
        defList.add(topCell);
        for (int i = 0; i < defList.size(); i++) {
            for (EDIFCellInst child : defList.get(i).getCellInsts()) {
                EDIFCell t = child.getCellType();
                if (!isLeafCell(t) && seen.add(t)) {
                    defList.add(t);
                }
            }
        }
        List<DefInfo> infos = new ArrayList<>(defList.size());
        defList.parallelStream().map(EDIFLeafIndex::computeDef).forEachOrdered(infos::add);
        for (int i = 0; i < defList.size(); i++) {
            defs.put(defList.get(i), infos.get(i));
        }
        infos.parallelStream().forEach(this::computeInner);
        lap("definitions");

        // Pass 1: enumerate hierarchical instances and leaves
        List<EDIFCellInst> hInstList = new ArrayList<>();
        IntList hParentList = new IntList();
        List<EDIFCellInst> leafList = new ArrayList<>();
        IntList leafHierList = new IntList();
        IntList leafChildList = new IntList();
        IntList hChildList = new IntList();
        EDIFCellInst topInst = netlist.getTopCellInst();
        hInstList.add(topInst);
        hParentList.add(-1);
        hChildList.add(-1);
        for (int h = 0; h < hInstList.size(); h++) {
            DefInfo d = defs.get(hInstList.get(h).getCellType());
            for (int i = 0; i < d.children.length; i++) {
                if (d.childIsLeaf[i]) {
                    leafList.add(d.children[i]);
                    leafHierList.add(h);
                    leafChildList.add(i);
                } else {
                    hInstList.add(d.children[i]);
                    hParentList.add(h);
                    hChildList.add(i);
                }
            }
        }
        numHier = hInstList.size();
        hInst = hInstList.toArray(new EDIFCellInst[numHier]);
        hParent = hParentList.toArray();
        int[] hChild = hChildList.toArray();
        hCell = new EDIFCell[numHier];
        hSegBase = new int[numHier + 1];
        int base = 0;
        for (int h = 0; h < numHier; h++) {
            hCell[h] = hInst[h].getCellType();
            hSegBase[h] = base;
            base += defs.get(hCell[h]).nets.length;
        }
        hSegBase[numHier] = base;
        numSegs = base;
        lap("hierarchy");

        // Pass 2: union segments across hierarchical port boundaries
        int[] uf = new int[numSegs];
        for (int i = 0; i < numSegs; i++) {
            uf[i] = i;
        }
        // Ports of preserved instances: each side connected to a net (outside in the parent,
        // inside the instance) gets a pin, even if the other side is not connected
        List<EDIFPortInst> boundaryOuter = new ArrayList<>();
        IntList boundaryHier = new IntList();
        IntList boundaryOuterSeg = new IntList();
        IntList boundaryInnerSeg = new IntList();
        int boundaryPins = 0;
        for (int h = 1; h < numHier; h++) {
            int parent = hParent[h];
            DefInfo pd = defs.get(hCell[parent]);
            int i = hChild[h];
            boolean opaque = boundary != null && boundary.test(hInst[h]);
            for (int p = pd.childPinStart[i]; p < pd.childPinStart[i + 1]; p++) {
                int outer = pd.pinOuter[p];
                int inner = pd.pinInner[p];
                if (opaque && (outer >= 0 || inner >= 0)) {
                    boundaryOuter.add(pd.pins[p]);
                    boundaryHier.add(h);
                    boundaryOuterSeg.add(outer < 0 ? -1 : hSegBase[parent] + outer);
                    boundaryInnerSeg.add(inner < 0 ? -1 : hSegBase[h] + inner);
                    boundaryPins += (outer < 0 ? 0 : 1) + (inner < 0 ? 0 : 1);
                } else if (!opaque && outer >= 0 && inner >= 0) {
                    union(uf, hSegBase[parent] + outer, hSegBase[h] + inner);
                }
            }
        }
        lap("union-find");
        segNet = new int[numSegs];
        int[] rootId = new int[numSegs];
        Arrays.fill(rootId, -1);
        numNets = 0;
        for (int s = 0; s < numSegs; s++) {
            int r = find(uf, s);
            if (rootId[r] < 0) {
                rootId[r] = numNets++;
            }
            segNet[s] = rootId[r];
        }
        lap("net ids");

        // Pass 3: leaf pins and top-level port pins
        numLeaves = leafList.size();
        lInst = leafList.toArray(new EDIFCellInst[numLeaves]);
        lHier = leafHierList.toArray();
        int[] lChild = leafChildList.toArray();
        lPinStart = new int[numLeaves + 1];
        lBlackBox = new boolean[numLeaves];
        int pinCount = 0;
        for (int l = 0; l < numLeaves; l++) {
            lPinStart[l] = pinCount;
            DefInfo d = defs.get(hCell[lHier[l]]);
            pinCount += d.childPinStart[lChild[l] + 1] - d.childPinStart[lChild[l]];
            lBlackBox[l] = isBlackBox(lInst[l]);
        }
        lPinStart[numLeaves] = pinCount;
        List<EDIFPortInst> topPins = new ArrayList<>();
        for (EDIFNet net : getNets(hCell[0])) {
            for (EDIFPortInst pi : net.getPortInsts()) {
                if (pi.isTopLevelPort()) {
                    topPins.add(pi);
                }
            }
        }
        numPins = pinCount + topPins.size() + boundaryPins;
        pPortInst = new EDIFPortInst[numPins];
        pLeaf = new int[numPins];
        pNet = new int[numPins];
        pDir = new byte[numPins];
        int p = 0;
        for (int l = 0; l < numLeaves; l++) {
            int h = lHier[l];
            DefInfo d = defs.get(hCell[h]);
            for (int k = d.childPinStart[lChild[l]]; k < d.childPinStart[lChild[l] + 1]; k++) {
                EDIFPortInst pi = d.pins[k];
                pPortInst[p] = pi;
                pLeaf[p] = l;
                pNet[p] = d.pinOuter[k] < 0 ? -1 : segNet[hSegBase[h] + d.pinOuter[k]];
                EDIFDirection dir = pi.getPort().getDirection();
                pDir[p] = dir == EDIFDirection.OUTPUT ? DRIVER
                        : dir == EDIFDirection.INPUT ? SINK : BIDIR;
                p++;
            }
        }
        for (EDIFPortInst pi : topPins) {
            pPortInst[p] = pi;
            pLeaf[p] = -1;
            pNet[p] = segNet[seg(0, pi.getNet())];
            EDIFDirection dir = pi.getPort().getDirection();
            // A top-level input port drives its net inside the top cell
            pDir[p] = dir == EDIFDirection.INPUT ? DRIVER
                    : dir == EDIFDirection.OUTPUT ? SINK : BIDIR;
            p++;
        }
        for (int i = 0; i < boundaryOuter.size(); i++) {
            EDIFPortInst pi = boundaryOuter.get(i);
            int h = boundaryHier.get(i);
            EDIFDirection dir = pi.getPort().getDirection();
            if (boundaryOuterSeg.get(i) >= 0) {
                pPortInst[p] = pi;
                pLeaf[p] = BOUNDARY;
                pNet[p] = segNet[boundaryOuterSeg.get(i)];
                pDir[p] = dir == EDIFDirection.INPUT ? SINK
                        : dir == EDIFDirection.OUTPUT ? DRIVER : BIDIR;
                p++;
            }
            if (boundaryInnerSeg.get(i) >= 0) {
                EDIFPortInst innerPi = hCell[h].getInternalNet(pi).getPortInst(null, pi.getName());
                pPortInst[p] = innerPi != null ? innerPi : pi;
                pLeaf[p] = BOUNDARY;
                pNet[p] = segNet[boundaryInnerSeg.get(i)];
                pDir[p] = dir == EDIFDirection.INPUT ? DRIVER
                        : dir == EDIFDirection.OUTPUT ? SINK : BIDIR;
                p++;
            }
        }

        lap("pins");
        // Pass 4: CSR net -> pins and net -> segments
        nPinStart = new int[numNets + 1];
        for (int i = 0; i < numPins; i++) {
            if (pNet[i] >= 0) {
                nPinStart[pNet[i] + 1]++;
            }
        }
        for (int n = 0; n < numNets; n++) {
            nPinStart[n + 1] += nPinStart[n];
        }
        nPins = new int[nPinStart[numNets]];
        int[] fill = Arrays.copyOf(nPinStart, numNets);
        for (int i = 0; i < numPins; i++) {
            if (pNet[i] >= 0) {
                nPins[fill[pNet[i]]++] = i;
            }
        }
        nSegStart = new int[numNets + 1];
        for (int s = 0; s < numSegs; s++) {
            nSegStart[segNet[s] + 1]++;
        }
        for (int n = 0; n < numNets; n++) {
            nSegStart[n + 1] += nSegStart[n];
        }
        nSegs = new int[numSegs];
        fill = Arrays.copyOf(nSegStart, numNets);
        for (int s = 0; s < numSegs; s++) {
            nSegs[fill[segNet[s]]++] = s;
        }
        lap("CSR");
    }

    private static int find(int[] uf, int x) {
        while (uf[x] != x) {
            uf[x] = uf[uf[x]];
            x = uf[x];
        }
        return x;
    }

    private static void union(int[] uf, int a, int b) {
        int ra = find(uf, a);
        int rb = find(uf, b);
        if (ra != rb) {
            if (ra < rb) {
                uf[rb] = ra;
            } else {
                uf[ra] = rb;
            }
        }
    }

    public int getNumHierInsts() {
        return numHier;
    }

    public EDIFCellInst getHierInst(int h) {
        return hInst[h];
    }

    public EDIFCell getHierCell(int h) {
        return hCell[h];
    }

    public int getHierParent(int h) {
        return hParent[h];
    }

    public int getNumLeaves() {
        return numLeaves;
    }

    public EDIFCellInst getLeafInst(int l) {
        return lInst[l];
    }

    /** @return The hierarchical instance index that contains leaf l. */
    public int getLeafHier(int l) {
        return lHier[l];
    }

    public boolean isLeafBlackBox(int l) {
        return lBlackBox[l];
    }

    public int getLeafPinStart(int l) {
        return lPinStart[l];
    }

    public int getLeafPinEnd(int l) {
        return lPinStart[l + 1];
    }

    public int getNumPins() {
        return numPins;
    }

    /** @return The number of leaf pins; top-level port pins are numbered from this value on. */
    public int getNumLeafPins() {
        return lPinStart[numLeaves];
    }

    public EDIFPortInst getPinPortInst(int p) {
        return pPortInst[p];
    }

    /**
     * @return The leaf of pin p, -1 if p is a top-level port pin or {@link #BOUNDARY} if it is
     *         on the port of a boundary hierarchical instance.
     */
    public int getPinLeaf(int p) {
        return pLeaf[p];
    }

    /** @return The physical net of pin p, or -1 if unconnected. */
    public int getPinNet(int p) {
        return pNet[p];
    }

    /** @return {@link #DRIVER}, {@link #SINK} or {@link #BIDIR}, as seen from the net. */
    public byte getPinDir(int p) {
        return pDir[p];
    }

    public int getNumNets() {
        return numNets;
    }

    public int getNetPinStart(int n) {
        return nPinStart[n];
    }

    public int getNetPinEnd(int n) {
        return nPinStart[n + 1];
    }

    /** @return The pin at position i of the net pin array (see {@link #getNetPinStart(int)}). */
    public int getNetPin(int i) {
        return nPins[i];
    }

    public int getNetSegStart(int n) {
        return nSegStart[n];
    }

    public int getNetSegEnd(int n) {
        return nSegStart[n + 1];
    }

    /** @return The segment at position i of the net segment array. */
    public int getNetSeg(int i) {
        return nSegs[i];
    }

    public int getNumSegs() {
        return numSegs;
    }

    /** @return The physical net that segment s belongs to. */
    public int getSegNet(int s) {
        return segNet[s];
    }

    /** @return The hierarchical instance index that owns segment s. */
    public int getSegHier(int s) {
        int h = Arrays.binarySearch(hSegBase, s);
        if (h < 0) {
            h = -h - 2;
        } else {
            // Skip over hierarchical instances whose cells have no nets
            while (hSegBase[h + 1] == s) {
                h++;
            }
        }
        return h;
    }

    /** @return The first segment of hierarchical instance h (local net i is segment base + i). */
    public int getHierSegBase(int h) {
        return hSegBase[h];
    }

    /** @return The nets of the cell of hierarchical instance h, indexed by local net index. */
    public EDIFNet[] getHierNets(int h) {
        return defs.get(hCell[h]).nets;
    }

    /** @return The EDIFNet of segment s. */
    public EDIFNet getSegEDIFNet(int s) {
        int h = getSegHier(s);
        return defs.get(hCell[h]).nets[s - hSegBase[h]];
    }

    /** @return The segment of the given net inside hierarchical instance h. */
    public int getSeg(int h, EDIFNet net) {
        return seg(h, net);
    }

    /** @return The physical net of the given EDIFNet inside hierarchical instance h. */
    public int getNet(int h, EDIFNet net) {
        return segNet[seg(h, net)];
    }

    /**
     * Gets the full hierarchical name of a hierarchical instance ("" for the top).
     * @param h The hierarchical instance index.
     * @return The '/'-separated hierarchical name.
     */
    public String getHierName(int h) {
        if (h == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        appendHierName(sb, h);
        return sb.toString();
    }

    private void appendHierName(StringBuilder sb, int h) {
        if (hParent[h] > 0) {
            appendHierName(sb, hParent[h]);
            sb.append(EDIFTools.EDIF_HIER_SEP);
        }
        sb.append(hInst[h].getName());
    }

    /** @return The full hierarchical name of leaf l. */
    public String getLeafName(int l) {
        int h = lHier[l];
        return h == 0 ? lInst[l].getName()
                : getHierName(h) + EDIFTools.EDIF_HIER_SEP + lInst[l].getName();
    }

    /** @return The full hierarchical name of pin p (top-level port pins use the port name). */
    public String getPinName(int p) {
        int l = pLeaf[p];
        if (l == BOUNDARY) {
            return "<boundary>" + pPortInst[p].getName();
        }
        if (l < 0) {
            return pPortInst[p].getName();
        }
        return getLeafName(l) + EDIFTools.EDIF_HIER_SEP + pPortInst[p].getName();
    }

    /** A minimal growable int array. */
    static class IntList {
        private int[] a = new int[16];
        private int size;

        void add(int v) {
            if (size == a.length) {
                a = Arrays.copyOf(a, size * 2);
            }
            a[size++] = v;
        }

        int get(int i) {
            return a[i];
        }

        int pop() {
            return a[--size];
        }

        int size() {
            return size;
        }

        void set(int i, int v) {
            a[i] = v;
        }

        void clear() {
            size = 0;
        }

        int[] toArray() {
            return Arrays.copyOf(a, size);
        }
    }
}
