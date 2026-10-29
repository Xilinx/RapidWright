/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IOStandard;
import com.xilinx.rapidwright.interchange.LogNetlistReader;
import com.xilinx.rapidwright.interchange.LogNetlistWriter;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.Params;

public class TestEDIFCollapsedView {

    /**
     * Creates an UltraScale+ netlist with an IBUF, an OBUFDS that expands into an OBUFDS_DUAL_BUF
     * for its (default) IOStandard, and an OBUFDS that does not, with its macros expanded.
     */
    private static EDIFNetlist createNetlist() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFTools.ensureCorrectPartInEDIF(netlist, Device.AWS_F1);
        EDIFCell top = netlist.getTopCell();
        top.createChildCellInst("ibuf", netlist.getHDIPrimitive(Unisim.IBUF));
        top.createChildCellInst("obufds_dual", netlist.getHDIPrimitive(Unisim.OBUFDS));
        EDIFCellInst obufds = top.createChildCellInst("obufds", netlist.getHDIPrimitive(Unisim.OBUFDS));
        obufds.addProperty(EDIFNetlist.IOSTANDARD_PROP, IOStandard.LVDS.name());
        Assertions.assertTrue(netlist.expandMacroUnisims());
        return netlist;
    }

    private static List<String> getLeafNames(EDIFNetlist netlist) {
        List<String> names = new ArrayList<>();
        for (EDIFHierCellInst inst : netlist.getAllLeafHierCellInstances()) {
            names.add(inst.getFullHierarchicalInstName() + " " + inst.getCellName());
        }
        names.sort(null);
        return names;
    }

    private static EDIFNetlist parseAsIs(Path edif) throws IOException {
        try (EDIFParser parser = new EDIFParser(edif)) {
            return parser.parseEDIFNetlist();
        }
    }

    @Test
    public void testExpandMacroUnisimsInPlace() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFTools.ensureCorrectPartInEDIF(netlist, Device.AWS_F1);
        EDIFCell ibuf = netlist.getHDIPrimitive(Unisim.IBUF);
        EDIFCellInst inst = netlist.getTopCell().createChildCellInst("ibuf", ibuf);
        Assertions.assertTrue(ibuf.isLeafCellOrBlackBox());

        Assertions.assertTrue(netlist.expandMacroUnisims());
        Assertions.assertSame(ibuf, netlist.getHDIPrimitivesLibrary().getCell("IBUF"));
        Assertions.assertSame(ibuf, inst.getCellType());
        Assertions.assertEquals(2, ibuf.getCellInsts().size());
        EDIFCellInst ibufctrl = ibuf.getCellInst("IBUFCTRL_INST");
        Assertions.assertSame(netlist.getHDIPrimitivesLibrary(), ibufctrl.getCellType().getLibrary());
        Assertions.assertEquals(1, ibufctrl.getCellType().getNonHierInstantiationCount());

        // Expanding again leaves the expanded macro as it is
        List<String> leaves = getLeafNames(netlist);
        int primCount = netlist.getHDIPrimitivesLibrary().getCells().size();
        Assertions.assertTrue(netlist.expandMacroUnisims());
        Assertions.assertSame(ibufctrl, ibuf.getCellInst("IBUFCTRL_INST"));
        Assertions.assertEquals(leaves, getLeafNames(netlist));
        Assertions.assertEquals(primCount, netlist.getHDIPrimitivesLibrary().getCells().size());
        Assertions.assertEquals(1, ibufctrl.getCellType().getNonHierInstantiationCount());
    }

    @Test
    public void testCollapsedView() {
        EDIFNetlist netlist = createNetlist();
        EDIFLibrary prims = netlist.getHDIPrimitivesLibrary();
        EDIFCell ibuf = prims.getCell("IBUF");
        EDIFCell obufds = prims.getCell("OBUFDS");
        EDIFCell dualBuf = prims.getCell("OBUFDS_DUAL_BUF");
        Assertions.assertSame(dualBuf, netlist.getTopCell().getCellInst("obufds_dual").getCellType());
        Assertions.assertSame(obufds, netlist.getTopCell().getCellInst("obufds").getCellType());

        EDIFCollapsedView view = new EDIFCollapsedView(netlist);
        Assertions.assertTrue(view.isCollapsed(ibuf));
        Assertions.assertTrue(view.isCollapsed(dualBuf));
        Assertions.assertFalse(view.isCollapsed(obufds));
        // Instances of OBUFDS_DUAL_BUF are of OBUFDS in this view
        Assertions.assertEquals("OBUFDS", view.getName(dualBuf));
        Assertions.assertSame(obufds, view.getInstanceType(dualBuf));
        Assertions.assertSame(ibuf, view.getInstanceType(ibuf));
        Assertions.assertTrue(view.isOmitted(dualBuf));
        Assertions.assertFalse(view.isOmitted(ibuf));
        Assertions.assertFalse(view.isOmitted(obufds));
        // Primitives that are only in macros are left out
        for (String name : new String[] {"IBUFCTRL", "INBUF", "OBUF", "INV"}) {
            Assertions.assertTrue(view.isOmitted(prims.getCell(name)), name);
        }
    }

    @Test
    public void testExportEDIFCollapsesMacros(@TempDir Path dir) throws IOException {
        EDIFNetlist netlist = createNetlist();
        List<String> leaves = getLeafNames(netlist);
        Path edif = dir.resolve("top.edf");
        netlist.exportEDIF(edif);

        // Writing does not modify the netlist
        Assertions.assertEquals(leaves, getLeafNames(netlist));
        Assertions.assertEquals("OBUFDS_DUAL_BUF", netlist.getTopCell().getCellInst("obufds_dual").getCellName());

        // What is written has its macros collapsed
        EDIFNetlist written = parseAsIs(edif);
        EDIFLibrary prims = written.getHDIPrimitivesLibrary();
        for (EDIFCell cell : prims.getCells()) {
            Assertions.assertTrue(cell.isLeafCellOrBlackBox(), cell.getName());
        }
        Assertions.assertTrue(prims.containsCell("IBUF"));
        Assertions.assertTrue(prims.containsCell("OBUFDS"));
        for (String name : new String[] {"OBUFDS_DUAL_BUF", "IBUFCTRL", "INBUF", "OBUF", "INV"}) {
            Assertions.assertFalse(prims.containsCell(name), name);
        }
        Assertions.assertEquals("OBUFDS", written.getTopCell().getCellInst("obufds_dual").getCellName());
        Assertions.assertEquals("OBUFDS", written.getTopCell().getCellInst("obufds").getCellName());

        // Reading it expands its macros again
        EDIFNetlist read = EDIFTools.readEdifFile(edif);
        Assertions.assertEquals(leaves, getLeafNames(read));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testWriteCheckpointKeepsMacrosExpanded(boolean write2024Dot1DCP, @TempDir Path dir) {
        Design design = RapidWrightDCP.loadDCP("inout.dcp");
        List<String> leaves = getLeafNames(design.getNetlist());
        // Its macros are expanded, such as the IBUFs and the OBUFDS (into an OBUFDS_DUAL_BUF)
        Assertions.assertTrue(leaves.stream().anyMatch(s -> s.contains("/INBUF_INST ")));
        Assertions.assertTrue(leaves.stream().anyMatch(s -> s.startsWith("ob/N ")));

        Path dcp = dir.resolve("inout.dcp");
        boolean orig = Params.RW_WRITE_DCP_2024_1;
        try {
            Params.RW_WRITE_DCP_2024_1 = write2024Dot1DCP;
            design.writeCheckpoint(dcp);
        } finally {
            Params.RW_WRITE_DCP_2024_1 = orig;
        }
        Assertions.assertEquals(leaves, getLeafNames(design.getNetlist()));

        Design read = Design.readCheckpoint(dcp);
        Assertions.assertEquals(leaves, getLeafNames(read.getNetlist()));
    }

    @Test
    public void testWriteLogNetlistKeepsMacrosExpanded(@TempDir Path dir) throws IOException {
        EDIFNetlist netlist = RapidWrightDCP.loadDCP("inout.dcp").getNetlist();
        List<String> leaves = getLeafNames(netlist);
        String fileName = dir.resolve("inout.netlist").toString();
        LogNetlistWriter.writeLogNetlist(netlist, fileName);
        Assertions.assertEquals(leaves, getLeafNames(netlist));

        // What is written has its macros collapsed, and reading it expands them again
        boolean expandMacros = false;
        EDIFNetlist written = LogNetlistReader.readLogNetlist(fileName, expandMacros);
        for (EDIFCell cell : written.getHDIPrimitivesLibrary().getCells()) {
            Assertions.assertTrue(cell.isLeafCellOrBlackBox(), cell.getName());
        }
        Assertions.assertEquals(leaves, getLeafNames(LogNetlistReader.readLogNetlist(fileName)));
    }

    @Test
    public void testExportEDIFExternalLibrary(@TempDir Path dir) throws IOException {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("top");
        EDIFTools.ensureCorrectPartInEDIF(netlist, Device.AWS_F1);
        EDIFCell blackBox = new EDIFCell(netlist.getWorkLibrary(), "bb");
        EDIFCellInst inst = netlist.getTopCell().createChildCellInst("bb_inst", blackBox);

        EDIFNetlist externalNetlist = EDIFTools.createNewNetlist("bb");
        EDIFCell external = externalNetlist.getTopCell();
        external.createChildCellInst("inv", externalNetlist.getHDIPrimitive(Unisim.INV));
        netlist.setExternalLibrary(externalNetlist.getWorkLibrary());
        Assertions.assertSame(external, inst.getCellType());

        Path edif = dir.resolve("top.edf");
        netlist.exportEDIF(edif);
        Assertions.assertSame(external, inst.getCellType());

        // The black box is written as it is, and instantiated in place of the cell that fills it
        EDIFNetlist written = parseAsIs(edif);
        EDIFCellInst writtenInst = written.getTopCell().getCellInst("bb_inst");
        Assertions.assertEquals("bb", writtenInst.getCellName());
        Assertions.assertSame(written.getWorkLibrary(), writtenInst.getCellType().getLibrary());
        Assertions.assertTrue(writtenInst.getCellType().isLeafCellOrBlackBox());
    }
}
