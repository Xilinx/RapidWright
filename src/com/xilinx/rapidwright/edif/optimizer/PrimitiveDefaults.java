/*
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

package com.xilinx.rapidwright.edif.optimizer;

import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;

/**
 * Values of the primitive properties that both the optimizer and the comparison tools evaluate,
 * with the defaults (from the unisim simulation models) that apply when an instance does not set
 * them. Shared so that both sides agree on the semantics of a netlist, while they evaluate it
 * independently.
 */
public final class PrimitiveDefaults {

    /** Default values per primitive and property. */
    private static final Map<String, Map<String, String>> DEFAULTS = new HashMap<>();

    private static void set(String key, String value, String... primitives) {
        for (String p : primitives) {
            DEFAULTS.computeIfAbsent(p, k -> new HashMap<>()).put(key, value);
        }
    }

    static {
        set("INIT", "1'b0", "FDRE", "FDCE", "LDCE");
        set("INIT", "1'b1", "FDSE", "FDPE", "LDPE");
        set("INIT", "16'h0000", "SRL16E");
        set("INIT", "32'h00000000", "SRLC32E");
    }

    private PrimitiveDefaults() {
    }

    private static boolean isPrimitive(EDIFCell cell) {
        EDIFLibrary lib = cell.getLibrary();
        return lib != null && lib.isHDIPrimitivesLibrary();
    }

    /**
     * @param cell A cell.
     * @param key A property name.
     * @return The default value of the property if cell is a primitive with a known default (an
     * IS_*_INVERTED inversion defaults to 1'b0), else null.
     */
    public static String getDefault(EDIFCell cell, String key) {
        if (!isPrimitive(cell)) {
            return null;
        }
        Map<String, String> m = DEFAULTS.get(cell.getName());
        String v = m == null ? null : m.get(key);
        if (v == null && key.startsWith("IS_") && key.endsWith("_INVERTED")) {
            v = "1'b0";
        }
        return v;
    }

    /**
     * @param inst A leaf instance.
     * @param key A property name.
     * @return The value of the property of the instance: its own value, else its primitive's
     * default (see {@link #getDefault}), else null.
     */
    public static String getValue(EDIFCellInst inst, String key) {
        EDIFPropertyValue v = inst.getProperty(key);
        return v != null ? v.getValue() : getDefault(inst.getCellType(), key);
    }
}
