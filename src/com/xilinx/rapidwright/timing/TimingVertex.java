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

    /**
     * Creates a vertex for insertion into the TimingGraph.
     * @param name Name is typically the hierarchical logical name of an input or output pin.  In 
     * some cases this timing library is using the EDIFPortInst name for the name.  This is 
     * noteworthy because the EDIFPortInst name may contain square brackets on pins that are indexed
     * slices of a bus (for example some pins on CARRY8 have indices).  The physical name for pins 
     * do not contain square brackets.
     */
    TimingVertex(String name) {
        this.name = name;
        //the lines below can be useful for debug
        //if (name.startsWith("Parser_inst/stage_0/tupleForward_inst/PktEop_d_1_i_1__1/")) { //Parser_inst/stage_0/MUX_PKT_VLD_reg/Q")) {
        //    System.out.println("exception on:"+name);
        //    new Exception().printStackTrace();
        //   System.exit(1);
       // }
        this.isFlopInput = false;
        this.isFlopOutput = false;
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

    /**
     * Sets the arrival time stored at this vertex.
     * @param arrivalTime Arrival time in picoseconds.  This is the sum of delay edges leading to 
     * this vertex.
     */
    public void setArrivalTime(float arrivalTime) {
        //System.out.println("Setting arrival time for "+this+" to:"+arrivalTime);
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
            return 0.f;
        } else {
            return arrivalTime;
        }
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


}
