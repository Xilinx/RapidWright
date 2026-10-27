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
        if (!COMBINATIONAL.contains(cellTypeName)) {
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
        return Collections.unmodifiableSet(types);
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
     * For the differential receivers, the golden model drives the output from
     * {@code I} whenever the pair is valid, that is whenever {@code IB} is the
     * complement of {@code I}; the degenerate cases are undefined rather than
     * meaningful. The model used here is therefore {@code O = I}.
     */
    private static void addBuffers(Set<String> types) {
        types.add("IBUF");
        types.add("IBUFG");
        types.add("OBUF");
        types.add("IBUFDS");
        types.add("IBUFGDS");
        types.add("IBUFDS_DIFF_OUT");
        types.add("OBUFDS");
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
