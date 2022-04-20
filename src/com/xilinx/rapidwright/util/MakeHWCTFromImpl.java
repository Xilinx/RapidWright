package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class MakeHWCTFromImpl {
    public static void main(String[] args) {
        String hwctDCPName = "hwct.dcp";
        String cellAnchor = "INT_X0Y60";
        ArrayList<Pair<String, String>> targets = new ArrayList<>();
        targets.add(new Pair<>("hw_contract_pr0", "INT_X0Y120"));
        targets.add(new Pair<>("hw_contract_pr2", "INT_X0Y0"));
        targets.add(new Pair<>("hw_contract_pr1", "INT_X0Y60"));


        String srcDCPName = "post_route.dcp";
        Design srcDesign = Design.readCheckpoint(srcDCPName);
//        String hwctCellName = "video_cp_i/composable/dfx_decouplers/hw_contract";
        String srcCellName = "video_cp_i/composable/dfx_decouplers/hw_contract/hw_contract_pr1";

//        this makes later d2 fail in copySiteRouting
//        Design d1 = DesignTools.createDesignFromCell(srcDesign, hwctCellName);
//        DesignTools.makeBlackBox(d1, "hw_contract_pr0");
//        DesignTools.makeBlackBox(d1, "hw_contract_pr1");
//        DesignTools.makeBlackBox(d1, "hw_contract_pr2");
//        d1.writeCheckpoint("hwctrw.dcp");

        Design d2 = DesignTools.createDesignFromCell(srcDesign, srcCellName);
        d2.writeCheckpoint("d2_temp.dcp");

//        // for debugging
//        List<Net> allnets = new ArrayList<>(d2.getNets());
//        System.out.println("num nets " + allnets.size()); // 2298
//        // unroute all ok
//        // 1st half fail
//        int startIdx =  allnets.size()/2;
//        int stopIdx = allnets.size();
//        for (int i = startIdx; i < stopIdx; i++)
//            allnets.get(i).unroute();

//        d2.writeCheckpoint("d2.dcp");



//        // Create an empty design to copy the implementation of the source cell to
//        EDIFNetlist srcCellNetlist = EDIFTools.createNewNetlist(srcDesign.getNetlist().getHierCellInstFromName(srcCellName).getInst());
//        EDIFTools.ensureCorrectPartInEDIF(srcCellNetlist, srcDesign.getPartName());
//        Design d2 = new Design(srcCellNetlist);
//        d2.setAutoIOBuffers(false);
//        d2.setDesignOutOfContext(true);
//
//        Map<String, String> cellMap = Collections.singletonMap(srcCellName, "");
////        DesignTools.copyImplementation(srcDesign, d2, true, true, cellMap);
//        DesignTools.copyImplementation(srcDesign, d2, true, true, true, true, cellMap);
//        d2.writeCheckpoint("d2_2.dcp");

        // use d2 for filing blackboxes directly below create dcp that will crash in vivado.
        // write cp above and call filling blackboxes are ok.   Does RW write/read_checkpoint do some cleanup?
//
//
//        EDIFNetlist hwctNetlist = EDIFTools.createNewNetlist(srcDesign.getNetlist().getHierCellInstFromName(hwctCellName).getInst());
//        EDIFTools.ensureCorrectPartInEDIF(hwctNetlist, srcDesign.getPartName());
//        Design hwct = new Design(hwctNetlist);
//        hwct.setAutoIOBuffers(false);
//        hwct.setDesignOutOfContext(true);

        Design hwct_component = Design.readCheckpoint("d2_temp.dcp");
        Module mod = new Module(hwct_component, false);
        Design hwct = Design.readCheckpoint(hwctDCPName);
        if (RelocateModulesIntoBlackboxes.relocateModuleInsts(hwct, mod, cellAnchor, targets)) {

            hwct.getNetlist().resetParentNetMap();

            for (Pair<String,String> toCellLoc : targets) {
                RelocateModulesIntoBlackboxes.combinePIPonClockNets(hwct, toCellLoc.getFirst());
            }

            RelocateModulesIntoBlackboxes.setPropertyValueInLateXDC (hwct, "HD.RECONFIGURABLE", "false");

            System.out.println("\n");
            hwct.writeCheckpoint("hwctdirect.dcp");
            System.out.println("\n\nFilled " + targets.size() + " target black boxes successfully.\n");

        } else {
            System.out.println("\n\nFailed to fill all target black boxes.\n");
        }
    }

}
