/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.tests;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestCodePerfTracker {

    @Test
    public void testUseGCToTrackMemoryDoesNotThrow() {
        // setTrackOSMemUsage(true) used to enable Linux-only /proc-based memory tracking on any
        // non-Windows OS (including macOS), crashing with FileNotFoundException there.
        CodePerfTracker t = new CodePerfTracker("Test", false);
        Assertions.assertDoesNotThrow(() -> {
            t.useGCToTrackMemory(true);
            t.start("segment1");
            t.stop();
        });
    }
}
