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

package com.xilinx.rapidwright.design.tools;

/**
 * Helper class to parse LUT equations.
 * @author clavin
 */
class LUTEquationEvaluator {
	
	public static final char XOR = '^';
	public static final char XOR2 = '@';
	public static final char AND = '&';
	public static final char AND2 = '*';
	public static final char AND3 = '.';
	public static final char OR = '+';
	public static final char OR2 = '|';
	public static final char NOT = '~';
	public static final char NOT2 = '!';
	
	private int pos = -1;
	
	private char ch;
	
	private String equation;
	
	private int row;

	public LUTEquationEvaluator(String equation){
		this.equation = equation;
	}
	
	public void setRow(int row){
		this.row = row;
	}
	
    private void nextChar() {
        ch = (char) ((++pos < equation.length()) ? equation.charAt(pos) : -1);
    }

    private boolean checkNextChar(char checkFor) {
        while (ch == ' ' || ch == '\t' || ch == '=' || ch == 'O') nextChar();
        if (ch == checkFor) {
            nextChar();
            return true;
        }
        return false;
    }
    
    public boolean eval(int row) {
    	pos = 0;
    	setRow(row);
        nextChar();
        boolean x = evalOR();
        nextChar();
        if (pos < equation.length()) 
        	throw new RuntimeException("Unexpected: '" + (char)ch + 
        			"' in LUT equation '" + equation + "'");
        return x;
    }

    boolean evalOR() {
        boolean x = evalXORAND();
        for (;;) {
            if (checkNextChar(OR) || checkNextChar(OR2)) x |= evalXORAND(); // OR
            else return x;
        }
    }

    boolean evalXORAND() {
        boolean x = evalLiteral();
        for (;;) {
            if (checkNextChar(XOR) || checkNextChar(XOR2)) 
            	x ^= evalLiteral(); // XOR
            else if (checkNextChar(AND) || checkNextChar(AND2) || checkNextChar(AND3)) 
            	x &= evalLiteral(); // AND

            else return x;
        }
    }

    boolean evalLiteral() {
    	boolean invert = false;
    	if (checkNextChar(NOT) || checkNextChar(NOT2)) invert = true;
        boolean x = false;
        
        if (checkNextChar('(')) { 
            x = evalOR();
            checkNextChar(')');
        } else if (ch == '0') {
        	x = false;
        } else if (ch == '1') {
        	x = true;
        } else if (ch == 'I') { 
        	nextChar();
        	x = LUTTools.getBit(row, ch - 48) == 0 ? false : true;
        	nextChar();
        } else {
            throw new RuntimeException("Unexpected: '" + (char)ch +
            		"' in LUT equation '" + equation + "'");
        }

        return invert ? !x : x;
    }        
}
