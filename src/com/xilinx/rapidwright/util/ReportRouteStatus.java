/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.util.routestatus.RouteStatusEngine;

public class ReportRouteStatus {

    /**
     * Compute the route status of the given Design the way Vivado's `report_route_status` does,
     * such that every count in the returned summary matches Vivado's. It examines the logical
     * netlist, the placement (including site routing) and the routing of the design, but does not
     * rely on (nor modify) the {@link SitePinInst#isRouted()} state.
     * @param design Design to examine.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Design design) {
        return reportRouteStatus(design, false);
    }

    /**
     * Compute the route status of the given Design the way Vivado's `report_route_status` does,
     * see {@link #reportRouteStatus(Design)}.
     * @param design Design to examine.
     * @param perNet If true, also populate {@link ReportRouteStatusResult#netsByStatus} with the
     * names of the nets having each status, as listed by `report_route_status -list_all_nets`.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Design design, boolean perNet) {
        return RouteStatusEngine.run(design, perNet);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("USAGE: ReportRouteStatus <design.dcp>");
            System.exit(1);
        }

        Design design = Design.readCheckpoint(args[0]);

        ReportRouteStatusResult rrs = reportRouteStatus(design);

        System.out.println();
        System.out.println(rrs.toString("RapidWright Design Route Status"));
        if (!rrs.isFullyRouted()) {
            throw new RuntimeException("Design is not fully routed");
        }
    }
}
