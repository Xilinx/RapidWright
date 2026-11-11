/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.Params;

public class TestCombineDcps {

    private static final String BLACK_BOX = "hw_contract_pr1";

    @Test
    public void testCombineDcps(@TempDir Path tempDir) {
        Path output = tempDir.resolve("combined.dcp");
        boolean orig2024Dot1 = Params.RW_WRITE_DCP_2024_1;
        Params.RW_WRITE_DCP_2024_1 = false;
        try {
            CombineDcps.main(new String[] {RapidWrightDCP.getString("hwct.dcp"), output.toString(),
                    BLACK_BOX + "=" + RapidWrightDCP.getString("hwct_pr1.dcp")});
            // The 2024.1 DCP format is only selected for the tool's own write
            Assertions.assertFalse(Params.RW_WRITE_DCP_2024_1);
        } finally {
            Params.RW_WRITE_DCP_2024_1 = orig2024Dot1;
        }

        Design combined = Design.readCheckpoint(output);
        // The black box now has the module's top cell, with its contents
        Design module = RapidWrightDCP.loadDCP("hwct_pr1.dcp");
        EDIFCellInst inst = combined.getNetlist().getCellInstFromHierName(BLACK_BOX);
        Assertions.assertEquals(module.getNetlist().getTopCell().getName(), inst.getCellType().getName());
        Assertions.assertFalse(inst.getCellType().getCellInsts().isEmpty());
        // Each of the module's placed cells is where the module put it
        int checked = 0;
        for (Cell c : module.getCells()) {
            if (!c.isPlaced() || c.isLocked() || c.isPortCell() || c.isRoutethru()) {
                continue;
            }
            Cell placed = combined.getCell(BLACK_BOX + "/" + c.getName());
            Assertions.assertNotNull(placed, c.getName());
            Assertions.assertEquals(c.getSite(), placed.getSite(), c.getName());
            Assertions.assertEquals(c.getBELName(), placed.getBELName(), c.getName());
            checked++;
        }
        Assertions.assertTrue(checked > 0);
    }

    @Test
    public void testParseModules(@TempDir Path tempDir) {
        String dcp = RapidWrightDCP.getString("hwct_pr1.dcp");
        Map<String, Path> modules = CombineDcps.parseModules(new String[] {"in.dcp", "out.dcp", "a/b=" + dcp}, 2);
        Assertions.assertEquals(Collections.singletonMap("a/b", RapidWrightDCP.getPath("hwct_pr1.dcp")), modules);

        for (String bad : new String[] {dcp, "=" + dcp, "a/b=", "a/b=" + tempDir.resolve("missing.dcp")}) {
            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> CombineDcps.parseModules(new String[] {bad}, 0), bad);
        }
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CombineDcps.parseModules(new String[] {"a/b=" + dcp, "a/b=" + dcp}, 0));
    }
}
