/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Params;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestDCPWrite {

    @Test
    public void testVersalDualOutputCOUT(@TempDir Path dir) {
        // Tests a dual-output scenario COUT and HQ2 in Versal (See Xilinx/RapidWright#572)
        Design d = RapidWrightDCP.loadDCP("versal_cout_hq2.dcp");
        d.writeCheckpoint(dir.resolve("output.dcp"));
    }

    @Test
    public void testNewPhysDBWrite(@TempDir Path dir) {
        Design d = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks_2024.1.dcp");
        Path dcp = dir.resolve("microblazeAndILA_3pblocks_2024.1_postrw.dcp");
        Params.RW_WRITE_DCP_2024_1 = true;
        d.writeCheckpoint(dcp);
        Params.RW_WRITE_DCP_2024_1 = false;
        Design.readCheckpoint(dcp);
        if (FileTools.isVivadoAtLeastVersion(2024, 1)) {
            VivadoToolsHelper.assertFullyRouted(dcp);
        }
    }

    @Test
    public void testAdvancedFlowFlags(@TempDir Path tempDir) {
        Design design = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");

        // Should be true since it is targeting Versal
        Assertions.assertTrue(design.isAdvancedFlow());
        Path defaultDCPPath = tempDir.resolve("default.dcp");
        design.writeCheckpoint(defaultDCPPath);

        Design defaultDCP = Design.readCheckpoint(defaultDCPPath);
        Assertions.assertTrue(defaultDCP.isAdvancedFlow());

        design.setAdvancedFlow(false);
        Assertions.assertFalse(design.isAdvancedFlow());
        Path setFalseDCPPath = tempDir.resolve("false.dcp");
        design.writeCheckpoint(setFalseDCPPath);

        Design falseDCP = Design.readCheckpoint(setFalseDCPPath);
        Assertions.assertFalse(falseDCP.isAdvancedFlow());

        falseDCP.setAdvancedFlow(true);

        Params.RW_DISABLE_WRITING_ADV_FLOW_DCPS = true;

        Path overrideDCPPath = tempDir.resolve("override.dcp");
        Assertions.assertTrue(falseDCP.isAdvancedFlow());
        falseDCP.writeCheckpoint(overrideDCPPath);

        Design overrideDCP = Design.readCheckpoint(overrideDCPPath);
        Assertions.assertFalse(overrideDCP.isAdvancedFlow());
    }
}
