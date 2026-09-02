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
 * Conversion between the escaped names an SDF file contains and the plain hierarchical names
 * RapidWright's netlist uses.
 *
 * Vivado escapes characters that would otherwise be ambiguous in a Verilog identifier by prefixing
 * them with a backslash, so a cell RapidWright knows as
 * {@code processor/data_path_loop[3].arith_logical_lut} appears in SDF as
 * {@code processor/data_path_loop\[3\]\.arith_logical_lut}. Bus indices on <i>pin</i> names are not
 * escaped, so an {@code INTERCONNECT} endpoint reads
 * {@code y_reg/DSP_A_B_DATA_INST/B[9]}: escaped instance path, unescaped pin.
 *
 * The hierarchy divider {@code /} is also the separator between the instance path and the pin name,
 * so splitting an endpoint requires finding the last <i>unescaped</i> slash. An in-identifier slash
 * would be written {@code \/} and is correctly skipped by {@link #lastUnescapedSlash(String)}.
 */
public class SdfNames {

    /** The character Vivado uses to escape the next character of an identifier. */
    public static final char ESCAPE = '\\';

    /** The hierarchy divider Vivado writes. */
    public static final char DIVIDER = '/';

    private SdfNames() {
    }

    /**
     * Removes Verilog-style backslash escapes, mapping an SDF name onto the plain hierarchical name
     * RapidWright's netlist uses.
     *
     * Each backslash is dropped and the character following it is kept verbatim. A trailing
     * backslash with nothing after it is kept as-is rather than silently discarded.
     *
     * @param escaped The name as it appears in the SDF file.
     * @return The unescaped name. The argument is returned unchanged when it contains no escapes,
     *         which is the common case for pin names.
     */
    public static String unescape(String escaped) {
        int first = escaped.indexOf(ESCAPE);
        if (first < 0) {
            return escaped;
        }
        StringBuilder sb = new StringBuilder(escaped.length());
        sb.append(escaped, 0, first);
        for (int i = first; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            if (c == ESCAPE && i + 1 < escaped.length()) {
                sb.append(escaped.charAt(++i));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Applies Verilog-style backslash escaping the way Vivado does when writing an instance path,
     * escaping every character that is not legal in a bare Verilog identifier.
     *
     * The hierarchy divider is not escaped, since a caller passes a full path whose slashes are
     * separators. To escape a single path component, call this on that component alone.
     *
     * @param plain The unescaped name.
     * @return The escaped name, suitable for writing into an SDF file.
     */
    public static String escapeInstancePath(String plain) {
        StringBuilder sb = null;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            boolean needsEscape = !isBareIdentifierChar(c) && c != DIVIDER;
            if (needsEscape && sb == null) {
                sb = new StringBuilder(plain.length() + 8);
                sb.append(plain, 0, i);
            }
            if (sb != null) {
                if (needsEscape) {
                    sb.append(ESCAPE);
                }
                sb.append(c);
            }
        }
        return sb == null ? plain : sb.toString();
    }

    /**
     * @param c The character to test.
     * @return True if the character may appear unescaped in a Verilog identifier.
     */
    private static boolean isBareIdentifierChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '$';
    }

    /**
     * Finds the last hierarchy divider that is not itself escaped.
     *
     * A slash is unescaped when it is preceded by an even number of consecutive backslashes, since
     * {@code \\} denotes a literal backslash and {@code \/} a literal slash.
     *
     * @param escaped The name as it appears in the SDF file.
     * @return The index of the last unescaped {@code /}, or -1 if there is none.
     */
    public static int lastUnescapedSlash(String escaped) {
        return lastUnescapedSlash(escaped, escaped.length() - 1);
    }

    /**
     * Finds the last hierarchy divider at or before {@code fromIndex} that is not itself escaped.
     *
     * @param escaped The name as it appears in the SDF file.
     * @param fromIndex Index to start searching backwards from, inclusive.
     * @return The index of the last unescaped {@code /} at or before {@code fromIndex}, or -1.
     */
    public static int lastUnescapedSlash(String escaped, int fromIndex) {
        for (int i = Math.min(fromIndex, escaped.length() - 1); i >= 0; i--) {
            if (escaped.charAt(i) != DIVIDER) {
                continue;
            }
            int backslashes = 0;
            for (int j = i - 1; j >= 0 && escaped.charAt(j) == ESCAPE; j--) {
                backslashes++;
            }
            if ((backslashes & 1) == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Splits an {@code INTERCONNECT} endpoint into its instance path and pin name at the last
     * unescaped divider, and unescapes both.
     *
     * This is the fast path, correct whenever the pin name itself contains no divider, which holds
     * for every endpoint Vivado emits. Callers that need to handle a cell whose own name ends in a
     * segment indistinguishable from a pin should validate the result against the netlist and fall
     * back to a longest-prefix search; see {@code SdfAnnotator}.
     *
     * @param endpoint The endpoint as it appears in the SDF file, e.g.
     *                 {@code y_reg/DSP_A_B_DATA_INST/B[9]}.
     * @return A two-element array of {@code {instancePath, pinName}}, both unescaped, or null if
     *         the endpoint contains no unescaped divider and so names no pin.
     */
    public static String[] splitEndpoint(String endpoint) {
        int slash = lastUnescapedSlash(endpoint);
        if (slash <= 0 || slash == endpoint.length() - 1) {
            return null;
        }
        return new String[] {
                unescape(endpoint.substring(0, slash)),
                unescape(endpoint.substring(slash + 1))
        };
    }
}
