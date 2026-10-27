/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.sdf;

/**
 * Options controlling how {@link SdfAnnotator} maps SDF delays onto a
 * {@code com.xilinx.rapidwright.timing.TimingGraph}.
 *
 * The defaults reproduce a worst-case setup analysis, which is what a slow-corner SDF is normally
 * written for: the maximum of each {@code min:typ:max} triple, and the worst transition of each
 * delay value list.
 */
public class SdfAnnotationConfig {

    /** Which component of a {@code min:typ:max} triple to use. */
    public enum Corner {
        /** The first component. */
        MIN,
        /** The second component. */
        TYP,
        /** The third component. */
        MAX
    }

    /**
     * Which entry of a delay value list to use.
     *
     * Vivado writes two values for most arcs, in the order rise then fall, and six for a tri-state
     * output using SDF's standard 01, 10, 0Z, Z1, 1Z, Z0 ordering. Only the first two have a
     * transition meaning assigned here; the high-impedance transitions are reachable only through
     * {@link #WORST} and {@link #AVERAGE}.
     */
    public enum Transition {
        /** The rising-output value. */
        RISE,
        /** The falling-output value. */
        FALL,
        /** The largest value present, which is the safe choice for setup analysis. */
        WORST,
        /** The mean of the values present. */
        AVERAGE
    }

    private Corner corner = Corner.MAX;

    private Transition transition = Transition.WORST;

    private boolean createMissingLogicEdges = true;

    private boolean annotateSequentialArcs = true;

    private int sampleLimit = 20;

    private boolean warnWhenNotClean = true;

    /**
     * @return Which component of a triple is used.
     */
    public Corner getCorner() {
        return corner;
    }

    /**
     * @param corner Which component of a triple to use.
     * @return This config, for chaining.
     */
    public SdfAnnotationConfig setCorner(Corner corner) {
        this.corner = corner;
        return this;
    }

    /**
     * @return Which entry of a delay value list is used.
     */
    public Transition getTransition() {
        return transition;
    }

    /**
     * @param transition Which entry of a delay value list to use.
     * @return This config, for chaining.
     */
    public SdfAnnotationConfig setTransition(Transition transition) {
        this.transition = transition;
        return this;
    }

    /**
     * @return Whether an IOPATH whose two pins exist but which has no edge between them causes one
     *         to be created.
     */
    public boolean getCreateMissingLogicEdges() {
        return createMissingLogicEdges;
    }

    /**
     * Sets whether to create an edge for an IOPATH the graph does not already have.
     *
     * RapidWright only builds cell-internal arcs for LUTs, carries and block RAMs, so an SDF for a
     * design using anything else describes arcs the graph has no edge for. Leaving this on is
     * usually right: with it off, those delays are parsed and then discarded.
     *
     * @param createMissingLogicEdges True to create the missing edges.
     * @return This config, for chaining.
     */
    public SdfAnnotationConfig setCreateMissingLogicEdges(boolean createMissingLogicEdges) {
        this.createMissingLogicEdges = createMissingLogicEdges;
        return this;
    }

    /**
     * @return Whether a clock-to-output IOPATH is applied to the net edges leaving that output.
     */
    public boolean getAnnotateSequentialArcs() {
        return annotateSequentialArcs;
    }

    /**
     * Sets whether a sequential IOPATH such as a flip-flop's clock-to-Q is applied.
     *
     * The graph deliberately omits clock nets, so there is usually no vertex for a clock pin and no
     * edge to carry the arc. RapidWright instead accounts for clock-to-output as the logic delay of
     * the net edges leaving the output pin, and that is where the SDF value is applied. Turning
     * this off leaves clock-to-output delay out of the annotated graph entirely.
     *
     * @param annotateSequentialArcs True to apply sequential arcs to outgoing net edges.
     * @return This config, for chaining.
     */
    public SdfAnnotationConfig setAnnotateSequentialArcs(boolean annotateSequentialArcs) {
        this.annotateSequentialArcs = annotateSequentialArcs;
        return this;
    }

    /**
     * @return How many example names are retained per diagnostic category.
     */
    public int getSampleLimit() {
        return sampleLimit;
    }

    /**
     * @param sampleLimit How many example names to retain per diagnostic category.
     * @return This config, for chaining.
     */
    public SdfAnnotationConfig setSampleLimit(int sampleLimit) {
        this.sampleLimit = sampleLimit;
        return this;
    }

    /**
     * @return Whether a warning is printed when annotation finishes with unresolved entries.
     */
    public boolean getWarnWhenNotClean() {
        return warnWhenNotClean;
    }

    /**
     * @param warnWhenNotClean False to suppress the warning printed when annotation is not clean.
     * @return This config, for chaining.
     */
    public SdfAnnotationConfig setWarnWhenNotClean(boolean warnWhenNotClean) {
        this.warnWhenNotClean = warnWhenNotClean;
        return this;
    }
}
