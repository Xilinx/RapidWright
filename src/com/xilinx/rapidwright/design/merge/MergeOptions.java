package com.xilinx.rapidwright.design.merge;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.xilinx.rapidwright.design.Net;

public class MergeOptions {

    private Set<String> cellTypesToMerge = null;
    
    private Set<String> netsToMerge = null;
    
    private Set<String> portsToMerge = null;
    
    private String clkName = null;

    public static final Set<String> DEFAULT_CELL_TYPES_TO_MERGE; 
    public static final Set<String> DEFAULT_NETS_TO_MERGE;
    public static final Set<String> DEFAULT_PORTS_TO_MERGE;
    public static final String DEFAULT_CLK_NAME = null;
    
    static {
        DEFAULT_CELL_TYPES_TO_MERGE = new HashSet<>();
        DEFAULT_CELL_TYPES_TO_MERGE.add("VCC");
        DEFAULT_CELL_TYPES_TO_MERGE.add("GND");
        DEFAULT_CELL_TYPES_TO_MERGE.add("BUFGCE");
        
        DEFAULT_NETS_TO_MERGE = new HashSet<>();
        DEFAULT_NETS_TO_MERGE.add(Net.VCC_NET);
        DEFAULT_NETS_TO_MERGE.add(Net.GND_NET);
        
        DEFAULT_PORTS_TO_MERGE = Collections.emptySet();
    }
    
    /**
     * 
     * @param cellTypesToMerge
     * @param clkName
     */
    public MergeOptions(Set<String> cellTypesToMerge, String clkName) {
        setCellTypesToMerge(cellTypesToMerge);
        setClkName(clkName);
        setNetsToMerge(DEFAULT_NETS_TO_MERGE);
        setPortsToMerge(DEFAULT_PORTS_TO_MERGE);
    }

    public MergeOptions(Set<String> cellTypesToMerge, String clkName, Set<String> netsToMerge, 
                        Set<String> portsToMerge) {
        setCellTypesToMerge(cellTypesToMerge);
        setClkName(clkName);
        setNetsToMerge(netsToMerge);
        setPortsToMerge(portsToMerge);
    }
    
    /**
     * Returns a new MergeOptions object with all the fields populated by the defaults
     * @return New MergeOptions object with all the fields populated by the defaults
     */
    public static MergeOptions getDefaults() {
        return new MergeOptions(DEFAULT_CELL_TYPES_TO_MERGE, DEFAULT_CLK_NAME);
    }


    /**
     * @return the cellTypesToMerge
     */
    public Set<String> getCellTypesToMerge() {
        return cellTypesToMerge;
    }


    /**
     * @param cellTypesToMerge the cellTypesToMerge to set
     */
    public void setCellTypesToMerge(Set<String> cellTypesToMerge) {
        this.cellTypesToMerge = cellTypesToMerge == null ? Collections.emptySet() : cellTypesToMerge;
    }


    /**
     * @return the clkName
     */
    public String getClkName() {
        return clkName;
    }


    /**
     * @param clkName the clkName to set
     */
    public void setClkName(String clkName) {
        this.clkName = clkName;
    }


    /**
     * @return the netsToMerge
     */
    public Set<String> getNetsToMerge() {
        return netsToMerge;
    }


    /**
     * @param netsToMerge the netsToMerge to set
     */
    public void setNetsToMerge(Set<String> netsToMerge) {
        this.netsToMerge = netsToMerge;
    }


    /**
     * @return the portsToMerge
     */
    public Set<String> getPortsToMerge() {
        return portsToMerge;
    }


    /**
     * @param portsToMerge the portsToMerge to set
     */
    public void setPortsToMerge(Set<String> portsToMerge) {
        this.portsToMerge = portsToMerge;
    }   
}
