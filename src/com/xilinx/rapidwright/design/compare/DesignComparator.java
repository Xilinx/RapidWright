/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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
package com.xilinx.rapidwright.design.compare;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Predicate;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.compare.EDIFNetlistComparator;

/**
 * A physical design comparison helper class that will compare two designs'
 * placement and routing information and keep track of the differences. Please
 * see {@link EDIFNetlistComparator} to compare logical netlists
 * ({@link EDIFNetlist}).
 */
public class DesignComparator {

    /** Stored map of design differences detected and their specific instances */
    private Map<DesignDiffType, List<DesignDiff>> diffMap;
    /** Total running count of differences found */
    private int diffCount;
    /** Flag indicating if PIP flags should be compared (default: false) */
    private boolean comparePIPFlags = false;
    /** Predicate indicating if routing (PIPs) should be compared for given Net (default: true) */
    private Predicate<Net> comparePIPs = (net) -> true;
    /**
     * Flag indicating if placement information should be compared (default: true)
     */
    private boolean comparePlacement = true;

    public DesignComparator() {
        resetDiffCount();
    }

    /**
     * Compares two designs placement and routing (according to flags, see
     * {@link #comparePlacement}, {@link #comparePIPs}, and
     * {@link #comparePIPFlags}). It will return the total number of differences
     * encountered and an optional report can be created by subsequently calling
     * {@link #printDiffReportSummary(PrintStream)} or
     * {@link #printDiffReport(PrintStream)}.
     * 
     * Note that the number of differences are stored in the class and can be
     * retrieved afterward by calling {@link #getDiffCount()}. Calling this method a
     * second and subsequent times will reset the counter and tracked diffs.
     * 
     * @param gold The expected design or reference.
     * @param test The design being compared with the reference.
     * @return The total number of differences encountered.
     */
    public int compareDesigns(Design gold, Design test) {
        resetDiffCount();
        if (!gold.getPartName().equals(test.getPartName())) {
            addDiff(DesignDiffType.DESIGN_PARTNAME, gold, test, null, "");
        }
        
        if (comparePlacement) {
            Map<String, SiteInst> goldMap = getSiteInstMap(gold);
            Map<String, SiteInst> testMap = getSiteInstMap(test);

            for (Entry<String, SiteInst> e : goldMap.entrySet()) {
                SiteInst testSiteInst = testMap.remove(e.getKey());
                if (testSiteInst == null) {
                    addDiff(DesignDiffType.SITEINST_MISSING, e.getValue(), null, gold, "");
                    continue;
                }
                compareSiteInsts(e.getValue(), testSiteInst);
            }

            for (Entry<String, SiteInst> e : testMap.entrySet()) {
                addDiff(DesignDiffType.SITEINST_EXTRA, null, e.getValue(), test, "");
            }
        }

        Map<String, Net> goldNetMap = getNetMap(gold);
        Map<String, Net> testNetMap = getNetMap(test);

        for (Entry<String, Net> e : goldNetMap.entrySet()) {
            Net testNet = testNetMap.remove(e.getKey());
            if (testNet == null) {
                addDiff(DesignDiffType.NET_MISSING, e.getValue(), null, gold, "");
                continue;
            }
            compareNets(e.getValue(), testNet);
        }

        for (Entry<String, Net> e : testNetMap.entrySet()) {
            addDiff(DesignDiffType.NET_EXTRA, null, e.getValue(), test, "");
        }

        return getDiffCount();
    }
    
    private Map<String,SiteInst> getSiteInstMap(Design design) {
        Map<String,SiteInst> map = new HashMap<>();
        for(SiteInst si : design.getSiteInsts()) {
            String siteName = si.getSiteName();
            if (siteName == null) {
                System.out.println("WARNING: " + design.getName() 
                    + " has at least one unplaced site instance: " + si.getName());
            }
            map.put(si.getSiteName(), si);
        }
        return map;
    }

    private Map<String, Net> getNetMap(Design design) {
        Map<String, Net> map = new HashMap<>();
        for (Net net : design.getNets()) {
            map.put(net.getName(), net);
        }
        return map;
    }

    /**
     * Gets the total number of differences encountered since the last call of
     * {@link #compareDesigns(Design, Design)}.
     * 
     * @return Total number of design differences found.
     */
    public int getDiffCount() {
        return diffCount;
    }

    public void resetDiffCount() {
        diffCount = 0;
        diffMap = new EnumMap<>(DesignDiffType.class);
    }

    /**
     * Gets the comparePIPFlags flag indicating if the routing flags on a design's
     * PIPs should be compared by DesignComparator.
     * 
     * @return True if the flag is set, false otherwise (default: false).
     */
    public boolean comparePIPFlags() {
        return comparePIPFlags;
    }

    /**
     * Sets a flag to tell the design comparator if PIP flags should also be
     * compared.
     * 
     * @param comparePIPFlags Desired flag value (default: false).
     */
    public void setComparePIPFlags(boolean comparePIPFlags) {
        this.comparePIPFlags = comparePIPFlags;
    }

    /**
     * Sets a flag to tell the design comparator if PIP should be compared.
     * 
     * @param comparePIPs Desired flag value (default: true).
     */
    public void setComparePIPs(boolean comparePIPs) {
        this.comparePIPs = (net) -> comparePIPs;
    }

    /**
     * Set the predicate for the design comparator if PIPs should be compared for the given Net.
     *
     * @param predicate Predicate.
     */
    public void setComparePIPs(Predicate<Net> predicate) {
        this.comparePIPs = predicate;
    }

    /**
     * Gets the comparePlacement flag indicating if a design's placement (cells,
     * sitepips, sitewire to net mappings) should be compared by DesignComparator.
     * 
     * @return True if the flag is set, false otherwise (default: true).
     */
    public boolean getComparePlacement() {
        return comparePlacement;
    }

    /**
     * Sets the flag to tell the design comparator if placement (cell placements,
     * sitePIPs, and site wire to net mappings) should be compared.
     * 
     * @param comparePlacement Desired flag value (default: true);
     */
    public void setComparePlacement(boolean comparePlacement) {
        this.comparePlacement = comparePlacement;
    }

    public Map<DesignDiffType, List<DesignDiff>> getDiffMap() {
        return diffMap;
    }

    public List<DesignDiff> getDiffList(DesignDiffType type) {
        return diffMap.getOrDefault(type, Collections.emptyList());
    }

    private void addDiff(DesignDiffType type, Object gold, Object test, Object context, String notEqualString) {
        List<DesignDiff> diffs = diffMap.computeIfAbsent(type, l -> new ArrayList<>());
        diffs.add(new DesignDiff(type, gold, test, context, notEqualString));
        diffCount++;
    }

    /**
     * Compares two site instances for placement and site routing differences.
     * Specifically it detects differences in three categories, (1) cell placements,
     * (2) site PIPs, and (3) site wire to net mappings.
     * 
     * @param gold The expected or reference site instance.
     * @param test The site instance being compared to the reference.
     * @return The number of differences encountered while comparing the provided
     *         site instances. This number also mutates the total diff count stored
     *         in the class.
     */
    public int compareSiteInsts(SiteInst gold, SiteInst test) {
        int init = getDiffCount();

        if (!gold.getName().equals(test.getName())) {
            addDiff(DesignDiffType.SITEINST_NAME, gold, test, null, "");
        }

        Map<String, Cell> goldMap = gold.getCellMap();
        Map<String, Cell> testMap = new HashMap<>(test.getCellMap());

        // Compare placed cells
        for (Entry<String, Cell> e : goldMap.entrySet()) {
            Cell testCell = testMap.remove(e.getKey());
            if (testCell == null) {
                addDiff(DesignDiffType.PLACED_CELL_MISSING, e.getValue(), null, gold, "");
                continue;
            }

            if (!Objects.equals(e.getValue().getName(), testCell.getName())) {
                addDiff(DesignDiffType.PLACED_CELL_NAME, e.getValue(), testCell, gold, "");
            }

            if (!Objects.equals(e.getValue().getType(), testCell.getType())) {
                addDiff(DesignDiffType.PLACED_CELL_TYPE, e.getValue(), testCell, gold, "");
            }
        }
        for (Entry<String, Cell> e : testMap.entrySet()) {
            Cell extraCell = e.getValue();
            addDiff(DesignDiffType.PLACED_CELL_EXTRA, null, extraCell, test, "");
        }

        
        // SiteWire to Net mappings
        Map<String, Net> goldSiteWireMap = gold.getSiteWireToNetMap();
        Map<String, Net> testSiteWireMap = new HashMap<>(test.getSiteWireToNetMap());

        for (Entry<String, Net> e : goldSiteWireMap.entrySet()) {
            Net testNet = testSiteWireMap.remove(e.getKey());
            if (testNet == null) {
                addDiff(DesignDiffType.SITEWIRE_NET_MISSING, e.getKey(), null, gold, "should be Net " + e.getValue());
                continue;
            }
            if (!e.getValue().getName().equals(testNet.getName())) {
                addDiff(DesignDiffType.SITEWIRE_NET_NAME, e.getKey(), testNet, gold , "");
            }
        }
        for (Entry<String, Net> e : testSiteWireMap.entrySet()) {
            Net extraNet = e.getValue();
            addDiff(DesignDiffType.SITEWIRE_NET_EXTRA, null, e.getKey(), test, " extra Net " + extraNet);
        }
        
        // Active SitePIPs
        Map<BEL, SitePIP> goldSitePIPs = getSitePIPMap(gold);
        Map<BEL, SitePIP> testSitePIPs = getSitePIPMap(test);
        
        for (Entry<BEL, SitePIP> e : goldSitePIPs.entrySet()) {
            SitePIP testPIP = testSitePIPs.remove(e.getKey());
            if (testPIP == null) {
                addDiff(DesignDiffType.SITEPIP_MISSING, e.getValue(), null, gold, "");
                continue;
            }
            if (!e.getValue().getInputPinName().equals(testPIP.getInputPinName())) {
                addDiff(DesignDiffType.SITEPIP_INPIN_NAME, e.getValue(), testPIP, gold, "");
            }
        }
        for (Entry<BEL, SitePIP> e : testSitePIPs.entrySet()) {
            addDiff(DesignDiffType.SITEPIP_EXTRA, null, e.getValue(), test, "");
        }

        return getDiffCount() - init;
    }
    
    private Map<BEL, SitePIP> getSitePIPMap(SiteInst siteInst) {
        Map<BEL, SitePIP> map = new HashMap<>();
        for (SitePIP p : siteInst.getUsedSitePIPs()) {
            SitePIP duplicate = map.put(p.getBEL(), p);
            if (duplicate != null) {
                System.out.println("WARNING: Multiple SitePIPs active on BEL " + p.getBELName() + " of Site "
                        + siteInst.getSiteName());
            }
        }
        return map;
    }

    private Map<String, PIP> getPIPMap(Net net) {
        Map<String, PIP> map = new HashMap<>();
        for (PIP p : net.getPIPs()) {
            map.put(p.toString(), p);
        }
        return map;
    }

    /**
     * Compares two nets for differences in routing (PIPs). Two flags can be set to
     * affect its operation: {@link #setComparePIPs(boolean)} (default: true) and
     * {@link #setComparePIPFlags(boolean)} (default: false).
     * 
     * @param gold The expected or reference net.
     * @param test The net to be compared against the reference net.
     * @return The number of differences encountered while comparing the provided
     *         nets. This number also mutates the total diff count stored in the
     *         class.
     */
    public int compareNets(Net gold, Net test) {
        int init = getDiffCount();

        if (!comparePIPs.test(gold) && !comparePIPs.test(test)) {
            return getDiffCount() - init;
        }

        Map<String, PIP> goldMap = getPIPMap(gold);
        Map<String, PIP> testMap = getPIPMap(test);

        for (Entry<String, PIP> e : goldMap.entrySet()) {
            PIP testPIP = testMap.remove(e.getKey());
            if (testPIP == null) {
                addDiff(DesignDiffType.PIP_MISSING, e.getValue(), null, gold, "");
                continue;
            }

            if (comparePIPFlags && !e.getValue().deepEquals(testPIP)) {
                addDiff(DesignDiffType.PIP_FLAGS, e.getValue(), testPIP, gold, "");
            }
        }

        for (Entry<String, PIP> e : testMap.entrySet()) {
            addDiff(DesignDiffType.PIP_EXTRA, null, e.getValue(), test, "");
        }
        
        return getDiffCount() - init;
    }

    /**
     * Prints a summary total for each difference type found in the recent
     * comparisons since the last time the diff counter was reset.
     * 
     * @param ps The desired print stream ('System.out', e.g. for immediate screen
     *           printing).
     */
    public void printDiffReportSummary(PrintStream ps) {
        ps.println("=============================================================================");
        ps.println("= Design Diff Summary");
        ps.println("=============================================================================");
        int totalSanity = 0;
        for (DesignDiffType type : DesignDiffType.values()) {
            int typeDiffCount = diffMap.getOrDefault(type, Collections.emptyList()).size();
            totalSanity += typeDiffCount;
            ps.printf("%9d %s Diffs\n", typeDiffCount, type.name());
        }
        ps.println("-----------------------------------------------------------------------------");
        assert (totalSanity == diffCount);
        ps.printf("%9d Total Diffs\n\n", diffCount);
    }

    /**
     * Prints an exhaustive list of diffs with specific contextual information about
     * each difference. This also begins by invoking
     * {@link #printDiffReportSummary(PrintStream)} first.
     * 
     * @param ps The desired print stream ('System.out', e.g. for immediate screen
     *           printing).
     */
    public void printDiffReport(PrintStream ps) {
        printDiffReportSummary(ps);

        for (Entry<DesignDiffType, List<DesignDiff>> e : diffMap.entrySet()) {
            ps.println(" *** " + e.getKey() + ": " + e.getValue().size() + " diffs");
            for (DesignDiff diff : e.getValue()) {
                ps.println("  " + diff.toString());
            }

        }
    }
}
