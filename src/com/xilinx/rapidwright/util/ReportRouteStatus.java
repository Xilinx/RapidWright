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
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("USAGE: ReportRouteStatus <design.dcp>");
            System.exit(1);
        }

        Design design = Design.readCheckpoint(args[0]);

        DesignTools.updatePinsIsRouted(design);

        Map<Node, Net> nodesUsedByDesign = new HashMap<>();
        Set<Net> conflictingNets = new HashSet<>();

        Collection<Net> nets = design.getNets();
        int numPhysicalNets = nets.size();
        int numRoutableNets = 0;
        int numUnroutedNets = 0;
        int numNetsWithUnroutedPins = 0;
        for (Net net : nets) {
            if (!RouterHelper.isRoutableNetWithSourceSinks(net)) {
                continue;
            }
            numRoutableNets++;

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
                    numNetsWithUnroutedPins++;
                } else {
                    numUnroutedNets++;
                }
            }
        }

        int numConflictingNets = conflictingNets.size();
        System.out.println();
        System.out.println("Design Route Status");
        System.out.println("                                               :      # nets :");
        System.out.println("   ------------------------------------------- : ----------- :");
        System.out.printf ("   # of physical nets......................... : %11d :\n", numPhysicalNets);
        System.out.printf ("       # of nets not needing routing.......... : %11d :\n", numPhysicalNets - numRoutableNets);
        System.out.printf ("       # of routable nets..................... : %11d :\n", numRoutableNets);
        System.out.printf ("           # of unrouted nets................. : %11d :\n", numUnroutedNets);
        System.out.printf ("           # of fully routed nets............. : %11d :\n", numRoutableNets - numUnroutedNets - numNetsWithUnroutedPins - numConflictingNets);
        System.out.printf ("       # of nets with routing errors.......... : %11d :\n", numNetsWithUnroutedPins + numConflictingNets);
        System.out.printf ("           # of nets with some unrouted pins.. : %11d :\n", numNetsWithUnroutedPins);
        System.out.printf ("           # of nets with resource conflicts.. : %11d :\n", numConflictingNets);
        System.out.println("   ------------------------------------------- : ----------- :");

        System.exit((numNetsWithUnroutedPins == 0 && numConflictingNets == 0) ? 0 : 1);
    }
}
