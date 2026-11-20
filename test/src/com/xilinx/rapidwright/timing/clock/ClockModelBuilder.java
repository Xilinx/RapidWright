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

package com.xilinx.rapidwright.timing.clock;

import com.xilinx.rapidwright.device.Series;

/**
 * Builds clock models by hand for the tests of other packages (e.g. those of the timing analysis): a tree of clock
 * nodes with their delays per corner, and the clock pins' nodes in it; and the clock node delay model of a series
 * with the default delay parameters.
 */
public final class ClockModelBuilder {

    /** The parent of a root node */
    public static final int NO_PARENT = ClockTree.NO_PARENT;

    private final ClockTree tree = new ClockTree();

    /**
     * Adds a node to the tree.
     * @param parentNode Its parent (added before), or {@link #NO_PARENT} for a root.
     * @param delays Its delay from its parent per corner ordinal, or null for 0.
     * @return Its id.
     */
    public int addNode(int parentNode, float[] delays) {
        return tree.addNode(parentNode, delays);
    }

    /**
     * Builds the model of the tree.
     * @param clockPinNodes The tree node of each clock pin (-1 for an ideal clock pin: no insertion).
     * @return The model.
     */
    public ClockModel build(int[] clockPinNodes) {
        return ClockModel.of(tree, clockPinNodes);
    }

    /**
     * Builds the model of the tree, with the SLR of each clock pin.
     * @param clockPinNodes The tree node of each clock pin (-1 for an ideal clock pin: no insertion).
     * @param clockPinSlrs The SLR index of each clock pin (-1 if unknown).
     * @return The model.
     */
    public ClockModel build(int[] clockPinNodes, int[] clockPinSlrs) {
        return ClockModel.of(tree, clockPinNodes, clockPinSlrs);
    }

    /**
     * Sets the tree node of a clock pin for a clock that reaches it by another path than the pin's (e.g. through
     * another input of a clock mux).
     * @param model The model.
     * @param clockPin The clock pin.
     * @param clock The clock.
     * @param node The tree node of the clock's path to the pin.
     */
    public static void setClockPathNode(ClockModel model, int clockPin, int clock, int node) {
        model.setClockPathNode(clockPin, clock, node);
    }

    /**
     * Creates the clock node delay model of a series with the default delay parameters.
     * @param series The series, for the classification of the clock nodes.
     * @return The model.
     */
    public static ClockNodeDelayModel nodeModel(Series series) {
        return new ClockNodeDelayModel(ClockNodeClassifier.forSeries(series), new ClockDelayParams());
    }

    /**
     * Creates the clock node delay model of a series with the default delay parameters and the delay of a buffer.
     * @param series The series, for the classification of the clock nodes.
     * @param bufferKey The buffer, see ClockDelayParams.setBufferDelay() (e.g. BUFGCE).
     * @param bufferDelays Its delays per corner ordinal.
     * @return The model.
     */
    public static ClockNodeDelayModel nodeModel(Series series, String bufferKey, float[] bufferDelays) {
        ClockDelayParams params = new ClockDelayParams();
        params.setBufferDelay(bufferKey, bufferDelays);
        return new ClockNodeDelayModel(ClockNodeClassifier.forSeries(series), params);
    }
}
