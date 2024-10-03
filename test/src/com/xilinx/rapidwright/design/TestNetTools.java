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

import org.junit.jupiter.api.Assertions;
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
}
