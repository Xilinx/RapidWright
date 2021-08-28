/*
 * 
 * Copyright (c) 2021 Ghent University. 
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

/**
 * A collection of customizable parameters for a {@link RWRoute} Object or a {@link ReportTimingAndWirelength} Object.
 * Modifications of default parameter values can be done by adding corresponding options with values to the arguments.
 * Each option (i.e. one of the parameters) name must start with two dashes. Values of parameters do not need dashes.
 */
public class Configuration {
	/** Allowed max number of routing iterations */
	private short maxIterations;
	/** Routing bounding box constraint */
	private boolean useBoundingBox;
	/** Bounding box extension range to the left and right */
	private short boundingBoxExtensionX;
	/** Bounding box extension range to the top and bottom */
	private short boundingBoxExtensionY;
	/** Further enlarge the bounding box by the number of INT tiles during routing*/
	private boolean enlargeBoundingBox;
	private short verticalINTTiles;
	private short horizontalINTTiles;
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
	private float initialPresentCongesFac; 
	/** The multiplier factor for present congestion update */
	private float presentCongesMultiplier; 
	/** Historical congestion penalty factor */
	private float historicalCongesFac;
	/** true to enable timing-aware routing */
	private boolean timingDriven;
	/** true to enable partial routing */
	private boolean partialRouting;
	/** true to allow the router to unroute preserved nets to release resources */
	private boolean softPreserve;
	/** The directory contains DSP timing data files for the target design */
	private String dspTimingDataFolder;
	/** The text file containing clock skew data */
	private String clkSkew;
	/** The text file containing clock enable partial route and timing data */
	private String clkRouteTiming;
	/** 
	 * true to enable a symmetric clk routing approach for non-timing routing purpose.
	 * false by default to enable the default clk routing for non-timing routing purpose
	 */
	private boolean symmetricClkRouting;
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
	public Configuration(String[] arguments) {
		this.maxIterations = (short) 100;
		this.useBoundingBox = true;
		this.boundingBoxExtensionX = (short) 3;
		this.boundingBoxExtensionY = (short) 15;
		this.enlargeBoundingBox = false;
		this.verticalINTTiles = 2;
		this.horizontalINTTiles = 1;
		this.wirelengthWeight = 0.8f;
		this.timingWeight = 0.35f;
		this.timingMultiplier = 1f;
		this.shareExponent = 2;
		this.criticalityExponent = 3;
		this.minRerouteCriticality = 0.85f;
		this.reroutePercentage = (short) 3;
		this.initialPresentCongesFac = 0.5f;
		this.presentCongesMultiplier = 2f;
		this.historicalCongesFac = 1f;
		this.timingDriven = true;
		this.symmetricClkRouting = false;
		this.partialRouting = false;
		this.softPreserve = false;
		this.pessimismA = 1.03f;
		this.pessimismB = (short) 100;
		this.maskNodesCrossRCLK = false;
		this.useUTurnNodes = false;
		this.verbose = false;
		this.printConnectionSpan = false;
		if(arguments != null) {
			this.parseArguments(arguments);
		}
	}

	private void parseArguments(String[] arguments) {
		for(int i = 0; i < arguments.length; i++) {
			String arg = arguments[i];
			switch(arg) {
			case "--maxIterations":
				this.setMaxIterations(Short.parseShort(arguments[++i]));
				break;
			case "--noBoundingBox":
				this.setUseBoundingBox(false);
				break;
			case "--boundingBoxExtensionX":
				this.setBoundingBoxExtensionX(Short.parseShort(arguments[++i]));
				break;
			case "--boundingBoxExtensionY":
				this.setBoundingBoxExtensionY(Short.parseShort(arguments[++i]));
				break;
			case "--enlargeBoundingBox":
				if(i+1 < arguments.length && (!arguments[i+1].startsWith("--verticalINTTiles") && !arguments[i+1].startsWith("--horizontalINTTiles"))) {
					System.out.println("WARNING: --enlargeBoundingBox option is not followed by --verticalINTTiles <arg> or --horizontalINTTiles <arg>.");
					System.out.println("  Use default settings: verticalINTTiles = 2, horizontalINTTiles = 1.");
				}
				this.setEnlargeBoundingBox(true);
				break;
			case "--fixBoundingBox":
				if(i+1 < arguments.length && (!arguments[i+1].startsWith("--boundingBoxExtensionX") && !arguments[i+1].startsWith("--boundingBoxExtensionY"))) {
					System.out.println("WARNING: --fixBoundingBox option is not followed by --boundingBoxExtensionX <arg> or --boundingBoxExtensionY <arg>.");
					System.out.println("  Use default settings: boundingBoxExtensionX = 3, boundingBoxExtensionY = 15.");
				}
				this.setEnlargeBoundingBox(false);
				break;
			case "--verticalINTTiles":
				this.setVerticalINTTiles(Short.parseShort(arguments[++i]));
				break;
			case "--horizontalINTTiles":
				this.setHorizontalINTTiles(Short.parseShort(arguments[++i]));
				break;
			case "--wirelengthWeight":
				this.setWirelengthWeight(Float.parseFloat(arguments[++i]));
				break;
			case "--shareExponent":
				this.setShareExponent(Float.parseFloat(arguments[++i]));
				break;
			case "--timingWeight":
				this.setTimingWeight(Float.parseFloat(arguments[++i]));
				break;
			case "--timingMultiplier":
				this.setTimingMultiplier(Float.parseFloat(arguments[++i]));
				break;
			case "--criticalityExponent":
				this.setCriticalityExponent(Float.parseFloat(arguments[++i]));
				break;
			case "--minRerouteCriticality":
				this.setMinRerouteCriticality(Float.parseFloat(arguments[++i]));
				break;
			case "--reroutePercentage":
				this.setReroutePercentage(Short.parseShort(arguments[++i]));
				break;
			case "--initialPresentCongesFac":
				this.setInitialPresentCongesFac(Float.parseFloat(arguments[++i]));
				break;
			case "--presentCongesMultiplier":
				this.setPresentCongesMultiplier(Float.parseFloat(arguments[++i]));
				break;
			case "--historicalCongesFac":
				this.setHistoricalCongesFac(Float.parseFloat(arguments[++i]));
				break;
			case "--timingDriven":
				this.setTimingDriven(true);
				break;
			case "--nonTimingDriven":
				this.setTimingDriven(false);
				break;
			case "--partialRouting":
				this.setPartialRouting(true);
				break;
			case "--softPreserve":
				this.setSoftPreserve(true);
				break;
			case "--dspTimingDataFolder":
				this.setDspTimingDataFolder(arguments[++i]);
				break;
			case "--clkSkew":
				this.setClkSkew(arguments[++i]);
				break;
			case "--clkRouteTiming":
				this.setClkRouteTiming(arguments[++i]);
				break;
			case "--symmetricClk":
				this.setSymmetricClkRouting(true);
				break;
			case "--pessimismA":
				this.setPessimismA(Float.parseFloat(arguments[++i]));
				break;
			case "--pessimismB":
				this.setPessimismB(Short.parseShort(arguments[++i]));
				break;
			case "--maskNodesCrossRCLK":
				this.setMaskNodesCrossRCLK(true);
				break;
			case "--maskUTurnNodes":
				this.setUseUTurnNodes(false);
				break;
			case "--useUTurnNodes":
				this.setUseUTurnNodes(true);
				break;
			case "--verbose":
				this.setVerbose(true);
				break;
			case "--printConnectionSpan":
				this.setPrintConnectionSpan(true);
				break;
			default:
				break;
			}
		}
	}	
	
	/**
	 * Gets the allowed maximum number of routing iterations.
	 * Default: 100
	 * @return The allowed maximum number of routing iterations.
	 */
	public short getMaxIterations() {
		return this.maxIterations;
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
		return this.useBoundingBox;
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
	 * Gets the bounding box extension range in the horizontal direction.
	 * Default: 3. Can be modified by using use "--boundingBoxExtensionX" option, e.g. "--boundingBoxExtensionX 5".
	 * @return The bounding box extension range in the horizontal direction.
	 */
	public short getBoundingBoxExtensionX() {
		return this.boundingBoxExtensionX;
	}

	/**
	 * Sets the bounding box extension range in the horizontal direction.
	 * Default: 3. Can be modified by using use "--boundingBoxExtensionX" option, e.g. "--boundingBoxExtensionX 5".
	 * @param boundingBoxExtensionX
	 */
	public void setBoundingBoxExtensionX(short boundingBoxExtensionX) {
		this.boundingBoxExtensionX = boundingBoxExtensionX;
	}
	
	/**
	 * Gets the bounding box extension range in the horizontal direction.
	 * Default: 15. Can be modified by using use "--boundingBoxExtensionY" option, e.g. "--boundingBoxExtensionY 5".
	 * @return The bounding box extension range in the horizontal direction.
	 */
	public short getBoundingBoxExtensionY() {
		return this.boundingBoxExtensionY;
	}

	/**
	 * Sets the bounding box extension range in the horizontal direction.
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
		return this.enlargeBoundingBox;
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
	 * Gets the number of INT Tiles that connections' bounding box should be enlarged vertically.
	 * Default: 2. Can be modified by using "--verticalINTTiles" option, e.g. "--verticalINTTiles 3".
	 * @return The number of INT Tiles that connections' bounding box should be enlarged vertically.
	 */
	public short getVerticalINTTiles() {
		return this.verticalINTTiles;
	}

	/**
	 * Sets the number of INT Tiles that connections' bounding box should be enlarged vertically.
	 * Default: 2. Can be modified by using "--verticalINTTiles" option, e.g. "--verticalINTTiles 3".
	 * @param verticalINTTiles The number of INT Tiles that connections' bounding box should be enlarged vertically.
	 */
	public void setVerticalINTTiles(short verticalINTTiles) {
		this.verticalINTTiles = verticalINTTiles;
	}

	/**
	 * Gets the number of INT Tiles that connections' bounding box should be enlarged horizontally.
	 * Default: 1. Can be modified by using "--horizontalINTTiles" option, e.g. "--horizontalINTTiles 2".
	 * @return The number of INT Tiles that connections' bounding box should be enlarged horizontally.
	 */
	public short getHorizontalINTTiles() {
		return this.horizontalINTTiles;
	}

	/**
	 * Sets the number of INT Tiles that connections' bounding box should be enlarged horizontally.
	 * Default: 1. Can be modified by using "--horizontalINTTiles" option, e.g. "--horizontalINTTiles 2".
	 * @param horizontalINTTiles The number of INT Tiles that connections' bounding box should be enlarged horizontally. 
	 */
	public void setHorizontalINTTiles(short horizontalINTTiles) {
		this.horizontalINTTiles = horizontalINTTiles;
	}

	/**
	 * Gets the wirelength-driven weighting factor used in the cost function.
	 * It should be within [0, 1]. The greater it is, the faster the router will run, at the cost of a greater total wirelength.
	 * Default: 0.8. Can be modified by using "--wirelengthWeight", e.g. "--wirelengthWeight 0.7".
	 * @return The wirelength-driven weighting factor used in the cost function
	 */
	public float getWirelengthWeight() {
		return this.wirelengthWeight;
	}

	/**
	 * Sets the wirelength-driven weighting factor used in the cost function.
	 * It should be within [0, 1]. The greater it is, the faster the router will run, at the cost of a greater total wirelength.
	 * Default: 0.8. Can be modified by using "--wirelengthWeight", e.g. "--wirelengthWeight 0.7".
	 * @param wirelengthWeight The wirelength-driven weighting factor used in the cost function
	 */
	public void setWirelengthWeight(float wirelengthWeight) {
		if(wirelengthWeight < 0 || wirelengthWeight > 1)
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
		return this.timingWeight;
	}

	/**
	 * Sets the timing-driven weighting factor used in the cost function.
	 * It should be within [0, 1]. The greater it is, the faster the router will run, at the cost of a greater critical path delay.
	 * Default: 0.35. Can be modified by using "--timingWeight" option, e.g. "--timingWeight 0.4".
	 * @param timingWeight The timing-driven weighting factor used in the cost function.
	 */
	public void setTimingWeight(float timingWeight) {
		if(timingWeight < 0 || timingWeight > 1)
			throw new IllegalArgumentException("ERROR: timing-driven weighting factor timingWeight cannot be negative or greater than 1.");
		this.timingWeight = timingWeight;
	}

	/**
	 * Gets the timing-driven weighting factor multiplier.
	 * Default: 1. Can be modified by using "--timingMultiplier" option, e.g. "--timingMultiplier 1.02".
	 * @return The timing-driven weighting factor multiplier.
	 */
	public float getTimingMultiplier() {
		return this.timingMultiplier;
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
		if(timingMultiplier < 1)
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
		return this.shareExponent;
	}

	/**
	 * Sets the sharing exponent that discourages resource sharing for timing-driven routing of critical connections.
	 * It is no less than 0. Default: 2.
	 * Can be modified by using "--shareExponent" option, e.g. "--shareExponent 4".
	 * @param shareExponent The sharing exponent that discourages resource sharing for timing-driven routing of critical connections.
	 */
	public void setShareExponent(float shareExponent) {
		if(shareExponent < 0)
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
		return this.criticalityExponent;
	}

	/**
	 * Sets the criticality exponent that is used in calculating connections' criticalities to 
	 * spread the criticalities of less critical connections and more critical connections apart. 
	 * It should be greater than 1. Default: 3.
	 * Can be modified by using "--criticalityExponent" option, e.g. "--criticalityExponent 5".
	 * @param criticalityExponent
	 */
	public void setCriticalityExponent(float criticalityExponent) {
		if(criticalityExponent < 1)
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
		return this.minRerouteCriticality;
	}

	/**
	 * Sets the criticality threshold for re-routing critical connections. 
	 * It should be within (0.5, 0.99). A greater value means less critical connections to be ripped up and re-routed. 
	 * Default: 0.85. Can be modified by using "--minRerouteCriticality" option, e.g. "--minRerouteCriticality 0.9".
	 * @param minRerouteCriticality
	 */
	public void setMinRerouteCriticality(float minRerouteCriticality) {
		if(minRerouteCriticality <= 0.5)
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
		return this.reroutePercentage;
	}

	/**
	 * Sets the maximum percentage of critical connections that should be re-routed.
	 * It should be greater than 0. Default: 3.
	 * Can be modified by using "--reroutePercentage" option, e.g. "--reroutePercentage 5".
	 * @param reroutePercentage
	 */
	public void setReroutePercentage(short reroutePercentage) {
		if(reroutePercentage < 0) 
			throw new IllegalArgumentException("ERROR: reroutePercentage cannot be negative.");
		this.reroutePercentage = reroutePercentage;
	}

	/**
	 * Gets the initial present congestion cost penalty factor.
	 * It should be greater than 0. Default: 0.5.
	 * Can be modified by using "--initialPresentCongesFac" option, e.g. "--initialPresentCongesFac 1".
	 * @return The initial present congestion cost penalty factor.
	 */
	public float getInitialPresentCongesFac() {
		return this.initialPresentCongesFac;
	}

	/**
	 * Sets the initial present congestion cost penalty factor.
	 * It should be greater than 0. Default: 0.5.
	 * Can be modified by using "--initialPresentCongesFac" option, e.g. "--initialPresentCongesFac 1".
	 * @param initialPresentCongesFac
	 */
	public void setInitialPresentCongesFac(float initialPresentCongesFac) {
		if(initialPresentCongesFac < 0)
			throw new IllegalArgumentException("ERROR: initialPresentCongesFac cannot be negative.");
		this.initialPresentCongesFac = initialPresentCongesFac;
	}

	/**
	 * Gets the present congestion factor multiplier.
	 * It should be greater than 1. Default: 2.
	 * Can be modified by using "--presentCongesMultiplier" option, e.g. "--presentCongesMultiplier 3".
	 * @return
	 */
	public float getPresentCongesMultiplier() {
		return this.presentCongesMultiplier;
	}

	/**
	 * Sets the present congestion factor multiplier.
	 * It should be greater than 1. Default: 2.
	 * Can be modified by using "--presentCongesMultiplier" option, e.g. "--presentCongesMultiplier 3".
	 * @param presentCongesMultiplier
	 */
	public void setPresentCongesMultiplier(float presentCongesMultiplier) {
		if(presentCongesMultiplier <= 1)
			throw new IllegalArgumentException("ERROR: the present congestion factor multiplier cannot be less than 1.");
		this.presentCongesMultiplier = presentCongesMultiplier;
	}

	/**
	 * Gets the historical congestion cost penalty factor.
	 * It should be greater than 0. Default: 1.
	 * Can be modified by using "--historicalCongesFac" option, e.g. "--historicalCongesFac 2".
	 * @return
	 */
	public float getHistoricalCongesFac() {
		return this.historicalCongesFac;
	}

	/**
	 * Sets the historical congestion cost penalty factor.
	 * It should be greater than 0. Default: 1.
	 * Can be modified by using "--historicalCongesFac" option, e.g. "--historicalCongesFac 2".
	 * @param historicalCongesFac
	 */
	public void setHistoricalCongesFac(float historicalCongesFac) {
		if(historicalCongesFac <= 0)
			throw new IllegalArgumentException("ERROR: historicalCongesFac cannot be less than 0.");
		this.historicalCongesFac = historicalCongesFac;
	}

	/**
	 * Checks if the router should run in the timing-driven mode.
	 * Default: true.
	 * For wirelength-driven routing only, please use "--nonTimingDriven" option to disable timing-driven routing.
	 * @return true, if the router runs in the timing-driven mode.
	 */
	public boolean isTimingDriven() {
		return this.timingDriven;
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
	 * Checks if the router runs in the partial routing mode.
	 * In the partial routing mode, nets that are already routed will be preserved and the router routes unrouted nets only.
	 * Default: false. For partial routing, please add "--partialRouting" to the arguments.
	 * @return true, if the router runs in the partial routing mode.
	 */
	public boolean isPartialRouting() {
		return this.partialRouting;
	}

	/**
	 * Sets partial routing.
	 * In the partial routing mode, nets that are already routed will be preserved and the router routes unrouted nets only.
	 * Default: false. For partial routing, please add "--partialRouting" to the arguments.
	 * @param partialRouting 
	 */
	public void setPartialRouting(boolean partialRouting) {
		this.partialRouting = partialRouting;
		if(this.partialRouting == false) return;
		if(this.enlargeBoundingBox == false) {
			// when enlargeBoundingBox is not set, use the default parameters as default
			// can be overridden later if there are corresponding options included in the arguments
			this.enlargeBoundingBox = true;
			this.verticalINTTiles = 2;
			this.horizontalINTTiles =1;
		}
		// use U-turn nodes and no masking of nodes cross RCLK
		// Pros: maximumly allows exploration of routing resources for routability
		// Con: might result in delay optimism and a slight increase in runtime
		this.useUTurnNodes = true;
		this.maskNodesCrossRCLK = false;
	}

	/**
	 * Checks if the partial routing is in the soft preserve mode.
	 * In the soft preserve mode, preserved routed nets can be unrouted and added to the routing targets of the router.
	 * Default: false. To enable the soft preserve mode, please add "--softPreserve" to the arguments.
	 * @return
	 */
	public boolean isSoftPreserve() {
		return this.softPreserve;
	}

	/**
	 * Sets softPreserve for partial routing.
	 * In the soft preserve mode, preserved routed nets can be unrouted and added to the routing targets of the router.
	 * Default: false. To enable the soft preserve mode, please add "--softPreserve" to the arguments.
	 * @param softPreserve
	 */
	public void setSoftPreserve(boolean softPreserve) {
		this.softPreserve = softPreserve;
	}

	/**
	 * Gets the DSP timing data folder that contains DSP timing data files for the current design to be routed.
	 * They are used for timing-driven routing of designs with DSPs.
	 * They can be generated by running Vivado with the Tcl script provided under $RAPIDWRIGHT_PATH/tcl/rwroute.
	 * Default: null.
	 * @return
	 */
	public String getDspTimingDataFolder() {
		return this.dspTimingDataFolder;
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
	 * Gets the clock skew timing data file path.
	 * The file can be generated by running Vivado with the Tcl script provided under $RAPIDWRIGHT_PATH/tcl/rwroute.
	 * Default: null. Can be modified by using "--dspTimingDataFolder" option, e.g. "--dspTimingDataFolder $RAPIDWRIGHT_PATH/DSPTimingFilesOfDesign/"
	 * Can be modified by using "--clkSkew" option, e.g. "--clkSkew $RAPIDWRIGHT_PATH/clkskew.txt".
	 * @return
	 */
	public String getClkSkew() {
		return this.clkSkew;
	}

	/**
	 * Sets the clock skew timing data file path.
	 * The file can be generated by running Vivado with the Tcl script provided under $RAPIDWRIGHT_PATH/tcl/rwroute.
	 * Default: null.
	 * Can be modified by using "--clkSkew" option, e.g. "--clkSkew $RAPIDWRIGHT_PATH/clkskew.txt".
	 * @param clkSkew
	 */
	public void setClkSkew(String clkSkew) {
		this.clkSkew = clkSkew;
	}

	/**
	 * Sets the clock enable net timing data file.
	 * The file can be generated by running Vivado with the Tcl script provided under $RAPIDWRIGHT_PATH/tcl/rwroute.
	 * Default: null.
	 * Can be modified by using "--clkRouteTiming" option, e.g. "--clkRouteTiming $RAPIDWRIGHT_PATH/ceroute.txt".
	 * @return
	 */
	public String getClkRouteTiming() {
		return this.clkRouteTiming;
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
	 * Checks if the non-timing-driven clock routing should call the symmetric clock router.
	 * Default: false. Can be modified by adding "--symmetricClk" to the arguments.
	 * @return true, if the symmetric clock router should be used.
	 */
	public boolean isSymmetricClkRouting() {
		return this.symmetricClkRouting;
	}

	/**
	 * Sets symmetricClkRouting that indicate if the non-timing-driven clock routing should call the symmetric clock router.
	 * Default: false. Can be modified by adding "--symmetricClk" to the arguments.
	 * @param symmetricClkRouting
	 */
	public void setSymmetricClkRouting(boolean symmetricClkRouting) {
		this.symmetricClkRouting = symmetricClkRouting;
	}

	/**
	 * Gets the critical path delay pessimism factor a.
	 * It should be greater than 0.99. Default: 1.03.
	 * Can be modified by using "--pessimismA" option, e.g. "--pessimismA 1.05".
	 * @return pessimismA
	 */
	public float getPessimismA() {
		return this.pessimismA;
	}

	/**
	 * Sets the critical path delay pessimism factor a.
	 * It should be greater than 0.99. Default: 1.03.
	 * Can be modified by using "--pessimismA" option, e.g. "--pessimismA 1.05".
	 * @param pessimismA
	 */
	public void setPessimismA(float pessimismA) {
		if(pessimismA < 0.99)
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
		return this.pessimismB;
	}

	/**
	 * Sets critical path delay pessimism factor b.
	 * It should be greater than 0. Default: 100.
	 * Can be modified by using "--pessimismB" option, e.g. "--pessimismB 50".
	 * @param pessimismB 
	 */
	public void setPessimismB(short pessimismB) {
		if(pessimismB < 0)
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
		if(this.isPartialRouting() && maskNodesCrossRCLK) {
			System.out.println("WARNING: Masking nodes cross RCLK for partial routing could result in routability problems.");
		}
		if(!this.isPartialRouting() && !maskNodesCrossRCLK) {
			System.out.println("WARNING: Not masking nodes cross RCLK for partial routing could result in delay optimism.");
		}
		this.maskNodesCrossRCLK = maskNodesCrossRCLK;
	}

	/**
	 * Checks if U-turn nodes at the device boundaries are considered to be used.
	 * If the design is placed near the device boundaries, U-turn nodes should be considered to be used. Otherwise, there could be routability problems.
	 * Default: false. Can be modified by adding "--useUTurnNodes" to the arguments.
	 * @return true, if U-turn nodes are considered to be used
	 */
	public boolean isUseUTurnNodes() {
		return this.useUTurnNodes;
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
		return this.verbose;
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
		s.append(formatString("Router Configuration\n"));
		s.append(formatString("Max routing iterations: ", this.maxIterations));
		s.append(formatString("Timing-driven: ", this.timingDriven));
		s.append(formatString("Partial routing: ", this.partialRouting));
		s.append(formatString("Use bounding boxes: ", this.isUseBoundingBox()));
		if(this.isUseBoundingBox()) {
			s.append(formatString("Bounding box extension X: ", this.boundingBoxExtensionX));
			s.append(formatString("Bounding box extension Y: ", this.boundingBoxExtensionY));
			if(this.isEnlargeBoundingBox()) {
				s.append(formatString("Enlarge bounding box: ", this.isEnlargeBoundingBox()));
				s.append(formatString("Vertical INT tiles: ", this.verticalINTTiles));
				s.append(formatString("Horizontal INT tiles: ", this.horizontalINTTiles));
			}else {
				s.append(formatString("Fixed bounding box: ", !this.isEnlargeBoundingBox()));
			}
		}
		s.append(formatString("Wirelength-driven weight: ", this.wirelengthWeight));
		if(this.timingDriven) {
			s.append(formatString("Sharing exponent: ", this.shareExponent));
			s.append(formatString("Timing-driven weight: ", this.timingWeight));
			s.append(formatString("Timing-driven mult fac: ", this.timingMultiplier));
			s.append(formatString("Criticality exponent: ", this.criticalityExponent));
			s.append(formatString("Reroute criticality threshold:", this.minRerouteCriticality));
			s.append(formatString("Reroute percentage: ", this.reroutePercentage));
			s.append(formatString("PessimismA: ", this.pessimismA));
			s.append(formatString("PessimismB: ", this.pessimismB));
		}
		s.append(formatString("Mask nodes across RCLK: ", this.maskNodesCrossRCLK));
		s.append(formatString("Include U-turn nodes: ", this.useUTurnNodes));
		s.append(formatString("Initial present conges fac: ", this.initialPresentCongesFac));
		s.append(formatString("Present conges fac mult: ", this.presentCongesMultiplier));
		s.append(formatString("Historical conges fac: ", this.historicalCongesFac));
		s.append(formatString("Symmtric clk routing: ", this.isSymmetricClkRouting()));
		
		return s.toString();
	}
	
	private static String formatString(String s1) {
		return String.format("%-30s\n", s1);
	}
	
	private static String formatString(String s, float value) {
		return String.format("%-30s %10.2f\n", s, value);
	}
	
	private static String formatString(String s, short value) {
		return String.format("%-30s %10d\n", s, value);
	}
	
	private static String formatString(String s, boolean value) {
		return String.format("%-30s %10s\n", s, value);
	}
}
