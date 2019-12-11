/*
 *
 * Copyright (c) 2019 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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

package com.xilinx.rapidwright.timing;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Build a delay model.
 *
 * Never construct DelayModel directly. DelayModelBuilder guarantees that there is at most one DelayModel
 * ie., DelayModelBuilder returns the existing model.
 */
class DelayModelBuilder {

    // Adding new mode or source requires appending them to the end of valid_mode or valid_source.
    // Never change the order of existing entries.

    /**
     * List all the valid modes to store the model. Adding new modes require appending them to the 
     * end of valid_mode or valid_source. Never change the order of existing entries.
     */
    private static String[]   valid_mode   = {"small"};
    /**
     * List all the valid sources to store the model. Adding new sources require appending them to 
     * the end of valid_mode or valid_source. Never change the order of existing entries.
     */
    private static String[]   valid_source = {"text"};

    private static DelayModel aModel       = null;

    /**
     * Prepare the appropriate input file for {@link #getDelayModel(String, String, String)}
     */
    public static DelayModel getDelayModel(String series) {
        String fileName = TimingModel.TIMING_DATA_DIR + File.separator +series+
                File.separator + "intrasite_delay_terms.txt";
        return getDelayModel("small", "text", fileName);
    }

    /**
     * The method that decides to build a new model or to return the existing one.
     * Please see the method newDelayModel for parameters' description.
     */
    private static DelayModel getDelayModel(String mode, String source, String fileName) {
        if (aModel == null) {
            synchronized (DelayModelBuilder.class) {
                if (aModel == null) {
                    newDelayModel(mode, source, fileName);
                }
            }
        }
        return aModel;
    }

    /**
     * The method to build DelayModel and DelayModelSource according to the given parameters.
     * @param mode      The type of delay model. It defines how data are stored which will affect 
     * the memory requirement and how fast the lookup is. Currently, the only valid entry is "small".
     * @param source    The source of delay model. Currently, the only valid entry is "text".
     * @param fileName  The text file describing the delay model.
     * @throws IllegalArgumentException  This method throw IllegalArgumentException if the fileName
     *  does not exist.
     */
    private static void newDelayModel(String mode, String source, String fileName) {
        DelayModelSource src;
        if (source.equalsIgnoreCase(valid_source[0])) {
            src = new DelayModelSourceFromText(fileName);
        } else {
            throw new IllegalArgumentException("DelayModelBuilder: Unknown source to newDelayModel.");
        }

        if (mode.equalsIgnoreCase(valid_mode[0])) {
            aModel = new SmallDelayModel(src);
        } else {
            throw new IllegalArgumentException("DelayModelBuilder: Unknown mode to newDelayModel.");
        }
    }


    // ************************    for testing     ***********************

    /**
     * For unit testing.
     */
    private static int testLogicDelay(DelayModel a, String belName, List<String> config, 
            String[] src, String[] dst) {

        int count = 0;
            for (String s : src) {
                for (String t : dst) {
                    try {
                        short dly = a.getLogicDelay(belName, s, t, config);
                     //   System.out.println(s + " " + t + " " + dly);
                        count++;
                    } catch (IllegalArgumentException ex) {
                        System.out.println("EXCEPTION: " + ex.getMessage());
                    }
                }
            }
        return count;
    }

    /**
     * For unit testing.
     */
    private static int testLogicDelayCarry8(DelayModel a, List<String> config, String fileName) {
        String[] src = {"CIN", "AX", "BX", "CX", "DX", "EX", "FX", "GX", "HX",
                "DI0", "DI1", "DI2", "DI3", "DI4", "DI5", "DI6", "DI7",
                "S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7"};
        String[] dst = {"CO0", "CO1", "CO2", "CO3", "CO4", "CO5", "CO6", "CO7",
                "O0", "O1", "O2", "O3", "O4", "O5", "O6", "O7"};

        int count = 0;
        try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(fileName));
            for (String s : src) {
                for (String t : dst) {
//                    System.out.println(s + " " + t);
                    short dly = a.getLogicDelay("CARRY8", s, t, config);
                    writer.write(s + " " + t + " " + dly + "\n");
//                    System.out.println(s + " " + t + " " + dly);
                    if (dly >= 0) {
                        count++;
                    }
                }
            }
            writer.close();
        }  catch (IOException ex) {
            System.out.println("EXCEPTION: " + ex.getMessage());
        }
        return count;
    }

    /**
     * For unit testing.
     */
    public static void main(String args[]) {

        long total_before= Runtime.getRuntime().totalMemory();
        long free_before = Runtime.getRuntime().freeMemory();

        DelayModel a;
        a = DelayModelBuilder.getDelayModel("ultrascaleplus");

        // intraSite delay

//        // test intra site delays. input site pins to LUT
//        if (true) {
//            int count = 0;
//            for (String s : new String[]{"SLICEL", "SLICEM"}) {
//                for (Integer i : new Integer[]{5,6}) {
//                    for (int p = 1; p <= i; p++) {
//                        for (String L : new String[]{"A", "B", "C", "D", "E", "F", "G", "H"}) {
//                            String fr = L + p;
//                            String to = L + i + "LUT/A" + p;
//                            System.out.println("Delay in " + s + "  fr " + fr + "  to " + to + " = " +
//                                    a.getIntraSiteDelay(s, fr, to));
//                            count++;
//                        }
//                    }
//                }
//            }
//            System.out.println("total " + count);
//        }
//        // test intra site delays. input site pins to FF
//        if (true) {
//            int count = 0;
//            for (String s : new String[]{"SLICEL", "SLICEM"}) {
//                String[] frs = {"X",    "_I"};
//                String[] tos = {"FF/D", "FF2/D"};
//                for (int i=0; i < frs.length; i++) {
//                    String f = frs[i]; String t = tos[i];
//                    for (char L : new char[]{'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'}) {
//                        String fr = L + f;
//                        String to = L + t;
//                        System.out.println("Delay in " + s + "  fr " + fr + "  to " + to + " = " +
//                                a.getIntraSiteDelay(s, fr, to));
//                        count++;
//                    }
//                }
//            }
//            System.out.println("total " + count);
//        }
//        // test intra site delays. FF-output site pins
//        if (true) {
//            int count = 0;
//            for (String s : new String[]{"SLICEL", "SLICEM"}) {
//                String[] frs = {"FF/Q", "FF2/Q"};
//                String[] tos = {"Q",    "Q2"};
//                for (int i=0; i < frs.length; i++) {
//                    String f = frs[i]; String t = tos[i];
//                    for (char L : new char[]{'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'}) {
//                        String fr = L + f;
//                        String to = L + t;
//                        System.out.println("Delay in " + s + "  fr " + fr + "  to " + to + " = " +
//                                a.getIntraSiteDelay(s, fr, to));
//                        count++;
//                    }
//                }
//            }
//            System.out.println("total " + count);
//        }
//        // test intra site delays. LUT-output site pins
//        if (true) {
//            int count = 0;
//            for (String s : new String[]{"SLICEL", "SLICEM"}) {
//                String[] frs = {"6LUT/O6", "6LUT/O6", "5LUT/O5"};
//                String[] tos = {"MUX",     "_O",      "MUX"};
//                for (int i=0; i < frs.length; i++) {
//                    String f = frs[i]; String t = tos[i];
//                    for (char L : new char[]{'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'}) {
//                        String fr = L + f;
//                        String to = L + t;
//                        System.out.println("Delay in " + s + "  fr " + fr + "  to " + to + " = " +
//                                a.getIntraSiteDelay(s, fr, to));
//                        count++;
//                    }
//                }
//            }
//            System.out.println("total " + count);
//        }
//        // test intra site delays. LUT-FF
//        if (true) {
//            int count = 0;
//            for (String s : new String[]{"SLICEL", "SLICEM"}) {
//                for (String f : new String[]{"6LUT/O6", "5LUT/O5"}) {
//                    for (String t : new String[]{"FF/D", "FF2/D"}) {
//                        for (char L : new char[]{'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'}) {
//                            String fr = L + f;
//                            String to = L + t;
//                            System.out.println("Delay in " + s + "  fr " + fr + "  to " + to + " = " +
//                                    a.getIntraSiteDelay(s, fr, to));
//                            count++;
//                        }
//                    }
//                }
//            }
//            System.out.println("total " + count);
//        }
//
//        // measure runtime for looking up intraSite delays
//        if (true) {
//            int count = 0;
//            // time measurement
//            long startTime = System.nanoTime();
//            for (int i = 0; i < 100000 ; i++) {
//                // 64 lookups
//                for (String s : new String[]{"SLICEL", "SLICEM"}) {
//                    for (String f : new String[]{"6LUT/O6", "5LUT/O5"}) {
//                        for (String t : new String[]{"FF/D", "FF2/D"}) {
//                            for (char L : new char[]{'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H'}) {
//                                String fr = L + f;
//                                String to = L + t;
//                                a.getIntraSiteDelay(s, fr, to);
//                                count++;
//                            }
//                        }
//                    }
//                }
//            }
//            long endTime = System.nanoTime();
//            long elapsedTime = endTime - startTime;
//
//            //   64000  58 ms = .9 us / lookup
//            //  640000 144 ms = .2 us / lookup
//            // 6400000 693 ms = .1 us / lookup
//            System.out.print("Execution time of " + count + " lookups is " + elapsedTime / 1000000 + " ms.");
//            System.out.println(" (" +  1.0*elapsedTime / (count * 1000) + " us. per lookup.)");
//        }


        // logic delay
        // A6LUT
        if (false) {
            String belName = "A6LUT";
            //System.out.println(belName);
            List<String> config = new ArrayList<String>();
            String[] src = {"A6", "A5", "A4", "A3", "A2", "A1"};
            String[] dst = {"O6"};
            testLogicDelay(a, belName, config, src, dst);
        }
        // A5LUT
        if (false) {
            String belName = "A5LUT";
            //System.out.println(belName);
            List<String> config = new ArrayList<String>();
            String[] src = {"A5", "A4", "A3", "A2", "A1"};
            String[] dst = {"O5"};
            testLogicDelay(a, belName, config, src, dst);
        }
        // E6LUT
        if (false) {
            String belName = "E6LUT";
            //System.out.println(belName);
            List<String> config = new ArrayList<String>();
            String[] src = {"A6", "A5", "A4", "A3", "A2", "A1"};
            String[] dst = {"O6"};
            testLogicDelay(a, belName, config, src, dst);
        }
        // E5LUT
        if (false) {
            String belName = "E5LUT";
            //System.out.println(belName);
            List<String> config = new ArrayList<String>();
            String[] src = {"A5", "A4", "A3", "A2", "A1"};
            String[] dst = {"O5"};
            testLogicDelay(a, belName, config, src, dst);
        }
        // AFF
        if (false) {
            String belName = "AFF";
           // System.out.println(belName);
            List<String> config = new ArrayList<String>();
            String[] src = {"CLK"};
            String[] dst = {"D", "Q"};
            testLogicDelay(a, belName, config, src, dst);
        }
        //AFF2
        if (false) {
            String belName = "AFF2";
           // System.out.println(belName);
            List<String> config = new ArrayList<String>();
            String[] src = {"CLK"};
            String[] dst = {"D", "Q"};
            testLogicDelay(a, belName, config, src, dst);
        }
        // HFF
        if (false) {
            String belName = "HFF";
         //  System.out.println(belName);
            List<String> config = new ArrayList<String>();
            String[] src = {"CLK"};
            String[] dst = {"D", "Q"};
            testLogicDelay(a, belName, config, src, dst);
        }
        //HFF2
        if (false) {
            String belName = "HFF2";
          //  System.out.println(belName);
            List<String> config = new ArrayList<String>();
            String[] src = {"CLK"};
            String[] dst = {"D", "Q"};
            testLogicDelay(a, belName, config, src, dst);
        }
        // test SINGLE_CY8
        if (false) {
            // check against
            // CI->  adder_c8_axci.txt
            // other adder_c8_gndci.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:AX"); config.add("CARRY_TYPE:SINGLE_CY8");
            testLogicDelayCarry8(a, config, "test_c8_axci.out");
        }
        if (false) {
            // check against adder_c8.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:CIN"); config.add("CARRY_TYPE:SINGLE_CY8");
            testLogicDelayCarry8(a, config, "test_c8.out");
        }
        if (false) {
            // check against adder_c8_gndci.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:GND"); config.add("CARRY_TYPE:SINGLE_CY8");
            testLogicDelayCarry8(a, config, "test_c8_gndci.out");
        }
        if (false) {
            // check against adder_c8_vccci.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:VCC"); config.add("CARRY_TYPE:SINGLE_CY8");
            testLogicDelayCarry8(a, config, "test_c8_vccci.out");
        }

        // test DUAL_CY4
        if (false) {
            // check against adder_c4_gnd_gnd.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:GND"); config.add("CYINIT_TOP:GND"); config.add("CARRY_TYPE:DUAL_CY4");
            testLogicDelayCarry8(a, config, "test_c4_gnd_gnd.out");
        }
        if (false) {
            // check against adder_c4_ci_gnd.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:CIN"); config.add("CYINIT_TOP:GND"); config.add("CARRY_TYPE:DUAL_CY4");
            testLogicDelayCarry8(a, config, "test_c4_ci_gnd.out");
        }
        if (false) {
            // check against adder_c4_ax_gnd.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:AX"); config.add("CYINIT_TOP:GND"); config.add("CARRY_TYPE:DUAL_CY4");
            testLogicDelayCarry8(a, config, "test_c4_ax_gnd.out");
        }
        if (false) {
            // check against adder_c4_gnd_ex.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:GND"); config.add("CYINIT_TOP:EX"); config.add("CARRY_TYPE:DUAL_CY4");
            testLogicDelayCarry8(a, config, "test_c4_gnd_ex.out");
        }
        if (false) {
            // check against adder_c4_ax_ex.txt
            List<String> config = new ArrayList<String>();
            config.add("CYINIT_BOT:AX"); config.add("CYINIT_TOP:EX"); config.add("CARRY_TYPE:DUAL_CY4");
            testLogicDelayCarry8(a, config, "test_c4_ax_ex.out");
        }

        // measure runtime for looking up logic delays
        if (true) {
            int count = 0;
            // time measurement
            long startTime = System.nanoTime();
            for (int i = 0; i < 10000 ; i++) {
                List<String> config = new ArrayList<String>();
                config.add("CYINIT_BOT:AX"); config.add("CYINIT_TOP:EX"); config.add("CARRY_TYPE:DUAL_CY4");
                count += testLogicDelayCarry8(a, config, "dummy");
            }
            long endTime = System.nanoTime();
            long elapsedTime = endTime - startTime;

            // 880000 9129 ms = 10 us / lookup
            System.out.print("Execution time of " + count + " lookups is " + elapsedTime / 1000000 + " ms.");
            System.out.println(" (" +  1.0*elapsedTime / (count * 1000) + " us. per lookup.)");
        }


        long total_after= Runtime.getRuntime().totalMemory();
        long free_after = Runtime.getRuntime().freeMemory();

        long beforeUsedMem=total_before - free_before;
        long afterUsedMem=total_after - free_after;
        long actualMemUsed=afterUsedMem-beforeUsedMem;
        System.out.println("Max memory usage " + actualMemUsed);
        System.out.println("total before " + total_before + " after " + total_after);
        System.out.println("The printed memory usage is valid only if total before and after are equal.");

    }
}
