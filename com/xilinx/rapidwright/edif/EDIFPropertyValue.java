/*
 * 
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
package com.xilinx.rapidwright.edif;

import java.io.IOException;
import java.io.Writer;

/**
 * Represents the EDIF property value construct.  Currently supports: 
 * string, integer and boolean.
 * Created on: May 11, 2017
 */
public class EDIFPropertyValue {

	private EDIFValueType type;
	
	private String value;

	public EDIFPropertyValue(){
		
	}
	
	public EDIFPropertyValue(String value, EDIFValueType type){
		this.value = value;
		this.type = type;
	}
	
	/**
	 * @return the type
	 */
	public EDIFValueType getType() {
		return type;
	}

	/**
	 * @param type the type to set
	 */
	public void setType(EDIFValueType type) {
		this.type = type;
	}

	/**
	 * @return the value
	 */
	public String getValue() {
		return value;
	}

	/**
	 * @param value the value to set
	 */
	public void setValue(String value) {
		this.value = value;
	}
	
	public void writeEDIFString(Writer wr) throws IOException{
		wr.write("(");
		wr.write(type + " ");
		if(type == EDIFValueType.STRING){
			wr.write("\"");
			wr.write(value);
			wr.write("\"");
		}else if(type == EDIFValueType.BOOLEAN){
			wr.write("(");
			wr.write(value);
			wr.write(")");
		}else{
			wr.write(value);
		}
		wr.write(")");
	}
}
