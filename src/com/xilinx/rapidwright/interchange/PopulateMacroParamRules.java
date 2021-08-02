/* 
 * Copyright (c) 2021 Xilinx, Inc. 
 * All rights reserved.
 *
 * Author: clavin, Xilinx Research Labs.
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
package com.xilinx.rapidwright.interchange;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Year;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Series;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFName;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LSFJob;

/**
 * This class is intended to find and make available macro parameter propagation rules for 
 * the Interchange.  For example, a LUT6_2 has a property INIT=64'hF000111122223333, which value 
 * gets propagated to its children (LUT5.INIT=32'h22223333 and LUT6.INIT=64'hF000111122223333). 
 * TODO - Still some macros with more complex mappings (RAM32M, for example) are not supported yet.
 */
public class PopulateMacroParamRules {
    private static String TMP_DIR = PopulateMacroParamRules.class.getSimpleName() + File.separator;
    private static String INIT = "INIT";
    private static Map<Series, String> seriesPartMap;
    private static final String[] SENTINEL_STRINGS = new String[] {
       "11" , "22", "33", "44", "55", "66", "77" , "88", "99", "AA", "BB", "CC", "DD", "EE", "FF",
       "A1" , "A2", "A3", "A4", "A5", "A6", "A7" , "A8", "A9", "A1", "AB", "AC", "AD", "AE", "AF",
       "B1" , "B2", "B3", "B4", "B5", "B6", "B7" , "B8", "B9", "BA", "B1", "BC", "BD", "BE", "BF",
       "C1" , "C2", "C3", "C4", "C5", "C6", "C7" , "C8", "C9", "CA", "CB", "C1", "CD", "CE", "CF",
       "D1" , "D2", "D3", "D4", "D5", "D6", "D7" , "D8", "D9", "DA", "DB", "DC", "D1", "DE", "DF",
       "E1" , "E2", "E3", "E4", "E5", "E6", "E7" , "E8", "E9", "EA", "EB", "EC", "ED", "E1", "EF",
       "F1" , "F2", "F3", "F4", "F5", "F6", "F7" , "F8", "F9", "FA", "FB", "FC", "FD", "FE", "F1",
    };
    
    static {
        seriesPartMap = new HashMap<>();
        seriesPartMap.put(Series.Series7, Device.PYNQ_Z1);
        seriesPartMap.put(Series.UltraScale, Device.KCU105);
        seriesPartMap.put(Series.UltraScalePlus, Device.AWS_F1);
        seriesPartMap.put(Series.Versal, "xcvc1802-viva1596-2LP-e-S");
    }
    private static List<EDIFCellInst> involvesParamRule(EDIFCell cell){
        if(cell.getName().startsWith("FD") || cell.getName().startsWith("LD")) {
            return Collections.emptyList();
        }
        List<EDIFCellInst> insts = new ArrayList<>();
        for(EDIFCellInst inst : cell.getCellInsts()) {
            for(EDIFName name : inst.getProperties().keySet()) {
                if(name.getName().contains(INIT)) {
                    insts.add(inst);
                }
            }
        }
        return insts.size() < 2 ? Collections.emptyList() : insts;
    }
    
    private static void createTclScript(Series series, String partName, String tclFileName) {
        List<String> lines = new ArrayList<>();
        lines.add("link_design -part " + partName);
        for(EDIFCell c : Design.getMacroPrimitives(series).getCells()) {
            if(involvesParamRule(c).size() < 2) continue;
            String name = c.getName();
            lines.add("create_cell -reference " + name + " my_"+ name);
            lines.add("set cell [get_cells my_"+name+"]");
            lines.add("set props [report_property -return_string $cell]");
            
            StringBuilder sb = new StringBuilder();
            for(String s : SENTINEL_STRINGS) {
                sb.append("\""+s+ "\" ");
            }
            writeTclProgram("", lines, sb.toString(), name);
            
            sb = new StringBuilder();
            for(int i=1; i < SENTINEL_STRINGS.length; i++) {
                sb.append("\""+SENTINEL_STRINGS[i]+ "\" ");
            }
            writeTclProgram("2", lines, sb.toString(), name);            
        }
        FileTools.writeLinesToTextFile(lines, tclFileName);
    }
    
    private static void writeTclProgram(String itr, List<String> lines, String sentinel, String name) {
        lines.add("set sentinelString"+itr+" [list "+sentinel+"] ");
        lines.add("set sentinelIdx 0");
        lines.add("for {set i 0} {$i < [llength $props]} {set i [expr {$i + 5}]} {");
        lines.add("    set init [lindex $props $i]");
        lines.add("    if {[string first \""+INIT+"\" $init] != -1} {");
        lines.add("        set val [lindex $props [expr {$i+4}]]");
        lines.add("        set width [string range $val 0 [string first \"'\" $val]-1]");
        lines.add("        set newInitVal \"${width}'h\"");
        lines.add("        for {set j 0} {$j < $width} {set j [expr {$j + 8}]} {");
        lines.add("            set newInitVal \"$newInitVal[lindex $sentinelString"+itr+" $sentinelIdx]\"");
        lines.add("            set sentinelIdx [expr $sentinelIdx + 1]");
        lines.add("        }");
        lines.add("        set_property $init $newInitVal $cell");
        lines.add("    }");
        lines.add("}");
        lines.add("set fp [open "+name+".params"+itr+" \"w\"]");
        lines.add("puts $fp [report_property -quiet -return_string $cell]");
        lines.add("write_edif -cell $cell "+name+itr+".edf");
        lines.add("close $fp");
        
    }
    
    private static Job buildInVivado(Series series, String partName) {
        String runDir = TMP_DIR + series;
        FileTools.makeDirs(runDir);
        String tclFileName = "run.tcl";
        createTclScript(series, partName, runDir + File.separator + tclFileName);
        LSFJob job = new LSFJob();
        job.setCommand("vivado -mode batch -source " + tclFileName);
        job.setRunDir(runDir);
        job.launchJob();
        return job;
    }
    
    private static MacroParamRule findRule(String instName, Entry<EDIFName,EDIFPropertyValue> prop, Map<String,String> primParams) {
        String val = prop.getValue().getValue();
        val = val.substring(val.indexOf('h')+1);
        for(Entry<String, String> e : primParams.entrySet()) {
            if(e.getValue().contains(val)) {
                String primVal = e.getValue().substring(e.getValue().indexOf('h')+1);
                int primLength = primVal.length() * 4;
                int count = val.length() * 4;
                int start = primLength - ((primVal.indexOf(val) * 4) + count);
                return MacroParamRule.bitRange(e.getKey(), instName, prop.getKey().getName(), start, count); 
            }
        }
        return null;
    }
    
    public static void runVivadoExtractTests() {
        JobQueue q = new JobQueue();
        for(Series s : Series.values()) {
            Job j = buildInVivado(s,seriesPartMap.get(s));
            q.addRunningJob(j);
        }
        
        q.runAllToCompletion();        
    }
    
    public static Map<Series,Map<String,List<MacroParamRule>>> createMacroParamRules() {
        Map<Series,Map<String,List<MacroParamRule>>> macroRules = new HashMap<>();
        for(Series s : Series.values()) {
            String outputDir = TMP_DIR + s;
            File dir = new File(outputDir);
            Map<String,List<MacroParamRule>> currMap = new HashMap<>();
            macroRules.put(s, currMap);
            for(File file : dir.listFiles()) {
                if(file.getName().endsWith(".params")) {
                    String macroName = file.getName().replace(".params", "");
                    String edfFileName = file.getParentFile().getAbsolutePath() + File.separator 
                            + macroName + ".edf" + File.separator + macroName + File.separator 
                            + macroName + ".edn";
                    List<MacroParamRule> currList = new ArrayList<>();
                    currMap.put(macroName, currList);
                    EDIFNetlist netlist = EDIFTools.readEdifFile(edfFileName);
                    Map<String,String> initValues = new HashMap<>();
                    System.out.println(macroName + ":");
                    for(String line : FileTools.getLinesFromTextFile(file.getAbsolutePath())) {
                        if(line.contains(INIT)) {
                            String[] tokens = line.split("\\s+");
                            initValues.put(tokens[0], tokens[4]);
                            System.out.println("  " + tokens[0] + " -> " + tokens[4]);
                        }
                    }
                    EDIFCell cell = netlist.getCell(macroName);
                    for(EDIFCellInst inst : cell.getCellInsts()) {
                        boolean printedInst = false;
                        for(Entry<EDIFName,EDIFPropertyValue> e : inst.getProperties().entrySet()) {
                            String paramName = e.getKey().getName(); 
                            if(paramName.contains(INIT)) {
                                if(!printedInst) {
                                    System.out.println(" " + inst.getName() + ":");
                                    printedInst = true;
                                }
                                System.out.println("   " + e.getKey() + " -> " + e.getValue());
                                MacroParamRule rule = findRule(inst.getName(), e, initValues);
                                if(rule != null) {
                                    currList.add(rule);
                                }
                            }
                        }
                    }
                }
            }
        }
        return macroRules;
    }
    
    public static void genStaticCode(Map<Series,Map<String,List<MacroParamRule>>> macroRules) {
        String className = "MacroParamMappingRules";
        try(BufferedWriter bw = new BufferedWriter(new FileWriter("src/com/xilinx/rapidwright/interchange/"+className+".java"))){
            ArrayList<String> lines = FileTools.getLinesFromTextFile(FileTools.getRapidWrightPath()+"/doc/SOURCE_HEADER.TXT");
            for(String line : lines) {
                bw.write(line.replace("${year}", Year.now().toString()) + "\n");
            }
            bw.write("package com.xilinx.rapidwright.interchange;\n\n");
            bw.write("import java.util.HashMap;\n");
            bw.write("import java.util.Map;\n\n");
            bw.write("import com.xilinx.rapidwright.device.Series;\n\n");
            bw.write("/**\n");
            bw.write(" * Generated by " + PopulateMacroParamRules.class.getName() + " on " + FileTools.getTimeString() + "\n");
            bw.write(" * RapidWright version: " + Device.RAPIDWRIGHT_VERSION + "\n");
            bw.write(" * Vivado version: " + FileTools.getVivadoVersion() + "\n");
            bw.write(" */\n");
            bw.write("public class "+className+" {\n\n");
            bw.write("    public static Map<Series,Map<String,MacroParamRule[]>> macroRules;\n\n");
            bw.write("    static {\n");
            bw.write("        macroRules = new HashMap<>();\n");
            bw.write("        Map<String, MacroParamRule[]> currMap = null;\n"); 
            for(Series series : Series.values()) {
                bw.write("        currMap = new HashMap<>();\n");
                bw.write("\n        // *** Begin Series."+series+"\n");
                bw.write("        macroRules.put(Series."+series+", currMap);\n");
                Map<String, List<MacroParamRule>> map = macroRules.get(series);
                for(Entry<String, List<MacroParamRule>> e2 : map.entrySet()) {
                    bw.write("        currMap.put(\""+e2.getKey()+"\", new MacroParamRule[] {\n");
                    for(MacroParamRule rule : e2.getValue()) {
                        bw.write("            MacroParamRule.bitRange(\""
                                +rule.getPrimParam()+"\", \""
                                +rule.getInstName()+"\", \""
                                +rule.getInstParam()+"\", "
                                +rule.getBitSlice()[0]+", " 
                                +rule.getBitSlice().length+"),\n");    
                    }
                    bw.write("        });\n");
                }
            }
            bw.write("    }\n");
            bw.write("}\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public static void main(String[] args) throws IOException {
        //runVivadoExtractTests();
        Map<Series,Map<String,List<MacroParamRule>>> macroRules = createMacroParamRules();
        genStaticCode(macroRules);
    }
}
