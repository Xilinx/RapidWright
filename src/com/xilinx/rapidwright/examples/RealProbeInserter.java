/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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
package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.tools.LUTTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Example code to insert the RealProbe IP
 */
public class RealProbeInserter {
    public static void main(String[] args) {
        // Original HLS design without RealProbe (this would be the input design)
        Design original = Design.readCheckpoint("design_1_wrapper_routed.dcp");

        // The synthesized RealProbe IP
        Design realProbeIP = Design.readCheckpoint("design_1_realprobe_ip_0_0.dcp");

        // The synthesized BRAM Ctrl IP
        Design bramCtrl = Design.readCheckpoint("design_1_axi_bram_ctrl_0_0.dcp");

        // References to the logical netlist object and top instance we want to modify
        EDIFNetlist netlist = original.getNetlist();
        EDIFTools.uniqueifyNetlist(original);
        
        EDIFCell top = original.getTopEDIFCell().getCellInst("design_1_i").getCellType();
        
        // Pre-requisite step to copy netlist elements from the RealProbe and BRAM Ctrl IP over to the target netlist
        netlist.copyCellAndSubCells(realProbeIP.getTopEDIFCell());
        netlist.copyCellAndSubCells(bramCtrl.getTopEDIFCell());
        
        // Create actual logical instances of the Real Probe IP and BRAM Ctrl in the target netlist
        EDIFCellInst realProbeInst = top.createChildCellInst("real_probe_ip_0", netlist.getCell(realProbeIP.getTopEDIFCell().getName()));
        EDIFCellInst bramCtrlInst = top.createChildCellInst("axi_bram_ctrl_0", netlist.getCell(bramCtrl.getTopEDIFCell().getName()));
        
        // Example to connect design_1_i/realprobe_ip_0/axi_rdata_32b -> design_1_i/axi_bram_ctrl_0/bram_rddata_a
        EDIFPort src = realProbeInst.getPort("axi_rdata_32b");
        EDIFPort snk = bramCtrlInst.getPort("bram_rddata_a");
        for (int i=0; i < 32; i++) {
            EDIFNet net = top.createNet("realprobe_ip_0_axi_rdata_32b["+i+"]");
            net.createPortInst(src, i, realProbeInst);
            net.createPortInst(snk, i, bramCtrlInst);
        }
        
        // Reconstruct missing LUT2 to generate ap_done_out input
        EDIFHierCellInst targetParent = netlist.getHierCellInstFromName("design_1_i/fxp_sqrt_top_0/inst/b_port_m_axi_U/store_unit/user_resp");
        EDIFCellInst lut2 = targetParent.getCellType().createChildCellInst("ap_done_out_INST_0", netlist.getHDIPrimitive(Unisim.LUT2));
        LUTTools.configureLUT(lut2, "O=I0 & I1");
        targetParent.getNet("dout_vld_reg_0").getNet().createPortInst("I0", lut2);
        targetParent.getNet("Q[1]").getNet().createPortInst("I1", lut2);
        
        // Connect the output of the LUT to the RealProbe IP
        EDIFHierCellInst hierRealProbeParent = netlist.getHierCellInstFromName("design_1_i");
        EDIFHierPortInst apDoneOut = new EDIFHierPortInst(hierRealProbeParent, realProbeInst.getOrCreatePortInst("ap_done_out"));
        EDIFHierPortInst lut2Output = new EDIFHierPortInst(targetParent, lut2.getOrCreatePortInst("O"));
        EDIFTools.connectPortInstsThruHier(lut2Output, apDoneOut, "ap_done_out");
        
        original.writeCheckpoint("original_plus_realprobe.dcp");
    }
}
