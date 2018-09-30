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
package com.xilinx.rapidwright.util;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Utility class to help {@link CompareRouteStatusReports}.
 * Created on: Jan 22, 2016
 */
public class RouteStatus implements Comparable<RouteStatus> {

	private String name;
	
	private String status;
	
	private ArrayList<ArrayList<String>> subTrees;

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	/**
	 * @param name the name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * @return the status
	 */
	public String getStatus() {
		return status;
	}

	/**
	 * @param status the status to set
	 */
	public void setStatus(String status) {
		this.status = status;
	}

	/**
	 * @return the subTrees
	 */
	public ArrayList<ArrayList<String>> getSubTrees() {
		return subTrees;
	}

	/**
	 * @param subTrees the subTrees to set
	 */
	public void setSubTrees(ArrayList<ArrayList<String>> subTrees) {
		this.subTrees = subTrees;
	}

	@Override
	public int compareTo(RouteStatus o) {
		return this.getName().compareTo(o.getName());
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + ((status == null) ? 0 : status.hashCode());
		result = prime * result
				+ ((subTrees == null) ? 0 : subTrees.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		RouteStatus other = (RouteStatus) obj;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		if (status == null) {
			if (other.status != null)
				return false;
		} else if (!status.equals(other.status))
			return false;
		if (subTrees == null) {
			if (other.subTrees != null)
				return false;
		} else if (!subTrees.equals(other.subTrees))
			return false;
		return true;
	}

	/**
	 * @param r2
	 */
	public void reportDifferences(RouteStatus r2) {
		boolean printedName = false;
		if(!getStatus().equals(r2.getStatus())){
			if(!printedName) {System.out.println("DIFF: " + getName()); printedName = true;}
			System.out.println("  Status: " + getStatus() + " " + r2.getStatus());
		}
		ArrayList<ArrayList<String>> myTrees = getSubTrees();
		ArrayList<ArrayList<String>> otherTrees = r2.getSubTrees();
		if(myTrees.size() != otherTrees.size()){
			if(!printedName) {System.out.println("DIFF: " + getName()); printedName = true;}
			System.out.println("  SubTree Count: " + myTrees.size() + " " + otherTrees.size());
		}
		
		
		HashSet<String> set = new HashSet<String>();
		for(ArrayList<String> list : myTrees){
			set.addAll(list);
		}
		for(ArrayList<String> list : myTrees){
			for(String other : list){
				boolean success = set.remove(other);
				if(!success){
					if(!printedName) {System.out.println("DIFF: " + getName()); printedName = true;}
					System.out.println("  2: " + other);
				}				
			}
		}
		for(String mine : set){
			if(!printedName) {System.out.println("DIFF: " + getName()); printedName = true;}
			System.out.println("  1: " + mine);				
		}
		
		
		/*
		for(int i=0; i < myTrees.size(); i++){
			if(i >= otherTrees.size()) return;
			ArrayList<String> myTree = myTrees.get(i);
			ArrayList<String> otherTree = otherTrees.get(i);
			HashSet<String> set = new HashSet<String>(myTree);
			for(String other : otherTree){
				boolean success = set.remove(other);
				if(!success){
					if(!printedName) {System.out.println("DIFF: " + getName()); printedName = true;}
					System.out.println("  2: " + other);
				}
			}
			for(String mine : set){
				if(!printedName) {System.out.println("DIFF: " + getName()); printedName = true;}
				System.out.println("  1: " + mine);				
			}
		}*/
		
	}
}
