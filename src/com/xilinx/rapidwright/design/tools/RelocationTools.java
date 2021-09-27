package com.xilinx.rapidwright.design.tools;

import java.util.*;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.Utils;

/**
 * A collection of tools to help relocate designs.
 *
 * @author eddieh
 *
 */
public class RelocationTools {

    /**
     * Relocate all SiteInsts (and all associated PIPs) belonging to the logical Cell at
     * instanceName in-place by tileColOffset/tileRowOffset tiles.
     *
     * Should a SiteInst contain a mix of physical Cells belonging to and outside of
     * instanceName then this function will fail.
     *
     * @param design Parent design
     * @param instanceName Full hierarchical instance name to logical cell
     *                     (empty for top cell)
     * @param tileColOffset Relocate this number of tile columns (X axis)
     * @param tileRowOffset Relocate this number of tile rows (Y axis)
     * @return True if successful, false otherwise.
     */
    public static boolean relocate(Design design,
                                   String instanceName,
                                   int tileColOffset,
                                   int tileRowOffset) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFHierCellInst instanceCell = netlist.getHierCellInstFromName(instanceName);
        if (instanceCell == null) {
            System.out.println("ERROR: Logical cell with instance name '" + instanceName + "' not found");
            return false;
        }

        Set<Cell> cells = new HashSet<>();
        Set<SiteInst> siteInsts = new HashSet<>();
        for (EDIFHierCellInst leaf : netlist.getAllLeafDescendants(instanceCell)) {
            String leafName = leaf.getCellName();
            if (leafName.equals("GND") || leafName.equals("VCC")) {
                continue;
            }

            Cell c = design.getCell(leaf.getFullHierarchicalInstName());
            if (c == null) {
                System.out.println("WARNING: Could not find physical cell corresponding to logical cell '" +
                        leaf.getFullHierarchicalInstName() + "'; ignoring");
                continue;
            }
            cells.add(c);

            SiteInst si = c.getSiteInst();
            if (si == null) {
                continue;
            }

            if (!Utils.sliceDspBramUramTypes.contains(si.getSiteTypeEnum())) {
                System.out.println("WARNING: Skipping cell '" + leaf.getFullHierarchicalInstName() +
                        "' as it is placed onto a SiteInst type '" + si.getSiteTypeEnum() + "'");
                continue;
            }

            siteInsts.add(si);
        }

        // Check that every SiteInst only contains Cells part of the hierarchy
        boolean error = false;
        for (SiteInst si : siteInsts) {
            for (Cell c : si.getCells()) {
                if (!c.isLocked() && !cells.contains(c)) {
                    System.out.println("ERROR: Failed to relocate SiteInst '" + si.getName()
                            + "' as it contains Cells both inside and outside of '" + instanceName + "'");
                    error = true;
                }
            }
        }

        return !error && relocate(design, siteInsts, tileColOffset, tileRowOffset);
    }

    /**
     * Relocate all SiteInsts (and PIPs) within the Pblock in-place by tileColOffset/tileRowOffset tiles.
     *
     * @param design Parent design
     * @param pblock PBlock
     * @param tileColOffset Relocate this number of tile columns (X axis)
     * @param tileRowOffset Relocate this number of tile rows (Y axis)
     * @return True if successful, false otherwise.
     */
    public static boolean relocate(Design design,
                                   PBlock pblock,
                                   int tileColOffset,
                                   int tileRowOffset) {
        Collection<SiteInst> siteInsts = new ArrayList<>();
        for (Site s : pblock.getAllSites(null)) {
            SiteInst si = design.getSiteInstFromSite(s);
            if (si != null) {
                siteInsts.add(si);
            }
        }
        return relocate(design, siteInsts, tileColOffset, tileRowOffset);
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
            SiteInst ssi = e.getKey();
            assert(ds != ss);
            if (dt == null || ds == null) {
                String destTileName = st.getNameRoot() + "_X" + (st.getTileXCoordinate() + tileColOffset)
                        + "Y" + (st.getTileYCoordinate() + tileRowOffset);
                System.out.println("ERROR: Failed to move SiteInst '" + ssi.getName() + "' from Tile '" + st.getName()
                        + "' to Tile '" + destTileName + "'");
                revertPlacement = true;
                continue;
            }
            SiteInst dsi = design.getSiteInstFromSite(ds);
            if (dsi != null) {
                if (dsi.getName().startsWith("STATIC_SOURCE")) {
                    dsi.unPlace();
                } else {
                    System.out.println("ERROR: Failed to move SiteInst '" + ssi.getName() + "' from Tile '" + st.getName()
                            + "' to Tile '" + dt.getName() + "' as it is already occupied");
                    revertPlacement = true;
                    continue;
                }
            }

            ssi.place(ds);
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

            SitePinInst src = n.getSource();
            if (src != null && !oldSite.containsKey(src.getSiteInst())) {
                System.out.println("INFO: Unrouting Net '" + n.getName() + "' since output SiteInstPin '" +
                        src + "' does not belong to SiteInsts to be relocated");
                n.unroute();
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
                for (SitePinInst spi : nonMatchingPins) {
                    System.out.println("INFO: Unrouting SitePinInst '" + spi + "' branch of Net '" + n.getName() +
                            "' since it does not belong to SiteInsts to be relocated");
                    n.unroutePin(spi);
                }
            }

            boolean isClockNet = n.isClockNet() || n.hasGapRouting();
            for (PIP sp : n.getPIPs()) {
                Tile st = sp.getTile();
                Tile dt = st.getTileXYNeighbor(tileColOffset, tileRowOffset);
                if (dt == null) {
                    if (isClockNet) {
                        System.out.println("INFO: Skipping clock net PIP '" + sp + "' (Net '" + n.getName() + "')");
                    } else {
                        String destTileName = st.getNameRoot() + "_X" + (st.getTileXCoordinate() + tileColOffset)
                                + "Y" + (st.getTileYCoordinate() + tileRowOffset);
                        System.out.println("ERROR: Failed to move PIP '" + sp + "' to Tile '" + destTileName +
                                "' (Net '" + n.getName() + "')");
                        revertRouting = true;
                        throw new RuntimeException();
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
