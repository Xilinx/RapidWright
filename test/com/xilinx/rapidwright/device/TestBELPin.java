package com.xilinx.rapidwright.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.util.Pair;

public class TestBELPin {

    private Pair<Site,BEL> getSiteBELUnderTest() {
        Device d = Device.getDevice("xczu7ev-ffvc1156-2-e");
        Site s = d.getSite("BITSLICE_RX_TX_X0Y305");
        BEL b = s.getBEL("RXTX_BITSLICE");
        return new Pair<>(s,b);
    }
    
    @Test
    public void testForNullSiteWire() {
        Pair<Site,BEL> siteBEL = getSiteBELUnderTest();
        BELPin pin = siteBEL.getSecond().getPin("RX_DIV2_CLK_Q");
        Assertions.assertEquals(pin.getSiteWireIndex(), -1);
        Assertions.assertNull(pin.getSiteWireName());
        Assertions.assertEquals(siteBEL.getFirst().getBELPins(pin.getSiteWireIndex()).length, 0); 
        Assertions.assertEquals(siteBEL.getFirst().getBELPins(pin.getSiteWireName()).length, 0);
        Assertions.assertEquals(pin.getSiteConns().size(), 0);
    }
    
    @Test
    public void testForNonNullSiteWire() {
        Pair<Site,BEL> siteBEL = getSiteBELUnderTest();
        BELPin pin = siteBEL.getSecond().getPin("TX_LOAD");
        Assertions.assertTrue(pin.getSiteWireIndex() > -1);
        Assertions.assertNotNull(pin.getSiteWireName());
        Assertions.assertTrue(siteBEL.getFirst().getBELPins(pin.getSiteWireIndex()).length > 0); 
        Assertions.assertTrue(siteBEL.getFirst().getBELPins(pin.getSiteWireName()).length > 0);
        Assertions.assertTrue(pin.getSiteConns().size() > 0);
    }
}
