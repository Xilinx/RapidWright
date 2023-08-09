package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestRouterHelper {
    @ParameterizedTest
    @ValueSource(strings = {"SLICE_X0Y0", "SLICE_X0Y299"})
    public void testProjectOutputPinToINTNodeCOUT(String siteName) {
        Design design = new Design("design", "xcvu3p");
        SiteInst si = design.createSiteInst(siteName);
        SitePinInst spi = new SitePinInst("COUT", si);
        Assertions.assertNull(RouterHelper.projectOutputPinToINTNode(spi));
    }
}
