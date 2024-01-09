/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestWireInterface {

    @Test
    public void testWireInterface() {
        Device device = Device.getDevice(Device.KCU105);

//        get_nodes INT_X45Y18/WW2_W_BEG1
//        INT_X45Y18/WW2_W_BEG1
//        get_wires -of [get_nodes INT_X45Y18/WW2_W_BEG1]
//        INT_X45Y18/WW2_W_BEG1 INT_X44Y18/WW2_W_END1 CLEL_R_X44Y18/EASTBUSOUT_FT1_17 FSR_GAP_X44Y18/EASTBUSOUT_FT1_17 CLE_M_X45Y18/EASTBUSOUT_FT1_17 CFRM_CBRK_L_X45Y0/EASTBUSOUT_FT1_18_17

        Node node = device.getNode("INT_X45Y18/WW2_W_BEG1");
        Wire[] wires = node.getAllWiresInNode();

        // Node vs. Wire
        for (int i = 0; i < wires.length; i++) {
            Assertions.assertEquals(i == 0, node.getTile().equals(wires[i].getTile()));
            Assertions.assertEquals(i == 0, node.getWireIndex() == wires[i].getWireIndex());
        }

        // Node vs. WireInterface
        WireInterface[] wireInts = wires;
        for (int i = 0; i < wireInts.length; i++) {
            Assertions.assertEquals(i == 0, node.hashCode() == wireInts[i].hashCode());
            Assertions.assertEquals(i == 0, node.equals(wireInts[i]));
            Assertions.assertEquals(i == 0, wireInts[i].equals(node));
        }

        // WireInterface vs. Wire
        WireInterface wireInt = node;
        for (int i = 0; i < wires.length; i++) {
            Assertions.assertEquals(i == 0, wireInt.hashCode() == wires[i].hashCode());
            Assertions.assertEquals(i == 0, wireInt.equals(wires[i]));
            Assertions.assertEquals(i == 0, wires[i].equals(wireInt));
        }

    }
}
