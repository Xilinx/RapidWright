package com.xilinx.rapidwright.design;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.checker.CheckOpenFiles;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Test that we can write an EDIF file and read it back in. We currently don't have a way to check designs for equality,
 * so we just try to catch obvious issues.
 */
public class TestEDIF {

    public static EDIFNetlist createEmptyNetlist() {
        EDIFNetlist netlist = new EDIFNetlist("test");
        netlist.setDesign(new EDIFDesign("test"));
        final EDIFLibrary workLibrary = netlist.getWorkLibrary();
        final EDIFCell topCell = new EDIFCell(workLibrary, "myTestCell");
        netlist.getDesign().setTopCell(topCell);

        EDIFTools.ensureCorrectPartInEDIF(netlist, TestDesign.DEVICE);

        return netlist;
    }

    @Test
    @CheckOpenFiles
    public void checkEdifRoundtrip(@TempDir Path tempDir) throws IOException {
        //Use separate files for writing/reading so we can identify identify leaking file handles by filename
        final Path filenameWrite = tempDir.resolve("testWrite.edf");
        final Path filenameRead = tempDir.resolve("testRead.edf");

        final EDIFNetlist original = createEmptyNetlist();

        EDIFCellInst inst = Design.createUnisimInst(original.getTopCell(), "inst", Unisim.FDRE);
        original.exportEDIF(filenameWrite);
        Files.copy(filenameWrite, filenameRead);

        final EDIFNetlist netlist = EDIFTools.readEdifFile(filenameRead);

        verifyNetlist(netlist, "inst");

    }

    public static void verifyNetlist(EDIFNetlist netlist, String expectedName) {
        final EDIFLibrary workLibrary = netlist.getWorkLibrary();
        Assertions.assertNotNull(workLibrary);

        final EDIFCell cell = workLibrary.getCell("myTestCell");
        Assertions.assertNotNull(cell);
        Assertions.assertEquals(cell, netlist.getTopCell());

        Assertions.assertEquals(cell.getCellInsts().size(), 1);
        final EDIFCellInst inst = cell.getCellInsts().iterator().next();
        Assertions.assertEquals(expectedName, inst.getName());
    }

    private void connectToParent(EDIFCellInst cellInst, String portName, String innerPrefix) {
        EDIFCell parent = cellInst.getParentCell();
        final EDIFNet net = parent.createNet("net/"+portName);
        final EDIFPortInst innerPortInst = net.createPortInst(innerPrefix+portName, cellInst);
        final EDIFPort port = parent.createPort("port/"+portName, innerPortInst.getDirection(), 1);
        net.createPortInst(port);
    }

    private void addFDREConnections(EDIFCellInst cellInst, String innerPrefix) {
        connectToParent(cellInst, "D", innerPrefix);
        connectToParent(cellInst, "C", innerPrefix);
        connectToParent(cellInst, "Q", innerPrefix);
    }

    @Test
    public void testParentNetMap() {
        EDIFNetlist netlist = createWrappedRegisterDesign();

        Map<String, String> expected = new HashMap<>();
        expected.put("net/D", "net/D");
        expected.put("net/C", "net/C");
        expected.put("net/Q", "inter/mediate/net/Q");
        expected.put("inter/mediate/net/D", "net/D");
        expected.put("inter/mediate/net/C", "net/C");
        expected.put("inter/mediate/net/Q", "inter/mediate/net/Q");

        final Map<String, String> actual = netlist.getParentNetMapNames();
        Assertions.assertEquals(expected, actual);
    }

    @Test
    public void testHierGetters() {
        final EDIFNetlist netlist = createWrappedRegisterDesign();

        final EDIFHierCellInst topHierCellInst = netlist.getTopHierCellInst();
        final EDIFHierCellInst intermediate = topHierCellInst.getChild("inter/mediate");
        final EDIFHierCellInst reg = intermediate.getChild("inst/asdf");

        //Check cell insts
        Assertions.assertEquals(topHierCellInst, netlist.getHierCellInstFromName(""));
        Assertions.assertEquals(intermediate, netlist.getHierCellInstFromName("inter/mediate"));
        Assertions.assertEquals(reg, netlist.getHierCellInstFromName("inter/mediate/inst/asdf"));

        //Check nets
        Assertions.assertEquals(topHierCellInst.getNet("net/D"), netlist.getHierNetFromName("net/D"));
        Assertions.assertEquals(topHierCellInst.getNet("net/C"), netlist.getHierNetFromName("net/C"));
        Assertions.assertEquals(topHierCellInst.getNet("net/Q"), netlist.getHierNetFromName("net/Q"));
        Assertions.assertEquals(intermediate.getNet("net/D"), netlist.getHierNetFromName("inter/mediate/net/D"));
        Assertions.assertEquals(intermediate.getNet("net/C"), netlist.getHierNetFromName("inter/mediate/net/C"));
        Assertions.assertEquals(intermediate.getNet("net/Q"), netlist.getHierNetFromName("inter/mediate/net/Q"));

        //Check Ports
        Assertions.assertEquals(intermediate.getPortInst("port/D"), netlist.getHierPortInstFromName("inter/mediate/port/D"));
        Assertions.assertEquals(intermediate.getPortInst("port/C"), netlist.getHierPortInstFromName("inter/mediate/port/C"));
        Assertions.assertEquals(intermediate.getPortInst("port/Q"), netlist.getHierPortInstFromName("inter/mediate/port/Q"));
        Assertions.assertEquals(reg.getPortInst("D"), netlist.getHierPortInstFromName("inter/mediate/inst/asdf/D"));
        Assertions.assertEquals(reg.getPortInst("C"), netlist.getHierPortInstFromName("inter/mediate/inst/asdf/C"));
        Assertions.assertEquals(reg.getPortInst("Q"), netlist.getHierPortInstFromName("inter/mediate/inst/asdf/Q"));
    }



    SoftReference<EDIFNetlist> wrappedRegisterDesign = null;
    private EDIFNetlist createWrappedRegisterDesign() {
        //Make a hard reference before checking to avoid race condition!
        EDIFNetlist netlist = wrappedRegisterDesign != null ? wrappedRegisterDesign.get() : null;
        if (netlist == null) {
            netlist = createEmptyNetlist();

            final EDIFCell topCell = netlist.getTopCell();
            final EDIFCell intermediate = new EDIFCell(netlist.getWorkLibrary(), "intermediate");

            EDIFCellInst fdreInst = Design.createUnisimInst(intermediate, "inst/asdf", Unisim.FDRE);
            final EDIFCellInst intermediateInst = intermediate.createCellInst("inter/mediate", topCell);

            addFDREConnections(fdreInst, "");
            addFDREConnections(intermediateInst, "port/");
            wrappedRegisterDesign = new SoftReference<>(netlist);
        }
        return netlist;
    }

    @ParameterizedTest(name="Macro Unisim expansion of {0}")
    @EnumSource(names = {"LUT6_2", "CFGLUT5", "BUFG"})
    @CheckOpenFiles
    public void testMacroExpansion(Unisim unisim) {
        EDIFNetlist netlist = createEmptyNetlist();

        EDIFCell cell = netlist.getHDIPrimitive(unisim);
        cell.createCellInst("inst", netlist.getTopCell());

        EDIFCellInst cellInst = netlist.getCellInstFromHierName("inst");
        Assertions.assertNotNull(cellInst);
        Assertions.assertEquals(cellInst.getCellName(), unisim.toString());
        Assertions.assertTrue(!cellInst.getCellType().hasContents());

        netlist.expandMacroUnisims(Series.UltraScale);

        cellInst = netlist.getCellInstFromHierName("inst");
        Assertions.assertEquals(cellInst.getCellName(), unisim.toString());
        cell = cellInst.getCellType();
        Assertions.assertTrue(cell.getAllLeafDescendants().size() >= 2);
    }
}
