package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.device.Device;

public class TestDesignHelper {

    public static Net createTestNet(Design design, String netName, String[] pips) {
        Net net = design.createNet(netName);
        TestDesignHelper.addPIPs(net, pips);
        return net;
    }

    public static void addPIPs(Net net, String[] pips) {
        Device device = net.getDesign().getDevice();
        for (String pip : pips) {
            net.addPIP(device.getPIP(pip));
        }
    }

}
