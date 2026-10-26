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

package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
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
import com.xilinx.rapidwright.util.ReportRouteStatusResult.NetRouteStatus;
import com.xilinx.rapidwright.util.route_status.RouteStatusMutator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class TestReportRouteStatus {
    @Test
    public void testReportRouteStatusMain() {
        String path = RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp");
        ReportRouteStatus.main(new String[]{path});
    }

    @Test
    public void testReportRouteStatus() {
        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");
        DesignTools.makePhysNetNamesConsistent(design);
        DesignTools.createMissingSitePinInsts(design);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design);
        Assertions.assertEquals(185994, rrs.logicalNets);
        Assertions.assertEquals(58865, rrs.routableNets);
        Assertions.assertEquals(58865, rrs.unroutedNets);
        Assertions.assertEquals(0, rrs.netsWithRoutingErrors);

        if (FileTools.isVivadoOnPath()) {
            rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(185994, rrs.logicalNets);
            Assertions.assertEquals(58865, rrs.routableNets);
            Assertions.assertEquals(58865, rrs.unroutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        }
    }

    @Test
    public void testReportRouteStatusNoPins() {
        Design design = new Design("testReportRouteStatusNoPins", "xcvu3p");
        design.createNet("net_with_no_pins");
        Net vcc = design.getVccNet(); // Causes a net to be created
        Net gnd = design.getGndNet(); // Causes a net to be created
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design);
        Assertions.assertEquals(0, rrs.logicalNets);
        Assertions.assertEquals(0, rrs.routableNets);
        Assertions.assertEquals(0, rrs.unroutedNets);
        Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        EDIFNetlist netlist = design.getNetlist();
        EDIFTools.getStaticNet(vcc.getType(), netlist.getTopHierCellInst(), netlist);
        EDIFTools.getStaticNet(gnd.getType(), netlist.getTopHierCellInst(), netlist);

        if (FileTools.isVivadoOnPath()) {
            rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(0, rrs.logicalNets);
            Assertions.assertEquals(0, rrs.routableNets);
            Assertions.assertEquals(0, rrs.unroutedNets);
            Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
        }
    }

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
    public static Stream<Arguments> testReportRouteStatusDesigns() {
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
                                "bd_0_i/hls_inst/inst/add_ln838_reg_1562_reg/ACOUT[10]=NOLOADS"}),
                // (the clock reaches the flip-flops of Versal slices through FF_CLK_MOD, whose site
                // PIPs are not stored)
                Arguments.of("versal_slr_crossing.dcp",
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :       44332 :\n" +
                        "       # of nets not needing routing.......... :       42321 :\n" +
                        "           # of internally routed nets........ :       34112 :\n" +
                        "           # of nets with no loads............ :        7968 :\n" +
                        "           # of implicitly routed ports....... :         241 :\n" +
                        "       # of routable nets..................... :        2011 :\n" +
                        "           # of fully routed nets............. :        2011 :\n" +
                        "       # of nets with routing errors.......... :           0 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"clk=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC0=HAS_ROUTING,ROUTED",
                                "GLOBAL_LOGIC1=HAS_ROUTING,ROUTED"})
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    public void testReportRouteStatusDesigns(String dcp, String vivadoSummary, String[] netStatuses) {
        Design design = RapidWrightDCP.loadDCP(dcp);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(vivadoSummary, rrs.toString());
        // The summary only is the same
        Assertions.assertEquals(vivadoSummary, ReportRouteStatus.reportRouteStatus(design).toString());
        assertNetStatuses(rrs, netStatuses);
    }

    /**
     * Designs with seeded mutations (see {@link RouteStatusMutator}), with the summary printed by
     * Vivado 2026.1's report_route_status for the mutated design, and the statuses of some nets.
     */
    public static Stream<Arguments> testReportRouteStatusMutated() {
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
                // (removing the physical nets of block RAM pins leaves invalid site programming in
                // 7-series block RAM sites, and nets without a physical net to slice loads have none)
                Arguments.of("bug709.dcp", "removenet:0.05", 1L,
                        "Design Route Status\n" +
                        "                                               :      # nets :\n" +
                        "   ------------------------------------------- : ----------- :\n" +
                        "   # of logical nets.......................... :         534 :\n" +
                        "       # of nets not needing routing.......... :         141 :\n" +
                        "           # of internally routed nets........ :         120 :\n" +
                        "           # of nets with no loads............ :          21 :\n" +
                        "       # of routable nets..................... :         393 :\n" +
                        "           # of fully routed nets............. :         338 :\n" +
                        "       # of nets with routing errors.......... :          64 :\n" +
                        "           # of nets with some unrouted pins.. :          29 :\n" +
                        "           # of nets with resource conflicts.. :          64 :\n" +
                        "           # of nets with antennas/islands.... :          53 :\n" +
                        "   ------------------------------------------- : ----------- :",
                        new String[]{"addr_read[0]=CONFLICTS,ANTENNAS,HAS_ROUTING",
                                "addr_write[0]=PARTIAL,CONFLICTS,ANTENNAS,HAS_ROUTING",
                                "doutb[0]=PARTIAL,CONFLICTS,ANTENNAS,HAS_ROUTING",
                                "doutb[2]=CONFLICTS,NOLOADS",
                                "vcnt[8]_i_1_n_0=NOLOADS",
                                "GLOBAL_LOGIC0=CONFLICTS,HAS_ROUTING",
                                "GLOBAL_LOGIC1=CONFLICTS,HAS_ROUTING"}),
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
    public void testReportRouteStatusMutated(String dcp, String mutation, long seed, String vivadoSummary,
                                                   String[] netStatuses) {
        Design design = RapidWrightDCP.loadDCP(dcp);
        new RouteStatusMutator(design, seed).apply(mutation);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(vivadoSummary, rrs.toString());
        assertNetStatuses(rrs, netStatuses);
    }

    @Test
    public void testReportRouteStatusRemovedSinkPinsRoundTrip(@TempDir Path tempDir) {
        // Removing sink pins leaves invalid site programming in 7-series block RAM sites; a net left
        // without site pins or routing, driven from such a site, has no loads where its other loads
        // have no site routing of it (as written to, and read back from, a checkpoint)
        Design design = RapidWrightDCP.loadDCP("bug709.dcp");
        new RouteStatusMutator(design, 1).apply("removesinkpin:0.05");
        Path dcp = tempDir.resolve("bug709_removesinkpin.dcp");
        design.writeCheckpoint(dcp);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(Design.readCheckpoint(dcp), true);
        Assertions.assertEquals("Design Route Status\n" +
                "                                               :      # nets :\n" +
                "   ------------------------------------------- : ----------- :\n" +
                "   # of logical nets.......................... :         534 :\n" +
                "       # of nets not needing routing.......... :         121 :\n" +
                "           # of internally routed nets........ :         120 :\n" +
                "           # of nets with no loads............ :           1 :\n" +
                "       # of routable nets..................... :         413 :\n" +
                "           # of unrouted nets................. :          12 :\n" +
                "           # of fully routed nets............. :         338 :\n" +
                "       # of nets with routing errors.......... :          64 :\n" +
                "           # of nets with some unrouted pins.. :          37 :\n" +
                "           # of nets with resource conflicts.. :          64 :\n" +
                "           # of nets with antennas/islands.... :          53 :\n" +
                "   ------------------------------------------- : ----------- :", rrs.toString());
        assertNetStatuses(rrs, new String[]{"doutb[2]=CONFLICTS,NOLOADS",
                "addr_read[11]=PARTIAL,CONFLICTS",
                "color[1]=PARTIAL,CONFLICTS",
                "GLOBAL_LOGIC0=CONFLICTS,HAS_ROUTING",
                "GLOBAL_LOGIC1=CONFLICTS,HAS_ROUTING"});
    }

    /**
     * A 7-series block RAM site whose programming is invalid (a pin on a signal net without site
     * routing) conflicts with the nets of all its pins, including VCC where its pins are tied to VCC
     * through the output port of a hierarchical cell (Vivado 2026.1 reports both the same).
     */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testReportRouteStatusInvalidSiteTiedThroughPort(boolean throughPort) {
        Design design = RapidWrightDCP.loadDCP("bug709.dcp");
        Cell ram = design.getCell("video_mem/ram_video_reg");
        SiteInst si = ram.getSiteInst();
        BELPin pin = ram.getBEL().getPin("ADDRARDADDRL10");
        Assertions.assertEquals("addr_write[8]", si.getNetFromSiteWire(pin.getSiteWireName()).getName());
        si.unrouteIntraSiteNet(pin, pin);
        if (throughPort) {
            // Tie the block RAM's VCC pins to the output of a hierarchical cell tied to VCC inside
            EDIFNetlist netlist = design.getNetlist();
            EDIFCell top = netlist.getTopCell();
            EDIFCell tie = new EDIFCell(netlist.getWorkLibrary(), "vcc_tie");
            tie.createPort("O", EDIFDirection.OUTPUT, 1);
            EDIFCellInst tieInst = tie.createCellInst("vcc_tie", top);
            EDIFTools.getStaticNet(NetType.VCC, netlist.getHierCellInstFromName("vcc_tie"), netlist)
                    .getNet().createPortInst("O", tie);
            EDIFNet tied = top.createNet("vcc_tie_o");
            tied.createPortInst("O", tieInst);
            int moved = 0;
            for (EDIFPortInst epi : new ArrayList<>(ram.getEDIFCellInst().getPortInsts())) {
                if (epi.getNet() != null && epi.getNet().isVCC()) {
                    epi.getNet().removePortInst(epi);
                    tied.addPortInst(epi);
                    moved++;
                }
            }
            Assertions.assertEquals(13, moved);
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals("Design Route Status\n" +
                "                                               :      # nets :\n" +
                "   ------------------------------------------- : ----------- :\n" +
                "   # of logical nets.......................... :         534 :\n" +
                "       # of nets not needing routing.......... :         120 :\n" +
                "           # of internally routed nets........ :         120 :\n" +
                "       # of routable nets..................... :         414 :\n" +
                "           # of fully routed nets............. :         378 :\n" +
                "       # of nets with routing errors.......... :          36 :\n" +
                "           # of nets with some unrouted pins.. :          16 :\n" +
                "           # of nets with resource conflicts.. :          36 :\n" +
                "           # of nets with antennas/islands.... :          34 :\n" +
                "   ------------------------------------------- : ----------- :", rrs.toString());
        assertNetStatuses(rrs, new String[]{"GLOBAL_LOGIC0=CONFLICTS,HAS_ROUTING",
                "GLOBAL_LOGIC1=CONFLICTS,HAS_ROUTING"});
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
    public void testReportRouteStatusParse() {
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
    public void testReportRouteStatusUnplacedNotFullyRouted() {
        Design design = RapidWrightDCP.loadDCP("routethru_luts.dcp");
        design.unplaceDesign();
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design);
        Assertions.assertTrue(rrs.netsWithNoPlacedPins > 0);
        Assertions.assertFalse(rrs.isFullyRouted());
    }

    @Test
    public void testReportRouteStatusRoutingOnAlias() {
        // Nets whose routing is stored under the name of another of their segments only: Vivado
        // uses that routing
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        for (int i = 0; i < 5; i++) {
            Net net = design.getNet("picoblaze_0_12/in_port[" + i + "]");
            Net alias = design.createNet("picoblaze_0_12/processor/Q[" + i + "]");
            alias.setPIPs(new ArrayList<>(net.getPIPs()));
            net.setPIPs(new ArrayList<>());
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
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
    public void testReportRouteStatusRemovedBidirectionalPIPs() {
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
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
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
    public void testReportRouteStatusUnroutedStaticTie(boolean hierarchical, boolean keepSiteRouting) {
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
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
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

    /**
     * A PIP stored twice in a net: Vivado gets its end node twice in the route tree, the second time
     * as a leaf. That is a conflict, and an antenna unless the node is a load site pin.
     */
    @ParameterizedTest(name = "toLoad={0}")
    @ValueSource(booleans = {false, true})
    public void testReportRouteStatusDuplicatePIP(boolean toLoad) {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        String[][] duplicated = toLoad
                ? new String[][]{{"picoblaze_0_12/in_port[0]", "INT_X6Y64/INT.INT_NODE_IMUX_13_INT_OUT0->>IMUX_E33"},
                        {"picoblaze_0_12/in_port[1]", "INT_X7Y64/INT.INT_NODE_IMUX_37_INT_OUT1->>IMUX_W23"},
                        {"picoblaze_0_12/in_port[5]", "INT_X7Y60/INT.INODE_W_5_FT1->>IMUX_W0"}}
                : new String[][]{{"picoblaze_0_12/in_port[0]", "INT_X7Y63/INT.LOGIC_OUTS_W14->INT_NODE_SDQ_65_INT_OUT0"},
                        {"picoblaze_0_12/in_port[1]", "INT_X6Y60/INT.LOGIC_OUTS_E10->INT_NODE_SDQ_12_INT_OUT1"},
                        {"picoblaze_0_12/in_port[5]", "INT_X6Y60/INT.LOGIC_OUTS_E6->INT_NODE_SDQ_4_INT_OUT0"}};
        for (String[] netPIP : duplicated) {
            Net net = design.getNet(netPIP[0]);
            PIP pip = net.getPIPs().stream().filter((p) -> p.toString().equals(netPIP[1])).findFirst().get();
            net.addPIP(new PIP(pip));
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
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
                "           # of fully routed nets............. :         769 :\n" +
                "       # of nets with routing errors.......... :           5 :\n" +
                "           # of nets with some unplaced pins.. :           2 :\n" +
                "           # of nets with some unrouted pins.. :           2 :\n" +
                "           # of nets with resource conflicts.. :           3 :\n" +
                (toLoad ? "" : "           # of nets with antennas/islands.... :           3 :\n") +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"picoblaze_0_12/in_port[0]=CONFLICTS," + (toLoad ? "" : "ANTENNAS,") + "HAS_ROUTING"});
    }

    /** The summary of picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp up to its routable nets (Vivado 2026.1) */
    private static final String PICOBLAZE4_SUMMARY_HEAD =
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
            "           # of unrouted nets................. :          33 :\n";

    /** The summary of microblazeAndILA_3pblocks.dcp up to its routable nets (Vivado 2026.1) */
    private static final String MICROBLAZE_SUMMARY_HEAD =
            "Design Route Status\n" +
            "                                               :      # nets :\n" +
            "   ------------------------------------------- : ----------- :\n" +
            "   # of logical nets.......................... :        8682 :\n" +
            "       # of nets not needing routing.......... :        3680 :\n" +
            "           # of internally routed nets........ :        3591 :\n" +
            "           # of nets with no loads............ :          89 :\n" +
            "       # of routable nets..................... :        5002 :\n";

    /**
     * The clock enable and set/reset site pins of UltraScale(+) slices whose flip-flops do not use them
     * are tied to VCC through routing, without a site pin or site routing. Without that routing,
     * Vivado lists the pin's node as an unrouted node of VCC (here INT_X9Y236/CTRL_W0, and
     * INT_X9Y94/CTRL_E_B1, whose routing is left as an antenna).
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "picoblaze_ooc_X10Y235.dcp, INT_X9Y236/INT.VCC_WIRE->>CTRL_W0",
            "microblazeAndILA_3pblocks.dcp, INT_X9Y94/INT.INT_NODE_GLOBAL_7_OUT1->>CTRL_E_B1"})
    public void testReportRouteStatusMissingSliceControlTie(String dcp, String tie) {
        Design design = RapidWrightDCP.loadDCP(dcp);
        Assertions.assertTrue(design.getVccNet().getPIPs().removeIf((p) -> p.toString().equals(tie)));
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        boolean picoblaze = dcp.startsWith("picoblaze");
        Assertions.assertEquals(picoblaze
                ? "Design Route Status\n" +
                  "                                               :      # nets :\n" +
                  "   ------------------------------------------- : ----------- :\n" +
                  "   # of logical nets.......................... :         407 :\n" +
                  "       # of nets not needing routing.......... :         212 :\n" +
                  "           # of internally routed nets........ :         119 :\n" +
                  "           # of nets with no loads............ :          27 :\n" +
                  "           # of implicitly routed ports....... :          66 :\n" +
                  "       # of routable nets..................... :         195 :\n" +
                  "           # of nets with explicit gaps....... :           1 :\n" +
                  "           # of fully routed nets............. :         194 :\n" +
                  "       # of nets with routing errors.......... :           1 :\n" +
                  "           # of nets with some unrouted pins.. :           1 :\n" +
                  "   ------------------------------------------- : ----------- :"
                : MICROBLAZE_SUMMARY_HEAD +
                  "           # of fully routed nets............. :        5001 :\n" +
                  "       # of nets with routing errors.......... :           1 :\n" +
                  "           # of nets with some unrouted pins.. :           1 :\n" +
                  "           # of nets with antennas/islands.... :           1 :\n" +
                  "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"GLOBAL_LOGIC1=PARTIAL," + (picoblaze ? "" : "ANTENNAS,") + "HAS_ROUTING"});
    }

    /**
     * A site pin that VCC ties through routing (without a site pin or site routing of VCC) driven
     * twice: a conflict of VCC with itself, but not an antenna, as the pin is a load.
     */
    @Test
    public void testReportRouteStatusStaticTieDrivenTwice() {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        Net vcc = design.getVccNet();
        for (String pip : new String[]{"INT_X8Y94/INT.VCC_WIRE->>EE2_E_BEG0", "INT_X9Y94/INT.EE2_E_END0->>INT_NODE_GLOBAL_11_OUT0",
                "INT_X9Y94/INT.INT_NODE_GLOBAL_11_OUT0->>CTRL_E_B1"}) {
            vcc.addPIP(design.getDevice().getPIP(pip));
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(MICROBLAZE_SUMMARY_HEAD +
                "           # of fully routed nets............. :        5001 :\n" +
                "       # of nets with routing errors.......... :           1 :\n" +
                "           # of nets with resource conflicts.. :           1 :\n" +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"GLOBAL_LOGIC1=CONFLICTS,HAS_ROUTING"});
    }

    /**
     * Output site pins of a net whose site wires are not driven by the net: here the HMUX output,
     * whose OUTMUXH selects the O6 output of the net's LUT, once that output (and the H_O pin) has no
     * site routing of the net. Vivado reports the routing from both pins as antennas, and the net as
     * partially routed.
     */
    @Test
    public void testReportRouteStatusUndrivenOutputSitePin() {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X10Y64");
        BELPin o6 = si.getBEL("H6LUT").getPin("O6");
        si.unrouteIntraSiteNet(o6, o6);
        Assertions.assertNull(si.getNetFromSiteWire("H_O"));
        Assertions.assertNotNull(si.getUsedSitePIP(si.getBEL("OUTMUXH")));
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(PICOBLAZE4_SUMMARY_HEAD +
                "           # of fully routed nets............. :         771 :\n" +
                "       # of nets with routing errors.......... :           3 :\n" +
                "           # of nets with some unplaced pins.. :           2 :\n" +
                "           # of nets with some unrouted pins.. :           3 :\n" +
                "           # of nets with antennas/islands.... :           1 :\n" +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"picoblaze_0_12/processor/alu_result_0=PARTIAL,ANTENNAS,HAS_ROUTING"});
    }

    /**
     * A net whose LUT drives both its G_O and GMUX outputs through site routing, with routing from
     * G_O only, is fully routed (to Vivado, neither pin is the source), also where GMUX is the
     * source of the net in RapidWright and G_O its alternate source.
     */
    @Test
    public void testReportRouteStatusRoutedFromAlternateSource() {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        Net net = design.getNet("picoblaze_0_12/processor/alu_result_2");
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X10Y62");
        SitePinInst routed = net.getSource();
        Assertions.assertEquals("G_O", routed.getName());
        Assertions.assertEquals(net, si.getNetFromSiteWire("GMUX"));
        SitePinInst gmux = net.createPin("GMUX", si);
        net.getAlternateSources().remove(gmux);
        net.setSource(gmux);
        net.setAlternateSource(routed);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(PICOBLAZE4_SUMMARY_HEAD +
                "           # of fully routed nets............. :         772 :\n" +
                "       # of nets with routing errors.......... :           2 :\n" +
                "           # of nets with some unplaced pins.. :           2 :\n" +
                "           # of nets with some unrouted pins.. :           2 :\n" +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"picoblaze_0_12/processor/alu_result_2=HAS_ROUTING,ROUTED"});
    }

    /**
     * Routing to an input site pin of a net (with a SitePinInst, with or without site routing of the
     * net) whose site routing reaches no placed cell is an antenna, even where all loads of the net
     * are routed (Vivado reports both the same).
     */
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testReportRouteStatusRoutingToStaleSitePin(boolean siteRouted) {
        Design design = RapidWrightDCP.loadDCP("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp");
        Net net = design.getNet("picoblaze_0_12/processor/alu_result_3");
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X8Y60");
        Assertions.assertNull(si.getCell("E6LUT"));
        net.addPIP(design.getDevice().getPIP("INT_X5Y60/INT.INT_NODE_IMUX_32_INT_OUT0->>IMUX_W2"));
        BELPin e1 = si.getBEL("E1").getPin("E1");
        si.routeIntraSiteNet(net, e1, e1);
        net.createPin("E1", si);
        if (!siteRouted) {
            si.unrouteIntraSiteNet(e1, e1);
            Assertions.assertNull(si.getNetFromSiteWire("E1"));
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(PICOBLAZE4_SUMMARY_HEAD +
                "           # of fully routed nets............. :         771 :\n" +
                "       # of nets with routing errors.......... :           3 :\n" +
                "           # of nets with some unplaced pins.. :           2 :\n" +
                "           # of nets with some unrouted pins.. :           2 :\n" +
                "           # of nets with antennas/islands.... :           1 :\n" +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"picoblaze_0_12/processor/alu_result_3=ANTENNAS,HAS_ROUTING"});
    }

    /**
     * Writing a checkpoint leaves the macro primitives of the netlist collapsed, while the placed
     * cells are those of their expansion: the route status is the same as before writing, and the
     * netlist is left as the write left it.
     */
    @Test
    public void testReportRouteStatusAfterWriteCheckpoint(@TempDir Path tempDir) {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        ReportRouteStatusResult before = ReportRouteStatus.reportRouteStatus(design, true);
        design.writeCheckpoint(tempDir.resolve("microblazeAndILA_3pblocks.dcp"));
        EDIFCell ram = design.getNetlist().getHDIPrimitivesLibrary().getCell("RAM32M");
        Assertions.assertTrue(ram != null && ram.isLeafCellOrBlackBox());
        ReportRouteStatusResult after = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(before.toString(), after.toString());
        Assertions.assertEquals(before.netsByStatus, after.netsByStatus);
        // (the report expands and collapses the macro again: the library holds the collapsed cell)
        ram = design.getNetlist().getHDIPrimitivesLibrary().getCell("RAM32M");
        Assertions.assertTrue(ram != null && ram.isLeafCellOrBlackBox());
    }

    /**
     * A netlist with nets but no primitives (here a wire from an input to an output port, or a
     * black box between them): Vivado lists GLOBAL_LOGIC0 as internally routed, without counting it.
     */
    @ParameterizedTest(name = "blackBox={0}")
    @ValueSource(booleans = {false, true})
    public void testReportRouteStatusNoPrimitives(boolean blackBox) {
        Design design = new Design("top", "xck26-sfvc784-2LV-c");
        design.setAutoIOBuffers(false);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = netlist.getTopCell();
        EDIFPort in = top.createPort("in", EDIFDirection.INPUT, 1);
        EDIFPort out = top.createPort("out", EDIFDirection.OUTPUT, 1);
        EDIFNet inNet = top.createNet("in");
        inNet.createPortInst(in);
        if (blackBox) {
            EDIFCell bb = new EDIFCell(netlist.getWorkLibrary(), "black_box");
            bb.createPort("I", EDIFDirection.INPUT, 1);
            bb.createPort("O", EDIFDirection.OUTPUT, 1);
            EDIFCellInst inst = bb.createCellInst("bb", top);
            inNet.createPortInst("I", inst);
            EDIFNet outNet = top.createNet("out");
            outNet.createPortInst("O", inst);
            outNet.createPortInst(out);
        } else {
            inNet.createPortInst(out);
        }
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(
                "Design Route Status\n" +
                "                                               :      # nets :\n" +
                "   ------------------------------------------- : ----------- :\n" +
                (blackBox
                ? "   # of logical nets.......................... :           2 :\n" +
                  "       # of nets not needing routing.......... :           2 :\n" +
                  "           # of internally routed nets........ :           1 :\n" +
                  "           # of implicitly routed ports....... :           1 :\n"
                : "   # of logical nets.......................... :           1 :\n" +
                  "       # of nets not needing routing.......... :           1 :\n" +
                  "           # of internally routed nets........ :           1 :\n") +
                "       # of routable nets..................... :           0 :\n" +
                "       # of nets with routing errors.......... :           0 :\n" +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, blackBox
                ? new String[]{"GLOBAL_LOGIC0=INTRASITE", "in=INTRASITE", "out=HIERPORT"}
                : new String[]{"GLOBAL_LOGIC0=INTRASITE", "in=INTRASITE"});
    }

    @Test
    public void testReportRouteStatusBlackBoxNoLoads() {
        // A net driven by a black box only, without loads, has no loads (not internally routed)
        Design design = RapidWrightDCP.loadDCP("design_with_backslash_2022.1.dcp");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = netlist.getTopCell();
        EDIFCell bb = new EDIFCell(netlist.getWorkLibrary(), "black_box");
        bb.createPort("I", EDIFDirection.INPUT, 1);
        bb.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCellInst inst = bb.createCellInst("bb", top);
        EDIFNet noLoad = top.createNet("bb_noload");
        noLoad.createPortInst("O", inst);
        top.getNets().stream().filter((n) -> !n.isGND() && !n.isVCC() && n.getPortInsts().size() > 1 && n != noLoad)
                .findFirst().get().createPortInst("I", inst);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design, true);
        Assertions.assertEquals(
                "Design Route Status\n" +
                "                                               :      # nets :\n" +
                "   ------------------------------------------- : ----------- :\n" +
                "   # of logical nets.......................... :           6 :\n" +
                "       # of nets not needing routing.......... :           5 :\n" +
                "           # of nets with no loads............ :           1 :\n" +
                "           # of implicitly routed ports....... :           4 :\n" +
                "       # of routable nets..................... :           1 :\n" +
                "           # of fully routed nets............. :           1 :\n" +
                "       # of nets with routing errors.......... :           0 :\n" +
                "   ------------------------------------------- : ----------- :",
                rrs.toString());
        assertNetStatuses(rrs, new String[]{"bb_noload=NOLOADS", "o=HIERPORT,NOLOADS"});
    }
}
