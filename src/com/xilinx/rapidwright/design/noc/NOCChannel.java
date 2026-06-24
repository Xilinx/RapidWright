/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author Zac Blair, Xilinx Research Labs.
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
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Represents the protocol and physical path of resources used to satisfy a protocol of a
 * NOC Connection.
 * @since 2022.1.0
 */
public class NOCChannel implements Serializable {

    private static final long serialVersionUID = 6748357394089207374L;
    private ChannelType channel;
    private int vc;
    private int requiredBandwidth;
    private int estimatedBandwidth;
    private int requiredLatency;
    private int estimatedLatency;

    private List<String> channelPath;

    /**
     * Creates an empty NOC channel.
     * @since 2026.1.0
     */
    public NOCChannel() {

    }

    /**
     * Creates a NOC channel from a JSON channel description.
     * @param json The JSON channel description.
     * @since 2026.1.0
     */
    public NOCChannel(JSONObject json) {
        this();

        channel = ChannelType.stringToValue(json.getString(NOCJSONUtil.JSON_FIELD_NET_CHANNEL));
        vc = json.getInt(NOCJSONUtil.JSON_FIELD_VIRTUAL_CHANNEL);
        //Required bandwidth is a function of:
        // endpoint width, avg burst length, frequency, Path BW, and channel
        // For example:
        //    AXI width: 32,  AXI avg burst: 4, Frequency: 1000, Path BW: 400
        //    Channel: Write
        //      Each write = 1 header flit + 1 data flit (32-bit x 4-burst = 1x 128-noc)
        //      For 400 Mbps write bandwidth, we need:
        //            800Mbps NOC Write bandwidth & 400Mbps Write response BW
        //    For Read/Read resp, 400Mbps with 512-bit avg burst, you need 1 READ_REQ per
        //  4 beats of RRESP, or 100Mbps on the READ_REQ channel.
        requiredBandwidth = json.getInt(NOCJSONUtil.JSON_FIELD_REQUIRED_BW);
        estimatedBandwidth = json.getInt(NOCJSONUtil.JSON_FIELD_ACHIEVED_BW);

        requiredLatency = json.getInt(NOCJSONUtil.JSON_FIELD_REQUIRED_LATENCY);
        estimatedLatency = json.getInt(NOCJSONUtil.JSON_FIELD_ACHIEVED_LATENCY);
        //= net.getString("PhyInstanceStart"); //Redundant: component array.
        //= net.getString("PhyInstanceEnd"); //Direction depends on Channel (R/W/RR/WR)

        //Nodes
        JSONArray nodeArray = json.getJSONArray(NOCJSONUtil.JSON_FIELD_ROUTE_NODES);
        channelPath = new ArrayList<String>();
        for (int i=0; i<nodeArray.length(); i+=2) {
            String loc = nodeArray.getString(i);
            String pin = nodeArray.getString(i+1);
            channelPath.add(loc + "/" + pin);
        }
    }

    /**
     * Gets the list of switches used to connect this NOC Channel
     * @return The list of switches used in this channel
     * @since 2022.1.0
     */
    public List<String> getChannelPath() {
        return channelPath;
    }

    /**
     * Sets the list of switches used to connect this NOC Channel
     * 
     * @param channelPath
     * @since 2026.1.0
     */
    public void setChannelPath(ArrayList<String> channelPath) {
        this.channelPath = channelPath;
    }

    /**
     * Gets this channel's type
     * @return This channel's type
     * @since 2022.1.0
     */
    public ChannelType getChannelType() {
        return channel;
    }

    /**
     * Sets this channel's type
     * 
     * @param channel The new channel type to set
     * @since 2026.1.0
     */
    public void setChannelType(ChannelType channel) {
        this.channel = channel;
    }

    /**
     * Gets the virtual channel index.
     * @return The virtual channel index.
     * @since 2026.1.0
     */
    public int getVc() {
        return vc;
    }

    /**
     * Sets the virtual channel index.
     * @param vc The virtual channel index to set.
     * @since 2026.1.0
     */
    public void setVc(int vc) {
        this.vc = vc;
    }

    /**
     * Gets this channel's required bandwidth (in MB/s)
     * @return The channel's required bandwidth in MB/s
     * @since 2022.1.0
     */
    public int getRequiredBandwidth() {
        return requiredBandwidth;
    }

    /**
     * Sets this channel's required bandwidth (in MB/s)
     * 
     * @param requiredBandwidth Channel's required bandwidth (in MB/s)
     * @since 2026.1.0
     */
    public void setRequiredBandwidth(int requiredBandwidth) {
        this.requiredBandwidth = requiredBandwidth;
    }

    /**
     * Gets the estimated bandwidth expected for this channel (MB/s)
     * @return The estimated channel bandwidth in MB/s
     * @since 2022.1.0
     */
    public int getEstimatedBandwidth() {
        return estimatedBandwidth;
    }

    /**
     * Sets the estimated bandwidth expected for this channel (in MB/s)
     * 
     * @param estimatedBandwidth
     * @since 2026.1.0
     */
    public void setEstimatedBandwidth(int estimatedBandwidth) {
        this.estimatedBandwidth = estimatedBandwidth;
    }

    /**
     * Gets the latency constraint on this channel (in cycles)
     * @return The latency constraint on this channel in cycles
     * @since 2022.1.0
     */
    public int getRequiredLatency() {
        return requiredLatency;
    }

    /**
     * Sets the required latency for this channel in cycles.
     * @param requiredLatency The required latency in cycles.
     * @since 2026.1.0
     */
    public void setRequiredLatency(int requiredLatency) {
        this.requiredLatency = requiredLatency;
    }

    /**
     * Gets the estimated latency achieved on this channel (in cycles)
     * @return The estimated latency achieved on this channel in cycles
     * @since 2022.1.0
     */
    public int getEstimatedLatency() {
        return estimatedLatency;
    }

    /**
     * Sets the estimated latency achieved on this channel in cycles.
     * @param estimatedLatency The estimated latency in cycles.
     * @since 2026.1.0
     */
    public void setEstimatedLatency(int estimatedLatency) {
        this.estimatedLatency = estimatedLatency;
    }

    /**
     * Gets all physical instances used by this channel path.
     * @return The physical instance names used by this channel path.
     * @since 2026.1.0
     */
    public ArrayList<String> getAllPhyInstances() {
        ArrayList<String> insts = new ArrayList<String>();
        for (String pin : channelPath) {
            insts.add(pin.split("/")[0]);
        }
        return insts;
    }

    /**
     * Converts this channel to its JSON representation.
     * @return The JSON representation of this channel.
     * @since 2026.1.0
     */
    public JSONObject toJSONObject() {
        JSONObject obj = NOCJSONUtil.createOrderedJSONObject();
        obj.put(NOCJSONUtil.JSON_FIELD_START_INSTANCE, channelPath.get(0).split("/")[0]);
        obj.put(NOCJSONUtil.JSON_FIELD_END_INSTANCE, channelPath.get(channelPath.size()-1).split("/")[0]);
        obj.put(NOCJSONUtil.JSON_FIELD_VIRTUAL_CHANNEL, vc);
        obj.put(NOCJSONUtil.JSON_FIELD_NET_CHANNEL, channel.toString());
        for (String pin : channelPath) {
            String[] cellAndPin = pin.split("/");
            obj.append(NOCJSONUtil.JSON_FIELD_ROUTE_NODES,cellAndPin[0]);
            obj.append(NOCJSONUtil.JSON_FIELD_ROUTE_NODES,cellAndPin[1]);
        }
        obj.put(NOCJSONUtil.JSON_FIELD_REQUIRED_BW, requiredBandwidth);
        obj.put(NOCJSONUtil.JSON_FIELD_ACHIEVED_BW, estimatedBandwidth);
        obj.put(NOCJSONUtil.JSON_FIELD_REQUIRED_LATENCY, requiredLatency);
        obj.put(NOCJSONUtil.JSON_FIELD_ACHIEVED_LATENCY, estimatedLatency);
        return obj;
    }

}
