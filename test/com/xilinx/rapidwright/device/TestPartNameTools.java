package com.xilinx.rapidwright.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestPartNameTools {
    @Test
    public void test() {
        Assertions.assertEquals(PartNameTools.getPart("xcvu3p-ffvc1517-2-i"), PartNameTools.getPart("xcVu3P-ffVC1517-2-i"));
    }
}