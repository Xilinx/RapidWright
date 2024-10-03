/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestNet {
    @Test
    void testSetPinsMultiSrc() {
        Design d = new Design("testSetPinsMultiSrc", Device.KCU105);
        SiteInst si = d.createSiteInst("SLICE_X32Y73");

        Net net = new Net("foo");
        List<SitePinInst> pins = Arrays.asList(
                new SitePinInst("A_O", si),
                new SitePinInst("AMUX", si)
        );

        Assertions.assertTrue(net.setPins(pins));
        Assertions.assertEquals(pins.get(0), net.getSource());
        Assertions.assertEquals(pins.get(1), net.getAlternateSource());
    }

    @Test
    void testSetPinsMultiSrcStatic() {
        Design d = new Design("testSetPinsMultiSrcStatic", Device.KCU105);
        SiteInst si = d.createSiteInst("SLICE_X32Y73");

        Net net = d.getVccNet();
        List<SitePinInst> pins = Arrays.asList(
                new SitePinInst("A_O", si),
                new SitePinInst("B_O", si),
                new SitePinInst("C_O", si)
        );

        Assertions.assertTrue(net.setPins(pins));
        Assertions.assertNull(net.getSource());
        Assertions.assertNull(net.getAlternateSource());
    }

    @Test
    void testSetPinsAltSourceAsPrimary() {
        Design d = new Design("testSetPinsAltSourceAsPrimary", Device.KCU105);
        SiteInst si = d.createSiteInst("SLICE_X32Y73");

        SitePinInst spiA = new SitePinInst("A_O", si);
        SitePinInst spiAMUX = new SitePinInst("AMUX", si);

        Net net = d.createNet("net");
        net.addPin(spiA);
        net.addPin(spiAMUX);

        // Set the alternate source as the primary source now
        List<SitePinInst> pins = new ArrayList<>();
        pins.add(spiAMUX);

        Assertions.assertTrue(net.setPins(pins));
        Assertions.assertEquals(spiAMUX, net.getSource());
        Assertions.assertNull(net.getAlternateSource());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testRemovePrimarySourcePinPreserve(boolean preserveOtherRoutes) {
        Design design = new Design("test", Device.KCU105);

        // Net with two outputs (HMUX primary and H_O alternate) and two sinks (SRST_B2 & B2)
        Net net = TestDesignHelper.createTestNet(design, "net", new String[]{
                // SLICE_X65Y158/HMUX-> SLICE_X64Y158/SRST_B2
                "INT_X42Y158/INT.LOGIC_OUTS_E16->>INT_NODE_SINGLE_DOUBLE_46_INT_OUT",
                "INT_X42Y158/INT.INT_NODE_SINGLE_DOUBLE_46_INT_OUT->>INT_INT_SINGLE_51_INT_OUT",
                "INT_X42Y158/INT.INT_INT_SINGLE_51_INT_OUT->>INT_NODE_GLOBAL_3_OUT1",
                "INT_X42Y158/INT.INT_NODE_GLOBAL_3_OUT1->>CTRL_W_B7",
                // Adding dual output net
                // SLICE_X65Y158/H_O-> SLICE_X64Y158/B2
                "INT_X42Y158/INT.LOGIC_OUTS_E29->>INT_NODE_QUAD_LONG_5_INT_OUT",
                "INT_X42Y158/INT.INT_NODE_QUAD_LONG_5_INT_OUT->>NN16_BEG3",
                "INT_X42Y174/INT.NN16_END3->>INT_NODE_QUAD_LONG_53_INT_OUT",
                "INT_X42Y174/INT.INT_NODE_QUAD_LONG_53_INT_OUT->>WW4_BEG14",
                "INT_X40Y174/INT.WW4_END14->>INT_NODE_QUAD_LONG_117_INT_OUT",
                "INT_X40Y174/INT.INT_NODE_QUAD_LONG_117_INT_OUT->>SS16_BEG3",
                "INT_X40Y158/INT.SS16_END3->>INT_NODE_QUAD_LONG_84_INT_OUT",
                "INT_X40Y158/INT.INT_NODE_QUAD_LONG_84_INT_OUT->>EE4_BEG12",
                "INT_X42Y158/INT.EE4_END12->>INT_NODE_GLOBAL_8_OUT1",
                "INT_X42Y158/INT.INT_NODE_GLOBAL_8_OUT1->>INT_NODE_IMUX_61_INT_OUT",
                "INT_X42Y158/INT.INT_NODE_IMUX_61_INT_OUT->>IMUX_W0",
        });

        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X65Y158"));
        SitePinInst src = net.createPin("HMUX", si);
        SitePinInst altSrc = net.createPin("H_O", si);
        Assertions.assertNotNull(net.getAlternateSource());
        Assertions.assertTrue(net.getAlternateSource().equals(altSrc));

        si = design.createSiteInst(design.getDevice().getSite("SLICE_X64Y158"));
        SitePinInst snk = net.createPin("SRST_B2", si);
        snk.setRouted(true);
        SitePinInst altSnk = net.createPin("B2", si);
        altSnk.setRouted(true);

        // Remove the primary source pin
        net.removePin(src, preserveOtherRoutes);
        // Check that alternate source has been promoted to primary
        Assertions.assertEquals(net.getSource(), altSrc);
        Assertions.assertNull(net.getAlternateSource());
        Assertions.assertFalse(snk.isRouted());
        if (preserveOtherRoutes) {
            Assertions.assertEquals(11, net.getPIPs().size());
            Assertions.assertTrue(altSnk.isRouted());
        } else {
            Assertions.assertEquals(0, net.getPIPs().size());
            Assertions.assertFalse(altSnk.isRouted());
        }
    }

    @Test
    public void testRemoveAlternateSourcePin() {
        Design design = new Design("test", Device.KCU105);
        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X65Y158"));
        Net net = design.createNet("net");
        net.createPin("HMUX", si);
        SitePinInst altSrc = net.createPin("H_O", si);
        Assertions.assertNotNull(net.getAlternateSource());
        Assertions.assertTrue(net.getAlternateSource().equals(altSrc));

        net.removePin(altSrc);
        Assertions.assertNull(net.getAlternateSource());
    }

    @Test
    public void testRemovePinOnStaticNet() {
        Design design = new Design("test", Device.KCU105);
        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X0Y0"));
        Net gndNet = design.getGndNet();
        SitePinInst a6 = gndNet.createPin("A6", si);
        SitePinInst b6 = gndNet.createPin("B6", si);
        TestDesignHelper.addPIPs(gndNet, new String[]{
                "INT_X0Y0/INT.LOGIC_OUTS_E29->>INT_NODE_SINGLE_DOUBLE_101_INT_OUT",
                "INT_X0Y0/INT.INT_NODE_SINGLE_DOUBLE_101_INT_OUT->>SS1_E_BEG7",
                "INT_X0Y0/INT.INT_NODE_IMUX_64_INT_OUT->>IMUX_E16",
                "INT_X0Y0/INT.NN1_E_END0->>INT_NODE_IMUX_64_INT_OUT",
                "INT_X0Y0/INT.INT_NODE_IMUX_64_INT_OUT->>IMUX_E17"
        });
        gndNet.removePin(a6, true);
        Assertions.assertEquals(gndNet.getPIPs().size(), 4);
        gndNet.removePin(b6, true);
        Assertions.assertEquals(gndNet.getPIPs().size(), 0);
    }

    @Test
    public void testRemovePinSiteInsts() {
        Design d = new Design("testRemovePinSiteInsts", Device.AWS_F1);
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        Net net = d.createNet("net");
        SitePinInst spi1 = net.createPin("A_O", si);
        SitePinInst spi2 = net.createPin("AMUX", si);

        Assertions.assertIterableEquals(net.getSiteInsts(), Arrays.asList(si));

        // Remove first of two pins
        net.removePin(spi1);

        Assertions.assertIterableEquals(net.getSiteInsts(), Arrays.asList(si));

        // Remove second of two pins
        net.removePin(spi2);

        Assertions.assertTrue(net.getSiteInsts().isEmpty());
    }

    @Test
    public void testGetLogicalHierNetDetachedNetlist() {
        String dcpPath = RapidWrightDCP.getString("bnn.dcp");
        Design design = Design.readCheckpoint(dcpPath);
        design.detachNetlist();

        String[] hierPortNets = new String[]{
                "dmem_mode_V[0]",
                "n_inputs_V[13]",
                "n_inputs_V[1]",
                "n_inputs_V[3]",
                "n_inputs_V[5]",
                "n_inputs_V[7]",
                "n_inputs_V[9]",
        };
        for (String name : hierPortNets) {
            Net net = design.getNet(name);
            Assertions.assertNotNull(net);
            Assertions.assertNull(net.getLogicalHierNet());
        }
    }

    @Test
    public void testCreatePinDuplicate() {
        Design d = new Design("testCreatePinDuplicate", Device.AWS_F1);
        Net net = d.getVccNet();
        SiteInst si = d.createSiteInst("SLICE_X0Y0");
        SitePinInst spi = net.createPin("SRST1", si);
        Assertions.assertEquals(net, spi.getNet());
        net.createPin("SRST2", si);
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> net.createPin("SRST1", si));
        Assertions.assertEquals("ERROR: SiteInst placed at SLICE_X0Y0 already has a pin named SRST1", ex.getMessage());

        // Remove from net
        Assertions.assertTrue(net.removePin(spi));
        Assertions.assertNull(spi.getNet());

        // Also remove from site inst
        Assertions.assertTrue(si.removePin(spi));
        SitePinInst newSpi = net.createPin("SRST1", si);
        Assertions.assertNotSame(newSpi, spi);
        Assertions.assertEquals(net, newSpi.getNet());
    }

    @Test
    public void testConnectPinDuplicate() {
        Design d = new Design("testConnectPinDuplicate", Device.KCU105);
        Cell cell = d.createAndPlaceCell("ff", Unisim.FDRE, "SLICE_X0Y0/AFF");
        Net net = d.createNet("net");

        SitePinInst spi = net.connect(cell, "CE");
        Assertions.assertNotNull(spi);
        Assertions.assertEquals(net, spi.getNet());

        // No error if connecting same net, returns same SPI
        Assertions.assertEquals(spi, net.connect(cell, "CE"));
        Assertions.assertEquals(net, spi.getNet());

        // Error if already connected
        Net otherNet = d.createNet("other_net");
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () -> otherNet.connect(cell, "CE"));
        Assertions.assertEquals("ERROR: SitePinInst 'IN SLICE_X0Y0.CKEN_B1' is already connected to net 'net'.  Disconnect it first from that net before calling Net.connect()", ex.getMessage());

        // Remove from net
        Assertions.assertTrue(net.removePin(spi));
        Assertions.assertNull(spi.getNet());
        Assertions.assertEquals(spi, otherNet.connect(cell, "CE"));
        Assertions.assertEquals(otherNet, spi.getNet());
    }
  
    @Test
    public void testCreatePinInvalid() {
        Design d = new Design("top", "xc7a200t");
        SiteInst si = d.createSiteInst(d.getDevice().getSite("RAMB36_X8Y14"));
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class, () ->
                d.getGndNet().createPin("RSTRAMARSTRAML", si)
        );
        Assertions.assertEquals("ERROR: Couldn't find pin RSTRAMARSTRAML on site type RAMBFIFO36E1",
                ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"H_O", "HMUX"})
    public void testAddPinSource(String altPinName) {
        Design design = new Design("top", Device.AWS_F1);
        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X0Y0"));
        Net net = design.createNet("net");
        String pinName = "H_O";
        boolean samePin = altPinName.equals(pinName);
        SitePinInst spi1 = new SitePinInst(pinName, si);
        Assertions.assertTrue(net.addPin(spi1));
        SitePinInst spi2 = new SitePinInst(altPinName, si);
        Assertions.assertEquals(samePin, spi1.equals(spi2));
        Assertions.assertEquals(!samePin, net.addPin(spi2));

        Assertions.assertSame(spi1, net.getSource());
        if (samePin) {
            Assertions.assertNull(net.getAlternateSource());
        } else {
            Assertions.assertSame(spi2, net.getAlternateSource());
        }
    }

    @Test
    public void testSetSourceDuplicate() {
        Design design = new Design("top", Device.AWS_F1);
        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X0Y0"));
        Net net = design.createNet("net");
        String pinName = "H_O";
        SitePinInst spi1 = new SitePinInst(pinName, si);
        Assertions.assertTrue(net.addPin(spi1));
        String altPinName = "HMUX";
        SitePinInst spi2 = new SitePinInst(altPinName, si);
        Assertions.assertNotEquals(spi1, spi2);

        net.setSource(spi1);
        Assertions.assertSame(spi1, net.getSource());
        net.setAlternateSource(spi2);
        Assertions.assertSame(spi2, net.getAlternateSource());

        SitePinInst spi3 = new SitePinInst(altPinName, si);
        Assertions.assertEquals(spi2, spi3);

        Assertions.assertThrows(RuntimeException.class, () -> net.setSource(spi3));
    }

    @Test
    public void testSetAlternateSourceDuplicate() {
        Design design = new Design("top", Device.AWS_F1);
        SiteInst si = design.createSiteInst(design.getDevice().getSite("SLICE_X0Y0"));
        Net net = design.createNet("net");
        String pinName = "H_O";
        SitePinInst spi1 = new SitePinInst(pinName, si);
        Assertions.assertTrue(net.addPin(spi1));
        SitePinInst spi2 = new SitePinInst(pinName, si);
        Assertions.assertEquals(spi1, spi2);

        net.setSource(spi1);
        Assertions.assertSame(spi1, net.getSource());

        Assertions.assertThrows(RuntimeException.class, () -> net.setAlternateSource(spi2));
    }

    @Test
    public void testInternalConnectNet() {
        Design design = new Design("test", Device.KCU105);
        Cell lut0 = design.createAndPlaceCell("lut0", Unisim.LUT5, "SLICE_X0Y0/C6LUT");
        Cell f7mux0 = design.createAndPlaceCell("f7mux0", Unisim.MUXF7, "SLICE_X0Y0/F7MUX_CD");
        Net net0 = design.createNet("O");
        net0.connect(lut0, "O");
        net0.connect(f7mux0, "I1");

        Assertions.assertEquals("I1", net0.getLogicalNet().getPortInst(f7mux0.getEDIFCellInst(), "I1").getName());
    }

    @Test
    public void testSingleClockNetSource() {
        Design design = RapidWrightDCP.loadDCP("bug349.dcp");
        Net net = design.getNet("CLK_BUFG_BOT_R_X60Y48_BUFGCTRL_X0Y0_O");
        SitePinInst bufg_o = design.getSiteInstFromSiteName("BUFGCTRL_X0Y0").getSitePinInst("O");
        Assertions.assertSame(bufg_o, net.getSource());
        Assertions.assertNull(net.getAlternateSource());
    }
}
