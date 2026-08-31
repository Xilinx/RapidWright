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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Represents an AXI Master instance of the NOC
 * @since 2022.1.0
 */
public class NOCMaster extends NOCClient implements Serializable {

    private static final long serialVersionUID = 890366600283311022L;
    private TrafficClass readTC;
    private TrafficClass writeTC;

    /**
     * Creates an empty NOC master client.
     * @since 2026.1.0
     */
    public NOCMaster() {

    }

    /**
     * Creates a copy of an existing NOC master client.
     * @param nm The NOC master client to copy.
     * @since 2026.1.0
     */
    public NOCMaster(NOCMaster nm) {
        super(nm);
        this.readTC = nm.readTC;
        this.writeTC = nm.writeTC;
    }

    /**
     * Creates a NOC master client from a JSON client description.
     * @param json The JSON client description.
     * @since 2026.1.0
     */
    public NOCMaster(JSONObject json) {
        super(json);
        readTC = TrafficClass.stringToValue(json.getString(NOCJSONUtil.JSON_FIELD_READ_TRAFFIC_CLASS));
        writeTC = TrafficClass.stringToValue(json.getString(NOCJSONUtil.JSON_FIELD_WRITE_TRAFFIC_CLASS));
    }

    /**
     * Gets the corresponding connection based on slave client name.
     * 
     * @param slaveName The name of the slave client that connects to this master.
     * @return The connection between the provided slave client name and this master
     *         or null if none found.
     * @since 2022.1.1
     */
    public NOCConnection getConnectionTo(String slaveName) {
        for (NOCConnection connection : getConnections()) {
            if (connection.getDest().getName().equals(slaveName))
                return connection;
        }
        return null;
    }

    /**
     * Converts this master client to its JSON representation.
     * @return The JSON representation of this master client.
     * @since 2026.1.0
     */
    public JSONObject toJSONObject() {
        JSONObject obj = super.toJSONObject();
        obj.put(NOCJSONUtil.JSON_FIELD_IS_MASTER,true);
        obj.put(NOCJSONUtil.JSON_FIELD_WRITE_TRAFFIC_CLASS, writeTC.toString());
        obj.put(NOCJSONUtil.JSON_FIELD_READ_TRAFFIC_CLASS, readTC.toString());
        obj.put(NOCJSONUtil.JSON_FIELD_MEMORY_APERTURES, new JSONArray());
        return obj;
    }

    /**
     * Gets the traffic class for master writes.
     * 
     * @return The traffic class for master writes.
     * @since 2022.1.1
     */
    public TrafficClass getWriteTC() {
        return writeTC;
    }

    /**
     * Gets the traffic class for master reads.
     * 
     * @return The traffic class for master reads.
     * @since 2022.1.1
     */
    public TrafficClass getReadTC() {
        return readTC;
    }

    /**
     * Sets the traffic class for master writes.
     * @param writeTC The write traffic class to set.
     * @since 2026.1.0
     */
    public void setWriteTC(TrafficClass writeTC) {
        this.writeTC = writeTC;
    }

    /**
     * Sets the traffic class for master reads.
     * @param readTC The read traffic class to set.
     * @since 2026.1.0
     */
    public void setReadTC(TrafficClass readTC) {
        this.readTC = readTC;
    }

}
