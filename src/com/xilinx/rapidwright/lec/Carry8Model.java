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
 * The eight bit carry primitive, transcribed from the golden simulation model:
 *
 * <pre>{@code
 * CI_TOP_in = (CARRY_TYPE == "DUAL_CY4") ? CI_TOP : CO[3]
 * CO_fb     = {CO[6:4], CI_TOP_in, CO[2:0], CI}
 * O         = S ^ CO_fb
 * CO        = (S & CO_fb) | (~S & DI)
 * }</pre>
 *
 * Unrolled, the carry into bit {@code i} is the carry out of bit {@code i-1},
 * except that bit 0 takes {@code CI}, and in dual mode bit 4 restarts from
 * {@code CI_TOP}. So bit {@code i} depends only on bits at or below {@code i}
 * within its own four or eight bit group. That strictly increasing dependency
 * is what makes a carry chain acyclic at bit level, and it is why the support
 * computed here must be exact.
 *
 * Two details are easy to get wrong and both are covered by tests. In single
 * mode {@code CI_TOP} is functionally ignored, so it must not appear in any
 * support set; otherwise a design that ties it to ground would appear to differ
 * from one that leaves it dangling. And the sum output {@code O[i]} does not
 * depend on {@code DI[i]}, only on the carry arriving at bit {@code i}, so
 * including it would create a dependency the hardware does not have.
 */
public class Carry8Model implements PrimitiveModel {

    /** Property selecting whether the eight bits form one chain or two. */
    public static final String CARRY_TYPE = "CARRY_TYPE";

    /** Value of {@link #CARRY_TYPE} that splits the cell into two four bit chains. */
    public static final String DUAL_CY4 = "DUAL_CY4";

    private static final int WIDTH = 8;

    /**
     * @return The bit index at which the chain containing {@code bit} starts:
     *         always 0 in single mode, and 4 for the upper half in dual mode.
     */
    private static int chainBase(EDIFCellInst inst, int bit) {
        boolean dual = ModelUtils.property(inst, CARRY_TYPE, "SINGLE_CY8")
                .trim().equalsIgnoreCase(DUAL_CY4);
        return dual && bit >= 4 ? 4 : 0;
    }

    /** @return The pin supplying the carry into the chain containing {@code bit}. */
    private static String carryInPin(EDIFCellInst inst, int bit) {
        return chainBase(inst, bit) == 0 ? "CI" : "CI_TOP";
    }

    /**
     * The support layout, shared by {@link #supportOf} and {@link #build} so the
     * two can never disagree about which literal is which:
     *
     * <pre>{@code
     * index 0                : the carry in pin
     * index 1 .. sCount      : S[base] .. S[bit]
     * index 1+sCount ..      : DI[base] .. DI[base+diCount-1]
     * }</pre>
     *
     * where {@code diCount} is one shorter for a sum output, which does not see
     * the data input of its own bit.
     */
    private static String[] supportPins(EDIFCellInst inst, int bit, boolean isSum) {
        int base = chainBase(inst, bit);
        int sCount = bit - base + 1;
        int diCount = isSum ? sCount - 1 : sCount;
        String[] pins = new String[1 + sCount + diCount];
        pins[0] = carryInPin(inst, bit);
        for (int k = 0; k < sCount; k++) {
            pins[1 + k] = "S[" + (base + k) + "]";
        }
        for (int k = 0; k < diCount; k++) {
            pins[1 + sCount + k] = "DI[" + (base + k) + "]";
        }
        return pins;
    }

    @Override
    public int[] supportOf(CellIndex index, EDIFCellInst inst, int outPortBit) {
        String outPin = index.getPortBitName(outPortBit);
        int bit = bitOf(outPin);
        if (bit < 0 || bit >= WIDTH) {
            return null;
        }
        boolean isSum = outPin.startsWith("O[");
        return ModelUtils.bitsOf(index, supportPins(inst, bit, isSum));
    }

    @Override
    public int build(Aig aig, CellIndex index, EDIFCellInst inst, int outPortBit,
            int[] inputLits) {
        String outPin = index.getPortBitName(outPortBit);
        int bit = bitOf(outPin);
        boolean isSum = outPin.startsWith("O[");
        int base = chainBase(inst, bit);
        int sCount = bit - base + 1;

        int carry = inputLits[0];
        for (int k = 0; k < bit - base; k++) {
            int s = inputLits[1 + k];
            int di = inputLits[1 + sCount + k];
            carry = aig.or(aig.and(s, carry), aig.and(Aig.not(s), di));
        }
        int sHere = inputLits[1 + (bit - base)];
        if (isSum) {
            return aig.xor(sHere, carry);
        }
        int diHere = inputLits[1 + sCount + (bit - base)];
        return aig.or(aig.and(sHere, carry), aig.and(Aig.not(sHere), diHere));
    }

    /** @return The bit index inside a bus pin name, or -1 for a scalar pin. */
    static int bitOf(String pinName) {
        int open = pinName.indexOf('[');
        if (open < 0 || !pinName.endsWith("]")) {
            return -1;
        }
        try {
            return Integer.parseInt(pinName.substring(open + 1, pinName.length() - 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
