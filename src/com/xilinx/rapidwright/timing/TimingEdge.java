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

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import org.jgrapht.graph.DefaultEdge;

/**
 * Edges within a TimingGraph that encapsulate logic delays, net delays, and debug information.
 */
public class TimingEdge extends DefaultEdge {

    private static final long serialVersionUID = 5888111557223514042L;

    private boolean hasEDIFPortInsts = false;

    private EDIFPortInst srcPort;
    private EDIFPortInst dstPort;
    private TimingGraph timingGraph;
    private Net net;
    private EDIFNet edifNet;
    private TimingVertex src;
    private TimingVertex dst;
    private float logicDelay = 0.0f;
    private float netDelay = 0.0f;
    private float delay = 0.0f;

    private SitePinInst first;
    private SitePinInst second;

    /**
     * Constructs a TimingEdge based only on specifying two vertices.
     * @param u First vertex as a TimingVertex.
     * @param v Second vertex as a TimingVertex.
     */
    public TimingEdge(TimingVertex u, TimingVertex v) {
        this.src = u;
        this.dst = v;
    }

    /**
     * Constructs a TimingEdge with the expected information for the TimingGraph.
     * @param timingGraph TimingGraph object where this edge will belong.
     * @param srcPort First vertex as a TimingVertex.
     * @param dstPort Second vertex as a TimingVertex.
     * @param edifNet Logical EDIFNet representing this edge.  In some cases this is set to null.
     * @param net Physical "Net" representing this edge.
     */
    public TimingEdge(TimingGraph timingGraph, TimingVertex srcPort, TimingVertex dstPort, 
                      EDIFNet edifNet, Net net) {
        this.src = srcPort;
        this.dst = dstPort;
        this.edifNet = edifNet;
        this.net = net;
        this.timingGraph = timingGraph;
    }

    /**
     * For debug, sets the first SitePinInst for this edge.
     * @param spi First SitePinInst.
     */
    public void setFirstSitePinInst(SitePinInst spi) {
        this.first = spi;
    }

    /**
     * For debug, sets the second SitePinInst for this edge.
     * @param spi Second SitePinInst.
     */
    public void setSecondSitePinInst(SitePinInst spi) {
        this.second = spi;
    }

    /**
     * For debug, gets the first SitePinInst for this edge.
     * @return First SitePinInst.
     */
    public SitePinInst getFirstPin() {
        return first;
    }

    /**
     * For debug, gets the second SitePinInst for this edge.
     * @return Second SitePinInst.
     */
    public SitePinInst getSecondPin() {
        return second;
    }

    private String simplifyName(String name) {
        if(name == null) return null;
        return name.replaceAll("\\[", "_").replaceAll("\\]", "_").replaceAll("\\.", "__")
                   .replaceAll("\\:", "___").replaceAll("\\/", "_");
    }
    
    /**
     * Represents this edge as a String for debug, etc.
     * @return String representing this edge.
     */
    public String toString() {
        String result = "";
         if (hasEDIFPortInsts) {
            String sCellInst = (srcPort.getCellInst() != null) ? 
                    "" + timingGraph.hierCellInstMap.get(srcPort.getCellInst()) : "top";
            String dCellInst = (dstPort.getCellInst() != null) ? 
                    "" + timingGraph.hierCellInstMap.get(dstPort.getCellInst()) : "top";
            sCellInst = simplifyName(sCellInst);
            dCellInst = simplifyName(dCellInst);
            String sPortName = simplifyName(srcPort.getName());
            String dPortName = simplifyName(dstPort.getName());
            //result += psCellInst+"__";
            result += sCellInst;
            result += "__"+sPortName;
            result += "->";
            //result += pdCellInst+"__";
            result += dCellInst;
            result += "__"+dPortName;
            result += "[ label = \""+ getDelay() +"\"]";
        }

        else {
            result += src;
            result += "->";
            result += dst;
        }

        return result;
    }

    private String formatVertexName(String name) {
        if(name == null) return null;
        return name .replaceAll("\\[","_").replaceAll("\\]","").replaceAll("\\(","")
                    .replaceAll("\\)","").replaceAll("\\/","____").replaceAll("\\.","_")
                    .replaceAll(":","->");
    }
    
    /**
     * Returns a string representing the edge to help towards printing out a textual dot file for 
     * creating a GraphViz dot visualization of the graph.
     * @return A string representing this edge for creating a GraphViz dot file
     */
    public String toGraphvizDotString() {
        String result = "";
        {
            result += formatVertexName(src.toString());
            result += "->";
            result += formatVertexName(dst.toString());
            if (dst.getSlack() != null && dst.getSlack() < 0)
                result += "[style = bold color = red label = \""+
                          Math.round(getLogicDelay()) +": "+Math.round(getNetDelay()) +"\"];";
            else
                result += "[ label = \""+ Math.round(getLogicDelay()) +": "+
                          Math.round(getNetDelay()) +"\"];";

            if (!src.getPrinted()) { // !src.getPrinted()) {
                src.setPrinted(true);
                result += "\n" + formatVertexName(src.toString());
                if (src.getSlack() != null && src.getSlack() < 0)
                    result += "[style = bold color = red label = <"+src.toString()+
                              "<BR /> <FONT POINT-SIZE=\"10\">"+ Math.round(src.getArrivalTime())+
                              ": "+ (src.getSlack()!=null? Math.round(src.getSlack()):0)+": "+
                              Math.round(src.getRequiredTime())+"</FONT>>]";
                else
                    result += "[ label = <"+src.toString()+"<BR /> <FONT POINT-SIZE=\"10\">"+ 
                              Math.round(src.getArrivalTime())+": "+ (src.getSlack()!=null? 
                              Math.round(src.getSlack()):0)+": "+Math.round(src.getRequiredTime())+
                              "</FONT>>]";
            }

            if (!dst.getPrinted()) {
                dst.setPrinted(true);
                result += "\n" + formatVertexName(dst.toString());
                if (dst.getSlack() != null && dst.getSlack() < 0)
                    result += "[style = bold color = red label = <" + dst.toString() + 
                              "<BR /> <FONT POINT-SIZE=\"10\">" + Math.round(dst.getArrivalTime()) +
                              ": " + (dst.getSlack()!=null?Math.round(dst.getSlack()):0) + ": " + 
                              Math.round(dst.getRequiredTime()) + "</FONT>>]";
                else
                    result += "[ label = <" + dst.toString() + "<BR /> <FONT POINT-SIZE=\"10\">" + 
                              Math.round(dst.getArrivalTime()) + ": " + (dst.getSlack()!=null?
                              Math.round(dst.getSlack()):0) + ": " + 
                              Math.round(dst.getRequiredTime()) + "</FONT>>]";
            }
        }
        if (result.endsWith(";"))
            result = result.substring(0, result.length()-1);

        return result;
    }

    /**
     * Gets the logic-related component of the delay in ps for this edge.
     * @return Logic delay in picoseconds.
     */
    public float getLogicDelay() {
        return logicDelay;
    }

    /**
     * Gets the net-related component of the delay in ps for this edge.
     * @return Net delay in picoseconds.
     */
    public float getNetDelay() {
        return netDelay;
    }

    /**
     * Gets the total delay in ps for this edge.  The total delay is currently the sum of logic 
     * delay and net delay components.
     * @return Total delay in picoseconds.
     */
    public float getDelay() {
        return delay;
    }

    /**
     * Sets the net-related component of the delay in ps for this edge.
     * @param netDelay Net delay in picoseconds.
     */
    public void setNetDelay(float netDelay) {
        this.netDelay = netDelay;
        this.delay = logicDelay+netDelay;
        if (timingGraph.containsEdge(this))
            timingGraph.setEdgeWeight(this, this.delay);
    }

    /**
     * Sets the logic-related component of the delay in ps for this edge.
     * @param logicDelay Logic delay in picoseconds.
     */
    public void setLogicDelay(float logicDelay) {
        this.logicDelay = logicDelay;
        this.delay = logicDelay+netDelay;
        if (timingGraph.containsEdge(this))
            timingGraph.setEdgeWeight(this, this.delay);
    }

    /**
     * Hash function specific to this object type for generating a hash code representation storing 
     * this object type in hashtables, hashmaps, etc.
     * @return HashCode specific to this object type.
     */
    public int hashCode() {
        return toString().hashCode();
    }

    /**
     * Gets the first vertex of this edge.
     * @return First vertex of type TimingVertex.
     */
    public TimingVertex getSrc() {
        return src;
    }

    /**
     * Gets the second vertex of this edge.
      * @return Second vertex of type TimingVertex.
     */
    public TimingVertex getDst() {
        return dst;
    }

    /**
     * Gets the physical "Net" object associated with this edge.
     * @return Physical "Net" for this edge.
     */
    public Net getNet() {
        return this.net;
    }

    /**
     * Gets the logical "EDIFNet" object associated with this edge, if one has been set.  This 
     * returns null if the logical net was not set.
     * @return Logical EDIFNet for this edge.
     */
    public EDIFNet getEdifNet() {
        return this.edifNet;
    }
}
