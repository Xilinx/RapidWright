/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.delayestimator;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.Part;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.timing.family.SpeedGrade;
import com.xilinx.rapidwright.timing.family.TimingFamilies;
import com.xilinx.rapidwright.timing.family.TimingFamily;

/**
 * Estimates the delay of routing resources for timing-driven algorithms such as RWRoute.
 * A route delay is the sum, over the nodes of a route, of {@link #getDelayOf(Node)} for the nodes
 * that are charged ({@link #isDelayCharged(Node)}) plus {@link #getExtraDelay(Node, Node)} for each
 * driver to node hop. Use {@link #create(Device, boolean)} to get the estimator of a device.
 */
public interface DelayEstimator {

    /**
     * Creates the delay estimator for a device, see
     * {@link TimingFamily#createDelayEstimator(Device, SpeedGrade, Corner, boolean)}.
     * @param device The target device.
     * @param useUTurnNodes Whether nodes that turn back towards their driver are allowed; when not,
     *                      they are given a prohibitive delay.
     * @return The delay estimator of the device's series.
     */
    static DelayEstimator create(Device device, boolean useUTurnNodes) {
        return create(device, null, useUTurnNodes);
    }

    /**
     * Creates the delay estimator for the part of a design, accounting for its speed grade where the
     * device's delay data supports it.
     * @param design The design in question.
     * @param useUTurnNodes Whether nodes that turn back towards their driver are allowed; when not,
     *                      they are given a prohibitive delay.
     * @return The delay estimator of the design's part.
     */
    static DelayEstimator create(Design design, boolean useUTurnNodes) {
        TimingFamily family = TimingFamilies.of(design.getDevice());
        SpeedGrade speedGrade = null;
        String partName = design.getPartName();
        if (partName != null && !partName.isEmpty()) {
            Part part = PartNameTools.getPart(partName);
            speedGrade = part == null ? null : family.resolveSpeedGrade(part);
        }
        return family.createDelayEstimator(design.getDevice(), speedGrade, Corner.SLOW_MAX, useUTurnNodes);
    }

    /**
     * Creates the delay estimator for a device and speed grade.
     * @param device The target device.
     * @param speedGrade The speed grade, e.g. -2MP, or null for the grade the device's delay data is for.
     * @param useUTurnNodes Whether nodes that turn back towards their driver are allowed; when not,
     *                      they are given a prohibitive delay.
     * @return The delay estimator of the device's series.
     */
    static DelayEstimator create(Device device, String speedGrade, boolean useUTurnNodes) {
        return TimingFamilies.of(device).createDelayEstimator(device, SpeedGrade.of(speedGrade), Corner.SLOW_MAX,
                useUTurnNodes);
    }

    /**
     * Gets the delay of a node, including the delay of the PIP driving it.
     * Only meaningful for nodes for which {@link #isDelayCharged(Node)} returns true.
     * @param node The node in question.
     * @return The delay in ps.
     */
    short getDelayOf(Node node);

    /**
     * Checks if a node carries a delay of its own in this model.
     * @param node The node in question.
     * @return True, if {@link #getDelayOf(Node)} should be charged for the node.
     */
    boolean isDelayCharged(Node node);

    /**
     * Checks if a node belongs to the inter-site routing whose delay this model describes, as opposed
     * to nodes of interface or site tiles that a route may pass through to reach a site pin.
     * @param node The node in question.
     * @return True, if the node's delay is part of a route delay.
     */
    default boolean isInterconnectNode(Node node) {
        return node.getTile().getTileTypeEnum() == TileTypeEnum.INT;
    }

    /**
     * Gets the delay that a node incurs on top of its own delay because of the node driving it.
     * @param parent The node driving the child.
     * @param child The node in question.
     * @return The extra delay in ps.
     */
    short getExtraDelay(Node parent, Node child);

    /**
     * Gets the class of a node that {@link #getExtraDelay(int, int)} looks the extra delay up by, so
     * that callers can cache it per node.
     * @param node The node in question.
     * @return Its class, from 0 to 127, or -1 if this model has no such classes, in which case
     * {@link #getExtraDelay(Node, Node)} is to be used.
     */
    default int getExtraDelayClass(Node node) {
        return -1;
    }

    /**
     * Gets the extra delay of a node driven by another (see {@link #getExtraDelay(Node, Node)}) from
     * their classes (see {@link #getExtraDelayClass(Node)}), with a table lookup.
     * @param parentClass The class of the node driving the child.
     * @param childClass The class of the node in question.
     * @return The extra delay in ps, the same as {@link #getExtraDelay(Node, Node)} for nodes of
     * those classes.
     */
    default short getExtraDelay(int parentClass, int childClass) {
        throw new UnsupportedOperationException("No extra delay classes in " + getClass().getSimpleName());
    }

    /**
     * Gets the least extra delay ({@link #getExtraDelay(Node, Node)}) of a node of a class driven by any node: a
     * lower bound of it for callers that do not know a node's driver, e.g. the lower bounds of a router's search.
     * @param nodeClass The class of the node (see {@link #getExtraDelayClass(Node)}), or -1 for any node.
     * @return The extra delay in ps, which may be negative; 0 by default, for models whose extra delays are never
     * negative (models with negative extra delays must override this).
     */
    default short getLeastExtraDelay(int nodeClass) {
        return 0;
    }

    /**
     * Gets the delay of a node (see {@link #getDelayOf(Node)}) given the wires of the node, which
     * spares the estimator from getting them again when the caller already has them.
     * @param node The node in question.
     * @param wires All the wires of the node, as {@link Node#getAllWiresInNode()} returns them.
     * @return The delay in ps, the same as {@link #getDelayOf(Node)}.
     */
    default short getDelayOf(Node node, Wire[] wires) {
        return getDelayOf(node);
    }

    /**
     * Checks if this estimator masks the nodes that turn back towards their driver (U-turn nodes at the
     * device's edges), giving them a delay of {@code Short.MAX_VALUE / 2} that routers exclude, as when
     * created with {@code useUTurnNodes} false.
     * @return True if it does; such delays are then no timing estimates.
     */
    default boolean masksUTurnNodes() {
        return false;
    }

    /**
     * Gets the delay of a node as {@link #getDelayOf(Node)}, except for a node that this estimator masks (see
     * {@link #masksUTurnNodes()}): its delay as if it did not mask it, e.g. to time an existing route that uses it.
     * @param node The node in question.
     * @return The delay in ps.
     */
    default short getUnmaskedDelayOf(Node node) {
        return getDelayOf(node);
    }

    /**
     * Checks if this model has fanout delays, see {@link #getFanoutDelay(Node, int)}.
     * @return True, if the delay of a node depends on how many nodes of its net it drives.
     */
    default boolean hasFanoutDelays() {
        return false;
    }

    /**
     * Gets the delay that the branches of a net's routing add to the node they branch from, which
     * all connections routed through that node incur.
     * @param node The node in question.
     * @param numBranches The number of nodes of its net that it drives.
     * @return The delay in ps.
     */
    default float getFanoutDelay(Node node, int numBranches) {
        return 0;
    }

    /**
     * Gets the delay that the loads of a net's routing below a node add to that node: the more sink
     * pins a node leads to, the slower it is. All connections routed through the node incur it; it adds
     * to {@link #getFanoutDelay(Node, int)} and is part of the fanout delays (see {@link #hasFanoutDelays()}).
     * @param node The node in question.
     * @param numLoads The number of sink pins of its net that its net's routing reaches through it (its own,
     *                 if it is the node of one, included).
     * @return The delay in ps, 0 for a node that leads to a single sink pin.
     */
    default float getFanoutLoadDelay(Node node, int numLoads) {
        return 0;
    }

    /**
     * Gets the delay that a connection adds, on top of the delays of the nodes of its route, for its
     * source and sink site pins: e.g. the last hop into a slice input pin, which differs per pin where
     * the pin shares its node with the interconnect. A connection's route delay is the sum of the
     * delays of its nodes ({@link #getDelayOf(Node)}, {@link #getExtraDelay(Node, Node)} and fanout
     * delays) plus this delay.
     * @param source The source site pin of the connection, or null if unknown.
     * @param sink The sink site pin of the connection, or null if unknown.
     * @return The delay in ps, which may be negative; 0 for models without pin delays.
     */
    default short getPinDelay(SitePinInst source, SitePinInst sink) {
        return 0;
    }

    /**
     * Gets the delay that a connection adds for its source and sink site pins, as
     * {@link #getPinDelay(SitePinInst, SitePinInst)}, with the sink given by its site and pin name: e.g. a
     * candidate LUT input pin of LUT pin swapping, which has no site pin instance.
     * @param source The source site pin of the connection, or null if unknown.
     * @param sinkSite The site of the sink pin.
     * @param sinkPinName The name of the sink pin in the site's primary type.
     * @return The delay in ps, which may be negative; 0 for models without pin delays.
     */
    default short getPinDelay(SitePinInst source, Site sinkSite, String sinkPinName) {
        return getPinDelay(source, null);
    }

    /**
     * Gets the delay per tile of horizontal distance of the pre-route estimate of a connection's delay
     * (the delay that the timing analysis assumes for a connection that is not routed yet): an estimate
     * of a typical route's delay, which may exceed that of a route (it is not a lower bound). A router's
     * lookahead takes {@link #getAdmissibleLookaheadDelayPerTileX()} instead.
     * @return The delay in units of 100 ps per INT tile.
     */
    float getLookaheadDelayPerTileX();

    /**
     * Gets the delay per tile of vertical distance of the pre-route estimate of a connection's delay: an
     * estimate, not a lower bound (see {@link #getLookaheadDelayPerTileX()}). A router's lookahead takes
     * {@link #getAdmissibleLookaheadDelayPerTileY()} instead.
     * @return The delay in units of 100 ps per INT tile.
     */
    float getLookaheadDelayPerTileY();

    /**
     * Gets the largest delay per tile of horizontal distance that never exceeds a route's, if known: e.g.
     * that of RWRoute's lookahead, which keeps its search's estimate a lower bound.
     * @return The delay in units of 100 ps per INT tile, NaN if unknown.
     */
    default float getAdmissibleLookaheadDelayPerTileX() {
        return Float.NaN;
    }

    /**
     * Gets the largest delay per tile of vertical distance that never exceeds a route's, if known.
     * @return The delay in units of 100 ps per INT tile, NaN if unknown.
     */
    default float getAdmissibleLookaheadDelayPerTileY() {
        return Float.NaN;
    }

    /**
     * Gets the delay assumed from a source to its first routing node, used to estimate connection
     * delays before routing.
     * @return The delay in ps.
     */
    short getPreRouteBaseDelay();
}
