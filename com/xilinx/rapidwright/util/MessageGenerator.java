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

import java.io.IOException;

/**
 * Common class for generating messages.
 * @author clavin
 *
 */
public class MessageGenerator{
	
	/**
	 * Used as a general way to create an error message and send it to
	 * std.err. Prints the stack trace from this point and exits the 
	 * program.
	 * @param msg The message to print to standard error
	 */
	public static void generalErrorAndExit(String msg){
		generalError(msg);
		System.exit(1);
	}
	
	/**
	 * Used as a general way to create an error message and send it to
	 * std.err. Prints the stack trace from this point.
	 * @param msg The message to print to standard error
	 */
	public static void generalError(String msg){
		System.err.println(msg);
		Exception e = new Exception();
		e.printStackTrace();		
	}
	
	/**
	 * Used as a general way to create an error message and send it to
	 * std.err. Exits the program.
	 * @param msg The message to print to standard error
	 */
	public static void briefErrorAndExit(String msg){
		briefError(msg);
		System.exit(1);
	}
	
	/**
	 * Used as a general way to create an error message and send it to
	 * std.err. 
	 * @param msg The message to print to standard error
	 */
	public static void briefError(String msg){
		System.err.println(msg);
	}
	
	/**
	 * Used as a general way to create a message and send it to
	 * std.out. Exits the program with return value of 0.
	 * @param msg The message to print to standard outs
	 */
	public static void briefMessageAndExit(String msg){
		briefMessage(msg);
		System.exit(0);
	}
	
	/**
	 * Used as a general way to create a message and send it to
	 * std.out. 
	 * @param msg The message to print to standard out
	 */
	public static void briefMessage(String msg){
		System.out.println(msg);
	}
	
	/**
	 * Prompts the user to press any key to continue execution.
	 */
	public static void waitOnAnyKey(){
		System.out.print("Press Enter to continue...");
		waitOnAnyKeySilent();
	}
	
	/**
	 * Pauses execution until a key is pressed.
	 */
	public static void waitOnAnyKeySilent(){
		try{
			System.in.read();
		}
		catch(IOException e){
			e.printStackTrace();
		}
	}
	
	/**
	 * Creates a simple string from an array of objects.  This method
	 * calls the toString() on each object in the array.
	 * @param objs The object to create a string from. 
	 * @return A string of all the objects toStrings() concatenated 
	 * with spaces in between.
	 */
	public static String createStringFromArray(Object[] objs){
		StringBuilder sb = new StringBuilder();
		for(Object o : objs){
			sb.append(o.toString() + " ");
		}
		return sb.toString();
	}

	/**
	 * Prints a generic header to standard out to separate operations.
	 * @param s
	 */
	public static void printHeader(String s){
		String bar = "==============================================================================";
		String left;
		String right;
		double whiteSpace = (72 - s.length())/2.0;
		left = MessageGenerator.makeWhiteSpace((int)(whiteSpace));
		right = MessageGenerator.makeWhiteSpace((int)(whiteSpace+0.5));
		System.out.println(bar);
		System.out.println("== "+ left + s + right +" ==");
		System.out.println(bar);
	}

	/**
	 * Creates a whitespace string with length number of spaces.
	 * @param length Number of spaces in the string.
	 * @return The newly created whitespace string.
	 */
	public static String makeWhiteSpace(int length){
		if (length < 1)
			return "";
		StringBuilder sb = new StringBuilder(length);
		for(int i=0; i<length; i++){
			sb.append(" ");
		}
		return sb.toString();
	}
	
	/**
	 * This will prompt the user to type y or n to either continue
	 * with a process or to exit.
	 */
	public static void promptToContinue(){
		System.out.print("Would you like to continue(y/n)? ");
		int ch;
		try{
			ch = System.in.read();
			while(ch != 'y' && ch != 'n' && ch != 'Y' && ch != 'N'){
				while((ch = System.in.read()) != '\n');
				System.out.print("Would you like to continue(y/n)? ");
				ch = System.in.read();
			}
			if(ch == 'y' || ch == 'Y'){
				return;
			}
			else{
				System.exit(1);
			}
		}
		catch(IOException e){
			briefErrorAndExit("Error reading user input");
		}
	}
	
	/**
	 * This will prompt the user to type y or n to either continue
	 * with a process or to exit.
	 */
	public static void agreeToContinue(){
		System.out.print("(y/n)? ");
		int ch;
		try{
			ch = System.in.read();
			while(ch != 'y' && ch != 'n' && ch != 'Y' && ch != 'N'){
				while((ch = System.in.read()) != '\n');
				System.out.print("Would you like to continue(y/n)? ");
				ch = System.in.read();
			}
			if(ch == 'y' || ch == 'Y'){
				return;
			}
			else{
				System.exit(1);
			}
		}
		catch(IOException e){
			briefErrorAndExit("Error reading user input");
		}
	}
}
