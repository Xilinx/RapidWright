package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.StructList;
import org.capnproto.TextList;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
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
    private Enumerator<String> allStrings;
    private List<EDIFPort> allPorts;
    private List<EDIFCell> allCells;
    private List<EDIFCellInst> allInsts;

    public LogNetlistReader() {
        allStrings = new Enumerator<String>();
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
        for(int i=0; i < count; i++) {
            PropertyMap.Entry.Reader entryReader = entries.get(i);
            String key = allStrings.get(entryReader.getKey());
            if(entryReader.isTextValue()) {
                String textValue = allStrings.get(entryReader.getTextValue());
                if(textValue.contains("\"")) {
                    throw new RuntimeException("ERROR: String '"+textValue+
                            "'\n\t value contains unescaped '\"' "
                            + "character. Please replace with EDIF escape value '%34%'.");
                }
                obj.addProperty(key, textValue);
            } else if(entryReader.isIntValue()) {
                obj.addProperty(key, entryReader.getIntValue());
            } else if(entryReader.isBoolValue()) {
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

    private void readEDIFCell(int cellIdx, EDIFNetlist n, Netlist.Reader netlist,
                                        StructList.Reader<Netlist.Cell.Reader> cellListReader,
                                        StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        Cell.Reader cellReader = cellListReader.get(cellIdx);

        EDIFCell edifCell = allCells.get(cellReader.getIndex());

        // Instances
        PrimitiveList.Int.Reader cellInstsReader = cellReader.getInsts();
        int instCount = cellInstsReader.size();
        for(int j=0; j < instCount; j++) {
            readEDIFCellInst(cellInstsReader.get(j), n, netlist, edifCell, cellListReader,
                             instListReader);
        }

        // Nets
        for(Net.Reader netReader : cellReader.getNets()) {
            EDIFNet net = new EDIFNet(allStrings.get(netReader.getName()), edifCell);
            extractPropertyMap(netReader.getPropMap(), net);

            for(PortInstance.Reader portInstReader : netReader.getPortInsts()) {
                EDIFCellInst inst = null;
                if(!portInstReader.isExtPort()) {
                    inst = allInsts.get(portInstReader.getInst());
                    if(inst == null) {
                        throw new RuntimeException("ERROR: EDIFCellInst should already have been read!");
                    }
                }

                EDIFPort port = allPorts.get(portInstReader.getPort());

                BusIdx.Reader portIdxReader = portInstReader.getBusIdx();
                if(portIdxReader.isSingleBit()) {
                    net.createPortInst(port, inst);
                }else {
                    net.createPortInst(port, portIdxReader.getIdx(), inst);
                }
            }
        }

        // Check if Unisim definitions match
        if(edifCell.getLibrary().isHDIPrimitivesLibrary()) {
            Unisim cellType = Unisim.valueOf(edifCell.getName());
            EDIFCell cell = Design.getUnisimCell(cellType);
            if(cell.getPorts().size() != edifCell.getPorts().size()) {
                System.err.println("[WARNING]: Unisim mismatch found in EDIF Library: "
                     + EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME  + ", Cell: "
                     + edifCell.getName() + ", port names/widths mismatch, should be: \n\t"
                     + cell.getPorts() + ",\n\tbut found: \n\t\t" + edifCell.getPorts());
            }
            for(EDIFPort port : cell.getPorts()) {
                String portKey = port.getBusName();
                EDIFPort portMatch = edifCell.getPort(portKey);
                if(portMatch == null || portMatch.getWidth() != port.getWidth()) {
                    System.err.println("[WARNING]: Unisim mismatch found in EDIF Library: "
                            + EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME  + ", Cell: "
                            + edifCell.getName() + ", port names/widths mismatch, should be: \n\t"
                            + cell.getPorts() + ",\nbut found: \n\t" + edifCell.getPorts());
                }
            }
        }
    }

    private EDIFCellInst getInst(int instIdx) {
        if(instIdx >= allInsts.size()) {
            return null;
        }

        return allInsts.get(instIdx);
    }

    private void readEDIFCellInst(int instIdx, EDIFNetlist n, Netlist.Reader netlist,
                                                EDIFCell parent,
                                                StructList.Reader<Netlist.Cell.Reader> cellListReader,
                                                StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        EDIFCellInst edifCellInst = getInst(instIdx);
        if(edifCellInst != null) {
            throw new RuntimeException("ERROR: Each EDIFCellInst should only be read once.");
        }

        CellInstance.Reader instReader = instListReader.get(instIdx);
        EDIFCell cell = allCells.get(instReader.getCell());

        String instName = allStrings.get(instReader.getName());
        edifCellInst = new EDIFCellInst(instName, cell, parent);
        edifCellInst.setViewref(new EDIFName(allStrings.get(instReader.getView())));

        extractPropertyMap(instReader.getPropMap(), edifCellInst);

        allInsts.add(instIdx, edifCellInst);
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
        return getLogNetlist(netlist, false);
    }

    private void readStrings(Netlist.Reader netlist) {
        TextList.Reader strListReader = netlist.getStrList();
        int strCount = strListReader.size();
        for(int i=0; i < strCount; i++) {
            String str = strListReader.get(i).toString();
            allStrings.add(str);
        }
    }

    private EDIFPort readEDIFPort(Port.Reader portReader) {
        int width = 1;
        if(portReader.hasBus()) {
            int start = portReader.getBus().getBusStart();
            int end = portReader.getBus().getBusEnd();
            width = Math.abs(start-end) + 1;
        }
        String portBusName = allStrings.get(portReader.getName());
        if(portReader.isBus()) {
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
        allPorts = new ArrayList<EDIFPort>(portReaderList.size());
        for(int i = 0; i < portReaderList.size(); ++i) {
            Port.Reader portReader = portReaderList.get(i);
            allPorts.add(i, readEDIFPort(portReader));
        }
    }

    private void createCells(EDIFNetlist n, Netlist.Reader netlist) {
        StructList.Reader<Netlist.CellDeclaration.Reader> cellDeclsReader = netlist.getCellDecls();

        allCells = new ArrayList<EDIFCell>(cellDeclsReader.size());
        for(int i = 0; i < cellDeclsReader.size(); ++i) {
            Netlist.CellDeclaration.Reader cellReader = cellDeclsReader.get(i);
            String libraryName = allStrings.get(cellReader.getLib());
            EDIFLibrary library = n.getLibrary(libraryName);
            if(library == null) {
                library = new EDIFLibrary(libraryName);
                n.addLibrary(library);
            }

            String cellName = allStrings.get(cellReader.getName());
            EDIFCell cell = new EDIFCell(library, cellName);
            extractPropertyMap(cellReader.getPropMap(), cell);
            cell.setView(allStrings.get(cellReader.getView()));

            allCells.add(i, cell);

            PrimitiveList.Int.Reader ports = cellReader.getPorts();
            int portCount = ports.size();
            for(int j=0; j < portCount; j++) {
                cell.addPort(allPorts.get(ports.get(j)));
            }
        }
    }

    public static EDIFNetlist getLogNetlist(Netlist.Reader netlist, boolean skipTopStuff) {
        EDIFNetlist n = new EDIFNetlist(netlist.getName().toString());

        LogNetlistReader reader = new LogNetlistReader();
        reader.readStrings(netlist);
        reader.readPorts(netlist);
        reader.createCells(n, netlist);

        int cellCount = netlist.getCellList().size();
        StructList.Reader<Netlist.Cell.Reader> cellListReader = netlist.getCellList();
        StructList.Reader<Netlist.CellInstance.Reader> instListReader = netlist.getInstList();
        reader.allInsts = new ArrayList<EDIFCellInst>(instListReader.size());
        for(int i=0; i < cellCount; i++) {
            reader.readEDIFCell(i, n, netlist, cellListReader, instListReader);
        }

        if(!skipTopStuff) {
            EDIFDesign design = new EDIFDesign(reader.allCells.get(netlist.getTopInst().getCell()).getName());
            design.setTopCell(reader.allCells.get(netlist.getTopInst().getCell()));
            n.setDesign(design);
            reader.extractPropertyMap(netlist.getPropMap(), design);
        }

        // Put libraries in proper export order
        List<EDIFLibrary> libs = n.getLibrariesInExportOrder();
        n.getLibrariesMap().clear();
        for(EDIFLibrary lib : libs) {
            n.addLibrary(lib);
        }

        return n;
    }

}
