package com.xilinx.rapidwright.interchange;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map.Entry;

import org.capnproto.MessageBuilder;
import org.capnproto.MessageReader;
import org.capnproto.PrimitiveList;
import org.capnproto.SerializePacked;
import org.capnproto.StructList;
import org.capnproto.Text;
import org.capnproto.TextList;
import org.capnproto.Void;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFName;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyObject;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Bus;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Cell;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.CellInstance;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Direction;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Net;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Port;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PortInstance;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;
import com.xilinx.rapidwright.tests.CodePerfTracker;


/**
 * Example reader/writer for Cap'n Proto serialization for a logical netlist.
 *
 */
public class LogicalNetlistExample {

    private static Enumerator<EDIFCell> allCells = new Enumerator<>();
    private static Enumerator<EDIFCellInst> allInsts = new Enumerator<>();
    private static Enumerator<EDIFPort> allPorts = new Enumerator<>();
    private static Enumerator<String> allStrings = new Enumerator<>();
    
    /**
     * Takes an EDIF property map and serializes (writes) it using the Cap'n Proto schema.  The
     * opposite is {@link #extractPropertyMap(com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap.Reader, EDIFPropertyObject)} 
     * @param builder The Cap'n Proto property map builder
     * @param obj The EDIF object that has a property map.
     */
    public static void populatePropertyMap(PropertyMap.Builder builder, EDIFPropertyObject obj) {
        StructList.Builder<PropertyMap.Entry.Builder> entries = 
                builder.initEntries(obj.getProperties().size());
        int i = 0;
        for (Entry<EDIFName, EDIFPropertyValue> e : obj.getProperties().entrySet()) {
            PropertyMap.Entry.Builder entry = entries.get(i);
            entry.setKey(allStrings.getIndex(e.getKey().getName()));
            switch (e.getValue().getType()) {
            case BOOLEAN:
                entry.setBoolValue(e.getValue().getValue().equalsIgnoreCase("true")
                        || e.getValue().getValue().equalsIgnoreCase("1"));
                break;
            case INTEGER:
                entry.setIntValue(e.getValue().getIntValue());
                break;
            default:
                entry.setTextValue(allStrings.getIndex(e.getValue().getValue()));
            }
            i++;
        }
    }
    
    /**
     * Extracts the property map information from a Cap'n Proto reader object and deserializes it 
     * into an EDIF property map object. The reverse function is {@link #populatePropertyMap(com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap.Builder, EDIFPropertyObject)}
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
     * Enumerates all cells, ports and cell instances in the netlist so a integer lookup reference
     * can be used for serialization.
     * @param n The current netlist to be serialized
     */
    public static void populateEnumerations(EDIFNetlist n) {
        // Enumerate all cells, ports and instances to break cyclic reference dependency
        // in netlist description
        for (EDIFLibrary lib : n.getLibrariesInExportOrder()) {
            for (EDIFCell cell : lib.getValidCellExportOrder()) {
                allCells.addObject(cell);
                for (EDIFPort port : cell.getPorts()) {
                    allPorts.addObject(port);
                }
                for (EDIFCellInst inst : cell.getCellInsts()) {
                    allInsts.addObject(inst);
                }
            }
        }        
    }

    /**
     * Extracts the EDIFDirection from an EDIFPort and converts it to the schema direction
     * object for serialization.
     * @param port Existing EDIFPort in the netlist
     * @return The corresponding schema direction
     */
    private static Direction getDirection(EDIFPort port) {
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
    
    public static EDIFCell readEDIFCell(int cellIdx, EDIFNetlist n, Netlist.Reader netlist) {
        EDIFCell edifCell = allCells.get(cellIdx);
        if(edifCell != null) return edifCell;
        
        Cell.Reader cellReader = netlist.getCellList().get(cellIdx);
        
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
        int instCount = cellReader.getInsts().size();
        for(int j=0; j < instCount; j++) {
            readEDIFCellInst(cellReader.getInsts().get(j), n, netlist, edifCell);
        }
        
        // Nets
        int netCount = cellReader.getNets().size();
        for(int j=0; j < netCount; j++) {
            Net.Reader netReader = cellReader.getNets().get(j);
            EDIFNet net = new EDIFNet(allStrings.get(netReader.getName()), edifCell);
            extractPropertyMap(netReader.getPropMap(), net);
            int portInstCount = netReader.getPortInsts().size();
            StructList.Reader<PortInstance.Reader> portInsts = netReader.getPortInsts();
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
        
        edifPort = parent.createPort(allStrings.get(portReader.getName()), 
                                     getEDIFDirection(portReader), width);
        extractPropertyMap(portReader.getPropMap(), edifPort);
        
        allPorts.ensureSize(portIdx+1);
        allPorts.update(edifPort, portIdx);
        return edifPort;
    }
    
    public static EDIFCellInst readEDIFCellInst(int instIdx, EDIFNetlist n, Netlist.Reader netlist,
                                                EDIFCell parent) {
        EDIFCellInst edifCellInst = allInsts.get(instIdx);
        if(edifCellInst != null) {
            parent.addCellInst(edifCellInst);
            return edifCellInst;
        }
        
        CellInstance.Reader instReader = netlist.getInstList().get(instIdx);
        int instCellIdx = instReader.getCell();
        EDIFCell cell = allCells.get(instCellIdx);
        if( cell == null ) {
            cell = readEDIFCell(instCellIdx, n, netlist);
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
        MessageReader readMsg = SerializePacked.readFromUnbuffered((fis).getChannel());
        fis.close();
        
        Netlist.Reader netlist = readMsg.getRoot(Netlist.factory);
        EDIFNetlist n = new EDIFNetlist(netlist.getName().toString());

        allStrings.clear();
        allPorts.clear();
        allCells.clear();
        allInsts.clear();
        
        int strCount = netlist.getStrList().size();
        for(int i=0; i < strCount; i++) {
            String str = netlist.getStrList().get(i).toString();
            allStrings.add(str);
        }
        
        int cellCount = netlist.getCellList().size();
        for(int i=0; i < cellCount; i++) {
            readEDIFCell(i, n, netlist);
        }
        EDIFDesign design = new EDIFDesign(allCells.get(netlist.getTopInst().getCell()).getName());
        design.setTopCell(allCells.get(netlist.getTopInst().getCell()));
        n.setDesign(design);
        
        return n;
    }
    
    /**
     * Populates Cap'n Proto message with netlist metadata such as name, top instance, top cell, 
     * etc.
     * @param n The RapidWright netlist (source)
     * @param netlist The Cap'n Proto netlist message (dest)
     */
    public static void writeTopNetlistStuffToNetlistBuilder(EDIFNetlist n, Netlist.Builder netlist) {
        netlist.setName(n.getName());
        
        populatePropertyMap(netlist.getPropMap(), n.getDesign());

        // Store top cell instance
        CellInstance.Builder topBuilder = netlist.initTopInst();
        topBuilder.setName(allStrings.getIndex(n.getTopCellInst().getName()));
        topBuilder.setCell(allCells.getIndex(n.getTopCell()));
        populatePropertyMap(topBuilder.getPropMap(), n.getTopCellInst());
        topBuilder.setView(allStrings.getIndex(n.getTopCellInst().getViewref().getName()));
    }
    
    /**
     * Writes master list of all cell objects to the Cap'n Proto message netlist
     * @param netlist The netlist builder.
     */
    public static void writeAllCellsToNetlistBuilder(Netlist.Builder netlist) {
        StructList.Builder<Cell.Builder> cellsList = netlist.initCellList(allCells.size());

        int i = 0;
        for (EDIFCell cell : allCells) {
            Cell.Builder cellBuilder = cellsList.get(i);
            cellBuilder.setName(allStrings.getIndex(cell.getName()));
            populatePropertyMap(cellBuilder.getPropMap(), cell);
            cellBuilder.setView(allStrings.getIndex(cell.getView()));
            cellBuilder.setLib(allStrings.getIndex(cell.getLibrary().getName()));

            PrimitiveList.Int.Builder insts = cellBuilder.initInsts(cell.getCellInsts().size());
            int j = 0;
            for (EDIFCellInst inst : cell.getCellInsts()) {
                insts.set(j, allInsts.getIndex(inst));
                j++;
            }

            PrimitiveList.Int.Builder ports = cellBuilder.initPorts(cell.getPorts().size());
            j = 0;
            for (EDIFPort port : cell.getPorts()) {
                ports.set(j, allPorts.getIndex(port));
                j++;
            }

            StructList.Builder<Net.Builder> nets = cellBuilder.initNets(cell.getNets().size());
            j = 0;
            for (EDIFNet net : cell.getNets()) {
                Net.Builder netBuilder = nets.get(j);
                netBuilder.setName(allStrings.getIndex(net.getName()));
                populatePropertyMap(netBuilder.getPropMap(), net);
                StructList.Builder<PortInstance.Builder> portInsts = netBuilder
                        .initPortInsts(net.getPortInsts().size());
                int k = 0;
                for (EDIFPortInst portInst : net.getPortInsts()) {
                    PortInstance.Builder piBuilder = portInsts.get(k);
                    piBuilder.setName(allStrings.getIndex(portInst.getName()));
                    piBuilder.setPort(allPorts.getIndex(portInst.getPort()));
                    if (portInst.getCellInst() != null) {
                        piBuilder.setInst(allInsts.getIndex(portInst.getCellInst()));
                    } else {
                        piBuilder.setExtPort(Void.VOID);
                    }
                    piBuilder.setIdx(portInst.getIndex());
                    k++;
                }
                j++;
            }
            i++;
        }
        
    }
    
    public static void writeAllPortsToNetlistBuilder(Netlist.Builder netlist) {
        int i = 0;
        StructList.Builder<Port.Builder> portsList = netlist.initPortList(allPorts.size());
        for (EDIFPort port : allPorts) {
            Port.Builder portBuilder = portsList.get(i);
            portBuilder.setName(allStrings.getIndex(port.getName()));
            portBuilder.setDir(getDirection(port));
            populatePropertyMap(portBuilder.getPropMap(), port);
            if (port.isBus()) {
                Bus.Builder bus = portBuilder.initBus();
                bus.setBusStart(port.getLeft());
                bus.setBusEnd(port.getRight());
            } else {
                portBuilder.setBit(Void.VOID);
            }
            i++;
        }
    }
    
    public static void writeAllInstsToNetlistBuilder(Netlist.Builder netlist) {
        int i = 0;
        StructList.Builder<CellInstance.Builder> cellInstsList = netlist.initInstList(allInsts.size());
        for (EDIFCellInst inst : allInsts) {
            CellInstance.Builder ciBuilder = cellInstsList.get(i);
            ciBuilder.setName(allStrings.getIndex(inst.getName()));
            populatePropertyMap(ciBuilder.getPropMap(), inst);
            ciBuilder.setCell(allCells.indexOf(inst.getCellType()));
            ciBuilder.setView(allStrings.getIndex(inst.getViewref().getName()));
            i++;
        }        
    }
    
    public static void writeAllStringsToNetlistBuilder(Netlist.Builder netlist) {
        int stringCount = allStrings.size();
        TextList.Builder strList = netlist.initStrList(stringCount);
        for(int i=0; i < stringCount; i++) {
            strList.set(i, new Text.Reader(allStrings.get(i)));
        }        
    }
    
    /**
     * Writes a RapidWright netlist to a Cap'n Proto serialized message
     * @param n RapidWright netlist
     * @return Cap'n Proto logical Netlist message of the provided RapidWright netlist
     */
    public static MessageBuilder writeLogNetlist(EDIFNetlist n) {
        populateEnumerations(n);

        MessageBuilder message = new MessageBuilder();
        Netlist.Builder netlist = message.initRoot(Netlist.factory);

        writeTopNetlistStuffToNetlistBuilder(n, netlist);
        
        writeAllCellsToNetlistBuilder(netlist);

        writeAllPortsToNetlistBuilder(netlist);

        writeAllInstsToNetlistBuilder(netlist);
        
        writeAllStringsToNetlistBuilder(netlist);
        
        return message;        
    }

    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <input>.edf");
            System.out.println("   Example round trip test for a logical netlist to start from EDIF,"
                    + " get converted to a\n   Cap'n Proto serialized file and then read back into "
                    + "an EDIF file.  Creates two new files:\n\t1. <input>.netlist "
                    + "- Cap'n Proto serialized file"
                    + "\n\t2. <input>.roundtrip.edf - EDIF after being written/read from serialized format");
            return;
        }
        CodePerfTracker t = new CodePerfTracker("Interchange EDIF->Cap'n Proto->EDIF");
        
        t.start("Read EDIF");
        // Read EDIF into memory using RapidWright
        EDIFNetlist n = EDIFTools.readEdifFile(args[0]);

        t.stop().start("Write Cap'n Proto");
        // Write Netlist to Cap'n Proto Serialization file
        String capnProtoFileName = args[0].replace(".edf", ".netlist");
        MessageBuilder message = writeLogNetlist(n);
        FileOutputStream fo = new java.io.FileOutputStream(capnProtoFileName);
        SerializePacked.writeToUnbuffered(fo.getChannel(), message);
        fo.close();
        
        t.stop().start("Read Cap'n Proto");
        // Read Netlist into RapidWright netlist
        EDIFNetlist n2 = readLogNetlist(capnProtoFileName);
        
        t.stop().start("Write EDIF");
        // Write RapidWright netlist back to edif
        n2.exportEDIF(args[0].replace(".edf", ".roundtrip.edf"));
        
        t.stop().printSummary();
    }
}
