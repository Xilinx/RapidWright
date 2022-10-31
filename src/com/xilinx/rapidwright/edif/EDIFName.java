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
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * This class serves as the universal common ancestor for most all EDIF netlist
 * objects.  Primarily it serves to manage the regular name and legal EDIF
 * rename if it exists.
 * Created on: May 11, 2017
 */
public class EDIFName implements Comparable<EDIFName> {
    /** Name of the EDIF object */
    private String name;


    protected EDIFName() {

    }

    public EDIFName(String name) {
        this.name = name;
    }

    /**
     * Copy constructor
     * @param edifName
     */
    public EDIFName(EDIFName edifName) {
        this.name = edifName.name;
    }

    public String getName() {
        return name;
    }

    protected void setName(String name) {
        this.name = name;
    }

    public static final byte[] EXPORT_CONST_RENAME_START = "(rename ".getBytes(StandardCharsets.UTF_8);


    public static void exportSomeEDIFName(OutputStream os, String name, byte[] legalName) throws IOException {
        if (legalName == null) {
            os.write(name.getBytes(StandardCharsets.UTF_8));
            return;
        }
        os.write(EXPORT_CONST_RENAME_START);
        os.write(legalName);
        os.write(' ');
        os.write('"');
        os.write(name.getBytes(StandardCharsets.UTF_8));
        os.write('"');
        os.write(')');
    }

    /**
     * Writes out valid EDIF syntax the name and/or rename of this object to
     * the provided output writer.
     * @param os The stream to export the EDIF syntax to.
     * @throws IOException
     */
    public void exportEDIFName(OutputStream os, EDIFWriteLegalNameCache<?> cache) throws IOException{
        exportSomeEDIFName(os, getName(), cache.getEDIFRename(getName()));
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        return Objects.hashCode(name);
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
        if (name == null) {
            if (other.name != null)
                return false;
        } else if (!name.equals(other.name))
            return false;
        return true;
    }

    public String toString() {
        return name;
    }

    public static <K, V> Map<K, V> getNewMap() {
        //Save some memory for small maps
        return new HashMap<K,V>(2);
    }

    public int compareTo(EDIFName o) {
        return this.getName().compareTo(o.getName());
    }

}
