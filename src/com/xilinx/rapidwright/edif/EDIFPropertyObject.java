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
     * Helper method to get the IOStandard property, with consideration for
     * an upper-case key name.
     * @return EDIFPropertyValue describing its IOStandard. Returns
     *         EDIFNetlist.DEFAULT_PROP_VALUE if no value found.
     */
    public EDIFPropertyValue getIOStandard() {
        EDIFPropertyValue value = getProperty(EDIFNetlist.IOSTANDARD_PROP);
        if (value != null) {
            return value;
        }

        value = getProperty(EDIFNetlist.IOSTANDARD_PROP.toUpperCase());
        if (value != null) {
            return value;
        }

        return EDIFNetlist.DEFAULT_PROP_VALUE;
    }

    /**
     * Convenience property creator.
     * @param key Key value (to be wrapped in an EDIFName)
     * @param value The value of the property
     * @param type The type of value (string, boolean, integer)
     * @return The previous property value stored under the provided key
     */
    public EDIFPropertyValue addProperty(String key, String value, EDIFValueType type) {
        EDIFPropertyValue p = new EDIFPropertyValue(value, type);
        return addProperty(key, p);
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
        EDIFPropertyValue p = new EDIFPropertyValue(Integer.toString(value), EDIFValueType.INTEGER);
        return addProperty(key, p);
    }

    /**
     * Convenience property creator for booleans
     * @param key Key value (to be wrapped in an EDIFName)
     * @param value The value of the property
     * @return The previous property value stored under the provided key
     */
    public EDIFPropertyValue addProperty(String key, boolean value) {
        EDIFPropertyValue p = new EDIFPropertyValue(value ? "true" : "false", EDIFValueType.BOOLEAN);
        return addProperty(key, p);
    }

    /**
     * Convenience method to remove a property
     * @param key Name of the property
     * @return The old property value or null if none existed
     */
    public EDIFPropertyValue removeProperty(String key) {
        if (properties == null) return null;
        return properties.remove(key);
    }

    /**
     * Adds the property entry mapping for this object.
     * @param key Key entry for the property
     * @param value Value entry for the property
     * @return Old property value for the provided key
     */
    public EDIFPropertyValue addProperty(String key, EDIFPropertyValue value) {
        if (properties == null) properties = getNewMap();
        return properties.put(key, value);
    }

    public EDIFPropertyValue getProperty(String key) {
        if (properties == null) return null;
        return properties.get(key);
    }

    /**
     * Creates a completely new copy of the map
     * @return
     */
    public Map<String, EDIFPropertyValue> createDuplicatePropertiesMap() {
        if (properties == null) return null;
        Map<String, EDIFPropertyValue> newMap = new HashMap<>();
        for (Entry<String, EDIFPropertyValue> e : properties.entrySet()) {
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
        if (properties == null) return;
        for (Entry<String, EDIFPropertyValue> e : EDIFTools.sortIfStable(properties, stable)) {
            try {
                os.write(indent);
                os.write(EXPORT_CONST_PROP_START);
                EDIFName.exportSomeEDIFName(os, e.getKey(), cache.getEDIFRename(e.getKey()));
                os.write(' ');
                e.getValue().writeEDIFString(os);
                if (e.getValue().getOwner() != null) {
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
