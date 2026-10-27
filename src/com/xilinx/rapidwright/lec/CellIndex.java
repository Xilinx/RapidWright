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

package com.xilinx.rapidwright.lec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * Dense local numbering of an {@link EDIFCell}'s nets, child instances and
 * bit-blasted port bits.
 *
 * These tables are computed once per cell <i>type</i>, not per instance, and
 * shared by every instance of that type. That is what allows
 * {@link FlatNetlist} to address a hierarchical net by pure arithmetic
 * ({@code netBase[instId] + localNetIndex}) instead of hashing on a
 * per-instance object. At the scale this tool targets that difference is worth
 * well over an order of magnitude in memory.
 *
 * <h2>Port bit indexing</h2>
 *
 * Port bits use the same <i>internal</i> ordering that
 * {@link EDIFPortInst#getIndex()} reports, which is a dense 0-based index into
 * the port's bit vector and is not in general the number appearing in the port
 * instance name. For a port declared {@code S[7:0]} the port instance named
 * {@code S[3]} has internal index 4. Confusing the two silently reverses every
 * bus, which for a carry chain means reversing the direction of carry
 * propagation, so the conversion lives here and nowhere else. Use
 * {@link #getPortBitName(int)} to recover the named form and
 * {@link #portBitIndexOfName(EDIFPort, int)} to go the other way.
 */
public class CellIndex {

    private static final Map<EDIFCell, CellIndex> CACHE = new IdentityHashMap<>();

    private final EDIFCell cell;

    private final Map<EDIFNet, Integer> netIndices;

    private final Map<EDIFCellInst, Integer> childIndices;

    /** Ordered child instances, indexed by the value in {@link #childIndices}. */
    private final List<EDIFCellInst> children;

    /** First port bit index of each port, keyed by port name. */
    private final Map<String, Integer> portBitBase;

    /** Ports in port bit order, one entry repeated per bit of a bussed port. */
    private final EDIFPort[] portOfBit;

    /** Bit position within its own port, for each port bit. */
    private final int[] bitWithinPort;

    /**
     * For each port bit, the local net index of the net attached to that port
     * bit on the <i>inside</i> of this cell, or -1 if nothing is attached. This
     * is the hierarchy crossing table: it pairs with the net seen from outside
     * the instance to merge the two into one electrical node.
     */
    private final int[] boundaryNet;

    private CellIndex(EDIFCell cell) {
        this.cell = cell;
        Collection<EDIFNet> nets = cell.getNets();
        netIndices = new IdentityHashMap<>(Math.max(4, nets.size() * 2));
        int netIdx = 0;
        for (EDIFNet net : nets) {
            netIndices.put(net, netIdx++);
        }

        Collection<EDIFCellInst> insts = cell.getCellInsts();
        childIndices = new IdentityHashMap<>(Math.max(4, insts.size() * 2));
        children = new ArrayList<>(insts.size());
        for (EDIFCellInst inst : insts) {
            childIndices.put(inst, children.size());
            children.add(inst);
        }

        int totalBits = 0;
        for (EDIFPort port : cell.getPorts()) {
            totalBits += port.getWidth();
        }
        portBitBase = new HashMap<>(Math.max(4, cell.getPorts().size() * 2));
        portOfBit = new EDIFPort[totalBits];
        bitWithinPort = new int[totalBits];
        int bit = 0;
        for (EDIFPort port : cell.getPorts()) {
            portBitBase.put(port.getBusName(), bit);
            for (int i = 0; i < port.getWidth(); i++) {
                portOfBit[bit] = port;
                bitWithinPort[bit] = i;
                bit++;
            }
        }

        boundaryNet = new int[totalBits];
        buildBoundaryNets();
    }

    /**
     * Populates {@link #boundaryNet} by scanning this cell's nets for port
     * instances that belong to the cell itself rather than to a child, which is
     * how EDIF represents a connection to the cell's own interface.
     */
    private void buildBoundaryNets() {
        for (int i = 0; i < boundaryNet.length; i++) {
            boundaryNet[i] = -1;
        }
        for (EDIFNet net : cell.getNets()) {
            Integer localNet = netIndices.get(net);
            for (EDIFPortInst portInst : net.getPortInsts()) {
                if (portInst.getCellInst() != null) {
                    continue;
                }
                int bit = portBitIndex(portInst);
                if (bit >= 0) {
                    boundaryNet[bit] = localNet;
                }
            }
        }
    }

    /**
     * Returns the shared index tables for a cell, computing them on first use.
     * Synchronized because {@link FlatNetlist} may be built from several
     * threads, though the common case is a single builder thread.
     */
    public static synchronized CellIndex get(EDIFCell cell) {
        CellIndex index = CACHE.get(cell);
        if (index == null) {
            index = new CellIndex(cell);
            CACHE.put(cell, index);
        }
        return index;
    }

    /**
     * Discards all cached tables. Call this once a netlist has been fully
     * processed and is about to be released, otherwise the cache pins the whole
     * EDIF object graph in memory and defeats the pipelining that keeps peak
     * usage down.
     */
    public static synchronized void clearCache() {
        CACHE.clear();
    }

    public EDIFCell getCell() {
        return cell;
    }

    public int getNetCount() {
        return netIndices.size();
    }

    public int getChildCount() {
        return children.size();
    }

    public int getPortBitCount() {
        return portOfBit.length;
    }

    /**
     * @return The local index of a net within this cell, or -1 if the net does
     *         not belong to this cell.
     */
    public int localNetIndex(EDIFNet net) {
        Integer idx = netIndices.get(net);
        return idx == null ? -1 : idx;
    }

    /**
     * @return The local index of a child instance within this cell, or -1 if the
     *         instance is not a child of this cell.
     */
    public int localChildIndex(EDIFCellInst inst) {
        Integer idx = childIndices.get(inst);
        return idx == null ? -1 : idx;
    }

    public EDIFCellInst getChild(int localChildIndex) {
        return children.get(localChildIndex);
    }

    public List<EDIFCellInst> getChildren() {
        return children;
    }

    /**
     * Resolves the port bit index that a port instance connects to.
     *
     * @param portInst A port instance, either on a child instance or on this
     *                 cell's own interface.
     * @return The port bit index in this cell's numbering, or -1 if the port is
     *         not part of the cell whose index this is.
     */
    public int portBitIndex(EDIFPortInst portInst) {
        EDIFPort port = portInst.getPort();
        if (port == null) {
            return -1;
        }
        Integer base = portBitBase.get(port.getBusName());
        if (base == null) {
            return -1;
        }
        if (!port.isBus()) {
            return base;
        }
        int internal = portInst.getIndex();
        if (internal < 0 || internal >= port.getWidth()) {
            return -1;
        }
        return base + internal;
    }

    /**
     * Resolves a port bit index from a port and the index as it appears in a
     * port instance name, inverting the endian and offset handling of
     * {@link EDIFPort#getPortIndexFromNameIndex(int)}.
     *
     * @param port      A port of this cell.
     * @param namedIndex The number appearing between the brackets in the port
     *                   instance name.
     * @return The port bit index, or -1 if unknown.
     */
    public int portBitIndexOfName(EDIFPort port, int namedIndex) {
        Integer base = portBitBase.get(port.getBusName());
        if (base == null) {
            return -1;
        }
        if (!port.isBus()) {
            return base;
        }
        int internal = port.getPortIndexFromNameIndex(namedIndex);
        if (internal < 0 || internal >= port.getWidth()) {
            return -1;
        }
        return base + internal;
    }

    /**
     * Looks up a port bit by the pin name a primitive model would use, such as
     * {@code "CI"} or {@code "S[3]"}.
     *
     * @return The port bit index, or -1 if this cell has no such pin.
     */
    public int portBitIndexOfPinName(String pinName) {
        int bracket = pinName.lastIndexOf('[');
        if (bracket < 0 || !pinName.endsWith("]")) {
            Integer base = portBitBase.get(pinName);
            return base == null ? -1 : base;
        }
        String busName = pinName.substring(0, bracket);
        EDIFPort port = cell.getPort(busName);
        if (port == null) {
            port = cell.getPortByPortInstName(pinName);
        }
        if (port == null) {
            return -1;
        }
        int namedIndex;
        try {
            namedIndex = Integer.parseInt(pinName.substring(bracket + 1, pinName.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
        return portBitIndexOfName(port, namedIndex);
    }

    public EDIFPort getPortOfBit(int portBitIndex) {
        return portOfBit[portBitIndex];
    }

    /**
     * @return The name a port instance on this port bit would carry, for example
     *         {@code "CI"} for a scalar port or {@code "S[3]"} for a bus bit.
     */
    public String getPortBitName(int portBitIndex) {
        EDIFPort port = portOfBit[portBitIndex];
        if (!port.isBus()) {
            return port.getBusName();
        }
        return port.getBusName() + "[" + getNamedIndex(portBitIndex) + "]";
    }

    /**
     * @return The index as it appears in the port instance name for this port
     *         bit, or -1 for a scalar port.
     */
    public int getNamedIndex(int portBitIndex) {
        EDIFPort port = portOfBit[portBitIndex];
        if (!port.isBus()) {
            return -1;
        }
        int internal = bitWithinPort[portBitIndex];
        int left = port.getLeft();
        return port.isLittleEndian() ? left - internal : left + internal;
    }

    /**
     * @return The local net index attached to the inside of the given port bit,
     *         or -1 if nothing is attached. Always -1 for a leaf primitive,
     *         which has no internal nets.
     */
    public int getBoundaryNet(int portBitIndex) {
        return boundaryNet[portBitIndex];
    }
}
