/*
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
/**
 *
 */
package com.xilinx.rapidwright.tests;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Pair;

/**
 * Simple tool for measuring code runtime and memory and reporting.
 *
 * Created on: Jun 29, 2016
 */
public class CodePerfTracker {

    private String name;

    private ArrayList<Long> runtimes;

    private ArrayList<Long> memUsages;

    private ArrayList<String> segmentNames;

    private Map<String,Long> inflightTimes;

    /** Stores the current (1st) and peak (2nd) total OS memory usage */
    private List<Pair<Long,Long>> totalOSMemUsages;
    /** If tracking memory, also print current OS memory usage if available */
    private boolean reportCurrOSMemUsage = false;
    
    private Runtime rt;

    private int maxRuntimeSize = 9;
    private int maxUsageSize = 10;
    private int maxSegmentNameSize = 24;
    private boolean printProgress = true;
    private boolean trackMemoryUsingGC = false;

    private Integer linuxProcID = null;

    public static final CodePerfTracker SILENT;

    static {
        SILENT = new CodePerfTracker("",false);
        SILENT.setVerbose(false);
    }

    private static final boolean GLOBAL_DEBUG = true;

    private boolean verbose = true;


    public CodePerfTracker(String name) {
        init(name,true);
    }

    public CodePerfTracker(String name, boolean printProgress) {
        init(name,printProgress);
    }

    public CodePerfTracker(String name, boolean printProgress, boolean isVerbose) {
        verbose = isVerbose;
        init(name,printProgress);
    }

    public void init(String name, boolean printProgress) {
        if (!GLOBAL_DEBUG) return;
        this.name = name;
        this.printProgress = printProgress;
        runtimes = new ArrayList<Long>();
        memUsages = new ArrayList<Long>();
        segmentNames = new ArrayList<String>();
        inflightTimes = new HashMap<>();
        rt = Runtime.getRuntime();
        if (this.printProgress && isVerbose() && name != null) {
            MessageGenerator.printHeader(name);
        }
    }

    public void updateName(String name) {
        this.name = name;
    }

    private int getSegmentIndex(String segmentName) {
        int i=0;
        for (String name : segmentNames) {
            if (name.equals(segmentName)) {
                return i;
            }
            i++;
        }
        return -1;
    }

    public Long getRuntime(String segmentName) {
        int i = getSegmentIndex(segmentName);
        return i == -1 ? null : runtimes.get(i);
    }

    public Long getMemUsage(String segmentName) {
        int i = getSegmentIndex(segmentName);
        return i == -1 ? null : memUsages.get(i);
    }

    public boolean isVerbose() {
        return verbose;
    }

    public void setVerbose(boolean verbose) {
        this.verbose = verbose;
    }

    public CodePerfTracker start(String segmentName) {
        if (!GLOBAL_DEBUG || this == SILENT) return this;
        if (isUsingGCCallsToTrackMemory()) System.gc();
        long currUsage = rt.totalMemory() - rt.freeMemory();
        segmentNames.add(segmentName);
        memUsages.add(currUsage);
        runtimes.add(System.nanoTime());
        if (linuxProcID != null) {
            totalOSMemUsages.add(null);
        }
        return this;
    }

    public CodePerfTracker stop() {
        if (!GLOBAL_DEBUG || this == SILENT) return this;
        long end = System.nanoTime();
        int idx = runtimes.size()-1;
        if (idx < 0) return null;
        long start = runtimes.get(idx);
        if (isUsingGCCallsToTrackMemory()) System.gc();
        long currUsage = (rt.totalMemory() - rt.freeMemory());
        long prevUsage = memUsages.get(idx);

        runtimes.set(idx, end-start);
        memUsages.set(idx,    currUsage-prevUsage);

        if (linuxProcID != null) {
            totalOSMemUsages.set(idx, getTotalOSMemUsage());
        }

        if (printProgress && isVerbose()) {
            print(idx);
        }
        return this;
    }

    /**
     * Gets the current and total peak memory usage. Depends on Linux's /proc to get
     * values.
     * 
     * @return A Pair where the first is current and second is peak memory usage.
     */
    private Pair<Long,Long> getTotalOSMemUsage() {
        if (linuxProcID == null) return null;
        Pair<Long,Long> totalOSMemUsage = new Pair<>();
        for (String line : FileTools.getLinesFromTextFile("/proc/" + linuxProcID + "/status")) {
            if (line.startsWith("VmHWM:")) {
                totalOSMemUsage.setSecond(Long.parseLong(line.split("\\s+")[1]));
            }else if (line.startsWith("VmRSS:")) {
                totalOSMemUsage.setFirst(Long.parseLong(line.split("\\s+")[1]));
            }
        }
        return totalOSMemUsage;
    }

    public synchronized CodePerfTracker start(String segmentName, boolean nested) {
        if (!nested) {
            return start(segmentName);
        }

        inflightTimes.put(segmentName, System.nanoTime());
        return this;
    }

    public synchronized CodePerfTracker stop(String segmentName) {
        Long start = inflightTimes.remove(segmentName);
        if (start == null) {
            return stop();
        }

        long end = System.nanoTime();
        if (printProgress && isVerbose()) {
            print("(" + segmentName + ")", end - start, null,
                    totalOSMemUsages != null ? getTotalOSMemUsage() : null, true);
        }
        return this;
    }

    private void print(String segmentName, Long runtime, Long memUsage, Pair<Long,Long> totalOSMemUsage) {
        print(segmentName, runtime, memUsage, totalOSMemUsage, false);
    }

    private void print(String segmentName, Long runtime, Long memUsage, Pair<Long,Long> totalOSMemUsage, boolean nested) {
        if (isUsingGCCallsToTrackMemory()) {
            if (nested) {
                System.out.printf(
                        "%" + maxSegmentNameSize + "s: %" + maxRuntimeSize + "s %" + maxUsageSize
                                + "s (%" + maxRuntimeSize + ".3fs)",
                        segmentName, "", "", (runtime) / 1000000000.0);
            } else {
                System.out.printf(
                        "%" + maxSegmentNameSize + "s: %" + maxRuntimeSize + ".3fs %" + maxUsageSize
                                + ".3fMBs",
                        segmentName, (runtime)/1000000000.0, (memUsage)/(1024.0*1024.0));
            }
        } else {
            if (nested) {
                System.out.printf(
                        "%" + maxSegmentNameSize + "s: %" + maxRuntimeSize + "s  (%" + maxRuntimeSize + ".3fs)",
                        segmentName,
                        "",
                        (runtime) / 1000000000.0);
            } else {
                System.out.printf("%" + maxSegmentNameSize + "s: %" + maxRuntimeSize + ".3fs",
                        segmentName,
                        (runtime) / 1000000000.0);
            }
        }
        if (totalOSMemUsage != null) {
            if (!nested) {
                // Add padding for the space that nested output would occupy
                int whitespaceCount = (isUsingGCCallsToTrackMemory() ? maxRuntimeSize : (maxRuntimeSize + 4));
                System.out.printf("%" + whitespaceCount + "s", "");
            }
            if (reportCurrOSMemUsage) {
                System.out.printf(" | %" + maxUsageSize + ".3fMBs (curr)", (totalOSMemUsage.getFirst()) / 1024.0);
            }
            System.out.printf(" | %" + maxUsageSize + ".3fMBs (peak)", (totalOSMemUsage.getSecond()) / 1024.0);
        }
        System.out.println();
    }

    private void print(int idx) {
        Pair<Long,Long> usages = totalOSMemUsages == null ? null : totalOSMemUsages.get(idx);
        print(segmentNames.get(idx), runtimes.get(idx), memUsages.get(idx), usages);
    }

    /**
     * Gets the flag that determines if System.gc() is called at beginning and end
     * of each segment to more accurately track memory usage.
     * @return True if this CodePerfTracker is using System.gc() calls at beginning and end
     * of segments to obtain more accurate memory usages.  False otherwise.
     */
    public boolean isUsingGCCallsToTrackMemory() {
        return trackMemoryUsingGC;
    }

    /**
     * By setting this flag, more accurate memory usage numbers can be captured. The
     * tradeoff is that System.gc() calls can increase total runtime of the program
     * (although the call is not included in measured runtime). When running in
     * Linux, this also sets {@link #setTrackOSMemUsage(boolean)} as a convenience.
     *
     * @param useGCCalls Sets a flag that uses System.gc() calls at the beginning
     *                   and end of segments to obtain more accurate memory usages.
     */
    public void useGCToTrackMemory(boolean useGCCalls) {
        this.trackMemoryUsingGC = useGCCalls;
        setTrackOSMemUsage(useGCCalls);
    }
    
    /**
     * Sets tracking of OS memory usage (in Linux only).
     * @param trackOSMemUsage If true, will track curr and peak memory usage of the process as 
     * reported by the OS. 
     */
    public void setTrackOSMemUsage(boolean trackOSMemUsage) {
        if (trackOSMemUsage && !FileTools.isWindows()) {
            String id = ManagementFactory.getRuntimeMXBean().getName();
            int idx = id.indexOf('@');
            if (idx > 0) {
                linuxProcID = Integer.parseInt(id.substring(0, idx));
                totalOSMemUsages = new ArrayList<>();
            }
        } else if (!trackOSMemUsage) {
            linuxProcID = null;
            totalOSMemUsages = null;
        }
    }

    /**
     * Checks a flag that indicates current OS memory usage should be reported alongside peak OS 
     * memory usage.  The default is false.
     * @return True if the flag is set, false otherwise.  Default is false.
     */
    public boolean isReportingCurrOSMemUsage() {
        return reportCurrOSMemUsage;
    }

    /**
     * Flag indicating if CodePerfTracker is running in Linux and is reporting memory usage, to also
     * report current OS memory usage alongside peak OS memory usage.
     * @param reportCurrOSMemUsage Flag to report current OS memory usage, default is false.
     */
    public void setReportingCurrOSMemUsage(boolean reportCurrOSMemUsage) {
        this.reportCurrOSMemUsage = reportCurrOSMemUsage;
    }

    private void addTotalEntry() {
        long totalRuntime = 0L;
        long totalUsage = 0L;
//        maxSegmentNameSize = 0;
        for (int i=0; i < runtimes.size(); i++) {
            totalRuntime += runtimes.get(i);
            totalUsage += memUsages.get(i);
            if (!printProgress) {
                int len = segmentNames.get(i).length();
                if (len > maxSegmentNameSize) maxSegmentNameSize = len;
            }
        }
        runtimes.add(totalRuntime);
        memUsages.add(totalUsage);
        String totalName = isUsingGCCallsToTrackMemory() ? "*Total*" : " [No GC] *Total*";
        segmentNames.add(totalName);
        if (linuxProcID != null) {
            totalOSMemUsages.add(getTotalOSMemUsage());
        }
        if (!printProgress && maxSegmentNameSize < totalName.length()) {
            maxSegmentNameSize = totalName.length();
        }
    }

    private void removeTotalEntry() {
        final int idx = runtimes.size() - 1;
        runtimes.remove(idx);
        memUsages.remove(idx);
        segmentNames.remove(idx);
        if (totalOSMemUsages != null) {
            totalOSMemUsages.remove(idx);
        }
    }

    public void printSummary() {
        if (!GLOBAL_DEBUG || this == SILENT) return;
        if (!isVerbose()) return;
        if (!printProgress) MessageGenerator.printHeader(name);
        addTotalEntry();
        int start = printProgress ? runtimes.size()-1 : 0;
        for (int i=start; i < runtimes.size(); i++) {
            if (i == runtimes.size()-1) {
                System.out.println("------------------------------------------------------------------------------");
            }
            print(i);
        }
        removeTotalEntry();
    }
}
