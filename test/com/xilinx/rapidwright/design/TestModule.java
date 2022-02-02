package com.xilinx.rapidwright.design;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.examples.AddSubGenerator;

public class TestModule {

    private static final String TEST_PART = Device.AWS_F1;
    
    private Module getAdderModule(String name, String siteOrigin) {
        Design design = new Design(name, TEST_PART);
        Device device = design.getDevice();
        AddSubGenerator.createAddSub(design, device.getSite(siteOrigin), 64, false, true, true);
        Module adderModule = new Module(design);        
        return adderModule;
    }
    
    @Test
    public void testModuleSLRRelocate() {
        Design top = new Design("top", TEST_PART);
        
        Module noSLRAdder = getAdderModule("adderNoSLR", "SLICE_X10Y10");
        Module slrAdder = getAdderModule("adderSLR", "SLICE_X10Y295");
        top.addModule(noSLRAdder);
        top.addModule(slrAdder);
        
        Assertions.assertTrue(noSLRAdder.isValidPlacement(noSLRAdder.getAnchor().getSite(), top));
        Assertions.assertFalse(noSLRAdder.isValidPlacement(slrAdder.getAnchor().getSite(), top));

        Assertions.assertTrue(slrAdder.isValidPlacement(slrAdder.getAnchor().getSite(), top));
        Assertions.assertFalse(slrAdder.isValidPlacement(noSLRAdder.getAnchor().getSite(), top));
    }
}
