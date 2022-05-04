package com.xilinx.rapidwright.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class TestPIP {

    @ParameterizedTest
    @ValueSource(strings = {"xczu3eg","xc7a12t"})
    public void testGetArbitraryPIP(String deviceName) {
        Device d = Device.getDevice(deviceName);
        
        for(Tile t : d.getAllTiles()) {
            for(PIP p : t.getPIPs()) {
                Node start = p.getStartNode();
                if(start == null) continue;
                Node end = p.getEndNode();
                if(end == null) continue;
                
                PIP pip = PIP.getArbitraryPIP(start, end);
                Assertions.assertEquals(start, pip.getStartNode());
                Assertions.assertEquals(end, pip.getEndNode());
            }
        }
    }
}
