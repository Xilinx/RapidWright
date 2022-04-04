package com.xilinx.rapidwright.edif;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestEDIFTools {

    public static final String UNIQUE_SUFFIX = "TestEDIFToolsWasHere";
    
    @Test
    public void testConnectPortInstsThruHier() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();
        
        EDIFHierPortInst srcPortInst = netlist.getHierPortInstFromName("base_mb_i/microblaze_0/U0/"
                + "MicroBlaze_Core_I/Performance.Core/Data_Flow_I/Data_Flow_Logic_I/Gen_Bits[22]."
                + "MEM_EX_Result_Inst/Using_FPGA.Native/Q");
        EDIFHierPortInst snkPortInst = netlist.getHierPortInstFromName("u_ila_0/inst/PROBE_PIPE."
                + "shift_probes_reg[0][7]/D");
        
        // Disconnect sink in anticipation of connecting to another net
        snkPortInst.getNet().removePortInst(snkPortInst.getPortInst());
        
        EDIFTools.connectPortInstsThruHier(srcPortInst, snkPortInst, netlist, UNIQUE_SUFFIX);
        
        netlist.resetParentNetMap();
        
        
        List<EDIFHierNet> netAliases = netlist.getNetAliases(srcPortInst.getHierarchicalNet());
        Assertions.assertEquals(netAliases.size(), 16);
        boolean containsSnkNet = false;
        for(EDIFHierNet net : netAliases) {
            if(net.getHierarchicalNetName().equals(snkPortInst.getHierarchicalNetName())) {
                containsSnkNet = true;
            }
        }
        Assertions.assertTrue(containsSnkNet);
        
        
        List<EDIFHierPortInst> portInsts = netlist.getPhysicalPins(srcPortInst.getHierarchicalNet());
        Assertions.assertEquals(portInsts.size(), 6);
        boolean containsSnk = false;
        for(EDIFHierPortInst sink : portInsts) {
            if(sink.toString().equals(snkPortInst.toString())) {
                containsSnk = true;
            }
        }
        Assertions.assertTrue(containsSnk);
        
        
        
    }

    @Test
    void testRename() {
        Assertions.assertEquals("emoji______", EDIFTools.makeNameEDIFCompatible("emoji_\uD83D\uDE0B\uD83C\uDF9B️"));
        Assertions.assertEquals("_", EDIFTools.makeNameEDIFCompatible(" "));
    }
}
