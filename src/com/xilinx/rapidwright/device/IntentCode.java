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
package com.xilinx.rapidwright.device;

import java.util.Arrays;

/**
 * List of wire/node annotations available on devices in Vivado.
 * These can be accessed by get_property (INTENT_CODE|INTENT_CODE_NAME) ($wire|$node) 
 * Created on: Oct 19, 2015
 */
public enum IntentCode {
	// DO NOT CHANGE ORDER OF CODES
	
	// UltraScale
	INTENT_DEFAULT,
    NODE_OUTPUT,
    NODE_DEDICATED,
    NODE_GLOBAL_VDISTR,
    NODE_GLOBAL_HROUTE,
    NODE_GLOBAL_HDISTR,
    NODE_PINFEED,
    NODE_PINBOUNCE,
    NODE_LOCAL,
    NODE_HLONG,
    NODE_SINGLE,
    NODE_DOUBLE,
    NODE_HQUAD,
    NODE_VLONG,
    NODE_VQUAD,
    NODE_OPTDELAY,
    NODE_GLOBAL_VROUTE,
    NODE_GLOBAL_LEAF,
    NODE_GLOBAL_BUFG,
    
    //UltraScale+  
    NODE_LAGUNA_DATA,
    NODE_CLE_OUTPUT,
    NODE_INT_INTERFACE,
    NODE_LAGUNA_OUTPUT,

    //Series 7
    GENERIC,
    DOUBLE,
    INPUT,
    BENTQUAD,
    SLOWSINGLE,
    CLKPIN,
    GLOBAL,
    OUTPUT,
    PINFEED,
    BOUNCEIN,
    LUTINPUT,
    IOBOUTPUT,
    BOUNCEACROSS,
    VLONG,
    OUTBOUND,
    HLONG,
    PINBOUNCE,
    BUFGROUT,
    PINFEEDR,
    OPTDELAY,
    IOBIN2OUT,
    HQUAD,
    IOBINPUT,
    PADINPUT,
    PADOUTPUT,
    VLONG12,
    HVCCGNDOUT,
    SVLONG,
    VQUAD,
    SINGLE,
    BUFINP2OUT,
    REFCLK;


    public static boolean isLongWire(Tile tile, int wire){
    	return isLongWire(tile.getWireIntentCode(wire).ordinal());
    }
    
    public static boolean isLongWire(int intentCode){
    	IntentCode ic = values()[intentCode];
    	return NODE_VLONG == ic || NODE_HLONG == ic || VLONG == ic || HLONG == ic || VLONG12 == ic || SVLONG == ic;
    }
    
    public static boolean isUltraScaleClocking(Tile tile, int wire){
    	return values()[tile.getWireIntentCode(wire).ordinal()].isUltraScaleClocking();	
    }
    
    public boolean isUltraScaleClocking(){
    	return NODE_GLOBAL_HDISTR == this || NODE_GLOBAL_VDISTR == this || NODE_GLOBAL_HROUTE == this || NODE_GLOBAL_LEAF == this || NODE_GLOBAL_VROUTE == this;
    }
    
    public boolean isUltraScaleClockRouting(){
    	return NODE_GLOBAL_HROUTE == this || NODE_GLOBAL_VROUTE == this;
    }
    
    public boolean isUltraScaleClockDistribution(){
    	return NODE_GLOBAL_HDISTR == this || NODE_GLOBAL_VDISTR == this;
    }
    
    private static final int SERIES7_START_IDX = 23;
    private static final int SERIES7_END_IDX = SERIES7_START_IDX + 33 - 1;

    private static final int ULTRASCALE_START_IDX = 0;
    private static final int ULTRASCALE_END_IDX = ULTRASCALE_START_IDX + 20 - 1;

    private static final int ULTRASCALEPLUS_START_IDX = ULTRASCALE_START_IDX;
    private static final int ULTRASCALEPLUS_END_IDX = ULTRASCALE_END_IDX + 4;

    
    /**
     * Returns an array of the intent codes specific the provided series.
     * @param s The series (or generation) 
     * @return The array of relevant intent codes or null if not available.
     */
    public static IntentCode[] getIntentCodesBySeries(Series s){
    	if(s == Series.Series7){
    		return Arrays.copyOfRange(IntentCode.values(), SERIES7_START_IDX, SERIES7_END_IDX);
    	}else if(s == Series.UltraScale){
    		return Arrays.copyOfRange(IntentCode.values(), ULTRASCALE_START_IDX, ULTRASCALE_END_IDX);
    	}else if(s == Series.UltraScalePlus){
    		return Arrays.copyOfRange(IntentCode.values(), ULTRASCALEPLUS_START_IDX, ULTRASCALEPLUS_END_IDX);
    	} 
    	return null;
    }
    
    public static void printIntentCodesBySeries(Series s){
    	System.out.println(s.name() + ":");
    	for(IntentCode i : getIntentCodesBySeries(s)){
			System.out.printf("%3d. %s\n", i.ordinal(), i.name());
		}
    }
    
    public static void main(String[] args) {
    	for(Series s : Series.values()){
    		printIntentCodesBySeries(s);
    	}
	}
}
