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

import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.CHUNK;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.isBusBit;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.isBusRangeDeclaration;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.isPrimitive;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.pow31;
import static com.xilinx.rapidwright.util.route_status.RouteStatusNetlist.slot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;

import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/** The connectivity of a (non-leaf) cell definition, precomputed once for all its instances. */
final class CellInfo {

    /** Connection kinds of a port instance on a net, as precomputed per cell definition */
    static final byte OWN_PORT = 0;
    static final byte LEAF_OUTPUT = 1;
    static final byte LEAF_INPUT = 2;
    static final byte LEAF_INOUT = 3;
    static final byte HIER = 4;
    /** Pins of black boxes (leaf cells that are not primitives) are not counted */
    static final byte BLACK_BOX = 5;
    static final byte BLACK_BOX_OUTPUT = 6;
    static final byte BLACK_BOX_INOUT = 7;
    /** Flags of a port instance on a net, combined with its connection kind */
    static final byte KIND_MASK = 0x0f;
    /** A hierarchical output port that dangles inside the child */
    static final byte DANGLING = 0x10;
    /** A hierarchical output port that only has a net inside the child */
    static final byte PORT_ONLY = 0x20;
    /** A hierarchical inout port that dangles inside the child */
    static final byte DANGLING_INOUT = 0x40;
    /** A hierarchical output port */
    static final byte CHILD_OUTPUT = (byte) 0x80;

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
            childIsBlackBox[i] = childIsLeaf[i] && !isPrimitive(type);
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
                if (childIsBlackBox[c]) {
                    kinds[i] = epi.isOutput() ? BLACK_BOX_OUTPUT : epi.isInput() ? BLACK_BOX : BLACK_BOX_INOUT;
                } else if (childIsLeaf[c]) {
                    chunk.leafPinNames[i] = epi.getName();
                    if (epi.isOutput()) {
                        kinds[i] = LEAF_OUTPUT;
                        EDIFCell type = eci.getCellType();
                        String typeName = type.getName();
                        if ((typeName.equals("GND") || typeName.equals("VCC")) && isPrimitive(type)) {
                            staticTypes[n] = typeName.equals("GND") ? NetType.GND : NetType.VCC;
                        }
                    } else {
                        kinds[i] = epi.isInput() ? LEAF_INPUT : LEAF_INOUT;
                    }
                } else {
                    int slot = slot(childOffsets[c], epi);
                    slots[i] = slot;
                    byte kind = HIER;
                    if (epi.isOutput()) {
                        kind |= CHILD_OUTPUT;
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

    /** Copies a (large) collection into an array, in parallel if in a fork join pool. */
    private static <T> T[] toArray(Collection<T> collection, T[] empty, boolean parallel) {
        if (parallel && collection.size() > 4 * CHUNK) {
            return collection.parallelStream().toArray((n) -> Arrays.copyOf(empty, n));
        }
        return collection.toArray(empty);
    }
}
