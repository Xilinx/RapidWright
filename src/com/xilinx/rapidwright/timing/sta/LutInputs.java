/*
 *
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

package com.xilinx.rapidwright.timing.sta;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * The inputs of LUT cells that their function depends on: the arc providers give no timing arc to
 * the others, as Vivado does not time through them.
 */
final class LutInputs {

    private LutInputs() {
    }

    /**
     * Gets the number of inputs of a LUT1 to LUT6 cell type.
     * @param cellType The cell type.
     * @return The number of inputs, 0 if the type is not one of these LUTs.
     */
    static int getSize(String cellType) {
        if (cellType.length() == 4 && cellType.startsWith("LUT")) {
            char n = cellType.charAt(3);
            if (n >= '1' && n <= '6') {
                return n - '0';
            }
        }
        return 0;
    }

    /**
     * Finds the inputs that the function of a LUT depends on, as Vivado times no arc from the others:
     * the LUT5 and LUT6 of a LUT6_2 take their function from the LUT6_2 (the LUT5 its lower half), and
     * the sixth input of a LUT6_2 tied to a constant fixes the half of the function that its LUT6
     * computes; other inputs tied to constants keep their arcs.
     * @param design The design.
     * @param c The LUT cell.
     * @param cellInst Its cell instance.
     * @param lutSize Its number of inputs.
     * @return A mask whose bit i is set if the function depends on input I&lt;i&gt; (all bits if the
     * function is unknown).
     */
    static int getDependencies(Design design, Cell c, EDIFCellInst cellInst, int lutSize) {
        return getDependencies(design, c, cellInst, lutSize, false);
    }

    /**
     * Finds the inputs that the function of a LUT depends on (see
     * {@link #getDependencies(Design, Cell, EDIFCellInst, int)}).
     * @param propagateConstants Whether to fix every input tied to a constant at its value, as Vivado's
     *                           constant propagation does: the function restricted to the other inputs
     *                           may then not depend on some of them (e.g. a LUT5 whose INIT is 0 once
     *                           its GND inputs are fixed has no arcs at all), and the constant inputs
     *                           have no arcs either.
     */
    static int getDependencies(Design design, Cell c, EDIFCellInst cellInst, int lutSize,
                               boolean propagateConstants) {
        int all = (1 << lutSize) - 1;
        // The LUT6 and LUT5 cells of a LUT6_2 take their function and inputs from the LUT6_2
        EDIFCellInst functionInst = cellInst;
        EDIFCell parent = c.getParentCell();
        if (parent != null && parent.getName().equals("LUT6_2")) {
            functionInst = design.getNetlist().getCellInstFromHierName(c.getParentHierarchicalInstName());
            if (functionInst == null) {
                return all;
            }
        }
        long init;
        try {
            init = LUTTools.getInitValue(functionInst.getProperty(LUTTools.LUT_INIT).getValue());
        } catch (RuntimeException e) {
            return all;
        }
        int constMask = 0;
        int constValue = 0;
        // Unless constants are propagated, keep the arcs of inputs tied to constants, except for the
        // sixth input of a LUT6_2 that uses both its outputs: its LUT6 then computes the upper half of
        // the function
        for (int i = propagateConstants ? 0 : 5; i < lutSize && (propagateConstants || functionInst != cellInst); i++) {
            EDIFPortInst portInst = functionInst.getPortInst("I" + i);
            if (portInst == null || portInst.getNet() == null) {
                continue;
            }
            Net physNet = null;
            String physPin = c.getPhysicalPinMapping("I" + i);
            if (physPin != null && c.getBEL().getPin(physPin) != null) {
                physNet = c.getSiteInst().getNetFromSiteWire(c.getBEL().getPin(physPin).getSiteWireName());
            }
            if (portInst.getNet().isGND() || (physNet != null && physNet.getType() == NetType.GND)) {
                constMask |= 1 << i;
            } else if (portInst.getNet().isVCC() || (physNet != null && physNet.getType() == NetType.VCC)) {
                constMask |= 1 << i;
                constValue |= 1 << i;
            }
        }
        int dependencies = 0;
        for (int row = 0; row <= all; row++) {
            if ((row & constMask) != constValue) continue;
            for (int i = 0; i < lutSize; i++) {
                int bit = 1 << i;
                if ((constMask & bit) != 0 || (row & bit) != 0) continue;
                if (((init >>> row) & 1) != ((init >>> (row | bit)) & 1)) {
                    dependencies |= bit;
                }
            }
        }
        return dependencies;
    }

    /**
     * Checks if a LUT input has timing arcs.
     * @param portName The name of the input port instance (I0 to I5).
     * @param dependencies The LUT's dependencies, see {@link #getDependencies}.
     * @return False for an input I&lt;i&gt; that the function does not depend on.
     */
    static boolean isUsed(String portName, int dependencies) {
        if (portName.length() == 2 && portName.charAt(0) == 'I') {
            int i = portName.charAt(1) - '0';
            return i < 0 || i > 5 || ((dependencies >> i) & 1) != 0;
        }
        return true;
    }
}
