/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util.routestatus;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.ReportRouteStatus;
import com.xilinx.rapidwright.util.ReportRouteStatusResult.NetRouteStatus;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Tests of the internals of the route status engine. */
public class TestRouteStatusEngine {

    @Test
    public void testReportRouteStatusConflictsFallback() {
        // Conflicts are marked the same way when nodes cannot be packed (as for huge designs)
        Design design = RapidWrightDCP.loadDCP("routethru_luts.dcp");
        new RouteStatusMutator(design, 1).apply("conflict:5");
        ReportRouteStatusResult expected = ReportRouteStatus.reportRouteStatus(design, true);
        ReportRouteStatusResult fallback = RouteStatusEngine.run(design, true, false);
        Assertions.assertEquals(expected.toString(), fallback.toString());
        Assertions.assertEquals(expected.netsByStatus, fallback.netsByStatus);
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
        ConflictMarker.markNodeUsers(status, users, users.length);
        int owner = bits(NetRouteStatus.PARTIAL, NetRouteStatus.CONFLICTS);
        Assertions.assertArrayEquals(new int[]{owner, owner,
                bits(NetRouteStatus.HAS_ROUTING, NetRouteStatus.CONFLICTS, NetRouteStatus.ANTENNAS)}, status);
    }

    @Test
    public void testIsTiedToThroughChildOutput() {
        // A flip-flop's clock enable tied to VCC through the output port of a hierarchical sibling
        // (not through the ports of its parents)
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Cell ff = null;
        for (Cell cell : design.getCells()) {
            EDIFHierCellInst inst = cell.getEDIFHierCellInst();
            EDIFPortInst ce = (inst == null || !cell.getType().equals("FDRE")) ? null : inst.getInst().getPortInst("CE");
            if (ce != null && ce.getNet() != null && ce.getNet().isVCC() && !inst.getParent().isTopLevelInst()) {
                ff = cell;
                break;
            }
        }
        Assertions.assertNotNull(ff);
        Assertions.assertTrue(SiteRouting.isTiedTo(ff, "CE", NetType.VCC));
        EDIFHierCellInst parent = ff.getEDIFHierCellInst().getParent();
        EDIFCell tie = new EDIFCell(netlist.getWorkLibrary(), "vcc_tie");
        tie.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCellInst tieInst = tie.createCellInst("vcc_tie", parent.getCellType());
        EDIFTools.getStaticNet(NetType.VCC, parent.getChild(tieInst), netlist).getNet().createPortInst("O", tie);
        EDIFNet tied = parent.getCellType().createNet("vcc_tie_o");
        tied.createPortInst("O", tieInst);
        EDIFPortInst ce = ff.getEDIFHierCellInst().getInst().getPortInst("CE");
        ce.getNet().removePortInst(ce);
        tied.addPortInst(ce);
        Assertions.assertTrue(SiteRouting.isTiedTo(ff, "CE", NetType.VCC));
        Assertions.assertFalse(SiteRouting.isTiedTo(ff, "CE", NetType.GND));
    }

    @Test
    public void testReportRouteStatusCreatesNoStaticNets() {
        // Without VCC, the flip-flops' clock enable ties are missing: looking for the routing of
        // VCC to them does not create it
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        design.removeNet(design.getVccNet());
        Assertions.assertNull(design.getNet(Net.VCC_NET));
        ReportRouteStatus.reportRouteStatus(design);
        Assertions.assertNull(design.getNet(Net.VCC_NET));
    }
}
