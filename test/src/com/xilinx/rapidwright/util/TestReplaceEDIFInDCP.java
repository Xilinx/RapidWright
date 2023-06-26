/*
 *
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

package com.xilinx.rapidwright.util;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestReplaceEDIFInDCP {

    @Test
    public void testReplaceEDIFInDCP(@TempDir Path tempDir) {
        Design.setAutoGenerateReadableEdif(false);
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        String unreadable = "picoblaze_ooc_X10Y235_unreadable_edif";
        Path readableEDIF = tempDir.resolve(unreadable + ".edf");
        design.getNetlist().exportEDIF(readableEDIF.toString());
        Path unreadableDCP = RapidWrightDCP.getPath(unreadable + ".dcp");
        Path readableDCP = tempDir.resolve("picoblaze_ooc_X10Y235.dcp");

        // Replace for new DCP
        ReplaceEDIFInDCP.main(new String[] { unreadableDCP.toString(), readableEDIF.toString(), readableDCP.toString() });
        Design.readCheckpoint(readableDCP);

        // Replace in-place
        Path unreadableDCPCopy = tempDir.resolve(unreadableDCP.getFileName());
        FileTools.copyFile(unreadableDCP.toString(), unreadableDCPCopy.toString());
        ReplaceEDIFInDCP.main(new String[] { unreadableDCPCopy.toString(), readableEDIF.toString() });
        Design.readCheckpoint(unreadableDCPCopy);
    }
}
