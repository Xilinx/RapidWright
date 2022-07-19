package com.xilinx.rapidwright.edif;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestEDIFHierNet {

    @Test
    public void testGetLeafHierPortInsts() {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        
        EDIFNetlist netlist = design.getNetlist();
        
        for(EDIFHierNet parentNet : netlist.getParentNetMap().values()) {
            Set<EDIFHierPortInst> goldSet = new HashSet<>(netlist.getPhysicalPins(parentNet)); 
            Set<EDIFHierPortInst> testSet = new HashSet<>(parentNet.getLeafHierPortInsts());
            Assertions.assertEquals(goldSet.size(), testSet.size());
            
            for(EDIFHierPortInst portInst : goldSet) {
                Assertions.assertTrue(testSet.remove(portInst));
            }
            Assertions.assertTrue(testSet.isEmpty());
        }
    }
}
