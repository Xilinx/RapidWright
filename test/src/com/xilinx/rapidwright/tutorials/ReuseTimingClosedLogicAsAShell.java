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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.Tutorial;
import com.xilinx.rapidwright.support.TutorialSupport;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Runs the "Reuse Timing-closed Logic As A Shell" Tutorial as a test
 */
public class ReuseTimingClosedLogicAsAShell {

    /**
     * This test runs the same commands as found in
     * 'RapidWrightInt/sphinx/ReusingTimingClosedLogicAsAShell.rst'
     * 
     * @param path Temporary path to run the tutorial
     */
    @Test
    public void testReuseTimingClosedLogicAsAShell(@TempDir Path path) {
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());
        
        TutorialSupport.runTutorialCommands(path, Tutorial.REUSING_TIMING_CLOSED_LOGIC_AS_A_SHELL, 
                118, 119, 120);
        
        TutorialSupport.runTutorialVivadoCommands(path.resolve("kcu105"), 
                Tutorial.REUSING_TIMING_CLOSED_LOGIC_AS_A_SHELL, 
                121, 145, 146, 147, 148, 175, 194, 195, 261, 274, 281, 282, 283, 284
                );
    }
}
