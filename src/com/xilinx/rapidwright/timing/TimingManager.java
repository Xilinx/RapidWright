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

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;

/**
 * A TimingManager sets up and creates an example TimingModel and an example TimingGraph for a given
 * Design.
 */
public class TimingManager {

    private TimingModel timingModel;
    private TimingGraph timingGraph;
    private Design design;
    private Device device;

    public static final int BUILD_GRAPH_PATHS_DEFAULT_PARAM = 1; // use 0 instead for all paths

    /**
     * Default constructor: creates the TimingManager object, which the user needs to create for 
     * using our TimingModel, and then it builds the model.
     * @param design RapidWright Design object.
     */
    public TimingManager(Design design) {
        this(design, true);
    }

    /**
     * Alternate constructor for creating the objects for the TimingModel, but with the choice to 
     * not build the model yet.
     * @param design RapidWright Design object.
     * @param doBuild Whether to go ahead and build the model now.  For example, a user might not 
     * want to build the TimingGraph yet.
     */
    public TimingManager(Design design, boolean doBuild) {
        timingModel = new TimingModel(design);
        timingGraph = new TimingGraph(design);
        timingModel.setTimingManager(this);
        timingGraph.setTimingManager(this);
        timingGraph.setTimingModel(timingModel);
        this.design = design;
        this.device = design.getDevice();
        if (doBuild)
            build();
    }

    /**
     * Builds the TimingModel and TimingGraph.
     * @return Indication of successful completion.
     */
    public boolean build() {
        timingModel.build();
        timingGraph.build();
        return postBuild();
    }

    private boolean postBuild() {
        timingGraph.removeClockCrossingPaths();
        timingGraph.buildGraphPaths(BUILD_GRAPH_PATHS_DEFAULT_PARAM);
        timingGraph.computeArrivalTimes();
        timingGraph.computeSlacks();
        return true;
    }

    /**
     * Gets the TimingGraph object.
     * @return TimingGraph
     */
    public TimingGraph getTimingGraph() {
        return timingGraph;
    }

    /**
     * Gets the TimingModel object.
     * @return TimingModel
     */
    public TimingModel getTimingModel() {
        return timingModel;
    }

    /**
     * Gets the corresponding design used in creating this TimingManager.
     * @return Corresponding design used in creating this TimingManager.
     */
    public Design getDesign() {
        return design;
    }
    
    /**
     * Gets the corresponding device used in creating this TimingManager.
     * @return Corresponding device used in creating this TimingManager.
     */
    public Device getDevice() {
        return device;
    }
}
