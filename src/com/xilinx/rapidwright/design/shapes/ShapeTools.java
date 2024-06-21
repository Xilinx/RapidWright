/*
 * Copyright (c) 2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development
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

package com.xilinx.rapidwright.design.shapes;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;

/**
 * Collection of APIs to handle netlist shapes to be used for placement.
 */
public class ShapeTools {

    private static final boolean SOURCE_SHAPES_FROM_FILE = true;

    private static Set<String> chainPrims;

    static {
        chainPrims = new HashSet<>();
        chainPrims.add(Unisim.CARRY4.name());
        chainPrims.add(Unisim.CARRY8.name());
        chainPrims.add(Unisim.LOOKAHEAD8.name());
        chainPrims.add(Unisim.DSP48.name());
        chainPrims.add(Unisim.DSP48A.name());
        chainPrims.add(Unisim.DSP48A1.name());
        chainPrims.add(Unisim.DSP48E.name());
        chainPrims.add(Unisim.DSP48E1.name());
        chainPrims.add(Unisim.DSP48E2.name());
        chainPrims.add(Unisim.DSP48E5.name());
        chainPrims.add(Unisim.DSP58.name());
        chainPrims.add(Unisim.DSP58C.name());

        chainPrims.add(Unisim.RAMB18.name());
        chainPrims.add(Unisim.RAMB18E1.name());
        chainPrims.add(Unisim.RAMB18E2.name());
        chainPrims.add(Unisim.RAMB18E5.name());

    }

    public static List<Shape> extractShapes(Design design, Path optionalShapeFile) {
        EDIFNetlist netlist = design.getNetlist();

        if (SOURCE_SHAPES_FROM_FILE) {
            // For now, we'll get the shapes from a shapes file
            Path shapeFile = null;
            if (optionalShapeFile == null || !Files.exists(optionalShapeFile)) {
                // Generate shapes file
                final Path workdir = FileSystems.getDefault()
                        .getPath("vivadoToolsWorkdir" + FileTools.getUniqueProcessAndHostID());
                File workdirHandle = new File(workdir.toString());
                workdirHandle.mkdirs();

                Path outputLog = workdir.resolve("outputLog.log");
                Path tcl = workdir.resolve("run.tcl");
                Path dcp = workdir.resolve("design.dcp");
                shapeFile = workdir.resolve("shapes.txt");

                design.writeCheckpoint(dcp);

                List<String> cmds = new ArrayList<>();
                cmds.add("open_checkpoint " + dcp);
                cmds.add("set_param place.debugShape " + shapeFile);
                cmds.add("place_design -directive Quick");
                FileTools.writeLinesToTextFile(cmds, tcl.toString());
                VivadoTools.runTcl(outputLog, "source " + tcl, true);
            } else {
                shapeFile = optionalShapeFile;
            }

            List<Shape> shapes = readDebugShapeFile(design, shapeFile);
            netlist.setShapes(shapes);
            return shapes;
        }

        // TODO TODO TODO - WIP
        List<Shape> shapes = new ArrayList<>();

        // Identify shape anchors
        EDIFLibrary macros = Design.getMacroPrimitives(design.getDevice().getSeries());
        Set<EDIFHierCellInst> macroInsts = new HashSet<>();
        Set<EDIFHierCellInst> chainInsts = new HashSet<>();
        for (EDIFHierCellInst inst : design.getNetlist().getAllLeafHierCellInstances()) {
            if (chainPrims.contains(inst.getCellName())) {
                chainInsts.add(inst);
            } else {
                EDIFHierCellInst parent = inst.getParent();
                if (macros.containsCell(parent.getCellName())) {
                    macroInsts.add(parent);
                }
            }
        }

        Map<String, Shape> cellToShape = new HashMap<>();
        for (EDIFHierCellInst i : macroInsts) {
            Shape shape = createShapeFromMacro(design, i);
            for (Cell cell : shape.getCells()) {
                Shape prev = cellToShape.put(cell.getName(), shape);
                if (prev != null) {
                    throw new RuntimeException("Unexpected overlap");
                }
            }
            shapes.add(shape);
            for (EDIFCellInst child : i.getInst().getCellType().getCellInsts()) {
                EDIFHierCellInst childInst = i.getChild(child.getName());
            }
        }


        for (EDIFHierCellInst i : chainInsts) {
            Unisim type = Unisim.valueOf(i.getCellName());
            Shape shape = new Shape();
            switch (type) {
            case CARRY8:
                Cell carry8 = shape.addCell(i.toString(), design, 0, 0, "CARRY8", type.name());

                for (EDIFPortInst pi : i.getInst().getPortInsts()) {
                    if (pi.getName().endsWith("X"))
                        continue;
                    EDIFNet net = pi.getNet();
                    if (net != null) {
                        EDIFHierNet hierNet = new EDIFHierNet(i, net);
                        for (EDIFHierPortInst conn : netlist.getPhysicalNetPinMap().get(hierNet)) {
                            if (conn.getPortInst().equals(pi))
                                continue;
                            EDIFHierCellInst neighbor = conn.getHierarchicalInst();
                            // shape.addCell(i.toString(), design, 0, 0, , neighbor.getCellName());
                        }
                    }
                }
                break;
            default:
                throw new RuntimeException("ERROR: Unhandled chain-type instance: " + type);
            }
        }

        return shapes;
    }
    
    public static Shape createShapeFromMacro(Design design, EDIFHierCellInst macro) {
        Unisim cellType = Unisim.valueOf(macro.getCellName());
        Shape shape = new Shape();
        switch (cellType) {
        case LUT6_2:
            shape.setHeight(0);
            shape.setWidth(0);
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String belName = "A" + i.getCellName();
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            break;
        case RAM64M:
            shape.setHeight(0);
            shape.setWidth(0);
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String belName = i.getName().charAt(i.getName().length() - 1) + "6LUT";
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            break;
        case RAM32M:
            shape.setHeight(0);
            shape.setWidth(0);
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String instName = i.getName();
                String suffix = instName.endsWith("_D1") ? "6LUT" : "5LUT";
                String belName = instName.charAt(3) + suffix;
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            break;
        default:
            throw new RuntimeException("ERROR: Unsupported shape for macro " + cellType);
        }

        return shape;
    }

    public static List<Shape> readDebugShapeFile(Design design, Path file) {
        List<Shape> shapes = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file.toString()))) {
            String line = null;
            boolean foundStart = false;
            int shapeCount = -1;
            Shape curr = null;
            while((line = br.readLine()) != null) {
                if (!foundStart) {
                    if (line.contains(" shape(s):")) {
                        shapeCount = Integer.parseInt(line.substring(0, line.indexOf(' ')));
                        foundStart = true;
                    }
                    continue;
                }
                if (line.trim().length() == 0) continue;
                if (curr == null) {
                    curr = new Shape();
                }
                if (line.startsWith("(")) {
                    int commaIdx = line.indexOf(',');
                    String relSiteName = line.substring(1, commaIdx);
                    int yIdx = relSiteName.lastIndexOf('Y');
                    int dx = Integer.parseInt(relSiteName.substring(relSiteName.lastIndexOf("_X") + 2, yIdx));
                    int dy = Integer.parseInt(relSiteName.substring(yIdx + 1));
                    String belName = line.substring(commaIdx + 1, line.indexOf(')')).trim();
                    String cellName = line.substring(line.indexOf('\t') + 1, line.indexOf(" ("));
                    String cellType = line.substring(line.lastIndexOf('(') + 1, line.lastIndexOf(')'));
                    curr.addCell(cellName, design, dx, dy, belName, cellType);
                } else if (line.startsWith("WxH: ")) {
                    int xIdx = line.lastIndexOf('x');
                    int width = Integer.parseInt(line.substring(line.indexOf(' ') + 1, xIdx));
                    int height = Integer.parseInt(line.substring(xIdx + 1));
                    curr.setHeight(height);
                    curr.setWidth(width);
                    shapes.add(curr);
                    curr = null;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return shapes;
    }

    public static void main(String[] args) {
        Design d = Design.readCheckpoint("/wrk/hdstaff/clavin/workspace2/RapidWright/test/RapidWrightDCP/picoblaze_ooc_X10Y235_2022_1.dcp");
//        List<Shape> shapes = readDebugShapeFile(d,
        // Paths.get("/wrk/hdstaff/clavin/workspace2/RapidWrightInt/picoblaze_shapes.txt"));
        // System.out.println(shapes.size());
        extractShapes(d, Paths.get("/wrk/hdstaff/clavin/workspace2/RapidWright/vivadoToolsWorkdir61306@xsjclavin40x_1/shapes.txt"));
    }
}
