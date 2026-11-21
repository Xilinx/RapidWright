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

package com.xilinx.rapidwright.timing.sta;

import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * Clock buffers used as data: global clock buffers (BUFGCE, BUFGCE_DIV, BUFGCTRL, BUFG; not BUFG_GT or
 * BUFG_PS) whose data input fabric logic drives, e.g. buffers inserted on the high-fanout clock enable or
 * reset nets of a design. Their output nets are clock nets, routed on the global clock network, but Vivado
 * times the data paths through them: from the logic driving the buffer, through the buffer, to the loads
 * of its output net.
 */
final class DataClockBuffers {

    private DataClockBuffers() {
    }

    /** Checks if a cell type is a global clock buffer that may carry data (not BUFG_GT or BUFG_PS). */
    private static boolean isGlobalClockBuffer(String cellType) {
        return cellType.startsWith("BUFG") && !cellType.startsWith("BUFG_GT") && !cellType.startsWith("BUFG_PS");
    }

    /** Checks if a port of a global clock buffer is a data input (I, or I0 and I1 of a BUFGCTRL). */
    static boolean isDataInput(String portName) {
        return portName.equals("I") || portName.equals("I0") || portName.equals("I1");
    }

    /** Checks if a cell type is fabric logic: a register, LUT, carry, mux, shift register, RAM or DSP. */
    private static boolean isFabricLogic(String cellType) {
        return cellType.startsWith("FD") || cellType.startsWith("LUT") || cellType.startsWith("CARRY")
                || cellType.startsWith("MUXF") || cellType.startsWith("SRL") || cellType.startsWith("RAM")
                || cellType.startsWith("DSP");
    }

    /**
     * Checks if fabric logic drives a data input of a global clock buffer.
     * @param netlist The netlist.
     * @param buffer The buffer.
     * @return True if the buffer is used as data.
     */
    private static boolean isDrivenByFabric(EDIFNetlist netlist, EDIFHierCellInst buffer) {
        for (EDIFPortInst in : buffer.getInst().getPortInsts()) {
            if (!in.isInput() || !isDataInput(in.getName())) continue;
            List<EDIFHierPortInst> pins = FamilyArcProvider.getPhysicalPins(netlist,
                    new EDIFHierPortInst(buffer.getParent(), in));
            if (pins == null) continue;
            for (EDIFHierPortInst p : pins) {
                if (p.isOutput() && !p.getPortInst().isTopLevelPort() && isFabricLogic(p.getCellType().getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Gets the global clock buffer that drives a net if it is used as data.
     * @param netlist The netlist.
     * @param net The net, e.g. a clock net.
     * @return The buffer, or null if a clock buffer used as data does not drive the net.
     */
    static EDIFHierCellInst getDataClockBuffer(EDIFNetlist netlist, Net net) {
        List<EDIFHierPortInst> pins = netlist.getPhysicalPins(net);
        if (pins == null) {
            return null;
        }
        for (EDIFHierPortInst p : pins) {
            if (!p.isOutput() || p.getPortInst().isTopLevelPort()) continue;
            if (!isGlobalClockBuffer(p.getCellType().getName())) {
                return null;
            }
            EDIFHierCellInst buffer = p.getFullHierarchicalInst();
            return isDrivenByFabric(netlist, buffer) ? buffer : null;
        }
        return null;
    }
}
