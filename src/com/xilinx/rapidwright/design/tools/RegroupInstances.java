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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.eco.ECOTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Regroups (or refactor) a set of instances in a design or netlist such that
 * each instance is logically re-located to a new parent in the hierarchy of the
 * netlist.
 */
public class RegroupInstances {

    /**
     * Regroups the design/netlist hierarchy according to the provided map which
     * specifies instances to regroup to a new parent name and maintains logical
     * connectivity. Note the design must be unplaced to perform this operation.
     * 
     * @param design          The current design to operate on
     * @param regroupMappings A map of regroup mappings where the key is the full
     *                        hierarchical instance name to regroup and the value is
     *                        the full hierarchical instance name of the new parent
     *                        of the instance.
     */
    public static void regroupInstances(Design design, Map<String, String> regroupMappings) {
        EDIFNetlist netlist = design.getNetlist();
        EDIFLibrary work = netlist.getWorkLibrary();
        for (Entry<String,String> mapping : regroupMappings.entrySet()) {
            EDIFHierCellInst instance = netlist.getHierCellInstFromName(mapping.getKey());
            if (instance == null) {
                throw new RuntimeException("ERROR: Couldn't find netlist instance " 
                        + mapping.getKey() + " to regroup to new parent: " 
                        + mapping.getValue());
            }
            EDIFHierCellInst newParent = netlist.getHierCellInstFromName(mapping.getValue());
            if (newParent == null) {
                // The parent instance does not exist, we need to create a new cell and instantiate it
                String newParentName = mapping.getValue();
                int idx = newParentName.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
                String proposedInstName = idx == -1 ? newParentName : newParentName.substring(idx + 1);
                EDIFCell newCell = new EDIFCell(work, proposedInstName + EDIFTools.getUniqueSuffix());
                EDIFHierCellInst grandParent = netlist.getHierCellInstFromName(idx == -1 ? "" : newParentName.substring(0, idx));
                EDIFCellInst newParentInst = grandParent.getCellType().createChildCellInst(proposedInstName, newCell);
                newParent = grandParent.getChild(newParentInst);
            }
            ECOTools.refactorCell(design, instance, newParent);
        }
    }

    public static Map<String, String> parseRegroupMappingsFile(String fileName) {
        Map<String, String> mappings = new LinkedHashMap<>();
        int lineNum = 0;
        for (String line : FileTools.getLinesFromTextFile(fileName)) {
            lineNum++;
            String tmp = line.trim();
            if (line.trim().length() == 0 || tmp.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length > 2) {
                throw new RuntimeException(
                        "ERROR: Unrecognized tokens on line " + lineNum + "'" + line + "'");
            }
            if (parts.length == 1) {
                // Regroup to top level
                mappings.put(parts[0], "");
            } else {
                mappings.put(parts[0], parts[1]);
            }
        }
        return mappings;
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("USAGE: <input_design{.edf|.dcp}> <output_design{.edf|.dcp}> <regroup_mappings.txt>");
            System.out.println("    *** Regroup Mappings File Format ***");
            System.out.println("    <current hierarchical instance name to regroup> <new parent instance name>");
            System.out.println("    ...");
            System.out.println("    # For example, the following file: ");
            System.out.println("    #   base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core partition2");
            System.out.println("    #   base_mb_i/microblaze_0_local_memory/dlmb_v10 partition2/microblaze_0_local_memory_2");
            System.out.println("    #   base_mb_i/microblaze_0_local_memory/ilmb_v10 partition2/microblaze_0_local_memory_2");
            System.out.println("    # Would result in regrouped instances:");
            System.out.println("    #   partition2/Performance.Core");
            System.out.println("    #   partition2/microblaze_0_local_memory_2/dlmb_v10");
            System.out.println("    #   partition2/microblaze_0_local_memory_2/ilmb_v10");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Regroup Instances");
        Design design = null;
        t.start("Read Design");
        if (args[0].endsWith(".dcp")) {
            design = Design.readCheckpoint(args[0], CodePerfTracker.SILENT);
            if (design.getSiteInsts().size() > 0) {
                // TODO - ECOTools.refactorCell() is the limitation. When support is added, this
                // constraint can be removed
                System.out.println("WARNING: Regrouping placed and/or routed non-leaf cells not "
                        + "supported.  Implementation information will be removed prior to regrouping instances.");
                design.unplaceDesign();
            }

        } else {
            design = new Design(EDIFTools.readEdifFile(args[0]));
        }
        t.stop().start("Regroup Instances");
        regroupInstances(design, parseRegroupMappingsFile(args[2]));
        t.stop().start("Write Design");
        if (args[1].endsWith(".dcp")) {
            design.writeCheckpoint(args[1], CodePerfTracker.SILENT);
        } else {
            design.getNetlist().collapseMacroUnisims(design.getSeries());
            design.getNetlist().exportEDIF(args[1]);
        }
        t.stop().printSummary();
    }
}
