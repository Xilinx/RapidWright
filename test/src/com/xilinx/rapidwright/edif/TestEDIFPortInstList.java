/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class TestEDIFPortInstList {

    public EDIFPortInst makeEDIFPortInst(String portInstName) {
        EDIFPortInst portInst = new EDIFPortInst();
        portInst.setName(portInstName);
        portInst.setPort(new EDIFPort());
        String instanceName = portInstName.contains("/") ? 
                portInstName.substring(0, portInstName.lastIndexOf("/")) : null; 
        if(instanceName != null) {
            EDIFCellInst cellInst = new EDIFCellInst();
            cellInst.setName(instanceName);
            portInst.setCellInst(cellInst);
            portInst.setName(portInstName.substring(portInstName.lastIndexOf("/")+1));
        }
        int index = portInstName.endsWith("]") ? EDIFTools.getPortIndexFromName(portInstName) : -1;
        portInst.setIndex(index);
        return portInst;
    }
    
    @Test
    public void testEDIFPortInstListSorting() {
        EDIFPortInstList list = new EDIFPortInstList();
 
        ArrayList<String> allNames = new ArrayList<>();
        String[] names = new String[] {
            "processor/DOADO[0]",
            "processor/D[7]",
            "processor/D[0]",
            "processor/DOADO[2]",
            "processor/DOADO[4]",
            "processor/DOADO[7]",
            "processor/DOADO[1]",
            "processor/DOADO[2]",
            "processor/DOADO[3]",
            "processor/DOADO[4]",
            "processor/DOADO[5]",
            "processor/DOADO[6]",
            "processor/DOADO[7]",
            "processor/D[0]",
            "processor/D[1]",
            "processor/D[2]",
            "processor/D[3]",
            "processor/D[4]",
            "processor/D[5]",
            "processor/D[6]",
            "processor/D[7]",
            "processor/E[0]",
            "processor/Q[1]",
            "processor/Q[3]",
            "processor/Q[2]",
            "processor/Q[5]",
            "processor/Q[0]",
            "processor/Q[0]",
            "processor/Q[1]",
            "processor/Q[2]",
            "processor/Q[3]",
            "processor/Q[4]",
            "processor/Q[5]",
            "processor/Q[6]",
            "processor/Q[7]",
            "processor/address[0]",
            "processor/address[10]",
            "processor/address[11]",
            "processor/address[1]",
            "processor/address[2]",
            "processor/address[2]",
            "processor/address[3]",
            "processor/address[4]",
            "processor/address[4]",
            "processor/address[5]",
            "processor/address[3]",
            "processor/address[1]",
            "processor/address[10]",
            "processor/address[0]",
            "processor/address[11]",
            "processor/address[5]",
            "processor/address[6]",
            "processor/address[7]",
            "processor/address[8]",
            "processor/address[9]",
            "processor/bram_enable",
            "processor/clk",
            "processor/input_port_a[0]",
            "processor/input_port_a[1]",
            "processor/input_port_a[2]",
            "processor/input_port_a[3]",
            "processor/input_port_a[4]",
            "processor/input_port_a[5]",
            "processor/input_port_a[5]",
            "processor/input_port_a[7]",
            "processor/input_port_a[2]",
            "processor/input_port_a[3]",
            "processor/input_port_a[1]",
            "processor/input_port_a[0]",
            "processor/input_port_a[6]",
            "processor/input_port_a[7]",
            "processor/input_port_b[0]",
            "processor/input_port_b[3]",
            "processor/input_port_b[1]",
            "processor/input_port_b[2]",
            "processor/input_port_b[1]",
            "processor/input_port_b[0]",
            "processor/input_port_b[2]",
            "processor/input_port_b[3]",
            "processor/input_port_b[4]",
            "processor/input_port_b[5]",
            "processor/input_port_b[6]",
            "processor/input_port_b[7]",
            "processor/input_port_c[1]",
            "processor/input_port_c[6]",
            "processor/input_port_c[7]",
            "processor/input_port_c[3]",
            "processor/input_port_c[0]",
            "processor/input_port_c[2]",
            "processor/input_port_c[3]",
            "processor/input_port_c[4]",
            "processor/input_port_c[5]",
            "processor/input_port_c[7]",
            "processor/input_port_d[0]",
            "processor/input_port_d[1]",
            "processor/input_port_d[2]",
            "processor/input_port_d[4]",
            "processor/input_port_d[5]",
            "processor/input_port_d[5]",
            "processor/input_port_d[0]",
            "processor/input_port_d[3]",
            "processor/input_port_d[4]",
            "processor/input_port_d[6]",
            "processor/input_port_d[7]",
            "processor/out_port[0]",
            "processor/out_port[2]",
            "processor/out_port[1]",
            "processor/out_port[0]",
            "processor/out_port[1]",
            "processor/out_port[2]",
            "processor/out_port[3]",
            "processor/out_port[4]",
            "processor/out_port[5]",
            "processor/out_port[6]",
            "processor/out_port[7]",
            "processor/reset",
            "processor/write_strobe_flop_0[0]",
            "processor/write_strobe_flop_1[0]",
            "processor/write_strobe_flop_2[0]",
            "port_name", 
            "port_name/Q"
        };
        
        
        
        for(String name : names) {
            allNames.add(name);
        }
        for(String name : EDIFTools.bitBlast("x[12:0]")) {
            allNames.add(name);
        }
        
        HashSet<String> uniqueSet = new HashSet<>();
        for(String name : allNames) {
            // Test to ensure duplicates are not allowed
            boolean success = list.add(makeEDIFPortInst(name));
            boolean isDuplicate = uniqueSet.add(name);
            Assertions.assertEquals(success, isDuplicate);
        }

        Assertions.assertEquals(uniqueSet.size(), list.size());
        allNames.clear();
        allNames.addAll(uniqueSet);
        
        Collections.sort(allNames);
        
        ArrayList<String> listSorted = new ArrayList<>();
        for(int i=0; i < allNames.size(); i++) {
            listSorted.add(list.get(i).getFullName());
        }

        Assertions.assertTrue(listSorted.containsAll(allNames) && allNames.containsAll(listSorted));
        
        for(EDIFPortInst portInst : new ArrayList<>(list)) {
            EDIFPortInst portInstGet = list.get(portInst.getCellInst(), portInst.getName());
            Assertions.assertEquals(portInst, portInstGet);
            list.remove(portInst);
            portInstGet = list.get(portInst.getCellInst(), portInst.getName());
            Assertions.assertNull(portInstGet);
            list.add(portInst);
            portInstGet = list.get(portInst.getCellInst(), portInst.getName());
            Assertions.assertEquals(portInst, portInstGet);
        }
    }
}
