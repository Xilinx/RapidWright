/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestGlobalSignalRouting {
    @ParameterizedTest
    @CsvSource({
            "CLKBWRCLK",
            "RSTRAMB"
    })
    public void testRAMB36(String logicalPinName) {
        Design design = new Design("design", Device.AWS_F1);
        Cell bufg = design.createAndPlaceCell("test_bufg", Unisim.BUFGCE, "BUFGCE_X0Y0/BUFCE");
        Net globalNet = design.createNet("clk");
        globalNet.connect(bufg,"O");

        Cell target = design.createAndPlaceCell("test_ram", Unisim.RAMB36E2, "RAMB36_X0Y0/RAMB36E2");
        if (logicalPinName.equals("CLKBWRCLK") || logicalPinName.equals("RSTRAMB")) {
            target.addPinMapping(logicalPinName + "L", logicalPinName);
            target.addPinMapping(logicalPinName + "U", logicalPinName);
        }
        globalNet.connect(target, logicalPinName);

        // FIXME: Currently, Net.connect() only connects the first physical pin to the net
        //        This is a canary assertion that will light up when this gets fixed.
        Assertions.assertEquals(2 /* 3 */, globalNet.getPins().size());

        GlobalSignalRouting.symmetricClkRouting(globalNet, design.getDevice(), (n) -> NodeStatus.AVAILABLE);
    }
}
