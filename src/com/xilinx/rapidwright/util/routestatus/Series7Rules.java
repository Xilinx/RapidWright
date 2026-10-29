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

import static com.xilinx.rapidwright.util.routestatus.NetStatus.CONFLICTS;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.ROUTED;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.has;
import static com.xilinx.rapidwright.util.routestatus.RouteStatusEngine.parallelFor;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.getLogicalCell;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.isBlockRamSite;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.isClockBufferSite;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.isReachableThroughRouting;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.storedPins;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.util.Utils;

/**
 * The rules of the route status of 7-series designs: invalid site programming (of block RAMs, and of
 * slices that a routethru passes through), conflicts at the output site pins of slices, and the
 * constants that hard blocks need tied through routing.
 */
final class Series7Rules {

    private final Design design;
    private final Entries entries;

    /** 7-series slices through which routethrus of other nets pass, see {@link #findRoutethruInvalidSites} */
    private final Set<SiteInst> routethruInvalidSites = ConcurrentHashMap.newKeySet();

    Series7Rules(Design design, Entries entries) {
        this.design = design;
        this.entries = entries;
    }

    /**
     * Finds the (7-series) slices whose site pins a routethru PIP of a net passes through while
     * their site routing has another net there: Vivado finds their site programming invalid.
     */
    void findRoutethruInvalidSites(Net net, int entry, List<Node> routethruNodes) {
        List<Net> aliases = entries.aliasNets.get(entry);
        for (Node node : routethruNodes) {
            SitePin sitePin = node.getSitePin();
            SiteInst si = (sitePin == null) ? null : design.getSiteInstFromSite(sitePin.getSite());
            if (si == null || !Utils.isSLICE(si)) {
                continue;
            }
            Net other = si.getNetFromSiteWire(sitePin.getPinName());
            if (other != null && other != net && (aliases == null || !aliases.contains(other))) {
                routethruInvalidSites.add(si);
            }
        }
    }

    /**
     * Adds the (7-series) sites whose site programming Vivado finds invalid: block RAM sites where a
     * pin of a placed cell, connected to a net that is not static, has no site routing (as left by
     * removing a physical net), and slices that a routethru of another net passes through (see
     * {@link #findRoutethruInvalidSites}).
     */
    void findInvalidSites(InvalidSites invalidSites) {
        for (SiteInst si : design.getSiteInsts()) {
            boolean routethruInvalid = routethruInvalidSites.contains(si);
            if (!isBlockRamSite(si) && !routethruInvalid) {
                continue;
            }
            List<EDIFHierPortInst> pins = new ArrayList<>();
            boolean invalid = routethruInvalid;
            for (Cell cell : si.getCells()) {
                EDIFHierCellInst inst = cell.isRoutethru() ? null : getLogicalCell(design, cell);
                if (inst == null) {
                    continue;
                }
                for (Map.Entry<String, String> e : cell.getPinMappingsP2L().entrySet()) {
                    EDIFHierPortInst pin = inst.getPortInst(e.getValue());
                    if (pin == null || pin.getNet() == null) {
                        continue;
                    }
                    pins.add(pin);
                    BELPin belPin = cell.getBEL().getPin(e.getKey());
                    if (!invalid && belPin != null && si.getNetFromSiteWire(belPin.getSiteWireName()) == null
                            && RouteStatusNetlist.getStaticType(pin.getHierarchicalNet()) == null) {
                        invalid = true;
                    }
                }
            }
            if (invalid) {
                invalidSites.add(si, pins);
            }
        }
    }

    /**
     * Marks the nets whose site routing drives an output site pin of a (7-series) slice that another
     * net's site pin instance (and routing) uses, as left by unplacing cells but not their site
     * routing: both conflict at the site pin.
     */
    void markOutputSitePinConflicts() {
        if (design.getDevice().getSeries() != Series.Series7) {
            // (seen with 7-series slices only)
            return;
        }
        Map<Net, Integer> entryOf = entries.getEntryOfNet();
        Net[] nets = design.getNets().toArray(new Net[0]);
        Set<Integer> conflicting = ConcurrentHashMap.newKeySet();
        parallelFor(nets.length, (n) -> {
            Net net = nets[n];
            if (net.isStaticNet()) {
                return;
            }
            for (SitePinInst spi : storedPins(net)) {
                if (!spi.isOutPin() || !Utils.isSLICE(spi.getSiteInst())) {
                    // (only seen in slices; the site routing of hard blocks, e.g. DSP cascades,
                    // may carry other segments of the net)
                    continue;
                }
                Net other = spi.getSiteInst().getNetFromSiteWire(spi.getSiteWireName());
                if (other == null || other == net || other.isStaticNet()) {
                    continue;
                }
                Integer a = entryOf.get(net);
                Integer b = entryOf.get(other);
                if (a != null && b != null && !a.equals(b)) {
                    conflicting.add(a);
                    conflicting.add(b);
                }
            }
        });
        for (int i : conflicting) {
            entries.status[i] = (entries.status[i] & ~ROUTED) | CONFLICTS;
        }
    }

    /**
     * Finds the inputs of hard blocks that the site programming needs tied to a constant through
     * routing, but which have no site routing (as left by removing nets or their pins): those reachable
     * through routing whose net has no site routing there need VCC, or GND for clock enables and
     * clock inputs. Vivado lists them as unrouted nodes of VCC (GND).
     * @return A combination of {@link StaticRouting#VCC_TIE} and {@link StaticRouting#GND_TIE}.
     */
    int getMissingTies(StaticRouting staticRouting) {
        int ties = 0;
        for (SiteInst si : design.getSiteInsts()) {
            if (Utils.isSLICE(si) || isBlockRamSite(si) || isClockBufferSite(si)) {
                // (block RAMs whose pins lack site routing are invalid instead, see findInvalidSites(),
                // and the inputs of clock buffers are reached through clock routing)
                continue;
            }
            for (Cell cell : si.getCells()) {
                EDIFHierCellInst inst = cell.isRoutethru() ? null : getLogicalCell(design, cell);
                if (inst == null) {
                    continue;
                }
                for (Map.Entry<String, String> e : cell.getPinMappingsP2L().entrySet()) {
                    BELPin belPin = cell.getBEL().getPin(e.getKey());
                    EDIFHierPortInst pin = inst.getPortInst(e.getValue());
                    if (belPin == null || !belPin.isInput() || pin == null || pin.getNet() == null) {
                        continue;
                    }
                    String siteWire = belPin.getSiteWireName();
                    int pinIndex = si.getSite().getPinIndex(siteWire);
                    if (pinIndex < 0 || si.getNetFromSiteWire(siteWire) != null || si.getSitePinInst(siteWire) != null
                            || !isReachableThroughRouting(si.getSite().getConnectedNode(pinIndex))
                            || staticRouting.isRoutingTo(si, siteWire)
                            || RouteStatusNetlist.getStaticType(pin.getHierarchicalNet()) != null) {
                        // (nor cascade inputs, which have dedicated connections)
                        continue;
                    }
                    ties |= (siteWire.startsWith("CE") || siteWire.startsWith("CLKIN") || siteWire.endsWith("USERRDY"))
                            ? StaticRouting.GND_TIE : StaticRouting.VCC_TIE;
                }
            }
        }
        return ties;
    }
}
