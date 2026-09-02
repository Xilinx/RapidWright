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
import java.nio.file.Paths;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.TimingManager;

/**
 * Entry points for reading and writing SDF files.
 *
 * This is the class to call rather than picking between {@link SdfParser} and
 * {@link ParallelSdfParser} by hand: {@link #readSdf(Path)} chooses between them based on file
 * size, in the same way {@code EDIFTools.loadEDIFFile} does for EDIF.
 */
public class SdfTools {

    private SdfTools() {
    }

    /**
     * Reads an SDF file, parsing it in parallel when it is large enough for that to pay off.
     *
     * Gzipped input is handled transparently, including the case that matters in practice:
     * {@code write_sdf -gzip} produces a gzip stream under a plain {@code .sdf} name.
     *
     * @param fileName The file to read.
     * @return The parsed model.
     */
    public static SdfFile readSdf(Path fileName) {
        return readSdf(fileName, 0, CodePerfTracker.SILENT);
    }

    /**
     * Reads an SDF file, parsing it in parallel when it is large enough for that to pay off.
     *
     * @param fileName The file to read.
     * @param maxThreads Upper bound on parser threads, or 0 for the system default.
     * @param t Performance tracker, or {@link CodePerfTracker#SILENT}.
     * @return The parsed model.
     */
    public static SdfFile readSdf(Path fileName, int maxThreads, CodePerfTracker t) {
        return ParallelSdfParser.parse(fileName, maxThreads, t);
    }

    /**
     * Writes an SDF model to a file.
     *
     * For a model read from a Vivado-written file and not modified, the output is byte-for-byte
     * identical to the input.
     *
     * @param sdf The model to write.
     * @param fileName Destination file.
     */
    public static void writeSdf(SdfFile sdf, Path fileName) {
        SdfWriter.write(sdf, fileName);
    }

    /**
     * Loads a design and an SDF file and returns a TimingManager whose graph carries Vivado's
     * delays.
     *
     * This is the one-call path for the common case. The timing graph is built for its topology
     * only, without RapidWright's built-in delay estimator, so this works on any device including
     * those the estimator has no data for. After it returns, the graph's existing traversal API
     * reports Vivado's numbers:
     *
     * <pre>
     * TimingManager tm = SdfTools.buildTimingManagerFromSdf(design, sdfPath, report);
     * GraphPath&lt;TimingVertex, TimingEdge&gt; critical = tm.getTimingGraph().getMaxDelayPath();
     * tm.getTimingGraph().prettyPrintPathDelays(critical);
     * </pre>
     *
     * Check the report before trusting the result: annotation is best-effort, and
     * {@link SdfAnnotationReport#getGraphCoverage()} says how much of the graph the SDF actually
     * accounted for.
     *
     * @param design The design the SDF was written from. Its netlist is macro-expanded in place if
     *               it has not been already, which is required for the names to match.
     * @param sdfFileName The SDF file to apply.
     * @param config Options controlling the mapping, or null for the defaults.
     * @param reportOut Single-element array receiving the annotation report, or null to discard it.
     * @return A TimingManager whose graph has been annotated.
     */
    public static TimingManager buildTimingManagerFromSdf(Design design, Path sdfFileName,
            SdfAnnotationConfig config, SdfAnnotationReport[] reportOut) {
        SdfFile sdf = readSdf(sdfFileName);
        SdfAnnotator.ensureMacrosExpanded(design);
        // Build the graph without the estimator: its delays would only be overwritten, and on a
        // device with no shipped timing data building it would throw.
        TimingManager tm = new TimingManager(design, false);
        SdfAnnotator annotator = new SdfAnnotator(design, sdf,
                config == null ? new SdfAnnotationConfig() : config);
        SdfAnnotationReport report = annotator.annotate(tm.getTimingGraph());
        if (reportOut != null && reportOut.length > 0) {
            reportOut[0] = report;
        }
        return tm;
    }

    /**
     * Reads an SDF file and reports what it contains; with a second argument, writes it back out.
     *
     * Writing it back out is a round-trip check: for any file Vivado produced, the output should be
     * byte-for-byte identical to the input.
     *
     * @param args Input SDF file, and optionally an output file.
     */
    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.out.println("USAGE: <input.sdf> [output.sdf]");
            return;
        }
        CodePerfTracker t = new CodePerfTracker("SDF", true);
        Path input = Paths.get(args[0]);
        SdfFile sdf = readSdf(input, 0, t);

        long ioPaths = 0;
        long interconnects = 0;
        long timingChecks = 0;
        for (SdfCell cell : sdf.getCells()) {
            for (SdfDelayEntry entry : cell.getDelayEntries()) {
                if (entry.getKind() == SdfDelayEntry.Kind.IOPATH) {
                    ioPaths++;
                } else {
                    interconnects++;
                }
            }
            timingChecks += cell.getTimingChecks().size();
        }
        System.out.println("  Design: " + sdf.getDesign());
        System.out.println("  SDF version: " + sdf.getSdfVersion()
                + ", written by " + sdf.getProgram() + " " + sdf.getProgramVersion());
        System.out.println("  Timescale: " + sdf.getTimeScale());
        System.out.println("  Cells: " + sdf.getCells().size());
        System.out.println("  IOPATH delays: " + ioPaths);
        System.out.println("  INTERCONNECT delays: " + interconnects);
        System.out.println("  Timing checks: " + timingChecks);

        if (args.length > 1) {
            writeSdfTimed(sdf, Paths.get(args[1]), t);
        }
        t.printSummary();
    }

    private static void writeSdfTimed(SdfFile sdf, Path output, CodePerfTracker t) {
        SdfWriter.write(sdf, output, t);
    }
}
