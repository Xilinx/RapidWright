/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.eco;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import org.jetbrains.annotations.NotNull;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.placer.blockplacer.Point;
import com.xilinx.rapidwright.util.Pair;

/**
 * Class for aiding with ECO placement activities.
 * e.g. given a SLICE site, methods are provided to spiral out to find other SLICEs
 * and allowing them to be queried for unused LUT/FF BELs.
 */
public class ECOPlacementHelper {
    /** Set of all sites determined to not have any unused LUTs */
    private final Set<Site> lutLessSites = new HashSet<>();

    /** Set of all sites determined to not have any unused FFs, associated by clock net */
    private final Map<Net, Set<Site>> flopLessSitesByClk = new HashMap<>();
    /** Set of all bypass site pins (which are used to reach an FF) that are already used for routing
     *  and thus blocks use of its associated FF */
    private final Set<SitePin> blockedPinBounces = new HashSet<>();
    /** An optional map populated with the site pins marked for removal.
     *  Sites with pins queued for removal will be treated as if the pin
     *  was already removed for the purposes of finding unused flops.
     */
    private final Map<Net, Set<SitePinInst>> deferredRemovals;

    /** Name of clock site pins for current device series */
    private final String[] clkSitePinNames;
    /** Alias to {@link DesignTools#belTypeSitePinNameMapping} for current device series */
    private final Map<String, Pair<String, String>> belTypeSitePinNameMapping;

    private static final Map<Series, String[]> ULTRASCALE_CLK_SITEPIN = new EnumMap<>(Series.class);
    public static final Set<String> ultraScaleFlopNames = new HashSet<>();
    static {
        // NOTE: Only FF BELs are considered, FF2s are not to limit congestion.
        ultraScaleFlopNames.add("AFF");
        ultraScaleFlopNames.add("BFF");
        ultraScaleFlopNames.add("CFF");
        ultraScaleFlopNames.add("DFF");
        ultraScaleFlopNames.add("EFF");
        ultraScaleFlopNames.add("FFF");
        ultraScaleFlopNames.add("GFF");
        ultraScaleFlopNames.add("HFF");

        ULTRASCALE_CLK_SITEPIN.put(Series.UltraScale, new String[]{"CLK_B1", "CLK_B2"});
        ULTRASCALE_CLK_SITEPIN.put(Series.UltraScalePlus, new String[]{"CLK1", "CLK2"});
    }

    /**
     * Constructor for ECOPlacementHelper class.
     *
     * @param design           Design to be analyzed.
     * @param deferredRemovals An optional map populated with the site pins marked for removal.
     *                         Sites with pins queued for removal will be treated as if the pin
     *                         was already removed for the purposes of finding unused flops.
     */
    public ECOPlacementHelper(Design design, Map<Net, Set<SitePinInst>> deferredRemovals) {
        Device device = design.getDevice();
        this.deferredRemovals = deferredRemovals;

        // Iterate over every net and extract all pinbounce nodes blocked by its routing
        for (Net net : design.getNets()) {
            for (PIP pip : net.getPIPs()) {
                if (pip.isEndWireNull()) {
                    continue;
                }
                Wire wire = pip.getEndWire();
                if (wire.getIntentCode() != IntentCode.NODE_PINBOUNCE) {
                    continue;
                }

                Node node = pip.getEndNode();
                SitePin sitePin = node.getSitePin();
                if (sitePin != null) {
                    blockedPinBounces.add(sitePin);
                }
            }
        }

        // Extract the correct set of clock/enable/reset pins according to device series
        Series series = device.getSeries();
        clkSitePinNames = ULTRASCALE_CLK_SITEPIN.get(series);
        belTypeSitePinNameMapping = DesignTools.belTypeSitePinNameMapping.get(series);
    }

    /**
     * Given a SiteInst and a clock net, find an unused flop BEL that can host a new cell.
     * This flop BEL will have its bypass pin ([A-H](X|_I)) available.
     * Assumes that CE and SR of flop to be placed is going to be held at VCC and GND
     * respectively.
     *
     * @param siteInst SiteInst object to search inside.
     * @param clk      Desired clock net for flop cell.
     * @return Unused flop BEL.
     */
    public BEL getUnusedFlop(SiteInst siteInst, Net clk) {
        return getUnusedFlop(siteInst, clk, null, null);
    }

    /**
     * Given a SiteInst and a clock net, find an unused flop BEL that can host a new cell.
     * This flop BEL will have its bypass pin ([A-H](X|_I)) available.
     *
     * @param siteInst SiteInst object to search inside.
     * @param clk      Desired clock net for flop cell.
     * @param ce       Desired clock enable net for flop cell (null for VCC).
     * @param rst      Desired reset net for flop cell (null for GND).
     * @return Unused flop BEL.
     */
    public BEL getUnusedFlop(SiteInst siteInst, Net clk, Net ce, Net rst) {
        Site site = siteInst.getSite();
        Set<Site> flopLessSites = flopLessSitesByClk.get(clk);
        if (flopLessSites != null && flopLessSites.contains(site)) {
            // Return immediately if this site was previously found to not have any flops
            return null;
        }
        if (!siteInst.getName().startsWith(SiteInst.STATIC_SOURCE)) {
            for (String belFlop : ultraScaleFlopNames) {
                // check flop availability
                Cell currentlyUsed = siteInst.getCell(belFlop);
                if (currentlyUsed != null) continue;
                char pairID = belFlop.charAt(0);

                // Check bypass input isn't already being used
                BEL bel = siteInst.getBEL(belFlop);
                BELPin dPin = bel.getPin("D");
                String sitePinName = DesignTools.getSitePinSource(dPin);
                assert (sitePinName.matches(pairID + "(X|_I)"));
                if (siteInst.getSitePinInst(sitePinName) != null) {
                    continue;
                }

                // Check site pin isn't blocked by a net that can't be unpreserved
                // (e.g. static nets)
                SitePin sitePin = new SitePin(site, sitePinName);
                if (blockedPinBounces.contains(sitePin)) {
                    continue;
                }

                // Check existing control signals (clk, rst, en) don't conflict
                int isUpper = pairID > 'D' ? 1 : 0;

                SitePinInst existingClkSpi = siteInst.getSitePinInst(clkSitePinNames[isUpper]);
                Net existingClk = existingClkSpi != null ? existingClkSpi.getNet() : null;
                if (existingClk != null && existingClk != clk) {
                    // Allow pre-existing SitePinInsts if they were deferred for removal
                    if (deferredRemovals != null && !deferredRemovals.getOrDefault(existingClk, Collections.emptySet()).contains(existingClkSpi)) {
                        continue;
                    }
                }

                // Check that CE and SR are already connected to the
                // required nets (if null, to VCC and GND respectively)
                Pair<String, String> p = belTypeSitePinNameMapping.get(belFlop);
                Net existingCE = siteInst.getNetFromSiteWire(p.getFirst());
                if (existingCE != null) {
                    if ((ce == null && existingCE.getType() != NetType.VCC) || !ce.equals(existingCE)) {
                        continue;
                    }
                }
                Net existingSR = siteInst.getNetFromSiteWire(p.getSecond());
                if (existingSR != null) {
                    if ((rst == null && existingSR.getType() != NetType.GND) || !rst.equals(existingSR)) {
                        continue;
                    }
                }

                // Compatible BEL found! Return.
                return bel;
            }
        }

        // This site has no compatible flops -- remember it for next time
        if (flopLessSites == null) {
            flopLessSites = new HashSet<>();
            flopLessSitesByClk.put(clk, flopLessSites);
        }
        flopLessSites.add(site);
        return null;
    }

    /**
     * Given a SiteInst, find an unused LUT BEL that can host a new LUT6 cell.
     *
     * @param siteInst SiteInst object to search inside.
     * @return Unused LUT6 BEL.
     */
    public BEL getUnusedLUT(SiteInst siteInst) {
        Site site = siteInst.getSite();
        if (lutLessSites.contains(site)) return null;
        if (!siteInst.getName().startsWith(SiteInst.STATIC_SOURCE)) {
            for (Character belLUT : LUTTools.lutLetters) {
                // Check both LUTs are unoccupied, try something fancy later (TODO)
                String lut6Name = belLUT + "6LUT";
                Cell lut6 = siteInst.getCell(lut6Name);
                Cell lut5 = siteInst.getCell(belLUT + "5LUT");
                if (lut6 != null || lut5 != null) continue;

                // Check if LUT is supplying GND/VCC
                String lutOutput = belLUT + "_O";
                SitePinInst pinInst = siteInst.getSitePinInst(belLUT + "_O");
                if (pinInst != null) continue;
                if (siteInst.getNetFromSiteWire(lutOutput) != null) continue;

                // Assume not being used as a thru-site PIP (TODO)
                return siteInst.getBEL(lut6Name);
            }
        }

        lutLessSites.add(site);
        return null;
    }

    public static Site getCentroidOfPoints(Device device, List<Point> points, Set<SiteTypeEnum> targetSiteTypes) {
        Point centroid = KMeans.calculateCentroid(points);
        Tile centroidTile = device.getTile(centroid.y, centroid.x);
    
        // We need to snap to the closest site with the site type of interest from the
        // centroid tile
        Site closest = null;
        int closetDist = Integer.MAX_VALUE;
        int searchGridDim = 0;
        while (closest == null) {
            searchGridDim++;
            for (int row = -searchGridDim; row < searchGridDim; row++) {
                for (int col = -searchGridDim; col < searchGridDim; col++) {
                    Tile neighbor = centroidTile.getTileNeighbor(col, row);
                    if (neighbor != null) {
                        for (Site s : neighbor.getSites()) {
                            if (targetSiteTypes.contains(s.getSiteTypeEnum())) {
                                int manDist = centroidTile.getManhattanDistance(neighbor);
                                if (manDist < closetDist) {
                                    closest = s;
                                    closetDist = manDist;
                                }
                            }
                        }
                    }
                }
            }
        }
        return closest;        
    }

    public static Site getCentroidOfNet(Net net, Set<SiteTypeEnum> targetSiteTypes) {
        List<Point> points = new ArrayList<>();
        for (SitePinInst i : net.getPins()) {
            points.add(new Point(i.getTile().getColumn(), i.getTile().getRow()));
        }
        return ECOPlacementHelper.getCentroidOfPoints(net.getSource().getTile().getDevice(), points, targetSiteTypes);
    }

    /**
     * Given a home Site, return an Iterable that yields the neighbouring sites encountered
     * when walking outwards in a spiral fashion. To be used in conjunction with
     * {@link #getUnusedLUT(SiteInst)} and {@link #getUnusedFlop(SiteInst, Net)}.
     * @param site Originating Site.
     * @return Iterable<Site> of neighbouring sites.
     */
    public static Iterable<Site> spiralOutFrom(Site site) {
        return spiralOutFrom(site, null);
    }

    /**
     * Given a home Site, return an Iterable that yields the neighbouring sites
     * encountered when walking outwards in a spiral fashion. To be used in
     * conjunction with {@link #getUnusedLUT(SiteInst)} and
     * {@link #getUnusedFlop(SiteInst, Net)}.
     * 
     * @param site   Originating Site.
     * @param pblock Also check to ensure the proposed sites are inside the provided
     *               pblock.
     * @return Iterable<Site> of neighbouring sites.
     */
    public static Iterable<Site> spiralOutFrom(Site site, PBlock pblock) {
        return new Iterable<Site>() {
            @NotNull
            @Override
            public Iterator<Site> iterator() {
                return new Iterator<Site>() {
                    // Delta X/Y from home site
                    int dx = 0;
                    int dy = 0;
                    // Increment X/Y
                    int ix = -1;
                    int iy = 0;
                    int stepsSinceLastTurn = 0;
                    int stepLimitForNextTurn = 1;
                    int watchdog = 0;

                    final Site home = site;
                    Site nextSite = home;

                    @Override
                    public boolean hasNext() {
                        return nextSite != null;
                    }

                    @Override
                    public Site next() {
                        if (nextSite == null) {
                            throw new NoSuchElementException();
                        }
                        Site retSite = nextSite;
                        do {
                            dx += ix;
                            dy += iy;

                            if (++stepsSinceLastTurn == stepLimitForNextTurn) {
                                int tmp = ix;
                                ix = -iy;
                                iy = tmp;

                                stepsSinceLastTurn = 0;
                                if (iy == 0) {
                                    stepLimitForNextTurn++;
                                }
                            }
                            if (++watchdog == 1000000) {
                                assert(nextSite == null);
                                break;
                            }
                            nextSite = home.getNeighborSite(dx, dy);
                        } while (nextSite == null || !insidePblock(nextSite));
                        return retSite;
                    }

                    private boolean insidePblock(Site nextSite) {
                        return pblock == null ? true : pblock.containsTile(nextSite.getTile());
                    }
                };
            }
        };
    }
}
