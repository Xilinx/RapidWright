package com.xilinx.rapidwright.design;

import java.util.HashSet;
import java.util.List;

import com.xilinx.rapidwright.support.RapidWrightDCP;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestNetTools {
    @Test
    public void testIsClockNet() {
        // command: select_objects [get_nets -filter {Type == "GLOBAL_CLOCK"}]
        HashSet<String> clkNetNamesFromVivado = new HashSet<>(List.of(
            "clk1_IBUF_BUFG",
            "clk2_IBUF_BUFG",
            "rst1",
            "rst2"
        ));

        Design design = RapidWrightDCP.loadDCP("two_clk_check_NetTools.dcp");
        HashSet<String> clkNetNamesFromRWRoute = new HashSet<>();
        for (Net net: design.getNets()) {
            if (NetTools.isClockNet(net)) {
                clkNetNamesFromRWRoute.add(net.getName());
            }
        }

        Assertions.assertTrue(clkNetNamesFromVivado.equals(clkNetNamesFromRWRoute));
    }
}
