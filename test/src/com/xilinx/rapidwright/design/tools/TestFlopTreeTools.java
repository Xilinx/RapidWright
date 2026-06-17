/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
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
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tests for {@link FlopTreeTools}, exercised against a real placed Versal (xcv80,
 * 3-SLR) design with a clock and cross-SLR nets.
 */
public class TestFlopTreeTools {

    private static final String DCP = "versal_slr_crossing.dcp";
    private static final String CLK = "clk";
    private static final String CROSS_SLR_NET = "x[0].y[0].u_tile/x[2].y[3].u_pe/accum_outputs[2][3]";
    private static final String CHAIN_NET = "x[0].y[0].u_tile/x[2].y[3].u_pe/accum_outputs[2][4]";

    private static Set<String> cellNames(Design design) {
        Set<String> names = new HashSet<>();
        for (Cell c : design.getCells()) {
            names.add(c.getName());
        }
        return names;
    }

    /** FDRE cells present now but not in {@code before} (i.e. inserted by the tool). */
    private static List<Cell> insertedFlops(Design design, Set<String> before) {
        List<Cell> flops = new ArrayList<>();
        for (Cell c : design.getCells()) {
            if (!before.contains(c.getName()) && c.getType().startsWith("FD")) {
                flops.add(c);
            }
        }
        return flops;
    }

    private static String portId(EDIFHierPortInst p) {
        return p.getFullHierarchicalInstName() + "/" + p.getPortInst().getName();
    }

    /** Each inserted flop must be placed and wired as a transparent register: R=GND, CE=VCC, C=clk. */
    private static void assertBufferFlops(Design design, List<Cell> flops) {
        EDIFNet gnd = EDIFTools.getStaticNet(NetType.GND, design.getTopEDIFCell(), design.getNetlist());
        EDIFNet vcc = EDIFTools.getStaticNet(NetType.VCC, design.getTopEDIFCell(), design.getNetlist());
        for (Cell c : flops) {
            Assertions.assertTrue(c.isPlaced(), c.getName() + " was not placed");
            EDIFCellInst inst = c.getEDIFCellInst();
            Assertions.assertSame(gnd, inst.getPortInst("R").getNet());
            Assertions.assertSame(vcc, inst.getPortInst("CE").getNet());
            Assertions.assertEquals(CLK, inst.getPortInst("C").getNet().getName());
        }
    }

    /**
     * A SLICE whose set/reset site pin is already driven by VCC cannot host an
     * inserted FDRE (whose R must be GND); the control-set guard must reject it.
     */
    @Test
    public void controlSetRejectsSiteWithVccReset() {
        Design design = RapidWrightDCP.loadDCP(DCP);
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X90Y549");
        Assertions.assertNotNull(si);
        Assertions.assertFalse(
                FlopTreeTools.isControlSetCompatibleForInsertedFDRE(si, si.getBEL("AFF")));
    }

    /** A SLICE with no conflicting CE/SR site pins is a valid FDRE host. */
    @Test
    public void controlSetAcceptsCompatibleSite() {
        Design design = RapidWrightDCP.loadDCP(DCP);
        SiteInst si = design.getSiteInstFromSiteName("SLICE_X102Y329");
        Assertions.assertNotNull(si);
        Assertions.assertTrue(
                FlopTreeTools.isControlSetCompatibleForInsertedFDRE(si, si.getBEL("AFF")));
    }

    /**
     * insertFlopChain inserts exactly {@code depth} buffer flops on a net and rewires it so
     * the source drives the chain and the chain drives the original sink.
     */
    @Test
    public void insertFlopChainBuffersNetThroughChain() {
        Design design = RapidWrightDCP.loadDCP(DCP);
        Net net = design.getNet(CHAIN_NET);
        Assertions.assertNotNull(net);
        List<EDIFHierPortInst> sinks = net.getLogicalHierNet().getLeafHierPortInsts(false, true);
        Assertions.assertEquals(1, sinks.size());
        String origSinkId = portId(sinks.get(0));

        Set<String> before = cellNames(design);
        int depth = 3;
        Net out = FlopTreeTools.insertFlopChain(design, net, CLK, depth, sinks, new HashSet<>());

        List<Cell> inserted = insertedFlops(design, before);
        Assertions.assertEquals(depth, inserted.size());
        assertBufferFlops(design, inserted);
        Set<String> insertedNames = inserted.stream().map(Cell::getName).collect(Collectors.toSet());

        // Rewire: the chain output drives the original sink and is sourced by an inserted flop...
        EDIFHierNet outNet = out.getLogicalHierNet();
        Assertions.assertTrue(
                outNet.getLeafHierPortInsts(false, true).stream().anyMatch(p -> portId(p).equals(origSinkId)),
                "original sink is not driven by the chain output");
        Assertions.assertTrue(
                insertedNames.contains(outNet.getSourcePortInsts(false).get(0).getFullHierarchicalInstName()),
                "chain output is not sourced by an inserted flop");
        // ...and the original net now feeds the chain (drives an inserted flop's D).
        Assertions.assertTrue(
                net.getLogicalHierNet().getLeafHierPortInsts(false, true).stream().anyMatch(
                        p -> insertedNames.contains(p.getFullHierarchicalInstName())
                                && p.getPortInst().getName().equals("D")),
                "original net does not feed the chain");
    }

    /**
     * insertFlopTreeForNet on a cross-SLR net inserts placed buffer flops, rewires the net
     * through them, and moves the original sink off the source net.
     */
    @Test
    public void insertFlopTreeInsertsBufferedFlops() {
        Design design = RapidWrightDCP.loadDCP(DCP);
        Net net = design.getNet(CROSS_SLR_NET);
        Assertions.assertNotNull(net);
        String origSinkId = portId(net.getLogicalHierNet().getLeafHierPortInsts(false, true).get(0));
        Set<String> before = cellNames(design);

        int depth = 4;
        FlopTreeTools.insertFlopTreeForNet(design, CROSS_SLR_NET, CLK, depth, 3);

        List<Cell> inserted = insertedFlops(design, before);
        Assertions.assertEquals(depth, inserted.size());
        assertBufferFlops(design, inserted);
        Set<String> insertedNames = inserted.stream().map(Cell::getName).collect(Collectors.toSet());

        // Rewire: the source net now feeds an inserted flop, and no longer drives the original sink.
        List<EDIFHierPortInst> newSinks =
                design.getNet(CROSS_SLR_NET).getLogicalHierNet().getLeafHierPortInsts(false, true);
        Assertions.assertTrue(
                newSinks.stream().anyMatch(p -> insertedNames.contains(p.getFullHierarchicalInstName())),
                "source net does not feed the tree");
        Assertions.assertTrue(
                newSinks.stream().noneMatch(p -> portId(p).equals(origSinkId)),
                "original sink is still directly on the source net");
    }
}
