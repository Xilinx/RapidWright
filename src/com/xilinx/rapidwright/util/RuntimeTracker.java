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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A customized RuntimeTracker class, providing start and stop methods for recording total elapsed time of a process. 
 * Each {@link RuntimeTracker} Object should be created at least with a name. 
 * It also supports a user case of {@link RuntimeTrackerTree} instance for runtime analysis of an entire program.
 */
public class RuntimeTracker {
	private String name;
	private long time;
	private long start;
	private short level;
	private List<RuntimeTracker> children;
	
	public RuntimeTracker(String name) {
		this.name = name + ":";
		this.time = 0;
	}
	
	public RuntimeTracker(String name, short level) {
		this.name = name + ":";
		this.time = 0;
		this.level = level;
		if(this.getLevel() * 3 + this.getName().length() > 36) {
			System.out.println("\nWARNING: RuntimeTracker name too long: " + name + ". Ideal max string length: " + (35 - this.getLevel() * 3));
		}
		this.children = new ArrayList<>();
	}

	/**
	 * Gets the level of a RuntimeTracker instance.
	 * @return
	 */
	public short getLevel() {
		return level;
	}

	/**
	 * Sets the level (depth) of a RuntimeTracker instance if it is included in a tree.
	 * @param level
	 */
	public void setLevel(short level) {
		this.level = level;
	}

	/**
	 * Gets the child runtime trackers.
	 * @return
	 */
	public List<RuntimeTracker> getChildren() {
		return children;
	}

	/**
	 * Adds a child runtime tracker.
	 * @param runtimeTracker The child runtime tracker.
	 */
	public void addChild(RuntimeTracker runtimeTracker) {
		if(!this.children.contains(runtimeTracker)) {
			this.children.add(runtimeTracker);
			if(runtimeTracker.level == 0) {
				runtimeTracker.setLevel((short) (this.getLevel() + 1));
			}
		}
	}
	
	public void start() {
		this.start = System.nanoTime();
	}
	
	/**
	 * Stops the runtime tracker and stores the total time elapsed in nanoseconds.
	 */
	public void stop() {
		this.time += System.nanoTime() - this.start;
	}
	
	/**
	 * Sets the total time.
	 * @param time
	 */
	public void setTime(long time) {
		if(time < 0) time = 0;
		this.time = time;
	}
	
	/**
	 * Gets the total time elapsed in nanoseconds.
	 * @return The total time elapsed in nanoseconds.
	 */
	public long getTime() {
		return this.time;
	}
	
	/**
	 * Gets the runtime tracker name.
	 * @return The runtime tracker name.
	 */
	public String getName() {
		return name;
	}
	
	@Override
	public int hashCode() {
		return name.hashCode();
	}
	
	private String spaces(int length) {
		StringBuilder s = new StringBuilder();
		for(int i = 0; i < length; i++) {
			s.append(" ");
		}
		return s.toString();
	}
	
	@Override
	public String toString() {
		if(this.getLevel() == 0) {
			for(RuntimeTracker child : this.children) {
				this.time += child.getTime();
			}
		}
		int length = 36 - this.getLevel() * 3 - this.getName().length();
		if(length < 0) length = 0;
		return this.name.replace(":", ":" + spaces(length) + String.format("%9.2fs\n", this.getTime()*1e-9));
	}
	
	/**
	 * Returns a string that represents the full hierarchy of a runtime tracker, 
	 * including all the downhill runtime trackers to the leaf runtime trackers.
	 * @return
	 */
	public String trakerWithFullHierarchy() {
		StringBuilder buffer = new StringBuilder();
		appendFullHierarchy(buffer, "", "");
		return buffer.toString();
	}
	
	private void appendFullHierarchy(StringBuilder buffer, String prefix, String childPrefix) {
		buffer.append(prefix);
		buffer.append(this.toString());
		if(this.children != null) {
			for (Iterator<RuntimeTracker> it = children.iterator(); it.hasNext();) {
				RuntimeTracker next = it.next();
				if (it.hasNext()) {
				    next.appendFullHierarchy(buffer, childPrefix + "\u251c\u2500 ", childPrefix + "\u2502  ");
				} else {
				    next.appendFullHierarchy(buffer, childPrefix + "\u2514\u2500 ", childPrefix + "   ");
				}
			}
		}
	}
	
	/**
	 * Returns a string representing a runtime tracker and its child trackers.
	 * @return
	 */
	public String trackerWithOneLevelChidren() {
		StringBuilder buffer = new StringBuilder();
		buffer.append(this.toString());
		if(this.children != null) {
			int id = 0;
			for(RuntimeTracker child : this.children) {
                if(id < this.children.size() - 1) buffer.append("\u251c\u2500 " + child);
                else buffer.append("\u2514\u2500 " + child);
				id++;
			}
		}
		return buffer.toString();
	}
	
}
