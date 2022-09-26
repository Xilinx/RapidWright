/*
 * Copyright (c) 2022 Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TestBlackBoxPopulator {

    private static String topDCPName = RapidWrightDCP.getString("hwct.dcp");
    private static String cellDCPName = RapidWrightDCP.getString("hwct_pr1.dcp");
    private static String cellAnchor = "INT_X0Y60";
    private static List<Pair<String, String>> targets = new ArrayList<Pair<String, String>>()
    {{
        add(new Pair<>("hw_contract_pr0", "INT_X0Y120"));
        add(new Pair<>("hw_contract_pr1", "INT_X0Y60"));
        add(new Pair<>("hw_contract_pr2", "INT_X0Y0"));
    }};

    private int numPIPs(Design d) {
        int PIPCount = 0;
        for (Net n : d.getNets()) {
            PIPCount += n.getPIPs().size();
        }
        return PIPCount;
    }

    @Test
    void testRelocateModuleInsts() {
        Design top = Design.readCheckpoint(topDCPName);
        int numCellTop = top.getCells().size();
        int numVccNetTop = (top.getVccNet() == null) ? 0 : 1;
        int numGndNetTop = (top.getGndNet() == null) ? 0 : 1;
        int numSignalNetTop = top.getNets().size() - numVccNetTop - numGndNetTop;
        int numPIPTop = numPIPs(top);

        Design template = Design.readCheckpoint(cellDCPName);
        int numVccNetTemplate = (template.getVccNet() == null) ? 0 : 1;
        int numGndNetTemplate = (template.getGndNet() == null) ? 0 : 1;
        int numSignalNetTemplate = template.getNets().size() - numVccNetTemplate - numGndNetTemplate;
        int numPIPTemplate = numPIPs(template);

        Module mod = new Module(template, false);
        BlackboxPopulator.relocateModuleInsts(top, mod, cellAnchor, targets);

        Assertions.assertEquals(targets.size()*template.getCells().size()+numCellTop, top.getCells().size()
                ,"Wrong number of cells!");

        int numExpectedNets = targets.size()*numSignalNetTemplate + numSignalNetTop
                              + Math.max(numVccNetTop, numVccNetTemplate) + Math.max(numGndNetTop, numGndNetTemplate);
        Assertions.assertEquals(numExpectedNets, top.getNets().size(),"Wrong number of nets!");

        Assertions.assertEquals(targets.size()*numPIPTemplate + numPIPTop, numPIPs(top),"Wrong number of PIPs!");


        /**
         * To catch a problem where the work library appears before its dependencies.
         *
         * The current workaround is to put every library into work library,
         * ie., using consolidateAllToWorkLibrary() at the end of relocateModuleInsts.
         * This test is specific to that workaround to be fast.
         * Later when a better solution is adopted, this test will need to adjust to check the solution.
         */
        Collection<EDIFLibrary> libs = top.getNetlist().getLibraries();
        Assertions.assertEquals(2, libs.size(),"Expect two libraries!");

        Set<String> libNames = new HashSet<>();
        for (EDIFLibrary l : libs) {
            libNames.add(l.getName());
        }

        Assertions.assertTrue(libNames.contains("hdi_primitives"),"Expect to have hdi_primitives library!");
        Assertions.assertTrue(libNames.contains("work"),"Expect to have work library!");
    }
}
