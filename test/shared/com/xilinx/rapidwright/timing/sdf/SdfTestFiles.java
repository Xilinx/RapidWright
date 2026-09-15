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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SDF fixtures shared between tests.
 *
 * These are written as literal text rather than checked in as files, because RapidWright's binary
 * test fixtures live in the separate {@code RapidWrightDCP} submodule and adding one there would
 * need its own pull request. Every construct here was observed in real Vivado 2025.2 output; the
 * layout, including the trailing spaces, is reproduced exactly so that a round-trip assertion over
 * these strings is as strong as one over a Vivado-written file.
 */
public class SdfTestFiles {

    private SdfTestFiles() {
    }

    /**
     * A small but deliberately hostile SDF exercising every construct and formatting quirk the
     * parser has to survive.
     *
     * It contains, in order: a design name containing the literal text {@code (CELL}, which a
     * naive chunk-boundary scan would mistake for a cell; a cell whose instance name is escaped
     * with every escape character Vivado can emit, including {@code \(}, {@code \)} and {@code \/};
     * a tri-state {@code IOPATH} with six delvals of which two are absent; negative
     * {@code SETUPHOLD} values; a cell with a {@code TIMINGCHECK} and no {@code DELAY}; a cell with
     * a {@code DELAY} and no {@code TIMINGCHECK}; a macro-expanded sub-cell name; and the
     * top-level cell with its distinct indentation and empty {@code INSTANCE}.
     *
     * @return The SDF text, with Unix line endings.
     */
    public static String adversarialSdf() {
        return "(DELAYFILE \n"
             + "(SDFVERSION \"3.0\" )\n"
             + "(DESIGN \"top_with_(CELL_in_the_name\")\n"
             + "(DATE \"Tue Aug 25 14:14:21 2026\")\n"
             + "(VENDOR \"XILINX\")\n"
             + "(PROGRAM \"Vivado\")\n"
             + "(VERSION \"2025.2\")\n"
             + "(DIVIDER /)\n"
             + "(TIMESCALE 1ps)\n"
             // Every escape Vivado can produce, including ones that would terminate a token.
             + "(CELL \n"
             + "  (CELLTYPE \"FDRE\")\n"
             + "  (INSTANCE weird\\[0\\]\\.name\\(CELL\\)\\/slash\\\\back\\\"quote)\n"
             + "  (DELAY \n"
             + "    (ABSOLUTE \n"
             + "      (IOPATH C Q (58.0:77.0:77.0) (58.0:77.0:77.0))\n"
             + "    )\n"
             + "  )\n"
             + "    (TIMINGCHECK\n"
             + "      (SETUPHOLD (posedge D) (posedge C) (-36.0:-25.0:-25.0) (60.0:60.0:60.0))\n"
             + "      (SETUPHOLD (negedge D) (negedge C) (-36.0:-25.0:-25.0) (60.0:60.0:60.0))\n"
             + "      (PERIOD (posedge C) (550.0:550.0:550.0))\n"
             + "      (WIDTH (negedge C) (275.0:275.0:275.0))\n"
             + "    )\n"
             + ")\n"
             // A flip-flop with an asynchronous preset. Vivado writes its reset-to-output arc with
             // an edge-qualified source and a single delay value, and pairs it with a RECREM
             // check. Neither appears in a design built only from synchronous flops, which is how
             // both went unnoticed until a production netlist was tried.
             + "(CELL \n"
             + "  (CELLTYPE \"FDPE\")\n"
             + "  (INSTANCE async_reg)\n"
             + "  (DELAY \n"
             + "    (ABSOLUTE \n"
             + "      (IOPATH C Q (70.0:96.0:96.0) (70.0:96.0:96.0))\n"
             + "      (IOPATH (posedge PRE) Q (162.0:213.0:213.0))\n"
             + "    )\n"
             + "  )\n"
             + "    (TIMINGCHECK\n"
             + "      (SETUPHOLD (posedge D) (posedge C) (-37.0:-27.0:-27.0) (53.0:53.0:53.0))\n"
             + "      (RECREM (negedge PRE) (posedge C) (67.0:72.0:72.0) (-33.0:-33.0:-33.0))\n"
             + "      (RECREM (negedge PRE) (negedge C) (67.0:72.0:72.0) (-33.0:-33.0:-33.0))\n"
             + "      (WIDTH (negedge PRE) (196.0:196.0:196.0))\n"
             + "    )\n"
             + ")\n"
             // Tri-state output: six delvals, the first two absent.
             + "(CELL \n"
             + "  (CELLTYPE \"OBUFT\")\n"
             + "  (INSTANCE iob\\[0\\]\\.iob_i/OBUFT)\n"
             + "  (DELAY \n"
             + "    (PATHPULSEPERCENT (30.0))\n"
             + "    (ABSOLUTE \n"
             + "      (IOPATH I O (750.6:976.4:976.4) (750.6:976.4:976.4))\n"
             + "      (IOPATH T O () () (629.4:5602.1:5602.1) (629.4:5602.1:5602.1)"
             + " (652.4:1348.1:1348.1) (652.4:1348.1:1348.1))\n"
             + "    )\n"
             + "  )\n"
             + ")\n"
             // A cell with timing checks and no DELAY block at all, as Vivado writes for an MMCM.
             + "(CELL \n"
             + "  (CELLTYPE \"MMCME4_ADV\")\n"
             + "  (INSTANCE mmcm)\n"
             + "    (TIMINGCHECK\n"
             + "      (PERIOD (posedge CLKIN1) (1071.0:1071.0:1071.0))\n"
             + "      (WIDTH (negedge RST) (5000.0:5000.0:5000.0))\n"
             + "    )\n"
             + ")\n"
             // A macro-expanded sub-cell, and a delay of exactly -0.0 which must survive.
             + "(CELL \n"
             + "  (CELLTYPE \"LUT6\")\n"
             + "  (INSTANCE processor/data_path_loop\\[3\\]\\.arith_logical_lut/LUT6)\n"
             + "  (DELAY \n"
             + "    (PATHPULSEPERCENT (30.0))\n"
             + "    (ABSOLUTE \n"
             + "      (IOPATH I0 O (-0.0:0.0:0.0) (-0.0:0.0:0.0))\n"
             + "      (IOPATH I5 O (57.0:90.0:90.0) (57.0:90.0:90.0))\n"
             + "    )\n"
             + "  )\n"
             + ")\n"
             // The top-level cell: empty INSTANCE, deeper indent, no trailing spaces.
             + "(CELL \n"
             + "    (CELLTYPE \"top_with_(CELL_in_the_name\")\n"
             + "    (INSTANCE )\n"
             + "    (DELAY\n"
             + "      (ABSOLUTE\n"
             + "      (INTERCONNECT weird\\[0\\]\\.name\\(CELL\\)\\/slash\\\\back\\\"quote/Q"
             + " processor/data_path_loop\\[3\\]\\.arith_logical_lut/LUT6/I0"
             + " (96.0:143.0:143.0) (96.0:143.0:143.0))\n"
             + "      (INTERCONNECT mmcm/CLKOUT0 iob\\[0\\]\\.iob_i/OBUFT/I"
             + " (0.0:0.0:0.0) (0.0:0.0:0.0))\n"
             + "      )\n"
             + "    )\n"
             + ")\n"
             + ")\n";
    }

    /**
     * The same content as {@link #adversarialSdf()} but formatted the way Vivado formats a
     * {@code -process_corner fast} file, with one space between each triple's opening parenthesis
     * and its first value.
     *
     * @return The SDF text, with Unix line endings.
     */
    public static String adversarialSdfFastCorner() {
        // The padding applies only to triples, never to a single-value delval such as
        // PATHPULSEPERCENT, and never after a colon.
        StringBuilder sb = new StringBuilder();
        String src = adversarialSdf();
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            sb.append(c);
            if (c != '(' || i + 1 >= src.length()) {
                continue;
            }
            if (isTripleStart(src, i + 1)) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    /**
     * @param s The text to inspect.
     * @param from Index just after an opening parenthesis.
     * @return True if a {@code min:typ:max} triple starts at {@code from}.
     */
    private static boolean isTripleStart(String s, int from) {
        int i = from;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            i++;
        }
        int digits = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
            digits++;
        }
        if (digits == 0 || i >= s.length() || s.charAt(i) != '.') {
            return false;
        }
        i++;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        return i < s.length() && s.charAt(i) == ':';
    }

    /**
     * Writes SDF text to a file.
     *
     * @param dir Directory to write into.
     * @param name File name.
     * @param content The SDF text.
     * @return The path written.
     */
    public static Path write(Path dir, String name, String content) {
        Path path = dir.resolve(name);
        try {
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't write file : " + path, e);
        }
        return path;
    }
}
