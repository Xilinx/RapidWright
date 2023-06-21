/*
 *
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.util;

import com.xilinx.rapidwright.design.Design;

/**
 * Convenience utility to enable updating a DCP's EDIF file from the command
 * line.
 *
 */
public class ReplaceEDIFInDCP {
    public static void main(String[] args) {
        if (args.length < 2 || args.length > 4) {
            System.out.println(
                    "USAGE: ReplaceEDIFInDCP <dcp filename> <new edif filename> [output dcp filename]");
            return;
        }
        boolean success = false;
        if (args.length == 2) {
            success = Design.replaceEDIFinDCP(args[0], args[1]);
        } else {
            success = Design.replaceEDIFinDCP(args[0], args[1], args[2]);
        }
        if (!success) {
            System.exit(1);
        }
    }
}
