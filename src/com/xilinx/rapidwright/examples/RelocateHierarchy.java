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

package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.tools.RelocationTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * Given an input DCP, a hierarchy prefix (empty string to match entire design)
 * and tile column/row offsets, move all matching cells (and the PIPs that
 * connect between such cells) by this tile offset.
 *
 * Specifically, the SiteInst associated with a matching Cell is relocated.
 * Should this SiteInst contain any Cell-s not matching the hierarchy prefix,
 * relocation will fail.
 *
 * @author eddieh
 *
 */
public class RelocateHierarchy {

    public static void main(String[] args) {
        if (args.length != 5) {
            System.out.println("USAGE: <input_dcp> <hierarchical_path> <tile_col_offset> <tile_row_offset> <output_dcp>");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Relocate Design", true).start("Loading design");

        String dcpName = args[0];
        Design design = Design.readCheckpoint(dcpName,CodePerfTracker.SILENT);

        t.stop().start("Relocation");

        String hierarchyPrefix = args[1];
        int colOffset = Integer.parseInt(args[2]);
        int rowOffset = Integer.parseInt(args[3]);

        if (!RelocationTools.relocate(design, hierarchyPrefix, colOffset, rowOffset)) {
            throw new RuntimeException("ERROR: Relocation failed");
        }

        t.stop().start("Write DCP");

        design.setAutoIOBuffers(false);
        design.writeCheckpoint(args[4], CodePerfTracker.SILENT);
        t.stop().printSummary();
    }
}
