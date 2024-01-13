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
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Node;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class TestRouterHelper {
    @ParameterizedTest
    @CsvSource({
            "SLICE_X0Y0,COUT,null",
            "SLICE_X0Y299,COUT,null",
            "SLICE_X0Y0,A_O,CLEL_R_X0Y0/CLE_CLE_L_SITE_0_A_O",
            "GTYE4_CHANNEL_X0Y12,TXOUTCLK_INT,null",
            "IOB_X1Y95,I,INT_INTF_L_IO_X72Y109/LOGIC_OUTS_R23"
    })
    public void testProjectOutputPinToINTNode(String siteName, String pinName, String nodeAsString) {
        Design design = new Design("design", "xcvu3p");
        SiteInst si = design.createSiteInst(siteName);
        SitePinInst spi = new SitePinInst(pinName, si);
        Assertions.assertEquals(nodeAsString, String.valueOf(RouterHelper.projectOutputPinToINTNode(spi)));
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

    public static void invertVccLutPinsToGndPins(Design design, Set<SitePinInst> pins) {
        for (SitePinInst spi : pins) { 
            assert (spi.getNet() == design.getVccNet());
            SiteInst si = spi.getSiteInst();
            for (BELPin bp : spi.getSiteWireBELPins()) {
                if (bp.isSitePort() || bp.getName().charAt(0) != 'A')
                    continue;
                if (bp.getBEL().isLUT()) {
                    Cell lut = si.getCell(bp.getBEL());
                    if (lut != null) {
                        String eq = LUTTools.getLUTEquation(lut);
                        String logInput = lut.getLogicalPinMapping(bp.getName());
                        if (logInput != null) {
                            LUTTools.configureLUT(lut, eq.replace(logInput, "(~" + logInput + ")"));
                        } else {
                            // Doesn't look like this pin is used by this [65]LUT,
                            // could be used by the other [56]LUT
                        }
                    }
                }
            }
            spi.getNet().removePin(spi, true);
            design.getGndNet().addPin(spi, true);
        }
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

        // FIXME: Known broken --  https://github.com/Xilinx/RapidWright/issues/558
        Assertions.assertNotEquals(Objects.toString(intNode), "INT_INTF_L_CMT_X182Y90/LOGIC_OUTS_R19");
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
            invertVccLutPinsToGndPins(design, invertedPins);

            // Check that pin is back on the original VCC net
            Assertions.assertTrue(targetNet.getPins().isEmpty());
            Assertions.assertEquals("[IN SLICE_X0Y0.A6]", sourceNet.getPins().toString());
        }

        // Check that LUT equation is back to normal
        Assertions.assertEquals("O=!I0", LUTTools.getLUTEquation(cell));
    }

    @Test
    public void testInvertPossibleGndPinsToVccPinsLutInputNotUniquified() {
        // TODO
    }
}
