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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.examples.Lesson1;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class TestVivadoTools {
    @Test
    public void testRunTclCmd(@TempDir Path tempDir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        List<String> log = VivadoTools.runTcl(tempDir.resolve("outputLog.log"), "exit", true);

        List<String> results = VivadoTools.searchVivadoLog(log, "INFO");
        Assertions.assertTrue(results.get(0).contains("Exiting Vivado"));
    }

    @Test
    public void testRunTclCmdThrowsException(@TempDir Path tempDir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        RuntimeException ex = Assertions.assertThrows(RuntimeException.class,
                () -> VivadoTools.runTcl(tempDir.resolve("outputLog.log"), "exit 1", true)
        );
        Assertions.assertEquals("Vivado exited with code: 1", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testReportRouteStatus(boolean fromDisk) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        Path dcp = RapidWrightDCP.getPath("picoblaze_partial.dcp");
        ReportRouteStatusResult rrs;
        if (fromDisk) {
            rrs = VivadoTools.reportRouteStatus(dcp);
        } else {
            Design d = Design.readCheckpoint(dcp);
            rrs = VivadoTools.reportRouteStatus(d);
        }

        Assertions.assertEquals(12144, rrs.unroutedNets);
    }

    private void assertVivadoLogContains(List<String> log, String query) {
        List<String> result = VivadoTools.searchVivadoLog(log, query);
        Assertions.assertTrue(0 < result.size());
    }

    @Test
    public void testReportRouteStatusInvalid() {
        ReportRouteStatusResult rrs = new ReportRouteStatusResult(Arrays.asList(
                "foo",
                "bar",
                "blah"
        ));
        Assertions.assertFalse(rrs.isFullyRouted());
    }

    @Test
    public void testWriteBitstream(@TempDir Path tempDir) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());
        Path dcpFolder = tempDir.resolve("dcp");
        FileTools.makeDir(dcpFolder.toString());
        Path dcp = dcpFolder.resolve("HelloWorld.dcp");
        Path bit = tempDir.resolve("HelloWorld.bit");
        Lesson1.main(new String[] { dcp.toString() });
        List<String> log = VivadoTools.writeBitstream(dcp, bit, false);
        assertVivadoLogContains(log, "write_bitstream completed successfully");
        assertVivadoLogContains(log, "Exiting Vivado");
        Assertions.assertTrue(Files.exists(bit));
    }
}

