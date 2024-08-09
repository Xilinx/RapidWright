/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
package com.xilinx.rapidwright.design.merge;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.tests.CodePerfTracker;

/**
 * Merges two or more designs into a single Design. Merge process can be
 * customized through the use of the @link {@link AbstractDesignMerger}
 * interface.
 */
public class MergeDesigns {

    private static Design mergeDesigns(Design design0, Design design1, AbstractDesignMerger merger) {
        EDIFCell topCell0 = design0.getTopEDIFCell();
        EDIFCell topCell1 = design1.getTopEDIFCell();

        design0.getNetlist().migrateCellAndSubCells(topCell1, true);

        for (EDIFPort port1 : new ArrayList<>(topCell1.getPorts())) {
            EDIFPort port0 = topCell0.getPort(port1.getBusName());
            if (port0 == null) {
                topCell0.addPort(port1);
            } else {
                merger.mergePorts(port0, port1);
            }
        }

        for (EDIFNet net1 : topCell1.getNets()) {
            EDIFNet net0 = topCell0.getNet(net1);
            if (net0 == null) {
                topCell0.addNet(net1);
                for (EDIFPortInst inst : net1.getPortInsts()) {
                    if (inst.isTopLevelPort() && inst.getPort().getParentCell() == topCell0) {
                        topCell0.addInternalPortMapEntry(inst.getPortInstNameFromPort(), net1);
                    }
                }
            } else {
                merger.mergeLogicalNets(net0, net1);
            }
        }

        for (EDIFCellInst inst1 : topCell1.getCellInsts()) {
            EDIFCellInst inst0 = topCell0.getCellInst(inst1.getName());
            if (inst0 == null) {
                topCell0.addCellInst(inst1);
            } else {
                merger.mergeCellInsts(inst0, inst1);
            }
        }

        for (SiteInst siteInst1 : design1.getSiteInsts()) {
            SiteInst siteInst0 = design0.getSiteInstFromSiteName(siteInst1.getSiteName());
            if (siteInst0 == null) {
                design0.addSiteInst(siteInst1);
            } else {
                merger.mergeSiteInsts(siteInst0, siteInst1);
            }
        }

        for (Net net1 : design1.getNets()) {
            Net net0 = design0.getNet(net1.getName());
            if (net0 == null) {
                design0.addNet(net1);
            } else {
                merger.mergePhysicalNets(net0, net1);
            }
        }

        // Merge encrypted cells
        List<String> encryptedCells = design1.getNetlist().getEncryptedCells();
        if (encryptedCells != null && encryptedCells.size() > 0) {
            design0.getNetlist().addEncryptedCells(encryptedCells);
        }

        design0.getNetlist().removeUnusedCellsFromAllWorkLibraries();
        design0.getNetlist().resetParentNetMap();
        return design0;
    }

    public static Design mergeDesigns(Design...designs) {
        return mergeDesigns(() -> new DefaultDesignMerger(), designs);
    }

    /**
     * Merges two or more designs together into a single design.  Merges both logical and physical
     * netlist.  Assumes that designs are compatible for merging. Assumes that if there are duplicate
     * cells in the set of designs to be merged that they are flip-flops and that they are always
     * connected to a top-level port.
     * @param merger The specific design merger instance to use to merge the designs
     * @param designs The set of designs to be merged into a single design.
     * @return The merged design that contains the superset of all logic, placement and routing of
     * the input designs.
     */
    public static Design mergeDesigns(Supplier<AbstractDesignMerger> merger, Design...designs) {
        Design result = null;
        for (Design design : designs) {
            if (result == null) {
                result = design;
            } else {
                result = mergeDesigns(result, design, merger.get());
            }
        }

        result.getNetlist().resetParentNetMap();
        DesignTools.makePhysNetNamesConsistent(result);
        return result;
    }

    /**
     * Searches recursively in the given input directory for DCPs and presents the set of those
     * DCPs to MergeDesigns.mergeDesigns() with the default set of options.
     * @param args [0]=Input directory of source DCPs to search recursively,
     * [1]=Merged DCP output filename and an optional
     * [2]=An optional regular expression string to apply to DCPs found in [0].
     * @throws InterruptedException
     */
    public static void main(String[] args) throws InterruptedException {
        if (args.length != 2 && args.length != 3) {
            System.out.println("Usage: <dir with DCPs> <merged output DCP filename> [dcp filter regex]");
            return;
        }
        String dcpRegex = args.length == 3 ? args[2] : ".*\\.dcp";
        CodePerfTracker t = new CodePerfTracker("Merge Designs");

        Path start = Paths.get(args[0]);
        List<File> dcps = null;
        try (Stream<Path> stream = Files.walk(start, Integer.MAX_VALUE)) {
            dcps = stream
                    .map(p -> p.toFile())
                    .filter(p -> p.isFile() && p.getAbsolutePath().matches(dcpRegex))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
        }
        System.out.println("Merging DCPs:");
        for (File f : dcps) {
            System.out.println("  " + f.getAbsolutePath());
        }

        Design[] designs = new Design[dcps.size()];
        for (int i=0; i < designs.length; i++) {
            t.start("Read DCP " + i);
            designs[i] = Design.readCheckpoint(dcps.get(i).toPath(), CodePerfTracker.SILENT);
            t.stop();
        }

        t.start("Merge DCPs");
        Design merged = mergeDesigns(designs);
        t.stop().start("Write DCP");
        merged.writeCheckpoint(args[1], CodePerfTracker.SILENT);
        t.stop().printSummary();
    }
}
