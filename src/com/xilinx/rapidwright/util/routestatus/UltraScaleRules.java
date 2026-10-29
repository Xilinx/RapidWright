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

import static com.xilinx.rapidwright.util.routestatus.SiteRouting.isTiedTo;
import static com.xilinx.rapidwright.util.routestatus.SiteRouting.isUltraScale;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.Utils;

/**
 * The rules of the route status of UltraScale and UltraScale+ designs: the ties of the control pins of
 * slices, and the loads of a driver in its own slice.
 */
final class UltraScaleRules {

    /**
     * The flip-flops and latches whose unused clock enable and set/reset are tied to VCC, with the
     * names of their logical clock enable and set/reset pins
     */
    private static final Map<String, Pair<String, String>> CE_SR_TIED_TYPES = new HashMap<>();

    static {
        CE_SR_TIED_TYPES.put("FDRE", new Pair<>("CE", "R"));
        CE_SR_TIED_TYPES.put("FDSE", new Pair<>("CE", "S"));
        CE_SR_TIED_TYPES.put("FDCE", new Pair<>("CE", "CLR"));
        CE_SR_TIED_TYPES.put("FDPE", new Pair<>("CE", "PRE"));
        CE_SR_TIED_TYPES.put("LDCE", new Pair<>("GE", "CLR"));
        CE_SR_TIED_TYPES.put("LDPE", new Pair<>("GE", "PRE"));
    }

    private UltraScaleRules() {
    }

    /**
     * In UltraScale(+) slices, the clock enable and set/reset site pins (e.g. CKEN1, SRST1) of
     * flip-flops (or latches) that tie them off in the netlist (CE to VCC, SR to GND), without a
     * signal (no site routing to the CE pin, and none or that of GND to the SR pin, as in
     * {@link DesignTools#createCeSrRstPinsToVCC(Design)}) are tied to VCC through routing, the SR
     * pin through the inverting setting of its inverter (without a used site PIP). Finds such a pin
     * that has neither a site pin nor site routing of a signal, and which the routing of GND or VCC
     * does not lead to (e.g. after moving flip-flops into the slice).
     * @return {@link StaticRouting#VCC_TIE} if there is such a pin, else 0.
     */
    static int getMissingSliceControlTies(Design design, StaticRouting staticRouting) {
        Map<String, Pair<String, String>> sitePinNames =
                DesignTools.belTypeSitePinNameMapping.get(design.getSeries());
        for (SiteInst si : design.getSiteInsts()) {
            if (!Utils.isSLICE(si)) {
                continue;
            }
            for (Cell cell : si.getCells()) {
                BEL bel = cell.getBEL();
                if (cell.isRoutethru() || bel == null || !bel.isFF() || !CE_SR_TIED_TYPES.containsKey(cell.getType())) {
                    continue;
                }
                Pair<String, String> pins = sitePinNames.get(bel.getName());
                if (pins == null) {
                    continue;
                }
                for (boolean ce : new boolean[]{true, false}) {
                    if (isMissingTie(si, cell, ce, ce ? pins.getFirst() : pins.getSecond(), staticRouting)) {
                        return StaticRouting.VCC_TIE;
                    }
                }
            }
        }
        return 0;
    }

    /**
     * Checks whether the clock enable (or set/reset) site pin of a flip-flop needs a tie to VCC that
     * no routing provides.
     */
    private static boolean isMissingTie(SiteInst si, Cell cell, boolean ce, String sitePin,
                                        StaticRouting staticRouting) {
        BELPin belPin = cell.getBEL().getPin(ce ? "CE" : "SR");
        if (belPin == null) {
            return false;
        }
        Net belPinNet = si.getNetFromSiteWireIndex(belPin.getSiteWireIndex());
        if (belPinNet != null && (ce || !belPinNet.isGNDNet())) {
            // Used by a signal (or a tie inside the site)
            return false;
        }
        Net sitePinNet = si.getNetFromSiteWire(sitePin);
        if (si.getSitePinInst(sitePin) != null || (sitePinNet != null && !sitePinNet.isStaticNet())
                || staticRouting.isRoutingTo(si, sitePin)) {
            return false;
        }
        // Only a pin that the netlist ties off needs it (not one that is unconnected, nor that of a
        // cell missing from the netlist). The tied pin is not mapped.
        Pair<String, String> logicalPins = CE_SR_TIED_TYPES.get(cell.getType());
        String logicalPin = ce ? logicalPins.getFirst() : logicalPins.getSecond();
        if (!isTiedTo(cell, logicalPin, ce ? NetType.VCC : NetType.GND)) {
            return false;
        }
        // (unless the set/reset inverter, e.g. RST_ABCDINV, does not invert: then it is not tied)
        return ce || !isDrivenThroughUsedSitePIP(si, belPin);
    }

    /** Checks whether a BEL in a site that drives the site wire of a BEL pin has a used site PIP */
    static boolean isDrivenThroughUsedSitePIP(SiteInst si, BELPin pin) {
        for (BELPin driver : si.getSiteWirePins(pin.getSiteWireIndex())) {
            if (!driver.isOutput() || driver.isSitePort()) {
                continue;
            }
            for (BELPin input : driver.getBEL().getPins()) {
                if (input.isInput() && si.getUsedSitePIP(input) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether a net has a single placed driver, in an UltraScale(+) slice, and all its loads
     * are placed in the same site.
     */
    static boolean hasOnlyLoadsInDriverSite(Design design, FlatNet flat) {
        Series series = design.getDevice().getSeries();
        if (!isUltraScale(series) || flat.leafDrivers != 1
                || flat.unplacedLeafPins > 0 || flat.leafInouts > 0 || flat.hasTopPorts()) {
            return false;
        }
        SiteInst driverSite = null;
        List<LeafPin> pins = flat.getLeafPins(design);
        for (LeafPin pin : pins) {
            if (!pin.isPlaced()) {
                // (e.g. a pin of a black box, which has no cell)
                return false;
            }
            if (pin.isOutput()) {
                driverSite = pin.cell.getSiteInst();
            }
        }
        if (driverSite == null || !Utils.isSLICE(driverSite)) {
            return false;
        }
        for (LeafPin pin : pins) {
            if (pin.cell.getSiteInst() != driverSite) {
                return false;
            }
        }
        return true;
    }
}
