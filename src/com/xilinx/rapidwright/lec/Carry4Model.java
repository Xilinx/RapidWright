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
 * The four bit carry primitive used by Series 7 and UltraScale, transcribed
 * from the golden simulation model:
 *
 * <pre>{@code
 * CO_fb = {CO[2:0], CI || CYINIT}
 * O     = S ^ CO_fb
 * CO    = (S & CO_fb) | (~S & DI)
 * }</pre>
 *
 * Note that the carry into bit 0 is the <b>disjunction</b> of {@code CI} and
 * {@code CYINIT}, not a selection between them. Only one of the two is ever
 * driven in a real design, so an implementation that used a multiplexer would
 * usually agree, which is precisely what would make the mistake hard to find.
 *
 * As with {@link Carry8Model}, bit {@code i} depends only on bits at or below
 * {@code i}, which is what keeps a carry chain acyclic at bit level, and the
 * sum output does not depend on the data input of its own bit.
 */
public class Carry4Model implements PrimitiveModel {

    private static final int WIDTH = 4;

    /**
     * The support layout, shared by both methods:
     *
     * <pre>{@code
     * index 0           : CI
     * index 1           : CYINIT
     * index 2 .. 2+bit  : S[0] .. S[bit]
     * then              : DI[0] .. DI[bit] , one shorter for a sum output
     * }</pre>
     */
    private static String[] supportPins(int bit, boolean isSum) {
        int sCount = bit + 1;
        int diCount = isSum ? bit : bit + 1;
        String[] pins = new String[2 + sCount + diCount];
        pins[0] = "CI";
        pins[1] = "CYINIT";
        for (int k = 0; k < sCount; k++) {
            pins[2 + k] = "S[" + k + "]";
        }
        for (int k = 0; k < diCount; k++) {
            pins[2 + sCount + k] = "DI[" + k + "]";
        }
        return pins;
    }

    @Override
    public int[] supportOf(CellIndex index, EDIFCellInst inst, int outPortBit) {
        String outPin = index.getPortBitName(outPortBit);
        int bit = Carry8Model.bitOf(outPin);
        if (bit < 0 || bit >= WIDTH) {
            return null;
        }
        return ModelUtils.bitsOf(index, supportPins(bit, outPin.startsWith("O[")));
    }

    @Override
    public int build(Aig aig, CellIndex index, EDIFCellInst inst, int outPortBit,
            int[] inputLits) {
        String outPin = index.getPortBitName(outPortBit);
        int bit = Carry8Model.bitOf(outPin);
        boolean isSum = outPin.startsWith("O[");
        int sCount = bit + 1;

        int carry = aig.or(inputLits[0], inputLits[1]);
        for (int k = 0; k < bit; k++) {
            int s = inputLits[2 + k];
            int di = inputLits[2 + sCount + k];
            carry = aig.or(aig.and(s, carry), aig.and(Aig.not(s), di));
        }
        int sHere = inputLits[2 + bit];
        if (isSum) {
            return aig.xor(sHere, carry);
        }
        int diHere = inputLits[2 + sCount + bit];
        return aig.or(aig.and(sHere, carry), aig.and(Aig.not(sHere), diHere));
    }
}
