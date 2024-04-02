/*
 *
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.examples;

import java.nio.file.Path;

import com.xilinx.rapidwright.util.VivadoToolsHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Created on: Mar 25, 2024
 */
public class TestSLRCrosserGenerator {

    @Test
    public void testSLRCrosserGenerator(@TempDir Path dir) {
        Path outputDCP = dir.resolve("slr_crosser.dcp");
        final String[] args0 = new String[] { "-j", "1440", "-k", "1", "-o", outputDCP.toString() };
        Assertions.assertThrows(RuntimeException.class, () -> SLRCrosserGenerator.main(args0));

        final String[] args1 = new String[] { "-w", "2", "-j", "1440", "-k", "1", "-o", outputDCP.toString() };
        Assertions.assertThrows(RuntimeException.class, () -> SLRCrosserGenerator.main(args1));

        final String[] args2 = new String[] { "-j", "-5", "-k", "1", "-o", outputDCP.toString() };
        Assertions.assertThrows(RuntimeException.class, () -> SLRCrosserGenerator.main(args2));

        String[] args = new String[] { "-j", "512", "-k", "256", "-o", outputDCP.toString() };
        SLRCrosserGenerator.main(args);

        VivadoToolsHelper.assertFullyRouted(outputDCP);
    }
}
