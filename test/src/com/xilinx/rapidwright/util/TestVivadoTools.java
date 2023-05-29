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

import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestVivadoTools {
    @Test
    public void testOpenDcpReadLog(@TempDir Path tempDir) throws IOException {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());
        // XXX
        // grabbing a handle to one of the example dcps requires to some
        // unfortunate path hacking to work in and out of gradle. inside gradle
        // we get a relative path, and we must convert to an absolute path
        // since vivado is run in the tempDir. outside of gradle we get an
        // absolute path and no conversion is needed.
        final Path dcpPath = Path.of(RapidWrightDCP.getString("picoblaze_partial.dcp"));
        final Path currentDirectory = Path.of(System.getProperty("user.dir"));
        String dcp = null;
        if (dcpPath.startsWith(currentDirectory)) {
            dcp = dcpPath.toString();
        }
        else {
            dcp = currentDirectory.resolve(dcpPath).normalize().toString();
        }
        // create a tcl script to open an example dcp
        final String tclScript = tempDir.resolve("tclScript.tcl").toString();
        List<String> lines = new ArrayList<>();
        lines.add("open_checkpoint " + dcp);
        lines.add("report_route_status");
        lines.add("exit");
        FileTools.writeLinesToTextFile(lines,  tclScript);

        // run the above script through vivado
        List<String> log = new ArrayList<>();
        log = VivadoTools.runTcl(tempDir.resolve("outputLog.log"), tclScript, true);

        // search the log for some key phrases, and check the results:
        List<String> results = new ArrayList<>();

        // check to see that the expected number of nets are unrouted in the example dcp
        results = VivadoTools.searchVivadoLog(log, "# of unrouted nets");
        Assertions.assertEquals(1, results.size());
        int parsed = Integer.parseInt(results.get(0).replaceAll("[^\\d]", ""));
        Assertions.assertEquals(12144, parsed);

        // check to see that a non existent key phrase doesn't break things
        results = VivadoTools.searchVivadoLog(log, "This key shouldn't be in the log!");
        Assertions.assertEquals(0, results.size());

        // check to see everything works if we get multiple matches
        results = VivadoTools.searchVivadoLog(log, "# of");
        Assertions.assertEquals(9, results.size());
    }

}

