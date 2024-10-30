/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, AMD Research and Advanced Development.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.xilinx.rapidwright.design;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestNetTools {
    /**
     * Tests the method NetTools.isGlobalClock(Net net).
     * For each DCP file, Vivado uses the TCL command 
     *      get_nets -hier -parent_net -filter { TYPE == "GLOBAL_CLOCK" } 
     * to retrieve all nets of type GLOBAL_CLOCK. 
     * The method NetTools.isGlobalClock(Net net) returns true for these nets and false for others.
     * 
     * All Versal/Ultrascale+/Series-7 designs under the RapidWrightDCP path are available to be checked, except for the following:
     *      picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp    The source cell bufgce_inst of this design is not placed yet.
     *      picoblaze_ooc_X10Y235_unreadable_edif.dcp       This design would call vivado to generate an readable EDIF file.
     * 
     * @param pathAndlobalClockNames Use ' ' as the delimiter to split this string, where the first element is the path to the DCP file, 
     *                               and the remaining elements are the names of the global clock nets reported by Vivado.
     */
    @ParameterizedTest
    @CsvSource({
            // UltraScale+
            // "bnn.dcp",
            // "hwct.dcp",
            // "bug701.dcp",
            // "hwct_pr1.dcp",
            // "inout.dcp",
            // "optical-flow.dcp",
            // "design_with_backslash_2021.2.dcp",
            // "design_with_backslash_2022.1.dcp",
            // "design_with_backslash_2022.2.dcp",
            // "gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp",
            // "picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp clk",          // The source cell bufgce_inst is not placed yet.
            // "picoblaze_ooc_X10Y235.dcp",
            // "picoblaze_ooc_X10Y235_2022_1.dcp",
            // "picoblaze_ooc_X10Y235_unreadable_edif.dcp",                 // This case would call vivado to generate an readable EDIF file.
            "picoblaze_partial.dcp clk",
            // "reduce_or_routed_7overlaps.dcp",
            // "testCopyImplementation.dcp",
            
            // UltraScale
            // "microblazeAndILA_3pblocks.dcp base_mb_i/clk_wiz_1/inst/clk_out1 base_mb_i/clk_wiz_1/inst/clkfbout_buf_base_mb_clk_wiz_1_0 base_mb_i/mdm_1/U0/No_Dbg_Reg_Access.BUFG_DRCK/Dbg_Clk_31 dbg_hub/inst/BSCANID.u_xsdbm_id/itck_i",
            "microblazeAndILA_3pblocks_2024.1.dcp base_mb_i/clk_wiz_1/inst/clk_out1 base_mb_i/clk_wiz_1/inst/clkfbout_buf_base_mb_clk_wiz_1_0 base_mb_i/mdm_1/U0/No_Dbg_Reg_Access.BUFG_DRCK/Dbg_Clk_31 dbg_hub/inst/BSCANID.u_xsdbm_id/itck_i",

            // Versal
            // "noc_tutorial_routed.dcp",
            // "picoblaze_2022.2.dcp",
            // "versal_cout_hq2.dcp",
            "two_clk_check_NetTools.dcp clk1_IBUF_BUFG clk2_IBUF_BUFG rst1 rst2",

            // Series-7
            "bug226.dcp murax.apb3Router_1.io_mainClk",
            // "bug349.dcp CLK_BUFG_BOT_R_X60Y48_BUFGCTRL_X0Y0_O",
            // "bug635.dcp",
            // "bug709.dcp clk_IBUF_BUFG",
            // "ramb18.dcp",
            // "routethru_luts.dcp",
            // "routethru_pip.dcp",
    })
    public void testisGlobalClock(String pathAndlobalClockNames) {
        String[] tmpList = pathAndlobalClockNames.split(" ");
        String path = tmpList[0];
        HashSet<String> globalClockNamesFromVivado = new HashSet<>();
        for (int i = 1; i < tmpList.length; i++) {
            globalClockNamesFromVivado.add(tmpList[i]);
        }
        HashSet<String> globalClockNamesFromNetTools = new HashSet<>();
        Design design = RapidWrightDCP.loadDCP(path);
        System.out.println("Series: " + design.getDevice().getSeries());

        for (Net net: design.getNets()) {
            if (NetTools.isGlobalClock(net)) {
                globalClockNamesFromNetTools.add(net.getName());
            }
        }
        System.out.println("-----------Vivado------------");
        for (String clkName: globalClockNamesFromVivado) {
            System.out.println(clkName);
        }
        System.out.println("-----------NetTools-----------");
        for (String clkName: globalClockNamesFromNetTools) {
            System.out.println(clkName);
        }
        Assertions.assertEquals(globalClockNamesFromVivado,globalClockNamesFromNetTools);
    }

    @Test
    public void testGetRouteTrees() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        Net net = design.getNet("processor/data_path_loop[0].output_data.sy_kk_mux_lut/O5");
        List<NetTools.NodeTree> trees = NetTools.getRouteTrees(net);
        Assertions.assertEquals(1, trees.size());

        // Taken directly from Vivado's report_route_status
        String[] expected = new String(
                "    [{       CLEL_R_X10Y236/CLE_CLE_L_SITE_0_AMUX (65535) \n" +
                        "     {       INT_X10Y236/INT_NODE_SDQ_27_INT_OUT1 ( 3) INT_X10Y236/INT.LOGIC_OUTS_E21->INT_NODE_SDQ_27_INT_OUT1\n" +
                        "                     INT_X10Y236/SS1_E_BEG5 ( 0) INT_X10Y236/INT.INT_NODE_SDQ_27_INT_OUT1->>SS1_E_BEG5\n" +
                        "     {       INT_X10Y235/INT_NODE_IMUX_16_INT_OUT0 ( 5) INT_X10Y235/INT.SS1_E_END5->>INT_NODE_IMUX_16_INT_OUT0\n" +
                        "                     INT_X10Y235/BYPASS_E10 ( 3) INT_X10Y235/INT.INT_NODE_IMUX_16_INT_OUT0->>BYPASS_E10\n" +
                        "     {       INT_X10Y235/INT_NODE_IMUX_9_INT_OUT1 ( 0) INT_X10Y235/INT.BYPASS_E10->>INT_NODE_IMUX_9_INT_OUT1\n" +
                        "         }             INT_X10Y235/IMUX_E23 ( 6) INT_X10Y235/INT.INT_NODE_IMUX_9_INT_OUT1->>IMUX_E23\n" +
                        "             INT_X10Y235/INT_NODE_IMUX_8_INT_OUT1 ( 0) INT_X10Y235/INT.BYPASS_E10->>INT_NODE_IMUX_8_INT_OUT1\n" +
                        "         }             INT_X10Y235/IMUX_E26 ( 6) INT_X10Y235/INT.INT_NODE_IMUX_8_INT_OUT1->>IMUX_E26\n" +
                        "     {       INT_X10Y235/INT_NODE_SDQ_28_INT_OUT0 ( 2) INT_X10Y235/INT.SS1_E_END5->INT_NODE_SDQ_28_INT_OUT0\n" +
                        "                     INT_X10Y235/EE1_E_BEG4 ( 3) INT_X10Y235/INT.INT_NODE_SDQ_28_INT_OUT0->>EE1_E_BEG4\n" +
                        "             INT_X11Y235/INT_NODE_IMUX_48_INT_OUT0 ( 1) INT_X11Y235/INT.EE1_E_END4->>INT_NODE_IMUX_48_INT_OUT0\n" +
                        "         }             INT_X11Y235/IMUX_W37 ( 3) INT_X11Y235/INT.INT_NODE_IMUX_48_INT_OUT0->>IMUX_W37\n" +
                        "             INT_X10Y235/INT_NODE_IMUX_16_INT_OUT1 ( 5) INT_X10Y235/INT.SS1_E_END5->>INT_NODE_IMUX_16_INT_OUT1\n" +
                        "     {   }             INT_X10Y235/IMUX_E30 ( 3) INT_X10Y235/INT.INT_NODE_IMUX_16_INT_OUT1->>IMUX_E30\n" +
                        "         }             INT_X10Y235/IMUX_E31 ( 3) INT_X10Y235/INT.INT_NODE_IMUX_16_INT_OUT1->>IMUX_E31\n" +
                        "             INT_X10Y236/INT_NODE_SDQ_29_INT_OUT1 ( 1) INT_X10Y236/INT.LOGIC_OUTS_E21->INT_NODE_SDQ_29_INT_OUT1\n" +
                        "     {               INT_X10Y236/NN2_E_BEG5 ( 0) INT_X10Y236/INT.INT_NODE_SDQ_29_INT_OUT1->>NN2_E_BEG5\n" +
                        "     {       INT_X10Y238/INT_NODE_IMUX_18_INT_OUT1 ( 4) INT_X10Y238/INT.NN2_E_END5->>INT_NODE_IMUX_18_INT_OUT1\n" +
                        "         }             INT_X10Y238/IMUX_E10 ( 4) INT_X10Y238/INT.INT_NODE_IMUX_18_INT_OUT1->>IMUX_E10\n" +
                        "     {       INT_X10Y238/INT_NODE_SDQ_30_INT_OUT1 ( 2) INT_X10Y238/INT.NN2_E_END5->INT_NODE_SDQ_30_INT_OUT1\n" +
                        "                     INT_X10Y238/EE1_E_BEG5 ( 0) INT_X10Y238/INT.INT_NODE_SDQ_30_INT_OUT1->>EE1_E_BEG5\n" +
                        "     {       INT_X11Y238/INT_NODE_SDQ_79_INT_OUT0 ( 0) INT_X11Y238/INT.EE1_E_END5->INT_NODE_SDQ_79_INT_OUT0\n" +
                        "                     INT_X11Y238/SS1_W_BEG5 ( 2) INT_X11Y238/INT.INT_NODE_SDQ_79_INT_OUT0->>SS1_W_BEG5\n" +
                        "             INT_X11Y237/INT_NODE_IMUX_49_INT_OUT1 ( 4) INT_X11Y237/INT.SS1_W_END5->>INT_NODE_IMUX_49_INT_OUT1\n" +
                        "                      INT_X11Y237/BYPASS_W8 ( 5) INT_X11Y237/INT.INT_NODE_IMUX_49_INT_OUT1->>BYPASS_W8\n" +
                        "     {       INT_X11Y237/INT_NODE_IMUX_36_INT_OUT0 ( 0) INT_X11Y237/INT.BYPASS_W8->>INT_NODE_IMUX_36_INT_OUT0\n" +
                        "         }              INT_X11Y237/IMUX_W6 ( 2) INT_X11Y237/INT.INT_NODE_IMUX_36_INT_OUT0->>IMUX_W6\n" +
                        "     {       INT_X11Y237/INT_NODE_IMUX_37_INT_OUT0 ( 0) INT_X11Y237/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8\n" +
                        "         }              INT_X11Y237/IMUX_W7 ( 1) INT_X11Y237/INT.INT_NODE_IMUX_37_INT_OUT0->>IMUX_W7\n" +
                        "             INT_X11Y237/INT_NODE_IMUX_36_INT_OUT1 ( 0) INT_X11Y237/INT.BYPASS_W8->>INT_NODE_IMUX_36_INT_OUT1\n" +
                        "     {   }              INT_X11Y237/IMUX_W2 ( 4) INT_X11Y237/INT.INT_NODE_IMUX_36_INT_OUT1->>IMUX_W2\n" +
                        "     {   }              INT_X11Y237/IMUX_W3 ( 2) INT_X11Y237/INT.INT_NODE_IMUX_36_INT_OUT1->>IMUX_W3\n" +
                        "                 INT_X11Y237/BOUNCE_W_2_FT1 ( 4) INT_X11Y237/INT.INT_NODE_IMUX_36_INT_OUT1->>BOUNCE_W_2_FT1\n" +
                        "                 INT_X11Y236/INODE_W_58_FT0 ( 0) INT_X11Y236/INT.BOUNCE_W_BLS_2_FT0->>INODE_W_58_FT0\n" +
                        "     {   }              INT_X11Y237/IMUX_W0 ( 4) INT_X11Y237/INT.INODE_W_BLN_58_FT1->>IMUX_W0\n" +
                        "         }              INT_X11Y237/IMUX_W1 ( 2) INT_X11Y237/INT.INODE_W_BLN_58_FT1->>IMUX_W1\n" +
                        "             INT_X11Y238/INT_NODE_IMUX_50_INT_OUT1 ( 1) INT_X11Y238/INT.EE1_E_END5->>INT_NODE_IMUX_50_INT_OUT1\n" +
                        "     {               INT_X11Y238/BYPASS_W10 ( 4) INT_X11Y238/INT.INT_NODE_IMUX_50_INT_OUT1->>BYPASS_W10\n" +
                        "             INT_X11Y238/INT_NODE_IMUX_40_INT_OUT1 ( 0) INT_X11Y238/INT.BYPASS_W10->>INT_NODE_IMUX_40_INT_OUT1\n" +
                        "         }             INT_X11Y238/IMUX_W26 ( 4) INT_X11Y238/INT.INT_NODE_IMUX_40_INT_OUT1->>IMUX_W26\n" +
                        "         }             INT_X11Y238/IMUX_W36 ( 4) INT_X11Y238/INT.INT_NODE_IMUX_50_INT_OUT1->>IMUX_W36\n" +
                        "             INT_X10Y238/INT_NODE_SDQ_30_INT_OUT0 ( 3) INT_X10Y238/INT.NN2_E_END5->INT_NODE_SDQ_30_INT_OUT0\n" +
                        "                     INT_X10Y238/NN1_E_BEG5 ( 2) INT_X10Y238/INT.INT_NODE_SDQ_30_INT_OUT0->>NN1_E_BEG5\n" +
                        "             INT_X10Y239/INT_NODE_SDQ_30_INT_OUT0 ( 2) INT_X10Y239/INT.NN1_E_END5->INT_NODE_SDQ_30_INT_OUT0\n" +
                        "                     INT_X10Y239/EE2_E_BEG5 ( 3) INT_X10Y239/INT.INT_NODE_SDQ_30_INT_OUT0->>EE2_E_BEG5\n" +
                        "             INT_X11Y239/INT_NODE_SDQ_27_INT_OUT0 ( 0) INT_X11Y239/INT.EE2_E_END5->INT_NODE_SDQ_27_INT_OUT0\n" +
                        "             INT_X11Y239/INT_INT_SDQ_74_INT_OUT0 ( 2) INT_X11Y239/INT.INT_NODE_SDQ_27_INT_OUT0->>INT_INT_SDQ_74_INT_OUT0\n" +
                        "             INT_X11Y239/INT_NODE_SDQ_73_INT_OUT0 ( 0) INT_X11Y239/INT.INT_INT_SDQ_74_INT_OUT0->INT_NODE_SDQ_73_INT_OUT0\n" +
                        "                     INT_X11Y239/SS1_W_BEG4 ( 3) INT_X11Y239/INT.INT_NODE_SDQ_73_INT_OUT0->>SS1_W_BEG4\n" +
                        "             INT_X11Y238/INT_NODE_SDQ_72_INT_OUT0 ( 2) INT_X11Y238/INT.SS1_W_END4->INT_NODE_SDQ_72_INT_OUT0\n" +
                        "                     INT_X11Y238/SS1_W_BEG4 ( 2) INT_X11Y238/INT.INT_NODE_SDQ_72_INT_OUT0->>SS1_W_BEG4\n" +
                        "             INT_X11Y237/INT_NODE_IMUX_46_INT_OUT0 ( 4) INT_X11Y237/INT.SS1_W_END4->>INT_NODE_IMUX_46_INT_OUT0\n" +
                        "     {   }             INT_X11Y237/IMUX_W10 ( 2) INT_X11Y237/INT.INT_NODE_IMUX_46_INT_OUT0->>IMUX_W10\n" +
                        "         }             INT_X11Y237/IMUX_W11 ( 2) INT_X11Y237/INT.INT_NODE_IMUX_46_INT_OUT0->>IMUX_W11\n" +
                        "             INT_X10Y236/INT_INT_SDQ_34_INT_OUT1 ( 0) INT_X10Y236/INT.INT_NODE_SDQ_29_INT_OUT1->>INT_INT_SDQ_34_INT_OUT1\n" +
                        "             INT_X10Y236/INT_NODE_SDQ_82_INT_OUT0 ( 0) INT_X10Y236/INT.INT_INT_SDQ_34_INT_OUT1->INT_NODE_SDQ_82_INT_OUT0\n" +
                        "             INT_X10Y236/INT_INT_SDQ_6_INT_OUT0 ( 2) INT_X10Y236/INT.INT_NODE_SDQ_82_INT_OUT0->>INT_INT_SDQ_6_INT_OUT0\n" +
                        "     {       INT_X10Y236/INT_NODE_GLOBAL_6_INT_OUT1 ( 4) INT_X10Y236/INT.INT_INT_SDQ_6_INT_OUT0->>INT_NODE_GLOBAL_6_INT_OUT1\n" +
                        "             INT_X10Y236/INT_NODE_IMUX_9_INT_OUT0 ( 2) INT_X10Y236/INT.INT_NODE_GLOBAL_6_INT_OUT1->>INT_NODE_IMUX_9_INT_OUT0\n" +
                        "         }             INT_X10Y236/IMUX_E30 ( 6) INT_X10Y236/INT.INT_NODE_IMUX_9_INT_OUT0->>IMUX_E30\n" +
                        "             INT_X10Y236/INT_NODE_IMUX_18_INT_OUT1 ( 1) INT_X10Y236/INT.INT_INT_SDQ_6_INT_OUT0->>INT_NODE_IMUX_18_INT_OUT1\n" +
                        "         }]            INT_X10Y236/IMUX_E35 ( 3) INT_X10Y236/INT.INT_NODE_IMUX_18_INT_OUT1->>IMUX_E35\n").split("\n");
        String[] actual = trees.get(0).toString().split("\n");
        Assertions.assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            // Remove all text after the first round bracket
            int firstRoundBracket = expected[i].indexOf("(");
            String expectedNodeOnly = expected[i].substring(0, firstRoundBracket - 1);
            Assertions.assertEquals(expectedNodeOnly, actual[i]);
        }
    }
}
