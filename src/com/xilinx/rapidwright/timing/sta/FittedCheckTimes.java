/*
 *
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

package com.xilinx.rapidwright.timing.sta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.FittedCellArcLibrary;
import com.xilinx.rapidwright.timing.family.FittedCellArcLibrary.Role;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;

/**
 * The setup and hold times of the timing checks of a design's endpoints, from the SETUP (else
 * RECOVERY) and HOLD (else REMOVAL) arcs of its family's {@link FittedCellArcLibrary}, from the clock
 * pin to the data pin of the endpoint's cell, scaled to the device and speed grade like the other cell
 * arcs (see {@link FamilyArcProvider}). Endpoints without such an arc get 0. In slices, the arcs are
 * those of the BEL pins of the data pin: of all of them when it has several (see {@link #getBELPins}),
 * the largest time.
 */
public class FittedCheckTimes implements StaClockBinding.CheckTimes {

    private final Design design;
    private final FittedCellArcLibrary lib;
    private final String deviceName;
    private final String speedGrade;
    private final int setupIdx;
    private final int holdIdx;
    private final Map<String, float[]> cache = new ConcurrentHashMap<>();
    private final Set<String> missing = ConcurrentHashMap.newKeySet();

    /**
     * Creates the check times of a design, setup times at SLOW_MAX and hold times at FAST_MIN.
     * @param design The design.
     */
    public FittedCheckTimes(Design design) {
        this(design, Corner.SLOW_MAX, Corner.FAST_MIN);
    }

    /**
     * Creates the check times of a design.
     * @param design The design.
     * @param setupCorner The corner of the setup times.
     * @param holdCorner The corner of the hold times.
     */
    public FittedCheckTimes(Design design, Corner setupCorner, Corner holdCorner) {
        this.design = design;
        TimingFamily family = TimingFamilies.of(design.getDevice());
        this.lib = family.getFittedCellArcs(design.getDevice());
        this.deviceName = design.getDevice().getName();
        SpeedGrade grade = null;
        String partName = design.getPartName();
        if (partName != null && !partName.isEmpty()) {
            grade = family.resolveSpeedGrade(PartNameTools.getPart(partName));
        }
        // An unknown grade takes the factors of the nearest listed one, as FamilyArcProvider's arcs
        this.speedGrade = grade == null ? null
                : SpeedGrade.resolve(grade.getName(), lib.getSpeedGrades(), lib.getSpeedRef(), "cell delay data");
        this.setupIdx = FittedCellArcLibrary.getDataIndex(setupCorner);
        this.holdIdx = FittedCellArcLibrary.getDataIndex(holdCorner);
    }

    @Override
    public float getSetup(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin) {
        return lookup(dataPin, clockPin, true);
    }

    @Override
    public float getHold(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin) {
        return lookup(dataPin, clockPin, false);
    }

    @Override
    public float[] getSetupHold(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin) {
        float[] d = lookup(dataPin, clockPin);
        return d == null ? new float[2] : d.clone();
    }

    /** @return The cell type, check and pins of the checks that had no arc (0 was used). */
    Set<String> getMissing() {
        return Collections.unmodifiableSet(missing);
    }

    private static boolean isSlice(String site) {
        return site.equals("SLICEL") || site.equals("SLICEM");
    }

    private float lookup(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin, boolean setup) {
        float[] d = lookup(dataPin, clockPin);
        return d == null ? 0f : setup ? d[0] : d[1];
    }

    /** @return The setup and hold times of a check (not to be changed), null if none (0) */
    private float[] lookup(EDIFHierPortInst dataPin, EDIFHierPortInst clockPin) {
        Cell c = design.getCell(dataPin.getFullHierarchicalInstName());
        if (c == null || c.getBEL() == null || c.getSiteInst() == null
                || !c.getEDIFHierCellInst().equals(clockPin.getFullHierarchicalInst())) {
            return null;
        }
        SiteInst si = c.getSiteInst();
        String cellType = c.getType();
        String bel = c.getBELName();
        String site = si.getSiteTypeEnum().name();
        boolean slice = isSlice(site);
        String dataName = dataPin.getPortInst().getName();
        String clockName = clockPin.getPortInst().getName();
        String from = slice ? c.getPhysicalPinMapping(clockName) : FamilyArcProvider.busBase(clockName);
        List<String> tos = slice ? getBELPins(c, dataName)
                : Collections.singletonList(FamilyArcProvider.busBase(dataName));
        if (from == null || tos.isEmpty()) {
            return null;
        }
        int fromBit = slice ? -1 : FamilyArcProvider.busBit(clockName);
        int toBit = slice ? -1 : FamilyArcProvider.busBit(dataName);
        boolean lut = FittedCellArcLibrary.isLUTOnLUTBEL(cellType, bel);
        Map<String, String> attrs = FamilyArcProvider.getProperties(design.getDevice().getSeries(), c,
                lib.getConfigKeys(lut ? null : cellType));
        String key = cellType + "|" + bel + "|" + site + "|" + from + fromBit + "|" + tos + toBit + "|" + attrs
                + (slice ? "|" + FamilyArcProvider.busBase(clockName) + "|" + FamilyArcProvider.busBase(dataName) : "");
        float[] d = cache.get(key);
        if (d == null) {
            d = new float[]{find(cellType, bel, site, from, fromBit, tos, toBit, attrs, slice, clockName, dataName, true),
                find(cellType, bel, site, from, fromBit, tos, toBit, attrs, slice, clockName, dataName, false)};
            cache.put(key, d);
        }
        return d;
    }

    /**
     * Gets the BEL pins of a pin of a slice cell: the one it maps to first, then the others in name order. A port
     * can map to several BEL pins, e.g. an address of a single-port LUT RAM (RAMS32, RAMS64E) to its read and
     * write address pins (A1 and WA1), of which only the write address pin has timing checks.
     * @param c The cell.
     * @param logicalPin The cell pin.
     * @return The BEL pins, empty if none.
     */
    static List<String> getBELPins(Cell c, String logicalPin) {
        String first = c.getPhysicalPinMapping(logicalPin);
        Set<String> all = c.getAllPhysicalPinMappings(logicalPin);
        List<String> pins = new ArrayList<>(all == null ? 1 : all.size());
        if (first != null) {
            pins.add(first);
        }
        if (all != null) {
            for (String p : new TreeSet<>(all)) {
                if (!p.equals(first)) {
                    pins.add(p);
                }
            }
        }
        return pins;
    }

    /** @return The setup or hold time of a check: the largest over the data pin's BEL pins with an arc, 0 if none */
    private float find(String cellType, String bel, String site, String from, int fromBit, List<String> tos,
                       int toBit, Map<String, String> attrs, boolean slice, String clockName, String dataName,
                       boolean setup) {
        float time = Float.NaN;
        for (String to : tos) {
            float t = find(cellType, bel, site, from, fromBit, to, toBit, attrs, slice, clockName, dataName, setup);
            if (!Float.isNaN(t) && (Float.isNaN(time) || t > time)) {
                time = t;
            }
        }
        if (Float.isNaN(time)) {
            missing.add(cellType + " " + (setup ? "setup" : "hold") + " " + from + "->" + tos.get(0));
            return 0f;
        }
        return time;
    }

    /** @return The setup or hold time of a check to one pin, NaN if it has no arc */
    private float find(String cellType, String bel, String site, String from, int fromBit, String to, int toBit,
                       Map<String, String> attrs, boolean slice, String clockName, String dataName, boolean setup) {
        Role[] roles = setup ? new Role[]{Role.SETUP, Role.RECOVERY} : new Role[]{Role.HOLD, Role.REMOVAL};
        int idx = setup ? setupIdx : holdIdx;
        for (Role role : roles) {
            FittedCellArcLibrary.Result r = lib.lookup(cellType, bel, site, role, from, fromBit, to, toBit, attrs,
                    slice ? FamilyArcProvider.busBase(clockName) : null,
                    slice ? FamilyArcProvider.busBase(dataName) : null);
            if (r.getArc() == null || r.getMismatch() >= FittedCellArcLibrary.REGISTER_MISMATCH
                    || r.getDelays() == null) continue;
            return lib.scale(r.getDelays()[idx], deviceName, speedGrade, cellType, r.getArc(),
                    FittedCellArcLibrary.getCategory(cellType, site), role, idx);
        }
        return Float.NaN;
    }
}
