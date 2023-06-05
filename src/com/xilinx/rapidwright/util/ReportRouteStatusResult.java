package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Design;

import java.io.File;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ReportRouteStatusResult {

    public final int logicalNets;
    public final int netsNotNeedingRouting;
    public final int internallyRoutedNets;
    public final int netsWithNoLoads;
    public final int implicitlyRoutedPorts;
    public final int routableNets;
    public final int unroutedNets;
    public final int fullyRoutedNets;
    public final int netsWithRoutingErrors;

    private static int parseLog(List<String> log, String key) {
        List<String> matchingLines = VivadoTools.searchVivadoLog(log, key);
        if (matchingLines.isEmpty()) {
            return 0;
        }
        // Consider first match only
        return Integer.parseInt(matchingLines.get(0).replaceAll("[^\\d]", ""));
    }

    /**
     * Analyze a Tcl script to open the Design d in vivado and run the command
     * `report_route_status`
     *
     * @param log the design to be analyzed
     */
    ReportRouteStatusResult(List<String> log) {
        logicalNets = parseLog(log, "# of logical nets");
        netsNotNeedingRouting = parseLog(log, "# of nets not needing routing");
        internallyRoutedNets = parseLog(log, "# of internally routed nets");
        netsWithNoLoads = parseLog(log, "# of nets with no loads");
        implicitlyRoutedPorts = parseLog(log, "# of implicitly routed ports");
        routableNets = parseLog(log, "# of routable nets");
        unroutedNets = parseLog(log, "# of unrouted nets");
        fullyRoutedNets = parseLog(log, "# of fully routed nets");
        netsWithRoutingErrors = parseLog(log, "# of nets with routing errors");
    }

    public boolean isFullyRouted() {
        return unroutedNets == 0 && netsWithRoutingErrors == 0;
    }

}
