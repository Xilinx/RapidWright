/*
 * Copyright (c) 2019 Xilinx, Inc.
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

package com.xilinx.rapidwright.timing;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;

import static com.xilinx.rapidwright.timing.TimingDirection.NORTH;
import static com.xilinx.rapidwright.timing.TimingDirection.SOUTH;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


/**
 * A TimingGroup is our main hardware abstraction proposed by our FPT'19 paper: a TimingGroup 
 * abstracts over a set of connected PIPs, Nodes, and pins in order to create a coarser grain unit 
 * for which we calculate the delay.
 */
public class TimingGroup implements Comparable<TimingGroup> {

    private TimingModel timingModel;
    private List<Node> nodes;
    private List<PIP> pips;
    private List<IntentCode> nodeTypes;
    private GroupDelayType groupDelayType;
    private GroupWireDirection groupWireDir;
    private TimingDirection direction;
    private boolean isInitialGroup = false;
    private boolean isFinalGroup = false;
    private boolean hasGlobalWire = false;
    private boolean hasPinFeed = false;

    /**
     * Term "D" that is used within the delay calculation
     */
    public int d;

    /**
     * This is the distance of the TimingGroup corresponding to its wire length type.
     */
    public int dist;
    public float delay;
    public float cost;
    public int sameSpotCounter;

    /**
     * Default constructor used by the TimingModel to create a TimingGroup.
     * @param timingModel Reference to the TimingModel.
     */
    public TimingGroup(TimingModel timingModel) {
        this.sameSpotCounter = 0;
        this.timingModel = timingModel;
        this.nodes = new LinkedList<>();
        this.pips = new LinkedList<>();
        this.nodeTypes = new LinkedList<>();
        this.isInitialGroup = false;
        this.isFinalGroup = false;
        this.hasGlobalWire = false;
    }

    /**
     * Constructor used for Router example to create a TimingGroup, starting at a given SitePinInst.
     * @param startPin Starting SitePinInst for the TimingGroup.
     * @param timingModel Reference to the current TimingModel.
     */
    public TimingGroup(SitePinInst startPin, TimingModel timingModel) {
        this.sameSpotCounter = 0;
        this.timingModel = timingModel;
        this.nodes = new LinkedList<>();
        this.pips = new LinkedList<>();
        this.nodeTypes = new LinkedList<>();
        this.isInitialGroup = true;
        this.isFinalGroup = false;
        this.hasGlobalWire = false;
        Node node = startPin.getConnectedNode();
        if (node != null) {
            Wire[] wires = node.getAllWiresInNode();
            IntentCode ic = wires[0].getIntentCode();
            add(startPin.getConnectedNode(),ic);
        }
        computeTypes();
        timingModel.calcDelay(this);
    }

    /**
     * Method used by the Router example to get the downhill TimingGroups from a given TimingGroup.  
     * For example a user may create a TimingGroup at a given SitePinInst using that constructor, 
     * and then request the possible downhill TimingGroups using this method.  The resulting array 
     * of TimingGroups can easily be filtered using the "filter" method within TimingModel.
     * @return Array of downhill/adjacent TimingGroups from the current TimingGroup
     */
    public TimingGroup[] getNextTimingGroups() {
        List<TimingGroup> preResult = new ArrayList<>();
        Node prevLastNode = nodes.get(nodes.size()-1);
        List<Node> downhillNodes = prevLastNode.getAllDownhillNodes();
        for (Node nextNode : downhillNodes) {
            Wire[] wires = nextNode.getAllWiresInNode();
            IntentCode ic = wires[0].getIntentCode();
            PIP pip = null;

            for (PIP p : prevLastNode.getAllDownhillPIPs()) {
                Node startNode = p.getStartNode();
                Node endNode = p.getEndNode();
                if (startNode.equals(prevLastNode) &&
                        endNode.equals(nextNode)) {
                    pip = p;
                    break;
                }
            }
            if (pip != null && !pip.getStartNode().equals(prevLastNode))
                continue;

            boolean nextNodeHasGlobalWire = false;
            for (Wire w : nextNode.getAllWiresInNode()) {
                if (w.getWireName().contains("_GLOBAL"))
                    nextNodeHasGlobalWire = true;
            }
            if (ic == IntentCode.NODE_CLE_OUTPUT) {
                TimingGroup newTS = new TimingGroup(timingModel);
                newTS.add(nextNode, ic);
                newTS.computeTypes();
                timingModel.calcDelay(newTS);
                preResult.add(newTS);
            }
            else if (nextNodeHasGlobalWire ||
                    ic == IntentCode.NODE_HLONG ||
                    ic == IntentCode.NODE_VLONG
            ) {
                TimingGroup newTS = new TimingGroup(timingModel);
                newTS.add(nextNode, ic);
                if (pip != null)
                    newTS.add(pip);
                newTS.computeTypes();
                timingModel.calcDelay(newTS);
                preResult.add(newTS);

            } else {
                PIP nextNextPip = null;

                for (Node nextNextNode : nextNode.getAllDownhillNodes()) {
                    for (PIP p : nextNode.getAllDownhillPIPs()) {
                        if (p.getStartNode().equals(nextNode) &&
                                p.getEndNode().equals(nextNextNode)) {
                            nextNextPip = p;
                            break;
                        }
                    }
                    nextNextNode = nextNextPip.getEndNode();

                    Wire[] nextNextWires = nextNextNode.getAllWiresInNode();
                    IntentCode nextNextIc = nextNextWires[0].getIntentCode();

                    TimingGroup newTS = new TimingGroup(timingModel);
                    newTS.add(nextNode, ic);
                    newTS.add(nextNextNode, nextNextIc);
                    if (pip != null)
                        newTS.add(pip);
                    if (nextNextPip != null)
                        newTS.add(nextNextPip);
                    newTS.computeTypes();
                    timingModel.calcDelay(newTS);
                    preResult.add(newTS);
                }
                if (nextNextPip == null) {
                    continue;
                }
            }
        }
        TimingGroup[] result = preResult.toArray(new TimingGroup[preResult.size()]);
        return result;
    }


    /**
     * Used for adding a node into a TimingGroup.
     * @param n Node to be added.
     * @param c IntentCode is the Vivado-assigned Type for the given node.
     */
    protected void add(Node n, IntentCode c) {
        nodes.add(n);
        if (c == IntentCode.NODE_PINFEED)
            hasPinFeed = true;
        nodeTypes.add(c);
        for (Wire w : n.getAllWiresInNode()) {
            if (w.getWireName().contains("_GLOBAL"))
                hasGlobalWire = true;
        }
    }

    /**
     * Used for adding a PIP into a TimingGroup.
     * @param p PIP to be added.
     */
    protected void add(PIP p) {
        pips.add(p);
    }

    /**
     * Returns a String representation of this object that may be useful for debugging.
     * @return String representation.
     */
    public String toString() {
        boolean moreNodesThanPips = nodes.size() > pips.size();
        String result = "<";
        if (moreNodesThanPips) {
            for(int i=0; i<nodes.size(); i++) {
                result += "n";
                if (i < pips.size())
                    result += "p";
            }
            result += ">";
            result += ":" +nodeTypes.get(nodeTypes.size()-1);
        } else if (pips.size() == 1 && nodes.size() == 1) {
            result += "pn>:";
            result += nodeTypes.get(0);
        } else if (pips.size() == 2 && nodes.size() == 2) {
            result += "pnpn>:";
            result += nodeTypes.get(1);
        }
        return result;
    }

    /**
     * Computes the D (distance) term used by the TimingModel calculation.
     * @param n Given Node to use when checking the wire names.
     * @return The D term used by the TimingModel delay calculation.
     */
    int computeD(Node n) {
        int result = 0;
        int minRow = 1<<20;
        int maxRow = 0;
        int minCol = 1<<20;
        int maxCol = 0;
        List<Wire> wList = new LinkedList<>();
        for (Wire w : n.getAllWiresInNode()) {
            if (w.getWireName().contains("BEG")) {
                wList.add(0,w);
            }
            if (w.getWireName().contains("END")) {
                wList.add(w);
            }
        }
        for (Wire w1 : wList) {
            if (w1.getTile().getColumn() < minCol)
                minCol = w1.getTile().getColumn();
            if (w1.getTile().getColumn() > maxCol)
                maxCol = w1.getTile().getColumn();
            if (w1.getTile().getRow() < minRow)
                minRow = w1.getTile().getRow();
            if (w1.getTile().getRow() > maxRow)
                maxRow = w1.getTile().getRow();
        }
        int col1 = minCol;
        int row1 = minRow;
        int col2 = maxCol;
        int row2 = maxRow;
        if (groupWireDir == GroupWireDirection.HORIZONTAL && col1 < col2) {
            result += timingModel.computeHorizontalDistFromArray(col1, col2, groupDelayType);
        }
        if (groupWireDir == GroupWireDirection.VERTICAL && row1 < row2) {
            result += timingModel.computeVerticalDistFromArray(row1, row2, groupDelayType);
        }
        this.d = result;
        return result;
    }

    /**
     * Computes the TimingGroup GroupDelayType for this group.
     */
    public void computeTypes() {
        if (nodes.size() == 0) {
            return;
        }
        IntentCode nodeToCheckIntent = null;
        int nodeToCheckInx = -1;
        if (nodes.size()>1 &&
                (nodeTypes.get(1)==IntentCode.NODE_PINBOUNCE ||
                        nodeTypes.get(0)==IntentCode.NODE_LOCAL)) {
            nodeToCheckInx = 1;
        } else {
            nodeToCheckInx = 0;
        }

        nodeToCheckIntent = nodeTypes.get(nodeToCheckInx);

        Wire[] wires;
        Tile t1, t2;
        String wName = "";

        switch(nodeToCheckIntent) {

            case NODE_PINBOUNCE:
                dist = 0;
                groupDelayType = GroupDelayType.PIN_BOUNCE;
                break;
            case NODE_SINGLE:
                dist = 1;
                wires = nodes.get(nodeToCheckInx).getAllWiresInNode();
                t1 = wires[0].getTile();
                t2 = wires[wires.length-1].getTile();
                wName = wires[0].getWireName();
                if (wName.startsWith("SS"))
                    direction = TimingDirection.SOUTH;
                else if (wName.startsWith("NN"))
                    direction = NORTH;
                else if (wName.startsWith("EE"))
                    direction = TimingDirection.EAST;
                else if (wName.startsWith("WW"))
                    direction = TimingDirection.WEST;
                if (t1 == t2) {
                    groupDelayType = GroupDelayType.INTERNAL;
                }
                else {
                    groupDelayType = GroupDelayType.SINGLE;
                    if (direction == NORTH ||
                            direction == TimingDirection.SOUTH) {
                        groupWireDir = GroupWireDirection.VERTICAL;
                        computeD(nodes.get(nodeToCheckInx));
                    }
                    else if (direction == TimingDirection.EAST ||
                            direction == TimingDirection.WEST) {
                        groupWireDir = GroupWireDirection.HORIZONTAL;
                        computeD(nodes.get(nodeToCheckInx));
                    }
                }
                break;

            case NODE_DOUBLE:
                dist = 2;
                groupDelayType = GroupDelayType.DOUBLE;
                wires = nodes.get(nodeToCheckInx).getAllWiresInNode();
                t1 = wires[0].getTile();
                t2 = wires[wires.length-1].getTile();
                wName = wires[0].getWireName();
                if (wName.startsWith("SS"))
                    direction = TimingDirection.SOUTH;
                else if (wName.startsWith("NN"))
                    direction = NORTH;
                else if (wName.startsWith("EE"))
                    direction = TimingDirection.EAST;
                else if (wName.startsWith("WW"))
                    direction = TimingDirection.WEST;
                if (direction == NORTH ||
                        direction == TimingDirection.SOUTH) {
                    groupWireDir = GroupWireDirection.VERTICAL;
                    computeD(nodes.get(nodeToCheckInx));
                }
                else if (direction == TimingDirection.EAST ||
                        direction == TimingDirection.WEST) {
                    groupWireDir = GroupWireDirection.HORIZONTAL;
                    computeD(nodes.get(nodeToCheckInx));
                }
                break;

            case NODE_HQUAD:
                dist = 4;
                groupDelayType = GroupDelayType.QUAD;
                wires = nodes.get(nodeToCheckInx).getAllWiresInNode();
                t1 = wires[0].getTile();
                t2 = wires[wires.length-1].getTile();
                wName = wires[0].getWireName();
                if (wName.startsWith("SS"))
                    direction = TimingDirection.SOUTH;
                else if (wName.startsWith("NN"))
                    direction = NORTH;
                else if (wName.startsWith("EE"))
                    direction = TimingDirection.EAST;
                else if (wName.startsWith("WW"))
                    direction = TimingDirection.WEST;
                if (direction == TimingDirection.EAST ||
                        direction == TimingDirection.WEST) {
                    groupWireDir = GroupWireDirection.HORIZONTAL;
                    computeD(nodes.get(nodeToCheckInx));
                }
                break;

            case NODE_VQUAD:
                dist = 4;
                groupDelayType = GroupDelayType.QUAD;
                wires = nodes.get(nodeToCheckInx).getAllWiresInNode();
                t1 = wires[0].getTile();
                t2 = wires[wires.length-1].getTile();
                wName = wires[0].getWireName();
                if (wName.startsWith("SS"))
                    direction = TimingDirection.SOUTH;
                else if (wName.startsWith("NN"))
                    direction = NORTH;
                else if (wName.startsWith("EE"))
                    direction = TimingDirection.EAST;
                else if (wName.startsWith("WW"))
                    direction = TimingDirection.WEST;
                if (direction == NORTH ||
                        direction == TimingDirection.SOUTH) {
                    groupWireDir = GroupWireDirection.VERTICAL;
                    computeD(nodes.get(nodeToCheckInx));
                }
                break;

            case NODE_HLONG:
                dist = 12;
                groupDelayType = GroupDelayType.LONG;
                wires = nodes.get(nodeToCheckInx).getAllWiresInNode();
                t1 = wires[0].getTile();
                t2 = wires[wires.length-1].getTile();
                wName = wires[0].getWireName();
                if (wName.startsWith("SS"))
                    direction = TimingDirection.SOUTH;
                else if (wName.startsWith("NN"))
                    direction = NORTH;
                else if (wName.startsWith("EE"))
                    direction = TimingDirection.EAST;
                else if (wName.startsWith("WW"))
                    direction = TimingDirection.WEST;
                if (direction == TimingDirection.EAST ||
                        direction == TimingDirection.WEST) {
                    groupWireDir = GroupWireDirection.HORIZONTAL;
                    computeD(nodes.get(nodeToCheckInx));
                }
                break;

            case NODE_VLONG:
                dist = 12;
                groupDelayType = GroupDelayType.LONG;
                wires = nodes.get(nodeToCheckInx).getAllWiresInNode();
                t1 = wires[0].getTile();
                t2 = wires[wires.length-1].getTile();
                wName = wires[0].getWireName();
                if (wName.startsWith("SS"))
                    direction = TimingDirection.SOUTH;
                else if (wName.startsWith("NN"))
                    direction = NORTH;
                else if (wName.startsWith("EE"))
                    direction = TimingDirection.EAST;
                else if (wName.startsWith("WW"))
                    direction = TimingDirection.WEST;
                if (direction == NORTH ||
                        direction == SOUTH) {
                    groupWireDir = GroupWireDirection.VERTICAL;
                    computeD(nodes.get(nodeToCheckInx));

                }
                break;
            case NODE_LOCAL:
                dist = 0;
                if (hasGlobalWire)
                    groupDelayType = GroupDelayType.GLOBAL;
                else
                    groupDelayType = GroupDelayType.OTHER;
                break;

            case NODE_PINFEED:
                dist = 0;
                groupDelayType = GroupDelayType.PINFEED;
                break;

            default:
                dist = 0;
                groupDelayType = GroupDelayType.OTHER;
        }
    }

    /**
     * This object implements the comparable object interface so that TimingGroup objects may be 
     * compared.  For example, this is used in the example Router to compare TimingGroups based on 
     * delay cost in picoseconds.
     * @param tg Second TimingGroup to compare this object to.
     * @return Returns -1 if this object has lower cost, 0 if the costs are the same, and 1 if this 
     * object has higher cost.
     */
    public int compareTo(TimingGroup tg) {
        int result = 0;
        if (cost < tg.cost) result = -1;
        if (cost > tg.cost) result = 1;
        return result;
    }

    /**
     * Returns whether this TimingGroup contains any PIPs.
     * @return Boolean indication of whether this object contains any PIPs.
     */
    public boolean hasPIPs() {
        return pips.size() > 0;
    }

    /**
     * Gets the list of nodes in the timing group.
     * @return The list of nodes in the timing group
     */
    public List<Node> getNodes() {
    	return nodes;
    }
    
    /**
     * Gets the node in the timing group at the specified index
     * @param i Index of the node to get.
     * @return Node at the index i of this timing group
     */
    public Node getNode(int i) {
    	return nodes.get(i);
    }
    
    /**
     * Gets the last node in the timing group
     * @return The last node in the timing group
     */
    public Node getLastNode() {
    	return nodes.get(nodes.size() - 1);
    }
    
    public List<PIP> getPIPs() {
    	return pips;
    }
    
    public PIP getPIP(int i) {
    	return pips.get(i);
    }
    
    public PIP getLastPIP() {
    	return pips.get(pips.size()-1);
    }
    
    public List<IntentCode> getNodeTypes() {
    	return nodeTypes; 
    }
    
    public IntentCode getNodeType(int i) {
    	return nodeTypes.get(i);
    }
    
    public GroupDelayType getDelayType() {
    	return groupDelayType; 
    }
    
    public GroupWireDirection getWireDirection() {
    	return groupWireDir;
    }
    
    public TimingDirection getDirection() {
    	return direction;
    }
    
    public boolean isInitialGroup() {
    	return isInitialGroup;
    }
    
    public boolean isFinalGroup() {
    	return isFinalGroup;
    }
    
    public void setInitialGroup(boolean value) {
    	isInitialGroup = value;
    }
    
    public void setFinalGroup(boolean value) {
    	isFinalGroup = value;
    }
    
    public boolean hasPinFeed() {
    	return hasPinFeed;
    }  
}
