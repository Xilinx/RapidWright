/*
 *
 * Copyright (c) 2021 Ghent University.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
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
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimator;
import com.xilinx.rapidwright.util.Pair;

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
     * The id of the connection of the timing graph that this connection drives (see
     * {@link com.xilinx.rapidwright.timing.sta.StaGraph#getConnectionId(SitePinInst)}), -1 if not timed
     */
    private int staConnId = -1;
    /** The route delay last set for timing, see {@link #setTimingRouteDelay(float)} */
    private float timingRouteDelay;
    /** The delay that timing adds to the route delay of this connection, for {@link #timingPinDelaySource} */
    private float timingPinDelay;
    /** The source the {@link #timingPinDelay} is for, null if not computed */
    private SitePinInst timingPinDelaySource;
    /** The criticality factor to indicate how timing-critical a connection is */
    private float criticality;
    /** List of RouteNodes that make up of the route of a connection */
    private List<RouteNode> rnodes;

    /** To indicate if the route delay of a connection has been patched up, when there are consecutive long nodes */
    private boolean dlyPatched;
    /** true to indicate that a connection cross SLRs in given direction */
    private boolean crossSLRnorth;
    private boolean crossSLRsouth;
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
        crossSLRnorth = false;
        crossSLRsouth = false;
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
     * @param routingGraph A RouteNodeGraph object for additional context.
     */
    public void computeConnectionBoundingBox(short boundingBoxExtensionX, short boundingBoxExtensionY,
                                             RouteNodeGraph routingGraph) {
        short xMin, xMax, yMin, yMax;
        short xNetCenter = (short) Math.ceil(netWrapper.getXCenter());
        short yNetCenter = (short) Math.ceil(netWrapper.getYCenter());
        xMax = maxOfThree(sourceRnode.getEndTileXCoordinate(), sinkRnode.getEndTileXCoordinate(), xNetCenter);
        xMin = minOfThree(sourceRnode.getEndTileXCoordinate(), sinkRnode.getEndTileXCoordinate(), xNetCenter);
        yMax = maxOfThree(sourceRnode.getEndTileYCoordinate(), sinkRnode.getEndTileYCoordinate(), yNetCenter);
        yMin = minOfThree(sourceRnode.getEndTileYCoordinate(), sinkRnode.getEndTileYCoordinate(), yNetCenter);

        if (isCrossSLR()) {
            // For SLR-crossing connections, ensure the bounding box width contains at least one column from which
            // super long lines can cross SLRs (Laguna on UltraScale/UltraScale+, SLL on Versal) before bounding box
            // extension
            short widenedXMin = (short) routingGraph.getCrossingWidenedXMin(xMin, xMax);
            xMax = (short) routingGraph.getCrossingWidenedXMax(xMin, xMax);
            xMin = widenedXMin;
        }

        xMaxBB = (short) (xMax + boundingBoxExtensionX);
        xMinBB = (short) (xMin - boundingBoxExtensionX);
        yMaxBB = (short) (yMax + boundingBoxExtensionY);
        yMinBB = (short) (yMin - boundingBoxExtensionY);

        if (isCrossSLR()) {
            // Equivalently, ensure that cross-SLR connections are at least as high as a SLL;
            // if necessary, expand the sink side of the bounding box.
            // The bounding box excludes its edges (see RouteNode.isInConnectionBoundingBox()), so an SLL
            // from row y to row y + SLL length fits only if yMinBB < y and y + SLL length < yMaxBB
            short heightMinusSLL = (short) ((yMaxBB - yMinBB - 2) - routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES);
            if (heightMinusSLL < 0) {
                if (sourceRnode.getEndTileYCoordinate() <= sinkRnode.getEndTileYCoordinate()) {
                    // Upwards
                    short newYMaxBB = (short) (Math.max(yMin, yMinBB + 1) + routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES + 1);
                    assert(newYMaxBB > yMaxBB);
                    yMaxBB = newYMaxBB;
                } else {
                    // Downwards
                    short newYMinBB = (short) (Math.min(yMax, yMaxBB - 1) - routingGraph.SUPER_LONG_LINE_LENGTH_IN_TILES - 1);
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
        markRouteChanged();
    }

    /** Marks the net of this connection as having a changed route, for the incremental fanout delay update */
    private void markRouteChanged() {
        if (netWrapper != null && !netWrapper.isRouteChanged()) {
            netWrapper.setRouteChanged(true);
        }
    }

    /**
     * Gets the id of the connection of the timing graph that this connection drives.
     * @return The id, -1 if this connection is not timed.
     */
    public int getStaConnId() {
        return staConnId;
    }

    public void setStaConnId(int staConnId) {
        this.staConnId = staConnId;
    }

    /**
     * Gets the route delay last set for timing, see
     * {@link com.xilinx.rapidwright.timing.TimingManager#setRouteDelay(Connection, float)}.
     * @return The delay in ps.
     */
    public float getTimingRouteDelay() {
        return timingRouteDelay;
    }

    /**
     * Records the route delay of this connection set for timing; called by
     * {@link com.xilinx.rapidwright.timing.TimingManager#setRouteDelay(Connection, float)}, which
     * also updates the timing analysis.
     * @param routeDelay The delay in ps.
     */
    public void setTimingRouteDelay(float routeDelay) {
        timingRouteDelay = routeDelay;
    }

    /**
     * Gets the delay that the timing analysis adds to the route delay of this connection, a constant
     * per connection (see {@link com.xilinx.rapidwright.timing.TimingManager#setRouteDelay(Connection, float)}),
     * if computed for its current source.
     * @return The delay in ps, or NaN if not computed for the current source.
     */
    public float getTimingPinDelay() {
        return timingPinDelaySource == source ? timingPinDelay : Float.NaN;
    }

    /**
     * Sets the delay that the timing analysis adds to the route delay of this connection, for its
     * current source.
     * @param delay The delay in ps.
     */
    public void setTimingPinDelay(float delay) {
        timingPinDelay = delay;
        timingPinDelaySource = source;
    }

    /**
     * Gets the delay of this connection's route, the sum of its rnodes' delays and their extra delays. A node
     * that the estimator masks (see {@link DelayEstimator#masksUTurnNodes()}), which only an existing route (e.g.
     * one recovered by partial routing) can use, counts with its unmasked delay.
     * @param estimator The delay estimator that the rnode delays were computed with.
     * @return The delay in ps.
     */
    float getRouteDelay(DelayEstimator estimator) {
        float routeDelay = getDelayOf(estimator, getRnodes().get(getRnodes().size() - 1));
        for (int i = getRnodes().size() - 2; i >= 0; i--) {
            RouteNode rnode = getRnodes().get(i);
            RouteNode parent = getRnodes().get(i+1);
            routeDelay += getDelayOf(estimator, rnode) + RouteNode.getExtraDelay(estimator, parent, rnode);
        }
        return routeDelay;
    }

    private static float getDelayOf(DelayEstimator estimator, RouteNode rnode) {
        float delay = rnode.getDelay();
        if (delay >= Short.MAX_VALUE / 2 && estimator.masksUTurnNodes()) {
            // The delay of a masked node (see DelayEstimator.masksUTurnNodes()), which is no estimate
            return estimator.getUnmaskedDelayOf(rnode);
        }
        return delay;
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
        markRouteChanged();
    }

    public RouteNode getSourceRnode() {
        return sourceRnode;
    }

    public void setSourceRnode(RouteNode sourceNode) {
        sourceRnode = sourceNode;
    }

    public RouteNode getSinkRnode() {
        return sinkRnode;
    }

    public void setSinkRnode(RouteNode sinkRnode) {
        this.sinkRnode = sinkRnode;

        assert(sourceRnode != null);
        if (!sourceRnode.getTile().getSLR().equals(sinkRnode.getTile().getSLR())) {
            if (sourceRnode.getTile().getTileYCoordinate() < sinkRnode.getTile().getTileYCoordinate()) {
                crossSLRnorth = true;
                assert(!crossSLRsouth);
            } else {
                assert(!crossSLRnorth);
                crossSLRsouth = true;
            }
        } else {
            assert(!crossSLRnorth);
            assert(!crossSLRsouth);
        }
    }

    public List<RouteNode> getAltSinkRnodes() {
        return altSinkRnodes == null ? Collections.emptyList() : altSinkRnodes;
    }

    public boolean hasAltSinks() {
        return altSinkRnodes != null && !altSinkRnodes.isEmpty();
    }

    /**
     * Checks whether this connection's sink (and any alternate, LUT pin swapping, sinks, which are in the same INT tile
     * and on the same side) is driven only by INODEs: an INT tile pin (NODE_PINFEED: an UltraScale/UltraScale+ IMUX;
     * NODE_PINBOUNCE: a bypass or bounce; NODE_IMUX: a Versal IMUX) other than an UltraScale/UltraScale+ CTRL pin
     * (NODE_PINFEED, but driven by INT_NODE_GLOBAL_*), as found on xcvu9p, xcku040, xcvc1902 and xcv80.
     * @return True if so.
     */
    public boolean isSinkDrivenByINodes() {
        if (sinkRnode.getType() == RouteNodeType.EXCLUSIVE_SINK_CTRL ||
                sinkRnode.getTile().getTileTypeEnum() != TileTypeEnum.INT) {
            return false;
        }
        IntentCode ic = sinkRnode.getIntentCode();
        return ic == IntentCode.NODE_PINFEED || ic == IntentCode.NODE_PINBOUNCE || ic == IntentCode.NODE_IMUX;
    }

    public void addAltSinkRnode(RouteNode sinkRnode) {
        if (altSinkRnodes == null) {
            altSinkRnodes = new ArrayList<>(1);
        } else {
            assert(!altSinkRnodes.contains(sinkRnode));
        }
        // Alternate sinks by their nature cannot be exclusive
        assert(!sinkRnode.getType().isAnyExclusiveSink());
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

    public Net getNet() {
        return netWrapper.getNet();
    }

    public SitePinInst getSource() {
        return source;
    }

    public void setSource(SitePinInst source) {
        assert(source != null);
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
        markRouteChanged();
    }

    public boolean isDlyPatched() {
        return dlyPatched;
    }

    public void setDlyPatched(boolean dlyPatched) {
        this.dlyPatched = dlyPatched;
    }

    public boolean isCrossSLR() {
        return crossSLRnorth || crossSLRsouth;
    }

    public boolean isCrossSLRnorth() {
        return crossSLRnorth;
    }

    public boolean isCrossSLRsouth() {
        return crossSLRsouth;
    }

    public List<Node> getNodes() {
        return nodes == null ? Collections.emptyList() : nodes;
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
    public int compareTo(Connection that) {
        // 1st priority: descending net fanout
        int comp = Integer.compare(that.getNetWrapper().getConnections().size(), this.getNetWrapper().getConnections().size());
        if (comp != 0) {
            return comp;
        }
        // 2nd priority: ascending HPWL
        return Short.compare(this.getHpwl(), that.getHpwl());
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
        s.append(String.format("source = %s", getSource().getSitePinName()));
        s.append(", ");
        s.append("sink = " + getSink().getSitePinName());
        s.append(", ");
        s.append(String.format("delay = %4d ", (short) timingRouteDelay));
        s.append(", ");
        s.append(String.format("criticality = %4.3f ", getCriticality()));

        return s.toString();
    }

    public void setAllTargets(RWRoute.ConnectionState state) {
        // With LUT pin swapping (the only source of alternate sinks), a net that drives several
        // inputs of the same LUT has several connections whose candidate pins overlap, and another
        // of them may already be routed to one of this connection's pins (including its original
        // one). Each LUT input needs its own pin, so such a pin is not a target -- even though
        // routing otherwise lets connections of the same net share nodes.
        if (!hasAltSinks() || sinkRnode.countConnectionsOfUser(netWrapper) == 0) {
            sinkRnode.markTarget(state);
        }
        if (altSinkRnodes != null) {
            for (RouteNode rnode : altSinkRnodes) {
                assert(!rnode.getType().isAnyExclusiveSink());
                assert(rnode.getIntentCode() != IntentCode.NODE_PINBOUNCE);
                if (rnode.countConnectionsOfUser(netWrapper) > 0) {
                    continue;
                }
                rnode.markTarget(state);
            }
        }
        // Each LUT pin carries one signal, and this connection's candidate pins are its LUT's
        // swappable pins that are not preserved (for other nets): this net's other connections
        // into the LUT cannot occupy all of them, since this connection needs one too
        assert(!state.targets.isEmpty());
    }

    protected Pair<SitePinInst,RouteNode> getOrCreateAlternateSource(RouteNodeGraph routingGraph) {
        SitePinInst altSource = netWrapper.getOrCreateAlternateSource(routingGraph);
        if (altSource == null) {
            return null;
        }

        Net net = netWrapper.getNet();
        RouteNode altSourceRnode;
        if (source.equals(net.getSource())) {
            altSourceRnode = netWrapper.getAltSourceRnode();
        } else {
            assert(source.equals(net.getAlternateSource()));
            altSource = net.getSource();
            assert(altSource != null);
            altSourceRnode = netWrapper.getSourceRnode();
        }

        assert(altSourceRnode != null);
        return new Pair<>(altSource, altSourceRnode);
    }

    public boolean isRouted() {
        return sink.isRouted();
    }

    public void setRouted(boolean isRouted) {
        sink.setRouted(isRouted);
    }
}
