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

import java.io.Serializable;
import java.util.Arrays;

/**
 * A helper class to help remove duplicate objects and reduce memory usage and file
 * size of the Device class. 
 * @author Chris Lavin
 */
public class TileSources implements Serializable{

	private static final long serialVersionUID = -139462627137160891L;
	/** Sources of the tile */
	public int[] sources;
	
	public TileSources(int[] sources){
		this.sources = sources;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		int hash = 0;
		
		if(sources == null){
			return hash;
		}
		else{
			Arrays.sort(sources);
			for(Integer i : sources){
				hash += i * 7;
			}
			return hash;
		}
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
		TileSources other = (TileSources) obj;
		if(other.sources == null && sources == null){
			return true;
		}
		if(other.sources == null || sources == null){
			return false;
		}
		Arrays.sort(other.sources);
		Arrays.sort(sources);
		for(int i=0; i< sources.length; i++){
			if(sources[i] != other.sources[i]){
				return false;
			}
		}
		return true;
	}
	
	
}
