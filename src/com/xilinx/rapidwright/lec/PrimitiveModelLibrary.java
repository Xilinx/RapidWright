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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The set of primitive types whose Boolean function this tool models, and hence
 * the set of types that form the interior of a cone.
 *
 * Membership here is the authoritative definition of
 * {@link CellRole#COMBINATIONAL}. Anything absent is either a terminal or an
 * uninterpreted function; see {@link CellClassifier}. Treating the list as a
 * whitelist rather than a blacklist is the fail safe direction, because an
 * unrecognised cell that is wrongly assumed combinational would silently
 * produce a wrong function, whereas one wrongly assumed opaque produces at
 * worst an inconclusive cone.
 *
 * <h2>A note on the Versal carry look up tables</h2>
 *
 * The Versal carry LUT is {@code LUT6CY}, whose five input halves appear as
 * {@code LUTCY1} and {@code LUTCY2} only after macro expansion. All three are
 * registered here.
 */
public class PrimitiveModelLibrary {

    private static final Set<String> COMBINATIONAL = buildCombinationalTypes();

    private static final Set<String> CONDITIONAL = buildConditionalTypes();

    private final Map<String, PrimitiveModel> models = new HashMap<>();

    private final LecLog log;

    public PrimitiveModelLibrary(LecLog log) {
        this.log = log;
    }

    /**
     * @return The model for a cell type, or null if the type has no model and
     *         must therefore be treated as an opaque cone endpoint.
     */
    public PrimitiveModel get(String cellTypeName) {
        if (!COMBINATIONAL.contains(cellTypeName) && !CONDITIONAL.contains(cellTypeName)) {
            return null;
        }
        PrimitiveModel cached = models.get(cellTypeName);
        if (cached == null) {
            cached = create(cellTypeName);
            models.put(cellTypeName, cached);
        }
        return cached;
    }

    private PrimitiveModel create(String cellTypeName) {
        if (cellTypeName.equals("CARRY8")) {
            return new Carry8Model();
        }
        if (cellTypeName.equals("CARRY4")) {
            return new Carry4Model();
        }
        if (cellTypeName.equals("LOOKAHEAD8")) {
            return new Lookahead8Model();
        }
        if (cellTypeName.equals("LUT6CY") || cellTypeName.equals("LUTCY1")
                || cellTypeName.equals("LUTCY2")) {
            return new Lut6cyModel(log);
        }
        if (cellTypeName.startsWith("LUT")) {
            return new LutModel(log);
        }
        PrimitiveModel simple = SimpleGateModel.forType(cellTypeName);
        if (simple != null) {
            return simple;
        }
        throw new LecException("Type '" + cellTypeName + "' is listed as combinational but has "
                + "no model");
    }

    private static Set<String> buildCombinationalTypes() {
        Set<String> types = new HashSet<>();
        addLuts(types);
        addMuxes(types);
        addCarry(types);
        addSoftGates(types);
        addBuffers(types);
        addClockBuffers(types);
        return Collections.unmodifiableSet(types);
    }

    /**
     * Global clock buffers with no control input, which are transparent:
     * {@code O = I}, or {@code O = !I} when {@code IS_I_INVERTED} is set.
     *
     * A placer inserts these on high fanout nets such as clock enables and
     * resets, to reach the global routing, and may replicate one into several
     * regional copies that each drive a subset of the loads. Treating them as
     * terminals would make every such insertion a terminal difference, and
     * would also stop every clock pin's cone one cell short of the clock port.
     *
     * The assumptions are those of the whole check, which compares logic
     * functions after configuration: a buffer's delay, its skew between
     * replicas and its routing resources are timing, not function, and are not
     * compared. {@code BUFG} has no control input at all; {@code BUFG_FABRIC}
     * neither. In the vendor simulation models both are plain buffers with no
     * gate, so the start-up assumption of the gated buffers below does not
     * arise for them.
     */
    private static void addClockBuffers(Set<String> types) {
        types.add("BUFG");
        types.add("BUFG_FABRIC");
    }

    /**
     * Clock buffers with control inputs, which are transparent only when their
     * settings and controls make them pass their input through, and are
     * otherwise terminals. These types are therefore not combinational by
     * type: {@link #get(String)} returns a model for them, and the role that
     * {@link CellClassifier#classify(FlatNetlist, Connectivity, int)} gives
     * each instance decides whether it is used.
     *
     * <ul>
     * <li>{@code BUFGCE}: {@code I} not tied to a constant, {@code CE} tied
     * active, {@code CE_TYPE} absent, {@code SYNC} or {@code ASYNC}, and
     * {@code STARTUP_SYNC} absent or {@code FALSE}.</li>
     * <li>{@code BUFGCE_DIV}: the same, and also {@code CLR} tied inactive,
     * {@code HARDSYNC_CLR} absent or {@code FALSE}, and {@code BUFGCE_DIVIDE}
     * absent or 1. A divider by any other value is not a buffer: its output
     * toggles once every so many input edges.</li>
     * </ul>
     *
     * Assumptions behind these conditions:
     * <ul>
     * <li>"Tied" is decided from the netlist alone: the pin's node is a
     * constant, after the pin's inversion attribute ({@code IS_CE_INVERTED},
     * {@code IS_CLR_INVERTED}), or the pin has no driver, no inversion
     * attribute, and a tie value listed in the pin default table for the part
     * that is the pass through value. A control driven by logic is never taken
     * as tied, even when that logic is constant. An unconnected control
     * without a part, without a table entry, or with an inversion attribute
     * keeps the buffer a terminal, since what it is tied to is then not
     * known.</li>
     * <li>{@code CE_TYPE HARDSYNC} and {@code HARDSYNC_CLR TRUE} add a three
     * stage synchroniser that starts with the buffer disabled or cleared, so
     * even with constant controls the output misses the first input edges
     * after configuration. Such a buffer, or one with any other value, stays a
     * terminal.</li>
     * <li>{@code STARTUP_SYNC TRUE} holds the output until the buffer has
     * synchronised to the end of configuration, which an unbuffered net does
     * not do, so such a buffer stays a terminal.</li>
     * <li>After configuration the gate is closed, and it opens only once
     * {@code I} has been low: always for {@code BUFGCE_DIV}, and for
     * {@code BUFGCE} with the default {@code SYNC} enable, as in the vendor
     * simulation models. A buffer whose {@code I} never goes low therefore
     * drives a constant low. The check rules out the cases it can see: a
     * buffer whose {@code I} is tied to a constant stays a terminal, and one
     * whose {@code I} cone is constantly high gets an unknown output, which
     * leaves its loads unproven. For any other {@code I} it assumes that
     * {@code I} is low at some point after configuration, and compares the
     * steady state that follows, in which the buffer is combinationally
     * transparent. That is not proven: an {@code I} driven by a register that
     * keeps an initial value of 1, or by a port held high, breaks it. The
     * vendor tool makes the same assumption when its own optimisation inserts
     * such buffers on device-wide control nets.</li>
     * <li>{@code SIM_DEVICE} and other simulation-only attributes have no
     * effect on the function.</li>
     * </ul>
     *
     * A gated buffer is not a combinational gate even then: a synchronous
     * enable is sampled by the clock it gates, so a dynamic {@code CE} cannot be
     * modelled as {@code I & CE}, which would let a glitching gate be proven
     * equal to a glitch free one.
     *
     * Every other clock buffer stays an opaque terminal matched by name, as
     * before: {@code BUFGCE_1} (its output idles high when disabled),
     * {@code BUFGCTRL} and the {@code BUFGMUX} family (clock multiplexers),
     * {@code BUFG_GT} (divide value on a dynamic pin bus with masked enable
     * and clear; driven only by a transceiver) and its enable and clear
     * synchroniser {@code BUFG_GT_SYNC},
     * {@code BUFG_PS} (driven only by the processing system), and the
     * {@code MBUFG} family (several outputs at different divisions). None of
     * them is provably {@code O = I} from the netlist under the rules above.
     */
    private static Set<String> buildConditionalTypes() {
        Set<String> types = new HashSet<>();
        types.add("BUFGCE");
        types.add("BUFGCE_DIV");
        return Collections.unmodifiableSet(types);
    }

    /**
     * @param cellTypeName A primitive type name.
     * @return True if the type is transparent only when its control inputs are
     *         tied to their pass through values, so it is classified per
     *         instance.
     */
    public static boolean isConditional(String cellTypeName) {
        return CONDITIONAL.contains(cellTypeName);
    }

    /**
     * Input and output buffers, which are transparent to logic.
     *
     * Modelling these matters more than it looks. Top level
     * design ports are source terminals, but in a design with IO buffers every path
     * to a top level port runs through one. If a buffer were opaque, no cone
     * would ever reach a primary input and every such comparison would be made
     * against a buffer output instead.
     *
     * Only buffers with unambiguous two valued behaviour are listed. Tristate
     * and disable-capable variants ({@code OBUFT}, {@code IOBUF},
     * {@code IBUF_IBUFDISABLE}) are deliberately excluded, because their output
     * can be high impedance, which an and-inverter graph cannot represent; they
     * fall through to being terminals instead.
     *
     * Differential receivers (IBUFDS and friends) are not modelled: their output
     * follows {@code I} only while {@code IB} is its complement, which nothing
     * here proves. As terminals, both of their inputs are checked.
     */
    private static void addBuffers(Set<String> types) {
        types.add("IBUF");
        types.add("IBUFG");
        types.add("OBUF");
        types.add("OBUFDS");
        // (what RapidWright's readers turn an OBUFDS into for some I/O standards)
        types.add("OBUFDS_DUAL_BUF");
    }

    /**
     * Look up table primitives, including the local and dual output variants
     * Vivado emits and the Versal carry LUTs.
     */
    private static void addLuts(Set<String> types) {
        for (int size = 1; size <= 6; size++) {
            String base = "LUT" + size;
            types.add(base);
            types.add(base + "_L");
            types.add(base + "_D");
        }
        types.add("LUT6_2");
        types.add("LUT6CY");
        types.add("LUTCY1");
        types.add("LUTCY2");
    }

    /** Wide function multiplexers, which stitch look up tables together. */
    private static void addMuxes(Set<String> types) {
        for (int size = 5; size <= 9; size++) {
            types.add("MUXF" + size);
        }
        for (int size = 5; size <= 8; size++) {
            types.add("MUXF" + size + "_L");
            types.add("MUXF" + size + "_D");
        }
    }

    /**
     * Every carry primitive. These are the cells whose
     * per output pin support has to be exact, otherwise a carry chain looks like
     * a combinational loop.
     */
    private static void addCarry(Set<String> types) {
        types.add("CARRY4");
        types.add("CARRY8");
        types.add("LOOKAHEAD8");
        types.add("MUXCY");
        types.add("MUXCY_L");
        types.add("MUXCY_D");
        types.add("XORCY");
        types.add("XORCY_L");
        types.add("XORCY_D");
    }

    /**
     * Soft logic gates and the two specialised gates
     * {@code AND2B1L} and {@code OR2L}, whose second input is a set or reset
     * input with an inversion attribute.
     */
    private static void addSoftGates(Set<String> types) {
        types.add("INV");
        types.add("BUF");
        types.add("AND2B1L");
        types.add("OR2L");
        String[] families = { "AND", "OR", "NAND", "NOR", "XOR", "XNOR" };
        for (String family : families) {
            for (int inputs = 2; inputs <= 5; inputs++) {
                types.add(family + inputs);
                for (int inverted = 1; inverted < inputs; inverted++) {
                    types.add(family + inputs + "B" + inverted);
                }
            }
        }
    }

    /**
     * @param cellTypeName A primitive type name.
     * @return True if this tool models the type's Boolean function, making it
     *         the interior of a cone rather than an endpoint.
     */
    public static boolean has(String cellTypeName) {
        return COMBINATIONAL.contains(cellTypeName);
    }

    /** @return Every modelled combinational type name, sorted, for reporting. */
    public static Set<String> getCombinationalTypes() {
        return new TreeSet<>(COMBINATIONAL);
    }
}
