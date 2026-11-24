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

import java.io.PrintStream;
import java.util.Arrays;

import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Path and slack reports of a {@link StaClockAnalysis}, such as that of
 * {@link com.xilinx.rapidwright.timing.TimingManager#getStaClockAnalysis()}: the critical path (the data path of the
 * worst setup check, whose slack is the WNS), the path of largest delay between two pins, path delays, the setup
 * slacks of the endpoints, WNS and TNS, and the critical path printout of
 * {@link com.xilinx.rapidwright.timing.TimingManager#getCriticalPathInfo()}. With the single ideal clock of a design
 * without clocks (see {@link StaClockBinding#singleClock(StaGraph, float)}), every endpoint has the same
 * requirement: an endpoint's setup slack is the requirement less its arrival time, and the critical path is the
 * path of largest arrival time.
 */
public class TimingReport {

    /**
     * Describes the edges of a printed path with what the timing graph does not hold, such as the
     * physical net of an edge or the nodes of its route.
     */
    public interface PathDescriber {
        /**
         * Gets the name of the physical net of an edge of the path.
         * @param edge The edge.
         * @return The name, or null for an edge that is not part of a net.
         */
        default String getNetName(int edge) {
            return null;
        }

        /**
         * Prints the details of an edge of the path, in verbose mode.
         * @param out Where to print.
         * @param edge The edge.
         */
        default void printDetails(PrintStream out, int edge) {
        }
    }

    private final StaClockAnalysis analysis;
    private final StaGraph g;

    /**
     * Creates the reports of an analysis.
     * @param analysis The analysis, e.g. {@link com.xilinx.rapidwright.timing.TimingManager#getStaClockAnalysis()}.
     */
    public TimingReport(StaClockAnalysis analysis) {
        this.analysis = analysis;
        this.g = analysis.getGraph();
    }

    /**
     * Gets the endpoints of the analysis (the pins with timing checks).
     * @return Their pin ids, in increasing order.
     */
    public int[] getEndpoints() {
        int[] ends = new int[analysis.getNumEndpoints()];
        for (int k = 0; k < ends.length; k++) {
            ends[k] = analysis.getEndpointPin(k);
        }
        return ends;
    }

    /**
     * Gets the worst setup slack of each endpoint (see {@link StaClockAnalysis#getEndpointSetupSlacks()}),
     * updating the analysis first; with a single ideal clock, the requirement less the endpoint's arrival time.
     * @return The slacks in ps, in the order of {@link #getEndpoints()}; +infinity for an endpoint without a timed
     * setup check.
     */
    public float[] getEndpointSlacks() {
        analysis.update();
        StaClockBinding b = analysis.getBinding();
        if (!b.isSingleClock()) {
            return analysis.getEndpointSetupSlacks();
        }
        // (The requirement as given: the clock of a binding without one has the smallest positive period)
        float[] slacks = new float[analysis.getNumEndpoints()];
        for (int k = 0; k < slacks.length; k++) {
            slacks[k] = b.getSingleClockRequirement() - analysis.getMaxArrival(analysis.getEndpointPin(k));
        }
        return slacks;
    }

    /**
     * Gets the worst negative slack: the smallest endpoint setup slack (see {@link #getEndpointSlacks()}).
     * @return The WNS in ps (positive if every check is met); if no endpoint has a timed setup check, +infinity, or
     * the requirement with a single ideal clock.
     */
    public float getWNS() {
        StaClockBinding b = analysis.getBinding();
        float wns = b.isSingleClock() ? b.getSingleClockRequirement() : Float.POSITIVE_INFINITY;
        for (float s : getEndpointSlacks()) {
            wns = Math.min(wns, s);
        }
        return wns;
    }

    /**
     * Gets the total negative slack: the sum of the negative endpoint slacks.
     * @return The TNS in ps (0 or less).
     */
    public float getTNS() {
        double tns = 0;
        for (float s : getEndpointSlacks()) {
            if (s < 0) tns += s;
        }
        return (float) tns;
    }

    /**
     * Gets the worst setup check of the analysis, updating it first: that of the endpoint of least setup slack (see
     * {@link StaClockAnalysis#getWorstSetupCheck()}); with a single ideal clock, that of the endpoint of largest
     * arrival time (the first one among ties).
     * @return The check, or null if no endpoint has a timed setup check.
     */
    public StaClockAnalysis.Check getWorstSetupCheck() {
        analysis.update();
        if (!analysis.getBinding().isSingleClock()) {
            return analysis.getWorstSetupCheck();
        }
        int worst = -1;
        float worstArrival = 0f;
        for (int k = 0; k < analysis.getNumEndpoints(); k++) {
            int p = analysis.getEndpointPin(k);
            float a = analysis.getMaxArrival(p);
            if (a != Float.NEGATIVE_INFINITY && (worst < 0 || a > worstArrival)) {
                worst = p;
                worstArrival = a;
            }
        }
        return worst < 0 ? null : analysis.getWorstSetupCheck(worst);
    }

    /**
     * Gets the critical path: the data path of the worst setup check (see {@link #getWorstSetupCheck()}).
     * @return Its edges, from its startpoint; empty if no endpoint has a timed setup check.
     */
    public int[] getCriticalPath() {
        StaClockAnalysis.Check worst = getWorstSetupCheck();
        return worst == null ? new int[0] : analysis.getPath(worst);
    }

    /**
     * Gets the path of largest delay between two pins, whatever their clocks.
     * @param from The first pin of the path.
     * @param to The last pin of the path.
     * @return Its edges, in order, or null if there is no path from one to the other.
     */
    public int[] getMaxDelayPath(int from, int to) {
        if (to < from) {
            // Pins are numbered in levelized order, so any path goes up in ids
            return null;
        }
        int n = to - from + 1;
        float[] delay = new float[n];
        int[] via = new int[n];
        Arrays.fill(via, -1);
        boolean[] reached = new boolean[n];
        reached[0] = true;
        for (int p = from + 1; p <= to; p++) {
            for (int e = g.inStart[p]; e < g.inStart[p + 1]; e++) {
                int s = g.eFrom[e];
                if (s < from || !reached[s - from]) continue;
                float d = delay[s - from] + g.getEdgeDelayMax(e);
                if (!reached[p - from] || d > delay[p - from]) {
                    delay[p - from] = d;
                    via[p - from] = e;
                    reached[p - from] = true;
                }
            }
        }
        if (!reached[n - 1]) {
            return null;
        }
        int len = 0;
        for (int p = to; p != from; p = g.eFrom[via[p - from]]) {
            len++;
        }
        int[] path = new int[len];
        for (int p = to; p != from; p = g.eFrom[via[p - from]]) {
            path[--len] = via[p - from];
        }
        return path;
    }

    /**
     * Gets the delay of a path: the sum of the delays of its edges.
     * @param path The edges of the path.
     * @return Its delay in ps.
     */
    public float getPathDelay(int[] path) {
        return getPathDelay(g, path);
    }

    /**
     * Gets the logic part of the delay of an edge: its fixed delay less its intra-site part.
     */
    public float getLogicDelay(int edge) {
        return g.eFixedMax[edge] - g.eIntraMax[edge];
    }

    /**
     * Gets the net part of the delay of an edge: its intra-site and route delays.
     */
    public float getNetDelay(int edge) {
        int c = g.eConn[edge];
        return c < 0 ? g.eIntraMax[edge] : g.eIntraMax[edge] + g.connRouteMax[c];
    }

    /**
     * Prints the critical path (see {@link #getCriticalPath()}): its requirement (the time between the launch and
     * capture clock edges of the worst setup check), its data path delay (the arrival time less the launch clock
     * delay) and its slack, which is the WNS of the analysis; with several clocks, the clock skew, uncertainty and
     * setup time that make up the difference; those with the timing closure guarantee pessimism on the data path
     * delay; and the table of the delays of the path's edges. With a single ideal clock, the requirement is that
     * of the clock, 0 for none (see {@link StaClockBinding#singleClock(StaGraph, float)}), and the slack the
     * requirement less the critical path delay.
     * @param out Where to print.
     * @param pessimismA Multiplicative pessimism factor of the timing closure guarantee.
     * @param pessimismB Additive pessimism of the timing closure guarantee, in ps.
     * @param verbose Whether to list the edges first and the details of each edge after.
     * @param describer Describes the edges; may be null.
     */
    public void printCriticalPathInfo(PrintStream out, float pessimismA, float pessimismB, boolean verbose,
                                      PathDescriber describer) {
        StaClockAnalysis.Check check = getWorstSetupCheck();
        int[] path = check == null ? new int[0] : analysis.getPath(check);
        StaClockBinding b = analysis.getBinding();
        if (b.isSingleClock() || check == null) {
            float requirement = b.isSingleClock() ? b.getSingleClockRequirement() : 0f;
            float arr = getPathDelay(path);
            out.printf(MessageGenerator.formatString("Timing requirement (ps):", requirement));
            out.printf(MessageGenerator.formatString("Critical path delay (ps):", Math.round(arr)));
            out.printf(MessageGenerator.formatString("Slack (ps):", Math.round(requirement - arr)));
            out.printf(MessageGenerator.formatString("With timing closure guarantee:"));
            float adjusted = pessimismA * arr + pessimismB;
            out.printf(MessageGenerator.formatString("Critical path delay (ps):", Math.round(adjusted)));
            out.printf(MessageGenerator.formatString("Slack (ps):", Math.round(requirement - adjusted)));
            printPath(out, g, path, arr, verbose, describer);
            return;
        }
        float dataPath = check.getDataPathDelay();
        out.printf(MessageGenerator.formatString("Timing requirement (ps):", check.relation));
        out.printf(MessageGenerator.formatString("Critical path delay (ps):", Math.round(dataPath)));
        out.printf(MessageGenerator.formatString("Slack (ps):", Math.round(check.slack)));
        out.printf(MessageGenerator.formatString("Clock skew (ps):", Math.round(check.getSkew())));
        out.printf(MessageGenerator.formatString("Clock uncertainty (ps):", Math.round(check.uncertainty)));
        out.printf(MessageGenerator.formatString("Setup time (ps):", Math.round(check.checkTime)));
        out.printf(MessageGenerator.formatString("With timing closure guarantee:"));
        float adjusted = pessimismA * dataPath + pessimismB;
        out.printf(MessageGenerator.formatString("Critical path delay (ps):", Math.round(adjusted)));
        out.printf(MessageGenerator.formatString("Slack (ps):", Math.round(check.slack - (adjusted - dataPath))));
        printPath(out, g, path, getPathDelay(path), verbose, describer);
    }

    private static float getPathDelay(StaGraph g, int[] path) {
        float delay = 0;
        for (int e : path) {
            delay += g.getEdgeDelayMax(e);
        }
        return delay;
    }

    private static void printPath(PrintStream out, StaGraph g, int[] path, float arr, boolean verbose,
                                  PathDescriber describer) {
        if (path.length == 0) {
            return;
        }

        if (verbose) {
            out.println("\nTimingEdges:");
            int id = 0;
            out.println(String.format("%5d", id++) + "  superSource -> " + g.getPinName(g.eFrom[path[0]]));
            for (int e : path) {
                out.println(String.format("%5d", id++) + "  " + g.getPinName(g.eFrom[e]) + " -> "
                        + g.getPinName(g.eTo[e]));
            }
            out.println(String.format("%5d", id) + "  " + g.getPinName(g.eTo[path[path.length - 1]])
                    + " -> superSink");
        }
        printPathTable(out, g, path, arr, describer);
        if (verbose && describer != null) {
            out.println();
            for (int e : path) {
                describer.printDetails(out, e);
            }
        }
    }

    private static final String ROW_FORMAT = "%10d  %8d  %16d  %10d    %-25s\n";

    private static void printPathTable(PrintStream out, StaGraph g, int[] path, float arr,
                                       PathDescriber describer) {
        out.println("\nDetail delays:");
        out.println("------------------------------------------------------------------------------");
        out.printf("%10s  %8s  %16s  %10s    %-25s\n", "Logic (ps)", "Net (ps)", "(intrasite (ps))",
                "Total (ps)", "Netlist Resource(s)");
        out.printf("----------  --------------------------  ----------    ------------------------\n");
        out.printf(ROW_FORMAT, 0, 0, 0, 0, "superSource");
        for (int e : path) {
            boolean net = g.eKind[e] == StaGraph.NET || g.eKind[e] == StaGraph.NET_INTRASITE;
            float netDelay = g.eConn[e] < 0 ? g.eIntraMax[e] : g.eIntraMax[e] + g.connRouteMax[g.eConn[e]];
            out.printf(ROW_FORMAT,
                    Math.round(net ? g.eFixedMax[e] - g.eIntraMax[e] : g.eFixedMax[e]),
                    Math.round(net ? netDelay : 0),
                    Math.round(g.eIntraMax[e]),
                    Math.round(g.getEdgeDelayMax(e)),
                    g.getPinName(g.eFrom[e]));
            String netName = describer == null ? null : describer.getNetName(e);
            if (netName != null) {
                out.printf("%50s  %-25s\n", "", "  net: " + netName);
            }
        }
        out.printf(ROW_FORMAT, 0, 0, 0, 0, g.getPinName(g.eTo[path[path.length - 1]]));
        out.printf("----------  --------------------------  ----------    ------------------------\n");
        out.printf("%-38s  %10d\n", "Arrival time:", Math.round(arr));
        out.println("------------------------------------------------------------------------------");
    }
}
