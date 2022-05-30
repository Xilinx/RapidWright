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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

/**
 * All EDIF netlist objects that can possess properties inherit from this 
 * class. 
 * Created on: May 12, 2017
 */
public class EDIFPropertyObject extends EDIFName {

	private Map<String,EDIFPropertyValue> properties;
	
	private String owner;
	
	public EDIFPropertyObject(String name){
		super(name);
	}
	
	public EDIFPropertyObject(EDIFPropertyObject obj) {
		super((EDIFName)obj);
		properties = new HashMap<>(obj.properties);
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
		EDIFPropertyValue p = new EDIFPropertyValue(value ? "true" : "false", EDIFValueType.BOOLEAN);
		return addProperty(k,p);
	}
	
	/**
	 * Convenience method to remove a property
	 * @param key Name of the property
	 * @return The old property value or null if none existed
	 */
	public EDIFPropertyValue removeProperty(String key) {
		if(properties == null) return null;
		EDIFName k = new EDIFName(key);
		return properties.remove(k);
	}
	
	/**
	 * Adds the property entry mapping for this object.
	 * @param key Key entry for the property
	 * @param value Value entry for the property
	 * @return Old property value for the provided key
	 */
	@Deprecated
	public EDIFPropertyValue addProperty(EDIFName key, EDIFPropertyValue value){
		return addProperty(key.getName(), value);
	}

	/**
	 * Adds the property entry mapping for this object.
	 * @param key Key entry for the property
	 * @param value Value entry for the property
	 * @return Old property value for the provided key
	 */
	@Deprecated
	public EDIFPropertyValue addProperty(String key, EDIFPropertyValue value){
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
		EDIFPropertyValue val = properties.get(key);
		return val;
	}
		
	/**
	 * @return the properties
	 */
	@Deprecated
	public Map<EDIFName, EDIFPropertyValue> getProperties() {
		if (properties == null) {
			return Collections.emptyMap();
		}
		return Collections.unmodifiableMap(properties.entrySet().stream().collect(Collectors.toMap(s->new EDIFName(s.getKey()), Entry::getValue)));
	}

	public Map<String, EDIFPropertyValue> getPropertiesNew() {
		return properties;
	}
	
	/**
	 * @param properties the properties to set
	 */
	public void setProperties(Map<EDIFName, EDIFPropertyValue> properties) {
		throw new RuntimeException("not implemented");
	}

	public void exportEDIFProperties(Writer wr, String indent, EDIFWriteLegalNameCache cache) throws IOException{
		if(properties == null) return;
		properties.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
			try {
				wr.write(indent);
				wr.write("(property ");
				EDIFName.exportSomeEDIFName(wr, e.getKey(), cache);
				wr.write(" ");
				e.getValue().writeEDIFString(wr);
				if(owner != null){
					wr.write(" (owner \"");
					wr.write(owner);
					wr.write("\")");
				}
				wr.write(")\n");
			} catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		});
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
