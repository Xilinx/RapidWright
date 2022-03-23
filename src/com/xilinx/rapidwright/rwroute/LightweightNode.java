package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;

class LightweightNode {
    private final int tileID;
    private final int wireID;

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
        if (that.getClass() == getClass())
            return equals((LightweightNode) that);
        if (that.getClass() == Node.class)
            return equals((Node) that);
        return false;
    }

    public boolean equals(LightweightNode that) {
        return tileID == that.tileID && wireID == that.wireID;
    }

    public boolean equals(Node that) {
        return tileID == that.getTile().getUniqueAddress() && wireID == that.getWire();
    }

    @Override
    public int hashCode() {
        // Same as Node.hashCode()
        final int prime = 31;
        int result = 1;
        result = prime * result + tileID;
        result = prime * result + wireID;
        return result;
    }
}
