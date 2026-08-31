/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.examples.AddSubGenerator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestModule {

    private static final String TEST_PART = Device.AWS_F1;

    private Module getAdderModule(String name, String siteOrigin) {
        Design design = new Design(name, TEST_PART);
        Device device = design.getDevice();
        AddSubGenerator.createAddSub(design, device.getSite(siteOrigin), 64, false, true, true);
        Module adderModule = new Module(design);
        return adderModule;
    }

    @Test
    public void testModuleSLRRelocate() {
        Design top = new Design("top", TEST_PART);

        Module noSLRAdder = getAdderModule("adderNoSLR", "SLICE_X10Y10");
        Module slrAdder = getAdderModule("adderSLR", "SLICE_X10Y295");
        top.addModule(noSLRAdder);
        top.addModule(slrAdder);

        Assertions.assertTrue(noSLRAdder.isValidPlacement(noSLRAdder.getAnchor(), top));
        Assertions.assertFalse(noSLRAdder.isValidPlacement(slrAdder.getAnchor(), top));

        Assertions.assertTrue(slrAdder.isValidPlacement(slrAdder.getAnchor(), top));
        Assertions.assertFalse(slrAdder.isValidPlacement(noSLRAdder.getAnchor(), top));
    }

    @Test
    public void testGetCorrespondingTile() {
        Device v80 = Device.getDevice("xcv80");

        // Testing for SLL to SLL_1 special case because they have the same tile type but overlapping X,Y grids
        Tile templateTile = v80.getTile("SLL_X23Y886");
        Tile originalAnchor = v80.getTile("CLE_W_CORE_X22Y886");
        Tile newAnchorTile = v80.getTile("CLE_W_CORE_X23Y900");
        Tile newTile = Module.getCorrespondingTile(templateTile, newAnchorTile, originalAnchor);

        Assertions.assertNotNull(newTile);
        Assertions.assertEquals("SLL_1_X23Y900", newTile.getName());
    }
}
