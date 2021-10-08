package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.xilinx.rapidwright.checker.CheckOpenFiles;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.CellPlacement;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PhysNet;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.PinMapping;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch;
import com.xilinx.rapidwright.interchange.PhysicalNetlist.PhysNetlist.RouteBranch.RouteSegment;
import com.xilinx.rapidwright.interchange.PhysNetlistReader;
import com.xilinx.rapidwright.interchange.PhysNetlistWriter;
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
    private void testAllRouteSegmentsEndInBELInputPins(RouteBranch.Reader routeBranch, Enumerator<String> strings) {
        RouteSegment.Reader segment = routeBranch.getRouteSegment();
        StructList.Reader<RouteBranch.Reader> branches = routeBranch.getBranches();
        int branchesCount = branches.size();
        if (branchesCount == 0) {
            Assertions.assertEquals(segment.which(), RouteSegment.Which.BEL_PIN);
        } else {
            for (PhysNetlist.RouteBranch.Reader childBranch : branches) {
                testAllRouteSegmentsEndInBELInputPins(childBranch, strings);
            }
        }
    }

    @Test
    @CheckOpenFiles
    public void testAllRouteSegmentsEndInBELInputPins(@TempDir Path tempDir) throws IOException {
        final String inputPath = "RapidWrightDCP/routethru_luts.dcp";
        Design input = Design.readCheckpoint(inputPath);

        final Path interchangePath = tempDir.resolve("routethru_luts.phys");
        PhysNetlistWriter.writePhysNetlist(input, interchangePath.toString());

        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                        ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(interchangePath.toString(), rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        Enumerator<String> allStrings = PhysNetlistReader.readAllStrings(physNetlist);

        for(PhysNet.Reader physNet : physNetlist.getPhysNets()) {
            for(PhysNetlist.RouteBranch.Reader routeBranch : physNet.getStubs()) {
                testAllRouteSegmentsEndInBELInputPins(routeBranch, allStrings);
            }
        }
    }

    @Test
    @CheckOpenFiles
    public void testNoLutRoutethruCells(@TempDir Path tempDir) throws IOException {
        final String inputPath = "RapidWrightDCP/routethru_luts.dcp";
        Design input = Design.readCheckpoint(inputPath);

        final Path interchangePath = tempDir.resolve("routethru_luts.phys");
        PhysNetlistWriter.writePhysNetlist(input, interchangePath.toString());

        ReaderOptions rdOptions =
                new ReaderOptions(ReaderOptions.DEFAULT_READER_OPTIONS.traversalLimitInWords * 64,
                        ReaderOptions.DEFAULT_READER_OPTIONS.nestingLimit * 128);
        MessageReader readMsg = Interchange.readInterchangeFile(interchangePath.toString(), rdOptions);

        PhysNetlist.Reader physNetlist = readMsg.getRoot(PhysNetlist.factory);

        Enumerator<String> allStrings = PhysNetlistReader.readAllStrings(physNetlist);

        for (CellPlacement.Reader placement : physNetlist.getPlacements()) {
            String bel = allStrings.get(placement.getBel());
            if (!bel.endsWith("LUT")) {
                continue;
            }
            for (PinMapping.Reader pinMapping : placement.getPinMap()) {
                Assertions.assertEquals(pinMapping.getBelPin(), pinMapping.getCellPin());
            }
        }
    }

}
