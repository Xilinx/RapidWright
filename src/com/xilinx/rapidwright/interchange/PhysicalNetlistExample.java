package com.xilinx.rapidwright.interchange;

import java.io.FileOutputStream;
import java.io.IOException;

import org.capnproto.MessageBuilder;
import org.capnproto.SerializePacked;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class PhysicalNetlistExample {


        
    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <input>.dcp");
            System.out.println("   Example round trip test for a logical & physical netlist to start from a DCP,"
                    + " get converted to a\n   Cap'n Proto serialized file and then read back into "
                    + "a DCP file.  Creates two new files:\n\t1. <input>.netlist "
                    + "- Cap'n Proto serialized file"
                    + "\n\t2. <input>.roundtrip.edf - EDIF after being written/read from serialized format");
            return;            
        }
    
        CodePerfTracker t = new CodePerfTracker("DCP->Interchange Format->DCP",false);
        
        t.start("Read DCP");
        // Read DCP into memory using RapidWright
        Design design = Design.readCheckpoint(args[0], CodePerfTracker.SILENT);
        t.stop().start("Write Logical Netlist");
        // Write Logical & Physical Netlist to Cap'n Proto Serialization file
        String logNetlistFileName = args[0].replace(".dcp", ".netlist");
        FileOutputStream fo = new java.io.FileOutputStream(logNetlistFileName);
        MessageBuilder message = LogicalNetlistExample.writeLogNetlist(design.getNetlist());
        SerializePacked.writeToUnbuffered(fo.getChannel(), message);
        fo.close();
        
        t.stop().start("Write Physical Netlist");
        String physNetlistFileName = args[0].replace(".dcp", ".phys");
        PhysNetlistWriter.writePhysNetlist(design, physNetlistFileName);
        
        t.stop().start("Read Logical Netlist");
        // Read Netlist into RapidWright netlist
        EDIFNetlist n2 = LogicalNetlistExample.readLogNetlist(logNetlistFileName);
        
        t.stop().start("Read Physical Netlist");
        Design roundtrip = PhysNetlistReader.readPhysNetlist(physNetlistFileName, n2);
        
        t.stop().start("Write DCP");
        // Write RapidWright netlist back to edif
        roundtrip.writeCheckpoint(args[0].replace(".dcp", ".roundtrip.dcp"), CodePerfTracker.SILENT);
        
        t.stop().printSummary();
    }
}
