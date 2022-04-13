package com.xilinx.rapidwright.design;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;

public class TestSiteInst {

    @ParameterizedTest
    @ValueSource(strings = {Device.KCU105, Device.PYNQ_Z1})
    public void testRouteLUTRouteThru(String deviceName) {
        Design d = new Design("testLUTRT", deviceName);
        
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));
        
        for(char letter : LUTTools.lutLetters) {
            BEL bel = si.getBEL(letter + "FF");
            d.createAndPlaceCell(d.getTopEDIFCell(), letter+"FF_inst", Unisim.FDRE, 
                    si.getSiteName() + "/" + bel.getName());
            Net net = d.createNet(Character.toString(letter));
            BELPin src = si.getSite().getBELPin(letter + "4");
            BELPin snk = si.getBEL(letter + "FF").getPin("D");
            Assertions.assertTrue(si.routeIntraSiteNet(net, src, snk));
            if(d.getDevice().getSeries() == Series.Series7 && letter == 'D') break;
        }
    }
}
