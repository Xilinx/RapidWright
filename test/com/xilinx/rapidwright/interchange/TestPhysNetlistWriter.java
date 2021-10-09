package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import com.xilinx.rapidwright.checker.CheckOpenFiles;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.CellPlacement;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.NetType;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysBelPin;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysNet;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch.RouteSegment;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.capnproto.MessageReader;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;

/**
 * Test that we can write a DCP file and read it back in. We currently don't have a way to check designs for equality,
 * so we just try to catch obvious issues.
 */
public class TestPhysNetlistWriter {
    private void testAllRouteSegmentsEndInBELInputPins(Design design, RouteBranch.Reader routeBranch, Enumerator<String> strings) {
        StructList.Reader<RouteBranch.Reader> branches = routeBranch.getBranches();
        int branchesCount = branches.size();
        if (branchesCount == 0) {
            RouteSegment.Reader segment = routeBranch.getRouteSegment();
            Assertions.assertEquals(segment.which(), RouteSegment.Which.BEL_PIN);
            PhysBelPin.Reader bpReader = segment.getBelPin();

            SiteInst si = design.getSiteInst(strings.get(bpReader.getSite()));
            Assertions.assertNotNull(si);

            BEL bel = si.getBEL(strings.get(bpReader.getBel()));
            Assertions.assertNotNull(bel);

            BELPin belPin = bel.getPin(strings.get(bpReader.getPin()));
            Assertions.assertNotNull(belPin);

            Assertions.assertTrue(belPin.isInput());
        } else {
            for (PhysNetlist.RouteBranch.Reader childBranch : branches) {
                testAllRouteSegmentsEndInBELInputPins(design, childBranch, strings);
            }
        }
    }

    @Test
    @CheckOpenFiles
    public void testAllRouteSegmentsEndInBELInputPins(@TempDir Path tempDir) throws IOException {
        final String inputPath = "RapidWrightDCP/routethru_luts.dcp";
        Design design = Design.readCheckpoint(inputPath);

        final Path interchangePath = tempDir.resolve("routethru_luts.phys");
        PhysNetlistWriter.writePhysNetlist(design, interchangePath.toString());

        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                        ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(interchangePath.toString(), rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        Enumerator<String> allStrings = PhysNetlistReader.readAllStrings(physNetlist);

        for(PhysNet.Reader physNet : physNetlist.getPhysNets()) {
            if (physNet.getType() == NetType.GND || physNet.getType() == NetType.VCC) {
                continue;
            }
            for (StructList.Reader<RouteBranch.Reader> i : Arrays.asList(physNet.getSources(), physNet.getStubs())) {
                for(PhysNetlist.RouteBranch.Reader routeBranch : i) {
                    StructList.Reader<RouteBranch.Reader> branches = routeBranch.getBranches();
                    // Necessary for nets having just one source
                    if (branches.size() > 0) {
                        testAllRouteSegmentsEndInBELInputPins(design, routeBranch, allStrings);
                    }
                }
            }
        }
    }

    @Test
    @CheckOpenFiles
    public void testNoLutRoutethruCells(@TempDir Path tempDir) throws IOException {
        final String inputPath = "RapidWrightDCP/routethru_luts.dcp";
        Design design = Design.readCheckpoint(inputPath);

        final Path interchangePath = tempDir.resolve("routethru_luts.phys");
        PhysNetlistWriter.writePhysNetlist(design, interchangePath.toString());

        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                        ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(interchangePath.toString(), rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        Enumerator<String> allStrings = PhysNetlistReader.readAllStrings(physNetlist);

        for (CellPlacement.Reader placement : physNetlist.getPlacements()) {
            SiteInst siteInst = design.getSiteInstFromSiteName(allStrings.get(placement.getSite()));
            Assertions.assertNotNull(siteInst);

            for (PinMapping.Reader pinMapping : placement.getPinMap()) {
                Cell belCell = siteInst.getCell(allStrings.get(pinMapping.getBel()));
                Assertions.assertNotNull(belCell);

                Assertions.assertFalse(belCell.isRoutethru());
            }
        }
    }

}
