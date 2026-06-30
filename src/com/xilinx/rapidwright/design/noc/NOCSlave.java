/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Zac Blair, Xilinx Research Labs.
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
package com.xilinx.rapidwright.design.noc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.json.JSONArray;
import org.json.JSONObject;

import com.xilinx.rapidwright.util.Pair;

/**
 * Represents an AXI Slave instance of the NOC
 * @since 2022.1.0
 */
public class NOCSlave extends NOCClient implements Serializable {

    private static final long serialVersionUID = 8335421544122786673L;
    private TreeMap<String,String> memParams;
    private ArrayList<String> ports;
    private ArrayList<Pair<String,String>> sysAddresses;
    private Map<String,Integer> portDestIDs;
    private int interleaveSize;

    /**
     * Creates an empty NOC slave client.
     * @since 2026.1.0
     */
    public NOCSlave() {
        super();
        init();
    }

    private void init() {
        ports = new ArrayList<String>();
        sysAddresses = new ArrayList<Pair<String,String>>();
        portDestIDs =  new HashMap<String,Integer>();
        interleaveSize = 0;
    }

    /**
     * Creates a copy of an existing NOC slave client.
     * @param ns The NOC slave client to copy.
     * @since 2026.1.0
     */
    public NOCSlave(NOCSlave ns) {
        super(ns);
        init();
        this.ports.addAll(ns.ports);
        this.sysAddresses.addAll(ns.sysAddresses);
        if (this.isDDRC()) {
            memParams = new TreeMap<String,String>();
            memParams.putAll(ns.memParams);
            this.portDestIDs.putAll(ns.portDestIDs);
        }
    }

    /**
     * Creates a NOC slave client from a JSON client description.
     * @param json The JSON client description.
     * @since 2026.1.0
     */
    public NOCSlave(JSONObject json) {
        super(json);
        ports = new ArrayList<String>();
        sysAddresses = new ArrayList<Pair<String,String>>();
        portDestIDs = new HashMap<String,Integer>();
        JSONArray apertures = json.getJSONArray(NOCJSONUtil.JSON_FIELD_MEMORY_APERTURES);
        for (int i=0; i<apertures.length(); i++) {
            JSONObject segment = apertures.getJSONObject(i);
            sysAddresses.add(new Pair<String,String>(segment.getString(NOCJSONUtil.JSON_FIELD_ADDRESS_BASE), segment.getString(NOCJSONUtil.JSON_FIELD_ADDRESS_SIZE)));
        }
        if (this.isDDRC()) {
            memParams = new TreeMap<String,String>();
            JSONObject memParamArray = json.getJSONObject(NOCJSONUtil.JSON_FIELD_DDRC_PARAMS);
            for (String key : memParamArray.keySet()) {
                memParams.put(key,memParamArray.getString(key));
            }
            JSONArray portArray = json.getJSONArray(NOCJSONUtil.JSON_FIELD_LOGICAL_PORTS);
            for (int i=0; i<portArray.length(); i++) {
                ports.add(portArray.getString(i));
            }
            if (json.has(NOCJSONUtil.JSON_FIELD_INTERLEAVE_SIZE)) {
                interleaveSize = json.getInt(NOCJSONUtil.JSON_FIELD_INTERLEAVE_SIZE);
            }
        }
    }

    /**
     * Gets the memory parameters for this slave client.
     * @return The memory parameters for this slave client.
     * @since 2026.1.0
     */
    public TreeMap<String,String> getMemParams() {
        return memParams;
    }

    /**
     * Sets the memory parameters for this slave client.
     * @param memParams The memory parameters to set.
     * @since 2026.1.0
     */
    public void setMemParams(TreeMap<String,String> memParams) {
        this.memParams = memParams;
    }

    /**
     * Gets the ports on this slave client.
     * @return The list of ports on this slave client.
     * @since 2022.1.0
     */
    public List<String> getPorts() {
        return ports;
    }

    /**
     * Sets the logical ports on this slave client.
     * @param ports The logical ports to set.
     * @since 2026.1.0
     */
    public void setPorts(ArrayList<String> ports) {
        this.ports = ports;
    }

    /**
     * Adds a logical port to this slave client.
     * @param port The logical port to add.
     * @since 2026.1.0
     */
    public void addPort(String port) {
        ports.add(port);
    }

    /**
     * Removes a logical port from this slave client.
     * @param port The logical port to remove.
     * @since 2026.1.0
     */
    public void removePort(String port) {
        ports.remove(port);
    }

    /**
     * Gets the system address ranges for this slave client.
     * @return The system address ranges for this slave client.
     * @since 2026.1.0
     */
    public List<Pair<String,String>> getSysAddresses() {
        return sysAddresses;
    }

    /**
     * Adds a system address range to this slave client.
     * @param base The base address for the range.
     * @param size The size of the range.
     * @since 2026.1.0
     */
    public void addSysAddress(String base, String size) {
        sysAddresses.add(new Pair<String,String>(base,size));
    }

    /**
     * Removes a system address range from this slave client by base address.
     * @param base The base address of the range to remove.
     * @since 2026.1.0
     */
    public void removeSysAddress(String base) {
        for (Pair<String,String> addr : sysAddresses) {
            if (addr.getFirst().equals(base)) {
                sysAddresses.remove(addr);
            }
        }
    }

    /**
     * Gets the map of logical ports to destination IDs.
     * @return The map of logical ports to destination IDs.
     * @since 2026.1.0
     */
    public Map<String,Integer> getPortDestIDMap() {
        return portDestIDs;
    }

    /**
     * Gets the destination ID for a logical port.
     * @param portName The logical port name.
     * @return The destination ID for the logical port.
     * @since 2026.1.0
     */
    public int getPortDestID(String portName) {
        return portDestIDs.get(portName).intValue();
    }

    /**
     * Sets the map of logical ports to destination IDs.
     * @param portDestIDs The map of logical ports to destination IDs.
     * @since 2026.1.0
     */
    public void setPortDestIDMap(Map<String,Integer> portDestIDs) {
        this.portDestIDs = portDestIDs;
    }

    /**
     * Sets the destination ID for a logical port.
     * @param portName The logical port name.
     * @param destID The destination ID to set.
     * @since 2026.1.0
     */
    public void setPortDestID(String portName, int destID) {
        if (!ports.contains(portName)) return;
        portDestIDs.put(portName, destID);
    }

    /**
     * Gets the interleave size in bytes.
     * @return The interleave size in bytes.
     * @since 2026.1.0
     */
    public int getInterleaveSize() {
        return interleaveSize;
    }

    /**
     * Sets the interleave size in bytes.
     * @param bytes The interleave size in bytes.
     * @since 2026.1.0
     */
    public void setInterleaveSize(int bytes) {
        interleaveSize = bytes;
    }

    /**
     * Converts this slave client to its JSON representation.
     * @return The JSON representation of this slave client.
     * @since 2026.1.0
     */
     public JSONObject toJSONObject() {
        JSONObject obj = super.toJSONObject();
        obj.put(NOCJSONUtil.JSON_FIELD_IS_MASTER,false);
        if (ports != null && !ports.isEmpty()) {
            JSONArray portArray = new JSONArray();
            for (String port : ports) {
                portArray.put(port);
            }
            obj.put(NOCJSONUtil.JSON_FIELD_LOGICAL_PORTS, portArray);
        }
        JSONArray addressArray = new JSONArray();
        for (Pair<String,String> addr : sysAddresses) {
            JSONObject addrObj = NOCJSONUtil.createOrderedJSONObject();
            addrObj.put(NOCJSONUtil.JSON_FIELD_ADDRESS_BASE,addr.getFirst());
            addrObj.put(NOCJSONUtil.JSON_FIELD_ADDRESS_SIZE,addr.getSecond());
            addressArray.put(addrObj);
        }
        obj.put(NOCJSONUtil.JSON_FIELD_MEMORY_APERTURES, addressArray);
        if (this.isDDRC()) {
            JSONObject memParamArray = NOCJSONUtil.createOrderedJSONObject();
            for (Map.Entry<String,String> param : memParams.entrySet()) {
                memParamArray.put(param.getKey(),param.getValue());
            }
            obj.put(NOCJSONUtil.JSON_FIELD_DDRC_PARAMS, memParamArray);
            if (interleaveSize != 0) {
                obj.put(NOCJSONUtil.JSON_FIELD_INTERLEAVE_SIZE, interleaveSize);
            }
        }
        return obj;
    }

    /**
     * Gets the corresponding connection based on master client name.
     * @param masterName The name of the master client that connects to this slave.
     * @return The connection between the provided master client name and this slave or null if
     * none found.
     * @since 2022.1.0
     */
    public NOCConnection getConnectionFrom(String masterName) {
        for (NOCConnection path : getConnections()) {
            if (path.getSource().getName().equals(masterName))
                return path;
        }
        return null;
    }

}
