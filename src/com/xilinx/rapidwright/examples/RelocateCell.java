package com.xilinx.rapidwright.examples;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.PIPType;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.helper.TileColumnPattern;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.placer.handplacer.HandPlacer;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Pair;

public class RelocateCell {

    /**
     * @param args
     */
    public static void main(String[] args) {
        if(args.length != 4 && args.length != 5){
            // TODO: Can part not be inferred from input_dcp?
            System.out.println("USAGE: <input_dcp> <tile_col_offset> <tile_row_offset> <output_dcp>");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Relocate Design", true).start("Loading design");

        String dcpName = args[0];
        Design design = Design.readCheckpoint(dcpName,CodePerfTracker.SILENT);

        t.stop().start("Moving contents");

        int colOffset = Integer.parseInt(args[1]);
        int rowOffset = Integer.parseInt(args[2]);

        boolean undo = false;
        {
            List<Pair<SiteInst, Site>> undoJournal = new ArrayList<Pair<SiteInst, Site>>();

            for (SiteInst si : design.getSiteInsts()) {
                if (si.isPlaced()) {
                    Site ss = si.getSite();
                    Tile st = si.getTile();
                    Tile dt = st.getTileXYNeighbor(colOffset, rowOffset);
                    Site ds = ss.getCorrespondingSite(si.getSiteTypeEnum(), dt);
                    if (dt == null || ds == null || ds == ss) {
                        System.out.println("FAILED to move SiteInst " + si.getName() + " from Tile " + st.getName() + " to Tile " + st.getNameRoot() + "_X" + (st.getTileXCoordinate() + colOffset) + "Y" + (st.getTileYCoordinate() + rowOffset));
                        undo = true;
                    } else {
                        undoJournal.add(new Pair<SiteInst, Site>(si, ss));
                        si.unPlace();
                        si.place(ds);
                    }
                }
            }

            if (undo) {
                for (Pair<SiteInst, Site> p : undoJournal) {
                    p.getFirst().unPlace();
                    p.getFirst().place(p.getSecond());
                }
                return;
            }
        }

        {
            for (Net n : design.getNets()) {
                for (PIP sp : n.getPIPs()) {
                    Tile st = sp.getTile();
                    Tile dt = st.getTileXYNeighbor(colOffset, rowOffset);
                    if (dt == null) {
                        // TODO: Are these all the prefixes we need?
                        if (st.getName().startsWith("CMT_") || st.getName().startsWith("RCLK_")) {
                            System.out.println("Skipping PIP " + sp + " (Net " + n.getName() + ")");
                        } else {
                            System.out.println("FAILED to move PIP " + sp + " to Tile " + st.getNameRoot() + "_X" + (st.getTileXCoordinate() + colOffset) + "Y" + (st.getTileYCoordinate() + rowOffset) + "(Net " + n.getName() + ")");
                        }
                    } else {
                        assert (st.getTileTypeEnum() == dt.getTileTypeEnum());
                        sp.setTile(dt);
                    }
                }
            }
        }

        t.stop().start("Write DCP");

        design.setAutoIOBuffers(false);
        design.writeCheckpoint(args[2], CodePerfTracker.SILENT);
        t.stop().printSummary();
    }
}
