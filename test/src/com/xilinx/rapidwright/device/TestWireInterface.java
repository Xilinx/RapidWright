package com.xilinx.rapidwright.device;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestWireInterface {

    @Test
    public void testWireInterface() {
        Device device = Device.getDevice(Device.KCU105);

//        get_nodes INT_X45Y18/WW2_W_BEG1
//        INT_X45Y18/WW2_W_BEG1
//        get_wires -of [get_nodes INT_X45Y18/WW2_W_BEG1]
//        INT_X45Y18/WW2_W_BEG1 INT_X44Y18/WW2_W_END1 CLEL_R_X44Y18/EASTBUSOUT_FT1_17 FSR_GAP_X44Y18/EASTBUSOUT_FT1_17 CLE_M_X45Y18/EASTBUSOUT_FT1_17 CFRM_CBRK_L_X45Y0/EASTBUSOUT_FT1_18_17

        Node node = device.getNode("INT_X45Y18/WW2_W_BEG1");
        Wire[] wires = node.getAllWiresInNode();

        // Node vs. Wire
        for (int i = 0; i < wires.length; i++) {
            Assertions.assertEquals(i == 0, node.getTile().equals(wires[i].getTile()));
            Assertions.assertEquals(i == 0, node.getWireIndex() == wires[i].getWireIndex());
        }

        // Node vs. WireInterface
        WireInterface[] wireInts = wires;
        for (int i = 0; i < wireInts.length; i++) {
            Assertions.assertEquals(i == 0, node.hashCode() == wireInts[i].hashCode());
            Assertions.assertEquals(i == 0, node.equals(wireInts[i]));
            Assertions.assertEquals(i == 0, wireInts[i].equals(node));
        }

        // WireInterface vs. Wire
        WireInterface wireInt = node;
        for (int i = 0; i < wires.length; i++) {
            Assertions.assertEquals(i == 0, wireInt.hashCode() == wires[i].hashCode());
            Assertions.assertEquals(i == 0, wireInt.equals(wires[i]));
            Assertions.assertEquals(i == 0, wires[i].equals(wireInt));
        }

    }
}
