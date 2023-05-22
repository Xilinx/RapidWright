/*
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

package com.xilinx.rapidwright;

import java.io.IOException;

import org.python.google.common.reflect.ClassPath;
import org.python.google.common.reflect.ClassPath.ClassInfo;
import org.python.util.jython;

/**
 * Convenience class to run arbitrary Java methods in RapidWright. Depends on
 * the built-in Jython interpreter to run Jython/Java code.
 *
 */
public class RunCode {

    public static final String DONT_PRINT_OPT = "-d";
    public static final String DONT_PRINT_OPT_FULL = "--dont-print-results";

    /**
     * Provide an arbitrary RapidWright Jython/Java code statement(s) to run (useful
     * for running methods that can't be run from the command line because they
     * don't have a corresponding main() entry point.
     * 
     * @param args List of Jython/Java commands to run. Each String is a Jython/Java
     *             command run in the Jython interpreter.
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("USAGE: [" + DONT_PRINT_OPT + " | " + DONT_PRINT_OPT_FULL + "] "
                    + "<RapidWright Java/Jython command(s)> ...");
            return;
        }
        boolean printResults = true;
        if (args[0].equals(DONT_PRINT_OPT) || args[0].equals(DONT_PRINT_OPT_FULL)) {
            printResults = false;
        }

        ClassPath cp = ClassPath.from(Thread.currentThread().getContextClassLoader());
        StringBuilder jythonCmd = new StringBuilder();
        for (ClassInfo s : cp.getAllClasses()) {
            if (s.getPackageName().startsWith("com.xilinx.rapidwright")) {
                // Filter out inner classes and this class to avoid run away recursion
                if (s.getSimpleName() == null || s.getSimpleName().isEmpty()) continue;
                if (Character.isLowerCase(s.getSimpleName().charAt(0))) continue;
                if (s.toString().contains("$")) continue;
                if (s.getSimpleName().equals("Run")) continue;                
                if (s.getPackageName().startsWith("com.xilinx.rapidwright.gui")) continue;
                
                jythonCmd.append("from " + s.getPackageName() + " import " + s.getSimpleName() + ";");
            }
        }
        for (int i = (printResults ? 0 : 1); i < args.length; i++) {
            if (printResults) {
                jythonCmd.append("print(" + args[i] + ");");
            } else {
                jythonCmd.append(args[i] + ";");
            }
        }

        String[] jythonArgs = new String[2];
        jythonArgs[0] = "-c";
        jythonArgs[1] = jythonCmd.toString();
        // System.out.println(jythonCmd);
        jython.main(jythonArgs);
    }
}
