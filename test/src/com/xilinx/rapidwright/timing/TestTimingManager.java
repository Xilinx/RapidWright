/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.NetWrapper;
import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.timing.sta.StaClockAnalysis;
import com.xilinx.rapidwright.timing.sta.StaGraph;

public class TestTimingManager {

    @Test
    public void testGetDesignTimingRequirement() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        float expectedClkPeriod = 2.850f;
        Assertions.assertEquals(expectedClkPeriod, TimingManager.getDesignTimingRequirement(d));
        
        d.addXDCConstraint("# create_clock -period 10.850 -name clk -waveform {0.000 1.425} "
                + "[get_ports -filter { NAME =~  \"*clk*\" && DIRECTION == \"IN\" }]");
        
        Assertions.assertEquals(expectedClkPeriod, TimingManager.getDesignTimingRequirement(d));
    }

    @Test
    public void testGetDesignTimingRequirementShortPeriod() {
        // A Versal design without clock constraints
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        Assertions.assertEquals(0f, TimingManager.getDesignTimingRequirement(d));

        // The period need not have as many characters as "2.850"
        d.addXDCConstraint("create_clock -period 2 -name clk [get_ports clk]");
        Assertions.assertEquals(2f, TimingManager.getDesignTimingRequirement(d));

        d.addXDCConstraint("create_clock -name clk2 -period 1.5 [get_ports clk2] # -period 0.5");
        Assertions.assertEquals(2f, TimingManager.getDesignTimingRequirement(d));
    }

    @Test
    public void testGetDesignTimingRequirementOfTclPeriods() {
        // Periods that are not literal numbers: a Tcl variable, an expression and a braced word, as the
        // timing analysis reads them
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        d.addXDCConstraint("set p 2.5");
        d.addXDCConstraint("create_clock -period $p -name clk [get_ports clk]");
        Assertions.assertEquals(2.5f, TimingManager.getDesignTimingRequirement(d));
        // Virtual clocks: a clock on ports that do not exist is not created, as in Vivado
        d.addXDCConstraint("create_clock -period [expr {2 * $p}] -name clk2");
        Assertions.assertEquals(5f, TimingManager.getDesignTimingRequirement(d));
        d.addXDCConstraint("create_clock -period {7} -name clk3");
        Assertions.assertEquals(7f, TimingManager.getDesignTimingRequirement(d));
    }

    /** The timing graph of a design builds and times */
    @Test
    public void testBuildsOnBnn() {
        Design d = RapidWrightDCP.loadDCP("bnn.dcp");
        TimingManager tm = new TimingManager(d);
        Assertions.assertTrue(tm.getStaGraph().getNumEdges() > 0);
        Assertions.assertTrue(tm.computeArrivalRequiredTimes() > 0);
    }

    /**
     * A clock that a later create_clock on the same port replaces (as Vivado does, whatever its name) does not set
     * the requirement.
     */
    @Test
    public void testGetDesignTimingRequirementOfReplacedClock() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        d.addXDCConstraint("create_clock -period 10.000 -name clk_slow [get_ports clk]");
        Assertions.assertEquals(10f, TimingManager.getDesignTimingRequirement(d));
        d.addXDCConstraint("create_clock -period 2.000 [get_ports clk]");
        Assertions.assertEquals(2f, TimingManager.getDesignTimingRequirement(d));
        TimingManager tm = new TimingManager(d);
        tm.setTimingRequirement();
        Assertions.assertEquals(2000f, tm.getTimingRequirementPs());
        // With -add, both clocks are on the port
        d.addXDCConstraint("create_clock -add -period 5.000 -name clk_add [get_ports clk]");
        Assertions.assertEquals(5f, TimingManager.getDesignTimingRequirement(d));
    }

    /** Creates the timing manager of a design as a router does */
    private static TimingManager createForRouter(Design d) {
        return new TimingManager(d, null, new RWRouteConfig(new String[0]), d.getNets(), false,
                DelayEstimator.create(d, false));
    }

    /** Maps a router connection to every sink site pin of the nets that a router routes as connections */
    private static List<Connection> mapConnections(Design d, TimingManager tm) {
        List<Connection> conns = new ArrayList<>();
        int id = 0;
        for (Net net : d.getNets()) {
            if (net.isStaticNet() || net.getSource() == null) continue;
            NetWrapper nw = new NetWrapper(id, net);
            for (SitePinInst sink : net.getSinkPins()) {
                conns.add(new Connection(id++, net.getSource(), sink, nw));
            }
        }
        tm.setTimingEdgesOfConnections(conns);
        return conns;
    }

    /**
     * Every connection of the timing graph gets the delays of a router connection: each site pin of a block RAM
     * pin on several site pins (a RAMB36's address on both RAMB18 halves, its write enable on eight site pins) is
     * the sink of its own router connection.
     */
    @Test
    public void testEveryTimingConnectionHasRouterConnection() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        TimingManager tm = createForRouter(d);
        StaGraph g = tm.getStaGraph();
        List<Connection> conns = mapConnections(d, tm);
        Set<Integer> mapped = new HashSet<>();
        int numOwn = 0;
        for (Connection c : conns) {
            int id = c.getStaConnId();
            if (id < 0) continue;
            mapped.add(id);
            if (g.getConnectionSink(id) == c.getSink()) {
                numOwn++;
            }
        }
        int numTimed = 0;
        for (int c = 0; c < g.getNumConnections(); c++) {
            SitePinInst sink = g.getConnectionSink(c);
            if (g.getConnectionNumEdges(c) == 0 || sink.getNet().isClockNet()) continue;
            numTimed++;
            Assertions.assertTrue(mapped.contains(c), "no router connection to " + sink);
        }
        Assertions.assertEquals(numTimed, numOwn);

        // The 8 write enable site pins of WEA[0]: their own router connections, and their own delays
        String ram = "your_program/ram_4096x8";
        Cell cell = d.getCell(ram);
        List<Connection> wea = new ArrayList<>();
        for (Connection c : conns) {
            if (c.getSink().getSiteInst() == cell.getSiteInst() && c.getSink().getName().startsWith("WEA")) {
                wea.add(c);
            }
        }
        Assertions.assertEquals(8, wea.size());
        for (int i = 0; i < wea.size(); i++) {
            tm.setRouteDelay(wea.get(i), 100f * (i + 1));
        }
        for (Connection c : wea) {
            Assertions.assertEquals(c.getTimingRouteDelay(), g.getConnectionRouteMax(c.getStaConnId()), 0f);
        }
    }

    /** The data input of a clock buffer used as data is the sink of a router connection, which the router times. */
    @Test
    public void testDataClockBufferInputHasRouterConnection() {
        Design d = new Design("bufg_data", "xcvu3p-ffvc1517-2-e");
        Cell src = d.createAndPlaceCell("src", Unisim.FDRE, "SLICE_X10Y60/AFF");
        Cell buf = d.createAndPlaceCell("buf", Unisim.BUFGCE, "BUFGCE_X0Y24/BUFCE");
        Cell dst = d.createAndPlaceCell("dst", Unisim.FDRE, "SLICE_X12Y62/AFF");
        Net enable = d.createNet("enable");
        enable.connect(src, "Q");
        enable.connect(buf, "I");
        Net bufferedEnable = d.createNet("enable_bufg");
        bufferedEnable.connect(buf, "O");
        bufferedEnable.connect(dst, "CE");

        TimingManager tm = createForRouter(d);
        Connection c = null;
        for (Connection conn : mapConnections(d, tm)) {
            if (conn.getSink().getNet() == enable) {
                c = conn;
            }
        }
        Assertions.assertNotNull(c);
        int id = c.getStaConnId();
        Assertions.assertTrue(id >= 0);
        Assertions.assertSame(c.getSink(), tm.getStaGraph().getConnectionSink(id));
        tm.setRouteDelay(c, 1234f);
        Assertions.assertEquals(c.getTimingRouteDelay(), tm.getStaGraph().getConnectionRouteMax(id), 0f);
    }

    /**
     * The timing of a routed design starts with the route delays of its routes at every corner: refreshing the min
     * (hold) route delays from the routing changes none of them, the SLOW_MIN ones included.
     */
    @Test
    public void testHoldRouteDelaysOfRoutedDesign() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        TimingManager tm = new TimingManager(d);
        StaGraph g = tm.getStaGraph();
        Assertions.assertTrue(g.hasSecondMinCorner());
        Assertions.assertTrue(tm.getStaClockAnalysis().hasSlowHold());
        int n = g.getNumConnections();
        Assertions.assertTrue(n > 0);
        float[][] before = new float[3][n];
        for (int c = 0; c < n; c++) {
            before[0][c] = g.getConnectionRouteMax(c);
            before[1][c] = g.getConnectionRouteMin(c);
            before[2][c] = g.getConnectionRouteMin2(c);
        }
        float[] holdSlacks = tm.getStaClockAnalysis().getEndpointHoldSlacks();
        int numSlowDifferent = 0;
        for (int c = 0; c < n; c++) {
            if (g.getConnectionRouteMin2(c) != g.getConnectionRouteMin(c)) {
                numSlowDifferent++;
            }
        }
        Assertions.assertTrue(numSlowDifferent > n / 2);

        tm.updateHoldDelays();
        for (int c = 0; c < n; c++) {
            String sink = g.getConnectionSink(c).toString();
            Assertions.assertEquals(before[0][c], g.getConnectionRouteMax(c), sink);
            Assertions.assertEquals(before[1][c], g.getConnectionRouteMin(c), sink);
            Assertions.assertEquals(before[2][c], g.getConnectionRouteMin2(c), sink);
        }
        Assertions.assertArrayEquals(holdSlacks, tm.getStaClockAnalysis().getEndpointHoldSlacks());
    }

    /** The value printed after the first occurrence of a label */
    private static float printed(String report, String label) {
        int i = report.indexOf(label);
        Assertions.assertTrue(i >= 0, label);
        return Float.parseFloat(report.substring(i + label.length()).trim().split("\\s+")[0]);
    }

    /**
     * With modeled clocks, the critical path that the timing manager reports (and that a router prints as its
     * critical path delay) is the path of the worst setup check: its slack is the WNS of the clock analysis, its
     * delay the data path delay of that check.
     */
    @Test
    public void testCriticalPathIsWorstSetupCheck() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        TimingManager tm = createForRouter(d);
        Assertions.assertFalse(tm.isSingleClock());
        StaClockAnalysis an = tm.getStaClockAnalysis();
        float delay = tm.computeArrivalRequiredTimes();
        float wns = Float.POSITIVE_INFINITY;
        for (float slack : an.getEndpointSetupSlacks()) {
            wns = Math.min(wns, slack);
        }
        StaClockAnalysis.Check worst = an.getWorstSetupCheck();
        Assertions.assertEquals(wns, worst.getSlack());
        Assertions.assertEquals(worst.getDataPathDelay(), delay);
        // The data path of the check: its edges sum to its data path delay
        float sum = 0;
        for (int e : an.getPath(worst)) {
            sum += tm.getStaGraph().getEdgeDelayMax(e);
        }
        Assertions.assertEquals(delay, sum, 0.01f);

        PrintStream old = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes));
        try {
            tm.getCriticalPathInfo();
        } finally {
            System.setOut(old);
        }
        String report = bytes.toString();
        Assertions.assertEquals(worst.getRelation(), printed(report, "Timing requirement (ps):"));
        Assertions.assertEquals(Math.round(delay), printed(report, "Critical path delay (ps):"));
        Assertions.assertEquals(Math.round(wns), printed(report, "Slack (ps):"));
        Assertions.assertEquals(Math.round(delay), printed(report, "Arrival time:"));
        // As the clock group table prints the WNS, in ns (one clock: one group)
        Assertions.assertEquals(wns / 1000, parseGroupWns(report), 0.0005f);
    }

    /** The WNS (ns) of the first clock group of the clock group table */
    private static float parseGroupWns(String report) {
        String[] lines = report.substring(report.indexOf("Clock groups")).split("\n");
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 4 && !f[0].equals("From") && !line.contains("Clock groups") && !line.contains("false paths")) {
                return Float.parseFloat(f[3]);
            }
        }
        return Float.NaN;
    }

    /** Without clocks, the critical path delay, the slack and the arrival time of the path printed agree. */
    @Test
    public void testSingleClockCriticalPathReport() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        TimingManager tm = createForRouter(d);
        Assertions.assertTrue(tm.isSingleClock());
        float delay = tm.computeArrivalRequiredTimes();
        PrintStream old = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        System.setOut(new PrintStream(bytes));
        try {
            tm.getCriticalPathInfo();
        } finally {
            System.setOut(old);
        }
        String report = bytes.toString();
        float requirement = printed(report, "Timing requirement (ps):");
        Assertions.assertEquals(Math.round(delay), printed(report, "Critical path delay (ps):"));
        Assertions.assertEquals(Math.round(requirement - delay), printed(report, "Slack (ps):"));
        Assertions.assertEquals(Math.round(delay), printed(report, "Arrival time:"));
    }

    private static int getNumSitePins(Design d) {
        int n = 0;
        for (Net net : d.getNets()) {
            n += net.getPins().size();
        }
        return n;
    }

    /**
     * A placed design whose nets have no site pins (as Vivado writes a placed checkpoint) is timed through the site
     * pins of its site routing, as if it had them: the same connections and timing as once they are created, and
     * the design is left as it is.
     */
    @Test
    public void testPlacedDesignWithoutSitePins() {
        Design d = RapidWrightDCP.loadDCP("bnn.dcp");
        Assertions.assertEquals(0, getNumSitePins(d));
        TimingManager tm = new TimingManager(d);
        Assertions.assertEquals(0, getNumSitePins(d));

        Design withPins = RapidWrightDCP.loadDCP("bnn.dcp");
        DesignTools.createMissingSitePinInsts(withPins);
        TimingManager expected = new TimingManager(withPins);
        StaGraph g = tm.getStaGraph();
        Assertions.assertEquals(expected.getStaGraph().getNumConnections(), g.getNumConnections());
        Assertions.assertEquals(expected.getStaGraph().getNumEdges(), g.getNumEdges());
        Assertions.assertTrue(g.getNumConnections() > 10000);
        Assertions.assertEquals(expected.computeArrivalRequiredTimes(), tm.computeArrivalRequiredTimes());
        Assertions.assertArrayEquals(expected.getStaClockAnalysis().getEndpointSetupSlacks(),
                tm.getStaClockAnalysis().getEndpointSetupSlacks());
        // The site routing of the analyzed design is intact: it gives the same site pins
        DesignTools.createMissingSitePinInsts(d);
        Assertions.assertEquals(getNumSitePins(withPins), getNumSitePins(d));
    }
}
