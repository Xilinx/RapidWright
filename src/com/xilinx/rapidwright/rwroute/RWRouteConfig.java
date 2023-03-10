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

import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * A collection of customizable parameters for a {@link RWRoute} Object or a {@link TimingAndWirelengthReport} Object.
 * Modifications of default parameter values can be done by adding corresponding options with values to the arguments.
 * Each option (i.e. one of the parameters) name must start with two dashes. Values of parameters do not need dashes.
 */
public class RWRouteConfig {
    /** Allowed max number of routing iterations */
    private short maxIterations;
    /** Routing bounding box constraint */
    private boolean useBoundingBox;
    /** Initial bounding box extension range to the left and right */
    private short boundingBoxExtensionX;
    /** Initial bounding box extension range to the top and bottom */
    private short boundingBoxExtensionY;
    /** Further enlarge the bounding box along with routing iterations by the extension X and Y increments */
    private boolean enlargeBoundingBox;
    /** Incremental extension of the bounding box in the vertical direction to the top and bottom */
    private short extensionYIncrement;
    /** Incremental extension of the bounding box in the horizontal direction to the left and right */
    private short extensionXIncrement;
    /** Wirelength-driven weighting factor */
    private float wirelengthWeight;
    /** Timing-driven weighting factor */
    private float timingWeight;
    /** The multiplier for timing weight update along iterations */
    private float timingMultiplier;
    /** The sharing exponent for calculating criticality-aware sharing factors */
    private float shareExponent;
    /** The exponent for criticality calculation */
    private float criticalityExponent;
    /** The threshold for determining critical connections to be rerouted */
    private float minRerouteCriticality;
    /** The maximum percentage of critical connections to be rerouted */
    private short reroutePercentage;
    /** Initial present congestion penalty factor */
    private float initialPresentCongestionFactor;
    /** The multiplier factor for present congestion update */
    private float presentCongestionMultiplier;
    /** Historical congestion penalty factor */
    private float historicalCongestionFactor;
    /** true to enable timing-aware routing */
    private boolean timingDriven;
    /** The directory contains DSP timing data files for the target design */
    private String dspTimingDataFolder;
    /** The text file containing clock enable partial route and timing data */
    private String clkRouteTiming;
    /** Pessimism factor A for timing closure guarantee */
    private float pessimismA;
    /** Pessimism factor B for timing closure guarantee */
    private short pessimismB;
    /** true to prevent usage of some nodes across RCLK row */
    private boolean maskNodesCrossRCLK;
    /** true to allow possible usage of U-turn nodes at the device boundaries*/
    private boolean useUTurnNodes;
    /** true to display more info along the routing process */
    private boolean verbose;
    /** true to display connection span statistics */
    private boolean printConnectionSpan;

    /** Constructs a Configuration Object */
    public RWRouteConfig(String[] arguments) {
        maxIterations = (short) 100;
        useBoundingBox = true;
        boundingBoxExtensionX = (short) 3;
        boundingBoxExtensionY = (short) 15;
        enlargeBoundingBox = false;
        extensionYIncrement = 2;
        extensionXIncrement = 1;
        wirelengthWeight = 0.8f;
        timingWeight = 0.35f;
        timingMultiplier = 1f;
        shareExponent = 2;
        criticalityExponent = 3;
        minRerouteCriticality = 0.85f;
        reroutePercentage = (short) 3;
        initialPresentCongestionFactor = 0.5f;
        presentCongestionMultiplier = 2f;
        historicalCongestionFactor = 1f;
        timingDriven = true;
        clkRouteTiming = null;
        pessimismA = 1.03f;
        pessimismB = (short) 100;
        maskNodesCrossRCLK = false;
        useUTurnNodes = false;
        verbose = false;
        printConnectionSpan = false;
        if (arguments != null) {
            parseArguments(arguments);
        }
    }

    private void parseArguments(String[] arguments) {
        for (int i = 0; i < arguments.length; i++) {
            String arg = arguments[i];
            switch(arg) {
            case "--maxIterations":
                setMaxIterations(Short.parseShort(arguments[++i]));
                break;
            case "--noBoundingBox":
                setUseBoundingBox(false);
                break;
            case "--boundingBoxExtensionX":
                setBoundingBoxExtensionX(Short.parseShort(arguments[++i]));
                break;
            case "--boundingBoxExtensionY":
                setBoundingBoxExtensionY(Short.parseShort(arguments[++i]));
                break;
            case "--enlargeBoundingBox":
                setEnlargeBoundingBox(true);
                break;
            case "--fixBoundingBox":
                setEnlargeBoundingBox(false);
                break;
            case "--extensionYIncrement":
                setExtensionYIncrement(Short.parseShort(arguments[++i]));
                break;
            case "--extensionXIncrement":
                setExtensionXIncrement(Short.parseShort(arguments[++i]));
                break;
            case "--wirelengthWeight":
                setWirelengthWeight(Float.parseFloat(arguments[++i]));
                break;
            case "--shareExponent":
                setShareExponent(Float.parseFloat(arguments[++i]));
                break;
            case "--timingWeight":
                setTimingWeight(Float.parseFloat(arguments[++i]));
                break;
            case "--timingMultiplier":
                setTimingMultiplier(Float.parseFloat(arguments[++i]));
                break;
            case "--criticalityExponent":
                setCriticalityExponent(Float.parseFloat(arguments[++i]));
                break;
            case "--minRerouteCriticality":
                setMinRerouteCriticality(Float.parseFloat(arguments[++i]));
                break;
            case "--reroutePercentage":
                setReroutePercentage(Short.parseShort(arguments[++i]));
                break;
            case "--initialPresentCongestionFactor":
                setInitialPresentCongestionFactor(Float.parseFloat(arguments[++i]));
                break;
            case "--presentCongestionMultiplier":
                setPresentCongestionMultiplier(Float.parseFloat(arguments[++i]));
                break;
            case "--historicalCongestionFactor":
                setHistoricalCongestionFactor(Float.parseFloat(arguments[++i]));
                break;
            case "--timingDriven":
                setTimingDriven(true);
                break;
            case "--nonTimingDriven":
                setTimingDriven(false);
                break;
            case "--dspTimingDataFolder":
                setDspTimingDataFolder(arguments[++i]);
                break;
            case "--clkRouteTiming":
                setClkRouteTiming(arguments[++i]);
                break;
            case "--pessimismA":
                setPessimismA(Float.parseFloat(arguments[++i]));
                break;
            case "--pessimismB":
                setPessimismB(Short.parseShort(arguments[++i]));
                break;
            case "--maskNodesCrossRCLK":
                setMaskNodesCrossRCLK(true);
                break;
            case "--maskUTurnNodes":
                setUseUTurnNodes(false);
                break;
            case "--useUTurnNodes":
                setUseUTurnNodes(true);
                break;
            case "--verbose":
                setVerbose(true);
                break;
            case "--printConnectionSpan":
                setPrintConnectionSpan(true);
                break;
            default:
                throw new IllegalArgumentException("ERROR: RWRoute argument '" + arg + "' not recognized.");
            }
        }
    }

    /**
     * Gets the allowed maximum number of routing iterations.
     * Default: 100
     * @return The allowed maximum number of routing iterations.
     */
    public short getMaxIterations() {
        return maxIterations;
    }

    /**
     * Sets the maximum number of routing iterations.
     * Default: 100. 100 iterations should be enough for most routing cases.
     * Otherwise, it is not promising that a design can be successfully routed.
     * To modify the value, please use "--maxIterations" option, e.g. "--maxIterations 60".
     * @param maxIterations The maximum number of routing iterations.
     */
    public void setMaxIterations(short maxIterations) {
        this.maxIterations = maxIterations;
    }

    /**
     * Checks if the routing bounding box constraint is used.
     * Default: true. To disable the bounding box constraint, please add "--noBoundingBox" to the arguments.
     * @return true, if the routing bounding box constraint is used.
     */
    public boolean isUseBoundingBox() {
        return useBoundingBox;
    }

    /**
     * Sets if the router should route connections with the routing bounding box constraint.
     * Default: true. To disable the bounding box constraint, please add "--noBoundingBox" option to the arguments.
     * @param useBoundingBox true to let the router use the bounding box constraint to route connections.
     */
    public void setUseBoundingBox(boolean useBoundingBox) {
        this.useBoundingBox = useBoundingBox;
    }

    /**
     * Gets the initial bounding box extension range in the horizontal direction.
     * Default: 3. Can be modified by using use "--boundingBoxExtensionX" option, e.g. "--boundingBoxExtensionX 5".
     * @return The bounding box extension range in the horizontal direction.
     */
    public short getBoundingBoxExtensionX() {
        return boundingBoxExtensionX;
    }

    /**
     * Sets the initial bounding box extension range in the horizontal direction.
     * Default: 3. Can be modified by using use "--boundingBoxExtensionX" option, e.g. "--boundingBoxExtensionX 5".
     * @param boundingBoxExtensionX
     */
    public void setBoundingBoxExtensionX(short boundingBoxExtensionX) {
        this.boundingBoxExtensionX = boundingBoxExtensionX;
    }

    /**
     * Gets the initial bounding box extension range in the vertical direction.
     * Default: 15. Can be modified by using use "--boundingBoxExtensionY" option, e.g. "--boundingBoxExtensionY 5".
     * @return The bounding box extension range in the vertical direction.
     */
    public short getBoundingBoxExtensionY() {
        return boundingBoxExtensionY;
    }

    /**
     * Sets the initial bounding box extension range in the vertical direction.
     * Default: 15. Can be modified by using use "--boundingBoxExtensionY" option, e.g. "--boundingBoxExtensionY 5".
     * @param boundingBoxExtensionY
     */
    public void setBoundingBoxExtensionY(short boundingBoxExtensionY) {
        this.boundingBoxExtensionY = boundingBoxExtensionY;
    }

    /**
     * Checks if the bounding boxes of connections would be expanded during routing.
     * Enlarging bounding boxes of connections helps resolve routability problems for some scenarios,
     * such as partial routing and very congested placement of designs.
     * Default: false for full routing, true for partial routing.
     * To enable enlarging bounding boxes, please add "--enlargeBoundingBox" to the arguments.
     * @return true, if enlarging bounding boxes of connections is allowed.
     */
    public boolean isEnlargeBoundingBox() {
        return enlargeBoundingBox;
    }

    /**
     * Sets enlargeBoundingBox.
     * Enlarging bounding boxes of connections helps resolve routability problems for some scenarios,
     * such as partial routing and very congested designs.
     * Default: false for full routing, true for partial routing.
     * To enable enlarging bounding boxes, please add "--enlargeBoundingBox" to the arguments.
     * @param enlargeBoundingBox A flag to indicate if connections' bounding boxes are allowed to be enlarged for routing.
     */
    public void setEnlargeBoundingBox(boolean enlargeBoundingBox) {
        this.enlargeBoundingBox = enlargeBoundingBox;
    }

    /**
     * Gets the extension increment that connections' bounding boxes should be enlarged by vertically.
     * Default: 2. Can be modified by using "--extensionYIncrement" option, e.g. "--extensionYIncrement 3".
     * @return The number of INT Tiles that connections' bounding boxes should be enlarged by vertically.
     */
    public short getExtensionYIncrement() {
        return extensionYIncrement;
    }

    /**
     * Sets the extension increment that connections' bounding boxes should be enlarged by vertically.
     * Default: 2. Can be modified by using "--extensionYIncrement" option, e.g. "--extensionYIncrement 3".
     * @param extensionYIncrement The number of INT Tiles that connections' bounding boxes should be enlarged by vertically.
     */
    public void setExtensionYIncrement(short extensionYIncrement) {
        this.extensionYIncrement = extensionYIncrement;
    }

    /**
     * Gets the extension increment that connections' bounding boxes should be enlarged by horizontally.
     * Default: 1. Can be modified by using "--extensionXIncrement" option, e.g. "--extensionXIncrement 2".
     * @return The number of INT Tiles that connections' bounding boxes should be enlarged by horizontally.
     */
    public short getExtensionXIncrement() {
        return extensionXIncrement;
    }

    /**
     * Sets the extension increment that connections' bounding boxes should be enlarged by horizontally.
     * Default: 1. Can be modified by using "--extensionXIncrement" option, e.g. "--extensionXIncrement 2".
     * @param extensionXIncrement The number of INT Tiles that connections' bounding boxes should be enlarged by horizontally.
     */
    public void setExtensionXIncrement(short extensionXIncrement) {
        this.extensionXIncrement = extensionXIncrement;
    }

    /**
     * Gets the wirelength-driven weighting factor used in the cost function.
     * It should be within [0, 1]. The greater it is, the faster the router will run, at the cost of a greater total wirelength.
     * Default: 0.8. Can be modified by using "--wirelengthWeight", e.g. "--wirelengthWeight 0.7".
     * @return The wirelength-driven weighting factor used in the cost function
     */
    public float getWirelengthWeight() {
        return wirelengthWeight;
    }

    /**
     * Sets the wirelength-driven weighting factor used in the cost function.
     * It should be within [0, 1]. The greater it is, the faster the router will run, at the cost of a greater total wirelength.
     * Default: 0.8. Can be modified by using "--wirelengthWeight", e.g. "--wirelengthWeight 0.7".
     * @param wirelengthWeight The wirelength-driven weighting factor used in the cost function
     */
    public void setWirelengthWeight(float wirelengthWeight) {
        if (wirelengthWeight < 0 || wirelengthWeight > 1)
            throw new IllegalArgumentException("ERROR: wirelength-driven weighting factor wirelengthWeight should be within [0, 1].");
        this.wirelengthWeight = wirelengthWeight;
    }

    /**
     * Gets the timing-driven weighting factor used in the cost function.
     * It should be within [0, 1]. The greater it is, the faster the router will run, at the cost of a greater critical path delay.
     * Default: 0.35. Can be modified by using "--timingWeight" option, e.g. "--timingWeight 0.4".
     * @return The timing-driven weighting factor used in the cost function
     */
    public float getTimingWeight() {
        return timingWeight;
    }

    /**
     * Sets the timing-driven weighting factor used in the cost function.
     * It should be within [0, 1]. The greater it is, the faster the router will run, at the cost of a greater critical path delay.
     * Default: 0.35. Can be modified by using "--timingWeight" option, e.g. "--timingWeight 0.4".
     * @param timingWeight The timing-driven weighting factor used in the cost function.
     */
    public void setTimingWeight(float timingWeight) {
        if (timingWeight < 0 || timingWeight > 1)
            throw new IllegalArgumentException("ERROR: timing-driven weighting factor timingWeight cannot be negative or greater than 1.");
        this.timingWeight = timingWeight;
    }

    /**
     * Gets the timing-driven weighting factor multiplier.
     * Default: 1. Can be modified by using "--timingMultiplier" option, e.g. "--timingMultiplier 1.02".
     * @return The timing-driven weighting factor multiplier.
     */
    public float getTimingMultiplier() {
        return timingMultiplier;
    }

    /**
     * Sets the multiplier for timingWeight. This is an experimental feature for future adaptive timingWeight.
     * The idea is to have less timingWeight in early routing iterations for faster runtime and put more weight on the timing cost
     * in late iterations for better timing performance.
     * Currently, the default timingMultiplier is 1.
     * Can be modified by using "--timingMultiplier" option, e.g. "--timingMultiplier 1.02".
     * @param timingMultiplier A multiplier greater than 1 to increase timingWeight along routing iterations.
     */
    public void setTimingMultiplier(float timingMultiplier) {
        if (timingMultiplier < 1)
            throw new IllegalArgumentException("ERROR: timingMultiplier cannot be less than 1.");
        this.timingMultiplier = timingMultiplier;
    }

    /**
     * Gets the sharing exponent that discourages resource sharing for timing-driven routing of critical connections.
     * It is no less than 0. Default: 2.
     * Can be modified by using "--shareExponent" option, e.g. "--shareExponent 4".
     * @return The sharing exponent that discourages resource sharing for timing-driven routing of critical connections.
     */
    public float getShareExponent() {
        return shareExponent;
    }

    /**
     * Sets the sharing exponent that discourages resource sharing for timing-driven routing of critical connections.
     * It is no less than 0. Default: 2.
     * Can be modified by using "--shareExponent" option, e.g. "--shareExponent 4".
     * @param shareExponent The sharing exponent that discourages resource sharing for timing-driven routing of critical connections.
     */
    public void setShareExponent(float shareExponent) {
        if (shareExponent < 0)
            throw new IllegalArgumentException("ERROR: shareExponent cannot be negative.");
        this.shareExponent = shareExponent;
    }

    /**
     * Gets the criticality exponent that is used in calculating connections' criticalities to
     * spread the criticalities of less critical connections and more critical connections apart.
     * It should be greater than 1. Default: 3.
     * Can be modified by using "--criticalityExponent" option, e.g. "--criticalityExponent 5".
     * @return The criticality exponent.
     */
    public float getCriticalityExponent() {
        return criticalityExponent;
    }

    /**
     * Sets the criticality exponent that is used in calculating connections' criticalities to
     * spread the criticalities of less critical connections and more critical connections apart.
     * It should be greater than 1. Default: 3.
     * Can be modified by using "--criticalityExponent" option, e.g. "--criticalityExponent 5".
     * @param criticalityExponent
     */
    public void setCriticalityExponent(float criticalityExponent) {
        if (criticalityExponent < 1)
            throw new IllegalArgumentException("ERROR: criticalityExponent cannot be less than 1.");
        this.criticalityExponent = criticalityExponent;
    }

    /**
     * Gets the criticality threshold for re-routing critical connections.
     * It should be within (0.5, 0.99). A greater value means less critical connections to be ripped up and re-routed.
     * Default: 0.85. Can be modified by using "--minRerouteCriticality" option, e.g. "--minRerouteCriticality 0.9".
     * @return
     */
    public float getMinRerouteCriticality() {
        return minRerouteCriticality;
    }

    /**
     * Sets the criticality threshold for re-routing critical connections.
     * It should be within (0.5, 0.99). A greater value means less critical connections to be ripped up and re-routed.
     * Default: 0.85. Can be modified by using "--minRerouteCriticality" option, e.g. "--minRerouteCriticality 0.9".
     * @param minRerouteCriticality
     */
    public void setMinRerouteCriticality(float minRerouteCriticality) {
        if (minRerouteCriticality <= 0.5)
            throw new IllegalArgumentException("ERROR: minRerouteCriticality cannot be less than 0.5.");
        this.minRerouteCriticality = minRerouteCriticality;
    }

    /**
     * Gets the maximum percentage of critical connections that should be re-routed.
     * It should be greater than 0. Default: 3.
     * Can be modified by using "--reroutePercentage" option, e.g. "--reroutePercentage 5".
     * @return
     */
    public short getReroutePercentage() {
        return reroutePercentage;
    }

    /**
     * Sets the maximum percentage of critical connections that should be re-routed.
     * It should be greater than 0. Default: 3.
     * Can be modified by using "--reroutePercentage" option, e.g. "--reroutePercentage 5".
     * @param reroutePercentage
     */
    public void setReroutePercentage(short reroutePercentage) {
        if (reroutePercentage < 0)
            throw new IllegalArgumentException("ERROR: reroutePercentage cannot be negative.");
        this.reroutePercentage = reroutePercentage;
    }

    /**
     * Gets the initial present congestion cost penalty factor.
     * It should be greater than 0. Default: 0.5.
     * Can be modified by using "--initialPresentCongestionFactor" option, e.g. "--initialPresentCongestionFactor 1".
     * @return The initial present congestion cost penalty factor.
     */
    public float getInitialPresentCongestionFactor() {
        return initialPresentCongestionFactor;
    }

    /**
     * Sets the initial present congestion cost penalty factor.
     * It should be greater than 0. Default: 0.5.
     * Can be modified by using "--initialPresentCongestionFactor" option, e.g. "--initialPresentCongestionFactor 1".
     * @param initialPresentCongestionFactor The value to set.
     */
    public void setInitialPresentCongestionFactor(float initialPresentCongestionFactor) {
        if (initialPresentCongestionFactor < 0)
            throw new IllegalArgumentException("ERROR: initialPresentCongesFactor cannot be negative.");
        this.initialPresentCongestionFactor = initialPresentCongestionFactor;
    }

    /**
     * Gets the present congestion factor multiplier.
     * It should be greater than 1. Default: 2.
     * Can be modified by using "--presentCongestionMultiplier" option, e.g. "--presentCongestionMultiplier 3".
     * @return
     */
    public float getPresentCongestionMultiplier() {
        return presentCongestionMultiplier;
    }

    /**
     * Sets the present congestion factor multiplier.
     * It should be greater than 1. Default: 2.
     * Can be modified by using "--presentCongestionMultiplier" option, e.g. "--presentCongestionMultiplier 3".
     * @param presentCongestionMultiplier
     */
    public void setPresentCongestionMultiplier(float presentCongestionMultiplier) {
        if (presentCongestionMultiplier <= 1)
            throw new IllegalArgumentException("ERROR: the present congestion factor multiplier cannot be less than 1.");
        this.presentCongestionMultiplier = presentCongestionMultiplier;
    }

    /**
     * Gets the historical congestion cost penalty factor.
     * It should be greater than 0. Default: 1.
     * Can be modified by using "--historicalCongestionFactor" option, e.g. "--historicalCongestionFactor 2".
     * @return
     */
    public float getHistoricalCongestionFactor() {
        return historicalCongestionFactor;
    }

    /**
     * Sets the historical congestion cost penalty factor.
     * It should be greater than 0. Default: 1.
     * Can be modified by using "--historicalCongestionFactor" option, e.g. "--historicalCongestionFactor 2".
     * @param historicalCongestionFactor
     */
    public void setHistoricalCongestionFactor(float historicalCongestionFactor) {
        if (historicalCongestionFactor <= 0)
            throw new IllegalArgumentException("ERROR: historicalCongestionFactor cannot be less than 0.");
        this.historicalCongestionFactor = historicalCongestionFactor;
    }

    /**
     * Checks if the router should run in the timing-driven mode.
     * Default: true.
     * For wirelength-driven routing only, please use "--nonTimingDriven" option to disable timing-driven routing.
     * @return true, if the router runs in the timing-driven mode.
     */
    public boolean isTimingDriven() {
        return timingDriven;
    }

    /**
     * Sets timingDriven.
     * Default: true.
     * For wirelength-driven routing only, please use "--nonTimingDriven" option to disable timing-driven routing.
     * @param timingDriven
     */
    public void setTimingDriven(boolean timingDriven) {
        this.timingDriven = timingDriven;
    }

    /**
     * Gets the DSP timing data folder that contains DSP timing data files for the current design to be routed.
     * They are used for timing-driven routing of designs with DSPs.
     * They can be generated by running Vivado with the Tcl script provided under $RAPIDWRIGHT_PATH/tcl/rwroute.
     * Default: null.
     * @return
     */
    public String getDspTimingDataFolder() {
        return dspTimingDataFolder;
    }

    /**
     * Sets the DSP timing data folder that contains DSP timing data files for the current design to be routed.
     * They are used for more accurate delay calculation during timing-driven routing of designs with DSPs.
     * They can be generated by running Vivado with the Tcl script provided under $RAPIDWRIGHT_PATH/tcl/rwroute.
     * Default: null. Can be specified by using "--dspTimingDataFolder" option, e.g. "--dspTimingDataFolder $RAPIDWRIGHT_PATH/DSPTimingFilesOfDesign/".
     * Without DSP timing files supplied, the router still continues timing-aware routing.
     * Yet there could be unexpected delay optimism.
     * @param dspTimingDataFolder The directory that contains DSP timing data files for the current design to be routed.
     */
    public void setDspTimingDataFolder(String dspTimingDataFolder) {
        this.dspTimingDataFolder = dspTimingDataFolder;
    }

    /**
     * Sets the clock enable net timing data file.
     * The file can be generated by running Vivado with the Tcl script provided under $RAPIDWRIGHT_PATH/tcl/rwroute.
     * Default: null.
     * Can be modified by using "--clkRouteTiming" option, e.g. "--clkRouteTiming $RAPIDWRIGHT_PATH/ceroute.txt".
     * @return
     */
    public String getClkRouteTiming() {
        return clkRouteTiming;
    }

    /**
     * Sets the clock enable net timing data file.
     * The file can be generated by running Vivado with the Tcl script provided under $RAPIDWRIGHT_PATH/tcl/rwroute.
     * Default: null.
     * Can be modified by using "--clkRouteTiming" option, e.g. "--clkRouteTiming $RAPIDWRIGHT_PATH/clkroute.txt".
     * @param clkRouteTiming
     */
    public void setClkRouteTiming(String clkRouteTiming) {
        this.clkRouteTiming = clkRouteTiming;
    }

    /**
     * Gets the critical path delay pessimism factor a.
     * It should be greater than 0.99. Default: 1.03.
     * Can be modified by using "--pessimismA" option, e.g. "--pessimismA 1.05".
     * @return pessimismA
     */
    public float getPessimismA() {
        return pessimismA;
    }

    /**
     * Sets the critical path delay pessimism factor a.
     * It should be greater than 0.99. Default: 1.03.
     * Can be modified by using "--pessimismA" option, e.g. "--pessimismA 1.05".
     * @param pessimismA
     */
    public void setPessimismA(float pessimismA) {
        if (pessimismA < 0.99)
            throw new IllegalArgumentException("ERROR: pessimismA cannot be less than 0.99");
        this.pessimismA = pessimismA;
    }

    /**
     * Gets critical path delay factor b.
     * It should be greater than 0. Default: 100.
     * Can be modified by using "--pessimismB" option, e.g. "--pessimismB 50".
     * @return pessimismB
     */
    public short getPessimismB() {
        return pessimismB;
    }

    /**
     * Sets critical path delay pessimism factor b.
     * It should be greater than 0. Default: 100.
     * Can be modified by using "--pessimismB" option, e.g. "--pessimismB 50".
     * @param pessimismB
     */
    public void setPessimismB(short pessimismB) {
        if (pessimismB < 0)
            throw new IllegalArgumentException("ERROR: pessimismB cannot be negative.");
        this.pessimismB = pessimismB;
    }

    /**
     * Checks if nodes cross RCLK are masked.
     * If should be set to true for full timing-driven routing to avoid delay optimism and false for partial timing-driven routing for routability.
     * Default: false. Can be modified by adding "--maskNodesCrossRCLK" to the arguments.
     * @return true, if nodes cross RCLK are masked
     */
    public boolean isMaskNodesCrossRCLK() {
        return maskNodesCrossRCLK;
    }

    /**
     * Sets maskNodesCrossRCLK.
     * If should be set to true for full timing-driven routing to avoid delay optimism and false for partial timing-driven routing for routability.
     * Default: false. Can be modified by adding "--maskNodesCrossRCLK" to the arguments.
     * @param maskNodesCrossRCLK A flag to indicate if masking nodes cross RCLK.
     */
    public void setMaskNodesCrossRCLK(boolean maskNodesCrossRCLK) {
        this.maskNodesCrossRCLK = maskNodesCrossRCLK;
    }

    /**
     * Checks if U-turn nodes at the device boundaries are considered to be used.
     * If the design is placed near the device boundaries, U-turn nodes should be considered to be used. Otherwise, there could be routability problems.
     * Default: false. Can be modified by adding "--useUTurnNodes" to the arguments.
     * @return true, if U-turn nodes are considered to be used
     */
    public boolean isUseUTurnNodes() {
        return useUTurnNodes;
    }

    /**
     * Sets useUTurnNodes.
     * If the design is placed near the device boundaries, U-turn nodes should be considered to be used. Otherwise, there could be routability problems.
     * Default: false. Can be modified by adding "--useUTurnNodes" to the arguments.
     * @param useUTurnNodes A flag to indicate if U-turn nodes are considered to be used for routing.
     */
    public void setUseUTurnNodes(boolean useUTurnNodes) {
        this.useUTurnNodes = useUTurnNodes;
    }

    /**
     * Checks if verbose is enabled.
     * If enabled, there will be more info in the routing log file regarding design netlist, routing statistics, and timing report.
     * Default: false. Can be modified by adding "--verbose" to the arguments.
     * @return true, if verbose is enabled.
     */
    public boolean isVerbose() {
        return verbose;
    }

    /**
     * Gets printConnectionSpan value.
     * The router calculates connection span statistics during the initialization.
     * Default: false.
     * @return
     */
    public boolean isPrintConnectionSpan() {
        return printConnectionSpan;
    }

    /**
     * Sets printConnectionSpan.
     * The router calculates connection span statistics during the initialization.
     * Default: false.
     * @param printConnectionSpan true, to print out connection span statistics.
     */
    public void setPrintConnectionSpan(boolean printConnectionSpan) {
        this.printConnectionSpan = printConnectionSpan;
    }

    /**
     * Sets verbose.
     * If true, there will be more info in the routing log file regarding design netlist, routing statistics, and timing report.
     * Default: false. Can be modified by adding "--verbose" to the arguments.
     * @param verbose true to print more info in the routing log file.
     */
    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    @Override
    public String toString() {
        StringBuilder s = new StringBuilder();
        s.append(MessageGenerator.formatString("Router Configuration\n"));
        s.append(MessageGenerator.formatString("Max routing iterations: ", maxIterations));
        s.append(MessageGenerator.formatString("Timing-driven: ", timingDriven));
        s.append(MessageGenerator.formatString("Use bounding boxes: ", isUseBoundingBox()));
        if (isUseBoundingBox()) {
            s.append(MessageGenerator.formatString("Bounding box extension X: ", boundingBoxExtensionX));
            s.append(MessageGenerator.formatString("Bounding box extension Y: ", boundingBoxExtensionY));
            if (isEnlargeBoundingBox()) {
                s.append(MessageGenerator.formatString("Enlarge bounding box: ", isEnlargeBoundingBox()));
                s.append(MessageGenerator.formatString("Extension X increment: ", extensionXIncrement));
                s.append(MessageGenerator.formatString("Extension Y increment: ", extensionYIncrement));
            } else {
                s.append(MessageGenerator.formatString("Fixed bounding box: ", !isEnlargeBoundingBox()));
            }
        }
        s.append(MessageGenerator.formatString("Wirelength-driven weight: ", wirelengthWeight));
        if (timingDriven) {
            s.append(MessageGenerator.formatString("Sharing exponent: ", shareExponent));
            s.append(MessageGenerator.formatString("Timing-driven weight: ", timingWeight));
            s.append(MessageGenerator.formatString("Timing-driven mult fac: ", timingMultiplier));
            s.append(MessageGenerator.formatString("Criticality exponent: ", criticalityExponent));
            s.append(MessageGenerator.formatString("Reroute criticality threshold:", minRerouteCriticality));
            s.append(MessageGenerator.formatString("Reroute percentage: ", reroutePercentage));
            s.append(MessageGenerator.formatString("PessimismA: ", pessimismA));
            s.append(MessageGenerator.formatString("PessimismB: ", pessimismB));
        }
        s.append(MessageGenerator.formatString("Mask nodes across RCLK: ", maskNodesCrossRCLK));
        s.append(MessageGenerator.formatString("Include U-turn nodes: ", useUTurnNodes));
        s.append(MessageGenerator.formatString("Initial present congestion factor: ", initialPresentCongestionFactor));
        s.append(MessageGenerator.formatString("Present congestion multiplier: ", presentCongestionMultiplier));
        s.append(MessageGenerator.formatString("Historical congestion factor ", historicalCongestionFactor));

        return s.toString();
    }
}
