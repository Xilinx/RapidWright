/*
 * 
 * Copyright (c) 2017-2022, Xilinx, Inc. 
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
/**
 * 
 */
package com.xilinx.rapidwright.edif;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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

    public EDIFPropertyObject(String name) {
        super(name);
    }
    
    public EDIFPropertyObject(EDIFPropertyObject obj) {
        super(obj);
        properties = obj.createDuplicatePropertiesMap();
    }
    
    protected EDIFPropertyObject() {
        
    }
    
    /**
     * Convenience property creator. 
     * @param key Key value (to be wrapped in an EDIFName)
     * @param value The value of the property
     * @param type The type of value (string, boolean, integer)
     * @return The previous property value stored under the provided key
     */
    public EDIFPropertyValue addProperty(String key, String value, EDIFValueType type) {
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
    public EDIFPropertyValue addProperty(String key, String value) {
        EDIFPropertyValue p = new EDIFPropertyValue(value, EDIFValueType.STRING);
        return addProperty(key,p);
    }
    
    /**
     * Convenience property creator for integers 
     * @param key Key value (to be wrapped in an EDIFName)
     * @param value The value of the property
     * @return The previous property value stored under the provided key
     */
    public EDIFPropertyValue addProperty(String key, int value) {
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
    public EDIFPropertyValue addProperty(String key, boolean value) {
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
        return properties.remove(key);
    }
    
    /**
     * Adds the property entry mapping for this object.
     * @param key Key entry for the property
     * @param value Value entry for the property
     * @return Old property value for the provided key
     */
    @Deprecated
    public EDIFPropertyValue addProperty(EDIFName key, EDIFPropertyValue value) {
        return addProperty(key.getName(), value);
    }

    /**
     * Adds the property entry mapping for this object.
     * @param key Key entry for the property
     * @param value Value entry for the property
     * @return Old property value for the provided key
     */
    public EDIFPropertyValue addProperty(String key, EDIFPropertyValue value) {
        if(properties == null) properties = getNewMap();
        return properties.put(key, value);
    }

    @Deprecated
    public void addProperties(Map<EDIFName,EDIFPropertyValue> properties) {
        for(Entry<EDIFName,EDIFPropertyValue> p : properties.entrySet()) {
            addProperty(p.getKey(),p.getValue());
        }
    }
    
    public EDIFPropertyValue getProperty(String key) {
        if(properties == null) return null;
        return properties.get(key);
    }
        
    /**
     * Get all properties. Because the internal representation has changed, this is read-only and
     * includes a conversion step.
     * Replaced by {@link #getPropertiesMap()}
     * @return the properties
     */
    @Deprecated
    public Map<EDIFName, EDIFPropertyValue> getProperties() {
        if (properties == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(properties.entrySet().stream()
                .collect(Collectors.toMap(e->new EDIFName(e.getKey()), Entry::getValue)));
    }

    /**
     * Creates a completely new copy of the map
     * @return
     */
    public Map<String, EDIFPropertyValue> createDuplicatePropertiesMap() {
        if(properties == null) return null;
        Map<String, EDIFPropertyValue> newMap = new HashMap<>();
        for(Entry<String, EDIFPropertyValue> e : properties.entrySet()) {
            newMap.put(e.getKey(), new EDIFPropertyValue(e.getValue()));
        }
        return newMap;
    }

    /**
     * Get all properties in native format
     */
    public Map<String, EDIFPropertyValue> getPropertiesMap() {
        if (properties == null) {
            return Collections.emptyMap();
        }
        return properties;
    }

    /**
     * This function does not work anymore and is only kept around to give users a hint on how to change their code.
     * Please use {@link #setPropertiesMap(Map)} instead.
     */
    @Deprecated
    public void setProperties(Map<EDIFName, EDIFPropertyValue> properties) {
        // We can't just copy the values from the user-supplied map into a Map<String, EDIFPropertyValue>. The user might
        // update the supplied map after calling this method. Those changes would not be reflected in the copied map.
        // In order to not silently change behaviour, let's just throw an exception.
        throw new RuntimeException("The internal representation of Properties has changed. Please use setPropertiesMap instead of this function.");
    }

    /**
     * Set all properties
     * @param properties the properties to set
     */
    public void setPropertiesMap(Map<String, EDIFPropertyValue> properties) {
        this.properties = properties;
    }

    public static final byte[] EXPORT_CONST_PROP_START = "(property ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_OWNER_START = " (owner \"".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_OWNER_END = "\")".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_PROP_END = ")\n".getBytes(StandardCharsets.UTF_8);

    public void exportEDIFProperties(OutputStream os, byte[] indent, EDIFWriteLegalNameCache<?> cache, boolean stable) throws IOException{
        if(properties == null) return;
        for(Entry<String, EDIFPropertyValue> e : EDIFTools.sortIfStable(properties, stable)) {
            try {
                os.write(indent);
                os.write(EXPORT_CONST_PROP_START);
                EDIFName.exportSomeEDIFName(os, e.getKey(), cache.getEDIFRename(e.getKey()));
                os.write(' ');
                e.getValue().writeEDIFString(os);
                if(e.getValue().getOwner() != null) {
                    os.write(EXPORT_CONST_OWNER_START);
                    os.write(e.getValue().getOwner().getBytes(StandardCharsets.UTF_8));
                    os.write(EXPORT_CONST_OWNER_END);
                }
                os.write(EXPORT_CONST_PROP_END);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }
}
