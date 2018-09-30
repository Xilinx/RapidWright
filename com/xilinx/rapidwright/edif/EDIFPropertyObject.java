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
import java.util.Collections;
import java.util.Map;
import java.util.Map.Entry;

/**
 * All EDIF netlist objects that can possess properties inherit from this 
 * class. 
 * Created on: May 12, 2017
 */
public class EDIFPropertyObject extends EDIFName {

	private Map<EDIFName,EDIFPropertyValue> properties;
	
	private String owner;
	
	private static EDIFName tmp = new EDIFName();
	
	public EDIFPropertyObject(String name){
		super(name);
	}
	
	protected EDIFPropertyObject(){
		
	}
	
	/**
	 * Convenience property creator. 
	 * @param key Key value (to be wrapped in an EDIFName)
	 * @param value The value of the property
	 * @param type The type of value (string, boolean, integer)
	 * @return The previous property value stored under the provided key
	 */
	public EDIFPropertyValue addProperty(String key, String value, EDIFValueType type){
		EDIFName k = new EDIFName(key);
		EDIFPropertyValue p = new EDIFPropertyValue(value, type);
		return addProperty(k,p);
	}
	
	/**
	 * Convenience property creator for string types
	 * @param key Key value (to be wrapped in an EDIFName)
	 * @param value The value of the property
	 * @return The previous property value stored under the provided key
	 */
	public EDIFPropertyValue addProperty(String key, String value){
		EDIFName k = new EDIFName(key);
		EDIFPropertyValue p = new EDIFPropertyValue(value, EDIFValueType.STRING);
		return addProperty(k,p);
	}
	
	/**
	 * Convenience property creator for integers 
	 * @param key Key value (to be wrapped in an EDIFName)
	 * @param value The value of the property
	 * @return The previous property value stored under the provided key
	 */
	public EDIFPropertyValue addProperty(String key, int value){
		EDIFName k = new EDIFName(key);
		EDIFPropertyValue p = new EDIFPropertyValue(Integer.toString(value), EDIFValueType.INTEGER);
		return addProperty(k,p);
	}
	
	/**
	 * Convenience property creator for booleans 
	 * @param key Key value (to be wrapped in an EDIFName)
	 * @param value The value of the property
	 * @return The previous property value stored under the provided key
	 */
	public EDIFPropertyValue addProperty(String key, boolean value){
		EDIFName k = new EDIFName(key);
		EDIFPropertyValue p = new EDIFPropertyValue(value ? "TRUE" : "FALSE", EDIFValueType.BOOLEAN);
		return addProperty(k,p);
	}
	
	/**
	 * Adds the property entry mapping for this object.
	 * @param key Key entry for the property
	 * @param value Value entry for the property
	 * @return Old property value for the provided key
	 */
	public EDIFPropertyValue addProperty(EDIFName key, EDIFPropertyValue value){
		if(properties == null) properties = getNewMap();
		return properties.put(key, value);
	}
	
	public void addProperties(Map<EDIFName,EDIFPropertyValue> properties){
		for(Entry<EDIFName,EDIFPropertyValue> p : properties.entrySet()){
			addProperty(p.getKey(),p.getValue());
		}
	}
	
	public EDIFPropertyValue getProperty(String key){
		if(properties == null) return null;
		String edifName = EDIFTools.makeNameEDIFCompatible(key);
		tmp.setName(key);
		if(!edifName.equals(key)) tmp.setEDIFRename(edifName);
		EDIFPropertyValue val = properties.get(tmp);
		tmp.setEDIFRename(null);
		return val;
	}
		
	/**
	 * @return the properties
	 */
	public Map<EDIFName, EDIFPropertyValue> getProperties() {
		if(properties == null) return Collections.emptyMap();
		return properties;
	}

	/**
	 * @param properties the properties to set
	 */
	public void setProperties(Map<EDIFName, EDIFPropertyValue> properties) {
		this.properties = properties;
	}

	public void exportEDIFProperties(Writer wr, String indent) throws IOException{
		if(properties == null) return;
		for(Entry<EDIFName, EDIFPropertyValue> e : properties.entrySet()){
			wr.write(indent);
			wr.write("(property ");
			e.getKey().exportEDIFName(wr);
			wr.write(" ");
			e.getValue().writeEDIFString(wr);
			if(owner != null){
				wr.write(" (owner \"");
				wr.write(owner);
				wr.write("\")");
			}
			wr.write(")\n");
		}
	}

	/**
	 * @return the owner
	 */
	public String getOwner() {
		return owner;
	}

	/**
	 * @param owner the owner to set
	 */
	public void setOwner(String owner) {
		this.owner = owner;
	}
}
