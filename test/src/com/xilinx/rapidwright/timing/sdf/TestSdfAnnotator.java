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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingGraph;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;

import org.jgrapht.GraphPath;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SdfAnnotator}.
 *
 * The tests that matter most here need a real placed-and-routed design and the SDF Vivado writes
 * from it, so they are gated on Vivado being available. What they check is not just that annotation
 * runs, but that it lands Vivado's numbers on the right edges: the strictest assertion walks
 * Vivado's own reported critical path arc by arc and requires the annotated graph to agree.
 */
public class TestSdfAnnotator {

    /** The design used throughout: small, routed, and exercising LUTs, carries, LUTRAM and BRAM. */
    private static final String DCP = "picoblaze_ooc_X10Y235.dcp";

    @Test
    public void testAnnotateAgainstVivadoSdf(@TempDir Path tempDir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Path dcp = RapidWrightDCP.getPath(DCP);
        Path sdfPath = tempDir.resolve("design.sdf");
        VivadoTools.writeSdf(dcp, sdfPath, "slow", "timesim", false, tempDir, false);

        Design design = Design.readCheckpoint(dcp);
        SdfAnnotationReport[] out = new SdfAnnotationReport[1];
        TimingManager tm = SdfTools.buildTimingManagerFromSdf(design, sdfPath, null, out);
        SdfAnnotationReport report = out[0];

        // Every edge the graph can carry a delay on must have received one. This is the assertion
        // that matters: an unannotated edge keeps a delay the SDF never supplied, and silently
        // corrupts every path through it.
        Assertions.assertEquals(report.getGraphEdgeCount(), report.getGraphEdgesAnnotated(),
                () -> "some timing graph edges were not annotated:\n" + summary(report));

        // Every routed net segment in the SDF must have been placed on an edge.
        Assertions.assertEquals(
                report.getCount(SdfAnnotationReport.Reason.OK_INTERCONNECT),
                countInterconnects(sdfPath),
                () -> "not every INTERCONNECT was applied:\n" + summary(report));

        // Nothing should be unexplained.
        Assertions.assertEquals(0,
                report.getCount(SdfAnnotationReport.Reason.CELL_INSTANCE_NOT_IN_NETLIST),
                () -> summary(report));
        Assertions.assertEquals(0,
                report.getCount(SdfAnnotationReport.Reason.MACRO_SUBCELL_NOT_IN_NETLIST),
                () -> summary(report));
        Assertions.assertEquals(0, report.getCount(SdfAnnotationReport.Reason.PIN_NOT_ON_CELL),
                () -> summary(report));
        Assertions.assertTrue(report.isClean(), () -> summary(report));

        TimingGraph tg = tm.getTimingGraph();
        GraphPath<TimingVertex, TimingEdge> critical = tg.getMaxDelayPath();
        Assertions.assertNotNull(critical, "no critical path was found in the annotated graph");
        float delay = tg.getPathDelay(critical);
        // A ~350 MHz picoblaze: the worst path is a few nanoseconds. This is a sanity bound, not a
        // golden value; the arc-by-arc test below is the precise one.
        Assertions.assertTrue(delay > 1000f && delay < 10000f,
                "implausible critical path delay of " + delay + " ps");
    }

    /**
     * Checks the annotated graph arc by arc against Vivado's own reported critical path.
     *
     * This is the real accuracy test. Rather than comparing a single total, which could match by
     * coincidence, it walks the specific hops Vivado reports and requires each annotated edge to
     * carry the same number Vivado did.
     */
    @Test
    public void testAnnotatedDelaysMatchVivado(@TempDir Path tempDir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Path dcp = RapidWrightDCP.getPath(DCP);
        Path sdfPath = tempDir.resolve("design.sdf");
        VivadoTools.writeSdf(dcp, sdfPath, "slow", "timesim", false, tempDir, false);

        Design design = Design.readCheckpoint(dcp);
        TimingManager tm = SdfTools.buildTimingManagerFromSdf(design, sdfPath, null, null);
        TimingGraph tg = tm.getTimingGraph();

        // Hops taken from "report_timing" on this DCP. Each is (source pin, sink pin, net delay in
        // ps, logic delay of the arc into the sink's cell in ps).
        assertNetDelay(tg, "your_program/ram_4096x8/DOUTADOUT[4]",
                "processor/lower_reg_banks/RAMA_D1/RADR0", 341f);
        assertNetDelay(tg, "processor/lower_reg_banks/RAMA_D1/O",
                "processor/data_path_loop[0].output_data.sy_kk_mux_lut/LUT6/I2", 100f);
        assertLogicDelay(tg, "processor/lower_reg_banks/RAMA_D1/RADR0",
                "processor/lower_reg_banks/RAMA_D1/O", 148f);
        assertLogicDelay(tg, "processor/data_path_loop[0].output_data.sy_kk_mux_lut/LUT6/I2",
                "processor/data_path_loop[0].output_data.sy_kk_mux_lut/LUT6/O", 98f);
    }

    @Test
    public void testCornerAndTransitionSelection(@TempDir Path tempDir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Path dcp = RapidWrightDCP.getPath(DCP);
        Path sdfPath = tempDir.resolve("design.sdf");
        VivadoTools.writeSdf(dcp, sdfPath, "slow", "timesim", false, tempDir, false);
        SdfFile sdf = SdfTools.readSdf(sdfPath);

        Design design = Design.readCheckpoint(dcp);
        SdfAnnotator.ensureMacrosExpanded(design);

        // The min corner of a slow-corner SDF is strictly faster than the max corner, so the same
        // path must come out shorter. This confirms the corner selection is actually plumbed
        // through rather than being ignored.
        float maxDelay = criticalPathDelay(design, sdf, new SdfAnnotationConfig()
                .setCorner(SdfAnnotationConfig.Corner.MAX));
        float minDelay = criticalPathDelay(design, sdf, new SdfAnnotationConfig()
                .setCorner(SdfAnnotationConfig.Corner.MIN));
        Assertions.assertTrue(minDelay < maxDelay,
                "min corner (" + minDelay + " ps) should be faster than max (" + maxDelay + " ps)");
    }

    @Test
    public void testAnnotationIsIndependentOfDeviceTimingData(@TempDir Path tempDir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Path dcp = RapidWrightDCP.getPath(DCP);
        Path sdfPath = tempDir.resolve("design.sdf");
        VivadoTools.writeSdf(dcp, sdfPath, "slow", "timesim", false, tempDir, false);

        // Building without the estimator must produce the same topology as building with it, so
        // that the no-estimator path used on Versal is not a second, divergent code path.
        Design a = Design.readCheckpoint(dcp);
        SdfAnnotator.ensureMacrosExpanded(a);
        TimingGraph without = new TimingManager(a, false).getTimingGraph();

        Design b = Design.readCheckpoint(dcp);
        SdfAnnotator.ensureMacrosExpanded(b);
        TimingGraph with = new TimingManager(b, true).getTimingGraph();

        // The estimator additionally creates cell-internal arcs, so the no-estimator graph has
        // fewer edges but must not have more, and must contain no vertex the other lacks.
        Assertions.assertTrue(without.edgeSet().size() <= with.edgeSet().size(),
                "topology-only build produced more edges than the full build");
        for (TimingVertex v : without.vertexSet()) {
            Assertions.assertTrue(with.containsVertex(v),
                    "topology-only build produced a vertex the full build lacks: " + v);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    private static float criticalPathDelay(Design design, SdfFile sdf, SdfAnnotationConfig config) {
        TimingManager tm = new TimingManager(design, false);
        new SdfAnnotator(design, sdf, config.setWarnWhenNotClean(false))
                .annotate(tm.getTimingGraph());
        GraphPath<TimingVertex, TimingEdge> path = tm.getTimingGraph().getMaxDelayPath();
        Assertions.assertNotNull(path);
        return tm.getTimingGraph().getPathDelay(path);
    }

    private static void assertNetDelay(TimingGraph tg, String src, String dst, float expected) {
        TimingEdge edge = findEdge(tg, src, dst);
        Assertions.assertEquals(expected, edge.getNetDelay(), 0.05f,
                "net delay of " + src + " -> " + dst + " does not match Vivado");
    }

    private static void assertLogicDelay(TimingGraph tg, String src, String dst, float expected) {
        TimingEdge edge = findEdge(tg, src, dst);
        Assertions.assertEquals(expected, edge.getLogicDelay(), 0.05f,
                "logic delay of " + src + " -> " + dst + " does not match Vivado");
    }

    private static TimingEdge findEdge(TimingGraph tg, String src, String dst) {
        TimingVertex vs = tg.getTimingVertex(src);
        Assertions.assertNotNull(vs, "no vertex named " + src);
        TimingVertex vd = tg.getTimingVertex(dst);
        Assertions.assertNotNull(vd, "no vertex named " + dst);
        TimingEdge edge = tg.getEdge(vs, vd);
        Assertions.assertNotNull(edge, "no edge " + src + " -> " + dst);
        return edge;
    }

    private static long countInterconnects(Path sdfPath) {
        return SdfTools.readSdf(sdfPath).getInterconnects().size();
    }

    private static String summary(SdfAnnotationReport report) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        report.printSummary(new java.io.PrintStream(bytes));
        return bytes.toString();
    }
}
