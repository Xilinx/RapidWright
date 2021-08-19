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

import org.python.google.common.collect.HashMultiset;

import com.xilinx.rapidwright.design.SitePinInst;

/**
 * A RoutableData Object is created whenever a unique {@link Routable} Object (e.g. {@link RoutableNode} Object) is created.
 * It stores a collection of data that is modified by the router during routing, 
 * such as the congestion costs, path costs, users and drivers of the {@link Routable} Object.
 * A {@link Routable} Object (e.g. {@link RoutableNode} Object) is denoted as rnode.
 */
public class RoutableData {
	/** A unique index of this RoutableData Object, same as the corresponding Routable Object */
	private final int index;
	/** Present congestion cost */
	private float presentCongesCost;
	/** Historical congestion cost */
	private float historicalCongesCost;
	/** Upstream path cost */
	private float upstreamPathCost;
	/** Lower bound of the total path cost */
	private float lowerBoundTotalPathCost;
	/** A flag to indicate if the rnode has been visited or not during the expansion */
	private boolean visited;
	/** The parent of the rnode for the routing of one connection */
	private Routable prev;
	/** A set of the source {@link SitePinInst} Objects of nets that are using the rnode */
	private HashMultiset<SitePinInst> sourceSet;
	/** A set of drivers (parents) of the rnode according to the routing paths of connections */
	private HashMultiset<Routable> parentSet;
	
	public RoutableData(int index) {
    	this.index = index;
    	this.presentCongesCost = 1;
    	this.historicalCongesCost = 1;
    	this.setVisited(false);
		this.sourceSet = null;
		this.parentSet = null;
		this.prev = null;
	}
	
	/**
	 * Sets the lower bound total path cost.
	 * @param totalPathCost The cost value to be set.
	 */
	public void setLowerBoundTotalPathCost(float totalPathCost) {
		this.lowerBoundTotalPathCost = totalPathCost;
		this.setVisited(true);
	}
	
	/**
	 * Sets the upstream path cost.
	 * @param newPartialPathCost The new value to be set.
	 */
	public void setUpstreamPathCost(float newPartialPathCost) {
		this.upstreamPathCost = newPartialPathCost;
	}
	
	/**
	 * Gets the lower bound total path cost.
	 * @return The lower bound total path cost.
	 */
	public float getLowerBoundTotalPathCost() {
		return this.lowerBoundTotalPathCost;
	}
	
	/**
	 * Gets the upstream path cost.
	 * @return The upstream path cost.
	 */
	public float getUpstreamPathCost() {
		return this.upstreamPathCost;
	}

	/**
	 * Gets a HashMultiset of the sources of nets that are using the associated rnode.
	 * @return The HashMultiset of the sources of nets that are using the associated rnode.
	 * {@link SitePinInst}
	 */
	public HashMultiset<SitePinInst> getSourceSet() {
		return sourceSet;
	}

	/**
	 * Sets a HashMultiset of the sources of nets that are using the associated rnode.
	 * @param sourceSet The sources to be set.
	 */
	public void setSourceSet(HashMultiset<SitePinInst> sourceSet) {
		this.sourceSet = sourceSet;
	}

	/**
	 * Adds a source SitePinInst to the source set.
	 * @param source The source of a net to be added.
	 */
	public void addSource(SitePinInst source) {
		if(this.sourceSet == null) {
			this.sourceSet = HashMultiset.create();
		}
		this.sourceSet.add(source);
	}
	
	/**
	 * Gets the number of unique sources in the source set.
	 * @return The number of unique sources in the source set.
	 */
	public int numUniqueSources() {
		if(this.sourceSet == null) {
			return 0;
		}
		return this.sourceSet.elementSet().size();
	}
	
	/**
	 * Removes a source from the source set.
	 * @param source The source {@link SitePinInst} to be removed from the set.
	 */
	public void removeSource(SitePinInst source) {
		this.sourceSet.remove(source);
	}

	/**
	 * Counts the total number of a source included in the source set, 
	 * which equals to the number of connections driven by the source that are using the rnode.
	 * @param source The source {@link SitePinInst}.
	 * @return The total number of a source included in the source set.
	 */
	public int countSourceUses(SitePinInst source) {
		if(this.sourceSet == null) {
			return 0;
		}
		return this.sourceSet.count(source);
	}
	
	/**
	 * Gets the number of unique drivers of the rnode.
	 * @return The number of unique drivers of the rnode.
	 */
	public int numUniqueParents() {
		if(this.parentSet == null) {
			return 0;
		}
		return this.parentSet.elementSet().size();
	}
	
	/**
	 * Adds a driver to the parent set of the associated rnode.
	 * @param parent The driver to be added.
	 */
	public void addParent(Routable parent) {
		if(this.parentSet == null) {
			this.parentSet = HashMultiset.create();
		}
		this.parentSet.add(parent);
	}
	
	/**
	 * Removes a parent from the parent set.
	 * @param parent The parent to be removed.
	 */
	public void removeParent(Routable parent) {
		this.parentSet.remove(parent);
	}
	
	/**
	 * Gets the occupancy of the rnode, which is the number of unique sources in the source set.
	 * @return The occupancy of the rnode.
	 */
	public int getOccupancy() {
		return this.numUniqueSources();
	}
	
	public Routable getPrev() {
		return prev;
	}

	public void setPrev(Routable prev) {
		this.prev = prev;
	}
	
	public float getPresentCongesCost() {
		return presentCongesCost;
	}

	public void setPresentCongesCost(float presentCongesCost) {
		this.presentCongesCost = presentCongesCost;
	}

	public float getHistoricalCongesCost() {
		return historicalCongesCost;
	}

	public void setHistoricalCongesCost(float historicalCongesCost) {
		this.historicalCongesCost = historicalCongesCost;
	}

	public boolean isVisited() {
		return visited;
	}

	public void setVisited(boolean visited) {
		this.visited = visited;
	}

	@Override
	public int hashCode() {
		return this.index;
	}
	
	@Override
	public String toString(){
		StringBuilder s = new StringBuilder();
		s.append("Rnode " + this.index + " ");
		s.append(", ");
		s.append(String.format("occupancy = %d", this.getOccupancy()));
		s.append(", ");
		s.append(String.format("num unique sources = %d", this.numUniqueSources()));
		s.append(", ");
		s.append(String.format("num unique parents = %d", this.numUniqueParents()));
		return s.toString();
	}
	
}
