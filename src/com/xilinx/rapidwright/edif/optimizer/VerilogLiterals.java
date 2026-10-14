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

    /** Width of an unsized Verilog literal (the minimum for binary, octal and hex ones). */
    private static final int UNSIZED_WIDTH = 32;

    private VerilogLiterals() {
    }

    /** A decoded number: its value and width in bits (-1 for a plain decimal number). */
    private static final class Literal {
        final BigInteger value;
        final int width;

        Literal(BigInteger value, int width) {
            this.value = value;
            this.width = width;
        }
    }

    private static Literal parse(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        try {
            if (DECIMAL.matcher(v).matches()) {
                return new Literal(new BigInteger(v), -1);
            }
            Matcher m = NUMBER.matcher(v);
            if (!m.matches()) {
                return null;
            }
            boolean signed = !m.group(2).isEmpty();
            char base = Character.toLowerCase(m.group(3).charAt(0));
            int bitsPerDigit = base == 'b' ? 1 : base == 'o' ? 3 : base == 'h' ? 4 : 0;
            String digits = m.group(4).replace("_", "");
            BigInteger n = new BigInteger(digits, bitsPerDigit == 0 ? 10 : 1 << bitsPerDigit);
            int width;
            if (!m.group(1).isEmpty()) {
                width = Integer.parseInt(m.group(1));
                if (width == 0) {
                    return null;
                }
            } else if (bitsPerDigit == 0) {
                // Unsized decimal: 32 bits (as in Vivado, e.g. 'sd4294967295 is -1)
                width = UNSIZED_WIDTH;
            } else {
                // Unsized binary/octal/hex: 32 bits, more if its digits (leading zeros
                // included) need them (as in Vivado, e.g. 'sh0100000000 has 40 bits)
                width = Math.max(UNSIZED_WIDTH, digits.length() * bitsPerDigit);
            }
            BigInteger range = BigInteger.ONE.shiftLeft(width);
            n = n.mod(range);
            if (signed && n.testBit(width - 1)) {
                n = n.subtract(range);
            }
            return new Literal(n, width);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @param value A property value.
     * @return The value of a decimal number (optionally signed) or of a Verilog number literal.
     * As in Verilog, a literal is truncated to its width (see {@link #parseWidth}) and a signed
     * literal (e.g. 8'shff) is a two's complement number of its width, so 8'shff is -1. Null if
     * value is not a number (including literals with x or z digits).
     */
    public static BigInteger parseNumber(String value) {
        Literal l = parse(value);
        return l == null ? null : l.value;
    }

    /**
     * @param value A property value.
     * @return The width in bits of a Verilog number literal: its size if sized; for an unsized
     * one, 32 if decimal, else the larger of 32 and the bits its digits need (leading zeros
     * included); -1 for a plain decimal number; null if value is not a number.
     */
    public static Integer parseWidth(String value) {
        Literal l = parse(value);
        return l == null ? null : l.width;
    }

    /**
     * @param n A number (see {@link #parseNumber}).
     * @param width The width in bits of a bit-vector parameter.
     * @return The value of the parameter when assigned n: n truncated, or sign-extended if
     * negative, to width bits (as a non-negative number).
     */
    public static BigInteger toWidth(BigInteger n, int width) {
        return n.mod(BigInteger.ONE.shiftLeft(width));
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
