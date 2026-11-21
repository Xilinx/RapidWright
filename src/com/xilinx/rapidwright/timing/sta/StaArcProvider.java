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

package com.xilinx.rapidwright.timing.sta;

import java.util.List;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * Gives {@link StaGraphBuilder} the timing arcs of the cells and the delays of the nets of a
 * design: the seam between the timing graph and the timing model, which {@link FamilyArcProvider}
 * implements with the fitted cell arcs and the delay estimator of the device's timing family. The
 * graph's tests in this package build small graphs with {@link StaGraphAssembler} instead.
 */
interface StaArcProvider {

    /**
     * Adds the timing arcs of a leaf cell, through {@link CellArcs}. Called once per placed leaf
     * cell of the design, possibly from several threads at once.
     * @param cell The cell, and the means to add its arcs.
     */
    void addCellArcs(CellArcs cell);

    /**
     * Gets the delay of an edge of a net, from its source pin to one of its sink pins.
     * @param arc The edge: its pins are set on input, its delays are to be set on output.
     * @return False to leave the edge out of the graph.
     */
    boolean getNetArc(NetArc arc);

    /**
     * Whether the provider gives the delays of a second min corner (e.g. SLOW_MIN, for the slow-corner hold
     * analysis): through {@link CellArcs#addArc(int, int, byte, float, float, float)} and
     * {@link NetArc#fixedMin2}.
     */
    boolean hasSecondMinCorner();

    /**
     * The cell whose arcs {@link StaArcProvider#addCellArcs(CellArcs)} is to add, and the means to
     * add them. Pins are referred to by the index of their port instance in the cell's sorted list
     * ({@link #getPortInsts()}), or by the (negative) handle of a pseudo pin from
     * {@link #getPseudoPin(String)}.
     */
    interface CellArcs {
        /** The physical cell */
        Cell getCell();

        /** The hierarchical cell instance */
        EDIFHierCellInst getHierCellInst();

        /** The cell instance */
        EDIFCellInst getCellInst();

        /** The hierarchical name of the cell (built on demand) */
        String getName();

        /** The port instances of the cell, sorted by name */
        List<EDIFPortInst> getPortInsts();

        /**
         * Finds a port instance by name.
         * @param portName Name of the port instance (e.g. "I0" or "DIN[3]").
         * @return Its index in {@link #getPortInsts()}, or -1 if the cell has none by that name.
         */
        int getPortIndex(String portName);

        /**
         * Gets (creating it the first time) a pseudo pin of the cell, which has no port instance,
         * such as a hub joining an input bus to an output bus.
         * @param name Name of the pseudo pin, unique within the cell.
         * @return Its handle, a negative number.
         */
        int getPseudoPin(String name);

        /**
         * Adds a timing arc.
         * @param from Port index or pseudo pin handle of its source pin.
         * @param to Port index or pseudo pin handle of its sink pin.
         * @param kind Its kind: {@link StaGraph#CELL_COMB} or {@link StaGraph#CLK_TO_Q}.
         * @param delayMax Its delay at the max corner, in ps.
         * @param delayMin Its delay at the min corner, in ps.
         */
        void addArc(int from, int to, byte kind, float delayMax, float delayMin);

        /**
         * Adds a timing arc with its delay at the second min corner too (see
         * {@link StaArcProvider#hasSecondMinCorner()}).
         * @param from Port index or pseudo pin handle of its source pin.
         * @param to Port index or pseudo pin handle of its sink pin.
         * @param kind Its kind: {@link StaGraph#CELL_COMB} or {@link StaGraph#CLK_TO_Q}.
         * @param delayMax Its delay at the max corner, in ps.
         * @param delayMin Its delay at the min corner, in ps.
         * @param delayMin2 Its delay at the second min corner, in ps.
         */
        default void addArc(int from, int to, byte kind, float delayMax, float delayMin, float delayMin2) {
            addArc(from, to, kind, delayMax, delayMin);
        }

        /**
         * Marks a pin of the cell as a timing endpoint if, once the cycles of the graph are cut, it
         * has in-edges but no out-edges, and if it had no out-edges before (see
         * {@link StaGraphAssembler#markEndpointIfNoFanout(int)}).
         * @param pin Port index or pseudo pin handle; the pin is only created if it has edges.
         */
        void markEndpointIfNoFanout(int pin);

        /**
         * Marks a pin of the cell as a timing endpoint, whatever its out-edges, unless it is a
         * startpoint (see {@link StaGraphAssembler#forceEndpoint(int)}).
         * @param pin Port index or pseudo pin handle; the pin is only created if it has edges.
         */
        void forceEndpoint(int pin);

        /**
         * Marks a pin of the cell as a timing endpoint (see {@link #forceEndpoint(int)}) checked against a
         * clock port of the cell, e.g. a hard block's interface data pin against its interface clock.
         * @param pin Port index or pseudo pin handle.
         * @param clockPort Name of the clock port instance of the check.
         */
        default void forceEndpoint(int pin, String clockPort) {
            forceEndpoint(pin);
        }
    }

    /**
     * An edge of a net, from its source pin to one of its sink pins: the pins, set by the builder,
     * and the delays, to be set by {@link StaArcProvider#getNetArc(NetArc)}.
     */
    final class NetArc {
        /** The net */
        Net net;
        /** Whether the net has no sink site pin, i.e. does not leave its site */
        boolean intraSiteNet;
        /** The source pin */
        EDIFHierPortInst sourcePin;
        /** The source cell */
        Cell sourceCell;
        /** The BEL pin of the source pin, or null */
        BELPin sourceBELPin;
        /** The source site pin of the net (that of a source pin, or the net's), or null */
        SitePinInst sourceSitePin;
        /** The sink pin */
        EDIFHierPortInst sinkPin;
        /** The sink cell */
        Cell sinkCell;
        /** The BEL pin of the sink pin, or null */
        BELPin sinkBELPin;
        /** The sink site pin that the sink pin is routed from, or null if none */
        SitePinInst sinkSitePin;

        /** Output: the fixed delay at the max corner (logic and intra-site delays), in ps */
        float fixedMax;
        /** Output: the fixed delay at the min corner, in ps */
        float fixedMin;
        /** Output: the fixed delay at the second min corner, in ps (NaN: that of the min corner) */
        float fixedMin2;
        /** Output: the intra-site part of fixedMax, for reports */
        float intraSiteMax;
        /** Output: the route delay at the max corner, if the edge has a sink site pin, in ps */
        float routeMax;
        /** Output: the route delay at the min corner, if the edge has a sink site pin, in ps */
        float routeMin;
        /** Output: the route delay at the second min corner, in ps (NaN: that of the min corner) */
        float routeMin2;

        /**
         * The provider's own state between the arcs it is given in this object (e.g. the routing of the net
         * it last looked at), null at first
         */
        Object scratch;

        /** Clears the outputs. */
        void clearDelays() {
            fixedMax = 0;
            fixedMin = 0;
            fixedMin2 = Float.NaN;
            intraSiteMax = 0;
            routeMax = 0;
            routeMin = 0;
            routeMin2 = Float.NaN;
        }
    }
}
