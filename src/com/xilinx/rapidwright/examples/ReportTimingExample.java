/*
 * Copyright (c) 2019-2022, Xilinx, Inc.
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.sta.StaClockAnalysis;
import com.xilinx.rapidwright.timing.sta.TimingReport;

/**
 * Basic example to show reading in a placed and routed DCP and reporting its critical path: the data
 * path of the worst setup check of the timing analysis, whose slack is the worst negative slack (WNS).
 */
public class ReportTimingExample {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("USAGE: <dcp_file_name>");
            return;
        }
        CodePerfTracker t = new CodePerfTracker("Report Timing Example");
        t.useGCToTrackMemory(true);

        // Read in an example placed and routed DCP
        t.start("Read DCP");
        Design design = Design.readCheckpoint(args[0], CodePerfTracker.SILENT);

        // Instantiate and populate the timing manager for the design (its timing graph, and the timing
        // analysis with the clocks of its constraints)
        t.stop().start("Create TimingManager");
        TimingManager tim = new TimingManager(design);

        // Get the worst setup check and its data path
        t.stop().start("Get Critical Path");
        TimingReport report = new TimingReport(tim.getStaClockAnalysis());
        StaClockAnalysis.Check worst = report.getWorstSetupCheck();
        int[] criticalPath = report.getCriticalPath();

        // Print runtime summary
        t.stop().printSummary();

        System.out.println("\nCritical path: " + (int) report.getPathDelay(criticalPath) + " ps");
        if (worst != null) {
            System.out.println("Requirement: " + (int) worst.getRelation() + " ps, worst slack: "
                    + (int) worst.getSlack() + " ps");
        }
        tim.prettyPrintPathDelays(criticalPath);
    }
}
