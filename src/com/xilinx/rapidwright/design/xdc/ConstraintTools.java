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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.xdc.parser.RegularEdifCellLookup;

/**
 * A collection of methods to access design constraints.
 *
 * Created on: Oct 31, 2025
 */
public class ConstraintTools {

    public static Map<String, PBlock> getPBlockFromXDCConstraints(Design d) {
        Map<String, PBlock> pblockMap = new HashMap<>();

        for (ConstraintGroup cg : ConstraintGroup.values()) {
            XDCConstraints xdcConstraints = XDCParser.parseXDC(d.getDevice(), d.getXDCConstraints(cg), new RegularEdifCellLookup(d.getNetlist()));
            xdcConstraints.getPBlockConstraints().forEach((k,v)->{
                pblockMap.put(k, v.getPblock());
            });
        }

        return pblockMap;
    }
}
