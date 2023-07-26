package com.xilinx.rapidwright.tutorials;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.MainStyleFunction;
import com.xilinx.rapidwright.rwroute.PartialRouter;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;

public class RWRouteTutorials {

    private void testRWRouteTutorial(MainStyleFunction<?> func, Path dir, String dcpName, String option) {
        Path inputDCP = dir.resolve(dcpName);
        FileTools.runCommand("wget http://www.rapidwright.io/docs/_downloads/" + dcpName + " -O" + inputDCP, true, null, dir.toFile());
        Path outputDCP = Paths.get(inputDCP.toString().replace(".dcp", "_routed.dcp"));
        RWRoute.main(option == null ? new String[] { inputDCP.toString(), outputDCP.toString() }
                : new String[] { inputDCP.toString(), outputDCP.toString(), option });
        if (FileTools.isVivadoOnPath()) {
            Assertions.assertTrue(VivadoTools.reportRouteStatus(outputDCP).isFullyRouted());
        }
    }

    @Test
    public void testRWRouteTimingDrivenRouting(@TempDir Path dir) {
        testRWRouteTutorial(RWRoute::main, dir, "gnl_2_4_7_3.0_gnl_3500_03_7_80_80.dcp", null);
    }

    @Test
    public void testRWRouteWirelengthDrivenRouting(@TempDir Path dir) {
        testRWRouteTutorial(RWRoute::main, dir, "gnl_2_4_7_3.0_gnl_3500_03_7_80_80.dcp", "--nonTimingDriven");
    }

    @Test
    public void testRWRoutePartialRouting(@TempDir Path dir) {
        testRWRouteTutorial(PartialRouter::main, dir, "picoblaze_partial.dcp", "--nonTimingDriven");
    }

}
