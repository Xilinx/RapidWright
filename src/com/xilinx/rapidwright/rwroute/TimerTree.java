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

import java.util.HashMap;
import java.util.Map;

/**
 * A TimerTree Object consists of {@link Timer} Objects, 
 * providing methods to create a tree of timers for the runtime breakdown of a program.
 */
public class TimerTree {
	Map<String, Timer> timers;
	private Timer root;
	
	boolean verbose = false;
	
	public TimerTree(String rootName, boolean verbose) {
		this.verbose = verbose;
		this.timers = new HashMap<>();
		this.root = new Timer(rootName, (short) 0);
		this.timers.put(this.root.getName(), this.root);
	}
	
	/**
	 * Creates a {@link Timer} instance with its name and its parent name. 
	 * If a timer under the given name exists, returns it.
	 * Otherwise, creates a new one and returns it. 
	 * @param name Name of a timer.
	 * @param parent The parent timer name.
	 * @return A timer under the name.
	 */
	public Timer createTimer(String name, String parent) {
		if(parent == null) {
			throw new RuntimeException("ERROR: Null parent name.");
		}
		Timer parentTimer = this.timers.get(parent);
		if(parentTimer == null) {
			throw new RuntimeException("ERROR: No parent timer under name " + parent + ".\n Please refer to one of the created timers: " + this.timers.keySet());
		}
		Timer newTimer = this.timers.get(name);
		if(newTimer == null) {
			newTimer = new Timer(name, (short) (parentTimer.getLevel() + 1));
			parentTimer.addChild(newTimer);	
			this.timers.put(name, newTimer);
		}
		return newTimer;
	}
	
	/**
	 * Gets a created {@link Timer} instance corresponding to a name.
	 * @param name The name of the timer.
	 * @return A {@link Timer} instance under the name.
	 */
	public Timer getTimer(String name) {
		Timer timer = this.timers.get(name);
		if(timer == null) {
			throw new IllegalArgumentException("ERROR: No Timer instance under name " + name + "." 
						+ "\n Please check if the name is correct. Timer instances created: " + this.timers.keySet());
		}
		return timer;
	}
	
	public Timer createStandAloneTimer(String name) {
		Timer timer = new Timer(name);
		this.timers.put(name, timer);
		return timer;
	}

	/**
	 * Gets the name of the root timer.
	 * @return The name of the root timer.
	 */
	public String getRootTimer() {
		return this.root.getName();
	}
	
	@Override
	public String toString() {
		if(verbose) {
			return this.root.fullHierarchyTimerTree();
		}
		return this.root.timerTreeWithOneLevelChidren();
	}
}