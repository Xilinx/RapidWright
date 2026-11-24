/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.family.Corner;

public class TestFittedCheckTimes {

    private static float[] getTimes(FittedCheckTimes times, Cell c, String dataPin) {
        EDIFHierCellInst inst = c.getEDIFHierCellInst();
        EDIFHierPortInst data = inst.getPortInst(dataPin);
        EDIFHierPortInst clk = inst.getPortInst("CLK");
        Assertions.assertNotNull(data, c + " " + dataPin);
        Assertions.assertNotNull(clk, c + " CLK");
        return times.getSetupHold(data, clk);
    }

    /**
     * The address of a single-port LUT RAM (RAMS32) maps to both the read and the write address pins of its LUT
     * (e.g. ADR0 to A1 and WA1). Its setup and hold times are those of the write address pin, as for the write
     * address of a dual-port LUT RAM (RAMD32 WADR0 on WA1), whichever of the two pins the cell maps to first.
     */
    @Test
    public void testSinglePortLutRamAddress() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235_2022_1.dcp");
        for (FittedCheckTimes times : new FittedCheckTimes[]{new FittedCheckTimes(d),
                new FittedCheckTimes(d, Corner.SLOW_MAX, Corner.SLOW_MIN)}) {
            // The write address times of the dual-port LUT RAMs, by address bit
            Map<Integer, float[]> expected = new HashMap<>();
            for (Cell c : d.getCells()) {
                if (!c.getType().equals("RAMD32")) continue;
                for (int i = 0; i < 5; i++) {
                    float[] t = getTimes(times, c, "WADR" + i);
                    Assertions.assertTrue(t[0] > 0 && t[1] > 0, c + " WADR" + i + " " + Arrays.toString(t));
                    float[] prev = expected.putIfAbsent(i, t);
                    if (prev != null) {
                        Assertions.assertArrayEquals(prev, t);
                    }
                }
            }
            Assertions.assertEquals(5, expected.size());

            int numChecked = 0;
            boolean readPinFirst = false;
            for (Cell c : d.getCells()) {
                if (!c.getType().equals("RAMS32")) continue;
                for (int i = 0; i < 5; i++) {
                    String pin = "ADR" + i;
                    Assertions.assertEquals(new TreeSet<>(Arrays.asList("A" + (i + 1), "WA" + (i + 1))),
                            new TreeSet<>(FittedCheckTimes.getBELPins(c, pin)), c + " " + pin);
                    readPinFirst |= c.getPhysicalPinMapping(pin).equals("A" + (i + 1));
                    Assertions.assertArrayEquals(expected.get(i), getTimes(times, c, pin), c + " " + pin);
                    numChecked++;
                }
            }
            Assertions.assertTrue(numChecked >= 20);
            // (The case this covers: a cell pin that maps first to its read address pin)
            Assertions.assertTrue(readPinFirst);
        }
    }
}
