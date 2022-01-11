package com.xilinx.rapidwright.design;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestParallelDCPLoad {

    @Test
    @CheckOpenFiles
    public void testParallelDCPLoad(@TempDir Path tempDir) throws IOException {
        List<String> dcpList = new ArrayList<>();
        dcpList.add(RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp"));
        dcpList.add(RapidWrightDCP.getString("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp"));
        dcpList.add(RapidWrightDCP.getString("optical-flow.dcp"));
        dcpList.add(RapidWrightDCP.getString("bnn.dcp"));
        
        List<Design> designs = dcpList.stream().parallel() // Comment out the .parallel() part to run serially
            .map(p -> (Design.readCheckpoint(p))).collect(Collectors.toList());
        
        // Write out serially for now as a sanity check
        for(Design dcp : designs) {
            dcp.writeCheckpoint(tempDir.resolve(dcp.getName() + ".dcp"));
        }
    }
}
