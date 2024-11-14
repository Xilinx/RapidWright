/*
 * Copyright (c) 2023-2024, Advanced Micro Devices, Inc.
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

import java.util.List;

public class ReportRouteStatusResult {

    public int logicalNets;
    public int netsWithNoPlacedPins;
    public int netsNotNeedingRouting;
    public int internallyRoutedNets;
    public int netsWithNoLoads;
    public int implicitlyRoutedPorts;
    public int routableNets;
    public int unroutedNets;
    public int fullyRoutedNets;
    public int netsWithNoDriver;
    public int netsWithRoutingErrors;
    public int netsWithSomeUnplacedPins;
    public int netsWithSomeUnroutedPins;
    public int netsWithResourceConflicts;

    private static int parseLog(List<String> log, String key) {
        List<String> matchingLines = VivadoTools.searchVivadoLog(log, key);
        if (matchingLines.isEmpty()) {
            return 0;
        }
        // Consider first match only
        return Integer.parseInt(matchingLines.get(0).replaceAll("[^\\d]", ""));
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
        unroutedNets = parseLog(log, "# of unrouted nets");
        fullyRoutedNets = parseLog(log, "# of fully routed nets");
        netsWithNoDriver = parseLog(log, "# of nets with no driver");
        netsWithRoutingErrors = parseLog(log, "# of nets with routing errors");
        netsWithSomeUnplacedPins = parseLog(log, "# of nets with some unplaced pins");
        netsWithSomeUnroutedPins = parseLog(log, "# of nets with some unrouted pins");
        netsWithResourceConflicts = parseLog(log, "# of nets with resource conflicts");
    }

    public boolean isFullyRouted() {
        return logicalNets > 0 && unroutedNets == 0 && netsWithRoutingErrors == 0;
    }

    @Override
    public String toString() {
        return toString("Design Route Status");
    }

    public String toString(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(title);
        sb.append("\n");
        sb.append("                                               :      # nets :\n");
        sb.append("   ------------------------------------------- : ----------- :\n");
        sb.append(String.format("   # of logical nets.......................... : %11d :\n", logicalNets));
        sb.append(String.format("       # of nets not needing routing.......... : %11d :\n", netsNotNeedingRouting));
        if (internallyRoutedNets > 0) {
            sb.append(String.format("           # of internally routed nets........ : %11d :\n", internallyRoutedNets));
        }
        if (netsWithNoLoads > 0) {
            sb.append(String.format("           # of nets with no loads............ : %11d :\n", netsWithNoLoads));
        }
        if (implicitlyRoutedPorts > 0) {
            sb.append(String.format("           # of implicitly routed ports....... : %11d :\n", implicitlyRoutedPorts));
        }
        sb.append(String.format("       # of routable nets..................... : %11d :\n", routableNets));
        if (unroutedNets > 0) {
            sb.append(String.format("           # of unrouted nets................. : %11d :\n", unroutedNets));
        }
        sb.append(String.format("           # of fully routed nets............. : %11d :\n", fullyRoutedNets));
        sb.append(String.format("       # of nets with routing errors.......... : %11d :\n", netsWithRoutingErrors));
        if (netsWithSomeUnroutedPins > 0) {
            sb.append(String.format("           # of nets with some unrouted pins.. : %11d :\n", netsWithSomeUnroutedPins));
        }
        if (netsWithResourceConflicts > 0) {
            sb.append(String.format("           # of nets with resource conflicts.. : %11d :\n", netsWithResourceConflicts));
        }
        sb.append("   ------------------------------------------- : ----------- :");
        return sb.toString();
    }

}
