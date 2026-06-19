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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

/**
 * Represents a NOC client endpoint within a NOCDesign or NOC solution.
 * @since 2022.1.0
 */
public class NOCClient implements Serializable {

    private static final long serialVersionUID = -7129544855705593999L;
    private String name;
    private boolean hasParityAddr;
    private boolean hasParityData;
    private boolean isVirtual;
    private int axiDataWidth;
    private String location;
    private int destID;

    private ComponentType compType;
    private ProtocolType protocol;

    private List<String> externalConnections;
    private List<NOCConnection> connections;

    private Map<String, String> simMetaData;

    private static final ArrayList<String> unsupportedFields = new ArrayList<String>();

    static {
        unsupportedFields.add("SysAddress");
        unsupportedFields.add("SysAddressSize");
        unsupportedFields.add("Remap");
        unsupportedFields.add("NumReadOutstanding");
        unsupportedFields.add("NumWriteOutstanding");
        unsupportedFields.add("ReadRateLimiter");
        unsupportedFields.add("WriteRateLimiter");
        //unsupportedFields.add("InterleaveSize");
    };

    /**
     * Creates an empty NOC client.
     * @since 2026.1.0
     */
    public NOCClient() {
        connections = new ArrayList<NOCConnection>();
        externalConnections = new ArrayList<String>();
    }

    /**
     * Creates a copy of an existing NOC client.
     * @param nc The NOC client to copy.
     * @since 2026.1.0
     */
    public NOCClient(NOCClient nc) {
        this();
        this.name = nc.name;
        this.hasParityAddr = nc.hasParityAddr;
        this.hasParityData = nc.hasParityData;
        this.isVirtual = nc.isVirtual;
        this.axiDataWidth = nc.axiDataWidth;
        this.location = nc.location;
        this.destID = nc.destID;
        this.compType = nc.compType;
        this.protocol = nc.protocol;
    }

    /**
     * Creates a NOC client from a JSON client description.
     * @param json The JSON client description.
     * @since 2026.1.0
     */
    public NOCClient(JSONObject json) {
        this();
        name = json.getString(NOCJSONUtil.JSON_FIELD_NAME);
        if (json.has(NOCJSONUtil.JSON_FIELD_HAS_PARITY_ADDR))
            hasParityAddr = json.getBoolean(NOCJSONUtil.JSON_FIELD_HAS_PARITY_ADDR);
        if (json.has(NOCJSONUtil.JSON_FIELD_HAS_PARITY_DATA))
            hasParityData = json.getBoolean(NOCJSONUtil.JSON_FIELD_HAS_PARITY_DATA);
        compType = ComponentType.stringToValue(json.getString(NOCJSONUtil.JSON_FIELD_COMPONENT_TYPE));
        protocol = ProtocolType.stringToValue(json.getString(NOCJSONUtil.JSON_FIELD_PROTOCOL));

        if (json.has(NOCJSONUtil.JSON_FIELD_IS_VIRTUAL))
            isVirtual = json.getBoolean(NOCJSONUtil.JSON_FIELD_IS_VIRTUAL);

        if (json.has(NOCJSONUtil.JSON_FIELD_EXTERNAL_CONNECTIONS)) {
            String connList = json.getString(NOCJSONUtil.JSON_FIELD_EXTERNAL_CONNECTIONS);
            externalConnections = new ArrayList<String>();
            externalConnections.addAll(Arrays.asList(connList.split(" ")));
        }

        if (this.isFabricClient()) {
            axiDataWidth = json.getInt(NOCJSONUtil.JSON_FIELD_AXI_DATA_WIDTH);
        }

        if (json.has(NOCJSONUtil.JSON_FIELD_SIM_META_DATA)) {
            simMetaData = new HashMap<String, String>();
            JSONObject simMetaDatas = json.getJSONObject(NOCJSONUtil.JSON_FIELD_SIM_META_DATA);
            for (String key : simMetaDatas.keySet()) {
                String value = simMetaDatas.get(key).toString();
                simMetaData.put(key, value);
            }
        }

        checkUnsupportedFields(json);
    }

    /**
     * Checks whether a JSON client description contains fields that are not supported.
     * @param json The JSON client description to check.
     * @since 2026.1.0
     */
    public void checkUnsupportedFields(JSONObject json) {
        for (String s : unsupportedFields) {
            if (json.has(s))
                System.out.println("Unsupported field " + s + " encountered in " + name);
        }
    }

    /**
     * Converts this client to its JSON representation.
     * @return The JSON representation of this client.
     * @since 2026.1.0
     */
    public JSONObject toJSONObject() {
        JSONObject obj = NOCJSONUtil.createOrderedJSONObject();
        obj.put(NOCJSONUtil.JSON_FIELD_NAME,name);
        if (hasParityAddr) obj.put(NOCJSONUtil.JSON_FIELD_HAS_PARITY_ADDR,hasParityAddr);
        if (hasParityData) obj.put(NOCJSONUtil.JSON_FIELD_HAS_PARITY_DATA,hasParityData);
        obj.put(NOCJSONUtil.JSON_FIELD_COMPONENT_TYPE,compType);
        obj.put(NOCJSONUtil.JSON_FIELD_PROTOCOL,protocol);
        if (this.isFabricClient()) {
            obj.put(NOCJSONUtil.JSON_FIELD_AXI_DATA_WIDTH,axiDataWidth);
        }
        if (isVirtual) {
            obj.put(NOCJSONUtil.JSON_FIELD_IS_VIRTUAL, true);
        }
        if (externalConnections != null && !externalConnections.isEmpty()) {
            String connList = "";
            for (String conn : externalConnections)
                connList += conn + " ";
            connList = connList.substring(0, connList.length()-1);
            obj.put(NOCJSONUtil.JSON_FIELD_EXTERNAL_CONNECTIONS, connList);
        }
        if (simMetaData != null) {
            obj.put(NOCJSONUtil.JSON_FIELD_SIM_META_DATA, simMetaData);
        }

        return obj;
    }

    /**
     * Checks if this client is located in the programmable logic fabric.
     * @return True if this client is located in the programmable logic fabric, false otherwise.
     * @since 2022.1.0
     */
    public boolean isFabricClient() {
        return compType.equals(ComponentType.PL_NSU)
            || compType.equals(ComponentType.PL_NMU);
    }

    /**
     * Adds a connection associated with this NOC client.
     * @param np The connection to add.
     * @since 2026.1.0
     */
    public void addConnection(NOCConnection np) {
        connections.add(np);
    }

    /**
     * Gets the connections associated with this NOC client
     * @return The list of connections associated with this NOC client
     * @since 2022.1.0
     */
    public List<NOCConnection> getConnections() {
        return connections;
    }

    /**
     * Removes a connection associated with this NOC client.
     * @param np The connection to remove.
     * @since 2026.1.0
     */
    public void removeConnection(NOCConnection np) {
        connections.remove(np);
    }

    /**
     * Sets the location of the client
     * @param location The site of the client to set
     * @since 2022.1.0
     */
    public void setLocation(String location) {
        this.location = location;
    }

    /**
     * Gets the name of the location (site) of this client
     * @return The name of the location (site) of this client
     * @since 2022.1.0
     */
    public String getLocation() {
        return location;
    }

    /**
     * Sets the destination ID for this client.
     * @param destID The destination ID to set.
     * @since 2026.1.0
     */
    public void setDestID(int destID) {
        this.destID = destID;
    }

    /**
     * Gets the destination ID for this client.
     * @return The destination ID for this client.
     * @since 2026.1.0
     */
    public int getDestID() {
        return destID;
    }

    /**
     * Gets the name of this client
     * @return The name of this client
     * @since 2022.1.0
     */
    public String getName() {
        return name;
    }

    /**
     * Checks if this client is a DDR memory client
     * @return True if this is a DDR memory client, false otherwise.
     * @since 2022.1.0
     */
    public boolean isDDRC() {
        return compType.equals(ComponentType.DDRC);
    }

    /**
     * Sets the name of this client.
     * @param name The client name to set.
     * @since 2026.1.0
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Gets external connection names for this client.
     * @return The external connection names for this client.
     * @since 2026.1.0
     */
    public List<String> getExternalConnections() {
        return externalConnections;
    }

    /**
     * Adds an external connection name for this client.
     * @param s The external connection name to add.
     * @since 2026.1.0
     */
    public void addExternalConnection(String s) {
        externalConnections.add(s);
    }

    /**
     * Sets whether this client has address parity.
     * @param hasParityAddr True if this client has address parity, false otherwise.
     * @since 2026.1.0
     */
    public void setParityAddr(boolean hasParityAddr) {
        this.hasParityAddr = hasParityAddr;
    }

    /**
     * Checks whether this client has address parity.
     * @return True if this client has address parity, false otherwise.
     * @since 2026.1.0
     */
    public boolean hasParityAddr() {
        return hasParityAddr;
    }

    /**
     * Sets whether this client has data parity.
     * @param hasParityData True if this client has data parity, false otherwise.
     * @since 2026.1.0
     */
    public void setParityData(boolean hasParityData) {
        this.hasParityData = hasParityData;
    }

    /**
     * Checks whether this client has data parity.
     * @return True if this client has data parity, false otherwise.
     * @since 2026.1.0
     */
    public boolean hasParityData() {
        return hasParityData;
    }

    /**
     * Sets whether this client is virtual.
     * @param isVirtual True if this client is virtual, false otherwise.
     * @since 2026.1.0
     */
    public void setVirtual(boolean isVirtual) {
        this.isVirtual = isVirtual;
    }

    /**
     * Checks whether this client is virtual.
     * @return True if this client is virtual, false otherwise.
     * @since 2026.1.0
     */
    public boolean isVirtual() {
        return isVirtual;
    }

    /**
     * Sets the AXI data width for this client.
     * @param axiDataWidth The AXI data width to set.
     * @since 2026.1.0
     */
    public void setAxiDataWidth(int axiDataWidth) {
        this.axiDataWidth = axiDataWidth;
    }

    /**
     * Gets the AXI data width for this client.
     * @return The AXI data width for this client.
     * @since 2026.1.0
     */
    public int getAxiDataWidth() {
        return axiDataWidth;
    }

    /**
     * Sets the component type of this client.
     * @param compType The component type to set.
     * @since 2026.1.0
     */
    public void setComponentType(ComponentType compType) {
        this.compType = compType;
    }

    /**
     * Gets the component type of this client
     * @return the component type of this client
     * @since 2022.1.0
     */
    public ComponentType getComponentType() {
        return compType;
    }

    /**
     * Sets the protocol type of this client.
     * @param protocol The protocol type to set.
     * @since 2026.1.0
     */
    public void setProtocol(ProtocolType protocol) {
        this.protocol = protocol;
    }

    /**
     * Gets the protocol type of this client
     * @return the protocol type of this client
     * @since 2022.1.0
     */
    public ProtocolType getProtocol() {
        return protocol;
    }

    /**
     * Gets simulation metadata for this client.
     * @return The simulation metadata for this client.
     * @since 2026.1.0
     */
    public Map<String, String> getSimMetaData() {
        return simMetaData;
    }

    /**
     * Gets the String representation of this client
     * 
     * @return The String representation of this client, 'name + (componentType)'
     * @since 2022.1.0
     */
    @Override
    public String toString() {
        return name + " (" + compType + ")";
    }

}
