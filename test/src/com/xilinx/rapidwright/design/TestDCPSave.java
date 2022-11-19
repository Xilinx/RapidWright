/*
 *
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

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;

public class TestDCPSave {

    @Test
    public void testDCPSave(@TempDir Path tempDir) {
        // Taken from example provided by https://github.com/nqdtan in #548
        Design d = new Design("test", "xcvc1902-vsvd1760-2MP-e-S");
        EDIFLibrary plib = d.getNetlist().getHDIPrimitivesLibrary();
        EDIFCell top = d.getNetlist().getTopCell();
        EDIFCell ec0 = new EDIFCell(plib, "LUT6CY");
        EDIFCell ec1 = new EDIFCell(plib, "LUTCY1");
        EDIFCell ec2 = new EDIFCell(plib, "LUTCY2");

        EDIFCellInst eci0 = new EDIFCellInst("lut6cy_test", ec0, top);
        EDIFCellInst eci1 = new EDIFCellInst("LUTCY1_INST", ec1, ec0);
        EDIFCellInst eci2 = new EDIFCellInst("LUTCY2_INST", ec2, ec0);

        SiteInst si = new SiteInst("SLICE_X182Y139", d, SiteTypeEnum.SLICEL,
                d.getDevice().getSite("SLICE_X182Y139"));
        BEL bel1 = si.getSite().getBEL("B5LUT");
        BEL bel2 = si.getSite().getBEL("B6LUT");

        Cell c1 = new Cell("lut6cy_test/" + eci1.getName(), si, bel1);
        Cell c2 = new Cell("lut6cy_test/" + eci2.getName(), si, bel2);

        c1.addPinMapping("A1", "I0");
        c1.addPinMapping("A2", "I1");
        c1.addPinMapping("A3", "I2");
        c1.addPinMapping("A4", "I3");
        c1.addPinMapping("A5", "I4");
        c1.addPinMapping("O6", "O");
        c1.addPinMapping("GE", "GE");

        c2.addPinMapping("A1", "I0");
        c2.addPinMapping("A2", "I1");
        c2.addPinMapping("A3", "I2");
        c2.addPinMapping("A4", "I3");
        c2.addPinMapping("A5", "I4");
        c2.addPinMapping("O5", "O");
        
        d.writeCheckpoint(tempDir.resolve("tmp.dcp"));        
    }
}
