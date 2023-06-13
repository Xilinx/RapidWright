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
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PortInstance.BusIdx;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;
import org.capnproto.TextList;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

public class LogNetlistReader {
    public static boolean CHECK_UNISIM_DEFINITIONS = true;

    private String[] allStrings;
    private EDIFPort[] allPorts;
    protected EDIFCellInst[] allInsts;
    protected EDIFCell[] allCells;
    protected Map<Integer, Integer> libraryRename;

    protected Map<Integer, EDIFLibrary> libraries;

    protected EDIFNetlist n;

    public LogNetlistReader() {
        libraryRename = Collections.emptyMap();
    }

    public LogNetlistReader(StringEnumerator otherAllStrings) {
        this();
        allStrings = new String[otherAllStrings.size()];
        for (int i = 0; i < otherAllStrings.size(); i++) {
            allStrings[i] = otherAllStrings.get(i);
        }
    }

    public LogNetlistReader(StringEnumerator outsideAllStrings, Map<String, String> libraryRename) {
        this(outsideAllStrings);
        this.libraryRename = new HashMap<>(libraryRename.size());
        for (Map.Entry<String, String> e : libraryRename.entrySet()) {
            Integer keyIndex = outsideAllStrings.getIndex(e.getKey());
            Integer valueIndex = outsideAllStrings.getIndex(e.getValue());
            this.libraryRename.put(keyIndex, valueIndex);
        }
    }

    protected void readAllStrings(TextList.Reader strListReader) {
        if (strListReader.size() == 0) {
            if (allStrings == null) {
                System.err.println("WARNING: LogNetlistReader has no string data.");
            }
            return;
        }
        allStrings = new String[strListReader.size()];
        for (int i = 0; i < strListReader.size(); i++) {
            allStrings[i] = strListReader.get(i).toString();
        }
    }

    protected String getString(int i) {
        return allStrings[i];
    }

    protected void readAllPorts(StructList.Reader<Port.Reader> portListReader) {
        allPorts = new EDIFPort[portListReader.size()];
        for (int i = 0; i < portListReader.size(); i++) {
            allPorts[i] = readEDIFPort(portListReader.get(i));
        }
    }

    protected EDIFPort getPort(int i) {
        return allPorts[i];
    }

    protected void readAllInsts(StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        allInsts = new EDIFCellInst[instListReader.size()];
        for (int i = 0; i < instListReader.size(); i++) {
            allInsts[i] = readEDIFCellInst(instListReader.get(i));
        }
    }

    protected EDIFCellInst getInst(int i) {
        return allInsts[i];
    }

    /**
     * Extracts the property map information from a Cap'n Proto reader object and deserializes it
     * into an EDIF property map object. The reverse function is
     * {@link LogNetlistWriter#populatePropertyMap(Supplier<PropertyMap.Builder>, EDIFPropertyObject)}
     * @param reader The Cap'n Proto reader object
     * @param obj The EDIF map object
     */
    protected void extractPropertyMap(PropertyMap.Reader reader, EDIFPropertyObject obj) {
        StructList.Reader<PropertyMap.Entry.Reader> entries = reader.getEntries();
        for (int i=0; i < entries.size(); i++) {
            PropertyMap.Entry.Reader entryReader = entries.get(i);
            String key = getString(entryReader.getKey());
            if (entryReader.isTextValue()) {
                String textValue = getString(entryReader.getTextValue());
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
    protected static EDIFDirection getEDIFDirection(Port.Reader port) {
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

    protected void readEDIFCell(Cell.Reader cellReader) {
        EDIFCell edifCell = allCells[cellReader.getIndex()];
        assert(edifCell != null);

        boolean deferSort = true;

        // Instances
        PrimitiveList.Int.Reader cellInstsReader = cellReader.getInsts();
        int instCount = cellInstsReader.size();
        for (int j=0; j < instCount; j++) {
            EDIFCellInst eci = getInst(cellInstsReader.get(j));
            edifCell.addCellInst(eci);
            eci.setParentCell(edifCell);
        }

        // Nets
        for (Net.Reader netReader : cellReader.getNets()) {
            EDIFNet net = new EDIFNet(getString(netReader.getName()), edifCell);
            if (netReader.hasPropMap()) {
                extractPropertyMap(netReader.getPropMap(), net);
            }

            for (PortInstance.Reader portInstReader : netReader.getPortInsts()) {
                EDIFCellInst inst = null;
                if (!portInstReader.isExtPort()) {
                    inst = getInst(portInstReader.getInst());
                    if (inst == null) {
                        throw new RuntimeException("ERROR: EDIFCellInst should already have been read!");
                    }
                }

                EDIFPort port = getPort(portInstReader.getPort());

                BusIdx.Reader portIdxReader = portInstReader.getBusIdx();
                if (portIdxReader.isSingleBit()) {
                    net.createPortInst(port, inst, deferSort);
                } else {
                    net.createPortInst(port, portIdxReader.getIdx(), inst, deferSort);
                }
            }
        }

        if (deferSort) {
            edifCell.sortEDIFPortInstLists();
        }

        if (CHECK_UNISIM_DEFINITIONS) {
            // Check if Unisim definitions match
            if (edifCell.getLibrary().isHDIPrimitivesLibrary()) {
                Unisim cellType = Unisim.valueOf(edifCell.getName());
                EDIFCell cell = Design.getUnisimCell(cellType);
                if (cell.getPorts().size() != edifCell.getPorts().size()) {
                    System.err.println("[WARNING]: Unisim mismatch found in EDIF Library: "
                            + EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME + ", Cell: "
                            + edifCell.getName() + ", port names/widths mismatch, should be: \n\t"
                            + cell.getPorts() + ",\n\tbut found: \n\t\t" + edifCell.getPorts());
                }
                for (EDIFPort port : cell.getPorts()) {
                    String portKey = port.getBusName();
                    EDIFPort portMatch = edifCell.getPort(portKey);
                    if (portMatch == null || portMatch.getWidth() != port.getWidth()) {
                        System.err.println("[WARNING]: Unisim mismatch found in EDIF Library: "
                                + EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME + ", Cell: "
                                + edifCell.getName() + ", port names/widths mismatch, should be: \n\t"
                                + cell.getPorts() + ",\nbut found: \n\t" + edifCell.getPorts());
                    }
                }
            }
        }
    }

    protected EDIFCellInst readEDIFCellInst(CellInstance.Reader instReader) {
        String instName = getString(instReader.getName());

        EDIFCell cell = allCells[instReader.getCell()];
        assert(cell != null);

        EDIFCell parent = null;
        EDIFCellInst edifCellInst = new EDIFCellInst(instName, cell, parent);

        String view = getString(instReader.getView());
        if (!cell.getEDIFView().getName().equals(view)) {
            edifCellInst.setViewref(new EDIFName(view));
        }

        if (instReader.hasPropMap()) {
            extractPropertyMap(instReader.getPropMap(), edifCellInst);
        }

        return edifCellInst;
    }

    private void readAllCellDecls(StructList.Reader<Netlist.CellDeclaration.Reader> cellDeclsReader) {
        libraries = new HashMap<>();

        allCells = createCells(cellDeclsReader);

        libraries = null;
    }

    private void readAllCells(StructList.Reader<Netlist.Cell.Reader> cellsReader) {
        for (int i = 0; i < cellsReader.size(); ++i) {
            readEDIFCell(cellsReader.get(i));
        }
    }

    /**
     * Reads Cap'n Proto serialized netlist into a RapidWright netlist in memory, with macros expanded.
     * @param fileName Name of the serialized netlist file
     * @return EDIFNetlist object in RapidWright framework
     * @throws IOException
     */
    public static EDIFNetlist readLogNetlist(String fileName) throws IOException {
        return readLogNetlist(fileName, true);
    }

    /**
     * Reads Cap'n Proto serialized netlist into a RapidWright netlist in memory, with macros expanded.
     * @param fileName Name of the serialized netlist file
     * @param expandMacros If true, expands the macros in the netlist before returning it to the caller.
     * @return EDIFNetlist object in RapidWright framework
     * @throws IOException
     */
    public static EDIFNetlist readLogNetlist(String fileName, boolean expandMacros) throws IOException {
        CodePerfTracker t = new CodePerfTracker("Read LogNetlist");

        t.start("Read File");
        ReaderOptions readerOptions = new ReaderOptions(32L * 1024L * 1024L * 1024L, 64);
        MessageReader readMsg = Interchange.readInterchangeFile(fileName, readerOptions);
        Netlist.Reader netlist = readMsg.getRoot(Netlist.factory);
        t.stop();

        LogNetlistReader reader = new LogNetlistReader();
        return reader.readLogNetlist(netlist, false, expandMacros, t);
    }

    private EDIFPort readEDIFPort(Port.Reader portReader) {
        int width = 1;
        if (portReader.hasBus()) {
            int start = portReader.getBus().getBusStart();
            int end = portReader.getBus().getBusEnd();
            width = Math.abs(start-end) + 1;
        }
        String portBusName = getString(portReader.getName());
        if (portReader.isBus()) {
            portBusName += "["+ portReader.getBus().getBusStart() +
                    ":" + portReader.getBus().getBusEnd() + "]";
        }
        EDIFPort edifPort = new EDIFPort(portBusName,
                                     getEDIFDirection(portReader), width);
        if (portReader.hasPropMap()) {
            extractPropertyMap(portReader.getPropMap(), edifPort);
        }
        return edifPort;
    }

    protected EDIFCell[] createCells(StructList.Reader<Netlist.CellDeclaration.Reader> cellDeclsReader) {
        EDIFCell[] cells = new EDIFCell[cellDeclsReader.size()];

        for (int i = 0; i < cellDeclsReader.size(); ++i) {
            Netlist.CellDeclaration.Reader cellReader = cellDeclsReader.get(i);
            cells[i] = createCell(cellReader, EDIFCell::new);
        }

        return cells;
    }

    protected EDIFCell createCell(Netlist.CellDeclaration.Reader cellReader,
                                  BiFunction<EDIFLibrary,String,EDIFCell> createCell) {
        EDIFLibrary library = getLibrary(cellReader.getLib());
        String cellName = getString(cellReader.getName());

        EDIFCell cell = createCell.apply(library, cellName);
        if (cellReader.hasPropMap()) {
            extractPropertyMap(cellReader.getPropMap(), cell);
        }
        cell.setView(getString(cellReader.getView()));

        PrimitiveList.Int.Reader ports = cellReader.getPorts();
        int portCount = ports.size();
        for (int j=0; j < portCount; j++) {
            cell.addPort(getPort(ports.get(j)));
        }

        return cell;
    }

    protected void addLibraryToNetlist(EDIFLibrary library) {
        n.addLibrary(library);
    }

    private EDIFLibrary getLibrary(Integer libraryIdx) {
        libraryIdx = libraryRename.getOrDefault(libraryIdx, libraryIdx);
        return libraries.computeIfAbsent(libraryIdx, (k) -> {
            String libraryName = getString(k);
            EDIFLibrary library = new EDIFLibrary(libraryName);
            addLibraryToNetlist(library);
            return library;
        });
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
        CodePerfTracker t = new CodePerfTracker("readLogNetlist");
        return readLogNetlist(netlist, skipTopStuff, expandMacros, t);
    }

    /**
     * Reads an Interchange netlist from Cap'n Proto reader.
     * @param netlist The Cap'n Proto netlist reader
     * @param skipTopStuff If true, skips netlist design object
     * @param expandMacros If true, expands the macros in the netlist before returning it to the caller.
     * @param t CodePerfTracker object.
     * @return The logical netlist.
     */
    public EDIFNetlist readLogNetlist(Netlist.Reader netlist, boolean skipTopStuff, boolean expandMacros, CodePerfTracker t) {
        n = new EDIFNetlist(netlist.getName().toString());

        t.start("Read Strings");
        readAllStrings(netlist.getStrList());

        t.stop().start("Read Ports");
        readAllPorts(netlist.getPortList());

        t.stop().start("Read CellDecls");
        readAllCellDecls(netlist.getCellDecls());

        t.stop().start("Read Insts");
        readAllInsts(netlist.getInstList());

        t.stop().start("Read Cells");
        readAllCells(netlist.getCellList());

        if (!skipTopStuff) {
            t.stop().start("Populate Top");
            EDIFDesign design = new EDIFDesign(allCells[netlist.getTopInst().getCell()].getName());
            design.setTopCell(allCells[netlist.getTopInst().getCell()]);
            n.setDesign(design);
            if (netlist.hasPropMap()) {
                extractPropertyMap(netlist.getPropMap(), design);
            }
        }

        t.stop().start("Order Libraries");

        // Put libraries in proper export order
        List<EDIFLibrary> libs = n.getLibrariesInExportOrder();
        n.getLibrariesMap().clear();
        for (EDIFLibrary lib : libs) {
            n.addLibrary(lib);
        }

        if (expandMacros) {
            t.stop().start("Expand Macros");
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
        t.stop().printSummary();

        return n;
    }
}
