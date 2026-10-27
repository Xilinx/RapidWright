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
 * The Versal carry lookahead primitive, transcribed from the golden simulation
 * model:
 *
 * <pre>{@code
 * PROP_AB  = PROPA && PROPB &&  LOOKB
 * PROP_ABF = PROPA && PROPB && !LOOKB
 * PROP_CD  = PROPC && PROPD &&  LOOKD
 * PROP_EF  = PROPE && PROPF &&  LOOKF
 * PROP_GH  = PROPG && PROPH &&  LOOKH
 *
 * COUTB = ((!LOOKB || !PROPB) && CYB)
 *       | ((LOOKB && PROPB) && ((!PROPA && CYA) | (PROPA && CIN)))
 *
 * COUTD = (PROP_AB && PROP_CD && CIN)
 *       | ((!LOOKD || !PROPD) && CYD)
 *       | ((LOOKD && PROPD) && ((!PROPC && CYC)
 *                                | (PROPC && !PROP_AB && COUTB)
 *                                | (PROPC &&  PROP_AB && CIN)))
 *
 * COUTF = ((!LOOKF || !PROPF) && CYF)
 *       | ((LOOKF && PROPF) && ((!PROPE && CYE)
 *                                | (PROPE && !(PROP_AB && PROP_CD) && COUTD)
 *                                | (PROPE &&  (PROP_AB && PROP_CD) && CIN)))
 *
 * COUTH = (PROP_AB && PROP_CD && PROP_EF && PROP_GH && CIN)
 *       | ((!LOOKH || !PROPH) && CYH)
 *       | ((LOOKH && PROPH) && ((!PROPG && CYG)
 *                                | (PROPG && !(PROP_AB && PROP_CD && PROP_EF) && COUTF)
 *                                | (PROPG && !(PROP_AB && PROP_CD) && PROPE && PROPF && COUTD)
 *                                | (PROP_ABF && PROP_CD && PROP_EF && PROP_GH && CIN)))
 * }</pre>
 *
 * The {@code LOOK} attributes are fixed at build time, so they fold away to
 * constants; with all of them false the whole cell reduces to
 * {@code COUTx = CYx}.
 *
 * The four outputs form a strictly increasing chain, {@code COUTB} then
 * {@code COUTD} then {@code COUTF} then {@code COUTH}, so the cell is
 * internally acyclic at bit level. The {@code GE} inputs do not appear in the
 * functional model at all and are therefore not in any support set.
 */
public class Lookahead8Model implements PrimitiveModel {

    private static final String[] STAGE_OUTPUTS = { "COUTB", "COUTD", "COUTF", "COUTH" };

    /** @return The stage index of an output, 0 for COUTB up to 3 for COUTH. */
    private static int stageOf(String outPin) {
        for (int i = 0; i < STAGE_OUTPUTS.length; i++) {
            if (STAGE_OUTPUTS[i].equals(outPin)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The inputs a stage can read: the carry in, and the propagate and carry
     * pins of its own pair and of every lower pair.
     */
    private static String[] supportPins(int stage) {
        int pairs = 2 * (stage + 1);
        String[] pins = new String[1 + 2 * pairs];
        pins[0] = "CIN";
        for (int i = 0; i < pairs; i++) {
            char letter = (char) ('A' + i);
            pins[1 + i] = "PROP" + letter;
            pins[1 + pairs + i] = "CY" + letter;
        }
        return pins;
    }

    @Override
    public int[] supportOf(CellIndex index, EDIFCellInst inst, int outPortBit) {
        int stage = stageOf(index.getPortBitName(outPortBit));
        if (stage < 0) {
            return null;
        }
        return ModelUtils.bitsOf(index, supportPins(stage));
    }

    @Override
    public int build(Aig aig, CellIndex index, EDIFCellInst inst, int outPortBit,
            int[] inputLits) {
        int stage = stageOf(index.getPortBitName(outPortBit));
        int pairs = 2 * (stage + 1);
        int cin = inputLits[0];
        int[] prop = new int[pairs];
        int[] cy = new int[pairs];
        for (int i = 0; i < pairs; i++) {
            prop[i] = inputLits[1 + i];
            cy[i] = inputLits[1 + pairs + i];
        }
        boolean lookB = ModelUtils.booleanProperty(inst, "LOOKB", false);
        boolean lookD = ModelUtils.booleanProperty(inst, "LOOKD", false);
        boolean lookF = ModelUtils.booleanProperty(inst, "LOOKF", false);
        boolean lookH = ModelUtils.booleanProperty(inst, "LOOKH", false);

        int propAb = groupPropagate(aig, prop, 0, lookB);
        if (stage == 0) {
            return coutB(aig, cin, prop, cy, lookB);
        }
        int propCd = groupPropagate(aig, prop, 2, lookD);
        int coutB = coutB(aig, cin, prop, cy, lookB);
        int coutD = coutD(aig, cin, prop, cy, lookD, propAb, propCd, coutB);
        if (stage == 1) {
            return coutD;
        }
        int propEf = groupPropagate(aig, prop, 4, lookF);
        int coutF = coutF(aig, cin, prop, cy, lookF, propAb, propCd, coutD);
        if (stage == 2) {
            return coutF;
        }
        int propGh = groupPropagate(aig, prop, 6, lookH);
        int propAbf = groupPropagate(aig, prop, 0, !lookB);
        return coutH(aig, cin, prop, cy, lookH, propAb, propAbf, propCd, propEf, propGh, coutD,
                coutF);
    }

    /**
     * {@code PROPx && PROPy}, gated by the corresponding lookahead attribute.
     *
     * The attribute is a build time constant, so when it is false the whole
     * group propagate folds to zero and every term using it disappears.
     */
    private static int groupPropagate(Aig aig, int[] prop, int first, boolean look) {
        if (!look || first + 1 >= prop.length) {
            return Aig.FALSE;
        }
        return aig.and(prop[first], prop[first + 1]);
    }

    private static int coutB(Aig aig, int cin, int[] prop, int[] cy, boolean lookB) {
        int notLookOrNotProp = lookB ? Aig.not(prop[1]) : Aig.TRUE;
        int direct = aig.and(notLookOrNotProp, cy[1]);
        if (!lookB) {
            return direct;
        }
        int through = aig.or(aig.and(Aig.not(prop[0]), cy[0]), aig.and(prop[0], cin));
        return aig.or(direct, aig.and(prop[1], through));
    }

    private static int coutD(Aig aig, int cin, int[] prop, int[] cy, boolean lookD, int propAb,
            int propCd, int coutB) {
        int result = aig.and(propAb, aig.and(propCd, cin));
        int notLookOrNotProp = lookD ? Aig.not(prop[3]) : Aig.TRUE;
        result = aig.or(result, aig.and(notLookOrNotProp, cy[3]));
        if (!lookD) {
            return result;
        }
        int inner = aig.and(Aig.not(prop[2]), cy[2]);
        inner = aig.or(inner, aig.and(prop[2], aig.and(Aig.not(propAb), coutB)));
        inner = aig.or(inner, aig.and(prop[2], aig.and(propAb, cin)));
        return aig.or(result, aig.and(prop[3], inner));
    }

    private static int coutF(Aig aig, int cin, int[] prop, int[] cy, boolean lookF, int propAb,
            int propCd, int coutD) {
        int notLookOrNotProp = lookF ? Aig.not(prop[5]) : Aig.TRUE;
        int result = aig.and(notLookOrNotProp, cy[5]);
        if (!lookF) {
            return result;
        }
        int abcd = aig.and(propAb, propCd);
        int inner = aig.and(Aig.not(prop[4]), cy[4]);
        inner = aig.or(inner, aig.and(prop[4], aig.and(Aig.not(abcd), coutD)));
        inner = aig.or(inner, aig.and(prop[4], aig.and(abcd, cin)));
        return aig.or(result, aig.and(prop[5], inner));
    }

    /**
     * The last stage. Note the fourth inner disjunct, which uses
     * {@code PROP_ABF} rather than {@code PROP_AB}: it is the path taken when
     * the AB pair propagates but {@code LOOKB} is false, so the carry has to
     * bypass the whole cell rather than come from {@code COUTF}.
     */
    private static int coutH(Aig aig, int cin, int[] prop, int[] cy, boolean lookH, int propAb,
            int propAbf, int propCd, int propEf, int propGh, int coutD, int coutF) {
        int allProp = aig.and(aig.and(propAb, propCd), aig.and(propEf, propGh));
        int result = aig.and(allProp, cin);
        int notLookOrNotProp = lookH ? Aig.not(prop[7]) : Aig.TRUE;
        result = aig.or(result, aig.and(notLookOrNotProp, cy[7]));
        if (!lookH) {
            return result;
        }
        int abcd = aig.and(propAb, propCd);
        int abcdef = aig.and(abcd, propEf);
        int inner = aig.and(Aig.not(prop[6]), cy[6]);
        inner = aig.or(inner, aig.and(prop[6], aig.and(Aig.not(abcdef), coutF)));
        inner = aig.or(inner, aig.and(prop[6],
                aig.and(Aig.not(abcd), aig.and(aig.and(prop[4], prop[5]), coutD))));
        inner = aig.or(inner, aig.and(aig.and(propAbf, propCd), aig.and(aig.and(propEf, propGh),
                cin)));
        return aig.or(result, aig.and(prop[7], inner));
    }
}
