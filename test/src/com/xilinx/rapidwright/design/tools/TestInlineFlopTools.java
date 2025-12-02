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
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.xdc.ConstraintTools;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.eco.ECOPlacementHelper;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestInlineFlopTools {

    private static Pair<Site, BEL> nextAvailPlacement(Design design, Iterator<Site> itr, SLR slr, String bel) {
        while (itr.hasNext()) {
            Site curr = itr.next();
            if (slr != null && curr.getTile().getSLR() != slr) {
                continue;
            }
            SiteInst candidate = design.getSiteInstFromSite(curr);
            if (candidate == null) {
                return new Pair<>(curr, curr.getBEL(bel));
            }
        }
        return null;
    }

    private static void createAndPlaceFlopInlineAtSpecificBEL(Design design, String clkNet, EDIFPort port, String bel,
                                                              PBlock keepOut) {
        Site start = keepOut.getAllSites("SLICE").iterator().next();
        boolean exclude = true;

        EDIFHierNet clk = design.getNetlist().getHierNetFromName(clkNet);

        Set<SiteInst> siteInstsToRoute = new HashSet<>();

        for (int i : port.getBitBlastedIndices()) {
            EDIFPortInst inst = port.getInternalPortInstFromIndex(i);

            Iterator<Site> siteItr = ECOPlacementHelper.spiralOutFrom(start, keepOut, exclude).iterator();
            siteItr.next(); // Skip the first site, as we are suggesting one inside the pblock
            Pair<Site, BEL> loc = nextAvailPlacement(design, siteItr, null, bel);
            Cell flop = InlineFlopTools.createAndPlaceFlopInlineOnTopPortInst(design, inst, loc, clk);
            siteInstsToRoute.add(flop.getSiteInst());
        }
        for (SiteInst si : siteInstsToRoute) {
            si.routeSite();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,AFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,AFF2,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,BFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,BFF2,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,CFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,CFF2,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,DFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,DFF2,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,EFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,EFF2,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,FFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,FFF2,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,GFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,GFF2,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,HFF,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcv80-lsva4737-2MHP-e-S,SLICE_X96Y803/AFF,HFF2,IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,AFF,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,AFF2,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,BFF,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,BFF2,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,CFF,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,CFF2,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,DFF,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,DFF2,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,EFF,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,EFF2,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,FFF,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,FFF2,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,GFF,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,GFF2,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,HFF,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xcvu3p-ffvc1517-2-i,SLICE_X14Y237/AFF,HFF2,RAMB36_X1Y47:RAMB36_X1Y47 RAMB18_X1Y94:RAMB18_X1Y95 SLICE_X13Y235:SLICE_X16Y239",
            "xc7a200tsbg484-1,SLICE_X60Y124/AFF,AFF,RAMB36_X3Y22:RAMB36_X3Y27 RAMB18_X3Y44:RAMB18_X3Y55 DSP48_X2Y44:DSP48_X3Y55 SLICE_X46Y110:SLICE_X67Y139",
            "xc7a200tsbg484-1,SLICE_X60Y124/AFF,A5FF,RAMB36_X3Y22:RAMB36_X3Y27 RAMB18_X3Y44:RAMB18_X3Y55 DSP48_X2Y44:DSP48_X3Y55 SLICE_X46Y110:SLICE_X67Y139",
            "xc7a200tsbg484-1,SLICE_X60Y124/AFF,BFF,RAMB36_X3Y22:RAMB36_X3Y27 RAMB18_X3Y44:RAMB18_X3Y55 DSP48_X2Y44:DSP48_X3Y55 SLICE_X46Y110:SLICE_X67Y139",
            "xc7a200tsbg484-1,SLICE_X60Y124/AFF,B5FF,RAMB36_X3Y22:RAMB36_X3Y27 RAMB18_X3Y44:RAMB18_X3Y55 DSP48_X2Y44:DSP48_X3Y55 SLICE_X46Y110:SLICE_X67Y139",
            "xc7a200tsbg484-1,SLICE_X60Y124/AFF,CFF,RAMB36_X3Y22:RAMB36_X3Y27 RAMB18_X3Y44:RAMB18_X3Y55 DSP48_X2Y44:DSP48_X3Y55 SLICE_X46Y110:SLICE_X67Y139",
            "xc7a200tsbg484-1,SLICE_X60Y124/AFF,C5FF,RAMB36_X3Y22:RAMB36_X3Y27 RAMB18_X3Y44:RAMB18_X3Y55 DSP48_X2Y44:DSP48_X3Y55 SLICE_X46Y110:SLICE_X67Y139",
            "xc7a200tsbg484-1,SLICE_X60Y124/AFF,DFF,RAMB36_X3Y22:RAMB36_X3Y27 RAMB18_X3Y44:RAMB18_X3Y55 DSP48_X2Y44:DSP48_X3Y55 SLICE_X46Y110:SLICE_X67Y139",
            "xc7a200tsbg484-1,SLICE_X60Y124/AFF,D5FF,RAMB36_X3Y22:RAMB36_X3Y27 RAMB18_X3Y44:RAMB18_X3Y55 DSP48_X2Y44:DSP48_X3Y55 SLICE_X46Y110:SLICE_X67Y139",
    })
    public void testRemoveInlineFlops(String partName, String ffBel, String harnessBel, String pblockRange) {
        Design design = new Design("inline_flops", partName);
        design.setDesignOutOfContext(true);
        design.setAutoIOBuffers(false);
        EDIFCell topCell = design.getNetlist().getTopCell();
        // Create clock port and net
        EDIFPort clkPort = topCell.createPort("clk", EDIFDirection.INPUT, 1);
        Net clk = design.createNet("clk");
        EDIFHierNet logicalClkNet = clk.getLogicalHierNet();
        logicalClkNet.getNet().createPortInst(clkPort);

        // Create in port and net
        EDIFPort inPort = topCell.createPort("in", EDIFDirection.INPUT, 1);
        Net inNet = design.createNet("in");
        EDIFHierNet logicalInNet = inNet.getLogicalHierNet();
        logicalInNet.getNet().createPortInst(inPort);

        // Create out port and net
        EDIFPort outPort = topCell.createPort("out", EDIFDirection.OUTPUT, 1);
        Net outNet = design.createNet("out");
        EDIFHierNet logicalOutNet = outNet.getLogicalHierNet();
        logicalOutNet.getNet().createPortInst(outPort);

        Cell flop0 = design.createAndPlaceCell(design.getTopEDIFCell(), "flop0",
                Unisim.FDRE, ffBel);
        inNet.connect(flop0, "D");
        outNet.connect(flop0, "Q");
        design.getGndNet().connect(flop0, "R");
        design.getVccNet().connect(flop0, "CE");
        clk.connect(flop0, "C");

        PBlock pblock = new PBlock(design.getDevice(), pblockRange);
        createAndPlaceFlopInlineAtSpecificBEL(design, "clk", inPort, harnessBel, pblock);
        design.getNetlist().resetParentNetMap();
        createAndPlaceFlopInlineAtSpecificBEL(design, "clk", outPort, harnessBel, pblock);
        InlineFlopTools.removeInlineFlops(design);
        for (Cell c : design.getCells()) {
            Assertions.assertFalse(c.getName().endsWith(InlineFlopTools.INLINE_SUFFIX));
        }
        Assertions.assertEquals(1, design.getVccNet().getSinkPins().size());
    }

    @Test
    public void testArbitraryInlineFlopPlacement() {
        Design design = RapidWrightDCP.loadDCP("PicoBlazeArray/pblock0.dcp");
        Map<String, PBlock> pblocks = ConstraintTools.getPBlocksFromXDC(design);
        PBlock pblock = pblocks.get("pe_pblock_1");
        design.unrouteDesign();
        design.unplaceDesign();
        InlineFlopTools.createAndPlaceFlopsInlineOnTopPortsArbitrarily(design, "clk", pblock);
        int count = 0;
        for (Cell c : design.getCells()) {
            if (c.isPlaced()) {
                count++;
                Assertions.assertTrue(c.getName().contains(InlineFlopTools.INLINE_SUFFIX));
                Assertions.assertFalse(c.getName().contains("clk"));
            }
        }
        Assertions.assertEquals(65, count);
    }

    @Test
    public void testCentroidInlineFlopPlacement() {
        Design design = RapidWrightDCP.loadDCP("PicoBlazeArray/pblock0.dcp");
        Map<String, PBlock> pblocks = ConstraintTools.getPBlocksFromXDC(design);
        PBlock pblock = pblocks.get("pe_pblock_1");
        InlineFlopTools.createAndPlaceFlopsInlineOnTopPortsNearPins(design, "clk", pblock);
        int count = 0;
        for (Cell c : design.getCells()) {
            if (c.isPlaced()) {
                if (c.getName().contains(InlineFlopTools.INLINE_SUFFIX)) {
                    count++;
                    Assertions.assertFalse(c.getName().contains("clk"));
                }
            }
        }
        Assertions.assertEquals(65, count);
    }

}
