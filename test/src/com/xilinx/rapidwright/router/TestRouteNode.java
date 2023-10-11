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

package com.xilinx.rapidwright.router;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Wire;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;

public class TestRouteNode {
    @ParameterizedTest
    @CsvSource({
            // Connecting west:
            //   node RCLK_DSP_INTF_CLKBUF_L_X36Y629/CLK_HDISTR_R8 ->
            //   node RCLK_DSP_INTF_CLKBUF_L_X19Y629/CLK_HDISTR_R8
            "RCLK_DSP_INTF_CLKBUF_L_X36Y629/CLK_HDISTR_R8,RCLK_DSP_INTF_CLKBUF_L_X36Y629/CLK_HDISTR_L8,RCLK_DSP_INTF_CLKBUF_L_X36Y629/RCLK_DSP_INTF_CLKBUF_L.CLK_HDISTR_L8<<->>CLK_HDISTR_R8,true",
            // Connecting east:
            //   node RCLK_DSP_INTF_CLKBUF_L_X36Y629/CLK_HDISTR_R8 ->
            //   node RCLK_DSP_INTF_CLKBUF_L_X59Y629/CLK_HDISTR_R8
            "RCLK_DSP_INTF_CLKBUF_L_X59Y629/CLK_HDISTR_L8,RCLK_DSP_INTF_CLKBUF_L_X59Y629/CLK_HDISTR_R8,RCLK_DSP_INTF_CLKBUF_L_X59Y629/RCLK_DSP_INTF_CLKBUF_L.CLK_HDISTR_L8<<->>CLK_HDISTR_R8,false",
    })
    public void testGetPIPsBackToSource(String srcWireName, String sinkWireName, String pipAsString, boolean isReversed) {
        Device device = Device.getDevice("xcvu13p");
        Wire srcWire = device.getWire(srcWireName);
        RouteNode src = new RouteNode(srcWire.getTile(), srcWire.getWireIndex());
        Assertions.assertNotNull(src);

        RouteNode sink = new RouteNode(device.getWire(sinkWireName), src);
        Assertions.assertNotNull(sink);
        sink.setParent(src);
        ArrayList<PIP> pips = sink.getPIPsBackToSource();
        Assertions.assertEquals(1, pips.size());
        Assertions.assertEquals(pipAsString, pips.get(0).toString());
        Assertions.assertEquals(isReversed, pips.get(0).isReversed());
    }
}
