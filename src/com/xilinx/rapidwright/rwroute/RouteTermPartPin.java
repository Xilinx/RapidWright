package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;

public class RouteTermPartPin implements RouteTerm {
    private Node node;
    private boolean isRouted = false;

    RouteTermPartPin(Node node) {
        this.node = node;
    }

    @Override
    public String getName() {
        return toString();
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
        RouteTermPartPin that = (RouteTermPartPin) obj;
        return node.equals(that.node);
    }
}
