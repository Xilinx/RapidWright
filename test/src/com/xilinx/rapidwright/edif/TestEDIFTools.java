
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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.compare.EDIFLeafNetlistComparator;
import com.xilinx.rapidwright.edif.compare.EDIFRandomSimEquivalence;
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

    // ---------------------------------------------------------------- netlist optimization

    private static Map<String, String> optProbeExpected;

    /** Loads Vivado's opt_design -propconst -sweep results for the probes (leaf dumps). */
    private static synchronized Map<String, String> getOptProbeExpected() throws Exception {
        if (optProbeExpected == null) {
            optProbeExpected = new java.util.HashMap<>();
            try (java.io.InputStream is = TestEDIFTools.class.getResourceAsStream(
                    "/edif/opt_probes_expected.txt")) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                String all = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                for (String section : all.split("### ")) {
                    int nl = section.indexOf('\n');
                    if (nl > 0) {
                        optProbeExpected.put(section.substring(0, nl),
                                section.substring(nl + 1));
                    }
                }
            }
        }
        return optProbeExpected;
    }

    static Stream<String> optProbeNames() throws Exception {
        Map<String, String> expected = getOptProbeExpected();
        return OptProbes.getProbes().keySet().stream().filter(expected::containsKey);
    }

    private static String dump(EDIFNetlist netlist) {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        EDIFLeafNetlistComparator.dump(netlist, new java.io.PrintStream(bos));
        return bos.toString();
    }

    @ParameterizedTest
    @MethodSource("optProbeNames")
    public void testOptimizeNetlistMatchesVivado(String probe) throws Exception {
        EDIFNetlist netlist = OptProbes.getProbes().get(probe).get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        Assertions.assertEquals(getOptProbeExpected().get(probe), dump(netlist));
    }

    static Stream<String> optProbeNamesIdempotent() throws Exception {
        // Inverter absorption does not re-simplify the absorbing LUT (as in Vivado), so a
        // second pass may still find something to do
        return optProbeNames().filter(n -> !n.equals("v2_inv_same_var"));
    }

    @ParameterizedTest
    @MethodSource("optProbeNamesIdempotent")
    public void testOptimizeNetlistIsIdempotent(String probe) {
        EDIFNetlist netlist = OptProbes.getProbes().get(probe).get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        String first = dump(netlist);
        EDIFOptStats again = EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        Assertions.assertEquals(first, dump(netlist));
        for (EDIFOptStats.Phase phase : EDIFOptStats.Phase.values()) {
            Assertions.assertEquals(0, again.getCreated(phase));
            Assertions.assertEquals(0, again.getRemoved(phase));
        }
    }

    @ParameterizedTest
    @MethodSource("optProbeNames")
    public void testOptimizeNetlistIsEquivalent(String probe) {
        EDIFNetlist orig = OptProbes.getProbes().get(probe).get();
        EDIFNetlist opt = OptProbes.getProbes().get(probe).get();
        EDIFTools.optimizeNetlist(opt, new EDIFOptOptions());
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(orig, opt, 4), eq.getExamples().toString());
    }

    @Test
    public void testRandomSimEquivalenceDetectsDifference() {
        EDIFNetlist orig = OptProbes.getProbes().get("lut_chain").get();
        EDIFNetlist bad = OptProbes.getProbes().get("lut_chain").get();
        bad.getTopCell().getCellInst("C3").addProperty("INIT", "4'h9");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(orig, bad, 2) > 0);
    }

    @Test
    public void testOptimizeLUTRules() {
        OptProbes.B b = new OptProbes.B("lut_rules");
        b.in("a", "b", "c");
        b.out("o", "p");
        // LUT4 with I2 tied low shrinks to a LUT3 with its remaining pins in order
        b.lut("L", "6996", "o", "a", "b", "0", "c");
        // LUT2 AND with I1 tied high is a buffer: removed, its load connects to 'a'
        b.lut("BF", "8", "m", "a", "1");
        b.lut("L2", "E", "p", "m", "b");
        EDIFOptStats stats = EDIFTools.propagateConstants(b.nl);
        EDIFCellInst l = b.top.getCellInst("L");
        Assertions.assertEquals("LUT3", l.getCellType().getName());
        Assertions.assertEquals("8'h96", l.getProperty("INIT").getValue());
        Assertions.assertEquals("PROPCONST", l.getProperty("OPT_MODIFIED").getValue());
        Assertions.assertEquals("c", l.getPortInst("I2").getNet().getName());
        Assertions.assertNull(b.top.getCellInst("BF"));
        Assertions.assertEquals("a", b.top.getCellInst("L2").getPortInst("I0").getNet().getName());
        Assertions.assertEquals(1, stats.getCreated(EDIFOptStats.Phase.CONSTANT_PROPAGATION));
        Assertions.assertEquals(2, stats.getRemoved(EDIFOptStats.Phase.CONSTANT_PROPAGATION));
    }

    @Test
    public void testOptimizeFlipFlopRules() {
        OptProbes.B b = new OptProbes.B("ff_rules");
        b.in("clk", "a", "r", "ce");
        b.out("q0", "q1", "q2");
        // CE low and reset value equal to INIT: constant 0
        b.fdre("F0", "a", "0", "r", "q0", "0");
        // CE low but reset can change INIT=1: kept
        b.fdre("F1", "a", "0", "r", "q1", "1");
        // D constant equal to INIT, no reset: constant 1
        b.fdre("F2", "1", "ce", "0", "q2", "1");
        EDIFTools.propagateConstants(b.nl);
        Assertions.assertNull(b.top.getCellInst("F0"));
        Assertions.assertNotNull(b.top.getCellInst("F1"));
        Assertions.assertNull(b.top.getCellInst("F2"));
        EDIFNet q0 = b.top.getInternalNet("q0");
        Assertions.assertTrue(q0.isGND());
        Assertions.assertTrue(b.top.getInternalNet("q2").isVCC());
    }

    @Test
    public void testOptimizeInverterAbsorption() {
        OptProbes.B b = new OptProbes.B("inv_rules");
        b.in("clk", "a", "b", "x");
        b.out("o", "q");
        b.lut("IV", "1", "n", "a");
        b.lut("L", "6", "o", "n", "b");
        b.fdre("F", "x", "1", "n", "q", "0");
        EDIFTools.propagateConstants(b.nl);
        // The inverter is absorbed by the LUT (INIT) and the flip-flop reset (IS_R_INVERTED)
        Assertions.assertNull(b.top.getCellInst("IV"));
        Assertions.assertEquals("4'h9", b.top.getCellInst("L").getProperty("INIT").getValue());
        EDIFCellInst f = b.top.getCellInst("F");
        Assertions.assertEquals("1'b1", f.getProperty("IS_R_INVERTED").getValue());
        Assertions.assertEquals("a", f.getPortInst("R").getNet().getName());
    }

    private static void assertOptimizedEquivalent(Supplier<EDIFNetlist> s, EDIFOptOptions opts) {
        EDIFNetlist orig = s.get();
        EDIFNetlist opt = s.get();
        EDIFTools.optimizeNetlist(opt, opts);
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(orig, opt, 4), eq.getExamples().toString());
        Assertions.assertTrue(eq.getChecked() > 0);
    }

    /** Two inverters in series, the first one also driving a flip-flop data input. */
    private static EDIFNetlist inverterPairPartialAbsorption(String first, String second) {
        OptProbes.B b = new OptProbes.B("inv_pair");
        b.in("clk", "a");
        b.out("o", "q");
        b.lut(first, "1", "n", "a");
        b.lut(second, "1", "o", "n");
        b.fdre("F", "n", "1", "0", "q", "0");
        return b.nl;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testOptimizePartialInverterAbsorptionKeepsPolarity(boolean swapNames) {
        // The second inverter absorbs the first one and becomes a buffer of 'a'
        String first = swapNames ? "B" : "A";
        String second = swapNames ? "A" : "B";
        Supplier<EDIFNetlist> s = () -> inverterPairPartialAbsorption(first, second);
        EDIFNetlist netlist = s.get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        Assertions.assertNull(top.getCellInst(second));
        Assertions.assertSame(top.getInternalNet("a"), top.getInternalNet("o"));
        assertOptimizedEquivalent(s, new EDIFOptOptions());
        assertOptimizedEquivalent(s, new EDIFOptOptions().setSweep(false));
    }

    /**
     * A LUT drives two inverters in series; the second one drives a flip-flop reset (which
     * absorbs it) and a flip-flop that is swept.
     */
    private static EDIFNetlist inverterChainToReset(String i0, String i1) {
        OptProbes.B b = new OptProbes.B("inv_chain_reset");
        b.in("clk", "a", "b", "d");
        b.out("q");
        b.lut("L", "6", "x", "a", "b");
        b.lut(i0, "1", "n", "x");
        b.lut(i1, "1", "m", "n");
        b.fdre("F", "d", "1", "m", "q", "0");
        b.fdre("F2", "n", "1", "0", "", "0");
        return b.nl;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testOptimizeTracksLoadsMovedOntoAliasedNets(boolean swapNames) {
        String i0 = swapNames ? "I1" : "I0";
        String i1 = swapNames ? "I0" : "I1";
        Supplier<EDIFNetlist> s = () -> inverterChainToReset(i0, i1);
        EDIFNetlist netlist = s.get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCellInst f = netlist.getTopCell().getCellInst("F");
        Assertions.assertNotNull(f.getPortInst("R"));
        Assertions.assertNotNull(f.getPortInst("R").getNet());
        Assertions.assertNotNull(netlist.getTopCell().getCellInst("L"));
        assertOptimizedEquivalent(s, new EDIFOptOptions());
    }

    static Stream<Supplier<EDIFNetlist>> optConnectivityCases() {
        return Stream.concat(OptProbes.getProbes().values().stream(), Stream.of(
                () -> inverterPairPartialAbsorption("A", "B"),
                () -> inverterPairPartialAbsorption("B", "A"),
                () -> inverterChainToReset("I0", "I1"),
                () -> inverterChainToReset("I1", "I0"),
                () -> inverterPairManyLoads("A", "B", 20),
                () -> inverterPairManyLoads("B", "A", 20),
                TestEDIFTools::inverterIntoChild));
    }

    @ParameterizedTest
    @MethodSource("optConnectivityCases")
    public void testOptimizerConnectivityInvariants(Supplier<EDIFNetlist> s) {
        for (boolean propConst : new boolean[] {false, true}) {
            EDIFNetlist netlist = s.get();
            EDIFLeafIndex idx = new EDIFLeafIndex(netlist, EDIFConstPropEngine::hasPreserveProp);
            EDIFConstPropEngine e = new EDIFConstPropEngine(idx,
                    new EDIFOptOptions().setPropConst(propConst));
            e.run(new EDIFOptStats());
            Assertions.assertTrue(e.checkConsistency());
        }
    }

    @Test
    public void testSweepKeepsBufferDrivingInoutPort() {
        Supplier<EDIFNetlist> s = () -> {
            OptProbes.B b = new OptProbes.B("iobuf_inout");
            b.in("a");
            EDIFPort io = b.top.createPort("io", EDIFDirection.INOUT, 1);
            b.net(b.top, "io").createPortInst(io);
            b.inst(Unisim.IOBUF, "IOB", "I=a", "T=0", "IO=io");
            return b.nl;
        };
        EDIFNetlist netlist = s.get();
        EDIFOptStats stats = EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        Assertions.assertEquals(0, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        EDIFCellInst iob = netlist.getTopCell().getCellInst("IOB");
        Assertions.assertNotNull(iob);
        Assertions.assertEquals("a", iob.getPortInst("I").getNet().getName());
        Assertions.assertEquals("io", iob.getPortInst("IO").getNet().getName());
    }

    @Test
    public void testOptimizeGeneratesUniqueHierarchicalNetNames(@TempDir Path dir) {
        Supplier<EDIFNetlist> s = () -> {
            OptProbes.B b = new OptProbes.B("hier_names");
            // An existing top-level input/net has the name a routed signal would get
            b.in("clk", "a", "b", "u_x_opt_o");
            b.out("o", "q", "r");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "i0", "i1");
            b.out(sub, "y");
            // Not a LUT: the inverter cannot be pulled back into it
            b.instIn(sub, Unisim.FDRE, "X", "C=i1", "D=i0", "CE=1", "R=0", "Q=x", "@INIT=1'b0");
            b.lut(sub, "IV", "1", "y", "x");
            b.inst(sub, "u", "i0=a", "i1=b", "y=n");
            // n: one load absorbs the inversion (and moves to u/x), the other cannot
            b.lut("L", "8", "o", "n", "b");
            b.fdre("F", "n", "1", "0", "q", "0");
            b.lut("R", "2", "r", "u_x_opt_o");
            return b.nl;
        };
        EDIFNetlist netlist = s.get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        EDIFNet existing = top.getNet("u_x_opt_o");
        Assertions.assertEquals(2, existing.getPortInsts().size());
        EDIFNet moved = top.getCellInst("L").getPortInst("I0").getNet();
        Assertions.assertNotSame(existing, moved);
        Assertions.assertSame(top.getCellInst("u"), moved.getPortInsts().stream()
                .filter(pi -> pi.isOutput()).findFirst().get().getCellInst());
        assertOptimizedEquivalent(s, new EDIFOptOptions());
        // The result survives an EDIF round trip
        Path f = dir.resolve("hier_names.edf");
        netlist.exportEDIF(f);
        EDIFNetlist back = EDIFTools.readEdifFile(f);
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertEquals(0, eq.check(s.get(), back, 4), eq.getExamples().toString());
    }

    /**
     * Top-level inverter A drives a flip-flop and inverter B inside a child instance, B drives
     * output o (o = a).
     */
    private static EDIFNetlist inverterIntoChild() {
        OptProbes.B b = new OptProbes.B("inv_into_child");
        b.in("clk", "a");
        b.out("o", "q");
        EDIFCell sub = b.cell("sub");
        b.in(sub, "x");
        b.out(sub, "z");
        b.lut(sub, "B", "1", "z", "x");
        b.lut("A", "1", "n", "a");
        b.fdre("F", "n", "1", "0", "q", "0");
        b.inst(sub, "u", "x=n", "z=o");
        return b.nl;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testOptimizePartialInverterAbsorptionIntoChild(boolean sweep) {
        // B absorbs A's inversion and becomes a buffer of 'a', which must be routed into 'u'
        EDIFOptOptions opts = new EDIFOptOptions().setSweep(sweep);
        EDIFNetlist netlist = inverterIntoChild();
        EDIFTools.optimizeNetlist(netlist, opts);
        EDIFCell top = netlist.getTopCell();
        Assertions.assertNotNull(top.getCellInst("u"));
        Assertions.assertNotNull(top.getInternalNet("o"));
        assertOptimizedEquivalent(TestEDIFTools::inverterIntoChild, opts);
    }

    /**
     * Two inverters in series; each partially absorbs into many LUT loads (the same loads move
     * twice).
     */
    private static EDIFNetlist inverterPairManyLoads(String first, String second, int loads) {
        OptProbes.B b = new OptProbes.B("inv_pair_loads");
        b.in("clk", "a", "b");
        b.out("q0", "q1");
        b.lut(first, "1", "n", "a");
        b.lut(second, "1", "m", "n");
        // Loads that cannot absorb an inversion keep both inverters
        b.fdre("F0", "n", "1", "0", "q0", "0");
        b.fdre("F1", "m", "1", "0", "q1", "0");
        for (int i = 0; i < loads; i++) {
            b.lut("L" + i, "8", "d" + i, "m", "b");
        }
        return b.nl;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testInverterAbsorptionIsLinearInMovedPins(boolean swapNames) {
        int loads = 250000;
        EDIFNetlist netlist = inverterPairManyLoads(swapNames ? "B" : "A", swapNames ? "A" : "B",
                loads);
        EDIFOptStats stats = EDIFTools.propagateConstants(netlist);
        // Every load reads 'a' through an absorbed double inversion
        EDIFCellInst l0 = netlist.getTopCell().getCellInst("L0");
        Assertions.assertEquals("a", l0.getPortInst("I0").getNet().getName());
        Assertions.assertEquals("4'h8", l0.getProperty("INIT").getValue());
        // A quadratic update of the moved pins takes several seconds here
        Assertions.assertTrue(stats.getTimes().get("Constant propagation") < 2_000_000_000L,
                stats.getTimes().toString());
    }

    @Test
    public void testSweepIsLinearInFanout() {
        // One surviving flip-flop drives many dead LUTs
        OptProbes.B b = new OptProbes.B("sweep_fanout");
        b.in("clk", "a", "b");
        b.out("q");
        b.fdre("F", "a", "1", "0", "n", "0");
        b.fdre("G", "n", "1", "0", "q", "0");
        int loads = 100000;
        for (int i = 0; i < loads; i++) {
            b.lut("L" + i, "6", "d" + i, "n", "b");
        }
        EDIFOptStats stats = EDIFTools.sweepDeadLogic(b.nl);
        Assertions.assertEquals(loads, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertNotNull(b.top.getCellInst("F"));
        // A quadratic sweep takes minutes here
        Assertions.assertTrue(stats.getTimes().get("Sweep") < 5_000_000_000L,
                stats.getTimes().toString());
    }

    @Test
    public void testRandomSimEquivalenceChecksCutCells() {
        // A surviving flip-flop loses a connected input
        Supplier<EDIFNetlist> ff = () -> {
            OptProbes.B b = new OptProbes.B("ff_check");
            b.in("clk", "a", "r");
            b.out("q");
            b.fdre("F", "a", "1", "r", "q", "0");
            return b.nl;
        };
        EDIFNetlist noReset = ff.get();
        EDIFCellInst f = noReset.getTopCell().getCellInst("F");
        EDIFPortInst r = f.getPortInst("R");
        r.getNet().removePortInst(r);
        f.removePortInst(r);
        EDIFRandomSimEquivalence eq = new EDIFRandomSimEquivalence();
        Assertions.assertTrue(eq.check(ff.get(), noReset, 2) > 0);
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(ff.get(), ff.get(), 2));
        // A surviving flip-flop changes its initial value
        EDIFNetlist init1 = ff.get();
        init1.getTopCell().getCellInst("F").addProperty("INIT", "1'b1");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(ff.get(), init1, 2) > 0);
        // A surviving flip-flop changes its type
        OptProbes.B fdse = new OptProbes.B("ff_check");
        fdse.in("clk", "a", "r");
        fdse.out("q");
        fdse.inst(Unisim.FDSE, "F", "C=clk", "D=a", "CE=1", "S=r", "Q=q", "@INIT=1'b0");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(ff.get(), fdse.nl, 2) > 0);
        // A surviving memory reads another initialization file (paths are case-sensitive)
        Supplier<EDIFNetlist> ram = () -> {
            OptProbes.B b = new OptProbes.B("ram_check");
            b.in("clk");
            b.inst(Unisim.RAMB18E5, "M", "CLKARDCLK=clk", "@INIT_FILE=/tmp/a.mem");
            return b.nl;
        };
        EDIFNetlist otherFile = ram.get();
        otherFile.getTopCell().getCellInst("M").addProperty("INIT_FILE", "/tmp/A.mem");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(ram.get(), otherFile, 2) > 0);
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(ram.get(), ram.get(), 2));
        // File names that look like booleans or numbers are still compared exactly
        for (String[] names : new String[][] {{"true", "TRUE"}, {"8'h1", "8'h01"}}) {
            EDIFNetlist x = ram.get();
            EDIFNetlist y = ram.get();
            x.getTopCell().getCellInst("M").addProperty("INIT_FILE", names[0]);
            y.getTopCell().getCellInst("M").addProperty("INIT_FILE", names[1]);
            Assertions.assertTrue(new EDIFRandomSimEquivalence().check(x, y, 2) > 0, names[0]);
        }
        // Declared numeric properties are compared by value
        EDIFNetlist hex = ram.get();
        hex.getTopCell().getCellInst("M").addProperty("SRVAL_A", "18'h00001");
        EDIFNetlist bin = ram.get();
        bin.getTopCell().getCellInst("M").addProperty("SRVAL_A", "18'b1");
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(hex, bin, 2));
        EDIFNetlist other = ram.get();
        other.getTopCell().getCellInst("M").addProperty("SRVAL_A", "18'h00002");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(hex, other, 2) > 0);
        // A user-defined black box named like a primitive does not get its property types
        Supplier<EDIFNetlist> userFdre = () -> {
            OptProbes.B b = new OptProbes.B("user_fdre");
            b.in("a");
            b.out("q");
            EDIFCell bb = b.cell("FDRE");
            bb.createPort("D", EDIFDirection.INPUT, 1);
            bb.createPort("Q", EDIFDirection.OUTPUT, 1);
            b.inst(bb, "F", "D=a", "Q=q", "@INIT=0");
            return b.nl;
        };
        EDIFNetlist userInit = userFdre.get();
        userInit.getTopCell().getCellInst("F").addProperty("INIT", "1'b0");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(userFdre.get(), userInit, 2) > 0);
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(userFdre.get(),
                userFdre.get(), 2));
        // A user-defined cell does not match the primitive of the same name
        EDIFNetlist primitive = ff.get();
        EDIFNetlist user = ff.get();
        EDIFCell bb = new EDIFCell(user.getWorkLibrary(), "FDRE");
        for (EDIFPort port : primitive.getHDIPrimitive(Unisim.FDRE).getPorts()) {
            bb.createPort(port.getName(), port.getDirection(), 1);
        }
        user.getTopCell().getCellInst("F").setCellType(bb);
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(primitive, user, 2) > 0);
        // An unknown part only disables declared property types
        Supplier<EDIFNetlist> unknownPart = () -> {
            EDIFNetlist n = ram.get();
            n.getDesign().addProperty(EDIFTools.EDIF_PART_PROP, "xcnosuchpart-ffvb1234-1-e");
            return n;
        };
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(unknownPart.get(),
                unknownPart.get(), 2));
        EDIFNetlist unknownOther = unknownPart.get();
        unknownOther.getTopCell().getCellInst("M").addProperty("INIT_FILE", "/tmp/A.mem");
        Assertions.assertTrue(new EDIFRandomSimEquivalence().check(unknownPart.get(),
                unknownOther, 2) > 0);
    }

    @Test
    public void testSweepDeadLogicOnly() {
        EDIFNetlist netlist = OptProbes.getProbes().get("sweep_unloaded").get();
        EDIFOptStats stats = EDIFTools.sweepDeadLogic(netlist);
        Assertions.assertEquals(4, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertEquals(1, netlist.getTopCell().getCellInsts().stream()
                .filter(i -> !i.getCellType().isStaticSource()).count());
        EDIFNetlist loop = OptProbes.getProbes().get("sweep_loop").get();
        Assertions.assertEquals(2, EDIFTools.sweepDeadLogic(loop)
                .getRemoved(EDIFOptStats.Phase.SWEEP));
    }

    @Test
    public void testPropagateConstantsDoesNotSweep() {
        EDIFNetlist netlist = OptProbes.getProbes().get("sweep_unloaded").get();
        EDIFOptStats stats = EDIFTools.propagateConstants(netlist);
        Assertions.assertEquals(0, stats.getRemoved(EDIFOptStats.Phase.SWEEP));
        Assertions.assertNotNull(netlist.getTopCell().getCellInst("D2"));
    }

    @Test
    public void testOptimizePreservesDontTouch() {
        EDIFNetlist netlist = OptProbes.getProbes().get("dont_touch_cell").get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        Assertions.assertEquals("LUT2", top.getCellInst("DT").getCellType().getName());
        Assertions.assertNotNull(top.getCellInst("DU"));
        Assertions.assertNotNull(top.getCellInst("DK"));
    }

    @Test
    public void testOptimizeUniquifiesOnlyWhenNeeded() {
        // u0 gets a constant input and u1 does not: u0 needs its own copy of 'sub'
        EDIFNetlist shared = OptProbes.getProbes().get("hier_const_in_shared").get();
        EDIFOptStats stats = EDIFTools.optimizeNetlist(shared, new EDIFOptOptions());
        EDIFCell top = shared.getTopCell();
        EDIFCell u0 = top.getCellInst("u0").getCellType();
        EDIFCell u1 = top.getCellInst("u1").getCellType();
        Assertions.assertNotSame(u0, u1);
        Assertions.assertEquals("sub", u1.getName());
        Assertions.assertEquals("sub_HD1", u0.getName());
        Assertions.assertEquals(2, u1.getCellInsts().size());
        Assertions.assertEquals(1, stats.getCellsUniquified());
        // Both instances get the same edit: the definition stays shared
        EDIFNetlist same = OptProbes.getProbes().get("hier_const_in_same").get();
        stats = EDIFTools.optimizeNetlist(same, new EDIFOptOptions());
        Assertions.assertSame(same.getTopCell().getCellInst("u0").getCellType(),
                same.getTopCell().getCellInst("u1").getCellType());
        Assertions.assertEquals(0, stats.getCellsUniquified());
    }

    @Test
    public void testOptimizeUniquifiesNestedSharedDefinitions() {
        // top -> m0, m1 (both 'mid') -> l0 ('leafc'); only m0's leaf gets a constant input
        EDIFNetlist netlist = OptProbes.getProbes().get("hier_nested").get();
        EDIFNetlist orig = OptProbes.getProbes().get("hier_nested").get();
        EDIFTools.optimizeNetlist(netlist, new EDIFOptOptions());
        EDIFCell top = netlist.getTopCell();
        EDIFCell m1 = top.getCellInst("m1").getCellType();
        Assertions.assertEquals("mid", m1.getName());
        Assertions.assertEquals("leafc", m1.getCellInst("l0").getCellType().getName());
        Assertions.assertEquals("LUT2", m1.getCellInst("l0").getCellType().getCellInst("LL")
                .getCellType().getName());
        Assertions.assertEquals(0, new EDIFRandomSimEquivalence().check(orig, netlist, 2));
    }

    @Test
    public void testLeafIndexMatchesPhysicalNetPinMap() {
        EDIFNetlist netlist = OptProbes.getProbes().get("hier_nested").get();
        EDIFLeafIndex idx = new EDIFLeafIndex(netlist);
        Map<EDIFHierNet, List<EDIFHierPortInst>> map = netlist.getPhysicalNetPinMap();
        int leafPins = 0;
        for (List<EDIFHierPortInst> pins : map.values()) {
            leafPins += pins.size();
        }
        Assertions.assertEquals(leafPins, idx.getNumLeafPins());
        for (Map.Entry<EDIFHierNet, List<EDIFHierPortInst>> e : map.entrySet()) {
            int net = -1;
            for (EDIFHierPortInst hpi : e.getValue()) {
                String name = hpi.getFullHierarchicalInstName() + "/" + hpi.getPortInst().getName();
                int found = -1;
                for (int p = 0; p < idx.getNumLeafPins(); p++) {
                    if (idx.getPinName(p).equals(name)) {
                        found = idx.getPinNet(p);
                    }
                }
                Assertions.assertTrue(found >= 0, name);
                if (net < 0) {
                    net = found;
                }
                Assertions.assertEquals(net, found, name);
            }
        }
    }
}
