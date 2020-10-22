package com.xilinx.rapidwright.interchange;

import java.io.File;
import java.io.IOException;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.tests.CodePerfTracker;

public class DeviceResourcesExample {

    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <device name>");
            System.out.println("   Example dump of device information for interchange format.");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Device Resources Dump: " + args[0]);
        t.useGCToTrackMemory(true);

        // Create device resource file if it doesn't exist
        String capnProtoFileName = args[0] + ".device";
        if(!new File(capnProtoFileName).exists()) {
            //MessageGenerator.waitOnAnyKey();
            t.start("Load Device");
            Device device = Device.getDevice(args[0]);
            t.stop();
            // Write Netlist to Cap'n Proto Serialization file
            DeviceResourcesWriter.writeDeviceResourcesFile(args[0], device, t, capnProtoFileName);            
            Device.releaseDeviceReferences();
        }
        
        t.start("Verify file");
        // Verify device resources
        DeviceResourcesVerifier.verifyDeviceResources(capnProtoFileName, args[0]);
        
        t.stop().printSummary();
    }
}
