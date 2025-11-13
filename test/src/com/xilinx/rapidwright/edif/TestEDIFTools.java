
/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Params;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestEDIFTools {

    public static final String UNIQUE_SUFFIX = "TestEDIFToolsWasHere";

    public static final String TEST_SRC = "base_mb_i/microblaze_0/U0/"
            + "MicroBlaze_Core_I/Performance.Core/Data_Flow_I/Data_Flow_Logic_I/Gen_Bits[22]."
            + "MEM_EX_Result_Inst/Using_FPGA.Native/Q";
    public static final String TEST_SNK = "u_ila_0/inst/PROBE_PIPE.shift_probes_reg[0][7]/D";
    public static final String TEST_SNK2 = "u_ila_0/inst/PROBE_PIPE.shift_probes_reg[0][8]/D";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testConnectPortInstsThruHier(boolean netToPin) {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("microblazeAndILA_3pblocks.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();

        EDIFHierPortInst srcPortInst = netlist.getHierPortInstFromName(TEST_SRC);
        EDIFHierPortInst snkPortInst = netlist.getHierPortInstFromName(TEST_SNK);

        // Disconnect sink in anticipation of connecting to another net
        snkPortInst.getNet().removePortInst(snkPortInst.getPortInst());

        if (netToPin) {
            EDIFTools.connectPortInstsThruHier(srcPortInst.getHierarchicalNet(), snkPortInst, UNIQUE_SUFFIX);
        } else {
            EDIFTools.connectPortInstsThruHier(srcPortInst, snkPortInst, UNIQUE_SUFFIX);
        }

        netlist.resetParentNetMap();


        List<EDIFHierNet> netAliases = netlist.getNetAliases(srcPortInst.getHierarchicalNet());
        Assertions.assertEquals(netAliases.size(), 16);
        boolean containsSnkNet = false;
        for (EDIFHierNet net : netAliases) {
            if (net.getHierarchicalNetName().equals(snkPortInst.getHierarchicalNetName())) {
                containsSnkNet = true;
            }
        }
        Assertions.assertTrue(containsSnkNet);


        List<EDIFHierPortInst> portInsts = netlist.getPhysicalPins(srcPortInst.getHierarchicalNet());
        Assertions.assertEquals(portInsts.size(), 6);
        boolean containsSnk = false;
        for (EDIFHierPortInst sink : portInsts) {
            if (sink.toString().equals(snkPortInst.toString())) {
                containsSnk = true;
            }
        }
        Assertions.assertTrue(containsSnk);

        // Now check if it will reuse ports already created

        // Disconnect sink in anticipation of connecting to another net
        EDIFHierPortInst snkPortInst2 = netlist.getHierPortInstFromName(TEST_SNK2);
        snkPortInst2.getNet().removePortInst(snkPortInst2.getPortInst());

        // Count number of ports prior to connection to ensure no additional ports were
        // created
        List<Integer> portCounts = new ArrayList<>();
        List<EDIFCellInst> insts = snkPortInst2.getFullHierarchicalInst().getFullHierarchy();
        for (EDIFCellInst i : insts) {
            portCounts.add(i.getCellPorts().size());
        }

        if (netToPin) {
            EDIFTools.connectPortInstsThruHier(srcPortInst.getHierarchicalNet(), snkPortInst2,
                    UNIQUE_SUFFIX);
        } else {
            EDIFTools.connectPortInstsThruHier(srcPortInst, snkPortInst2, UNIQUE_SUFFIX);
        }

        // Ensure no additional ports were created
        for (int i = 0; i < insts.size(); i++) {
            EDIFCellInst inst = insts.get(i);
            Assertions.assertEquals(portCounts.get(i), inst.getCellPorts().size());
        }

        Assertions.assertEquals(snkPortInst.getNet(), snkPortInst2.getNet());
    }

    @Test
    public void testConnectPortInstsThruHierNet() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("bnn.dcp"), true);
        EDIFNetlist netlist = d.getNetlist();
        boolean includeSrcs = true;
        boolean includeSnks = false;

        // Test connecting a source in a low hierarchical cell to a unconnected net in at the root
        //   [pin] 'bd_0_i/hls_inst/inst/add_ln180_1_reg_1471_reg[5]/Q' --> 
        //   [net] 'test_net'
        String netName = "test_net";
        d.getTopEDIFCell().createNet(netName);
        EDIFHierNet net = netlist.getHierNetFromName(netName);
        EDIFHierPortInst pin = netlist.getHierPortInstFromName("bd_0_i/hls_inst/inst/add_ln180_1_reg_1471_reg[5]/Q");
        Assertions.assertEquals(2, pin.getNet().getPortInsts().size());
        EDIFTools.connectPortInstsThruHier(net, pin, "test_connect");
        Assertions.assertEquals(1, net.getNet().getPortInsts().size());
        List<EDIFHierPortInst> leafPins = net.getLeafHierPortInsts(includeSrcs, includeSnks);
        Assertions.assertEquals(1, leafPins.size());
        Assertions.assertEquals(pin.toString(), leafPins.get(0).toString());
        // Needed to punch a new port ...
        Assertions.assertEquals(3, pin.getNet().getPortInsts().size());
        Assertions.assertTrue(pin.getHierarchicalNet().getPortInsts().stream().anyMatch(
                // ... upwards from the pin
                ehpi -> ehpi.toString().equals("bd_0_i/hls_inst/inst/test_connect")
        ));


        // Test connecting a source in a low hierarchical cell to an unconnected net in another (separate) low hierarchical cell
        //   [pin] 'bd_0_i/hls_inst/inst/grp_bin_dense_fu_523/ram_reg_bram_0_i_6__4/O' -->
        //   [net] 'bd_0_i/hls_inst/inst/wt_mem_V_U/top_wt_mem_V_ram_U/test_net2'
        netName = "test_net2";
        EDIFHierCellInst targetInst = netlist.getHierCellInstFromName("bd_0_i/hls_inst/inst/wt_mem_V_U/top_wt_mem_V_ram_U");
        targetInst.getCellType().createNet(netName);
        net = targetInst.getNet(netName);
        pin = netlist.getHierPortInstFromName("bd_0_i/hls_inst/inst/grp_bin_dense_fu_523/ram_reg_bram_0_i_6__4/O");
        Assertions.assertEquals(2, pin.getNet().getPortInsts().size());
        EDIFTools.connectPortInstsThruHier(net, pin, "test_connect2");
        Assertions.assertEquals(1, net.getNet().getPortInsts().size());
        leafPins = net.getLeafHierPortInsts(includeSrcs, includeSnks);
        Assertions.assertEquals(1, leafPins.size());
        Assertions.assertEquals(pin.toString(), leafPins.get(0).toString());
        // Re-used the existing port
        Assertions.assertEquals(2, pin.getNet().getPortInsts().size());

        includeSrcs = false;
        includeSnks = true;

        // Test connecting a sink in a low hierarchical cell to a unconnected net in at the root
        //   [net] 'test_net3' -->
        //   [pin] 'bd_0_i/hls_inst/inst/add_ln180_1_reg_1471_reg[5]/D'
        netName = "test_net3";
        d.getTopEDIFCell().createNet(netName);
        net = netlist.getHierNetFromName(netName);
        Assertions.assertEquals(0, net.getNet().getPortInsts().size());
        pin = netlist.getHierPortInstFromName("bd_0_i/hls_inst/inst/add_ln180_1_reg_1471_reg[5]/D");
        Assertions.assertEquals(2, pin.getNet().getPortInsts().size());
        ECOTools.disconnectNet(d, pin);
        Assertions.assertNull(pin.getNet());
        EDIFTools.connectPortInstsThruHier(net, pin, "test_connect3");
        // Needed to punch a new port
        Assertions.assertEquals(1, net.getNet().getPortInsts().size());
        Assertions.assertTrue(net.getPortInsts().stream().anyMatch(
                // ... downwards from the net
                ehpi -> ehpi.toString().equals("bd_0_i/test_connect3")
        ));


        leafPins = net.getLeafHierPortInsts(includeSrcs, includeSnks);
        Assertions.assertEquals(1, leafPins.size());
        Assertions.assertEquals(pin.toString(), leafPins.get(0).toString());

        // Needed to punch a new port
        Assertions.assertEquals(2, pin.getNet().getPortInsts().size());
        Assertions.assertTrue(pin.getHierarchicalNet().getPortInsts().stream().anyMatch(
                // ... upwards from the pin
                ehpi -> ehpi.toString().equals("bd_0_i/hls_inst/inst/test_connect3")
        ));

        // Test connecting a sink in a low hierarchical cell to an unconnected net in
        // another (separate) low hierarchical cell
        //   [net] 'bd_0_i/hls_inst/inst/wt_mem_V_U/top_wt_mem_V_ram_U/test_net4' -->
        //   [pin] 'bd_0_i/hls_inst/inst/add_ln180_1_reg_1471_reg[4]/D'
        netName = "test_net4";
        targetInst.getCellType().createNet(netName);
        net = targetInst.getNet(netName);
        Assertions.assertEquals(0, net.getNet().getPortInsts().size());
        pin = netlist.getHierPortInstFromName("bd_0_i/hls_inst/inst/add_ln180_1_reg_1471_reg[4]/D");
        Assertions.assertEquals(2, pin.getNet().getPortInsts().size());
        ECOTools.disconnectNet(d, pin);
        Assertions.assertNull(pin.getNet());
        EDIFTools.connectPortInstsThruHier(net, pin, "test_connect4");
        // Needed to punch a new port
        Assertions.assertEquals(1, net.getNet().getPortInsts().size());
        Assertions.assertTrue(net.getPortInsts().stream().anyMatch(
                // ... upwards from the net
                ehpi -> ehpi.toString().equals("bd_0_i/hls_inst/inst/wt_mem_V_U/top_wt_mem_V_ram_U/test_connect4")
        ));

        leafPins = net.getLeafHierPortInsts(includeSrcs, includeSnks);
        Assertions.assertEquals(1, leafPins.size());
        Assertions.assertEquals(pin.toString(), leafPins.get(0).toString());

        // Needed to punch a new port
        Assertions.assertEquals(2, pin.getNet().getPortInsts().size());
        Assertions.assertTrue(pin.getHierarchicalNet().getPortInsts().stream().anyMatch(
                // ... downwards from the pin
                ehpi -> ehpi.toString().equals("bd_0_i/hls_inst/inst/wt_mem_V_U/test_connect4")
        ));
    }
    
    
    @Test
    public void testCreateNewNetlist() {
        Design d = Design.readCheckpoint(RapidWrightDCP.getPath("bnn.dcp"), true);
        EDIFHierCellInst inst = d.getNetlist().getHierCellInstFromName("bd_0_i/hls_inst/inst/dmem_V_U");

        EDIFNetlist newNetlist = EDIFTools.createNewNetlist(inst.getInst());
        EDIFTools.ensureCorrectPartInEDIF(newNetlist, d.getPartName());
        Design d2 = new Design(newNetlist);
        d2.setAutoIOBuffers(false);
        d2.setDesignOutOfContext(true);

        List<EDIFHierCellInst> goldChildren = d.getNetlist().getAllLeafDescendants(inst);
        List<EDIFHierCellInst> testChildren = d2.getNetlist().getAllLeafDescendants("");

        Assertions.assertEquals(goldChildren.size(), testChildren.size());
    }

    @Test
    void testRename() {
        //This test string contains multi-byte characters. We cannot encode it directly as a string here, because
        //source code encoding varies between platforms.
        byte[] special = new byte[]{
                (byte)0x65, (byte)0x6d, (byte)0x6f, (byte)0x6a, (byte)0x69, (byte)0x5f, (byte)0xf0,
                (byte)0x9f, (byte)0x98, (byte)0x8b, (byte)0xf0, (byte)0x9f, (byte)0x8e, (byte)0x9b,
                (byte)0xef, (byte)0xb8, (byte)0x8f
        };
        String unicodeStr = new String(special, StandardCharsets.UTF_8);
        Assertions.assertEquals("emoji______", EDIFTools.makeNameEDIFCompatible(unicodeStr));
        Assertions.assertEquals("&_", EDIFTools.makeNameEDIFCompatible(" "));
    }

    @Test
    public void testUniqueifyNetlist() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        Design design = new Design("test", Device.PYNQ_Z1);
        design.setNetlist(netlist);

        EDIFCell top = netlist.getTopCell();
        EDIFCell foo = new EDIFCell(netlist.getWorkLibrary(), "foo");
        EDIFCell bar = new EDIFCell(netlist.getWorkLibrary(), "bar");
        EDIFCell baz = new EDIFCell(netlist.getWorkLibrary(), "baz");

        bar.createChildCellInst("baz1", baz);
        bar.createChildCellInst("baz2", baz);

        foo.createChildCellInst("bar1", bar);
        foo.createChildCellInst("bar2", bar);

        top.createChildCellInst("foo1", foo);
        top.createChildCellInst("foo2", foo);

        Assertions.assertTrue(EDIFTools.uniqueifyNetlist(design));

        for (Entry<EDIFLibrary, Map<EDIFCell, List<EDIFHierCellInst>>> e :
                                            EDIFTools.createCellInstanceMap(netlist).entrySet()) {
            if (e.getKey().isHDIPrimitivesLibrary()) continue;
            for (Entry<EDIFCell, List<EDIFHierCellInst>> e2 : e.getValue().entrySet()) {
                Assertions.assertEquals(e2.getValue().size(), 1);
            }
        }

        Assertions.assertFalse(EDIFTools.uniqueifyNetlist(design));
    }

    @Test
    public void testCreateUniqueNet() {
        Design design = new Design("test", Device.AWS_F1);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = netlist.getTopCell();

        String netName = "foo";
        Assertions.assertEquals(netName, EDIFTools.createUniqueNet(top, netName).getName());

        String newNet1 = EDIFTools.createUniqueNet(top, netName).getName();
        Assertions.assertNotEquals(newNet1, netName);
        Assertions.assertTrue(newNet1.matches(netName + "_rw_created\\d+"));
        String newNet2 = EDIFTools.createUniqueNet(top, netName).getName();
        Assertions.assertNotEquals(newNet2, netName);
        Assertions.assertNotEquals(newNet2, newNet1);
        Assertions.assertTrue(newNet2.matches(netName + "_rw_created\\d+"));

        // Check that creating a net with the same name as an existing port is allowed.
        String portName = "bar";
        top.createPort(portName, EDIFDirection.INPUT, 1);
        Assertions.assertEquals(portName, EDIFTools.createUniqueNet(top, portName).getName());

        // Canary to check that creating a net with the same name as the root name of an existing bus net
        // -- designating by the existence of at least one bus[\d+] -- is allowed.
        // (Even though doing so may cause Vivado an issue.)
        String busNetName = "baz";
        top.createNet(busNetName + "[999]");
        Assertions.assertEquals(busNetName, EDIFTools.createUniqueNet(top, busNetName).getName());
    }

    @Test
    public void testCreateUniquePort() {
        Design design = new Design("test", Device.AWS_F1);
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell top = netlist.getTopCell();

        // Single-bit port
        String portName = "foo";
        Assertions.assertEquals(portName, EDIFTools.createUniquePort(top, portName, EDIFDirection.INPUT, 1).getName());

        // Multi-bit bus port
        int busPortWidth = 16;
        String busPortBaseName = "bar";
        String busPortName = busPortBaseName + "[" + (busPortWidth-1) + ":0]";
        Assertions.assertEquals(busPortName, EDIFTools.createUniquePort(top, busPortName, EDIFDirection.INPUT, 16).getName());

        // Check that creating a new port with the same basename as a port gets uniquified
        String slicedPortName = busPortBaseName + "[17]";
        String newPort1 = EDIFTools.createUniquePort(top, slicedPortName, EDIFDirection.INPUT, 1).getName();
        Assertions.assertNotEquals(newPort1, slicedPortName);
        Assertions.assertTrue(newPort1.matches(Pattern.quote(slicedPortName) + "_rw_created\\d+"));
        String newPort2 = EDIFTools.createUniquePort(top, slicedPortName, EDIFDirection.OUTPUT, 1).getName();
        Assertions.assertNotEquals(newPort2, slicedPortName);
        Assertions.assertNotEquals(newPort2, newPort1);
        Assertions.assertTrue(newPort2.matches(Pattern.quote(slicedPortName) + "_rw_created\\d+"));
    }

    @Test
    public void testCreateFlatNetlist() {
        Design d = RapidWrightDCP.loadDCP("picoblaze_2022.2.dcp");
        EDIFNetlist flatNetlist = EDIFTools.createFlatNetlist(d.getNetlist(), d.getPartName());
        Assertions.assertTrue(flatNetlist.getHierCellInstFromName("your_program").getCellType().isLeafCellOrBlackBox());
        Assertions.assertEquals(445, flatNetlist.getAllLeafHierCellInstances().size());
        flatNetlist.collapseMacroUnisims(d.getDevice().getSeries());
        Assertions.assertEquals(321, flatNetlist.getAllLeafHierCellInstances().size());
        boolean includeBlackBoxes = true;
        Assertions.assertEquals(322, flatNetlist.getAllLeafHierCellInstances(includeBlackBoxes).size());
    }

    /**
     * In the provided directory, it will create 3 files: (1) <name>.dcp, (2)
     * <name>.edf, (3) dummy.edn. This is to simulate a DCP that contains encrypted
     * cells.
     * 
     * @param dir         Destination directory to write the three files
     * @param testDCPName Name of the test DCP to read from the RapidWrightDCP
     *                    directory.
     * @return The path to the resulting DCP.
     */
    public static Path createEncryptedDCPExample(Path dir, String testDCPName, String cellName) {
        Path dcp = RapidWrightDCP.getPath(testDCPName);
        Design tmp = Design.readCheckpoint(dcp, true);
        Path edf = dir.resolve(testDCPName.replace(".dcp", ".edf"));
        tmp.getNetlist().exportEDIF(edf);
        String dummyEDN = cellName + ".edn";
        Path copyDCP = dir.resolve(testDCPName);
        FileTools.copyFile(dcp.toString(), copyDCP.toString());
        FileTools.writeStringToTextFile("Dummy EDN", dir.resolve(dummyEDN).toString());
        return copyDCP;
    }

    public static final String PICOBLAZE_BB_CELLNAME = "ram_4096x8_bb";

    @Test
    public void testCopyEDNOnDCPWrite(@TempDir Path dir) {
        Path srcDir = dir.resolve("src");
        FileTools.makeDir(srcDir.toString());
        Path dcp = createEncryptedDCPExample(srcDir, "picoblaze_2022.2.dcp", PICOBLAZE_BB_CELLNAME);
        Path edf = srcDir.resolve(dcp.getFileName().toString().replace(".dcp", ".edf"));
        Path edn = srcDir.resolve(PICOBLAZE_BB_CELLNAME + ".edn");

        Design d = Design.readCheckpoint(dcp, edf);

        Assertions.assertEquals(1, d.getNetlist().getEncryptedCells().size());

        Params.RW_COPY_EDNS_ON_DCP_WRITE = true;

        d.writeCheckpoint(dir.resolve(dcp.getFileName()));
        Assertions.assertTrue(Files.exists(dir.resolve(edn.getFileName())));

        Path tclLoadScript = dir
                .resolve(dcp.getFileName().toString().replace(".dcp", EDIFTools.LOAD_TCL_SUFFIX));
        Assertions.assertTrue(Files.exists(tclLoadScript));

        boolean hasDummyEDN = false;
        for (String line : FileTools.getLinesFromTextFile(tclLoadScript.toString())) {
            if (line.contains("read_edif") && line.contains(edn.getFileName().toString())) {
                hasDummyEDN = true;
                break;
            }
        }
        Assertions.assertTrue(hasDummyEDN);

    }

    @Test
    public void testEnsurePreservedInterfaceVivado(@TempDir Path dir) {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        topCell.renamePort("dmem_i_V_ce0", "test_port[0]");
        topCell.renamePort("kh_i_V_ce0", "test_port[1]");
        topCell.renamePort("wt_i_V_ce0", "test_port[2]");

        VivadoToolsHelper.assertPortCountAfterRoundTripInVivado(design, dir, false);
        EDIFTools.ensurePreservedInterfaceVivado(design.getNetlist());
        VivadoToolsHelper.assertPortCountAfterRoundTripInVivado(design, dir, true);
    }

    @Test
    public void testRemoveVivadoBusPreventionAnnotations(@TempDir Path dir) {
        Design design = RapidWrightDCP.loadDCP("bnn.dcp");
        EDIFNetlist netlist = design.getNetlist();
        EDIFCell topCell = netlist.getTopCell();

        topCell.renamePort("dmem_i_V_ce0", EDIFTools.VIVADO_PRESERVE_PORT_INTERFACE + "test_port[0]");
        topCell.renamePort("kh_i_V_ce0", EDIFTools.VIVADO_PRESERVE_PORT_INTERFACE + "test_port[1]");
        topCell.renamePort("wt_i_V_ce0", EDIFTools.VIVADO_PRESERVE_PORT_INTERFACE + "test_port[2]");

        EDIFTools.removeVivadoBusPreventionAnnotations(design.getNetlist());
        EDIFPort testPort0 = topCell.getPort("test_port[0]");
        Assertions.assertNotNull(testPort0);
        Assertions.assertFalse(testPort0.getName().startsWith(EDIFTools.VIVADO_PRESERVE_PORT_INTERFACE));
        EDIFPort testPort1 = topCell.getPort("test_port[1]");
        Assertions.assertNotNull(testPort1);
        Assertions.assertFalse(testPort1.getName().startsWith(EDIFTools.VIVADO_PRESERVE_PORT_INTERFACE));
        EDIFPort testPort2 = topCell.getPort("test_port[2]");
        Assertions.assertNotNull(testPort2);
        Assertions.assertFalse(testPort2.getName().startsWith(EDIFTools.VIVADO_PRESERVE_PORT_INTERFACE));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testWriteEDIFFilterUnrelatedEDNFiles(@TempDir Path dir)
            throws NoSuchFieldException, SecurityException, IllegalArgumentException,
            IllegalAccessException {
        String name = "picoblaze_ooc_X10Y235.dcp";
        Path dcp = dir.resolve(name);
        Path edf = dir.resolve(name.replace(".dcp", ".edf"));
        Path edn = dir.resolve("fake.edn");
        Design d = RapidWrightDCP.loadDCP(name);
        d.writeCheckpoint(dcp);
        // All our test DCPs have unencrypted EDIF inside, we need to create an external
        // one
        d.getNetlist().exportEDIF(edf);
        FileTools.writeStringToTextFile("Fake EDIF", edn.toString());

        // We need to read an external EDIF in order to trigger the .edn search
        // When provided with an external EDIF, RapidWright will look for encrypted cells in the same directory as the 
        // .edn file, keeping a record of possible encrypted cells as it goes.  
        d = Design.readCheckpoint(dcp, edf);

        {
            Field encCellsList = EDIFNetlist.class.getDeclaredField("encryptedCells");
            encCellsList.setAccessible(true);
            Assertions.assertEquals(1, ((List<String>) encCellsList.get(d.getNetlist())).size());
        }

        String outputName = "test";
        Path loadScript = dir.resolve(outputName + EDIFTools.LOAD_TCL_SUFFIX);
        d.writeCheckpoint(dir.resolve(outputName + ".dcp"));
        Assertions.assertFalse(Files.exists(loadScript));
    }
}
