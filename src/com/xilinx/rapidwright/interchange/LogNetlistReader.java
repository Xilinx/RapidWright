/*
 * Copyright (c) 2020-2022, Xilinx, Inc.
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

package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;
import org.capnproto.StructList.Reader;
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
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PortInstance.BusIdx;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.StringTools;

public class LogNetlistReader {
    private Enumerator<String> allStrings;
    private EDIFPort[] allPorts;
    private EDIFCell[] allCells;
    private EDIFCellInst[] allInsts;
    private Map<String, String> libraryRename;
    private Netlist.Reader cellNetlist;
    private Netlist.Reader[] cellNetlists;

    public LogNetlistReader() {
        allStrings = new Enumerator<String>();
        libraryRename = Collections.emptyMap();
    }

    public LogNetlistReader(Enumerator<String> otherAllStrings) {
        allStrings = otherAllStrings;
        libraryRename = Collections.emptyMap();
    }

    public LogNetlistReader(Enumerator<String> otherAllStrings, Map<String, String> libraryRename) {
        allStrings = otherAllStrings;
        this.libraryRename = libraryRename;
    }

    /**
     * Extracts the property map information from a Cap'n Proto reader object and deserializes it
     * into an EDIF property map object. The reverse function is
     * {@link #populatePropertyMap(com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap.Builder, EDIFPropertyObject)}
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

    public Netlist.Reader getCellNetlist() {
        return cellNetlist;
    }

    public Netlist.Reader[] getCellNetlists() {
        return cellNetlists;
    }

    public void setCellNetlist(Netlist.Reader cellNetlist) {
        this.cellNetlist = cellNetlist;
    }

    public void setCellNetlist(Netlist.Reader cellNetlist, int index) {
        this.cellNetlists[index] = cellNetlist;
    }

    public void initCellNetlists(int size) {
        this.cellNetlists = new Netlist.Reader[size];
    }

    private void readEDIFCell(int cellIdx, EDIFNetlist n,
                                        StructList.Reader<Netlist.Cell.Reader> cellListReader,
                                        StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        Cell.Reader cellReader = cellListReader.get(cellIdx);

        EDIFCell edifCell = allCells[cellReader.getIndex()];

        // Instances
        PrimitiveList.Int.Reader cellInstsReader = cellReader.getInsts();
        int instCount = cellInstsReader.size();
        for (int j=0; j < instCount; j++) {
            readEDIFCellInst(cellInstsReader.get(j), n, edifCell, cellListReader,
                             instListReader);
        }

        // Nets
        for (Net.Reader netReader : cellReader.getNets()) {
            EDIFNet net = new EDIFNet(allStrings.get(netReader.getName()), edifCell);
            extractPropertyMap(netReader.getPropMap(), net);

            for (PortInstance.Reader portInstReader : netReader.getPortInsts()) {
                EDIFCellInst inst = null;
                if (!portInstReader.isExtPort()) {
                    inst = allInsts[portInstReader.getInst()];
                    if (inst == null) {
                        throw new RuntimeException("ERROR: EDIFCellInst should already have been read!");
                    }
                }

                EDIFPort port = allPorts[portInstReader.getPort()];

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
        if (instIdx >= allInsts.length) {
            return null;
        }

        return allInsts[instIdx];
    }

    private void readEDIFCellInst(int instIdx, EDIFNetlist n,
                                                EDIFCell parent,
                                                StructList.Reader<Netlist.Cell.Reader> cellListReader,
                                                StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        EDIFCellInst edifCellInst = getInst(instIdx);
        if (edifCellInst != null) {
            return;
        }

        CellInstance.Reader instReader = instListReader.get(instIdx);
        EDIFCell cell = allCells[instReader.getCell()];

        String instName = allStrings.get(instReader.getName());
        edifCellInst = new EDIFCellInst(instName, cell, parent);
        edifCellInst.setViewref(new EDIFName(allStrings.get(instReader.getView())));

        extractPropertyMap(instReader.getPropMap(), edifCellInst);

        allInsts[instIdx] = edifCellInst;
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

    private static List<String> getCellFileNames(String rootFileName) {
        Path parentDir = Paths.get(rootFileName).getParent();
        if (parentDir == null) {
            parentDir = Paths.get(System.getProperty("user.dir"));
        }
        List<String> fileNames = new ArrayList<>();

        try {
            Files.list(parentDir).filter(f -> StringTools.endsWithSuffixAndInteger(f.toString(), rootFileName))
                    .forEach(p -> fileNames.add(p.toString()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return fileNames;
    }

    /**
     * Reads a Cap'n Proto serialized netlist into a RapidWright netlist in memory
     * using parallelization techniques through multiple messages. See also
     * {@link LogNetlistWriter#writeLogNetlistParallel(EDIFNetlist, String, boolean)}
     * 
     * @param fileName Common name of the serialized netlist files
     * @return Netlist object in RapidWright framework
     * @throws IOException
     */
    public static EDIFNetlist readLogNetlistParallel(String fileName) throws IOException {
        CodePerfTracker t = new CodePerfTracker("Read LogNetlist");
        t.start("Read Top");
        ReaderOptions readerOptions = new ReaderOptions(32L * 1024L * 1024L * 1024L, 64);
        MessageReader readMsg = Interchange.readInterchangeFile(fileName + LogNetlistWriter.TOP_SUFFIX, readerOptions);
        Netlist.Reader topNetlist = readMsg.getRoot(Netlist.factory);
        EDIFNetlist n = new EDIFNetlist(topNetlist.getName().toString());
        LogNetlistReader reader = new LogNetlistReader();
        t.stop();

        List<String> cellFileNames = getCellFileNames(fileName + LogNetlistWriter.CELLS_SUFFIX);

        Runnable[] tasks = new Runnable[cellFileNames.size() + 1];
        reader.initCellNetlists(cellFileNames.size());

        for (int i = 0; i < cellFileNames.size(); i++) {
            Integer ii = i;
            tasks[i] = () -> {
                // t.start("ReadCells" + ii, true);
                String cellFileName = cellFileNames.get(ii);
                int cellFileNameIdx = StringTools.getUnsignedIntegerSuffix(cellFileName);
                reader.setCellNetlist(getNetlistReaderFromFile(cellFileName), cellFileNameIdx);
                // t.stop("ReadCells" + ii);
            };
        }

        tasks[tasks.length - 1] = () -> {
            // t.start("ReadStrings", true);
            reader.readStrings(getNetlistReaderFromFile(fileName + LogNetlistWriter.STRINGS_SUFFIX));
            // t.stop("ReadStrings");
        };

        t.start("ReadCellsStrings");
        ParallelismTools.invokeAll(tasks);
        t.stop();

        t.start("ReadPorts");
        reader.readPorts(getNetlistReaderFromFile(fileName + LogNetlistWriter.PORTS_SUFFIX));
        t.stop();

        t.start("Create Cells");
        reader.createCells(n, reader.cellNetlists);


        t.stop().start("Read Insts");
        StructList.Reader<Netlist.CellInstance.Reader> instListReader = 
                getNetlistReaderFromFile(fileName + LogNetlistWriter.INSTS_SUFFIX).getInstList();
        t.stop();

        reader.allInsts = new EDIFCellInst[instListReader.size()];

//        System.out.println("readCells = " + reader.allCells.length);
//        System.out.println("readInsts = " + reader.allInsts.length);
//        System.out.println("readPorts = " + reader.allPorts.length);

        Runnable[] readEDIFCellTasks = new Runnable[reader.getCellNetlists().length];
        for (int i = 0; i < readEDIFCellTasks.length; i++) {
            Integer ii = i;
            Netlist.Reader netlistReader = reader.getCellNetlists()[i];
            Reader<Netlist.Cell.Reader> cellListReader = netlistReader.getCellList();
            readEDIFCellTasks[i] = () -> {
                // t.start("ReadEDIFCells" + ii, true);
                for (int j = 0; j < cellListReader.size(); j++) {
                    reader.readEDIFCell(j, n, cellListReader, instListReader);
                }
                // t.stop("ReadEDIFCells" + ii);
            };
        }

        t.start("ReadEDIFCells");
        ParallelismTools.invokeAll(readEDIFCellTasks);
        t.stop();

        t.start("Populate Top");
        EDIFDesign design = new EDIFDesign(reader.allCells[topNetlist.getTopInst().getCell()].getName());
        design.setTopCell(reader.allCells[topNetlist.getTopInst().getCell()]);
        n.setDesign(design);
        reader.extractPropertyMap(topNetlist.getPropMap(), design);

        t.stop().start("Order Libraries");
        // Put libraries in proper export order
        List<EDIFLibrary> libs = n.getLibrariesInExportOrder();
        n.getLibrariesMap().clear();
        for (EDIFLibrary lib : libs) {
            n.addLibrary(lib);
        }

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
        t.stop().printSummary();

        return n;
    }

    private void readStrings(Netlist.Reader netlist) {
        TextList.Reader strListReader = netlist.getStrList();
        int strCount = strListReader.size();
        for (int i=0; i < strCount; i++) {
            String str = strListReader.get(i).toString();
            allStrings.add(str);
        }
    }

    private static Netlist.Reader getNetlistReaderFromFile(String fileName) {
        ReaderOptions readerOptions = new ReaderOptions(32L * 1024L * 1024L * 1024L, 64);
        MessageReader readMsg = null;
        try {
            readMsg = Interchange.readInterchangeFile(fileName, readerOptions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Netlist.Reader netlist = readMsg.getRoot(Netlist.factory);
        return netlist;
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
        allPorts = new EDIFPort[portReaderList.size()];
        for (int i = 0; i < portReaderList.size(); ++i) {
            Port.Reader portReader = portReaderList.get(i);
            allPorts[i] = readEDIFPort(portReader);
        }
    }

    private void createCells(EDIFNetlist n, Netlist.Reader[] netlists) {
        int totalCellCount = 0;
        for (Netlist.Reader reader : netlists) {
            totalCellCount += reader.getCellDecls().size();
        }
        allCells = new EDIFCell[totalCellCount];

        int offset = 0;
        for (Netlist.Reader netlist : netlists) {
            createCells(n, netlist, offset);
            offset += netlist.getCellDecls().size();
        }
    }

    private void createCells(EDIFNetlist n, Netlist.Reader netlist, int offset) {
        StructList.Reader<Netlist.CellDeclaration.Reader> cellDeclsReader = netlist.getCellDecls();

        if (allCells == null) {
            allCells = new EDIFCell[cellDeclsReader.size()];
        }
        for (int i = 0; i < cellDeclsReader.size(); ++i) {
            Netlist.CellDeclaration.Reader cellReader = cellDeclsReader.get(i);
            
            allCells[i + offset] = createCell(n, cellReader, i + offset);
        }
    }
    
    private EDIFCell createCell(EDIFNetlist n, Netlist.CellDeclaration.Reader cellReader, int debug) {
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

        PrimitiveList.Int.Reader ports = cellReader.getPorts();
        int portCount = ports.size();
        for (int j=0; j < portCount; j++) {
            cell.addPort(allPorts[ports.get(j)]);
        }
        
        return cell;
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
        createCells(n, netlist, 0);

        int cellCount = netlist.getCellList().size();
        StructList.Reader<Netlist.Cell.Reader> cellListReader = netlist.getCellList();
        StructList.Reader<Netlist.CellInstance.Reader> instListReader = netlist.getInstList();
        allInsts = new EDIFCellInst[instListReader.size()];
        for (int i=0; i < cellCount; i++) {
            readEDIFCell(i, n, cellListReader, instListReader);
        }

        if (!skipTopStuff) {
            EDIFDesign design = new EDIFDesign(allCells[netlist.getTopInst().getCell()].getName());
            design.setTopCell(allCells[netlist.getTopInst().getCell()]);
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
