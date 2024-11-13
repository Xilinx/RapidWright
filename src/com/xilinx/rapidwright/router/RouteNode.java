/*
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
package com.xilinx.rapidwright.router;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.rwroute.RouterHelper;


/**
 * This class represents the basic routing element, a node or wire.  A node is described as a
 * wire with a particular name in a particular tile.  When routing, it keeps track of the source node
 * by setting the parent variable.
 * @author Chris Lavin
 *
 */
public class RouteNode implements Comparable<RouteNode> {

    /** This is the tile where the node/wire resides */
    public Tile tile;
    /** This is the enumerated int that represents the name of the wire specified */
    public int wire;
    /** This is the routing cost of using this node in the current route */
    public int cost;
    /** This is the pointer to a parent node in the route it is a part of */
    public RouteNode parent;
    /** This is the number of hops from the original source of the route this node is */
    public int level;
    /** This is the combined cost of a node when it is used, and used multiple times */
    public int history;

    /**
     * Empty constructor, sets tile and wires to null. Sets wire and cost to -1.
     * level and history are set to 0 and isPIP is set to false.
     */
    public RouteNode() {
        tile = null;
        wire = -1;
        cost = -1;
        level = 0;
        history = 0;
    }

    /**
     * A quick population constructor, parent is set to null, and the level is 0.
     * @param tile The tile of the new node.
     * @param wire The wire of the new node.
     */
    public RouteNode(Tile tile, int wire) {
        setTile(tile);
        setWire(wire);
        setParent(null);
        setLevel(0);
    }

    /**
     * Constructor common for routing expansion
     * @param wire Wire object to construct route node from
     * @param parent The parent of the wire in the expanion search
     */
    public RouteNode(Wire wire, RouteNode parent) {
        setTile(wire.getTile());
        setWire(wire.getWireIndex());
        setParent(parent);
        setLevel(parent.getLevel() + 1);
    }

    /**
     * A quick population constructor.
     * @param tile The tile of the new node.
     * @param wire The wire of the new node.
     * @param parent The parent of the new node, or null if none.
     * @param level The number of nodes between this node and the source node.
     */
    public RouteNode(Tile tile, int wire, RouteNode parent, int level) {
        setTile(tile);
        setWire(wire);
        setParent(parent);
        setLevel(level);
    }

    public RouteNode(SitePinInst p) {
        setTile(p.getTile());
        setWire(p.getConnectedWireIndex());
    }

    public RouteNode(Node n) {
        setTile(n.getTile());
        setWire(n.getWireIndex());
    }

    /**
     * Convenience constructor that takes the Node name {@code "<Tile>/<Wire>"} and
     * creates the node.
     * @param nodeName Name of the node
     * @param dev Device to which the node belongs
     */
    public RouteNode(String nodeName, Device dev) {
        String tileName = nodeName.substring(0, nodeName.indexOf('/'));
        setTile(dev.getTile(tileName));
        if (tile == null) throw new RuntimeException("ERROR: Tile '" + tileName + "' not found in device " + dev.getName() );
        setWire(getTile().getWireIndex(nodeName.substring(nodeName.indexOf('/')+1)));
        setParent(null);
        setLevel(0);
    }

    /**
     * A quick setter method for the tile and wire.
     * @param tile The new tile of the node.
     * @param wire The new wire of the node.
     */
    public void setTileAndWire(Tile tile, int wire) {
        setTile(tile);
        setWire(wire);
    }

    public void setTileAndWire(Wire wire) {
        setTile(wire.getTile());
        setWire(wire.getWireIndex());
    }

    /**
     * Gets all the possible connections to leaving this node
     * @return The list of all possible connections leaving this node
     */
    public List<Wire> getConnections() {
        return tile.getWireConnections(wire);
    }

    /**
     * Returns the current cost of this node
     * @return The cost of this node
     */
    public int getCost() {
        return this.cost;
    }

    /**
     * @return the number of hops from the source this node is
     */
    public int getLevel() {
        return level;
    }

    /**
     * @param level the number of hops from the source to this node
     */
    public void setLevel(int level) {
        this.level = level;
    }

    /**
     * @return the tile
     */
    public Tile getTile() {
        return tile;
    }

    /**
     * @param tile the tile to set
     */
    public void setTile(Tile tile) {
        this.tile = tile;
    }

    public void setCost(int cost) {
        this.cost = cost;
    }

    /**
     * @return the parent
     */
    public RouteNode getParent() {
        return parent;
    }

    /**
     * @param parent the parent to set
     */
    public void setParent(RouteNode parent) {
        this.parent = parent;
    }

    /**
     * @return the wire
     */
    public int getWire() {
        return wire;
    }

    /**
     * @param wire the wire to set
     */
    public void setWire(int wire) {
        this.wire = wire;
    }

    /**
     * @return the history
     */
    public int getHistory() {
        return history;
    }

    /**
     * @param history the history to set
     */
    public void setHistory(int history) {
        this.history = history;
    }

    public int getManhattanDistance(RouteNode snk) {
        return tile.getManhattanDistance(snk.getTile());
    }

    /**
     * The priority queue will use strictly the cost to evaluate priority
     */
    public int compareTo(RouteNode node) {
       return this.cost - node.cost;
    }

    /**
     * Quick check to see if the tile/wire combination match this node.
     */
    public boolean matches(String tileName, String wireName) {
         return getTile().getName().equals(tileName) && (getWireName().equals(wireName));
    }

    /* (non-Javadoc)
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((tile == null) ? 0 : tile.hashCode());
        result = prime * result + wire;
        return result;
    }

    /* (non-Javadoc)
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        RouteNode other = (RouteNode) obj;
        if (wire != other.wire)
            return false;
        if (tile == null) {
            if (other.tile != null)
                return false;
        } else if (!tile.equals(other.tile))
            return false;
        return true;
    }

    public String toString() {
        return this.tile + "/" + getWireName() + " " + this.cost + " " + this.level + " " + getIntentCode();
    }

    public String getName() {
        return this.tile + "/" + getWireName();
    }

    public String getWireName() {
        return tile.getWireName(wire);
    }

    public int getWireIndex(String wireName) {
        return getTile().getWireIndex(wireName);
    }

    public List<Wire> getWireConnections() {
        return tile.getWireConnections(wire);
    }

    public List<PIP> getBackwardPIPs() {
        return getTile().getBackwardPIPs(getWire());
    }

    public List<PIP> getForwardPIPs() {
        return getTile().getPIPs(getWire());
    }

    public IntentCode getIntentCode() {
        return tile.getWireIntentCode(getWire());
    }

    /**
     * @return
     */
    public ArrayList<Wire> getBackwardConnections() {
        return tile.getBackwardConnections(wire);
    }

    public RouteNode getBaseWire() {
        Node n = Node.getNode(tile,wire);
        return new RouteNode(n);
    }

    public ArrayList<PIP> getPIPsForwardToSink() {
        ArrayList<PIP> pips = new ArrayList<>();
        RouteNode curr = this;
        while (curr.parent != null) {
            for (Wire currWire : Arrays.asList(curr.getWiresInNode())) {
                if (!currWire.getTile().equals(curr.parent.getTile()))
                    continue;
                for (Wire w1 : curr.parent.tile.getWireConnections(currWire.getWireIndex())) {
                    if (w1.getWireIndex() == curr.parent.wire) {
                        if (w1.isEndPIPWire()) {
                            pips.add(new PIP(curr.parent.tile, currWire.getWireIndex(), curr.parent.wire, w1.getPIPType()));
                            break;
                        }
                    }
                }
                curr = curr.parent;
            }
        }
        return pips;
    }

    public ArrayList<PIP> getPIPsBackToSource() {
        ArrayList<PIP> pips = new ArrayList<>();
        RouteNode curr = this;
        while (curr.parent != null) {
            for (Wire parentWire : Arrays.asList(curr.parent.getWiresInNode())) {
                if (!parentWire.getTile().equals(curr.getTile()))
                    continue;
                for (Wire w1 : curr.tile.getWireConnections(parentWire.getWireIndex())) {
                    if (w1.getWireIndex() == curr.wire) {
                        if (w1.isEndPIPWire()) {
                            PIP p = new PIP(curr.tile, parentWire.getWireIndex(), curr.wire, w1.getPIPType());
                            if (p.isBidirectional()) {
                                p.setIsReversed(p.getStartWire().equals(w1));
                            }
                            pips.add(p);
                            break;
                        }
                    }
                }
            }
            curr = curr.parent;
        }
        return pips;
    }

    public ArrayList<PIP> getPIPsBackToSourceByNodes() {
        ArrayList<PIP> pips = new ArrayList<>();
        RouteNode curr = this;
        while (curr.parent != null) {
            PIP pip = RouterHelper.findPIPbetweenNodes(Node.getNode(curr.parent), Node.getNode(curr));
            if (pip != null) {
                pips.add(pip);
            }
            curr = curr.parent;
        }
        return pips;
    }

    public Wire[] getWiresInNode() {
        return Node.getWiresInNode(getTile(),getWire());
    }

    /**
     * Creates a new node representing the start wire of this PIP
     * @return
     */
    public RouteNode getStartNode(PIP p) {
        return new RouteNode(p.getTile(), p.getStartWireIndex());
    }

    /**
     * Creates a new node representing the end wire of this PIP
     * @return
     */
    public RouteNode getEndNode(PIP p) {
        return new RouteNode(p.getTile(), p.getEndWireIndex());
    }

    public RouteNode createNode(Wire w) {
        return new RouteNode(w.getTile(), w.getWireIndex());
    }

    public RouteNode createNode(Wire w, RouteNode parent) {
        RouteNode n = new RouteNode(w.getTile(), w.getWireIndex());
        n.setParent(parent);
        return n;
    }


    /**
     * Creates a new priority queue that sorts route nodes based on
     * their lowest cost (see {@link RouteNode#getCost()}).
     * @return The newly created priority queue.
     */
    public static PriorityQueue<RouteNode> createPriorityQueue() {
        return new PriorityQueue<>(16);
    }
}
