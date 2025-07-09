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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestReportRouteStatus {
    @Test
    public void testReportRouteStatusMain() {
        String path = RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp");
        ReportRouteStatus.main(new String[]{path});
    }

    @Test
    public void testReportRouteStatus() {
        Design design = RapidWrightDCP.loadDCP("optical-flow.dcp");
        DesignTools.createMissingSitePinInsts(design);
        ReportRouteStatusResult rrs = ReportRouteStatus.reportRouteStatus(design);
        Assertions.assertEquals(185996, rrs.logicalNets);
        Assertions.assertEquals(58865, rrs.routableNets);
        Assertions.assertEquals(58865, rrs.unroutedNets);
        Assertions.assertEquals(0, rrs.netsWithRoutingErrors);
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
}
