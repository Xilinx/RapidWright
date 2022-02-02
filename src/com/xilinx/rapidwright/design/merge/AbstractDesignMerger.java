package com.xilinx.rapidwright.design.merge;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPort;

/**
 * Provides a standardized set of APIs to coordinate design merging.  Allows the user to implement 
 * customized design merge behavior by extending this abstract class.  RapidWright provides
 * a default merging behavior in {@link DefaultDesignMerger}.  These methods are only called by
 * {@link MergeDesigns#mergeDesigns(com.xilinx.rapidwright.design.Design...)} when the two objects
 * collide in their respective namespace.
 */
public abstract class AbstractDesignMerger {

    public abstract void mergePorts(EDIFPort p0, EDIFPort p1);
    
    public abstract void mergeLogicalNets(EDIFNet n0, EDIFNet n1);
    
    public abstract void mergeCellInsts(EDIFCellInst i0, EDIFCellInst i1);
    
    public abstract void mergeSiteInsts(SiteInst s0, SiteInst s1);

    public abstract void mergePhysicalNets(Net n0, Net n1);    
}
