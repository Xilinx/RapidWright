/*
 *
 * Copyright (c) 2022, Xilinx, Inc.
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.xilinx.rapidwright.util.FileTools;

/**
 * A Writer for the RapidWright Binary EDIF Format
 *
 * Provides a binary alternative to textual EDIF that is ~10-15X smaller and loads 5-10X faster.
 * This is intended as a cached version of text-based EDIF as it cannot be read by Vivado.  One
 * additional tradeoff is that it takes about 2.5-3X longer to write than text-based EDIF.
 */
public class BinaryEDIFWriter {

    public static final String EDIF_BINARY_FILE_TAG = "RAPIDWRIGHT_EDIF_BINARY";
    public static final String EDIF_BINARY_FILE_VERSION = "0.0.2";

    public static final int EDIF_NAME_FLAG = 0x80000000;
    public static final int EDIF_UNIQUE_VIEW_FLAG = 0x80000000;
    public static final int EDIF_SAME_LIB_FLAG = 0x80000000;
    public static final int EDIF_PROP_FLAG = 0x40000000;
    public static final int EDIF_HAS_OWNER = 0x80000000;


    public static final int EDIF_DIR_INPUT_MASK  = 0x40000000;
    public static final int EDIF_DIR_OUTPUT_MASK = 0x20000000;
    public static final int EDIF_DIR_INOUT_MASK  = 0x10000000;
    public static final int EDIF_RENAME_MASK     = 0x80000000;
    public static final int PORT_WIDTH_MASK      = ~(EDIF_RENAME_MASK
                                                   | EDIF_DIR_INPUT_MASK
                                                   | EDIF_DIR_OUTPUT_MASK
                                                   | EDIF_DIR_INOUT_MASK);
    public static final int EDIF_PROP_TYPE_BIT   = 30;
    public static final int EDIF_PROP_VALUE_MASK  = 0x3fffffff;
    public static final int EDIF_NULL_INST       = -1;
    public static final int EDIF_MACRO_LIB       = 0x40000000;


    private static void addStringToStringMap(String s, Map<String,Integer> stringMap) {
        stringMap.computeIfAbsent(s, v -> stringMap.size());
        if (stringMap.size() >= EDIF_PROP_FLAG) {
            throw new RuntimeException("ERROR: Too many unique strings for this encoding");
        }
    }

    private static void addNameToStringMap(EDIFName o, Map<String,Integer> stringMap) {
        addStringToStringMap(o.getName(), stringMap);
    }

    private static void addObjectToStringMap(EDIFPropertyObject o, Map<String,Integer> stringMap) {
        addNameToStringMap(o, stringMap);
        for (Entry<String, EDIFPropertyValue> e : o.getPropertiesMap().entrySet()) {
            addStringToStringMap(e.getKey(), stringMap);
            addStringToStringMap(e.getValue().getValue(), stringMap);
            addStringToStringMap(e.getValue().getOwner(), stringMap);
        }
    }

    /**
     * This method iterates over an entire EDIFNetlist to enumerate all Strings.  This is done to
     * provide a fast lookup array at the front of the file when loading the Binary EDIF.
     * @param netlist The netlist to include in the String map.
     * @return A new map keyed by all unique strings in the netlist mapped to unique integers.
     */
    public static Map<String, Integer> createStringMap(EDIFNetlist netlist) {
        Map<String, Integer> stringMap = new HashMap<>();
        for (EDIFLibrary lib : netlist.getLibraries()) {
            addNameToStringMap(lib, stringMap);
            for (EDIFCell cell : lib.getCells()) {
                addObjectToStringMap(cell, stringMap);
                addNameToStringMap(cell.getEDIFView(), stringMap);
                for (EDIFCellInst inst : cell.getCellInsts()) {
                    addObjectToStringMap(inst, stringMap);
                }
                for (EDIFNet net : cell.getNets()) {
                    addObjectToStringMap(net, stringMap);
                    for (EDIFPortInst pi : net.getPortInsts()) {
                        String name = pi.getPort().isBus() ? pi.getPort().getBusName(true) : pi.getName();
                        addStringToStringMap(name, stringMap);
                    }
                }
                for (EDIFPort port : cell.getPorts()) {
                    addObjectToStringMap(port, stringMap);
                }
            }
        }
        addNameToStringMap(netlist, stringMap);
        addObjectToStringMap(netlist.getDesign(), stringMap);
        return stringMap;
    }

    /**
     * Writes, in a compact manner, the EDIFName provided.  Assumes the follow data is not a
     * EDIF property map
     * @param o The EDIFName to write
     * @param os The kryo output stream
     * @param stringMap The string map to reference enumerations from
     * @see BinaryEDIFReader#readEDIFName(EDIFName, Input, String[])
     */
    private static void writeEDIFName(EDIFName o, Output os, Map<String,Integer> stringMap) {
        writeEDIFName(o, os, stringMap, false);
    }

    /**
     * Writes, in a compact manner, the EDIFName provided.
     * @param o The EDIFName to write
     * @param os The kryo output stream
     * @param stringMap The string map to reference enumerations from
     * @param hasPropMap Flag that is set in the final word indicating to the parser if it should
     * expect an EDIF property map
     * @see BinaryEDIFReader#readEDIFName(EDIFName, Input, String[])
     */
    private static void writeEDIFName(EDIFName o, Output os, Map<String,Integer> stringMap,
            boolean hasPropMap) {
        os.writeInt((hasPropMap ? EDIF_PROP_FLAG : 0) | stringMap.get(o.getName()));
    }

    /**
     * Writes the common attributes of an EDIFPropertyObject (EDIFName and property map)
     * @param o The object write
     * @param os The Kryo-based output stream
     * @param stringMap Map of String to enumeration integers
     * @see #readEDIFObject(EDIFPropertyObject, Output, Map)
     */
    private static void writeEDIFObject(EDIFPropertyObject o, Output os, Map<String,Integer> stringMap) {
        boolean hasProperties = o.getPropertiesMap().size() > 0;
        writeEDIFName(o, os, stringMap, hasProperties);
        if (hasProperties) {
            if (o.getPropertiesMap().size() > 0x0000ffff) {
                throw new RuntimeException("ERROR: EDIF object exceeded number of encoded "
                        + "properties on object '" + o.getName() + "'");
            }

            os.writeInt(o.getPropertiesMap().size());

            for (Entry<String, EDIFPropertyValue> e : o.getPropertiesMap().entrySet()) {
                int ownerFlag = e.getValue().getOwner() != null ? EDIF_HAS_OWNER : 0;
                os.writeInt(ownerFlag | stringMap.get(e.getKey()));
                int propType = e.getValue().getType().ordinal();
                os.writeInt(propType << EDIF_PROP_TYPE_BIT | stringMap.get(e.getValue().getValue()));
                if (e.getValue().getOwner() != null) {
                    os.writeInt(stringMap.get(e.getValue().getOwner()));
                }
            }
        }
    }

    /**
     * Writes the EDIFDesign object
     * @param design The object to write
     * @param os The Kryo-based output stream
     * @param stringMap Map of String to enumeration integers
     * @see BinaryEDIFReader#readEDIFDesign(Input, String[], EDIFNetlist)
     */
    static void writeEDIFDesign(EDIFDesign design, Output os, Map<String,Integer> stringMap) {
        writeEDIFObject(design, os, stringMap);
        EDIFCell topCell = design.getTopCell();
        writeEDIFCellRef(topCell, os, stringMap, null);
    }

    /**
     * Writes a reference to an EDIFCell.  It uses a single bit to encoded if the written EDIFCell
     * is in the same EDIFLibrary as the parent cell library.  If it is not, then it will write the
     * 32-bit integer naming the other library.
     * @param ref The cell to reference
     * @param os The Kryo-based output stream
     * @param stringMap Map of String to enumeration integers
     * @param parentCellLib The current library context, or null if it is for EDIFDesign
     * @see BinaryEDIFReader#readEDIFCellRef(Input, String[], EDIFNetlist, EDIFLibrary)
     */
    static void writeEDIFCellRef(EDIFCell ref, Output os, Map<String,Integer> stringMap,
                                         EDIFLibrary parentCellLib) {
        int libMask = ref.getLibrary().equals(parentCellLib) ? EDIF_SAME_LIB_FLAG : 0;
        os.writeInt(libMask | stringMap.get(ref.getName()));
        if (libMask != EDIF_SAME_LIB_FLAG) {
            os.writeInt(stringMap.get(ref.getLibrary().getName()));
        }
    }

    /**
     * Writes the provided EDIFCell to Kryo-based output stream.
     * @param c The current cell to write
     * @param os The Kryo-based output stream
     * @param stringMap Map of string to integer enumerations to use to reference strings
     * @see BinaryEDIFReader#readEDIFCell(Input, String[], EDIFLibrary, EDIFNetlist)
     */
    public static void writeEDIFCell(EDIFCell c, Output os, Map<String,Integer> stringMap) {
        writeEDIFObject(c, os, stringMap);
        boolean hasUniqueView = c.getEDIFView() != EDIFCell.DEFAULT_VIEW;
        os.writeInt((hasUniqueView ? EDIF_UNIQUE_VIEW_FLAG : 0) | c.getPorts().size());
        if (hasUniqueView) {
            writeEDIFName(c.getEDIFView(), os, stringMap);
        }
        for (EDIFPort p : c.getPorts()) {
            writeEDIFObject(p, os, stringMap);
            int dirAndWidth = p.getWidth();
            if (dirAndWidth >= EDIF_DIR_INOUT_MASK) {
                throw new RuntimeException("ERROR: Port " + p.getName() + " is too wide ("+
                        dirAndWidth+") to be encoded in this file format.");
            }
            EDIFDirection dir = p.getDirection();
            if (dir == EDIFDirection.INPUT) {
                dirAndWidth |= EDIF_DIR_INPUT_MASK;
            } else if (dir == EDIFDirection.OUTPUT) {
                dirAndWidth |= EDIF_DIR_OUTPUT_MASK;
            } else if (dir == EDIFDirection.INOUT) {
                dirAndWidth |= EDIF_DIR_INOUT_MASK;
            }
            os.writeInt(dirAndWidth);
        }

        os.writeInt(c.getCellInsts().size());
        for (EDIFCellInst i : c.getCellInsts()) {
            writeEDIFObject(i, os, stringMap);
            writeEDIFCellRef(i.getCellType(), os, stringMap, c.getLibrary());
        }
        os.writeInt(c.getNets().size());
        for (EDIFNet n : c.getNets()) {
            writeEDIFObject(n, os, stringMap);
            os.writeInt(n.getPortInsts().size());
            for (EDIFPortInst pi : n.getPortInsts()) {
                String name = getPortInstKey(pi);
                os.writeInt(stringMap.get(name));
                os.writeInt(pi.getIndex());
                os.writeInt(pi.getCellInst() == null ?
                        EDIF_NULL_INST : stringMap.get(pi.getCellInst().getName()));
            }
        }
    }

    /**
     * Gets the proper port instance name, specifically the port name if it is a bussed port and
     * there is a naming collision on the cell.
     * @param portInst The port instance to use
     * @return The keyed-name of the port instance port on the cell
     */
    private static String getPortInstKey(EDIFPortInst portInst) {
        EDIFPort port = portInst.getPort();
        String returnValue = null;
        if (port.isBus()) {
            EDIFCell cell = portInst.getCellInst() == null ? portInst.getParentCell() : portInst.getCellInst().getCellType();
            EDIFPort portCollision = cell.getPort(portInst.getPort().getName());
            returnValue = portCollision != null ? port.getName() : port.getBusName(true);
        } else {
            returnValue = portInst.getName();
        }
        return returnValue;
    }

    /**
     * Writes the provided netlist as a binary EDIF file (.bedf).  This has the advantage of being
     * an order of magnitude smaller in size and faster to load.
     * @param fileName Name of the file to write
     * @param netlist The current netlist to write
     * @see BinaryEDIFReader#readBinaryEDIF(String)
     */
    public static void writeBinaryEDIF(String fileName, EDIFNetlist netlist) {
        writeBinaryEDIF(Paths.get(fileName), netlist);
    }

    /**
     * Writes the provided netlist as a binary EDIF file (.bedf).  This has the advantage of being
     * an order of magnitude smaller in size and faster to load.
     * @param path Path to the file to write
     * @param netlist The current netlist to write
     * @see BinaryEDIFReader#readBinaryEDIF(Path)
     */
    public static void writeBinaryEDIF(Path path, EDIFNetlist netlist) {
        try (final OutputStream outputStream = Files.newOutputStream(path)) {
            writeBinaryEDIF(outputStream, netlist);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void writeBinaryEDIF(OutputStream outputStream, EDIFNetlist netlist) {
        Map<String, Integer> stringMap = createStringMap(netlist);
        try (Output os = FileTools.getKryoZstdOutputStream(outputStream)) {
            os.writeString(EDIF_BINARY_FILE_TAG);
            os.writeString(EDIF_BINARY_FILE_VERSION);
            String[] strings = new String[stringMap.size()];
            for (Entry<String,Integer> e : stringMap.entrySet()) {
                strings[e.getValue()] = e.getKey();
            }
            FileTools.writeStringArray(os, strings);
            os.writeInt(netlist.getLibraries().size());
            for (EDIFLibrary lib : netlist.getLibrariesInExportOrder()) {
                writeEDIFName(lib, os, stringMap);
                os.writeInt(lib.getCells().size());
                for (EDIFCell cell : lib.getValidCellExportOrder(false)) {
                    writeEDIFCell(cell, os, stringMap);
                }
            }
            writeEDIFName(netlist, os, stringMap);
            // Comments are likely to be unique
            os.writeInt(netlist.getComments().size());
            for (String comment : netlist.getComments()) {
                os.writeString(comment);
            }
            writeEDIFDesign(netlist.getDesign(), os, stringMap);
        }
    }
}
