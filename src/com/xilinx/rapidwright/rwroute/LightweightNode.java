/* 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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
import com.xilinx.rapidwright.device.PIP;

import java.util.Objects;

class LightweightNode {
    final int tileID;
    final int wireID;

    public LightweightNode(Node node) {
        assert(!node.isInvalidNode());
        this.tileID = node.getTile().getUniqueAddress();
        this.wireID = node.getWire();
    }

    public LightweightNode(SitePinInst pin) {
        this(pin.getConnectedNode());
    }

    @Override
    public boolean equals(Object that) {
        if (this == that)
            return true;
        if (that == null)
            return false;
        if (that.getClass() != getClass())
            return false;
        LightweightNode thatNode = (LightweightNode) that;
        return tileID == thatNode.tileID && wireID == thatNode.wireID;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tileID, wireID);
    }
}
