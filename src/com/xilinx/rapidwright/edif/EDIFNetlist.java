/*
 *
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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
package com.xilinx.rapidwright.edif;

import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IOStandard;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;
import com.xilinx.rapidwright.util.ParallelDCPInput;
import com.xilinx.rapidwright.util.ParallelDCPOutput;
import com.xilinx.rapidwright.util.ParallelismTools;

/**
 * Top level object for a (logical) EDIF netlist.
 *
 * Created on: May 11, 2017
 */
public class EDIFNetlist extends EDIFName {

    private Map<String, EDIFLibrary> libraries;

    private EDIFDesign design;

    private EDIFCellInst topCellInstance = null;

    private List<String> comments;

    private Map<String,EDIFPropertyValue> metax;

    private Map<EDIFHierNet,EDIFHierNet> parentNetMap;
    private Map<String,String> parentNetMapNames;

    private Map<EDIFHierNet, List<EDIFHierPortInst>> physicalNetPinMap;
    private List<EDIFHierPortInst> physicalGndPins;
    private List<EDIFHierPortInst> physicalVccPins;

    protected int nameSpaceUniqueCount = 0;

    private transient Device device;

    private Set<String> primsToRemoveOnCollapse = new HashSet<String>();

    private String origDirectory;

    private List<String> encryptedCells;

    private boolean trackCellChanges = false;

    private Map<EDIFCell, List<EDIFChange>> modifiedCells = null;

    private boolean DEBUG = false;

    /**
     * Map that stores prim to macro expansions conditional based on IOStandards
     * (Start Prim to End Macro (if set IOStandard is in set))
     */
    public static final Map<Series, Map<String,Pair<String,EnumSet<IOStandard>>>> macroExpandExceptionMap;
    /**
     * Reverse map that stores macro to prim collapse conditional based on IOStandards
     * (Macro to Prim (if set IOStandard is in set))
     */
    public static final Map<Series, Map<String,Pair<String,EnumSet<IOStandard>>>> macroCollapseExceptionMap;

    public static final String IOSTANDARD_PROP = "IOStandard";

    public static final EDIFPropertyValue DEFAULT_PROP_VALUE = new EDIFPropertyValue(IOStandard.DEFAULT.name(), EDIFValueType.STRING);

    static {
        EnumSet<IOStandard> obufExpansion = EnumSet.of(
                                                IOStandard.BLVDS_25,
                                                IOStandard.DEFAULT,
                                                IOStandard.DIFF_HSTL_I,
                                                IOStandard.DIFF_HSTL_I_12,
                                                IOStandard.DIFF_HSTL_I_18,
                                                IOStandard.DIFF_HSTL_I_DCI,
                                                IOStandard.DIFF_HSTL_I_DCI_12,
                                                IOStandard.DIFF_HSTL_I_DCI_18,
                                                IOStandard.DIFF_HSTL_II,
                                                IOStandard.DIFF_HSTL_II_18,
                                                IOStandard.DIFF_HSUL_12,
                                                IOStandard.DIFF_HSUL_12_DCI,
                                                IOStandard.DIFF_MOBILE_DDR,
                                                IOStandard.DIFF_POD10,
                                                IOStandard.DIFF_POD10_DCI,
                                                IOStandard.DIFF_POD12,
                                                IOStandard.DIFF_POD12_DCI,
                                                IOStandard.DIFF_SSTL12,
                                                IOStandard.DIFF_SSTL12_DCI,
                                                IOStandard.DIFF_SSTL135,
                                                IOStandard.DIFF_SSTL135_DCI,
                                                IOStandard.DIFF_SSTL135_II,
                                                IOStandard.DIFF_SSTL135_R,
                                                IOStandard.DIFF_SSTL15,
                                                IOStandard.DIFF_SSTL15_DCI,
                                                IOStandard.DIFF_SSTL15_II,
                                                IOStandard.DIFF_SSTL15_R,
                                                IOStandard.DIFF_SSTL18_I,
                                                IOStandard.DIFF_SSTL18_I_DCI,
                                                IOStandard.DIFF_SSTL18_II,
                                                IOStandard.MIPI_DPHY_DCI
                                            );

        macroExpandExceptionMap = new HashMap<>();
        macroCollapseExceptionMap = new HashMap<>();
        for (Series s : Series.values()) {
            Map<String,Pair<String,EnumSet<IOStandard>>> seriesMacroExpandExceptionMap = new HashMap<>();
            Map<String,Pair<String,EnumSet<IOStandard>>> seriesMacroCollapseExceptionMap = new HashMap<>();

            if (s == Series.Versal) continue;
            // Prim -> Macro (when set IOStandard matches expansion set)
            seriesMacroExpandExceptionMap.put("OBUFDS", new Pair<>("OBUFDS_DUAL_BUF", obufExpansion));
            seriesMacroExpandExceptionMap.put("OBUFTDS", new Pair<>("OBUFTDS_DUAL_BUF", obufExpansion));
            seriesMacroExpandExceptionMap.put("OBUFTDS_DCIEN", new Pair<>("OBUFTDS_DCIEN_DUAL_BUF", obufExpansion));
            macroExpandExceptionMap.put(s, seriesMacroExpandExceptionMap);

            for (Entry<String,Pair<String,EnumSet<IOStandard>>> e : seriesMacroExpandExceptionMap.entrySet()) {
                Pair<String,EnumSet<IOStandard>> newPair = new Pair<>(e.getKey(), e.getValue().getSecond());
                seriesMacroCollapseExceptionMap.put(e.getValue().getFirst(), newPair);
            }
            macroCollapseExceptionMap.put(s, seriesMacroCollapseExceptionMap);
        }
    }

    /**
     * Map that stores EDIFCellInst to a set of EDIFPropertyValue-s for non-default
     * IOStandard values propagated from other parts of the netlist.
     * Anecdotally, it's been observed that Vivado propagates the IOStandard property
     * from the EDIFNet feeding a top-level output port to its driving EDIFCellInst
     * I/O buffer.
     * @see #getIOStandards(EDIFCellInst)
     */
    private Map<EDIFCellInst,Collection<EDIFPropertyValue>> cellInstIOStandardFallback;
    private final static Collection<EDIFPropertyValue> defaultPropValueAsCollection = Arrays.asList(DEFAULT_PROP_VALUE);


    public EDIFNetlist(String name) {
        super(name);
        init();
    }

    protected EDIFNetlist() {
        init();
    }

    /**
     * Creates an ordered list of cells such that each cell that appears
     * in the list only references cells that have already been seen in
     * the list. This is a requirement when exporting the EDIF to a file.
     * @param cells A set of cells to consider.
     * @param stable makes sure that the list is always the same for the same input
     * @return The ordered list.
     */
    public static List<EDIFCell> getValidCellExportOrder(Set<EDIFCell> cells, boolean stable) {
        Predicate<EDIFCell> recurseIf = cells::contains;
        return getValidCellExportOrder(cells, stable, recurseIf);
    }

    static List<EDIFCell> getValidCellExportOrder(Collection<EDIFCell> cellsToExport, boolean stable, Predicate<EDIFCell> recurseIf) {
        List<EDIFCell> visited = new ArrayList<>();
        Iterable<EDIFCell> cells;
        if (stable) {
            cells = cellsToExport.stream().sorted(Comparator.comparing(EDIFName::getName))::iterator;
        } else {
            cells = cellsToExport;
        }
        Set<EDIFCell> visitedSet = new HashSet<>();
        for (EDIFCell cell : cells) {
            getValidCellExportOrderVisit(cell, visited, visitedSet, stable, recurseIf);
        }

        return visited;
    }

    private static void getValidCellExportOrderVisit(EDIFCell cell, List<EDIFCell> visitedList, Set<EDIFCell> visitedSet, boolean stable, Predicate<EDIFCell> recurseIf) {
        if (!visitedSet.add(cell)) {
            return;
        }
        final Iterable<EDIFCellInst> cellInsts;
        if (stable) {
            cellInsts = cell.getCellInsts().stream().sorted(Comparator.comparing(EDIFName::getName))::iterator;
        } else {
            cellInsts = cell.getCellInsts();
        }
        for (EDIFCellInst i : cellInsts) {
            EDIFCell childCell = i.getCellType();
            if (recurseIf.test(childCell)) {
                getValidCellExportOrderVisit(childCell, visitedList, visitedSet, stable, recurseIf);
            }
        }
        visitedList.add(cell);
    }

    private void init() {
        libraries = new LinkedHashMap<>();
        comments = new ArrayList<>();
        metax = getNewMap();
    }

    /**
     * Adds date and username build comments such as:
     *  (comment "Built on 'Mon May  1 15:17:36 PDT 2017'")
     *  (comment "Built by 'clavin'")
     */
    public void generateBuildComments() {
        addComment("Built on '"+FileTools.getTimeString()+"'");
        addComment("Built by '"+System.getenv().get("USER")+"'");
    }

    /**
     * Adds the library to this netlist.  Checks for naming collisions
     * and throws a RuntimeException if it occurs.
     * @param library The library to add.
     * @return The library that was added.
     */
    public EDIFLibrary addLibrary(EDIFLibrary library) {
        library.setNetlist(this);
        EDIFLibrary collision = libraries.put(library.getName(), library);
        if (collision != null) {
            throw new RuntimeException("ERROR: EDIFNetlist already has "
                    + "library named " + library.getName() );
        }
        return library;
    }

    public EDIFLibrary getLibrary(String name) {
        return libraries.get(name);
    }

    public EDIFLibrary getHDIPrimitivesLibrary() {
        EDIFLibrary primLib = libraries.get(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
        if (primLib == null) {
            primLib = addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME));
        }
        return primLib;
    }

    /**
     * Will create or get the specified unisim cell and ensure it is added to the HDI
     * primitives library. If the cell is already in the library, it will simply get it
     * and return it.
     * @param unisim The desired Unisim cell type.
     * @return The current unisim cell in the HDI primitive library for this netlist.
     */
    public EDIFCell getHDIPrimitive(Unisim unisim) {
        EDIFLibrary lib = getHDIPrimitivesLibrary();
        EDIFCell cell = lib.getCell(unisim.name());
        if (cell == null) {
            cell = new EDIFCell(lib, Design.getUnisimCell(unisim), unisim.name());
        }
        return cell;
    }

    public EDIFLibrary getWorkLibrary() {
        EDIFLibrary primLib = libraries.get(EDIFTools.EDIF_LIBRARY_WORK_NAME);
        if (primLib == null) {
            primLib = addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME));
        }
        return primLib;
    }

    public EDIFLibrary removeLibrary(String name) {
        EDIFLibrary library = libraries.remove(name);
        if (library != null) {
            library.clearNetlist();
        }
        return library;
    }

    public void renameNetlistAndTopCell(String newName) {
        this.setName(newName);
        design.setName(newName);
        EDIFLibrary topLib = design.getTopCell().getLibrary();
        EDIFCell top = topLib.removeCell(design.getTopCell());
        top.setName(newName);
        topLib.addCell(top);
        if (topCellInstance != null) {
            topCellInstance.setName(newName);
        }
    }

    /**
     * Helper method for {@link #removeUnusedCellsFromAllWorkLibraries()}
     * @param cellsToRemove The map keeping track of unused cells
     * @param cell Cell to delete from removal list
     */
    private static void _keepCell(HashMap<String,HashMap<String,EDIFCell>> cellsToRemove,
            EDIFCell cell) {
        EDIFLibrary lib = cell.getLibrary();
        if (lib.isHDIPrimitivesLibrary()) return;
        String libName = lib.getName();
        HashMap<String,EDIFCell> libCells = cellsToRemove.get(libName);
        if (libCells == null) {
            throw new RuntimeException("ERROR: Cell " + cell + " references unknown library "
                    + libName);
        }
        libCells.remove(cell.getName());
    }

    /**
     * Removals all unused cells from a netlist from any work library (all except hdi_primitives)
     */
    public void removeUnusedCellsFromAllWorkLibraries() {
        HashMap<String,HashMap<String,EDIFCell>> cellsToRemove = new HashMap<>();
        for (EDIFLibrary lib : getLibraries()) {
            if (lib.isHDIPrimitivesLibrary()) continue;
            cellsToRemove.put(lib.getName(), new HashMap<>(lib.getCellMap()));
        }

        _keepCell(cellsToRemove, getTopCell());
        for (EDIFHierCellInst i : getAllDescendants("", null, false)) {
            _keepCell(cellsToRemove, i.getCellType());
        }

        for (Entry<String, HashMap<String,EDIFCell>> e : cellsToRemove.entrySet()) {
            String libName = e.getKey();
            EDIFLibrary lib = getLibrary(libName);
            for (EDIFCell cell : e.getValue().values()) {
                lib.removeCell(cell);
            }
        }
    }

    public void removeUnusedCellsFromWorkLibrary() {
        HashMap<String,EDIFCell> cellsToRemove = new HashMap<>(getWorkLibrary().getCellMap());

        cellsToRemove.remove(getTopCell().getName());
        for (EDIFHierCellInst i : getAllDescendants("", null, false)) {
            if (i.getCellType().getLibrary().getName().equals(EDIFTools.EDIF_LIBRARY_WORK_NAME)) {
                cellsToRemove.remove(i.getCellType().getName());
            }
        }

        for (String name : cellsToRemove.keySet()) {
            getWorkLibrary().removeCell(name);
        }
    }

    /**
     * Iterates through libraries to find first cell with matching name and
     * returns it.
     * @param legalEdifName The legal EDIF name of the cell to find.
     * @return The first occurring cell with the provided name.
     */
    public EDIFCell getCell(String legalEdifName) {
        for (EDIFLibrary lib : getLibraries()) {
            EDIFCell c = lib.getCell(legalEdifName);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * @return the design
     */
    public EDIFDesign getDesign() {
        return design;
    }

    /**
     * @param design the design to set
     */
    public void setDesign(EDIFDesign design) {
        this.design = design;
    }



    public Device getDevice() {
        if (device == null) {
            String partName = EDIFTools.getPartName(this);
            if (partName != null) {
                device = Device.getDevice(partName);
            }
            if (device == null) {
                System.err.println("WARNING: PART property on EDIF Design object not set correctly,"
                        + " currently set to '"+partName+"', couldn't load device.");
            }
        }
        return device;
    }

    public void setDevice(Device device) {
        this.device = device;
    }

    public EDIFCell getTopCell() {
        return design.getTopCell();
    }

    public EDIFCellInst getTopCellInst() {
        if (topCellInstance == null) {
            topCellInstance = getTopCell().createCellInst("top", null);
        }
        return topCellInstance;
    }

    private EDIFHierCellInst topHierCellInstance;
    public EDIFHierCellInst getTopHierCellInst() {
        if (topHierCellInstance == null) {
            topHierCellInstance = EDIFHierCellInst.createTopInst(getTopCellInst());
        }
        return topHierCellInstance;
    }

    public boolean addComment(String comment) {
        return comments.add(comment);
    }

    public EDIFPropertyValue addMetax(String key, EDIFPropertyValue value) {
        return metax.put(key, value);
    }

    /**
     * @return the comments
     */
    public List<String> getComments() {
        return comments;
    }

    /**
     * Migrates all cells in the provided library
     * into the standard work library.
     * @param library The library with cells to be migrated to work.
     */
    public void migrateToWorkLibrary(String library) {
        EDIFLibrary work = getWorkLibrary();
        EDIFLibrary oldWork = getLibrary(library);
        List<EDIFCell> toRemove = new ArrayList<>(oldWork.getCells());
        for (EDIFCell c : toRemove) {
            oldWork.removeCell(c);
            work.addCell(c);
        }
        removeLibrary(library);
    }

    /**
     * Migrates all libraries except HDI primitives and work to
     * the work library.
     */
    public void consolidateAllToWorkLibrary() {
        List<EDIFLibrary> librariesToMigrate = new ArrayList<>();
        for (EDIFLibrary l : getLibraries()) {
            if (!l.isHDIPrimitivesLibrary() && !l.isWorkLibrary()) {
                librariesToMigrate.add(l);
            }
        }
        for (EDIFLibrary l : librariesToMigrate) {
            migrateToWorkLibrary(l.getName());
        }
    }

    private EDIFCell migrateCellAndSubCellsWorker(EDIFCell cell) {
        EDIFLibrary srcLib = cell.getLibrary();
        EDIFLibrary destLib = getLibrary(srcLib.getName());
        if (destLib == null) {
            if (srcLib.isHDIPrimitivesLibrary()) {
                destLib = getHDIPrimitivesLibrary();
            } else {
                destLib = addLibrary(new EDIFLibrary(srcLib.getName()));
            }
        }

        EDIFCell existingCell = destLib.getCell(cell.getName());
        if (existingCell == null) {
            srcLib.removeCell(cell);
            destLib.addCell(cell);
            for (EDIFCellInst inst : cell.getCellInsts()) {
                inst.setCellType(migrateCellAndSubCellsWorker(inst.getCellType()));
                //The view might have changed
                inst.getViewref().setName(inst.getCellType().getView());
            }
            return cell;
        } else {
            return existingCell;
        }
    }

    /**
     * This moves the cell and all of its descendants into this netlist.  This is a destructive
     * operation for the source netlist.
     * @param cell The cell (and all its descendants) to move into this netlist's libraries
     */
    public void migrateCellAndSubCells(EDIFCell cell) {
        migrateCellAndSubCellsWorker(cell);
    }


    public void migrateCellAndSubCells(EDIFCell cell, boolean uniqueifyCollisions) {
        if (!uniqueifyCollisions) {
            migrateCellAndSubCells(cell);
            return;
        }

        Queue<EDIFCell> cells = new LinkedList<>(); // which contains cells that have been added to libraries but whose subcells haven't.
        //Step 1: add the top cell to the library.
        //If the top cell belongs to HDIPrimitivesLibrary && the top cell exists in HDIPrimitivesLibrary, return and do nothing.
        //Otherwise, the code would add the top cell to the library; if repeat happens, using "parameterized" suffix to distinguish
        EDIFLibrary srcLib = cell.getLibrary();
        EDIFLibrary destLibTop = getLibrary(srcLib.getName());
        if (destLibTop == null) {
            if (srcLib.isHDIPrimitivesLibrary()) {
                destLibTop = getHDIPrimitivesLibrary();
            } else {
                destLibTop = addLibrary(new EDIFLibrary(srcLib.getName()));
            }
        }
        if (destLibTop.containsCell(cell) && destLibTop.isHDIPrimitivesLibrary())
            return;
        srcLib.removeCell(cell);
        int i=0;
        String currentCellName = cell.getName();
        while (destLibTop.containsCell(cell)) {
            cell.setName(currentCellName + "_parameterized" + i);
            cell.setView(currentCellName + "_parameterized" + i);
            i++;
        }
        destLibTop.addCell(cell);
        cells.add(cell);

        //Step 2: add the subcells, subsubcells... to the library.
        //Do it like before, but updating the celltype of each cellInst should be noticed.
        while (!cells.isEmpty()) {
            EDIFCell pollFromCells = cells.poll();
            for (EDIFCellInst inst : pollFromCells.getCellInsts()) {
                EDIFCell cellSub = inst.getCellType();
                EDIFLibrary srcLibSub = cellSub.getLibrary();
                EDIFLibrary destLibSub = getLibrary(srcLibSub.getName());
                if (destLibSub == null) {
                    if (srcLibSub.isHDIPrimitivesLibrary()) {
                        destLibSub = getHDIPrimitivesLibrary();
                    } else {
                        destLibSub = addLibrary(new EDIFLibrary(srcLibSub.getName()));
                    }
                }
                if (destLibSub.containsCell(cellSub) && destLibSub.isHDIPrimitivesLibrary())
                    continue;
                currentCellName = cellSub.getName();
                if (checkIfAlreadyInLib(cellSub, destLibSub)) {
                    inst.setViewref(cellSub.getEDIFView());
                    continue;
                }
                srcLibSub.removeCell(cellSub);
                i=0;
                while (destLibSub.containsCell(cellSub) && !checkIfAlreadyInLib(cellSub, destLibSub)) {
                    String newName = currentCellName + "_parameterized" + i;
                    cellSub.setName(newName);
                    cellSub.setView(newName);
                    i++;
                }
                inst.setCellType(cellSub); // updating the celltype, which could be changed due to adding suffix
                destLibSub.addCell(cellSub);
                cells.add(cellSub);
            }
        }
    }

    /**
     * This copies the cell and all of its descendants into this netlist.
     * @param cell The cell (and all its descendants) to copy into this netlist's libraries
     */
    public void copyCellAndSubCells(EDIFCell cell) {
        Set<EDIFCell> copiedCells = new HashSet<>();
        copyCellAndSubCellsWorker(cell, copiedCells);
    }

    /**
     * This copies the library and all of its cells into this netlist.
     * @param library The library (and all its cells) to copy into this netlist's libraries
     */
    public EDIFLibrary copyLibraryAndSubCells(EDIFLibrary library) {
        Set<EDIFCell> copiedCells = new HashSet<>();
        for (EDIFCell cell : library.getCells()) {
            copyCellAndSubCellsWorker(cell, copiedCells);
        }
        return getLibrary(library.getName());
    }

    private EDIFCell copyCellAndSubCellsWorker(EDIFCell cell, Set<EDIFCell> copiedCells) {
        EDIFLibrary destLib = getLibrary(cell.getLibrary().getName());
        if (destLib == null) {
            if (cell.getLibrary().isHDIPrimitivesLibrary()) {
                destLib = getHDIPrimitivesLibrary();
            } else {
                destLib = addLibrary(new EDIFLibrary(cell.getLibrary().getName()));
            }
        }

        EDIFCell existingCell = destLib.getCell(cell.getName());
        if (existingCell == null) {
            EDIFCell newCell = new EDIFCell(destLib, cell, cell.getName());
            copiedCells.add(newCell);
            for (EDIFCellInst inst : newCell.getCellInsts()) {
                inst.setCellType(copyCellAndSubCellsWorker(inst.getCellType(), copiedCells));
                //The view might have changed
                inst.getViewref().setName(inst.getCellType().getView());
            }
            return newCell;
        } else {
            if (destLib.isHDIPrimitivesLibrary() || copiedCells.contains(existingCell) || cell == existingCell) {
                return existingCell;
            }
            throw new RuntimeException("ERROR: Destination netlist already contains EDIFCell named " +
                    "'" + cell.getName() + "' in library '" + destLib.getName() + "'");
        }
    }

    private boolean checkIfAlreadyInLib(EDIFCell cell, EDIFLibrary lib) {
        EDIFCell existing = lib.getCell(cell.getName());
        if (existing == cell && lib.getNetlist() == cell.getLibrary().getNetlist()) {
            return true;
        }
        return false;
    }

    /**
     * Will change the netlist name and top cell and instance name.
     * @param newName New name for the netlist
     */
    public void changeTopName(String newName) {
        this.setName(newName);
        this.design.setName(newName);
        EDIFCell top = this.design.getTopCell();
        EDIFLibrary lib = top.getLibrary();
        top.getLibrary().removeCell(top);
        top.setName(newName);
        lib.addCell(top);
    }

    /**
     * @return the libraries
     */
    public Map<String, EDIFLibrary> getLibrariesMap() {
        return libraries;
    }

    public Collection<EDIFLibrary> getLibraries() {
        return libraries.values();
    }

    /**
     * Get Libraries in export order so that any cell instance appearing in a library will only
     * refer to cells in its own library or previous libraries in the list.  This is a pre-requisite
     * for export to a file.
     * @return List of all libraries in the netlist sorted for valid export, HDIPrimitives library
     * is always first.
     */
    public List<EDIFLibrary> getLibrariesInExportOrder() {
        Set<EDIFLibrary> toExport = new LinkedHashSet<EDIFLibrary>();
        // Assume HDI Primitives are always first as they should not refer to any previous libraries
        toExport.add(getHDIPrimitivesLibrary());

        Map<String, HashSet<EDIFLibrary>> deps = new HashMap<String, HashSet<EDIFLibrary>>();
        for (EDIFLibrary lib : getLibraries()) {
            if (lib.isHDIPrimitivesLibrary()) continue;
            HashSet<EDIFLibrary> externalRefs =
                    new HashSet<EDIFLibrary>(lib.getExternallyReferencedLibraries());
            externalRefs.remove(getHDIPrimitivesLibrary());

            if (externalRefs.isEmpty()) {
                toExport.add(lib);
            } else {
                deps.put(lib.getName(), externalRefs);
            }
        }

        Queue<Entry<String, HashSet<EDIFLibrary>>> q = new LinkedList<>(deps.entrySet());
        int lastSize = q.size();
        int size = lastSize;
        int watchdog = 10;
        while (!q.isEmpty()) {
            Entry<String,HashSet<EDIFLibrary>> curr = q.poll();
            size--;
            if (toExport.containsAll(curr.getValue())) {
                toExport.add(getLibrary(curr.getKey()));
                continue;
            }
            q.add(curr);
            if (!q.isEmpty() && size == 0) {
                if (q.size() == lastSize) {
                    watchdog--;
                    if (watchdog == 0) {
                        throw new RuntimeException("Circular dependency in EDIF Libraries between "
                                + "cells.  Please merge libraries or resolve dependency.");
                    }
                    lastSize = q.size();
                    size = lastSize;
                }
            }
        }

        return new ArrayList<>(toExport);
    }

    public static final byte[] EXPORT_CONST_EDIF_VERSION = "\n  (edifversion 2 0 0)\n  (edifLevel 0)\n  (keywordmap (keywordlevel 0))\n(status\n (written\n  (timeStamp ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_EDIF_HEAD = "(edif ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_PROGRAM = (")\n  (program \"" + Device.FRAMEWORK_NAME + "\" (version \"" + Device.RAPIDWRIGHT_VERSION + "\"))\n").getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CLOSE_NL = ")\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_QUOTE_CLOSE_NL = "\")\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_COMMENT = "  (comment \"".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_META_X = "(metax ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_DOUBLE_CLOSE = " )\n)\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_DESIGN_START = "(comment \"Reference To The Cell Of Highest Level\")\n\n  (design ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CELLREF = "\n    (cellref ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_LIBRARYREF = " (libraryref ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CLOSE_REF = "))\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_PROP_INDENT = "    ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CLOSE_DESIGN = "  )\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_CLOSE_EDIF = ")\n".getBytes(StandardCharsets.UTF_8);


    public void exportEDIF(OutputStream out, boolean stable) throws IOException {
        try (BufferedOutputStream os = new BufferedOutputStream(out)) {
            final ParallelDCPOutput dos = ParallelismTools.getParallel() ?
                    ParallelDCPOutput.cast(out) : null;

            EDIFWriteLegalNameCache<?> cache = dos!=null ? EDIFWriteLegalNameCache.multiThreaded() : EDIFWriteLegalNameCache.singleThreaded();

            os.write(EXPORT_CONST_EDIF_HEAD);
            exportEDIFName(os, cache);
            os.write(EXPORT_CONST_EDIF_VERSION);
            if (stable) {
                os.write("1970 01 01 00 00 00".getBytes(StandardCharsets.UTF_8));
            } else {
                SimpleDateFormat formatter = new SimpleDateFormat("yyyy MM dd HH mm ss");
                os.write(formatter.format(new Date()).getBytes(StandardCharsets.UTF_8));
            }
            os.write(EXPORT_CONST_PROGRAM);
            for (String comment : getComments()) {
                os.write(EXPORT_CONST_COMMENT);
                os.write(comment.getBytes(StandardCharsets.UTF_8));
                os.write(EXPORT_CONST_QUOTE_CLOSE_NL);
            }
            for (Entry<String, EDIFPropertyValue> e : EDIFTools.sortIfStable(metax, stable)) {
                os.write(EXPORT_CONST_META_X);
                os.write(e.getKey().getBytes(StandardCharsets.UTF_8));
                os.write(' ');
                e.getValue().writeEDIFString(os);
                os.write(EXPORT_CONST_CLOSE_NL);
            }
            os.write(EXPORT_CONST_DOUBLE_CLOSE);

            List<EDIFLibrary> librariesToWrite = new ArrayList<>();
            librariesToWrite.add(getHDIPrimitivesLibrary());
            for (EDIFLibrary lib : EDIFTools.sortIfStable(getLibrariesMap().values(), stable)) {
                if (lib.isHDIPrimitivesLibrary()) {
                    continue;
                }
                librariesToWrite.add(lib);
            }

            if (dos != null) {
                Deque<Future<ParallelDCPInput>> streamFutures = new ArrayDeque<>();
                for (EDIFLibrary lib : librariesToWrite) {
                    streamFutures.addAll(lib.exportEDIF(cache));
                }

                os.flush();

                while (!streamFutures.isEmpty()) {
                    try {
                        ParallelDCPInput dis = ParallelismTools.joinFirst(streamFutures);
                        dos.write(dis);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            } else {
                for (EDIFLibrary lib : librariesToWrite) {
                    lib.exportEDIF(os, cache, stable);
                }
            }

            os.write(EXPORT_CONST_DESIGN_START);
            EDIFDesign design = getDesign();
            if (design != null) {
                design.exportEDIFName(os, cache);
                os.write(EXPORT_CONST_CELLREF);
                os.write(cache.getLegalEDIFName(design.getTopCell().getName()));
                os.write(EXPORT_CONST_LIBRARYREF);
                os.write(cache.getLegalEDIFName(design.getTopCell().getLibrary().getName()));
                os.write(EXPORT_CONST_CLOSE_REF);
                design.exportEDIFProperties(os, EXPORT_CONST_PROP_INDENT, cache, stable);
                os.write(EXPORT_CONST_CLOSE_DESIGN);
            }
            os.write(EXPORT_CONST_CLOSE_EDIF);
        }
    }
    public void exportEDIF(OutputStream out) throws IOException {
        exportEDIF(out, false);
    }

    public void exportEDIF(Path fileName, boolean stable) {
        try (OutputStream out = Files.newOutputStream(fileName)) {
            exportEDIF(out, stable);
        } catch (IOException e) {
            MessageGenerator.briefError("ERROR: Failed to export EDIF file " + fileName);
            e.printStackTrace();
        }
    }

    public void exportEDIF(Path fileName) {
        exportEDIF(fileName, false);
    }

    public void exportEDIF(String fileName) {
        exportEDIF(Paths.get(fileName));
    }


    /**
     * Based on a hierarchical string, this method will get the instance corresponding
     * to the name provided.
     *
     * If more hierarchy information is needed, consider using
     * {@link EDIFNetlist#getHierCellInstFromName(String)} instead.
     *
     * @param name Hierarchical name of the instance, for example: 'clk_wiz/inst/bufg0'
     * @return The instance corresponding to the provided name.  If the name string is empty,
     * it returns the top cell instance.
     */
    public EDIFCellInst getCellInstFromHierName(String name) {
        final EDIFHierCellInst hierCellInst = getHierCellInstFromName(name);
        if (hierCellInst == null) {
            return null;
        }
        return hierCellInst.getInst();
    }



    /**
     * Based on a hierarchical string name, this method gets and returns the net inside
     * the instance.
     * @param netName The hierarchical name of the net to get, for example: 'inst0/inst1/inst2/net0'
     * @return The hierarchical net, or null if none could be found.
     */
    public EDIFNet getNetFromHierName(String netName) {
        EDIFHierNet net = getHierNetFromName(netName);
        return net == null ? null : net.getNet();
    }

    /**
     * Gets the hierarchical port instance object from the full name.
     * @param hierPortInstName Full hierarchical name of the port instance.
     * @return The port instance of interest or null if none could be found.
     */
    public EDIFHierPortInst getHierPortInstFromName(String hierPortInstName) {
        return getHierObject(
                hierPortInstName,
                EDIFCellInst::getPortInst,
                (ehci, pi) -> new EDIFHierPortInst(ehci.getParent(), pi) //TODO can we avoid the call to getParent()? We are constructing
        );
    }

    /**
     * Resolve as much of a hierarchical name as possible to a List of EDIFCellInsts, suitable for creating a EDIFHierCellInst from.
     * Also return the unmatched portion
     * @param name the hierarchical name
     * @return A pair of EdifHierCellInst and the unmatched portion of the name. The name may be null if we found a complete match
     */
    public Pair<List<EDIFCellInst>, String> getHierObject(String name) {
        if (name.isEmpty()) return new Pair<>(Collections.singletonList(getTopCellInst()), null);
        String[] parts = name.split(EDIFTools.EDIF_HIER_SEP);

        // Sadly, cells can be named 'fred/' instead of 'fred', this code handles this situation
        if (name.charAt(name.length()-1) == '/') {
            parts[parts.length-1] = parts[parts.length-1] + EDIFTools.EDIF_HIER_SEP;
        }

        List<EDIFCellInst> cells = new ArrayList<>(parts.length);
        EDIFCellInst currInst = getTopCellInst();
        cells.add(currInst);

        for (int i=0; i < parts.length; i++) {
            EDIFCellInst checkInst = currInst.getCellType().getCellInst(parts[i]);
            // Someone named their instance with hierarchy separators, joy!
            if (checkInst == null) {
                StringBuilder sb = new StringBuilder(parts[i]);
                i++;
                while (checkInst == null && i < parts.length) {
                    sb.append(EDIFTools.EDIF_HIER_SEP);
                    sb.append(parts[i]);
                    checkInst = currInst.getCellType().getCellInst(sb.toString());
                    if (checkInst == null) i++;
                }
                if (checkInst == null) {
                    // Try searching other direction
                    String prefixHierName = "";
                    for (int j=0; j < cells.size(); j++) {
                        EDIFCellInst curr = cells.get(j);
                        prefixHierName += curr == getTopCellInst() ? "" : (curr.getName() + "/");
                        int index = name.indexOf(prefixHierName);
                        String suffixHierName = name.substring(index + prefixHierName.length());
                        EDIFCellInst match = curr.getCellType().getCellInst(suffixHierName);
                        if (match != null) {
                                    for (int k=cells.size()-1; k > j; k--) {
                                        cells.remove(k);
                                    }
                                    cells.add(match);
                                    return new Pair<>(cells, null);
                        }
                    }
                    //Not found
                    return new Pair<>(cells, sb.toString());
                }
            }
            currInst = checkInst;
            cells.add(currInst);
        }
        return new Pair<>(cells, null);
    }

    private EDIFHierCellInst cellListToHier(List<EDIFCellInst> cells) {
        //Is toplevel?
        if (cells.size()==1) {
            return getTopHierCellInst();
        } else {
            return EDIFHierCellInst.create(cells.toArray(new EDIFCellInst[0]));
        }
    }

    /**
     * Parse a hierarchical name into an object of some sort.
     *
     * As '/' can occur within names and is the hierarchy separator, we have to try quite a few combinations of names.
     * @param hierObjName the name to parse
     * @param relativeLookup try to match a name to some local object. should return null if there is no match.
     * @param hierConstructor construct an hierarchical object from a hierarchical cell and a relative object
     * @param <RelObjT> relative object type
     * @param <HierObjT> hierarchical object type
     * @return the constructed hierarchical object or null if not found
     */
    private <RelObjT, HierObjT> HierObjT getHierObject(
            String hierObjName,
            BiFunction<EDIFCellInst, String, RelObjT> relativeLookup,
            BiFunction<EDIFHierCellInst, RelObjT, HierObjT> hierConstructor
    ) {
        Pair<List<EDIFCellInst>, String> hierPair = getHierObject(hierObjName);

        List<EDIFCellInst> currentHierarchy = hierPair.getFirst();
        String relObjName = hierPair.getSecond();

        if (relObjName == null) {
            //Name collision between cell inst names and what we are searching for, immediately move one level up
            final EDIFCellInst leafCellInst = currentHierarchy.get(currentHierarchy.size() - 1);
            relObjName = leafCellInst.getName();
            currentHierarchy.remove(currentHierarchy.size()-1);
        }

        while (!currentHierarchy.isEmpty()) {
            final EDIFCellInst leafCellInst = currentHierarchy.get(currentHierarchy.size() - 1);
            RelObjT relObj = relativeLookup.apply(leafCellInst, relObjName);
            if (relObj != null) {
                return hierConstructor.apply(cellListToHier(currentHierarchy), relObj);
            }

            //Not found, move one level up
            relObjName = leafCellInst.getName() + EDIFTools.EDIF_HIER_SEP + relObjName;
            currentHierarchy.remove(currentHierarchy.size()-1);
        }
        return null;
    }

    /**
     * Creates a new hierarchical cell instance reference from the provided hierarchical cell
     * instance name
     * @param name Full hierarchical cell instance name
     * @return Hierarchical cell instance reference or null if named instance could not be found
     */
    public EDIFHierCellInst getHierCellInstFromName(String name) {
        final Pair<List<EDIFCellInst>, String> hierObject = getHierObject(name);
        //Incomplete match?
        if (hierObject.getSecond() != null) {
            return null;
        }
        return cellListToHier(hierObject.getFirst());
    }

    /**
     * Gets the hierarchical net from the netname provided. Returns the wrapped EDIFNet, with the hierarchical
     * String in {@link EDIFHierNet}.
     * @param netName Full hierarchical name of the net to retrieve.
     * @return The absolute net with hierarchical name, or null if none could be found.
     */
    public EDIFHierNet getHierNetFromName(String netName) {
        return getHierObject(
                netName,
                (eci, n) -> eci.getCellType().getNet(n),
                EDIFHierNet::new
        );
    }
    public Net getPhysicalNetFromPin(String parentHierInstName, EDIFPortInst p, Design d) {
        return getPhysicalNetFromPin(new EDIFHierPortInst(getHierCellInstFromName(parentHierInstName), p), d);
    }
    public Net getPhysicalNetFromPin(EDIFHierPortInst p, Design d) {
        if (p.getHierarchicalInst().isTopLevelInst()) {
            if (p.getNet().getName().equals(EDIFTools.LOGICAL_GND_NET_NAME)) return d.getGndNet();
            if (p.getNet().getName().equals(EDIFTools.LOGICAL_VCC_NET_NAME)) return d.getVccNet();
        }

        Map<EDIFHierNet,EDIFHierNet> parentNetMap = getParentNetMap();
        EDIFHierNet parentNetName = parentNetMap.get(p.getHierarchicalNet());
        Net n = parentNetName == null ? null : d.getNet(parentNetName.getHierarchicalNetName());
        if (n == null) {
            if (parentNetName == null) {
                // Maybe it is GND/VCC
                List<EDIFPortInst> src = p.getNet().getSourcePortInsts(false);
                if (src.size() > 0 && src.get(0).getCellInst() != null) {
                    String cellType = src.get(0).getCellInst().getCellType().getName();
                    if (cellType.equals("GND")) return d.getGndNet();
                    if (cellType.equals("VCC")) return d.getVccNet();
                }
            }
            if (parentNetName == null) {
                System.err.println("WARNING: Could not find parent of net \"" + p.getHierarchicalNet() +
                        "\", please check that the netlist is fully connected through all levels of "
                        + "hierarchy for this net.");
            }
            EDIFNet logicalNet = parentNetName.getNet();
            List<EDIFPortInst> eprList = logicalNet.getSourcePortInsts(false);
            if (eprList.size() > 1) throw new RuntimeException("ERROR: Bad assumption on net, has two sources.");
            if (eprList.size() == 1) {
                String cellTypeName = eprList.get(0).getCellInst().getCellType().getName();
                if (cellTypeName.equals("GND")) {
                    return d.getGndNet();
                } else if (cellTypeName.equals("VCC")) {
                    return d.getVccNet();
                }
            }
            // If size is 0, assume top level port in an OOC design

            n = d.createNet(parentNetName.getHierarchicalNetName());
            n.setLogicalHierNet(parentNetName);
        }
        return n;
    }

    /**
     * Searches all EDIFCellInst objects to find those with matching names
     * against the wildcard pattern.
     * @param wildcardPattern Search pattern that includes alphanumeric and wildcards (*).
     * @return The list of all matching EDIFHierCellInst
     */
    public List<EDIFHierCellInst> findCellInsts(String wildcardPattern) {
        return getAllDescendants("", wildcardPattern, false);
    }

    /**
     * Searches all lower levels of hierarchy to find all leaf descendants.  It returns a
     * list of all leaf cells that fall under the hierarchy of the provided instance name.
     * @param instanceName Name of the instance to start searching from.
     * @return A list of all leaf cell instances or null if the instanceName was not found.
     */
    public List<EDIFHierCellInst> getAllLeafDescendants(String instanceName) {
        return getAllLeafDescendants(getHierCellInstFromName(instanceName));
    }

    /**
     * Searches all lower levels of hierarchy to find all leaf descendants.  It returns a
     * list of all leaf cells that fall under the hierarchy of the provided instance name.
     * @param instance The instance to start searching from.
     * @return A list of all leaf cell instances or null if the instanceName was not found.
     */
    public List<EDIFHierCellInst> getAllLeafDescendants(EDIFHierCellInst instance) {
        return getAllLeafDescendants(instance, false);
    }

    /**
     * Searches all lower levels of hierarchy to find all leaf descendants. It
     * returns a list of all leaf cells that fall under the hierarchy of the
     * provided instance name.
     * 
     * @param instance          The instance to start searching from.
     * @param includeBlackBoxes Flag, if set to true, will include black box
     *                          instances in the returned list.
     * @return A list of all leaf cell instances or null if the instanceName was not
     *         found.
     */
    public List<EDIFHierCellInst> getAllLeafDescendants(EDIFHierCellInst instance, boolean includeBlackBoxes) {
        List<EDIFHierCellInst> leafCells = new ArrayList<>();


        Queue<EDIFHierCellInst> toProcess = new LinkedList<EDIFHierCellInst>();
        toProcess.add(instance);

        while (!toProcess.isEmpty()) {
            EDIFHierCellInst curr = toProcess.poll();
            if (curr.getCellType().isPrimitive() || (includeBlackBoxes && curr.getCellType().isLeafCellOrBlackBox())) {
                leafCells.add(curr);
            } else {
                curr.addChildren(toProcess);
            }
        }
        return leafCells;
    }

    private String convertWildcardToRegex(String wildcardPattern) {
        if (wildcardPattern == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i=0; i < wildcardPattern.length(); i++) {
            char c = wildcardPattern.charAt(i);
            switch (c) {
                case '*':
                    sb.append(".*");
                    break;
                case '?': case '\\': case '{': case '}': case '|':
                case '^': case '$':  case '(': case ')': case '[': case ']':
                    sb.append("\\");
                    sb.append(c);
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    public List<EDIFHierCellInst> getAllLeafDescendants(String instanceName, String wildcardPattern) {
        return getAllDescendants(instanceName, wildcardPattern, true);
    }


    /**
     * Searches all lower levels of hierarchy to find descendants.  It returns the
     * set of all cells that fall under the hierarchy of the provided instance name.
     * @param instanceName Name of the instance to start searching from.
     * @param wildcardPattern if non-null, filters results by matching wildcard pattern
     * @param leavesOnly Flag indicating if only leaf cells should be included
     * @return A set of all leaf cell instances or null if the instanceName was not found.
     */
    public List<EDIFHierCellInst> getAllDescendants(String instanceName, String wildcardPattern, boolean leavesOnly) {
        List<EDIFHierCellInst> children = new ArrayList<>();

        final EDIFHierCellInst eci = getHierCellInstFromName(instanceName);
        if (eci==null) {
            return null;
        }
        Queue<EDIFHierCellInst> q = new LinkedList<>();
        q.add(eci);
        String pattern = convertWildcardToRegex(wildcardPattern);
        Pattern pat = wildcardPattern != null ? Pattern.compile(pattern) : null;

        while (!q.isEmpty()) {
            EDIFHierCellInst i = q.poll();
            for (EDIFCellInst child : i.getInst().getCellType().getCellInsts()) {
                EDIFHierCellInst newCell = i.getChild(child);
                if (newCell.getInst().getCellType().isPrimitive()) {
                    if (pat != null && !pat.matcher(newCell.getFullHierarchicalInstName()).matches()) {
                        continue;
                    }
                    children.add(newCell);
                } else {
                    q.add(newCell);
                    if (!leavesOnly) {
                        if (pat != null && !pat.matcher(newCell.getFullHierarchicalInstName()).matches()) {
                            continue;
                        }
                        children.add(newCell);
                    }
                }
            }
        }

        return children;
    }

    private static boolean isDeviceNullPrinted = false;
    private boolean isTransformPrim(EDIFHierPortInst p) {
        EDIFCellInst cellInst = p.getPortInst().getCellInst();
        if (!cellInst.getCellType().isPrimitive()) return false;
        Unisim u = Unisim.valueOf(p.getPortInst().getCellInst().getCellType().getName());
        if (device == null && !isDeviceNullPrinted) {
            System.err.println("WARNING: EDIFNetlist.device==null when calling isTransformPrim(), results may be incorrect");
            isDeviceNullPrinted = true;
        }
        return u.hasTransform(device == null ? Series.UltraScale : device.getSeries());
    }

    public static NetType identifyNetType(EDIFHierPortInst source) {
        String cellType = source.getPortInst().getCellInst() == null ? "" : source.getPortInst().getCellInst().getCellType().getName();
        if (cellType.equals("GND")) {
            return NetType.GND;
        }
        if (cellType.equals("VCC")) {
            return NetType.VCC;
        }
        return NetType.WIRE;
    }

    /**
     * Get's all equivalent nets in the netlist from the provided net name.
     * The returned list also includes the provided netName.
     * @param initialNet Full hierarchical netname to use as a starting point in the search.
     * @return A list of all electrically connected nets in the netlist that are equivalent.
     * The list is composed of all full hierarchical net names or an empty list if netName is invalid.
     */
    public List<EDIFHierNet> getNetAliases(EDIFHierNet initialNet) {
        if (physicalNetPinMap == null) {
            physicalNetPinMap = new HashMap<>();
            physicalGndPins = new ArrayList<>();
            physicalVccPins = new ArrayList<>();
        }
        ArrayList<EDIFHierPortInst> leafCellPins = new ArrayList<>();
        List<EDIFHierNet> aliases = new ArrayList<>();
        Queue<EDIFHierNet> queue = new ArrayDeque<>();
        queue.add(initialNet);
        HashSet<EDIFHierNet> visited = new HashSet<>();

        EDIFHierPortInst source = null;
        EDIFHierNet parentNet = null;
        EDIFHierNet fallbackParentNet = null;
        while (!queue.isEmpty()) {
            EDIFHierNet net = queue.poll();
            if (!visited.add(net)) {
                continue;
            }
            aliases.add(net);
            for (EDIFPortInst relP : net.getNet().getPortInsts()) {
                EDIFHierPortInst p = new EDIFHierPortInst(net.getHierarchicalInst(), relP);

                boolean isCellPin = relP.getCellInst() != null && relP.getCellInst().getCellType().isLeafCellOrBlackBox();
                if (isCellPin) {
                    leafCellPins.add(p);
                }


                boolean isTopLevelPortInst = p.getHierarchicalInst().isTopLevelInst() && relP.getCellInst() == null;
                boolean isToplevelInput = isTopLevelPortInst && p.isInput();
                if (isToplevelInput || (isCellPin && p.isOutput())) {
                    if (parentNet != null) {
                        throw new RuntimeException("Multiple sources!");
                    }
                    source = p;
                    parentNet = net;
                }

                // For top-level INOUT ports, consider the possibility that it might be an input
                // and thus a parent net
                boolean isToplevelInout = isTopLevelPortInst && !p.isInput() && !p.isOutput();
                if (isToplevelInout) {
                    if (fallbackParentNet != null) {
                        throw new RuntimeException("Multiple sources!");
                    } else if (parentNet == null) {
                        source = p;
                        fallbackParentNet = net;
                    }
                }

                if (p.getPortInst().getCellInst() == null) {
                    // Moving up in hierarchy
                    if (!p.getHierarchicalInst().isTopLevelInst()) {
                        final EDIFHierPortInst upPort = p.getPortInParent();
                        if (upPort != null && upPort.getNet() != null) {
                            queue.add(upPort.getHierarchicalNet());
                        }
                    }
                } else {
                    // Moving down in hierarchy
                    EDIFHierNet otherNet = p.getInternalNet();
                    if (otherNet == null) {
                        // Looks unconnected
                        continue;
                    }
                    queue.add(otherNet);
                }
            }
        }

        if (parentNet == null) {
            // No other parent net was found, promote the fallback net
            parentNet = fallbackParentNet;
        }

        if (parentNet != null) {
            switch (identifyNetType(source)) {
                case GND:
                    physicalGndPins.addAll(leafCellPins);
                    break;
                case VCC:
                    physicalVccPins.addAll(leafCellPins);
                    break;
            }
            physicalNetPinMap.put(parentNet, leafCellPins);
        } else if (initialNet.getNet().getPortInsts().size() == 0) {
            return aliases;
        } else {
            throw new RuntimeException("ERROR: Couldn't identify parent net, no output pins (or top level output port) found.");
        }

        return aliases;
    }

    /**
     * Gets the canonical net for this net name.  This corresponds to the driving net
     * in the netlist and/or the physical net name.
     * @param netAlias An absolute net name alias (from logical netlist)
     * @return The physical/parent net name or null if none could be found.
     */
    public String getParentNetName(String netAlias) {
        EDIFHierNet parentNet = getParentNetMap().get(getHierNetFromName(netAlias));
        return (parentNet != null) ? parentNet.getHierarchicalNetName() : null;
    }
    /**
     * Gets the canonical net for this net name.  This corresponds to the driving net
     * in the netlist and/or the physical net name.
     * @param netAlias An absolute net name alias (from logical netlist)
     * @return The physical/parent net name or null if none could be found.
     */
    public EDIFHierNet getParentNet(EDIFHierNet netAlias) {
        return getParentNetMap().get(netAlias);
    }

    /**
     * Gets the map of all canonical net for every net alias.  This corresponds to the driving net
     * in the netlist and/or the physical net name.
     * @return the map
     */
    public Map<EDIFHierNet,EDIFHierNet> getParentNetMap() {
        if (parentNetMap == null) {
            generateParentNetMap();
        }
        return parentNetMap;
    }


    /**
     * Gets the map of all canonical net for every net alias.  This corresponds to the driving net
     * in the netlist and/or the physical net name.
     *
     * This is the same as of {@link #getParentNetMap()}, but converted to Strings.
     * @return the map
     */
    public Map<String, String> getParentNetMapNames() {
        if (parentNetMapNames == null) {
            parentNetMapNames = getParentNetMap().entrySet().stream().collect(Collectors.toMap(
                n->n.getKey().getHierarchicalNetName(),
                n->n.getValue().getHierarchicalNetName()
            ));
        }
        return parentNetMapNames;
    }

    /**
     * Resets the internal parent net map of the netlist.  This is necessary any time modifications
     * are made to the netlist (add/remove/change cells/nets, removing/adding black boxes, etc).
     */
    public void resetParentNetMap() {
        parentNetMap = null;
        parentNetMapNames = null;
        physicalNetPinMap = null;
        physicalGndPins = null;
        physicalVccPins = null;
    }

    private void generateParentNetMap() {
        long start = 0;
        if (DEBUG) {
            start = System.currentTimeMillis();
        }
        if (parentNetMap == null) {
            parentNetMap = new HashMap<>();
        }
        if (physicalNetPinMap == null) {
            physicalNetPinMap = new HashMap<>();
            physicalGndPins = new ArrayList<>();
            physicalVccPins = new ArrayList<>();
        }
        EDIFCell c = getTopCell();
        EDIFHierCellInst topCellInst = getTopHierCellInst();
        Queue<EDIFHierPortInst> queue = new LinkedList<>();
        // All parent nets are either top-level inputs/inouts or outputs of leaf cells
        // Here we gather all top-level inputs/inouts
        for (EDIFNet n : c.getNets()) {
            for (EDIFPortInst p : n.getPortInsts()) {
                if (p.isTopLevelPort() && !p.isOutput()) {
                    queue.add(new EDIFHierPortInst(topCellInst, p));
                }
            }
        }
        // Here we search for all leaf cell insts
        Queue<EDIFHierCellInst> instQueue = new LinkedList<>();
        instQueue.add(getTopHierCellInst());
        while (!instQueue.isEmpty()) {
            EDIFHierCellInst currInst = instQueue.poll();
            for (EDIFCellInst eci : currInst.getInst().getCellType().getCellInsts()) {
                // Checks if cell is primitive or black box
                if (eci.getCellType().getCellInsts().size() == 0 && eci.getCellType().getNets().size() == 0) {
                    for (EDIFPortInst portInst : eci.getPortInsts()) {
                        if (portInst.isOutput() && portInst.getNet() != null) {
                            queue.add(new EDIFHierPortInst(currInst, portInst));
                        }
                    }
                } else {
                    instQueue.add(currInst.getChild(eci));
                }
            }
        }

        for (EDIFHierPortInst pr : queue) {
            assert(pr.getNet() != null);
            EDIFHierNet parentNetName = pr.getHierarchicalNet();
            for (EDIFHierNet alias : getNetAliases(parentNetName)) {
                parentNetMap.put(alias, parentNetName);
            }
        }
        if (DEBUG) {
            long stop = System.currentTimeMillis();
            System.out.println("generateParentNetMap() runtime: " + (stop-start)/1000.0f +" seconds ");
        }
    }

    /**
     * Traverses the netlist and produces a list of all primitive leaf cell instances.
     * @return A list of all primitive leaf cell instances.
     */
    public List<EDIFCellInst> getAllLeafCellInstances() {
        List<EDIFCellInst> insts = new ArrayList<>();
        Queue<EDIFCellInst> q = new LinkedList<>();
        q.add(getTopCellInst());
        while (!q.isEmpty()) {
            EDIFCellInst curr = q.poll();
            for (EDIFCellInst eci : curr.getCellType().getCellInsts()) {
                if (eci.getCellType().isPrimitive())
                    insts.add(eci);
                else
                    q.add(eci);
            }
        }
        return insts;
    }

    /**
     * Traverses the netlist and produces a list of all primitive leaf hierarchical
     * cell instances.
     * 
     * @param includeBlackBoxes Flag if set to true will also include black boxes in
     *                          the returned list.
     * @return A list of all primitive leaf hierarchical cell instances.
     */
    public List<EDIFHierCellInst> getAllLeafHierCellInstances(boolean includeBlackBoxes) {
        return getAllLeafDescendants(getTopHierCellInst(), includeBlackBoxes);
    }

    /**
     * Traverses the netlist and produces a list of all primitive leaf hierarchical
     * cell instances.
     * 
     * @return A list of all primitive leaf hierarchical cell instances.
     */
    public List<EDIFHierCellInst> getAllLeafHierCellInstances() {
        return getAllLeafHierCellInstances(false);
    }

    /**
     * Get the physical pins all parent nets (as returned by {@link #getParentNet(EDIFHierNet)}).
     *
     * No special handling for static nets is performed. Therefore, only the local connectivity is visible. To see
     * all globally connected static pins, use {@link #getPhysicalVccPins()} and {@link #getPhysicalGndPins()}.
     * @return the physicalNetPinMap
     */
    public Map<EDIFHierNet, List<EDIFHierPortInst>> getPhysicalNetPinMap() {
        if (physicalNetPinMap == null) {
            generateParentNetMap();
        }
        return physicalNetPinMap;
    }



    /**
     * Get all Physical vcc pins
     * @return the physical vcc pins
     */
    public List<EDIFHierPortInst> getPhysicalVccPins() {
        if (physicalNetPinMap == null) {
            generateParentNetMap();
        }
        return physicalVccPins;
    }

    /**
     * Get all Physical ground pins
     * @return the physical ground pins
     */
    public List<EDIFHierPortInst> getPhysicalGndPins() {
        if (physicalNetPinMap == null) {
            generateParentNetMap();
        }
        return physicalGndPins;
    }

    /**
     * Get the physical pins of this net.
     *
     * No special handling for static nets is performed. Therefore, only the local connectivity is visible. To see
     * all globally connected static pins, use {@link #getPhysicalVccPins()} and {@link #getPhysicalGndPins()}.
     * @param parentNet the parent net, as returned by {@link #getParentNet(EDIFHierNet)}
     * @return all pins
     */
    public List<EDIFHierPortInst> getPhysicalPins(EDIFHierNet parentNet) {
        return getPhysicalNetPinMap().get(parentNet);
    }


    /**
     * Get all physical pins of a net.
     *
     * If "GLOBAL_LOGIC0" or "GLOBAL_LOGIC1" is passed as net name, all global GND/VCC pins will be returned. If a
     * local name of a static net is passed, only the locally connected Pins will be returned.
     *
     *
     * If you want to call this method for a physical net, use {@link #getPhysicalPins(Net)} instead.
     * @param parentNetName the net name, as returned by {@link #getParentNet(EDIFHierNet)}
     * @return the physical pins
     */
    public List<EDIFHierPortInst> getPhysicalPins(String parentNetName) {
        if (parentNetName.equals(Net.GND_NET)) {
            return physicalGndPins;
        }
        if (parentNetName.equals(Net.VCC_NET)) {
            return physicalVccPins;
        }
        return getPhysicalPins(getHierNetFromName(parentNetName));
    }

    /**
     * For a given physical net, get all physical pins from the EDIF.
     *
     * This is the same as calling {@link #getPhysicalPins(String)} with the net's name, but this function is faster.
     * @param net the physical net
     * @return all pins
     */
    public List<EDIFHierPortInst> getPhysicalPins(Net net) {
        switch (net.getType()) {
            case GND:
                return physicalGndPins;
            case VCC:
                return physicalVccPins;
            default:
                final EDIFHierNet hierNet = getHierNetFromName(net.getName());
                return getPhysicalNetPinMap().get(hierNet);
        }
    }

    /**
     * Gets all the primitive pin sinks that are strict descendants of
     * this provided net.
     * @param net The net to trace to its sinks.
     * @return The list of all sink pins on primitive cells that are descendants
     * of the provided net
     */
    public List<EDIFHierPortInst> getSinksFromNet(EDIFHierNet net) {
        return net.getLeafHierPortInsts(false);
    }

    /**
     * @param cellInstMap
     * @return
     */
    public HashMap<String, EDIFNet> generateEDIFNetMap(HashMap<String, EDIFCellInst> cellInstMap) {
        HashMap<String,EDIFNet> map = new HashMap<String, EDIFNet>();

        Queue<EDIFHierCellInst> toProcess = new LinkedList<EDIFHierCellInst>();

        // Add nets at the very top level to start
        for (EDIFNet net : getTopCell().getNets()) {
            map.put(net.getName(), net);
        }

        getTopHierCellInst().addChildren(toProcess);

        while (!toProcess.isEmpty()) {
            EDIFHierCellInst curr = toProcess.poll();
            if (curr.getInst().getCellType().getNets() == null) continue;
            for (EDIFNet net : curr.getInst().getCellType().getNets()) {
                map.put(new EDIFHierNet(curr, net).getHierarchicalNetName(), net);
                //System.out.println("NET: " + name + "/" + net.getOldName());
            }
            curr.addChildren(toProcess);

        }
        return map;
    }

    /**
     * Identify primitive cell instances in EDIF netlist
     * @return A map of hierarchical names (not including top-level name)
     *         to EdifCellInstances that use primitives in the library
     */
    public HashMap<String,EDIFCellInst> generateCellInstMap() {
        HashMap<String,EDIFCellInst> primitiveInstances = new HashMap<String, EDIFCellInst>();

        Queue<EDIFHierCellInst> toProcess = new LinkedList<EDIFHierCellInst>();
        getTopHierCellInst().addChildren(toProcess);

        while (!toProcess.isEmpty()) {
            EDIFHierCellInst curr = toProcess.poll();
            if (curr.getInst().getCellType().isPrimitive()) {
                primitiveInstances.put(curr.getFullHierarchicalInstName(), curr.getInst());
            } else {
                curr.addChildren(toProcess);
            }
        }

        return primitiveInstances;
    }

    private static Set<String> getAllDecendantCellTypes(EDIFCell c) {
        Set<String> types = new HashSet<>();

        Queue<EDIFCell> q = new LinkedList<>();
        q.add(c);
        while (!q.isEmpty()) {
            EDIFCell curr = q.poll();
            types.add(curr.getName());
            for (EDIFCellInst i : curr.getCellInsts()) {
                q.add(i.getCellType());
            }
        }

        return types;
    }

    /**
     * Expands macro primitives into a native-compatible implementation.
     * In Vivado, some non-native unisims are expanded or transformed
     * into one or more native unisims to target the architecture while
     * supporting the functionality of the macro unisim.  When writing out
     * EDIF in Vivado, these primitives are collapsed back down to their
     * primitive state.  This method compensates for this behavior by expanding
     * the macro primitives. As an example, {@code IBUF => IBUF (IBUFCTRL, IBUF)} for
     * UltraScale devices.
     * @param series The architecture series targeted by this netlist.
     */
    public void expandMacroUnisims(Series series) {
        //Invalidate Cached Data
        resetParentNetMap();

        EDIFLibrary macros = Design.getMacroPrimitives(series);
        EDIFLibrary netlistPrims = getHDIPrimitivesLibrary();

        Map<String, Pair<String, EnumSet<IOStandard>>> seriesMacroExpandExceptionMap =
                               macroExpandExceptionMap.getOrDefault(series, Collections.emptyMap());

        // Find the macro primitives to replace
        Set<String> toReplace = new HashSet<String>();
        Set<String> possibleExceptions = seriesMacroExpandExceptionMap == null ?
                Collections.emptySet() : seriesMacroExpandExceptionMap.keySet();
        for (EDIFCell c : netlistPrims.getCells()) {
            if (macros.containsCell(c.getName())) {
                toReplace.addAll(getAllDecendantCellTypes(macros.getCell(c.getName())));
            }
            if (possibleExceptions.contains(c.getName())) {
                toReplace.add(c.getName());
            }
        }

        // Replace macro primitives in library and import pre-requisite cells if needed
        for (String cellName : toReplace) {
            EDIFCell removed = netlistPrims.removeCell(cellName);
            if (removed == null) {
                primsToRemoveOnCollapse.add(cellName);
            }
            EDIFCell toAdd = macros.getCell(cellName);
            if (toAdd == null) {
                toAdd = Design.getUnisimCell(Unisim.valueOf(cellName));
            }
            // Add copy to prim library to avoid destructive changes when collapsed
            // Needs to be a deep copy because it may have child instances that will get updated
            EDIFCell deepCopy = new EDIFCell(netlistPrims, toAdd, cellName);
            for (EDIFCellInst inst : deepCopy.getCellInsts()) {
                EDIFCell child = netlistPrims.getCell(inst.getCellName());
                if (child == null) {
                    EDIFCell childCopy = new EDIFCell(netlistPrims, inst.getCellType(), inst.getCellType().getName());
                    inst.setCellType(childCopy);
                } else if (inst.getCellType() != child) {
                    inst.setCellType(child);
                }
            }
        }

        // Update all cell references to macro versions
        for (EDIFLibrary lib : getLibraries()) {
            boolean isHDILib = lib.isHDIPrimitivesLibrary();
            for (EDIFCell cell : new ArrayList<>(lib.getCells())) {
                for (EDIFCellInst inst : cell.getCellInsts()) {
                    String cellName = inst.getCellType().getName();
                    if (toReplace.contains(cellName)) {
                        if (!isHDILib) {
                            Pair<String, EnumSet<IOStandard>> exception = seriesMacroExpandExceptionMap.get(cellName);
                            if (exception != null) {
                                if (checkIOStandardForExpansion(inst, exception)) {
                                    cellName = exception.getFirst();
                                }
                            }
                        }
                        expandMacroCellType(cellName, inst, macros, netlistPrims);
                        // Check for multi-level expansion for IOBUFDSE3 (only one known)
                        if (inst.getCellType().getName().equals("IOBUFDSE3")) {
                            EDIFCellInst child = inst.getCellType().getCellInst("OBUFTDS");
                            if (child != null && child.getCellType().getName().equals("OBUFTDS_DCIEN")) {
                                Pair<String, EnumSet<IOStandard>> exception = seriesMacroExpandExceptionMap
                                        .get(child.getCellType().getName());
                                String newChildType = null;
                                if (exception != null) {
                                    if (checkIOStandardForExpansion(child, exception)) {
                                        newChildType = exception.getFirst();
                                    }
                                }
                                if (newChildType != null) {
                                    expandMacroCellType(newChildType, child, macros, netlistPrims);
                                }
                            }
                        }
                    }
                }
            }
            for (EDIFCell cell : lib.getCells()) {
                for (EDIFNet net : cell.getNets()) {
                    for (EDIFPortInst portInst : net.getPortInsts()) {
                        EDIFCell parent = portInst.getPort().getParentCell();
                        assert (parent.getLibrary().getCell(parent.getName()) == parent);
                    }
                }
            }
        }
    }

    private Boolean checkIOStandardForExpansion(EDIFCellInst inst, Pair<String, EnumSet<IOStandard>> exception) {
        Boolean expand = null;
        for (EDIFPropertyValue value : getIOStandards(inst)) {
            IOStandard ioStandard = IOStandard.valueOf(value.getValue());
            boolean contained = exception.getSecond().contains(ioStandard);
            if (expand == null) {
                expand = contained;
            } else {
                if (expand != contained) {
                    throw new RuntimeException("ERROR: EDIFCellInst '" + inst + "' has conflicting IOSTANDARDs "
                            + "propagated from multiple top-level nets. Consider uniquifying the EDIFNetlist to "
                            + "enable correct macro expansion.");
                }
            }
        }
        return expand;
    }

    private void expandMacroCellType(String newCellType, EDIFCellInst instToExpand, EDIFLibrary macros,
            EDIFLibrary netlistPrims) {
        EDIFCell newCell = netlistPrims.getCell(newCellType);
        if (newCell == null) {
            EDIFCell macro = macros.getCell(newCellType);
            if (macro == null) {
                throw new RuntimeException("failed to find cell macro " + newCellType + ", we are in "
                        + instToExpand.getParentCell().getLibrary().getName());
            }
            primsToRemoveOnCollapse.add(newCellType);
            newCell = new EDIFCell(netlistPrims, macro, newCellType);
            for (EDIFCellInst childInst : newCell.getCellInsts()) {
                EDIFCell primCell = netlistPrims.getCell(childInst.getCellType().getName());
                if (primCell == null) {
                    primCell = new EDIFCell(netlistPrims, childInst.getCellType());
                    primsToRemoveOnCollapse.add(childInst.getCellType().getName());
                }
                childInst.setCellType(primCell);
            }
        }
        assert (newCell == netlistPrims.getCell(newCellType));
        instToExpand.setCellType(newCell);
    }

    /**
     * Collapses any macro primitives back into their primitive state.
     * Performs the opposite of {@link EDIFNetlist#expandMacroUnisims(Series)}.
     * @param series The architecture series targeted by this netlist.
     */
    public void collapseMacroUnisims(Series series) {
        EDIFLibrary macros = Design.getMacroPrimitives(series);
        EDIFLibrary prims = getHDIPrimitivesLibrary();
        ArrayList<EDIFCell> reinsert = new ArrayList<EDIFCell>();
        Map<String, Pair<String, EnumSet<IOStandard>>> seriesMacroCollapseExceptionMap =
                macroCollapseExceptionMap.getOrDefault(series, Collections.emptyMap());
        Map<EDIFCell, EDIFCell> updateCellTypes = new IdentityHashMap<>();
        for (EDIFCell cell : prims.getCells()) {
            if (macros.containsCell(cell.getName())) {
                cell.makePrimitive();
                Pair<String, EnumSet<IOStandard>> exception = seriesMacroCollapseExceptionMap.get(cell.getName());
                if (exception != null) {
                    EDIFCell existingCell = prims.getCell(exception.getFirst());
                    if (existingCell != null) {
                        // Existing cell (e.g. OBUFDS) already exists/used in primitives library
                        // thus cannot simply rename it (e.g. from OBUFDS_DUAL_BUF)
                        updateCellTypes.put(cell, existingCell);
                    } else {
                        cell.rename(exception.getFirst());
                        reinsert.add(cell);
                    }
                }
            }
        }

        if (!updateCellTypes.isEmpty()) {
            // Update all cell references
            for (EDIFLibrary lib : getLibraries()) {
                if (lib.isHDIPrimitivesLibrary())
                    continue;
                for (EDIFCell cell : new ArrayList<>(lib.getCells())) {
                    for (EDIFCellInst inst : cell.getCellInsts()) {
                        EDIFCell newCellType = updateCellTypes.get(inst.getCellType());
                        if (newCellType != null) {
                            inst.setCellType(newCellType);
                        }
                    }
                }
            }
        }

        for (EDIFCell cell : reinsert) {
            prims.removeCell(cell);
            prims.addCell(cell);
        }

        for (String name : primsToRemoveOnCollapse) {
            prims.removeCell(name);
        }
    }

    /**
     * Keeps track of the original source directory from where this EDIF file was loaded.
     * @return Original directory path from where the EDIF file was loaded
     */
    public String getOrigDirectory() {
        return origDirectory;
    }

    protected void setOrigDirectory(String origDirectory) {
        this.origDirectory = origDirectory;
    }

    /**
     * Gets the list of EDN filenames that were present in the original directory where the EDIF
     * file was loaded from.  These may be important when loading a netlist/checkpoint back into
     * Vivado.
     * @return A list of EDN filenames that may populate encrypted cells within the netlist.
     */
    public List<String> getEncryptedCells() {
        return encryptedCells != null ? encryptedCells : Collections.emptyList();
    }

    public void setEncryptedCells(List<String> encryptedCells) {
        if (encryptedCells == null || encryptedCells.isEmpty()) {
            this.encryptedCells = null;
        } else {
            this.encryptedCells = encryptedCells;
        }
    }

    public void addEncryptedCells(List<String> encryptedCells) {
        if (encryptedCells == null || encryptedCells.size() == 0) {
            return;
        }
        if (this.encryptedCells == null) {
            setEncryptedCells(encryptedCells);
            return;
        }
        this.encryptedCells.addAll(encryptedCells);
    }

    public static final String READ_EDIF_CMD = "read_edif ";

    /**
     * Parses a Tcl load script generated by {@link com.xilinx.rapidwright.edif.EDIFTools#writeTclLoadScriptForPartialEncryptedDesigns(EDIFNetlist, Path, String)}.
     * and appends them to this netlist (useful for when merging designs).
     * @param tclPath Path to the existing Tcl load script for the accompanying DCP file
     */
    public void addTclLoadEncryptedCells(Path tclPath) {

        List<String> encryptedCells = new ArrayList<>();
        for (String line : FileTools.getLinesFromTextFile(tclPath.toFile().getAbsolutePath())) {
            if (line.startsWith(READ_EDIF_CMD) && line.endsWith(".edn")) {
                String ednFileName = line.trim().substring(READ_EDIF_CMD.length());
                encryptedCells.add(ednFileName.trim());
            }
        }
        addEncryptedCells(encryptedCells);
    }

    public static EDIFNetlist readBinaryEDIF(Path path) {
        return BinaryEDIFReader.readBinaryEDIF(path);
    }

    public static EDIFNetlist readBinaryEDIF(String fileName) {
        return BinaryEDIFReader.readBinaryEDIF(fileName);
    }

    public void writeBinaryEDIF(Path path) {
        BinaryEDIFWriter.writeBinaryEDIF(path, this);
    }
    public void writeBinaryEDIF(OutputStream os) {
        BinaryEDIFWriter.writeBinaryEDIF(os, this);
    }

    public void writeBinaryEDIF(String fileName) {
        BinaryEDIFWriter.writeBinaryEDIF(fileName, this);
    }

    /**
     * Checks a flag indicating if this netlist is currently tracking changes to its EDIFCells.
     * Modified EDIFCells are tracked in a set which can be queried with {@link #getModifiedCells()}.
     * EDIFCells are determined as modified if one of the following is true:
     *   (1) A port was removed, added or modified
     *   (2) A net was removed, added or modified
     *   (3) An instance was removed, added or modified
     * @return True if this netlist is tracking EDIFCell changes, false otherwise.
     */
    public boolean isTrackingCellChanges() {
        return trackCellChanges;
    }

    /**
     * Flag to track changes to EDIFCell changes to this netlist. See {@link #isTrackingCellChanges()}
     * @param trackCellChanges True to enable tracking of EDIFCells, false to stop tracking
     */
    public void setTrackCellChanges(boolean trackCellChanges) {
        this.trackCellChanges = trackCellChanges;
    }

    public void trackChange(EDIFCell cell, EDIFChangeType type, String objectName) {
        if (isTrackingCellChanges()) {
            addTrackingChange(cell, new EDIFChange(type, objectName));
        }
    }

    public void addTrackingChange(EDIFCell cell, EDIFChange change) {
        getModifiedCells().computeIfAbsent(cell, l -> new ArrayList<>()).add(change);
    }

    public Map<EDIFCell, List<EDIFChange>> getModifiedCells() {
        if (modifiedCells == null) modifiedCells = new HashMap<>();
        return modifiedCells;
    }

    /**
     * Helper method to get the set of IOStandard properties of an EDIFCellInst;
     * should one not exist, fallback to the IOStandard property on
     * the EDIFNet object(s) connected to the top-level output port(s) that it drives.
     * On the first time a fallback is considered, a map is initialized
     * {@link #cellInstIOStandardFallback} to speed up future queries.
     * This map will not be updated upon connecting a new EDIFNet nor
     * if a new property was added to the existing EDIFNet --- for this
     * map to be re-initialized use {@link #resetCellInstIOStandardFallbackMap}.
     * @param eci EDIFCellInst object.
     * @return Collection of EDIFPropertyValue describing its IOStandard.
     *         Returns a one-element collection containing EDIFNetlist.DEFAULT_PROP_VALUE
     *         if no value found.
     */
    public Collection<EDIFPropertyValue> getIOStandards(EDIFCellInst eci) {
        EDIFPropertyValue value = eci.getIOStandard();
        if (!value.equals(DEFAULT_PROP_VALUE)) {
            return Arrays.asList(value);
        }

        if (cellInstIOStandardFallback == null) {
            cellInstIOStandardFallback = new HashMap<>();

            Device device = getDevice();
            if (device != null) {
                EDIFLibrary macrosLibrary = Design.getMacroPrimitives(device.getSeries());

                EDIFHierCellInst topEhci = getTopHierCellInst();
                for (EDIFPort ep : getTopCell().getPorts()) {
                    if (ep.isInput()) {
                        // Only propagate backward from output and inout ports
                        // (propagating forwards from input/inout ports not
                        // currently handled yet)
                        continue;
                    }

                    for (EDIFNet en : ep.getInternalNets()) {
                        if (en == null) {
                            continue;
                        }
                        EDIFPropertyValue netEpv = en.getIOStandard();
                        if (netEpv == DEFAULT_PROP_VALUE) {
                            // Net has no IOStandard
                            continue;
                        }
                        EDIFHierNet ehn = new EDIFHierNet(topEhci, en);
                        for (EDIFHierPortInst ehpi : ehn.getLeafHierPortInsts(true, false)) {
                            assert(ehpi.isOutput());

                            EDIFCellInst driverEci = ehpi.getPortInst().getCellInst();
                            EDIFCell driverParent = driverEci.getParentCell();
                            if (driverParent != null && driverParent.getLibrary() == macrosLibrary) {
                                // Driver cell instance is a primitive inside an expanded macro,
                                // use this macro instead
                                driverEci = ehpi.getHierarchicalInst().getInst();
                            }

                            EDIFPropertyValue driverEpv = driverEci.getIOStandard();
                            if (!driverEpv.equals(DEFAULT_PROP_VALUE)) {
                                if (driverEpv.equals(netEpv)) {
                                    // Cell and Net IOStandards match
                                    continue;
                                }
                                throw new RuntimeException("ERROR: EDIFCellInst '" + driverEci + "' has a conflicting IOSTANDARD" +
                                        " with EDIFHierNet '" + ehn + "'.");
                            }

                            cellInstIOStandardFallback.computeIfAbsent(driverEci, (k) -> new HashSet<>())
                                .add(netEpv);
                        }
                    }
                }
            }
        }
        return cellInstIOStandardFallback.getOrDefault(eci, defaultPropValueAsCollection);
    }

    /**
     * Reset the fallback map used by {@link #getIOStandards} so that it may be
     * re-initialized upon next use.
     */
    public void resetCellInstIOStandardFallbackMap() {
        cellInstIOStandardFallback = null;
    }

    public static void main(String[] args) throws FileNotFoundException {
        CodePerfTracker t = new CodePerfTracker("EDIF Import/Export", true);
        t.start("Read EDIF");
        EDIFParser p = new EDIFParser(args[0]);
        EDIFNetlist n = p.parseEDIFNetlist();
        t.stop().start("Export EDIF");
        n.exportEDIF(args[1]);
        t.stop().printSummary();
    }
}
