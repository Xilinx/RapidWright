/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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
import com.xilinx.rapidwright.device.Device;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFNet {

    @Test
    void testEquals() {
        String designName = "design";
        final EDIFNetlist netlist = EDIFTools.createNewNetlist(designName);
        final Design design = new Design(designName, Device.KCU105);
        design.setNetlist(netlist);

        EDIFCell ec1 = new EDIFCell(netlist.getWorkLibrary(), "ec1");
        EDIFCell ec2 = new EDIFCell(netlist.getWorkLibrary(), "ec2");

        EDIFNet en1 = ec1.createNet("foo");
        EDIFNet en2 = ec2.createNet("foo");

        Assertions.assertNotEquals(en1, en2);
    }
}
