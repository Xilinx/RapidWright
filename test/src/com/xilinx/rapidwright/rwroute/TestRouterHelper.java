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
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.support.rwroute.RouterHelperSupport;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class TestRouterHelper {
    @ParameterizedTest
    @CsvSource({
            "xcvu3p,SLICE_X0Y0,COUT,null",
            "xcvu3p,SLICE_X0Y299,COUT,null",
            "xcvu3p,SLICE_X0Y0,A_O,CLEL_R_X0Y0/CLE_CLE_L_SITE_0_A_O",
            "xcvu3p,GTYE4_CHANNEL_X0Y12,TXOUTCLK_INT,null",
            "xcvu3p,IOB_X1Y95,I,INT_INTF_L_IO_X72Y109/LOGIC_OUTS_R23",
            "xcvu3p,MMCM_X0Y0,LOCKED,INT_INTF_L_IO_X36Y54/LOGIC_OUTS_R0",
            "xcvp1002,MMCM_X2Y0,LOCKED,BLI_CLE_BOT_CORE_X27Y0/LOGIC_OUTS_D23"
    })
    public void testProjectOutputPinToINTNode(String partName, String siteName, String pinName, String nodeAsString) {
        Design design = new Design("design", partName);
        SiteInst si = design.createSiteInst(siteName);
        SitePinInst spi = new SitePinInst(pinName, si);
        Assertions.assertEquals(nodeAsString, String.valueOf(RouterHelper.projectOutputPinToINTNode(spi)));
    }

    @ParameterizedTest
    @CsvSource({
            "xcvu3p,MMCM_X0Y0,PSEN,INT_X36Y56/IMUX_W0",
            "xcvp1002,MMCM_X2Y0,PSEN,INT_X27Y0/IMUX_B_W24"
    })
    public void testProjectInputPinToINTNode(String partName, String siteName, String pinName, String nodeAsString) {
        Design design = new Design("design", partName);
        SiteInst si = design.createSiteInst(siteName);
        SitePinInst spi = new SitePinInst(pinName, si);
        Assertions.assertEquals(nodeAsString, String.valueOf(RouterHelper.projectInputPinToINTNode(spi)));
    }

    @ParameterizedTest
    @MethodSource
    public void testInvertPossibleGndPinsToVccPins(String partName, String siteName, List<String> pinNamesAndInverted) {
        Design design = new Design("design", partName);
        SiteInst si = design.createSiteInst(siteName);
        Map<SitePinInst, Boolean> expectedResult = new HashMap<>(pinNamesAndInverted.size());
        Net gndNet = design.getGndNet();
        for (String pinNameAndInverted : pinNamesAndInverted) {
            String[] split = pinNameAndInverted.split(",", 2);
            String pinName = split[0];
            Boolean inverted = Boolean.parseBoolean(split[1]);
            SitePinInst spi = new SitePinInst(pinName, si);
            gndNet.addPin(spi);
            expectedResult.put(spi, inverted);
        }

        RouterHelper.invertPossibleGndPinsToVccPins(design, gndNet.getPins());

        Net vccNet = design.getVccNet();
        for (Map.Entry<SitePinInst, Boolean> e : expectedResult.entrySet()) {
            SitePinInst spi = e.getKey();
            boolean expectVcc = e.getValue();
            Assertions.assertSame(expectVcc ? vccNet : gndNet, spi.getNet());
        }
    }

    public static Stream<Arguments> testInvertPossibleGndPinsToVccPins() {
        return Stream.of(
                Arguments.of("xcvu3p", "RAMB36_X0Y0", Arrays.asList(
                        "ENAL,true",
                        "ENAU,true",
                        "ENBL,true",
                        "ENBU,true",
                        "RSTFIFO,true",
                        "RSTRAMAL,true",
                        "RSTRAMAU,true",
                        "RSTRAMBL,true",
                        "RSTRAMBU,true",
                        "RSTREGAL,true",
                        "RSTREGAU,true",
                        "RSTREGBL,true",
                        "RSTREGBU,true",

                        "CLKAL,false",
                        "CLKAU,false",
                        "CLKBL,false",
                        "CLKBU,false",
                        "ADDRENAL,false",
                        "ADDRENAU,false",
                        "REGCEAL,false",
                        "REGCEAU,false"
                        )),
                Arguments.of("xcvu3p", "DSP48E2_X0Y0", Arrays.asList(
                        "ALUMODE0,true",
                        "ALUMODE1,true",
                        "ALUMODE2,true",
                        "ALUMODE3,true",
                        "CARRYIN,true",
                        "CLK,true",
                        "INMODE0,true",
                        "INMODE1,true",
                        "INMODE2,true",
                        "INMODE3,true",
                        "INMODE4,true",
                        "OPMODE0,true",
                        "OPMODE1,true",
                        "OPMODE2,true",
                        "OPMODE3,true",
                        "OPMODE4,true",
                        "OPMODE5,true",
                        "OPMODE6,true",
                        "OPMODE7,true",
                        "OPMODE8,true",
                        "RSTA,true",
                        "RSTALLCARRYIN,true",
                        "RSTALUMODE,true",
                        "RSTB,true",
                        "RSTC,true",
                        "RSTCTRL,true",
                        "RSTD,true",
                        "RSTINMODE,true",
                        "RSTM,true",
                        "RSTP,true",

                        "CEINMODE,false",
                        "CED,false",
                        "CEAD,false"
                )),
                Arguments.of("xcvu3p", "URAM288_X0Y0", Arrays.asList(
                        "CLK,true",
                        "EN_A,true",
                        "EN_B,true",
                        "RDB_WR_A,true",
                        "RDB_WR_B,true",
                        "RST_A,true",
                        "RST_B,true",

                        "SLEEP,false",
                        "ADDR_A0,false",
                        "ADDR_A1,false",
                        "ADDR_A2,false"
                )),
                Arguments.of("xcvu3p", "SLICE_X1Y0", Arrays.asList(
                        "CLK1,true",
                        "CLK2,true",
                        "SRST1,true",
                        "SRST2,true",
                        "LCLK,true",

                        "CKEN1,false",
                        "CKEN2,false",
                        "CKEN3,false",
                        "CKEN4,false",
                        "WCKEN,false",
                        "CIN,false"
                ))
        );
    }

    @Test
    public void testProjectOutputPinToINTNodeBitslice() {
        Design d = new Design("test", "xcvu19p-fsva3824-1-e");

        String[] testSites = { "SLICE_X0Y1199", "SLICE_X1Y1199" };
        for (String siteName : testSites) {
            SiteInst si = d.createSiteInst(siteName);
            for (String pinName : si.getSitePinNames()) {
                SitePinInst pin = new SitePinInst(pinName, si);
                // Only test output pins to project
                if (!pin.isOutPin() || pin.getName().equals("COUT")) {
                    continue;
                }

                Node intNode = RouterHelper.projectOutputPinToINTNode(pin);
                Assertions.assertNotNull(intNode);
            }
        }

        SiteInst si = d.createSiteInst(d.getDevice().getSite("BITSLICE_RX_TX_X1Y78"));
        SitePinInst p = new SitePinInst("TX_T_OUT", si);
        Node intNode = RouterHelper.projectOutputPinToINTNode(p);

        Assertions.assertEquals(intNode.toString(), "INT_INTF_L_CMT_X182Y90/LOGIC_OUTS_R19");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testInvertPossibleGndPinsToVccPinsLutInput(boolean invertLutInputs) {
        Design design = new Design("design", "xcvu3p");
        Cell cell = design.createAndPlaceCell("lut", Unisim.LUT1, "SLICE_X0Y0/A6LUT");
        LUTTools.configureLUT(cell, "O=~I0");
        Assertions.assertEquals("O=!I0", LUTTools.getLUTEquation(cell));

        Net gndNet = design.getGndNet();
        gndNet.createPin("A6", cell.getSiteInst());

        // Check A6 was inverted, and it was moved off gndNet
        Set<SitePinInst> invertedPins = RouterHelper.invertPossibleGndPinsToVccPins(design, gndNet.getPins(), invertLutInputs);
        if (invertLutInputs) {
            Assertions.assertEquals("[IN SLICE_X0Y0.A6]", invertedPins.toString());
        } else {
            Assertions.assertTrue(invertedPins.isEmpty());
        }
        Assertions.assertEquals(invertLutInputs, gndNet.getPins().isEmpty());

        Net targetNet = invertLutInputs ? design.getVccNet() : design.getGndNet();
        Net sourceNet = !invertLutInputs ? design.getVccNet() : design.getGndNet();
        Assertions.assertEquals("[IN SLICE_X0Y0.A6]", targetNet.getPins().toString());
        Assertions.assertTrue(sourceNet.getPins().isEmpty());
        if (invertLutInputs) {
            // Must have moved onto vccNet, and the LUT mask inverted
            Assertions.assertEquals("O=I0", LUTTools.getLUTEquation(cell));

            // Now undo this optimization by going from VCC pin back to GND pin
            RouterHelperSupport.invertVccLutPinsToGndPins(design, invertedPins);

            // Check that pin is back on the original VCC net
            Assertions.assertTrue(targetNet.getPins().isEmpty());
            Assertions.assertEquals("[IN SLICE_X0Y0.A6]", sourceNet.getPins().toString());
        }

        // Check that LUT equation is back to normal
        Assertions.assertEquals("O=!I0", LUTTools.getLUTEquation(cell));
    }

    @ParameterizedTest
    @CsvSource({"" +
            "false,false",
            "false,true",
            "true,false",
            "true,true"
    })
    public void testInvertPossibleGndPinsToVccPinsLutInputOnlyIfFlattenedAndUniquified(boolean flatten, boolean uniquify) {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");

        Assertions.assertEquals(1, design.getModules().size());
        Assertions.assertEquals(4, design.getModuleInsts().size());

        if (flatten) {
            // Since ModuleInst-s (and indeed Vivado's write_edif) can create folded netlists,
            // completely flatten the design (required for ModuleInst designs) as well as uniqueify
            // all leaf cells so that modifying a LUT's INIT mask does not inadvertently modify
            // masks for other leaf cells
            design.flattenDesign();

            Assertions.assertEquals(0, design.getModules().size());
            Assertions.assertEquals(0, design.getModuleInsts().size());
        } else {
            // Not flattening nor uniquifying means that less/no opportunities exist for making
            // GND -> VCC transformations as they are only applied to uniquified LUTs.
            // It's assumed/expected that Vivado will pick up at least one error when RWRoute
            // incorrectly inverts a non-uniquified LUT
        }

        if (uniquify) {
            Boolean result = EDIFTools.uniqueifyNetlist(design);
            if (!flatten && uniquify) {
                // Cannot uniqueify without flattening -- skip test if this is the case
                Assumptions.assumeTrue(result != null);
            }
            Assertions.assertTrue(result);
        }

        RWRoute.preprocess(design);

        Net gndNet = design.getGndNet();
        List<SitePinInst> gndLutPins = new ArrayList<>();
        for (SitePinInst spi : gndNet.getPins()) {
            if (!spi.isLUTInputPin()) {
                continue;
            }
            gndLutPins.add(spi);
        }
        Assertions.assertFalse(gndLutPins.isEmpty());

        Set<SitePinInst> invertedPins = RouterHelper.invertPossibleGndPinsToVccPins(design, gndLutPins);

        // If not flattening/uniquifying, there must be no inverted pins
        Assertions.assertEquals(!flatten || !uniquify, invertedPins.isEmpty());
    }

    @ParameterizedTest
    @CsvSource({
            "xcvp1002,XPIO_NIBBLE_SC_1_X9Y0/XPIO_IOBPAIR_5_RXOUT_M_PIN,CMT_MMCM_X11Y0/CMT_MMCM_TOP_0_CLKIN1_PIN",
            "xcvp1002,CMT_MMCM_X11Y0/CMT_MMCM_TOP_0_CLKOUT0_PIN,CLK_REBUF_BUFGS_HSR_CORE_X8Y0/CLK_BUFGCE_59_I_PIN",
    })
    public void testFindPathBetweenNodes(String partName, String sourceNodeName, String sinkNodeName) {
        Device device = Device.getDevice(partName);
        Node sourceNode = device.getNode(sourceNodeName);
        Node sinkNode = device.getNode(sinkNodeName);

        List<Node> path = RouterHelper.findPathBetweenNodes(sourceNode, sinkNode);
        Assertions.assertTrue(path.size() > 2);
    }
}
