package com.xilinx.rapidwright.examples;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.timing.TimingEdge;
import com.xilinx.rapidwright.timing.TimingManager;
import com.xilinx.rapidwright.timing.TimingVertex;
import org.jgrapht.GraphPath;

/**
 * Basic example to show reading in a placed and routed DCP and calculating the worst case
 * data path delay. 
 */
public class ReportTimingExample {

    public static void main(String[] args) {
        if(args.length != 1) {
        	System.out.println("USAGE: <dcp_file_name>");
        	return;
        }
        CodePerfTracker t = new CodePerfTracker("Report Timing Example");
        t.useGCToTrackMemory(true);

        // Read in an example placed and routed DCP        
        t.start("Read DCP");
        Design design = Design.readCheckpoint(args[0], CodePerfTracker.SILENT);
        
        // Instantiate and populate the timing manager for the design
        t.stop().start("Create TimingManager");
        TimingManager tim = new TimingManager(design);
        
        // Get and print out worst data path delay in design
        t.stop().start("Get Max Delay");
        GraphPath<TimingVertex, TimingEdge> criticalPath = tim.getTimingGraph().getMaxDelayPath();
        
        // Print runtime summary
        t.stop().printSummary();        
        System.out.println("\nCritical path: "+ ((int)criticalPath.getWeight())+ " ps");
        System.out.println("\nPath details:");
        System.out.println(criticalPath.toString().replace(",", ",\n")+"\n");
    }
}
