/*
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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
package com.xilinx.rapidwright.design;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * Abstract base class for {@link ModuleInst} and {@link ModuleImplsInst}
 */
public abstract class AbstractModuleInst<ModuleT, T extends AbstractModuleInst<ModuleT, T>> {

    /** Name of the module instance */
    private String name;
    /** Reference to the logical cell instance in the netlist */
    private EDIFCellInst cellInst;

    public AbstractModuleInst(String name, EDIFCellInst cellInst) {
        this.name = name;
        this.cellInst = cellInst;
    }

    public AbstractModuleInst(String name) {
        this.name = name;
    }

    public abstract void unplace();

    public abstract ModuleT getModule();

    /**
     * @return the name of this module instance
     */
    public String getName(){
        return name;
    }

    /**
     * @param name the name to set
     */
    public void setName(String name){
        this.name = name;
    }


    public EDIFCellInst getCellInst() {
        return cellInst;
    }

    public void setCellInst(EDIFCellInst cellInst) {
        this.cellInst = cellInst;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode(){
        final int prime = 31;
        int result = 1;
        result = prime * result + ((name == null) ? 0 : name.hashCode());
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj){
        if(this == obj)
            return true;
        if(obj == null)
            return false;
        if(getClass() != obj.getClass())
            return false;
        AbstractModuleInst<?,?> other = (AbstractModuleInst<?,?>) obj;
        if(name == null){
            if(other.name != null)
                return false;
        }
        else if(!name.equals(other.name))
            return false;
        return true;
    }
    public String toString(){
        return name;
    }


    /**
     * Connects two signals by port name between this module instance and a top-level port.
     * This method will create a new net for the connection handling adding both
     * a logical net (EDIFNet) and physical net (Net).
     * @param portName This module instance's port name to connect.
     * @param otherPortName The top-level port of the the cell instance.
     *
     */
    public void connect(String portName, String otherPortName){
        connect(portName, null, otherPortName, -1);
    }

    /**
     * Connects two signals by port name between this module instance and a top-level port.
     * This method will create a new net for the connection handling adding both
     * a logical net (EDIFNet) and physical net (Net).
     * @param portName This module instance's port name to connect.
     * @param otherPortName The top-level port of the the cell instance.
     * @param busIndex If the port is multi-bit, specify the index to connect or -1 if single bit bus.
     */
    public void connect(String portName, String otherPortName, int busIndex){
        connect(portName, null, otherPortName, busIndex);
    }

    /**
     * Connects two signals by port name between this module instance and another.
     * This method will create a new net for the connection handling adding both
     * a logical net (EDIFNet) and physical net (Net).
     * @param portName This module instance's port name to connect.
     * @param other The other module instance to connect to. If this is null, it will
     * connect it to an existing parent cell port named otherPortName
     * @param otherPortName The port name on the other module instance to connect to or
     * the top-level port of the the cell instance.
     */
    public void connect(String portName, T other, String otherPortName){
        connect(portName, other, otherPortName, -1);
    }

    /**
     * Connects two signals by port name between this module instance and another.
     * This method will create a new net for the connection handling adding both
     * a logical net (EDIFNet) and physical net (Net).
     * @param portName This module instance's port name to connect.
     * @param other The other module instance to connect to. If this is null, it will
     * connect it to an existing parent cell port named otherPortName
     * @param otherPortName The port name on the other module instance to connect to or
     * the top-level port of the the cell instance.
     * @param busIndex If the port is multi-bit, specify the index to connect or -1 if single bit bus.
     */
    public void connect(String portName, T other, String otherPortName, int busIndex){
        connect(portName, busIndex, other, otherPortName, busIndex);
    }

    /**
     * Connects two signals by port name between this module instance and another.
     * This method will create a new net for the connection handling adding both
     * a logical net (EDIFNet) and physical net (Net).
     * @param portName This module instance's port name to connect.
     * @param busIndex0 If the assigned port of this module instance is multi-bit,
     * specify the index to connect or -1 if single bit bus.
     * @param other The other module instance to connect to. If this is null, it will
     * connect it to an existing parent cell port named otherPortName
     * @param otherPortName The port name on the other module instance to connect to or
     * the top-level port of the the cell instance.
     * @param busIndex1 If the port (of the other module instance or the existing parent cell) is multi-bit,
     * specify the index to connect or -1 if single bit bus.
     */

    public void connect(String portName, int busIndex0, T other, String otherPortName, int busIndex1){
        EDIFCell top = cellInst.getParentCell();
        EDIFCellInst eci0 = cellInst;
        if(eci0 == null) throw new RuntimeException("ERROR: Couldn't find logical cell instance for " + getName());
        if(other == null) {
            // Connect to a top-level port
            EDIFPort port = top.getPort(otherPortName);

            String netName = busIndex1 == -1 ? otherPortName : port.getBusName() + "[" + busIndex1 + "]";
            EDIFNet net = top.getNet(netName);
            if(net == null){
                net = top.createNet(netName);
            }
            if(net.getPortInst(null, netName) == null){
                net.createPortInst(port, busIndex1);
            }
            net.createPortInst(portName, busIndex0, eci0);

            return;
        }
        EDIFCellInst eci1 = other.getCellInst();
        if(eci1 == null) throw new RuntimeException("ERROR: Couldn't find logical cell instance for " + getName());

        String netName = busIndex0 == -1 ? getName() + "_" + portName : getName() + "_" + portName + "["+busIndex0+"]";
        EDIFNet net = top.createNet(netName);
        net.createPortInst(portName, busIndex0, eci0);
        net.createPortInst(otherPortName, busIndex1, eci1);
    }

    public abstract RelocatableTileRectangle getBoundingBox();
}
