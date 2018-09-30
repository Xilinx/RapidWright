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
package com.xilinx.rapidwright.util;

import java.util.HashMap;

/**
 * Keeps track of a unique set of Strings.
 * @author clavin
 *
 */
public class StringPool extends HashMap<String,String>{

	private static final long serialVersionUID = 3200270847975999156L;
	
	/**
	 * This gets a unique copy of the String string from the HashMap. 
	 * If the string is not in the HashMap yet, it adds it and returns
	 * the original string. 
	 * @param string The string to make unique.
	 * @return The unique copy of the String string. 
	 */
	public String getUnique(String string){
		if(this.get(string)==null) {
			this.put(string,string);
			return string;
		}
		return this.get(string);
	}
}
