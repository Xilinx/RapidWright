/* 
 * Original work: Copyright (c) 2010-2011 Brigham Young University
 * Modified work: Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.device.helper;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * This class is a special data structure used for Xilinx FPGA devices to help reduce memory footprint
 * of objects.  It keeps exactly one copy of an object of type E and maintains a unique integer enumeration
 * of each object.  It depends on the type E's equals() and hashCode() function to determine uniqueness.
 * @author Chris Lavin
 * Created on: Apr 30, 2010
 * @param <E> The type of object to use.
 */
public class HashPool<E> extends HashMap<Integer,ArrayList<E>> {

	private static final long serialVersionUID = -7643508400771696765L;

	private ArrayList<E> enumerations;
	
	private HashMap<E,ArrayList<Integer>> enumerationMap;
	
	public HashPool(){
		super();
		enumerations = new ArrayList<E>();
		enumerationMap = new HashMap<E,ArrayList<Integer>>();
	}
	
	private void addToEnumerationMap(E obj, Integer enumeration){
		ArrayList<Integer> enumerationMatches = enumerationMap.get(obj);
		if(enumerationMatches == null){
			enumerationMatches = new ArrayList<Integer>();
			enumerationMatches.add(enumeration);
			enumerationMap.put(obj, enumerationMatches);
		}
		else{
			enumerationMatches.add(enumeration);
		}
	}
	
	/**
	 * Gets the Integer enumeration of the object based on the HashPool.
	 * @param obj The object to get an enumeration value for.
	 * @return The enumeration value of the object obj, or -1 if none exists.
	 */
	public Integer getEnumerationValue(E obj){
		ArrayList<Integer> enumerationMatches = enumerationMap.get(obj);
		if(enumerationMatches == null){
			System.out.println("Object does not have enumeration value: " + obj.toString() + " in class: " + this.getClass().getCanonicalName());
			throw new IllegalArgumentException();
			//return -1;
		}
		else{
			for(Integer i : enumerationMatches){
				if(enumerations.get(i) == null && obj == null){
					return i;
				}
					
				if(enumerations.get(i).equals(obj)){
					return i;
				}
			}
		}
		System.out.println("Object does not have enumeration value: " + obj.toString() + " in class: " + this.getClass().getCanonicalName());
		throw new IllegalArgumentException();
		//return -1;
	}
	
	/**
	 * Adds the object to the pool if an identical copy doesn't already exist.
	 * @param obj The object to be added
	 * @return The unique object contained in the HashPool
	 */
	public E add(E obj){
		int hash = obj == null ? 0 : obj.hashCode();
		ArrayList<E> hashMatches = get(hash);
		if(hashMatches == null){
			hashMatches = new ArrayList<E>();
			hashMatches.add(obj);
			put(hash, hashMatches);
			addToEnumerationMap(obj,enumerations.size());
			enumerations.add(obj);
			return obj;
		}
		else{
			for(E e :hashMatches){
				if(e.equals(obj)){
					return e;
				}
			}
			hashMatches.add(obj);
			put(hash, hashMatches);
			addToEnumerationMap(obj,enumerations.size());
			enumerations.add(obj);
			return obj;
		}
	}
	
	/**
	 * Checks the HashPool if it contains an equal object to obj as defined by the equals() method.
	 * @param obj The object to check for.
	 * @return True if the HashPool contains the object, false otherwise.
	 */
	public boolean contains(E obj){
		ArrayList<E> hashMatches = get(obj.hashCode());
		if(hashMatches == null){
			return false;
		}
		for(E e :hashMatches){
			if(e.equals(obj)){
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Gets the identical object in the HashPool that is equal by definition of the equals()
	 * method.  Returns null if no equivalent object exists in pool
	 * @param obj The object to find in the pool
	 * @return The object in the pool that is equal to obj, null otherwise.
	 */
	public E find(E obj){
		ArrayList<E> hashMatches = get(obj.hashCode());
		if(hashMatches == null){
			return null;
		}
		for(E e :hashMatches){
			if(e.equals(obj)){
				return e;
			}
		}
		return null;
	}

	/**
	 * @return the enumerations
	 */
	public ArrayList<E> getEnumerations() {
		return enumerations;
	}

	/**
	 * @param enumerations the enumerations to set
	 */
	public void setEnumerations(ArrayList<E> enumerations) {
		this.enumerations = enumerations;
	}

	/**
	 * @return the enumerationMap
	 */
	public HashMap<E, ArrayList<Integer>> getEnumerationMap() {
		return enumerationMap;
	}

	/**
	 * @param enumerationMap the enumerationMap to set
	 */
	public void setEnumerationMap(HashMap<E, ArrayList<Integer>> enumerationMap) {
		this.enumerationMap = enumerationMap;
	}
	
}
