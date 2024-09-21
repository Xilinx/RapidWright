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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IOStandard;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
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

        EDIFCell primCell = new EDIFCell(netlist.getHDIPrimitivesLibrary(), prototypePrim, prim);

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

        Design testDesign2 = createSamplePrimitiveDesign("OBUFDS", part);
        EDIFNetlist testNetlist2 = testDesign2.getNetlist();

        testNetlist2.expandMacroUnisims(part.getSeries());

        Assertions.assertTrue(testNetlist2.getHDIPrimitivesLibrary().containsCell("OBUFDS_DUAL_BUF"));

        testNetlist2.collapseMacroUnisims(part.getSeries());

        Assertions.assertTrue(testNetlist2.getHDIPrimitivesLibrary().containsCell("OBUFDS"));
        Assertions.assertFalse(testNetlist2.getHDIPrimitivesLibrary().containsCell("OBUFDS_DUAL_BUF"));
        testDesign2.getTopEDIFCell().getCellInst("testOBUFDS").addProperty("IOStandard", IOStandard.LVCMOS12.name());

        testNetlist2.expandMacroUnisims(part.getSeries());

        Assertions.assertTrue(testNetlist2.getHDIPrimitivesLibrary().containsCell("OBUFDS"));
        Assertions.assertFalse(testNetlist2.getHDIPrimitivesLibrary().containsCell("OBUFDS_DUAL_BUF"));
    }

    @Test
    void testMacroExpansionWithAndWithoutException() {
        final Part part = PartNameTools.getPart(PART_NAME);
        Design testDesign = createSamplePrimitiveDesign("OBUFDS", part);

        EDIFNetlist testNetlist = testDesign.getNetlist();
        testNetlist.setDevice(testDesign.getDevice());

        EDIFCellInst wontBeExpanded = testDesign.getTopEDIFCell().getCellInst("testOBUFDS");
        wontBeExpanded.addProperty("IOStandard", IOStandard.LVCMOS12.name());
        EDIFCellInst willBeExpanded = testNetlist.getTopCell().createChildCellInst("willBeExpanded", testNetlist.getHDIPrimitive(Unisim.OBUFDS));

        testNetlist.expandMacroUnisims(part.getSeries());

        EDIFCell obufdsCell = testNetlist.getHDIPrimitivesLibrary().getCell("OBUFDS");
        EDIFCell obufdsDualBufCell = testNetlist.getHDIPrimitivesLibrary().getCell("OBUFDS_DUAL_BUF");

        Assertions.assertSame(obufdsCell, wontBeExpanded.getCellType());
        Assertions.assertSame(obufdsDualBufCell, willBeExpanded.getCellType());

        testNetlist.collapseMacroUnisims(part.getSeries());

        Assertions.assertSame(obufdsCell, testNetlist.getHDIPrimitivesLibrary().getCell("OBUFDS"));
        Assertions.assertFalse(testNetlist.getHDIPrimitivesLibrary().containsCell("OBUFDS_DUAL_BUF"));

        Assertions.assertSame(obufdsCell, wontBeExpanded.getCellType());
        Assertions.assertSame(obufdsCell, willBeExpanded.getCellType());

        testNetlist.expandMacroUnisims(part.getSeries());

        Assertions.assertNotSame(obufdsCell, testNetlist.getHDIPrimitivesLibrary().getCell("OBUFDS"));
        Assertions.assertNotSame(obufdsDualBufCell, testNetlist.getHDIPrimitivesLibrary().getCell("OBUFDS_DUAL_BUF"));
    }

    @Test
    void testMacroExpansionPortParents() {
        final Part part = PartNameTools.getPart(Device.KCU105);
        Design testDesign = createSamplePrimitiveDesign("OBUFDS", part);

        EDIFCell cell = testDesign.getNetlist().getHDIPrimitivesLibrary().getCell("OBUFDS");
        for (EDIFPort p : cell.getPorts()) {
            Assertions.assertSame(p.getParentCell(), cell);
        }

        testDesign.getNetlist().expandMacroUnisims(part.getSeries());

        EDIFCell expandedCell = testDesign.getNetlist().getHDIPrimitivesLibrary().getCell("OBUFDS_DUAL_BUF");
        Assertions.assertNotNull(expandedCell);
        for (EDIFPort p : expandedCell.getPorts()) {
            Assertions.assertSame(p.getParentCell(), expandedCell);
        }

        testDesign.getNetlist().collapseMacroUnisims(part.getSeries());

        EDIFCell collapsedCell = testDesign.getNetlist().getHDIPrimitivesLibrary().getCell("OBUFDS");
        Assertions.assertNotNull(collapsedCell);
        for (EDIFPort p : collapsedCell.getPorts()) {
            Assertions.assertEquals(p.getParentCell(), collapsedCell);
        }
    }

    @Test
    void testMacroExpansionInstanceTypes() {
        final Part part = PartNameTools.getPart(Device.AWS_F1);
        String macroName = "DSP48E2";
        Design testDesign = createSamplePrimitiveDesign(macroName, part);
        EDIFNetlist testNetlist = testDesign.getNetlist();
        EDIFLibrary netlistPrimLibrary = testNetlist.getHDIPrimitivesLibrary();

        EDIFCell cell = netlistPrimLibrary.getCell(macroName);
        Assertions.assertTrue(cell.getCellInsts().isEmpty());

        // Singleton libraries
        EDIFLibrary macroLibrary = Design.getMacroPrimitives(part.getSeries());
        EDIFLibrary primLibrary = Design.getPrimitivesLibrary();
        // Netlist should have its own copy of the singleton library
        Assertions.assertNotSame(netlistPrimLibrary, primLibrary);

        EDIFCell macroCell = macroLibrary.getCell(macroName);
        Assertions.assertNotSame(macroCell, cell);
        Assertions.assertEquals(8, macroCell.getCellInsts().size());

        testNetlist.expandMacroUnisims(part.getSeries());

        // Expanded cell must not be the same cell as before, and be a copy
        // of the macro library's cell
        EDIFCell expandedCell = netlistPrimLibrary.getCell(macroName);
        Assertions.assertNotSame(expandedCell, cell);
        Assertions.assertNotSame(expandedCell, macroCell);
        Assertions.assertEquals(8, expandedCell.getCellInsts().size());
        for (EDIFCellInst eci : expandedCell.getCellInsts()) {
            // Its instances should also refer to netlist's primitive library copy
            Assertions.assertSame(netlistPrimLibrary, eci.getCellType().getLibrary());
        }

        // Check that original macro cell wasn't inadvertently modified
        for (EDIFCellInst eci : macroCell.getCellInsts()) {
            Assertions.assertNotSame(netlistPrimLibrary, eci.getCellType().getLibrary());
        }

        testNetlist.collapseMacroUnisims(part.getSeries());

        EDIFCell collapsedCell = netlistPrimLibrary.getCell(macroName);
        Assertions.assertNotSame(collapsedCell, cell);
        Assertions.assertTrue(collapsedCell.getCellInsts().isEmpty());

        // Check original macro cell wasn't affected
        Assertions.assertEquals(8, macroCell.getCellInsts().size());
        for (EDIFCellInst eci : macroCell.getCellInsts()) {
            Assertions.assertNotSame(netlistPrimLibrary, eci.getCellType().getLibrary());
        }
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
    public void testBussedPortReNamingCollision(@TempDir Path path) {
        final EDIFNetlist origNetlist = EDIFTools.createNewNetlist("test");

        EDIFCell top = origNetlist.getTopCell();

        EDIFCellInst ff = top.createChildCellInst("ff", Design.getPrimitivesLibrary().getCell("FDRE"));
        origNetlist.getHDIPrimitivesLibrary().addCell(ff.getCellType());

        String portName = "unfortunate_name";

        // Create two bussed ports with a common root name, one having a trailing '_' underscore
        EDIFPort port0 = top.createPort(portName + "[2:0]", EDIFDirection.INOUT, 3);
        EDIFPort port1 = top.createPort(portName + "_[1:0]", EDIFDirection.INOUT, 2);
        // And for good measure, throw in a single-bit port too with the same root
        EDIFPort port2 = top.createPort(portName, EDIFDirection.INOUT, 1);

        EDIFNet net0 = top.createNet("net0");
        net0.createPortInst(port0, 0);
        net0.createPortInst("D", ff);

        EDIFNet net1 = top.createNet("net1");
        net1.createPortInst(port1, 1);
        net1.createPortInst("R", ff);
        
        EDIFNet net2 = top.createNet("net2");
        net2.createPortInst(port2);
        net2.createPortInst("Q", ff);

        Path tempFile = path.resolve("test.edf");
        origNetlist.exportEDIF(tempFile);

        // Check using EDIFNetlistComparator
        EDIFNetlist testNetlist = EDIFTools.readEdifFile(tempFile);
        EDIFNetlistComparator comparer = new EDIFNetlistComparator();
        Assertions.assertEquals(0, comparer.compareNetlists(origNetlist, testNetlist));
    }

    @Test
    public void testGetIOStandard() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        netlist.setDevice(Device.getDevice(Device.AWS_F1));

        EDIFCell top = netlist.getTopCell();
        EDIFPort port = top.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCellInst obufds = top.createChildCellInst("obuf", Design.getPrimitivesLibrary().getCell("OBUFDS"));
        EDIFNet net = top.createNet("O");
        new EDIFPortInst(port, net);
        new EDIFPortInst(obufds.getPort("O"), net, obufds);
        Assertions.assertEquals("[string(DEFAULT)]", netlist.getIOStandards(obufds).toString());

        // Previous call to netlist.getIOStandard() will have initialized this map,
        // clear it here so that it gets re-initialized
        netlist.resetCellInstIOStandardFallbackMap();

        // Test that top-level-port's connected net property is propagated
        net.addProperty(EDIFNetlist.IOSTANDARD_PROP, "LVDS");
        Assertions.assertEquals("[string(LVDS)]", netlist.getIOStandards(obufds).toString());

        // Test that cell inst takes priority
        obufds.addProperty(EDIFNetlist.IOSTANDARD_PROP, "DIFF_SSTL12_DCI");
        Assertions.assertEquals("[string(DIFF_SSTL12_DCI)]", netlist.getIOStandards(obufds).toString());
    }

    @Test
    public void testGetIOStandardCellDefaultGetsOverriddenByNet() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        netlist.setDevice(Device.getDevice(Device.AWS_F1));

        EDIFCell top = netlist.getTopCell();
        EDIFPort port = top.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCellInst obufds = top.createChildCellInst("obuf", Design.getPrimitivesLibrary().getCell("OBUFDS"));
        EDIFNet net = top.createNet("O");
        new EDIFPortInst(port, net);
        new EDIFPortInst(obufds.getPort("O"), net, obufds);

        // Explicitly attach DEFAULT property to the cell
        obufds.addProperty(EDIFNetlist.IOSTANDARD_PROP, EDIFNetlist.DEFAULT_PROP_VALUE.getValue());

        // Test that top-level-port's connected net property is propagated
        net.addProperty(EDIFNetlist.IOSTANDARD_PROP, "LVDS");

        // Test that net gets priority
        Assertions.assertEquals("[string(LVDS)]", netlist.getIOStandards(obufds).toString());
    }

    @ParameterizedTest
    @CsvSource({
            "LVDS,OBUFDS",
            "BLVDS_25,OBUFDS_DUAL_BUF"
    })
    public void testExpandMacroUnisimsExceptionWithFallbackIOStandard(String standard, String cellType) {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        netlist.setDevice(Device.getDevice(Device.AWS_F1));

        EDIFCell top = netlist.getTopCell();
        EDIFPort port = top.createPort("O", EDIFDirection.OUTPUT, 1);
        EDIFCellInst obufds = top.createChildCellInst("obuf", netlist.getHDIPrimitive(Unisim.OBUFDS));
        netlist.getHDIPrimitivesLibrary().addCell(obufds.getCellType());
        EDIFNet net = top.createNet("O");
        new EDIFPortInst(port, net);
        new EDIFPortInst(obufds.getPort("O"), net, obufds);

        // Set IOStandard only on top-level-port's connected net
        net.addProperty(EDIFNetlist.IOSTANDARD_PROP, standard);

        netlist.expandMacroUnisims(Series.UltraScalePlus);
        Assertions.assertEquals(cellType, obufds.getCellType().getName());
    }

    @Test
    public void testMultiLevelMacroExpansion() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        netlist.setDevice(Device.getDevice(Device.AWS_F1));

        EDIFCell top = netlist.getTopCell();

        EDIFCellInst iobufdse3 = top.createChildCellInst("IOBUFDSE3_expandme",
                netlist.getHDIPrimitive(Unisim.IOBUFDSE3));
        netlist.getHDIPrimitivesLibrary().addCell(iobufdse3.getCellType());

        netlist.expandMacroUnisims(Series.UltraScalePlus);

        EDIFCellInst childInst = iobufdse3.getCellType().getCellInst("OBUFTDS");
        Assertions.assertEquals(childInst.getCellType().getName(), "OBUFTDS_DCIEN_DUAL_BUF");
        Assertions.assertEquals(childInst.getCellType().getCellInsts().size(), 3);
    }

    @ParameterizedTest
    @CsvSource({
            "RAM32X1S,1'b0",
    })
    public void testRAM32X1SExpansion(String unisim, String expected) {
        EDIFNetlist n = EDIFTools.createNewNetlist("test");

        EDIFCell macro = n.getHDIPrimitivesLibrary().addCell(Design.getUnisimCell(Unisim.valueOf(unisim)));
        n.getTopCell().createChildCellInst("inst", macro);

        Assertions.assertNull(n.getCellInstFromHierName("inst/SP"));

        n.expandMacroUnisims(Series.Series7);
        EDIFCellInst inst = n.getCellInstFromHierName("inst/SP");
        Assertions.assertNotNull(inst);
        Assertions.assertEquals(expected, inst.getProperty("IS_CLK_INVERTED").getValue());
    }

    @ParameterizedTest
    @CsvSource({
            "RAM32X1S_1",
            "RAM16X1S",
            "RAM16X1S_1",
    })
    public void testUnsupportedMacroExpansionAndProperty(String unisim) {
        EDIFNetlist n = EDIFTools.createNewNetlist("test");

        EDIFCell macro = n.getHDIPrimitivesLibrary().addCell(Design.getUnisimCell(Unisim.valueOf(unisim)));
        EDIFCellInst inst = n.getTopCell().createChildCellInst("inst", macro);

        Assertions.assertEquals(0, inst.getCellType().getCellInsts().size());

        n.expandMacroUnisims(Series.Series7);

        // Assert no expansion/retargeting occurred for unsupported macros
        Assertions.assertEquals(0, inst.getCellType().getCellInsts().size());

        // Assert no property exists on unsupported macros either
        Assertions.assertNull(inst.getProperty("IS_CLK_INVERTED"));
    }

    @Test
    public void testGetPhysicalPinsInout() {
        Design design = RapidWrightDCP.loadDCP("inout.dcp");
        EDIFNetlist netlist = design.getNetlist();

        {
            Assertions.assertEquals("[i_IBUF_inst/INBUF_INST/PAD]", netlist.getPhysicalPins("i").toString());
            Assertions.assertEquals(null, netlist.getPhysicalPins("i_IBUF"));
            Assertions.assertEquals("[o_OBUF_inst/O]", netlist.getPhysicalPins("o").toString());
            Assertions.assertEquals(null, netlist.getPhysicalPins("o_IBUF"));
        }
        {
            Assertions.assertEquals("[ib/DIFFINBUF_INST/DIFF_IN_P]", netlist.getPhysicalPins("i2_p").toString());
            Assertions.assertEquals("[ib/DIFFINBUF_INST/DIFF_IN_N]", netlist.getPhysicalPins("i2_n").toString());
            Assertions.assertEquals(null, netlist.getPhysicalPins("o2_p"));
            Assertions.assertEquals("[ob/N/O]", netlist.getPhysicalPins("ob/OB").toString());
            Assertions.assertEquals(null, netlist.getPhysicalPins("o2_n"));
            Assertions.assertEquals("[ob/P/O]", netlist.getPhysicalPins("ob/O").toString());
        }
    }
}
