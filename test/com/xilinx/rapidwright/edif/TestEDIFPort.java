package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFPort {

    @Test    
    public void testEDIFPortInternalNets() {
        Design design = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp"));
        
        for(EDIFLibrary lib : design.getNetlist().getLibraries()) {
            for(EDIFCell cell : lib.getCells()) {
                boolean isLeaf = cell.isLeafCellOrBlackBox();
                Map<String,EDIFNet> internalNetMap = cell.getInternalNetMap();
                for(EDIFPort port : cell.getPorts()) {
                   if(port.isBus()) {
                       List<EDIFNet> nets = port.getInternalNets();                      
                       for(int i=0; i < port.getWidth(); i ++) {
                           EDIFNet net = nets.get(i);
                           Assertions.assertEquals(port.getInternalNet(i), nets.get(i));
                           String portInstName = port.getPortInstNameFromPort(i);
                           Assertions.assertEquals(internalNetMap.get(portInstName), net);
                           if(isLeaf) {
                               Assertions.assertNull(net);
                           }
                       }
                   }else {
                       EDIFNet net = port.getInternalNet();
                       String portInstName = port.getPortInstNameFromPort(0);
                       Assertions.assertEquals(internalNetMap.get(portInstName), net);
                       if(isLeaf) {
                           Assertions.assertNull(net);
                       }
                   }
                }
            }
        }
    }
}
