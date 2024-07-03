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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
import com.xilinx.rapidwright.examples.PipelineGenerator;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.interchange.LogNetlistWriter;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.VivadoTools;

/**
 * Collection of APIs to handle netlist shapes to be used for placement.
 */
public class ShapeTools {

    private static final boolean SOURCE_SHAPES_FROM_FILE = true;

    private static Set<String> chainPrims;

    private static Map<String, String> carryPinMap;

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

        carryPinMap = new HashMap<>();
        for (int i = 0; i < 8; i++) {
            char letter = (char) ('A' + i);
            carryPinMap.put("S[" + i + "]", letter + "6LUT");
            carryPinMap.put("DI[" + i + "]", letter + "5LUT");
            carryPinMap.put("O[" + i + "]", letter + "FF");
            carryPinMap.put("CO[" + i + "]", letter + "FF2");
        }

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
                design.getNetlist().expandMacroUnisims(design.getDevice().getSeries());

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
            sortShapesByBELName(shapes);
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

        Map<EDIFHierNet, List<EDIFHierPortInst>> physicalNetPinMap = netlist.getPhysicalNetPinMap();
        for (EDIFHierCellInst i : chainInsts) {
            Unisim type = Unisim.valueOf(i.getCellName());
            Shape shape = new Shape();
            shapes.add(shape);
            switch (type) {
            case CARRY8:
                shape.addCell(i.toString(), design, 0, 0, "CARRY8", type.name());
                for (EDIFPortInst pi : i.getInst().getPortInsts()) {
                    EDIFNet net = pi.getNet();
                    if (net != null) {
                        EDIFHierNet hierNet = netlist.getParentNet(new EDIFHierNet(i.getParent(), net));
                        for (EDIFHierPortInst conn : physicalNetPinMap.get(hierNet)) {
                            if (conn.getPortInst().equals(pi))
                                continue;
                            String connType = conn.getCellType().getName();
                            if ((pi.getName().startsWith("DI") || pi.getName().startsWith("S"))
                                    && !connType.contains("LUT")) {
                                continue;
                            }
                            String belName = carryPinMap.get(pi.getName());
                            if (belName != null) {
                                shape.addCell(conn.getFullHierarchicalInstName(), design, 0, 0, belName,
                                        conn.getCellType().getName());

                            }
                        }
                    }
                }
                shape.addTag("Carry-chain");
                break;
            default:
                throw new RuntimeException("ERROR: Unhandled chain-type instance: " + type);
            }
        }
        sortShapesByBELName(shapes);
        return shapes;
    }
    
    public static Shape createShapeFromMacro(Design design, EDIFHierCellInst macro) {
        Unisim cellType = Unisim.valueOf(macro.getCellName());
        Shape shape = new Shape();
        switch (cellType) {
        case LUT6_2:
            shape.setHeight(1);
            shape.setWidth(1);
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String belName = "A" + (i.getCellName().charAt(i.getCellName().length() - 1) == '6' ? "6LUT" : "5LUT");
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            shape.addTag("LUTNM");
            break;
        case RAM64M:
            shape.setHeight(1);
            shape.setWidth(1);
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String belName = i.getName().charAt(i.getName().length() - 1) + "6LUT";
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            shape.addTag("Cluster");
            break;
        case RAM32M:
            shape.setHeight(1);
            shape.setWidth(1);
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String instName = i.getName();
                String suffix = instName.endsWith("_D1") ? "6LUT" : "5LUT";
                String belName = instName.charAt(3) + suffix;
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            shape.addTag("Cluster");
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
                    String belName = line.substring(commaIdx + 1, line.indexOf(')')).replace(", optional", "").trim();
                    String cellName = line.substring(line.indexOf('\t') + 1, line.indexOf(" ("));
                    String cellType = line.substring(line.lastIndexOf('(') + 1, line.lastIndexOf(')'));
                    curr.addCell(cellName, design, dx, dy, belName, cellType);
                } else if (line.startsWith("Tag(s): ")) {
                    String[] tags = line.split("\\s+");
                    Set<String> tagNames = new HashSet<>();
                    for (int i = 1; i < tags.length; i++) {
                        tagNames.add(tags[i]);
                    }
                    curr.setTags(tagNames);
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

    public static void sortShapesByBELName(List<Shape> shapes) {
        for (Shape s : shapes) {
            Map<String, Cell> cellOrderStrings = new HashMap<>();
            for (Entry<Cell, ShapeLocation> e : s.getCellMap().entrySet()) {
                ShapeLocation loc = e.getValue();
                String key = loc.getDx() + "_" + loc.getDy() + "_" + loc.getBelName();
                cellOrderStrings.put(key, e.getKey());
            }
            String[] keys = cellOrderStrings.keySet().toArray(new String[cellOrderStrings.size()]);
            Arrays.sort(keys);
            Map<Cell, ShapeLocation> orderedMap = new LinkedHashMap<>();
            for (String key : keys) {
                Cell cell = cellOrderStrings.get(key);
                orderedMap.put(cell, s.getCellMap().get(cell));
            }
            s.setCellMap(orderedMap);
        }

    }
}
