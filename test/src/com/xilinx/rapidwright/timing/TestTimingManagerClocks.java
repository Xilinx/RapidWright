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

package com.xilinx.rapidwright.timing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.NetWrapper;
import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.clock.ClockModel;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.sta.StaClockAnalysis;
import com.xilinx.rapidwright.timing.sta.StaGraph;

/**
 * The clocks of the timing analysis of {@link TimingManager} (see
 * {@link TimingFamilies#CLOCKS_PROPERTY}).
 */
public class TestTimingManagerClocks {

    private static Design load(String name) {
        Assumptions.assumeTrue(Files.exists(RapidWrightDCP.getPath(name)), name + " not available");
        return RapidWrightDCP.loadDCP(name);
    }

    /** Creates the router-like timing manager of a design with given clocks (null for the default) */
    private static TimingManager create(Design d, String clocks) {
        String oldClocks = set(TimingFamilies.CLOCKS_PROPERTY, clocks);
        try {
            return new TimingManager(d, null, new RWRouteConfig(new String[0]), d.getNets(), false,
                    DelayEstimator.create(d, false));
        } finally {
            set(TimingFamilies.CLOCKS_PROPERTY, oldClocks);
        }
    }

    private static String set(String key, String value) {
        String old = System.getProperty(key);
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
        return old;
    }

    private static List<Connection> map(Design d, TimingManager tm) {
        List<Connection> conns = new ArrayList<>();
        int id = 0;
        for (Net net : d.getNets()) {
            if (net.isClockNet() || net.isStaticNet() || net.getSource() == null) continue;
            NetWrapper nw = new NetWrapper(id, net);
            for (SitePinInst sink : net.getSinkPins()) {
                conns.add(new Connection(id++, net.getSource(), sink, nw));
            }
        }
        tm.setTimingEdgesOfConnections(conns);
        return conns;
    }

    /**
     * Router connections that map to the same connection of the timing graph (e.g. to a site pin that the timing
     * graph has no connection of its own for): its route delay is the largest of theirs, whatever the order they
     * are set in.
     */
    @Test
    public void testSharedConnectionsTakeTheMaxDelay() {
        Design d = load("picoblaze_2022.2.dcp");
        TimingManager tm = create(d, TimingFamilies.CLOCKS_IDEAL);
        List<Connection> conns = map(d, tm);
        Connection first = null;
        for (Connection c : conns) {
            if (c.getStaConnId() >= 0) {
                first = c;
                break;
            }
        }
        Assertions.assertNotNull(first);
        // Three router connections to that sink
        List<Connection> shared = new ArrayList<>();
        shared.add(first);
        for (int i = 0; i < 2; i++) {
            shared.add(new Connection(conns.size() + i, first.getSource(), first.getSink(), first.getNetWrapper()));
        }
        tm.setTimingEdgesOfConnections(shared);
        int id = first.getStaConnId();
        for (Connection c : shared) {
            Assertions.assertEquals(id, c.getStaConnId());
        }
        for (boolean reversed : new boolean[]{false, true}) {
            float max = 0;
            for (int i = 0; i < shared.size(); i++) {
                Connection c = shared.get(reversed ? shared.size() - 1 - i : i);
                tm.setRouteDelay(c, 100f * (i + 1) + (reversed ? 7 : 0));
                max = Math.max(max, c.getTimingRouteDelay());
            }
            Assertions.assertEquals(max, tm.getStaGraph().getConnectionRouteMax(id), "reversed " + reversed);
        }
    }

    @Test
    public void testSwitchValues() {
        String old = set(TimingFamilies.CLOCKS_PROPERTY, null);
        try {
            // Modeled clocks by default
            Assertions.assertTrue(TimingFamilies.isModeledClocksSelected());
            set(TimingFamilies.CLOCKS_PROPERTY, TimingFamilies.CLOCKS_IDEAL);
            Assertions.assertFalse(TimingFamilies.isModeledClocksSelected());
            set(TimingFamilies.CLOCKS_PROPERTY, TimingFamilies.CLOCKS_MODELED);
            Assertions.assertTrue(TimingFamilies.isModeledClocksSelected());
            set(TimingFamilies.CLOCKS_PROPERTY, "skewed");
            Assertions.assertThrows(IllegalArgumentException.class, TimingFamilies::isModeledClocksSelected);
        } finally {
            set(TimingFamilies.CLOCKS_PROPERTY, old);
        }
    }

    /**
     * A design with several clocks: their relations, even with ideal clocks. (The multi-clock
     * designs of RapidWrightDCP, verilog_ethernet and microblazeAndILA, are 7 series and UltraScale
     * ones, which have no timing model; picoblaze gets a second clock instead.)
     */
    @Test
    public void testMultiClockDesign() {
        Design d = load("picoblaze_ooc_X10Y235_2022_1.dcp");
        d.addXDCConstraint("create_clock -period 4.000 -name clk_b -add [get_ports clk]");
        TimingManager tm = create(d, TimingFamilies.CLOCKS_IDEAL);
        Assertions.assertFalse(tm.isSingleClock());
        StaClockAnalysis an = tm.getStaClockAnalysis();
        Assertions.assertEquals(2, tm.getStaClockBinding().getClocks().size());
        Assertions.assertEquals(4, an.getNumGroups());
        List<Connection> conns = map(d, tm);
        Assertions.assertTrue(tm.computeArrivalRequiredTimes() > 0);
        tm.calculateCriticality(conns, 0.99f, 3f);
        int critical = 0;
        for (Connection c : conns) {
            Assertions.assertTrue(c.getCriticality() >= 0 && c.getCriticality() <= 0.99f, c.toString());
            if (c.getCriticality() > 0.5f) critical++;
        }
        Assertions.assertTrue(critical > 0);
        // The 2.85 ns clock to the 4 ns one and back: the tightest requirement is between them
        List<StaClockAnalysis.GroupSummary> groups = an.getGroupSummaries();
        Assertions.assertEquals(4, groups.size());
        float tightest = Float.MAX_VALUE;
        for (StaClockAnalysis.GroupSummary g : groups) {
            tightest = Math.min(tightest, g.getRequirement());
        }
        Assertions.assertTrue(tightest < 2850f, "requirement " + tightest);

        // Asynchronous clocks are not timed against each other
        Design d2 = load("picoblaze_ooc_X10Y235_2022_1.dcp");
        d2.addXDCConstraint("create_clock -period 4.000 -name clk_b -add [get_ports clk]");
        d2.addXDCConstraint("set_clock_groups -asynchronous -group [get_clocks clk] -group [get_clocks clk_b]");
        TimingManager tm2 = create(d2, null);
        Assertions.assertEquals(2, tm2.getStaClockAnalysis().getGroupSummaries().size());
    }

    /** Modeled clocks: setup/hold times and a hold report */
    @Test
    public void testModeledClocksHoldReport() {
        Design d = load("picoblaze_ooc_X10Y235_2022_1.dcp");
        TimingManager tm = create(d, TimingFamilies.CLOCKS_MODELED);
        Assertions.assertFalse(tm.isSingleClock());
        StaGraph g = tm.getStaGraph();
        // The min delays are those of the FAST_MIN corner
        int faster = 0;
        for (int e = 0; e < g.getNumEdges(); e++) {
            if (g.getEdgeDelayMin(e) < g.getEdgeDelayMax(e)) faster++;
        }
        Assertions.assertTrue(faster > g.getNumEdges() / 2);

        map(d, tm);
        PrintStream out = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buf));
        try {
            tm.getCriticalPathInfo();
        } finally {
            System.setOut(out);
        }
        String report = buf.toString();
        Assertions.assertTrue(report.contains("Critical path delay (ps):"), report);
        Assertions.assertTrue(report.contains("Clock groups (launch -> capture)"), report);
        Assertions.assertTrue(report.contains("WHS(ns)"), report);
        StaClockAnalysis an = tm.getStaClockAnalysis();
        List<StaClockAnalysis.GroupSummary> groups = an.getGroupSummaries();
        Assertions.assertEquals(1, groups.size());
        Assertions.assertTrue(groups.get(0).getNumHoldEndpoints() > 100);
        Assertions.assertTrue(Float.isFinite(groups.get(0).getWHS()));
        // Setup times of the fitted arcs: WNS differs from that of the ideal clock without them
        Assertions.assertTrue(Float.isFinite(groups.get(0).getWNS()));
    }

    /** Both values of the clocks switch build and time the design */
    @Test
    public void testSwitchCombinations() {
        Design versal = load("picoblaze_2022.2.dcp");
        Design usPlus = load("picoblaze_ooc_X10Y235_2022_1.dcp");
        for (Design d : new Design[]{versal, usPlus}) {
            for (String clocks : new String[]{TimingFamilies.CLOCKS_IDEAL, TimingFamilies.CLOCKS_MODELED}) {
                TimingManager tm = create(d, clocks);
                List<Connection> conns = map(d, tm);
                float max = tm.computeArrivalRequiredTimes();
                Assertions.assertTrue(max > 500 && max < 20000, clocks + ": " + max);
                tm.calculateCriticality(conns, 0.99f, 3f);
                // Without clocks (the Versal picoblaze), modeled clocks fall back to the single requirement
                Assertions.assertEquals(d == versal || clocks.equals(TimingFamilies.CLOCKS_IDEAL),
                        tm.isSingleClock(), clocks);
            }
        }
    }

    /**
     * The warnings of the constraints, clock network, clock model, uncertainties and clock binding are printed
     * once, one line per kind; the router's timing manager builds its clock model when first needed (after the
     * router routed the clock nets, see refreshClockTiming()).
     */
    @Test
    public void testClockWarningsPrinted() {
        Design d = load("picoblaze_ooc_X10Y235_2022_1.dcp");
        d.addXDCConstraint(ConstraintGroup.NORMAL, "set_input_delay -clock clk 0.5 din");
        d.addXDCConstraint(ConstraintGroup.NORMAL, "set_input_delay -clock clk 0.6 din");
        PrintStream old = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TimingManager tm;
        try {
            System.setOut(new PrintStream(bytes, true));
            tm = create(d, null);
            tm.computeArrivalRequiredTimes();
            tm.refreshClockTiming();
        } finally {
            System.setOut(old);
        }
        String out = bytes.toString();
        String w = "WARNING: Timing constraints: set_input_delay (not supported, ignored) (2 times)";
        Assertions.assertTrue(out.contains(w), out);
        Assertions.assertEquals(out.indexOf(w), out.lastIndexOf(w), out);
        Assertions.assertFalse(tm.isSingleClock());
        ClockModel cm = tm.getStaClockBinding().getClockModel();
        Assertions.assertTrue(cm.includesSourceLatency());
        Assertions.assertTrue(cm.getNumClockPins() > 0);
        Assertions.assertEquals(cm.getNumClockPins(), cm.getNumRoutedPins() + cm.getNumEstimatedPins());

        // Criticalities computed first, before any other timing query, use the clock model too
        TimingManager first = create(d, null);
        List<Connection> c1 = map(d, first);
        first.calculateCriticality(c1, 0.99f, 3f);
        TimingManager second = create(d, null);
        List<Connection> c2 = map(d, second);
        second.refreshClockTiming();
        second.calculateCriticality(c2, 0.99f, 3f);
        for (int i = 0; i < c1.size(); i++) {
            Assertions.assertEquals(c2.get(i).getCriticality(), c1.get(i).getCriticality(), 1e-6f, c1.get(i).toString());
        }
    }
}
