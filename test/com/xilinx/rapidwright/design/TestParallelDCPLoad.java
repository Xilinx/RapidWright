package com.xilinx.rapidwright.design;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestParallelDCPLoad {
    
    @Test
    @CheckOpenFiles
    public void testParallelDCPLoad() throws IOException {
        List<String> dcpList = new ArrayList<>();
        dcpList.add(RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp"));
        dcpList.add(RapidWrightDCP.getString("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp"));
        dcpList.add(RapidWrightDCP.getString("optical-flow.dcp"));
        dcpList.add(RapidWrightDCP.getString("bnn.dcp"));
        
        List<Design> designs = dcpList.stream().parallel() // Comment out the .parallel() part to run serially
            .map(p -> (Design.readCheckpoint(p))).collect(Collectors.toList());
    }
    
    @Test
    @CheckOpenFiles
    public void testParallelDCPLoad2() throws IOException, InterruptedException {
        List<String> dcpList = new ArrayList<>();
        dcpList.add(RapidWrightDCP.getString("picoblaze_ooc_X10Y235.dcp"));
        dcpList.add(RapidWrightDCP.getString("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp"));
        dcpList.add(RapidWrightDCP.getString("optical-flow.dcp"));
        dcpList.add(RapidWrightDCP.getString("bnn.dcp"));
        
        ExecutorService exec = Executors.newFixedThreadPool(dcpList.size());
        exec.invokeAll(dcpList.stream().<Callable<Design>>map(p -> () -> (Design.readCheckpoint(p))).collect(Collectors.toList()));
        exec.shutdown();
    }

}
