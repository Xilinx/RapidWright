package com.xilinx.rapidwright.edif;

import java.util.LinkedList;
import java.util.Queue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.python.google.common.base.Strings;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestEDIFHierCellInst {

    @Test
    public void testIsAncestor() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();

        EDIFHierCellInst topInst = netlist.getTopHierCellInst();
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        topInst.addChildren(q);
        while(!q.isEmpty()) {
            EDIFHierCellInst curr = q.poll();
            Assertions.assertTrue(curr.isDescendantOf(topInst));
            Assertions.assertFalse(topInst.isDescendantOf(curr));
            curr.addChildren(q);
        }
    }
    
    @Test
    public void testGetCommonAncestor() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();

        String name0 = "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Data_Flow_I/"
                        + "Data_Flow_Logic_I/Gen_Bits[22].MEM_EX_Result_Inst/Using_FPGA.Native";
        String name1 = "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Decode_I/"
                + "PreFetch_Buffer_I1/Instruction_Prefetch_Mux[9].Gen_Instr_DFF/EX_Op3[22]_i_2";
        
        EDIFHierCellInst inst0 = netlist.getHierCellInstFromName(name0);
        EDIFHierCellInst inst1 = netlist.getHierCellInstFromName(name1);
        
        EDIFHierCellInst commonAncestor = inst0.getCommonAncestor(inst1);
        
        String commonPrefix = Strings.commonPrefix(name0, name1); 
        Assertions.assertEquals(commonAncestor.getFullHierarchicalInstName(), 
                commonPrefix.substring(0, commonPrefix.lastIndexOf('/')));
        
        String name2 = "u_ila_0/inst/ila_core_inst/basic_trigger_reg";
        
        EDIFHierCellInst inst2 = netlist.getHierCellInstFromName(name2);
        commonAncestor = inst1.getCommonAncestor(inst2);
        Assertions.assertEquals(commonAncestor, netlist.getTopHierCellInst());
    }
}
