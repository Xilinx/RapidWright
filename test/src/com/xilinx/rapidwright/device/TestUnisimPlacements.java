/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.device;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;

public class TestUnisimPlacements {

    /**
     * Set of unsupported unisim families (for now) in 2022.2.3. Hopefully will add
     * support in 2023.1.0.
     */
    private static Set<FamilyType> unsupportedTypes = null;

    static {
        unsupportedTypes = new HashSet<>();
        unsupportedTypes.add(FamilyType.ZYNQUPLUSRFSOC);
        unsupportedTypes.add(FamilyType.QVIRTEXUPLUSHBM);
        unsupportedTypes.add(FamilyType.QZYNQUPLUSRFSOC);
        unsupportedTypes.add(FamilyType.VIRTEXUPLUSHBMES1);
        unsupportedTypes.add(FamilyType.VIRTEXUPLUSHBM);
        unsupportedTypes.add(FamilyType.VIRTEXUPLUS58G);
    }

    @ParameterizedTest
    @EnumSource(FamilyType.class)
    public void testUnisimPlacements(FamilyType familyType) {
        if (unsupportedTypes.contains(familyType)) {
            return;
        }
        for (Part part : PartNameTools.getParts()) {
            if (part.getFamily() != familyType)
                continue;
            Design des = new Design("top", part.getName());
            EDIFCell cell = Design.getPrimitivesLibrary(des.getDevice().getName()).getCell("FDRE");
            Cell c = des.createCell("inst", cell);
            Assertions.assertTrue(c.getCompatiblePlacements().size() > 1);
            break;
        }
    }
}
