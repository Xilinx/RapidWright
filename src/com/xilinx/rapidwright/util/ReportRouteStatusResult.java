/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

    public final int logicalNets;
    public final int netsWithNoPlacedPins;
    public final int netsNotNeedingRouting;
    public final int internallyRoutedNets;
    public final int netsWithNoLoads;
    public final int implicitlyRoutedPorts;
    public final int routableNets;
    public final int unroutedNets;
    public final int fullyRoutedNets;
    public final int netsWithNoDriver;
    public final int netsWithRoutingErrors;
    public final int netsWithSomeUnplacedPins;
    public final int netsWithSomeUnroutedPins;

    private static int parseLog(List<String> log, String key) {
        List<String> matchingLines = VivadoTools.searchVivadoLog(log, key);
        if (matchingLines.isEmpty()) {
            return 0;
        }
        // Consider first match only
        return Integer.parseInt(matchingLines.get(0).replaceAll("[^\\d]", ""));
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
    }

    public boolean isFullyRouted() {
        return unroutedNets == 0 && netsWithRoutingErrors == 0;
    }

}
