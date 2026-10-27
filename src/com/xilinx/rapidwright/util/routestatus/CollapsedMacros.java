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

package com.xilinx.rapidwright.util.routestatus;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Expands the macro primitives (e.g. RAM64X1D) that are collapsed into leaf cells in a netlist
 * while cells of their expansion are placed, as {@link Design#writeCheckpoint(String)} leaves them,
 * and collapses them again afterwards.
 * <p>
 * Unlike {@link EDIFNetlist#expandMacroUnisims(Series)} and
 * {@link EDIFNetlist#collapseMacroUnisims(Series)}, which recreate every macro of the netlist from
 * its template and leave them all collapsed, only the collapsed cells are expanded, in place (their
 * instances are unchanged), and the cells their expansion needs are added to the library: undoing
 * this leaves the netlist as it was found, including the macros that were expanded and the
 * identity of the library's cells.
 */
class CollapsedMacros {

    private final EDIFLibrary prims;

    private final EDIFLibrary macros;

    /** The collapsed macro cells of the netlist, with their templates */
    private final Map<EDIFCell, EDIFCell> collapsed = new IdentityHashMap<>();

    /** The cells expanded in place */
    private final List<EDIFCell> expanded = new ArrayList<>();

    /** The cells added to the library for the expansion */
    private final List<EDIFCell> added = new ArrayList<>();

    private CollapsedMacros(EDIFLibrary prims, EDIFLibrary macros) {
        this.prims = prims;
        this.macros = macros;
    }

    /**
     * Expands the collapsed macro cells of a design's netlist if cells of their expansion are placed.
     * @return What to collapse again with {@link #collapse()}, or null if nothing was expanded.
     */
    static CollapsedMacros expand(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFLibrary prims = netlist.getHDIPrimitivesLibrary();
        Series series = design.getSeries();
        if (prims == null || series == null) {
            return null;
        }
        CollapsedMacros macros = new CollapsedMacros(prims, Design.getMacroPrimitives(series));
        for (EDIFCell cell : prims.getCells()) {
            EDIFCell macro = macros.macros.getCell(cell.getName());
            if (macro != null && cell.isLeafCellOrBlackBox() && !macro.isLeafCellOrBlackBox()) {
                macros.collapsed.put(cell, macro);
            }
        }
        if (macros.collapsed.isEmpty() || !macros.hasPlacedExpansion(design)) {
            return null;
        }
        boolean tracking = netlist.isTrackingCellChanges();
        netlist.setTrackCellChanges(false);
        try {
            // (a nested macro, e.g. DSP58C in DSP58, is expanded when the library has it collapsed,
            // or added expanded)
            for (Map.Entry<EDIFCell, EDIFCell> e : macros.collapsed.entrySet()) {
                macros.expanded.add(e.getKey());
                macros.populate(e.getKey(), e.getValue());
            }
        } catch (RuntimeException e) {
            macros.collapse();
            throw e;
        } finally {
            netlist.setTrackCellChanges(tracking);
        }
        return macros;
    }

    /**
     * Checks whether cells are placed below an instance of a collapsed macro cell.
     */
    private boolean hasPlacedExpansion(Design design) {
        EDIFNetlist netlist = design.getNetlist();
        for (Cell cell : design.getCells()) {
            if (cell.isRoutethru()) {
                continue;
            }
            // The closest instance above the cell in the netlist (macros may be nested)
            String name = cell.getName();
            for (int sep = name.lastIndexOf(EDIFTools.EDIF_HIER_SEP); sep > 0;
                    sep = name.lastIndexOf(EDIFTools.EDIF_HIER_SEP, sep - 1)) {
                EDIFHierCellInst parent = netlist.getHierCellInstFromName(name.substring(0, sep));
                if (parent != null) {
                    if (collapsed.containsKey(parent.getCellType())) {
                        return true;
                    }
                    break;
                }
            }
        }
        return false;
    }

    /**
     * Collapses the expanded cells again and removes the cells added to the library.
     */
    void collapse() {
        EDIFNetlist netlist = prims.getNetlist();
        boolean tracking = netlist.isTrackingCellChanges();
        netlist.setTrackCellChanges(false);
        try {
            for (EDIFCell cell : expanded) {
                cell.makePrimitive();
            }
            for (EDIFCell cell : added) {
                cell.makePrimitive();
                prims.removeCell(cell);
            }
            expanded.clear();
            added.clear();
            // (the parent net map may have been built while expanded)
            netlist.resetParentNetMap();
        } finally {
            netlist.setTrackCellChanges(tracking);
        }
    }

    /**
     * Adds the instances and nets of a template to a cell.
     */
    private void populate(EDIFCell cell, EDIFCell template) {
        for (EDIFCellInst templateInst : template.getCellInsts()) {
            EDIFCellInst inst = cell.createChildCellInst(templateInst.getName(),
                    getPrimitive(templateInst.getCellType()));
            inst.setPropertiesMap(templateInst.createDuplicatePropertiesMap());
        }
        for (EDIFNet templateNet : template.getNets()) {
            EDIFNet net = cell.createNet(templateNet.getName());
            for (EDIFPortInst templatePortInst : templateNet.getPortInsts()) {
                EDIFCellInst templateInst = templatePortInst.getCellInst();
                EDIFCellInst inst = templateInst == null ? null : cell.getCellInst(templateInst.getName());
                EDIFPort port = getPort(inst == null ? cell : inst.getCellType(), templatePortInst.getPort());
                net.createPortInst(port, templatePortInst.getIndex(), inst);
            }
        }
    }

    /**
     * Gets the cell of the library a template's instance refers to, adding it (expanded, if a
     * macro) if missing.
     */
    private EDIFCell getPrimitive(EDIFCell type) {
        EDIFCell cell = prims.getCell(type.getName());
        if (cell == null) {
            cell = new EDIFCell(prims, type, type.getName(), false);
            added.add(cell);
            populate(cell, type);
        }
        return cell;
    }

    private static EDIFPort getPort(EDIFCell cell, EDIFPort templatePort) {
        EDIFPort port = cell.getPort(templatePort.getBusName(true));
        if (port == null || port.getWidth() != templatePort.getWidth()) {
            port = cell.getPort(templatePort.getName());
        }
        return port;
    }
}
