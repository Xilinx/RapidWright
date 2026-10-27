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

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFCellInst;

/**
 * Look up tables: {@code LUT1} through {@code LUT6}, their local and dual
 * output variants, and the dual output {@code LUT6_2}.
 *
 * The INIT property is already a truth table in the form
 * {@link TruthTable} uses: bit {@code k} is the output when the inputs encode
 * {@code k} with {@code I0} as the least significant bit.
 *
 * Arity comes from the cell type name rather than from the width of INIT.
 * Inferring it from the INIT string would be wrong for a {@code LUT2} written
 * with a wider literal, and wrong for {@code LUT6_2} whose two outputs have
 * different widths.
 */
public class LutModel implements PrimitiveModel {

    /** Warning category for a look up table with no INIT property. */
    public static final String MISSING_INIT = "Look up table without INIT";

    private final LecLog log;

    public LutModel(LecLog log) {
        this.log = log;
    }

    /**
     * @return How many inputs the output pin sees. For {@code LUT6_2} the
     *         {@code O5} output is a five input function of the low half of
     *         INIT, whereas {@code O6} sees all six.
     */
    private static int arityOf(CellIndex index, int outPortBit) {
        String typeName = index.getCell().getName();
        String outPin = index.getPortBitName(outPortBit);
        if (typeName.equals("LUT6_2")) {
            return outPin.equals("O5") ? 5 : 6;
        }
        return Character.getNumericValue(typeName.charAt(3));
    }

    /**
     * The inputs the INIT actually depends on, which is not always all of them.
     *
     * Declaring all {@code arity} pins would be the lazy over-approximation
     * {@link PrimitiveModel} warns against, and it has teeth here rather than
     * being merely wasteful. Vivado routinely packs two unrelated functions into
     * one {@code LUT6_2}: the six input pins are shared, but {@code O5} may use
     * only the low two, so the <i>cell</i> can sit on a cycle while the
     * <i>pin</i> does not. Claiming the wider support makes the cone builder
     * follow an edge the function ignores and report a combinational loop that
     * cannot exist.
     *
     * The same reduction also shrinks cones, and keeps the source terminals a
     * difference reports honest, since a terminal the function cannot observe no
     * longer appears among them.
     */
    @Override
    public int[] supportOf(CellIndex index, EDIFCellInst inst, int outPortBit) {
        int arity = arityOf(index, outPortBit);
        int[] dependent = TruthTable.dependentVars(readInitQuietly(index, inst, arity), arity);
        String[] pins = new String[dependent.length];
        for (int i = 0; i < dependent.length; i++) {
            pins[i] = "I" + dependent[i];
        }
        return ModelUtils.bitsOf(index, pins);
    }

    @Override
    public int build(Aig aig, CellIndex index, EDIFCellInst inst, int outPortBit,
            int[] inputLits) {
        int arity = arityOf(index, outPortBit);
        long table = readInit(index, inst, arity);
        // inputLits arrives in the order supportOf declared, so the table has to
        // be renumbered onto that same reduced variable set.
        int[] dependent = TruthTable.dependentVars(table, arity);
        if (dependent.length == 0) {
            return (table & 1L) != 0 ? Aig.TRUE : Aig.FALSE;
        }
        return TruthTableSynth.synthesize(aig, TruthTable.project(table, dependent),
                dependent.length, inputLits);
    }

    /**
     * Reads the INIT property and truncates it to the width the output sees.
     * A {@code LUT6_2} carries a single 64 bit INIT whose low half is the
     * five input function.
     */
    private long readInit(CellIndex index, EDIFCellInst inst, int arity) {
        return readInit(index, inst, arity, true);
    }

    /**
     * The same read without the warning, for {@link #supportOf}.
     *
     * Both entry points need the INIT, but a defective one must be reported once
     * per cell rather than once per read, or the warning counts double.
     */
    private long readInitQuietly(CellIndex index, EDIFCellInst inst, int arity) {
        return readInit(index, inst, arity, false);
    }

    private long readInit(CellIndex index, EDIFCellInst inst, int arity, boolean warn) {
        String init = ModelUtils.property(inst, LUTTools.LUT_INIT, null);
        if (init == null) {
            if (warn) {
                log.warn(MISSING_INIT, index.getCell().getName(),
                        inst == null ? null : inst.getName());
            }
            return 0L;
        }
        long value;
        try {
            value = LUTTools.getInitValue(init);
        } catch (RuntimeException e) {
            if (warn) {
                log.warn(MISSING_INIT, index.getCell().getName() + " has unreadable INIT '" + init
                        + "'", inst == null ? null : inst.getName());
            }
            return 0L;
        }
        return value & TruthTable.widthMask(arity);
    }
}
