/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel
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

package com.xilinx.rapidwright.design;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.NoSuchElementException;

import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestMetadataParser {

    private static WeakReference<Design> moduleDesign = null;

    private static Design loadDesign() {
        if (moduleDesign!=null) {
            Design existing = moduleDesign.get();
            if (existing!=null) {
                return existing;
            }
        }

        Design design = RapidWrightDCP.loadDCP("module_no_site_pin_for_output.dcp");
        moduleDesign = new WeakReference<>(design);
        return design;
    }
    private static Module loadModule(String metadataSuffix) {
        return new Module(loadDesign(), RapidWrightDCP.getString("module_no_site_pin_for_output_metadata_"+metadataSuffix+".txt"));
    }
    @Test
    public void testMissingSpi() {
        Assertions.assertThrows(NoSuchElementException.class, () -> {
            loadModule("explicit");
        });
    }
    @Test
    public void testMetadataParser(){
        Module explicit = loadModule("explicit_manualfix");
        Module implicit = loadModule("implicit");

        Assertions.assertEquals(explicit.getPorts().size()+1, implicit.getPorts().size());
        for (Port explicitPort : explicit.getPorts()) {
            Port implicitPort = implicit.getPort(explicitPort.getName());
            Assertions.assertNotNull(implicitPort);

            Assertions.assertEquals(explicitPort.getSitePinInsts(), implicitPort.getSitePinInsts(), () -> "expected same site pin insts for " + explicitPort.getName());

        }
    }
}
