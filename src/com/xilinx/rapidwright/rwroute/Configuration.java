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
 * A collection of customizable routing parameters
 *
 */
public class Configuration {
	/** Allowed max number of routing iterations */
	private short maxIterations;
	/** Routing bounding box constraint */
	private boolean useBoundingBox;
	/** Bounding box extension range */
	private short boundingBoxExtension;
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
	private String ceRouteTiming;
	/** true to enable a symmetric clk routing approach for non-timing routing purpose.
	 * false by default to enable the default clk routing for non-timing routing purpose */
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
	
	/** The default configuration */
	public Configuration() {
		this.maxIterations = (short) 100;
		this.useBoundingBox = true;
		this.boundingBoxExtension = (short) 3;
		this.enlargeBoundingBox = false;
		this.verticalINTTiles = 0;
		this.horizontalINTTiles = 0;
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
	}

	public void parseArguments(int startIndex, String[] arguments) {
		for(int i = startIndex; i < arguments.length; i++) {
			if(arguments[i].startsWith("--noBoundingBox")) {
				this.setUseBoundingBox(false);
				
			}else if(arguments[i].startsWith("--boundingBoxExtension")){
				this.setBoundingBoxExtension(Short.parseShort(arguments[++i]));
				
			}else if(arguments[i].startsWith("--enlargeBoundingBox")){				
				if(i+1 < arguments.length && (!arguments[i+1].startsWith("--verticalINTTiles") && !arguments[i+1].startsWith("--horizontalINTTiles"))) {
					throw new RuntimeException("ERROR: --enlargeBoundingBox needs to be followed by --verticalINTTiles <arg> and / or --horizontalINTTiles <arg>");
				}		
				this.setEnlargeBoundingBox(true);
				
			}else if(arguments[i].startsWith("--verticalINTTiles")){
				this.setVerticalINTTiles(Short.parseShort(arguments[++i]));	
			
			}else if(arguments[i].startsWith("--horizontalINTTiles")){
				this.setHorizontalINTTiles(Short.parseShort(arguments[++i]));	
			
			}else if(arguments[i].startsWith("--wirelengthWeight")){
				this.setWirelengthWeight(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--shareExponent")){
				this.setShareExponent(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--timingWeight")){
				this.setTimingWeight(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--timingMultiplier")){
				this.setTimingMultiplier(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--criticalityExponent")) {
				this.setCriticalityExponent(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--minRerouteCriticality")){
				this.setMinRerouteCriticality(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--reroutePercentage")){
				this.setReroutePercentage(Short.parseShort(arguments[++i]));
				
			}else if(arguments[i].startsWith("--initialPresentCongesFac")){
				this.setInitialPresentCongesFac(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--presentCongesMultiplier")){
				this.setPresentCongesMultiplier(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--historicalCongesFac")){
				this.setHistoricalCongesFac(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--timingDriven")){
				this.setTimingDriven(true);
				
			}else if(arguments[i].startsWith("--nonTimingDriven")){
				this.setTimingDriven(false);
				
			}else if(arguments[i].startsWith("--partialRouting")){
				this.setPartialRouting(true);
				
			}else if(arguments[i].startsWith("--softPreserve")){
				this.setSoftPreserve(true);
				
			}else if(arguments[i].startsWith("--dspTimingDataFolder")){
				this.setDspTimingDataFolder(arguments[++i]);
				
			}else if(arguments[i].startsWith("--clkSkew")){
				this.setClkSkew(arguments[++i]);
				
			}else if(arguments[i].startsWith("--ceRouteTiming")){
				this.setCeRouteTiming(arguments[++i]);
				
			}else if(arguments[i].startsWith("--symmetricClk")){
				this.setSymmetricClkRouting(true);
				
			}else if(arguments[i].startsWith("--pessimismA")){
				this.setPessimismA(Float.parseFloat(arguments[++i]));
				
			}else if(arguments[i].startsWith("--pessimismB")){
				this.setPessimismB(Short.parseShort(arguments[++i]));
				
			}else if(arguments[i].startsWith("--maskNodesCrossRCLK")){
				this.setMaskNodesCrossRCLK(true);
				
			}else if(arguments[i].startsWith("--useUTurnNodes")){
				this.setUseUTurnNodes(true);
				
			}else if(arguments[i].startsWith("--verbose")){
				this.setVerbose(true);
			}
		}
	}	

	public short getMaxIterations() {
		return maxIterations;
	}

	public void setMaxIterations(short maxIterations) {
		this.maxIterations = maxIterations;
	}

	public boolean isUseBoundingBox() {
		return useBoundingBox;
	}

	public void setUseBoundingBox(boolean useBoundingBox) {
		this.useBoundingBox = useBoundingBox;
	}

	public short getBoundingBoxExtension() {
		return boundingBoxExtension;
	}

	public void setBoundingBoxExtension(short boundingBoxExtension) {
		this.boundingBoxExtension = boundingBoxExtension;
	}

	public boolean isEnlargeBoundingBox() {
		return enlargeBoundingBox;
	}

	public void setEnlargeBoundingBox(boolean enlargeBoundingBox) {
		this.enlargeBoundingBox = enlargeBoundingBox;
	}

	public short getVerticalINTTiles() {
		return verticalINTTiles;
	}

	public void setVerticalINTTiles(short verticalINTTiles) {
		this.verticalINTTiles = verticalINTTiles;
	}

	public short getHorizontalINTTiles() {
		return horizontalINTTiles;
	}

	public void setHorizontalINTTiles(short horizontalINTTiles) {
		this.horizontalINTTiles = horizontalINTTiles;
	}

	public float getWirelengthWeight() {
		return wirelengthWeight;
	}

	public void setWirelengthWeight(float wirelengthWeight) {
		this.wirelengthWeight = wirelengthWeight;
	}

	public float getTimingWeight() {
		return timingWeight;
	}

	public void setTimingWeight(float timingWeight) {
		this.timingWeight = timingWeight;
	}

	public float getTimingMultiplier() {
		return timingMultiplier;
	}

	public void setTimingMultiplier(float timingMultiplier) {
		this.timingMultiplier = timingMultiplier;
	}

	public float getShareExponent() {
		return shareExponent;
	}

	public void setShareExponent(float shareExponent) {
		this.shareExponent = shareExponent;
	}

	public float getCriticalityExponent() {
		return criticalityExponent;
	}

	public void setCriticalityExponent(float criticalityExponent) {
		this.criticalityExponent = criticalityExponent;
	}

	public float getMinRerouteCriticality() {
		return minRerouteCriticality;
	}

	public void setMinRerouteCriticality(float minRerouteCriticality) {
		this.minRerouteCriticality = minRerouteCriticality;
	}

	public short getReroutePercentage() {
		return reroutePercentage;
	}

	public void setReroutePercentage(short reroutePercentage) {
		this.reroutePercentage = reroutePercentage;
	}

	public float getInitialPresentCongesFac() {
		return initialPresentCongesFac;
	}

	public void setInitialPresentCongesFac(float initialPresentCongesFac) {
		this.initialPresentCongesFac = initialPresentCongesFac;
	}

	public float getPresentCongesMultiplier() {
		return presentCongesMultiplier;
	}

	public void setPresentCongesMultiplier(float presentCongesMultiplier) {
		this.presentCongesMultiplier = presentCongesMultiplier;
	}

	public float getHistoricalCongesFac() {
		return historicalCongesFac;
	}

	public void setHistoricalCongesFac(float historicalCongesFac) {
		this.historicalCongesFac = historicalCongesFac;
	}

	public boolean isTimingDriven() {
		return timingDriven;
	}

	public void setTimingDriven(boolean timingDriven) {
		this.timingDriven = timingDriven;
	}

	public boolean isPartialRouting() {
		return partialRouting;
	}

	public void setPartialRouting(boolean partialRouting) {
		this.partialRouting = partialRouting;
	}

	public boolean isSoftPreserve() {
		return softPreserve;
	}

	public void setSoftPreserve(boolean softPreserve) {
		this.softPreserve = softPreserve;
	}

	public String getDspTimingDataFolder() {
		return dspTimingDataFolder;
	}

	public void setDspTimingDataFolder(String dspTimingDataFolder) {
		this.dspTimingDataFolder = dspTimingDataFolder;
	}

	public String getClkSkew() {
		return clkSkew;
	}

	public void setClkSkew(String clkSkew) {
		this.clkSkew = clkSkew;
	}

	public String getCeRouteTiming() {
		return ceRouteTiming;
	}

	public void setCeRouteTiming(String ceRouteTiming) {
		this.ceRouteTiming = ceRouteTiming;
	}

	public boolean isSymmetricClkRouting() {
		return symmetricClkRouting;
	}

	public void setSymmetricClkRouting(boolean symmetricClkRouting) {
		this.symmetricClkRouting = symmetricClkRouting;
	}

	public float getPessimismA() {
		return pessimismA;
	}

	public void setPessimismA(float pessimismA) {
		this.pessimismA = pessimismA;
	}

	public short getPessimismB() {
		return pessimismB;
	}

	public void setPessimismB(short pessimismB) {
		this.pessimismB = pessimismB;
	}

	public boolean isMaskNodesCrossRCLK() {
		return maskNodesCrossRCLK;
	}

	public void setMaskNodesCrossRCLK(boolean useNodesCrossRCLK) {
		this.maskNodesCrossRCLK = useNodesCrossRCLK;
	}

	public boolean isUseUTurnNodes() {
		return useUTurnNodes;
	}

	public void setUseUTurnNodes(boolean useUTurnNodes) {
		this.useUTurnNodes = useUTurnNodes;
	}

	public boolean isVerbose() {
		return verbose;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	@Override
	public String toString() {
		StringBuilder s = new StringBuilder();
		s.append(this.formatString("Router Configuration\n"));
		s.append(this.formatString("Timing-driven: ", this.timingDriven));
		s.append(this.formatString("Partial routing: ", this.partialRouting));
		s.append(this.formatString("Bounding box extension: ", this.boundingBoxExtension));
		if(this.isUseBoundingBox()) {
			s.append(this.formatString("Connection bounding box: ", this.isUseBoundingBox()));
		}
		if(this.isEnlargeBoundingBox()) {
			s.append(this.formatString("Enlarge bounding box: ", this.isEnlargeBoundingBox()));
			s.append(this.formatString("Vertical INT tiles: ", this.verticalINTTiles));
			s.append(this.formatString("Horizontal INT tiles: ", this.horizontalINTTiles));
		}
		s.append(this.formatString("Wirelength-driven weight: ", this.wirelengthWeight));
		if(this.timingDriven) {
			s.append(this.formatString("Sharing exponent: ", this.shareExponent));
			s.append(this.formatString("Timing-driven weight: ", this.timingWeight));
			s.append(this.formatString("Timing-driven mult fac: ", this.timingMultiplier));
			s.append(this.formatString("Criticality exponent: ", this.criticalityExponent));
			s.append(this.formatString("Reroute criticality threshold:", this.minRerouteCriticality));
			s.append(this.formatString("Reroute percentage: ", this.reroutePercentage));
			s.append(this.formatString("PessimismA: ", this.pessimismA));
			s.append(this.formatString("PessimismB: ", this.pessimismB));
		}
		s.append(this.formatString("Mask nodes across RCLK: ", this.maskNodesCrossRCLK));
		s.append(this.formatString("Include U-turn nodes: ", this.useUTurnNodes));
		s.append(this.formatString("Enlarge bounding box: ", this.isEnlargeBoundingBox()));
		if(this.isEnlargeBoundingBox()) {
			s.append(this.formatString("Vertical INT tiles: ", this.verticalINTTiles));
			s.append(this.formatString("Horizontal INT tiles ", this.horizontalINTTiles));
		}
		s.append(this.formatString("Initial present conges fac: ", this.initialPresentCongesFac));
		s.append(this.formatString("Present conges fac mult: ", this.presentCongesMultiplier));
		s.append(this.formatString("Historical conges fac: ", this.historicalCongesFac));
		s.append(this.formatString("Symmtric clk routing: ", this.isSymmetricClkRouting()));
		
		return s.toString();
	}
	
	private String formatString(String s1) {
		return String.format("%-30s\n", s1);
	}
	
	private String formatString(String s, float value) {		
		return String.format("%-30s %10.2f\n", s, value);
	}
	
	private String formatString(String s, short value) {		
		return String.format("%-30s %10d\n", s, value);
	}
	
	private String formatString(String s, boolean value) {		
		return String.format("%-30s %10s\n", s, value);
	}
}
