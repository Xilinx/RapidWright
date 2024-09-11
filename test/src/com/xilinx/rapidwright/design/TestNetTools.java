package com.xilinx.rapidwright.design;

import java.util.HashSet;
import java.util.List;

import com.xilinx.rapidwright.support.RapidWrightDCP;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestNetTools {
    @ParameterizedTest
    @CsvSource({
            // UltraScale+
            "bnn.dcp",
            "hwct.dcp",
            "bug701.dcp",
            "hwct_pr1.dcp",
            "inout.dcp",
            "optical-flow.dcp",
            "design_with_backslash_2021.2.dcp",
            "design_with_backslash_2022.1.dcp",
            "design_with_backslash_2022.2.dcp",
            "gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp",
            // "picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp clk",          // The source cell bufgce_inst is not placed yet.
            "picoblaze_ooc_X10Y235.dcp",
            "picoblaze_ooc_X10Y235_2022_1.dcp",
            "picoblaze_ooc_X10Y235_unreadable_edif.dcp",
            "picoblaze_partial.dcp clk",
            "reduce_or_routed_7overlaps.dcp",
            "testCopyImplementation.dcp",
            
            // UltraScale
            // "microblazeAndILA_3pblocks.dcp sl_iport0_o_0[1] u_ila_0_clk_out1",
            // "microblazeAndILA_3pblocks_2024.1.dcp sl_iport0_o_0[1] u_ila_0_clk_out1",

            // Versal
            "noc_tutorial_routed.dcp",
            "picoblaze_2022.2.dcp",
            "versal_cout_hq2.dcp",
            // "two_clk_check_NetTools.dcp clk1_IBUF_BUFG clk2_IBUF_BUFG rst1 rst2",

            // Series-7
            // "bug226.dcp murax.apb3Router_1.io_mainClk",
            // "bug349.dcp CLK_BUFG_BOT_R_X60Y48_BUFGCTRL_X0Y0_O",
            // "bug635.dcp",
            // "bug709.dcp clk_IBUF_BUFG",
            // "ramb18.dcp",
            // "routethru_luts.dcp",
            // "routethru_pip.dcp",
    })
    public void testIsGlobalClockNet(String pathAndlobalClockNames) {
        String[] tmpList = pathAndlobalClockNames.split(" ");
        String path = tmpList[0];
        HashSet<String> globalClockNamesFromVivado = new HashSet<>();
        for (int i = 1; i < tmpList.length; i++) {
            globalClockNamesFromVivado.add(tmpList[i]);
        }
        HashSet<String> globalClockNamesFromRWRoute = new HashSet<>();
        Design design = RapidWrightDCP.loadDCP(path);
        System.out.println(design.getDevice().getSeries());

        for (Net net: design.getNets()) {
            if (NetTools.isGlobalClockNet(net)) {
                globalClockNamesFromRWRoute.add(net.getName());
            }
        }
        System.out.println("-----------Vivado------------");
        for (String nameInVivado: globalClockNamesFromVivado) {
            System.out.println(nameInVivado);
        }
        System.out.println("-----------RWRoute-----------");
        for (String nameInRWRoute: globalClockNamesFromRWRoute) {
            System.out.println(nameInRWRoute);
        }
        Assertions.assertTrue(globalClockNamesFromVivado.equals(globalClockNamesFromRWRoute));
    }
}
