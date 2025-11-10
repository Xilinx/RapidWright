/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Coherent Ho, Synopsys, Inc.
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

package com.xilinx.rapidwright.design.xdc;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PblockProperty;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

public class TestConstraintTools {

    @ParameterizedTest
    @EnumSource(TestXDCParser.RoundtripMode.class)
    public void testGetPBlockFromXDCConstraints(TestXDCParser.RoundtripMode roundtripMode) {
        Design d = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        d.getXDCConstraints(ConstraintGroup.LATE).add("set_property " + PblockProperty.IS_SOFT + " 1 [get_pblocks pblock_dbg_hub]");
        d.getXDCConstraints(ConstraintGroup.LATE).add("set_property " + PblockProperty.EXCLUDE_PLACEMENT + " 1 [get_pblocks pblock_u_ila_0]");
        roundtripMode.doRoundtrip(d);

        Map<String, PBlock> pblockMap = ConstraintTools.getPBlockFromXDCConstraints(d);
        Assertions.assertEquals(3, pblockMap.size());
        Assertions.assertTrue(pblockMap.containsKey("pblock_dbg_hub"));
        Assertions.assertTrue(pblockMap.containsKey("pblock_base_mb_i"));
        Assertions.assertTrue(pblockMap.containsKey("pblock_u_ila_0"));

        // Check for the property and cooresponding TclConstraints
        String TclConstraints;
        PBlock dbgHub = pblockMap.get("pblock_dbg_hub");
        dbgHub.setName("pblock_dbg_hub");
        Assertions.assertTrue(dbgHub.containRouting());
        Assertions.assertTrue(dbgHub.isSoft());
        Assertions.assertFalse(dbgHub.excludePlacement());
        TclConstraints = String.join(" ", dbgHub.getTclConstraints());
        Assertions.assertTrue(
            TclConstraints.contains(PblockProperty.CONTAIN_ROUTING.toString())
            && TclConstraints.contains(PblockProperty.IS_SOFT.toString())
            && !TclConstraints.contains(PblockProperty.EXCLUDE_PLACEMENT.toString())
        );

        PBlock baseMb = pblockMap.get("pblock_base_mb_i");
        baseMb.setName("pblock_base_mb_i");
        Assertions.assertTrue(baseMb.containRouting());
        Assertions.assertFalse(baseMb.isSoft());
        Assertions.assertFalse(baseMb.excludePlacement());
        TclConstraints = String.join(" ", baseMb.getTclConstraints());
        Assertions.assertTrue(
            TclConstraints.contains(PblockProperty.CONTAIN_ROUTING.toString())
            && !TclConstraints.contains(PblockProperty.IS_SOFT.toString())
            && !TclConstraints.contains(PblockProperty.EXCLUDE_PLACEMENT.toString())
        );

        PBlock uila0 = pblockMap.get("pblock_u_ila_0");
        uila0.setName("pblock_u_ila_0");
        Assertions.assertTrue(uila0.containRouting());
        Assertions.assertFalse(uila0.isSoft());
        Assertions.assertTrue(uila0.excludePlacement());
        TclConstraints = String.join(" ", uila0.getTclConstraints());
        Assertions.assertTrue(
            TclConstraints.contains(PblockProperty.CONTAIN_ROUTING.toString())
            && !TclConstraints.contains(PblockProperty.IS_SOFT.toString())
            && TclConstraints.contains(PblockProperty.EXCLUDE_PLACEMENT.toString())
        );
    }
}
