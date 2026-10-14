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
 * 1'b1, 64'h0123, 8'shff, 'd12, 12 or -3) and booleans (TRUE/FALSE, YES/NO, or a number).
 * Shared by the optimizer and the comparison tools, which otherwise evaluate netlists
 * independently.
 */
public final class VerilogLiterals {

    private static final Pattern NUMBER = Pattern.compile(
            "(\\d*)'([sS]?)([bBoOdDhH])([0-9a-fA-F_]+)");

    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d+");

    /** Width of an unsized Verilog literal. */
    private static final int UNSIZED_WIDTH = 32;

    private VerilogLiterals() {
    }

    /**
     * @param value A property value.
     * @return The value of a decimal number (optionally signed) or of a Verilog number literal.
     * As in Verilog, a sized literal is truncated to its width and a signed literal (e.g. 8'shff)
     * is a two's complement number of its width (32 bits if unsized), so 8'shff is -1. Null if
     * value is not a number (including literals with x or z digits).
     */
    public static BigInteger parseNumber(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        try {
            if (DECIMAL.matcher(v).matches()) {
                return new BigInteger(v);
            }
            Matcher m = NUMBER.matcher(v);
            if (!m.matches()) {
                return null;
            }
            boolean signed = !m.group(2).isEmpty();
            int width = m.group(1).isEmpty() ? (signed ? UNSIZED_WIDTH : -1)
                    : Integer.parseInt(m.group(1));
            if (width == 0) {
                return null;
            }
            char base = Character.toLowerCase(m.group(3).charAt(0));
            int radix = base == 'b' ? 2 : base == 'o' ? 8 : base == 'h' ? 16 : 10;
            BigInteger n = new BigInteger(m.group(4).replace("_", ""), radix);
            if (width > 0) {
                BigInteger range = BigInteger.ONE.shiftLeft(width);
                n = n.mod(range);
                if (signed && n.testBit(width - 1)) {
                    n = n.subtract(range);
                }
            }
            return n;
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
