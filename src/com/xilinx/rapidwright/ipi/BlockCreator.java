/* 
 * Copyright (c) 2017 Xilinx, Inc. 
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
package com.xilinx.rapidwright.ipi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleCache;
import com.xilinx.rapidwright.design.ModuleImpls;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.blocks.BlockGuide;
import com.xilinx.rapidwright.design.blocks.ImplGuide;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.design.blocks.SubPBlock;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.JobQueue;
import com.xilinx.rapidwright.util.LSFJob;
import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.StringTools;
import com.xilinx.rapidwright.util.Utils;


/**
 * Manages pre-implemented block creation.
 * 
 * Created on: Aug 14, 2015
 */
public class BlockCreator {

	public static final String BLOCK_CACHE_PATH = "/home/"+System.getenv("USER")+"/blockCache";

	public static final String ROUTED_XPN_SUFFIX = "_routed.xpn";
	
	public static final String ROUTED_DCP_SUFFIX = "_routed.dcp";
	
	public static final String METADATA_FILE_SUFFIX = "_metadata.txt";
	
	public static final String ROUTED_EDIF_SUFFIX = "_routed.edf";

	public static final String IMPL_RUN_SCRIPT_NAME = "launch_impl_run.tcl";

	public static final String USED_PBLOCK_FILE_SUFFIX = "_routed_pblock.txt";
	
	public static final boolean BC_DEBUG = false;
	
	private static HashMap<String,ModuleImpls> inMemModuleCache = new HashMap<String, ModuleImpls>();
	
	public static final String DONE_FILE_PREFIX = "done.file.";
	
	public static final String DONE_FILE_PATTERN = "IMPLGUIDE";
	
	public static String getUniqueFileName(String xciFileName){
		return xciFileName.replace(".xci", "");
	}
	
	public static ModuleImpls createBlock(String routedDCPFileName, String metadataFileName, EDIFNetlist e, int blockImplCount, String cellInstName){
		ArrayList<String> routedDCPFileNames = getRoutedDCPFileNames(routedDCPFileName, blockImplCount);
		
		ModuleImpls modImpls = new ModuleImpls();
		for(String dcpName : routedDCPFileNames){
			Design d = new Design(e);
			d.updateDesignWithCheckpointPlaceAndRoute(dcpName);
			SiteInst anchorCandidate = null;
			for(SiteInst i : d.getSiteInsts()) {
				if(i.getName().startsWith("STATIC_SOURCE")) continue;
				if(Utils.isModuleSiteType(i.getSite().getSiteTypeEnum())){
					anchorCandidate = i;
					break;				
				}
			}
			if(anchorCandidate == null) {
				// No suitable anchor site instances, let's create a dummy one
				d.createSiteInst(d.getDevice().getSite("SLICE_X0Y0"));
			}
			Module m = new Module(d,dcpName.replace(ROUTED_DCP_SUFFIX, METADATA_FILE_SUFFIX));

			m.setDevice(d.getDevice());
			m.calculateAllValidPlacements(d.getDevice());
			modImpls.add(m);
			
			// Store PBlock with Module Here
			String guidedPblockFile = routedDCPFileName.replace("_routed.dcp", USED_PBLOCK_FILE_SUFFIX);
			List<String> lines = FileTools.getLinesFromTextFile(guidedPblockFile);
			if(lines == null || lines.size() == 0){
				throw new RuntimeException("ERROR: Problem reading pblock from guided block file " + guidedPblockFile);
			}
			String pblockString = lines.get(0).trim(); 
			if(pblockString.length() > 0 && !pblockString.contains("Failed!")) m.setPBlock(pblockString);
		}
		return modImpls;
	}
	
	private static ArrayList<String> getRoutedDCPFileNames(String routedDCPFileName, int blockImplCount){
		ArrayList<String> routedDCPFileNames = new ArrayList<String>();
		if(blockImplCount == 1){
			routedDCPFileNames.add(routedDCPFileName);
		}else{
			File f = new File(routedDCPFileName);
			for(File siblings :  f.getParentFile().listFiles()){
				if(siblings.getName().endsWith(ROUTED_DCP_SUFFIX)){
					routedDCPFileNames.add(siblings.toString());
				}
			}
		}
		StringTools.naturalSort(routedDCPFileNames);
		return routedDCPFileNames;
	}
	
	public static byte[] createChecksum(String filename) {
		InputStream fis;
		MessageDigest complete = null;
		try {
			fis = new FileInputStream(filename);
			byte[] buffer = new byte[1024];
			complete = MessageDigest.getInstance("MD5");
			int numRead;

			do {
				numRead = fis.read(buffer);
				if (numRead > 0) {
					complete.update(buffer, 0, numRead);
				}
			} while (numRead != -1);

			fis.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		}
		return complete.digest();
	}

	public static String getMD5Checksum(String filename) {
		byte[] b = createChecksum(filename);
		StringBuilder result = new StringBuilder(32);
		for (int i=0; i < b.length; i++) {
			result.append(Integer.toString( ( b[i] & 0xff ) + 0x100, 16).substring( 1 ));
		}
		return result.toString();
	}

	/**
	 * Read the stored module from disk.  
	 * @param commonFileName This is the root name of the two files involved that store the module information.
	 * @return The populated module.
	 */
	public static ModuleImpls readStoredModule(String commonFileName, String cellInstanceName){
		EDIFNetlist netlist = FileTools.readObjectFromKryoFile(commonFileName+".kryo", EDIFNetlist.class);
		if(cellInstanceName != null){
			// For cached blocks, the cell instance name gets lost so we restore it here
			netlist.renameNetlistAndTopCell(cellInstanceName);
		}
		
		ModuleImpls modImpls = ModuleCache.readFromCompactFile(commonFileName+".dat",netlist);
		for(Module m : modImpls){
			m.setName(cellInstanceName);
			m.setSrcDatFile(commonFileName+".dat");
		}
		return modImpls;

	}
	
	public static void implementBlocks(HashMap<String,String> ipNames, String cacheDir, ImplGuide implHelper, Device dev){
		JobQueue jobs = new JobQueue();
		Map<Long,String> jobLocations = new HashMap<>();

		boolean useLSF = JobQueue.isLSFAvailable();
		
		for(Entry<String,String> e : ipNames.entrySet()){
			String blockName = e.getKey();
			String cacheID = e.getValue();

			File cachedIPDir = new File(cacheDir + File.separator + cacheID);
			if(cachedIPDir.list() == null || cachedIPDir.list().length == 0){
				throw new RuntimeException("ERROR: Cached entry " + cachedIPDir + " for ip " + blockName +" is empty!");
			}
			
			// Look for done.file.<implCount> to see if implementation has completed
			String doneFileName = null;
			String optDcpFileName = null;
			int implCount = -1;
			for(String fileName : cachedIPDir.list()){
				if(fileName.startsWith(DONE_FILE_PREFIX)){
					doneFileName = fileName;
					implCount = Integer.parseInt(doneFileName.substring(doneFileName.lastIndexOf('.')+1));
				}else if(fileName.endsWith("_opt.dcp")){
					optDcpFileName = cachedIPDir + "/" + fileName;
				}
			}
			if(optDcpFileName == null){
				throw new RuntimeException("ERROR: Expected an _opt.dcp file in " + cachedIPDir.getAbsolutePath());
			}
			
			if(doneFileName != null) {
				if(implHelper != null && implHelper.hasBlock(cacheID)){
					ArrayList<String> lines = FileTools.getLinesFromTextFile(cachedIPDir + "/" + doneFileName);
					if(lines.size() > 0 && lines.get(0).equals(DONE_FILE_PATTERN)){
						BlockGuide blockHelper = implHelper.getBlock(cacheID);
						String md5Hash = blockHelper.getMD5Hash();
						if(lines.get(1).equals(md5Hash)){
							continue;
						}
					}
				}else{
					continue;					
				}
			}
			
			// implement the blocks
			int implIndex = 0;
			if(implHelper != null && implHelper.hasBlock(cacheID)){
				// A helper entry exists for this block, we'll use the guides provided
				BlockGuide blockHelper = implHelper.getBlock(cacheID);
				ArrayList<PBlock> pblocks = blockHelper.getImplementations();
				for(int i=0; i < pblocks.size(); i++){
					PBlock pblock = pblocks.get(i);
					Job job = createImplRun(optDcpFileName, pblock, i, blockHelper, useLSF);
					jobs.addJob(job);
					jobLocations.put(job.getJobNumber(), optDcpFileName + " " + i);
					FileTools.writeStringToTextFile(pblock.toString(), optDcpFileName.replace("opt.dcp", +i+USED_PBLOCK_FILE_SUFFIX));
				}
				
				implIndex = pblocks.size();
			}else{
				// Create a run for each implementation of each module in pblock file
				String pblockFileName = optDcpFileName.replace("_opt.dcp", "_pblock.txt");
				if(new File(pblockFileName).exists()){
					for(String pblock : FileTools.getLinesFromTextFile(pblockFileName)){
						if(pblock.startsWith("#")) continue;
						PBlock pblock2 = (pblock.trim().equals("") || pblock.contains("Failed!")) ? null : new PBlock(dev,pblock);
						FileTools.writeStringToTextFile(pblock, optDcpFileName.replace("opt.dcp", +implIndex+USED_PBLOCK_FILE_SUFFIX));
						Job job = createImplRun(optDcpFileName, pblock2, implIndex, null, useLSF);
						jobs.addJob(job);
						jobLocations.put(job.getJobNumber(), optDcpFileName + " " + implIndex);
						implIndex++;
					}					
				}
				else if(new File(optDcpFileName.replace("_opt.dcp", "_utilization.report")).exists()){
					Job job = createImplRun(optDcpFileName, null, implIndex, null, useLSF);
					jobs.addJob(job);
					jobLocations.put(job.getJobNumber(), optDcpFileName + " " + implIndex);
					implIndex++;					
				}
			}
			BlockGuide bg = implHelper == null ? null : implHelper.getBlock(cacheID);
			createDoneFile(cachedIPDir+"/"+DONE_FILE_PREFIX+implIndex, bg);
			//FileTools.writeLinesToTextFile(doneFileContents, cachedIPDir+"/"+DONE_FILE_PREFIX+implIndex);
			//FileTools.getCommandOutput(new String[]{"touch", cachedIPDir+"/"+DONE_FILE_SUFFIX+implIndex});
		}
		
		// Returns when all jobs are finished
		boolean success = jobs.runAllToCompletion(useLSF ? JobQueue.MAX_LSF_CONCURRENT_JOBS : JobQueue.MAX_LOCAL_CONCURRENT_JOBS);
		if(!success)System.out.println("Job failures detected...");
		// When all jobs are done, check outputs to see if any failed
		if(jobLocations.size() > 0) MessageGenerator.printHeader("OOC Implementation Runs Summary");
		boolean halt = false;
		for(Entry<Long, String> e : jobLocations.entrySet()){
			int lastSlash = e.getValue().lastIndexOf('/');
			int lastSpace = e.getValue().lastIndexOf(' ');
			String dir = e.getValue().substring(0, lastSlash);
			String optFileName = e.getValue().substring(lastSlash+1, lastSpace);
			int implIndex = Integer.parseInt(e.getValue().substring(lastSpace+1));
			String routedFileName = dir + "/" + optFileName.replace("_opt.dcp","_"+implIndex+"_routed.dcp");
			boolean routedFileExists = new File(routedFileName).exists();
			boolean pass = false;
			boolean noDCP = true;
			String doneFile = null;
			if(routedFileExists){
				for(String file : new File(dir).list()){
					if(file.startsWith(DONE_FILE_PREFIX)){
						doneFile = file;
						break;
					}
				}
				if(doneFile != null && FileTools.isFileNewer(routedFileName, dir + "/" + doneFile)){
					pass = true;
					noDCP = false;
				}
			}
			String wns = null;
			String timingFile = dir + "/route_timing" + implIndex + ".twr"; 
			if(!noDCP && new File(timingFile).exists() && FileTools.isFileNewer(timingFile, dir + "/" + doneFile)){
				for(String line : FileTools.getLinesFromTextFile(timingFile)){
					if(line.contains("Slack")){
						String[] tokens = line.split("\\s+");
						if(tokens.length > 2) wns = tokens[3];
						break;
					}
				}
			}
			boolean isNegSlack = wns == null ? true : Float.parseFloat(wns.replace("ns", "")) < 0;
			
			if(wns == null || isNegSlack) pass = false;
			String timing = noDCP ? "NO DCP" : (wns == null ? "inf" : wns.toString());
			System.out.println( (pass ? "PASS" : (wns==null ? "N/A" : "FAIL")) + " | " + timing + " | " + routedFileName );
			if(noDCP){
				String logFile = dir + "/" + implIndex +"/vivado.log";
				System.out.println("  VIVADO LOG: " + logFile);
				for(String line : FileTools.getLinesFromTextFile(logFile)){
					if(line.contains("ERROR"))
						System.out.println("  *** " + line + " ***");
				}
				
				// Stop the process if a DCP is missing, but keep going 
				// even if we miss timing
				halt = true;
				
				// Delete the done file
				if(doneFile != null && new File( dir + "/" + doneFile).exists())
					FileTools.deleteFile( dir + "/" + doneFile);
			}
		}
		
		if(halt){
			MessageGenerator.briefErrorAndExit("ERROR: Failure to generate all necessary OOC DCPs.  "
				+ "Please see error messages and logs above to resolve issues in order to continue.");
		}
	}
	
	public static void createDoneFile(String fileName, BlockGuide bg){
		ArrayList<String> doneFileContents = new ArrayList<>();
		if(bg != null){
			doneFileContents.add(DONE_FILE_PATTERN);
			doneFileContents.add(bg.getMD5Hash());
		}
		FileTools.writeLinesToTextFile(doneFileContents, fileName);
	}
	
	private static void createTclScript(String scriptName, String optDcpFileName, PBlock pblock, int implIndex, BlockGuide blockGuide){
		PrintWriter pw = null;
		try {
			pw = new PrintWriter(scriptName);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException("ERROR: Couldn't create Tcl script " + scriptName);
		}
		if(FileTools.isWindows()){
			optDcpFileName = optDcpFileName.replace("\\", "/");
		}
		
		pw.println("if { [info procs rapid_compile_ipi] == \"\" } {");
		pw.println("	if {[info exists env(RAPIDWRIGHT_PATH)]} {");
		pw.println("		set rw_path $::env(RAPIDWRIGHT_PATH)");
		pw.println("		source ${rw_path}/tcl/rapidwright.tcl");
		pw.println("	} else {");
		pw.println("		error \"Please set the environment variable RAPIDWRIGHT_PATH to point to your RapidWright installation.\"");
		pw.println("	}");
		pw.println("}");
		pw.println("puts \"RAPIDWRIGHT_PATH=$::env(RAPIDWRIGHT_PATH)\"");
		
		
		pw.println("open_checkpoint " + optDcpFileName);
		if(blockGuide != null){
			// Add Clock Constraints
			for(String clkPortName : blockGuide.getClocks()){
				pw.println("create_clock -name " + clkPortName + " -period " + Float.toString(blockGuide.getClockPeriod(clkPortName)) + " [get_ports " + clkPortName + "]");
			}
			for(String clkPortName : blockGuide.getClocksWithBuffers()){
				pw.println("set_property HD.CLK_SRC " + blockGuide.getClockBuffer(clkPortName) + " [get_ports " + clkPortName + "]");
			}
			if(blockGuide.getClocks().size() > 1){
				pw.print("set_clock_groups -asynchronous");
				for(String clkPortName : blockGuide.getClocks()){
					pw.print(" -group [get_clocks " + clkPortName +"]");
				}
				pw.println();
			}				
		}
		
		pw.println("set optDcpFileName " + optDcpFileName);
		pw.println("set pblock \"" + (pblock == null ? "" : pblock.toString()) + "\"");
		pw.println("set implIndex " + implIndex);
		pw.println("set designCells [get_cells -filter {NAME!=VCC && NAME!=GND}]");
		pw.println("if { $designCells != {} } {");
		pw.println("    if {[string trim $pblock] != {} }  {"); 
		pw.println("        create_pblock pblock_1");
		pw.println("        add_cells_to_pblock pblock_1 -instances $designCells");
		//if(!optDcpFileName.contains("_slr"))
		pw.println("        set_property CONTAIN_ROUTING true [get_pblocks pblock_1]");
		pw.println("        resize_pblock pblock_1 -add $pblock");
		int i = 2;
		if(pblock != null){
			for(SubPBlock subPBlock : pblock.getSubPBlocks()){
				String pblockName = "pblock_" + i; 
				i++;
				pw.println("        create_pblock " + pblockName);
				String[] parts = subPBlock.getGetCellsArgs().split(",");
				for(String p : parts){
					pw.println("        add_cells_to_pblock " + pblockName + " -instances [get_cells "+ p + "]");
				}
				pw.println("        resize_pblock " + pblockName + " -add {"+ subPBlock.toString() + "}");
				pw.println("        set_property PARENT pblock_1 [get_pblocks "+pblockName+"]");
			}
		}
		pw.println("    }");
		
		if(blockGuide != null){
			for(String xdc : blockGuide.getXDCCommands()){
				pw.println("    " + xdc);
			}				
		}
		
		pw.println("    place_design");
		pw.println("    route_design -directive Explore");
		pw.println("}");
		pw.println("puts \"PBLOCK: $implIndex Placed and Routed!\"");
		pw.println("set routedDcpFile [string map \"_opt.dcp _${implIndex}_routed.dcp\" $optDcpFileName]");
		pw.println("set dcpFile [string map \"_opt.dcp .dcp\" $optDcpFileName]");
		pw.println("update_routed_dcp $dcpFile $implIndex");		
		//pw.println("create_impl_block " + optDcpFileName +" \"" + pblock + "\" " + implIndex);
		pw.close();
	}
	
	/**
	 * Launches an independent run of an area constrained place and route job
	 * @param optDcpFileName The name of the input DCP to start with
	 * @param pblock The pblock to use to constrain the block
	 * @param implIndex The implementation index for this block
	 * @return The job object representing the process (either local or on LSF)
	 */
	public static Job createImplRun(String optDcpFileName, PBlock pblock, int implIndex, BlockGuide blockGuide, boolean useLSF){
		String currDir = optDcpFileName.substring(0, optDcpFileName.lastIndexOf('/')+1) + implIndex;
		String scriptName = currDir + File.separator + IMPL_RUN_SCRIPT_NAME;
		
		Job j = useLSF ? new LSFJob() : new LocalJob();
		j.setRunDir(currDir);
		j.setCommand(FileTools.getVivadoPath() + " -mode batch -source " + scriptName);
		FileTools.makeDirs(currDir);
		createTclScript(scriptName, optDcpFileName, pblock, implIndex, blockGuide);
		return j;
	}
	
	/**
	 * Relies on the cache to load or create from the prepared design the appropriate module based on the input files. 
	 * @param edifFileName The EDIF file name (logical netlist)
	 * @param routedDCPFileName The routed DCP file name 
	 * @param cellInstanceName The name of the cell instance (also the IP name in IPI)
	 * @param xciFileName The XCI file name (attributes to the IP block that indicate its uniqueness)
	 * @param blockImplCount The number of implementations provided for this module 
	 * @return The module corresponding 
	 */
	public static ModuleImpls createOrRetrieveBlock(String edifFileName, String routedDCPFileName, String cellInstanceName, String xciFileName, int blockImplCount){
		String uniqueFileName = getUniqueFileName(xciFileName);
		String cacheID = xciFileName.replace(".xci", "");
		cacheID = cacheID.substring(cacheID.lastIndexOf('/')+1, cacheID.length());
		ModuleImpls cachedModules = inMemModuleCache.get(cacheID);
		if(cachedModules != null) {
			//System.out.println("Cache hit on " + cellInstanceName + " " + cacheID);
			return cachedModules;
		}
		//System.out.println("Cache miss on " + cellInstanceName + " " + cacheID);
		String datFileName = uniqueFileName+".dat";
		boolean storedModuleValid = new File(datFileName).exists() && new File(uniqueFileName+".kryo").exists(); 
		if(storedModuleValid){
			// Check that all *routed.dcp files are older than .dat
			for(String routedDCP : getRoutedDCPFileNames(routedDCPFileName, blockImplCount)){
				if(FileTools.isFileNewer(routedDCP, datFileName)){
					storedModuleValid = false;
					break;
				}
			}
		}
		
		if(storedModuleValid){
			// Module has already been built, load existing file
			//System.out.println("Reading Stored dat/kryo for " + uniqueFileName);
			ModuleImpls modules = readStoredModule(uniqueFileName, cellInstanceName);
			inMemModuleCache.put(cacheID, modules);
			return modules;
		}
		//System.out.println("Generating dat/kryo for " + uniqueFileName);
		EDIFNetlist e = EDIFTools.readEdifFile(edifFileName);
		String metadataFileName = routedDCPFileName == null ? null : routedDCPFileName.replace(ROUTED_DCP_SUFFIX, METADATA_FILE_SUFFIX);
		ModuleImpls modules = createBlock(routedDCPFileName, metadataFileName, e, blockImplCount, cellInstanceName);
		for(Module m : modules){
			m.setSrcDatFile(uniqueFileName+".dat");
			m.setNetlist(e);
			e.setDevice(m.getDevice());
		}

		ModuleCache.saveToCompactFile(modules,uniqueFileName+".dat");
		FileTools.writeObjectToKryoFile(uniqueFileName+".kryo", e);
		inMemModuleCache.put(cacheID, modules);
		return modules;
	}
	
	public static void main(String[] args) {
		if(args.length != 5){
			MessageGenerator.briefMessageAndExit("USAGE: <EDIF file name> <routed DCP file name> <XCI file name|path to store block> <part name> <blockImplCount>");
		}
		String edifFileName = args[0];
		String routedDCPFileName = args[1];
		int blockImplCount = Integer.parseInt(args[4]);
		System.out.println("BlockCreator: " + edifFileName + " " + routedDCPFileName + " " + args[2] + " " + args[3] + " " + args[4]);
		if(args[2].toLowerCase().endsWith(".xci")){
			// Use the caching mechanism
			System.out.println(getMD5Checksum(args[2]));
			createOrRetrieveBlock(edifFileName, routedDCPFileName, args[2], args[3], blockImplCount);
		}else{
			// we just want to build a block outside of the cache
			EDIFNetlist e = EDIFTools.readEdifFile(edifFileName);
			//Module m = createBlock(xpnFileName, e);
			String metadataFileName = routedDCPFileName.replace(ROUTED_XPN_SUFFIX, METADATA_FILE_SUFFIX);
			ModuleImpls moduleImpls = createBlock(routedDCPFileName, metadataFileName, e, blockImplCount, null);
			String blockDirName = args[2];
			FileTools.makeDirs(blockDirName);
			String fileName = edifFileName.substring(edifFileName.lastIndexOf('/')+1).replace(".edf", "");
			ModuleCache.saveToCompactFile(moduleImpls, blockDirName + "/" + fileName + ".dat");
			e = EDIFTools.readEdifFile(edifFileName.replace(ROUTED_EDIF_SUFFIX, ".edf"));
			FileTools.writeObjectToKryoFile(blockDirName + "/" + fileName + ".kryo", e);
		}
	}
}
