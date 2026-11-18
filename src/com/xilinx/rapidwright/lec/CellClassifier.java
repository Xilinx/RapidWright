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

import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;

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

    /**
     * Warning category listing clock buffers kept as terminals because
     * a control input or a setting does not make them pass their input through.
     */
    public static final String CLOCK_BUFFER_KEPT = "Clock buffer kept as a terminal";

    private final LecConfig config;

    private final Series series;

    private final LecLog log;

    private final Map<EDIFCell, CellRole> roleCache = new IdentityHashMap<>();

    /** Roles of conditionally transparent instances, by instance identifier. */
    private final Map<Integer, CellRole> instanceRoleCache = new HashMap<>();

    /** The netlist {@link #instanceRoleCache} describes. */
    private FlatNetlist instanceRoleNetlist;

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
     * The role of one instance. The same as {@link #classify(EDIFCell)} except
     * for the conditionally transparent clock buffers, see
     * {@link PrimitiveModelLibrary#isConditional(String)}, whose role depends
     * on the instance's settings and on how its control inputs are tied:
     * combinational when every one of them makes the buffer pass its input
     * through, and otherwise a terminal, as any other clock buffer was, with a
     * warning naming the reason.
     *
     * A control counts as tied only if its node is a constant, or the pin is
     * unconnected (or its net has no driver), has no inversion attribute set,
     * and the pin default table, the one cone construction uses, lists a tie
     * value for it on the netlist's series that is the pass through value. A
     * control computed by logic that happens to be constant is not tied.
     * Without a recognised part, for a pin the table does not list, or for an
     * unconnected pin with an inversion attribute, what the pin is tied to is
     * not known, and the buffer stays a terminal.
     */
    public CellRole classify(FlatNetlist flat, Connectivity conn, int instId) {
        CellRole role = classify(flat.getCellType(instId));
        if (role != CellRole.TERMINAL
                || !PrimitiveModelLibrary.isConditional(flat.getCellTypeName(instId))) {
            return role;
        }
        if (instanceRoleNetlist != flat) {
            instanceRoleCache.clear();
            instanceRoleNetlist = flat;
        }
        CellRole cached = instanceRoleCache.get(instId);
        if (cached == null) {
            String reason = whyNotTransparent(flat, conn, instId);
            if (reason == null) {
                cached = CellRole.COMBINATIONAL;
            } else {
                log.warn(CLOCK_BUFFER_KEPT, flat.getCellTypeName(instId) + " " + reason,
                        flat.getInstName(instId));
                cached = CellRole.TERMINAL;
            }
            instanceRoleCache.put(instId, cached);
        }
        return cached;
    }

    /**
     * The conditions under which a conditionally transparent clock buffer is
     * {@code O = I}; see {@link PrimitiveModelLibrary} for the assumptions.
     * Every condition must be proven from the netlist itself. An attribute
     * that is absent takes its primitive default; one that is present but not
     * recognised as the pass through setting keeps the buffer a terminal.
     *
     * @return Null if a conditionally transparent clock buffer passes its
     *         input through, otherwise why not.
     */
    private String whyNotTransparent(FlatNetlist flat, Connectivity conn, int instId) {
        String type = flat.getCellTypeName(instId);
        EDIFCellInst inst = flat.getInst(instId);
        boolean divider = type.equals("BUFGCE_DIV");
        // HARDSYNC puts a three stage synchroniser on the enable, which starts
        // cleared: the output stays low for the first input edges even with
        // CE constantly active, so the buffer delays the first pulses of a
        // reset or an enable it carries.
        String ceType = unquoted(ModelUtils.property(inst, "CE_TYPE", "SYNC"));
        if (!ceType.equalsIgnoreCase("SYNC") && !ceType.equalsIgnoreCase("ASYNC")) {
            return "with CE_TYPE other than SYNC or ASYNC";
        }
        // A buffer that synchronises its start to the end of configuration
        // holds its output for some cycles that an unbuffered net does not.
        if (!isFalse(inst, "STARTUP_SYNC")) {
            return "with STARTUP_SYNC other than FALSE";
        }
        if (divider) {
            // The clear's synchroniser starts asserted, with the same effect
            // as a HARDSYNC enable.
            if (!isFalse(inst, "HARDSYNC_CLR")) {
                return "with HARDSYNC_CLR other than FALSE";
            }
            if (!unquoted(ModelUtils.property(inst, "BUFGCE_DIVIDE", "1")).equals("1")) {
                return "with BUFGCE_DIVIDE other than 1";
            }
        }
        // An inversion attribute that cannot be read keeps the buffer a
        // terminal, as it was before buffers were modelled, rather than
        // failing classification; the value is then refused where the
        // terminal's pins are read, as for any primitive terminal.
        for (String pin : divider ? new String[] { "I", "CE", "CLR" }
                : new String[] { "I", "CE" }) {
            try {
                ModelUtils.isPinInverted(inst, pin);
            } catch (LecException e) {
                String key = "IS_" + pin + "_INVERTED";
                return "whose " + key + " value '" + ModelUtils.property(inst, key, "")
                        + "' cannot be read";
            }
        }
        // The gate opens only once I has been low after configuration, so a
        // buffer whose I is tied high never passes it. Any tied I keeps the
        // buffer the terminal it was before buffers were modelled.
        if (isTied(flat, conn, instId, "I")) {
            return "whose I is tied to a constant";
        }
        if (!isTiedTo(flat, conn, instId, "CE", true)) {
            return "whose CE is not tied active";
        }
        if (divider && !isTiedTo(flat, conn, instId, "CLR", false)) {
            return "whose CLR is not tied inactive";
        }
        return null;
    }

    /** @return True if the attribute is absent or reads FALSE. */
    private static boolean isFalse(EDIFCellInst inst, String key) {
        return unquoted(ModelUtils.property(inst, key, "FALSE")).equalsIgnoreCase("FALSE");
    }

    /** @return The attribute value without surrounding white space or quotes. */
    private static String unquoted(String value) {
        String v = value.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1).trim();
        }
        return v;
    }

    /**
     * @return True if the pin is missing, unconnected, on a net with no
     *         driver, or on a constant node.
     */
    private static boolean isTied(FlatNetlist flat, Connectivity conn, int instId,
            String pinName) {
        int bit = flat.getIndex(instId).portBitIndexOfPinName(pinName);
        if (bit < 0) {
            return true;
        }
        int pin = flat.getGlobalPin(instId, bit);
        int root = pin < 0 ? -1 : conn.getPinRoot(pin);
        if (root < 0) {
            return true;
        }
        byte kind = conn.getRootKind(root);
        return kind == Connectivity.CONST0 || kind == Connectivity.CONST1
                || kind == Connectivity.UNDRIVEN;
    }

    /**
     * @return True if the pin's effective value, after its inversion
     *         attribute, is provably the given constant; see
     *         {@link #classify(FlatNetlist, Connectivity, int)}.
     */
    private boolean isTiedTo(FlatNetlist flat, Connectivity conn, int instId, String pinName,
            boolean value) {
        CellIndex index = flat.getIndex(instId);
        int bit = index.portBitIndexOfPinName(pinName);
        if (bit < 0) {
            return false;
        }
        int pin = flat.getGlobalPin(instId, bit);
        int root = pin < 0 ? -1 : conn.getPinRoot(pin);
        byte kind = root < 0 ? Connectivity.UNDRIVEN : conn.getRootKind(root);
        boolean inverted = ModelUtils.isPinInverted(flat.getInst(instId), pinName);
        boolean high;
        if (kind == Connectivity.CONST0 || kind == Connectivity.CONST1) {
            high = kind == Connectivity.CONST1;
        } else if (kind == Connectivity.UNDRIVEN && !inverted) {
            NetType tie = ConeBuilder.listedPinDefault(series, flat.getCellTypeName(instId),
                    pinName);
            if (tie != NetType.VCC && tie != NetType.GND) {
                return false;
            }
            high = tie == NetType.VCC;
        } else {
            return false;
        }
        return (high ^ inverted) == value;
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
     * A black box is user or IP logic whose contents were not elaborated. By
     * default it is nevertheless matched like a terminal, see
     * {@link #isMatchedByName(CellRole)}. With {@code --strict-black-boxes},
     * nothing guarantees its name corresponds, so its outputs become
     * unmatchable variables and any cone reaching one can only be reported
     * inconclusive.
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
        if (PrimitiveModelLibrary.isConditional(name)) {
            // Refined per instance, which warns when one stays a terminal.
            return CellRole.TERMINAL;
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
     * @return True if cells of this role are matched between the two netlists
     *         by instance name, with their inputs checked as cones and their
     *         outputs shared variables: terminals, and black boxes unless
     *         {@code --strict-black-boxes} is given. A matched black box rests on
     *         the usual equivalence checking assumption that two black boxes of
     *         the same type and properties compute the same function.
     */
    public boolean isMatchedByName(CellRole role) {
        return role == CellRole.TERMINAL
                || (role == CellRole.UNINTERPRETED && !config.isStrictBlackBoxes());
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
