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

package com.xilinx.rapidwright.util.routestatus;

import static com.xilinx.rapidwright.util.routestatus.NodeKeys.concat;
import static com.xilinx.rapidwright.util.routestatus.NodeKeys.sortedDistinct;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.getLogicalCell;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.getSiteTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
import com.xilinx.rapidwright.util.Utils;

/**
 * The rules of the route status of Versal designs: clock buffers whose outputs share routing, the
 * leaf clock dividers, the clocking sites and invalid LUT programming.
 */
final class VersalRules {

    /** Clock buffers with several outputs whose clocks share the routing of one of them */
    private static final String[] SHARED_ROUTING_CLOCK_BUFFERS = {"MBUFGCE", "MBUFGCE_DIV", "MBUFGCTRL", "MBUFG_GT",
        "MBUFG_PS"};

    /** The outputs of such clock buffers */
    private static final String[] SHARED_ROUTING_OUTPUTS = {"O1", "O2", "O3", "O4"};

    /** The site types of the global clock buffers */
    private static final Set<SiteTypeEnum> GLOBAL_CLOCK_BUFFER_SITE_TYPES = getSiteTypes((n) -> n.startsWith("BUFG"));

    /** The site types of the clock management sites */
    private static final Set<SiteTypeEnum> CLOCKING_SITE_TYPES = getSiteTypes((n) -> n.startsWith("MMCM")
            || n.startsWith("XPLL") || n.startsWith("DPLL"));

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
            if (!GLOBAL_CLOCK_BUFFER_SITE_TYPES.contains(si.getSiteTypeEnum())) {
                continue;
            }
            for (Cell cell : si.getCells()) {
                if (!Arrays.asList(SHARED_ROUTING_CLOCK_BUFFERS).contains(cell.getType())) {
                    continue;
                }
                EDIFHierCellInst inst = getLogicalCell(design, cell);
                List<Integer> others = new ArrayList<>();
                int routed = (inst == null) ? -1 : findRoutedOutput(inst, entries, flatNetlist, others);
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

    /**
     * Finds the entries of the nets of the outputs of a clock buffer.
     * @param others Set to the entries of the outputs without routing.
     * @return The entry of the only output with routing, or a negative value if none (or several).
     */
    private static int findRoutedOutput(EDIFHierCellInst inst, Entries entries, RouteStatusNetlist flatNetlist,
                                        List<Integer> others) {
        int routed = -1;
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
            if (net == null || !net.hasPIPs()) {
                others.add(entry);
            } else if (routed >= 0) {
                // (each output routed on its own)
                return -2;
            } else {
                routed = entry;
            }
        }
        return routed;
    }

    /**
     * Adds the slices whose LUT programming Vivado finds invalid: a cell of a 6LUT whose output is O5
     * (a LUT pair used as two LUT5s) with inputs on both A5 and A6 (whether or not the 5LUT has a cell).
     */
    static void findInvalidSites(Design design, InvalidSites invalidSites) {
        for (SiteInst si : design.getSiteInsts()) {
            if (Utils.isSLICE(si) && hasInvalidLutPair(si)) {
                invalidSites.add(si, InvalidSites.getPins(design, si));
            }
        }
    }

    private static boolean hasInvalidLutPair(SiteInst si) {
        for (char letter = 'A'; letter <= 'H'; letter++) {
            Cell lut6 = si.getCell(letter + "6LUT");
            if (lut6 != null && !lut6.isRoutethru() && lut6.getLogicalPinMapping("O5") != null
                    && lut6.getLogicalPinMapping("A5") != null && lut6.getLogicalPinMapping("A6") != null) {
                return true;
            }
        }
        return false;
    }

    static boolean isVersalClockingSite(SiteInst si) {
        if (si.getSite().getTile().getDevice().getSeries() != Series.Versal) {
            return false;
        }
        return CLOCKING_SITE_TYPES.contains(si.getSiteTypeEnum());
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
