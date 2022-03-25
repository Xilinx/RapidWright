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

    public LightweightNode(PIP pip, boolean start) {
        this((start) ? pip.getStartNode() : pip.getEndNode());
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
