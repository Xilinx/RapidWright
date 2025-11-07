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
import java.util.EnumSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.PblockProperty;
import com.xilinx.rapidwright.design.ConstraintGroup;

/**
 * A collection of methods to access design constraints.
 *
 * Created on: Oct 31, 2025
 */
public class ConstraintTools {
    private static final Pattern PBLOCK_NAME_PATTERN = Pattern.compile("\\[get_pblocks\\s+(\\S+)]");
    private static final Pattern RANGE_PATTERN = Pattern.compile("\\{([^}]+)}");

    public static Map<String, PBlock> getPBlockFromXDCConstraints(Design d) {
        Map<String, PBlock> pblockMap = new HashMap<>();
        Map<String, EnumSet<PblockProperty>> pblockProperties = new HashMap<>();

        for (ConstraintGroup cg : ConstraintGroup.values()) {
            for (String tclLine : d.getXDCConstraints(cg)) {
                String name = extractPBlockName(tclLine);
                if (name == null) continue;

                EnumSet<PblockProperty> props =
                        pblockProperties.computeIfAbsent(name, k -> EnumSet.noneOf(PblockProperty.class));

                if (tclLine.contains("resize_pblock")) {
                    String range = extractRange(tclLine);
                    if (range != null) {
                        pblockMap.put(name, new PBlock(d.getDevice(), range));
                    }
                } else if (tclLine.contains(PblockProperty.CONTAIN_ROUTING + " 1")) {
                    props.add(PblockProperty.CONTAIN_ROUTING);
                } else if (tclLine.contains(PblockProperty.IS_SOFT + " 1")) {
                    props.add(PblockProperty.IS_SOFT);
                } else if (tclLine.contains(PblockProperty.EXCLUDE_PLACEMENT + " 1")) {
                    props.add(PblockProperty.EXCLUDE_PLACEMENT);
                }
            }
        }

        // set property
        for (Map.Entry<String, EnumSet<PblockProperty>> entry : pblockProperties.entrySet()) {
            String name = entry.getKey();
            EnumSet<PblockProperty> props = entry.getValue();
            PBlock pb = pblockMap.get(name);
            if (pb == null) continue;

            if (props.contains(PblockProperty.CONTAIN_ROUTING)) {
                pb.setContainRouting(true);
            }
            if (props.contains(PblockProperty.IS_SOFT)) {
                pb.setIsSoft(true);
            }
            if (props.contains(PblockProperty.EXCLUDE_PLACEMENT)) {
                pb.setExcludePlacement(true);
            }
        }
        return pblockMap;
    }

    private static String extractPBlockName(String line) {
        Matcher m = PBLOCK_NAME_PATTERN.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private static String extractRange(String line) {
        Matcher m = RANGE_PATTERN.matcher(line);
        return m.find() ? m.group(1) : null;
    }
}
