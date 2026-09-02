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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * One {@code (CELL ...)} block of an SDF file: the timing data for a single cell instance.
 *
 * Vivado writes two structurally different flavours of cell, distinguished by {@link Style}. Most
 * cells are leaf instances carrying {@code IOPATH} arcs and timing checks. Exactly one cell, always
 * written last, is the top-level cell: it has an empty {@code (INSTANCE )}, a different
 * indentation, and holds every {@code INTERCONNECT} entry in the design.
 *
 * Both the {@code DELAY} and {@code TIMINGCHECK} blocks are optional and independently so; an
 * {@code MMCME4_ADV}, for instance, is written with timing checks and no delays at all. Absent is
 * distinct from present-but-empty, and both must round-trip, hence {@link #hasDelay()} and
 * {@link #hasTimingCheck()} rather than testing the lists for emptiness.
 */
public class SdfCell {

    /**
     * Which of Vivado's two cell layouts this cell uses. This selects the writer's indentation and
     * trailing-whitespace constants, which differ between the two.
     */
    public enum Style {
        /** A leaf cell instance: 2-space indent, trailing space after {@code (CELL}/{@code (DELAY}/
         *  {@code (ABSOLUTE}. */
        NORMAL,
        /** The top-level cell: 4-space indent, no trailing space after {@code (DELAY}/
         *  {@code (ABSOLUTE}, empty {@code (INSTANCE )}. */
        TOP
    }

    private final String cellType;

    private final String instance;

    private final Style style;

    private final boolean hasDelay;

    private final boolean hasTimingCheck;

    private final SdfDelayValues pathPulsePercent;

    private final List<SdfDelayEntry> delayEntries;

    private final List<SdfTimingCheck> timingChecks;

    private final long startByteOffset;

    private final long endByteOffset;

    private final long lineNumber;

    /**
     * @param cellType The {@code CELLTYPE} value, without its surrounding quotes.
     * @param instance The {@code INSTANCE} value, escaped and verbatim; empty for the top cell.
     * @param style Which cell layout this is.
     * @param hasDelay Whether a {@code (DELAY ...)} block was present.
     * @param hasTimingCheck Whether a {@code (TIMINGCHECK ...)} block was present.
     * @param pathPulsePercent The {@code PATHPULSEPERCENT} value, or null if absent.
     * @param delayEntries The {@code IOPATH} and {@code INTERCONNECT} entries, in file order.
     * @param timingChecks The timing check entries, in file order.
     * @param startByteOffset Byte offset of this cell's opening parenthesis.
     * @param endByteOffset Byte offset one past this cell's closing parenthesis and newline.
     * @param lineNumber 1-based line number of this cell's opening parenthesis.
     */
    public SdfCell(String cellType, String instance, Style style, boolean hasDelay,
            boolean hasTimingCheck, SdfDelayValues pathPulsePercent,
            List<SdfDelayEntry> delayEntries, List<SdfTimingCheck> timingChecks,
            long startByteOffset, long endByteOffset, long lineNumber) {
        this.cellType = Objects.requireNonNull(cellType);
        this.instance = Objects.requireNonNull(instance);
        this.style = Objects.requireNonNull(style);
        this.hasDelay = hasDelay;
        this.hasTimingCheck = hasTimingCheck;
        this.pathPulsePercent = pathPulsePercent;
        this.delayEntries = delayEntries == null ? Collections.<SdfDelayEntry>emptyList()
                : delayEntries;
        this.timingChecks = timingChecks == null ? Collections.<SdfTimingCheck>emptyList()
                : timingChecks;
        this.startByteOffset = startByteOffset;
        this.endByteOffset = endByteOffset;
        this.lineNumber = lineNumber;
    }

    /**
     * @return The library cell type, e.g. {@code FDRE}, without quotes.
     */
    public String getCellType() {
        return cellType;
    }

    /**
     * @return The hierarchical instance path exactly as written, including backslash escapes. Empty
     *         for the top-level cell. Use {@link SdfNames#unescape(String)} to obtain the name that
     *         matches RapidWright's netlist.
     */
    public String getInstance() {
        return instance;
    }

    /**
     * @return Which of Vivado's two cell layouts this cell uses.
     */
    public Style getStyle() {
        return style;
    }

    /**
     * @return True if this is the top-level cell, which holds the design's INTERCONNECT entries.
     */
    public boolean isTopCell() {
        return style == Style.TOP;
    }

    /**
     * @return True if a {@code (DELAY ...)} block was present, even if it contained no entries.
     */
    public boolean hasDelay() {
        return hasDelay;
    }

    /**
     * @return True if a {@code (TIMINGCHECK ...)} block was present, even if it was empty.
     */
    public boolean hasTimingCheck() {
        return hasTimingCheck;
    }

    /**
     * @return The {@code PATHPULSEPERCENT} value, or null if none was written.
     */
    public SdfDelayValues getPathPulsePercent() {
        return pathPulsePercent;
    }

    /**
     * @return The {@code IOPATH} and {@code INTERCONNECT} entries, in file order. Order is
     *         significant: Vivado's cell and entry ordering is not stable across runs, so input
     *         order is the only thing a byte-exact round-trip can rely on.
     */
    public List<SdfDelayEntry> getDelayEntries() {
        return delayEntries;
    }

    /**
     * @return The timing check entries, in file order.
     */
    public List<SdfTimingCheck> getTimingChecks() {
        return timingChecks;
    }

    /**
     * @return Byte offset of this cell's opening parenthesis in the source file.
     */
    public long getStartByteOffset() {
        return startByteOffset;
    }

    /**
     * @return Byte offset one past the end of this cell in the source file. Together with
     *         {@link #getStartByteOffset()} this gives the cell's exact encoded length, which
     *         {@link SdfWriter} uses to verify that re-rendering reproduced the original bytes.
     */
    public long getEndByteOffset() {
        return endByteOffset;
    }

    /**
     * @return 1-based line number of this cell's opening parenthesis, for diagnostics.
     */
    public long getLineNumber() {
        return lineNumber;
    }

    /**
     * Collects the names of this cell's clock pins, as identified by its timing checks.
     *
     * A pin named as the reference event of a {@code PERIOD} check, or as the second port of a
     * {@code SETUPHOLD}, is a clock. This is how {@code SdfAnnotator} distinguishes a sequential
     * {@code IOPATH} such as {@code C -> Q} from a combinational one, without needing a per-device
     * table of clock pin names.
     *
     * @return The clock pin names, escaped and verbatim; empty if this cell has no timing checks.
     */
    public List<String> getClockPorts() {
        if (timingChecks.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> clocks = new ArrayList<>(2);
        for (SdfTimingCheck check : timingChecks) {
            String clock = check.getClockPort();
            if (clock != null && !clocks.contains(clock)) {
                clocks.add(clock);
            }
        }
        return clocks;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SdfCell that = (SdfCell) o;
        // Byte offsets and line numbers are positional metadata, not content, and are excluded so
        // that a parallel parse can be compared against a serial one.
        return hasDelay == that.hasDelay && hasTimingCheck == that.hasTimingCheck
                && style == that.style && cellType.equals(that.cellType)
                && instance.equals(that.instance)
                && Objects.equals(pathPulsePercent, that.pathPulsePercent)
                && delayEntries.equals(that.delayEntries)
                && timingChecks.equals(that.timingChecks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cellType, instance, style, hasDelay, hasTimingCheck, pathPulsePercent,
                delayEntries, timingChecks);
    }

    @Override
    public String toString() {
        return "(CELL (CELLTYPE \"" + cellType + "\") (INSTANCE " + instance + ") "
                + delayEntries.size() + " delays, " + timingChecks.size() + " checks)";
    }
}
