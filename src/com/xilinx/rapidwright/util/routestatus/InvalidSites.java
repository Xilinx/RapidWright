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

import static com.xilinx.rapidwright.util.routestatus.NetStatus.ANTENNAS;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.CONFLICTS;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.HAS_ROUTING;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.INTRASITE;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.NOLOADS;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.PARTIAL;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.ROUTED;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.UNROUTED;
import static com.xilinx.rapidwright.util.routestatus.NetStatus.has;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.getLogicalCell;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.storedPins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.util.Utils;

/**
 * The sites whose site programming Vivado finds invalid ("Invalid Programming for Site", see
 * {@link Series7Rules#findInvalidSites} and {@link VersalRules#findInvalidSites}). All the nets with
 * pins there conflict; the routing into (or out of) such a site leads nowhere, and its pins there are
 * not loads (nor drivers).
 */
final class InvalidSites {

    private final Design design;
    private final Entries entries;

    /** The sites, with the pins of their cells that have nets */
    private final Map<SiteInst, List<EDIFHierPortInst>> sitePins = new IdentityHashMap<>();

    /** The slices among the sites */
    private final Set<SiteInst> slices = new HashSet<>();

    InvalidSites(Design design, Entries entries) {
        this.design = design;
        this.entries = entries;
    }

    /**
     * Adds a site whose site programming is invalid.
     * @param pins The pins of its cells that have nets (see {@link #getPins}).
     */
    void add(SiteInst si, List<EDIFHierPortInst> pins) {
        sitePins.put(si, pins);
        if (Utils.isSLICE(si)) {
            slices.add(si);
        }
    }

    /** Gets the pins of the (placed, not routethru) cells of a site that have nets. */
    static List<EDIFHierPortInst> getPins(Design design, SiteInst si) {
        List<EDIFHierPortInst> pins = new ArrayList<>();
        for (Cell cell : si.getCells()) {
            EDIFHierCellInst inst = cell.isRoutethru() ? null : getLogicalCell(design, cell);
            if (inst == null) {
                continue;
            }
            for (String logicalPin : cell.getPinMappingsP2L().values()) {
                EDIFHierPortInst pin = inst.getPortInst(logicalPin);
                if (pin != null && pin.getNet() != null) {
                    pins.add(pin);
                }
            }
        }
        return pins;
    }

    /**
     * Marks the nets with pins in the sites.
     * @param staticEntry The entries of GND and VCC (-1 if none).
     */
    void mark(int[] staticEntry) {
        if (sitePins.isEmpty()) {
            return;
        }
        // Per entry: whether it is driven from such a site
        Map<Integer, Boolean> drivenFromInvalidSite = new HashMap<>();
        // The entries driven from such a slice (rather than a block RAM)
        Set<Integer> drivenFromSlice = new HashSet<>();
        findEntriesOfInvalidSites(sitePins, staticEntry, drivenFromInvalidSite, drivenFromSlice);
        for (Map.Entry<Integer, Boolean> e : drivenFromInvalidSite.entrySet()) {
            int i = e.getKey();
            entries.status[i] = getStatusWithInvalidSite(i, e.getValue(), drivenFromSlice.contains(i),
                    sitePins.keySet());
        }
    }

    /**
     * Finds the entries of the pins in the sites, and whether they are driven from such a site (and
     * from a slice).
     */
    private void findEntriesOfInvalidSites(Map<SiteInst, List<EDIFHierPortInst>> invalidSitePins, int[] staticEntry,
                                           Map<Integer, Boolean> drivenFromInvalidSite, Set<Integer> drivenFromSlice) {
        Map<String, Integer> entryOfName = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            entryOfName.put(entries.getName(i), i);
        }
        for (Map.Entry<SiteInst, List<EDIFHierPortInst>> sitePins : invalidSitePins.entrySet()) {
            boolean slice = slices.contains(sitePins.getKey());
            for (EDIFHierPortInst pin : sitePins.getValue()) {
                int entry = -1;
                NetType staticType = null;
                for (EDIFHierNet seg : RouteStatusNetlist.getSegments(pin.getHierarchicalNet())) {
                    Integer e = entryOfName.get(seg.getHierarchicalNetName());
                    if (e != null) {
                        entry = e;
                        break;
                    }
                    if (staticType == null && (seg.getNet().isGND() || seg.getNet().isVCC())) {
                        staticType = seg.getNet().isVCC() ? NetType.VCC : NetType.GND;
                    }
                }
                if (entry < 0 && staticType != null) {
                    entry = staticEntry[staticType == NetType.VCC ? 1 : 0];
                }
                if (entry < 0) {
                    continue;
                }
                drivenFromInvalidSite.merge(entry, pin.isOutput(), Boolean::logicalOr);
                if (slice && pin.isOutput()) {
                    drivenFromSlice.add(entry);
                }
            }
        }
    }

    /**
     * Gets the status of a net with pins in sites with invalid site programming: it conflicts, and
     * routing into (or out of) such a site leads nowhere.
     */
    private int getStatusWithInvalidSite(int i, boolean drivenFromSite, boolean drivenFromSlice,
                                         Set<SiteInst> invalidSites) {
        int st = entries.status[i];
        FlatNet flat = entries.flats.get(i);
        if (flat == null) {
            // Static nets conflict too
            return (st & ~ROUTED) | CONFLICTS;
        }
        // (a net inside such a site is not internally routed either)
        st &= ~INTRASITE;
        boolean loadsElsewhere = hasLoadsOutsideSites(entries.nets.get(i), flat, invalidSites);
        if (drivenFromSlice && !loadsElsewhere) {
            // Driven from such a slice, without loads elsewhere: it is not without loads
            return (st & ~(ROUTED | NOLOADS)) | CONFLICTS | (has(st, HAS_ROUTING) ? ANTENNAS : 0);
        }
        if (!has(st, HAS_ROUTING)) {
            // An unrouted net driven from such a site has no loads unless it has others, and so does
            // one without a physical net (unless it has loads in other hard blocks reached through
            // general routing); others are partially routed
            if (has(st, UNROUTED)) {
                boolean noLoads = drivenFromSite ? !loadsElsewhere : entries.nets.get(i) == null && !loadsElsewhere;
                st = (st & ~UNROUTED) | (noLoads ? NOLOADS : PARTIAL);
            }
            return st | CONFLICTS;
        }
        st = (st & ~ROUTED) | CONFLICTS | ANTENNAS;
        if (drivenFromSite) {
            // Driven from such a site: its routing is all islands
            st |= loadsElsewhere ? PARTIAL : NOLOADS;
        } else if (!loadsElsewhere) {
            st |= PARTIAL;
        }
        return st;
    }

    /** Checks whether a net has (connected) loads outside the given sites. */
    private boolean hasLoadsOutsideSites(Net net, FlatNet flat, Set<SiteInst> sites) {
        if (net == null) {
            // (without a physical net, only loads in hard blocks reached through general routing are
            // connected, see RouteStatusEngine.classifyNet())
            return flat.hasRoutedHardBlockLoad(design, sites);
        }
        // (loads of a physical net without site pins need site routing of it into their site)
        boolean noPins = storedPins(net).isEmpty();
        for (LeafPin pin : flat.getLeafPins(design)) {
            if (!pin.isOutput() && !(pin.isPlaced() && sites.contains(pin.cell.getSiteInst()))
                    && (!noPins || hasInputSiteRouting(pin, net))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the site routing of a net enters the site of a placed cell pin (at an input site pin) */
    private static boolean hasInputSiteRouting(LeafPin pin, Net net) {
        if (!pin.isPlaced()) {
            return false;
        }
        SiteInst si = pin.cell.getSiteInst();
        for (String siteWire : si.getSiteWiresFromNet(net)) {
            String sitePin = si.getPrimarySitePinName(siteWire);
            int pinIndex = (sitePin == null) ? -1 : si.getSite().getPinIndex(sitePin);
            if (pinIndex >= 0 && si.getSite().isInputPin(pinIndex)) {
                return true;
            }
        }
        return false;
    }
}
