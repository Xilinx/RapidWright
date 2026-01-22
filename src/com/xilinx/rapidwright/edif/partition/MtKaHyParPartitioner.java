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

    private double epsilon = 0.03; // Imbalance for partition
    
    private String objective = "cut"; // Cost function 
    
    private String presetType = "default";
    
    private Path outputDir = null; // Use current working directory

    private int seed = 0;

    private Path fixedVerticesFile = null; // Manual partition constraints

    /**
     * Executes the MtKaHyPar partitioning tool with configured parameters.
     * @return Exit code from the partitioner execution
     */
    @Override
    public Integer runPartitioner() {
        // If fixed vertices are present, override incompatible presets
        String effectivePreset = presetType;
        if (fixedVerticesFile != null) {
            if ("deterministic".equals(effectivePreset) || "large_k".equals(effectivePreset)) {
                System.out.println("partitioner debug: fixed vertices present; " +
                   "overriding preset to quality");
                effectivePreset = "quality";
            }
        }
        String cmd = name 
                + " -h " + getInputFile() 
                + " --preset-type=" + effectivePreset + " "
                + " -t " + numOfThreads
                + " -k " + getKPartitions() 
                + " --seed " + seed
                + " --epsilon " + epsilon
                + " --objective "+ objective
                + " --write-partition-file=true"
                + " --verbose=true" // Displays detailed information on the partitioning process
                + " --show-detailed-timings=true"; // sub-timings of each phase of partitioning
        if (fixedVerticesFile != null) {
            cmd += " -f " + fixedVerticesFile;
        }

        
        String[] environ = null; // inherit env
        File runDir = getOutputDir().toFile();

        return FileTools.runCommand(cmd, true, environ, runDir);
    }

    /**
     * Gets the name of this partitioner.
     * @return The partitioner name "MtKaHyPar"
     */
    @Override
    public String Name() {
        return name;
    }

    /**
     * Sets the number of partitions to create.
     * @param k The number of partitions
     */
    @Override
    public void setKPartitions(int k) {
        this.k = k;
    }

    /**
     * Sets the input hypergraph file path.
     * @param filePath Path to the input .hgr file
     */
    @Override
    public void setInputFile(Path filePath) {
        this.inputFile = filePath;
    }

    /**
     * Gets the input hypergraph file path.
     * @return Path to the input file
     */
    @Override
    public Path getInputFile() {
        return inputFile;
    }

    /**
     * Gets the output directory for partition results.
     * @return Path to output directory
     */
    public Path getOutputDir() {
        return outputDir == null ? Paths.get(System.getProperty("user.dir")) : outputDir;
    }

    /**
     * Sets the output directory for partition results.
     * @param d Path to output directory
     */
    public void setOutputDir(Path d) {
        this.outputDir = d;
    }

    /**
     * Gets the expected output partition file path.
     * @return Path to the partition solution file
     */
    @Override
    public Path getOutputFile() {
        Path outputFile = getOutputDir()
                .resolve(getInputFile() + ".part" + getKPartitions() + ".epsilon" + epsilon
                + ".seed" + seed + ".KaHyPar");
        return outputFile;
    }

    /**
     * Gets the number of partitions configured.
     * @return The number of partitions
     */
    @Override
    public Integer getKPartitions() {
        return k;
    }

    /**
     * Sets the number of threads for partitioning.
     * @param t Number of threads to use
     */
    public void setNumThreads(int t) {
        this.numOfThreads = t;
    }

    /**
     * Sets the partition imbalance tolerance.
     * @param e Epsilon value for imbalance
     */
    public void setEpsilon(double e) {
        this.epsilon = e;
    }

    /**
     * Sets the random seed for partitioning.
     * @param s Seed value for reproducibility
     */
    public void setSeed(int s) {
        this.seed = s;
    }

    /**
     * Sets the partitioner preset configuration type.
     * @param p Preset type like "default" or "deterministic"
     */
    public void setPresetType(String p) {
        this.presetType = p;
    }

    /**
     * Sets the partitioning objective function.
     * @param o Objective like "cut", "km1", or "soed"
     */
    public void setObjective(String o) {
        if (o != null && !o.isEmpty()) {
            this.objective = o;
        }
    }

    /**
     * Gets the current partitioning objective function.
     * @return The objective function name
     */
    public String getObjective() {
        return this.objective;
    }

    /**
     * Sets the fixed vertices constraint file path.
     * @param p Path to the .fix file
     */
    public void setFixedVerticesFile(Path p) {
        this.fixedVerticesFile = p;
    }

    /**
     * Gets the fixed vertices constraint file path.
     * @return Path to the .fix file or null
     */
    public Path getFixedVerticesFile() {
        return this.fixedVerticesFile;
    }


}
