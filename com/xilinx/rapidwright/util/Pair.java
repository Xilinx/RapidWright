/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
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

package com.xilinx.rapidwright.util;

/**
 * Simple class to group two items together.
 * @author clavin
 *
 * @param <T>
 */
public class Pair<T,U> {

	private T first;
	
	private U second;
	
	public Pair(){
		
	}
	
	public Pair(T first, U second){
		this.first = first;
		this.second = second;
	}

	public T getFirst() {
		return first;
	}

	public void setFirst(T first) {
		this.first = first;
	}

	public U getSecond() {
		return second;
	}

	public void setSecond(U second) {
		this.second = second;
	}

	public String toString(){
		return "<" + first.toString() + "," + second.toString() + ">";
	}
	
	/**
	 * Combines two arrays of equal length into an array of Pair
	 * objects for convenience in loop or parameter passing.
	 * @param t First array, stored in the first location
	 * @param u Second array, stored in the second location
	 * @return An array of populated Pair objects or null if input was invalid.
	 */
	public static <V,W> Pair<V,W>[] zip(V[] t, W[] u){
		if(t==null || u == null) return null;
		if(t.length != u.length) return null;
		@SuppressWarnings("unchecked")
		Pair<V,W>[] arr = new Pair[t.length];
		for(int i=0; i < t.length; i++){
			arr[i].first = t[i];
			arr[i].second = u[i];
		}
		return arr;
	}
	
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((first == null) ? 0 : first.hashCode());
		result = prime * result + ((second == null) ? 0 : second.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Pair<?,?> other = (Pair<?,?>) obj;
		if (first == null) {
			if (other.first != null)
				return false;
		} else if (!first.equals(other.first))
			return false;
		if (second == null) {
			if (other.second != null)
				return false;
		} else if (!second.equals(other.second))
			return false;
		return true;
	}
}
