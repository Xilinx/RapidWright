package com.xilinx.rapidwright.design.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.merge.MergeDesigns;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;

public class TestMergeDesigns {

    @Test
    @CheckOpenFiles
    public void testDisjointMerge() throws IOException {
        Design design0 = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze_ooc_X10Y235.dcp"));
        Design design1 = Design.readCheckpoint(RapidWrightDCP.getPath("picoblaze4_ooc_X6Y60_X6Y65_X10Y60_X10Y65.dcp"));

        Design merged = MergeDesigns.mergeDesigns(design0, design1);

    }
}
