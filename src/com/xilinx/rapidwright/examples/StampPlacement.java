package com.xilinx.rapidwright.examples;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.StringTools;

public class StampPlacement {

	public static Module[] loadStampDCPs(String stampDCPFilePrefix){
		String stampFileDir = System.getProperty("user.dir");
		String filePrefix = stampDCPFilePrefix;
		if(stampDCPFilePrefix.contains(File.separator)){
			int sep = stampDCPFilePrefix.lastIndexOf(File.separator);
			stampFileDir = stampDCPFilePrefix.substring(0, sep);
			filePrefix = stampDCPFilePrefix.substring(sep+1);
		}
		ArrayList<String> dcpFileNames = new ArrayList<>();
		for(File f : new File(stampFileDir).listFiles(FileTools.getDCPFilenameFilter())){
			if(f.getName().startsWith(filePrefix)){
				dcpFileNames.add(f.getName());
			}
		}
		
		int i=0;
		Module[] stamps = new Module[dcpFileNames.size()];
		for(String fileName : StringTools.naturalSort(dcpFileNames)){
			String fullDCPFileName = stampFileDir + File.separator + fileName;
			String metadataFileName = stampFileDir + File.separator + fileName.replace(".dcp", "_metadata.txt");
			stamps[i] = new Module(Design.readCheckpoint(fullDCPFileName, CodePerfTracker.SILENT), metadataFileName);
			i++;
		}
		return stamps;
	}
	
	public static Map<Integer,HashMap<String,Site>> loadPlacementDirectives(Device device, Map<Integer,Module> stamps, String placementDirectionFile){
		Map<Integer, HashMap<String,Site>> placementDirectives = new HashMap<Integer,HashMap<String,Site>>();
		// Parse the stamp direction file
		int lineNum = 0;
		for(String line : FileTools.getLinesFromTextFile(placementDirectionFile)){
			lineNum++;
			if(line.trim().startsWith("#")) continue;
			String[] tokens = line.split("\\s+");
			if(line.trim().startsWith("STAMP")){
				int idx = Integer.parseInt(tokens[1]);
				Site anchor = device.getSite(tokens[2]);
				if(anchor == null){
					throw new RuntimeException("ERROR: invalid anchor site " + anchor + " on line " + lineNum);
				}
				String fullDCPFileName = tokens[3];
				String metadataFileName = tokens[4];
				stamps.put(idx, new Module(Design.readCheckpoint(fullDCPFileName, CodePerfTracker.SILENT), metadataFileName));
				stamps.get(idx).setAnchor(stamps.get(idx).getSiteInstAtSite(anchor));
				if(stamps.get(idx).getAnchor() == null){
					throw new RuntimeException("ERROR: No site used in " + fullDCPFileName + " at proposed anchor " + anchor + " on line " + lineNum);
				}
				placementDirectives.put(idx, new HashMap<>());
				continue;
			}
			int stampIdx = Integer.parseInt(tokens[0]);
			Site placement = device.getSite(tokens[1]);
			if(placement == null){
				throw new RuntimeException("ERROR: invalid anchor site " + placement + " on line " + lineNum);
			}
			String instName = tokens[2];
			placementDirectives.get(stampIdx).put(instName, placement);
		}		
		
		return placementDirectives;
	}
	
	public static void main(String[] args) {
		if(args.length != 3){
			System.out.println("USAGE: <input.dcp> <stamp_direction_file> <output.dcp>\n");
			System.out.println("  Format for the stamp_direction_file is: ");
			System.out.println("    STAMP <define_stamp_index> <anchor_site> <stamp_dcp_file_name> <dcp_metadata_file_name>");
			System.out.println("    <stamp_index> <set anchor site> <desired anchor site placement> <hierarchical cell instance name>");
			return;
		}
		
		CodePerfTracker t = new CodePerfTracker("Stamp-based Placement", true);		
		
		t.start("Read starting design");
		
		Design design = Design.readCheckpoint(args[0], CodePerfTracker.SILENT);
		Device device = design.getDevice();
		
		t.stop().start("Load stamp DCPs");

		Map<Integer,Module> stamps = new HashMap<>();
		Map<Integer, HashMap<String,Site>> placementDirectives = loadPlacementDirectives(device, stamps, args[1]);
		t.stop().start("Stamp placement");

		// Perform the actual placements for each stamp type
		for(Entry<Integer,Module> e : stamps.entrySet()){
			DesignTools.stampPlacement(design, e.getValue(), placementDirectives.get(e.getKey()));
		}
		
		t.stop().start("Writing stamped design");
		
		// Unlock cells by default
		for(Cell c: design.getCells()){
			c.setBELFixed(false);
			c.setSiteFixed(false);
		}
		
		design.writeCheckpoint(args[2], CodePerfTracker.SILENT);
		
		t.stop().printSummary();
	}
}
