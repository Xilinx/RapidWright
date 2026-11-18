/*
 *
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;

public class TestRegroupInstances {

    @Test
    public void testRegroupInstances(@TempDir Path dir) {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks_2024.1.dcp");
        design.unplaceDesign();

        Path regroupFile = dir.resolve("regroup_mappings.txt");
        List<String> lines = new ArrayList<>();
        lines.add("base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core partition2");
        lines.add("base_mb_i/microblaze_0_local_memory/dlmb_v10 partition2/microblaze_0_local_memory_2");
        lines.add("base_mb_i/microblaze_0_local_memory/ilmb_v10 partition2/microblaze_0_local_memory_2");

        FileTools.writeLinesToTextFile(lines, regroupFile.toString());

        Map<String, String> mappings = RegroupInstances.parseRegroupMappingsFile(regroupFile.toString());
        
        // Create a reference map of each pin count on nets connected to each port inst on a regroup'd cell
        Map<String, Integer> portInstPinCountRef = new HashMap<>();
        for (String inst : mappings.keySet()) {
            EDIFHierCellInst i = design.getNetlist().getHierCellInstFromName(inst);
            for (EDIFHierPortInst pi : i.getHierPortInsts()) {                
                int pinCount = pi.getHierarchicalNet().getLeafHierPortInsts().size();
                portInstPinCountRef.put(pi.getPortInst().getFullName(), pinCount);
            }
        }
        
        
        RegroupInstances.regroupInstances(design, mappings);
        EDIFNetlist n = design.getNetlist();
        
        String[] expectedInstances = new String[] {
                                "partition2/Performance.Core", 
                                "partition2/microblaze_0_local_memory_2/dlmb_v10", 
                                "partition2/microblaze_0_local_memory_2/ilmb_v10"
                                };
        
        for (String expectedInst : expectedInstances) {
            EDIFHierCellInst hierInst = n.getHierCellInstFromName(expectedInst);
            Assertions.assertNotNull(hierInst);
            for (EDIFHierPortInst pi : hierInst.getHierPortInsts()) {                
                int pinCount = pi.getHierarchicalNet().getLeafHierPortInsts().size();
                Assertions.assertEquals(pinCount, portInstPinCountRef.get(pi.getPortInst().getFullName()));
            }
        }
    }
}
