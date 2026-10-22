/*
 * Copyright (c) 2023-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Zak Nafziger, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.util.route_status.NetRouteStatus;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public class ReportRouteStatusResult {

    public int logicalNets;
    public int netsWithNoPlacedPins;
    public int netsNotNeedingRouting;
    public int internallyRoutedNets;
    public int netsWithNoLoads;
    public int implicitlyRoutedPorts;
    public int routableNets;
    public int netsWithFixedRouting;
    public int netsWithExplicitGaps;
    public int unroutedNets;
    public int fullyRoutedNets;
    public int netsWithNoDriver;
    public int netsWithRoutingErrors;
    public int netsWithSomeUnplacedPins;
    public int netsWithSomeUnroutedPins;
    public int netsWithResourceConflicts;
    public int netsWithAntennasOrIslands;

    /**
     * Names of the (parent) nets with each route status, as listed by Vivado's
     * `report_route_status -list_all_nets`. Null unless per-net status was requested or parsed.
     */
    public Map<NetRouteStatus, Set<String>> netsByStatus;

    private static int parseLog(List<String> log, String key) {
        List<String> matchingLines = VivadoTools.searchVivadoLog(log, key);
        if (matchingLines.isEmpty()) {
            return 0;
        }
        // Consider first match only, and only its value column (e.g. "# of logical nets... :  42 :")
        String line = matchingLines.get(0);
        int start = line.indexOf(':');
        int end = line.lastIndexOf(':');
        String value = (start >= 0 && end > start) ? line.substring(start + 1, end) : line;
        return Integer.parseInt(value.replaceAll("[^\\d]", ""));
    }

    /**
     * Parses the per-net sections of a log produced by Vivado's
     * `report_route_status -list_all_nets`.
     * @param log The lines to be analyzed.
     * @return A map from each route status to the names of the nets that have it.
     */
    public static Map<NetRouteStatus, Set<String>> parseNetsByStatus(List<String> log) {
        Map<NetRouteStatus, Set<String>> netsByStatus = new EnumMap<>(NetRouteStatus.class);
        for (NetRouteStatus s : NetRouteStatus.values()) {
            netsByStatus.put(s, new TreeSet<>());
        }
        Set<String> currentNets = null;
        boolean inNames = false;
        for (String line : log) {
            if (!line.startsWith(" ") && line.endsWith(":")) {
                NetRouteStatus s = NetRouteStatus.fromSectionTitle(line);
                currentNets = (s == null) ? null : netsByStatus.get(s);
                inNames = false;
            } else if (line.trim().equals("Parent Net Names:")) {
                inNames = true;
            } else if (inNames && currentNets != null && line.startsWith("    ") && !line.trim().isEmpty()) {
                currentNets.add(line.trim());
            } else if (line.trim().isEmpty()) {
                inNames = false;
            }
        }
        return netsByStatus;
    }

    public ReportRouteStatusResult() {
    }

    /**
     * Analyze a log file produced by Vivado's `report_route_status`
     * command.
     *
     * @param log the List<String> of lines to be analyzed
     */
    public ReportRouteStatusResult(List<String> log) {
        logicalNets = parseLog(log, "# of logical nets");
        netsWithNoPlacedPins = parseLog(log, "# of nets with no placed pins");
        netsNotNeedingRouting = parseLog(log, "# of nets not needing routing");
        internallyRoutedNets = parseLog(log, "# of internally routed nets");
        netsWithNoLoads = parseLog(log, "# of nets with no loads");
        implicitlyRoutedPorts = parseLog(log, "# of implicitly routed ports");
        routableNets = parseLog(log, "# of routable nets");
        netsWithFixedRouting = parseLog(log, "# of nets with fixed routing");
        netsWithExplicitGaps = parseLog(log, "# of nets with explicit gaps");
        unroutedNets = parseLog(log, "# of unrouted nets");
        fullyRoutedNets = parseLog(log, "# of fully routed nets");
        netsWithNoDriver = parseLog(log, "# of nets with no driver");
        netsWithRoutingErrors = parseLog(log, "# of nets with routing errors");
        netsWithSomeUnplacedPins = parseLog(log, "# of nets with some unplaced pins");
        netsWithSomeUnroutedPins = parseLog(log, "# of nets with some unrouted pins");
        netsWithResourceConflicts = parseLog(log, "# of nets with resource conflicts");
        netsWithAntennasOrIslands = parseLog(log, "# of nets with antennas/islands");
        if (hasNetsByStatus(log)) {
            netsByStatus = parseNetsByStatus(log);
        }
    }

    /** Checks whether a log has the per-net sections of `report_route_status -list_all_nets`. */
    private static boolean hasNetsByStatus(List<String> log) {
        for (String line : log) {
            if (!line.startsWith(" ") && line.endsWith(":") && NetRouteStatus.fromSectionTitle(line) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks whether the design is fully routed: it has nets, and none of them is unrouted, has
     * routing errors, or has no placed pins (as an unplaced net cannot be routed either).
     * @return True if fully routed.
     */
    public boolean isFullyRouted() {
        return logicalNets > 0 && unroutedNets == 0 && netsWithRoutingErrors == 0 && netsWithNoPlacedPins == 0;
    }

    /**
     * Gets the route statuses of each net, the inverse of {@link #netsByStatus}.
     * @return Map from net name to its statuses (empty if {@link #netsByStatus} is null).
     */
    public Map<String, EnumSet<NetRouteStatus>> getNetStatuses() {
        if (netsByStatus == null) {
            return Collections.emptyMap();
        }
        Map<String, EnumSet<NetRouteStatus>> byNet = new HashMap<>();
        for (Map.Entry<NetRouteStatus, Set<String>> e : netsByStatus.entrySet()) {
            for (String net : e.getValue()) {
                byNet.computeIfAbsent(net, (k) -> EnumSet.noneOf(NetRouteStatus.class)).add(e.getKey());
            }
        }
        return byNet;
    }

    /**
     * Gets the differences between the summary counts of this result and another.
     * @param other The result to compare against.
     * @return Map from count (field) name to "this other", for the counts that differ.
     */
    public Map<String, String> diffSummary(ReportRouteStatusResult other) {
        Map<String, String> diffs = new TreeMap<>();
        for (Field f : ReportRouteStatusResult.class.getFields()) {
            if (f.getType() != int.class) {
                continue;
            }
            try {
                int a = f.getInt(this);
                int b = f.getInt(other);
                if (a != b) {
                    diffs.put(f.getName(), a + " " + b);
                }
            } catch (IllegalAccessException ex) {
                throw new RuntimeException(ex);
            }
        }
        return diffs;
    }

    /**
     * Gets the differences between the per-net statuses of this result and another, bucketed by
     * "this statuses -> other statuses". A null {@link #netsByStatus} counts as having no nets.
     * @param other The result to compare against.
     * @return Map from bucket to the names of the nets in it.
     */
    public Map<String, List<String>> diffNets(ReportRouteStatusResult other) {
        Map<String, EnumSet<NetRouteStatus>> a = getNetStatuses();
        Map<String, EnumSet<NetRouteStatus>> b = other.getNetStatuses();
        Set<String> allNets = new TreeSet<>(a.keySet());
        allNets.addAll(b.keySet());
        Map<String, List<String>> buckets = new TreeMap<>();
        EnumSet<NetRouteStatus> none = EnumSet.noneOf(NetRouteStatus.class);
        for (String net : allNets) {
            EnumSet<NetRouteStatus> as = a.getOrDefault(net, none);
            EnumSet<NetRouteStatus> bs = b.getOrDefault(net, none);
            if (!as.equals(bs)) {
                buckets.computeIfAbsent(as + " -> " + bs, (k) -> new ArrayList<>()).add(net);
            }
        }
        return buckets;
    }

    @Override
    public String toString() {
        return toString("Design Route Status");
    }

    /**
     * Formats the summary followed by the per-net sections, in the layout of Vivado's
     * `report_route_status -list_all_nets` (per-category segment totals are not reproduced).
     * Requires {@link #netsByStatus} to be populated.
     * @return The report as a String.
     */
    public String toListAllNetsString() {
        StringBuilder sb = new StringBuilder();
        sb.append(toString());
        sb.append("\n\n");
        for (NetRouteStatus s : NetRouteStatus.values()) {
            Set<String> nets = netsByStatus.get(s);
            sb.append(s.getSectionTitle()).append(":\n");
            sb.append("  Parents: ").append(nets.size()).append("\n");
            if (!nets.isEmpty()) {
                sb.append("  Parent Net Names:\n");
                for (String net : nets) {
                    sb.append("    ").append(net).append("\n");
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, int value, boolean always) {
        if (always || value > 0) {
            sb.append(String.format("%s : %11d :\n", label, value));
        }
    }

    /**
     * Formats the summary table exactly as Vivado's `report_route_status` prints it, where all but
     * the top level counts are only printed if non-zero.
     * @param title The title line of the table.
     * @return The summary table.
     */
    public String toString(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(title);
        sb.append("\n");
        sb.append("                                               :      # nets :\n");
        sb.append("   ------------------------------------------- : ----------- :\n");
        appendLine(sb, "   # of logical nets..........................", logicalNets, true);
        appendLine(sb, "       # of nets with no placed pins..........", netsWithNoPlacedPins, false);
        appendLine(sb, "       # of nets not needing routing..........", netsNotNeedingRouting, true);
        appendLine(sb, "           # of internally routed nets........", internallyRoutedNets, false);
        appendLine(sb, "           # of nets with no loads............", netsWithNoLoads, false);
        appendLine(sb, "           # of implicitly routed ports.......", implicitlyRoutedPorts, false);
        appendLine(sb, "       # of routable nets.....................", routableNets, true);
        appendLine(sb, "           # of nets with fixed routing.......", netsWithFixedRouting, false);
        appendLine(sb, "           # of nets with explicit gaps.......", netsWithExplicitGaps, false);
        appendLine(sb, "           # of unrouted nets.................", unroutedNets, false);
        appendLine(sb, "           # of fully routed nets.............", fullyRoutedNets, false);
        appendLine(sb, "       # of nets with routing errors..........", netsWithRoutingErrors, true);
        appendLine(sb, "           # of nets with no driver...........", netsWithNoDriver, false);
        appendLine(sb, "           # of nets with some unplaced pins..", netsWithSomeUnplacedPins, false);
        appendLine(sb, "           # of nets with some unrouted pins..", netsWithSomeUnroutedPins, false);
        appendLine(sb, "           # of nets with resource conflicts..", netsWithResourceConflicts, false);
        appendLine(sb, "           # of nets with antennas/islands....", netsWithAntennasOrIslands, false);
        sb.append("   ------------------------------------------- : ----------- :");
        return sb.toString();
    }

}
