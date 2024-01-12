/*
 * Copyright (c) 2023-2024, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.router.RouteThruHelper;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.function.Function;

public class TestGlobalSignalRouting {
    @ParameterizedTest
    @CsvSource({
            "CLKBWRCLK,",
            "RSTRAMB,",
            "WEBWE[0],WEBL0",
            "ADDRENA,ADDRENAL",
            "ADDRENB,ADDRENBU"
    })
    public void testRAMB36(String logicalPinName, String erroringSitePinName) throws Throwable {
        Design design = new Design("design", "xcvu3p");
        Cell bufg = design.createAndPlaceCell("test_bufg", Unisim.BUFGCE, "BUFGCE_X0Y0/BUFCE");
        Net globalNet = design.createNet("clk");
        globalNet.connect(bufg,"O");

        Cell target = design.createAndPlaceCell("test_ram", Unisim.RAMB36E2, "RAMB36_X0Y0/RAMB36E2");
        if (logicalPinName.equals("CLKBWRCLK") || logicalPinName.equals("RSTRAMB") ||
                logicalPinName.equals("ADDRENA") || logicalPinName.equals("ADDRENB")) {
            target.addPinMapping(logicalPinName + "L", logicalPinName);
            target.addPinMapping(logicalPinName + "U", logicalPinName);
        } else if (logicalPinName.equals("WEBWE[0]")) {
            target.addPinMapping("WEBWEL0", logicalPinName);
            target.addPinMapping("WEBWEU0", logicalPinName);
        }
        globalNet.connect(target, logicalPinName);

        // FIXME: Currently, Net.connect() only connects the first physical pin to the net
        //        This is a canary assertion that will light up when this gets fixed.
        Assertions.assertEquals(2 /* 3 */, globalNet.getPins().size());

        Executable e = () -> GlobalSignalRouting.symmetricClkRouting(globalNet, design.getDevice(), (n) -> NodeStatus.AVAILABLE);
        if (erroringSitePinName == null) {
            e.execute();
        } else {
            // FIXME: Known broken -- see https://github.com/Xilinx/RapidWright/issues/756
            RuntimeException ex = Assertions.assertThrows(RuntimeException.class, e, "true");
            Assertions.assertEquals("ERROR: No mapped LCB to SitePinInst IN RAMB36_X0Y0." + erroringSitePinName,
                    ex.getMessage());
        }
    }

    @Test
    public void testRouteStaticNet() {
        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");

        RWRoute.preprocess(design);

        List<SitePinInst> gndPins = design.getGndNet().getPins();
        List<SitePinInst> vccPins = design.getVccNet().getPins();
        // Note: these numbers are slightly different from RWRoute since RWRoute.routeStaticNets()
        //       uses RouterHelper.invertPossibleGndPinsToVccPins()
        Assertions.assertEquals(22760, gndPins.size());
        Assertions.assertEquals(19402, vccPins.size());

        Function<Node, NodeStatus> gns = (n) -> NodeStatus.AVAILABLE;
        RouteThruHelper routeThruHelper = new RouteThruHelper(design.getDevice());

        GlobalSignalRouting.routeStaticNet(design.getGndNet(), gns, design, routeThruHelper);
        gndPins = design.getGndNet().getPins();
        Assertions.assertEquals(1952, gndPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(22760, gndPins.stream().filter((spi) -> !spi.isOutPin()).count());

        GlobalSignalRouting.routeStaticNet(design.getVccNet(), gns, design, routeThruHelper);
        vccPins = design.getVccNet().getPins();
        Assertions.assertEquals(0, vccPins.stream().filter((spi) -> spi.isOutPin()).count());
        Assertions.assertEquals(19402, vccPins.stream().filter((spi) -> !spi.isOutPin()).count());
    }
}
