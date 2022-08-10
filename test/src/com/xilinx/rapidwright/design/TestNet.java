package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.device.Device;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

public class TestNet {
    @Test
    void testSetPinsMultiSrc() {
        Design d = new Design("testSetPinsMultiSrc", Device.KCU105);
        SiteInst si = d.createSiteInst("SLICE_X32Y73");

        Net net = new Net("foo");
        List<SitePinInst> pins = Arrays.asList(
                new SitePinInst(true, "A_O", si),
                new SitePinInst(true, "AMUX", si)
        );

        Assertions.assertTrue(net.setPins(pins));
    }

    @Test
    void testSetPinsMultiSrcStatic() {
        Design d = new Design("testSetPinsMultiSrcStatic", Device.KCU105);
        SiteInst si = d.createSiteInst("SLICE_X32Y73");

        Net net = d.getVccNet();
        List<SitePinInst> pins = Arrays.asList(
                new SitePinInst(true, "A_O", si),
                new SitePinInst(true, "B_O", si),
                new SitePinInst(true, "C_O", si)
        );

        Assertions.assertTrue(net.setPins(pins));
    }
}
