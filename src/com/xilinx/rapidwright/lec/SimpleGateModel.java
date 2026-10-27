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

import com.xilinx.rapidwright.edif.EDIFCellInst;

/**
 * The primitives whose function is fixed by their type name: multiplexers,
 * carry multiplexers and exclusive ors, inverters, buffers, the soft logic gate
 * family, and the two set and reset gates {@code AND2B1L} and {@code OR2L}.
 *
 * <h2>Soft gates</h2>
 *
 * The soft gate family is {@code FAMILY<n>} optionally followed by
 * {@code B<m>}, where the suffix inverts the <i>first</i> {@code m} inputs. The
 * retarget models define each one as a look up table, which pins the convention
 * down exactly: {@code AND2B1} is {@code LUT2} with INIT {@code 4'h4}, that is
 * {@code O = !I0 & I1}, and {@code AND3B2} is {@code LUT3} with INIT
 * {@code 8'h10}, that is {@code O = !I0 & !I1 & I2}.
 *
 * <h2>Set and reset gates</h2>
 *
 * {@code AND2B1L} and {@code OR2L} both carry an {@code IS_SRI_INVERTED}
 * attribute on their second input, and they are not symmetric with each other:
 *
 * <pre>{@code
 * AND2B1L  O = DI & !(SRI ^ IS_SRI_INVERTED)
 * OR2L     O = DI | (SRI ^ IS_SRI_INVERTED)
 * }</pre>
 *
 * The extra negation on {@code AND2B1L} is in the golden model, not a
 * transcription slip.
 *
 * <h2>Buffers</h2>
 *
 * Input and output buffers are transparent. Modelling them is not optional:
 * top level ports are source terminals, but in a design with IO
 * buffers every path to a port runs through one, so an opaque buffer would stop
 * every cone one cell short of the port. The differential receivers drive their
 * output from {@code I} whenever the pair is valid, which is the only case with
 * a defined value, so {@code O = I}.
 */
public class SimpleGateModel implements PrimitiveModel {

    /** How the output of a gate is derived from its inputs. */
    private enum Kind {
        /** Two input multiplexer: {@code O = S ? I1 : I0}. */
        MUXF,
        /** Carry multiplexer: {@code O = S ? CI : DI}. */
        MUXCY,
        /** Carry exclusive or: {@code O = CI ^ LI}. */
        XORCY,
        /** {@code O = !I}. */
        INVERTER,
        /** {@code O = I}, and for differential outputs {@code OB = !I}. */
        BUFFER,
        /** {@code O = DI & !(SRI ^ IS_SRI_INVERTED)}. */
        AND2B1L,
        /** {@code O = DI | (SRI ^ IS_SRI_INVERTED)}. */
        OR2L,
        /** A soft logic gate of the {@code FAMILY<n>B<m>} family. */
        SOFT
    }

    private final Kind kind;

    private final String typeName;

    private SimpleGateModel(Kind kind, String typeName) {
        this.kind = kind;
        this.typeName = typeName;
    }

    /**
     * @return A model for the type, or null if this class does not handle it.
     */
    public static SimpleGateModel forType(String typeName) {
        if (typeName.startsWith("MUXF")) {
            return new SimpleGateModel(Kind.MUXF, typeName);
        }
        if (typeName.startsWith("MUXCY")) {
            return new SimpleGateModel(Kind.MUXCY, typeName);
        }
        if (typeName.startsWith("XORCY")) {
            return new SimpleGateModel(Kind.XORCY, typeName);
        }
        if (typeName.equals("INV")) {
            return new SimpleGateModel(Kind.INVERTER, typeName);
        }
        if (typeName.equals("AND2B1L")) {
            return new SimpleGateModel(Kind.AND2B1L, typeName);
        }
        if (typeName.equals("OR2L")) {
            return new SimpleGateModel(Kind.OR2L, typeName);
        }
        if (isBuffer(typeName)) {
            return new SimpleGateModel(Kind.BUFFER, typeName);
        }
        if (softGateArity(typeName) > 0) {
            return new SimpleGateModel(Kind.SOFT, typeName);
        }
        return null;
    }

    private static boolean isBuffer(String typeName) {
        return typeName.equals("BUF") || typeName.equals("IBUF") || typeName.equals("OBUF")
                || typeName.equals("IBUFG") || typeName.equals("IBUFDS")
                || typeName.equals("IBUFGDS") || typeName.equals("IBUFDS_DIFF_OUT")
                || typeName.equals("OBUFDS");
    }

    @Override
    public int[] supportOf(CellIndex index, EDIFCellInst inst, int outPortBit) {
        switch (kind) {
        case MUXF:
            return ModelUtils.bitsOf(index, "S", "I1", "I0");
        case MUXCY:
            return ModelUtils.bitsOf(index, "S", "CI", "DI");
        case XORCY:
            return ModelUtils.bitsOf(index, "CI", "LI");
        case INVERTER:
        case BUFFER:
            return ModelUtils.bitsOf(index, "I");
        case AND2B1L:
        case OR2L:
            return ModelUtils.bitsOf(index, "DI", "SRI");
        case SOFT:
            return softInputs(index);
        default:
            return null;
        }
    }

    @Override
    public int build(Aig aig, CellIndex index, EDIFCellInst inst, int outPortBit,
            int[] inputLits) {
        switch (kind) {
        case MUXF:
            return aig.mux(inputLits[0], inputLits[1], inputLits[2]);
        case MUXCY:
            return aig.mux(inputLits[0], inputLits[1], inputLits[2]);
        case XORCY:
            return aig.xor(inputLits[0], inputLits[1]);
        case INVERTER:
            return Aig.not(inputLits[0]);
        case BUFFER:
            return index.getPortBitName(outPortBit).equals("OB")
                    ? Aig.not(inputLits[0]) : inputLits[0];
        case AND2B1L:
            return aig.and(inputLits[0], Aig.not(setResetInput(inst, inputLits[1])));
        case OR2L:
            return aig.or(inputLits[0], setResetInput(inst, inputLits[1]));
        case SOFT:
            return buildSoftGate(aig, inputLits);
        default:
            throw new LecException("No model for " + typeName);
        }
    }

    /** Applies the {@code IS_SRI_INVERTED} attribute to the set and reset input. */
    private static int setResetInput(EDIFCellInst inst, int lit) {
        return ModelUtils.isPinInverted(inst, "SRI") ? Aig.not(lit) : lit;
    }

    private static int[] softInputs(CellIndex index) {
        int arity = softGateArity(index.getCell().getName());
        String[] pins = new String[arity];
        for (int i = 0; i < arity; i++) {
            pins[i] = "I" + i;
        }
        return ModelUtils.bitsOf(index, pins);
    }

    /**
     * Builds the gate by constructing its truth table and lowering it, rather
     * than by special casing each family, so that the inversion suffix is
     * handled once.
     */
    private int buildSoftGate(Aig aig, int[] inputLits) {
        int arity = softGateArity(typeName);
        int inverted = softGateInvertedInputs(typeName);
        String family = softGateFamily(typeName);

        long[] operands = new long[arity];
        for (int i = 0; i < arity; i++) {
            long v = TruthTable.var(i);
            operands[i] = i < inverted ? ~v : v;
        }
        long table;
        if (family.equals("AND") || family.equals("NAND")) {
            table = -1L;
            for (long operand : operands) {
                table &= operand;
            }
        } else if (family.equals("OR") || family.equals("NOR")) {
            table = 0L;
            for (long operand : operands) {
                table |= operand;
            }
        } else {
            table = 0L;
            for (long operand : operands) {
                table ^= operand;
            }
        }
        if (family.equals("NAND") || family.equals("NOR") || family.equals("XNOR")) {
            table = ~table;
        }
        return TruthTableSynth.synthesize(aig, table, arity, inputLits);
    }

    /** @return The family prefix of a soft gate name, or null. */
    static String softGateFamily(String typeName) {
        for (String family : new String[] { "XNOR", "NAND", "XOR", "NOR", "AND", "OR" }) {
            if (typeName.startsWith(family)) {
                return family;
            }
        }
        return null;
    }

    /** @return The input count of a soft gate, or 0 if the name is not one. */
    static int softGateArity(String typeName) {
        String family = softGateFamily(typeName);
        if (family == null || typeName.length() <= family.length()) {
            return 0;
        }
        String tail = typeName.substring(family.length());
        int bIndex = tail.indexOf('B');
        String digits = bIndex < 0 ? tail : tail.substring(0, bIndex);
        int arity = parsePositive(digits);
        if (arity < 2 || arity > TruthTable.MAX_VARS) {
            return 0;
        }
        if (bIndex >= 0 && parsePositive(tail.substring(bIndex + 1)) < 1) {
            return 0;
        }
        return arity;
    }

    /** @return How many leading inputs the name's {@code B} suffix inverts. */
    static int softGateInvertedInputs(String typeName) {
        String family = softGateFamily(typeName);
        if (family == null) {
            return 0;
        }
        String tail = typeName.substring(family.length());
        int bIndex = tail.indexOf('B');
        if (bIndex < 0) {
            return 0;
        }
        return Math.max(0, parsePositive(tail.substring(bIndex + 1)));
    }

    private static int parsePositive(String text) {
        if (text.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return -1;
            }
        }
        return Integer.parseInt(text);
    }
}
