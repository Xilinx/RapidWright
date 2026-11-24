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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestDspControl {

    private static final long ALL = 0x1ff;

    /** OPMODE known on a mask, ALUMODE/CARRYINSEL/INMODE all 0 */
    private static DspControl opmode(long mask, long opmode) {
        return new DspControl(new long[]{mask, 0xf, 0x7, 0x1f}, new long[]{opmode, 0, 0, 0});
    }

    @Test
    public void testDsp58Multiplexers() {
        // W = 0, Z = C, Y = M, X = M: the multiplier plus C
        DspControl c = opmode(ALL, 0b00_011_01_01);
        Assertions.assertTrue(c.keepsArc("DSP_ALUMUX", "U_DATA", "X"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUMUX", "V_DATA", "Y"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUMUX", "C_DATA", "Z"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUMUX", "C_DATA", "W"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUMUX", "C_DATA", "Y"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUMUX", "A_ALU", "X"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUMUX", "P_FDBK", "X"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUMUX", "P_FDBK", "Z"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUMUX", "PCIN", "Z"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUMUX", "OPMODE_DATA", "X"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUMUX", "CARRYIN_DATA", "CIN"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUADD", "W", "ALU_OUT"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUADD", "ALUMODE_DATA", "ALU_OUT"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUADD", "Z", "ALU_OUT"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUADD", "X", "ALU_OUT"));
        // Y selecting all ones carries no data
        Assertions.assertFalse(opmode(ALL, 0b00_000_10_11).keepsArc("DSP_ALUADD", "Y", "ALU_OUT"));
    }

    @Test
    public void testDynamicBits() {
        // OPMODE[5:4] driven by logic: Z may be 0, PCIN, P or C; W and X, Y known 0
        DspControl c = opmode(ALL & ~0x30L, 0);
        Assertions.assertTrue(c.keepsArc("DSP_ALUMUX", "PCIN", "Z"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUMUX", "C_DATA", "Z"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUMUX", "P_FDBK", "Z"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUMUX", "OPMODE_DATA", "Z"));
        Assertions.assertTrue(c.keepsArc("DSP_ALUADD", "Z", "ALU_OUT"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUMUX", "C_DATA", "W"));
        Assertions.assertFalse(c.keepsArc("DSP_ALUADD", "X", "ALU_OUT"));
        // Nothing known: every arc
        DspControl none = new DspControl(new long[4], new long[4]);
        Assertions.assertTrue(none.keepsArc("DSP_ALUMUX", "A_ALU", "X"));
        Assertions.assertTrue(none.keepsArc("DSP_ALUADD", "W", "ALU_OUT"));
        Assertions.assertTrue(none.keepsArc("DSP_PREADD_DATA58", "A1_DATA", "A2A1"));
    }

    @Test
    public void testUltraScalePlusAluAndInmode() {
        // X = P only (Z, W 0): the P feedback through X is timed, C is not
        DspControl c = opmode(ALL, 0b00_000_00_10);
        Assertions.assertTrue(c.keepsArc("DSP_ALU", "P_FDBK", "ALU_OUT"));
        Assertions.assertFalse(c.keepsArc("DSP_ALU", "C_DATA", "ALU_OUT"));
        Assertions.assertFalse(c.keepsArc("DSP_ALU", "U_DATA", "ALU_OUT"));
        // W = C
        Assertions.assertTrue(opmode(ALL, 0b11_000_00_00).keepsArc("DSP_ALU", "C_DATA", "ALU_OUT"));
        // INMODE = 0b10001: A1, B1, no D
        DspControl in = new DspControl(new long[]{0, 0, 0, 0x1f}, new long[]{0, 0, 0, 0b10001});
        Assertions.assertTrue(in.keepsArc("DSP_PREADD_DATA58", "A1_DATA", "A2A1"));
        Assertions.assertFalse(in.keepsArc("DSP_PREADD_DATA58", "A2_DATA", "A2A1"));
        Assertions.assertFalse(in.keepsArc("DSP_PREADD_DATA58", "A2_DATA", "PREADD_AB"));
        Assertions.assertTrue(in.keepsArc("DSP_PREADD_DATA58", "B1_DATA", "B2B1"));
        Assertions.assertFalse(in.keepsArc("DSP_PREADD_DATA58", "B2_DATA", "B2B1"));
        Assertions.assertTrue(in.keepsArc("DSP_PREADD_DATA58", "B2_DATA", "PREADD_AB"));
        Assertions.assertFalse(in.keepsArc("DSP_PREADD58", "D_DATA", "AD"));
        // INMODE[1] zeroes A
        DspControl zeroA = new DspControl(new long[]{0, 0, 0, 0x1f}, new long[]{0, 0, 0, 0b00010});
        Assertions.assertFalse(zeroA.keepsArc("DSP_PREADD_DATA58", "A2_DATA", "A2A1"));
        Assertions.assertFalse(zeroA.keepsArc("DSP_PREADD_DATA58", "A1_DATA", "A2A1"));
    }

    /** A LUT6CY half's propagate and generate outputs do not depend on its carry input I4 */
    @Test
    public void testLutCarryInput() {
        Assertions.assertTrue(FamilyArcProvider.isLutCarryInput("LUTCY2", "I4", "GE"));
        Assertions.assertTrue(FamilyArcProvider.isLutCarryInput("LUTCY1", "I4", "PROP"));
        Assertions.assertFalse(FamilyArcProvider.isLutCarryInput("LUTCY2", "I4", "O"));
        Assertions.assertFalse(FamilyArcProvider.isLutCarryInput("LUTCY2", "I3", "GE"));
        Assertions.assertFalse(FamilyArcProvider.isLutCarryInput("LUT6", "I4", "O"));
    }
}
