package com.xilinx.rapidwright.design.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.util.Pair;

/**
 * A collection of tools to help relocate designs.
 *
 * @author eddieh
 *
 */
public class RelocationTools {

    /**
     * Relocate all SiteInsts containing exclusively Cells matching
     * hierarchyPrefix (and all associated PIPs) in-place by
     * tileColOffset/tileRowOffset tiles.
     *
     * Should a SiteInst contain matching and non-matching Cells,
     * function will fail.
     *
     * @param design Parent design
     * @param hierarchyPrefix Cell matches if hierarchical path starts with this value
     *                        (empty string to match all Cells)
     * @param tileColOffset Relocate this number of tile columns (X axis)
     * @param tileRowOffset Relocate this number of tile rows (Y axis)
     * @return True if successful, false otherwise.
     */
    public static boolean relocate(Design design,
                                   String hierarchyPrefix,
                                   int tileColOffset,
                                   int tileRowOffset) {
        Collection<SiteInst> matchingSiteInsts = new ArrayList<>();
        Predicate<Cell> matchLambda = (c) -> c.getName().startsWith(hierarchyPrefix);
        boolean error = false;

        for (SiteInst si : design.getSiteInsts()) {
            Collection<Cell> cells = si.getCells();
            Cell firstCell = cells.iterator().next();
            boolean firstCellMatches = matchLambda.test(firstCell);
            // Check that all other cells in this site also match/don't match
            if (cells.stream().skip(1).allMatch(c -> matchLambda.test(c) == firstCellMatches))
            {
                if (firstCellMatches)
                    matchingSiteInsts.add(si);
            } else {
                System.out.println("ERROR: Failed to relocate SiteInst " + si.getName()
                        + " as it contains both matching and non-matching Cells");
                error = true;
            }
        }

        return !error && relocate(design, matchingSiteInsts, tileColOffset, tileRowOffset);
    }

    /**
     * Relocate all given SiteInsts and PIPs in-place by
     * tileColOffset/tileRowOffset tiles.
     *
     * Should any SiteInst or PIP not be relocatable to a tile at the specified
     * offset (e.g. if a compatible tile does not exist) function will return
     * false and design will be unmodified.
     *
     * Any net sourced from a SiteInst not in the given set will be fully
     * unrouted; those destined for a SiteInst not in the given set will have
     * their specific branch unrouted.
     *
     * @param design Parent design
     * @param siteInsts List of SiteInsts to be relocated
     * @param tileColOffset Relocate this number of tile columns (X axis)
     * @param tileRowOffset Relocate this number of tile rows (Y axis)
     * @return True if successful, false otherwise.
     */
    public static boolean relocate(Design design,
                                   Collection<SiteInst> siteInsts,
                                   int tileColOffset,
                                   int tileRowOffset) {
        if (siteInsts.isEmpty())
            return true;

        if (tileColOffset == 0 && tileRowOffset == 0)
            return true;

        Map<SiteInst, Site> oldSite = new HashMap<>();
        for (SiteInst si : siteInsts) {
            assert(si.isPlaced());
            oldSite.put(si, si.getSite());
            si.unPlace();
        }

        boolean revertPlacement = false;
        for (Map.Entry<SiteInst, Site> e : oldSite.entrySet()) {
            Site ss = e.getValue();
            Tile st = ss.getTile();
            Tile dt = st.getTileXYNeighbor(tileColOffset, tileRowOffset);
            Site ds = ss.getCorrespondingSite(ss.getSiteTypeEnum(), dt);
            SiteInst si = e.getKey();
            assert(ds != ss);
            if (dt == null || ds == null) {
                String destTileName = st.getNameRoot() + "_X" + (st.getTileXCoordinate() + tileColOffset)
                        + "Y" + (st.getTileYCoordinate() + tileRowOffset);
                System.out.println("ERROR: Failed to move SiteInst " + si.getName() + " from Tile " + st.getName()
                        + " to Tile " + destTileName);
                revertPlacement = true;
            } else if (design.isSiteUsed(ds)) {
                System.out.println("ERROR: Failed to move SiteInst " + si.getName() + " from Tile " + st.getName()
                        + " to Tile " + dt.getName() + " as its is already occupied");
                revertPlacement = true;
            } else {
                si.place(ds);
            }
        }

        if (revertPlacement) {
            revertPlacement(oldSite);
            return false;
        }

        List<Pair<Net, List<PIP>>> oldRoute = new ArrayList<>();
        boolean revertRouting = false;

        DesignTools.createMissingSitePinInsts(design);

        for (Net n : design.getNets()) {
            if (!n.hasPIPs()) {
                continue;
            }

            Collection<SitePinInst> pins = n.getPins();
            Collection<SitePinInst> nonMatchingPins = pins.stream().filter(
                    (spi) -> !oldSite.containsKey(spi.getSiteInst())).collect(Collectors.toList());
            if (nonMatchingPins.size() == pins.size()) {
                continue;
            }

            oldRoute.add(new Pair<>(n, n.getPIPs()));

            if (!nonMatchingPins.isEmpty()) {
                // TODO: Unroute just this branch
                // nonMatchingPins.forEach((spi) -> n.unroutePin(spi));

                SitePinInst spi = nonMatchingPins.iterator().next();
                System.out.println("INFO: Unrouting net " + n.getName() + " since SiteInstPin " + spi + " does not belong to selected hierarchy");
                n.unroute();
                continue;
            }

            boolean isClockNet = n.isClockNet() || n.hasGapRouting();
            for (PIP sp : n.getPIPs()) {
                Tile st = sp.getTile();
                Tile dt = st.getTileXYNeighbor(tileColOffset, tileRowOffset);
                if (dt == null) {
                    if (isClockNet) {
                        System.out.println("INFO: Skipping clock net PIP " + sp + " (Net " + n.getName() + ")");
                    } else {
                        String destTileName = st.getNameRoot() + "_X" + (st.getTileXCoordinate() + tileColOffset)
                                + "Y" + (st.getTileYCoordinate() + tileRowOffset);
                        System.out.println("ERROR: Failed to move PIP " + sp + " to Tile " + destTileName + "(Net " + n.getName() + ")");
                        revertRouting = true;
                    }
                } else {
                    assert (st.getTileTypeEnum() == dt.getTileTypeEnum());
                    sp.setTile(dt);
                }
            }
        }

        if (revertRouting) {
            revertPlacement(oldSite);
            revertRouting(oldRoute);
            return false;
        }

        return true;
    }

    private static void revertRouting(List<Pair<Net, List<PIP>>> oldRoute) {
        for (Pair<Net,List<PIP>> e : oldRoute) {
            e.getFirst().setPIPs(e.getSecond());
        }
    }

    private static void revertPlacement(Map<SiteInst, Site> oldSite) {
        for (Map.Entry<SiteInst, Site> e : oldSite.entrySet()) {
            e.getKey().unPlace();
            e.getKey().place(e.getValue());
        }
    }
}
