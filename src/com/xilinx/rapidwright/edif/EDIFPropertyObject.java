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
import java.util.Objects;

/**
 * All EDIF netlist objects that can possess properties inherit from this
 * class.
 * Created on: May 12, 2017
 */
public class EDIFPropertyObject extends EDIFName {

    /**
     * Maximum number of entries kept in the compact array representation before
     * the backing store is promoted to a {@link HashMap}. Chosen comfortably
     * above the typical EDIF object property count (the overwhelming majority
     * have fewer than 8) so promotion only affects rare large-property objects
     * (e.g. transceiver primitives), while bounding the worst-case linear-scan
     * length.
     */
    static final int PROMOTE_THRESHOLD = 16;

    /**
     * Inlined property storage. Rather than holding a separate map object per
     * netlist object (which would cost an extra object header + reference for
     * every property-bearing object), the properties are stored directly in this
     * single field, which is one of:
     * <ul>
     *   <li>{@code null} - no properties;</li>
     *   <li>an {@code Object[]} of alternating key/value entries
     *       ({@code [k0, v0, k1, v1, ...]}) - the memory-compact representation
     *       used for small property sets (lookup is a linear scan); or</li>
     *   <li>a {@code HashMap<String,EDIFPropertyValue>} - used once the entry
     *       count exceeds {@link #PROMOTE_THRESHOLD}, restoring amortized O(1)
     *       access for the rare large-property objects.</li>
     * </ul>
     * {@link #getPropertiesMap()} exposes a lightweight {@link EDIFPropertyMap}
     * view over this field that implements the full {@link Map} contract.
     *
     * This storage is not thread-safe (neither was the previous map); a single
     * EDIF object's properties are only ever mutated by one thread during parsing.
     */
    private Object propertyData;

    public EDIFPropertyObject(String name) {
        super(name);
    }

    public EDIFPropertyObject(EDIFPropertyObject obj) {
        super(obj);
        copyPropertiesFrom(obj);
    }

    protected EDIFPropertyObject() {

    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, EDIFPropertyValue> asMap(Object d) {
        return (HashMap<String, EDIFPropertyValue>) d;
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
        Object d = propertyData;
        if (d == null) return null;
        if (d instanceof HashMap) {
            return asMap(d).remove(key);
        }
        Object[] a = (Object[]) d;
        for (int i = 0; i < a.length; i += 2) {
            if (a[i].equals(key)) {
                EDIFPropertyValue old = (EDIFPropertyValue) a[i + 1];
                if (a.length == 2) {
                    propertyData = null;
                } else {
                    Object[] n = new Object[a.length - 2];
                    System.arraycopy(a, 0, n, 0, i);
                    System.arraycopy(a, i + 2, n, i, a.length - i - 2);
                    propertyData = n;
                }
                return old;
            }
        }
        return null;
    }

    /**
     * Adds the property entry mapping for this object.
     * @param key Key entry for the property
     * @param value Value entry for the property
     * @return Old property value for the provided key
     */
    public EDIFPropertyValue addProperty(String key, EDIFPropertyValue value) {
        Objects.requireNonNull(key, "EDIF property key cannot be null");
        Object d = propertyData;
        if (d == null) {
            propertyData = new Object[] { key, value };
            return null;
        }
        if (d instanceof HashMap) {
            return asMap(d).put(key, value);
        }
        Object[] a = (Object[]) d;
        for (int i = 0; i < a.length; i += 2) {
            if (a[i].equals(key)) {
                EDIFPropertyValue old = (EDIFPropertyValue) a[i + 1];
                a[i + 1] = value;
                return old;
            }
        }
        // New key. Promote to a HashMap first if we would exceed the compact threshold.
        if ((a.length >> 1) >= PROMOTE_THRESHOLD) {
            HashMap<String, EDIFPropertyValue> m = new HashMap<>(a.length);
            for (int i = 0; i < a.length; i += 2) {
                m.put((String) a[i], (EDIFPropertyValue) a[i + 1]);
            }
            m.put(key, value);
            propertyData = m;
            return null;
        }
        Object[] n = new Object[a.length + 2];
        System.arraycopy(a, 0, n, 0, a.length);
        n[a.length] = key;
        n[a.length + 1] = value;
        propertyData = n;
        return null;
    }

    public EDIFPropertyValue getProperty(String key) {
        Object d = propertyData;
        if (d == null) return null;
        if (d instanceof HashMap) {
            return asMap(d).get(key);
        }
        Object[] a = (Object[]) d;
        for (int i = 0; i < a.length; i += 2) {
            if (a[i].equals(key)) {
                return (EDIFPropertyValue) a[i + 1];
            }
        }
        return null;
    }

    /**
     * Gets the number of properties on this object without materializing a map.
     * @return The number of properties.
     */
    public int getPropertyCount() {
        Object d = propertyData;
        if (d == null) return 0;
        if (d instanceof HashMap) return asMap(d).size();
        return ((Object[]) d).length >> 1;
    }

    /**
     * Package-private raw accessor used by the {@link EDIFPropertyMap} view. The
     * returned object is {@code null}, an {@code Object[]} of alternating
     * key/value entries, or a {@code HashMap}.
     */
    protected Object getRawPropertyData() {
        return propertyData;
    }

    /**
     * Package-private bulk clear used by the {@link EDIFPropertyMap} view.
     */
    protected void clearProperties() {
        propertyData = null;
    }

    /**
     * Creates a completely new copy of the properties (with copied values).
     * @return A new map of the properties, or null if this object has none.
     */
    public Map<String, EDIFPropertyValue> createDuplicatePropertiesMap() {
        Object d = propertyData;
        if (d == null) return null;
        Map<String, EDIFPropertyValue> newMap = new HashMap<>(getPropertyCount() * 2);
        if (d instanceof HashMap) {
            for (Entry<String, EDIFPropertyValue> e : asMap(d).entrySet()) {
                newMap.put(e.getKey(), new EDIFPropertyValue(e.getValue()));
            }
        } else {
            Object[] a = (Object[]) d;
            for (int i = 0; i < a.length; i += 2) {
                newMap.put((String) a[i], new EDIFPropertyValue((EDIFPropertyValue) a[i + 1]));
            }
        }
        return newMap;
    }

    private void copyPropertiesFrom(EDIFPropertyObject obj) {
        Object d = obj.propertyData;
        if (d == null) return;
        if (d instanceof HashMap) {
            for (Entry<String, EDIFPropertyValue> e : asMap(d).entrySet()) {
                addProperty(e.getKey(), new EDIFPropertyValue(e.getValue()));
            }
        } else {
            Object[] a = (Object[]) d;
            for (int i = 0; i < a.length; i += 2) {
                addProperty((String) a[i], new EDIFPropertyValue((EDIFPropertyValue) a[i + 1]));
            }
        }
    }

    /**
     * Get all properties in native format.  Returns a live {@link Map} view over
     * this object's inlined property storage (mutations write through). An empty,
     * immutable map is returned when this object has no properties.
     */
    public Map<String, EDIFPropertyValue> getPropertiesMap() {
        if (propertyData == null) {
            return Collections.emptyMap();
        }
        return new EDIFPropertyMap(this);
    }

    /**
     * Set all properties.  The entries of the provided map are copied into this
     * object's inlined storage (the map reference itself is not retained).
     *
     * <p>The provided map is read in full <i>before</i> this object's existing
     * storage is replaced, so it is safe to pass this object's own live
     * {@link #getPropertiesMap()} view (it is detected and treated as a no-op).
     *
     * @param properties the properties to set
     */
    public void setPropertiesMap(Map<String, EDIFPropertyValue> properties) {
        // Passing this object's own live view is a no-op (the data is already ours).
        if (properties instanceof EDIFPropertyMap && ((EDIFPropertyMap) properties).isViewOf(this)) {
            return;
        }
        if (properties == null || properties.isEmpty()) {
            propertyData = null;
            return;
        }
        // Build the new backing from the source first; only then replace our own
        // storage. This keeps the operation correct even if 'properties' aliases
        // this object's current data (e.g. another live view of this object).
        int n = properties.size();
        if (n > PROMOTE_THRESHOLD) {
            HashMap<String, EDIFPropertyValue> m = new HashMap<>(n * 2);
            m.putAll(properties);
            propertyData = m;
        } else {
            Object[] a = new Object[n * 2];
            int i = 0;
            for (Entry<String, EDIFPropertyValue> e : properties.entrySet()) {
                a[i++] = e.getKey();
                a[i++] = e.getValue();
            }
            // Defensive against a source whose size() and entrySet() disagree.
            propertyData = (i == a.length) ? a : java.util.Arrays.copyOf(a, i);
        }
    }

    public static final byte[] EXPORT_CONST_PROP_START = "(property ".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_OWNER_START = " (owner \"".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_OWNER_END = "\")".getBytes(StandardCharsets.UTF_8);
    public static final byte[] EXPORT_CONST_PROP_END = ")\n".getBytes(StandardCharsets.UTF_8);

    public void exportEDIFProperties(OutputStream os, byte[] indent, EDIFWriteLegalNameCache<?> cache, boolean stable) throws IOException{
        if (propertyData == null) return;
        for (Entry<String, EDIFPropertyValue> e : EDIFTools.sortIfStable(getPropertiesMap(), stable)) {
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
