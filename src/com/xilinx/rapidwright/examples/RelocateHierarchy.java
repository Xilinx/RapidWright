package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.design.tools.RelocationTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * Given an input DCP, a hierarchy prefix (empty string to match entire design)
 * and tile column/row offsets, move all matching cells (and the PIPs that
 * connect between such cells) by this tile offset.
 *
 * Specifically, the SiteInst associated with a matching Cell is relocated;
 * thus it is assumed that all
 *
 * @author eddieh
 *
 */
public class RelocateHierarchy {

    public static void main(String[] args) {
        if (args.length != 5) {
            System.out.println("USAGE: <input_dcp> <hierarchy_prefix> <tile_col_offset> <tile_row_offset> <output_dcp>");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Relocate Design", true).start("Loading design");

        String dcpName = args[0];
        Design design = Design.readCheckpoint(dcpName,CodePerfTracker.SILENT);

        t.stop().start("Relocation");

        String hierarchyPrefix = args[1];
        int colOffset = Integer.parseInt(args[2]);
        int rowOffset = Integer.parseInt(args[3]);

        if (!RelocationTools.relocate(design, hierarchyPrefix, colOffset, rowOffset)) {
            throw new RuntimeException("ERROR: Relocation failed");
        }

        t.stop().start("Write DCP");

        design.setAutoIOBuffers(false);
        design.writeCheckpoint(args[4], CodePerfTracker.SILENT);
        t.stop().printSummary();
    }
}
