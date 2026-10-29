/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.xilinx.rapidwright.device.IOStandard;
import com.xilinx.rapidwright.util.Pair;

/**
 * A view of a netlist with its macros collapsed, as Vivado writes and reads them. RapidWright keeps
 * the macros of a netlist expanded in memory (see {@link EDIFNetlist#expandMacroUnisims()}), and
 * writes them collapsed through this view (see {@link EDIFNetlist#exportEDIF(java.io.OutputStream)}),
 * which, unlike {@link EDIFNetlist#collapseMacroUnisims(com.xilinx.rapidwright.device.Series)},
 * leaves the netlist as it is. In this view:
 * <ul>
 * <li>A cell of the primitives library with contents, which is an expanded macro, has no contents.</li>
 * <li>A macro that an instance is expanded into for its IOStandard, such as OBUFDS_DUAL_BUF, has the
 * name of the primitive it is expanded from, such as OBUFDS. If the primitives library also has that
 * primitive, instances of the macro are of the primitive instead, and the macro is left out.</li>
 * <li>A primitive whose every instance is inside expanded macros, such as one that was added by
 * expanding them, is left out.</li>
 * <li>An instance of a cell of the netlist's external library (see
 * {@link EDIFNetlist#setExternalLibrary(EDIFLibrary)}) is of the black box that the cell fills.</li>
 * </ul>
 * The view is of the netlist when it is created, so it should not be kept while the netlist changes.
 */
public class EDIFCollapsedView {

    /**
     * The name of the primitive that each macro, that an instance may be expanded into for its
     * IOStandard, is collapsed into, by the name of the macro (the same in each series that has them)
     */
    private static final Map<String, String> collapsedNames = new HashMap<>();

    static {
        for (Map<String, Pair<String, EnumSet<IOStandard>>> m : EDIFNetlist.macroCollapseExceptionMap.values()) {
            for (Entry<String, Pair<String, EnumSet<IOStandard>>> e : m.entrySet()) {
                collapsedNames.put(e.getKey(), e.getValue().getFirst());
            }
        }
    }

    /** The netlist's primitives library, or null if it has none */
    private final EDIFLibrary prims;

    /** The cells of the primitives library that are left out */
    private final Set<EDIFCell> omitted = Collections.newSetFromMap(new IdentityHashMap<>());

    /** The name of each cell whose name differs in this view */
    private final Map<EDIFCell, String> names = new IdentityHashMap<>();

    /** The cell that an instance is of in this view, by its cell type, for those that differ */
    private final Map<EDIFCell, EDIFCell> instanceTypes = new IdentityHashMap<>();

    /**
     * Creates a view of the given netlist with its macros collapsed. This only looks at the cells
     * of its primitives library and the contents of its expanded macros, not at the rest of it.
     * @param netlist The netlist.
     */
    public EDIFCollapsedView(EDIFNetlist netlist) {
        prims = netlist.getLibrary(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
        if (prims != null) {
            // The number of instances of each primitive that are inside expanded macros
            Map<EDIFCell, Integer> instancesInMacros = new IdentityHashMap<>();
            for (EDIFCell cell : prims.getCells()) {
                if (!cell.isLeafCellOrBlackBox()) {
                    for (EDIFCellInst inst : cell.getCellInsts()) {
                        instancesInMacros.merge(inst.getCellType(), 1, Integer::sum);
                    }
                }
                String collapsedName = collapsedNames.get(cell.getName());
                if (collapsedName != null) {
                    names.put(cell, collapsedName);
                    EDIFCell primitive = prims.getCell(collapsedName);
                    if (primitive != null) {
                        instanceTypes.put(cell, primitive);
                        omitted.add(cell);
                    }
                }
            }

            // Leave out each primitive (with any macro that has its name in this view) whose every
            // instance is inside expanded macros. One that is not instantiated at all is kept, as
            // there is no telling where it came from
            Map<String, int[]> instanceCounts = new HashMap<>();
            for (EDIFCell cell : prims.getCells()) {
                int[] counts = instanceCounts.computeIfAbsent(getName(cell), (k) -> new int[2]);
                counts[0] += cell.getNonHierInstantiationCount();
                counts[1] += instancesInMacros.getOrDefault(cell, 0);
            }
            for (EDIFCell cell : prims.getCells()) {
                int[] counts = instanceCounts.get(getName(cell));
                if (counts[0] > 0 && counts[0] == counts[1]) {
                    omitted.add(cell);
                }
            }
        }

        Map<EDIFCell, EDIFCell> externalMappings = netlist.getExternalMappings();
        if (externalMappings != null) {
            for (Entry<EDIFCell, EDIFCell> e : externalMappings.entrySet()) {
                if (e.getKey() != null) {
                    instanceTypes.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    /**
     * Checks if the given cell is left out of this view.
     * @param cell A cell of the netlist.
     * @return True if the cell is left out, false otherwise.
     */
    public boolean isOmitted(EDIFCell cell) {
        return omitted.contains(cell);
    }

    /**
     * Checks if the given cell is a macro that is collapsed in this view, so has no contents in it.
     * @param cell A cell of the netlist.
     * @return True if the cell is collapsed, false otherwise.
     */
    public boolean isCollapsed(EDIFCell cell) {
        return prims != null && cell.getLibrary() == prims && !cell.isLeafCellOrBlackBox();
    }

    /**
     * Gets the name of the given cell in this view.
     * @param cell A cell of the netlist.
     * @return The name of the cell in this view.
     */
    public String getName(EDIFCell cell) {
        String name = names.isEmpty() ? null : names.get(cell);
        return name != null ? name : cell.getName();
    }

    /**
     * Gets the cell that an instance of the given cell is an instance of in this view, whose name
     * (see {@link #getName(EDIFCell)}) and library an instance refers to.
     * @param cellType The cell type of an instance in the netlist.
     * @return The cell that the instance is of in this view.
     */
    public EDIFCell getInstanceType(EDIFCell cellType) {
        EDIFCell instanceType = instanceTypes.isEmpty() ? null : instanceTypes.get(cellType);
        return instanceType != null ? instanceType : cellType;
    }

    /**
     * Gets the port of the given port instance in this view, which is the matching port of the cell
     * that its instance is of in this view (see {@link #getInstanceType(EDIFCell)}).
     * @param portInst A port instance in the netlist.
     * @return The port of the port instance in this view.
     */
    public EDIFPort getPort(EDIFPortInst portInst) {
        EDIFPort port = portInst.getPort();
        EDIFCellInst inst = portInst.getCellInst();
        if (inst == null || instanceTypes.isEmpty()) {
            return port;
        }
        EDIFCell instanceType = getInstanceType(inst.getCellType());
        if (instanceType == inst.getCellType()) {
            return port;
        }
        // Matched as EDIFCellInst.setCellType() does
        EDIFPort match = instanceType.getPort(port.getBusName(true));
        if (match == null || match.getWidth() != port.getWidth()) {
            match = instanceType.getPort(port.getName());
        }
        return match != null ? match : port;
    }

    /**
     * Gets the cells of the given library in this view, in an order that they can be written in
     * (see {@link EDIFLibrary#getValidCellExportOrder(boolean)}).
     * @param lib A library of the netlist.
     * @param stable If true, the order is always the same for the same library.
     * @return The cells of the library that are not left out of this view.
     */
    public List<EDIFCell> getValidCellExportOrder(EDIFLibrary lib, boolean stable) {
        List<EDIFCell> cells;
        if (instanceTypes.isEmpty()) {
            cells = lib.getValidCellExportOrder(stable);
        } else {
            // A cell must come after the cells that its instances are of in this view
            cells = EDIFNetlist.getValidCellExportOrder(lib.getCells(), stable, (c) -> c.getLibrary() == lib,
                    this::getInstanceType);
        }
        if (lib == prims && !omitted.isEmpty()) {
            cells.removeIf(this::isOmitted);
        }
        return cells;
    }
}
