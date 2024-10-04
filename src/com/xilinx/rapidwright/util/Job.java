/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
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
package com.xilinx.rapidwright.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


/**
 * A parent class for all task jobs types.
 *
 * Created on: Jan 26, 2018
 */
public abstract class Job {

    private String command;

    private String runDir;

    private long jobNumber;

    public static final String DEFAULT_SCRIPT_NAME = "run";

    public static final String DEFAULT_COMMAND_NAME = "cmd";

    public static final String DEFAULT_LOG_EXTENSION = ".log";

    public static final String DEFAULT_SCRIPT_LOG_FILE = DEFAULT_SCRIPT_NAME + DEFAULT_LOG_EXTENSION;

    public static final String DEFAULT_COMMAND_LOG_FILE = DEFAULT_COMMAND_NAME + DEFAULT_LOG_EXTENSION;

    public abstract long launchJob();

    public abstract JobState getJobState();

    public final boolean isFinished() {
        return getJobState() == JobState.EXITED;
    }

    public abstract boolean jobWasSuccessful();

    public abstract void killJob();


    public Pair<String,String> createLaunchScript() {
        List<String> startupScript = new ArrayList<>();

        String scriptExt = FileTools.isWindows() ? ".bat" : ".sh";
        String dir = getRunDir()==null? System.getProperty("user.dir") : getRunDir();
        FileTools.makeDirs(dir);

        startupScript.add("cd " + dir);
        startupScript.add(getCommand() + " > " + DEFAULT_COMMAND_LOG_FILE + " 2>&1");

        String startupScriptName = dir + File.separator + DEFAULT_SCRIPT_NAME + scriptExt;
        FileTools.writeLinesToTextFile(startupScript, startupScriptName);
        new File(startupScriptName).setExecutable(true);
        String startupScriptLog = dir + File.separator + DEFAULT_SCRIPT_LOG_FILE;
        return new Pair<String,String>(startupScriptName,startupScriptLog);
    }
    /**
     * @return the command
     */
    public String getCommand() {
        return command;
    }



    /**
     * @param command the command to set
     */
    public void setCommand(String command) {
        this.command = command;
    }

    /**
     * Set the command to run a RapidWright main class
     * @param mainClass the main class to use
     * @param memoryLimitMB maximum memory in MB
     * @param arguments command arguments as single string
     */
    public void setRapidWrightCommand(Class<?> mainClass, int memoryLimitMB, boolean enableAssertions, String arguments) {
        command = System.getProperty("java.home")+"/bin/java -cp "
                + System.getProperty("java.class.path") + " -Xmx"+memoryLimitMB+"m "
                + (enableAssertions ? "-ea " : "")
                + mainClass.getCanonicalName()+" "+arguments;
    }

    /**
     * @return the jobNumber
     */
    public long getJobNumber() {
        return jobNumber;
    }

    /**
     * @param jobNumber the jobNumber to set
     */
    public void setJobNumber(long jobNumber) {
        this.jobNumber = jobNumber;
    }

    /**
     * @return the runDir
     */
    public String getRunDir() {
        return runDir;
    }

    /**
     * @param runDir the runDir to set
     */
    public void setRunDir(String runDir) {
        this.runDir = runDir;
    }

    public String toString() {
        return Long.toString(jobNumber);
    }

    public Optional<List<String>> getLastLogLines() {
        String logFileName = getLogFilename();
        if (new File(logFileName).exists()) {
            ArrayList<String> lines = FileTools.getLinesFromTextFile(logFileName);
            int start = lines.size() >= 8 ? lines.size()-8 : 0;
            return Optional.of(IntStream.range(start, lines.size()).mapToObj(lines::get).collect(Collectors.toList()));
        }
        return Optional.empty();
    }

    public String getLogFilename() {
        return getRunDir() + File.separator + Job.DEFAULT_COMMAND_LOG_FILE;
    }
}
