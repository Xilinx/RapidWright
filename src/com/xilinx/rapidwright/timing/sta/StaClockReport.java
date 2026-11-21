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
import java.util.Arrays;
import java.util.List;

import com.xilinx.rapidwright.timing.clock.Clock;
import com.xilinx.rapidwright.timing.clock.ClockRelations;

/**
 * Text reports of a {@link StaClockAnalysis}, in the spirit of Vivado's timing summary: the clocks,
 * WNS/TNS/WHS/THS per group of launch and capture clocks, and the worst setup and hold checks with
 * their clock skew, CRPR and uncertainty breakdown. Times are printed in ns.
 */
public final class StaClockReport {

    private StaClockReport() {
    }

    /** Formats the report with the 5 worst setup and hold checks. */
    public static String format(StaClockAnalysis an) {
        return format(an, 5);
    }

    private static String ns(float ps) {
        return Float.isInfinite(ps) ? "inf" : String.format("%.3f", ps / 1000);
    }

    /**
     * Formats the report.
     * @param an The analysis (updated by this call, hold included).
     * @param numPaths Number of worst setup and of worst hold checks to detail.
     * @return The report.
     */
    public static String format(StaClockAnalysis an, int numPaths) {
        an.update();
        an.updateHold();
        StaClockBinding b = an.getBinding();
        List<Clock> clocks = b.getClocks();
        StringBuilder sb = new StringBuilder();
        sb.append("Clocks\n");
        sb.append(String.format("  %-32s %10s  %-20s %10s  %s%n", "Clock", "Period", "Waveform", "Latency", "Master"));
        for (Clock c : clocks) {
            double[] w = c.getWaveform();
            sb.append(String.format("  %-32s %10.3f  {%.3f %.3f}%s %10.3f  %s%n", c.getName(), c.getPeriod() / 1000,
                    w[0] / 1000, w[1] / 1000, pad(w), c.getSourceLatency() / 1000,
                    c.getMaster() == null ? "-" : c.getMaster().getName()));
        }
        sb.append("\nClock groups (launch -> capture)\n");
        if (hasUnrelatedFalse(b.getRelations())) {
            sb.append("  Paths between unrelated clocks (no common primary clock) are false paths, not timed\n");
        }
        sb.append(String.format("  %-24s %-24s %9s %9s %11s %8s %8s %9s %11s %8s%n", "From Clock", "To Clock",
                "Req(ns)", "WNS(ns)", "TNS(ns)", "Failing", "Total", "WHS(ns)", "THS(ns)", "Failing"));
        for (StaClockAnalysis.GroupSummary s : an.getGroupSummaries()) {
            sb.append(String.format("  %-24s %-24s %9s %9s %11s %8d %8d %9s %11s %8d%n",
                    clocks.get(s.launchClock).getName(), clocks.get(s.captureClock).getName(), ns(s.requirement),
                    ns(s.wns), ns(s.tns), s.failing, s.endpoints, ns(s.whs), ns(s.ths), s.holdFailing));
        }
        // The numPaths worst checks of the endpoints by slack (the first endpoint first among equal slacks)
        List<StaClockAnalysis.Check> setup = new ArrayList<>();
        List<StaClockAnalysis.Check> hold = new ArrayList<>();
        StaGraph g = an.getGraph();
        for (int k = 0; k < an.getNumEndpoints() && numPaths > 0; k++) {
            int p = an.getEndpointPin(k);
            addWorst(setup, an.getWorstSetupCheck(p), numPaths);
            addWorst(hold, an.getWorstHoldCheck(p), numPaths);
        }
        appendChecks(sb, "Worst setup checks", setup, numPaths, an, clocks, g, true);
        appendChecks(sb, "Worst hold checks", hold, numPaths, an, clocks, g, false);
        return sb.toString();
    }

    /**
     * Adds a check to a list sorted by slack, after those of equal slack, keeping the n first.
     * @param worst The checks.
     * @param check The check, or null for none.
     * @param n The number of checks to keep.
     */
    private static void addWorst(List<StaClockAnalysis.Check> worst, StaClockAnalysis.Check check, int n) {
        if (check == null) {
            return;
        }
        int i = worst.size();
        while (i > 0 && Double.compare(worst.get(i - 1).slack, check.slack) > 0) {
            i--;
        }
        if (i < n) {
            worst.add(i, check);
            if (worst.size() > n) {
                worst.remove(n);
            }
        }
    }

    /** Whether a pair of clocks is false for being unrelated */
    private static boolean hasUnrelatedFalse(ClockRelations rel) {
        if (rel == null || !rel.isUnrelatedFalse()) return false;
        for (int a = 0; a < rel.getNumClocks(); a++) {
            for (int c = 0; c < rel.getNumClocks(); c++) {
                if (rel.isUnrelated(a, c)) return true;
            }
        }
        return false;
    }

    private static String pad(double[] w) {
        int len = String.format("{%.3f %.3f}", w[0] / 1000, w[1] / 1000).length();
        char[] c = new char[Math.max(0, 20 - len)];
        Arrays.fill(c, ' ');
        return new String(c);
    }

    private static void appendChecks(StringBuilder sb, String title, List<StaClockAnalysis.Check> checks, int n,
                                     StaClockAnalysis an, List<Clock> clocks, StaGraph g, boolean setup) {
        if (n <= 0 || checks.isEmpty()) {
            return;
        }
        sb.append("\n").append(title).append("\n");
        for (int i = 0; i < Math.min(n, checks.size()); i++) {
            StaClockAnalysis.Check c = checks.get(i);
            int[] path = an.getPath(c);
            int start = path.length == 0 ? c.endpoint : g.getEdgeFrom(path[0]);
            String lc = clocks.get(c.launchEdge >> 1).getName() + ((c.launchEdge & 1) != 0 ? " (fall)" : "");
            String cc = clocks.get(c.captureEdge >> 1).getName() + ((c.captureEdge & 1) != 0 ? " (fall)" : "");
            sb.append(String.format("  Slack %s ns: %s -> %s%n", ns(c.slack), name(g, start), name(g, c.endpoint)));
            sb.append(String.format("    launch %s, capture %s, requirement %s%n", lc, cc, ns(c.relation)));
            sb.append(String.format("    data path %s (%d edges), clock skew %s (source %s + %s, destination %s + %s, "
                    + "CRPR %s), uncertainty %s, %s %s%n",
                    ns(c.getDataPathDelay()), path.length, ns(c.getSkew()), ns(c.launchInsertion), ns(c.launchLatency),
                    ns(c.captureInsertion), ns(c.captureLatency), ns(c.crpr), ns(c.uncertainty),
                    setup ? "setup" : "hold", ns(c.checkTime)));
        }
    }

    private static String name(StaGraph g, int pin) {
        try {
            return g.getPinName(pin);
        } catch (RuntimeException e) {
            return "pin " + pin;
        }
    }
}
