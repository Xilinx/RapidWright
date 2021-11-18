package com.xilinx.rapidwright.placer;

import java.io.File;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.examples.PicoBlazeArray;
import com.xilinx.rapidwright.placer.blockplacer.BlockPlacer2;
import com.xilinx.rapidwright.support.CheckOpenFiles;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestBlockPlacer2 {

    private void placeSomePicoBlazeArray(PicoBlazeArray.PicoBlazeArrayCreator<?> arrayCreator) {
        CodePerfTracker t = new CodePerfTracker("PicoBlazeArray Test");
        File srcDir = RapidWrightDCP.getPath("PicoBlazeArray").toFile();
        Design design = arrayCreator.createDesign(srcDir, "xcvu3p-ffvc1517-2-i", t);

        BlockPlacer2<?, ?, ?, ?> placer = arrayCreator.createPlacer(design, null);
        double cost = placer.placeDesign(false);

        Assertions.assertTrue(cost < 400000);

        arrayCreator.lowerToModules(design, t);

        t.stop();
        t.printSummary();

    }

    @Test
    @CheckOpenFiles
    public void placePicoBlazeArrayModules() {
        placeSomePicoBlazeArray(PicoBlazeArray.makeModuleCreator());
    }

    @Test
    @CheckOpenFiles
    public void placePicoBlazeArrayImpls() {
        placeSomePicoBlazeArray(PicoBlazeArray.makeImplsCreator());
    }
}
