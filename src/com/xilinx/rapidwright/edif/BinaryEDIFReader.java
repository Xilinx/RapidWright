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

import static com.xilinx.rapidwright.edif.BinaryEDIFWriter.EDIF_HAS_OWNER;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.xilinx.rapidwright.util.FileTools;

/**
 * A Reader for the RapidWright Binary EDIF Format
 *
 * Provides a binary alternative to textual EDIF that is ~10-15X smaller and loads 5-10X faster.
 * This is intended as a RapidWright-only cache (with the extension *.bedf) of this text-based
 * EDIF and cannot be read by Vivado.  One additional tradeoff is that it takes about 2.5-3X
 * longer to write than text-based EDIF, but it is expected that this binary alternative will
 * be written once and read many times.
 */
public class BinaryEDIFReader {

    /**
     * Reads an EDIFName object from Kryo-based input stream.
     * @param o The object to populate
     * @param is Kryo-based input stream
     * @param strings Indexed string lookup
     * @return True if this object has a non-zero property map, false if none
     * @see BinaryEDIFWriter#writeEDIFName(EDIFName, Output, Map, boolean)
     */
    private static boolean readEDIFName(EDIFName o, Input is, String[] strings) {
        int nameIdx = is.readInt();
        o.setName(strings[nameIdx & ~BinaryEDIFWriter.EDIF_PROP_FLAG]);
        return (nameIdx & BinaryEDIFWriter.EDIF_PROP_FLAG) == BinaryEDIFWriter.EDIF_PROP_FLAG;
    }

    /**
     * Reads the common attributes of an EDIFPropertyObject (EDIFName and property map)
     * @param o The object to read
     * @param is The Kryo-based input stream
     * @param strings Indexed string lookup
     * @see BinaryEDIFWriter#writeEDIFObject(EDIFPropertyObject, Output, Map<String,Integer>)
     */
    static void readEDIFObject(EDIFPropertyObject o, Input is, String[] strings) {
        if (readEDIFName(o, is, strings)) {
            int numProps = is.readInt();
            for (int i=0; i < numProps; i++) {
                int ownerAndKeyIdx = is.readInt();
                boolean hasOwner = (ownerAndKeyIdx & EDIF_HAS_OWNER) == EDIF_HAS_OWNER;
                String key = strings[ownerAndKeyIdx & ~EDIF_HAS_OWNER];
                int typeAndStringIdx = is.readInt();
                EDIFValueType type = EDIFValueType.values[typeAndStringIdx >>> BinaryEDIFWriter.EDIF_PROP_TYPE_BIT];
                String value = strings[BinaryEDIFWriter.EDIF_PROP_VALUE_MASK & typeAndStringIdx];
                String owner = hasOwner ? strings[is.readInt()] : null;
                o.addProperty(key, new EDIFPropertyValue(value, type, owner));
            }
        }
    }

    /**
     * Reads an EDIFDesign object
     * @param is The Kryo-based input stream
     * @param strings Indexed string lookup
     * @param netlist The current netlist being populated
     * @return A new EDIFDesign object, populated with data from the input stream
     * @see BinaryEDIFWriter#writeEDIFDesign(EDIFDesign, Output, Map)
     */
    static EDIFDesign readEDIFDesign(Input is, String[] strings, EDIFNetlist netlist) {
        EDIFDesign design = new EDIFDesign();
        readEDIFObject(design, is, strings);
        design.setTopCell(BinaryEDIFReader.readEDIFCellRef(is, strings, netlist, null));
        netlist.setDesign(design);
        return design;
    }

    /**
     * Reads the reference for an EDIFCell
     * @param is A Kryo-based input stream
     * @param strings Indexed string lookup
     * @param netlist The current netlist being populated
     * @param parentCellLib The current parent cell's library, or null if this is for EDIFDesign
     * @return The existing EDIFCell contained in the specified library of the netlist.
     * @see BinaryEDIFWriter#writeEDIFCellRef(EDIFCell, Output, Map, EDIFLibrary)
     */
    static EDIFCell readEDIFCellRef(Input is, String[] strings, EDIFNetlist netlist,
                                            EDIFLibrary parentCellLib) {
        int cellNameIdx = is.readInt();
        EDIFLibrary lib = null;
        if ((cellNameIdx & BinaryEDIFWriter.EDIF_SAME_LIB_FLAG) == BinaryEDIFWriter.EDIF_SAME_LIB_FLAG) {
            cellNameIdx = cellNameIdx & ~BinaryEDIFWriter.EDIF_SAME_LIB_FLAG;
            lib = parentCellLib;
        } else {
            lib = netlist.getLibrary(strings[is.readInt()]);
        }
        String cellName = strings[cellNameIdx];
        if (lib == null) {
            throw new RuntimeException("ERROR: Couldn't find Library for cell '" + cellName + "'");
        }
        EDIFCell cell = lib.getCell(cellName);
        if (cell == null) {
            throw new RuntimeException("ERROR: Couldn't find cell '"
                    + cellName + "' in Library '" + cellName + "'");
        }
        return cell;
    }

    /**
     * Reads and creates a new EDIFCell from the Kryo-based input stream
     * @param is Kryo-based input stream
     * @param strings Indexed string lookup
     * @param lib Parent library for which this EDIFCell should become a member
     * @param netlist The current netlist being read
     * @return The newly read and created EDIFCell
     * @see BinaryEDIFWriter#writeEDIFCell(EDIFCell, Output, Map)
     */
    public static EDIFCell readEDIFCell(Input is, String[] strings, EDIFLibrary lib, EDIFNetlist netlist) {
        EDIFCell c = new EDIFCell();
        readEDIFObject(c, is, strings);
        lib.addCell(c);
        int portCount = is.readInt();
        if ((portCount & BinaryEDIFWriter.EDIF_UNIQUE_VIEW_FLAG) == BinaryEDIFWriter.EDIF_UNIQUE_VIEW_FLAG) {
            portCount = portCount & ~BinaryEDIFWriter.EDIF_UNIQUE_VIEW_FLAG;
            EDIFName view = new EDIFName();
            readEDIFName(view, is, strings);
            c.setView(view);
        }
        for (int i=0; i < portCount; i++) {
            EDIFPort port = new EDIFPort();
            readEDIFObject(port, is, strings);
            int dirAndWidth = is.readInt();
            int width = dirAndWidth & (BinaryEDIFWriter.PORT_WIDTH_MASK);
            EDIFDirection dir = null;
            if ((dirAndWidth & BinaryEDIFWriter.EDIF_DIR_INPUT_MASK) == BinaryEDIFWriter.EDIF_DIR_INPUT_MASK) {
                dir = EDIFDirection.INPUT;
            } else if ((dirAndWidth & BinaryEDIFWriter.EDIF_DIR_OUTPUT_MASK) == BinaryEDIFWriter.EDIF_DIR_OUTPUT_MASK) {
                dir = EDIFDirection.OUTPUT;
            } else if ((dirAndWidth & BinaryEDIFWriter.EDIF_DIR_INOUT_MASK) == BinaryEDIFWriter.EDIF_DIR_INOUT_MASK) {
                dir = EDIFDirection.INOUT;
            } else {
                throw new RuntimeException("ERROR: Couldn't read port direction in cell "
                        + c.getName());
            }
            port.setWidth(width);
            port.setDirection(dir);
            port.setIsLittleEndian();
            c.addPort(port);
        }
        int instCount = is.readInt();
        for (int i=0; i < instCount; i++) {
            EDIFCellInst inst = new EDIFCellInst();
            readEDIFObject(inst, is, strings);
            inst.setCellType(readEDIFCellRef(is, strings, netlist, lib));
            c.addCellInst(inst);
        }
        int netCount = is.readInt();
        for (int i=0; i < netCount; i++) {
            EDIFNet net = new EDIFNet();
            readEDIFObject(net, is, strings);
            c.addNet(net);
            int portRefCount = is.readInt();
            for (int j=0; j < portRefCount; j++) {
                String name = strings[is.readInt()];
                int index = is.readInt();
                int instRef = is.readInt();
                if (instRef == BinaryEDIFWriter.EDIF_NULL_INST) {
                    net.createPortInst(c.getPort(name), index);
                } else {
                    EDIFCellInst inst = c.getCellInst(strings[instRef]);
                    EDIFPort port = inst.getPort(name);
                    net.createPortInst(port, index, inst);
                }
            }
        }
        return c;
    }

    /**
     * Reads a binary EDIF (.bedf) file and creates a new EDIFNetlist object.
     * @param fileName Name of the file to read
     * @return The newly created netlist populated from the binary EDIF file
     * @see BinaryEDIFWriter#writeBinaryEDIF(String, EDIFNetlist)
     */
    public static EDIFNetlist readBinaryEDIF(String fileName) {
        return BinaryEDIFReader.readBinaryEDIF(Paths.get(fileName));
    }

    /**
     * Reads a binary EDIF (.bedf) file and creates a new EDIFNetlist object.
     * @param path Name of the file to read
     * @return The newly created netlist populated from the binary EDIF file
     * @see BinaryEDIFWriter#writeBinaryEDIF(Path, EDIFNetlist)
     */
    public static EDIFNetlist readBinaryEDIF(Path path) {
        try (Input is = FileTools.getKryoZstdInputStream(path.toString())) {
            if (!is.readString().equals(BinaryEDIFWriter.EDIF_BINARY_FILE_TAG)) {
                throw new RuntimeException("ERROR: Cannot recognize EDIF Binary format");
            }
            if (!is.readString().equals(BinaryEDIFWriter.EDIF_BINARY_FILE_VERSION)) {
                throw new RuntimeException("ERROR: Unsupported EDIF Binary format version");
            }
            EDIFNetlist netlist = new EDIFNetlist();
            String[] strings = FileTools.readStringArray(is);
            int numLibraries = is.readInt();
            for (int i=0; i < numLibraries; i++) {
                EDIFLibrary lib = new EDIFLibrary();
                readEDIFName(lib, is, strings);
                netlist.addLibrary(lib);
                int numCells = is.readInt();
                for (int j=0; j < numCells; j++) {
                    readEDIFCell(is, strings, lib, netlist);
                }
            }
            readEDIFName(netlist, is, strings);
            int numComments = is.readInt();
            for (int i=0; i < numComments; i++) {
                netlist.addComment(is.readString());
            }
            readEDIFDesign(is, strings, netlist);
            return netlist;
        }
    }

}
