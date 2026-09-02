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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * An in-memory model of a Standard Delay Format file as written by Vivado's {@code write_sdf}.
 *
 * The model is deliberately faithful rather than convenient: cells are kept in file order, names
 * keep their backslash escapes, delay values are exact integers, and optional blocks are tracked as
 * present-or-absent. That is what lets {@link SdfWriter} reproduce the input byte for byte, which
 * in turn is the strongest available evidence that nothing was silently dropped on the way in.
 *
 * Typical use:
 * <pre>
 * SdfFile sdf = SdfParser.parse(Paths.get("design.sdf"));
 * for (SdfCell cell : sdf.getCells()) { ... }
 * </pre>
 *
 * To push the delays onto a {@code TimingGraph}, see {@code SdfAnnotator}.
 */
public class SdfFile {

    /** Default hierarchy divider when a file omits {@code (DIVIDER ...)}. */
    public static final String DEFAULT_DIVIDER = "/";

    /** Default time scale when a file omits {@code (TIMESCALE ...)}; the SDF standard's default. */
    public static final String DEFAULT_TIMESCALE = "1ns";

    private Path source;

    private String sdfVersion;

    private String design;

    private String date;

    private String vendor;

    private String program;

    private String programVersion;

    private String divider = DEFAULT_DIVIDER;

    private String timeScale = DEFAULT_TIMESCALE;

    private final List<SdfCell> cells;

    /**
     * Creates an empty SDF file model.
     */
    public SdfFile() {
        this.cells = new ArrayList<>();
    }

    /**
     * @return The file this model was parsed from, or null if it was built programmatically.
     */
    public Path getSource() {
        return source;
    }

    /**
     * @param source The file this model was parsed from.
     */
    public void setSource(Path source) {
        this.source = source;
    }

    /**
     * @return The {@code SDFVERSION} value without quotes, e.g. {@code 3.0}, or null if absent.
     */
    public String getSdfVersion() {
        return sdfVersion;
    }

    /**
     * @param sdfVersion The SDF language version, without quotes.
     */
    public void setSdfVersion(String sdfVersion) {
        this.sdfVersion = sdfVersion;
    }

    /**
     * @return The {@code DESIGN} value without quotes: the top module name, or null if absent.
     */
    public String getDesign() {
        return design;
    }

    /**
     * @param design The top module name, without quotes.
     */
    public void setDesign(String design) {
        this.design = design;
    }

    /**
     * @return The {@code DATE} value without quotes, or null if absent. This changes on every
     *         Vivado run and so should be excluded when comparing two files for equivalent content.
     */
    public String getDate() {
        return date;
    }

    /**
     * @param date The write timestamp, without quotes.
     */
    public void setDate(String date) {
        this.date = date;
    }

    /**
     * @return The {@code VENDOR} value without quotes, e.g. {@code XILINX}, or null if absent.
     */
    public String getVendor() {
        return vendor;
    }

    /**
     * @param vendor The tool vendor, without quotes.
     */
    public void setVendor(String vendor) {
        this.vendor = vendor;
    }

    /**
     * @return The {@code PROGRAM} value without quotes, e.g. {@code Vivado}, or null if absent.
     */
    public String getProgram() {
        return program;
    }

    /**
     * @param program The writing program, without quotes.
     */
    public void setProgram(String program) {
        this.program = program;
    }

    /**
     * @return The {@code VERSION} value without quotes, e.g. {@code 2025.2}, or null if absent.
     */
    public String getProgramVersion() {
        return programVersion;
    }

    /**
     * @param programVersion The writing program's version, without quotes.
     */
    public void setProgramVersion(String programVersion) {
        this.programVersion = programVersion;
    }

    /**
     * @return The {@code DIVIDER} value; Vivado writes {@code /}.
     */
    public String getDivider() {
        return divider;
    }

    /**
     * @param divider The hierarchy separator.
     */
    public void setDivider(String divider) {
        this.divider = divider;
    }

    /**
     * @return The {@code TIMESCALE} value, e.g. {@code 1ps}. Every delay value in the file is
     *         expressed in this unit.
     */
    public String getTimeScale() {
        return timeScale;
    }

    /**
     * @param timeScale The time scale, e.g. {@code 1ps}.
     */
    public void setTimeScale(String timeScale) {
        this.timeScale = timeScale;
    }

    /**
     * Returns the factor converting this file's delay values, expressed in tenths of the
     * {@code TIMESCALE} unit, into picoseconds.
     *
     * The conversion is deliberately deferred to annotation time rather than applied during
     * parsing, so that the stored values remain exactly what the file contained and can be written
     * back out unchanged.
     *
     * @return Picoseconds per tenth of the time scale unit.
     * @throws SdfParseException If the time scale is not one Vivado can emit.
     */
    public double getTenthsToPs() {
        return SdfTimeScale.tenthsToPs(timeScale);
    }

    /**
     * @return The cells, in file order. The list is mutable so that a model can be built up or
     *         edited before being written.
     */
    public List<SdfCell> getCells() {
        return cells;
    }

    /**
     * @param cell The cell to append.
     */
    public void addCell(SdfCell cell) {
        cells.add(Objects.requireNonNull(cell));
    }

    /**
     * @param newCells The cells to append, in order.
     */
    public void addCells(List<SdfCell> newCells) {
        cells.addAll(newCells);
    }

    /**
     * Returns the top-level cell, which holds the design's {@code INTERCONNECT} entries.
     *
     * @return The single cell with {@link SdfCell.Style#TOP}, or null if the file has none.
     */
    public SdfCell getTopCell() {
        for (int i = cells.size() - 1; i >= 0; i--) {
            if (cells.get(i).isTopCell()) {
                return cells.get(i);
            }
        }
        return null;
    }

    /**
     * @return All {@code INTERCONNECT} entries in the file, in order. In Vivado output these all
     *         live in the top-level cell, but this method does not assume that.
     */
    public List<SdfDelayEntry> getInterconnects() {
        List<SdfDelayEntry> result = new ArrayList<>();
        for (SdfCell cell : cells) {
            for (SdfDelayEntry entry : cell.getDelayEntries()) {
                if (entry.getKind() == SdfDelayEntry.Kind.INTERCONNECT) {
                    result.add(entry);
                }
            }
        }
        return result;
    }

    @Override
    public String toString() {
        return "SdfFile[design=" + design + ", version=" + sdfVersion + ", timescale=" + timeScale
                + ", cells=" + cells.size() + "]";
    }
}
