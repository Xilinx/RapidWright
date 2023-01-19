/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;

public enum RouteNodeType {
    /**
     * Denotes {@link RouteNode} objects that correspond to the output pins of {@link Net} Objects,
     * typically the source {@link RouteNode} Objects of {@link Connection} Objects.
     */
    PINFEED_O,
    /**
     * Denotes {@link RouteNode} objects that correspond to input pins of {@link Net} Objects,
     * typically the sink {@link RouteNode} Objects of {@link Connection} Objects.
     */
    PINFEED_I,
    /**
     * Denotes {@link RouteNode} objects that are created based on {@link Node} Objects
     * that have an {@link IntentCode} of NODE_PINBOUNCE.
     */
    PINBOUNCE,

    /**
     * Denotes {@link RouteNode} objects that correspond to a super long line {@link Node},
     * i.e. nodes that span two SLRs.
     */
    SUPER_LONG_LINE,

    /**
     * Denotes {@link RouteNode} objects that correspond to {@link Node} objects that enter
     * a Laguna tile from an INT tile, or those Laguna tile nodes leading to a SUPER_LONG_LINE.
     */
    LAGUNA_I,

    /**
     * Denotes other wiring {@link RouteNode} Objects
     * that are created for routing {@link Connection} Objects.
     */
    WIRE

}
