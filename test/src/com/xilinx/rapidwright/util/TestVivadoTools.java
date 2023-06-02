/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Zak Nafziger, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestVivadoTools {
    @Test
    public void testRunTclCmd(@TempDir Path tempDir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        List<String> log = new ArrayList<>();
        log = VivadoTools.runTcl(tempDir.resolve("outputLog.log"), "exit", true);

        List<String> results = new ArrayList<>();
        results = VivadoTools.searchVivadoLog(log, "INFO");
        Assertions.assertTrue(results.get(0).contains("Exiting Vivado"));
    }

    @Test
    public void testReportRouteStatus() throws IOException {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());
        String dcp = RapidWrightDCP.getPath("picoblaze_partial.dcp").toString();
        Design d = Design.readCheckpoint(dcp);
        VivadoTools.ReportRouteStatusResult r = new VivadoTools.ReportRouteStatusResult(d);

        Assertions.assertEquals(12144, r.unroutedNets);
    }
}

