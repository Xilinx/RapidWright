package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A customized Timer class, providing start and stop methods for recording total elapsed time of a process. 
 * Each Timer Object should be created at least with a name. 
 * It also supports a user case of {@link TimerTree} instance for runtime analysis of an entire program.
 */
public class Timer {
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

	/**
	 * Gets the level of this timer.
	 * @return
	 */
	public short getLevel() {
		return level;
	}

	/**
	 * Sets the level (depth) of this timer in the tree.
	 * @param level
	 */
	public void setLevel(short level) {
		this.level = level;
	}

	/**
	 * Gets the child timers.
	 * @return
	 */
	public List<Timer> getChildren() {
		return children;
	}

	/**
	 * Adds a child timer.
	 * @param timer The child timer.
	 */
	public void addChild(Timer timer) {
		if(!this.children.contains(timer)) {
			this.children.add(timer);
			if(timer.level == 0) {
				timer.setLevel((short) (this.getLevel() + 1));
			}
		}
	}
	
	public void start() {
		this.start = System.nanoTime();
	}
	
	/**
	 * Stops the timer and stores the total time elapsed in nanoseconds.
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
	 * Gets the timer name.
	 * @return The timer name.
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
			for(Timer child : this.children) {
				this.time += child.getTime();
			}
		}
		int length = 31 - this.getLevel() * 3 - this.getName().length();
		if(length < 0) length = 0;
		return this.name.replace(":", ":" + spaces(length) + String.format("%9.2fs\n", this.getTime()*1e-9));
	}
	
	/**
	 * Returns a string that represents the full hierarchy of this timer, including all the downhill timers to the leaf timers.
	 * @return
	 */
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
	
	/**
	 * Returns a string representing this timer and its child timers.
	 * @return
	 */
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
