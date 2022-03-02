/*
 * 
 * Copyright (c) 2021 Ghent University. 
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.rwroute.RWRouteConfig;
import com.xilinx.rapidwright.util.Pair;

/**
 * A DSPTimingData instance stores logic delay of a DSP block in the design.
 * The logic delay of each DSP block is parsed from a text file that can obtained 
 * by running Vivado with the Tcl script dump_all_dsp_delay.tcl under $RAPIDWRIGHT_PATH/tcl/rwroute.
 * For more information of how to generate DSP timing files of a design, please refer to the Tcl script.
 * When the DSP timing files are ready, please use "--dspTimingDataFolder" option (see {@link RWRouteConfig}),
 * so that the DSP timing info becomes accessible for RWRoute.
 */
public class DSPTimingData{
	/** Full hierarchical name of the DSP block */
	private String blockName;
	/** 
	 * <<Input port name, output port name>, logic delay from the input port to the output port>,
	 *  e.g. <A[1], PCOUT[1]>, 1527
	 */
	private Map<Pair<String, String>, Short> inputOutputDelays;
	/** 
	 * Two separate lists of input and output EDIFPortInst names, 
	 * used for checking if an EDIFPortInst is included in the timing file
	 */
	private List<String> inputPorts;//two lists used for checking if port name included in delay info
	private List<String> outputPorts;
	/** A pin mapping from sub-block cell pin to top level dsp pin */
	private Map<String, String> pinMapping;//from sub-block cell pin to top level dsp pin, string is enough
	/** A flag to indicate if the input file of the DSP is valid */
	private boolean valid;
	
	public DSPTimingData(String fullName, String dspTimingDataFolder){
		this.setBlockName(fullName);
		File dspTimingFile = new File(dspTimingDataFolder + fullName.replace("/", "-") + ".txt");
		if(dspTimingFile.exists()) {
        	this.valid = true;
		}else {
			this.valid = false;
		}
		this.setInputOutputDelays(new HashMap<>());
		this.inputPorts = new ArrayList<>();
		this.outputPorts = new ArrayList<>();
		
		try{
			if(this.valid) this.parse(dspTimingFile);
		}catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void generateWarningInfo() {
		System.out.println("                  The tool continues in the timing-driven mode. Due to the missing DSP timing info, there could be unexpected critical path delay optimism.");
		System.out.println("INFO: To obtain DSP logic delay files, please refer to dump_all_dsp_delay.tcl under $RAPIDWRIGHT_PATH/tcl/rwroute.");
		System.out.println("INFO: Please use --dspTimingDataFolder <DSP delay files path> to grant the tool access to DSP timing files.");
	}
	
	//Explicit:          PCOUT[47]
	//Implicit:          PCOUT : 0 47
	//Example:
	//	A : 0 29    PCOUT : 0 47   1.527
	//	A : 0 29    P     : 0 47   1.514
	//	B : 0 17    PCOUT : 0 47   1.572
	//	B : 0 17    P     : 0 47   1.559
	//	A[31]    PCOUT[50]   1.527
	/**
	 * Parses the input file
	 * @param file The input DSP timing file
	 * @throws IOException
	 */
	private void parse(File file) throws IOException {
		@SuppressWarnings("resource")
		BufferedReader reader = new BufferedReader(new FileReader(file));
		String line;
		while ((line = reader.readLine()) != null) {
			if (line.length() == 0) {				
				return;
			}
	    	
			if (!line.startsWith("#")){// comment line starts with #   		 
				//explicit implicit
				// delay with clk: clk-out and in-clk 
				if(line.contains("clk")) {
					String[] s = line.split("\\s+");
					if(s.length == 3) {
						// clk    P[10]            1.62
						this.addInputOutputPortDelay("CLK", s[1], (short) (Float.parseFloat(s[2])*1000));
					}else {
						// CEA2     clk       0.00       0.16
						// introducing virtual clk: VCLK, which is connected to superSink in timing graph
						this.addInputOutputPortDelay(s[0], "VCLK", (short) (Float.parseFloat(s[2])*1000 + Float.parseFloat(s[3])*1000));
					}
					
				}else if(line.contains(":")) {
					String[] s = line.replaceAll(":", " ").split("\\s+");
					//port index inclusive
					for(short idin = Short.parseShort(s[1]); idin <= Short.parseShort(s[2]); idin++) {
						//input pins 
						for(short ido = idin; ido <= Short.parseShort(s[5]); ido++) {
							String in = s[0]+"[" + idin + "]";
							String out = s[3]+"[" + ido + "]";
							this.addInputOutputPortDelay(in, out, (short) (Float.parseFloat(s[6])*1000));
						}
					}
					
				}else if(line.contains("[")) {
					String[] s = line.split("\\s+");
					this.addInputOutputPortDelay(s[0], s[1], (short) (Float.parseFloat(s[2])*1000));
				}
			}
		}
		if(this.getPinMapping() != null) {
			this.valid = true;
		}
		reader.close();
	}
	
	public boolean isValid() {
		return valid;
	}

	public void setValid(boolean valid) {
		this.valid = valid;
	}

	/**
	 * Adds the input, output port names and the delay.
	 * @param in The input port name.
	 * @param out The output port name.
	 * @param delay The delay from in to out.
	 */
	private void addInputOutputPortDelay(String in, String out, short delay) {	
		this.inputPorts.add(in);
		this.outputPorts.add(out);
		this.getInputOutputDelays().put(new Pair<>(in, out), delay);
	}
	
	/**
	 * Checks if the EDIFPortInst is included in the input and output lists by name.
	 * @param edifPortInstName The name of EDIFPortInst to be checked.
	 * @return true, if the EDIFPortInst is included in one of the lists.
	 */
	public boolean containsPortInst(String edifPortInstName) {	
		return this.inputPorts.contains(edifPortInstName) || this.outputPorts.contains(edifPortInstName);
	}
	
	/**
	 * Adds pin mapping to the map.
	 * @param subblockPortName The sub-block port name.
	 * @param toplevelPortName The top-level block port name.
	 */
	public void addPinMapping(String subblockPortName, String toplevelPortName) {
		if(this.getPinMapping() == null) {
			this.setPinMapping(new HashMap<>());
		}
		// this.blockName + "/" + key + " -->> " +  this.blockName + "/" + value
		// bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_OUTPUT_INST/P[13] -->> bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/P[13]
		this.getPinMapping().put(this.getBlockName() + "/" + subblockPortName, this.getBlockName() + "/" + toplevelPortName);
	}
	
	@Override
	public int hashCode() {
		return this.getBlockName().hashCode();
	}

	public Map<Pair<String, String>, Short> getInputOutputDelays() {
		return inputOutputDelays;
	}

	public void setInputOutputDelays(Map<Pair<String, String>, Short> inOutPortDelays) {
		this.inputOutputDelays = inOutPortDelays;
	}

	public String getBlockName() {
		return blockName;
	}

	public void setBlockName(String blockName) {
		this.blockName = blockName;
	}

	public Map<String, String> getPinMapping() {
		return pinMapping;
	}

	public void setPinMapping(Map<String, String> pinMapping) {
		this.pinMapping = pinMapping;
	}
	
}
