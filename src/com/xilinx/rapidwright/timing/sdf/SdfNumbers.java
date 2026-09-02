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

package com.xilinx.rapidwright.timing.sdf;

/**
 * Exact, allocation-free conversion between SDF delay literals and the {@code int} tenths
 * representation used by {@link SdfDelayValues}.
 *
 * Every delay value Vivado writes matches {@code -?[0-9]+\.[0-9]} exactly, so tenths are a lossless
 * representation and formatting is a fixed {@code sign, abs/10, '.', abs%10}. Parsing and
 * formatting are hand-rolled here rather than delegated to {@code Float.parseFloat} /
 * {@code String.format} because a large SDF holds tens of millions of these values, and because
 * a float round-trip would not reproduce the input bytes.
 */
class SdfNumbers {

    /**
     * Largest magnitude in tenths that can be stored. Only {@link SdfDelayValues#NEG_ZERO}, which
     * is {@link Integer#MIN_VALUE}, is reserved, so the full positive range is available and its
     * negation is still distinct from the sentinel.
     */
    private static final int MAX_TENTHS = Integer.MAX_VALUE;

    private SdfNumbers() {
    }

    /**
     * Parses an SDF delay literal into tenths of the file's time unit.
     *
     * Accepts an optional leading sign, one or more integer digits, a mandatory {@code .}, and one
     * or more fractional digits. Vivado always writes exactly one fractional digit; additional
     * digits are accepted only if they are zero, since anything else could not be represented
     * exactly and must not be silently rounded.
     *
     * @param s The literal to parse.
     * @return The value in tenths, or {@link SdfDelayValues#NEG_ZERO} for a literal {@code -0.0}.
     * @throws NumberFormatException If the literal is malformed or not representable.
     */
    public static int parseTenths(String s) {
        return parseTenths(s, 0, s.length());
    }

    /**
     * Parses an SDF delay literal into tenths of the file's time unit.
     *
     * @param s Character source.
     * @param from Index of the first character, inclusive.
     * @param to Index one past the last character.
     * @return The value in tenths, or {@link SdfDelayValues#NEG_ZERO} for a literal {@code -0.0}.
     * @throws NumberFormatException If the literal is malformed or not representable.
     */
    public static int parseTenths(CharSequence s, int from, int to) {
        int i = from;
        if (i >= to) {
            throw new NumberFormatException("ERROR: empty SDF delay value");
        }
        boolean negative = false;
        if (s.charAt(i) == '-') {
            negative = true;
            i++;
        } else if (s.charAt(i) == '+') {
            i++;
        }

        long value = 0;
        int intDigits = 0;
        while (i < to) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            value = value * 10 + (c - '0');
            if (value > MAX_TENTHS) {
                throw new NumberFormatException("ERROR: SDF delay value out of range: "
                        + s.subSequence(from, to));
            }
            intDigits++;
            i++;
        }
        if (intDigits == 0) {
            throw new NumberFormatException("ERROR: malformed SDF delay value: "
                    + s.subSequence(from, to));
        }

        // Scale the integer part to tenths, then fold in the first fractional digit.
        value *= 10;
        if (value > MAX_TENTHS) {
            throw new NumberFormatException("ERROR: SDF delay value out of range: "
                    + s.subSequence(from, to));
        }

        if (i >= to || s.charAt(i) != '.') {
            // Required, not optional: the writer always emits one fractional digit, so accepting
            // "1" would mean writing back "1.0" and quietly breaking the round-trip guarantee.
            throw new NumberFormatException("ERROR: SDF delay value has no fractional digit: "
                    + s.subSequence(from, to));
        }
        {
            i++;
            int fracDigits = 0;
            while (i < to) {
                char c = s.charAt(i);
                if (c < '0' || c > '9') break;
                if (fracDigits == 0) {
                    value += (c - '0');
                    if (value > MAX_TENTHS) {
                        throw new NumberFormatException("ERROR: SDF delay value out of range: "
                                + s.subSequence(from, to));
                    }
                } else if (c != '0') {
                    // More precision than tenths can hold; rounding here would corrupt delays.
                    throw new NumberFormatException("ERROR: SDF delay value has more precision"
                            + " than one fractional digit: " + s.subSequence(from, to));
                }
                fracDigits++;
                i++;
            }
            if (fracDigits == 0) {
                throw new NumberFormatException("ERROR: malformed SDF delay value: "
                        + s.subSequence(from, to));
            }
        }

        if (i != to) {
            throw new NumberFormatException("ERROR: malformed SDF delay value: "
                    + s.subSequence(from, to));
        }

        if (negative) {
            if (value == 0) {
                return SdfDelayValues.NEG_ZERO;
            }
            return (int) -value;
        }
        return (int) value;
    }

    /**
     * Appends the SDF literal for a value in tenths, reproducing Vivado's fixed
     * one-fractional-digit formatting exactly.
     *
     * @param sb Destination.
     * @param tenths The value in tenths, or {@link SdfDelayValues#NEG_ZERO} for {@code -0.0}.
     */
    public static void appendTenths(StringBuilder sb, int tenths) {
        if (tenths == SdfDelayValues.NEG_ZERO) {
            sb.append("-0.0");
            return;
        }
        int abs = tenths;
        if (tenths < 0) {
            sb.append('-');
            abs = -tenths;
        }
        sb.append(abs / 10);
        sb.append('.');
        sb.append((char) ('0' + (abs % 10)));
    }

    /**
     * Writes the SDF literal for a value in tenths into a byte array, reproducing Vivado's fixed
     * one-fractional-digit formatting exactly.
     *
     * @param buf Destination buffer, which must have room for {@link #maxEncodedLength()} bytes at
     *            {@code offset}.
     * @param offset Index at which to start writing.
     * @param tenths The value in tenths, or {@link SdfDelayValues#NEG_ZERO} for {@code -0.0}.
     * @return The index one past the last byte written.
     */
    public static int writeTenths(byte[] buf, int offset, int tenths) {
        int o = offset;
        int abs;
        if (tenths == SdfDelayValues.NEG_ZERO) {
            buf[o++] = '-';
            abs = 0;
        } else if (tenths < 0) {
            buf[o++] = '-';
            abs = -tenths;
        } else {
            abs = tenths;
        }

        int whole = abs / 10;
        int frac = abs % 10;

        // Write the integer part most-significant digit first without allocating.
        int digits = 1;
        for (int t = whole; t >= 10; t /= 10) {
            digits++;
        }
        int end = o + digits;
        for (int p = end - 1; p >= o; p--) {
            buf[p] = (byte) ('0' + (whole % 10));
            whole /= 10;
        }
        o = end;

        buf[o++] = '.';
        buf[o++] = (byte) ('0' + frac);
        return o;
    }

    /**
     * @return The maximum number of bytes {@link #writeTenths} can emit for a single value.
     */
    public static int maxEncodedLength() {
        // "-" + 10 integer digits + "." + 1 fractional digit
        return 13;
    }
}
