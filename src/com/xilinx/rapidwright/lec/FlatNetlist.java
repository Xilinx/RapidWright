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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * A flattened, integer indexed view of a hierarchical netlist.
 *
 * Every instance in the hierarchy, at every depth, is assigned a dense integer
 * identifier in breadth first order, with the top cell instance as identifier 0.
 * Because children are enumerated contiguously, a child can be addressed by
 * arithmetic ({@code childBase[instId] + localChildIndex}) rather than by a map
 * lookup, and the same holds for hierarchical nets
 * ({@code netBase[instId] + localNetIndex}) and leaf pins.
 *
 * This is the central scaling decision of the tool. At half a million leaf
 * primitives a netlist has on the order of two million hierarchical nets; the
 * parallel integer arrays here total a few tens of megabytes, whereas the
 * equivalent map keyed on hierarchical net objects costs hundreds of megabytes
 * plus sustained garbage collection pressure.
 *
 * <h2>What counts as a leaf</h2>
 *
 * A cell is an atom if it is a leaf or black box, or if it lives in the
 * {@code hdi_primitives} library. The second condition matters: RapidWright's
 * macro expansion shares one expanded cell across every instance of a macro
 * type and does not propagate per instance parameters, so descending into an
 * expanded macro would read the macro template's INIT rather than the
 * instance's. Macro types are therefore treated as atoms and modeled natively.
 */
public class FlatNetlist {

    private static final int NO_PARENT = -1;

    private final EDIFNetlist netlist;

    /** Parent instance identifier, or -1 for the top instance. */
    private final int[] parent;

    /**
     * Instance identifier of this instance's first child. Children occupy a
     * contiguous identifier range, so child {@code c} of instance {@code i} is
     * {@code childBase[i] + c}. -1 when the instance has no children.
     */
    private final int[] childBase;

    /** Index into {@link #cellTypes} for each instance. */
    private final int[] cellTypeId;

    /**
     * The underlying instance object. Retained because pin enumeration and
     * property reads (INIT, CARRY_TYPE, IS_x_INVERTED) both need it. This pins
     * the EDIF object graph in memory, which is why a FlatNetlist must be
     * released once elaboration has produced a {@link NetlistSummary}.
     */
    private final EDIFCellInst[] instObj;

    /** Prefix sum of hierarchical net counts; length is instance count plus one. */
    private final int[] netBase;

    /** Prefix sum of leaf pin counts; length is instance count plus one. */
    private final int[] pinBase;

    private final boolean[] leaf;

    private final List<EDIFCell> cellTypes;

    private final int[] leafInstIds;

    private FlatNetlist(EDIFNetlist netlist, int instCount, int[] parent, int[] childBase,
            int[] cellTypeId, EDIFCellInst[] instObj, boolean[] leaf, List<EDIFCell> cellTypes) {
        this.netlist = netlist;
        this.parent = parent;
        this.childBase = childBase;
        this.cellTypeId = cellTypeId;
        this.instObj = instObj;
        this.leaf = leaf;
        this.cellTypes = cellTypes;

        netBase = new int[instCount + 1];
        pinBase = new int[instCount + 1];
        int leafCount = 0;
        for (int i = 0; i < instCount; i++) {
            CellIndex index = CellIndex.get(cellTypes.get(cellTypeId[i]));
            netBase[i + 1] = netBase[i] + index.getNetCount();
            pinBase[i + 1] = pinBase[i] + (leaf[i] ? index.getPortBitCount() : 0);
            if (leaf[i]) {
                leafCount++;
            }
        }
        leafInstIds = new int[leafCount];
        int next = 0;
        for (int i = 0; i < instCount; i++) {
            if (leaf[i]) {
                leafInstIds[next++] = i;
            }
        }
    }

    /**
     * Flattens a netlist's instance tree into integer indexed arrays. Traversal
     * uses an explicit work list, never recursion, because hierarchy depth is
     * unbounded in principle and stack depth is not.
     */
    public static FlatNetlist build(EDIFNetlist netlist, LecLog log) {
        EDIFCellInst top = netlist.getTopCellInst();
        if (top == null) {
            throw new LecException("Netlist has no top cell instance");
        }

        int capacity = 1024;
        int[] parent = new int[capacity];
        int[] childBase = new int[capacity];
        int[] cellTypeId = new int[capacity];
        EDIFCellInst[] instObj = new EDIFCellInst[capacity];
        boolean[] leaf = new boolean[capacity];

        List<EDIFCell> cellTypes = new ArrayList<>();
        Map<EDIFCell, Integer> cellTypeIds = new IdentityHashMap<>();

        int count = 0;
        parent[count] = NO_PARENT;
        childBase[count] = -1;
        cellTypeId[count] = typeIdOf(top.getCellType(), cellTypes, cellTypeIds);
        instObj[count] = top;
        leaf[count] = isAtom(top.getCellType());
        count++;

        Deque<Integer> work = new ArrayDeque<>();
        work.add(0);
        while (!work.isEmpty()) {
            int instId = work.remove();
            if (leaf[instId]) {
                continue;
            }
            CellIndex index = CellIndex.get(cellTypes.get(cellTypeId[instId]));
            List<EDIFCellInst> children = index.getChildren();
            if (children.isEmpty()) {
                continue;
            }
            if (count + children.size() > capacity) {
                capacity = Math.max(capacity * 2, count + children.size());
                parent = Arrays.copyOf(parent, capacity);
                childBase = Arrays.copyOf(childBase, capacity);
                cellTypeId = Arrays.copyOf(cellTypeId, capacity);
                instObj = Arrays.copyOf(instObj, capacity);
                leaf = Arrays.copyOf(leaf, capacity);
            }
            childBase[instId] = count;
            for (EDIFCellInst child : children) {
                parent[count] = instId;
                childBase[count] = -1;
                cellTypeId[count] = typeIdOf(child.getCellType(), cellTypes, cellTypeIds);
                instObj[count] = child;
                leaf[count] = isAtom(child.getCellType());
                work.add(count);
                count++;
            }
        }

        FlatNetlist flat = new FlatNetlist(netlist, count, Arrays.copyOf(parent, count),
                Arrays.copyOf(childBase, count), Arrays.copyOf(cellTypeId, count),
                Arrays.copyOf(instObj, count), Arrays.copyOf(leaf, count), cellTypes);
        if (log != null) {
            log.info("Flattened " + count + " instances (" + flat.getLeafCount() + " leaves, "
                    + cellTypes.size() + " distinct cell types, " + flat.getNetCount()
                    + " hierarchical nets, " + flat.getLeafPinCount() + " leaf pins)");
        }
        return flat;
    }

    private static int typeIdOf(EDIFCell cell, List<EDIFCell> cellTypes, Map<EDIFCell, Integer> ids) {
        Integer id = ids.get(cell);
        if (id == null) {
            id = cellTypes.size();
            cellTypes.add(cell);
            ids.put(cell, id);
        }
        return id;
    }

    /**
     * Decides whether cone traversal stops at this cell type.
     *
     * @see FlatNetlist class documentation for why membership of the primitives
     *      library alone is enough, even when the cell has contents.
     */
    public static boolean isAtom(EDIFCell cell) {
        if (cell.isLeafCellOrBlackBox()) {
            return true;
        }
        return cell.getLibrary() != null && cell.getLibrary().isHDIPrimitivesLibrary();
    }

    public EDIFNetlist getNetlist() {
        return netlist;
    }

    public int getInstCount() {
        return parent.length;
    }

    public int getNetCount() {
        return netBase[netBase.length - 1];
    }

    public int getLeafPinCount() {
        return pinBase[pinBase.length - 1];
    }

    public int getLeafCount() {
        return leafInstIds.length;
    }

    /** @return The instance identifiers of every atom, in ascending order. */
    public int[] getLeafInstIds() {
        return leafInstIds;
    }

    public int getTopInstId() {
        return 0;
    }

    public int getParent(int instId) {
        return parent[instId];
    }

    public boolean isLeaf(int instId) {
        return leaf[instId];
    }

    public EDIFCell getCellType(int instId) {
        return cellTypes.get(cellTypeId[instId]);
    }

    public String getCellTypeName(int instId) {
        return cellTypes.get(cellTypeId[instId]).getName();
    }

    /** @return The dense identifier of this instance's cell type. */
    public int getCellTypeId(int instId) {
        return cellTypeId[instId];
    }

    public List<EDIFCell> getCellTypes() {
        return cellTypes;
    }

    public CellIndex getIndex(int instId) {
        return CellIndex.get(cellTypes.get(cellTypeId[instId]));
    }

    public String getLocalName(int instId) {
        return instObj[instId].getName();
    }

    /** @return The underlying instance object for property and pin access. */
    public EDIFCellInst getInst(int instId) {
        return instObj[instId];
    }

    /**
     * @return The instance identifier of the given child of an instance, or -1
     *         if the local index is out of range.
     */
    public int getChild(int instId, int localChildIndex) {
        if (childBase[instId] < 0) {
            return -1;
        }
        return childBase[instId] + localChildIndex;
    }

    /**
     * @return The global identifier of a hierarchical net, formed by offsetting
     *         the net's index within its parent cell by that instance's base.
     */
    public int getGlobalNet(int instId, int localNetIndex) {
        if (localNetIndex < 0) {
            return -1;
        }
        return netBase[instId] + localNetIndex;
    }

    /**
     * @return The dense identifier of a leaf pin, or -1 if the instance is not a
     *         leaf.
     */
    public int getGlobalPin(int instId, int portBitIndex) {
        if (!leaf[instId] || portBitIndex < 0) {
            return -1;
        }
        return pinBase[instId] + portBitIndex;
    }

    /**
     * Recovers the instance identifier owning a dense leaf pin identifier by
     * binary search over the pin base prefix sums.
     */
    public int getPinOwner(int globalPin) {
        int lo = 0;
        int hi = parent.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (pinBase[mid] <= globalPin) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    public int getPinPortBit(int globalPin) {
        return globalPin - pinBase[getPinOwner(globalPin)];
    }

    /**
     * Builds the full hierarchical name of an instance by walking the parent
     * chain. The top instance's own name is not included, matching RapidWright's
     * convention that hierarchical names are relative to the top cell.
     *
     * @return The hierarchical name, or the empty string for the top instance.
     */
    public String getInstName(int instId) {
        if (instId == 0) {
            return "";
        }
        int depth = 0;
        for (int i = instId; i != 0; i = parent[i]) {
            depth++;
        }
        String[] parts = new String[depth];
        int at = depth;
        for (int i = instId; i != 0; i = parent[i]) {
            parts[--at] = instObj[i].getName();
        }
        return String.join(EDIFTools.EDIF_HIER_SEP, parts);
    }

    /**
     * Finds an instance by its full hierarchical name, by walking down from the
     * top cell, so the cost is the number of children per level along the path
     * rather than the size of the netlist.
     *
     * A renamed EDIF instance can have a separator in its own name, so every
     * child whose name is a prefix of the remaining path is followed, and a
     * name that resolves to more than one instance is refused.
     *
     * @return The instance identifier, or -1 if no instance has that name.
     */
    public int findInstId(String hierName) {
        int found = findInstId(0, hierName);
        if (found == -2) {
            throw new LecException("The hierarchical name '" + hierName + "' matches more than "
                    + "one instance");
        }
        return found;
    }

    /** @return The match below an instance, -1 for none, -2 for several. */
    private int findInstId(int instId, String remaining) {
        if (childBase[instId] < 0) {
            return -1;
        }
        int found = -1;
        int children = getIndex(instId).getChildCount();
        for (int i = 0; i < children; i++) {
            int child = childBase[instId] + i;
            String name = instObj[child].getName();
            if (!remaining.startsWith(name)) {
                continue;
            }
            int match;
            if (remaining.length() == name.length()) {
                match = child;
            } else if (remaining.startsWith(EDIFTools.EDIF_HIER_SEP, name.length())) {
                match = findInstId(child, remaining.substring(name.length() + 1));
            } else {
                continue;
            }
            if (match == -2 || (match >= 0 && found >= 0)) {
                return -2;
            }
            if (match >= 0) {
                found = match;
            }
        }
        return found;
    }

    /**
     * @return The full hierarchical name of a leaf pin, such as
     *         {@code "u_top/u_core/reg0/D"}.
     */
    public String getPinName(int instId, int portBitIndex) {
        String inst = getInstName(instId);
        String pin = getIndex(instId).getPortBitName(portBitIndex);
        return inst.isEmpty() ? pin : inst + EDIFTools.EDIF_HIER_SEP + pin;
    }
}
