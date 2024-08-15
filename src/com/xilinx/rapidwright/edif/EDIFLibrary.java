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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import com.xilinx.rapidwright.util.NoCloseOutputStream;
import com.xilinx.rapidwright.util.ParallelDCPInput;
import com.xilinx.rapidwright.util.ParallelDCPOutput;
import com.xilinx.rapidwright.util.ParallelismTools;

/**
 * Keeps track of a set of {@link EDIFCell} objects
 * that are part of a netlist.
 *
 * Created on: May 11, 2017
 */
public class EDIFLibrary extends EDIFName {

    private EDIFNetlist netlist;

    private Map<String,EDIFCell> cells;

    public EDIFLibrary(String name) {
        super(name);
    }

    /**
     * Shallow copy constructor - Creates a new EDIFLibrary object containing
     * shallow copies of its contained EDIFCell-s.
     *
     * @param copy The original library
     */
    public EDIFLibrary(EDIFLibrary copy) {
        super(copy.getName());

        for (Map.Entry<String,EDIFCell> e : copy.getCellMap().entrySet()) {
            addCell(e.getValue());
        }
    }

    protected EDIFLibrary() {

    }

    /**
     * Adds the provided cell to the library. All cells must be unique by their name.
     * If provided cell is already attached to a library, make a shallow copy.
     * @param cell The cell to add to the library.
     * @return The cell that has been added.
     */
    public EDIFCell addCell(EDIFCell cell) {
        if (cells == null) cells = getNewMap();
        return cells.compute(cell.getName(), (k,v) -> {
            if (v == null) {
                v = (cell.getLibrary() != null) ? new EDIFCell(cell) : cell;
                v.setLibrary(this);
                return v;
            }
            if (v == cell) {
                return v;
            }
            throw new RuntimeException("ERROR: Failed to add cell " +
                    cell.getName() + " to library " + getName()+". The library "
                    + "already contains a cell with the same name.");
        });
    }

    private String findUniqueCellName(String name) {
        int counter = 0;
        String counterName = name; //First try without suffix
        while (cells.containsKey(counterName)) {
            counter++;
            counterName = name+"_"+counter;
        }
        return counterName;
    }

    /**
     * Adds the provided cell to the library. If a cell by this name already exists, append a suffix to uniqueify the
     * name.
     * @param cell The cell to add to the library.
     * @param preferredSuffix If a suffix needs to be appended for uniquification, use this one
     * @return The cell that has been added.
     */
    public EDIFCell addCellRenameDuplicates(EDIFCell cell, String preferredSuffix) {
        if (cells == null) cells = getNewMap();
        cell.setLibrary(this);

        EDIFCell collision = cells.put(cell.getName(), cell);
        if (collision == null) {
            return cell;
        }
        //Restore the old mapping
        cells.put(cell.getName(), collision);

        if (preferredSuffix == null) {
            preferredSuffix = "collisionRename";
        }
        String newName = findUniqueCellName(cell.getName()+"_RW_"+preferredSuffix);
        System.err.println("EDIF library "+getName()+" contains cells with same name \""+cell.getName()+"\". Changing name of one of those instances to "+newName);
        cell.setName(newName);

        cells.put(newName, cell);
        return cell;
    }

    /**
     * Gets the name of the cell using the EDIF name (rename construct).  This is
     * to avoid collisions that Vivado generates with parameterized cells (end with _HDI_###).
     * Note that
     * @param name The name of the cell as would be returned by getLegalEDIFName().
     * When the original name was already legal, it is the same.
     * @return The cell in the library by the given legal EDIF name, or null if none exists.
     */
    public EDIFCell getCell(String name) {
        return cells == null ? null : cells.get(name);
    }

    /**
     * @return the cells
     */
    public Collection<EDIFCell> getCells() {
        return cells == null ? Collections.emptyList() : cells.values();
    }

    /**
     * @return the netlist
     */
    public EDIFNetlist getNetlist() {
        return netlist;
    }

    /**
     * @param netlist the netlist to set
     */
    public void setNetlist(EDIFNetlist netlist) {
        if (netlist == null) {
            throw new RuntimeException("ERROR: netlist argument cannot be null.");
        }
        if (this.netlist != null && this.netlist != netlist) {
            throw new RuntimeException("ERROR: EDIFLibrary is already attached to a netlist. Call EDIFNetlist.removeLibrary() first.");
        }

        this.netlist = netlist;
    }

    protected void clearNetlist() {
        this.netlist = null;
    }

    /**
     * Removes the cell from the library.  Uses the legal EDIF name as the key.
     * @param cell The cell to remove.
     * @return The removed cell.
     */
    public EDIFCell removeCell(EDIFCell cell) {
        return removeCell(cell.getName());
    }

    /**
     * Removes the cell by name
     * @param name The name of the cell to remove
     * @return The removed cell, or null if it did not exist in the library.
     */
    public EDIFCell removeCell(String name) {
        EDIFCell cell = cells == null ? null : cells.remove(name);
        if (cell != null) {
            cell.clearLibrary();
        }
        return cell;
    }

    /**
     * Checks the library based on the legal EDIF name (rename) if the cell
     * is stored within.
     * @param cell The cell in question.
     * @return True if the cell exists in the library, False otherwise.
     */
    public boolean containsCell(EDIFCell cell) {
        return containsCell(cell.getName());
    }

    /**
     * Checks if the library contains a cell with the name provided.
     * @param name The name of the cell to query in the library.
     * @return True if a cell by such name was found in the library, False otherwise.
     */
    public boolean containsCell(String name) {
        return cells == null ? false : cells.containsKey(name);
    }

    /**
     * Gets and returns the current map of cells in the library.  The cells
     * are keyed by the legal EDIF name of the cell.
     * @return The map containing the cells for this library.
     */
    public Map<String,EDIFCell> getCellMap() {
        return cells == null ? Collections.emptyMap() : cells;
    }

    /**
     * Creates a list of all cells that have references outside of this
     * library.
     * @return A newly created list of all cells that contains any instances
     * of cells not located within this library.
     */
    public List<EDIFCell> getExternallyReferencedCells() {
        List<EDIFCell> list = new ArrayList<>();
        for (EDIFCell c : getCells()) {
            for (EDIFCellInst i : c.getCellInsts()) {
                if (!containsCell(i.getCellType())) {
                    list.add(i.getCellType());
                }
            }
        }
        return list;
    }

    /**
     * Creates a collections of libraries that are referenced by cell
     * instances within this library.
     * @return A collection of libraries that are referenced by
     * cell instances found within cells of this library.
     */
    public Collection<EDIFLibrary> getExternallyReferencedLibraries() {
        Set<EDIFLibrary> set = new HashSet<>();
        for (EDIFCell c : getExternallyReferencedCells()) {
            set.add(c.getLibrary());
        }
        return set;
    }

    /**
     * This method prepares all the cells in the library for a merger with another
     * library by adding a unique prefix to all cell names.  This method aims to
     * address EDIF naming convention.
     * @param prefix The prefix to add to all cells
     */
    public void uniqueifyCellsWithPrefix(String prefix) {
        ArrayList<EDIFCell> renamedCells = new ArrayList<>(getCells());
        cells.clear();
        for (EDIFCell c : renamedCells) {
            c.setName(prefix + c.getName());
            addCell(c);
        }
    }

    /**
     * Creates an ordered list of cells such that each cell that appears
     * in the list only references cells that have already been seen in
     * the list. This is a requirement when exporting the EDIF to a file.
     * @param stable makes sure that the list is always the same for the same input
     * @return The ordered list.
     */
    public List<EDIFCell> getValidCellExportOrder(boolean stable) {
        Predicate<EDIFCell> recurseIf = (c) -> c.getLibrary() == this;
        return EDIFNetlist.getValidCellExportOrder(getCells(), stable, recurseIf);
    }

    /**
     * Checks if this library is the work library or that the name
     * matches {@link EDIFTools#EDIF_LIBRARY_WORK_NAME}.
     * @return True if this is the work library, false otherwise.
     */
    public boolean isWorkLibrary() {
        return getName().equals(EDIFTools.EDIF_LIBRARY_WORK_NAME);
    }

    /**
     * Checks if this library is the HDI primitives library or that the name
     * matches {@link EDIFTools#EDIF_LIBRARY_HDI_PRIMITIVES_NAME}.
     * @return True if this is the HDI primitives library, false otherwise.
     */
    public boolean isHDIPrimitivesLibrary() {
        return getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
    }

    private static final byte[] EXPORT_CONST_LIBRARY_START = "  (Library ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EXPORT_CONST_TECHNOLOGY = "\n    (edifLevel 0)\n    (technology (numberDefinition ))\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] EXPORT_CONST_LIBRARY_END = "  )\n".getBytes(StandardCharsets.UTF_8);

    void exportEDIF(List<EDIFCell> cells, OutputStream os, boolean writeHeader, boolean writeFooter, EDIFWriteLegalNameCache<?> cache, boolean stable) throws IOException {
        if (writeHeader) {
            os.write(EXPORT_CONST_LIBRARY_START);
            exportEDIFName(os, cache);
            os.write(EXPORT_CONST_TECHNOLOGY);
        }
        for (EDIFCell c : cells) {
            c.exportEDIF(os, cache, stable);
        }
        if (writeFooter) {
            os.write(EXPORT_CONST_LIBRARY_END);
        }
    }

    public void exportEDIF(OutputStream os, EDIFWriteLegalNameCache<?> cache, boolean stable) throws IOException {
        exportEDIF(getValidCellExportOrder(stable), os, true, true, cache, stable);
    }
    public void exportEDIF(OutputStream os, EDIFWriteLegalNameCache<?> cache) throws IOException {
        exportEDIF(os, cache, false);
    }

    public List<Future<ParallelDCPInput>> exportEDIF(EDIFWriteLegalNameCache<?> cache) throws IOException{
        if (!ParallelismTools.getParallel()) {
            throw new RuntimeException();
        }

        List<EDIFCell> validCellOrder = getValidCellExportOrder(false);
        final int chunkSize = 256;

        List<Future<ParallelDCPInput>> streamFutures = new ArrayList<>();
        for (long i = 0; i < validCellOrder.size(); i += chunkSize) {
            final boolean firstChunk = (i == 0);
            final boolean lastChunk = (i + chunkSize >= validCellOrder.size());
            List<EDIFCell> chunk = validCellOrder.subList((int) i, (int) (lastChunk ? validCellOrder.size() : i + chunkSize));

            streamFutures.add(ParallelismTools.submit(
                    () -> ParallelDCPOutput.newStream((os) -> {
                        try (BufferedOutputStream bs = new BufferedOutputStream(new NoCloseOutputStream(os))) {
                            exportEDIF(chunk, bs, firstChunk, lastChunk, cache, false);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
            ));
        }

        return streamFutures;
    }
}
