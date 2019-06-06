/* 
 * Copyright (c) 2017 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
/**
 * 
 */
package com.xilinx.rapidwright.tests;

import java.util.ArrayList;

import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * Simple tool for measuring code runtime and memory and reporting.
 * 
 * Created on: Jun 29, 2016
 */
public class CodePerfTracker {

	private String name;
	
	private ArrayList<Long> runtimes;
	
	private ArrayList<Long> memUsages;
	
	private ArrayList<String> segmentNames; 
	
	private Runtime rt;
	
	private int maxRuntimeSize = 9;
	private int maxUsageSize = 10;
	private int maxSegmentNameSize = 24;
	private boolean printProgress = true;
	private boolean trackMemoryUsingGC = false;

	public static final CodePerfTracker SILENT;
	
	static {
		SILENT = new CodePerfTracker("",false);
		SILENT.setVerbose(false);
	}
	
	private static final boolean GLOBAL_DEBUG = true;

	private boolean verbose = true;
	
	
	public CodePerfTracker(String name){
		super();
		init(name,true);
	}
	
	public CodePerfTracker(String name, boolean printProgress){
		init(name,printProgress);
	}
	
	public CodePerfTracker(String name, boolean printProgress, boolean isVerbose){
		super();
		verbose = isVerbose;
		init(name,true);
	}
	
	public void init(String name, boolean printProgress){
		if(!GLOBAL_DEBUG) return; 
		this.name = name;
		this.printProgress = printProgress;
		runtimes = new ArrayList<Long>();
		memUsages = new ArrayList<Long>();
		segmentNames = new ArrayList<String>();
		rt = Runtime.getRuntime();		
		if(this.printProgress && isVerbose()){
			MessageGenerator.printHeader(name);
		}
	}
	
	public void updateName(String name){
		this.name = name;
	}
	
	private int getSegmentIndex(String segmentName){
		int i=0;
		for(String name : segmentNames){
			if(name.equals(segmentName)){
				return i;
			}
			i++;
		}
		return -1;
	}
	
	public Long getRuntime(String segmentName){
		int i = getSegmentIndex(segmentName);
		return i == -1 ? null : runtimes.get(i);
	}
	
	public Long getMemUsage(String segmentName){
		int i = getSegmentIndex(segmentName);
		return i == -1 ? null : memUsages.get(i);		
	}
	
	public boolean isVerbose() {
		return verbose;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	public CodePerfTracker start(String segmentName){
		if(!GLOBAL_DEBUG) return this;
		int idx = runtimes.size();
		if(isUsingGCCallsToTrackMemory()) System.gc();
		long currUsage = rt.totalMemory() - rt.freeMemory();
		segmentNames.add(segmentName);
		memUsages.add(currUsage);
		runtimes.add(System.nanoTime());
		return this;
	}
	
	public CodePerfTracker stop(){
		if(!GLOBAL_DEBUG) return this;
		long end = System.nanoTime();
		int idx = runtimes.size()-1;
		if(idx < 0) return null;
		long start = runtimes.get(idx);
		if(isUsingGCCallsToTrackMemory()) System.gc();
		long currUsage = (rt.totalMemory() - rt.freeMemory());
		long prevUsage = memUsages.get(idx);
		
		runtimes.set(idx, end-start);
		memUsages.set(idx,	currUsage-prevUsage);
		
		if(printProgress && isVerbose()){
			print(idx);
		}
		return this;
	}
	
	private void print(int idx){
		if(isUsingGCCallsToTrackMemory()){
			System.out.printf("%"+maxSegmentNameSize+"s: %"+maxRuntimeSize+".3fs %"+maxUsageSize+".3fMBs\n", 
				segmentNames.get(idx), 
				(runtimes.get(idx))/1000000000.0,
				(memUsages.get(idx))/(1024.0*1024.0));
		} else {
			System.out.printf("%"+maxSegmentNameSize+"s: %"+maxRuntimeSize+".3fs\n", 
					segmentNames.get(idx), 
					(runtimes.get(idx))/1000000000.0);
		}
	}
	
	/**
	 * Gets the flag that determines if System.gc() is called at beginning and end
	 * of each segment to more accurately track memory usage. 
	 * @return True if this CodePerfTracker is using System.gc() calls at beginning and end
	 * of segments to obtain more accurate memory usages.  False otherwise.
	 */
	public boolean isUsingGCCallsToTrackMemory() {
		return trackMemoryUsingGC;
	}

	/**
	 * By setting this flag, more accurate memory usage numbers can be captured.
	 * The tradeoff is that System.gc() calls can increase total runtime of the program
	 * (although the call is not included in measured runtime).
	 * 
	 * @param useGCCalls Sets a flag that uses System.gc() calls at the beginning and end
	 * of segements to obtain more accurate memory usages.
	 */
	public void useGCToTrackMemory(boolean useGCCalls) {
		this.trackMemoryUsingGC = useGCCalls;
	}

	private void addTotalEntry(){
		long totalRuntime = 0L;
		long totalUsage = 0L;
		maxSegmentNameSize = 0;
		for(int i=0; i < runtimes.size(); i++){
			totalRuntime += runtimes.get(i);
			totalUsage += memUsages.get(i);
			int len = segmentNames.get(i).length() + 1;
			if(len > maxSegmentNameSize) maxSegmentNameSize = len;
		}
		runtimes.add(totalRuntime);
		memUsages.add(totalUsage);
		String totalName = isUsingGCCallsToTrackMemory() ? "*Total*" : " [No GC] *Total*";  
		segmentNames.add(totalName);
		if(maxSegmentNameSize < totalName.length()) maxSegmentNameSize = totalName.length();
	}
	
	public void printSummary(){
		if(!GLOBAL_DEBUG) return;
		if(!isVerbose()) return;
		if(!printProgress) MessageGenerator.printHeader(name);
		addTotalEntry();
		int start = printProgress ? runtimes.size()-1 : 0;
		for(int i=start; i < runtimes.size(); i++){
			if(i == runtimes.size()-1){
				System.out.println("------------------------------------------------------------------------------");
			}
			print(i);
		}
	}
}
