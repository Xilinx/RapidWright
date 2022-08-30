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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void testMacroExpansionException(@TempDir Path tempDir) {
        final Part part = PartNameTools.getPart(PART_NAME);
        Design testDesign = createSampleMacroDesign(TEST_MACRO, part);
        final Path outputDCP = tempDir.resolve(testDesign.getName() + ".dcp");
        testDesign.writeCheckpoint(outputDCP);
        
        Design loadAgain = Design.readCheckpoint(outputDCP);
        Assertions.assertTrue(loadAgain.getNetlist().getHDIPrimitivesLibrary().containsCell("OBUFTDS"));
    }

    @Test
    public void testTrackChanges() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();

        EDIFHierPortInst srcPortInst = netlist.getHierPortInstFromName(TestEDIFTools.TEST_SRC);
        EDIFHierPortInst snkPortInst = netlist.getHierPortInstFromName(TestEDIFTools.TEST_SNK);

        // Disconnect sink in anticipation of connecting to another net
        snkPortInst.getNet().removePortInst(snkPortInst.getPortInst());

        netlist.setTrackCellChanges(true);

        EDIFTools.connectPortInstsThruHier(srcPortInst, snkPortInst, TestEDIFTools.UNIQUE_SUFFIX);

        netlist.resetParentNetMap();

        Map<EDIFCell,List<EDIFChange>> modifiedCells = netlist.getModifiedCells();

        Assertions.assertEquals(modifiedCells.size(), 8);

        Set<EDIFCell> potentiallyModifiedCells = new HashSet<>();
        for(EDIFHierNet logNets : netlist.getNetAliases(srcPortInst.getHierarchicalNet())) {
            potentiallyModifiedCells.add(logNets.getParentInst().getCellType());
        }

        for(EDIFCell modifiedCell : modifiedCells.keySet()) {
            Assertions.assertTrue(potentiallyModifiedCells.contains(modifiedCell));
        }
    }

    @Test
    public void testCopyCellsAndSubCells() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist srcNetlist = design.getNetlist();

        EDIFNetlist dstNetlist = EDIFTools.createNewNetlist("dstNetlist");
        dstNetlist.copyCellAndSubCells(srcNetlist.getTopCell());
        for (EDIFLibrary srcLib : srcNetlist.getLibraries()) {
            if (srcLib.isHDIPrimitivesLibrary())
                continue;
            EDIFLibrary dstLib = dstNetlist.getLibrary(srcLib.getName());
            Assertions.assertEquals(srcLib, dstLib);
            for (EDIFCell srcCell : srcLib.getCells()) {
                EDIFCell dstCell = dstLib.getCell(srcCell.getName());
                // Check contents are equal, but not pointers
                Assertions.assertEquals(srcCell, dstCell);
                Assertions.assertTrue(srcCell != dstCell);
                Assertions.assertTrue(srcCell.getLibrary().getNetlist() == srcNetlist);
                Assertions.assertTrue(dstCell.getLibrary().getNetlist() == dstNetlist);
                for (EDIFCellInst srcInst : srcCell.getCellInsts()) {
                    EDIFCellInst dstInst = dstCell.getCellInst(srcInst.getName());
                    Assertions.assertEquals(srcInst, dstInst);
                    Assertions.assertTrue(srcInst != dstInst);
                    Assertions.assertTrue(srcInst.getCellType().getLibrary().getNetlist() == srcNetlist);
                    Assertions.assertTrue(dstInst.getCellType().getLibrary().getNetlist() == dstNetlist);
                }
            }
        }
    }

    @Test
    public void testCopyCellsAndSubCellsCollision() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist srcNetlist = design.getNetlist();

        EDIFNetlist dstNetlist = EDIFTools.createNewNetlist("dstNetlist");
        dstNetlist.copyCellAndSubCells(srcNetlist.getTopCell());

        RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                () -> dstNetlist.copyCellAndSubCells(srcNetlist.getTopCell()));
        Assertions.assertEquals("ERROR: Destination netlist already contains EDIFCell named 'picoblaze_top' in library 'work'",
                e.getMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testCopyCellsAndSubCellsIntoLibrary(boolean existing) {
        EDIFDesign srcDesign = new EDIFDesign();
        EDIFNetlist srcNetlist = EDIFTools.createNewNetlist("srcNetlist");
        srcNetlist.setDesign(srcDesign);
        EDIFLibrary srcLibrary = srcNetlist.addLibrary(new EDIFLibrary("srcLibrary"));
        EDIFCell srcCell = new EDIFCell(srcLibrary, "srcCell");
        srcDesign.setTopCell(srcCell);

        EDIFNetlist dstNetlist = EDIFTools.createNewNetlist("dstNetlist");
        EDIFLibrary dstLibraryBefore = null;
        if (existing) {
            dstLibraryBefore = dstNetlist.addLibrary(new EDIFLibrary(srcLibrary.getName()));
        }

        dstNetlist.copyCellAndSubCells(srcNetlist.getTopCell());

        EDIFLibrary dstLibraryAfter = dstNetlist.getLibrary(srcLibrary.getName());
        Assertions.assertNotNull(dstLibraryAfter);
        if (existing) {
            Assertions.assertTrue(dstLibraryBefore == dstLibraryAfter);
        }
        Assertions.assertFalse(srcLibrary == dstLibraryAfter);

        EDIFCell dstCell = dstLibraryAfter.getCell(srcCell.getName());
        Assertions.assertNotNull(dstCell);
        // Copy really makes a copy of the source cell
        Assertions.assertFalse(srcCell == dstCell);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testMigrateCellsAndSubCellsIntoLibrary(boolean existing) {
        EDIFDesign srcDesign = new EDIFDesign();
        EDIFNetlist srcNetlist = EDIFTools.createNewNetlist("srcNetlist");
        srcNetlist.setDesign(srcDesign);
        EDIFLibrary srcLibrary = srcNetlist.addLibrary(new EDIFLibrary("srcLibrary"));
        EDIFCell srcCell = new EDIFCell(srcLibrary, "srcCell");
        srcDesign.setTopCell(srcCell);

        EDIFNetlist dstNetlist = EDIFTools.createNewNetlist("dstNetlist");
        EDIFLibrary dstLibraryBefore = null;
        if (existing) {
            dstLibraryBefore = dstNetlist.addLibrary(new EDIFLibrary(srcLibrary.getName()));
        }

        dstNetlist.migrateCellAndSubCells(srcNetlist.getTopCell());

        EDIFLibrary dstLibraryAfter = dstNetlist.getLibrary(srcLibrary.getName());
        Assertions.assertNotNull(dstLibraryAfter);
        if (existing) {
            Assertions.assertTrue(dstLibraryBefore == dstLibraryAfter);
        }
        Assertions.assertFalse(srcLibrary == dstLibraryAfter);

        EDIFCell dstCell = dstLibraryAfter.getCell(srcCell.getName());
        Assertions.assertNotNull(dstCell);
        // Migration re-uses the same cell
        Assertions.assertTrue(srcCell == dstCell);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testMigrateCellsAndSubCells(boolean uniquify) {
        EDIFNetlist dstNetlist = EDIFTools.createNewNetlist("dstNetlist");
        final String srcLibraryName = "srcLibrary";
        final String srcCellName = "srcCell";
        for (int i = 0; i < 3; i++) {
            EDIFDesign srcDesign = new EDIFDesign();
            EDIFNetlist srcNetlist = EDIFTools.createNewNetlist("srcNetlist");
            srcNetlist.setDesign(srcDesign);
            EDIFLibrary srcLibrary = srcNetlist.addLibrary(new EDIFLibrary(srcLibraryName));
            EDIFCell srcCell = new EDIFCell(srcLibrary, srcCellName);
            srcDesign.setTopCell(srcCell);

            dstNetlist.migrateCellAndSubCells(srcNetlist.getTopCell(), uniquify);
        }

        EDIFLibrary dstLibrary = dstNetlist.getLibrary(srcLibraryName);
        Assertions.assertNotNull(dstLibrary);
        Assertions.assertNotNull(dstLibrary.getCell(srcCellName));
        if (uniquify) {
            Assertions.assertNotNull(dstLibrary.getCell(srcCellName + "_parameterized0"));
            Assertions.assertNotNull(dstLibrary.getCell(srcCellName + "_parameterized1"));
            Assertions.assertEquals(dstLibrary.getCells().size(), 3);
        } else {
            Assertions.assertEquals(dstLibrary.getCells().size(), 1);
        }
    }
}
