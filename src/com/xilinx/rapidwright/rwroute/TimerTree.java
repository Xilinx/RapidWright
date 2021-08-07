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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class TimerTree {
	Map<String, Timer> timers;
	private Timer root;
	
	boolean verbose = false;
	
	public TimerTree(String rootName, boolean verbose) {
		this.verbose = verbose;
		this.timers = new HashMap<>();
		/** three initialized timers*/
		this.root = new Timer(rootName, (short) 0);
		this.timers.put(this.root.getName(), this.root);
	}
	
	/**
	 * Creates a timer with its name and its parent name
	 * @param name Name of a timer
	 * @param parent The parent timer name
	 * @return A created timer under the name
	 */
	public Timer createAddTimer(String name, String parent) {
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
	
	public static Timer createStandAloneTimer(String name) {
		return new Timer(name);
	}

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

	public static class Timer {
		private String name;
		private long time;
		private long start;
		private short level;
		private List<Timer> children;
		
		public Timer(String name) {
			this.name = name + ":";
			this.time = 0;
		}
		
		public Timer(String name, short level) {
			this.name = name + ":";
			this.time = 0;
			this.level = level;
			if(this.getLevel() * 3 + this.getName().length() > 31) {
				System.out.println("\nWARNING: Timer name too long: " + name + ". Ideal max string length: " + (30 - this.getLevel() * 3));
			}
			this.children = new ArrayList<>();
		}

		public short getLevel() {
			return level;
		}

		public void setLevel(short level) {
			this.level = level;
		}

		public List<Timer> getChildren() {
			return children;
		}
	
		public void addChild(Timer timer) {
			if(!this.children.contains(timer)) {
				this.children.add(timer);
			}
		}
		
		public void start() {
			this.start = System.nanoTime();
		}
		
		public void stop() {
			this.time += System.nanoTime() - this.start;
		}
		
		public void setTime(long time) {
			this.time = time;
		}
		
		public long getTime() {
			return this.time;
		}
		
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
				for(Timer child : this.children) {
					this.time += child.getTime();
				}
			}
			int length = 31 - this.getLevel() * 3 - this.getName().length();
			if(length < 0) length = 0;
    		return this.name.replace(":", ":" + spaces(length) + String.format("%9.2fs\n", this.getTime()*1e-9));
		}
		
		public String fullHierarchyTimerTree() {
			StringBuilder buffer = new StringBuilder();
			appendThisAndChildren(buffer, "", "");
			return buffer.toString();
		}
		
		private void appendThisAndChildren(StringBuilder buffer, String prefix, String childPrefix) {
			buffer.append(prefix);
	        buffer.append(this.toString());
	        if(this.children != null) {
	        	for (Iterator<Timer> it = children.iterator(); it.hasNext();) {
	                Timer next = it.next();
	                if (it.hasNext()) {
	                    next.appendThisAndChildren(buffer, childPrefix + "├─ ", childPrefix + "│  ");
	                } else {
	                    next.appendThisAndChildren(buffer, childPrefix + "└─ ", childPrefix + "   ");
	                }
	            }
	        }
		}
		
		public String timerTreeWithOneLevelChidren() {
			StringBuilder buffer = new StringBuilder();
	        buffer.append(this.toString());
	        if(this.children != null) {
	        	int id = 0;
	        	for(Timer child : this.children) {
	        		if(id < this.children.size() - 1) buffer.append("├─ " + child);
	        		else buffer.append("└─ " + child);
	        		id++;
	        	}
	        }
			return buffer.toString();
		}
	}
}