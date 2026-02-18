/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;

import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;

import com.xilinx.rapidwright.design.Design;

public class VivadoToolsHelper {
    public static void assertFullyRouted(Design design) {
        if (!FileTools.isVivadoOnPath()) {
            return;
        }

        ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
        Assertions.assertTrue(rrs.isFullyRouted());
    }

    public static void assertFullyRouted(Path dcp) {
        if (!FileTools.isVivadoOnPath()) {
            return;
        }

        ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(dcp);
        Assertions.assertTrue(rrs.isFullyRouted());
    }

    /**
     * Ensures that the provided design can be routed successfully in Vivado.
     * 
     * @param design The design to route.
     * @param dir    The directory to work within.
     */
    public static void assertRoutedSuccessfullyByVivado(Design design, Path dir) {
        if (!FileTools.isVivadoOnPath()) {
            return;
        }
        ReportRouteStatusResult rrs = VivadoTools.routeDesignAndGetStatus(design, dir);
        Assertions.assertTrue(rrs.isFullyRouted());
    }

    public static void assertCanBeFullyPlacedAndRoutedByVivado(Design design, Path dir) {
        if (!FileTools.isVivadoOnPath()) {
            return;
        }
        ReportRouteStatusResult rrs = VivadoTools.placeAndRouteDesignAndGetStatus(design, dir);
        Assertions.assertTrue(rrs.isFullyRouted());
    }

    public static void assertCanBeFullyRoutedByVivado(Path dcp, Path dir, boolean hasEncryptedCells) {
        if (!FileTools.isVivadoOnPath()) {
            return;
        }
        ReportRouteStatusResult rrs = VivadoTools.routeDesignAndGetStatus(dcp, dir, hasEncryptedCells);
        Assertions.assertTrue(rrs.isFullyRouted());
    }
    
    public static void assertPortCountAfterRoundTripInVivado(Design design, Path dir, boolean expectEquals) {
        if (!FileTools.isVivadoOnPath()) {
            return;
        }
        int beforePortCount = design.getNetlist().getTopCell().getPorts().size();
        Design newDesign = VivadoTools.roundTripDCPThruVivado(design, dir);
        int afterPortCount = newDesign.getNetlist().getTopCell().getPorts().size();
        if (expectEquals) {
            Assertions.assertEquals(beforePortCount, afterPortCount);
        } else {
            Assertions.assertNotEquals(beforePortCount, afterPortCount);
        }
    }

    public static void createWorstPathFile(Path dcpPath, Path pathTxt) {
        String tclCommand = "open_checkpoint " + dcpPath + ";";
        tclCommand += "set fp [open " + pathTxt + " \"w\"];";
        tclCommand += " foreach p [get_pins -of [get_timing_paths -nworst 1 ]] {puts $fp $p};";
        tclCommand += "close $fp";
        VivadoTools.runTcl(pathTxt.getParent().resolve("out.log"), tclCommand, true);
    }
}
