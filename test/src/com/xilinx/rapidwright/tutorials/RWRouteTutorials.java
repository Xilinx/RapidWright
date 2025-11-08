/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.tutorials;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.support.Tutorial;
import com.xilinx.rapidwright.support.TutorialSupport;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;

public class RWRouteTutorials {

    @Test
    @LargeTest(max_memory_gb = 8)
    public void testRWRouteTimingDrivenRouting(@TempDir Path dir) {
        testRWRouteTutorials(dir, Tutorial.RWROUTE_TIMING_DRIVEN, 15, 21);
    }

    @Test
    @LargeTest(max_memory_gb = 8)
    public void testRWRouteWirelengthDrivenRouting(@TempDir Path dir) {
        testRWRouteTutorials(dir, Tutorial.RWROUTE_WIRELENGTH_DRIVEN, 15, 21);
    }

    @Test
    @LargeTest(max_memory_gb = 8)
    public void testRWRoutePartialRouting(@TempDir Path dir) {
        testRWRouteTutorials(dir, Tutorial.RWROUTE_PARTIAL, 15, 22);
    }

    private void testRWRouteTutorials(Path dir, Tutorial name, int... lineNumbers) {
        long maxMemoryNeeded = 1024L * 1024L * 1024L * 8L;
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= maxMemoryNeeded);
        List<String> cmds = TutorialSupport.runTutorialCommands(dir, name, lineNumbers);
        if (FileTools.isVivadoOnPath()) {
            Path outputDCP = dir.resolve(cmds.get(2).split(" ")[2]);
            Assertions.assertTrue(VivadoTools.reportRouteStatus(outputDCP).isFullyRouted());
        }
    }
}
