/*
 *
 * Copyright (c) 2018-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;


import com.xilinx.rapidwright.StandaloneEntrypoint;

/**
 * Main entry point for the RapidWright executable stand-alone jar
 * @author clavin
 * @deprecated Use {@link StandaloneEntrypoint instead}.
 * To be removed in 2024.1.0
 *
 */
public class RapidWright {
    /** Option to unpack ./data/ directory into current directory */
    public static final String UNPACK_OPTION_NAME = StandaloneEntrypoint.UNPACK_OPTION_NAME;
    /** Option to create JSON Kernel file for Jupyter Notebook support */
    public static final String CREATE_JUPYTER_KERNEL = StandaloneEntrypoint.CREATE_JUPYTER_KERNEL;
    public static final String HELP_OPTION_NAME = StandaloneEntrypoint.HELP_OPTION_NAME;
    public static final String JUPYTER_KERNEL_FILENAME = StandaloneEntrypoint.JUPYTER_KERNEL_FILENAME;
    public static final String JUPYTER_JYTHON_KERNEL_NAME = StandaloneEntrypoint.JUPYTER_JYTHON_KERNEL_NAME;
    public static final String[] RAPIDWRIGHT_OPTIONS = StandaloneEntrypoint.RAPIDWRIGHT_OPTIONS;

    public static void main(String[] args) {
        StandaloneEntrypoint.main(args);
    }
}
