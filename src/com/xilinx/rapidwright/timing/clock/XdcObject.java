/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.clock;

import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;

/**
 * A design object returned by an object query of a timing constraint (get_cells, get_pins,
 * get_ports, get_nets or get_clocks), see {@link TimingConstraints}.
 * <p>
 * Clocks are not resolved when the constraints are parsed, as auto-derived clocks only exist
 * once the clock network is traced: a {@link Kind#CLOCK} object is a query (a name pattern, or the
 * clocks of other objects), which {@link ClockNetwork#resolveClocks(List)} resolves against the
 * clocks defined before the query (see {@code getQueryIndex}), as Vivado does.
 */
public final class XdcObject {

    /** Kind of design object */
    public enum Kind {
        /** A cell instance (leaf or hierarchical) */
        CELL,
        /** A pin of a cell instance */
        PIN,
        /** A top-level port */
        PORT,
        /** A net */
        NET,
        /** A clock query */
        CLOCK
    }

    private final Kind kind;
    private final String name;
    private final EDIFHierCellInst cell;
    private final EDIFHierPortInst pin;
    private final EDIFHierNet net;
    private final boolean includeGenerated;
    private final List<XdcObject> clockOf;
    private final int queryIndex;
    /** A clock pattern's -regexp and -nocase */
    private final boolean regexp;
    private final boolean nocase;
    /** The matcher of a clock pattern, built on first use */
    private volatile Pattern clockMatcher;

    private XdcObject(Kind kind, String name, EDIFHierCellInst cell, EDIFHierPortInst pin, EDIFHierNet net,
                      boolean includeGenerated, List<XdcObject> clockOf, int queryIndex, boolean regexp,
                      boolean nocase) {
        this.kind = kind;
        this.name = name;
        this.cell = cell;
        this.pin = pin;
        this.net = net;
        this.includeGenerated = includeGenerated;
        this.clockOf = clockOf;
        this.queryIndex = queryIndex;
        this.regexp = regexp;
        this.nocase = nocase;
    }

    private XdcObject(Kind kind, String name, EDIFHierCellInst cell, EDIFHierPortInst pin, EDIFHierNet net) {
        this(kind, name, cell, pin, net, false, null, 0, false, false);
    }

    public static XdcObject ofCell(EDIFHierCellInst cell) {
        return new XdcObject(Kind.CELL, cell.getFullHierarchicalInstName(), cell, null, null);
    }

    /** A cell whose full hierarchical name is known (the name of {@code cell}) */
    static XdcObject ofCell(String fullName, EDIFHierCellInst cell) {
        return new XdcObject(Kind.CELL, fullName, cell, null, null);
    }

    /** A cell known by name only (constraints parsed without a netlist). */
    static XdcObject ofCellName(String name) {
        return new XdcObject(Kind.CELL, name, null, null, null);
    }

    /** A net known by name only (constraints parsed without a netlist). */
    static XdcObject ofNetName(String name) {
        return new XdcObject(Kind.NET, name, null, null, null);
    }

    public static XdcObject ofPin(EDIFHierPortInst pin) {
        return new XdcObject(Kind.PIN, pin.toString(), null, pin, null);
    }

    /**
     * @param name Name of the pin, as cell name '/' port instance name.
     * @param pin The pin, or null if the pin has no port instance (e.g. an unconnected pin).
     */
    static XdcObject ofPin(String name, EDIFHierPortInst pin) {
        return new XdcObject(Kind.PIN, name, null, pin, null);
    }

    /**
     * @param name Port instance name of the top-level port, e.g. "din[3]".
     */
    static XdcObject ofPort(String name) {
        return new XdcObject(Kind.PORT, name, null, null, null);
    }

    static XdcObject ofNet(EDIFHierNet net) {
        return new XdcObject(Kind.NET, net.getHierarchicalNetName(), null, null, net);
    }

    /**
     * A query of the clocks whose names match a pattern.
     * @param pattern Name pattern ('*' and '?' wildcards).
     * @param includeGenerated Whether to add the clocks generated from the matching clocks.
     * @param queryIndex Index of the constraint making the query.
     */
    static XdcObject ofClockPattern(String pattern, boolean includeGenerated, int queryIndex) {
        return ofClockPattern(pattern, includeGenerated, queryIndex, false, false);
    }

    /**
     * A get_clocks pattern.
     * @param pattern The pattern: a glob, or a regular expression with regexp.
     * @param includeGenerated -include_generated_clocks.
     * @param queryIndex The index of the command.
     * @param regexp -regexp: the pattern is a regular expression matching whole names.
     * @param nocase -nocase: case-insensitive.
     */
    static XdcObject ofClockPattern(String pattern, boolean includeGenerated, int queryIndex, boolean regexp,
                                    boolean nocase) {
        return new XdcObject(Kind.CLOCK, pattern, null, null, null, includeGenerated, null, queryIndex, regexp,
                nocase);
    }

    /**
     * A query of the clocks of design objects (the clocks reaching pins, ports, nets, the clock pins of cells).
     * @param objects The objects.
     * @param includeGenerated -include_generated_clocks.
     * @param queryIndex The index of the command.
     */
    static XdcObject ofClocksOf(List<XdcObject> objects, boolean includeGenerated, int queryIndex) {
        return new XdcObject(Kind.CLOCK, "-of_objects", null, null, null, includeGenerated,
                Collections.unmodifiableList(objects), queryIndex, false, false);
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * @return The full hierarchical name of a cell, pin or net; the name of a port; the name pattern
     * of a clock query.
     */
    public String getName() {
        return name;
    }

    /** @return The cell instance of a {@link Kind#CELL}, null if unknown. */
    public EDIFHierCellInst getCell() {
        return cell;
    }

    /** @return The port instance of a {@link Kind#PIN}, null if unknown. */
    public EDIFHierPortInst getPin() {
        return pin;
    }

    /** @return The net of a {@link Kind#NET}, null if unknown. */
    public EDIFHierNet getNet() {
        return net;
    }

    /** @return For a clock query, whether the clocks generated from the matching ones are added. */
    boolean isIncludeGenerated() {
        return includeGenerated;
    }

    /** @return For a clock query of the clocks of objects, those objects, else null. */
    List<XdcObject> getClockOf() {
        return clockOf;
    }

    /** @return For a clock query, the index of the constraint that made it. */
    int getQueryIndex() {
        return queryIndex;
    }

    /**
     * Checks if a clock pattern matches a clock name: a glob ('*', '?') or, with -regexp, a regular
     * expression matching the whole name; -nocase ignores case.
     */
    boolean matchesClockName(String clockName) {
        Pattern p = clockMatcher;
        if (p == null) {
            int flags = nocase ? Pattern.CASE_INSENSITIVE : 0;
            if (regexp) {
                try {
                    p = Pattern.compile(name, flags);
                } catch (PatternSyntaxException e) {
                    // An invalid regular expression matches no clock
                    p = NO_MATCH;
                }
            } else {
                p = XdcObjectFinder.compileGlob(name, true, flags);
            }
            clockMatcher = p;
        }
        return p.matcher(clockName).matches();
    }

    /** A pattern that matches nothing */
    private static final Pattern NO_MATCH = Pattern.compile("(?!)");

    @Override
    public String toString() {
        return kind + ":" + name;
    }
}
