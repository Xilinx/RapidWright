package com.xilinx.rapidwright.router;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Wire;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;

public class TestRouteNode {
    @ParameterizedTest
    @CsvSource({
            // Connecting east:
            //   node RCLK_DSP_INTF_CLKBUF_L_X36Y629/CLK_HDISTR_R8 ->
            //   node RCLK_DSP_INTF_CLKBUF_L_X19Y629/CLK_HDISTR_R8
            "RCLK_DSP_INTF_CLKBUF_L_X36Y629/CLK_HDISTR_R8,RCLK_DSP_INTF_CLKBUF_L_X36Y629/CLK_HDISTR_L8,RCLK_DSP_INTF_CLKBUF_L_X36Y629/RCLK_DSP_INTF_CLKBUF_L.CLK_HDISTR_L8<<->>CLK_HDISTR_R8,true",
            // Connecting west:
            //   node RCLK_DSP_INTF_CLKBUF_L_X36Y629/CLK_HDISTR_R8 ->
            //   node RCLK_DSP_INTF_CLKBUF_L_X59Y629/CLK_HDISTR_R8
            "RCLK_DSP_INTF_CLKBUF_L_X59Y629/CLK_HDISTR_L8,RCLK_DSP_INTF_CLKBUF_L_X59Y629/CLK_HDISTR_R8,RCLK_DSP_INTF_CLKBUF_L_X59Y629/RCLK_DSP_INTF_CLKBUF_L.CLK_HDISTR_L8<<->>CLK_HDISTR_R8,false",
    })
    public void testGetPIPsBackToSource(String srcWireName, String sinkWireName, String pipAsString, boolean isReversed) {
        Device device = Device.getDevice("xcvu13p");
        Wire srcWire = device.getWire(srcWireName);
        RouteNode src = new RouteNode(srcWire.getTile(), srcWire.getWireIndex());
        Assertions.assertNotNull(src);

        RouteNode sink = new RouteNode(device.getWire(sinkWireName), src);
        Assertions.assertNotNull(sink);
        sink.setParent(src);
        ArrayList<PIP> pips = sink.getPIPsBackToSource();
        Assertions.assertEquals(1, pips.size());
        Assertions.assertEquals(pipAsString, pips.get(0).toString());
        Assertions.assertEquals(isReversed, pips.get(0).isReversed());
    }
}
