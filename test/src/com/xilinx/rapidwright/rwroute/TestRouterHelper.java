package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestRouterHelper {
    @ParameterizedTest
    @CsvSource({
            "SLICE_X0Y0,COUT,null",
            "SLICE_X0Y299,COUT,null",
            "SLICE_X0Y0,A_O,CLEL_R_X0Y0/CLE_CLE_L_SITE_0_A_O"
    })
    public void testProjectOutputPinToINTNode(String siteName, String pinName, String nodeAsString) {
        Design design = new Design("design", "xcvu3p");
        SiteInst si = design.createSiteInst(siteName);
        SitePinInst spi = new SitePinInst(pinName, si);
        Assertions.assertEquals(nodeAsString, String.valueOf(RouterHelper.projectOutputPinToINTNode(spi)));
    }
}
