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
    private void testAllRouteSegmentsEndInBELInputPins(Design design, RouteBranch.Reader routeBranch, StringEnumerator strings) {
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

        StringEnumerator allStrings = PhysNetlistReader.readAllStrings(physNetlist);

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

        StringEnumerator allStrings = PhysNetlistReader.readAllStrings(physNetlist);

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

}
