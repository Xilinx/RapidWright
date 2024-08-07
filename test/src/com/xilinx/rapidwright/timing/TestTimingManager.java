/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestTimingManager {

    @Test
    public void testGetDesignTimingRequirement() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        float expectedClkPeriod = 2.850f;
        Assertions.assertEquals(expectedClkPeriod, TimingManager.getDesignTimingRequirement(d));
        
        d.addXDCConstraint("# create_clock -period 10.850 -name clk -waveform {0.000 1.425} "
                + "[get_ports -filter { NAME =~  \"*clk*\" && DIRECTION == \"IN\" }]");
        
        Assertions.assertEquals(expectedClkPeriod, TimingManager.getDesignTimingRequirement(d));
    }
}
