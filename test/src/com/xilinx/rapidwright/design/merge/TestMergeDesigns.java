/* 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
 
package com.xilinx.rapidwright.design.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.merge.MergeDesigns;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Pair;

public class TestMergeDesigns {

    private static final String partName = "xczu3eg-sfva625-2-e";
    
    public static Pair<Design, Design> createDesignsToMerge(boolean reverse) {
        Pair<Design, Design> designs = new Pair<>();
        for(String name : new String[] {"a", "b"}) {
            Design a = new Design(name, partName);
            int i = name.equals("a") ? 0 : 1;
            Cell ff0 = a.createAndPlaceCell("ff" + i, Unisim.FDRE, "SLICE_X"+i+"Y0/AFF");
            Cell ff1 = a.createAndPlaceCell("ff" + (i+1), Unisim.FDRE, "SLICE_X"+(i+1)+"Y0/AFF");
            Cell testBufg = a.createAndPlaceCell("test_bufg", Unisim.BUFGCE, "BUFGCE_X0Y0/BUFCE");

            Net ff0Out = a.createNet("ff"+i+"_q");
            ff0Out.connect(ff0, "Q");
            ff0Out.connect(ff1, "D");
            
            Net ff1Out = a.createNet("ff"+(i+1)+"_q");
            ff1Out.connect(ff1, "Q");
            Net ff0In = a.createNet("ff"+i+"_d");
            ff0In.connect(ff0, "D");
            
            Net clkIn = a.createNet("clk_in");
            Net clk = a.createNet("clk");
            
            String inPortPrefix = i == 0 ? "in" : "common";
            String outPortPrefix = i == 0 ? "common" : "out"; 
            EDIFPort in0 = a.getTopEDIFCell().createPort(inPortPrefix + "0", EDIFDirection.INPUT, 1);
            EDIFPort clkPort = a.getTopEDIFCell().createPort("clk_in", EDIFDirection.INPUT, 1);
            EDIFPort out0 = a.getTopEDIFCell().createPort(outPortPrefix + "0", EDIFDirection.OUTPUT, 1);
            ff0In.getLogicalNet().createPortInst(in0);
            ff1Out.getLogicalNet().createPortInst(out0);
            clkIn.getLogicalNet().createPortInst(clkPort);
            clkIn.connect(testBufg, "I");
            clk.connect(testBufg,"O");
            clk.connect(ff0, "C");
            clk.connect(ff1, "C");
            
            Net vcc = a.getVccNet();
            vcc.setLogicalNet(EDIFTools.getStaticNet(NetType.VCC, a.getTopEDIFCell(), a.getNetlist()));
            vcc.connect(ff0, "CE");
            vcc.connect(ff1, "CE");
            vcc.connect(testBufg, "CE");
            Net gnd = a.getGndNet();
            gnd.setLogicalNet(EDIFTools.getStaticNet(NetType.GND, a.getTopEDIFCell(), a.getNetlist()));
            gnd.connect(ff0, "R");
            gnd.connect(ff1, "R");
            
            a.routeSites();
            Router r = new Router(a);
            r.routeDesign();
            
            a.setAutoIOBuffers(false);
            a.setDesignOutOfContext(true);
            if(name.equals("a")) {
                designs.setFirst(a);
            }else {
                designs.setSecond(a);
            }
            
        }
        if(reverse) {
            Design tmp = designs.getFirst();
            designs.setFirst(designs.getSecond());
            designs.setSecond(tmp);
        }
        
        return designs;
    }

    private static int countMergedNets(EDIFCell ... topCells) {
        HashSet<String> nets = new HashSet<>();
        for(EDIFCell top : topCells) {
            for(EDIFNet net : top.getNets()) {
                nets.add(net.getName());
            }
        }
        return nets.size();
    }
    
    private static int countMergedInsts(EDIFCell ... topCells) {
        HashSet<String> insts = new HashSet<>();
        for(EDIFCell top : topCells) {
            for(EDIFCellInst inst : top.getCellInsts()) {
                insts.add(inst.getName());
            }
        }
        return insts.size();
    }
    
    private static int countMergedPorts(EDIFCell ... topCells) {
        HashSet<String> ports = new HashSet<>();
        for(EDIFCell top : topCells) {
            for(EDIFPort port : top.getPorts()) {
                ports.add(port.getName());
            }
        }
        return ports.size();
    }
    
    private static int countMergedSiteInsts(Design ... designs) {
        HashSet<String> sites = new HashSet<>();
        for(Design design : designs) {
            for(SiteInst siteInst : design.getSiteInsts()) {
                if(siteInst.isPlaced()) {
                    sites.add(siteInst.getSiteName());
                }
            }
        }
        return sites.size();
    }
    
    @Test
    @CheckOpenFiles
    public void testMergePicoblaze() throws IOException {
        Design design0 = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp"));
        Design design1 = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp"));
        EDIFCell top0 = design0.getTopEDIFCell();
        EDIFCell top1 = design1.getTopEDIFCell();
        
        Design merged = MergeDesigns.mergeDesigns(design0, design1);
        
        EDIFCell top = merged.getTopEDIFCell();

        // Check the merged 'reset' net
        String reset = "reset";
        Assertions.assertEquals(top.getNet(reset).getPortInsts().size(), 
                                top0.getNet(reset).getPortInsts().size() + 
                                top1.getNet(reset).getPortInsts().size() - 1);
        
        Assertions.assertEquals(top.getCellInsts().size(), countMergedInsts(top0, top1));
        Assertions.assertEquals(top.getNets().size(), countMergedNets(top0, top1));
        Assertions.assertEquals(top.getPorts().size(), countMergedPorts(top0, top1));
        Assertions.assertEquals(merged.getSiteInsts().size(), countMergedSiteInsts(design0, design1));
    }
    
    @ParameterizedTest
    @ValueSource(booleans = {false, true}) 
    public void testMergeDesign(boolean reverseDesignOrder) {
        Pair<Design,Design> designs = createDesignsToMerge(reverseDesignOrder);
        Design merged = MergeDesigns.mergeDesigns(designs.getFirst(), designs.getSecond());
        
        EDIFCell top = merged.getNetlist().getTopCell();
        EDIFCell top0 = designs.getFirst().getTopEDIFCell();
        EDIFCell top1 = designs.getSecond().getTopEDIFCell();
        
        Assertions.assertEquals(top.getCellInsts().size(), countMergedInsts(top0, top1));
        Assertions.assertEquals(top.getNets().size(), countMergedNets(top0, top1));
        Assertions.assertEquals(top.getPorts().size(), countMergedPorts(top0, top1));
        Assertions.assertEquals(merged.getSiteInsts().size(), 
                countMergedSiteInsts(designs.getFirst(), designs.getSecond()));
        
        
        Assertions.assertNull(top.getNet("ff1_d"));
    }
}
