/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.edif;

import com.xilinx.rapidwright.design.Design;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFPropertyObject {
    @Test
    public void testGetIOStandard() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");

        EDIFCell top = netlist.getTopCell();
        EDIFCellInst obufds = top.createChildCellInst("obuf", Design.getPrimitivesLibrary().getCell("OBUFDS"));
        Assertions.assertEquals(EDIFNetlist.DEFAULT_PROP_VALUE, obufds.getIOStandard());

        obufds.addProperty("IOSTANDARD", "LVDS");
        Assertions.assertEquals("LVDS", obufds.getIOStandard().getValue());
    }
}
