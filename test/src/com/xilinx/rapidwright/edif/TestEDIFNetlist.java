/* 
 * Copyright (c) 2021 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.edif;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.support.CheckOpenFiles;

class TestEDIFNetlist {

    private static final String PART_NAME = Device.KCU105;
    
    private static final String TEST_MACRO = "IOBUFDS_INTERMDISABLE";
    
    private Design createSampleMacroDesign(String macro, Part part) {
        String designName = TEST_MACRO +"_design";
        final EDIFNetlist netlist = EDIFTools.createNewNetlist(designName);
        final Design design = new Design(designName, part.getName());
        design.setNetlist(netlist);
        
        final EDIFCell prototypeMacro = Design.getMacroPrimitives(part.getSeries()).getCell(macro); 
        
        EDIFCell macroCell = new EDIFCell(netlist.getHDIPrimitivesLibrary(), prototypeMacro);
        
        macroCell.createCellInst("test" + macro, netlist.getTopCell());

        return design;
    }
    
    @Test
    @CheckOpenFiles
    void testMacroExpansionException(@TempDir Path tempDir) {
        final Part part = PartNameTools.getPart(PART_NAME);
        Design testDesign = createSampleMacroDesign(TEST_MACRO, part);
        final Path outputDCP = tempDir.resolve(testDesign.getName() + ".dcp");
        testDesign.writeCheckpoint(outputDCP);
        
        Design loadAgain = Design.readCheckpoint(outputDCP);
        Assertions.assertTrue(loadAgain.getNetlist().getHDIPrimitivesLibrary().containsCell("OBUFTDS"));
    }

}
