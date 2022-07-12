package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.device.Device;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSitePinInst {
    // https://github.com/Xilinx/RapidWright/issues/454
    @Test
    public void testSetSameSiteInst() {
        Design d = new Design("Test1", Device.PYNQ_Z1);
        Device dev = d.getDevice();
        Net net0 = d.createNet("net0");
        net0.addPIP(dev.getPIP("CLBLM_L_X2Y0/CLBLM_L.CLBLM_CLK1->CLBLM_M_CLK"));

        Assertions.assertEquals(1, net0.getPIPs().size());

        SiteInst siteInst = d.createSiteInst("SLICE_X0Y0");
        SitePinInst spi = net0.createPin("CLK", siteInst);
        spi.setSiteInst(siteInst);

        Assertions.assertEquals(1, net0.getPIPs().size());
    }
}
