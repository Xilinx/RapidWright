package com.xilinx.rapidwright.interchange;

import java.io.File;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LSFJob;
import com.xilinx.rapidwright.util.LocalJob;

/**
 * Small application to attempt to generate all Xilinx devices in the Interchange format. 
 */
public class GenerateInterchangeDevices {
    public static void main(String[] args) {
        if(args.length != 1) {
            System.out.println("USAGE: java " + GenerateInterchangeDevices.class.getCanonicalName() 
                    + " <destination_dir>");
            return;
        }
        String rootRunDir = args[0];
        JobQueue q = new JobQueue();
        int concurrentJobLimit = JobQueue.isLSFAvailable() ? 1000 : 1;
        for(String deviceName : Device.getAvailableDevices()) {
            Job job = JobQueue.isLSFAvailable() ? new LSFJob() : new LocalJob();
            job.setCommand("java " + DeviceResourcesExample.class.getCanonicalName() + " " + deviceName);
            job.setRunDir(rootRunDir + File.separator + deviceName);
            q.addJob(job);
        }
        q.runAllToCompletion(concurrentJobLimit);
    }
}
