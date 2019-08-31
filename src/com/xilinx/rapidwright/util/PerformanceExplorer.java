/*
 * 
 * Copyright (c) 2018 Xilinx, Inc. 
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
/**
 * 
 */
package com.xilinx.rapidwright.util;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.blocks.PBlock;

import joptsimple.OptionParser;
import joptsimple.OptionSet;

/**
 * This class is designed to run multiple instances of Vivado with the goal
 * of achieving a better result than what is produced on average.
 * Created on: Mar 20, 2018
 */
public class PerformanceExplorer {

	private static final String INITIAL_DCP_NAME = "initial.dcp";
	private static final String PLACED_TIMING_RESULT = "place_timing.twr";
	private static final String ROUTED_TIMING_RESULT = "route_timing.twr";
	private static final String RUN_TCL_NAME = "run.tcl";
	private static final double DEFAULT_MIN_CLK_UNCERT = -0.100;
	private static final double DEFAULT_MAX_CLK_UNCERT = 0.250;
	private static final double DEFAULT_STEP_CLK_UNCERT = 0.025;
	private static final List<PlacerDirective> DEFAULT_PLACE_DIRECTIVES = Arrays.asList(PlacerDirective.Default, PlacerDirective.Explore);
	private static final List<RouterDirective> DEFAULT_ROUTE_DIRECTIVES = Arrays.asList(RouterDirective.Default, RouterDirective.Explore);
	private static final String DEFAULT_VIVADO = "vivado";
	private static final boolean DEFAULT_CONTAIN_ROUTING = true;
	private static final boolean DEFAULT_ADD_EDIF_METADATA = true;

	private static final DecimalFormat df = new DecimalFormat("#.###");
	
	private Design design; 
	
	private String runDirectory;
	
	String clkName;
	
	double targetPeriod;
	
	private ArrayList<PBlock> pblocks;
	
	private boolean containRouting;
	
	private boolean addEDIFAndMetadata;
	
	private ArrayList<PlacerDirective> placerDirectives;
	
	private ArrayList<RouterDirective> routerDirectives;
	
	private ArrayList<Double> clockUncertaintyValues;

	private double minClockUncertainty = DEFAULT_MIN_CLK_UNCERT;
	
	private double maxClockUncertainty = DEFAULT_MAX_CLK_UNCERT;
	
	private double clockUncertaintyStep = DEFAULT_STEP_CLK_UNCERT;

	private String vivadoPath = DEFAULT_VIVADO;
	
	public PerformanceExplorer(Design d, String testDir, String clkName, double targetPeriod){
		init(d, testDir, clkName, targetPeriod, null);
	}
	
	public PerformanceExplorer(Design d, String testDir, String clkName, double targetPeriod, ArrayList<PBlock> pblocks){
		init(d, testDir, clkName, targetPeriod, pblocks);

	}
	
	private void init(Design d, String testDir, String clkName, double targetPeriod, ArrayList<PBlock> pblocks){
		this.design = d;
		this.runDirectory = testDir;
		this.clkName = clkName;
		this.targetPeriod = targetPeriod;
		this.pblocks = pblocks;
		this.placerDirectives = new ArrayList<>();
		placerDirectives.add(PlacerDirective.Default);
		placerDirectives.add(PlacerDirective.Explore);
		this.routerDirectives = new ArrayList<>();
		routerDirectives.add(RouterDirective.Default);
		routerDirectives.add(RouterDirective.Explore);
		updateClockUncertaintyValues();
	}
	
	public void updateClockUncertaintyValues(){
		this.clockUncertaintyValues = new ArrayList<>();
		for(double i=minClockUncertainty; i < maxClockUncertainty; i+=clockUncertaintyStep){
			clockUncertaintyValues.add(i);
		}
	}
	
	public Design getDesign() {
		return design;
	}

	public void setDesign(Design design) {
		this.design = design;
	}

	public String getRunDirectory() {
		return runDirectory;
	}

	public void setRunDirectory(String runDirectory) {
		this.runDirectory = runDirectory;
	}

	public String getClkName() {
		return clkName;
	}

	public void setClkName(String clkName) {
		this.clkName = clkName;
	}

	public double getTargetPeriod() {
		return targetPeriod;
	}

	public void setTargetPeriod(double targetPeriod) {
		this.targetPeriod = targetPeriod;
	}
	

	public ArrayList<PBlock> getPBlocks() {
		return pblocks;
	}

	public void setPBlocks(ArrayList<PBlock> pblocks) {
		this.pblocks = pblocks;
	}

	public ArrayList<PlacerDirective> getPlacerDirectives() {
		return placerDirectives;
	}

	public void setPlacerDirectives(ArrayList<PlacerDirective> placerDirectives) {
		this.placerDirectives = placerDirectives;
	}
	
	public void setPlacerDirectives(String[] directives) {
		this.placerDirectives = new ArrayList<>();
		for(String directive : directives) {
			directive = directive.trim();
			placerDirectives.add(PlacerDirective.valueOf(directive));
		}
	}

	public ArrayList<RouterDirective> getRouterDirectives() {
		return routerDirectives;
	}

	public void setRouterDirectives(ArrayList<RouterDirective> routerDirectives) {
		this.routerDirectives = routerDirectives;
	}

	public void setRouterDirectives(String[] directives) {
		this.routerDirectives = new ArrayList<>();
		for(String directive : directives) {
			directive = directive.trim();
			routerDirectives.add(RouterDirective.valueOf(directive));
		}
	}
	
	public ArrayList<Double> getClockUncertaintyValues() {
		return clockUncertaintyValues;
	}

	public void setClockUncertaintyValues(ArrayList<Double> clockUncertaintyValues) {
		this.clockUncertaintyValues = clockUncertaintyValues;
	}
	
	public void setClockUncertaintyValues(String[] values){
		this.clockUncertaintyValues = new ArrayList<Double>();
		for(String val : values){
			clockUncertaintyValues.add(Double.parseDouble(val));
		}
	}

	public double getMinClockUncertainty() {
		return minClockUncertainty;
	}

	public void setMinClockUncertainty(double minClockUncertainty) {
		this.minClockUncertainty = minClockUncertainty;
	}

	public double getMaxClockUncertainty() {
		return maxClockUncertainty;
	}

	public void setMaxClockUncertainty(double maxClockUncertainty) {
		this.maxClockUncertainty = maxClockUncertainty;
	}

	public double getClockUncertaintyStep() {
		return clockUncertaintyStep;
	}

	public void setClockUncertaintyStep(double clockUncertaintyStep) {
		this.clockUncertaintyStep = clockUncertaintyStep;
	}

	public String getVivadoPath() {
		return vivadoPath;
	}

	public void setVivadoPath(String vivadoPath) {
		this.vivadoPath = vivadoPath;
	}

	public boolean isContainRouting() {
		return containRouting;
	}

	public void setContainRouting(boolean containRouting) {
		this.containRouting = containRouting;
	}

	public boolean addEDIFAndMetadata() {
		return addEDIFAndMetadata;
	}

	public void setAddEDIFAndMetadata(boolean addEDIFAndMetadata) {
		this.addEDIFAndMetadata = addEDIFAndMetadata;
	}

	public ArrayList<String> createTclScript(String initialDcp, String instDirectory, 
			PlacerDirective p, RouterDirective r, String clockUncertainty, PBlock pblock){
		ArrayList<String> lines = new ArrayList<>();
		lines.add("open_checkpoint " + initialDcp);
		lines.add("set_clock_uncertainty -setup "+clockUncertainty+" [get_clocks "+clkName+"]");
		if(pblock != null){
			String pblockName = pblock.getName() == null ? "pe_pblock_1" : pblock.getName();
			lines.add("create_pblock " + pblockName);
			lines.add("resize_pblock "+pblockName+" -add {"+pblock.toString()+"}");
			lines.add("add_cells_to_pblock "+pblockName+" -top");
			if(containRouting){
				lines.add("set_property CONTAIN_ROUTING 1 [get_pblocks "+ pblockName+"]");
			}
		}
		lines.add("place_design -unplace");	
		lines.add("place_design -directive " + p.name());		
		lines.add("set_clock_uncertainty -setup 0.0 [get_clocks "+clkName+"]");
		lines.add("report_timing -file "+instDirectory + File.separator+PLACED_TIMING_RESULT);
		lines.add("route_design -directive " + r.name());
		lines.add("report_timing -file "+instDirectory + File.separator+ROUTED_TIMING_RESULT);
		lines.add("write_checkpoint -force " + instDirectory + File.separator + "routed.dcp");
		if(addEDIFAndMetadata){
			lines.add("write_edif -force " + instDirectory + File.separator + "routed.edf");
			lines.add("source " + FileTools.getRapidWrightPath() + File.separator + "tcl" + File.separator + "rapidwright.tcl");
			lines.add("generate_metadata "+ instDirectory + File.separator + "routed.dcp false 0");
		}
		for (int i = 0 ; i < lines.size(); i++){
			lines.set(i, lines.get(i).replace('\\', '/'));
		}
		return lines;
	}
	
	
	public void explorePerformance(){
		
		if(vivadoPath.equals(DEFAULT_VIVADO) && !FileTools.isVivadoOnPath()){
			throw new RuntimeException("ERROR: Couldn't find \n"
				+ "    vivado on PATH, please update PATH or specify path with option -" + VIVADO_PATH_OPT);
		}
		
		FileTools.makeDirs(runDirectory);
		runDirectory = new File(runDirectory).getAbsolutePath();		
		String dcpName = runDirectory + File.separator + INITIAL_DCP_NAME;
		// Update clock period constraint
		for(ConstraintGroup g : ConstraintGroup.values()){
			List<String> xdcList = design.getXDCConstraints(g);
			for(int i=0; i < xdcList.size(); i++){
				String xdc = xdcList.get(i);
				if(xdc.contains("create_clock") && xdc.contains("-name " + clkName)){
					// TODO - For now, user will need to update DCP beforehand
				}
			}		
		}
		
		design.writeCheckpoint(dcpName); 
		JobQueue jobs = new JobQueue();
		boolean useLSF = JobQueue.isLSFAvailable();
		
		if(pblocks == null){
			pblocks = new ArrayList<>();
			pblocks.add(null);
		}
		
		for(int pb=0; pb < pblocks.size(); pb++){
			PBlock pblock = pblocks.get(pb);
			for(PlacerDirective p : getPlacerDirectives()){
				for(RouterDirective r : getRouterDirectives()){
					for(double c : getClockUncertaintyValues()){
						String roundedC = printNS(c);
						String uniqueID = p.name() + "_" + r.name() + "_" + roundedC;
						if(pblock != null){
							uniqueID = uniqueID + "_pblock" + pb;
						}
						System.out.println(uniqueID);
						String instDir = runDirectory + File.separator + uniqueID;
						FileTools.makeDir(instDir);
						ArrayList<String> tcl = createTclScript(dcpName, instDir, p, r, roundedC, pblock);
						String scriptName = instDir + File.separator + RUN_TCL_NAME;
						FileTools.writeLinesToTextFile(tcl, scriptName);
						
						Job j = useLSF ? new LSFJob() : new LocalJob();
						j.setRunDir(instDir);
						j.setCommand(getVivadoPath() + " -mode batch -source " + scriptName);
						@SuppressWarnings("unused")
						long id = j.launchJob();
						jobs.addRunningJob(j);
					}
				}
			}			
		}
		
		boolean success = jobs.runAllToCompletion(JobQueue.MAX_LSF_CONCURRENT_JOBS);

		System.out.println("Performance Explorer " + (success ? "Finished Successfully." : "Failed!"));
	}
	
	
	private static final String INPUT_DCP_OPT = "i";
	private static final String PBLOCK_FILE_OPT = "b";
	private static final String CLK_NAME_OPT = "c";
	private static final String CONTAIN_ROUTING_OPT = "q";
	private static final String TARGET_PERIOD_OPT = "t";
	private static final String PLACER_DIRECTIVES_OPT = "p";
	private static final String ROUTER_DIRECTIVES_OPT = "r";
	private static final String CLK_UNCERTAINTY_OPT = "u";
	private static final String MIN_CLK_UNCERTAINTY_OPT = "m";
	private static final String MAX_CLK_UNCERTAINTY_OPT = "x";
	private static final String CLK_UNCERTAINTY_STEP_OPT = "s";
	private static final String ADD_EDIF_METADATA_OPT = "a";
	private static final String HELP_OPT = "h";
	private static final String RUN_DIR_OPT = "d";
	private static final String VIVADO_PATH_OPT = "y";
	private static final String MAX_CONCURRENT_JOBS_OPT = "z";
	
	private static OptionParser createOptionParser(){
		// Defaults		
		String placerDirectiveDefaults = DEFAULT_PLACE_DIRECTIVES.toString().replace("[", "").replace("]", "");
		String routerDirectiveDefaults = DEFAULT_ROUTE_DIRECTIVES.toString().replace("[", "").replace("]", "");
		OptionParser p = new OptionParser() {{
			accepts(INPUT_DCP_OPT).withRequiredArg().required().describedAs("Input DCP");
			accepts(CLK_NAME_OPT).withRequiredArg().required().describedAs("Name of clock to optimize");
			accepts(TARGET_PERIOD_OPT).withRequiredArg().required().describedAs("Target clock period (ns)");
			accepts(PLACER_DIRECTIVES_OPT).withOptionalArg().defaultsTo(placerDirectiveDefaults).describedAs("Comma separated list of place_design -directives");
			accepts(ROUTER_DIRECTIVES_OPT).withOptionalArg().defaultsTo(routerDirectiveDefaults).describedAs("Comma separated list of route_design -directives");
			accepts(CLK_UNCERTAINTY_OPT).withOptionalArg().describedAs("Comma separated list of clk uncertainty values (ns)");
			accepts(PBLOCK_FILE_OPT).withRequiredArg().describedAs("PBlock file, one set of ranges per line");
			accepts(MIN_CLK_UNCERTAINTY_OPT).withOptionalArg().defaultsTo(printNS(DEFAULT_MIN_CLK_UNCERT)).describedAs("Min clk uncertainty (ns)");
			accepts(MAX_CLK_UNCERTAINTY_OPT).withOptionalArg().defaultsTo(printNS(DEFAULT_MAX_CLK_UNCERT)).describedAs("Max clk uncertainty (ns)");
			accepts(CLK_UNCERTAINTY_STEP_OPT).withOptionalArg().defaultsTo(printNS(DEFAULT_STEP_CLK_UNCERT)).describedAs("Clk uncertainty step (ns)");
			accepts(ADD_EDIF_METADATA_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(DEFAULT_ADD_EDIF_METADATA).describedAs("Create EDIF and Metadata");
			accepts(RUN_DIR_OPT).withOptionalArg().defaultsTo("<current directory>").describedAs("Run directory (jobs data location)");
			accepts(VIVADO_PATH_OPT).withOptionalArg().defaultsTo(DEFAULT_VIVADO).describedAs("Specifies vivado path");
			accepts(CONTAIN_ROUTING_OPT).withOptionalArg().ofType(Boolean.class).defaultsTo(DEFAULT_CONTAIN_ROUTING).describedAs("Sets attribute on pblock to contain routing");
			accepts(MAX_CONCURRENT_JOBS_OPT).withOptionalArg().ofType(Integer.class).defaultsTo(JobQueue.MAX_LOCAL_CONCURRENT_JOBS).describedAs("Max number of concurrent job when run locally");
			acceptsAll( Arrays.asList(HELP_OPT, "?"), "Print Help" ).forHelp();			
		}};
		
		return p;
	}
	
	private static void printHelp(OptionParser p){
		MessageGenerator.printHeader("DCP Performance Explorer");
		System.out.println("This RapidWright program will place and route the same DCP in a variety of \n"
						 + "ways with the goal of achieving higher performance in timing closure. This \n"
						 + "tool will launch parallel jobs with the cross product of:\n"
						 + "   < placer directives x router directives x clk uncertainty settings > \n");
		try {
			p.printHelpOn(System.out);
			
		} catch (IOException e) {
			e.printStackTrace();
		}
		return;
	}

	
	public static String printNS(double num){
		return df.format(num);
	}
	
	public static void main(String[] args) {
		OptionParser p = createOptionParser();
		OptionSet opts = p.parse(args);
		
		if(opts.has(HELP_OPT)){
			printHelp(p);
			return;
		}
		
		String dcpInputName = (String) opts.valueOf(INPUT_DCP_OPT);
		String clkName = (String) opts.valueOf(CLK_NAME_OPT);
		double targetPeriod = Double.parseDouble((String)  opts.valueOf(TARGET_PERIOD_OPT));
		String runDir = opts.hasArgument(RUN_DIR_OPT) ? (String) opts.valueOf(RUN_DIR_OPT) : System.getProperty("user.dir");
		
		Design d = Design.readCheckpoint(dcpInputName);
		PerformanceExplorer pe = new PerformanceExplorer(d, runDir, clkName, targetPeriod);

		if(opts.hasArgument(MAX_CONCURRENT_JOBS_OPT)){
			JobQueue.MAX_LOCAL_CONCURRENT_JOBS = (int) opts.valueOf(MAX_CONCURRENT_JOBS_OPT);
		}
		
		if(opts.hasArgument(CLK_UNCERTAINTY_OPT)){
			String clkUncertaintyValues = (String) opts.valueOf(CLK_UNCERTAINTY_OPT);
			pe.setClockUncertaintyValues(clkUncertaintyValues.split("[,]"));			
		} else{
			pe.setMinClockUncertainty(Double.parseDouble((String) opts.valueOf(MIN_CLK_UNCERTAINTY_OPT)));
			pe.setMaxClockUncertainty(Double.parseDouble((String) opts.valueOf(MAX_CLK_UNCERTAINTY_OPT)));
			pe.setClockUncertaintyStep(Double.parseDouble((String) opts.valueOf(CLK_UNCERTAINTY_STEP_OPT)));
			pe.updateClockUncertaintyValues();
		}
		String placerDirValues = (String) opts.valueOf(PLACER_DIRECTIVES_OPT);
		pe.setPlacerDirectives(placerDirValues.split(","));
		String routerDirValues = (String) opts.valueOf(ROUTER_DIRECTIVES_OPT);
		pe.setRouterDirectives(routerDirValues.split(","));
		pe.setVivadoPath((String)opts.valueOf(VIVADO_PATH_OPT));
		pe.setContainRouting((boolean)opts.valueOf(CONTAIN_ROUTING_OPT));
		pe.setAddEDIFAndMetadata((boolean)opts.valueOf(ADD_EDIF_METADATA_OPT));

		
		if(opts.hasArgument(PBLOCK_FILE_OPT)){
			String fileName = (String) opts.valueOf(PBLOCK_FILE_OPT);
			ArrayList<PBlock> pblockList = new ArrayList<>();
			for(String line : FileTools.getLinesFromTextFile(fileName)){
				if(line.trim().startsWith("#")) continue;
				if(line.trim().length()==0) continue;
				PBlock pblock = new PBlock(d.getDevice(), line.trim());
				pblockList.add(pblock);
			}
			pe.setPBlocks(pblockList);
		}
		
		pe.explorePerformance();
	}
}
