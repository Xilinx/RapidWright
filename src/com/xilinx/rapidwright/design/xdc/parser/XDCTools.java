/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Technical University of Darmstadt
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

package com.xilinx.rapidwright.design.xdc.parser;

import java.util.ArrayList;
import java.util.List;

public class XDCTools {
    public static boolean stringNeedsBraces(String s) {
        return s.contains(" ") || s.contains("*") || s.contains("$");
    }
    public static String braceEnclosedIfNeeded(String s) {
        if (stringNeedsBraces(s)) {
            return '{'+s+'}';
        }
        return s;
    }

    /**
     * Checks whether a name has characters that Tcl would substitute or split a word on. Unlike
     * {@link #stringNeedsBraces(String)}, which is given strings that may hold a command
     * substitution meant to be evaluated, such as {@code [get_cells a]/b}, this counts
     * {@code [ ]} as part of the name, as in {@code data_reg[0]}.
     *
     * @param name The name.
     * @return True if the name has to be quoted to be read back as itself.
     */
    public static boolean nameNeedsQuoting(String name) {
        if (stringNeedsBraces(name)) return true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || TCL_SPECIAL.indexOf(c) >= 0) return true;
        }
        return false;
    }

    /**
     * Writes a name as a single Tcl word that reads back as the name itself. It is left as it is
     * if it needs no quoting ({@link #nameNeedsQuoting(String)}). Otherwise it is braced, as Vivado
     * writes such names, unless it has braces or backslashes of its own, in which case each
     * special character is escaped with a backslash instead.
     *
     * @param name The name, such as a hierarchical cell name.
     * @return The name as a Tcl word.
     */
    public static String quotedName(String name) {
        if (!nameNeedsQuoting(name)) return name;
        if (name.indexOf('{') < 0 && name.indexOf('}') < 0 && name.indexOf('\\') < 0) {
            return '{' + name + '}';
        }
        StringBuilder sb = new StringBuilder(name.length() + 8);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isWhitespace(c) || TCL_SPECIAL.indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Reads a Tcl word as written in a constraint file: without the braces or double quotes
     * around it, and with any backslash escapes resolved. The reverse of
     * {@link #quotedName(String)}.
     *
     * @param word The word, such as the argument of {@code current_instance}.
     * @return The value of the word.
     */
    public static String unquotedName(String word) {
        int len = word.length();
        if (len >= 2 && word.charAt(0) == '{' && word.charAt(len - 1) == '}') {
            return word.substring(1, len - 1);
        }
        if (len >= 2 && word.charAt(0) == '"' && word.charAt(len - 1) == '"') {
            word = word.substring(1, len - 1);
        }
        return word.replaceAll("\\\\(.)", "$1");
    }

    /**
     * Splits the arguments of a Tcl command into words, as Tcl does: a word in braces or double
     * quotes, or with a backslash before a space, can hold whitespace, and a command substitution
     * in square brackets is part of the word it is in. Each word is returned as written, quoting
     * included, for {@link #unquotedName(String)} to read.
     *
     * @param args The arguments, without the command name.
     * @return The words.
     */
    public static List<String> splitWords(String args) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int braces = 0;
        int brackets = 0;
        boolean quoted = false;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (c == '\\' && i + 1 < args.length()) {
                word.append(c).append(args.charAt(++i));
                continue;
            }
            if (Character.isWhitespace(c) && braces == 0 && brackets == 0 && !quoted) {
                if (word.length() > 0) {
                    words.add(word.toString());
                    word.setLength(0);
                }
                continue;
            }
            if (c == '{' && !quoted) {
                braces++;
            } else if (c == '}' && braces > 0 && !quoted) {
                braces--;
            } else if (c == '[' && braces == 0) {
                brackets++;
            } else if (c == ']' && braces == 0 && brackets > 0) {
                brackets--;
            } else if (c == '"' && braces == 0 && brackets == 0
                    && (quoted || word.length() == 0)) {
                quoted = !quoted;
            }
            word.append(c);
        }
        if (word.length() > 0) {
            words.add(word.toString());
        }
        return words;
    }

    /** Characters, besides whitespace, that Tcl gives a meaning to within a word */
    private static final String TCL_SPECIAL = "[]{}$\\\";";
}
