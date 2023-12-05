/*
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestEDIFHierPortInst {
    @Test
    void testGetPhysicalCell() {
        Design d = new Design("design", Device.KCU105);
        EDIFNetlist n = d.getNetlist();
        String cellName = "name\\.with\\.backslashes";
        // Note: need to place cell as Cell.updateName() requires a SiteInst in order to acquire the Design
        Cell c = d.createAndPlaceCell(cellName, Unisim.FDRE, "SLICE_X0Y0/AFF");
        EDIFHierCellInst ehci = n.getHierCellInstFromName(cellName);
        new EDIFPortInst(ehci.getCellType().getPort("Q"), null, ehci.getInst());

        EDIFHierPortInst ehpi = n.getHierPortInstFromName(cellName + EDIFTools.EDIF_HIER_SEP + "Q");
        Assertions.assertEquals(c, ehpi.getPhysicalCell(d));

        // Check that we can still find it in this case
        Assertions.assertEquals(c, ehpi.getPhysicalCell(d));
    }

    @Test
    public void testGetPhysicalCellMacroHierarchy() {
        Design design = new Design("design", "xcvc1902-vsvd1760-2MP-e-S");
        EDIFNetlist n = design.getNetlist();
        
        EDIFCell macro = n.getHDIPrimitive(Unisim.RAM64X1D);
        Assertions.assertSame(n.getHDIPrimitivesLibrary(), macro.getLibrary());
        n.getTopCell().createChildCellInst("inst", macro);
        n.expandMacroUnisims(Series.Versal);

        String cellName = "inst/DP/RAMD64_INST";

        // We can't instantiate RAM64X1D since it's a transformed prim, so we'll update
        // the type after creation
        Cell c = design.createAndPlaceCell(cellName, Unisim.LUT6, "SLICE_X235Y138/B6LUT");
        c.setType(Unisim.RAM64X1D.toString());

        EDIFHierCellInst leafInst = n.getHierCellInstFromName("inst/DP/RAMD64_INST");

        EDIFHierPortInst portInst = new EDIFHierPortInst(leafInst.getParent(), leafInst.getInst().getPortInst("O"));
        Assertions.assertEquals(c, portInst.getPhysicalCell(design));
    }
}
