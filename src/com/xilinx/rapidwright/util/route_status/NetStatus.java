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

import com.xilinx.rapidwright.util.ReportRouteStatusResult.NetRouteStatus;

/**
 * The statuses of a net in the route status engine, as a bit mask: one bit per {@link NetRouteStatus}
 * (by ordinal), and some internal ones that are not listed.
 */
final class NetStatus {

    static final int UNPLACED_ALL = bit(NetRouteStatus.UNPLACED_ALL);
    static final int UNPLACED = bit(NetRouteStatus.UNPLACED);
    static final int UNROUTED = bit(NetRouteStatus.UNROUTED);
    static final int PARTIAL = bit(NetRouteStatus.PARTIAL);
    static final int GAPS = bit(NetRouteStatus.GAPS);
    static final int HIERPORT = bit(NetRouteStatus.HIERPORT);
    static final int CONFLICTS = bit(NetRouteStatus.CONFLICTS);
    static final int ANTENNAS = bit(NetRouteStatus.ANTENNAS);
    static final int NODRIVER = bit(NetRouteStatus.NODRIVER);
    static final int LOCKED_NODES = bit(NetRouteStatus.LOCKED_NODES);
    static final int HAS_ROUTING = bit(NetRouteStatus.HAS_ROUTING);
    static final int INTRASITE = bit(NetRouteStatus.INTRASITE);
    static final int NOLOADS = bit(NetRouteStatus.NOLOADS);
    static final int ROUTED = bit(NetRouteStatus.ROUTED);

    /** Internal status (not listed): a net with no placed pins, but with routing */
    static final int NO_PLACED_PINS_ROUTED = 1 << 30;

    /**
     * Internal status (not listed): a net without a physical net whose placed loads claim input
     * site pins (see {@link Entries#driverPinKeys})
     */
    static final int LOGICAL_ONLY = 1 << 29;

    /**
     * Internal status of an output net of a clock buffer that shares the routing of another output
     * (see {@link VersalRules#shareClockBufferRouting}): counted as fully routed, but not listed as such
     */
    static final int ROUTED_UNLISTED = 1 << 28;

    /**
     * Internal status of a static net while conflicts are marked: its site pins (from its site
     * routing) are claims, and it is partially routed where the routing of another net uses one
     */
    static final int STATIC_CLAIMS = 1 << 27;

    private NetStatus() {
    }

    static int bit(NetRouteStatus s) {
        return 1 << s.ordinal();
    }

    static boolean has(int status, int bits) {
        return (status & bits) != 0;
    }
}
