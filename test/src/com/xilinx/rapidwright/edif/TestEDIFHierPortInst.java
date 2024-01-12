/*
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;

import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Objects;

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

    @ParameterizedTest
    @CsvSource({
            // Cell pin placed onto a D6LUT/O6 -- its net does exit the site
            "processor/address_loop[8].output_data.pc_vector_mux_lut/LUT6/O,D_O,true",
            // Cell pin placed onto a D5LUT/O5 -- its net does exit the site
            "processor/address_loop[8].output_data.pc_vector_mux_lut/LUT5/O,DMUX,true",

            // Cell pin placed onto a E6LUT/O6 -- its net does not exit the site
            "processor/stack_loop[4].upper_stack.stack_pointer_lut/LUT6/O,null,true",

            // Cell pin placed onto a D5LUT/O5 -- its net does not exit the site and
            // nothing is using DMUX
            "processor/stack_loop[3].upper_stack.stack_pointer_lut/LUT5/O,null,true",

            // FIXME: Known broken -- see https://github.com/Xilinx/RapidWright/pull/577
            // Cell pin placed onto a E5LUT/O5 -- its net does not exit the site but
            // another net is using EMUX
            "processor/stack_loop[4].upper_stack.stack_pointer_lut/LUT5/O,null,false",

    })
    void testGetRoutedSitePinInst(String hierPortInstName, String expected, boolean expectPass) {
        Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = d.getNetlist();
        EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(hierPortInstName);
        SitePinInst spi = ehpi.getRoutedSitePinInst(d);
        Assertions.assertEquals(expectPass, Objects.equals(expected, spi == null ? "null" : spi.getName()));
    }
}
