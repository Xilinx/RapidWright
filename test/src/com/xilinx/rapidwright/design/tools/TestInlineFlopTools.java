/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Research and Advanced Development
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

package com.xilinx.rapidwright.design.tools;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.CodeGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

public class TestInlineFlopTools {
    @ParameterizedTest
    @CsvSource({
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,AFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
    })
//    @Test
    public void testRemoveInlineFlops(String partName, String ffBel, String harnessBel, String pblockRange) {
        Design design = new Design("inline_flops", partName);
        design.setDesignOutOfContext(true);
        design.setAutoIOBuffers(false);
        EDIFCell topCell = design.getNetlist().getTopCell();
        // Create clock port and net
        EDIFPort clkPort = topCell.createPort("clk", EDIFDirection.INPUT, 1);
        EDIFNet clkNet = topCell.createNet("clk");
        clkNet.createPortInst(clkPort);
        EDIFHierNet logicalClkNet = new EDIFHierNet(design.getNetlist().getTopHierCellInst(), clkPort.getInternalNet());
        Net clk = design.createNet(logicalClkNet);

        // Create in port and net
        EDIFPort inPort = topCell.createPort("in", EDIFDirection.INPUT, 1);
        EDIFNet logicalInNet = topCell.createNet("in");
        logicalInNet.createPortInst(inPort);
        EDIFHierNet hierInNet = new EDIFHierNet(design.getNetlist().getTopHierCellInst(), inPort.getInternalNet());
        Net inNet = design.createNet(hierInNet);

        // Create out port and net
        EDIFPort outPort = topCell.createPort("out", EDIFDirection.OUTPUT, 1);
        EDIFNet logicalOutNet = topCell.createNet("out");
        logicalOutNet.createPortInst(outPort);
        EDIFHierNet hierOutNet = new EDIFHierNet(design.getNetlist().getTopHierCellInst(), outPort.getInternalNet());
        Net outNet = design.createNet(hierOutNet);

        Cell flop0 = design.createAndPlaceCell(design.getTopEDIFCell(), "flop0",
                Unisim.FDRE, ffBel);
        inNet.connect(flop0, "D");
        outNet.connect(flop0, "Q");
        design.getGndNet().connect(flop0, "R");
        design.getVccNet().connect(flop0, "CE");
        clk.connect(flop0, "C");

        PBlock pblock = new PBlock(design.getDevice(), pblockRange);
        InlineFlopTools.createAndPlaceFlopsInlineOnTopPortsArbitrarily(design, "clk", pblock);
//        InlineFlopTools.removeInlineFlops(design);
//        for (Cell c : design.getCells()) {
//            Assertions.assertFalse(c.getName().endsWith(InlineFlopTools.INLINE_SUFFIX));
//        }
        design.writeCheckpoint("/group/zircon2/abutt/picoblaze_test.dcp");
    }
}
