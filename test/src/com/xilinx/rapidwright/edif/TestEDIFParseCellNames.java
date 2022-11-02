/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

package com.xilinx.rapidwright.edif;

import java.io.IOException;

import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFParseCellNames {

    private void verifyNetlist(EDIFNetlist netlist) {
        final EDIFCell cellA = netlist.getTopCell().getCellInst("instA").getCellType();
        final EDIFCell cellB = netlist.getTopCell().getCellInst("instB").getCellType();

        Assertions.assertNotEquals(cellA, cellB);
        Assertions.assertNotNull(cellA.getPort("portA"));
        Assertions.assertNull(cellB.getPort("portA"));
        Assertions.assertNotNull(cellB.getPort("portB"));
        Assertions.assertNull(cellA.getPort("portB"));
        Assertions.assertNotEquals(cellA.getName(), cellB.getName());
        Assertions.assertEquals(netlist.getWorkLibrary(), cellA.getLibrary());
        Assertions.assertEquals(netlist.getWorkLibrary(), cellB.getLibrary());
    }

    @Test
    public void parseDuplicateCellsSerial() throws IOException {
        try (final EDIFParser edifParser = new EDIFParser(RapidWrightDCP.getPath("duplicateCellNames.edf"))) {
            final EDIFNetlist netlist = edifParser.parseEDIFNetlist();
            verifyNetlist(netlist);
        }
    }
    @Test
    public void parseDuplicateCellsParallel() throws IOException {
        try (final ParallelEDIFParser parallelEDIFParser = new ParallelEDIFParser(RapidWrightDCP.getPath("duplicateCellNames.edf"))) {
            final EDIFNetlist netlist = parallelEDIFParser.parseEDIFNetlist();
            verifyNetlist(netlist);
        }
    }
}
