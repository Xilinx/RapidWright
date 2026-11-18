/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.design.tools;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.VivadoToolsHelper;

public class TestDesignObfuscator {

    @Test
    public void testDesignObfuscator(@TempDir Path path) {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");

        Set<String> names = new HashSet<>();
        // Populate names with strings that should be obfuscated
        for (EDIFHierCellInst inst : design.getNetlist().getAllDescendants("", null, false)) {
            names.add(inst.getFullHierarchicalInstName());
        }
        for (EDIFHierNet net : design.getNetlist().getParentNetMap().keySet()) {
            names.add(net.getHierarchicalNetName());
        }

        DesignObfuscator o = new DesignObfuscator();
        Design obfuscatedDesign = o.obfuscateDesign(design);

        Assertions.assertEquals(design.getCells().size(), obfuscatedDesign.getCells().size());
        Assertions.assertEquals(design.getNets().size(), obfuscatedDesign.getNets().size());

        for (EDIFHierCellInst inst : obfuscatedDesign.getNetlist().getAllDescendants("", null, false)) {
            Assertions.assertFalse(names.contains(inst.getFullHierarchicalInstName()));
        }
        for (EDIFHierNet net : obfuscatedDesign.getNetlist().getParentNetMap().keySet()) {
            Assertions.assertFalse(names.contains(net.getHierarchicalNetName()));
        }

        VivadoToolsHelper.assertFullyRouted(design);
    }

}
