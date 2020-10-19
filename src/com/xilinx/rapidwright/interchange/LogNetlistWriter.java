package com.xilinx.rapidwright.interchange;

import java.io.IOException;
import java.util.Map.Entry;

import org.capnproto.MessageBuilder;
import org.capnproto.PrimitiveList;
import org.capnproto.StructList;
import org.capnproto.Text;
import org.capnproto.TextList;
import org.capnproto.Void;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFName;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyObject;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Bus;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Cell;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.CellInstance;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Net;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.Port;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PortInstance;
import com.xilinx.rapidwright.interchange.LogicalNetlist.Netlist.PropertyMap;

public class LogNetlistWriter {
	
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
                    piBuilder.setPort(allPorts.getIndex(portInst.getPort()));
                    if (portInst.getCellInst() != null) {
                        piBuilder.setInst(allInsts.getIndex(portInst.getCellInst()));
                    } else {
                        piBuilder.setExtPort(Void.VOID);
                    }
                    if(portInst.getPort().isBus()) {
                        piBuilder.initBusIdx().setIdx(portInst.getIndex());   
                    }
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
            portBuilder.setName(allStrings.getIndex(port.getBusName()));
            portBuilder.setDir(LogNetlistReader.getDirection(port));
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
     * Writes a RapidWright netlist to a Cap'n Proto serialized file
     * @param n RapidWright netlist
     * @param fileName Name of the file to write
     * @throws IOException 
     */
    public static void writeLogNetlist(EDIFNetlist n, String fileName) throws IOException {
        MessageBuilder message = new MessageBuilder();
        Netlist.Builder netlist = message.initRoot(Netlist.factory);

        populateNetlistBuilder(n, netlist, false);

        Interchange.writeInterchangeFile(fileName, message);
    }
    
    /**
     * Helper method to populate the logical netlist object with an existing builder.
     * @param n The EDIF Netlist to serialize
     * @param netlist The current builder object to receive the EDIF Netlist
     */
    protected static void populateNetlistBuilder(EDIFNetlist n, Netlist.Builder netlist, 
            boolean skipTopNetlistStuff) {
        populateEnumerations(n);
        if(!skipTopNetlistStuff) {
            writeTopNetlistStuffToNetlistBuilder(n, netlist);
        }else {
            netlist.setName(n.getName());
        }
        
        writeAllCellsToNetlistBuilder(netlist);

        writeAllPortsToNetlistBuilder(netlist);

        writeAllInstsToNetlistBuilder(netlist);
        
        writeAllStringsToNetlistBuilder(netlist);
    }
}
