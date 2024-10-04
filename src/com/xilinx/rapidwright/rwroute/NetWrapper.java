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
import java.util.List;

import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;

/**
 * A wrapper class of {@link Net} with additional information for the router.
 */
public class NetWrapper{
    /** A unique index for a NetWrapper Object*/
    private int id;
    /** The associated {@link Net} Object */
    private Net net;
    /** A list of {@link Connection} Objects of the net */
    private List<Connection> connections;
    /** Geometric center coordinates */
    private float xCenter;
    private float yCenter;
    /** The half-perimeter wirelength */
    private short doubleHpwl;
    boolean noAltSourceFound;
    private RouteNode sourceRnode;
    private RouteNode altSourceRnode;

    public NetWrapper(int id, Net net) {
        this.id = id;
        this.net = net;
        connections = new ArrayList<>();
        noAltSourceFound = false;
    }

    public void computeHPWLAndCenterCoordinates(int[] nextLagunaColumn, int[] prevLagunaColumn) {
        int xMin = Integer.MAX_VALUE;
        int yMin = Integer.MAX_VALUE;
        int xMax = Integer.MIN_VALUE;
        int yMax = Integer.MIN_VALUE;
        int xSum = 0;
        int ySum = 0;
        int count = 0;
        boolean sourceRnodeAdded = false;
        for (Connection connection : connections) {
            if (connection.isDirect()) continue;
            if (!sourceRnodeAdded) {
                short x = connection.getSourceRnode().getEndTileXCoordinate();
                short y = connection.getSourceRnode().getEndTileYCoordinate();
                xMin = Integer.min(xMin, x);
                yMin = Integer.min(yMin, y);
                xMax = Integer.max(xMax, x);
                yMax = Integer.max(yMax, y);

                if (connection.isCrossSLR()) {
                    // For SLR-crossing connections, ensure it contains at least one Laguna column
                    int nextLaguna = nextLagunaColumn[xMin];
                    int prevLaguna = prevLagunaColumn[xMax];
                    xMin = (short) Math.min(xMin, prevLaguna);
                    xMax = (short) Math.max(xMax, nextLaguna);
                }

                xSum += x;
                ySum += y;
                sourceRnodeAdded = true;
                count++;
            }
            short x = connection.getSinkRnode().getEndTileXCoordinate();
            short y = connection.getSinkRnode().getEndTileYCoordinate();
            xMin = Integer.min(xMin, x);
            yMin = Integer.min(yMin, y);
            xMax = Integer.max(xMax, x);
            yMax = Integer.max(yMax, y);
            xSum += x;
            ySum += y;
            count++;
        }

        doubleHpwl = (short) ((xMax - xMin + 1 + yMax - yMin + 1) * 2);
        xCenter = (float)xSum / count;
        yCenter = (float)ySum / count;
    }

    public Net getNet() {
        return net;
    }

    public void addConnection(Connection connection) {
        connections.add(connection);
    }

    public List<Connection> getConnections() {
        return connections;
    }

    public short getDoubleHpwl() {
        return doubleHpwl;
    }

    public float getYCenter() {
        return yCenter;
    }

    public float getXCenter() {
        return xCenter;
    }

    @Override
    public int hashCode() {
        return id;
    }

    public RouteNode getSourceRnode() {
        return sourceRnode;
    }

    public void setSourceRnode(RouteNode sourceRnode) {
        this.sourceRnode = sourceRnode;
    }

    public SitePinInst getOrCreateAlternateSource(RouteNodeGraph routingGraph) {
        if (noAltSourceFound) {
            return null;
        }

        SitePinInst altSource = net.getAlternateSource();
        if (altSource == null) {
            altSource = DesignTools.getLegalAlternativeOutputPin(net);
            if (altSource == null) {
                noAltSourceFound = true;
                return null;
            }

            net.addPin(altSource);
            DesignTools.routeAlternativeOutputSitePin(net, altSource);
        }
        if (altSourceRnode == null) {
            Node altSourceNode = RouterHelper.projectOutputPinToINTNode(altSource);
            if (altSourceNode == null) {
                noAltSourceFound = true;
                return null;
            }

            if (routingGraph.isPreserved(altSourceNode)) {
                noAltSourceFound = true;
                return null;
            }

            altSourceRnode = routingGraph.getOrCreate(altSourceNode, RouteNodeType.PINFEED_O);
        }
        assert(altSourceRnode != null);
        return altSource;
    }

    public RouteNode getAltSourceRnode() {
        return altSourceRnode;
    }
}
