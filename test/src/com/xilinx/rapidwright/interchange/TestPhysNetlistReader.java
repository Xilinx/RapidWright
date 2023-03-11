/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TestPhysNetlistReader {
    private void testRoutethruLUTsHelper(Design d) {
        SiteInst si = d.getSiteInstFromSiteName("SLICE_X0Y0");
        Cell a6lut = si.getCell("A6LUT");
        Assertions.assertNotNull(a6lut);
        Assertions.assertTrue(a6lut.isRoutethru());
        Assertions.assertEquals(a6lut.getPinMappingsP2L().toString(), "{A3=S[0]}");
        Cell a5lut = si.getCell("A5LUT");
        Assertions.assertNotNull(a5lut);
        Assertions.assertTrue(a5lut.isRoutethru());
        Assertions.assertEquals(a5lut.getPinMappingsP2L().toString(), "{A1=D}");
    }

    @Test
    public void testRoutethruLUTs(@TempDir Path tempDir) throws IOException {
        final String inputPath = RapidWrightDCP.getString("routethru_luts.dcp");
        Design input = Design.readCheckpoint(inputPath);
        testRoutethruLUTsHelper(input);

        final Path interchangePath = tempDir.resolve("routethru_luts.phys");
        PhysNetlistWriter.writePhysNetlist(input, interchangePath.toString());

        Design output = PhysNetlistReader.readPhysNetlist(interchangePath.toString(), input.getNetlist());
        testRoutethruLUTsHelper(output);
    }

    @Test
    public void testReadPhysNetlist() throws IOException {
        Path netlistPath = RapidWrightDCP.getPath("interchange/bug546.netlist");
        Path physPath = RapidWrightDCP.getPath("interchange/bug546.phys");

        EDIFNetlist netlist = LogNetlistReader.readLogNetlist(netlistPath.toString());
        PhysNetlistReader.readPhysNetlist(physPath.toString(), netlist);
    }

    @Test
    public void testReadPhysNetlistBelConflict() throws IOException {
        Path netlistPath = RapidWrightDCP.getPath("interchange/bug626.netlist");
        Path physPath = RapidWrightDCP.getPath("interchange/bug626.phys");

        EDIFNetlist netlist = LogNetlistReader.readLogNetlist(netlistPath.toString());
        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> PhysNetlistReader.readPhysNetlist(physPath.toString(), netlist));
        Assertions.assertEquals("ERROR: Cell \"LUT6_2_70/LUT6\" placement on BEL SLICE_X61Y155/B6LUT conflicts with previously placed cell \"LUT6_2_70/LUT5\".",
                ex.getMessage());
    }
}
