/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

/**
 * Test that we can write a DCP file and read it back in. We currently don't have a way to check designs for equality,
 * so we just try to catch obvious issues.
 */
public class TestDesign {
    public static final String DEVICE = "xc7a12t";

    private static final String SITE = "SLICE_X20Y42";

    private Design createSampleDesign() {
        final EDIFNetlist netlist = TestEDIF.createEmptyNetlist();
        final Design design = new Design(netlist);

        final Cell myCell = design.createCell("myCell", Unisim.FDRE);

        final Site site = design.getDevice().getSite(SITE);
        design.createSiteInst(site);
        final BEL bel = site.getBEL("AFF");
        Assertions.assertNotNull(bel);
        design.placeCell(myCell, site, bel);

        return design;
    }

    @Test
    public void checkDcpRoundtrip(@TempDir Path tempDir) throws IOException {
        //Keep a reference to the device to avoid it being garbage collected during testcase execution
        @SuppressWarnings("unused")
        Device device = Device.getDevice(DEVICE);

        //Use separate files for writing/reading so we can identify leaking file handles by filename
        final Path filenameWrite = tempDir.resolve("testWrite.dcp");
        final Path filenameRead = tempDir.resolve("testRead.dcp");

        createSampleDesign().writeCheckpoint(filenameWrite);
        Files.copy(filenameWrite, filenameRead);

        Design design = Design.readCheckpoint(filenameRead);
        TestEDIF.verifyNetlist(design.getNetlist(), "myCell");

        final Cell cell = design.getCell("myCell");
        Assertions.assertNotNull(cell);
        Assertions.assertEquals(SITE, cell.getSiteInst().getSite().getName());

    }

    @Test
    public void checkDcpRoundtripModuleInstAnchor(@TempDir Path tempDir) throws IOException {
        //Keep a reference to the device to avoid it being garbage collected during testcase execution
        Device device = Device.getDevice(DEVICE);

        //Use separate files for writing/reading so we can identify leaking file handles by filename
        final Path filenameWrite = tempDir.resolve("testWrite.dcp");
        final Path filenameRead = tempDir.resolve("testRead.dcp");

        Module module = new Module(createSampleDesign());

        Design design = new Design("top", device.getName());
        ModuleInst mi = design.createModuleInst("inst", module);
        mi.placeOnOriginalAnchor();
        String oldAnchor = mi.getAnchor().toString();

        design.writeCheckpoint(filenameWrite);
        Files.copy(filenameWrite, filenameRead);

        design = Design.readCheckpoint(filenameRead);
        mi = design.getModuleInst("inst");
        Assertions.assertEquals(oldAnchor, mi.getAnchor().toString());
    }

    @Test
    public void checkDcpRoundtripParallel(@TempDir Path tempDir) {
        final Path filenameRead = RapidWrightDCP.getPath("optical-flow.dcp");
        final Path filenameWrite = tempDir.resolve("testWrite.dcp");

        Design before = Design.readCheckpoint(filenameRead);

        try {
            ParallelismTools.setParallel(true);
            before.writeCheckpoint(filenameWrite);
        } finally {
            ParallelismTools.setParallel(false);
        }

        Design after = Design.readCheckpoint(filenameWrite);

        Assertions.assertEquals(before.getNetlist(), after.getNetlist());
        Assertions.assertEquals(new HashSet<>(before.getSiteInsts()),
                                new HashSet<>(after.getSiteInsts()));
        Assertions.assertEquals(new HashSet<>(before.getNets()),
                                new HashSet<>(after.getNets()));
    }

    @Test
    public void testDcpSecondarySourcePins() {
        final String inputPath = RapidWrightDCP.getString("ramb18.dcp");
        Design design = Design.readCheckpoint(inputPath);

        for (int i = 0; i < 18; i++) {
            Net net = design.getNet("r/U0/inst_blk_mem_gen/gnbram.gnativebmg.native_blk_mem_gen/valid.cstr/ramloop[0].ram.r/prim_noinit.ram/douta[" + i + "]");
            SitePinInst spi = net.getSource();
            if (i == 8 || i == 17)
                Assertions.assertEquals("DOPADOP" + i/9, spi.getName());
            else
                Assertions.assertEquals("DOADO" + (i - i/9), spi.getName());
        }
    }

    @Test
    public void testRouteThruPIPsSitePinInsts() {
        final String inputPath = RapidWrightDCP.getString("routethru_pip.dcp");
        Design design = Design.readCheckpoint(inputPath);

        Net net = design.getNet("CO3_OBUF");
        for (SitePinInst spi : net.getSinkPins()) {
            Assertions.assertTrue(spi.getSite().isInputPin(spi.getName()));
        }
    }

    private EDIFNetlist generateEDIF(String edifName, long numLibraries, long cellsPerLibrary, long netsPerCell) {
        EDIFNetlist netlist = EDIFTools.createNewNetlist(edifName);
        EDIFTools.ensureCorrectPartInEDIF(netlist, Device.AWS_F1);
        for (int libraryIdx = 0; libraryIdx < numLibraries; libraryIdx++) {
            EDIFLibrary lib = new EDIFLibrary("library_" + libraryIdx);
            for (int cellIdx = 0; cellIdx < cellsPerLibrary; cellIdx++) {
                EDIFCell cell = new EDIFCell(lib, "cell_" + cellIdx);
                for (int netIdx = 0; netIdx < netsPerCell; netIdx++) {
                    new EDIFNet("net_" + netIdx, cell);
                }
            }
            netlist.addLibrary(lib);
        }
        return netlist;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    public void testDcpEdifBiggerThan4GB(boolean parallel, @TempDir Path tempDir) {
        long maxMemoryNeeded = 1024L*1024L*1024L*14L;
        Assumptions.assumeTrue(Runtime.getRuntime().maxMemory() >= maxMemoryNeeded);

        try {
            ParallelismTools.setParallel(parallel);

            final String edifName = "testDcpEdifBiggerThan4GB" + ((parallel) ? "Parallel" : "");
            final long numLibraries = 100;
            final long cellsPerLibrary = 1000;
            final long netsPerCell = 1000;
            final Path outputPath = tempDir.resolve(edifName + ".dcp");

            CodePerfTracker t = new CodePerfTracker(edifName, true);
            t.useGCToTrackMemory(true);
            t.start(numLibraries + " x " + cellsPerLibrary + " x " + netsPerCell);
            EDIFNetlist netlist = generateEDIF(edifName, numLibraries, cellsPerLibrary, netsPerCell);
            t.stop();

            Design design = new Design(netlist);
            design.writeCheckpoint(outputPath, t);
        } finally {
            ParallelismTools.setParallel(false);
        }
    }

    @Test
    public void testBug349(@TempDir Path tempDir) throws IOException {
        // This test won't run in CI as Vivado is not available
        Assumptions.assumeTrue(FileTools.isVivadoOnPath());

        final String inputPath = RapidWrightDCP.getString("bug349.dcp");
        Design design = Design.readCheckpoint(inputPath);

        final Path filenameWrite = tempDir.resolve("bug349_roundtrip.dcp");
        final Path tclScript = tempDir.resolve("bug349_roundtrip.tcl");
        design.writeCheckpoint(filenameWrite);

        final Job job = new LocalJob();
        job.setCommand(FileTools.getVivadoPath() + " -mode batch -source " + tclScript);

        job.setRunDir(tempDir.toString());

        Files.write(tclScript, Arrays.asList(
                "open_checkpoint " + filenameWrite.toAbsolutePath()
        ));

        JobQueue queue = new JobQueue();
        queue.addJob(job);
        Assertions.assertTrue(queue.runAllToCompletion());
    }

    @Test
    public void testCopyCell() {
        Design d = new Design("test", "xcvc1902-vsvd1760-2MP-e-S");
        Cell orig = d.createAndPlaceCell("orig", Unisim.DSP_PREADD58, "DSP_X0Y0/DSP_PREADD");
        Design d2 = new Design("test2", d.getPartName());
        Assertions.assertNotNull(d2.copyCell(orig, "copy"));
    }

    /**
     * Input description: (synthesized out of context)
     * module top(input a, b,
     *            input \this.is.an\.escaped\.net\.identifier ,
     *            output o);
     *
     * wire \this.is.another\\.escaped\$net\+&!identifier ;
     *
     * (* DONT_TOUCH="true" *)
     * LUT2 \this.is.an\.escaped\.cell\.identifier (.O(\this.is.another\\.escaped\$net\+&!identifier ), .I0(\this.is.an\.escaped\.net\.identifier ), .I1(a));
     *
     * (* DONT_TOUCH="true" *)
     * LUT2 \this.is.another\\.escaped\$cell\+&!identifier (.O(o), .I0(\this.is.another\\.escaped\$net\+&!identifier ), .I1(b));
     *
     * endmodule
     */
    @ParameterizedTest
    @CsvSource({
            "design_with_backslash_2022.2.dcp",
            "design_with_backslash_2022.1.dcp",
            "design_with_backslash_2021.2.dcp",
    })
    public void testDesignWithBackslash(String dcp, @TempDir Path tempDir) {
        Design design = RapidWrightDCP.loadDCP(dcp);
        testDesignWithBackslashHelper(design);

        if (dcp.endsWith("_2021.2.dcp") || dcp.endsWith("_2022.1.dcp")) {
            final Path rapidWrightDcp = tempDir.resolve("rapidwright.dcp");
            design.writeCheckpoint(rapidWrightDcp);
            design = Design.readCheckpoint(rapidWrightDcp);
            testDesignWithBackslashHelper(design);
        }
    }

    private static void testDesignWithBackslashHelper(Design design) {
        for (String cellName : Arrays.asList("this.is.an\\.escaped\\.cell\\.identifier",
                "this.is.another\\\\.escaped\\$cell\\+&!identifier")) {
            EDIFHierCellInst ehci = design.getNetlist().getHierCellInstFromName(cellName);
            Assertions.assertNotNull(ehci);
            Cell c = design.getCell(cellName);
            Assertions.assertNotNull(c);
            Assertions.assertEquals(ehci.getFullHierarchicalInstName(), c.getName());
        }
        Assertions.assertEquals(design.getCells().size(), 2);

        for (String netName : Arrays.asList("this.is.an\\.escaped\\.net\\.identifier",
                "this.is.another\\\\.escaped\\$net\\+&!identifier")) {
            EDIFHierNet ehn = design.getNetlist().getHierNetFromName(netName);
            Assertions.assertNotNull(ehn);
            Net n = design.getNet(netName);
            Assertions.assertNotNull(n);
            Assertions.assertEquals(ehn.getHierarchicalNetName(), n.getName());
        }
        final int extraNets = 5; // {a, b, o, GLOBAL_USEDNET, GLOBAL_LOGIC0}
        Assertions.assertEquals(design.getNets().size(), 2 + extraNets);
    }
    
    @Test
    public void testFindDualOutputSitePins() {
        Design d = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");

        String[] testNets = new String[] {
            "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Data_Flow_I/Operand_Select_I/Gen_Bit[14].MUXF7_I1/Using_FPGA.Native_0[0]",
            "base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Data_Flow_I/exception_registers_I1/Using_FPGA_LUT6.Gen_Ret_Addr[20].MUXCY_XOR_I/LOCKSTEP_Out_reg[3027][0]",
            "u_ila_0/inst/ila_core_inst/u_ila_regs/slaveRegDo_mux_2[15]_i_1_n_0"
        };

        for (int i = 0; i < testNets.length; i++) {
            Net net = d.getNet(testNets[i]);
            Assertions.assertNotNull(net.getSource());
            Assertions.assertNotNull(net.getAlternateSource());
        }
    }

    @Test
    public void testMovePinsToNewNetDeleteOldNet() {
        Design d = new Design("testMovePinsToNewNetDeleteOldNet", "xcvu3p");
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));
        Unisim unisim = Unisim.CARRY8;
        d.createAndPlaceCell("carry", unisim, si.getSiteName() + "/" + unisim);

        Net oldNet = d.createNet("oldNet");
        oldNet.createPin("A1", si);
        oldNet.createPin("HQ", si);
        Assertions.assertTrue(si.routeIntraSiteNet(oldNet, si.getBELPin("A1", "A1"),
                si.getBELPin(unisim.toString(), "DI0")));
        Assertions.assertEquals("[IN SLICE_X32Y73.A1, OUT SLICE_X32Y73.HQ]", oldNet.getPins().toString());
        Assertions.assertEquals("[A1, HQ, A5LUT_O5]", si.getSiteWiresFromNet(oldNet).toString());

        Net newNet = d.createNet("newNet");
        SitePinInst h6 = newNet.createPin("H6", si);
        newNet.addPIP(h6.getConnectedNode().getAllUphillPIPs().get(0));
        Assertions.assertEquals("[IN SLICE_X32Y73.H6]", newNet.getPins().toString());
        Assertions.assertEquals("[H6]", si.getSiteWiresFromNet(newNet).toString());
        Assertions.assertEquals("[INT_X21Y73/INT.VCC_WIRE->>IMUX_E47]", newNet.getPIPs().toString());

        d.movePinsToNewNetDeleteOldNet(oldNet, newNet, true);

        Assertions.assertNull(d.getNet(oldNet.getName()));
        Assertions.assertSame(newNet, d.getNet(newNet.getName()));
        Assertions.assertEquals("[IN SLICE_X32Y73.H6, IN SLICE_X32Y73.A1, OUT SLICE_X32Y73.HQ]", newNet.getPins().toString());
        Assertions.assertEquals("[H6, A1, HQ, A5LUT_O5]", si.getSiteWiresFromNet(newNet).toString());
        Assertions.assertEquals("[INT_X21Y73/INT.VCC_WIRE->>IMUX_E47]", newNet.getPIPs().toString());
    }

    @Test
    public void testMovePinsToNewNetDeleteOldNetIntraSiteOnly() {
        Design d = new Design("testMovePinsToNewNetDeleteOldNetIntraSiteOnly", "xcvu3p");
        SiteInst si = d.createSiteInst(d.getDevice().getSite("SLICE_X32Y73"));

        Net oldNet = d.createNet("oldNet");
        // Note that oldNet has no site pins or inter-site routing
        Assertions.assertTrue(si.routeIntraSiteNet(oldNet, si.getBELPin("B6LUT", "O6"),
                si.getBELPin("BFF", "D")));
        Assertions.assertEquals("[B_O, FFMUXB1_OUT1]", si.getSiteWiresFromNet(oldNet).toString());

        Net newNet = d.createNet("newNet");
        SitePinInst h6 = newNet.createPin("H6", si);
        newNet.addPIP(h6.getConnectedNode().getAllUphillPIPs().get(0));
        Assertions.assertEquals("[IN SLICE_X32Y73.H6]", newNet.getPins().toString());
        Assertions.assertEquals("[H6]", si.getSiteWiresFromNet(newNet).toString());
        Assertions.assertEquals("[INT_X21Y73/INT.VCC_WIRE->>IMUX_E47]", newNet.getPIPs().toString());

        d.movePinsToNewNetDeleteOldNet(oldNet, newNet, false);

        Assertions.assertNull(d.getNet(oldNet.getName()));
        Assertions.assertSame(newNet, d.getNet(newNet.getName()));
        Assertions.assertEquals("[IN SLICE_X32Y73.H6]", newNet.getPins().toString());
        Assertions.assertEquals("[H6, B_O, FFMUXB1_OUT1]", si.getSiteWiresFromNet(newNet).toString());
        Assertions.assertTrue(newNet.getPIPs().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testCreateModuleInstCopiesStaticSource(boolean unrouteStaticNets) {
        Design design = new Design("testCreateModuleInstCopiesStaticSource", "xcku035");

        Design microblaze = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        final String siteName = "SLICE_X60Y116";
        Assertions.assertNotNull(microblaze.getSiteInstFromSiteName(siteName));

        Module module = new Module(microblaze, unrouteStaticNets);
        ModuleInst mi = design.createModuleInst("inst", module);
        SiteInst si = design.getSiteInstFromSiteName(siteName);
        Assertions.assertNull(si);

        mi.placeOnOriginalAnchor();
        si = design.getSiteInstFromSiteName(siteName);
        if (!unrouteStaticNets) {
            Assertions.assertNotNull(si);
            Assertions.assertEquals(mi.getName() + "/STATIC_SOURCE_" + si.getSiteName(), si.getName());
        } else {
            Assertions.assertNull(si);
        }
    }

    @ParameterizedTest
    @CsvSource({ 
        "xcvu3p-ffvc1517-1-i,N28", 
        "xcku040-ffva1156-2-e,AE10",
        })
    public void testPlaceIOB(String partName, String pkgPin) {
        Design design = new Design("testPlaceIOB", partName);
        EDIFCell top = design.getNetlist().getTopCell();
        EDIFCell ibuf = Design.getMacroPrimitives(design.getDevice().getSeries()).getCell("IBUF");
        EDIFCellInst ibufInst = ibuf.createCellInst("iobInst", top);
        EDIFNet inNet = top.createNet("inNet");
        EDIFNet outNet = top.createNet("outNet");
        EDIFPort input = top.createPort("in0", EDIFDirection.INPUT, 1);
        inNet.createPortInst(input);
        inNet.createPortInst("I", ibufInst);
        outNet.createPortInst("O", ibufInst);
        Cell iob = design.placeIOB(ibufInst, pkgPin, null);
        SiteInst siteInst = iob.getSiteInst();
        SitePIP sitePIP = siteInst.getUsedSitePIP("INPUTMUX");
        Assertions.assertEquals("IN1", sitePIP.getInputPinName());
        String netName = ibufInst.getName() + "/OUT";
        Net net = design.getNet(netName);
        Assertions.assertEquals(net.getName(), netName);
        Net dout = design.getDevice().getSeries().equals(Series.UltraScalePlus) ? net
                : design.getNet(ibufInst.getName() + "/O");
        Assertions.assertEquals(siteInst.getNetFromSiteWire("DOUT"), dout);
    }

    private void ensureDesignInSLR(Design d, int expectedSLR) {
        for (SiteInst si : d.getSiteInsts()) {
            Assertions.assertEquals(expectedSLR, si.getTile().getSLR().getId());
        }
        for (Net n : d.getNets()) {
            for (PIP p : n.getPIPs()) {
                Assertions.assertEquals(expectedSLR, p.getTile().getSLR().getId());
            }
        }
    }

    /**
     * Tests relocating and retargeting a Picoblaze design from a vu3p to each of
     * the three SLRs in a vu9p since all of the SLRs between the devices are
     * relocation compatible.
     * 
     * @param tempDir Temp directory to write out results.
     */
    @Test
    public void testRetargetPart(@TempDir Path tempDir) {
        String targetPartName = "xcvu9p-flgb2104-2-i";
        Part targetPart = PartNameTools.getPart(targetPartName);
        Device targetDevice = Device.getDevice(targetPart);
        for (int slr = 0; slr < targetDevice.getNumOfSLRs(); slr++) {
            Design d = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
            Part origPart = d.getPart();
            assert (d.getDevice().getName().equals("xcvu3p"));
            int tileDX = 0;
            int tileDY = slr * targetDevice.getMasterSLR().getNumOfClockRegionRows()
                    * targetPart.getSeries().getCLEHeight();
            Assertions.assertTrue(d.retargetPart(targetPart, tileDX, tileDY));
            Path output = tempDir.resolve("retarget_" + slr + ".dcp");

            Assertions.assertEquals(targetPartName, d.getPartName());
            ensureDesignInSLR(d, slr);

            d.writeCheckpoint(output);

            Design d2 = Design.readCheckpoint(output);

            Assertions.assertEquals(targetPartName, d2.getPartName());
            ensureDesignInSLR(d2, slr);

            // Try reversing the process to see if we get the original
            d2.retargetPart(origPart, tileDX * -1, tileDY * -1);
            assert (d2.getDevice().getName().equals(origPart.getDevice()));
            ensureDesignInSLR(d2, 0);

            // Just do a single sanity check that it opens ok in Vivado
            if (slr == 1 && FileTools.isVivadoOnPath()) {
                VivadoToolsHelper.assertFullyRouted(output);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "picoblaze_ooc_X10Y235.dcp",            // Pre 2022.1 DCP
            "picoblaze_ooc_X10Y235_2022_1.dcp",     // 2022.1 DCP
    })
    public void testNetOrder(String dcpFileName) {
        Design design1 = RapidWrightDCP.loadDCP(dcpFileName);
        Object[] nets1 = design1.getNets().toArray();

        for (int i = 0; i < 10; i++) {
            Design design2 = RapidWrightDCP.loadDCP(dcpFileName);
            Object[] nets2 = design2.getNets().toArray();
            Assertions.assertTrue(Arrays.equals(nets1, nets2));
        }
    }
}
