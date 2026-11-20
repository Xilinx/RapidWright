/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.rwroute;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestRWRouteConfig {

    /**
     * The option of the removed RCLK node mask is still accepted: it has no effect other than a warning.
     */
    @Test
    @SuppressWarnings("deprecation")
    public void testMaskNodesCrossRCLKIsAcceptedWithWarning() {
        PrintStream stdout = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        RWRouteConfig config;
        try {
            System.setOut(new PrintStream(captured, true));
            config = new RWRouteConfig(new String[] {"--timingDriven", "--maskNodesCrossRCLK"});
        } finally {
            System.setOut(stdout);
        }
        Assertions.assertTrue(config.isTimingDriven());
        Assertions.assertFalse(config.isMaskNodesCrossRCLK());
        String warning = captured.toString();
        Assertions.assertTrue(warning.contains("WARNING: RWRoute option '--maskNodesCrossRCLK' has no effect"), warning);
    }

    @Test
    public void testUnknownOptionIsRejected() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new RWRouteConfig(new String[] {"--noSuchOption"}));
    }
}
