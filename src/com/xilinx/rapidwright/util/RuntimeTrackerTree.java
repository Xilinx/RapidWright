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

package com.xilinx.rapidwright.util;

import java.util.HashMap;
import java.util.Map;

/**
 * A {@link RuntimeTrackerTree} Object consists of {@link RuntimeTracker} Objects, 
 * providing methods to create a tree of runtime trackers for the runtime breakdown of a program.
 */
public class RuntimeTrackerTree {
	Map<String, RuntimeTracker> runtimeTrackers;
	private RuntimeTracker root;
	
	boolean verbose = false;
	
	public RuntimeTrackerTree(String rootName, boolean verbose) {
		this.verbose = verbose;
		this.runtimeTrackers = new HashMap<>();
		this.root = new RuntimeTracker(rootName, (short) 0);
		this.runtimeTrackers.put(this.root.getName(), this.root);
	}
	
	/**
	 * Creates a {@link RuntimeTracker} instance with its name and its parent name. 
	 * If a runtime tracker under the given name exists, returns it.
	 * Otherwise, creates a new one and returns it. 
	 * @param name Name of a runtime tracker.
	 * @param parent The parent runtime tracker name.
	 * @return A runtime tracker under the name.
	 */
	public RuntimeTracker createRuntimeTracker(String name, String parent) {
		if(parent == null) {
			throw new RuntimeException("ERROR: Null parent name.");
		}
		RuntimeTracker parentTracker = this.runtimeTrackers.get(parent);
		if(parentTracker == null) {
			throw new RuntimeException("ERROR: No parent runtime tracker under name " + parent + 
					".\n Please refer to one of the created runtime trackers: " + this.runtimeTrackers.keySet());
		}
		RuntimeTracker newTracker = this.runtimeTrackers.get(name);
		if(newTracker == null) {
			newTracker = new RuntimeTracker(name, (short) (parentTracker.getLevel() + 1));
			parentTracker.addChild(newTracker);	
			this.runtimeTrackers.put(name, newTracker);
		}
		return newTracker;
	}
	
	/**
	 * Gets a created {@link RuntimeTracker} instance corresponding to a name.
	 * @param name The name of the runtime tracker.
	 * @return A {@link RuntimeTracker} instance under the name.
	 */
	public RuntimeTracker getRuntimeTracker(String name) {
		RuntimeTracker tracker = this.runtimeTrackers.get(name);
		if(tracker == null) {
			throw new IllegalArgumentException("ERROR: No runtime tracker instance under name " + name + "." 
						+ "\n Please check if the name is correct. Runtime trackers created: " + this.runtimeTrackers.keySet());
		}
		return tracker;
	}
	
	public RuntimeTracker createStandAloneRuntimeTracker(String name) {
		RuntimeTracker tracker = new RuntimeTracker(name);
		this.runtimeTrackers.put(name, tracker);
		return tracker;
	}

	/**
	 * Gets the name of the root runtime tracker.
	 * @return The name of the root runtime tracker.
	 */
	public String getRootRuntimeTracker() {
		return this.root.getName();
	}
	
	@Override
	public String toString() {
		if(verbose) {
			return this.root.trakerWithFullHierarchy();
		}
		return this.root.trackerWithOneLevelChidren();
	}
	
}
