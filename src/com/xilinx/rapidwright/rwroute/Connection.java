/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;

/**
 * A Connection instance represents a pair of source-sink {@link SitePinInst} instances of a {@link Net} instance.
 */
public class Connection implements Comparable<Connection>{
    /** A unique index of a connection */
    private final int id;
    /** The source and sink {@link SitePinInst} instances of a connection */
    private SitePinInst source;
    private final SitePinInst sink;
    /**
     * The source and sink {@link RouteNode} instances (rnodes) of a connection.
     * They are created based on the INT tile nodes the source and sink SitePinInsts connect to, respectively.
     */
    private RouteNode sourceRnode;
    private RouteNode altSourceRnode;
    private RouteNode sinkRnode;
    private List<RouteNode> altSinkRnodes;
    /**
     * true to indicate the source and the sink are connected through dedicated resources,
     * such as the carry chain connections and connections between cascaded BRAMs.
     * These connections only need to be routed once after the iterative routing of other connections.
     */
    private boolean direct;
    /** The {@link NetWrapper} instance indicating the net a connection belongs to */
    private NetWrapper netWrapper;
    /**
     * The half-perimeter wirelength of a connection based on the source and sink rnodes,
     * used for sorting connection and statistics of connection span.
     */
    private short hpwl;
    /** Boundary coordinates of a connection's bounding box (BB), based on INT tile X and Y coordinates */
    private short xMinBB;
    private short xMaxBB;
    private short yMinBB;
    private short yMaxBB;
    /**
     * {@link TimingEdge} instances associated to a connection.
     * For LUT_6_2_* pins, there will be two timing edges mapped to the same connection.
     */
    private List<TimingEdge> timingEdges;
    /** The criticality factor to indicate how timing-critical a connection is */
    private float criticality;
    /** List of RouteNodes that make up of the route of a connection */
    private List<RouteNode> rnodes;

    /** To indicate if the route delay of a connection has been patched up, when there are consecutive long nodes */
    private boolean dlyPatched;
    /** true to indicate that a connection cross SLRs */
    private boolean crossSLR;
    /** List of nodes assigned to a connection to form the path for generating PIPs */
    private List<Node> nodes;

    public Connection(int id, SitePinInst source, SitePinInst sink, NetWrapper netWrapper) {
        this.id = id;
        this.source = source;
        this.sink = sink;
        criticality = 0f;
        rnodes = new ArrayList<>();
        this.netWrapper = netWrapper;
        netWrapper.addConnection(this);
        crossSLR = !source.getTile().getSLR().equals(sink.getTile().getSLR());
    }

    /**
     * Computes the half-perimeter wirelength of connection based on the source and sink rnodes.
     */
    public void computeHpwl() {
        hpwl = (short) (Math.abs(sourceRnode.getEndTileXCoordinate() - sinkRnode.getEndTileXCoordinate()) + 1
                + Math.abs(sourceRnode.getEndTileYCoordinate() - sinkRnode.getEndTileYCoordinate()) + 1);
    }

    /**
     * Computes the connection bounding box based on the geometric center of the net, source and sink rnodes,
     * and for cross SLR connections the location of Laguna columns.
     * @param boundingBoxExtensionX To indicate the extension on top of the minimum bounding box in the horizontal direction.
     * @param boundingBoxExtensionY To indicate the extension on top of the minimum bounding box in the vertical direction.
     * @param nextLagunaColumn Array mapping arbitrary tile columns to the next Laguna column
     * @param prevLagunaColumn Array mapping arbitrary tile columns to the previous Laguna column
     */
    public void computeConnectionBoundingBox(short boundingBoxExtensionX, short boundingBoxExtensionY,
                                             int[] nextLagunaColumn, int[] prevLagunaColumn) {
        short xMin, xMax, yMin, yMax;
        short xNetCenter = (short) Math.ceil(netWrapper.getXCenter());
        short yNetCenter = (short) Math.ceil(netWrapper.getYCenter());
        xMax = maxOfThree(sourceRnode.getEndTileXCoordinate(), sinkRnode.getEndTileXCoordinate(), xNetCenter);
        xMin = minOfThree(sourceRnode.getEndTileXCoordinate(), sinkRnode.getEndTileXCoordinate(), xNetCenter);
        yMax = maxOfThree(sourceRnode.getEndTileYCoordinate(), sinkRnode.getEndTileYCoordinate(), yNetCenter);
        yMin = minOfThree(sourceRnode.getEndTileYCoordinate(), sinkRnode.getEndTileYCoordinate(), yNetCenter);

        if (isCrossSLR()) {
            // For SLR-crossing connections, ensure the bounding box width contains at least one Laguna column
            // before bounding box extension
            int nextLaguna = nextLagunaColumn[xMin];
            int prevLaguna = prevLagunaColumn[xMax];
            if (nextLaguna != Integer.MAX_VALUE) {
                xMax = (short) Math.max(xMax, nextLaguna);
            }
            if (prevLaguna != Integer.MIN_VALUE) {
                xMin = (short) Math.min(xMin, prevLaguna);
            }
        }

        xMaxBB = (short) (xMax + boundingBoxExtensionX);
        xMinBB = (short) (xMin - boundingBoxExtensionX);
        yMaxBB = (short) (yMax + boundingBoxExtensionY);
        yMinBB = (short) (yMin - boundingBoxExtensionY);

        if (isCrossSLR()) {
            // Equivalently, ensure that cross-SLR connections are at least as high as a SLL;
            // if necessary, expand the sink side of the bounding box
            short heightMinusSLL = (short) ((yMaxBB - yMinBB - 1) - RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES);
            if (heightMinusSLL < 0) {
                if (sourceRnode.getEndTileYCoordinate() <= sinkRnode.getEndTileYCoordinate()) {
                    // Upwards
                    short newYMaxBB = (short) (yMin + RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES + 1);
                    assert(newYMaxBB > yMaxBB);
                    yMaxBB = newYMaxBB;
                } else {
                    // Downwards
                    short newYMinBB = (short) (yMax - RouteNodeGraph.SUPER_LONG_LINE_LENGTH_IN_TILES - 1);
                    assert(newYMinBB < yMinBB);
                    yMinBB = newYMinBB;
                }
            }
        }

        xMinBB = xMinBB < 0? -1:xMinBB;
        yMinBB = yMinBB < 0? -1:yMinBB;
    }

    private short maxOfThree(short var1, short var2, short var3) {
        return (short) Math.max(Math.max(var1, var2), var3);
    }

    private short minOfThree(short var1, short var2, short var3) {
        return (short) Math.min(Math.min(var1, var2), var3);
    }

    /**
     * Computes criticality of a connection.
     * @param maxDelay The maximum delay to normalize the slack of a connection.
     * @param maxCriticality The maximum criticality.
     * @param criticalityExponent The exponent to separate critical connections and non-critical connections.
     */
    public void calculateCriticality(float maxDelay, float maxCriticality, float criticalityExponent) {
        float minSlack = Float.MAX_VALUE;
        for (TimingEdge e : getTimingEdges()) {
            float slack = e.getDst().getRequiredTime() - e.getSrc().getArrivalTime() - e.getDelay();
            minSlack = Float.min(minSlack, slack);
        }

        // Negative slacks are not supported, and should not occur if maxDelay was
        // normalized correctly.
        assert(minSlack >= 0);

        float tempCriticality  = (1 - minSlack / maxDelay);

        tempCriticality = (float) Math.pow(tempCriticality, criticalityExponent) * maxCriticality;

        if (tempCriticality > criticality)
            setCriticality(tempCriticality);
    }

    /**
     * Checks if a connection has any overused rnodes to indicate the congestion status.
     * @return
     */
    public boolean isCongested() {
        for (RouteNode rn : getRnodes()) {
            if (rn.isOverUsed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if a connection is routed through any rnodes that have multiple drivers.
     * @return
     */
    public boolean useRnodesWithMultiDrivers() {
        for (RouteNode rn : getRnodes()) {
            if (rn.hasMultiDrivers()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Add the give RouteNode to the list of those used by this Connection.
     * Expand the bounding box accordingly, since this node could describe an
     * existing routing path computed using a different bounding box.
     * @param rn RouteNode to add
     */
    public void addRnode(RouteNode rn) {
        xMinBB = (short) Math.min(xMinBB, rn.getBeginTileXCoordinate() - 1);
        xMaxBB = (short) Math.max(xMaxBB, rn.getEndTileXCoordinate() + 1);
        yMinBB = (short) Math.min(yMinBB, rn.getBeginTileYCoordinate() - 1);
        yMaxBB = (short) Math.max(yMaxBB, rn.getEndTileYCoordinate() + 1);
        rnodes.add(rn);
    }

    public void updateRouteDelay() {
        setTimingEdgesDelay(getRouteDelay());
    }

    public void setTimingEdgesDelay(float routeDelay) {
        for (TimingEdge e : getTimingEdges()) {
            e.setRouteDelay(routeDelay);
        }
    }

    private float getRouteDelay() {
        float routeDelay = getRnodes().get(getRnodes().size() - 1).getDelay();
        for (int i = getRnodes().size() - 2; i >= 0; i--) {
            RouteNode rnode = getRnodes().get(i);
            RouteNode parent = getRnodes().get(i+1);
            routeDelay += rnode.getDelay() +
                    DelayEstimatorBase.getExtraDelay(rnode, DelayEstimatorBase.isLong(parent));
        }
        return routeDelay;
    }

    public void setCriticality(float criticality) {
        this.criticality = criticality;
    }

    public void resetCriticality() {
        criticality = 0;
    }

    public float getCriticality() {
        return criticality;
    }

    public void resetRoute() {
        getRnodes().clear();
        sink.setRouted(false);
    }

    public RouteNode getSourceRnode() {
        return sourceRnode;
    }

    public void setSourceRnode(RouteNode sourceNode) {
        sourceRnode = sourceNode;
    }

    public RouteNode getAltSourceRnode() {
        return altSourceRnode;
    }

    public void setAltSourceRnode(RouteNode altSourceNode) {
        altSourceRnode = altSourceNode;
    }

    public RouteNode getSinkRnode() {
        return sinkRnode;
    }

    public void setSinkRnode(RouteNode sinkRnode) {
        this.sinkRnode = sinkRnode;
    }

    public List<RouteNode> getAltSinkRnodes() {
        return altSinkRnodes == null ? Collections.emptyList() : altSinkRnodes;
    }

    public void addAltSinkRnode(RouteNode sinkRnode) {
        if (altSinkRnodes == null) {
            altSinkRnodes = new ArrayList<>(1);
        } else {
            assert(!altSinkRnodes.contains(sinkRnode));
        }
        assert(sinkRnode.getType() == RouteNodeType.PINFEED_I ||
               // Can be a WIRE if node is not exclusive a sink
               sinkRnode.getType() == RouteNodeType.WIRE);
        altSinkRnodes.add(sinkRnode);
    }

    public short getXMinBB() {
        return xMinBB;
    }

    public void setXMinBB(short xMinBB) {
        this.xMinBB = xMinBB;
    }

    public short getXMaxBB() {
        return xMaxBB;
    }

    public void setXMaxBB(short xMaxBB) {
        this.xMaxBB = xMaxBB;
    }

    public short getYMinBB() {
        return yMinBB;
    }

    public void setYMinBB(short yMinBB) {
        this.yMinBB = yMinBB;
    }

    public short getYMaxBB() {
        return yMaxBB;
    }

    public void setYMaxBB(short yMaxBB) {
        this.yMaxBB = yMaxBB;
    }

    public void setNetWrapper(NetWrapper netWrapper) {
        this.netWrapper = netWrapper;
    }

    public NetWrapper getNetWrapper() {
        return this.netWrapper;
    }

    public SitePinInst getSource() {
        return source;
    }

    public void setSource(SitePinInst source) {
        this.source = source;
    }

    public boolean isDirect() {
        return direct;
    }

    public void setDirect(boolean direct) {
        this.direct = direct;
    }

    public SitePinInst getSink() {
        return sink;
    }

    public List<TimingEdge> getTimingEdges() {
        return timingEdges;
    }

    public void setTimingEdges(List<TimingEdge> timingEdges) {
        this.timingEdges = timingEdges;
    }

    public short getHpwl() {
        return hpwl;
    }

    public void setHpwl(short conHpwl) {
        hpwl = conHpwl;
    }

    public List<RouteNode> getRnodes() {
        return rnodes;
    }

    public void setRnodes(List<RouteNode> rnodes) {
        this.rnodes = rnodes;
    }

    public boolean isDlyPatched() {
        return dlyPatched;
    }

    public void setDlyPatched(boolean dlyPatched) {
        this.dlyPatched = dlyPatched;
    }

    public boolean isCrossSLR() {
        return crossSLR;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public void setNodes(List<Node> nodes) {
        this.nodes = nodes;
    }

    public void enlargeBoundingBox(int horizontalIncrement, int verticalIncrement) {
        xMinBB -= horizontalIncrement;
        xMaxBB += horizontalIncrement;
        yMinBB -= verticalIncrement;
        yMaxBB += verticalIncrement;
        xMinBB = xMinBB < 0? -1:xMinBB;
        yMinBB = yMinBB < 0? -1:yMinBB;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public int compareTo(Connection arg0) {
        if (this == arg0)
            return 0;
        if (netWrapper.getConnections().size() > arg0.getNetWrapper().getConnections().size()) {
            return 1;
        } else if (netWrapper.getConnections().size() == arg0.getNetWrapper().getConnections().size()) {
            if (this.getHpwl() > arg0.getHpwl()) {
                return 1;
            } else if (getHpwl() == arg0.getHpwl()) {
                if (hashCode() > arg0.hashCode()) {
                    return -1;
                }
            }
        } else {
            return -1;
        }
        return -1;
    }

    public String bbRectangleString() {
        return "[( " + xMinBB + ", " + yMinBB + " ), -> ( " + xMaxBB + ", " + yMaxBB + " )]";
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append("Con ");
        s.append(String.format("%6s", id));
        s.append(", ");
        s.append("boundbox = " + bbRectangleString());
        s.append(", ");
        s.append("net = " + netWrapper.getNet().getName());
        s.append(", ");
        s.append(String.format("net fanout = %3s", netWrapper.getConnections().size()));
        s.append(", ");
        s.append(String.format("source = %s", getSource().getName()));
        s.append(", ");
        s.append("sink = " + getSink().getName());
        s.append(", ");
        s.append(String.format("delay = %4d ", (short)(getTimingEdges() == null? 0:getTimingEdges().get(0).getNetDelay())));
        s.append(", ");
        s.append(String.format("criticality = %4.3f ", getCriticality()));

        return s.toString();
    }

    public void setAllTargets(boolean target) {
        if (sinkRnode.countConnectionsOfUser(netWrapper) == 0 ||
            sinkRnode.getIntentCode() == IntentCode.NODE_PINBOUNCE) {
            // Since this connection will have been ripped up, only mark a node
            // as a target if it's not already used by this net.
            // This prevents -- for the case where the same net needs to be routed
            // to the same LUT more than once -- the illegal case of the same
            // physical pin servicing more than one logical pin
            sinkRnode.setTarget(target);
        } else {
            assert(altSinkRnodes != null && !altSinkRnodes.isEmpty());
        }
        if (altSinkRnodes != null) {
            for (RouteNode rnode : altSinkRnodes) {
                // Same condition as above: only allow this as an alternate sink
                // if it's not already in use by the current net to prevent the case
                // where the same physical pin services more than one logical pin
                if (rnode.countConnectionsOfUser(netWrapper) == 0 ||
                    // Except if it is not a PINFEED_I
                    rnode.getType() != RouteNodeType.PINFEED_I) {
                    assert(rnode.getIntentCode() != IntentCode.NODE_PINBOUNCE);
                    rnode.setTarget(target);
                }
            }
        }
    }
}
