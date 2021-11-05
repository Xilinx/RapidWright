/*
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

package com.xilinx.rapidwright.design.drc;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.util.Pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Check that each LUT contains at most one routethru of each net.
 * Identifies occurrences of issue #226.
 * Failed checks will print a warning and are not counted unless the strict parameter is true.
 */
public class NetRoutesThruLutAtMostOnce {

    private static String getGlobalLutName(SitePin sp) {
        return sp.getSite().getName() + "/" + sp.getPinName().charAt(0);
    }

    public static int run(Design design, boolean strict) {
        List<Pair<Net, List<Pair<String, Integer>>>> netToLutRoutethrus = design.getNets().stream()
                .map((n) -> {
                    Map<String, Integer> lutRoutethrus = new HashMap<>();

                    for (PIP p : n.getPIPs()) {
                        if (!p.isRouteThru()) continue;

                        Node startNode = p.getStartNode();
                        BELPin portPin = startNode.getSitePin().getBELPin();
                        List<BELPin> connPins = portPin.getSiteConns();

                        // Check that this BELPin is only connected to LUTs (e.g. as opposed to IOLOGIC)
                        if (!connPins.stream()
                                .allMatch((bp) -> DesignTools.isBELALut(bp.getBELName()))) {
                            continue;
                        }

                        // Set to 1 if not exist, increment by 1 if does exist
                        lutRoutethrus.merge(getGlobalLutName(startNode.getSitePin()), 1, Integer::sum);
                    }

                    for (SitePinInst spi : n.getSinkPins()) {
                        for (BELPin bp : spi.getSiteWireBELPins()) {
                            Cell c = spi.getSiteInst().getCell(bp.getBEL());
                            // Filter out all non routethru cells
                            if (c == null || !c.isRoutethru()) continue;

                            // Check that the pin is actually used by this BEL
                            if (c.getLogicalPinMapping(bp.getName()) == null) {
                                continue;
                            }

                            // Verify this is a LUT
                            if (!DesignTools.isBELALut(bp.getBELName())) {
                                continue;
                            }

                            // Set to 1 if not exist, increment by 1 if does exist
                            SitePin sp = bp.getSitePin(spi.getSite());
                            lutRoutethrus.merge(getGlobalLutName(sp), 1, Integer::sum);
                        }
                    }

                    // Copy out all lutNames routed through just once
                    List<Pair<String, Integer>> moreThanOneRoutethru = new ArrayList<>();
                    for (Map.Entry<String, Integer> e : lutRoutethrus.entrySet()) {
                        if (e.getValue() == 1) continue;
                        moreThanOneRoutethru.add(new Pair<>(e.getKey(), e.getValue()));
                    }

                    return new Pair<>(n, moreThanOneRoutethru);
                // Filter out all nets an empty list of more-than-one-routethru
                }).filter(
                        (e) -> !e.getSecond().isEmpty()
                ).collect(Collectors.toList());

        int numFails = 0;

        // Only count failures if in strict mode
        if (strict) {
            for (Pair<Net, List<Pair<String, Integer>>> e : netToLutRoutethrus) {
                for (Pair<String, Integer> l : e.getSecond()) {
                    System.out.println("Net '" + e.getFirst() + "' routes-thru this LUT more than once: " +
                            l.getFirst() + "; this may not be faithfully representable to Vivado");
                    numFails += l.getSecond() - 1;
                }
            }
        }

        return numFails;
    }
}
