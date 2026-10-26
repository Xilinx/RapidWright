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

import static com.xilinx.rapidwright.util.route_status.NodeKeys.nodeKey;

import java.util.HashSet;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;

/**
 * The routing of the GND and VCC nets of a design, to find the site pins that it ties to a constant.
 */
final class StaticRouting {

    /** Kinds of missing ties, as found by the rules of each series */
    static final int VCC_TIE = 1;

    static final int GND_TIE = 2;

    private final Design design;

    /** The nodes that the routing of GND and VCC leads to, see {@link #isRoutingTo} */
    private Set<Long> staticRoutingEnds;

    StaticRouting(Design design) {
        this.design = design;
    }

    /** Checks whether the routing of GND or VCC leads to a site pin (even without site routing) */
    boolean isRoutingTo(SiteInst si, String sitePinName) {
        if (staticRoutingEnds == null) {
            staticRoutingEnds = new HashSet<>();
            for (Net net : new Net[]{design.getGndNet(), design.getVccNet()}) {
                for (PIP pip : net.getPIPs()) {
                    Node end = pip.isReversed() ? pip.getStartNode() : pip.getEndNode();
                    if (end != null) {
                        staticRoutingEnds.add(nodeKey(end));
                    }
                }
            }
        }
        int pinIndex = si.getSite().getPinIndex(sitePinName);
        Node node = (pinIndex < 0) ? null : si.getSite().getConnectedNode(pinIndex);
        return node != null && staticRoutingEnds.contains(nodeKey(node));
    }
}
