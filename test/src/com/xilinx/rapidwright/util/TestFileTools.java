/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD AEAI CTO Group.
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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.converter.ConvertWith;
import org.junit.jupiter.params.provider.CsvSource;

import com.xilinx.rapidwright.support.StringArrayConverter;

public class TestFileTools {

    @ParameterizedTest
    @CsvSource({ "'xc7a15t'", "'xc7a15t, xcau10p'" })
    public void testStaticInstallDataFiles(@ConvertWith(StringArrayConverter.class) String[] devices,
            @TempDir Path tmpPath) {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put(FileTools.RAPIDWRIGHT_VARIABLE_NAME, tmpPath.toString());
        String classpath = ManagementFactory.getRuntimeMXBean().getClassPath();
        
        StringBuilder devicesString = new StringBuilder();
        boolean first = true;
        for (String device : devices) {
            if (first) {
                first = false;
            } else {
                devicesString.append(",");
            }
            devicesString.append("\"" + device + "\"");
        }
        
        pb.command("java", "-cp", classpath, RapidWright.class.getCanonicalName(), "-c",
                "from com.xilinx.rapidwright.util import FileTools;"
                + "FileTools.ensureDataFilesAreStaticInstallFriendly("+devicesString+")");
        pb.redirectErrorStream(true);
        pb.inheritIO();
        try {
            Process p = pb.start();
            Assertions.assertEquals(0, p.waitFor());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
        
        for (String expectedFile : FileTools.getAllDependentDataFiles(devices)) {
            Assertions.assertTrue(tmpPath.resolve(expectedFile).toFile().exists());
        }
    }
}
