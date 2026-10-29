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

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
     * `report_route_status -list_all_nets`. Null unless per-net status was requested.
     */
    public Map<NetRouteStatus, Set<String>> netsByStatus;

    /**
     * Per-net route status flags, mirroring the route types of Vivado's
     * `report_route_status -route_type` and the per-category sections of
     * `report_route_status -list_all_nets`. A net may have more than one flag.
     * Constants are declared in the order Vivado lists the sections.
     */
    public enum NetRouteStatus {
        /** None of the pins for the net are placed. */
        UNPLACED_ALL("Nets with no placed ports or pins"),
        /** The net has some unplaced pins. */
        UNPLACED("Nets with some unplaced ports or pins"),
        /** The net needs to be routed but has no route data. */
        UNROUTED("Unrouted Nets"),
        /** The net has routing but is not fully routed. */
        PARTIAL("Partially Routed Nets"),
        /** The net has known gaps in the route tree. */
        GAPS("Nets with Gaps"),
        /** The net has either no loads or no drivers and is connected to a top level port. */
        HIERPORT("Nets with Hierarchal Ports"),
        /** The net shares routing resources or site pins with another net. */
        CONFLICTS("Nets with Routing or Site Pin Conflicts"),
        /** The net has routing that does not go to a valid load, or disconnected routing. */
        ANTENNAS("Nets with Antennas or Islands"),
        /** The net has no drivers. */
        NODRIVER("Nets with No Driver"),
        /** The net has multiple drivers. */
        MULTI_DRIVER("Nets with Multiple Driver"),
        /** The net has locked routing nodes. */
        LOCKED_NODES("Nets with Locked Nodes"),
        /** The net has a route tree associated with it. */
        HAS_ROUTING("Nets with any Stored Routing"),
        /** The net is fully routed inside a site and needs no routing. */
        INTRASITE("Nets Internally Routed Inside a Site"),
        /** The net has no loads and so needs no routing. */
        NOLOADS("Nets with No Loads"),
        /** The net is fully routed with no errors. */
        ROUTED("Routed Nets"),
        ;

        private final String sectionTitle;

        NetRouteStatus(String sectionTitle) {
            this.sectionTitle = sectionTitle;
        }

        /**
         * Gets the title of the section listing nets with this status in the output of Vivado's
         * `report_route_status -list_all_nets`.
         * @return The section title, without the trailing colon.
         */
        public String getSectionTitle() {
            return sectionTitle;
        }
    }

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

    @Override
    public String toString() {
        return toString("Design Route Status");
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
