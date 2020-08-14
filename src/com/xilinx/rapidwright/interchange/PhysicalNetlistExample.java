package com.xilinx.rapidwright.interchange;

import java.io.IOException;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class PhysicalNetlistExample {

    public static void main(String[] args) throws IOException {
        if(args.length < 2 || args.length > 4) {
            System.out.println("USAGE: <input>.dcp [input.edf] <output>.dcp");
            System.out.println("   Example round trip test for a logical & physical netlist to start from a DCP,"
                    + " get converted to a\n   Cap'n Proto serialized file and then read back into "
                    + "a DCP file.  Creates two new files:\n\t1. <input>.netlist "
                    + "- Cap'n Proto serialized file"
                    + "\n\t2. <input>.roundtrip.edf - EDIF after being written/read from serialized format");
            return;            
        }
    
        CodePerfTracker t = new CodePerfTracker("DCP->Interchange Format->DCP",false);
        
        t.start("Read DCP");
        
        String edifFileName = null;
        String outputDCPFileName = args[1];
        if(args.length > 2) {
        	edifFileName = args[1];
        	outputDCPFileName = args[2];
        }
        // Read DCP into memory using RapidWright
        Design design = edifFileName == null ? 
        					Design.readCheckpoint(args[0], CodePerfTracker.SILENT) : 
        					Design.readCheckpoint(args[0], edifFileName, CodePerfTracker.SILENT);
        
        t.stop().start("Write Logical Netlist");
        // Write Logical & Physical Netlist to Cap'n Proto Serialization file
        String logNetlistFileName = outputDCPFileName.replace(".dcp", ".netlist");
        LogNetlistWriter.writeLogNetlist(design.getNetlist(), logNetlistFileName);
        
        t.stop().start("Write Physical Netlist");
        String physNetlistFileName = outputDCPFileName.replace(".dcp", ".phys");
        PhysNetlistWriter.writePhysNetlist(design, physNetlistFileName);
        
        t.stop().start("Read Logical Netlist");
        // Read Netlist into RapidWright netlist
        EDIFNetlist n2 = LogNetlistReader.readLogNetlist(logNetlistFileName);
        
        t.stop().start("Read Physical Netlist");
        Design roundtrip = PhysNetlistReader.readPhysNetlist(physNetlistFileName, n2);
        
        t.stop().start("Write DCP");
        // Write RapidWright netlist back to edif
        roundtrip.writeCheckpoint(outputDCPFileName, CodePerfTracker.SILENT);
        
        t.stop().printSummary();
    }
}
