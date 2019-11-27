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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This class serves as the universal common ancestor for most all EDIF netlist
 * objects.  Primarily it serves to manage the regular name and legal EDIF 
 * rename if it exists.  
 * Created on: May 11, 2017
 */
public class EDIFName {
	/** Name of the EDIF object */
	private String name;
	/** Legal EDIF rename of the original name */
	private String edifRename;
	
	
	protected EDIFName(){
		
	}
	
	public EDIFName(String name){
		this.name = name;
		updateEDIFRename();
	}
	
	/**
	 * Forces the object to update its EDIF legal equivalent name based on 
	 * the current name.  If the name is the same, the edifRename will be
	 * null.
	 * @return The newly updated EDIF rename string.
	 */
	protected String updateEDIFRename(){
		String tmp = EDIFTools.makeNameEDIFCompatible(name); 
		if(!tmp.equals(name)){
			edifRename = tmp;
		}else{
			edifRename = null;
		}
		return edifRename;
	}
	
	protected String updateEDIFRename(int unique){
		updateEDIFRename();
		edifRename =  getLegalEDIFName() + "_" + unique;
		return edifRename;
	}
	
	public String getName(){
		return name;
	}
	
	protected String getEDIFName(){
		return edifRename;
	}
	
	protected void setName(String name){
		this.name = name;
	}
	
	protected void setEDIFRename(String edifRename){
		this.edifRename = edifRename;
	}
	
	public String getLegalEDIFName(){
		return edifRename == null ? name : edifRename;
	}
	
	/**
	 * Writes out valid EDIF syntax the name and/or rename of this object to
	 * the provided output writer.
	 * @param wr The writer to export the EDIF syntax to.
	 * @throws IOException
	 */
	public void exportEDIFName(Writer wr) throws IOException{
		if(edifRename == null) {
			wr.write(name);
			return;
		}
		wr.write("(rename ");
		wr.write(edifRename);
		wr.write(" \"");
		wr.write(name);
		wr.write("\")");
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((edifRename == null) ? 0 : edifRename.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		EDIFName other = (EDIFName) obj;
		if (edifRename == null) {
			if (other.edifRename != null)
				return false;
		} else if (!edifRename.equals(other.edifRename))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		return true;
	}
	
	public String toString(){
		return name;
	}
	
	public <K, V> Map<K, V> getNewMap(){
		return new LinkedHashMap<K,V>();
	}
}
