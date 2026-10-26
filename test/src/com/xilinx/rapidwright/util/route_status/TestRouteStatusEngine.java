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

package com.xilinx.rapidwright.util.route_status;

import com.xilinx.rapidwright.design.Design;
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
}
