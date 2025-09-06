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

import java.nio.file.Path;

/**
 * Interface to abstract calling outside partitioning tools. These tools should
 * accept hMETIS file formats.
 */
public abstract interface AbstractPartitioner {

    public Integer runPartitioner();

    public String Name();

    public void setKPartitions(int k);

    public Integer getKPartitions();

    public void setInputFile(Path fileName);

    public Path getInputFile();

    public Path getOutputFile();
}
