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

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes the literals found in EDIF property values written by Vivado: Verilog numbers (e.g.
 * 1'b1, 64'h0123, 'd12 or 12) and booleans (TRUE/FALSE, YES/NO, or a number). Shared by the
 * optimizer and the comparison tools, which otherwise evaluate netlists independently.
 */
public final class VerilogLiterals {

    private static final Pattern NUMBER = Pattern.compile(
            "(\\d*)'[sS]?([bBoOdDhH])([0-9a-fA-F_]+)");

    private VerilogLiterals() {
    }

    /**
     * @param value A property value.
     * @return The value of a Verilog number literal (sized or not) or of a decimal number, null
     * if value is not a number (including literals with x or z digits).
     */
    public static BigInteger parseNumber(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        try {
            Matcher m = NUMBER.matcher(v);
            if (!m.matches()) {
                return v.matches("\\d+") ? new BigInteger(v) : null;
            }
            char base = Character.toLowerCase(m.group(2).charAt(0));
            int radix = base == 'b' ? 2 : base == 'o' ? 8 : base == 'h' ? 16 : 10;
            return new BigInteger(m.group(3).replace("_", ""), radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param value A property value.
     * @return TRUE or FALSE for TRUE/FALSE or YES/NO (in any case) and for numbers (true if not
     * zero), null for anything else.
     */
    public static Boolean parseBoolean(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.equalsIgnoreCase("TRUE") || v.equalsIgnoreCase("YES")) {
            return Boolean.TRUE;
        }
        if (v.equalsIgnoreCase("FALSE") || v.equalsIgnoreCase("NO")) {
            return Boolean.FALSE;
        }
        BigInteger n = parseNumber(v);
        return n == null ? null : n.signum() != 0;
    }

    /** @return True if value is a boolean literal that is true (see {@link #parseBoolean}). */
    public static boolean isTrue(String value) {
        return Boolean.TRUE.equals(parseBoolean(value));
    }
}
