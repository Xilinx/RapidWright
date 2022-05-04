package com.xilinx.rapidwright.edif;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    public void testCreateNewNetlist() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("bnn.dcp"), true);
        EDIFHierCellInst inst = d.getNetlist().getHierCellInstFromName("bd_0_i/hls_inst/inst/dmem_V_U");
        
        EDIFNetlist newNetlist = EDIFTools.createNewNetlist(inst.getInst());
        EDIFTools.ensureCorrectPartInEDIF(newNetlist, d.getPartName());
        Design d2 = new Design(newNetlist);
        d2.setAutoIOBuffers(false);
        d2.setDesignOutOfContext(true);

        List<EDIFHierCellInst> goldChildren = d.getNetlist().getAllLeafDescendants(inst);
        List<EDIFHierCellInst> testChildren = d2.getNetlist().getAllLeafDescendants("");
        
        Assertions.assertEquals(goldChildren.size(), testChildren.size());        
    }
}
