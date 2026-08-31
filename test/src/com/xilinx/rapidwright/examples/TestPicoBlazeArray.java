
/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestPicoBlazeArray {

    @Test
    public void testPicoBlazeArray(@TempDir Path dir) {
        Path outputDCP = dir.resolve("picoblaze_array.dcp");
        PicoBlazeArray.main(new String[] { FileTools.getRapidWrightPath() + "/test/RapidWrightDCP/PicoBlazeArray",
                                "xcvu3p-ffvc1517-2-i", outputDCP.toString(), "--no_hand_placer" });

        boolean hasEncryptedCells = false;
        VivadoToolsHelper.assertCanBeFullyRoutedByVivado(outputDCP, dir, hasEncryptedCells);
    }
}
