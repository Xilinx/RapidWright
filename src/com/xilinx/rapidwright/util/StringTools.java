/*
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
/**
 *
 */
package com.xilinx.rapidwright.util;

import java.io.File;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import org.python.jline.TerminalFactory;

/**
 * A set of String utility methods.
 * Created on: May 25, 2016
 */
public class StringTools {

    public static String makeCamelCase(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i=0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '_') continue;
            if (i != 0 && name.charAt(i-1) == '_') {
                sb.append(Character.toUpperCase(c));
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static String lowerCaseFirstLetter(String name) {
        return name.substring(0, 1).toLowerCase() + name.substring(1);
    }

    public static String makeUpperCamelCase(String name) {
        return Character.toUpperCase(name.charAt(0)) + makeCamelCase(name).substring(1);
    }

    /**
     * Removes any double quote characters from the ends of the string.
     * @param s
     * @return
     */
    public static String removeOuterQuotes(String s) {
        if (s == null) return s;
        int len = s.length();
        if (len == 0) return s;
        boolean atFront = s.charAt(0) == '\"';
        if (len == 1) {
            return atFront ? "" : s;
        }
        boolean atBack = s.charAt(len-1) == '\"';
        return s.substring(atFront ? 1 : 0, atBack ? len-1 : len);
    }

    @SuppressWarnings("unused")
    private void _test_removeOuterQuotes() {
        String[] tests = new String[] {
                "\"Hello At Start"
                ,"\"Hello At Both\""
                ,"Hello At End\""
                ,"\"\""
                ,"\""
                ,""
                ,null
                };

        for (String s : tests) {
            String result = removeOuterQuotes(s);
            System.out.println("<<" + s + ">>  <<" + result + ">>");
        }
    }

    private static Comparator<String> naturalComparator;

    static {
        naturalComparator = new Comparator<String>() {
            private boolean isDigit(char c) {
                return 0x30 <= c && c <= 0x39;
            }

            @Override
            public int compare(String a, String b) {
                int ai = 0, bi = 0;
                while (ai < a.length() && bi < b.length()) {
                    if (isDigit(a.charAt(ai)) && isDigit(b.charAt(bi))) {
                        int aStart = ai, bStart = bi;
                        while (ai < a.length() && isDigit(a.charAt(ai))) ai++;
                        while (bi < b.length() && isDigit(b.charAt(bi))) bi++;
                        String aStr = a.substring(aStart,ai);
                        String bStr = b.substring(bStart,bi);
                        if (aStr.length() > 9 || bStr.length() > 9) {
                            if (!aStr.equals(bStr)) {
                                return aStr.compareTo(bStr);
                            }
                        } else {
                            int aInt = Integer.parseInt(aStr);
                            int bInt = Integer.parseInt(bStr);
                            if (aInt != bInt) return aInt - bInt;
                        }
                    } else if (a.charAt(ai) != b.charAt(bi))
                        return a.charAt(ai) - b.charAt(bi);
                    ai++; bi++;
                }
                return a.length() - b.length();
            }
        };
    }

    /**
     * Sorts strings using the 'natural' sort approach where numbers are sorted by
     * their magnitudes, i.e. {1,2,3,4,5,6,7,8,9,10,...} as opposed to strict ASCII
     * sorting {1,10,11,12,13, ...}
     * @param strings The list of strings to sort
     * @return The natural sorted list of strings.
     */
    public static List<String> naturalSort(List<String> strings) {
        strings.sort(naturalComparator);
        return strings;
    }

    /**
     * Removes any trailing '/' (File.separator) characters from file paths.
     * If none are present, the original string is returned.
     * @param s File path
     * @return The file path with File.separator removed or the original
     * string if
     */
    public static String removeLastSeparator(String s) {
        if (s.endsWith(File.separator)) {
            return s.substring(0, s.length()-1);
        }
        return s;
    }

    /**
     * Sorts strings using the 'natural' sort approach where numbers are sorted by
     * their magnitudes, i.e. {1,2,3,4,5,6,7,8,9,10,...} as opposed to strict ASCII
     * sorting {1,10,11,12,13, ...}
     * @param strings The array of strings to sort
     * @return The natural sorted array of strings.
     */
    public static String[] naturalSort(String[] strings) {
        Arrays.sort(strings, naturalComparator);
        return strings;
    }

    public static boolean isInteger(String s) {
        for (int i=0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    /**
     * Will insert angle brackets around indexing value
     * at the end of a String.  For example:
     * {@code input0 --> input<0>}
     * {@code input10 --> input<10>}
     * {@code clk --> clk}
     * @param s The string to which potentially angle brackets will be added
     * @return The string with angle bracket around last integer or
     * no change if no integer is found.
     */
    public static String addIndexingAngleBrackets(String s) {
        int i = s.length() -1;
        if (!Character.isDigit(s.charAt(i))) return s;
        while (Character.isDigit(s.charAt(i))) {
            i--;
        }
        StringBuilder sb = new StringBuilder(s.substring(0, i+1));
        sb.append('<');
        sb.append(s.substring(i+1));
        sb.append('>');
        return sb.toString();
    }

    /**
     * Counts occurrences of a character in a string.
     * @param str String to check
     * @param c Character of interest
     * @return The number of occurrences in the the string
     */
    public static int countOccurrences(String str, char c) {
        int count = 0;
        for (int i=0; i < str.length(); i++) {
            if (str.charAt(i) == c) count++;
        }
        return count;
    }

    /**
     * Get the longest common prefix between two strings starting at the beginning.
     * @param s0 First string to check
     * @param s1 Second string to check
     * @return Longest common prefix string or empty string if none.
     */
    public static String longestCommonPrefix(String s0, String s1) {
        if (s0 == null || s1 == null) return "";
        int min = Math.min(s0.length(), s1.length());
        for (int i=0; i < min; i++) {
            if (s0.charAt(i) != s1.charAt(i)) {
                return s0.substring(0, i);
            }
        }
        return s0.substring(0,min);
    }

    /**
     * Checks if given string starts with any of the provided prefix strings
     * @param str The string to check
     * @param prefixes The set of possible prefixes to match against str
     * @return A prefix that matches, although there could exist other matches that have a longer
     * match.  Returns null if no prefix match is found.
     */
    public static String startsWithAny(String str, Collection<String> prefixes) {
        for (String prefix : prefixes) {
            if (str.startsWith(prefix)) return prefix;
        }
        return null;
    }

    /**
     * Creates a String of spaces of the specified length
     * 
     * @param length The number of spaces in the desired String
     * @return A String containing spaces of the desired length. Any length less
     *         than 1 will return a String of length 0.
     */
    public static String makeWhiteSpace(int length) {
        return length < 1 ? "" : new String(new char[length]).replace('\0', ' ');
    }

    /**
     * Number of spaces between column prints in
     * {@link #printListInColumns(List, PrintStream)}
     */
    public static final int COLUMN_SPACING = 2;

    /**
     * Prints a list of Strings in columns, based upon the terminal width.
     * 
     * @param items      The list of Strings to print
     * @param ps         The stream to send the printed Strings to.
     * @param maxColumns A maximum limit to the number columns to print
     */
    public static void printListInColumns(List<String> items, PrintStream ps) {
        printListInColumns(items, ps, Integer.MAX_VALUE);
    }

    /**
     * Prints a list of Strings in columns, based upon the terminal width.
     * 
     * @param items      The list of Strings to print
     * @param ps         The stream to send the printed Strings to.
     * @param maxColumns A maximum limit to the number columns to print
     */
    public static void printListInColumns(List<String> items, PrintStream ps, int maxColumns) {
        // Find the longest length of all the provided Strings
        int maxLength = items.stream().max(Comparator.comparingInt(String::length)).get().length();

        // Get the width in characters of the current terminal
        int termWidth = TerminalFactory.get().getWidth();

        int colWidth = maxLength + COLUMN_SPACING;
        int numCols = Integer.min(termWidth / colWidth, maxColumns);
        int colHeight = (items.size() + numCols) / numCols;
        String fmt = makeWhiteSpace(COLUMN_SPACING) + "%-" + maxLength + "s";
        for (int i = 0; i < colHeight; i++) {
            for (int col = 0; col < numCols; col++) {
                int idx = col * colHeight + i;
                if (idx < items.size())
                    ps.printf(fmt, items.get(idx));
            }
            ps.println();
        }
    }

    public static void main(String[] args) {
        String[] tests = new String[] {
            "ARCHITECTURE",
            "ARCHITECTURE_FULL_NAME",
            "AVAILABLE_CONFIG_MODES",
            "AVAILABLE_IOBS",
            "BLOCK_RAMS",
            "CLASS",
            "COLS",
            "COMPATIBLE_PARTS",
            "C_FAMILY",
            "DEVICE",
            "DSP",
            "FAMILY",
            "FLIPFLOPS",
            "GB_TRANSCEIVERS",
            "GTXE2_TRANSCEIVERS",
            "IO_PIN_COUNT",
            "IO_STANDARDS",
            "IS_INTERNAL",
            "LUT_ELEMENTS",
            "MAX_OPERATING_TEMPERATURE",
            "MAX_OPERATING_VOLTAGE",
            "MCBS",
            "MIN_OPERATING_TEMPERATURE",
            "MIN_OPERATING_VOLTAGE",
            "MMCM",
            "NAME",
            "PACKAGE",
            "PACKAGE_PINOUT_VERSION",
            "PACKAGE_PIN_DELAY_VERSION",
            "PCI_BUSES",
            "REF_OPERATING_TEMPERATURE",
            "REF_OPERATING_VOLTAGE",
            "ROWS",
            "SLICES",
            "SLRS",
            "SPEED",
            "SPEED_LABEL",
            "SPEED_LEVEL_ID",
            "SPEED_LEVEL_ID_DATE",
            "SSN_REPORT",
            "TEMAC_NETWORK_CONTROLLERS",
            "TEMPERATURE_GRADE_LETTER",
        };

        for (String s : tests) {
            System.out.println(s + " = " + makeCamelCase(s) + " " + makeUpperCamelCase(s));
        }
    }
}
