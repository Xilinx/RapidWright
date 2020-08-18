package com.xilinx.rapidwright.interchange;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.ReaderOptions;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.TextList;

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
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Cell;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.CellInstance;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Net;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Port;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PortInstance;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;

public class LogNetlistReader {

    private static Enumerator<EDIFCell> allCells = new Enumerator<>();
    private static Enumerator<EDIFCellInst> allInsts = new Enumerator<>();
    private static Enumerator<EDIFPort> allPorts = new Enumerator<>();
    private static Enumerator<String> allStrings = new Enumerator<>();
        
    /**
     * Extracts the property map information from a Cap'n Proto reader object and deserializes it 
     * into an EDIF property map object. The reverse function is 
     * {@link #populatePropertyMap(com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap.Builder, EDIFPropertyObject)}
     * @param reader The Cap'n Proto reader object
     * @param obj The EDIF map object
     */
    public static void extractPropertyMap(PropertyMap.Reader reader, EDIFPropertyObject obj) {
        StructList.Reader<PropertyMap.Entry.Reader> entries = reader.getEntries();
        int count = entries.size();
        for(int i=0; i < count; i++) {
            PropertyMap.Entry.Reader entryReader = entries.get(i);
            String key = allStrings.get(entryReader.getKey());
            if(entryReader.isTextValue()) {
                obj.addProperty(key, allStrings.get(entryReader.getTextValue()));
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

    private static EDIFLibrary getEDIFLibrary(Cell.Reader cellReader, EDIFNetlist netlist) {
        String libName = allStrings.get(cellReader.getLib());
        EDIFLibrary lib = netlist.getLibrary(libName);
        if(lib == null) { 
            lib = netlist.addLibrary(new EDIFLibrary(libName));
        }
        return lib;
    }
    
    public static EDIFCell readEDIFCell(int cellIdx, EDIFNetlist n, Netlist.Reader netlist,
                                        StructList.Reader<Netlist.Cell.Reader> cellListReader,
                                        StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        EDIFCell edifCell = allCells.get(cellIdx);
        if(edifCell != null) return edifCell;
        
        Cell.Reader cellReader = cellListReader.get(cellIdx);
        
        edifCell = new EDIFCell(getEDIFLibrary(cellReader, n),
                                        allStrings.get(cellReader.getName()));
        edifCell.setView(allStrings.get(cellReader.getView()));

        // Ports
        int portCount = cellReader.getPorts().size();
        PrimitiveList.Int.Reader cellReaderPorts = cellReader.getPorts();
        StructList.Reader<Port.Reader> portReaderList = netlist.getPortList();
        for (int j = 0; j < portCount; j++) {
            int portIdx = cellReaderPorts.get(j);
            readEDIFPort(portIdx, n, portReaderList, edifCell);
        }
        
        // Instances
        PrimitiveList.Int.Reader cellInstsReader = cellReader.getInsts();
        int instCount = cellInstsReader.size(); 
        for(int j=0; j < instCount; j++) {
            readEDIFCellInst(cellInstsReader.get(j), n, netlist, edifCell, cellListReader, instListReader);
        }
        
        // Nets
        StructList.Reader<Net.Reader> netListReader = cellReader.getNets();
        int netCount = netListReader.size();
        for(int j=0; j < netCount; j++) {
            Net.Reader netReader = netListReader.get(j);
            EDIFNet net = new EDIFNet(allStrings.get(netReader.getName()), edifCell);
            extractPropertyMap(netReader.getPropMap(), net);
            StructList.Reader<PortInstance.Reader> portInsts = netReader.getPortInsts();
            int portInstCount = portInsts.size();
            for(int k=0; k < portInstCount; k++) {
                PortInstance.Reader portInstReader = portInsts.get(k);
                EDIFCellInst inst = null;
                if(!portInstReader.isExtPort()) {
                    inst = allInsts.get(portInstReader.getInst());
                }
                EDIFCell portCellType = inst == null? edifCell : inst.getCellType();
                EDIFPort port = readEDIFPort(portInstReader.getPort(), n, portReaderList, portCellType);

                net.createPortInst(port, portInstReader.getIdx(), inst);
            }
        }
        
        extractPropertyMap(cellReader.getPropMap(), edifCell);
        
        allCells.ensureSize(cellIdx+1);
        allCells.update(edifCell, cellIdx);
        return edifCell;
    }
    
    public static EDIFPort readEDIFPort(int portIdx, EDIFNetlist n, 
            StructList.Reader<Port.Reader> portReaderList, EDIFCell parent) {
        EDIFPort edifPort = allPorts.get(portIdx);
        if(edifPort != null) {
            parent.addPort(edifPort);
            return edifPort;
        }
        
        Port.Reader portReader = portReaderList.get(portIdx);
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
        edifPort = parent.createPort(portBusName, 
                                     getEDIFDirection(portReader), width);
        extractPropertyMap(portReader.getPropMap(), edifPort);
        
        allPorts.ensureSize(portIdx+1);
        allPorts.update(edifPort, portIdx);
        return edifPort;
    }
    
    public static EDIFCellInst readEDIFCellInst(int instIdx, EDIFNetlist n, Netlist.Reader netlist,
                                                EDIFCell parent, 
                                                StructList.Reader<Netlist.Cell.Reader> cellListReader,
                                                StructList.Reader<Netlist.CellInstance.Reader> instListReader) {
        EDIFCellInst edifCellInst = allInsts.get(instIdx);
        if(edifCellInst != null) {
            parent.addCellInst(edifCellInst);
            return edifCellInst;
        }
        
        CellInstance.Reader instReader = instListReader.get(instIdx);
        int instCellIdx = instReader.getCell();
        EDIFCell cell = allCells.get(instCellIdx);
        if( cell == null ) {
            cell = readEDIFCell(instCellIdx, n, netlist, cellListReader, instListReader);
        }
        
        String instName = allStrings.get(instReader.getName());
        edifCellInst = new EDIFCellInst(instName, cell, parent);
        edifCellInst.setViewref(new EDIFName(allStrings.get(instReader.getView())));
        
        extractPropertyMap(instReader.getPropMap(), edifCellInst);
        
        allInsts.ensureSize(instIdx+1);
        allInsts.update(edifCellInst, instIdx);
        return edifCellInst;
    }
    
    /**
     * Reads Cap'n Proto serialized netlist into a RapidWright netlist in memory
     * @param fileName Name of the serialized netlist file
     * @return Netlist object in RapidWright framework
     * @throws IOException
     */
    public static EDIFNetlist readLogNetlist(String fileName) throws IOException {
        FileInputStream fis = new java.io.FileInputStream(fileName);
        ReaderOptions readerOptions = new ReaderOptions(32L*1024L*1024L*1024L, 64);
        MessageReader readMsg = SerializePacked.readFromUnbuffered((fis).getChannel(),readerOptions);
        fis.close();
        
        Netlist.Reader netlist = readMsg.getRoot(Netlist.factory);
        EDIFNetlist n = new EDIFNetlist(netlist.getName().toString());

        allStrings.clear();
        allPorts.clear();
        allCells.clear();
        allInsts.clear();
        
        TextList.Reader strListReader = netlist.getStrList();
        int strCount = strListReader.size();
        for(int i=0; i < strCount; i++) {
            String str = strListReader.get(i).toString();
            allStrings.add(str);
        }
        
        int cellCount = netlist.getCellList().size();
        StructList.Reader<Netlist.Cell.Reader> cellListReader = netlist.getCellList();
        StructList.Reader<Netlist.CellInstance.Reader> instListReader = netlist.getInstList();
        for(int i=0; i < cellCount; i++) {
            readEDIFCell(i, n, netlist, cellListReader, instListReader);
        }
        
        EDIFDesign design = new EDIFDesign(allCells.get(netlist.getTopInst().getCell()).getName());
        design.setTopCell(allCells.get(netlist.getTopInst().getCell()));
        n.setDesign(design);
        
        // Put libraries in proper export order
        List<EDIFLibrary> libs = n.getLibrariesInExportOrder();
        n.getLibrariesMap().clear();
        for(EDIFLibrary lib : libs) {
        	n.addLibrary(lib);
        }
        
        return n;
    }

}
