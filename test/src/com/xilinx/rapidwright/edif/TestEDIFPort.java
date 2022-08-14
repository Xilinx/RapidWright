/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
 *  
 * This file is part of RapidWright. 
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
 
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
