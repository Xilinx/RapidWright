/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Andrew Butt, AMD Advanced Research and Development.
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
package com.xilinx.rapidwright.design.blocks;

import com.xilinx.rapidwright.device.Device;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestPBlock {
    @Test
    public void testVersalPBlockMove() {
        Device device = Device.getDevice("xcv80");
        PBlock pblock = new PBlock(device, "IRI_QUAD_X58Y3212:IRI_QUAD_X59Y3275 DSP_X0Y398:DSP_X1Y405 " +
                "DSP58_CPLX_X0Y398:DSP58_CPLX_X0Y405 SLICE_X92Y796:SLICE_X99Y811");
        PBlock newPblock = new PBlock(device, pblock.getAllSites(null));

        PBlockRange iriRange = null;
        for (PBlockRange pbr : newPblock) {
            if (pbr.getLowerLeftSite().getName().contains("IRI_QUAD")) {
                iriRange = pbr;
            }
        }
        Assertions.assertNotNull(iriRange);
        Assertions.assertEquals("IRI_QUAD_X58Y3212", iriRange.getLowerLeftSite().getName());

        newPblock.movePBlock(0, 220);
        Assertions.assertEquals("CLE_W_CORE_X28Y624", newPblock.getBottomLeftTile().getName());
        Assertions.assertEquals("CLE_E_CORE_X31Y639", newPblock.getTopRightTile().getName());
        Assertions.assertEquals("IRI_QUAD_X58Y2508", iriRange.getLowerLeftSite().getName());
        Assertions.assertEquals("IRI_QUAD_X59Y2571", iriRange.getUpperRightSite().getName());
    }
}
