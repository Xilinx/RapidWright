/* 
 * Copyright (c) 2021 Xilinx, Inc. 
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
 
package com.xilinx.rapidwright.device;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LSFJob;
import com.xilinx.rapidwright.util.Pair;

public class EnumerateIOExpansions {

    private static HashMap<Part,Pair<Unisim[], IOStandard[]>> diffIOMap;
    
    static {
        Unisim[] series7DiffIOs = new Unisim[] {
                Unisim.IBUFDS,
                Unisim.IBUFDS_DIFF_OUT,
                Unisim.IBUFDS_DIFF_OUT_IBUFDISABLE,
                Unisim.IBUFDS_DIFF_OUT_INTERMDISABLE,
                Unisim.IBUFDS_IBUFDISABLE,
                Unisim.IBUFDS_INTERMDISABLE,
                Unisim.IBUFDS_GTE2,
                Unisim.IOBUFDS,
                Unisim.IOBUFDS_DCIEN,
                Unisim.IOBUFDS_DIFF_OUT,
                Unisim.IOBUFDS_DIFF_OUT_DCIEN,
                Unisim.IOBUFDS_DIFF_OUT_INTERMDISABLE,
                Unisim.IOBUFDS_INTERMDISABLE,
                Unisim.OBUFDS,
                Unisim.OBUFTDS,
        };

        Unisim[] ultrascaleDiffIOs = new Unisim[] {
                Unisim.IBUFDS,
                Unisim.IBUFDS_DIFF_OUT,
                Unisim.IBUFDS_DIFF_OUT_IBUFDISABLE,
                Unisim.IBUFDS_DIFF_OUT_INTERMDISABLE,
                Unisim.IBUFDS_DPHY,
                Unisim.IBUFDS_IBUFDISABLE,
                Unisim.IBUFDS_INTERMDISABLE,
                Unisim.IBUFDSE3,
                Unisim.IOBUFDS,
                Unisim.IOBUFDS_DCIEN,
                Unisim.IOBUFDS_DIFF_OUT,
                Unisim.IOBUFDS_DIFF_OUT_DCIEN,
                Unisim.IOBUFDS_DIFF_OUT_INTERMDISABLE,
                Unisim.IOBUFDS_INTERMDISABLE,
                Unisim.IOBUFDSE3,
                Unisim.OBUFDS,
                Unisim.OBUFDS_DPHY,
                Unisim.OBUFTDS,
        };
        IOStandard[] series7IOStandards = new IOStandard[] {
                IOStandard.DIFF_HSTL_I,
                IOStandard.DIFF_HSTL_II,
                IOStandard.DIFF_HSTL_I_18,
                IOStandard.DIFF_HSTL_II_18,
                IOStandard.DIFF_SSTL18_I,
                IOStandard.DIFF_SSTL18_II,
                IOStandard.DIFF_SSTL15,
                IOStandard.DIFF_SSTL15_R,
                IOStandard.DIFF_SSTL135,
                IOStandard.DIFF_SSTL135_R,
                IOStandard.DIFF_HSUL_12,
                IOStandard.DIFF_MOBILE_DDR,
                IOStandard.BLVDS_25,
                IOStandard.LVDS_25,
                IOStandard.RSDS_25,
                IOStandard.TMDS_33,
                IOStandard.MINI_LVDS_25,
                IOStandard.PPDS_25,
        };
        IOStandard[] ultrascaleIOStandards = new IOStandard[] {
                IOStandard.DIFF_HSTL_I,
                IOStandard.DIFF_HSTL_I_18,
                IOStandard.DIFF_SSTL18_I,
                IOStandard.DIFF_SSTL18_II,
                IOStandard.DIFF_SSTL15,
                IOStandard.DIFF_SSTL15_II,
                IOStandard.DIFF_SSTL135,
                IOStandard.DIFF_SSTL135_II,
                IOStandard.DIFF_SSTL12,
                IOStandard.DIFF_HSUL_12,
                IOStandard.DIFF_HSTL_I_DCI,
                IOStandard.DIFF_HSTL_I_DCI_18,
                IOStandard.DIFF_SSTL18_I_DCI,
                IOStandard.DIFF_SSTL15_DCI,
                IOStandard.DIFF_SSTL135_DCI,
                IOStandard.DIFF_SSTL12_DCI,
                IOStandard.DIFF_HSUL_12_DCI,
                IOStandard.LVDS_25,
                IOStandard.LVDS,
                IOStandard.ANALOG,
                IOStandard.LVPECL,
                IOStandard.SLVS_400_18,
                IOStandard.SLVS_400_25,
                IOStandard.SUB_LVDS,
                IOStandard.DIFF_HSTL_I_12,
                IOStandard.DIFF_POD10,
                IOStandard.DIFF_POD12,
                IOStandard.DIFF_HSTL_I_DCI_12,
                IOStandard.DIFF_POD10_DCI,
                IOStandard.DIFF_POD12_DCI,
                IOStandard.MIPI_DPHY_DCI,   
        };
        
        diffIOMap = new HashMap<>();
        diffIOMap.put(PartNameTools.getPart("xc7a35tcsg324-1"), new Pair<Unisim[], IOStandard[]>(series7DiffIOs, series7IOStandards));
        diffIOMap.put(PartNameTools.getPart("xcku035-fbva676-1-c"), new Pair<Unisim[], IOStandard[]>(ultrascaleDiffIOs, ultrascaleIOStandards));
        diffIOMap.put(PartNameTools.getPart("xczu2eg-sbva484-2-e"), new Pair<Unisim[], IOStandard[]>(ultrascaleDiffIOs, ultrascaleIOStandards));
    }
    
    public static void main(String[] args) {

        JobQueue q = new JobQueue();
        for(Entry<Part, Pair<Unisim[], IOStandard[]>> e : diffIOMap.entrySet()) {
            String seriesName = e.getKey().getSeries().toString();
            String pkgPin = seriesName.equals("Series7") ? "D5" : 
                (seriesName.equals("UltraScalePlus") ? "B2" : "D14");
            System.out.println(seriesName);
            if(!FileTools.makeDir(seriesName)) {
                throw new RuntimeException("ERROR: Couldn't create folder " + e.getKey().toString());
            }
            Pair<Unisim[], IOStandard[]> ios = e.getValue();
            for(Unisim u : ios.getFirst()) {
                String unisimName = u.name();
                String dirName = seriesName + File.separator + unisimName + File.separator;
                if(!FileTools.makeDir(dirName)) {
                    throw new RuntimeException("ERROR: Couldn't create folder " + dirName);
                }
                for(IOStandard iostd : ios.getSecond()) {
                    EDIFCell cell = Design.getUnisimCell(u);

                    System.out.println("  " + u + " " + iostd);
                    String ioStddirName = dirName + File.separator + iostd + File.separator;
                    if(!FileTools.makeDir(ioStddirName)) {
                        throw new RuntimeException("ERROR: Couldn't create folder " + ioStddirName);
                    }                    
                    
                    List<String> lines = new ArrayList<>();
                    lines.add("create_project -in_memory -part " + e.getKey().getName());
                    lines.add("add_files top.v");
                    lines.add("add_files top.xdc");
                    lines.add("set_property top top [current_fileset]");
                    lines.add("synth_design");
                    //lines.add("write_checkpoint -force top.dcp");
                    lines.add("set fp [open result.txt w]");
                    lines.add("puts $fp \"" + seriesName + " "+ iostd + " " + u + " [get_property REF_NAME [get_cells inst]]\"");
                    lines.add("close $fp");
                    String runScript = "run.tcl";
                    FileTools.writeLinesToTextFile(lines, ioStddirName + runScript);
                    
                    lines.clear();
                    lines.add("module top(\n");
                    List<EDIFPort> ports = new ArrayList<>(cell.getPorts());
                    for(int i=0; i < ports.size(); i++) {
                        EDIFPort port = ports.get(i);
                        String lastComma = (i == ports.size()-1) ? "" : ","; 
                        if(port.isInput()) {
                            lines.add("    input " + port.getName() + lastComma);
                        }else {
                            lines.add("    output " + port.getName() + "_" + unisimName + lastComma);
                        }
                    }

                    lines.add(");\n");
                    lines.add("    " + u.name() + " inst (");
                    for(int i=0; i < ports.size(); i++) {
                        EDIFPort port = ports.get(i);
                        String lastComma = (i == ports.size()-1) ? "" : ","; 
                        if(port.isInput()) {
                            lines.add("        ." + port.getName() + "(" + port.getName() + ")" + lastComma);
                        }else {
                            lines.add("        ." + port.getName() + "(" + port.getName() +"_"+ unisimName + ")" + lastComma);
                        }
                    }
                    lines.add("    );\n");
                    lines.add("endmodule");
                    FileTools.writeLinesToTextFile(lines, ioStddirName + "top.v");
                    
                    lines.clear();
                    if(unisimName.startsWith("IBUF")) {
                        lines.add("set_property IOSTANDARD "+iostd.name()+" [get_ports I]");
                        lines.add("set_property IOSTANDARD LVCMOS18 [get_ports O_"+unisimName+"]");
                    }else {
                        lines.add("set_property IOSTANDARD LVCMOS18 [get_ports I]");
                        lines.add("set_property IOSTANDARD "+iostd.name()+" [get_ports O_"+unisimName+"]");                        
                    }
                    //lines.add("set_property PACKAGE_PIN B2 [get_ports O_"+unisimName+"]");
                    
                    FileTools.writeLinesToTextFile(lines, ioStddirName + "top.xdc");
                    
                    LSFJob job = new LSFJob();
                    job.setCommand("vivado -mode batch -source " + runScript);
                    job.setRunDir(ioStddirName);
                    job.launchJob();
                    q.addRunningJob(job);
                }
            }
        }
        q.runAllToCompletion();
    }
}
