/*
 *
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

package com.xilinx.rapidwright.edif.partition;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.xilinx.rapidwright.util.FileTools;

/**
 * Facilitates calling the MtKaHyPar partitioning tool.
 */
public class MtKaHyParPartitioner implements AbstractPartitioner {

    private static final String name = "MtKaHyPar";

    private Integer k;

    private Path inputFile;

    private int numOfThreads = 2;

    private double epsilon = 0.03;
    
    private String objective = "cut";
    
    private Path outputDir = null; // Use current working directory

    private int seed = 0;

    @Override
    public Integer runPartitioner() {
        String cmd = name 
                + " -h " + getInputFile() 
                + " --preset-type=default "
                + " -t " + numOfThreads
                + " -k " + getKPartitions() 
                + " --seed " + seed
                + " --epsilon " + epsilon
                + " --objective "+ objective
                + " --write-partition-file=true";

//        String[] cmd = new String[] {name
//                , "-h" , getInputFile().toString() 
//                , "--preset-type=default"
//                , "-t" , Integer.toString(numOfThreads)
//                , "-k" , Integer.toString(getKPartitions()) 
//                , "--seed " , Integer.toString(seed)
//                , "--epsilon " , Double.toString(epsilon)
//                , "--objective ", objective
//                , "--write-partition-file=true" };

        
        String[] environ = new String[] {};
        File runDir = getOutputDir().toFile();

        return FileTools.runCommand(cmd, true, environ, runDir);
    }

    @Override
    public String Name() {
        return name;
    }

    @Override
    public void setKPartitions(int k) {
        this.k = k;
    }

    @Override
    public void setInputFile(Path filePath) {
        this.inputFile = filePath;
    }

    @Override
    public Path getInputFile() {
        return inputFile;
    }

    public Path getOutputDir() {
        return outputDir == null ? Paths.get(System.getProperty("user.dir")) : outputDir;
    }

    @Override
    public Path getOutputFile() {
        Path outputFile = getOutputDir()
                .resolve(getInputFile() + ".part" + getKPartitions() + ".epsilon" + epsilon
                + ".seed" + seed + ".KaHyPar");
        return outputFile;
    }

    @Override
    public Integer getKPartitions() {
        return k;
    }
}
