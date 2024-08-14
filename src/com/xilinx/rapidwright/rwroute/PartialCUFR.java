/*
 *
 * Copyright (c) 2024 The Chinese University of Hong Kong.
 * Copyright (c) 2022-2024, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Wenhao Lin, The Chinese University of Hong Kong.
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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.interchange.Interchange;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.ParallelismTools;
import com.xilinx.rapidwright.util.RuntimeTracker;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that extends {@link PartialRouter} with {@link CUFR}'s parallel capabilities.
 */
public class PartialCUFR extends PartialRouter {
    /* A recursive partitioning ternary tree */
    private CUFRpartitionTree partitionTree;
    /** Timer to store partitioning runtime */
    private RuntimeTracker partitionTimer;
    /** A unique ConnectionState instance to be reused by each thread (shadows RWRoute.connectionState)
     *  (do not use ThreadLocal as the only way to have its values garbage collected is through calling
     *  ThreadLocal.remove() from the owning thread; this cannot be done elegantly when routing has finished) */
    private final Map<Thread,ConnectionState> connectionState;
    private boolean needsRepartitioning;

    public PartialCUFR(Design design, RWRouteConfig config, Collection<SitePinInst> pinsToRoute, boolean softPreserve) {
        super(design, config, pinsToRoute, softPreserve);
        connectionState = new ConcurrentHashMap<>();
        needsRepartitioning = true;
    }

    public static class RouteNodeGraphPartialCUFR extends RouteNodeGraphPartial {
        public RouteNodeGraphPartialCUFR(Design design, RWRouteConfig config) {
            super(design, config, new ConcurrentHashMap<>());
        }

        // Do not track createRnodeTime since it is meaningless when multithreading
        @Override
        protected void addCreateRnodeTime(long time) {}
    }

    public static class RouteNodeGraphPartialCUFRTimingDriven extends RouteNodeGraphPartialTimingDriven {
        public RouteNodeGraphPartialCUFRTimingDriven(Design design, RWRouteConfig config, DelayEstimatorBase delayEstimator) {
            super(design, config, delayEstimator, new ConcurrentHashMap<>());
        }

        // Do not track createRnodeTime since it is meaningless when multithreading
        @Override
        protected void addCreateRnodeTime(long time) {}
    }

    @Override
    protected RouteNodeGraph createRouteNodeGraph() {
        if (config.isTimingDriven()) {
            /* An instantiated delay estimator that is used to calculate delay of routing resources */
            DelayEstimatorBase estimator = new DelayEstimatorBase(design.getDevice(), new InterconnectInfo(), config.isUseUTurnNodes(), 0);
            return new RouteNodeGraphPartialCUFRTimingDriven(design, config, estimator);
        } else {
            return new RouteNodeGraphPartialCUFR(design, config);
        }
    }

    @Override
    protected ConnectionState getConnectionState() {
        return connectionState.computeIfAbsent(Thread.currentThread(), (k) -> new ConnectionState());
    }

    @Override
    protected void initialize() {
        super.initialize();
        partitionTimer = routerTimer.createStandAloneRuntimeTracker("update partitioning");
    }

    /**
     * Parallel route a partition tree.
     */
    private void routePartitionTree(CUFRpartitionTree.PartitionTreeNode node) {
        assert(node != null);
        if (node.left == null && node.right == null) {
            assert(node.middle == null);
            super.routeIndirectConnections(node.connections);
        } else {
            assert(node.left != null && node.right != null);
            if (node.middle != null) {
                routePartitionTree(node.middle);
            }

            ParallelismTools.invokeAll(
                    () -> routePartitionTree(node.left),
                    () -> routePartitionTree(node.right)
            );
        }
    }

    @Override
    protected void routeIndirectConnections(Collection<Connection> connections) {
        boolean firstIteration = (routeIteration == 1);
        if (firstIteration || config.isEnlargeBoundingBox() || needsRepartitioning) {
            partitionTimer.start();
            partitionTree = new CUFRpartitionTree(sortedIndirectConnections, design.getDevice().getColumns(), design.getDevice().getRows());
            partitionTimer.stop();
            needsRepartitioning = false;
        }

        routePartitionTree(partitionTree.root);
    }

    @Override
    protected void unpreserveNet(Net net) {
        super.unpreserveNet(net);
        needsRepartitioning = true;
    }

    @Override
    protected void printRoutingStatistics() {
        routerTimer.getRuntimeTracker("route wire nets").addChild(partitionTimer);
        RuntimeTracker routeConnectionsTimer = routerTimer.getRuntimeTracker("route connections");
        routeConnectionsTimer.setTime(routeConnectionsTimer.getTime() - partitionTimer.getTime());
        super.printRoutingStatistics();
    }

    /**
     * Partially routes a {@link Design} instance; specifically, all nets with no routing PIPs already present.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design, String[] args) {
        boolean softPreserve = false;
        List<SitePinInst> pinsToRoute = null;

        // Instantiates a RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        return routeDesignWithUserDefinedArguments(design, args, pinsToRoute, softPreserve);
    }

    /**
     * Partially routes a {@link Design} instance; specifically, all nets with no routing PIPs already present.
     * @param design The {@link Design} instance to be routed.
     * @param args An array of string arguments, can be null.
     * If null, the design will be routed in the full timing-driven routing mode with default a {@link RWRouteConfig} instance.
     * For more options of the configuration, please refer to the {@link RWRouteConfig} class.
     * @param pinsToRoute Collection of {@link SitePinInst}-s to be routed. If null, route all unrouted pins in the design.
     * @param softPreserve Allow routed nets to be unrouted and subsequently rerouted in order to improve routability.
     * @return Routed design.
     */
    public static Design routeDesignWithUserDefinedArguments(Design design,
                                                             String[] args,
                                                             Collection<SitePinInst> pinsToRoute,
                                                             boolean softPreserve) {
        // Instantiates a RWRouteConfig Object and parses the arguments.
        // Uses the default configuration if basic usage only.
        RWRouteConfig config = new RWRouteConfig(args);
        if (pinsToRoute == null) {
            preprocess(design);
            pinsToRoute = getUnroutedPins(design);
        }

        if (config.isMaskNodesCrossRCLK()) {
            System.out.println("WARNING: Masking nodes across RCLK for partial routing could result in routability problems.");
        }

        return routeDesign(design, new PartialCUFR(design, config, pinsToRoute, softPreserve));
    }

    /**
     * The main interface of {@link PartialCUFR} that reads in a {@link Design} design
     * (DCP or FPGA Interchange), and parses the arguments for the
     * {@link RWRouteConfig} object of the router.
     *
     * @param args An array of strings that are used to create a
     *             {@link RWRouteConfig} object for the router.
     */
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("USAGE: <input.dcp|input.phys> <output.dcp>");
            return;
        }
        // Reads the output directory and set the output design checkpoint file name
        String routedDCPfileName = args[1];

        CodePerfTracker t = new CodePerfTracker("PartialCUFR", true);

        // Reads in a design and routes it
        String[] rwrouteArgs = Arrays.copyOfRange(args, 2, args.length);
        Design input = null;
        if (Interchange.isInterchangeFile(args[0])) {
            input = Interchange.readInterchangeDesign(args[0]);
        } else {
            input = Design.readCheckpoint(args[0]);
        }
        Design routed = routeDesignWithUserDefinedArguments(input, rwrouteArgs);

        // Writes out the routed design checkpoint
        routed.writeCheckpoint(routedDCPfileName,t);
        System.out.println("\nINFO: Wrote routed design\n " + routedDCPfileName + "\n");
    }
}
