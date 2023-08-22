/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.CellPlacement;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.NetType;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysBelPin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysNet;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch.RouteSegment;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.capnproto.MessageReader;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;

public class TestPhysNetlistWriter {
    private void testAllRouteSegmentsEndInBELInputPins(Design design, RouteBranch.Reader routeBranch, List<String> strings) {
        StructList.Reader<RouteBranch.Reader> branches = routeBranch.getBranches();
        int branchesCount = branches.size();
        if (branchesCount == 0) {
            RouteSegment.Reader segment = routeBranch.getRouteSegment();
            Assertions.assertEquals(segment.which(), RouteSegment.Which.BEL_PIN);
            PhysBelPin.Reader bpReader = segment.getBelPin();

            SiteInst si = design.getSiteInst(strings.get(bpReader.getSite()));
            Assertions.assertNotNull(si);

            BEL bel = si.getBEL(strings.get(bpReader.getBel()));
            Assertions.assertNotNull(bel);

            BELPin belPin = bel.getPin(strings.get(bpReader.getPin()));
            Assertions.assertNotNull(belPin);

            Assertions.assertTrue(belPin.isInput());
        } else {
            for (PhysNetlist.RouteBranch.Reader childBranch : branches) {
                testAllRouteSegmentsEndInBELInputPins(design, childBranch, strings);
            }
        }
    }

    @Test
    public void testAllRouteSegmentsEndInBELInputPins(@TempDir Path tempDir) throws IOException {
        final String inputPath = RapidWrightDCP.getString("routethru_luts.dcp");
        Design design = Design.readCheckpoint(inputPath);

        final Path interchangePath = tempDir.resolve("routethru_luts.phys");
        PhysNetlistWriter.writePhysNetlist(design, interchangePath.toString());

        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                        ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(interchangePath.toString(), rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        List<String> allStrings = PhysNetlistReader.readAllStrings(physNetlist);

        for (PhysNet.Reader physNet : physNetlist.getPhysNets()) {
            if (physNet.getType() == NetType.GND || physNet.getType() == NetType.VCC) {
                continue;
            }
            for (StructList.Reader<RouteBranch.Reader> i : Arrays.asList(physNet.getSources(), physNet.getStubs())) {
                for (PhysNetlist.RouteBranch.Reader routeBranch : i) {
                    StructList.Reader<RouteBranch.Reader> branches = routeBranch.getBranches();
                    // Necessary for nets having just one source
                    if (branches.size() > 0) {
                        testAllRouteSegmentsEndInBELInputPins(design, routeBranch, allStrings);
                    }
                }
            }
        }
    }

    @Test
    public void testNoLutRoutethruCells(@TempDir Path tempDir) throws IOException {
        final String inputPath = RapidWrightDCP.getString("routethru_luts.dcp");
        Design design = Design.readCheckpoint(inputPath);

        final Path interchangePath = tempDir.resolve("routethru_luts.phys");
        PhysNetlistWriter.writePhysNetlist(design, interchangePath.toString());

        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                        ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(interchangePath.toString(), rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        List<String> allStrings = PhysNetlistReader.readAllStrings(physNetlist);

        for (CellPlacement.Reader placement : physNetlist.getPlacements()) {
            SiteInst siteInst = design.getSiteInstFromSiteName(allStrings.get(placement.getSite()));
            Assertions.assertNotNull(siteInst);

            for (PinMapping.Reader pinMapping : placement.getPinMap()) {
                Cell belCell = siteInst.getCell(allStrings.get(pinMapping.getBel()));
                Assertions.assertNotNull(belCell);

                String belName = belCell.getBELName();
                boolean expectRT = belName.equals("A5LUT") || belName.equals("A6LUT");
                Assertions.assertEquals(expectRT, belCell.isRoutethru());
            }
        }
    }

    @Test
    public void testMissingBELPin(@TempDir Path tempDir) throws IOException {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");

        String interchangePath = tempDir.resolve("design.phys").toString();
        PhysNetlistWriter.writePhysNetlist(design, interchangePath);

        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                        ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(interchangePath, rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        List<String> allStrings = PhysNetlistReader.readAllStrings(physNetlist);

        for (PhysNet.Reader net : physNetlist.getPhysNets()) {
            String netName = allStrings.get(net.getName());
            if (!netName.equals("processor/alu_decode1_lut/O6"))
                continue;

            StructList.Reader<RouteBranch.Reader> fanouts = net.getSources();
            Assertions.assertEquals(1, fanouts.size());
            RouteBranch.Reader CLUT6_O6_branch = fanouts.get(0);
            Assertions.assertEquals("BEL_PIN", CLUT6_O6_branch.getRouteSegment().which().toString());
            PhysBelPin.Reader CLUT6_O6 = CLUT6_O6_branch.getRouteSegment().getBelPin();
            Assertions.assertEquals("C6LUT", allStrings.get(CLUT6_O6.getBel()));
            Assertions.assertEquals("O6", allStrings.get(CLUT6_O6.getPin()));

            fanouts = CLUT6_O6_branch.getBranches();
            Assertions.assertEquals(1, fanouts.size());
            RouteBranch.Reader C_O_C_O_branch = fanouts.get(0);
            Assertions.assertEquals("BEL_PIN", C_O_C_O_branch.getRouteSegment().which().toString());
            PhysBelPin.Reader C_O_C_O = C_O_C_O_branch.getRouteSegment().getBelPin();
            Assertions.assertEquals("C_O", allStrings.get(C_O_C_O.getBel()));
            Assertions.assertEquals("C_O", allStrings.get(C_O_C_O.getPin()));

            fanouts = C_O_C_O_branch.getBranches();
            Assertions.assertEquals(1, fanouts.size());
            RouteBranch.Reader C_O_branch = fanouts.get(0);
            Assertions.assertEquals("SITE_PIN", C_O_branch.getRouteSegment().which().toString());
            PhysNetlist.PhysSitePin.Reader C_O = C_O_branch.getRouteSegment().getSitePin();
            Assertions.assertEquals("SLICE_X16Y239", allStrings.get(C_O.getSite()));
            Assertions.assertEquals("C_O", allStrings.get(C_O.getPin()));
            break;
        }
    }

    @Test
    public void testReversedPIPs(@TempDir Path tempDir) throws IOException {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");

        String interchangePath = tempDir.resolve("design.phys").toString();
        PhysNetlistWriter.writePhysNetlist(design, interchangePath);

        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                        ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(interchangePath, rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        List<String> allStrings = PhysNetlistReader.readAllStrings(physNetlist);

        for (PhysNet.Reader net : physNetlist.getPhysNets()) {
            String netName = allStrings.get(net.getName());
            // It's known that all these clock nets are fully routed
            if (!netName.equals("base_mb_i/clk_wiz_1/inst/clk_out1") &&
                !netName.equals("base_mb_i/mdm_1/U0/No_Dbg_Reg_Access.BUFG_DRCK/Dbg_Clk_31") &&
                !netName.equals("base_mb_i/clk_wiz_1/inst/clkfbout_buf_base_mb_clk_wiz_1_0") &&
                !netName.equals("dbg_hub/inst/BSCANID.u_xsdbm_id/itck_i")) {
                continue;
            }

            Assertions.assertEquals(1, net.getSources().size());
            Assertions.assertEquals(0, net.getStubs().size());
        }
    }
}
