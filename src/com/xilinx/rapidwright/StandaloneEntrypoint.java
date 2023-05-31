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

package com.xilinx.rapidwright;


import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Jython;
import com.xilinx.rapidwright.util.MessageGenerator;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.security.CodeSource;

/**
 * Main entry point for the RapidWright executable stand-alone jar
 * @author clavin
 *
 */
public class StandaloneEntrypoint {
    /** Option to unpack ./data/ directory into current directory */
    public static final String UNPACK_OPTION_NAME = "--unpack_data";
    /** Option to create JSON Kernel file for Jupyter Notebook support */
    public static final String CREATE_JUPYTER_KERNEL = "--create_jupyter_kernel";
    public static final String HELP_OPTION_NAME = "--help";
    public static final String JUPYTER_KERNEL_FILENAME = "kernel.json";
    public static final String JUPYTER_JYTHON_KERNEL_NAME = "jython27";
    public static final String[] RAPIDWRIGHT_OPTIONS = new String[]{CREATE_JUPYTER_KERNEL, HELP_OPTION_NAME, UNPACK_OPTION_NAME};

    private static String toWindowsPath(String linuxPath) {
        linuxPath = linuxPath.startsWith("/") ? linuxPath.substring(1) : linuxPath;
        return linuxPath.replace("/", "\\\\");
    }

    public static void createJupyterKernelFile() {
        try {
            FileTools.makeDirs(JUPYTER_JYTHON_KERNEL_NAME);
            File f = new File(JUPYTER_JYTHON_KERNEL_NAME + File.separator + JUPYTER_KERNEL_FILENAME);
            BufferedWriter bw = new BufferedWriter(new FileWriter(f));
            bw.write("{\n");
            bw.write(" \"argv\": [\"java\",\n");

            // Figure proper CLASSPATH based on if this is running from a jar or not
            CodeSource src = StandaloneEntrypoint.class.getProtectionDomain().getCodeSource();
            if (src == null) {
                MessageGenerator.briefError("Couldn't identify classpath for running RapidWright.  "
                        + "Either set the CLASSPATH correctly, or modify " + f.getAbsolutePath() + " "
                        + "to include classpath information");
            }
            bw.write("          \"-classpath\",\n");
            boolean isWindows = FileTools.isWindows();
            String location = src.getLocation().getPath();
            location = isWindows ? toWindowsPath(location) : location;
            if (location.toLowerCase().endsWith(".jar")) {
                bw.write("          \""+location+"\",\n");
            } else {
                bw.write("          \""+location+ "");
                File binFolder = new File(location);
                if (binFolder.isDirectory() && binFolder.getName().equals("bin")) {
                    location = binFolder.getParentFile().getAbsolutePath();
                }
                File jarDir = new File(location + File.separator + FileTools.JARS_FOLDER_NAME);
                if (jarDir != null && jarDir.isDirectory()) {
                    for (String jar : jarDir.list()) {
                        if (isWindows && jar.contains("-linux64-")) continue;
                        if (!isWindows && jar.contains("-win64-")) continue;
                        if (jar.contains("javadoc")) continue;
                        String jarPath = jarDir.getAbsolutePath() + File.separator;
                        if (isWindows) {
                            jarPath = jarPath.replace("\\", "\\\\");
                        }
                        bw.write(File.pathSeparator + jarPath + jar);
                    }
                } else {
                    MessageGenerator.briefError("ERROR: Couldn't read "+jarDir.getAbsolutePath()+" directory, please check RapidWright installation.");
                }

                bw.write("\",\n");
            }
            bw.write("          \"org.jupyterkernel.kernel.Session\",\n");
            bw.write("          \"-k\", \"python\",\n");
            bw.write("          \"-f\", \"{connection_file}\"],\n");
            bw.write(" \"display_name\": \"Jython 2.7\",\n");
            bw.write(" \"language\": \"python\"\n");
            bw.write("}\n");
            bw.close();
            System.out.println("Wrote Jupyter Notebook Kernel File: '" + f.getAbsolutePath() + "'\n");
            System.out.println("You can install the RapidWright (Jython 2.7) kernel by running:");
            System.out.println("    $ jupyter kernelspec install " + f.getAbsolutePath().replace(File.separator + JUPYTER_KERNEL_FILENAME, ""));
            System.out.println("and list currently installed kernels with:");
            System.out.println("    $ jupyter kernelspec list");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        for (String s : args) {
            if (s.equals(UNPACK_OPTION_NAME)) {
                boolean success = FileTools.unPackSupportingJarData();
                if (success) {
                    System.out.println("Successfully unpacked "
                            + " RapidWright jar data to "+FileTools.getExecJarStoragePath()+". "
                            + "To override, please set the environment variable RAPIDWRIGHT_PATH to"
                            + " point to the desired data location.");
                    return;
                }
                else {
                    throw new RuntimeException("ERROR: Couldn't unpack ./data directory "
                            + "from RapidWright jar.");
                }
            } else if (s.equals(CREATE_JUPYTER_KERNEL)) {
                createJupyterKernelFile();
                return;
            } else if (s.equals(HELP_OPTION_NAME)) {
                System.out.println("*** RapidWright specific options: ***");
                for (String option : RAPIDWRIGHT_OPTIONS) {
                    System.out.println("\t" + option);
                }
                System.out.println("*** Jython --help output: ***");

            }
        }

        Jython.main(args);
    }
}
