/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;
import org.capnproto.TextList;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFName;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPropertyObject;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Cell;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.CellInstance;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Net;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Port;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PortInstance;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PortInstance.BusIdx;

public class LogNetlistReader {
    private StringEnumerator allStrings;
    private List<EDIFPort> allPorts;
    private List<EDIFCell> allCells;
    private List<EDIFCellInst> allInsts;
    private Map<String, String> libraryRename;

    public LogNetlistReader() {
        allStrings = new StringEnumerator();
        libraryRename = Collections.emptyMap();
    }

    public LogNetlistReader(StringEnumerator otherAllStrings) {
        allStrings = otherAllStrings;
        libraryRename = Collections.emptyMap();
    }

    public LogNetlistReader(StringEnumerator otherAllStrings, Map<String, String> libraryRename) {
        allStrings = otherAllStrings;
        this.libraryRename = libraryRename;
    }

    /**
     * Extracts the property map information from a Cap'n Proto reader object and deserializes it
     * into an EDIF property map object. The reverse function is
     * {@link LogNetlistWriter#populatePropertyMap(PropertyMap.Builder, EDIFPropertyObject)}
     * @param reader The Cap'n Proto reader object
     * @param obj The EDIF map object
     */
    private void extractPropertyMap(PropertyMap.Reader reader, EDIFPropertyObject obj) {
        StructList.Reader<PropertyMap.Entry.Reader> entries = reader.getEntries();
        int count = entries.size();
        for (int i=0; i < count; i++) {
            PropertyMap.Entry.Reader entryReader = entries.get(i);
            String key = allStrings.get(entryReader.getKey());
            if (entryReader.isTextValue()) {
                String textValue = allStrings.get(entryReader.getTextValue());
                if (textValue.contains("\"")) {
                    throw new RuntimeException("ERROR: String '"+textValue+
                            "'\n\t value contains unescaped '\"' "
                            + "character. Please replace with EDIF escape value '%34%'.");
                }
                obj.addProperty(key, textValue);
            } else if (entryReader.isIntValue()) {
                obj.addProperty(key, entryReader.getIntValue());
            } else if (entryReader.isBoolValue()) {
                obj.addProperty(key, entryReader.getBoolValue());
            } else {
                throw new RuntimeException("ERROR: Unknown property type for key " + key);
            }

        }
    }

    /**
     * Extracts the EDIFDirection from an EDIFPort and converts it to the schema direction
     * object for serialization.
     * @param port Existing EDIFPort in the netlist
     * @return The corresponding schema direction
     */
    protected static Direction getDirection(EDIFPort port) {
        switch (port.getDirection()) {
        case INPUT:
            return Direction.INPUT;
        case OUTPUT:
            return Direction.OUTPUT;
        case INOUT:
            return Direction.INOUT;
        default:
            return Direction._NOT_IN_SCHEMA;
        }
    }

    /**
     * Convert from serialized type to RapidWright EDIFDirection type.
     * @param port The Cap'n Proto Port Reader object with the direction reference of interest.
     * @return The corresponding EDIFDirection
     */
    private static EDIFDirection getEDIFDirection(Port.Reader port) {
        switch (port.getDir()) {
        case INPUT:
            return EDIFDirection.INPUT;
        case OUTPUT:
            return EDIFDirection.OUTPUT;
        case INOUT:
            return EDIFDirection.INOUT;
        default:
            return null;
        }
    }

    private void readEDIFCell(int cellIdx,
                              StructList.Reader<Netlist.Cell.Reader> cellListReader,
                              StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        Cell.Reader cellReader = cellListReader.get(cellIdx);

        EDIFCell edifCell = allCells.get(cellReader.getIndex());

        // Instances
        PrimitiveList.Int.Reader cellInstsReader = cellReader.getInsts();
        int instCount = cellInstsReader.size();
        for (int j=0; j < instCount; j++) {
            readEDIFCellInst(cellInstsReader.get(j), edifCell, instListReader);
        }

        // Nets
        for (Net.Reader netReader : cellReader.getNets()) {
            EDIFNet net = new EDIFNet(allStrings.get(netReader.getName()), edifCell);
            extractPropertyMap(netReader.getPropMap(), net);

            for (PortInstance.Reader portInstReader : netReader.getPortInsts()) {
                EDIFCellInst inst = null;
                if (!portInstReader.isExtPort()) {
                    inst = allInsts.get(portInstReader.getInst());
                    if (inst == null) {
                        throw new RuntimeException("ERROR: EDIFCellInst should already have been read!");
                    }
                }

                EDIFPort port = allPorts.get(portInstReader.getPort());

                BusIdx.Reader portIdxReader = portInstReader.getBusIdx();
                if (portIdxReader.isSingleBit()) {
                    net.createPortInst(port, inst);
                } else {
                    net.createPortInst(port, portIdxReader.getIdx(), inst);
                }
            }
        }

        // Check if Unisim definitions match
        if (edifCell.getLibrary().isHDIPrimitivesLibrary()) {
            Unisim cellType = Unisim.valueOf(edifCell.getName());
            EDIFCell cell = Design.getUnisimCell(cellType);
            if (cell.getPorts().size() != edifCell.getPorts().size()) {
                System.err.println("[WARNING]: Unisim mismatch found in EDIF Library: "
                     + EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME  + ", Cell: "
                     + edifCell.getName() + ", port names/widths mismatch, should be: \n\t"
                     + cell.getPorts() + ",\n\tbut found: \n\t\t" + edifCell.getPorts());
            }
            for (EDIFPort port : cell.getPorts()) {
                String portKey = port.getBusName();
                EDIFPort portMatch = edifCell.getPort(portKey);
                if (portMatch == null || portMatch.getWidth() != port.getWidth()) {
                    System.err.println("[WARNING]: Unisim mismatch found in EDIF Library: "
                            + EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME  + ", Cell: "
                            + edifCell.getName() + ", port names/widths mismatch, should be: \n\t"
                            + cell.getPorts() + ",\nbut found: \n\t" + edifCell.getPorts());
                }
            }
        }
    }

    private EDIFCellInst getInst(int instIdx) {
        if (instIdx >= allInsts.size()) {
            return null;
        }

        return allInsts.get(instIdx);
    }

    private void readEDIFCellInst(int instIdx, EDIFCell parent,
                                  StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        EDIFCellInst edifCellInst = getInst(instIdx);
        if (edifCellInst != null) {
            throw new RuntimeException("ERROR: Each EDIFCellInst should only be read once.");
        }

        CellInstance.Reader instReader = instListReader.get(instIdx);
        EDIFCell cell = allCells.get(instReader.getCell());

        String instName = allStrings.get(instReader.getName());
        edifCellInst = new EDIFCellInst(instName, cell, parent);
        edifCellInst.setViewref(new EDIFName(allStrings.get(instReader.getView())));

        extractPropertyMap(instReader.getPropMap(), edifCellInst);

        EDIFCellInst oldCellInst = allInsts.set(instIdx, edifCellInst);
        assert(oldCellInst == null);
    }

    /**
     * Reads Cap'n Proto serialized netlist into a RapidWright netlist in memory
     * @param fileName Name of the serialized netlist file
     * @return Netlist object in RapidWright framework
     * @throws IOException
     */
    public static EDIFNetlist readLogNetlist(String fileName) throws IOException {
        ReaderOptions readerOptions = new ReaderOptions(32L*1024L*1024L*1024L, 64);
        MessageReader readMsg = Interchange.readInterchangeFile(fileName, readerOptions);

        Netlist.Reader netlist = readMsg.getRoot(Netlist.factory);
        return getLogNetlist(netlist);
    }

    private void readStrings(Netlist.Reader netlist) {
        TextList.Reader strListReader = netlist.getStrList();
        int strCount = strListReader.size();
        allStrings.ensureCapacity(strCount);
        for (int i=0; i < strCount; i++) {
            String str = strListReader.get(i).toString();
            allStrings.add(str);
        }
    }

    private EDIFPort readEDIFPort(Port.Reader portReader) {
        int width = 1;
        if (portReader.hasBus()) {
            int start = portReader.getBus().getBusStart();
            int end = portReader.getBus().getBusEnd();
            width = Math.abs(start-end) + 1;
        }
        String portBusName = allStrings.get(portReader.getName());
        if (portReader.isBus()) {
            portBusName += "["+ portReader.getBus().getBusStart() +
                    ":" + portReader.getBus().getBusEnd() + "]";
        }
        EDIFPort edifPort = new EDIFPort(portBusName,
                                     getEDIFDirection(portReader), width);
        extractPropertyMap(portReader.getPropMap(), edifPort);
        return edifPort;
    }

    private void readPorts(Netlist.Reader netlist) {
        StructList.Reader<Port.Reader> portReaderList = netlist.getPortList();
        allPorts = new ArrayList<>(portReaderList.size());
        for (int i = 0; i < portReaderList.size(); ++i) {
            Port.Reader portReader = portReaderList.get(i);
            allPorts.add(readEDIFPort(portReader));
        }
    }

    private void createCells(EDIFNetlist n, Netlist.Reader netlist) {
        StructList.Reader<Netlist.CellDeclaration.Reader> cellDeclsReader = netlist.getCellDecls();

        allCells = new ArrayList<>(cellDeclsReader.size());
        for (int i = 0; i < cellDeclsReader.size(); ++i) {
            Netlist.CellDeclaration.Reader cellReader = cellDeclsReader.get(i);
            String libraryName = allStrings.get(cellReader.getLib());
            libraryName = libraryRename.getOrDefault(libraryName, libraryName);

            EDIFLibrary library = n.getLibrary(libraryName);
            if (library == null) {
                library = new EDIFLibrary(libraryName);
                n.addLibrary(library);
            }

            String cellName = allStrings.get(cellReader.getName());
            EDIFCell cell = new EDIFCell(library, cellName);
            extractPropertyMap(cellReader.getPropMap(), cell);
            cell.setView(allStrings.get(cellReader.getView()));

            allCells.add(cell);

            PrimitiveList.Int.Reader ports = cellReader.getPorts();
            int portCount = ports.size();
            for (int j=0; j < portCount; j++) {
                cell.addPort(allPorts.get(ports.get(j)));
            }
        }
        assert(allCells.size() == cellDeclsReader.size());
    }

    /**
     * Reads an Interchange netlist from Cap'n Proto reader.  Will expand macros by default.
     * @param netlist The Cap'n Proto netlist reader
     * @param skipTopStuff If true, skips netlist design object
     * @return The logical netlist.
     */
    public EDIFNetlist readLogNetlist(Netlist.Reader netlist, boolean skipTopStuff) {
        return readLogNetlist(netlist, skipTopStuff, true);
    }

    /**
     * Reads an Interchange netlist from Cap'n Proto reader.
     * @param netlist The Cap'n Proto netlist reader
     * @param skipTopStuff If true, skips netlist design object
     * @param expandMacros If true, expands the macros in the netlist before returning it to the caller.
     * @return The logical netlist.
     */
    public EDIFNetlist readLogNetlist(Netlist.Reader netlist, boolean skipTopStuff, boolean expandMacros) {
        EDIFNetlist n = new EDIFNetlist(netlist.getName().toString());

        readPorts(netlist);
        createCells(n, netlist);

        int cellCount = netlist.getCellList().size();
        StructList.Reader<Netlist.Cell.Reader> cellListReader = netlist.getCellList();
        StructList.Reader<Netlist.CellInstance.Reader> instListReader = netlist.getInstList();
        allInsts = new ArrayList<>(Collections.nCopies(instListReader.size(), null));
        for (int i=0; i < cellCount; i++) {
            readEDIFCell(i, cellListReader, instListReader);
        }
        assert(allInsts.size() == instListReader.size());

        if (!skipTopStuff) {
            EDIFDesign design = new EDIFDesign(allCells.get(netlist.getTopInst().getCell()).getName());
            design.setTopCell(allCells.get(netlist.getTopInst().getCell()));
            n.setDesign(design);
            extractPropertyMap(netlist.getPropMap(), design);
        }

        // Put libraries in proper export order
        List<EDIFLibrary> libs = n.getLibrariesInExportOrder();
        n.getLibrariesMap().clear();
        for (EDIFLibrary lib : libs) {
            n.addLibrary(lib);
        }

        if (expandMacros) {
            String partName = EDIFTools.getPartName(n);
            if (partName != null) {
                n.expandMacroUnisims(PartNameTools.getPart(partName).getSeries());
            } else {
                System.err.println("WARNING: Could not determine target device from netlist.  Macro "
                    + "unisims are not expanded.  Please add a top netlist property to indicate the "
                    + "target part such as [part=xcvu095-ffva2104-2-e].  Macro expansion can also be"
                    + " run manually with EDIFNetlist.expandMacroUnisims(Series)");
            }
        }

        return n;
    }

    public static EDIFNetlist getLogNetlist(Netlist.Reader netlist) {
        LogNetlistReader reader = new LogNetlistReader();
        reader.readStrings(netlist);
        return reader.readLogNetlist(netlist, /*skipTopStuff=*/false);
    }

}
