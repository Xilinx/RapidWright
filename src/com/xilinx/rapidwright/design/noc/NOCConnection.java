/*
 * Copyright (c) 2015-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
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
import java.util.HashSet;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Represents a NOC connection between two client endpoints.
 * @since 2022.1.0
 */
public class NOCConnection implements Serializable {

    private static final long serialVersionUID = -1354063342472694874L;
    private int phase;
    private NOCMaster source;
    private NOCSlave dest;
    private String port; //Destination port
    private CommunicationType commType;
    private int readBandwidth;
    private int writeBandwidth;
    private int estReadBandwidth;
    private int estWriteBandwidth;
    private int readLatency;
    private int writeLatency;
    private int readAverageBurst;
    private int writeAverageBurst;
    private String exclusiveGroup;

    private Map<ChannelType,NOCChannel> channels;

    boolean isRouted;
    boolean isPathLocked;
    boolean isSourceLocked;
    boolean isDestLocked;


    private static final ArrayList<String> unsupportedFields = new ArrayList<String>();
    static {
        unsupportedFields.add("WriteBurstSize");
        unsupportedFields.add("ReadBurstSize");
        unsupportedFields.add("SeparateRoutingGroup");
    };

    /**
     * Creates an empty NOC connection.
     * @since 2026.1.0
     */
    public NOCConnection() {
        channels = new HashMap<ChannelType,NOCChannel>();
        isRouted = false;
        phase = 0;
    }

    /**
     * Creates a NOC connection by copying traffic properties from a template path.
     * @param source The source master client.
     * @param dest The destination slave client.
     * @param templatePath The connection that provides the traffic properties to copy.
     * @since 2026.1.0
     */
    public NOCConnection(NOCMaster source, NOCSlave dest, NOCConnection templatePath) {
        this();
        this.source = source;
        this.dest = dest;
        this.port = templatePath.port;
        this.phase = templatePath.phase;
        this.commType = templatePath.commType;
        this.readBandwidth = templatePath.readBandwidth;
        this.writeBandwidth = templatePath.writeBandwidth;
        //this.readAchievedMBps = templatePath.readAchievedMBps;
        //this.writeAchievedMBps = templatePath.writeAchievedMBps;
        this.readLatency = templatePath.readLatency;
        this.writeLatency = templatePath.writeLatency;
        this.readAverageBurst = templatePath.readAverageBurst;
        this.writeAverageBurst = templatePath.writeAverageBurst;
    }

    /**
     * Creates a NOC connection from a JSON path description.
     * @param json The JSON path description.
     * @param nd The NOC design containing the referenced clients.
     * @since 2026.1.0
     */
    public NOCConnection(JSONObject json, NOCDesign nd) {
        this();
        phase = json.getInt(NOCJSONUtil.JSON_FIELD_PHASE);
        source = nd.getMasterClients().get(json.getString(NOCJSONUtil.JSON_FIELD_FROM_CELL));
        dest = nd.getSlaveClients().get(json.getString(NOCJSONUtil.JSON_FIELD_TO_CELL));
        port = json.getString(NOCJSONUtil.JSON_FIELD_PORT);
        commType = CommunicationType.stringToValue(json.getString(NOCJSONUtil.JSON_FIELD_COMMUNICATION_TYPE));
        readBandwidth = json.getInt(NOCJSONUtil.JSON_FIELD_READ_BANDWIDTH);
        writeBandwidth = json.getInt(NOCJSONUtil.JSON_FIELD_WRITE_BANDWIDTH);
        readLatency = json.getInt(NOCJSONUtil.JSON_FIELD_READ_LATENCY);
        writeLatency = json.getInt(NOCJSONUtil.JSON_FIELD_WRITE_LATENCY);
        readAverageBurst = json.getInt(NOCJSONUtil.JSON_FIELD_READ_AVERAGE_BURST);
        writeAverageBurst = json.getInt(NOCJSONUtil.JSON_FIELD_WRITE_AVERAGE_BURST);
        if (json.has(NOCJSONUtil.JSON_FIELD_EXCLUSIVE_GROUP)) {
            exclusiveGroup = json.getString(NOCJSONUtil.JSON_FIELD_EXCLUSIVE_GROUP);
        }

        //= json.getString("ReadTC"); //Redundant: NOC Master
        //= json.getString("WriteTC"); //Redundant: NOC Master
        checkUnsupportedFields(json);
    }

    /**
     * Checks whether a JSON path description contains fields that are not supported.
     * @param json The JSON path description to check.
     * @since 2026.1.0
     */
    public void checkUnsupportedFields(JSONObject json) {
        for (String s : unsupportedFields) {
            if (json.has(s))
                System.out.println("Unsupported field " + s +
                    " encountered in Path " + source + " -> " + dest);
        }
    }

    /**
     * Converts this connection to its traffic JSON representation.
     * @return The traffic JSON representation of this connection.
     * @since 2026.1.0
     */
    public JSONObject getTrafficJSONObject() {
        JSONObject obj = NOCJSONUtil.createOrderedJSONObject();
        obj.put(NOCJSONUtil.JSON_FIELD_PHASE, phase);
        obj.put(NOCJSONUtil.JSON_FIELD_FROM_CELL,source.getName());
        obj.put(NOCJSONUtil.JSON_FIELD_TO_CELL,dest.getName());
        obj.put(NOCJSONUtil.JSON_FIELD_PORT,port);
        obj.put(NOCJSONUtil.JSON_FIELD_COMMUNICATION_TYPE,commType);
        obj.put(NOCJSONUtil.JSON_FIELD_READ_BANDWIDTH,readBandwidth);
        obj.put(NOCJSONUtil.JSON_FIELD_READ_LATENCY,readLatency);
        obj.put(NOCJSONUtil.JSON_FIELD_READ_AVERAGE_BURST,readAverageBurst);
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_BANDWIDTH,writeBandwidth);
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_LATENCY,writeLatency);
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_AVERAGE_BURST,writeAverageBurst);
        if (exclusiveGroup != null) {
            obj.put(NOCJSONUtil.JSON_FIELD_EXCLUSIVE_GROUP, exclusiveGroup);
        }
        return obj;
    }

    /**
     * Gets the source or NOC master for this connection.
     * @return The source or NOC master for this connection
     * @since 2022.1.0
     */
    public NOCMaster getSource() {
        return source;
    }

    /**
     * Gets the destination or NOC slave for this connection.
     * @return The destination or NOC slave for this connection
     * @since 2022.1.0
     */
    public NOCSlave getDest() {
        return dest;
    }

    /**
     * Gets the traffic phase for this connection.
     * @return The traffic phase for this connection.
     * @since 2026.1.0
     */
    public int getPhase() {
        return phase;
    }

    /**
     * Sets the traffic phase for this connection.
     * @param phase The traffic phase to set.
     * @since 2026.1.0
     */
    public void setPhase(int phase) {
        this.phase = phase;
    }

    /**
     * Gets the port name associated with this connection.
     * @return Port name associated with this connection.
     * @since 2022.1.0
     */
    public String getPort() {
        return port;
    }

    /**
     * Sets the port name associated with this connection.
     * @param port The port name to set.
     * @since 2026.1.0
     */
    public void setPort(String port) {
        this.port = port;
    }

    /**
     * Gets the communication type associated with this connection.
     * @return The communication type associated with this connection.
     * @since 2022.1.0
     */
    public CommunicationType getCommType() {
        return commType;
    }

    /**
     * Sets the communication type associated with this connection.
     * @param commType The communication type to set.
     * @since 2026.1.0
     */
    public void setCommType(CommunicationType commType) {
        this.commType = commType;
    }

    /**
     * Gets the read bandwidth in MB/s of this connection.
     * @return The read bandwidth in MB/s of this connection.
     * @since 2022.1.0
     */
    public int getReadBandwidth() {
        return readBandwidth;
    }

    /**
     * Sets the read bandwidth in MB/s for this connection.
     * @param readBandwidth The read bandwidth in MB/s.
     * @since 2026.1.0
     */
    public void setReadBandwidth(int readBandwidth) {
        this.readBandwidth = readBandwidth;
    }

    /**
     * Gets the write bandwidth in MB/s of this connection.
     * @return The write bandwidth in MB/s of this connection.
     * @since 2022.1.0
     */
    public int getWriteBandwidth() {
        return writeBandwidth;
    }

    /**
     * Sets the write bandwidth in MB/s for this connection.
     * @param writeBandwidth The write bandwidth in MB/s.
     * @since 2026.1.0
     */
    public void setWriteBandwidth(int writeBandwidth) {
        this.writeBandwidth = writeBandwidth;
    }

    /**
     * Gets the estimated read bandwidth in MB/s of this connection.
     * @return The estimated read bandwidth in MB/s of this connection.
     * @since 2022.1.0
     */
    public int getEstimatedReadBandwidth() {
        return estReadBandwidth;
    }

    /**
     * Sets the estimated read bandwidth in MB/s for this connection.
     * @param estReadBandwidth The estimated read bandwidth in MB/s.
     * @since 2026.1.0
     */
    public void setEstimatedReadBandwidth(int estReadBandwidth) {
        this.estReadBandwidth = estReadBandwidth;
    }

    /**
     * Gets the estimated write bandwidth in MB/s of this connection.
     * @return The estimated write bandwidth in MB/s of this connection.
     * @since 2022.1.0
     */
    public int getEstimatedWriteBandwidth() {
        return estWriteBandwidth;
    }

    /**
     * Sets the estimated write bandwidth in MB/s for this connection.
     * @param estWriteBandwidth The estimated write bandwidth in MB/s.
     * @since 2026.1.0
     */
    public void setEstimatedWriteBandwidth(int estWriteBandwidth) {
        this.estWriteBandwidth = estWriteBandwidth;
    }

    /**
     * Gets the read latency for this connection in cycles.
     * @return The read latency for this connection in cycles.
     * @since 2022.1.0
     */
    public int getReadLatency() {
        return readLatency;
    }

    /**
     * Sets the read latency for this connection in cycles.
     * @param readLatency The read latency in cycles.
     * @since 2026.1.0
     */
    public void setReadLatency(int readLatency) {
        this.readLatency = readLatency;
    }

    /**
     * Gets the write latency for this connection in cycles.
     * @return The write latency for this connection in cycles.
     * @since 2022.1.0
     */
    public int getWriteLatency() {
        return writeLatency;
    }

    /**
     * Sets the write latency for this connection in cycles.
     * @param writeLatency The write latency in cycles.
     * @since 2026.1.0
     */
    public void setWriteLatency(int writeLatency) {
        this.writeLatency = writeLatency;
    }

    /**
     * Gets the average read burst length for this connection.
     * @return The average read burst length for this connection.
     * @since 2026.1.0
     */
    public int getReadAverageBurst() {
        return readAverageBurst;
    }

    /**
     * Sets the average read burst length for this connection.
     * @param readAverageBurst The average read burst length to set.
     * @since 2026.1.0
     */
    public void setReadAverageBurst(int readAverageBurst) {
        this.readAverageBurst = readAverageBurst;
    }

    /**
     * Gets the average write burst length for this connection.
     * @return The average write burst length for this connection.
     * @since 2026.1.0
     */
    public int getWriteAverageBurst() {
        return writeAverageBurst;
    }

    /**
     * Sets the average write burst length for this connection.
     * @param writeAverageBurst The average write burst length to set.
     * @since 2026.1.0
     */
    public void setWriteAverageBurst(int writeAverageBurst) {
        this.writeAverageBurst = writeAverageBurst;
    }

    /**
     * Gets the map of channels that pertain to this connection.  The map
     * is keyed by the channel type.
     * @return The map of channel types to channels in this connection.
     * @since 2022.1.0
     */
    public Map<ChannelType, NOCChannel> getChannels() {
        return channels;
    }

    /**
     * Sets the channels that pertain to this connection.
     * @param nets The map of channel types to channels to set.
     * @since 2026.1.0
     */
    public void setChannels(Map<ChannelType, NOCChannel> nets) {
        this.channels = nets;
    }

    /**
     * Adds a channel to this connection.
     * @param net The channel to add.
     * @since 2026.1.0
     */
    public void addChannel(NOCChannel net) {
        channels.put(net.getChannelType(),net);
    }

    /**
     * Removes a channel from this connection.
     * @param nc The channel type to remove.
     * @since 2026.1.0
     */
    public void removeChannel(ChannelType nc) {
        channels.remove(nc);
    }

    /**
     * Checks if this connection is routed.
     * @return True if this connection is routed, false otherwise.
     * @since 2022.1.0
     */
    public boolean isRouted() {
        return isRouted;
    }

    /**
     * Sets whether this connection is routed.
     * @param isRouted True if this connection is routed, false otherwise.
     * @since 2026.1.0
     */
    public void setRouted(boolean isRouted) {
        this.isRouted = isRouted;
    }

    /**
     * Checks whether this connection path is locked.
     * @return True if the path is locked, false otherwise.
     * @since 2026.1.0
     */
    public boolean isPathLocked() {
        return isPathLocked;
    }

    /**
     * Sets whether this connection path is locked.
     * @param isPathLocked True if the path is locked, false otherwise.
     * @since 2026.1.0
     */
    public void setPathLocked(boolean isPathLocked) {
        this.isPathLocked = isPathLocked;
    }

    /**
     * Checks whether this connection source is locked.
     * @return True if the source is locked, false otherwise.
     * @since 2026.1.0
     */
    public boolean isSourceLocked() {
        return isSourceLocked;
    }

    /**
     * Sets whether this connection source is locked.
     * @param isSourceLocked True if the source is locked, false otherwise.
     * @since 2026.1.0
     */
    public void setSourceLocked(boolean isSourceLocked) {
        this.isSourceLocked = isSourceLocked;
    }

    /**
     * Checks whether this connection destination is locked.
     * @return True if the destination is locked, false otherwise.
     * @since 2026.1.0
     */
    public boolean isDestLocked() {
        return isDestLocked;
    }

    /**
     * Sets whether this connection destination is locked.
     * @param isDestLocked True if the destination is locked, false otherwise.
     * @since 2026.1.0
     */
    public void setDestLocked(boolean isDestLocked) {
        this.isDestLocked = isDestLocked;
    }

    /**
     * Sets the source master client for this connection.
     * @param source The source master client to set.
     * @since 2026.1.0
     */
    public void setSource(NOCMaster source) {
        this.source = source;
    }

    /**
     * Sets the destination slave client for this connection.
     * @param dest The destination slave client to set.
     * @since 2026.1.0
     */
    public void setDest(NOCSlave dest) {
        this.dest = dest;
    }

    /**
     * Gets the exclusive group name for this connection.
     * @return The exclusive group name for this connection.
     * @since 2026.1.0
     */
    public String getExclusiveGroup() {
        return exclusiveGroup;
    }

    /**
     * Sets the exclusive group name for this connection.
     * @param exclusiveGroup The exclusive group name to set.
     * @since 2026.1.0
     */
    public void setExclusiveGroup(String exclusiveGroup) {
        this.exclusiveGroup = exclusiveGroup;
    }

    /**
     * Parses routed solution data into this connection.
     * @param json The JSON route description to parse.
     * @since 2026.1.0
     */
    public void parseRoute(JSONObject json) {
        //= json.getString("Phase"); //Redundant: Traffic Path
        isSourceLocked = json.getBoolean(NOCJSONUtil.JSON_FIELD_PATH_SRC_LOCKED);
        isDestLocked = json.getBoolean(NOCJSONUtil.JSON_FIELD_PATH_DST_LOCKED);
        //= json.getString("Port"); //Redundant: Slave Client
        //= json.getString("ReadTC"); //Redundant: Master Client
        //= json.getString("WriteTC"); //Redundant: Master Client
        //= json.getInt("ReadBW"); //Redundant: Traffic Path
        //= json.getInt("WriteBW"); //Redundant: Traffic Path
        estReadBandwidth = json.getInt(NOCJSONUtil.JSON_FIELD_READ_BW_ACHIEVED);
        estWriteBandwidth = json.getInt(NOCJSONUtil.JSON_FIELD_WRITE_BW_ACHIEVED);
        //= json.getInt("ReadLatency");  //Redundant: Traffic Path
        //= json.getInt("WriteLatency");  //Redundant: Traffic Path
        //= json.getInt("ReadBestPossibleLatency"); //Not accurate. Same as "required"
        //= json.getInt("WriteBestPossibleLatency"); //"" from T Path's R/WLatency
        isPathLocked = json.getBoolean(NOCJSONUtil.JSON_FIELD_PATH_LOCKED);

        //Nets
        channels = new HashMap<ChannelType,NOCChannel>();
        JSONArray netArray = json.getJSONArray(NOCJSONUtil.JSON_FIELD_PATH_NETS);
        for (int i=0; i<netArray.length(); i++) {
            JSONObject net = netArray.getJSONObject(i);
            ChannelType netChannel = ChannelType.stringToValue(net.getString(NOCJSONUtil.JSON_FIELD_NET_CHANNEL));
            channels.put(netChannel,new NOCChannel(net));
        }
    }

    /**
     * Converts this connection to its solution JSON representation.
     * @return The solution JSON representation of this connection.
     * @since 2026.1.0
     */
    public JSONObject getSolutionJSONObject() {
        JSONObject obj = NOCJSONUtil.createOrderedJSONObject();
        obj.put(NOCJSONUtil.JSON_FIELD_PHASE, phase);
        obj.put(NOCJSONUtil.JSON_FIELD_FROM_CELL,source.getName());
        obj.put(NOCJSONUtil.JSON_FIELD_PATH_SRC_LOCKED,isSourceLocked);
        obj.put(NOCJSONUtil.JSON_FIELD_TO_CELL,dest.getName());
        obj.put(NOCJSONUtil.JSON_FIELD_PATH_DST_LOCKED,isDestLocked);
        obj.put(NOCJSONUtil.JSON_FIELD_PORT,port);
        obj.put(NOCJSONUtil.JSON_FIELD_READ_TRAFFIC_CLASS,source.getReadTC().toString());
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_TRAFFIC_CLASS,source.getWriteTC().toString());
        obj.put(NOCJSONUtil.JSON_FIELD_READ_BANDWIDTH,readBandwidth);
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_BANDWIDTH,writeBandwidth);
        obj.put(NOCJSONUtil.JSON_FIELD_READ_BW_ACHIEVED,estReadBandwidth);
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_BW_ACHIEVED,estWriteBandwidth);
        obj.put(NOCJSONUtil.JSON_FIELD_READ_LATENCY,readLatency);
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_LATENCY,writeLatency);
        obj.put(NOCJSONUtil.JSON_FIELD_READ_BEST_LATENCY,readLatency);
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_BEST_LATENCY,writeLatency);
        obj.put(NOCJSONUtil.JSON_FIELD_PATH_LOCKED,isPathLocked);
        for (NOCChannel net : channels.values()) {
            obj.append(NOCJSONUtil.JSON_FIELD_PATH_NETS, net.toJSONObject());
        }
        return obj;
    }

    /**
     * Gets all physical components used by this connection.
     * @return The physical component names used by this connection.
     * @since 2026.1.0
     */
    public HashSet<String> getAllComponents() {
        HashSet<String> comps = new HashSet<String>();
        for (NOCChannel net : channels.values()) {
            comps.addAll(net.getAllPhyInstances());
        }
        return comps;
    }
}
