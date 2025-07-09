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
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.rwroute.RouterHelper;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ReportRouteStatus {
    /**
     * Compute the route status of given Design's physical nets by examining the
     * {@link SitePinInst#isRouted()} state of each net's pins, as well as to discovering node conflicts
     * between each net's PIPs.
     * Freshly loaded designs, as well as designs that are not up-to-date, can call
     * {@link DesignTools#updatePinsIsRouted(Design)} for recomputing the SitePinInst.isRouted() state.
     * Note that currently this method does not check the Design's logical netlist nor its physical
     * placement --- these are assumed to be correct.
     * @param design Design to examine.
     * @return ReportRouteStatusResult object.
     */
    public static ReportRouteStatusResult reportRouteStatus(Design design) {
        ReportRouteStatusResult rrs = new ReportRouteStatusResult();

        Map<Node, Net> nodesUsedByDesign = new HashMap<>();
        Set<Net> conflictingNets = new HashSet<>();

        Collection<Net> nets = design.getNets();
        rrs.logicalNets = nets.size();
        for (Net net : nets) {
            if (net.isStaticNet()) {
                if (net.getPins().isEmpty()) {
                    rrs.logicalNets--;
                    continue;
                }
            } else if (!RouterHelper.isRoutableNetWithSourceSinks(net)) {
                rrs.netsNotNeedingRouting++;
                continue;
            }
            rrs.routableNets++;

            boolean isFullyRouted = true;
            boolean isPartiallyRouted = false;
            for (SitePinInst spi : net.getPins()) {
                if (spi.isRouted()) {
                    isPartiallyRouted = true;
                    continue;
                }
                isFullyRouted = false;
            }

            boolean isConflictFree = true;
            for (PIP pip : net.getPIPs()) {
                Node endNode = pip.isReversed() ? pip.getStartNode() : pip.getEndNode();
                Net conflictingNet = nodesUsedByDesign.putIfAbsent(endNode, net);
                if (conflictingNet != null && conflictingNet != net) {
                    conflictingNets.add(conflictingNet);
                    isConflictFree = false;
                }
            }

            if (!isConflictFree) {
                conflictingNets.add(net);
            } else if (!isFullyRouted) {
                if (isPartiallyRouted) {
                    rrs.netsWithSomeUnroutedPins++;
                } else {
                    rrs.unroutedNets++;
                }
            }
        }

        rrs.netsWithResourceConflicts = conflictingNets.size();
        rrs.netsWithRoutingErrors = rrs.netsWithSomeUnroutedPins + rrs.netsWithResourceConflicts;
        rrs.fullyRoutedNets = rrs.routableNets - rrs.unroutedNets - rrs.netsWithRoutingErrors;
        return rrs;
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("USAGE: ReportRouteStatus <design.dcp>");
            System.exit(1);
        }

        Design design = Design.readCheckpoint(args[0]);

        DesignTools.updatePinsIsRouted(design);

        ReportRouteStatusResult rrs = reportRouteStatus(design);

        System.out.println();
        System.out.println(rrs.toString("RapidWright Design Route Status"));
        if (!rrs.isFullyRouted()) {
            throw new RuntimeException("Design is not fully routed");
        }
    }
}
