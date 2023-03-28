/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.device;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestNode {
    @ParameterizedTest
    @CsvSource({
            "xcvu19p-fsva3824-1-e,INT_X182Y338/WW12_BEG5",
            "xcvu19p-fsva3824-1-e,INT_X182Y338/NN12_BEG5",
    })
    public void testGetDownhillUphillNodesUnique(String deviceName, String nodeName) {
        Device device = Device.getDevice(deviceName);
        Node node = device.getNode(nodeName);

        List<Node> downhill = node.getAllDownhillNodes();
        if (node.getWireName().equals("WW12_BEG5")) {
            Assertions.assertNotEquals(downhill.size(), new HashSet<>(downhill).size());
        } else {
            Assertions.assertEquals(downhill.size(), new HashSet<>(downhill).size());
        }

        Collection<Node> downhillUnique = node.getAllDownhillNodes(new HashSet<>());
        Assertions.assertEquals(downhillUnique.size(), new HashSet<>(downhillUnique).size());

        List<Node> uphill = node.getAllUphillNodes();
        Assertions.assertEquals(uphill.size(), new HashSet<>(uphill).size());
        
        Collection<Node> uphillUnique = node.getAllUphillNodes(new HashSet<>());
        Assertions.assertEquals(uphillUnique.size(), new HashSet<>(uphillUnique).size());
    }
    
    @Test
    public void testNullNode() {
        Device d = Device.getDevice("xcvm1802-vfvc1760-1LHP-i-L");
        PIP p = d.getPIP("BLI_LS_CORE_X90Y335/BLI_LS_CORE_R180.HSR_GRP1_A_BLI_LOGIC_OUTS0->>BLI_GRP1_A_BLI_LOGIC_OUTS0");
        Assertions.assertNull(p.getStartNode());
    }

    @Test
    public void testUphillNodeIsInvalid() {
        // link_design -part [lindex [get_parts xcvu440*] 0]
        // get_nodes -uphill -of [get_nodes INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0]
        // WARNING: [Vivado 12-2683] No nodes matched 'get_nodes -uphill -of [get_nodes INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0]'
        Device d = Device.getDevice("xcvu440");
        Node n = d.getNode("INT_INT_INTERFACE_XIPHY_FT_X157Y688/LOGIC_OUTS_R0");
        Assertions.assertNotNull(n);
        Assertions.assertTrue(n.getAllUphillNodes().isEmpty());
    }
}

