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

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import org.json.JSONObject;

/**
 * Utility methods and field names for NOC JSON serialization.
 * @since 2026.1.0
 */
public class NOCJSONUtil implements Serializable {

    private static final long serialVersionUID = 3364483400091126305L;
    protected static final String JSON_FIELD_NAME = "Name";
    protected static final String JSON_FIELD_IS_MASTER = "IsMaster";
    protected static final String JSON_FIELD_HAS_PARITY_ADDR = "HasParityAddr";
    protected static final String JSON_FIELD_HAS_PARITY_DATA = "HasParityData";
    protected static final String JSON_FIELD_COMPONENT_TYPE = "CompType";
    protected static final String JSON_FIELD_PROTOCOL = "Protocol";
    protected static final String JSON_FIELD_AXI_DATA_WIDTH = "AxiDataWidth";
    protected static final String JSON_FIELD_MEMORY_APERTURES = "SysAddresses";
    protected static final String JSON_FIELD_SIM_META_DATA = "SimMetaData";
    protected static final String JSON_FIELD_IS_VIRTUAL = "IsVirtual";
    protected static final String JSON_FIELD_EXTERNAL_CONNECTIONS = "ExternalConn";
    protected static final String JSON_FIELD_EXCLUSIVE_GROUP = "ExclusiveGroup";
    protected static final String JSON_FIELD_SYSTEM_PROPERTIES = "SystemProperties";
    protected static final String JSON_FIELD_DFX_PATHS = "DfxPaths";
    protected static final String JSON_FIELD_FREQUENCY = "Frequency";
    protected static final String JSON_FIELD_CLIENT_INSTANCES = "LogicalInstances";
    protected static final String JSON_FIELD_PATHS = "Paths";
    protected static final String JSON_FIELD_SOLUTION_TYPE = "SolutionType";
    protected static final String JSON_FIELD_LOCK_DEST_IDS = "LockAllDestIds";
    protected static final String JSON_FIELD_COMPONENTS = "Components";
    protected static final String JSON_FIELD_LOGICAL_INST = "TrafficLInst";
    protected static final String JSON_FIELD_FROM_CELL = "From";
    protected static final String JSON_FIELD_TO_CELL = "To";
    protected static final String JSON_FIELD_DEST_ID = "DestId";
    protected static final String JSON_FIELD_PORT_PREFIX = "PORT";
    protected static final String JSON_FIELD_PORT_INDEX = "PortIndex";
    protected static final String JSON_FIELD_WRITE_TRAFFIC_CLASS = "WriteTC";
    protected static final String JSON_FIELD_READ_TRAFFIC_CLASS = "ReadTC";
    protected static final String JSON_FIELD_ADDRESS_BASE = "Base";
    protected static final String JSON_FIELD_ADDRESS_SIZE = "Size";
    protected static final String JSON_FIELD_IP_NAME = "IPName";
    protected static final String JSON_FIELD_COMPONENT_DDRC = "DDRC";
    protected static final String JSON_FIELD_DDRC_PARAMS = "MemoryParams";
    protected static final String JSON_FIELD_INTERLEAVE_SIZE = "InterleaveSize";
    protected static final String JSON_FIELD_LOGICAL_PORTS = "Ports";
    protected static final String JSON_FIELD_START_INSTANCE = "PhyInstanceStart";
    protected static final String JSON_FIELD_END_INSTANCE = "PhyInstanceEnd";
    protected static final String JSON_FIELD_REQUIRED_BW = "RequiredBW";
    protected static final String JSON_FIELD_ACHIEVED_BW = "AchievedBW";
    protected static final String JSON_FIELD_REQUIRED_LATENCY = "RequiredLatency";
    protected static final String JSON_FIELD_ACHIEVED_LATENCY = "AchievedLatency";
    protected static final String JSON_FIELD_ROUTE_NODES = "Connections";
    protected static final String JSON_FIELD_NET_CHANNEL = "CommType";
    protected static final String JSON_FIELD_VIRTUAL_CHANNEL = "VC";
    protected static final String JSON_FIELD_PHASE = "Phase";
    protected static final String JSON_FIELD_PORT = "Port";
    protected static final String JSON_FIELD_COMMUNICATION_TYPE = "CommType";
    protected static final String JSON_FIELD_READ_BANDWIDTH = "ReadBW";
    protected static final String JSON_FIELD_WRITE_BANDWIDTH = "WriteBW";
    protected static final String JSON_FIELD_READ_LATENCY = "ReadLatency";
    protected static final String JSON_FIELD_READ_BEST_LATENCY = "ReadBestPossibleLatency";
    protected static final String JSON_FIELD_WRITE_LATENCY = "WriteLatency";
    protected static final String JSON_FIELD_WRITE_BEST_LATENCY = "WriteBestPossibleLatency";
    protected static final String JSON_FIELD_READ_AVERAGE_BURST = "ReadAvgBurst";
    protected static final String JSON_FIELD_WRITE_AVERAGE_BURST = "WriteAvgBurst";
    protected static final String JSON_FIELD_PATH_SRC_LOCKED = "FromLocked";
    protected static final String JSON_FIELD_PATH_DST_LOCKED = "ToLocked";
    protected static final String JSON_FIELD_PATH_LOCKED = "PathLocked";
    protected static final String JSON_FIELD_READ_BW_ACHIEVED = "ReadAchievedBW";
    protected static final String JSON_FIELD_WRITE_BW_ACHIEVED = "WriteAchievedBW";
    protected static final String JSON_FIELD_PATH_NETS = "Nets";

    private static Field mapField = null;

    /**
     * Creates a JSON object that preserves insertion order when serialized.
     * @return A JSON object backed by an ordered map.
     * @since 2026.1.0
     */
    public static JSONObject createOrderedJSONObject() {
        JSONObject result = new JSONObject();
        try {
            if (mapField == null) {
                mapField = JSONObject.class.getDeclaredField("map");
                mapField.setAccessible(true);
            }
            mapField.set(result, new LinkedHashMap<>());
        } catch (Exception e) {}
        return result;
    }

    /**
     * Writes a formatted JSON string to the provided output stream.
     * @param obj The JSON object to write.
     * @param out The output stream to write to.
     * @since 2026.1.0
     */
    public static void writeFormattedJSONString(JSONObject obj, OutputStream out) {
        char[] jsonChars = obj.toString().toCharArray();
        StringBuilder sb = new StringBuilder();
        boolean isInQuotes = false;
        String indent = "";
        for (int i=0; i<jsonChars.length; i++) {
            switch(jsonChars[i]) {
            case '}':
            case ']':
                if (isInQuotes) break;
                sb.append("\n");
                indent = indent.substring(2);
                sb.append(indent);
            default:
                break;
            }
            sb.append(jsonChars[i]);
            switch(jsonChars[i]) {
            case ':':
                sb.append(' ');
                break;
            case '"':
                isInQuotes = !isInQuotes;
                break;
            case '{':
            case '[':
                if (isInQuotes) break;
                if (jsonChars[i+1] == ']') {
                    sb.append(jsonChars[++i]);
                    continue;
                }
                indent += "  ";
                sb.append('\n');
                sb.append(indent);
                break;
            case ',':
                if (isInQuotes) break;
                sb.append('\n');
                sb.append(indent);
            default:
                break;
            }
        }
        String s = sb.toString();
        try {
            out.write(s.getBytes());
        } catch(IOException e) {
            System.out.println("Exception while writing JSON.");
            e.printStackTrace();
        }
    }

}
