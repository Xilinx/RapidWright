/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Coherent Ho, Synopsys, Inc.
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

package com.xilinx.rapidwright.design;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestConstraintsTools {

    @Test
    public void testParsePBlockFromXDCConstraints() {
        String dcpPath = RapidWrightDCP.getString("microblazeAndILA_3pblocks.dcp");
        Design d = Design.readCheckpoint(dcpPath);
        ConstraintsTools ct = new ConstraintsTools(d);
        Assertions.assertEquals(3, ct.getPBlockMap().size());
        Assertions.assertTrue(ct.getPBlockMap().containsKey("pblock_dbg_hub"));
        Assertions.assertTrue(ct.getPBlockMap().containsKey("pblock_base_mb_i"));
        Assertions.assertTrue(ct.getPBlockMap().containsKey("pblock_u_ila_0"));
    }
}