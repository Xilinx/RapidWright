/* 
 * Copyright (c) 2022 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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
