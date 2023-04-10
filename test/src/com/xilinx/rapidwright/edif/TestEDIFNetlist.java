/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

import com.xilinx.rapidwright.device.Series;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IOStandard;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.compare.EDIFNetlistComparator;
import com.xilinx.rapidwright.support.RapidWrightDCP;

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

    private Design createSamplePrimitiveDesign(String prim, Part part) {
        String designName = prim +"_design";
        final EDIFNetlist netlist = EDIFTools.createNewNetlist(designName);
        final Design design = new Design(designName, part.getName());
        design.setNetlist(netlist);

        final EDIFCell prototypePrim = Design.getPrimitivesLibrary().getCell(prim);

        EDIFCell primCell = new EDIFCell(netlist.getHDIPrimitivesLibrary(), prototypePrim);

        primCell.createCellInst("test" + prim, netlist.getTopCell());

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

        final Part part2 = PartNameTools.getPart(Device.KCU105);
        Design testDesign2 = createSamplePrimitiveDesign("OBUFDS", part2);
        testDesign2.getNetlist().expandMacroUnisims(part.getSeries());
        Assertions.assertTrue(testDesign2.getNetlist().getHDIPrimitivesLibrary().containsCell("OBUFDS_DUAL_BUF"));
        testDesign2.getNetlist().collapseMacroUnisims(part.getSeries());
        Assertions.assertTrue(testDesign2.getNetlist().getHDIPrimitivesLibrary().containsCell("OBUFDS"));
        Assertions.assertFalse(testDesign2.getNetlist().getHDIPrimitivesLibrary().containsCell("OBUFDS_DUAL_BUF"));
        testDesign2.getTopEDIFCell().getCellInst("testOBUFDS").addProperty("IOStandard", IOStandard.LVCMOS12.name());
        testDesign2.getNetlist().expandMacroUnisims(part.getSeries());
        Assertions.assertTrue(testDesign2.getNetlist().getHDIPrimitivesLibrary().containsCell("OBUFDS"));
        Assertions.assertFalse(testDesign2.getNetlist().getHDIPrimitivesLibrary().containsCell("OBUFDS_DUAL_BUF"));
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
        for (EDIFHierNet logNets : netlist.getNetAliases(srcPortInst.getHierarchicalNet())) {
            potentiallyModifiedCells.add(logNets.getParentInst().getCellType());
        }

        for (EDIFCell modifiedCell : modifiedCells.keySet()) {
            Assertions.assertTrue(potentiallyModifiedCells.contains(modifiedCell));
        }
    }

    @Test
    public void testGetHier() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");

        EDIFCell top = netlist.getTopCell();
        EDIFCell lut2 = Design.getPrimitivesLibrary().getCell("LUT2");
        top.createChildCellInst("fred", lut2);
        top.createChildCellInst("fred/barney", lut2);

        Assertions.assertNotNull(netlist.getHierCellInstFromName("fred"));
        Assertions.assertNotNull(netlist.getHierCellInstFromName("fred/barney"));

        EDIFCell hierCell = new EDIFCell(netlist.getWorkLibrary(), "flintstones");
        top.createChildCellInst("flintstones", hierCell);
        hierCell.createChildCellInst("wilma", lut2);
        hierCell.createChildCellInst("wilma/betty", lut2);

        Assertions.assertNotNull(netlist.getHierCellInstFromName("flintstones/wilma"));
        Assertions.assertNotNull(netlist.getHierCellInstFromName("flintstones/wilma/betty"));
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

    @Test
    public void testBussedPortNamingCollision(@TempDir Path path) {
        final EDIFNetlist origNetlist = EDIFTools.createNewNetlist("test");

        EDIFCell top = origNetlist.getTopCell();

        EDIFCellInst ff = top.createChildCellInst("ff", Design.getPrimitivesLibrary().getCell("FDRE"));
        origNetlist.getHDIPrimitivesLibrary().addCell(ff.getCellType());

        String portName = "unfortunate_name";

        // Create two ports, one single-bit and another bussed with the same root name
        EDIFPort port0 = top.createPort(portName, EDIFDirection.INOUT, 1);
        EDIFPort port1 = top.createPort(portName + "[1:0]", EDIFDirection.INOUT, 2);

        EDIFNet net0 = top.createNet("net0");
        net0.createPortInst(port0);
        net0.createPortInst("D", ff);

        EDIFNet net1 = top.createNet("net1");
        net1.createPortInst(port1, 1);
        net1.createPortInst("R", ff);

        Path tempFile = path.resolve("test.edf");
        origNetlist.exportEDIF(tempFile);

        // Check using EDIFNetlistComparator
        EDIFNetlist testNetlist = EDIFTools.readEdifFile(tempFile);
        EDIFNetlistComparator comparer = new EDIFNetlistComparator();
        Assertions.assertEquals(0, comparer.compareNetlists(origNetlist, testNetlist));

        // Perform explicit check of port widths
        EDIFCell testTopCell = testNetlist.getTopCell();
        EDIFPort testPort0 = testTopCell.getNet(net0.getName()).getPortInst(null, portName).getPort();
        Assertions.assertEquals(port0.getWidth(), testPort0.getWidth());
        EDIFPort testPort1 = testTopCell.getNet(net1.getName()).getPortInst(null, portName + "[0]").getPort();
        Assertions.assertEquals(port1.getWidth(), testPort1.getWidth());
    }

    @Test
    public void testGetIOStandard() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");

        EDIFCell top = netlist.getTopCell();
        EDIFPort port = top.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCellInst obufds = top.createChildCellInst("obuf", Design.getPrimitivesLibrary().getCell("OBUFDS"));
        EDIFNet net = top.createNet("O");
        new EDIFPortInst(port, net);
        new EDIFPortInst(obufds.getPort("O"), net, obufds);
        Assertions.assertEquals(EDIFNetlist.DEFAULT_PROP_VALUE, netlist.getIOStandard(obufds));

        // Previous call to netlist.getIOStandard() will have initialized this map,
        // clear it here so that it gets re-initialized
        netlist.resetCellInstIOStandardFallbackMap();

        // Test that top-level-port's connected net property is propagated
        net.addProperty(EDIFNetlist.IOSTANDARD_PROP, "LVDS");
        Assertions.assertEquals("LVDS", netlist.getIOStandard(obufds).getValue());

        // Test that cell inst takes priority
        obufds.addProperty(EDIFNetlist.IOSTANDARD_PROP, "DIFF_SSTL12_DCI");
        Assertions.assertEquals("DIFF_SSTL12_DCI", netlist.getIOStandard(obufds).getValue());
    }

    @ParameterizedTest
    @CsvSource({
            "LVDS,OBUFDS",
            "BLVDS_25,OBUFDS_DUAL_BUF"
    })
    public void testExpandMacroUnisimsExceptionWithFallbackIOStandard(String standard, String cellType) {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");

        EDIFCell top = netlist.getTopCell();
        EDIFPort port = top.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCellInst obufds = top.createChildCellInst("obuf", Design.getPrimitivesLibrary().getCell("OBUFDS"));
        netlist.getHDIPrimitivesLibrary().addCell(obufds.getCellType());
        EDIFNet net = top.createNet("O");
        new EDIFPortInst(port, net);
        new EDIFPortInst(obufds.getPort("O"), net, obufds);

        // Set IOStandard only on top-level-port's connected net
        net.addProperty(EDIFNetlist.IOSTANDARD_PROP, standard);

        netlist.expandMacroUnisims(Series.UltraScalePlus);
        Assertions.assertEquals(cellType, obufds.getCellType().getName());
    }
}
