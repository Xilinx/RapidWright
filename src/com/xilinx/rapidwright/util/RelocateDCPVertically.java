/*
 *
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
 *
 */

package com.xilinx.rapidwright.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
// Need util.Pair to allow compiling outside IDE with only dependent on RapidWright
import com.xilinx.rapidwright.util.Pair;



/**
 * Fill some black boxes of a given design with a specific implementation.
 */
public class RelocateDCPVertically {

    /**
     * Fill some black boxes of the given design with the given implementation.
     * @param top          The design with black boxes to fill
     * @param mod          The implementation to fill the black boxes
     * @param cell_anchor  The reference INT tile used as the handle to {@code mod} parameter
     * @param toCells      The list of pairs of a black box cell to be filled and its reference INT tile.
     *                     The x-coordinate of this INT tile must match that of the cell_anchor.
     */
    public static boolean relocateCell(Design top, Module mod, String cell_anchor, List<Pair<String, String>> toCells) {
        System.out.println("\n\nrelocateCell");
        boolean clearTgtSiteInst = true;

        EDIFNetlist netlist = top.getNetlist();
        netlist.migrateCellAndSubCells(mod.getNetlist().getTopCell());
        top.setAutoIOBuffers(false);

        Site frSite = mod.getAnchor().getSite();
        Tile frTile = frSite.getTile();
        // The cell_anchor is used by a user as an anchor to the cell.  It can differ from anchorTile.
        // Relocate the cell is equivalent to moving the cell so that the cell_anchor align with the specified INT tile.
        Tile tFrom = top.getDevice().getTile(cell_anchor);
        System.out.printf("\nmove fr %12s                : anchor %14s  %14s\n", cell_anchor, frSite, frTile);

        for (Pair<String,String> cell : toCells) {
            Tile tTo   = top.getDevice().getTile(cell.getSecond());
            if (tFrom.getColumn() != tTo.getColumn()) {
                System.out.println("Target location of " + cell.getFirst() + ", " + cell.getSecond() +
                        ", is not vertically aligned with that of the implementation, " + cell_anchor + ".");
                return false;
            }
        }

        for (Pair<String,String> cell : toCells) {
            Tile tTo   = top.getDevice().getTile(cell.getSecond());
            int verticalMoveOffset = tFrom.getRow() - tTo.getRow();

            Tile toTile = frTile.getTileNeighbor(0,verticalMoveOffset);
            Site toSite = toTile.getSites()[frTile.getSiteIndex(frSite)];
            System.out.printf("     to %12s  y_offset %4d : anchor %14s  %14s\n", cell.getSecond(), verticalMoveOffset, toSite, toTile);
            clearTargetSiteInsts(mod, toSite, top);
            if(!mod.isValidPlacement(toSite, top)){
                System.out.println("Invalid placement.");
                return false;
            }

            ModuleInst mi = top.createModuleInst(cell.getFirst(), mod, true);
            mi.getCellInst().setCellType(mod.getNetlist().getTopCell());
            mi.place(toSite);
        }
        System.out.println("\n\n");
        return true;
    }


    private static Site getCorrespondingValidSite(Module mod, SiteInst inst, Site anchorSite) {
        Site site = inst.getSite();
        Tile newTile = mod.getCorrespondingTile(site.getTile(), anchorSite.getTile());
        if(newTile == null){
            return null;
        }
        Site newSite = site.getCorrespondingSite(inst.getSiteTypeEnum(), newTile);
        if(newSite == null){
            return null;
        }
        return newSite;
    }

    /**
     * Unplace existing SiteInts already placed at the proposed locations.
     * @param proposedAnchorSite The proposed new anchor site
     * @param design The design to operate on
     * @return False if an invalid Tile or Site is encountered. True otherwise.
     */
    private static boolean clearTargetSiteInsts(Module mod, Site proposedAnchorSite, Design design){

        for(SiteInst inst : mod.getSiteInsts()){
            if(Utils.isLockedSiteType(inst.getSiteTypeEnum())){
                continue;
            }

            Site newSite = getCorrespondingValidSite(mod, inst, proposedAnchorSite);

            SiteInst si = design.getSiteInstFromSite(newSite);
            if(design != null &&  si != null){
                design.removeSiteInst(si);
            }
        }

        return true;
    }

    public static boolean isClockNet(Net net) {
        for(SitePinInst sink : net.getSinkPins()) {
            if(sink.getName().contains("CLK")) return true;
        }
        return false;
    }


    //  cellName = "openacap_shell_i/RP_0"
    public static void combinePIPonClockNets(Design top, String cellName) {
        System.out.println("Begin combinePIPonClockNets " + cellName);

        List<String> clockNets = new ArrayList<>();

        for(EDIFPortInst p : top.getNetlist().getCellInstFromHierName(cellName).getPortInsts()) {
            // openacap_shell_i/RP_0/clk[0],1,2, openacap_shell_i/RP_0/rp_clk[0]
            String hierNetName_outside = top.getNetlist().getHierNetFromName(cellName + "/" + p.getInternalNet().getName()).getHierarchicalNetName();
            for(EDIFHierNet net : top.getNetlist().getNetAliases(top.getNetlist().getHierNetFromName(hierNetName_outside))) {
                String physNetName = net.getHierarchicalNetName();
                Net physNet = top.getNet(physNetName);

                if (physNet != null) {
                    //                 System.out.println("   " +physNetName + " is clock " + physNet.isClockNet() + " look up from "  + hierNetName_outside);
                    if (isClockNet(physNet)) {
                        clockNets.add(hierNetName_outside);
                        break;
                    }
                }
            }
        }

        for(String netName : clockNets) {
            Set<PIP> pips = new HashSet<>();
            for(EDIFHierNet net : top.getNetlist().getNetAliases(top.getNetlist().getHierNetFromName(netName))) {
                String physNetName = net.getHierarchicalNetName();
                Net physNet = top.getNet(physNetName);
                if (physNet != null) {
                    for (PIP p : physNet.getPIPs()) {
                        pips.add(p);
                    }
//                     System.out.println("hierNet   " + physNetName);
//                     System.out.println("   " +physNetName + " is clock " + physNet.isClockNet());
                }
            }

            for(EDIFHierNet net : top.getNetlist().getNetAliases(top.getNetlist().getHierNetFromName(netName))) {
                String physNetName = net.getHierarchicalNetName();
                Net physNet = top.getNet(physNetName);
                if (physNet != null) {
                    physNet.setPIPs(new ArrayList<>(pips));
//                     System.out.println(" Put " + pips.size() + " pips on " + physNetName);
//                     System.out.println("hierNet   " + physNetName);
//                     System.out.println("   " +physNetName + " is clock " + physNet.isClockNet());
                }
            }

        }
    }


    public static void setPropertyValueInLateXDC (Design top, String prop, String val) {
        ArrayList<String> xdcList =  new ArrayList<String>(top.getXDCConstraints(ConstraintGroup.LATE));
        int lineNum = 0;
        for (; lineNum < xdcList.size(); lineNum++) {
            String line = xdcList.get(lineNum);
            if (line.contains(prop)) {
                String[] words = line.split("\\s+");
                int idx = Arrays.asList(words).indexOf(prop);
                if (++idx < words.length) {
                    words[idx] = val;
                    String newLine = String.join(" ", words);
                    xdcList.set(lineNum, newLine);
                    break;
                }
            }
        }

        if (lineNum < xdcList.size()) {
            top.setXDCConstraints(xdcList, ConstraintGroup.LATE);
            System.out.println("\nINFO: property " + prop + " is found for the top design. It will be set to false.");
        }
    }

// Example arguments
/*
   -in    hwct
   -from  hwct_rp0           INT_X32Y0
   -to    hw_contract_rp2    INT_X32Y240
   -to    hw_contract_rp1    INT_X32Y120
   -to    hw_contract_rp0    INT_X32Y0
   -out   hw_contract_userp0
*/

/*   need to do RP_1 last, otherwise nets of some BUFGCE become unrouted!
   -in    openacap_shell_bb
   -from  AES128_inst_1_RP1     INT_X32Y120
   -to    openacap_shell_i/RP_2 INT_X32Y240
   -to    openacap_shell_i/RP_0 INT_X32Y0
   -to    openacap_shell_i/RP_1 INT_X32Y120
   -out   openacap_shell_aes128
 */

    public static void main(String[] args) {
        String usage = String.join(System.getProperty("line.separator"),
                "Relocate DCP to fill vertically aligned black boxes",
                "  -in   <name of the DCP of a design with black boxes>",
                "  -out  <name of the output DCP>",
                "  -form <name of the DCP of an implementation to fill the black boxes>",
                "        <a reference interconnect tile, eg., INT_X32Y0>",
                "  -to   <a full hierarchical name to a black box in the DCP specified by -in>",
                "        <a reference interconnect tile, eg., INT_X32Y120>",
                "  -to   <can be repeated as many as the number of black boxes to fill>",
                "",
                "Note: If the DCP specified by -form has a corresponding black box in the DCP specified by -in,",
                "      it can be filled as well and it must be listed as the last -to option.");


        long startTime = System.nanoTime();

        String topDCPName  = null;
        String newDCPName  = null;
        String cellDCPName = null;
        String cellAnchor  = null;
        List<Pair<String,String>> targets = new ArrayList<>();

        String toCell = null;
        String toLoc  = null;

        // collect command line arguments
        int i = 0;
        while (i < args.length) {
            // check flags
            switch (args[i]) {
                case "-help":
                    System.out.println(usage);
                    System.exit(0);
                    break;
                case "-in":   topDCPName = args[++i];
                    break;
                case "-out":  newDCPName = args[++i];
                    break;
                case "-from": cellDCPName = args[++i];
                    if (i < args.length) {
                        cellAnchor  = args[++i];
                    } else {
                        System.out.println("Missing value for option -from.");
                        System.out.println(usage);
                        System.exit(1);
                    }
                    break;
                case "-to":   toCell  = args[++i];
                    if (i < args.length) {
                        toLoc   = args[++i];
                        targets.add(new Pair<>(toCell, toLoc));
                    } else {
                        System.out.println("Missing value for option -to");
                        System.out.println(usage);
                        System.exit(1);
                    }
                    break;
                default:      System.out.println("Invalid option " + args[i] + " found.");
                    System.out.println(usage);
                    System.exit(1);
                    break;
            }
            i++;
        }

        // report collected arguments
        System.out.println("RelocateDCP");
        System.out.println("  -in   " + topDCPName);
        System.out.println("  -out  " + newDCPName);
        System.out.println("  -from " + cellDCPName + " " + cellAnchor);
        for (Pair<String,String> toCellLoc : targets) {
            System.out.println("  -to   " + toCellLoc.getFirst() + " " + toCellLoc.getSecond());
        }
        System.out.println();

        // trim extension in case I need to specify an edf file.
        // TODO: Consider removing this step
        int idx = topDCPName.lastIndexOf('.');
        if (idx >= 0) {
            topDCPName = topDCPName.substring(0, idx);
        }
        idx = newDCPName.lastIndexOf('.');
        if (idx >= 0) {
            newDCPName = newDCPName.substring(0, idx);
        }
        idx = cellDCPName.lastIndexOf('.');
        if (idx >= 0) {
            cellDCPName = cellDCPName.substring(0, idx);
        }

        // fill the black boxes
        Design top = Design.readCheckpoint(topDCPName + ".dcp", topDCPName + ".edf");
        Module mod = new Module(Design.readCheckpoint(cellDCPName + ".dcp", cellDCPName + ".edf"), false);
        if (relocateCell(top, mod, cellAnchor, targets)) {

            top.getNetlist().resetParentNetMap();

            for (Pair<String,String> toCellLoc : targets) {
                combinePIPonClockNets(top, toCellLoc.getFirst());
            }

            setPropertyValueInLateXDC (top, "HD.RECONFIGURABLE", "false");

            System.out.println();
            top.writeCheckpoint(newDCPName + ".dcp");
            System.out.println("\nFill " + targets.size() + " target black boxes successfully.\n");
        } else {
            System.out.println("\nFail to fill all target black boxes.\n");
        }

        long stopTime = System.nanoTime();
        System.out.printf("\nElapsed time %3.0f sec.\n\n", (stopTime - startTime)*1e-9);
    }
}
