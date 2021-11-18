/*
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

import java.util.ArrayList;
import java.util.List;

import com.xilinx.rapidwright.design.drc.NetRoutesThruLutAtMostOnce;

/**
 * Parent DRC that executes a list of child DRCs, returning the sum of all failed checks.
 */
public class DRC {
    interface DrcTypeSignature {
        int run(Design design, boolean strict);
    }

    // Static list of all DRCs to be run
    public static final List<DrcTypeSignature> checks =
            new ArrayList<DrcTypeSignature>() {{
        add(NetRoutesThruLutAtMostOnce::run);
    }};

    public int run(Design design, boolean strict) {
        // Each check's run() returns an int of how many checks failed,
        // sum those up
        return checks.stream().map((f) -> f.run(design, strict))
                .reduce(0, Integer::sum);
    }

    private static void printUsageAndExit() {
        System.out.println("USAGE: <input.dcp> [--strict]");
        System.exit(1);
    }

    public static void main(String[] args) {
        if(args.length < 1 || args.length > 2) {
            printUsageAndExit();
        }

        boolean strict = false;
        if (args.length == 2) {
            if (args[1].equals("--strict")) {
                strict = true;
            } else {
                printUsageAndExit();
            }
        }

        Design design = Design.readCheckpoint(args[0]);
        int numFailed = new DRC().run(design, strict);
        if (numFailed > 0) {
            throw new RuntimeException("ERROR: " + numFailed + " failed DRCs");
        }
    }
}
