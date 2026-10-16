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

    @Test
    public void testGetCorrespondingTileModularSLR() {
        // On modular-SLR devices (e.g. Versal VP1902), tile X/Y coordinates are
        // die-local and restart in each SLR: CLE_W_CORE_S0X54Y284 and
        // CLE_W_CORE_S3X54Y284 are different tiles in different dies. Relocating
        // a module across dies must translate within the new anchor's own die,
        // not silently jump to whichever die happens to share the same X/Y.
        Device dev = Device.getDevice("xcvp1902");
        Assertions.assertTrue(dev.hasModularSLRs());

        Tile templateTile = dev.getTile("CLE_W_CORE_S0X54Y284");

        // Zero-offset: the template tile is itself the anchor, so relocating to
        // an anchor in a different die must reproduce the same tile in that die.
        Tile newAnchorTile = dev.getTile("CLE_W_CORE_S3X54Y284");
        Tile zeroOffset = Module.getCorrespondingTile(templateTile, newAnchorTile, templateTile);
        Assertions.assertNotNull(zeroOffset);
        Assertions.assertEquals("CLE_W_CORE_S3X54Y284", zeroOffset.getName());

        // Non-zero offset: originalAnchor is 4 tile columns to the left of
        // templateTile in S0; the same offset must land in the new anchor's die.
        Tile originalAnchor = dev.getTile("CLE_W_CORE_S0X50Y284");
        Tile withOffset = Module.getCorrespondingTile(templateTile, newAnchorTile, originalAnchor);
        Assertions.assertNotNull(withOffset);
        Assertions.assertEquals("CLE_W_CORE_S3X58Y284", withOffset.getName());

        // Same-die relocation must continue to work exactly as before.
        Tile newAnchorSameDie = dev.getTile("CLE_W_CORE_S0X60Y284");
        Tile sameDie = Module.getCorrespondingTile(templateTile, newAnchorSameDie, originalAnchor);
        Assertions.assertNotNull(sameDie);
        Assertions.assertEquals("CLE_W_CORE_S0X64Y284", sameDie.getName());
    }
}
