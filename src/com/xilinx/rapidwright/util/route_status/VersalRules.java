/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

import static com.xilinx.rapidwright.util.route_status.NodeKeys.concat;
import static com.xilinx.rapidwright.util.route_status.NodeKeys.sortedDistinct;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;

/**
 * The rules of the route status of Versal designs: clock buffers whose outputs share routing, the
 * leaf clock dividers and the clocking sites.
 */
final class VersalRules {

    /** Clock buffers with several outputs whose clocks share the routing of one of them */
    private static final String[] SHARED_ROUTING_CLOCK_BUFFERS = {"MBUFGCE", "MBUFGCE_DIV", "MBUFGCTRL", "MBUFG_GT", "MBUFG_PS"};

    /** The outputs of such clock buffers */
    private static final String[] SHARED_ROUTING_OUTPUTS = {"O1", "O2", "O3", "O4"};

    private VersalRules() {
    }

    /**
     * The outputs of a (Versal) clock buffer with several, whose clocks are divided at the leaves
     * (e.g. MBUFGCE), share the routing of one of them: the site pins of the loads of the others,
     * which have no routing, are loads of that routing. The others are counted as routed (but not
     * listed as such) if it is.
     * @return The entries of the nets sharing routing, with the entry of the net that has it.
     */
    static Map<Integer, Integer> shareClockBufferRouting(Design design, Entries entries,
                                                         RouteStatusNetlist flatNetlist) {
        Map<Integer, Integer> shared = new HashMap<>();
        for (SiteInst si : design.getSiteInsts()) {
            if (!si.getSiteTypeEnum().name().startsWith("BUFG")) {
                continue;
            }
            for (Cell cell : si.getCells()) {
                if (!Arrays.asList(SHARED_ROUTING_CLOCK_BUFFERS).contains(cell.getType())) {
                    continue;
                }
                EDIFHierCellInst inst = cell.getEDIFHierCellInst();
                if (inst == null) {
                    continue;
                }
                int routed = -1;
                List<Integer> others = new ArrayList<>();
                for (String output : SHARED_ROUTING_OUTPUTS) {
                    EDIFHierPortInst pin = inst.getPortInst(output);
                    EDIFHierNet hierNet = (pin == null) ? null : pin.getHierarchicalNet();
                    RouteStatusNetlist.Segment seg = (hierNet == null) ? null
                            : flatNetlist.getSegment(hierNet.getHierarchicalNetName());
                    int entry = (seg == null) ? -1 : seg.flat.find().entry;
                    if (entry < 0 || entry == routed || others.contains(entry)) {
                        continue;
                    }
                    Net net = entries.nets.get(entry);
                    if (net != null && net.hasPIPs()) {
                        if (routed >= 0) {
                            // (each output routed on its own)
                            routed = -2;
                            break;
                        }
                        routed = entry;
                    } else {
                        others.add(entry);
                    }
                }
                if (routed < 0) {
                    continue;
                }
                for (int other : others) {
                    if (entries.sitePinKeys[other] == null) {
                        continue;
                    }
                    long[] keys = concat(entries.sitePinKeys[routed], entries.sitePinKeys[other]);
                    entries.sitePinKeys[routed] = sortedDistinct(keys, keys.length);
                    entries.sitePinKeys[other] = null;
                    shared.put(other, routed);
                }
            }
        }
        return shared;
    }

    static boolean isVersalClockingSite(SiteInst si) {
        if (si.getSite().getTile().getDevice().getSeries() != Series.Versal) {
            return false;
        }
        String type = si.getSiteTypeEnum().name();
        return type.startsWith("MMCM") || type.startsWith("XPLL") || type.startsWith("DPLL");
    }

    /**
     * Checks whether a node is the CLR_B pin of a leaf clock divider, which (though not a pin of
     * any cell) is a load of the CLRB_LEAF pin of the clock buffer (e.g. MBUFGCE) whose leaves it
     * resets.
     */
    static boolean isLeafClockClearPin(Node node) {
        if (node.getIntentCode() != IntentCode.NODE_PINFEED) {
            return false;
        }
        SitePin sitePin = node.getSitePin();
        if (sitePin == null || !sitePin.getPinName().equals("CLR_B")) {
            return false;
        }
        SiteTypeEnum type = sitePin.getSite().getSiteTypeEnum();
        return type == SiteTypeEnum.BUFDIV_LEAF || type == SiteTypeEnum.BUFDIV_LEAF_ULVT;
    }
}
