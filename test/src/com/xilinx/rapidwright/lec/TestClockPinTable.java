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

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * Checks which pins are excluded from cone checking as clock fanout.
 *
 * Getting this wrong is asymmetric. Marking a data pin as a clock silently
 * skips a real check, which is unsound; failing to mark a clock pin at worst
 * produces a spurious difference on a clock tree. The table is therefore
 * expected to err towards not a clock, and these tests pin that down.
 */
public class TestClockPinTable {

    private static ClockPinTable tableFor(Series series) {
        return new ClockPinTable(series, new LecLog(false, new PrintStream(
                new ByteArrayOutputStream())));
    }

    /**
     * A combinational cell driving a latch gate
     * terminates at that latch, making the gate a real cone endpoint. Both
     * RapidWright tables this one is seeded from say the opposite, so this is
     * the deviation most at risk of being undone by accident.
     */
    @ParameterizedTest
    @EnumSource(Series.class)
    public void testLatchGateIsNotAClock(Series series) {
        ClockPinTable table = tableFor(series);
        for (String latch : new String[] { "LDCE", "LDPE", "LDC", "LDP", "LDE", "LD" }) {
            Assertions.assertFalse(table.isClockPin(latch, "G"),
                    latch + ".G must be a checked cone endpoint, not clock fanout");
        }
    }

    /** The register clock pins, which are the bulk of what gets excluded. */
    @ParameterizedTest
    @EnumSource(Series.class)
    public void testRegisterClockPins(Series series) {
        ClockPinTable table = tableFor(series);
        for (String ff : new String[] { "FDRE", "FDSE", "FDCE", "FDPE" }) {
            Assertions.assertTrue(table.isClockPin(ff, "C"), ff + ".C should be a clock");
            Assertions.assertFalse(table.isClockPin(ff, "D"), ff + ".D must be checked");
            Assertions.assertFalse(table.isClockPin(ff, "CE"), ff + ".CE must be checked");
            Assertions.assertFalse(table.isClockPin(ff, "R"), ff + ".R must be checked");
        }
        Assertions.assertTrue(table.isClockPin("SRL16E", "CLK"));
        Assertions.assertFalse(table.isClockPin("SRL16E", "D"));
        Assertions.assertFalse(table.isClockPin("SRL16E", "A0"));
    }

    /**
     * Combinational primitives must have no clock pins at all. A false positive
     * here would cut a cone in the middle.
     */
    @ParameterizedTest
    @EnumSource(Series.class)
    public void testCombinationalCellsHaveNoClockPins(Series series) {
        ClockPinTable table = tableFor(series);
        Assertions.assertFalse(table.isClockPin("LUT6", "I0"));
        Assertions.assertFalse(table.isClockPin("CARRY8", "CI"));
        Assertions.assertFalse(table.isClockPin("CARRY8", "S"));
        Assertions.assertFalse(table.isClockPin("MUXF7", "S"));
        Assertions.assertFalse(table.isClockPin("LUT6CY", "I4"));
        Assertions.assertFalse(table.isClockPin("LOOKAHEAD8", "CIN"));
    }

    /** Block RAM and ultra RAM clock pins, which differ per series. */
    @Test
    public void testMemoryClockPins() {
        Assertions.assertTrue(tableFor(Series.UltraScalePlus).isClockPin("RAMB36E2", "CLKARDCLK"));
        Assertions.assertTrue(tableFor(Series.UltraScalePlus).isClockPin("RAMB18E2", "CLKBWRCLK"));
        Assertions.assertTrue(tableFor(Series.UltraScalePlus).isClockPin("URAM288", "CLK"));
        Assertions.assertTrue(tableFor(Series.Series7).isClockPin("RAMB36E1", "CLKARDCLK"));
        Assertions.assertTrue(tableFor(Series.Versal).isClockPin("RAMB36E5", "CLKARDCLKL"));
        Assertions.assertFalse(tableFor(Series.UltraScalePlus).isClockPin("RAMB36E2", "ADDRARDADDR"));
        Assertions.assertFalse(tableFor(Series.UltraScalePlus).isClockPin("RAMB36E2", "WEA"));
    }

    /**
     * A short pin name on an unfamiliar cell must not be guessed to be a clock.
     * The fallback is deliberately restricted to names built around CLK.
     */
    @Test
    public void testNameFallbackIsConservative() {
        ClockPinTable table = tableFor(Series.UltraScalePlus);
        Assertions.assertFalse(table.isClockPin("SOME_UNKNOWN_IP", "C"),
                "A bare C on an unknown cell must not be assumed to be a clock");
        Assertions.assertFalse(table.isClockPin("SOME_UNKNOWN_IP", "G"));
        Assertions.assertFalse(table.isClockPin("SOME_UNKNOWN_IP", "DATA"));
        Assertions.assertTrue(table.isClockPin("SOME_UNKNOWN_IP", "CLK"));
        Assertions.assertTrue(table.isClockPin("SOME_UNKNOWN_IP", "WRCLK"));
    }

    /**
     * Every pin the table names must actually exist on the primitive, with the
     * spelling the netlist will use. A typo would silently stop excluding a
     * clock, which is exactly the kind of error that hides until a real design
     * produces a puzzling difference.
     */
    @ParameterizedTest
    @EnumSource(Series.class)
    public void testEveryListedPinExistsOnThePrimitive(Series series) {
        EDIFLibrary primitives = loadPrimitives();
        ClockPinTable table = tableFor(series);
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (String cellTypeName : table.getListedCellTypes()) {
            EDIFCell cell = primitives.getCell(cellTypeName);
            if (cell == null) {
                continue;
            }
            for (String pinName : table.getClockPins(cellTypeName)) {
                checked++;
                EDIFPort port = cell.getPort(pinName);
                if (port == null) {
                    port = cell.getPortByPortInstName(pinName);
                }
                if (port == null) {
                    problems.add(cellTypeName + "." + pinName + " does not exist");
                } else if (port.isOutput()) {
                    problems.add(cellTypeName + "." + pinName + " is an output, not a clock input");
                }
            }
        }
        Assertions.assertTrue(problems.isEmpty(), "Clock pin table errors: " + problems);
        Assertions.assertTrue(checked > 0, "No clock pins were cross-checked for " + series);
    }

    /**
     * Loads the unisim primitive library, skipping the test rather than failing
     * if the data files are unavailable in this environment.
     */
    private static EDIFLibrary loadPrimitives() {
        EDIFLibrary primitives = null;
        try {
            primitives = Design.getPrimitivesLibrary();
        } catch (RuntimeException e) {
            Assumptions.assumeTrue(false, "Unisim primitive library is unavailable: "
                    + e.getMessage());
        }
        Assumptions.assumeTrue(primitives != null, "Unisim primitive library is unavailable");
        Set<String> names = primitives.getCellMap().keySet();
        Assumptions.assumeTrue(names.contains("FDRE"), "Unisim primitive library looks empty");
        return primitives;
    }
}
