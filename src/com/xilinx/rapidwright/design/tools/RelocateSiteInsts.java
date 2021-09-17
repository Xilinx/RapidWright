package com.xilinx.rapidwright.design.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;

public class RelocateSiteInsts {
    public static boolean relocate(Design design,
                                   String hierarchyPrefix,
                                   int tileColOffset,
                                   int tileRowOffset) {
        Collection<SiteInst> matchingSiteInsts = new ArrayList<>();

        for (SiteInst si : design.getSiteInsts()) {
            for (Cell c : si.getCells()) {
                if (c.getName().startsWith(hierarchyPrefix)) {
                    if (si.isPlaced()) {
                        matchingSiteInsts.add(si);
                    }
                    break;
                }
            }
        }

        return relocate(design, matchingSiteInsts, tileColOffset, tileRowOffset);
    }

    public static boolean relocate(Design design,
                                   Collection<SiteInst> siteInsts,
                                   int tileColOffset,
                                   int tileRowOffset) {

        HashMap<SiteInst, Site> oldPlacementSite = new HashMap<>();
        boolean revertPlacement = false;

        for (SiteInst si : siteInsts) {
            assert(si.isPlaced());
            Site ss = si.getSite();
            Tile st = si.getTile();
            Tile dt = st.getTileXYNeighbor(tileColOffset, tileRowOffset);
            Site ds = ss.getCorrespondingSite(si.getSiteTypeEnum(), dt);
            if (dt == null || ds == null || ds == ss) {
                String destTileName = st.getNameRoot() + "_X" + (st.getTileXCoordinate() + tileColOffset)
                        + "Y" + (st.getTileYCoordinate() + tileRowOffset);
                System.out.println("FAILED to move SiteInst " + si.getName() + " from Tile " + st.getName()
                        + " to Tile " + destTileName);
                revertPlacement = true;
            } else {
                oldPlacementSite.put(si, ss);
                si.unPlace();
                si.place(ds);
            }
        }

        if (revertPlacement) {
            for (HashMap.Entry<SiteInst, Site> p : oldPlacementSite.entrySet()) {
                p.getKey().unPlace();
                p.getKey().place(p.getValue());
            }
            return false;
        }

        foreachNet: for (Net n : design.getNets()) {
            for (SitePinInst spi : n.getPins()) {
                SiteInst si = spi.getSiteInst();
                if (!oldPlacementSite.containsKey(si)) {
                    // TODO: Unroute just this branch
                    // n.unroutePin(spi);

                    System.out.println("Unrouting net " + n.getName() + " since SiteInstPin " + spi + " does not belong to selected hierarchy");
                    n.unroute();
                    continue foreachNet;
                }
            }
            boolean isClockNet = n.isClockNet() || n.hasGapRouting();
            for (PIP sp : n.getPIPs()) {
                Tile st = sp.getTile();
                Tile dt = st.getTileXYNeighbor(tileColOffset, tileRowOffset);
                if (dt == null) {
                    if (isClockNet) {
                        System.out.println("Skipping clock net PIP " + sp + " (Net " + n.getName() + ")");
                    } else {
                        String destTileName = st.getNameRoot() + "_X" + (st.getTileXCoordinate() + tileColOffset)
                                + "Y" + (st.getTileYCoordinate() + tileRowOffset);
                        System.out.println("FAILED to move PIP " + sp + " to Tile " + destTileName + "(Net " + n.getName() + ")");
                    }
                } else {
                    assert (st.getTileTypeEnum() == dt.getTileTypeEnum());
                    sp.setTile(dt);
                }
            }
        }

        return true;
    }
}
