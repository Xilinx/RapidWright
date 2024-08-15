/*
 * Copyright (c) 2023-2024, Advanced Micro Devices, Inc.
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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;

public class TestUnisimPlacements {

    @ParameterizedTest
    @EnumSource(FamilyType.class)
    public void testUnisimPlacements(FamilyType familyType) {
        for (Part part : PartNameTools.getParts()) {
            if (part.getFamily() != familyType)
                continue;
            Design des = new Design("top", part.getName());
            EDIFCell cell = Design.getPrimitivesLibrary(des.getDevice().getName()).getCell("FDRE");
            Cell c = des.createCell("inst", cell);
            Assertions.assertTrue(c.getCompatiblePlacements(des.getDevice()).size() > 1);
            break;
        }
    }
}
