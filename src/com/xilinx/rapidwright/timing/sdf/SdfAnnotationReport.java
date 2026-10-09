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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What happened when an SDF file was annotated onto a timing graph.
 *
 * Annotation is best-effort by nature: an SDF describes Vivado's post-implementation view of a
 * design, and a name in it may have no counterpart in RapidWright's netlist. Silently ignoring
 * those cases would be the worst outcome, because the resulting graph looks fully populated while
 * carrying stale or zero delays on some edges. This report exists so that never happens quietly.
 *
 * Coverage is tracked in <b>both directions</b>, and the second is the one that matters most. An
 * unmatched SDF entry is merely data that was not used. A graph delay that no SDF entry supplied
 * still has a value -- zero, or whatever the estimator put there -- and every path through it is
 * wrong. Those are counted per component as {@link Reason#EDGE_MISSING_NET_DELAY},
 * {@link Reason#EDGE_MISSING_LOGIC_DELAY} and {@link Reason#FLOP_OUTPUT_MISSING_CLOCK_ARC}, since
 * one edge can need both a net delay and a clock-to-output delay and either can go missing alone.
 */
public class SdfAnnotationReport {

    /** Why a given SDF entry or graph edge ended up the way it did. */
    public enum Reason {

        /** An IOPATH was applied to an existing cell-internal edge. */
        OK_IOPATH,

        /** An INTERCONNECT was applied to an existing net edge. */
        OK_INTERCONNECT,

        /** An IOPATH's two pins both exist but had no edge, so one was created. */
        OK_IOPATH_EDGE_CREATED,

        /** A clock-to-output IOPATH was applied to the net edges leaving that output. */
        OK_SEQUENTIAL_ARC,

        /** The SDF names a cell instance that is not in the netlist. */
        CELL_INSTANCE_NOT_IN_NETLIST,

        /**
         * The SDF names a sub-cell of a macro or a physical BEL that the netlist does not expand
         * into a hierarchical cell. The sample records the longest prefix that did resolve.
         */
        MACRO_SUBCELL_NOT_IN_NETLIST,

        /** The cell exists but has no port with the named pin. */
        PIN_NOT_ON_CELL,

        /** An un-indexed SDF pin named a whole bus, which was expanded to its individual bits. */
        PIN_BUS_EXPANDED,

        /**
         * The pin exists on the cell, but the timing graph has no vertex for it, so there is no
         * edge for the delay to land on.
         *
         * This is expected rather than wrong. The graph only creates a vertex for a pin some net
         * connects, whereas an SDF describes every arc of a cell whether or not the design uses it
         * -- all sixty-four {@code S[n] -> CO[m]} arcs of a CARRY8, for instance, when the design
         * uses two. Clock pins land here too, since the graph deliberately omits clock nets.
         */
        PIN_NOT_IN_TIMING_GRAPH,

        /** Both pins exist but there is no edge, and creating one was disabled. */
        EDGE_MISSING_SKIPPED,

        /** A clock-to-output arc was found but applying sequential arcs was disabled. */
        SEQUENTIAL_ARC_SKIPPED,

        /** An endpoint names a top-level port rather than a leaf cell pin. */
        TOP_LEVEL_PORT,

        /** Two entries described the same arc; the larger delay was kept. */
        DUPLICATE_ENTRY,

        /** A propagation delay was negative, which is meaningful only for a timing check. */
        NEGATIVE_DELAY,

        /** Every delay value in a list was absent, so the entry carried no usable number. */
        EMPTY_DELVAL_LIST,

        /**
         * The configured transition was not among the delay values present, so the worst present
         * value was used instead. A tri-state arc describing only its high-impedance transitions
         * does this when RISE or FALL is asked for.
         */
        REQUESTED_TRANSITION_ABSENT,

        /** A routed net segment whose net delay no INTERCONNECT supplied; it is still stale. */
        EDGE_MISSING_NET_DELAY,

        /** A cell-internal arc whose logic delay no IOPATH supplied; it is still stale. */
        EDGE_MISSING_LOGIC_DELAY,

        /**
         * A net edge leaving a sequential output that received no clock-to-output delay, so the
         * paths through it understate their arrival time by that cell's clock-to-output time.
         */
        FLOP_OUTPUT_MISSING_CLOCK_ARC
    }

    private final Map<Reason, long[]> counts = new EnumMap<>(Reason.class);

    private final Map<Reason, List<String>> samples = new EnumMap<>(Reason.class);

    private final int sampleLimit;

    private long sdfEntryCount;

    private long graphEdgeCount;

    private long graphEdgesAnnotated;

    private long netDelaysRequired;

    private long netDelaysAnnotated;

    private long logicDelaysRequired;

    private long logicDelaysAnnotated;

    private Path sdfSource;

    /**
     * @param sampleLimit How many example names to retain per category.
     */
    public SdfAnnotationReport(int sampleLimit) {
        this.sampleLimit = sampleLimit;
    }

    /**
     * Records one outcome.
     *
     * @param reason What happened.
     * @param detail A one-line description including a file position, or null to record no sample.
     */
    public void record(Reason reason, String detail) {
        long[] counter = counts.get(reason);
        if (counter == null) {
            counter = new long[1];
            counts.put(reason, counter);
        }
        counter[0]++;
        if (detail == null) {
            return;
        }
        List<String> list = samples.get(reason);
        if (list == null) {
            list = new ArrayList<>();
            samples.put(reason, list);
        }
        // Samples are the first N encountered in file order, so the report is identical from run to
        // run and can be diffed.
        if (list.size() < sampleLimit) {
            list.add(detail);
        }
    }

    /**
     * @param reason The category to query.
     * @return How many times it occurred.
     */
    public long getCount(Reason reason) {
        long[] counter = counts.get(reason);
        return counter == null ? 0 : counter[0];
    }

    /**
     * @param reason The category to query.
     * @return Up to the sample limit of examples, in file order.
     */
    public List<String> getSamples(Reason reason) {
        List<String> list = samples.get(reason);
        return list == null ? Collections.<String>emptyList() : Collections.unmodifiableList(list);
    }

    /**
     * @return The SDF file that was annotated, or null if unknown.
     */
    public Path getSdfSource() {
        return sdfSource;
    }

    /**
     * @param sdfSource The SDF file that was annotated.
     */
    public void setSdfSource(Path sdfSource) {
        this.sdfSource = sdfSource;
    }

    /**
     * @return Total delay entries read from the SDF.
     */
    public long getSdfEntryCount() {
        return sdfEntryCount;
    }

    /**
     * @param sdfEntryCount Total delay entries read from the SDF.
     */
    public void setSdfEntryCount(long sdfEntryCount) {
        this.sdfEntryCount = sdfEntryCount;
    }

    /**
     * @return Edges in the timing graph that carry a real delay, excluding the artificial edges to
     *         and from the super source and super sink, which are zero by construction and so are
     *         not something an SDF could annotate.
     */
    public long getGraphEdgeCount() {
        return graphEdgeCount;
    }

    /**
     * @param graphEdgeCount Number of annotatable edges in the timing graph.
     */
    public void setGraphEdgeCount(long graphEdgeCount) {
        this.graphEdgeCount = graphEdgeCount;
    }

    /**
     * @return Edges that received at least one delay from the SDF.
     */
    public long getGraphEdgesAnnotated() {
        return graphEdgesAnnotated;
    }

    /**
     * @param graphEdgesAnnotated Edges that received at least one delay from the SDF.
     */
    public void setGraphEdgesAnnotated(long graphEdgesAnnotated) {
        this.graphEdgesAnnotated = graphEdgesAnnotated;
    }

    /**
     * @param required Net delays the graph needs, one per routed net segment.
     * @param annotated How many of those the SDF supplied.
     */
    public void setNetDelayCounts(long required, long annotated) {
        this.netDelaysRequired = required;
        this.netDelaysAnnotated = annotated;
    }

    /**
     * @param required Logic delays the graph needs: one per cell-internal arc, plus one per net
     *                 edge leaving a sequential output for its clock-to-output time.
     * @param annotated How many of those the SDF supplied.
     */
    public void setLogicDelayCounts(long required, long annotated) {
        this.logicDelaysRequired = required;
        this.logicDelaysAnnotated = annotated;
    }

    /**
     * @return Net delays the graph needs.
     */
    public long getNetDelaysRequired() {
        return netDelaysRequired;
    }

    /**
     * @return Net delays the SDF supplied.
     */
    public long getNetDelaysAnnotated() {
        return netDelaysAnnotated;
    }

    /**
     * @return Logic delays the graph needs.
     */
    public long getLogicDelaysRequired() {
        return logicDelaysRequired;
    }

    /**
     * @return Logic delays the SDF supplied.
     */
    public long getLogicDelaysAnnotated() {
        return logicDelaysAnnotated;
    }

    /**
     * @return The fraction of required net delays that were supplied, between 0 and 1.
     */
    public double getNetDelayCoverage() {
        return netDelaysRequired == 0 ? 1.0 : (double) netDelaysAnnotated / netDelaysRequired;
    }

    /**
     * @return The fraction of required logic delays that were supplied, between 0 and 1.
     */
    public double getLogicDelayCoverage() {
        return logicDelaysRequired == 0 ? 1.0 : (double) logicDelaysAnnotated / logicDelaysRequired;
    }

    /**
     * @return Number of SDF entries that were successfully applied.
     */
    public long getAppliedEntryCount() {
        return getCount(Reason.OK_IOPATH) + getCount(Reason.OK_INTERCONNECT)
                + getCount(Reason.OK_IOPATH_EDGE_CREATED) + getCount(Reason.OK_SEQUENTIAL_ARC);
    }

    /**
     * @return The fraction of SDF entries that were applied, between 0 and 1.
     */
    public double getSdfCoverage() {
        return sdfEntryCount == 0 ? 1.0 : (double) getAppliedEntryCount() / sdfEntryCount;
    }

    /**
     * Returns the fraction of edges that received at least one delay.
     *
     * Prefer {@link #getNetDelayCoverage()} and {@link #getLogicDelayCoverage()} when judging
     * whether an annotated graph can be trusted. An edge is not a single number: a net edge leaving
     * a flip-flop needs both a net delay and that flop's clock-to-output delay, and this figure
     * counts it as covered once either has arrived.
     *
     * @return The fraction of timing graph edges that received a delay, between 0 and 1.
     */
    public double getGraphCoverage() {
        return graphEdgeCount == 0 ? 1.0 : (double) graphEdgesAnnotated / graphEdgeCount;
    }

    /**
     * Reports whether annotation completed with nothing unexplained.
     *
     * Bus expansion and top-level ports are expected and do not count against cleanliness; an
     * unmatched name or an unannotated edge does.
     *
     * @return True if every diagnostic category other than the benign ones is empty.
     */
    public boolean isClean() {
        for (Reason reason : Reason.values()) {
            if (isBenign(reason)) {
                continue;
            }
            if (getCount(reason) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param reason The category to classify.
     * @return True if the category represents success or an expected, harmless situation.
     */
    private static boolean isBenign(Reason reason) {
        switch (reason) {
            case OK_IOPATH:
            case OK_INTERCONNECT:
            case OK_IOPATH_EDGE_CREATED:
            case OK_SEQUENTIAL_ARC:
            case PIN_BUS_EXPANDED:
            case TOP_LEVEL_PORT:
            case PIN_NOT_IN_TIMING_GRAPH:
            case DUPLICATE_ENTRY:
                return true;
            case REQUESTED_TRANSITION_ABSENT:
                // Reported so the substitution is visible, but the value used is conservative.
                return true;
            default:
                return false;
        }
    }

    /**
     * Prints a human-readable summary.
     *
     * @param out Destination.
     */
    public void printSummary(PrintStream out) {
        out.println("SDF annotation report"
                + (sdfSource == null ? "" : " for " + sdfSource));
        out.printf("  SDF delay entries:      %,d%n", sdfEntryCount);
        out.printf("  Applied:                %,d (%.2f%%)%n", getAppliedEntryCount(),
                getSdfCoverage() * 100);
        out.printf("  Timing graph edges:     %,d%n", graphEdgeCount);
        out.printf("  Edges annotated:        %,d (%.2f%%)%n", graphEdgesAnnotated,
                getGraphCoverage() * 100);
        out.printf("  Net delays supplied:    %,d of %,d (%.2f%%)%n", netDelaysAnnotated,
                netDelaysRequired, getNetDelayCoverage() * 100);
        out.printf("  Logic delays supplied:  %,d of %,d (%.2f%%)%n", logicDelaysAnnotated,
                logicDelaysRequired, getLogicDelayCoverage() * 100);
        out.println();
        for (Reason reason : Reason.values()) {
            long count = getCount(reason);
            if (count == 0) {
                continue;
            }
            out.printf("  %-34s %,d%n", reason + ":", count);
            for (String sample : getSamples(reason)) {
                out.println("      " + sample);
            }
            long hidden = count - getSamples(reason).size();
            if (hidden > 0) {
                out.println("      ... and " + hidden + " more");
            }
        }
        if (!isClean()) {
            out.println();
            out.println("  WARNING: annotation was not clean; delays on unannotated edges are not"
                    + " from the SDF.");
        }
    }

    /**
     * Writes the full, unsampled counts as CSV, so two runs can be compared directly.
     *
     * @param fileName Destination file.
     */
    public void writeCsv(Path fileName) {
        try (BufferedWriter w = Files.newBufferedWriter(fileName, StandardCharsets.UTF_8)) {
            w.write("reason,count\n");
            for (Reason reason : Reason.values()) {
                w.write(reason.name());
                w.write(',');
                w.write(Long.toString(getCount(reason)));
                w.write('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException("ERROR: Couldn't write file : " + fileName, e);
        }
    }

    @Override
    public String toString() {
        return "SdfAnnotationReport[applied=" + getAppliedEntryCount() + "/" + sdfEntryCount
                + ", net=" + netDelaysAnnotated + "/" + netDelaysRequired
                + ", logic=" + logicDelaysAnnotated + "/" + logicDelaysRequired
                + ", clean=" + isClean() + "]";
    }
}
