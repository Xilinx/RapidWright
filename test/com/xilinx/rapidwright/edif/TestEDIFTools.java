package com.xilinx.rapidwright.edif;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFTools {
    @Test
    void testRename() {
        Assertions.assertEquals("emoji______", EDIFTools.makeNameEDIFCompatible("emoji_\uD83D\uDE0B\uD83C\uDF9B️"));
        Assertions.assertEquals("&_", EDIFTools.makeNameEDIFCompatible(" "));
    }
}
