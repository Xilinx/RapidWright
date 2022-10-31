package com.xilinx.rapidwright.rwroute;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;

public interface RouteTerm {
    String getName();

    Node getConnectedNode();

    SitePinInst getSitePinInst();

    void setRouted(boolean isRouted);
    boolean isRouted();
}
