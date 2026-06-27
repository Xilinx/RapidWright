/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

public class ReportPlaceStatusResult {

    public int logicalCells;
    public int unplacedCells;
    public int placedCells;
    public int cellsWithFixedPlacement;
    public int usedSites;
    public int sitesWithRouteThrus;
    public int sitesWithInvertedInputs;
    public int sitesWithTieOffPins;
    public int sitesWithErrors;

    private static int parseLog(List<String> log, String key) {
        List<String> matchingLines = VivadoTools.searchVivadoLog(log, key);
        if (matchingLines.isEmpty()) {
            return 0;
        }
        // Consider first match only
        return Integer.parseInt(matchingLines.get(0).replaceAll("[^\\d]", ""));
    }

    public ReportPlaceStatusResult() {
    }

    /**
     * Analyze a log file produced by Vivado's `report_place_status`
     * command.
     *
     * @param log the List<String> of lines to be analyzed
     */
    public ReportPlaceStatusResult(List<String> log) {
        logicalCells = parseLog(log, "# of logical cells");
        unplacedCells = parseLog(log, "# of unplaced cells");
        placedCells = parseLog(log, "# of placed cells");
        cellsWithFixedPlacement = parseLog(log, "# of cells with fixed placement");
        usedSites = parseLog(log, "# of used sites");
        sitesWithRouteThrus = parseLog(log, "# of sites with route thrus");
        sitesWithInvertedInputs = parseLog(log, "# of sites with inverted inputs");
        sitesWithTieOffPins = parseLog(log, "# of sites with tie-off pins");
        sitesWithErrors = parseLog(log, "# of sites with errors");
    }

    public boolean isFullyPlaced() {
        return logicalCells > 0 && unplacedCells == 0 && sitesWithErrors == 0;
    }

    @Override
    public String toString() {
        return toString("Design Place Status");
    }

    public String toString(String title) {
        StringBuilder sb = new StringBuilder();
        sb.append(title);
        sb.append("\n");
        sb.append("   ---------------------------------------------- : ----------- :\n");
        sb.append(String.format("   # of logical cells............................ : %11d :\n", logicalCells));
        sb.append(String.format("       # of unplaced cells....................... : %11d :\n", unplacedCells));
        sb.append(String.format("       # of placed cells......................... : %11d :\n", placedCells));
        sb.append(String.format("       # of cells with fixed placement........... : %11d :\n", cellsWithFixedPlacement));
        sb.append(String.format("   # of used sites............................... : %11d :\n", usedSites));
        sb.append(String.format("       # of sites with route thrus............... : %11d :\n", sitesWithRouteThrus));
        sb.append(String.format("       # of sites with inverted inputs........... : %11d :\n", sitesWithInvertedInputs));
        sb.append(String.format("       # of sites with tie-off pins.............. : %11d :\n", sitesWithTieOffPins));
        sb.append(String.format("       # of sites with errors.................... : %11d :\n", sitesWithErrors));
        sb.append("   ---------------------------------------------- : ----------- :");
        return sb.toString();
    }

}
