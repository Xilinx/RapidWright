/*
 * 
 * Copyright (c) 2017 Xilinx, Inc. 
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
/**
 * 
 */
package com.xilinx.rapidwright.edif;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps track of a set of {@link EDIFCell} objects 
 * that are part of a netlist.
 * 
 * Created on: May 11, 2017
 */
public class EDIFLibrary extends EDIFName {

	private EDIFNetlist netlist;
	
	private Map<String,EDIFCell> cells;
	
	public EDIFLibrary(String name){
		super(name);
	}
	
	protected EDIFLibrary(){
		
	}
	
	/**
	 * Adds the provided cell to the library. All cells 
	 * must be unique by their legal EDIF name (rename construct).  
	 * @param cell The cell to add to the library.
	 * @return The cell that has been added.
	 */
	public EDIFCell addCell(EDIFCell cell){
		if(cells == null) cells = getNewMap(); 
		EDIFCell collision = cells.put(cell.getLegalEDIFName(), cell);
		if(collision != null && cell != collision){
			throw new RuntimeException("ERROR: Failed to add cell " + 
				cell.getName() + " to library " + getName()+". The library "
				+ "already contains a cell with the same name.");
		}
		cell.setLibrary(this);
		return cell;
	}

	/**
	 * Gets the name of the cell using the EDIF name (rename construct).  This is 
	 * to avoid collisions that Vivado generates with parameterized cells (end with _HDI_###).
	 * Note that 
	 * @param legalEdifName The name of the cell as would be returned by getLegalEDIFName().  
	 * When the original name was already legal, it is the same.
	 * @return The cell in the library by the given legal EDIF name, or null if none exists.
	 */
	public EDIFCell getCell(String legalEdifName){
		return cells == null ? null : cells.get(legalEdifName);
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
		this.netlist = netlist;
	}
	
	/**
	 * Removes the cell from the library.  Uses the legal EDIF name as the key.
	 * @param cell The cell to remove.
	 * @return The removed cell.
	 */
	public EDIFCell removeCell(EDIFCell cell){
		return removeCell(cell.getLegalEDIFName());
	}
	
	/**
	 * Removes the cell by 'legal EDIF name' (rename construct)
	 * @param legalEdifName The legal EDIF name (rename) of the cell to remove
	 * @return The removed cell, or null if it did not exist in the library.
	 */
	public EDIFCell removeCell(String legalEdifName){
		return cells == null ? null : cells.remove(legalEdifName);
	}
	
	/**
	 * Checks the library based on the legal EDIF name (rename) if the cell
	 * is stored within.  
	 * @param cell The cell in question.
	 * @return True if the cell exists in the library, False otherwise.
	 */
	public boolean containsCell(EDIFCell cell){
		return containsCell(cell.getLegalEDIFName());
	}
	
	/**
	 * Checks if the library contains a cell with the legal EDIF Name provided.
	 * @param legalEDIFName The legal EDIF name of the cell to query in the library.
	 * @return True if a cell by such name was found in the library, False otherwise.
	 */
	public boolean containsCell(String legalEDIFName){
		return cells == null ? false : cells.containsKey(legalEDIFName);
	}
	
	/**
	 * Gets and returns the current map of cells in the library.  The cells
	 * are keyed by the legal EDIF name of the cell.
	 * @return The map containing the cells for this library.
	 */
	public Map<String,EDIFCell> getCellMap(){
		return cells == null ? Collections.emptyMap() : cells;
	}
	
	/**
	 * Creates a list of all cells that have references outside of this
	 * library.  
	 * @return A newly created list of all cells that contains any instances
	 * of cells not located within this library.
	 */
	public List<EDIFCell> getExternallyReferencedCells(){
		List<EDIFCell> list = new ArrayList<>();
		for(EDIFCell c : getCells()){
			for(EDIFCellInst i : c.getCellInsts()){
				if(!containsCell(i.getCellType())){
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
	public Collection<EDIFLibrary> getExternallyReferencedLibraries(){
		Set<EDIFLibrary> set = new HashSet<>();
		for(EDIFCell c : getExternallyReferencedCells()){
			set.add(c.getLibrary());
		}
		return set;
	}
	
	/**
	 * This method prepares all the cells in the library for a merger with another
	 * library by adding a unique prefix to all cell names.  This method aims to
	 * address EDIF naming convention.
	 * @param name The prefix to add to all cells
	 */
	public void uniqueifyCellsWithPrefix(String name){
		ArrayList<EDIFCell> renamedCells = new ArrayList<>(getCells());
		String validEDIFPrefix = EDIFTools.makeNameEDIFCompatible(name);
		cells.clear();
		for(EDIFCell c : renamedCells){
			c.setName(name + c.getName());
			if(c.getEDIFName() != null){
				c.setEDIFRename(validEDIFPrefix + c.getEDIFName()); 
			}
			addCell(c);
		}
	}
	
	/**
	 * Creates an ordered list of cells such that each cell that appears
	 * in the list only references cells that have already been seen in 
	 * the list.  This is a requirement when exporting the EDIF to a file.
	 * @return The ordered list.
	 */
	public List<EDIFCell> getValidCellExportOrder(){
		List<EDIFCell> visited = new ArrayList<>();
		Map<String,EDIFCell> yetToVisit = new HashMap<>(getCellMap());		
		while(!yetToVisit.isEmpty()){
			EDIFCell c = yetToVisit.values().iterator().next();
			visit(c, visited, yetToVisit);
		}
		
		return visited;
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

	private void visit(EDIFCell cell, List<EDIFCell> visited, Map<String,EDIFCell> yetToVisit){
		yetToVisit.remove(cell.getLegalEDIFName());
		for(EDIFCellInst i : cell.getCellInsts()){
			EDIFCell childCell = i.getCellType();
			if(containsCell(childCell) && yetToVisit.containsKey(childCell.getLegalEDIFName())){
				visit(childCell,visited,yetToVisit);
			}
		}
		visited.add(cell);
	}
	
	protected void ensureValidEDIFCellNames(){
		HashSet<String> names = new HashSet<>();
		for(EDIFCell c : getCells()){
			String setLegalName = c.getLegalEDIFName();
			if(!names.add(setLegalName.toLowerCase())){
				names.add(c.updateEDIFRename(netlist.nameSpaceUniqueCount++).toLowerCase());
			}
		}
	}
	

	public void exportEDIF(Writer bw) throws IOException{
		bw.write("  (Library ");
		exportEDIFName(bw);
		bw.write("\n    (edifLevel 0)\n");
		bw.write("    (technology (numberDefinition ))\n");
		for(EDIFCell cell : getValidCellExportOrder()){
			cell.exportEDIF(bw);
		}
		bw.write("  )\n");
	}
}
