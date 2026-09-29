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
 * The complete set of SDF keywords emitted by Vivado's {@code write_sdf}.
 *
 * RapidWright's SDF support is deliberately scoped to exactly this subset: any other keyword causes
 * {@link SdfParser} to throw {@link SdfParseException} rather than be silently ignored, so that a
 * construct RapidWright does not understand can never be quietly dropped from a round-trip or an
 * annotation.
 *
 * Absent by design, because no Vivado-written SDF observed so far contains them: {@code COND},
 * {@code CONDELSE}, {@code PORT}, {@code DEVICE}, {@code RETAIN}, {@code RECOVERY},
 * {@code REMOVAL}, {@code NOCHANGE}, {@code SKEW}, {@code INCREMENT}, {@code TIMINGENV},
 * {@code VOLTAGE}, {@code PROCESS} and {@code TEMPERATURE}. The keyword set here was confirmed
 * against roughly 19 GB of SDF from production xcvu19p and xcvu440 designs as well as smaller
 * UltraScale+ and Versal ones.
 */
public class SdfKeywords {

    /** The outermost construct of an SDF file. */
    public static final String DELAYFILE = "DELAYFILE";

    /** SDF language version; Vivado writes {@code "3.0"}. */
    public static final String SDFVERSION = "SDFVERSION";

    /** Name of the design the file was written from. */
    public static final String DESIGN = "DESIGN";

    /** Timestamp of the write; varies per run and so is excluded from content comparisons. */
    public static final String DATE = "DATE";

    /** Tool vendor; Vivado writes {@code "XILINX"}. */
    public static final String VENDOR = "VENDOR";

    /** Writing program; Vivado writes {@code "Vivado"}. */
    public static final String PROGRAM = "PROGRAM";

    /** Writing program's version, e.g. {@code "2025.2"}. */
    public static final String VERSION = "VERSION";

    /** Hierarchy separator character; Vivado writes {@code /}. */
    public static final String DIVIDER = "DIVIDER";

    /** Unit for every delay value in the file; Vivado writes {@code 1ps}. */
    public static final String TIMESCALE = "TIMESCALE";

    /** One cell instance's timing data. */
    public static final String CELL = "CELL";

    /** The library cell type of the enclosing {@link #CELL}. */
    public static final String CELLTYPE = "CELLTYPE";

    /** The hierarchical instance path of the enclosing {@link #CELL}; empty for the top cell. */
    public static final String INSTANCE = "INSTANCE";

    /** Wrapper for a cell's propagation delays. */
    public static final String DELAY = "DELAY";

    /** Pulse rejection threshold as a percentage. */
    public static final String PATHPULSEPERCENT = "PATHPULSEPERCENT";

    /** Wrapper indicating the enclosed delays are absolute rather than incremental. */
    public static final String ABSOLUTE = "ABSOLUTE";

    /** A delay arc between two pins of the enclosing cell. */
    public static final String IOPATH = "IOPATH";

    /** A delay arc between two pins of other cells, i.e. a routed net segment. */
    public static final String INTERCONNECT = "INTERCONNECT";

    /** Wrapper for a cell's timing checks. */
    public static final String TIMINGCHECK = "TIMINGCHECK";

    /** A combined setup and hold check. */
    public static final String SETUPHOLD = "SETUPHOLD";

    /** A minimum clock period check. */
    public static final String PERIOD = "PERIOD";

    /** A minimum pulse width check. */
    public static final String WIDTH = "WIDTH";

    /**
     * A combined recovery and removal check, written for the asynchronous set or reset pin of a
     * flip-flop.
     */
    public static final String RECREM = "RECREM";

    /** Rising edge qualifier; lower case in the file. */
    public static final String POSEDGE = "posedge";

    /** Falling edge qualifier; lower case in the file. */
    public static final String NEGEDGE = "negedge";

    /** Opening parenthesis, interned so it can be compared by reference. */
    public static final String LEFT_PAREN = "(";

    /** Closing parenthesis, interned so it can be compared by reference. */
    public static final String RIGHT_PAREN = ")";

    private SdfKeywords() {
    }
}
