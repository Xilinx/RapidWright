/*
 * Copyright (c) 2019 Xilinx, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.timing;

import java.util.Arrays;

/**
 * A TimingVertex represents a node within the TimingGraph.  It encapsulates slack, arrival time, 
 * required time, and whether it represents a pin on a flip flop.
 */
public class TimingVertex {

    private Float slack;
    private Float arrivalTime;
    private Float requiredTime;
    private String name;
    private String clockName;
    private boolean isFlopInput;
    private boolean isFlopOutput;
    private boolean printed;
    /** The parent TimingVertex that leads to the maximum arrival time of this one*/
    private TimingVertex prev;
    /** This is for an experimental router feature: route a design with clock skew data */
    public static CLKSkewRouteDelay clkSkew;
    /** array of src_dly - CPR for 4 CRs: in order of X2Y2, X2Y3, X3Y2, X3Y3 */
    private short[] arrivalTimes;//
    private short[] requiredTimes;
    private TimingVertex[] prevs;
    private boolean sinkD;
    
    public boolean isSinkD() {
    	return this.sinkD;
    }
    
    public void setSinkD(boolean isSinkD) {
    	this.sinkD = isSinkD;
    }
    
    public static void setCLKSkew(CLKSkewRouteDelay clkskew) {
    	clkSkew = clkskew;
    }
    
    public void setArrivalTimeVector(short value) {
    	if(arrivalTimes == null) {
    		arrivalTimes = new short[4];
    	}
    	Arrays.fill(arrivalTimes, value);
    }
    
    public void setArrivalTimeVector(String cr, short value, TimingVertex prev) {
    	if(arrivalTimes == null) {
    		arrivalTimes = new short[4];
    		Arrays.fill(arrivalTimes, (short)0);
    	}
    	
    	if(prevs == null) {
    		prevs = new TimingVertex[4];
    	}
    	
    	switch(cr) {
    	case "X2Y2":
    		if(value > arrivalTimes[0]) {
    			arrivalTimes[0] = value;
    			prevs[0] = prev;
    		}
    		break;
    	case "X2Y3":
    		if(value > arrivalTimes[1]) {
    			arrivalTimes[1] = value;
    			prevs[1] = prev;
    		}
    		break;
    	case "X3Y2":
    		if(value > arrivalTimes[2]) {
    			arrivalTimes[2] = value;
    			prevs[2] = prev;
    		}
    		break;
    	case "X3Y3":
    		if(value > arrivalTimes[3]) {
    			arrivalTimes[3] = value;
    			prevs[3] = prev;
    		}
    		break;
    	default:
    		break;
    	}
    	
    }
    
    public void setArrivalTimeVector(short[] srcArrs, float edgeDly, TimingVertex src) {
    	if(arrivalTimes == null) {
    		arrivalTimes = new short[4];
    		Arrays.fill(arrivalTimes, (short)0);
    	}
    	
    	if(this.prevs == null) {
    		this.prevs = new TimingVertex[4];
    	}
    	
    	for(int i = 0; i < 4; i++) {
    		if(srcArrs[i] == 0) continue;//do not bother with those that have not been assigned values, which means there is no corresponding source CR
    		short newarr = (short) (srcArrs[i] + edgeDly);
    		if(newarr > this.arrivalTimes[i]) {
    			this.arrivalTimes[i] = newarr;
    			this.prevs[i] = src;
    		}
    	}
    	
    }
    
    public void setRequiredTimeVector(short value) {
    	if(requiredTimes == null) {
    		requiredTimes = new short[4];
    	}
    	Arrays.fill(requiredTimes, value);
    }
    
    public void setRequiredVector(short[] dstReqs, short edgeDly) {
    	if(requiredTimes == null) {
    		requiredTimes = new short[4];
    		Arrays.fill(requiredTimes, (short) (Short.MAX_VALUE/2));
    	}
    	for(int i = 0; i < 4; i++) {
    		if(this.arrivalTimes[i] == 0) continue;
    		short newreq = (short) (dstReqs[i] - edgeDly);
    		if(newreq < requiredTimes[i]) requiredTimes[i] = newreq;
    	}
    }

    /**
     * Creates a vertex for insertion into the TimingGraph.
     * @param name Name is typically the hierarchical logical name of an input or output pin.  In 
     * some cases this timing library is using the EDIFPortInst name for the name.  This is 
     * noteworthy because the EDIFPortInst name may contain square brackets on pins that are indexed
     * slices of a bus (for example some pins on CARRY8 have indices).  The physical name for pins 
     * do not contain square brackets.
     */
    public TimingVertex(String name) {
        this.name = name;
        this.arrivalTimes = new short[4];
        this.isFlopInput = false;
        this.isFlopOutput = false;
        this.printed = false;
    }
    
    /**
     * Create a timing vertex for insertion into the timing graph
     * @param name - name is the cell name of an input or output pin
     * @param isFlopInput - to indicate if it is an input or output
     */
    public TimingVertex(String name, boolean isFlopInput) {
    	this.name = name;
    	this.isFlopInput = isFlopInput;
        this.isFlopOutput = !isFlopInput;
        this.printed = false;
    }

    /**
     * Returns a String representation of this object.
     * @return String representation, which is the name.
     */
    public String toString() {
        return name;
    }

    /**
     * Implements the comparable object interface so that Vertices can be compared. This comparison 
     * is based on the name.
     * @param o Object to be compared to.
     * @return Result of comparing the name if both objects are of TimingVertex type.
     */
    public int compareTo(Object o) {
        if (o instanceof TimingVertex)
            return name.compareTo(((TimingVertex)o).name);
        return -1;
    }

    /**
     * Implements the comparable object interface so that vertices can be compared.  This checks 
     * whether the names of two objects are equal.
     * @param o Object to be compared to.
     * @return Result of comparing the name if both objects are of TimingVertex type for equality.
     */
    public boolean equals(Object o) {
        if (o instanceof TimingVertex)
            return ((TimingVertex)o).name.equals(name);
        return false;
    }

    /**
     * This method is used when storing TimingVertices in HashTables and HashMaps.  This method 
     * returns a hashed representation based on the name.
     * @return Returns a hash code based on the name.
     */
    public int hashCode() {
        return name.hashCode();
    }

    /**
     * Sets the slack value stored at this vertex.
     * @param slack Slack in picoseconds.
     */
    public void setSlack(float slack) {
        this.slack = slack;
    }

    /**
     * Sets the required time stored at this vertex.
     * @param requiredTime Required time in picoseconds.
     */
    public void setRequiredTime(float requiredTime) {
        this.requiredTime = requiredTime;
        if (requiredTime == 0) {
            //System.err.println("Setting required time to zero for:"+this);
        } else {
            //System.out.println("Setting required time for "+this+" to:"+requiredTime);
        }
        this.slack = requiredTime - arrivalTime;
    }
    
	public void setMinRequiredTime(float requiredTime){
    	if(this.requiredTime == null){
    		this.requiredTime = requiredTime;
    	}else{
    		if(this.requiredTime > requiredTime){
    			this.requiredTime = requiredTime;
    		}
    	}
    	
    }
    
    /**
     * Sets the arrival time stored at this vertex WHEN the new arrival time is larger than the current.
     * @param arrivalTime Arrival time in picoseconds.  This is the sum of delay edges leading to 
     * this vertex 
     */
    public void setMaxArrivalTime(float arrivalTime){
    	if(this.arrivalTime == null){
    		this.arrivalTime = arrivalTime;
    	}else if(this.arrivalTime < arrivalTime){
    		this.arrivalTime = arrivalTime;
    	}
    }
    
    public void setMaxArrivalTime(float arrivalTime, TimingVertex prev){
    	if(this.arrivalTime == null){
    		this.arrivalTime = arrivalTime;
    		this.setPrev(prev);
    	}else if(this.arrivalTime < arrivalTime){
    		this.arrivalTime = arrivalTime;
    		this.setPrev(prev);
    	}
    }
    
    public void resetRequiredTime(){
    	this.requiredTime = null;
    }
    
    public void resetRequiredTimes(){
    	this.requiredTimes = null;
    }
    
    public void resetArrivalTime(){
    	this.arrivalTime = null;
    }
    
    public void resetArrivalTimes(){
    	this.arrivalTimes = null;
    }
    
    public void resetPrevs() {
    	this.prevs = null;
    }
    
    /**
     * Sets the arrival time stored at this vertex.
     * @param arrivalTime Arrival time in picoseconds.  This is the sum of delay edges leading to 
     * this vertex.
     */
    public void setArrivalTime(float arrivalTime) {
        this.arrivalTime = arrivalTime;
    }
        
    /**
     * Gets the slack stored at this vertex.
     * @return Slack value in picoseconds.
     */
    public Float getSlack() {
        if (requiredTime == null || arrivalTime == null) {
            return null;
        }
        slack = getRequiredTime() - getArrivalTime();
        return slack;
    }

    /**
     * Gets the arrival time stored at this vertex.
     * @return Arrival time in picoseconds.
     */
    public float getArrivalTime() {
        if (arrivalTime == null) {
            return 0f;
        } else {
            return arrivalTime;
        }
    }
    
    public void setMaxArrivalTimePrevFromVector() {
   	
    	if(this.arrivalTimes != null) {
    		short max = 0;
    		TimingVertex tmpPrev = null;
	    	for(int i = 0; i < 4; i++) {
	    		if(this.arrivalTimes[i] >= max) {
	    			max = this.arrivalTimes[i];
	    			tmpPrev = this.prevs[i];
	    		}
	    	}
	    	
	    	this.arrivalTime = (float) max;
	    	this.prev = tmpPrev;
    	}
    }
    
    public short getMinReqTimeFromVector() {
    	if(this.requiredTimes == null) {
    		return 0;
    	}
    	short min = Short.MAX_VALUE;
    	for(int i = 0; i < 4; i++) {
    		if(this.requiredTimes[i] < min) {
    			min = this.requiredTimes[i];
    		}
    	}
    	return min;
    }
    
    /**
     * Gets the required time stored at this vertex.
     * @return Required time in picoseconds.
     */
    public float getRequiredTime() {
        if (requiredTime == null) {
            return 0.f;
        } else {
            return requiredTime;
        }
    }

    /**
     * For debug, this is used within the timing library to confirm that this vertex has been printed when
     * writing the GraphViz dot file representation.  Sets whether the vertex has been printed.
     * @param b Boolean for marking whether this vertex has been printed.
     */
    protected void setPrinted(boolean b) {
        printed = b;
    }

    /**
     * For debug, this is used within the timing library to confirm that this vertex has been printed when
     * writing the GraphViz dot file representation.  Gets whether the vertex has been printed.
     * @return Boolean indication of whether it has already been printed.
     */
    protected boolean getPrinted() {
        return printed;
    }

    /**
     * Gets the name of the vertex.
     * @return Name of the vertex.
     */
    public String getName() {
        return name;
    }

    /**
     * This will be used in a future release to get the name of the clock associated with this vertex.
     * @return Clock name.
     */
    public String getClockName() { return clockName; }

    /**
     * This will be used in a future release to set the name of the clock associated with this vertex.
     * @param clockName  Clock name.
     */
    public void setClockName(String clockName) {
        if (clockName==null) {
            new Exception().printStackTrace();
        }
        this.clockName = clockName;
    }

    /**
     * This is used for checking if the vertex represents on input to a flip flop.
     * @return Boolean indication of whether this is an input to a flip flop.
     */
    public boolean getFlopInput() {
        return isFlopInput;
    }

    /**
     * This is used for checking if the vertex represents an output from a flip flop.
     * @return Boolean indication of whether this is an output from a flip flop.
     */
    public boolean getFlopOutput() {
        return isFlopOutput;
    }

    /**
     * This is used for setting that the vertex represents an input to a flip flop.
     */
    public void setFlopInput() {
        isFlopInput = true;
    }

    /**
     * This is used for setting that the vertex represents an output from a flip flop.
     */
    public void setFlopOutput() {
        isFlopOutput = true;
    }

	public short[] getArrivalTimes() {
		return arrivalTimes;
	}

	public short[] getRequiredTimes() {
		return requiredTimes;
	}

	public TimingVertex getPrev() {
		return prev;
	}

	public void setPrev(TimingVertex prev) {
		this.prev = prev;
	}
}
