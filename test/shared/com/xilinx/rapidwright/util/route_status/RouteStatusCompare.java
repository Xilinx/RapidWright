/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util.route_status;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatus;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Compares the result of {@link ReportRouteStatus#reportVivadoRouteStatus(Design, boolean)}
 * against a golden report written by Vivado's `report_route_status -list_all_nets`, both for the
 * summary counts and for the per-net statuses. A test harness: it also times the method, and can
 * write the comparison as JSON for collecting results over many designs.
 */
public class RouteStatusCompare {

    private static final int MAX_EXAMPLES = 5;

    public static List<String> readLines(Path path) throws IOException {
        try (BufferedReader br = FileTools.getProperInputStream(path.toString())) {
            return br.lines().collect(Collectors.toList());
        }
    }

    /** Facts about a static net, for analysis: [has logical source, pins, PIPs, site insts] */
    private static JSONArray staticInfo(Design design, String name) {
        Net net = design.getNet(name);
        boolean logical = false;
        EDIFLibrary prims = design.getNetlist().getHDIPrimitivesLibrary();
        EDIFCell source = prims.getCell(name.equals(Net.GND_NET) ? "GND" : "VCC");
        if (source != null) {
            for (EDIFLibrary lib : design.getNetlist().getLibraries()) {
                for (EDIFCell cell : lib.getCells()) {
                    for (EDIFCellInst inst : cell.getCellInsts()) {
                        if (inst.getCellType() == source) {
                            logical = true;
                            break;
                        }
                    }
                }
            }
        }
        return new JSONArray().put(logical).put(net == null ? 0 : net.getPins().size())
                .put(net == null ? 0 : net.getPIPs().size()).put(net == null ? 0 : net.getSiteInsts().size());
    }

    /** Prints the per-net differences, by bucket, with a few example nets each. */
    private static void printNetDiffs(Map<String, List<String>> netDiffs) {
        System.out.println("Per-net diffs: " + countNetDiffs(netDiffs));
        for (Map.Entry<String, List<String>> e : netDiffs.entrySet()) {
            List<String> nets = e.getValue();
            System.out.println("  " + nets.size() + "  " + e.getKey() + "  e.g. "
                    + nets.subList(0, Math.min(MAX_EXAMPLES, nets.size())));
        }
    }

    private static int countNetDiffs(Map<String, List<String>> netDiffs) {
        return netDiffs.values().stream().mapToInt(List::size).sum();
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("USAGE: RouteStatusCompare <design.dcp|actual.rpt> <vivado_list_all_nets.rpt[.gz]> [design.edf] [out.json]");
            System.exit(1);
        }
        Path dcp = Paths.get(args[0]);
        Path golden = Paths.get(args[1]);
        if (!dcp.toString().endsWith(".dcp")) {
            // Compare two previously written reports
            ReportRouteStatusResult expected = new ReportRouteStatusResult(readLines(golden));
            ReportRouteStatusResult actual = new ReportRouteStatusResult(readLines(dcp));
            System.out.println("Summary diffs (expected actual): " + expected.diffSummary(actual));
            printNetDiffs(expected.diffNets(actual));
            return;
        }
        Path edf = (args.length > 2 && !args[2].equals("-")) ? Paths.get(args[2]) : null;
        Path jsonOut = (args.length > 3) ? Paths.get(args[3]) : null;

        long t0 = System.nanoTime();
        Design design = (edf == null) ? Design.readCheckpoint(dcp) : Design.readCheckpoint(dcp, edf);
        long loadNs = System.nanoTime() - t0;

        t0 = System.nanoTime();
        ReportRouteStatusResult summaryOnly = ReportRouteStatus.reportVivadoRouteStatus(design);
        long rrsNs = System.nanoTime() - t0;
        t0 = System.nanoTime();
        ReportRouteStatusResult rw = ReportRouteStatus.reportVivadoRouteStatus(design, true);
        long rrsPerNetNs = System.nanoTime() - t0;

        ReportRouteStatusResult vivado = new ReportRouteStatusResult(readLines(golden));
        Map<String, String> summaryDiffs = vivado.diffSummary(summaryOnly);
        Map<String, List<String>> netDiffs = vivado.diffNets(rw);

        System.out.println(vivado.toString("Vivado Design Route Status"));
        System.out.println(rw.toString("RapidWright Design Route Status"));
        System.out.println("Summary diffs (vivado rapidwright): " + summaryDiffs);
        printNetDiffs(netDiffs);
        System.out.printf("Load: %.3fs  reportVivadoRouteStatus: %.3fs  (per-net: %.3fs)%n",
                loadNs / 1e9, rrsNs / 1e9, rrsPerNetNs / 1e9);

        if (jsonOut != null) {
            JSONObject json = new JSONObject();
            json.put("dcp", dcp.toString());
            json.put("load_s", loadNs / 1e9);
            json.put("rrs_s", rrsNs / 1e9);
            json.put("rrs_per_net_s", rrsPerNetNs / 1e9);
            json.put("summary_diffs", new JSONObject(summaryDiffs));
            json.put("net_diff_count", countNetDiffs(netDiffs));
            json.put("static", new JSONObject().put(Net.GND_NET, staticInfo(design, Net.GND_NET))
                    .put(Net.VCC_NET, staticInfo(design, Net.VCC_NET)));
            JSONObject buckets = new JSONObject();
            for (Map.Entry<String, List<String>> e : netDiffs.entrySet()) {
                List<String> nets = e.getValue();
                buckets.put(e.getKey(), new JSONObject().put("count", nets.size())
                        .put("examples", new JSONArray(nets.subList(0, Math.min(MAX_EXAMPLES, nets.size())))));
            }
            json.put("net_diffs", buckets);
            Files.write(jsonOut, json.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
