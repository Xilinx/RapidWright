package com.xilinx.rapidwright.design.tools;


import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFTools;

import java.util.ArrayList;
import java.util.List;

public class EDIFPortBusTest {
    public static void main(String[] args) {
        Design d = Design.readCheckpoint("/group/zircon2/abutt/sa_tile_synth_2025_1.dcp");
//        EDIFTools.writeEDIFFile("test1.edf", d.getNetlist(), "xcv80-lsva4737-2MHP-e-S");
        EDIFCell top = d.getNetlist().getTopCell();
        for (EDIFPort p : d.getNetlist().getTopCell().getPorts()) {
//            System.out.println(p);
            for (int i = 0; i < p.getWidth(); i++) {
                EDIFNet net = p.getInternalNet(i);

                if (net != null) {
                    System.out.println(p + " net=" + net + " | portInsts=" + net.getPortInsts());
                } else {
                    System.out.println(p);
                }
            }
        }
        System.out.println("==============================");
        List<String> portsToRename = new ArrayList<>();
        for (EDIFPort p : top.getPorts()) {
            if (!p.isBus() && !p.getName().startsWith("[]")) {
                portsToRename.add(p.getName());
            }
        }
        for (String p : portsToRename) {
            top.renamePort(p, "[]" + p);
        }
        for (EDIFPort p : d.getNetlist().getTopCell().getPorts()) {
//            System.out.println(p);
            for (int i = 0; i < p.getWidth(); i++) {
                EDIFNet net = p.getInternalNet(i);

                if (net != null) {
                    System.out.println(p + " net=" + net + " | portInsts=" + net.getPortInsts());
                } else {
                    System.out.println(p);
                }
            }
        }
        d.writeCheckpoint("test.dcp");
        EDIFTools.writeEDIFFile("test2.edf", d.getNetlist(), "xcv80-lsva4737-2MHP-e-S");
        System.out.println();
    }
}