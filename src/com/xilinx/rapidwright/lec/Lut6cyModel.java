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
 * The Versal carry look up tables {@code LUT6CY}, {@code LUTCY1} and
 * {@code LUTCY2}.
 *
 * Transcribed from the golden simulation models:
 *
 * <pre>
 * LUT6CY   O51  = INIT[{1'b0, I4, I3, I2, I1, I0}]      INIT bits 0..31
 *          O52  = INIT[{1'b1, I4, I3, I2, I1, I0}]      INIT bits 32..63
 *          PROP = INIT[{1'b0, 1'b0, I3, I2, I1, I0}]    INIT bits 0..15
 *          GE   is declared but never driven
 * LUTCY1   O    = INIT[{I4, I3, I2, I1, I0}]
 *          PROP = INIT[{1'b0, I3, I2, I1, I0}]
 * LUTCY2   O    = INIT[{I4, I3, I2, I1, I0}]
 *          GE   is declared but never driven
 * </pre>
 *
 * The load bearing detail is that {@code PROP} is a function of {@code I0}
 * through {@code I3} only. Because it excludes {@code I4}, which is where the
 * carry arrives, a Versal carry chain is acyclic at bit level even though the
 * cells form a cycle. Widening this support would reintroduce false
 * combinational loops.
 *
 * {@code GE} is never assigned in the golden model, so it has no function to
 * model. Nothing consumes it either: {@code LOOKAHEAD8} ignores its own
 * {@code GE} inputs. Should a checked cone reach one anyway, it is an
 * unmodelled output, so that cone is inconclusive rather than proven against a
 * guessed constant.
 */
public class Lut6cyModel implements PrimitiveModel {

    private final LecLog log;

    public Lut6cyModel(LecLog log) {
        this.log = log;
    }

    @Override
    public int[] supportOf(CellIndex index, EDIFCellInst inst, int outPortBit) {
        String outPin = index.getPortBitName(outPortBit);
        if (outPin.equals("GE")) {
            return null;
        }
        // As for a plain look up table, an input the INIT ignores is not support,
        // or a packed half can appear to sit on a loop it is not part of.
        int[] dependent = TruthTable.dependentVars(tableOf(index, inst, outPin, false),
                arityOf(outPin));
        String[] pins = new String[dependent.length];
        for (int i = 0; i < dependent.length; i++) {
            pins[i] = "I" + dependent[i];
        }
        return ModelUtils.bitsOf(index, pins);
    }

    @Override
    public int build(Aig aig, CellIndex index, EDIFCellInst inst, int outPortBit,
            int[] inputLits) {
        String outPin = index.getPortBitName(outPortBit);
        long table = tableOf(index, inst, outPin, true);
        // inputLits arrives in the reduced order supportOf declared.
        int[] dependent = TruthTable.dependentVars(table, arityOf(outPin));
        if (dependent.length == 0) {
            return (table & 1L) != 0 ? Aig.TRUE : Aig.FALSE;
        }
        return TruthTableSynth.synthesize(aig, TruthTable.project(table, dependent),
                dependent.length, inputLits);
    }

    private static int arityOf(String outPin) {
        return outPin.equals("PROP") ? 4 : 5;
    }

    /** @return The slice of the INIT that one output computes. */
    private long tableOf(CellIndex index, EDIFCellInst inst, String outPin, boolean warn) {
        long init = readInit(index, inst, warn);
        if (outPin.equals("PROP")) {
            return init & 0xFFFFL;
        }
        if (outPin.equals("O52")) {
            return init >>> 32;
        }
        return init & 0xFFFFFFFFL;
    }

    private long readInit(CellIndex index, EDIFCellInst inst, boolean warn) {
        String init = ModelUtils.property(inst, LUTTools.LUT_INIT, null);
        if (init == null) {
            if (warn) {
                log.warn(LutModel.MISSING_INIT, index.getCell().getName(),
                        inst == null ? null : inst.getName());
            }
            return 0L;
        }
        try {
            return LUTTools.getInitValue(init);
        } catch (RuntimeException e) {
            throw new LecException(index.getCell().getName() + " " + inst.getName()
                    + " has unreadable INIT '" + init + "'");
        }
    }
}
