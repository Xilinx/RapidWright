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

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;

/**
 * Decides what role each cell type plays in cone construction: a terminal that
 * bounds a cone, or combinational logic inside one.
 *
 * Classification is by cell type name and is cached, so the cost is paid once
 * per distinct type rather than once per instance.
 *
 * The rule order matters. A type is combinational only if
 * {@link PrimitiveModelLibrary} models it; otherwise the terminal name rules
 * apply; otherwise it is uninterpreted and reported. Defaulting to opaque means
 * an unfamiliar cell degrades the verdict to inconclusive rather than silently
 * producing a wrong function.
 */
public class CellClassifier {

    /** Warning category listing black box cells with no functional model. */
    public static final String BLACK_BOX_TYPE = "Black box cell type";

    /**
     * Warning category listing hard block primitives treated as opaque
     * terminals. Informational rather than alarming: cutting a cone at a
     * transceiver or clock manager is correct behaviour.
     */
    public static final String OPAQUE_PRIMITIVE = "Opaque primitive";

    private final LecConfig config;

    private final Series series;

    private final LecLog log;

    private final Map<EDIFCell, CellRole> roleCache = new IdentityHashMap<>();

    public CellClassifier(Series series, LecConfig config, LecLog log) {
        this.config = config;
        this.log = log;
        this.series = series;
    }

    /**
     * @return The role of a cell type, computing and caching it on first use.
     */
    public CellRole classify(EDIFCell cell) {
        CellRole cached = roleCache.get(cell);
        if (cached != null) {
            return cached;
        }
        CellRole role = computeRole(cell, cell.getName());
        roleCache.put(cell, role);
        return role;
    }

    /**
     * The rule order is significant.
     *
     * Only a cell in {@code hdi_primitives} is a primitive at all. A leaf cell
     * <i>outside</i> that library is a black box, whatever it is called: a user
     * module named {@code FD_custom} or {@code LUT4} says nothing about its
     * contents. Among primitives, the well known terminal and combinational
     * types are handled first. Any other primitive is a device hard block: a
     * transceiver, a clock manager, an IO serialiser. Its identity is fixed by
     * the architecture, so its instance name corresponds one to one between the
     * two netlists exactly as it does for any other terminal, and treating it
     * as an opaque terminal is both sound and matchable.
     *
     * A black box is user or IP logic whose contents were not elaborated.
     * Nothing guarantees its name
     * corresponds, so its outputs become unmatchable variables and any cone
     * reaching one can only be reported inconclusive.
     *
     * This split also means a primitive introduced by a future architecture
     * degrades safely rather than being silently mistaken for combinational
     * logic.
     */
    private CellRole computeRole(EDIFCell cell, String name) {
        if (!isDevicePrimitive(cell)) {
            log.warn(BLACK_BOX_TYPE, name, null);
            return CellRole.UNINTERPRETED;
        }
        if (cell.isGNDSource()) {
            return CellRole.CONSTANT_GND;
        }
        if (cell.isVCCSource()) {
            return CellRole.CONSTANT_VCC;
        }
        if (PrimitiveModelLibrary.has(name)) {
            return CellRole.COMBINATIONAL;
        }
        if (isSequentialTerminal(name) || isMemoryTerminal(name)) {
            return CellRole.TERMINAL;
        }
        if (isDistributedRam(name)) {
            return config.isDistributedRamTerminal() ? CellRole.TERMINAL : CellRole.UNINTERPRETED;
        }
        if (isDsp(name)) {
            return CellRole.TERMINAL;
        }
        log.warn(OPAQUE_PRIMITIVE, name, null);
        return CellRole.TERMINAL;
    }

    /**
     * @return True if the cell is a device primitive, meaning it lives in the
     *         {@code hdi_primitives} library and therefore has an
     *         architecture-defined identity.
     */
    public static boolean isDevicePrimitive(EDIFCell cell) {
        return cell.getLibrary() != null && cell.getLibrary().isHDIPrimitivesLibrary();
    }

    /**
     * Flip flops, latches and shift register look up tables. {@code CFGLUT5} is
     * included: despite the name it is not a static look up table, because its
     * contents are shifted at run time, so its outputs cannot be a function of
     * its inputs alone.
     */
    private static boolean isSequentialTerminal(String name) {
        if (name.startsWith("FD") || name.startsWith("LD") || name.startsWith("SRL")) {
            return true;
        }
        return name.equals("CFGLUT5");
    }

    /** Block RAM, ultra RAM and the FIFO primitives built on them. */
    private static boolean isMemoryTerminal(String name) {
        return name.startsWith("RAMB") || name.startsWith("URAM") || name.startsWith("FIFO");
    }

    /**
     * Distributed RAM, whose treatment is configurable. The names cover the
     * user facing macros ({@code RAM64X1D}) and the primitives they decompose
     * into ({@code RAMD64E}, {@code RAMS32}).
     */
    public static boolean isDistributedRam(String name) {
        if (!name.startsWith("RAM")) {
            return false;
        }
        return name.startsWith("RAMD") || name.startsWith("RAMS") || name.startsWith("RAM32")
                || name.startsWith("RAM64") || name.startsWith("RAM128") || name.startsWith("RAM256")
                || name.startsWith("RAM512");
    }

    /** DSP slices, opaque in this release. */
    public static boolean isDsp(String name) {
        return name.startsWith("DSP");
    }

    /**
     * @return The pin name with any bus index removed, so {@code "ADDRA[3]"}
     *         becomes {@code "ADDRA"}.
     */
    public static String stripBusIndex(String pinName) {
        int bracket = pinName.lastIndexOf('[');
        if (bracket < 0 || !pinName.endsWith("]")) {
            return pinName;
        }
        return pinName.substring(0, bracket);
    }

    /** @return The series the netlist targets, or null if it names none. */
    public Series getSeries() {
        return series;
    }

    /**
     * @return Every cell type classified so far, by library qualified name,
     *         mapped to its role.
     */
    public Map<String, CellRole> getClassifiedTypes() {
        Map<String, CellRole> types = new HashMap<>();
        for (Map.Entry<EDIFCell, CellRole> e : roleCache.entrySet()) {
            EDIFCell cell = e.getKey();
            String library = cell.getLibrary() == null ? "" : cell.getLibrary().getName() + ".";
            types.put(library + cell.getName(), e.getValue());
        }
        return types;
    }
}
