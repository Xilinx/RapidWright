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

package com.xilinx.rapidwright.ipi;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import com.xilinx.rapidwright.design.blocks.BlockGuide;
import com.xilinx.rapidwright.design.blocks.ImplGuide;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Job;
import com.xilinx.rapidwright.util.LocalJob;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.StringTools;

public class BlockUpdater {

	public static void runVivadoTasks(String dcpName, int implIndex){
		ArrayList<String> tclLines = new ArrayList<>();
		tclLines.add("open_checkpoint " + dcpName);
		
		String rootDCPName = dcpName.replace("_" + implIndex+ "_routed.dcp", ".dcp");
		tclLines.add("generate_metadata "+rootDCPName+" false " + implIndex);
		tclLines.add("report_timing -file route_timing"+implIndex+".twr");
		
		String dirName = new File(dcpName).getParent();
		String tclScriptName = dirName + File.separator + "run.tcl";
		FileTools.writeLinesToTextFile(tclLines, tclScriptName);
		Job j = new LocalJob();
		j.setCommand("vivado -mode batch -source " + tclScriptName);
		j.setRunDir(dirName);
		long id = j.launchJob();
		while(!j.isFinished()){
			System.out.println("Job " + id + " is still running...");
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		System.out.println("Vivado run " + (j.jobWasSuccessful() ? "successful"  : "failed"));
	}
	
	public static void main(String[] args) {
		if(args.length != 3 && args.length != 4){
			MessageGenerator.briefMessageAndExit("USAGE: <path to cache entry> <new DCP> <implementation index> [impl guide file]");
		}
		String cacheEntryPath = null;
		try {
			cacheEntryPath = new File(args[0]).getCanonicalPath();
		} catch (IOException e1) {
			throw new RuntimeException("ERROR: The cache entry " + cacheEntryPath +
					" does not exist.  Cannot update an entry that has not been created yet.");
		}
		String newDCPPath = null;
		try {
			newDCPPath = new File(args[1]).getCanonicalPath();
		} catch (IOException e1) {
			throw new RuntimeException("ERROR: Couldn't access dcp " + newDCPPath);	
		}
		int implementationIdx = Integer.parseInt(args[2]);
		
		if(!FileTools.isVivadoOnPath()){
			throw new RuntimeException("ERROR: Vivado executable could not be found on PATH,"
					+ " please set environment variable accordingly.");
		}
		
		// Find existing routed DCP
		File dir = new File(cacheEntryPath);
		String existingDCP = null;
		String suffix = "_"+implementationIdx +"_routed.dcp";
		String doneFileName = null;
		int count = 0;
		for(String fileName : dir.list()){
			if(fileName.endsWith("_routed.dcp")){
				count++;
			}
			if(fileName.endsWith(suffix)){
				existingDCP = fileName;
			}
			else if(fileName.startsWith(BlockCreator.DONE_FILE_PREFIX)){
				doneFileName = dir + File.separator + fileName;
			}
		}
		if(existingDCP == null){
			throw new RuntimeException("ERROR: Couldn't find existing DCP in " + dir.getAbsolutePath());
		}
		
		String cacheID = StringTools.removeLastSeparator(cacheEntryPath);
		cacheID = cacheID.substring(cacheID.lastIndexOf(File.separator)+1);
		
		BlockGuide bg =  null;
		if(args.length == 4){
			String implGuideFile = args[3];
			ImplGuide ig = ImplGuide.readImplGuide(implGuideFile);
			bg = ig.getBlock(cacheID);
		}
		
		// Move old file to .old
		String fullExistingDCPName = dir.getAbsolutePath() + File.separator + existingDCP;
		try {
			Files.move(Paths.get(fullExistingDCPName), Paths.get(fullExistingDCPName + ".old"),StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new RuntimeException("ERROR: Couldn't move existing file " + fullExistingDCPName);
		}
		FileTools.copyFile(newDCPPath, fullExistingDCPName);
		
		if(doneFileName == null){
			doneFileName = dir + File.separator + BlockCreator.DONE_FILE_PREFIX + count;
		}
		BlockCreator.createDoneFile(doneFileName, bg);
		runVivadoTasks(fullExistingDCPName, implementationIdx);
	}
}
