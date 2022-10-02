package com.xilinx.rapidwright.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestNode {
    @ParameterizedTest
    @CsvSource({
            "xcvu440-flga2892,LAGUNA_TILE_X161Y659/VCC_WIRE",
            "xcvu440-flga2892,LAGUNA_TILE_X161Y659/VCC_WIRE0",
    })
    public void testIsTiedToVcc(String deviceName, String nodeName) {
        Device device = Device.getDevice(deviceName);
        Node node = device.getNode(nodeName);

        Assertions.assertTrue(node.isTiedToVcc());
    }
}