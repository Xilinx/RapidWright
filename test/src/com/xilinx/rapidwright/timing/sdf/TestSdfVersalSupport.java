/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.sdf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.support.LargeTest;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.util.FileTools;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Tests that a timing graph can be built for a device RapidWright ships no timing data for.
 *
 * This is the point of the whole exercise for the use case in issue #1312: the requester targets
 * Versal, and RapidWright's delay estimator has data only for UltraScale+. Without the
 * topology-only build path, a Versal design cannot get a timing graph at all, so there is nothing
 * for an SDF to annotate.
 *
 * The design is constructed in code rather than read from a checkpoint, so this needs neither
 * Vivado nor a fixture in the {@code RapidWrightDCP} submodule.
 */
public class TestSdfVersalSupport {

    private static final String VERSAL_PART = "xcvp1902-vsva6865-2MP-e-S";

    /**
     * Builds a minimal placed design on a Versal part.
     *
     * @return The design.
     */
    private static Design createVersalDesign() {
        Design design = new Design("versalTimingTest", VERSAL_PART);
        Device device = design.getDevice();
        Assertions.assertEquals(com.xilinx.rapidwright.device.Series.Versal, device.getSeries());
        Site slice = null;
        for (Site site : device.getAllSitesOfType(SiteTypeEnum.SLICEL)) {
            slice = site;
            break;
        }
        Assertions.assertNotNull(slice, "no SLICEL found on " + VERSAL_PART);
        design.createAndPlaceCell("ff", Unisim.FDRE, slice.getName() + "/AFF");
        return design;
    }

    @Test
    @LargeTest
    public void testTimingGraphBuildsOnVersalWithoutDelayModel() {
        Design design = createVersalDesign();

        // The whole point: this must not need timing/versal/*.txt, which does not exist.
        TimingManager tm = new TimingManager(design, false);
        Assertions.assertNotNull(tm.getTimingGraph());
        Assertions.assertFalse(tm.getTimingGraph().getUseDelayModel());
        Assertions.assertFalse(tm.getTimingGraph().vertexSet().isEmpty(),
                "the topology-only build produced no vertices");
    }

    @Test
    @LargeTest
    public void testEstimatorPathStillRequiresDeviceTimingData() {
        // Guard the assertion on the data genuinely being absent, so that adding a
        // timing/versal directory in future turns this test into a no-op rather than a failure.
        Path versalTiming = Paths.get(FileTools.getRapidWrightPath(), "timing", "versal",
                "intersite_delay_terms.txt");
        Assumptions.assumeFalse(Files.exists(versalTiming),
                "Versal timing data is now present, so the estimator is expected to work");

        Design design = createVersalDesign();

        // Documents why the topology-only path exists: the estimator cannot run here.
        RuntimeException e = Assertions.assertThrows(RuntimeException.class,
                () -> new TimingManager(design));
        Assertions.assertTrue(e.getMessage().contains("intersite_delay_terms.txt"),
                "expected a complaint about the missing timing data, got: " + e.getMessage());
    }

}
