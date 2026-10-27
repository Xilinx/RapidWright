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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

/**
 * The identity and configuration of a terminal, for the part of the comparison
 * that cone equivalence cannot reach.
 *
 * Cones prove the logic <i>arriving at</i> a terminal. They say nothing about
 * the terminal itself, so two netlists could agree on every cone and still
 * differ because a flip flop was changed from synchronous to asynchronous
 * reset, or a memory's write mode was altered. Those are compared here.
 *
 * <h2>Why some attributes are deliberately excluded</h2>
 *
 * An {@code IS_<pin>_INVERTED} attribute on a pin whose cone is checked is
 * <i>folded into that cone's function</i> rather than compared. Absorbing an
 * inverter into the flip flop's data pin, instead of leaving it as a separate
 * cell, is a legitimate implementation choice that does not change the logic;
 * comparing the attribute as well would report it as a difference and then the
 * cone comparison would report the compensating change too.
 *
 * Placement, timing and physical attributes are excluded because the
 * comparison is of logic, not implementation.
 */
public class TerminalAttributes {

    /** Attributes that describe implementation rather than logic. */
    private static final Set<String> IGNORED = buildIgnored();

    private final String cellTypeName;

    private final Map<String, String> properties;

    private TerminalAttributes(String cellTypeName, Map<String, String> properties) {
        this.cellTypeName = cellTypeName;
        this.properties = properties;
    }

    private static Set<String> buildIgnored() {
        Set<String> ignored = new TreeSet<>();
        ignored.add("LOC");
        ignored.add("BEL");
        ignored.add("DONT_TOUCH");
        ignored.add("KEEP");
        ignored.add("KEEP_HIERARCHY");
        ignored.add("MARK_DEBUG");
        ignored.add("RLOC");
        ignored.add("HLUTNM");
        ignored.add("SOFT_HLUTNM");
        ignored.add("BOX_TYPE");
        ignored.add("XILINX_LEGACY_PRIM");
        ignored.add("X_ORIG_CELL_NAME");
        ignored.add("ORIG_CELL_NAME");
        ignored.add("X_ORIG_MODULE_NAME");
        return ignored;
    }

    /**
     * Captures the comparable attributes of one terminal.
     *
     * @param cellTypeName The primitive type.
     * @param inst         The instance carrying the properties.
     * @param foldedPins   Pins whose inversion attribute is folded into a cone
     *                     and must therefore not be compared here.
     */
    public static TerminalAttributes of(String cellTypeName, EDIFCellInst inst,
            Set<String> foldedPins) {
        Map<String, String> properties = new TreeMap<>();
        if (inst != null && inst.getPropertiesMap() != null) {
            for (Map.Entry<String, EDIFPropertyValue> e : inst.getPropertiesMap().entrySet()) {
                String key = e.getKey();
                if (IGNORED.contains(key) || isFoldedInversion(key, foldedPins)) {
                    continue;
                }
                EDIFPropertyValue value = e.getValue();
                properties.put(key, value == null ? "" : String.valueOf(value.getValue()));
            }
        }
        return new TerminalAttributes(cellTypeName, properties);
    }

    /**
     * @return True if the key is an inversion attribute for a pin whose cone
     *         already accounts for it.
     */
    private static boolean isFoldedInversion(String key, Set<String> foldedPins) {
        if (!key.startsWith("IS_") || !key.endsWith("_INVERTED")) {
            return false;
        }
        String pin = key.substring(3, key.length() - "_INVERTED".length());
        return foldedPins.contains(pin);
    }

    public String getCellTypeName() {
        return cellTypeName;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    /**
     * Compares against the corresponding terminal in the other netlist.
     *
     * @return Human readable descriptions of every disagreement, empty if the
     *         two terminals are configured identically.
     */
    public List<String> diff(TerminalAttributes other) {
        List<String> differences = new ArrayList<>();
        if (!cellTypeName.equals(other.cellTypeName)) {
            differences.add("cell type " + cellTypeName + " vs " + other.cellTypeName);
            return differences;
        }
        Set<String> keys = new TreeSet<>(properties.keySet());
        keys.addAll(other.properties.keySet());
        for (String key : keys) {
            String mine = properties.get(key);
            String theirs = other.properties.get(key);
            if (mine == null) {
                differences.add(key + " absent vs " + theirs);
            } else if (theirs == null) {
                differences.add(key + " " + mine + " vs absent");
            } else if (!mine.equals(theirs)) {
                differences.add(key + " " + mine + " vs " + theirs);
            }
        }
        return differences;
    }
}
