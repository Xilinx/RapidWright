/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Advanced Research and Development.
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

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.util.MessageGenerator;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * Copies the placement and routing information from one or more cells in one
 * design to corresponding cells in another design.
 */
public class CopyImplementation {

    private static final List<String> SRC_DESIGN_OPTS = Arrays.asList("s", "src_design");
    private static final List<String> DST_DESIGN_OPTS = Arrays.asList("d", "dst_design");
    private static final List<String> OUT_DESIGN_OPTS = Arrays.asList("o", "out_design");
    private static final List<String> HELP_OPTS = Arrays.asList("?", "h", "help");
    private static final List<String> DONT_LOCK_PLACEMENT_OPTS = Arrays.asList("p", "dont_lock_placement");
    private static final List<String> DONT_LOCK_ROUTING_OPTS = Arrays.asList("r", "dont_lock_routing");
    private static final List<String> INST_MAPPINGS_OPTS = Arrays.asList("i", "src_dst_mappings");

    private static void printHelp(OptionParser p) {
        MessageGenerator.printHeader(CopyImplementation.class.getSimpleName());
        System.out.println(
                "Copies the placement and routing information from one or more cells in one design \n"
                + "to corresponding cells in another design.");
        try {
            p.printHelpOn(System.out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static OptionSet getOptions(String[] args) {

        OptionParser p = new OptionParser() {
            {
                acceptsAll(SRC_DESIGN_OPTS, "Implementation Source Design DCP File Name").withRequiredArg();
                acceptsAll(DST_DESIGN_OPTS, "Implementation Destination Design DCP File Name").withRequiredArg();
                acceptsAll(OUT_DESIGN_OPTS, "Resulting Output Design DCP File Name").withRequiredArg();
                acceptsAll(DONT_LOCK_PLACEMENT_OPTS, "Don't lock placement of resulting output design");
                acceptsAll(DONT_LOCK_ROUTING_OPTS, "Don't lock routing of resulting output design");
                acceptsAll(HELP_OPTS, "Print this help message").forHelp();
                acceptsAll(INST_MAPPINGS_OPTS, "Instance name mappings to copy implementation, each "
                        + "mapping separated by a comma (',') and src and dst names separated by a "
                        + "colon (':') (src0:dst0,src1:dst1,...)").withRequiredArg();
            }
        };

        OptionSet options = p.parse(args);
        if (options.has(HELP_OPTS.get(0)) || args.length == 0) {
            printHelp(p);
            return null;
        }

        return options;
    }

    public static void main(String[] args) {
        OptionSet options = getOptions(args);
        if (options == null) {
            // Help message was invoked
            return;
        }
        
        Design src = Design.readCheckpoint(options.valueOf(SRC_DESIGN_OPTS.get(0)).toString());
        Design dst = Design.readCheckpoint(options.valueOf(DST_DESIGN_OPTS.get(0)).toString());
        boolean lockPlacement = options.valueOf(DONT_LOCK_PLACEMENT_OPTS.get(0)) == null;
        boolean lockRouting = options.valueOf(DONT_LOCK_ROUTING_OPTS.get(0)) == null;
        Map<String,String> srcToDstInstNames = new HashMap<>();
        
        String[] mappings = options.valueOf(INST_MAPPINGS_OPTS.get(0)).toString().split(",");
        // Parse src-to-dst instance name mappings(separated by ':') and put into map
        for (String mapping : mappings) {
            int idx = mapping.indexOf(':');
            String srcName = mapping.substring(0, idx);
            String dstName = mapping.substring(idx + 1);
            srcToDstInstNames.put(srcName, dstName);
        }

        DesignTools.copyImplementation(src, dst, lockPlacement, lockRouting, srcToDstInstNames);
        
        dst.writeCheckpoint(options.valueOf(OUT_DESIGN_OPTS.get(0)).toString());
    }
}
