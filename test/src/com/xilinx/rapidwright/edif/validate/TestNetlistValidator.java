/*
 *
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
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
package com.xilinx.rapidwright.edif.validate;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFName;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.support.RapidWrightDCP;

/**
 * Unit tests for {@link NetlistValidator}. Most tests build a minimal,
 * self-contained netlist (no device/Unisim data needed), then inject exactly one
 * fault using back-door setters and assert that the corresponding
 * {@link IssueCode} is reported.
 */
class TestNetlistValidator {

    /**
     * Builds a minimal but fully consistent netlist:
     *
     * <pre>
     *   hdi_primitives: BUF(I:in, O:out)   [leaf primitive]
     *   work: top(in -&gt; bufInst.I, bufInst.O -&gt; out)
     * </pre>
     */
    private static EDIFNetlist buildMinimalNetlist() {
        EDIFNetlist netlist = new EDIFNetlist("top");
        EDIFLibrary primLib = netlist.addLibrary(
                new EDIFLibrary(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME));
        EDIFLibrary workLib = netlist.addLibrary(
                new EDIFLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME));

        // Leaf primitive cell BUF
        EDIFCell buf = new EDIFCell(primLib, "BUF");
        buf.createPort("I", EDIFDirection.INPUT, 1);
        buf.createPort("O", EDIFDirection.OUTPUT, 1);

        // Top cell
        EDIFCell top = new EDIFCell(workLib, "top");
        EDIFDesign design = new EDIFDesign("top");
        design.setTopCell(top);
        netlist.setDesign(design);
        // Avoid the (harmless) MISSING_PART_PROPERTY warning in clean tests.
        design.addProperty("PART", "xcku040-ffva1156-2-e");

        EDIFPort inPort = top.createPort("in", EDIFDirection.INPUT, 1);
        EDIFPort outPort = top.createPort("out", EDIFDirection.OUTPUT, 1);

        EDIFCellInst bufInst = new EDIFCellInst("bufInst", buf, top);

        EDIFNet nIn = top.createNet("n_in");
        nIn.createPortInst(inPort);
        nIn.createPortInst("I", bufInst);

        EDIFNet nOut = top.createNet("n_out");
        nOut.createPortInst("O", bufInst);
        nOut.createPortInst(outPort);

        return netlist;
    }

    private static EDIFCell getTop(EDIFNetlist n) {
        return n.getDesign().getTopCell();
    }

    private static ValidationReport validate(EDIFNetlist n) {
        return new NetlistValidator().validate(n);
    }

    // ---------------------------------------------------------------- //
    //  Positive controls
    // ---------------------------------------------------------------- //

    @Test
    void testCleanMinimalNetlistPasses() {
        EDIFNetlist n = buildMinimalNetlist();
        ValidationReport report = validate(n);
        Assertions.assertTrue(report.isValid(),
                "Clean netlist should have no errors, got: " + report.getIssues());
        Assertions.assertEquals(0, report.getErrorCount());
    }

    @Test
    void testCleanNetlistRoundTripsThroughEDIF(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        EDIFNetlist n = buildMinimalNetlist();
        java.nio.file.Path edf = dir.resolve("min.edf");
        n.exportEDIF(edf);
        EDIFNetlist reloaded = EDIFTools.readEdifFile(edf);
        ValidationReport report = validate(reloaded);
        Assertions.assertTrue(report.isValid(),
                "Round-tripped netlist should be valid, got: " + report.getIssues());
    }

    @Test
    void testRealDcpNetlistHasNoStructuralErrors() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        ValidationReport report = validate(design.getNetlist());
        // A well-formed Vivado netlist must not contain structural errors.
        Assertions.assertEquals(0, report.getErrorCount(),
                "Real DCP netlist reported errors: " + report.getIssues());
    }

    // ---------------------------------------------------------------- //
    //  Fault injection — one targeted test per representative IssueCode
    // ---------------------------------------------------------------- //

    @Test
    void testNullDesign() {
        EDIFNetlist n = buildMinimalNetlist();
        n.setDesign(null);
        Assertions.assertTrue(validate(n).getCount(IssueCode.NULL_DESIGN) > 0);
    }

    @Test
    void testNetMultiDriver() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        // Add a second driver to n_in: re-add the buf output as another source.
        EDIFNet nIn = top.getNet("n_in");
        EDIFCellInst buf = top.getCellInst("bufInst");
        // Create a second buf instance and connect its output as a second source.
        EDIFCellInst buf2 = new EDIFCellInst("bufInst2", buf.getCellType(), top);
        nIn.createPortInst("O", buf2);
        Assertions.assertTrue(validate(n).getCount(IssueCode.NET_MULTI_DRIVER) > 0);
    }

    /**
     * Builds a primitive with a normal output "O" plus a Vivado "lopt"
     * optimization output, and adds it to the netlist's primitives library.
     */
    private static EDIFCell makeOptCell(EDIFNetlist n) {
        EDIFLibrary prim = n.getLibrary(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
        EDIFCell opt = new EDIFCell(prim, "OPTCELL");
        opt.createPort("I", EDIFDirection.INPUT, 1);
        opt.createPort("O", EDIFDirection.OUTPUT, 1);
        opt.createPort("lopt", EDIFDirection.OUTPUT, 1);
        return opt;
    }

    @Test
    void testLoptSecondDriverIsInfoNotError() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        EDIFCell opt = makeOptCell(n);
        EDIFCellInst oi = new EDIFCellInst("optInst", opt, top);
        EDIFCellInst sink = new EDIFCellInst("sinkInst", opt, top);
        // Net driven by a real output "O" and the artifact "lopt" output.
        EDIFNet net = top.createNet("lopt_net");
        net.createPortInst("O", oi);
        net.createPortInst("lopt", oi);
        net.createPortInst("I", sink);
        ValidationReport r = validate(n);
        Assertions.assertEquals(0, r.getCount(IssueCode.NET_MULTI_DRIVER),
                "lopt second driver must not be a hard error");
        Assertions.assertTrue(r.getCount(IssueCode.NET_MULTI_DRIVER_LOPT) > 0,
                "lopt second driver should be reported as INFO");
    }

    @Test
    void testTwoRealDriversStillErrorEvenWithLopt() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        EDIFCell opt = makeOptCell(n);
        EDIFCellInst a = new EDIFCellInst("a", opt, top);
        EDIFCellInst b = new EDIFCellInst("b", opt, top);
        // Two genuine output drivers ("O" of a and b) plus an lopt -> still ERROR.
        EDIFNet net = top.createNet("two_real_net");
        net.createPortInst("O", a);
        net.createPortInst("O", b);
        net.createPortInst("lopt", a);
        Assertions.assertTrue(validate(n).getCount(IssueCode.NET_MULTI_DRIVER) > 0,
                "two real drivers must remain a hard error even when an lopt pin is present");
    }

    @Test
    void testNetUndriven() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        EDIFNet nIn = top.getNet("n_in");
        // Remove the top-level input source, leaving only the sink (bufInst.I).
        nIn.removePortInst(nIn.getPortInst(null, "in"));
        Assertions.assertTrue(validate(n).getCount(IssueCode.NET_UNDRIVEN) > 0);
    }

    @Test
    void testNetEmpty() {
        EDIFNetlist n = buildMinimalNetlist();
        getTop(n).createNet("danglingNet");
        Assertions.assertTrue(validate(n).getCount(IssueCode.NET_EMPTY) > 0);
    }

    @Test
    void testInstNullCellType() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCellInst buf = getTop(n).getCellInst("bufInst");
        buf.setCellTypeRaw(null);
        Assertions.assertTrue(validate(n).getCount(IssueCode.CELLINST_NULL_CELLTYPE) > 0);
    }

    @Test
    void testInstDanglingCellType() {
        EDIFNetlist n = buildMinimalNetlist();
        // Point the instance at a cell that is not in any library of this netlist.
        EDIFCell orphan = new EDIFCell((EDIFLibrary) null, "ORPHAN");
        getTop(n).getCellInst("bufInst").setCellTypeRaw(orphan);
        Assertions.assertTrue(validate(n).getCount(IssueCode.INST_DANGLING_CELLTYPE) > 0);
    }

    @Test
    void testViewrefMismatch() {
        EDIFNetlist n = buildMinimalNetlist();
        getTop(n).getCellInst("bufInst").setViewref(new EDIFName("bogusView"));
        Assertions.assertTrue(validate(n).getCount(IssueCode.VIEWREF_MISMATCH) > 0);
    }

    @Test
    void testPortInstParentNetBroken() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        EDIFNet nIn = top.getNet("n_in");
        EDIFNet nOut = top.getNet("n_out");
        // Corrupt the parent-net back-pointer on one of n_in's port insts.
        nIn.getPortInsts().get(0).setParentNet(nOut);
        Assertions.assertTrue(validate(n).getCount(IssueCode.PORTINST_PARENTNET_BROKEN) > 0);
    }

    @Test
    void testPortInstIndexMismatch() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        // Set a bogus index on a single-bit port inst.
        EDIFNet nOut = top.getNet("n_out");
        EDIFPortInst pi = nOut.getPortInst(top.getCellInst("bufInst"), "O");
        pi.setIndex(5);
        Assertions.assertTrue(validate(n).getCount(IssueCode.PORTINST_INDEX_MISMATCH) > 0);
    }

    @Test
    void testNameLegalizationCollision() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        EDIFCell bufType = top.getCellInst("bufInst").getCellType();
        // 'a_b' is already EDIF-legal; 'a.b' legalizes to 'a_b' and would shadow it.
        new EDIFCellInst("a_b", bufType, top);
        new EDIFCellInst("a.b", bufType, top);
        Assertions.assertTrue(validate(n).getCount(IssueCode.NAME_LEGALIZATION_COLLISION) > 0);
    }

    @Test
    void testRenameVsRenameDoesNotCollide() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        EDIFCell bufType = top.getCellInst("bufInst").getCellType();
        // Both legalize to 'a_b' but neither is already legal; the writer uniquifies
        // them with a _HDI_ suffix, so this must NOT be reported.
        new EDIFCellInst("a.b", bufType, top);
        new EDIFCellInst("a/b", bufType, top);
        Assertions.assertEquals(0, validate(n).getCount(IssueCode.NAME_LEGALIZATION_COLLISION));
    }

    @Test
    void testCyclicHierarchy() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFLibrary work = n.getLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME);
        EDIFCell top = getTop(n);
        // Create A and B in work that instantiate each other; make top instantiate A.
        EDIFCell a = new EDIFCell(work, "A");
        EDIFCell b = new EDIFCell(work, "B");
        new EDIFCellInst("bInst", b, a);
        new EDIFCellInst("aInst", a, b);
        new EDIFCellInst("aTop", a, top);
        Assertions.assertTrue(validate(n).getCount(IssueCode.CYCLIC_HIERARCHY) > 0);
    }

    @Test
    void testInternalPortMapStale() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        // Add an internal port map entry pointing at a net not in the cell.
        EDIFCell other = new EDIFCell(n.getLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME), "other");
        EDIFNet stray = other.createNet("stray");
        top.addInternalPortMapEntry("phantom", stray);
        Assertions.assertTrue(validate(n).getCount(IssueCode.INTERNAL_PORTMAP_STALE) > 0);
    }

    @Test
    void testPortWidthMismatch() {
        EDIFNetlist n = buildMinimalNetlist();
        // Create a bus port whose declared width disagrees with its [hi:lo] range.
        EDIFCell top = getTop(n);
        EDIFPort bus = top.createPort("d[3:0]", EDIFDirection.INPUT, 2);
        Assertions.assertNotNull(bus);
        Assertions.assertTrue(validate(n).getCount(IssueCode.PORT_WIDTH_MISMATCH) > 0);
    }

    @Test
    void testInstantiationCountDrift() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell buf = getTop(n).getCellInst("bufInst").getCellType();
        // Artificially inflate the cached count.
        buf.incrementNonHierInstantiationCount();
        buf.incrementNonHierInstantiationCount();
        Assertions.assertTrue(validate(n).getCount(IssueCode.INSTANTIATION_COUNT_DRIFT) > 0);
    }

    @Test
    void testUnreachableCell() {
        EDIFNetlist n = buildMinimalNetlist();
        // Add an unused cell in the work library (not instantiated under top).
        new EDIFCell(n.getLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME), "unused");
        Assertions.assertTrue(validate(n).getCount(IssueCode.UNREACHABLE_CELL) > 0);
    }

    @Test
    void testMissingPartProperty() {
        EDIFNetlist n = buildMinimalNetlist();
        n.getDesign().removeProperty("PART");
        Assertions.assertTrue(validate(n).getCount(IssueCode.MISSING_PART_PROPERTY) > 0);
    }

    // ---------------------------------------------------------------- //
    //  Report cap behavior
    // ---------------------------------------------------------------- //

    @Test
    void testIssueCapAndOverflowCount() {
        EDIFNetlist n = buildMinimalNetlist();
        EDIFCell top = getTop(n);
        // Inject many empty nets.
        int total = 250;
        for (int i = 0; i < total; i++) {
            top.createNet("empty_" + i);
        }
        NetlistValidator v = new NetlistValidator();
        v.maxIssuesPerCode = 50;
        ValidationReport report = v.validate(n);
        // Total count reflects all; stored issues for that code are capped.
        Assertions.assertEquals(total, report.getCount(IssueCode.NET_EMPTY));
        long storedEmpty = report.getIssues().stream()
                .filter(iss -> iss.getCode() == IssueCode.NET_EMPTY).count();
        Assertions.assertEquals(50, storedEmpty);
    }

    @Test
    void testDisabledCodeSuppressed() {
        EDIFNetlist n = buildMinimalNetlist();
        getTop(n).createNet("danglingNet");
        NetlistValidator v = new NetlistValidator();
        v.disabledCodes.add(IssueCode.NET_EMPTY);
        Assertions.assertEquals(0, v.validate(n).getCount(IssueCode.NET_EMPTY));
    }
}
