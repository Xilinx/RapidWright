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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.util.FileTools;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

/**
 * Represents the Network-on-Chip solution for the current design
 * @since 2022.1.0
 */
public class NOCDesign implements Serializable {

    private static final long serialVersionUID = -1303425805455056695L;

    private Design design;

    private int nocFrequency;

    private Map<String,NOCMaster> masterClients;
    private Map<String,NOCSlave> slaveClients;
    private Map<String,Integer> switchDestIDs;
    private List<NOCConnection> nocConnections;
    private List<String> dfxPaths; //List of RP cell names

    private boolean lockAllDestIDs;
    private SolutionType solutionType;

    private boolean isRouted; //Has any routing, even partial. Triggers NCR.

    private static final Set<SiteTypeEnum> switchSiteTypes = new HashSet<SiteTypeEnum>();

    static {
        switchSiteTypes.add(SiteTypeEnum.NOC_NCRB);
        switchSiteTypes.add(SiteTypeEnum.NOC_NPS5555);
        switchSiteTypes.add(SiteTypeEnum.NOC_NPS_VNOC);
    }

    /**
     * Creates an empty NOC design.
     * @since 2026.1.0
     */
    public NOCDesign() {
        masterClients = new HashMap<String,NOCMaster>();
        slaveClients = new HashMap<String,NOCSlave>();
        nocConnections = new ArrayList<NOCConnection>();
        dfxPaths = new ArrayList<String>();
        switchDestIDs = new HashMap<String,Integer>();
        isRouted = false;
        lockAllDestIDs = false;
        solutionType = SolutionType.NO_SOLUTION;
        nocFrequency = 1000;
    }

    /**
     * Creates a NOC design associated with a RapidWright design.
     * @param d The RapidWright design to associate with this NOC design.
     * @since 2026.1.0
     */
    public NOCDesign(Design d) {
        this();
        setDesign(d);
    }

    /**
     * Sets the main design corresponding to this NOCDesign
     * @param design The design to which this NOC solution belongs
     * @since 2022.1.0
     */
    public void setDesign(Design design) {
        this.design = design;
    }

    /**
     * Gets the main design corresponding to this NOCDesign
     * @return The design to which this NOC solution belongs
     * @since 2022.1.0
     */
    public Design getDesign() {
        return design;
    }

    /**
     * Gets the map of master clients in the NOC design. The map is keyed by client name.
     * @return The map (keyed by client name) of master clients in this NOC design.
     * @since 2022.1.0
     */
    public Map<String,NOCMaster> getMasterClients() {
        return masterClients;
    }

    /**
     * Gets the map of slave clients in the NOC design. The map is keyed by client name.
     * @return The map (keyed by client name) of slave clients in this NOC design.
     * @since 2022.1.0
     */
    public Map<String,NOCSlave> getSlaveClients() {
        return slaveClients;
    }

    /**
     * Sets the target NOC frequency.
     * @param nocFrequency The target NOC frequency to set.
     * @since 2026.1.0
     */
    public void setFrequency(int nocFrequency) {
        this.nocFrequency = nocFrequency;
    }

    /**
     * Gets the target frequency of this NOC design.
     * @return The target frequency of this NOC design.
     * @since 2022.1.0
     */
    public int getFrequency() {
        return nocFrequency;
    }

    /**
     * Checks whether all destination IDs are locked.
     * @return True if all destination IDs are locked, false otherwise.
     * @since 2026.1.0
     */
    public boolean areDestIDsLocked() {
        return lockAllDestIDs;
    }

    /**
     * Sets whether all destination IDs are locked.
     * @param lockAllDestIDs True to lock all destination IDs, false otherwise.
     * @since 2026.1.0
     */
    public void setAllDestIDsLocked(boolean lockAllDestIDs) {
        this.lockAllDestIDs = lockAllDestIDs;
    }

    /**
     * Gets the current NOC solution type.
     * @return The current NOC solution type.
     * @since 2026.1.0
     */
    public SolutionType getSolutionType() {
        return solutionType;
    }

    /**
     * Sets the current NOC solution type.
     * @param solutionType The solution type to set.
     * @since 2026.1.0
     */
    public void setSolutionType(SolutionType solutionType) {
        this.solutionType = solutionType;
    }

    /**
     * Clears the NOC solution if it exists.
     * @since 2025.2.2
     */
    public void clearSolution() {
        this.solutionType = SolutionType.NO_SOLUTION;
    }

    /**
     * Checks if the NOC design is routed.
     * @return True if the NOC design is routed, false otherwise.
     * @since 2022.1.0
     */
    public boolean isRouted() {
        return isRouted;
    }

    /**
     * Sets whether this NOC design has routed solution data.
     * @param isRouted True if the NOC design is routed, false otherwise.
     * @since 2026.1.0
     */
    public void setIsRouted(boolean isRouted) {
        this.isRouted = isRouted;
    }

    /**
     * Gets DFX cell paths in this NOC design.
     * @return The DFX cell paths in this NOC design.
     * @since 2026.1.0
     */
    public List<String> getDFXCells() {
        return dfxPaths;
    }

    /**
     * Copy a client from another NOCDesign into this NOCDesign under a new name.
     * @param orig The original NOC client to copy.
     * @param name The name for the copied client.
     * @return The copied NOC client.
     * @since 2026.1.0
     */
    public NOCClient copyClient(NOCClient orig, String name) {
        NOCClient newClient = null;
        if (orig instanceof NOCMaster) {
            newClient = new NOCMaster((NOCMaster)orig);
        } else {
            newClient = new NOCSlave((NOCSlave)orig);
        }
        newClient.setName(name);
        addClient(newClient);
        return newClient;
    }

    /**
     * Adds a NOC client to this design.
     * @param nc The NOC client to add.
     * @since 2026.1.0
     */
    public void addClient(NOCClient nc) {
        if (nc instanceof NOCMaster) {
            masterClients.put(nc.getName(), (NOCMaster) nc);
        } else {
            slaveClients.put(nc.getName(), (NOCSlave) nc);
        }
    }

    /**
     * Removes a NOC client from this design by name.
     * @param clientName The name of the client to remove.
     * @since 2026.1.0
     */
    public void removeClient(String clientName) {
        NOCClient nc = getClients().get(clientName);
        if (nc instanceof NOCMaster) {
            masterClients.remove(nc.getName());
        } else {
            slaveClients.remove(nc.getName());
        }
        List<NOCConnection> connections = nc.getConnections();
        for (NOCConnection np : connections) {
            nocConnections.remove(np);
            np.getSource().removeConnection(np);
            np.getDest().removeConnection(np);
        }
    }

    /**
     * Adds a NOC connection to this design.
     * @param np The NOC connection to add.
     * @since 2026.1.0
     */
    public void addConnection(NOCConnection np) {
        NOCMaster source = np.getSource();
        NOCSlave dest = np.getDest();
        if (masterClients.get(source.getName()) != null) {
            masterClients.put(source.getName(),source);
        }
        if (slaveClients.get(dest.getName()) != null) {
            slaveClients.put(dest.getName(),dest);
        }
        source.addConnection(np);
        dest.addConnection(np);
        nocConnections.add(np);
    }

    /**
     * Removes a NOC connection from this design.
     * @param np The NOC connection to remove.
     * @since 2026.1.0
     */
    public void removeConnection(NOCConnection np) {
        nocConnections.remove(np);
        np.getSource().removeConnection(np);
        np.getDest().removeConnection(np);
    }

    /**
     * Gets all the connections that are part of this NOC design.
     * @return The list of all connections part of this NOC design.
     * @since 2022.1.0
     */
    public List<NOCConnection> getAllConnections() {
        return nocConnections;
    }

    /**
     * Gets all NOC clients part of this NOC design.  The map is keyed by client name.
     * @return A new map of all NOC clients part of this design.  The map is keyed by client name.
     * @since 2022.1.0
     */
    public Map<String,NOCClient> getClients() {
        HashMap<String,NOCClient> allClients = new HashMap<String,NOCClient>();
        allClients.putAll(masterClients);
        allClients.putAll(slaveClients);
        return allClients;
    }

    /**
     * Loads NOC traffic data from a JSON input stream.
     * @param in The input stream containing NOC traffic JSON data.
     * @since 2026.1.0
     */
    public void loadTraffic(InputStream in) {

        //Read JSON File
        JSONObject jsonTraffic = new JSONObject(String.join("\n", FileTools.getLinesFromInputStream(in)));

        //System Properties
        JSONObject sysProps = jsonTraffic.getJSONObject(NOCJSONUtil.JSON_FIELD_SYSTEM_PROPERTIES);
        if (sysProps.has(NOCJSONUtil.JSON_FIELD_FREQUENCY)) {
            nocFrequency = sysProps.getInt(NOCJSONUtil.JSON_FIELD_FREQUENCY);
        }
        if (sysProps.has(NOCJSONUtil.JSON_FIELD_DFX_PATHS)) {
            //System.out.println("Unsupported NoC traffic encountered: " + NOCJSONUtil.JSON_FIELD_DFX_PATHS);
            JSONArray dfxPathsArray = sysProps.getJSONArray(NOCJSONUtil.JSON_FIELD_DFX_PATHS);
            for (int i=0; i<dfxPathsArray.length(); i++) {
                dfxPaths.add(dfxPathsArray.getString(i));
            }
        }

        //Logical Instances
        JSONArray instances = jsonTraffic.getJSONArray(NOCJSONUtil.JSON_FIELD_CLIENT_INSTANCES);
        for (int i=0; i<instances.length(); i++) {
            JSONObject inst = instances.getJSONObject(i);
            boolean isMaster = inst.getBoolean(NOCJSONUtil.JSON_FIELD_IS_MASTER);
            if (isMaster) {
                masterClients.put(inst.getString(NOCJSONUtil.JSON_FIELD_NAME),new NOCMaster(inst));
            } else {
                slaveClients.put(inst.getString(NOCJSONUtil.JSON_FIELD_NAME),new NOCSlave(inst));
            }
        }

        //Paths
        nocConnections = new ArrayList<NOCConnection>();
        if (jsonTraffic.has(NOCJSONUtil.JSON_FIELD_PATHS)) {
            JSONArray paths = jsonTraffic.getJSONArray(NOCJSONUtil.JSON_FIELD_PATHS);
            for (int i=0; i<paths.length(); i++) {
                JSONObject path = paths.getJSONObject(i);
                NOCConnection np = new NOCConnection(path,this);
                nocConnections.add(np);
                np.getSource().addConnection(np);
                np.getDest().addConnection(np);
            }
        }
    }

    /**
     * Loads NOC solution data from a JSON input stream.
     * @param in The input stream containing NOC solution JSON data.
     * @since 2026.1.0
     */
    public void loadSolution(InputStream in) {
        //Read JSON File
        JSONObject jsonSolution = new JSONObject(String.join("\n", FileTools.getLinesFromInputStream(in)));

        //Solution Properties
        if (!jsonSolution.has(NOCJSONUtil.JSON_FIELD_SOLUTION_TYPE)) {
            return;
        }
        solutionType = SolutionType.stringToValue(jsonSolution.getString(NOCJSONUtil.JSON_FIELD_SOLUTION_TYPE));
        if (jsonSolution.has(NOCJSONUtil.JSON_FIELD_LOCK_DEST_IDS))
            lockAllDestIDs = jsonSolution.getBoolean(NOCJSONUtil.JSON_FIELD_LOCK_DEST_IDS);

        //Components
        JSONArray componentArray = jsonSolution.getJSONArray(NOCJSONUtil.JSON_FIELD_COMPONENTS);
        for (int i=0; i<componentArray.length(); i++) {
            JSONObject component = componentArray.getJSONObject(i);
            if (!component.has(NOCJSONUtil.JSON_FIELD_DEST_ID) || !component.has(NOCJSONUtil.JSON_FIELD_NAME)) {
                continue;
            }
            int destID = component.getInt(NOCJSONUtil.JSON_FIELD_DEST_ID);
            if (!component.has(NOCJSONUtil.JSON_FIELD_LOGICAL_INST)) {
                switchDestIDs.put(component.getString(NOCJSONUtil.JSON_FIELD_NAME), destID);
                continue;
            }
            String cellName = component.getString(NOCJSONUtil.JSON_FIELD_LOGICAL_INST);
            if (cellName == null)
                continue;
            NOCClient nc = slaveClients.get(cellName);
            if (nc == null) nc = masterClients.get(cellName);
            if (nc == null) {System.out.println("Component in NCR " + cellName + " not found."); continue;}
            nc.setLocation(component.getString(NOCJSONUtil.JSON_FIELD_NAME));
            nc.setDestID(destID);
            if (nc.isDDRC()) {
                String portName = NOCJSONUtil.JSON_FIELD_PORT_PREFIX + component.getInt(NOCJSONUtil.JSON_FIELD_PORT_INDEX);
                NOCSlave ns = (NOCSlave) nc;
                if (ns.getPorts().contains(portName))
                    ns.setPortDestID(portName,destID);
                else
                    continue;
            }
        }

        //Paths
        if (jsonSolution.has(NOCJSONUtil.JSON_FIELD_PATHS)) {
            JSONArray pathArray = jsonSolution.getJSONArray(NOCJSONUtil.JSON_FIELD_PATHS);
            for (int i=0; i<pathArray.length(); i++) {
                JSONObject pathSolution = pathArray.getJSONObject(i);
                NOCMaster master = masterClients.get(pathSolution.getString(NOCJSONUtil.JSON_FIELD_FROM_CELL));
                NOCConnection path = master.getConnectionTo(pathSolution.getString(NOCJSONUtil.JSON_FIELD_TO_CELL));
                path.parseRoute(pathSolution);
            }
            isRouted = true;
        }
    }

    /**
     * Writes NOC traffic data to a JSON output stream.
     * @param out The output stream to write NOC traffic JSON data to.
     * @since 2026.1.0
     */
    public void writeTraffic(OutputStream out) {
        JSONObject nocTraffic = NOCJSONUtil.createOrderedJSONObject();
        JSONObject sysProps = NOCJSONUtil.createOrderedJSONObject();
        sysProps.put(NOCJSONUtil.JSON_FIELD_FREQUENCY,nocFrequency);
        if (!dfxPaths.isEmpty() ) {
            JSONArray dfxPathsArray = new JSONArray();
            for (String rpCellName : dfxPaths)
                dfxPathsArray.put(rpCellName);
            sysProps.put(NOCJSONUtil.JSON_FIELD_DFX_PATHS,dfxPathsArray);
        }
        nocTraffic.put(NOCJSONUtil.JSON_FIELD_SYSTEM_PROPERTIES, sysProps);
        for (NOCClient nc : getClients().values()) {
            nocTraffic.append(NOCJSONUtil.JSON_FIELD_CLIENT_INSTANCES, nc.toJSONObject());
        }
        for (NOCConnection connection : nocConnections) {
            nocTraffic.append(NOCJSONUtil.JSON_FIELD_PATHS,connection.getTrafficJSONObject());
        }
        NOCJSONUtil.writeFormattedJSONString(nocTraffic, out);
    }

    /**
     * Writes NOC solution data to a JSON output stream.
     * @param out The output stream to write NOC solution JSON data to.
     * @since 2026.1.0
     */
    public void writeSolution(OutputStream out) {
        JSONObject nocSolution = NOCJSONUtil.createOrderedJSONObject();
        nocSolution.put(NOCJSONUtil.JSON_FIELD_SOLUTION_TYPE, solutionType);
        nocSolution.put(NOCJSONUtil.JSON_FIELD_LOCK_DEST_IDS, lockAllDestIDs);
        HashSet<String> phyComponents = new HashSet<String>();
        if (solutionType == SolutionType.NO_SOLUTION) {
            nocSolution.put(NOCJSONUtil.JSON_FIELD_PATHS, new JSONArray());
            nocSolution.put(NOCJSONUtil.JSON_FIELD_COMPONENTS, new JSONArray());
        } else {
            for (NOCConnection np : nocConnections) {
                nocSolution.append(NOCJSONUtil.JSON_FIELD_PATHS, np.getSolutionJSONObject());
                phyComponents.addAll(np.getAllComponents());
            }
            for (NOCClient clt : getClients().values()) {
                String loc = clt.getLocation();
                phyComponents.remove(loc);
                int numPorts = 1;
                if (clt.isDDRC()) {
                    numPorts = 4;
                }
                for (int i = 0; i < numPorts; i++) {
                    JSONObject component = NOCJSONUtil.createOrderedJSONObject();
                    component.put(NOCJSONUtil.JSON_FIELD_NAME, loc);
                    component.put(NOCJSONUtil.JSON_FIELD_LOGICAL_INST, clt.getName());
                    int destID = clt.getDestID();
                    if (clt.isDDRC()) {
                        String portName = NOCJSONUtil.JSON_FIELD_PORT_PREFIX + i;
                        NOCSlave ns = (NOCSlave) clt;
                        if (ns.getPorts().contains(portName))
                            destID = ns.getPortDestID(portName);
                        else
                            destID = 0;
                        component.put(NOCJSONUtil.JSON_FIELD_PORT_INDEX, i);
                    }
                    component.put(NOCJSONUtil.JSON_FIELD_DEST_ID, destID);
                    nocSolution.append(NOCJSONUtil.JSON_FIELD_COMPONENTS, component);
                }
            }
            Device dev = design.getDevice();
            for (Entry<String, Integer> e : switchDestIDs.entrySet()) {
                JSONObject component = NOCJSONUtil.createOrderedJSONObject();
                component.put(NOCJSONUtil.JSON_FIELD_NAME, e.getKey());
                component.put(NOCJSONUtil.JSON_FIELD_DEST_ID, e.getValue().intValue());
                nocSolution.append(NOCJSONUtil.JSON_FIELD_COMPONENTS, component);
            }
            //Write out all routing components, not just those used in the solution.
            for (SiteTypeEnum type : switchSiteTypes) {
                for (Site site : Arrays.asList(dev.getAllSitesOfType(type))) {
                    if (!switchDestIDs.containsKey(site.getName())) {
                        //phyComponents.add(site.getName());
                        JSONObject component = NOCJSONUtil.createOrderedJSONObject();
                        component.put(NOCJSONUtil.JSON_FIELD_NAME, site.getName());
                        component.put(NOCJSONUtil.JSON_FIELD_DEST_ID, 0);
                        nocSolution.append(NOCJSONUtil.JSON_FIELD_COMPONENTS, component);
                    }
                }
            }

            /*for (String compName : phyComponents) {
                JSONObject component = NOCJSONUtil.createOrderedJSONObject();
                component.put(NOCJSONUtil.JSON_FIELD_NAME,compName);
                component.put(NOCJSONUtil.JSON_FIELD_DEST_ID,0);
                nocSolution.append(NOCJSONUtil.JSON_FIELD_COMPONENTS,component);
            }*/
        }
        NOCJSONUtil.writeFormattedJSONString(nocSolution, out);
    }
}
