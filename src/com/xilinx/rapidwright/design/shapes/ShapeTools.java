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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.placer.dreamplacefpga.DREAMPlaceFPGA;
import com.xilinx.rapidwright.rwroute.RWRoute;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.VivadoTools;

/**
 * Collection of APIs to handle netlist shapes to be used for placement.
 */
public class ShapeTools {

    private static final boolean SOURCE_SHAPES_FROM_FILE = false;

    private static Set<String> chainPrims;

    private static Map<String, String> carryPinMap;

    private static String[] writeAddressPinNames;

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

        writeAddressPinNames = new String[9];
        for (int i = 1; i <= 9; i++) {
            writeAddressPinNames[i - 1] = "WA" + i;
        }
    }

    public static Collection<Shape> getShapesFromFile(Design design, Path optionalShapeFile) {
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
        design.getNetlist().setShapes(shapes);
        return shapes;

    }

    public static Collection<Shape> extractShapes(Design design, Path optionalShapeFile) {
        EDIFNetlist netlist = design.getNetlist();

        if (SOURCE_SHAPES_FROM_FILE) {
            // For now, we'll get the shapes from a shapes file
            return getShapesFromFile(design, optionalShapeFile);
        }

        List<Shape> shapes = new ArrayList<>();
        Map<String, Shape> cellShapeMap = new HashMap<>();

        // Identify shape anchors
        EDIFLibrary macros = Design.getMacroPrimitives(design.getDevice().getSeries());
        Set<EDIFHierCellInst> macroInsts = new HashSet<>();
        Set<EDIFHierCellInst> chainInsts = new HashSet<>();
        Map<String, EDIFHierCellInst> muxF9s = new HashMap<>();
        Map<String, EDIFHierCellInst> muxF8s = new HashMap<>();
        Map<String, EDIFHierCellInst> muxF7s = new HashMap<>();
        for (EDIFHierCellInst inst : design.getNetlist().getAllLeafHierCellInstances()) {
            String instName = inst.getCellName();
            if (chainPrims.contains(instName)) {
                chainInsts.add(inst);
            } else if (instName.equals("MUXF9")) {
                muxF9s.put(inst.getFullHierarchicalInstName(), inst);
            } else if (instName.equals("MUXF8")) {
                muxF8s.put(inst.getFullHierarchicalInstName(), inst);
            } else if (instName.equals("MUXF7")) {
                muxF7s.put(inst.getFullHierarchicalInstName(), inst);
            } else {
                EDIFHierCellInst parent = inst.getParent();
                if (macros.containsCell(parent.getCellName())) {
                    macroInsts.add(parent);
                }
            }
        }

        for (EDIFHierCellInst i : macroInsts) {
            Shape shape = createShapeFromMacro(design, i);
            for (Cell cell : shape.getCells()) {
                Shape prev = cellShapeMap.put(cell.getName(), shape);
                if (prev != null) {
                    throw new RuntimeException("Unexpected overlap");
                }
            }
            shapes.add(shape);
        }

        List<EDIFHierPortInst> chainStarts = new ArrayList<>();
        Map<EDIFHierNet, List<EDIFHierPortInst>> physicalNetPinMap = netlist.getPhysicalNetPinMap();
        for (EDIFHierCellInst i : chainInsts) {
            Unisim type = Unisim.valueOf(i.getCellName());
            Shape shape = new Shape();
            shapes.add(shape);
            switch (type) {
            case CARRY8:
                Cell cell = shape.addCell(i.toString(), design, 0, 0, "CARRY8", type.name());
                cellShapeMap.put(cell.getName(), shape);
                Map<String, EDIFHierPortInst> connMap = new HashMap<>();
                EDIFHierPortInst chainConn = null;
                boolean hasCin = false;
                for (EDIFPortInst pi : i.getInst().getPortInsts()) {
                    EDIFNet net = pi.getNet();
                    if (net != null) {
                        EDIFHierNet hierNet = netlist.getParentNet(new EDIFHierNet(i.getParent(), net));
                        for (EDIFHierPortInst conn : physicalNetPinMap.get(hierNet)) {
                            if (conn.getPortInst().equals(pi))
                                continue;
                            String connType = conn.getCellType().getName();
                            String pinName = pi.getName();
                            if (pinName.equals("CO[7]")) {
                                chainConn = i.getPortInst(pi.getName());
                                continue;
                            } else if ((pinName.startsWith("DI") || pinName.startsWith("S"))
                                    && !connType.contains("LUT")) {
                                continue;
                            } else if ((pinName.startsWith("O") || pinName.startsWith("CO"))
                                    && !DesignTools.unisimFlipFlopTypes.contains(connType)) {
                                continue;
                            } else if (pinName.equals("CIN")) {
                                hasCin = true;
                                continue;
                            }
                            connMap.put(pi.getName(), conn);
                        }
                    }
                }
                if (!hasCin && chainConn != null) {
                    chainStarts.add(chainConn);
                }
                for (Entry<String, EDIFHierPortInst> e : connMap.entrySet()) {
                    EDIFHierPortInst conn = e.getValue();
                    if (e.getKey().startsWith("DI")) {
                        // Check the corresponding S port to see if the source cell is
                        // placement-compatible
                        String sum = e.getKey().replace("DI", "S");
                        EDIFHierPortInst sumConn = connMap.get(sum);
                        if (sumConn != null && !lutPlacementCompatible(sumConn, conn, cellShapeMap)) {
                            continue;
                        }
                    }

                    String belName = carryPinMap.get(e.getKey());
                    if (belName != null) {
                        int dx = 0;
                        int dy = 0;
                        if (belName.equals("HFF2") && conn.getCellType().getName().equals("CARRY8")) {
                            continue;
                        }
                        String cellName = conn.getFullHierarchicalInstName();
                        Shape testCollision = cellShapeMap.get(cellName);
                        if (testCollision != null && testCollision.hasTag(ShapeTag.CARRY_CHAIN)) {
                            continue;
                        }
                        Cell c = shape.addCell(cellName, design, dx, dy, belName, conn.getCellType().getName());
                        Shape overlap = cellShapeMap.put(c.getName(), shape);
                        if (overlap != null && overlap != shape) {
                            shapes.remove(overlap);
                            // re orient LUT6_2 cells related to the CARRY (A*LUT -> approprate LUT)
                            for (ShapeTag tag : overlap.getTags()) {
                                shape.addTag(tag);
                            }
                            for (Entry<Cell, ShapeLocation> e2 : overlap.getCellMap().entrySet()) {
                                if (overlap.getTags().size() == 1 && overlap.getTags().contains(ShapeTag.LUTNM)) {
                                    // This is a LUT6_2, we need to align it to where the CARRY expects it to be
                                    assert (belName.endsWith("LUT"));
                                    e2.getValue()
                                            .setBelName(belName.charAt(0) + e2.getValue().getBelName().substring(1));
                                }
                                shape.getCellMap().put(e2.getKey(), e2.getValue());
                                cellShapeMap.put(e2.getKey().getName(), shape);

                            }
                        }
                    }
                }
                shape.addTag(ShapeTag.CARRY_CHAIN);
                break;
            case RAMB18E2:
                EDIFHierPortInst portInst = i.getPortInst("CASDIMUXA");
                if (portInst != null && portInst.getNet() != null) {
                    // TODO handle RAMB18E2 cascades
                }
                break;
            default:
                throw new RuntimeException("ERROR: Unhandled chain-type instance: " + type + ", instance " + i);
            }
        }

        // Combine chain shapes into single shape
        for (EDIFHierPortInst cout : chainStarts) {
            EDIFHierPortInst currCout = cout;
            Shape currShape = cellShapeMap.get(cout.getFullHierarchicalInstName());
            int currDy = 0;
            while (currCout != null) {
                String currCoutName = currCout.toString();
                for (EDIFHierPortInst conn : physicalNetPinMap.get(currCout.getHierarchicalNet())) {
                    if (conn.toString().equals(currCoutName))
                        continue;
                    currCout = conn.getHierarchicalInst().getChild(conn.getPortInst().getCellInst())
                            .getPortInst("CO[7]");
                    String nextCarry = conn.getFullHierarchicalInstName();
                    Shape nextShape = cellShapeMap.get(nextCarry);
                    if (nextShape != null) {
                        currShape.getTags().addAll(nextShape.getTags());
                        for (Entry<Cell, ShapeLocation> e : nextShape.getCellMap().entrySet()) {
                            ShapeLocation loc = e.getValue();
                            currDy++;
                            loc.setDy(currDy);
                            currShape.getCellMap().put(e.getKey(), loc);
                            cellShapeMap.put(e.getKey().getName(), currShape);
                        }
                        shapes.remove(nextShape);
                        currShape.setHeight(currShape.getHeight() + 1);
                    }
                }
            }
        }

        List<Shape> clusterShapes = shapes.stream().filter(s -> s.getTags().contains(ShapeTag.CLUSTER))
                .collect(Collectors.toList());
        Set<Shape> merged = new HashSet<>();
        // Cluster LUTRAMs into single SLICEs when sharing address lines
        nextShape: for (Shape shape : clusterShapes) {
            if (merged.contains(shape)) continue;
            Shape other = null;
            for (Cell c : shape.getCells()) {
                for (String waPin : writeAddressPinNames) {
                    String pin = c.getLogicalPinMapping(waPin);
                    if (pin == null)
                        continue;
                    EDIFHierPortInst portInst = c.getEDIFHierCellInst().getPortInst(pin);
                    if (portInst != null) {
                        EDIFHierNet net = portInst.getHierarchicalNet();
                        if (net.getNet().isGND() || net.getNet().isVCC()) {
                            continue;
                        }
                        for (EDIFHierPortInst otherPort : net.getLeafHierPortInsts()) {
                            if (portInst.equals(otherPort) || otherPort.isOutput())
                                continue;
                            Shape test = cellShapeMap.get(otherPort.getFullHierarchicalInstName());
                            if (test == shape)
                                continue;
                            if (test != null && test.getTags().contains(ShapeTag.CLUSTER)) {
                                if (other != null) {
                                    if (other != test) {
                                        continue nextShape;
                                    }
                                }
                                other = test;
                            }
                        }
                    }
                }
            }
            if (other != null) {
                // We should join these shapes into one
                shape.getTags().addAll(other.getTags());
                char highestLUTLetter = shape.getLargestLUTLetter();
                int lutOffset = highestLUTLetter - ('A' - 1);
                for (Entry<Cell, ShapeLocation> e : other.getCellMap().entrySet()) {
                    ShapeLocation loc = e.getValue();
                    String belName = loc.getBelName();
                    assert (belName.contains("LUT"));
                    belName = ((char) (belName.charAt(0) + lutOffset)) + belName.substring(1);
                    loc.setBelName(belName);
                    shape.getCellMap().put(e.getKey(), loc);
                    cellShapeMap.put(e.getKey().getName(), shape);
                }
                shapes.remove(other);
                merged.add(shape);
                merged.add(other);

                // Look for any flip-flops attached to LUTRAM and merge into shape
                for (Entry<Cell, ShapeLocation> e : new HashMap<>(shape.getCellMap()).entrySet()) {
                    EDIFHierPortInst output = e.getKey().getEDIFHierCellInst().getPortInst("O");
                    // If there is high fanout, let the placer choose
                    Collection<EDIFHierPortInst> pins = output.getHierarchicalNet().getLeafHierPortInsts();
                    if (pins.size() < 3) {
                        for (EDIFHierPortInst pin : pins) {
                            if (pin.isOutput())
                                continue;
                            if (DesignTools.unisimFlipFlopTypes.contains(pin.getCellType().getName())) {
                                String ffBELName = e.getValue().getBelName().replace("6LUT", "FF").replace("5LUT",
                                        "FF2");
                                shape.addCell(pin.getFullHierarchicalInstName(), design, e.getValue().getDx(),
                                        e.getValue().getDy(), ffBELName, pin.getCellType().getName());
                            }
                        }
                    }
                }
            }
        }
        
        for (Entry<String, EDIFHierCellInst> e : muxF9s.entrySet()) {
            Shape shape = new Shape();
            shape.addCell(e.getKey(), design, 0, 0, "F9MUX", "F9MUX");
            shape.addTag(ShapeTag.MUXF9);
            for (int i = 0; i < 2; i++) {
                EDIFHierPortInst f8Driver = e.getValue().getPortInstDriver("I" + Integer.toString(i));
                assert (f8Driver.getCellType().getName().equals("F8MUX"));
                Cell muxF8 = shape.addCell(f8Driver.getFullHierarchicalInstName(), design, 0, 0,
                        i == 0 ? "F8MUX_TOP" : "F8MUX_BOT", "F8MUX");
                muxF8s.remove(muxF8.getName());
                buildMuxF8Shape(design, muxF8, shape, i == 0, muxF7s);
            }
            shapes.add(shape);
        }

        for (Entry<String, EDIFHierCellInst> e : muxF8s.entrySet()) {
            Shape shape = new Shape();
            Cell muxF8 = shape.addCell(e.getKey(), design, 0, 0, "F8MUX_BOT", "F8MUX");
            shape.addTag(ShapeTag.MUXF8);
            buildMuxF8Shape(design, muxF8, shape, false, muxF7s);
            shapes.add(shape);
        }

        for (Entry<String, EDIFHierCellInst> e : muxF7s.entrySet()) {
            Shape shape = new Shape();
            Cell muxF7 = shape.addCell(e.getKey(), design, 0, 0, "F7MUX_AB", "F7MUX");
            shape.addTag(ShapeTag.MUXF7);
            buildMuxF7Shape(design, muxF7, shape, "F7MUX_AB");
            shapes.add(shape);
        }

        sortShapesByBELName(shapes);
        return shapes;
    }
    
    private static void buildMuxF8Shape(Design design, Cell muxf8, Shape shape, boolean isTop,
            Map<String, EDIFHierCellInst> muxf7s) {

        EDIFHierPortInst ffSink = checkAndGetSingleSinkFlop(muxf8);
        if (ffSink != null) {
            shape.addCell(ffSink.getFullHierarchicalInstName(), design, 0, 0, isTop ? "GFF" : "CFF",
                    ffSink.getCellType().getName());
        }
        for (int j = 0; j < 2; j++) {
            EDIFHierPortInst f7Driver = muxf8.getEDIFHierCellInst().getPortInstDriver("I" + Integer.toString(j));
            assert (f7Driver.getCellType().getName().equals("F7MUX"));
            String belName = "F7MUX_" + (isTop ? (j == 0 ? "GH" : "EF") : (j == 0 ? "CD" : "AB"));
            Cell muxf7 = shape.addCell(f7Driver.getFullHierarchicalInstName(), design, 0, 0, belName, "F7MUX");
            muxf7s.remove(muxf7.getName());
            buildMuxF7Shape(design, muxf7, shape, belName);
        }
    }

    private static void buildMuxF7Shape(Design design, Cell muxf7, Shape shape, String f7BELName) {
        EDIFHierPortInst ffSink = checkAndGetSingleSinkFlop(muxf7);
        if (ffSink != null) {
            shape.addCell(ffSink.getFullHierarchicalInstName(), design, 0, 0, f7BELName.charAt(0) + "FF",
                    ffSink.getCellType().getName());
        }
        for (int k = 0; k < 2; k++) {
            EDIFHierPortInst lutDriver = muxf7.getEDIFHierCellInst().getPortInstDriver("I" + Integer.toString(k));
            String lutBELName = f7BELName.charAt(6 + k) + "6LUT";
            String cellType = lutDriver.getCellType().getName();
            shape.addCell(lutDriver.getFullHierarchicalInstName(), design, 0, 0, lutBELName, cellType);
            if (cellType.equals("SRLC32E")) {
                shape.addTag(ShapeTag.SRL);
            }
        }
    }

    /**
     * Checks if the provided mux drives exactly one flip flop. If it does, it will
     * return it, otherwise it returns null.
     * 
     * @return The single flip flop input driven by the provided mux, null
     *         otherwise.
     */
    private static EDIFHierPortInst checkAndGetSingleSinkFlop(Cell mux) {
        EDIFHierNet outNet = mux.getEDIFHierCellInst().getPortInst("O").getHierarchicalNet();

        if (outNet != null) {
            List<EDIFHierPortInst> portInsts = outNet.getLeafHierPortInsts(false, true);
            if (portInsts.size() == 1) {
                EDIFHierPortInst sink = portInsts.get(0);
                String cellType = sink.getCellType().getName();
                if (DesignTools.unisimFlipFlopTypes.contains(cellType)) {
                    return sink;
                }
            }
        }
        return null;
    }

    private static boolean lutPlacementCompatible(EDIFHierPortInst sum, EDIFHierPortInst di,
            Map<String, Shape> cellShapeMap) {
        String diType = di.getCellType().getName();
        String sumType = sum.getCellType().getName();
        Shape sumShape = cellShapeMap.get(sum.getFullHierarchicalInstName());
        Shape diShape = cellShapeMap.get(di.getFullHierarchicalInstName());
        if (sumShape != null && diShape != null && sumShape != diShape) {
            return false;
        }
        if (sumShape != null || diShape != null) {
            return false;
        }
        if (diType.equals("LUT6") && sumType.equals("LUT6")) {
            return false;
        }
        return true;
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
            shape.addTag(ShapeTag.LUTNM);
            break;
        case RAM64M:
            shape.setHeight(1);
            shape.setWidth(1);
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String belName = i.getName().charAt(i.getName().length() - 1) + "6LUT";
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            shape.addTag(ShapeTag.CLUSTER);
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
            shape.addTag(ShapeTag.CLUSTER);
            break;
        case RAM32M16:
            shape.setHeight(1);
            shape.setWidth(1);
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String instName = i.getName();
                String suffix = instName.endsWith("_D1") ? "6LUT" : "5LUT";
                String belName = instName.charAt(3) + suffix;
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            shape.addTag(ShapeTag.RAM32M16);
            break;
        case RAM32X1D:
            shape.setHeight(1);
            shape.setWidth(1);
            // TODO - These macros can be combined into a single if shared inputs
            for (EDIFCellInst i : macro.getCellType().getCellInsts()) {
                String instName = i.getName();
                String belName = instName.endsWith("SP") ? "H6LUT" : "G6LUT";
                shape.addCell(macro.getChild(i).toString(), design, 0, 0, belName, i.getCellName());
            }
            shape.addTag(ShapeTag.CLUSTER);
            break;
        default:
            throw new RuntimeException("ERROR: Unsupported shape for macro " + cellType + ", instance " + macro);
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
                    Set<ShapeTag> tagNames = new HashSet<>();
                    for (int i = 1; i < tags.length; i++) {
                        tagNames.add(ShapeTag.values.get(tags[i]));
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

    public static void sortShapesByBELName(Collection<Shape> shapes) {
        for (Shape s : shapes) {
            Map<String, Cell> cellOrderStrings = new HashMap<>();
            for (Entry<Cell, ShapeLocation> e : s.getCellMap().entrySet()) {
                ShapeLocation loc = e.getValue();
                String key = loc.getDx() + "_" + loc.getDy() + "_" + loc.getBelName();
                Cell collision = cellOrderStrings.put(key, e.getKey());
                if (collision != null) {
                    throw new RuntimeException("ERROR: Key collision with key: " 
                        + key + ", cell=" + collision + "; In shape:\n" + s);
                }
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
