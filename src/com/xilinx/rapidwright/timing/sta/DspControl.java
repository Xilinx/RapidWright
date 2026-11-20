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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * The static control inputs of a DSP (DSP48E2 of UltraScale+, DSP58 of Versal): the bits of its OPMODE,
 * ALUMODE, CARRYINSEL and INMODE ports that constants drive, and the arcs of its ALU multiplexers, adder
 * and pre-adder inputs that they deselect, which Vivado does not time. A bit driven by logic (or
 * unconnected) is dynamic: an arc is kept if any value of the dynamic bits selects it.
 * <p>
 * Multiplexer selects (UG579, AM004): OPMODE[1:0] X (00 0, 01 M, 10 P, 11 A:B), OPMODE[3:2] Y (00 0,
 * 01 M, 10 all ones, 11 C), OPMODE[6:4] Z (000 0, 001 PCIN, 010 P, 011 C, 100 P, 101 PCIN shifted,
 * 110 P shifted), OPMODE[8:7] W (00 0, 01 P, 10 RND, 11 C); CARRYINSEL 000 selects CARRYIN; INMODE[0]
 * selects A1 (else A2), INMODE[1] zeroes A, INMODE[2] enables D, INMODE[4] selects B1 (else B2).
 */
final class DspControl {

    /** Known bits and their values, by bus */
    private final long[] known = new long[4];
    private final long[] value = new long[4];

    private static final int OPMODE = 0;
    private static final int ALUMODE = 1;
    private static final int CARRYINSEL = 2;
    private static final int INMODE = 3;
    private static final String[] BUS = {"OPMODE", "ALUMODE", "CARRYINSEL", "INMODE"};
    private static final int[] WIDTH = {9, 4, 3, 5};

    private DspControl() {
    }

    /**
     * A control of known bits (for tests).
     * @param knownBits The constant bits of OPMODE, ALUMODE, CARRYINSEL and INMODE.
     * @param values Their values.
     */
    DspControl(long[] knownBits, long[] values) {
        System.arraycopy(knownBits, 0, known, 0, known.length);
        System.arraycopy(values, 0, value, 0, value.length);
    }

    /** Whether the arcs of a DSP sub-cell type depend on its macro's control inputs */
    static boolean isControlled(String cellType) {
        switch (cellType) {
            case "DSP_ALU":
            case "DSP_ALUMUX":
            case "DSP_ALUADD":
            case "DSP_PREADD_DATA":
            case "DSP_PREADD_DATA58":
            case "DSP_PREADD":
            case "DSP_PREADD58":
                return true;
            default:
                return false;
        }
    }

    /**
     * Reads the control inputs of the DSP macro (the top primitive) of a sub-cell.
     * @param c The sub-cell.
     * @return The control, or null if the cell is not in a macro.
     */
    static DspControl of(Cell c) {
        EDIFHierCellInst macro = null;
        EDIFHierCellInst inst = c.getEDIFHierCellInst() == null ? null : c.getEDIFHierCellInst().getParent();
        while (inst != null && !inst.isTopLevelInst()
                && inst.getCellType().getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME)) {
            macro = inst;
            inst = inst.getParent();
        }
        if (macro == null) {
            return null;
        }
        DspControl dc = new DspControl();
        for (int b = 0; b < BUS.length; b++) {
            long inverted = 0;
            EDIFPropertyValue inv = macro.getInst().getProperty("IS_" + BUS[b] + "_INVERTED");
            if (inv != null) {
                try {
                    inverted = LUTTools.getInitValue(inv.getValue());
                } catch (RuntimeException e) {
                    // Unknown inversion: every bit dynamic
                    continue;
                }
            }
            for (int i = 0; i < WIDTH[b]; i++) {
                EDIFPortInst pi = macro.getInst().getPortInst(BUS[b] + "[" + i + "]");
                int v = pi == null ? -1 : constantOf(macro.getParent(), pi.getNet());
                if (v < 0) continue;
                dc.known[b] |= 1L << i;
                if ((v ^ ((inverted >> i) & 1)) != 0) {
                    dc.value[b] |= 1L << i;
                }
            }
        }
        return dc;
    }

    /**
     * The constant driving a net of a hierarchical cell instance: up through the input ports of the cells it
     * crosses (without the netlist's parent net map, which the threads building a graph would each build).
     * @return 0 or 1, or -1 if logic drives it (or it is unconnected).
     */
    private static int constantOf(EDIFHierCellInst inst, EDIFNet net) {
        while (net != null) {
            if (net.isGND()) {
                return 0;
            }
            if (net.isVCC()) {
                return 1;
            }
            EDIFPortInst outer = null;
            for (EDIFPortInst p : net.getPortInsts()) {
                if (p.isTopLevelPort() && p.getPort().isInput()) {
                    outer = p;
                    break;
                }
            }
            if (outer == null || inst == null || inst.isTopLevelInst()) {
                return -1;
            }
            EDIFPortInst up = inst.getInst().getPortInst(outer.getName());
            net = up == null ? null : up.getNet();
            inst = inst.getParent();
        }
        return -1;
    }

    /** Whether all the bits of a bus are constant */
    private boolean isStatic(int bus) {
        return known[bus] == (1L << WIDTH[bus]) - 1;
    }

    /** Whether a field of a bus (bits lo..lo+width-1) may take any of some values */
    private boolean may(int bus, int lo, int width, int... values) {
        long mask = ((1L << width) - 1) << lo;
        long k = known[bus] & mask;
        for (int v : values) {
            if ((((long) v << lo) & k) == (value[bus] & k)) {
                return true;
            }
        }
        return false;
    }

    private boolean mayX(int... v) {
        return may(OPMODE, 0, 2, v);
    }

    private boolean mayY(int... v) {
        return may(OPMODE, 2, 2, v);
    }

    private boolean mayZ(int... v) {
        return may(OPMODE, 4, 3, v);
    }

    private boolean mayW(int... v) {
        return may(OPMODE, 7, 2, v);
    }

    /** Whether some multiplexer may select an input bus of the ALU */
    private boolean isSelected(String in) {
        switch (in) {
            case "U_DATA":
                return mayX(1);
            case "A_ALU":
            case "B_ALU":
                return mayX(3);
            case "V_DATA":
                return mayY(1);
            case "PCIN":
                return mayZ(1, 5);
            case "P_FDBK":
            case "P_FDBK_47":
            case "P_FDBK_MSB":
                return mayX(2) || mayZ(2, 4, 6) || mayW(1);
            case "C_DATA":
                return mayY(3) || mayZ(3) || mayW(3);
            default:
                return true;
        }
    }

    /** Whether a multiplexer output may carry data (not a constant: 0, all ones, the RND attribute) */
    private boolean carriesData(String mux) {
        switch (mux) {
            case "X":
                return mayX(1, 2, 3);
            case "Y":
                return mayY(1, 3);
            case "Z":
                return mayZ(1, 2, 3, 4, 5, 6);
            case "W":
                return mayW(1, 3);
            default:
                return true;
        }
    }

    /**
     * Checks if a combinational arc of a DSP sub-cell is timed with the macro's control inputs.
     * @param cellType The sub-cell type (see {@link #isControlled(String)}).
     * @param from The arc's input pin (bus base name).
     * @param to The arc's output pin (bus base name).
     * @return False if constant control inputs deselect it.
     */
    boolean keepsArc(String cellType, String from, String to) {
        switch (cellType) {
            case "DSP_ALU":
                // UltraScale+: the multiplexers and the adder in one cell
                return isSelected(from);
            case "DSP_ALUMUX":
                if (from.equals("OPMODE_DATA")) {
                    return !isStatic(OPMODE);
                }
                if (from.equals("CARRYIN_DATA")) {
                    return may(CARRYINSEL, 0, 3, 0);
                }
                return isSelectedBy(from, to);
            case "DSP_ALUADD":
                if (from.equals("ALUMODE_DATA")) {
                    return !isStatic(ALUMODE);
                }
                return carriesData(from);
            case "DSP_PREADD_DATA":
            case "DSP_PREADD_DATA58":
                switch (from) {
                    case "A1_DATA":
                        return may(INMODE, 0, 2, 1);
                    case "A2_DATA":
                        return may(INMODE, 0, 2, 0);
                    case "B1_DATA":
                        return !to.equals("B2B1") || may(INMODE, 4, 1, 1);
                    case "B2_DATA":
                        return !to.equals("B2B1") || may(INMODE, 4, 1, 0);
                    case "INMODE":
                        return !isStatic(INMODE);
                    default:
                        return true;
                }
            case "DSP_PREADD":
            case "DSP_PREADD58":
                return !from.equals("D_DATA") || may(INMODE, 2, 1, 1);
            default:
                return true;
        }
    }

    /** Whether a multiplexer (X, Y, Z, W) may select an input bus */
    private boolean isSelectedBy(String in, String mux) {
        switch (mux) {
            case "X":
                switch (in) {
                    case "U_DATA":
                        return mayX(1);
                    case "A_ALU":
                    case "B_ALU":
                        return mayX(3);
                    case "P_FDBK":
                    case "P_FDBK_MSB":
                        return mayX(2);
                    default:
                        return true;
                }
            case "Y":
                switch (in) {
                    case "V_DATA":
                        return mayY(1);
                    case "C_DATA":
                        return mayY(3);
                    default:
                        return true;
                }
            case "Z":
                switch (in) {
                    case "PCIN":
                        return mayZ(1, 5);
                    case "P_FDBK":
                    case "P_FDBK_MSB":
                        return mayZ(2, 4, 6);
                    case "C_DATA":
                        return mayZ(3);
                    default:
                        return true;
                }
            case "W":
                switch (in) {
                    case "P_FDBK":
                    case "P_FDBK_MSB":
                        return mayW(1);
                    case "C_DATA":
                        return mayW(3);
                    default:
                        return true;
                }
            default:
                return true;
        }
    }
}
