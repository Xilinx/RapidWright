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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

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
	 * In situations where the type is an integer, this method will parse the verilog-syntax
	 * integer format to get an integer value and return it.
	 * @return The integer value of this property, or null if it is not an integer or failed to
	 * parse.
	 */
	public Integer getIntValue() {
		if(type != EDIFValueType.STRING) {
			return null;
		}
		int radix = 10;
		boolean lastCharWasTick = false;
		boolean isSigned = false;
		for(int i=0; i < value.length(); i++) {
			char c = value.charAt(i);
			if(lastCharWasTick) {
				switch (c) {
					case 'b':
					case 'B':
						radix = 2;
						break;
					case 'o':
					case 'O':
						radix = 8;
						break;
					case 'd':
					case 'D':
						radix = 10;
						break;
					case 'h':
					case 'H':
						radix = 16;
						break;
					case 's':
					case 'S':
						isSigned = true;
						continue;
				}
				if(isSigned) {
					return Integer.parseInt(value.substring(i+1), radix);
				}
				return Integer.parseUnsignedInt(value.substring(i+1), radix);
				
			}
			if(c == '\'') {
				lastCharWasTick = true;
			}
		}

		return Integer.parseUnsignedInt(value);
	}

	/**
	 * In situations where the type is an integer, this method will parse the verilog-syntax
	 * integer format to get a long value and return it.
	 * @return The long integer value of this property, or null if it is not an integer or failed to
	 * parse.
	 */
	public Long getLongValue() {
		if(type != EDIFValueType.STRING) {
			return null;
		}
		int radix = 10;
		boolean lastCharWasTick = false;
		boolean isSigned = false;
		for(int i=0; i < value.length(); i++) {
			char c = value.charAt(i);
			if(lastCharWasTick) {
				switch (c) {
					case 'b':
					case 'B':
						radix = 2;
						break;
					case 'o':
					case 'O':
						radix = 8;
						break;
					case 'd':
					case 'D':
						radix = 10;
						break;
					case 'h':
					case 'H':
						radix = 16;
						break;
					case 's':
					case 'S':
						isSigned = true;
						continue;
				}
				if(isSigned) {
					return Long.parseLong(value.substring(i+1), radix);
				}
				return Long.parseUnsignedLong(value.substring(i+1), radix);
				
			}
			if(c == '\'') {
				lastCharWasTick = true;
			}
		}

		return Long.parseUnsignedLong(value);
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
	
	@Override
	public String toString() {
		return type + "("+value+")";
	}

	public static void main(String[] args) {
		int[] testValues = new int[] {0, 1, -1, 4, 15, -15, 16, 
				Integer.MAX_VALUE, Integer.MIN_VALUE};
		int[] radixValues = new int[] { 2,   8,  10,  16};
		char[] radixChars = new char[] {'b', 'o', 'd', 'h'};
		
		Map<String,Integer> examples = new HashMap<>();
		for(int testValue : testValues) {
			for(int i=0; i < radixValues.length; i++) {
				int radix = radixValues[i];
				char radixChar = radixChars[i];
				for(String signed : new String[] {"s", "S", ""}) {
					if(testValue < 0 && signed.length() != 0) continue;
					String value = signed.length() == 0 ? Integer.toUnsignedString(testValue,radix) :
						Integer.toString(testValue,radix);
					examples.put("32'" + Character.toString(radixChar) + value, testValue);
					examples.put("32'" + Character.toString(Character.toUpperCase(radixChar))
						+ value, testValue);
					examples.put(Integer.toUnsignedString(testValue), testValue);
				}
			}
		}
		
		for(Entry<String,Integer> e : examples.entrySet()) {
			EDIFPropertyValue p = new EDIFPropertyValue();
			p.setType(EDIFValueType.INTEGER);
			p.setValue(e.getKey());
			System.out.print(e.getKey() + " " + e.getValue());
			Integer parsedValue = p.getIntValue();
			System.out.println( " " + parsedValue);
			if(!e.getValue().equals(parsedValue)) {
				throw new RuntimeException("ERROR: Couldn't parse test value " + e.getKey());
			}
		}
		
	}
}
