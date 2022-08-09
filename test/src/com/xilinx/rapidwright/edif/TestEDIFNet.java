package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFNet {

    @Test
    void testEquals() {
        String designName = "design";
        final EDIFNetlist netlist = EDIFTools.createNewNetlist(designName);
        final Design design = new Design(designName, Device.KCU105);
        design.setNetlist(netlist);

        EDIFCell ec1 = new EDIFCell(netlist.getWorkLibrary(), "ec1");
        EDIFCell ec2 = new EDIFCell(netlist.getWorkLibrary(), "ec2");

        EDIFNet en1 = ec1.createNet("foo");
        EDIFNet en2 = ec2.createNet("foo");

        Assertions.assertNotEquals(en1, en2);
    }
}
