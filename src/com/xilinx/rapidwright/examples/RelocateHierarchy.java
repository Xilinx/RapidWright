package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.*;
import com.xilinx.rapidwright.design.tools.RelocationTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class RelocateHierarchy {

    /**
     * @param args
     */
    public static void main(String[] args) {
        if(args.length != 5) {
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

        RelocationTools.relocate(design, hierarchyPrefix, colOffset, rowOffset);

        t.stop().start("Write DCP");

        design.setAutoIOBuffers(false);
        design.writeCheckpoint(args[4], CodePerfTracker.SILENT);
        t.stop().printSummary();
    }
}
