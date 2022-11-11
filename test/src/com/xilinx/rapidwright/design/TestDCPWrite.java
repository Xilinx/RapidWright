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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestDCPWrite {

    @Test
    public void testVersalDualOutputCOUT(@TempDir Path dir) {
        // Tests a dual-output scenario COUT and HQ2 in Versal (See Xilinx/RapidWright#572)
        Design d = RapidWrightDCP.loadDCP("versal_cout_hq2.dcp");
        d.writeCheckpoint(dir.resolve("output.dcp"));
    }
}
