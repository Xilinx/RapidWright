package com.xilinx.rapidwright.design;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestSiteInst {

    @Test
    public void testRouteIntraSiteNet() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp"));
        SiteInst si = d.getSiteInstFromSiteName("SLICE_X15Y237");
        Net net = d.createNet("dummy_test_net");
        d.createAndPlaceCell("dummy_flop", Unisim.FDRE, "SLICE_X15Y237/EFF");
        
        BELPin src = si.getBEL("E3").getPin("E3");
        BELPin snk = si.getBEL("EFF").getPin("D");
        Assertions.assertTrue(si.routeIntraSiteNet(net, src, snk));
        
        Assertions.assertEquals(si.getSiteWiresFromNet(net).size(), 3);
        
        Assertions.assertTrue(si.unrouteIntraSiteNet(src, snk));
        
        Assertions.assertEquals(si.getSiteWiresFromNet(net).size(), 0);
    }
}
