package com.xilinx.rapidwright.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestPartNameTools {
    @Test
    public void testGetPartCase() {
        Assertions.assertEquals(PartNameTools.getPart("xcvu3p-ffvc1517-2-i"), PartNameTools.getPart("xcVu3P-ffVC1517-2-i"));
    }

    @Test
    public void testXazu2egResources() {
        Part p = PartNameTools.getPart("xazu2eg-sfvc784-1");
        Assertions.assertEquals(p.getDsp(), 240);
        Assertions.assertEquals(p.getBlockRams(), 150);
        Assertions.assertEquals(p.getUltraRams(), 0);
        Assertions.assertEquals(p.getLutElements(), 47232);
        Assertions.assertEquals(p.getFlipflops(), 94464);
    }
}
