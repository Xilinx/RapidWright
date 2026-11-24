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

package com.xilinx.rapidwright.timing.sta;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.rwroute.Connection;
import com.xilinx.rapidwright.rwroute.NetWrapper;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.family.TimingFamilies;

/**
 * Checks the single-clock timing analysis of {@link TimingManager} (ideal clocks) on real designs: its
 * arrival times, required times and criticalities equal those of the reference propagation
 * ({@link TestStaSingleClock#referenceTimes(StaGraph, float)} and
 * {@link TestStaSingleClock#referenceCriticality(StaGraph, float[][], int, float, float)}), before and after
 * the router sets route delays. The frozen values are those of the fitted timing models.
 */
public class TestStaEquivalence {

    private static final float TOLERANCE = 0.01f;

    /** Checks the arrival and required times of all pins against those of the reference */
    private static void assertSameTimes(float[][] ref, StaGraph g, StaClockAnalysis an) {
        an.update();
        Assertions.assertEquals(ref[2][0], an.getGroupNormalization(0), TOLERANCE);
        for (int p = 0; p < g.getNumPins(); p++) {
            if (an.getNumPinTags(p) == 0) {
                // A pin that no path reaches (no in-edge, no out-edge)
                Assertions.assertEquals(0, g.getInDegree(p) + g.getOutDegree(p), g.getPinName(p));
                continue;
            }
            Assertions.assertEquals(1, an.getNumPinTags(p), g.getPinName(p));
            Assertions.assertEquals(ref[0][p], an.getArrival(p, 0), TOLERANCE, g.getPinName(p));
            if (an.getShiftedRequired(p, 0) == StaClockAnalysis.DANGLING_REQUIRED) {
                // Only pins without fanout ahead: +infinity, as they constrain nothing
                Assertions.assertEquals(Float.POSITIVE_INFINITY, ref[1][p], g.getPinName(p));
            } else {
                Assertions.assertEquals(ref[1][p], an.getShiftedRequired(p, 0), TOLERANCE, g.getPinName(p));
            }
        }
    }

    /** Creates the RWRoute connections of the design and maps them to the timing graph */
    private static List<Connection> createConnections(Design d, TimingManager tm) {
        List<Connection> conns = new ArrayList<>();
        int id = 0;
        for (Net net : d.getNets()) {
            if (net.isClockNet() || net.isStaticNet() || net.getSource() == null) continue;
            NetWrapper netWrapper = new NetWrapper(id, net);
            for (SitePinInst sink : net.getSinkPins()) {
                conns.add(new Connection(id++, net.getSource(), sink, netWrapper));
            }
        }
        tm.setTimingEdgesOfConnections(conns);
        return conns;
    }

    /**
     * Checks the criticalities that {@link TimingManager#calculateCriticality(List, float, float)} gives the
     * connections against those computed from the reference times over the edges that go through the connection (0
     * for a connection that the timing graph does not time).
     */
    private static void assertSameCriticality(TimingManager tm, List<Connection> conns, float[][] ref) {
        StaGraph g = tm.getStaGraph();
        tm.calculateCriticality(conns, 0.99f, 3f);
        int numTimed = 0;
        for (Connection c : conns) {
            int conn = c.getStaConnId();
            float expected = conn < 0 ? 0f : TestStaSingleClock.referenceCriticality(g, ref, conn, 0.99f, 3f);
            Assertions.assertEquals(expected, c.getCriticality(), 1e-5f, c.getSink().toString());
            if (conn >= 0) numTimed++;
        }
        Assertions.assertTrue(numTimed > conns.size() / 2);
    }

    @ParameterizedTest
    @CsvSource({
            // Design, endpoints, max arrival (ps), delay of the path between two of its pins (ps)
            "picoblaze_ooc_X10Y235_2022_1.dcp,432,2489.0,1447.54",
            // (No clock: a requirement of 0)
            "picoblaze_2022.2.dcp,411,2299.4,1537.8",
    })
    public void testSameAsReference(String dcp, int numEndpoints, float expectedMaxArrival, float otherPathDelay) {
        String oldClocks = System.setProperty(TimingFamilies.CLOCKS_PROPERTY, TimingFamilies.CLOCKS_IDEAL);
        try {
            Design d = RapidWrightDCP.loadDCP(dcp);
            TimingManager tm = new TimingManager(d);
            float requirement = TimingManager.getDesignTimingRequirement(d) * 1000;
            tm.setTimingRequirementPs(requirement);
            Assertions.assertTrue(tm.isSingleClock());
            StaGraph g = tm.getStaGraph();
            StaClockAnalysis an = tm.getStaClockAnalysis();
            Assertions.assertEquals(0, g.getNumCutEdges());
            float maxArrival = tm.computeArrivalRequiredTimes();
            float[][] ref = TestStaSingleClock.referenceTimes(g, requirement);
            assertSameTimes(ref, g, an);

            TimingReport report = new TimingReport(an);
            int[] criticalPath = report.getCriticalPath();
            Assertions.assertEquals(maxArrival, report.getPathDelay(criticalPath), TOLERANCE);
            Assertions.assertEquals(numEndpoints, report.getEndpoints().length);
            Assertions.assertEquals(expectedMaxArrival, maxArrival, TOLERANCE);
            // See TestTimingPaths.testGetTimingPaths()
            int[] otherPath = report.getMaxDelayPath(g.getPin("processor/sx_addr4_flop/Q"),
                    g.getPin("output_port_w_reg[6]/D"));
            Assertions.assertEquals(otherPathDelay, report.getPathDelay(otherPath), TOLERANCE);

            List<Connection> conns = createConnections(d, tm);
            assertSameCriticality(tm, conns, ref);

            // Route delays as the router would set them
            Random r = new Random(1);
            for (Connection c : conns) {
                tm.setRouteDelay(c, 50 + r.nextInt(2000) + r.nextFloat());
            }
            Assertions.assertTrue(tm.getStaClockAnalysis().isDirty());
            tm.computeArrivalRequiredTimes();
            float[][] ref2 = TestStaSingleClock.referenceTimes(g, requirement);
            assertSameTimes(ref2, g, an);
            assertSameCriticality(tm, conns, ref2);
        } finally {
            if (oldClocks == null) {
                System.clearProperty(TimingFamilies.CLOCKS_PROPERTY);
            } else {
                System.setProperty(TimingFamilies.CLOCKS_PROPERTY, oldClocks);
            }
        }
    }
}
