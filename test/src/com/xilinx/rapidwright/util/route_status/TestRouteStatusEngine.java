/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util.route_status;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.ReportRouteStatus;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class TestRouteStatusEngine {

    /** The summary printed by Vivado 2026.1's report_route_status for picoblaze_ooc_X10Y235.dcp */
    private static final String PICOBLAZE_OOC_X10Y235_SUMMARY =
            "Design Route Status\n" +
            "                                               :      # nets :\n" +
            "   ------------------------------------------- : ----------- :\n" +
            "   # of logical nets.......................... :         407 :\n" +
            "       # of nets not needing routing.......... :         212 :\n" +
            "           # of internally routed nets........ :         119 :\n" +
            "           # of nets with no loads............ :          27 :\n" +
            "           # of implicitly routed ports....... :          66 :\n" +
            "       # of routable nets..................... :         195 :\n" +
            "           # of nets with explicit gaps....... :           1 :\n" +
            "           # of fully routed nets............. :         195 :\n" +
            "       # of nets with routing errors.......... :           0 :\n" +
            "   ------------------------------------------- : ----------- :";

    /**
     * Designs with the summary printed by Vivado 2026.1's report_route_status, and the statuses
     * (from report_route_status -list_all_nets) of some of their nets.
     */
    public static Stream<Arguments> testReportVivadoRouteStatus() {
        return Stream.of(
                Arguments.of("picoblaze_ooc_X10Y235.dcp", PICOBLAZE_OOC_X10Y235_SUMMARY,
                        new String[]{"clk=GAPS,HIERPORT,HAS_ROUTING",
                                "input_port_a[0]=HIERPORT",
                                "output_port_w[0]=HIERPORT,NOLOADS",
                                "output_port_w[1]=HIERPORT,NOLOADS",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("bug635.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           2 :\n" +
                        "       # of nets not needing routing.......... :           0 :\n" +
                        "       # of routable nets..................... :           2 :\n" +
                        "           # of fully routed nets............. :           2 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("bug709.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :         534 :\n" +
                        "       # of nets not needing routing.......... :         120 :\n" +
                        "           # of internally routed nets........ :         120 :\n" +
                        "       # of routable nets..................... :         414 :\n" +
                        "           # of fully routed nets............. :         414 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("design_with_backslash_2022.1.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           5 :\n" +
                        "       # of nets not needing routing.......... :           4 :\n" +
                        "           # of implicitly routed ports....... :           4 :\n" +
                        "       # of routable nets..................... :           1 :\n" +
                        "           # of fully routed nets............. :           1 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"a=HIERPORT",
                                "b=HIERPORT"}),
                Arguments.of("ramb18.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :          96 :\n" +
                        "       # of nets not needing routing.......... :          47 :\n" +
                        "           # of internally routed nets........ :          47 :\n" +
                        "       # of routable nets..................... :          49 :\n" +
                        "           # of fully routed nets............. :          49 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("reduce_or_routed_7overlaps.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :         524 :\n" +
                        "       # of nets not needing routing.......... :         264 :\n" +
                        "           # of internally routed nets........ :          44 :\n" +
                        "           # of implicitly routed ports....... :         220 :\n" +
                        "       # of routable nets..................... :         260 :\n" +
                        "           # of fully routed nets............. :         252 :\n" +
                        "       # of nets with routing errors.......... :           8 :\n" +
                        "           # of nets with resource conflicts.. :           8 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"clk=HIERPORT",
                                "clken=HIERPORT",
                                "pipe_reg_n_0_[0][148]=CONFLICTS,HAS_ROUTING",
                                "pipe_reg_n_0_[0][150]=CONFLICTS,HAS_ROUTING",
                                "GLOBAL_LOGIC0=INTRASITE",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("routethru_luts.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           8 :\n" +
                        "       # of nets not needing routing.......... :           4 :\n" +
                        "           # of internally routed nets........ :           4 :\n" +
                        "       # of routable nets..................... :           4 :\n" +
                        "           # of fully routed nets............. :           4 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=INTRASITE",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("routethru_pip.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :          21 :\n" +
                        "       # of nets not needing routing.......... :          10 :\n" +
                        "           # of internally routed nets........ :          10 :\n" +
                        "       # of routable nets..................... :          11 :\n" +
                        "           # of fully routed nets............. :          11 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("inout.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :          13 :\n" +
                        "       # of nets not needing routing.......... :           5 :\n" +
                        "           # of internally routed nets........ :           5 :\n" +
                        "       # of routable nets..................... :           8 :\n" +
                        "           # of fully routed nets............. :           8 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=INTRASITE"}),
                Arguments.of("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :        1505 :\n" +
                        "       # of nets with no placed pins.......... :           1 :\n" +
                        "       # of nets not needing routing.......... :         697 :\n" +
                        "           # of internally routed nets........ :         476 :\n" +
                        "           # of nets with no loads............ :         188 :\n" +
                        "           # of implicitly routed ports....... :          33 :\n" +
                        "       # of routable nets..................... :         805 :\n" +
                        "           # of unrouted nets................. :          33 :\n" +
                        "           # of fully routed nets............. :         772 :\n" +
                        "       # of nets with routing errors.......... :           2 :\n" +
                        "           # of nets with some unplaced pins.. :           2 :\n" +
                        "           # of nets with some unrouted pins.. :           2 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC1=UNPLACED_ALL,UNPLACED,PARTIAL",
                                "clk=UNPLACED_ALL,UNPLACED,PARTIAL",
                                "GLOBAL_LOGIC0=UNROUTED",
                                "picoblaze_0_12/output_port_y[0]=UNROUTED",
                                "picoblaze_0_13/output_port_z[0]=HIERPORT,NOLOADS",
                                "picoblaze_0_13/output_port_z[1]=HIERPORT,NOLOADS",
                                "picoblaze_0_12/output_port_w[0]=NOLOADS",
                                "picoblaze_0_12/output_port_w[1]=NOLOADS"}),
                Arguments.of("bug701.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :         104 :\n" +
                        "       # of nets not needing routing.......... :          67 :\n" +
                        "           # of internally routed nets........ :          64 :\n" +
                        "       # of routable nets..................... :          37 :\n" +
                        "           # of unrouted nets................. :          34 :\n" +
                        "           # of fully routed nets............. :           3 :\n" +
                        "       # of nets with routing errors.......... :           3 :\n" +
                        "           # of nets with no driver........... :           3 :\n" +
                        "           # of nets with some unrouted pins.. :           3 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=UNROUTED",
                                "GLOBAL_LOGIC1=UNROUTED",
                                "adder/ce=PARTIAL,NODRIVER",
                                "adder/clk=PARTIAL,NODRIVER"}),
                Arguments.of("gnl_2_4_3_1.3_gnl_3000_07_3_80_80_placed.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :        4738 :\n" +
                        "       # of nets not needing routing.......... :        4736 :\n" +
                        "           # of internally routed nets........ :        1656 :\n" +
                        "           # of nets with no loads............ :        2962 :\n" +
                        "           # of implicitly routed ports....... :         118 :\n" +
                        "       # of routable nets..................... :           2 :\n" +
                        "           # of unrouted nets................. :           2 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=UNROUTED",
                                "GLOBAL_LOGIC1=UNROUTED",
                                "clk=HIERPORT",
                                "op[0]=HIERPORT,NOLOADS",
                                "LUT6_2_10/O5=NOLOADS",
                                "LUT6_2_10/O6=NOLOADS"}),
                Arguments.of("hwct_pr1.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :        2060 :\n" +
                        "       # of nets not needing routing.......... :        1125 :\n" +
                        "           # of internally routed nets........ :         571 :\n" +
                        "           # of nets with no loads............ :          40 :\n" +
                        "           # of implicitly routed ports....... :         514 :\n" +
                        "       # of routable nets..................... :         935 :\n" +
                        "           # of nets with fixed routing....... :         930 :\n" +
                        "           # of unrouted nets................. :           5 :\n" +
                        "           # of fully routed nets............. :         930 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"M_AXIS_rp_2_s_0_tready=HIERPORT",
                                "M_AXIS_rp_2_s_1_tready=HIERPORT",
                                "GLOBAL_LOGIC0=LOCKED_NODES,HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=LOCKED_NODES,HAS_ROUTING,ROUTED",
                                "clock_isolation/decouple_status_sync_s/inst/xsingle/syncstages_ff[3]=HIERPORT,NOLOADS",
                                "dfx_decoupler/U0/rp_in_0_TVALID=HIERPORT,NOLOADS"}),
                Arguments.of("microblazeAndILA_3pblocks.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :        8682 :\n" +
                        "       # of nets not needing routing.......... :        3680 :\n" +
                        "           # of internally routed nets........ :        3591 :\n" +
                        "           # of nets with no loads............ :          89 :\n" +
                        "       # of routable nets..................... :        5002 :\n" +
                        "           # of fully routed nets............. :        5002 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"dbg_hub/inst/BSCANID.u_xsdbm_id/SWITCH_N_EXT_BSCAN.bscan_inst/s_bscan_tms=NOLOADS",
                                "dbg_hub/inst/BSCANID.u_xsdbm_id/SWITCH_N_EXT_BSCAN.bscan_switch/m_bscan_capture[0]=NOLOADS",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("picoblaze_partial.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :      147009 :\n" +
                        "       # of nets not needing routing.......... :       58434 :\n" +
                        "           # of internally routed nets........ :       47124 :\n" +
                        "           # of nets with no loads............ :       11132 :\n" +
                        "           # of implicitly routed ports....... :         178 :\n" +
                        "       # of routable nets..................... :       88575 :\n" +
                        "           # of unrouted nets................. :       12144 :\n" +
                        "           # of fully routed nets............. :       76431 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"picoblaze_0_12/output_port_y[0]=UNROUTED",
                                "picoblaze_0_12/output_port_y[1]=UNROUTED",
                                "clkin=HIERPORT",
                                "picoblaze_0_47/output_port_z[0]=HIERPORT,NOLOADS",
                                "picoblaze_0_12/output_port_w[0]=NOLOADS",
                                "picoblaze_0_12/output_port_w[1]=NOLOADS",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("bnn.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :       11338 :\n" +
                        "       # of nets not needing routing.......... :        5454 :\n" +
                        "           # of internally routed nets........ :        4585 :\n" +
                        "           # of nets with no loads............ :         590 :\n" +
                        "           # of implicitly routed ports....... :         279 :\n" +
                        "       # of routable nets..................... :        5884 :\n" +
                        "           # of unrouted nets................. :        5884 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=UNROUTED",
                                "GLOBAL_LOGIC1=UNROUTED",
                                "ap_clk=HIERPORT",
                                "ap_ctrl_start=HIERPORT",
                                "bd_0_i/hls_inst/inst/add_ln838_reg_1562_reg/ACOUT[0]=NOLOADS",
                                "bd_0_i/hls_inst/inst/add_ln838_reg_1562_reg/ACOUT[10]=NOLOADS"})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    public void testReportVivadoRouteStatus(String dcp, String vivadoSummary, String[] netStatuses) {
        Design design = RapidWrightDCP.loadDCP(dcp);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportVivadoRouteStatus(design, true);
        Assertions.assertEquals(vivadoSummary, rrs.toString());
        // The summary only is the same
        Assertions.assertEquals(vivadoSummary, ReportRouteStatus.reportVivadoRouteStatus(design).toString());
        assertNetStatuses(rrs, netStatuses);
    }

    /**
     * Designs with seeded mutations (see {@link RouteStatusMutator}), with the summary printed by
     * Vivado 2026.1's report_route_status for the mutated design, and the statuses of some nets.
     */
    public static Stream<Arguments> testReportVivadoRouteStatusMutated() {
        return Stream.of(
                // A net with two drivers: Vivado reports the other driver's pin as unrouted (and
                // conflicting with the net it was taken from), not the net as multiply driven
                Arguments.of("routethru_pip.dcp", "multidriver:1", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :          21 :\n" +
                        "       # of nets not needing routing.......... :          11 :\n" +
                        "           # of internally routed nets........ :          10 :\n" +
                        "       # of routable nets..................... :          10 :\n" +
                        "           # of fully routed nets............. :           9 :\n" +
                        "       # of nets with routing errors.......... :           2 :\n" +
                        "           # of nets with no driver........... :           1 :\n" +
                        "           # of nets with some unrouted pins.. :           1 :\n" +
                        "           # of nets with resource conflicts.. :           2 :\n" +
                        "           # of nets with antennas/islands.... :           1 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"S_IBUF[0]=PARTIAL,CONFLICTS,HAS_ROUTING",
                                "S_IBUF[2]=CONFLICTS,ANTENNAS,NODRIVER,HAS_ROUTING"}),
                Arguments.of("routethru_luts.dcp", "unroute:0.1", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           8 :\n" +
                        "       # of nets not needing routing.......... :           4 :\n" +
                        "           # of internally routed nets........ :           4 :\n" +
                        "       # of routable nets..................... :           4 :\n" +
                        "           # of unrouted nets................. :           1 :\n" +
                        "           # of fully routed nets............. :           3 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"i_IBUF[1]=UNROUTED",
                                "GLOBAL_LOGIC0=INTRASITE",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("routethru_luts.dcp", "conflict:5", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           8 :\n" +
                        "       # of nets not needing routing.......... :           4 :\n" +
                        "           # of internally routed nets........ :           4 :\n" +
                        "       # of routable nets..................... :           4 :\n" +
                        "           # of fully routed nets............. :           1 :\n" +
                        "       # of nets with routing errors.......... :           3 :\n" +
                        "           # of nets with resource conflicts.. :           3 :\n" +
                        "           # of nets with antennas/islands.... :           2 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"i_IBUF[0]=CONFLICTS,ANTENNAS,HAS_ROUTING",
                                "i_IBUF[1]=CONFLICTS,HAS_ROUTING",
                                "i_IBUF[2]=CONFLICTS,ANTENNAS,HAS_ROUTING",
                                "GLOBAL_LOGIC0=INTRASITE",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("routethru_luts.dcp", "removepip:0.1", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           8 :\n" +
                        "       # of nets not needing routing.......... :           4 :\n" +
                        "           # of internally routed nets........ :           4 :\n" +
                        "       # of routable nets..................... :           4 :\n" +
                        "           # of fully routed nets............. :           3 :\n" +
                        "       # of nets with routing errors.......... :           1 :\n" +
                        "           # of nets with antennas/islands.... :           1 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"i_IBUF[1]=ANTENNAS,HAS_ROUTING",
                                "GLOBAL_LOGIC0=INTRASITE",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("routethru_luts.dcp", "antenna:0.05", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           8 :\n" +
                        "       # of nets not needing routing.......... :           4 :\n" +
                        "           # of internally routed nets........ :           4 :\n" +
                        "       # of routable nets..................... :           4 :\n" +
                        "           # of fully routed nets............. :           3 :\n" +
                        "       # of nets with routing errors.......... :           1 :\n" +
                        "           # of nets with antennas/islands.... :           1 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"i_IBUF[1]=ANTENNAS,HAS_ROUTING",
                                "GLOBAL_LOGIC0=INTRASITE",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("routethru_luts.dcp", "nodriver:3", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           8 :\n" +
                        "       # of nets not needing routing.......... :           7 :\n" +
                        "           # of internally routed nets........ :           4 :\n" +
                        "       # of routable nets..................... :           1 :\n" +
                        "           # of fully routed nets............. :           1 :\n" +
                        "       # of nets with routing errors.......... :           3 :\n" +
                        "           # of nets with no driver........... :           3 :\n" +
                        "           # of nets with antennas/islands.... :           3 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"i_IBUF[0]=ANTENNAS,NODRIVER,HAS_ROUTING",
                                "i_IBUF[1]=ANTENNAS,NODRIVER,HAS_ROUTING",
                                "GLOBAL_LOGIC0=INTRASITE",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("routethru_luts.dcp", "noloads:3", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :           8 :\n" +
                        "       # of nets not needing routing.......... :           4 :\n" +
                        "           # of internally routed nets........ :           4 :\n" +
                        "       # of routable nets..................... :           4 :\n" +
                        "           # of fully routed nets............. :           1 :\n" +
                        "       # of nets with routing errors.......... :           3 :\n" +
                        "           # of nets with antennas/islands.... :           3 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"i_IBUF[0]=ANTENNAS,HAS_ROUTING",
                                "i_IBUF[1]=ANTENNAS,HAS_ROUTING",
                                "GLOBAL_LOGIC0=INTRASITE",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("ramb18.dcp", "unroutesinks:0.2", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :          96 :\n" +
                        "       # of nets not needing routing.......... :          47 :\n" +
                        "           # of internally routed nets........ :          47 :\n" +
                        "       # of routable nets..................... :          49 :\n" +
                        "           # of fully routed nets............. :          48 :\n" +
                        "       # of nets with routing errors.......... :           1 :\n" +
                        "           # of nets with some unrouted pins.. :           1 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"clk_IBUF=PARTIAL,HAS_ROUTING",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("ramb18.dcp", "lock:0.2", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :          96 :\n" +
                        "       # of nets not needing routing.......... :          47 :\n" +
                        "           # of internally routed nets........ :          47 :\n" +
                        "       # of routable nets..................... :          49 :\n" +
                        "           # of nets with fixed routing....... :          10 :\n" +
                        "           # of fully routed nets............. :          49 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"addr_IBUF[1]=LOCKED_NODES,HAS_ROUTING,ROUTED",
                                "addr_IBUF[2]=LOCKED_NODES,HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("ramb18.dcp", "unroutesource:0.05", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :          96 :\n" +
                        "       # of nets not needing routing.......... :          47 :\n" +
                        "           # of internally routed nets........ :          47 :\n" +
                        "       # of routable nets..................... :          49 :\n" +
                        "           # of unrouted nets................. :           3 :\n" +
                        "           # of fully routed nets............. :          46 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"addr_IBUF[1]=UNROUTED",
                                "addr_IBUF[4]=UNROUTED",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"}),
                Arguments.of("microblazeAndILA_3pblocks.dcp", "removenet:0.05", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :        8682 :\n" +
                        "       # of nets not needing routing.......... :        3922 :\n" +
                        "           # of internally routed nets........ :        3672 :\n" +
                        "           # of nets with no loads............ :         250 :\n" +
                        "       # of routable nets..................... :        4760 :\n" +
                        "           # of unrouted nets................. :           8 :\n" +
                        "           # of fully routed nets............. :        4751 :\n" +
                        "       # of nets with routing errors.......... :           1 :\n" +
                        "           # of nets with some unrouted pins.. :           1 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"base_mb_i/axi_gpio_0/U0/AXI_LITE_IPIF_I/I_SLAVE_ATTACHMENT/start2=UNROUTED",
                                "base_mb_i/axi_uartlite_0/U0/AXI_LITE_IPIF_I/I_SLAVE_ATTACHMENT/start2=UNROUTED",
                                "GLOBAL_LOGIC1=PARTIAL,HAS_ROUTING",
                                "base_mb_i/axi_gpio_0/U0/AXI_LITE_IPIF_I/I_SLAVE_ATTACHMENT/bus2ip_addr[6]=NOLOADS",
                                "base_mb_i/axi_gpio_0/U0/AXI_LITE_IPIF_I/I_SLAVE_ATTACHMENT/s_axi_rdata[7]=NOLOADS",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED"}),
                Arguments.of("microblazeAndILA_3pblocks.dcp", "unroutestatic:0.3", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :        8682 :\n" +
                        "       # of nets not needing routing.......... :        3680 :\n" +
                        "           # of internally routed nets........ :        3591 :\n" +
                        "           # of nets with no loads............ :          89 :\n" +
                        "       # of routable nets..................... :        5002 :\n" +
                        "           # of fully routed nets............. :        5000 :\n" +
                        "       # of nets with routing errors.......... :           2 :\n" +
                        "           # of nets with some unrouted pins.. :           2 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=PARTIAL,HAS_ROUTING",
                                "GLOBAL_LOGIC1=PARTIAL,HAS_ROUTING",
                                "dbg_hub/inst/BSCANID.u_xsdbm_id/SWITCH_N_EXT_BSCAN.bscan_inst/s_bscan_tms=NOLOADS",
                                "dbg_hub/inst/BSCANID.u_xsdbm_id/SWITCH_N_EXT_BSCAN.bscan_switch/m_bscan_capture[0]=NOLOADS"}),
                Arguments.of("microblazeAndILA_3pblocks.dcp", "unplacedesign", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :        8682 :\n" +
                        "       # of nets with no placed pins.......... :        6733 :\n" +
                        "       # of nets not needing routing.......... :        1949 :\n" +
                        "           # of internally routed nets........ :        1949 :\n" +
                        "       # of routable nets..................... :           0 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"GLOBAL_LOGIC0=UNPLACED_ALL",
                                "GLOBAL_LOGIC1=UNPLACED_ALL"}),
                Arguments.of("microblazeAndILA_3pblocks.dcp", "noloads:3", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :        8682 :\n" +
                        "       # of nets not needing routing.......... :        3680 :\n" +
                        "           # of internally routed nets........ :        3591 :\n" +
                        "           # of nets with no loads............ :          89 :\n" +
                        "       # of routable nets..................... :        5002 :\n" +
                        "           # of fully routed nets............. :        4999 :\n" +
                        "       # of nets with routing errors.......... :           3 :\n" +
                        "           # of nets with antennas/islands.... :           3 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"base_mb_i/mdm_1/U0/Use_E2.BSCAN_I/Ext_JTAG_UPDATE=ANTENNAS,HAS_ROUTING",
                                "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/wb_dlmb_valid_read_data[13]=ANTENNAS,HAS_ROUTING",
                                "dbg_hub/inst/BSCANID.u_xsdbm_id/SWITCH_N_EXT_BSCAN.bscan_inst/s_bscan_tms=NOLOADS",
                                "dbg_hub/inst/BSCANID.u_xsdbm_id/SWITCH_N_EXT_BSCAN.bscan_switch/m_bscan_capture[0]=NOLOADS",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"})
        );
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource
    public void testReportVivadoRouteStatusMutated(String dcp, String mutation, long seed, String vivadoSummary,
                                                   String[] netStatuses) {
        Design design = RapidWrightDCP.loadDCP(dcp);
        new RouteStatusMutator(design, seed).apply(mutation);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportVivadoRouteStatus(design, true);
        Assertions.assertEquals(vivadoSummary, rrs.toString());
        assertNetStatuses(rrs, netStatuses);
    }

    private static void assertNetStatuses(ReportRouteStatusResult rrs, String[] netStatuses) {
        Map<String, EnumSet<NetRouteStatus>> statuses = rrs.getNetStatuses();
        for (String netStatus : netStatuses) {
            String net = netStatus.substring(0, netStatus.lastIndexOf('='));
            EnumSet<NetRouteStatus> expected = EnumSet.noneOf(NetRouteStatus.class);
            for (String s : netStatus.substring(net.length() + 1).split(",")) {
                expected.add(NetRouteStatus.valueOf(s));
            }
            Assertions.assertEquals(expected, statuses.getOrDefault(net, EnumSet.noneOf(NetRouteStatus.class)), net);
        }
    }

    @Test
    public void testReportVivadoRouteStatusParse() {
        // The summary printed by Vivado parses back to the same counts
        String vivado = PICOBLAZE_OOC_X10Y235_SUMMARY;
        ReportRouteStatusResult rrs = new ReportRouteStatusResult(Arrays.asList(vivado.split("\n")));
        Assertions.assertEquals(407, rrs.logicalNets);
        Assertions.assertEquals(66, rrs.implicitlyRoutedPorts);
        Assertions.assertEquals(1, rrs.netsWithExplicitGaps);
        Assertions.assertNull(rrs.netsByStatus);
        Assertions.assertEquals(vivado, rrs.toString());
    }

    @Test
    public void testReportVivadoRouteStatusParseEmptyNetLists() {
        // A per-net report in which no net has any status still has per-net (empty) lists
        ReportRouteStatusResult empty = new ReportRouteStatusResult();
        empty.netsByStatus = ReportRouteStatusResult.parseNetsByStatus(Collections.emptyList());
        String report = empty.toListAllNetsString();
        ReportRouteStatusResult parsed = new ReportRouteStatusResult(Arrays.asList(report.split("\n")));
        Assertions.assertNotNull(parsed.netsByStatus);
        Assertions.assertTrue(parsed.netsByStatus.values().stream().allMatch(Set::isEmpty));
        Assertions.assertTrue(empty.diffNets(parsed).isEmpty());
    }

    @Test
    public void testReportVivadoRouteStatusConflictsFallback() {
        // Conflicts are marked the same way when nodes cannot be packed (as for huge designs)
        Design design = RapidWrightDCP.loadDCP("routethru_luts.dcp");
        new RouteStatusMutator(design, 1).apply("conflict:5");
        ReportRouteStatusResult expected = ReportRouteStatus.reportVivadoRouteStatus(design, true);
        try {
            RouteStatusEngine.forceConflictsFallback = true;
            ReportRouteStatusResult fallback = ReportRouteStatus.reportVivadoRouteStatus(design, true);
            Assertions.assertEquals(expected.toString(), fallback.toString());
            Assertions.assertEquals(expected.netsByStatus, fallback.netsByStatus);
        } finally {
            RouteStatusEngine.forceConflictsFallback = false;
        }
    }

    @Test
    public void testReportVivadoRouteStatusUnplacedNotFullyRouted() {
        Design design = RapidWrightDCP.loadDCP("routethru_luts.dcp");
        design.unplaceDesign();
        ReportRouteStatusResult rrs = ReportRouteStatus.reportVivadoRouteStatus(design);
        Assertions.assertTrue(rrs.netsWithNoPlacedPins > 0);
        Assertions.assertFalse(rrs.isFullyRouted());
    }

    @Test
    public void testReportVivadoRouteStatusRoutingOnAlias() {
        // Nets whose routing is stored under the name of another of their segments only: Vivado
        // uses that routing
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        for (int i = 0; i < 5; i++) {
            Net net = design.getNet("picoblaze_0_12/in_port[" + i + "]");
            Net alias = design.createNet("picoblaze_0_12/processor/Q[" + i + "]");
            alias.setPIPs(new ArrayList<>(net.getPIPs()));
            net.setPIPs(new ArrayList<>());
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportVivadoRouteStatus(design, true);
        Assertions.assertEquals(
                "Design Route Status\n" +
                "                                               :      # nets :\n" +
                "   ------------------------------------------- : ----------- :\n" +
                "   # of logical nets.......................... :        1505 :\n" +
                "       # of nets with no placed pins.......... :           1 :\n" +
                "       # of nets not needing routing.......... :         697 :\n" +
                "           # of internally routed nets........ :         476 :\n" +
                "           # of nets with no loads............ :         188 :\n" +
                "           # of implicitly routed ports....... :          33 :\n" +
                "       # of routable nets..................... :         805 :\n" +
                "           # of unrouted nets................. :          33 :\n" +
                "           # of fully routed nets............. :         772 :\n" +
                "       # of nets with routing errors.......... :           2 :\n" +
                "           # of nets with some unplaced pins.. :           2 :\n" +
                "           # of nets with some unrouted pins.. :           2 :\n" +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"picoblaze_0_12/in_port[0]=HAS_ROUTING,ROUTED"});
    }

    @Test
    public void testReportVivadoRouteStatusRemovedBidirectionalPIPs() {
        // Bidirectional PIPs that are not stored with a net do not connect its routing (unlike
        // those joining global clock routing)
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        String[][] removed = {
                {"picoblaze_1_12/processor/data_path_loop[0].output_data.sy_kk_mux_lut/O5",
                        "INT_X11Y62/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8"},
                {"picoblaze_0_13/processor/data_path_loop[0].lsb_shift_rotate.shift_rotate_lut/O5",
                        "INT_X5Y65/INT.INT_NODE_IMUX_5_INT_OUT0<<->>BYPASS_E8"},
                {"picoblaze_0_12/processor/active_interrupt_lut/O5",
                        "INT_X5Y64/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8"},
                {"picoblaze_1_13/processor/active_interrupt_lut/O5",
                        "INT_X9Y69/INT.INT_NODE_IMUX_37_INT_OUT0<<->>BYPASS_W8"},
                {"picoblaze_0_12/processor/data_path_loop[0].second_operand.out_port_lut/O6",
                        "INT_X6Y64/INT.INT_NODE_IMUX_18_INT_OUT0<<->>BYPASS_E14"}};
        for (String[] netPIP : removed) {
            Net net = design.getNet(netPIP[0]);
            PIP pip = net.getPIPs().stream().filter((p) -> p.toString().equals(netPIP[1])).findFirst().get();
            Assertions.assertTrue(pip.isBidirectional());
            net.removePIP(pip);
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportVivadoRouteStatus(design, true);
        Assertions.assertEquals(
                "Design Route Status\n" +
                "                                               :      # nets :\n" +
                "   ------------------------------------------- : ----------- :\n" +
                "   # of logical nets.......................... :        1505 :\n" +
                "       # of nets with no placed pins.......... :           1 :\n" +
                "       # of nets not needing routing.......... :         697 :\n" +
                "           # of internally routed nets........ :         476 :\n" +
                "           # of nets with no loads............ :         188 :\n" +
                "           # of implicitly routed ports....... :          33 :\n" +
                "       # of routable nets..................... :         805 :\n" +
                "           # of unrouted nets................. :          33 :\n" +
                "           # of fully routed nets............. :         767 :\n" +
                "       # of nets with routing errors.......... :           7 :\n" +
                "           # of nets with some unplaced pins.. :           2 :\n" +
                "           # of nets with some unrouted pins.. :           6 :\n" +
                "           # of nets with antennas/islands.... :           5 :\n" +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{
                // (a reversed PIP)
                "picoblaze_1_12/processor/data_path_loop[0].output_data.sy_kk_mux_lut/O5=ANTENNAS,HAS_ROUTING",
                "picoblaze_0_12/processor/active_interrupt_lut/O5=PARTIAL,ANTENNAS,HAS_ROUTING"});
    }

    /**
     * An input of a LUT tied to GND, with its routing and site pin removed: with its site routing
     * kept, it is an unrouted load of GND, whether tied in its parent cell or (through a port of that
     * cell) further up. Without site routing, it is not a load.
     */
    @ParameterizedTest(name = "hierarchical={0} keepSiteRouting={1}")
    @CsvSource({"false,true", "true,true", "false,false", "true,false"})
    public void testReportVivadoRouteStatusUnroutedStaticTie(boolean hierarchical, boolean keepSiteRouting) {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        Cell cell = design.getCell("u_ila_0/inst/i_0");
        Net gnd = design.getGndNet();
        SiteInst si = cell.getSiteInst();
        BELPin lutPin = cell.getBEL().getPin("A6");
        BELPin sitePort = null;
        for (BELPin p : si.getSiteWirePins(lutPin.getSiteWireIndex())) {
            if (p.isSitePort()) {
                sitePort = p;
            }
        }
        SitePinInst spi = si.getSitePinInst(sitePort.getName());
        Assertions.assertSame(gnd, spi.getNet());
        if (hierarchical) {
            // Tie the LUT input to GND through a new input port of its parent cell
            EDIFHierCellInst parent = cell.getEDIFHierCellInst().getParent();
            EDIFPort port = parent.getCellType().createPort("tie", EDIFDirection.INPUT, 1);
            EDIFNet inner = parent.getCellType().createNet("tie");
            inner.createPortInst(port);
            EDIFPortInst input = cell.getEDIFCellInst().getPortInst("I0");
            Assertions.assertTrue(input.getNet().isGND());
            input.getNet().removePortInst(input);
            inner.addPortInst(input);
            EDIFTools.getStaticNet(NetType.GND, parent.getParent(), design.getNetlist()).getNet()
                    .createPortInst(port, parent.getInst());
        }
        DesignTools.unroutePins(gnd, Collections.singletonList(spi));
        List<PIP> pips = new ArrayList<>(gnd.getPIPs());
        gnd.removePin(spi);
        si.removePin(spi);
        gnd.setPIPs(pips);
        if (keepSiteRouting) {
            si.routeIntraSiteNet(gnd, sitePort, lutPin);
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportVivadoRouteStatus(design, true);
        Assertions.assertEquals(
                "Design Route Status\n" +
                "                                               :      # nets :\n" +
                "   ------------------------------------------- : ----------- :\n" +
                "   # of logical nets.......................... :        8682 :\n" +
                "       # of nets not needing routing.......... :        3680 :\n" +
                "           # of internally routed nets........ :        3591 :\n" +
                "           # of nets with no loads............ :          89 :\n" +
                "       # of routable nets..................... :        5002 :\n" +
                (keepSiteRouting
                ? "           # of fully routed nets............. :        5001 :\n" +
                  "       # of nets with routing errors.......... :           1 :\n" +
                  "           # of nets with some unrouted pins.. :           1 :\n"
                : "           # of fully routed nets............. :        5002 :\n" +
                  "       # of nets with routing errors.......... :           0 :\n") +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"GLOBAL_LOGIC0=" + (keepSiteRouting ? "PARTIAL," : "") + "HAS_ROUTING"
                + (keepSiteRouting ? "" : ",ROUTED")});
    }

    private static int bits(NetRouteStatus... statuses) {
        int bits = 0;
        for (NetRouteStatus s : statuses) {
            bits |= 1 << s.ordinal();
        }
        return bits;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testMarkNodeUsersSeveralSitePinOwners(boolean reversed) {
        // Two unrouted nets claim the same site pin node, which a third net routes through: both
        // become partially routed, whatever the order of the users
        int[] status = {bits(NetRouteStatus.UNROUTED), bits(NetRouteStatus.UNROUTED),
                bits(NetRouteStatus.HAS_ROUTING, NetRouteStatus.ROUTED)};
        int[] users = reversed ? new int[]{1 << 1 | 1, 0 << 1 | 1, 2 << 1} : new int[]{0 << 1 | 1, 1 << 1 | 1, 2 << 1};
        RouteStatusEngine.markNodeUsers(status, users, users.length);
        int owner = bits(NetRouteStatus.PARTIAL, NetRouteStatus.CONFLICTS);
        Assertions.assertArrayEquals(new int[]{owner, owner,
                bits(NetRouteStatus.HAS_ROUTING, NetRouteStatus.CONFLICTS, NetRouteStatus.ANTENNAS)}, status);
    }

    @Test
    public void testMultidriverSkipsSharedDefinitions() {
        // Moving a driver inside a definition shared by u0 and u1 would also change u1
        Design design = new Design("top", "xcvu3p");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell sub = new EDIFCell(netlist.getWorkLibrary(), "sub");
        EDIFCell lut = netlist.getHDIPrimitive(Unisim.LUT1);
        for (int i = 0; i < 2; i++) {
            sub.createNet("n" + i).createPortInst("O", lut.createCellInst("lut" + i, sub));
        }
        EDIFCellInst u0 = sub.createCellInst("u0", design.getTopEDIFCell());
        Net target = design.createNet(netlist.getHierNetFromName("u0/n0"));
        RouteStatusMutator mutator = new RouteStatusMutator(design, 1);

        sub.createCellInst("u1", design.getTopEDIFCell());
        Assertions.assertNull(mutator.addSecondDriver(target));
        Assertions.assertEquals(1, sub.getNet("n0").getPortInsts().size());

        design.getTopEDIFCell().removeCellInst("u1");
        Assertions.assertEquals("n1", mutator.addSecondDriver(target));
        Assertions.assertEquals(2, sub.getNet("n0").getPortInsts().size());
        Assertions.assertSame(u0.getCellType(), sub);
    }

    @Test
    public void testAntennaSkipsGapMarkers() {
        // The routing of clk has explicit gaps, marked by PIPs without an end node
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        Assertions.assertTrue(design.getNet("clk").getPIPs().stream().anyMatch((p) -> p.getEndNode() == null));
        // (adds an antenna to every routed net)
        new RouteStatusMutator(design, 1).apply("antenna:100000");
    }

    @Test
    public void testMultidriverUsesCurrentConnectivity() {
        // After its driver moved to another net, a net has no driver to give that net another one
        Design design = new Design("top", "xcvu3p");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = design.getTopEDIFCell();
        EDIFCell lut = netlist.getHDIPrimitive(Unisim.LUT1);
        Net[] nets = new Net[3];
        for (int i = 0; i < nets.length; i++) {
            top.createNet("n" + i).createPortInst("O", lut.createCellInst("lut" + i, top));
            nets[i] = design.createNet(netlist.getHierNetFromName("n" + i));
        }
        RouteStatusMutator mutator = new RouteStatusMutator(design, 1);
        String donor = mutator.addSecondDriver(nets[0]);
        Assertions.assertNotNull(donor);
        Assertions.assertEquals(2, top.getNet("n0").getPortInsts().size());
        Assertions.assertTrue(top.getNet(donor).getPortInsts().isEmpty());

        Assertions.assertNull(mutator.addSecondDriver(design.getNet(donor)));
        Assertions.assertEquals(2, top.getNet("n0").getPortInsts().size());
    }
}
