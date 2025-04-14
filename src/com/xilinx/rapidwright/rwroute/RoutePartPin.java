/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Tile;

public class RoutePartPin implements RoutePinInterface {
    private Node node;
    private boolean isRouted = false;

    RoutePartPin(Node node) {
        this.node = node;
    }

    @Override
    public String getName() {
        return toString();
    }

    @Override
    public Tile getTile() {
        return node.getTile();
    }

    @Override
    public Node getConnectedNode() {
        return node;
    }

    @Override
    public SitePinInst getSitePinInst() {
        return null;
    }

    @Override
    public boolean isLUTInputPin() {
        return false;
    }

    @Override
    public void setRouted(boolean isRouted) {
        this.isRouted = isRouted;
    }

    @Override
    public boolean isRouted() {
        return isRouted;
    }

    @Override
    public String toString() {
        return node.toString();
    }

    @Override
    public int hashCode() {
        return node.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RoutePartPin that = (RoutePartPin) obj;
        return node.equals(that.node);
    }
}
