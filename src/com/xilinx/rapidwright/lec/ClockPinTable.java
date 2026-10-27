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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.tools.NetlistClockDetection;
import com.xilinx.rapidwright.device.Series;

/**
 * Which input pins of a primitive are clock pins, and therefore excluded from
 * cone checking.
 *
 * The logical netlist carries no clock metadata at all: there is no
 * {@code isClock} property on an EDIF port, and the physical
 * {@code BELPin.isClock()} needs a placed design. RapidWright's own table,
 * {@link NetlistClockDetection#seriesPrimsToClockPins}, is populated for Versal
 * only, so the other three series are authored here.
 *
 * <h2>Two deliberate deviations</h2>
 *
 * The latch gate pin {@code G} is <b>not</b> treated as a clock, even though
 * both {@code NetlistClockDetection} and {@code ClockTools} classify it as one.
 * A combinational cell driving a latch gate terminates at that latch, making
 * {@code G} a real cone endpoint whose function is checked.
 *
 * Where a cell type is absent from the table, a pin is treated as a clock only
 * if its name unambiguously says so. Erring towards <i>not</i> a clock is the
 * safe direction for soundness: a missed clock pin at worst produces a spurious
 * difference on a clock tree, whereas a data pin wrongly excluded silently
 * skips a real check.
 */
public class ClockPinTable {

    /** Warning category raised when the name based fallback decides a pin is a clock. */
    public static final String UNLISTED_CLOCK_PIN = "Clock pin identified by name";

    /**
     * The latch gate pin, which is treated as a checked cone endpoint rather
     * than clock fanout.
     */
    public static final String LATCH_GATE_PIN = "G";

    private static final Map<Series, Map<String, Set<String>>> TABLES =
            new HashMap<Series, Map<String, Set<String>>>();

    static {
        for (Series series : Series.values()) {
            TABLES.put(series, buildTable(series));
        }
    }

    private final Series series;

    private final Map<String, Set<String>> table;

    private final LecLog log;

    public ClockPinTable(Series series, LecLog log) {
        this.series = series;
        this.log = log;
        Map<String, Set<String>> found = TABLES.get(series);
        this.table = found == null ? Collections.<String, Set<String>>emptyMap() : found;
    }

    /**
     * Assembles the clock pin table for one series: the primitives common to all
     * series, then the series specific block RAM, ultra RAM and DSP families,
     * then RapidWright's own Versal table where it exists.
     */
    private static Map<String, Set<String>> buildTable(Series series) {
        Map<String, Set<String>> map = new HashMap<>();
        addCommon(map);
        switch (series) {
        case Series7:
            addSeries7(map);
            break;
        case UltraScale:
            addUltraScale(map);
            break;
        case UltraScalePlus:
            addUltraScalePlus(map);
            break;
        case Versal:
            addVersal(map);
            break;
        default:
            break;
        }
        mergeRapidWrightTable(map, series);
        map.remove("LDCE");
        map.remove("LDPE");
        map.remove("LDC");
        map.remove("LDP");
        map.remove("LDE");
        map.remove("LD");
        return map;
    }

    /**
     * Folds in RapidWright's own table, then strips the latch gate entries it
     * contains, since a latch gate is a checked cone endpoint here.
     */
    private static void mergeRapidWrightTable(Map<String, Set<String>> map, Series series) {
        Map<String, Set<String>> rw = NetlistClockDetection.getPrimsToClockPins(series);
        if (rw == null) {
            return;
        }
        for (Map.Entry<String, Set<String>> e : rw.entrySet()) {
            Set<String> pins = new HashSet<>(e.getValue());
            if (isLatch(e.getKey())) {
                pins.remove(LATCH_GATE_PIN);
            }
            if (!pins.isEmpty()) {
                map.computeIfAbsent(e.getKey(), k -> new HashSet<>()).addAll(pins);
            }
        }
    }

    /**
     * Registers, shift registers and distributed RAM, present in every series.
     *
     * Note the split between distributed RAM macros and the primitives they
     * decompose into: the macros ({@code RAM64X1D}) name their write clock
     * {@code WCLK}, whereas the primitives ({@code RAMD64E}, {@code RAMS32})
     * name it {@code CLK}. Every pin here is cross checked against the real
     * unisim interface by {@code TestClockPinTable}.
     */
    private static void addCommon(Map<String, Set<String>> map) {
        put(map, "C", "FDRE", "FDSE", "FDCE", "FDPE", "FD", "FDC", "FDP", "FDR", "FDS", "FDE",
                "FD_1", "FDCE_1", "FDPE_1", "FDRE_1", "FDSE_1", "ODDRE1");
        put(map, new String[] { "C", "CB" }, "IDDRE1");
        put(map, "CLK", "SRL16", "SRL16E", "SRL16_1", "SRL16E_1", "SRLC16", "SRLC16E",
                "SRLC16_1", "SRLC16E_1", "SRLC32E", "SRLC32E_1", "CFGLUT5");
        put(map, "CLK", "RAMD32", "RAMD64E", "RAMS32", "RAMS64E", "RAMD64E5", "RAMS64E1");
        put(map, "WCLK", "RAM32X1S", "RAM32X1S_1", "RAM32X1D", "RAM32X1D_1", "RAM32X2S",
                "RAM32M", "RAM32M16", "RAM64X1S", "RAM64X1S_1", "RAM64X1D", "RAM64X1D_1",
                "RAM64M", "RAM64M8", "RAM128X1S", "RAM128X1S_1", "RAM128X1D", "RAM256X1S",
                "RAM256X1D", "RAM512X1S", "RAM32X16DR8", "RAM64X8SW");
        put(map, "I", "BUFG", "BUFGCE", "BUFGCE_1", "BUFGCE_DIV", "BUFH", "BUFHCE", "BUFR",
                "BUFMR", "BUFMRCE", "BUFIO", "BUFG_GT", "BUFG_PS", "BUFG_FABRIC", "BUFGCE_HDIO",
                "MBUFGCE", "MBUFGCE_DIV", "MBUFG_GT", "MBUFG_PS");
        put(map, new String[] { "I0", "I1" }, "BUFGCTRL", "BUFGMUX", "BUFGMUX_1",
                "BUFGMUX_CTRL", "BUFHCE_CTRL", "MBUFGCTRL");
        put(map, new String[] { "RDCLK", "WRCLK" }, "FIFO18E1", "FIFO36E1", "FIFO18E2",
                "FIFO36E2");
    }

    private static void addSeries7(Map<String, Set<String>> map) {
        put(map, new String[] { "CLKA", "CLKB" }, "RAMB16BWER");
        put(map, new String[] { "CLKAWRCLK", "CLKBRDCLK" }, "RAMB8BWER");
        put(map, new String[] { "CLKARDCLK", "CLKBWRCLK" }, "RAMB18E1", "RAMB36E1");
        put(map, "CLK", "DSP48E1", "DSP48A1", "DSP48");
    }

    private static void addUltraScale(Map<String, Set<String>> map) {
        put(map, new String[] { "CLKARDCLK", "CLKBWRCLK" }, "RAMB18E2", "RAMB36E2");
        put(map, "CLK", "DSP48E2", "URAM288", "URAM288_BASE");
    }

    private static void addUltraScalePlus(Map<String, Set<String>> map) {
        addUltraScale(map);
    }

    /**
     * Versal block RAM comes in two shapes. The ordinary {@code RAMB18E5} and
     * {@code RAMB36E5} carry a single pair of clocks, whereas the internal
     * {@code _INT} variants expose the lower and upper halves separately and
     * suffix their clocks accordingly.
     */
    private static void addVersal(Map<String, Set<String>> map) {
        put(map, new String[] { "CLKARDCLK", "CLKBWRCLK" }, "RAMB18E5", "RAMB36E5",
                "RAMB18E5_INT");
        put(map, new String[] { "CLKARDCLKL", "CLKARDCLKU", "CLKBWRCLKL", "CLKBWRCLKU" },
                "RAMB36E5_INT");
        put(map, "CLK", "DSP58", "DSP48E5", "DSPCPLX", "DSPFP32", "URAM288E5",
                "URAM288E5_BASE", "URAM288E5_INT");
    }

    private static void put(Map<String, Set<String>> map, String pin, String... cellTypes) {
        put(map, new String[] { pin }, cellTypes);
    }

    private static void put(Map<String, Set<String>> map, String[] pins, String... cellTypes) {
        for (String cellType : cellTypes) {
            map.computeIfAbsent(cellType, k -> new HashSet<>()).addAll(Arrays.asList(pins));
        }
    }

    private static boolean isLatch(String cellTypeName) {
        return cellTypeName.startsWith("LD");
    }

    /**
     * Decides whether a pin is clock fanout and therefore not a cone root.
     *
     * @param cellTypeName The primitive type name.
     * @param pinName      The pin name, without any bus index for scalar pins.
     * @return True if the pin is a clock.
     */
    public boolean isClockPin(String cellTypeName, String pinName) {
        if (LATCH_GATE_PIN.equals(pinName) && isLatch(cellTypeName)) {
            return false;
        }
        Set<String> pins = table.get(cellTypeName);
        if (pins != null) {
            if (pins.contains(pinName)) {
                return true;
            }
            if (!looksLikeClockName(pinName)) {
                return false;
            }
        }
        if (looksLikeClockName(pinName)) {
            if (log != null) {
                log.warn(UNLISTED_CLOCK_PIN, cellTypeName + "." + pinName, null);
            }
            return true;
        }
        return false;
    }

    /**
     * Name based fallback for cell types absent from the table. Deliberately
     * narrow: only names built around the token CLK qualify, so a short data pin
     * name such as C on an unknown cell is never mistaken for a clock.
     */
    private static boolean looksLikeClockName(String pinName) {
        return pinName.startsWith("CLK") || pinName.endsWith("CLK");
    }

    public Series getSeries() {
        return series;
    }

    /**
     * @return The clock pins listed for a cell type, or an empty set if the type
     *         is not in the table.
     */
    public Set<String> getClockPins(String cellTypeName) {
        Set<String> pins = table.get(cellTypeName);
        return pins == null ? Collections.<String>emptySet() : Collections.unmodifiableSet(pins);
    }

    /** @return The cell type names carrying at least one listed clock pin. */
    public Set<String> getListedCellTypes() {
        return Collections.unmodifiableSet(table.keySet());
    }
}
