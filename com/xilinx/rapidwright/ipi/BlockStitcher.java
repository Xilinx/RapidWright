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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Module;
import com.xilinx.rapidwright.design.ModuleImpls;
import com.xilinx.rapidwright.design.ModuleInst;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Port;
import com.xilinx.rapidwright.design.PortType;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.blocks.BlockGuide;
import com.xilinx.rapidwright.design.blocks.BlockInst;
import com.xilinx.rapidwright.design.blocks.ImplGuide;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.PartNameTools;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.placer.blockplacer.BlockPlacer2;
import com.xilinx.rapidwright.placer.handplacer.HandPlacer;
import com.xilinx.rapidwright.router.Router;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;
import com.xilinx.rapidwright.util.Utils;

/**
 * Main flow for processing and stitching pre-implemented modules together
 * from IP Integrator-based designs.
 * 
 * Created on: Jun 19, 2015
 */
public class BlockStitcher {

	private String partName = null;

	private HashSet<String> uniqueModInstNames = new HashSet<String>();

	private HashMap<String,ArrayList<EDIFCellInst>> ipiBlockInstanceMap = null;
	
	HashMap<String,EDIFCellInst> instNameToInst = new HashMap<String,EDIFCellInst>();

	HashMap<String,String> bdInstNameToModInstName = new HashMap<String, String>();
	
	public static final boolean DUMP_SYNTH_DCP_ONLY = false;
	
	public static final boolean CREATE_ROUTED_DCP = false;
	
	public static final boolean OPEN_HAND_PLACER = false;
	
	public static final boolean INSTANCE_PORT_IOs = true;
	
	public static final boolean REPORT_UNCONNECTED = false;

	public static final String CACHE_ID = "CACHE_ID";
	
	public static final String BLOCK_NAME = "BLOCK_NAME";
	
	private static HashSet<String> reportedUnconnects = new HashSet<>();
	
	private ArrayList<EDIFHierPortInst> getPassThruPortInsts(Port port, EDIFHierPortInst curr){
		ArrayList<EDIFHierPortInst> list = new ArrayList<>();
		for(String name : port.getPassThruPortNames()){
			EDIFPortInst passThruInst = curr.getPortInst().getCellInst().getPortInst(name);
			if(passThruInst == null) {
				String unconnected = curr.getPortInst().getCellInst().getName() + EDIFTools.EDIF_HIER_SEP + name;
				if(!reportedUnconnects.contains(unconnected)){
					System.out.println("INFO: Port " + unconnected + " is unconnected");
					reportedUnconnects.add(unconnected);
				}
				continue;
			}
			EDIFHierPortInst passThru = new EDIFHierPortInst(curr.getHierarchicalInstName(), passThruInst);
			list.add(passThru);
		}
		return list;
	}
	
	private Net validateNet(Net curr, Net found){
		if(curr == null) return found;
		if(!curr.equals(found)){
			throw new RuntimeException("ERROR: Consolidation of nets failed: curr=" + curr + " found=" + found);
		}
		return found;
	}
	
	/**
	 * Stitches the logical netlist components into the black boxes of the top-level EDIF netlist.
	 * Also stitches together the physical nets.
	 * @param design
	 * @param constraints
	 */
	public void stitchDesign(Design design, HashMap<String,PackagePinConstraint> constraints){
		boolean debug = false;
		EDIFNetlist n = design.getNetlist();
		// Create a reverse parent net map (Parent Net -> Children Nets: all logical nets that are physically equivalent) 
		HashMap<String,ArrayList<String>> reverseMap = new HashMap<>();		
		for(Entry<String,String> e : n.getParentNetMap().entrySet()){
			ArrayList<String> l = reverseMap.get(e.getValue());
			if(l == null){
				l = new ArrayList<>();
				reverseMap.put(e.getValue(), l);
			}
			l.add(e.getKey());
		}
		
		HashSet<String> addedPorts = new HashSet<>();
		HashMap<String,ArrayList<EDIFHierPortInst>> portGroups = new HashMap<>();
		// For each parent (physical) net...
		for(Entry<String,ArrayList<String>> e : reverseMap.entrySet()){
			if(debug) System.out.println(e.getKey() + ":");
			ArrayList<EDIFHierPortInst> absPortInsts = new ArrayList<>();
			EDIFHierNet absNet = n.getHierNetFromName(e.getKey());
			if(absNet == null){
				throw new RuntimeException("ERROR: Couldn't find net named " + e.getKey());
			}
			// For each child (physically equivalent) net...
			for(String netName : e.getValue()){
				absNet = n.getHierNetFromName(netName);
				// Get all the port instances that belong to these nets
				for(EDIFPortInst p : absNet.getNet().getPortInsts()){
					EDIFHierPortInst absPort = new EDIFHierPortInst(absNet.getHierarchicalInstName(), p);
					if(addedPorts.contains(absPort.toString())) continue;
					addedPorts.add(absPort.toString());
					absPortInsts.add(absPort);
				}
			}
			// For each port instance connected to the group of physically equivalent nets...
			for(EDIFHierPortInst p : absPortInsts){
				// Create a port group - physically equivalent set of ports
				portGroups.put(p.toString(), absPortInsts);
				if(debug) System.out.println("  "+ p.getFullHierarchicalInstName() + "/"+ p.getPortInst().getName());
				ModuleInst mi = design.getModuleInst(p.getFullHierarchicalInstName());
				if(mi == null) continue;
				Port port = mi.getPort(p.getPortInst().getName());
				if(debug) System.out.println("    (PORT) " + port.getType() + " " + port.getName() + " " + port.getPassThruPortNames());
			}
		}
		
		HashSet<String> visited = new HashSet<>();
		HashMap<EDIFHierPortInst,Net> topPortsMap = new HashMap<>();
		// Connect port groups through pass-thru connections from Module Port meta-data
		for(Entry<String, ArrayList<EDIFHierPortInst>> e : portGroups.entrySet()){
			if(visited.contains(e.getKey())) continue;
			Queue<EDIFHierPortInst> q = new LinkedList<>(e.getValue());
			ArrayList<Net> nets = new ArrayList<>();
			HashSet<String> netsAlreadyVisited = new HashSet<>();
			ArrayList<EDIFHierPortInst> topPorts = new ArrayList<>();
			Net newNet = null;
			while(!q.isEmpty()){
				EDIFHierPortInst curr = q.poll();
				if(visited.contains(curr.toString())) continue;
				visited.add(curr.toString());
				ModuleInst mi = design.getModuleInst(curr.getFullHierarchicalInstName());
				if(mi == null) {
					EDIFCellInst inst = curr.getPortInst().getCellInst();
					if(inst != null){
						// Internal VCC/GND source
						if(inst.getCellType().getName().equals("GND")) {
							newNet = validateNet(newNet, design.getGndNet());
						}
						else if(inst.getCellType().getName().equals("VCC")) {
							newNet = validateNet(newNet, design.getVccNet());
						}
					} else if(curr.getHierarchicalInstName().equals("")){
						topPorts.add(curr);
					}
					continue;
				}
				Port port = mi.getPort(curr.getPortInst().getName());
				if(REPORT_UNCONNECTED && !port.isOutPort() && port.getType() == PortType.UNCONNECTED){
					MessageGenerator.briefError("WARNING: " + curr + " is unconnected internally.");
				}
				for(EDIFHierPortInst passThru : getPassThruPortInsts(port, curr)){
					String passThruName = passThru.toString();
					if(visited.contains(passThruName)) continue;
					ArrayList<EDIFHierPortInst> passThruGroup = portGroups.get(passThruName);
					if(passThruGroup == null){
						// This is likely a pass-thru that is static-driven and down stream 
						// sinks have not been explored
						EDIFHierPortInst portInst = n.getHierPortInstFromName(passThruName);
						passThruGroup = new ArrayList<>();
						for(EDIFPortInst pi : portInst.getPortInst().getNet().getPortInsts()){
							passThruGroup.add(new EDIFHierPortInst(portInst.getHierarchicalInstName(), pi));
						}
						
					}
					q.addAll(passThruGroup);
				}
				Net net = getCorrespondingNet(curr,design);
				if(net == null) continue;
				if(netsAlreadyVisited.contains(net.getName())) continue;
				netsAlreadyVisited.add(net.getName());

				if(net.getType() == NetType.GND) {
					newNet = validateNet(newNet, design.getGndNet());
				}
				else if(net.getType() == NetType.VCC){
					newNet = validateNet(newNet, design.getVccNet());
				}else{
					nets.add(net);
					if(net.getSource() != null){
						newNet = validateNet(newNet, net);
					}
				}
			}
			
			if(newNet == null){
				if(nets.size() == 0) continue;
				// This is a new with a top-level input yet to be instantiated
				newNet = nets.get(0);
			}
			
			for(Net net : nets){
				if(net.equals(newNet)) continue;
				design.movePinsToNewNetDeleteOldNet(net, newNet, true);
			}
			for(EDIFHierPortInst p : topPorts){
				topPortsMap.put(p, newNet);
			}
		}

		// Handle top level pins / IO instantiation
		// Note that it appears like some top-level pins might already have IOs instantiated (clk_wiz)
		if(INSTANCE_PORT_IOs && constraints != null && constraints.size() > 0){
			nextPort: for(Entry<EDIFHierPortInst, Net> e : topPortsMap.entrySet()){			
				String portName = e.getKey().getPortInst().getName();
				Net portNet = null;
				portNet = e.getValue();

				Site site = design.getDevice().getSiteFromPackagePin(constraints.get(portName).getName());
				if(site == null){
					MessageGenerator.briefMessage("WARNING: It appears that the I/O called " + portName + " is not assigned to a package pin!");
					continue nextPort;
				}
				
				// Check for IOs that already exist, we can skip instantiation
				boolean isPortOutput = e.getKey().isOutput(); 
				for(SitePinInst p : portNet.getPins()){
					boolean portDirMatch = isPortOutput == !p.isOutPin();
					if(portDirMatch && p.getSite().getName().startsWith("IOB_")){
						MessageGenerator.briefMessage("INFO: IOB already instantiated for " + e.getKey());
						continue nextPort;
					}
				}					
				
				SiteInst inst = design.getSiteInstFromSite(site);
				if(inst != null){
					// IO Site has already been created
					continue;
				}

				String ioStandard = constraints.get(portName).getIoStandard();
				String pkgPin = constraints.get(portName).getName();
				EDIFNet logNet = e.getKey().getPortInst().getNet();
				design.createAndPlaceIOB(portName, isPortOutput ? PinType.OUT : PinType.IN, pkgPin, ioStandard, portNet, logNet);
			}
		}
		
		HashMap<Site, SiteInst> uniqueMap = new HashMap<Site, SiteInst>();
		for(SiteInst i : design.getSiteInsts()){			
			if(!Utils.isModuleSiteType(i.getSiteTypeEnum())){
				i.detachFromModule();
				for(SitePinInst p : i.getSitePinInsts()){
					p.getNet().unroute();
				}
				Site site = i.getSite();
				if(site != null){					
					if(uniqueMap.containsKey(i.getSite())){
						SiteInst duplicate = uniqueMap.get(site);
						System.out.println("WARNING: Found duplicate site used by instances: " + i.getName() + " " + duplicate.getName() + " " + i.getSiteName());
					}else{
						uniqueMap.put(i.getSite(),i);
					}
				}else{
					if(i.getModuleInst() == null){
						System.out.println("WARNING: Unplaced site outside of module instance: " + i.getName()  + " "+ i.getSiteTypeEnum());
					}
				}
			}
		}
	}
	
	public Net getCorrespondingNet(EDIFHierPortInst pr, Design design){
		String modInstName = pr.getFullHierarchicalInstName();
		ModuleInst mi = design.getModuleInst(modInstName);
		if(mi == null){
			//throw new RuntimeException("ERROR: No module instance named: " +modInstName);
			return null;
		}
		Port port = mi.getModule().getPort(pr.getPortInst().getName());
		if(port.getType() == PortType.UNCONNECTED) 
			return null;
		if(port.getSitePinInst() == null){
			if(port.getType() == PortType.GROUND) return design.getGndNet();
			if(port.getType() == PortType.POWER) return design.getVccNet();
			return null;
		}
		if(port.getSitePinInst().getNet() == null){
			return null;
		}
		String netName = modInstName + "/" + port.getSitePinInst().getNet().getName();
		Net net = design.getNet(netName);

		return net;
	}
	
	public String getModuleInstName(EDIFNetlist module, EDIFNetlist netlist, HashMap<String,String> modInstNameMap){
		String fullInstName = modInstNameMap.get(module.getTopCell().getName());
		
		boolean first = true;
		int i=0;
		while(uniqueModInstNames.contains(fullInstName)){
			i++;
			if(first) {
				fullInstName = fullInstName + "_" + i;
				first = false;
			}else{
				fullInstName = fullInstName.substring(0, fullInstName.lastIndexOf('_')+1) + i; 
			}
		}
		uniqueModInstNames.add(fullInstName);
		return fullInstName;
	}
	

	
	private boolean netlistHasBlock(Design stitched, String blockName){
		String netlistPrefix = stitched.getNetlist().getName() + "_";
		String blockName2 = blockName.replaceFirst(netlistPrefix, "");
		blockName2 = blockName2.substring(0, blockName2.length()-2);
		
		if(ipiBlockInstanceMap == null){
			ipiBlockInstanceMap = new HashMap<String,ArrayList<EDIFCellInst>>();
			Queue<EDIFCell> q = new LinkedList<EDIFCell>();
			q.add(stitched.getNetlist().getTopCell());
			while(!q.isEmpty()){
				EDIFCell curr = q.poll();
				for(EDIFCellInst eci : curr.getCellInsts()){
					if(eci.getCellType().getName().equals("GND") || eci.getCellType().getName().equals("VCC")){
						continue;
					}
					ArrayList<EDIFCellInst> insts = ipiBlockInstanceMap.get(eci.getName());
					if(insts == null){
						insts = new ArrayList<EDIFCellInst>();
						ipiBlockInstanceMap.put(eci.getName(), insts);
					}
					insts.add(eci);
					//EDIFCellInst eciErr = ipiBlockInstanceMap.put(eci.getName(),eci);
					if(eci.getCellType().getCellInsts() != null){
						q.add(eci.getCellType());
					}
				}
			}
		}
		
		return ipiBlockInstanceMap.containsKey(blockName2);
	}
	
	private HashMap<String,String> getPartAndIPNames(String fileName){
		ArrayList<String> lines = FileTools.getLinesFromTextFile(fileName);
		HashMap<String,String> names = new HashMap<String,String>();
		boolean first = true;
		for(String line : lines){
			if(first){
				first = false;
				partName = line.trim();
				continue;
			}
			String[] parts = line.split(" ");
			names.put(parts[0], parts[2]);
			bdInstNameToModInstName.put(parts[0], parts[3].substring(1));
		}
		return names;
	}
	
	private void populateModuleInstMaps(EDIFCell top){
		Queue<EDIFHierCellInst> queue = new LinkedList<EDIFHierCellInst>();
		for(EDIFCellInst i : top.getCellInsts()){
			queue.add(new EDIFHierCellInst("", i));
		}
		while(!queue.isEmpty()){
			EDIFHierCellInst p = queue.poll();
			EDIFCellInst i = p.getInst();
			if(i.getName().equals("VCC") || i.getName().equals("GND")) continue;
			String sep = p.getHierarchicalInstName().equals("") ? "" : "/";
			String curr = p.getHierarchicalInstName() + sep + i.getName();
			instNameToInst.put(curr, i);
			if(i.getCellType().getCellInsts() == null) continue;
			for(EDIFCellInst i2 : i.getCellType().getCellInsts()){
				queue.add(new EDIFHierCellInst(curr, i2));
			}
		}
	}
	
	
	public static void main(String[] args) {
		if(args.length != 3){
			MessageGenerator.briefMessageAndExit("USAGE: <directory to runs> <top_level_edif_file> <file_of_ip_names>");
		}
		CodePerfTracker t = new CodePerfTracker("BlockStitcher", false);
		t.start("Init");
		long[] runtimes = new long[6];
		runtimes[0] = runtimes[1] = System.currentTimeMillis();
		
		File dir = new File(args[0]);
		if(!dir.exists()){
			throw new RuntimeException("ERROR: BlockGuide cache directory does not exist!");
		}
		BlockStitcher stitcher = new BlockStitcher();
		HashMap<String,String> ipNames = stitcher.getPartAndIPNames(args[2]);
		
		ImplGuide implHelper = null;
		HashSet<String> unusedImplGuides = null;
		
		boolean buildExampleGuideFile = false;
		if(!DUMP_SYNTH_DCP_ONLY){
			String implGuideFileName = args[1].replace(".edf", ".igf");
			if(new File(implGuideFileName).exists()){
				implHelper = ImplGuide.readImplGuide(implGuideFileName);
				unusedImplGuides = new HashSet<String>(implHelper.getBlockNames());
			}else{
				System.out.println("INFO: No .igf file found, proceeding with auto block placement and routing.");
				buildExampleGuideFile = true;
			}
		}
		t.stop().start("Reading Top Level EDIF");
		EDIFNetlist topEdifNetlist = EDIFTools.readEdifFile(args[1]);
		Design stitched = new Design("top_stitched", stitcher.partName);
		stitched.setNetlist(topEdifNetlist);
		t.stop().start("Implement Blocks");
		stitcher.populateModuleInstMaps(topEdifNetlist.getTopCell());
		
		if(!FileTools.isVivadoOnPath()){
			MessageGenerator.briefError("WARNING: Vivado executable is not on path.  All BlockStitcher implementation builds will fail.");
		}
		
		BlockCreator.implementBlocks(ipNames, dir.getAbsolutePath(), implHelper, stitched.getDevice());
		
		HashMap<String,String> modInstName2CacheID = new HashMap<String, String>();
		t.stop().start("Retrieve Blocks from Cache");
		int totalBlocks = 0;
		HashMap<ModuleInst,EDIFNetlist> miMap = new HashMap<ModuleInst,EDIFNetlist>();
		for(Entry<String,String> e : ipNames.entrySet()){
			String blockName = e.getKey();
			String cacheID = e.getValue();
			if(implHelper != null){
				unusedImplGuides.remove(cacheID);
			}
			
			File dir2 = new File(dir + File.separator + cacheID);
			String routedDCPFileName = null;
			String edifFileName = null;
			String xciFileName = null;
			int blockImplCount = 0;
			if(dir2.list() == null || dir2.list().length == 0){
				throw new RuntimeException("ERROR: Cached entry " + dir2 + " for ip " + blockName +" is empty!");
			}
			for(String d2: dir2.list()){
				File f = new File(d2);
				if(f.getName().endsWith(BlockCreator.ROUTED_DCP_SUFFIX) && !f.getName().contains("roundtrip")){
					routedDCPFileName = dir + File.separator + cacheID + File.separator + f.getName();
					blockImplCount++;
				}else if(f.getName().endsWith(BlockCreator.ROUTED_EDIF_SUFFIX)){
					edifFileName = dir + File.separator + cacheID + File.separator + f.getName();
				}					
			}

			if(!stitcher.netlistHasBlock(stitched, blockName)){
				continue;
			}

			xciFileName = dir2 + "/" + cacheID + ".xci"; 
			//System.out.println(routedDCPFileName + " " + edifFileName + " " + xciFileName);

			ModuleImpls modImpls = BlockCreator.createOrRetrieveBlock(edifFileName, routedDCPFileName, blockName, xciFileName, blockImplCount);
			for(Module m : modImpls){
				// Add Cache ID to Module
				m.getMetaDataMap().put(CACHE_ID, cacheID);
				m.getMetaDataMap().put(BLOCK_NAME, blockName);
			}
			totalBlocks++;
			
			String modInstName = stitcher.bdInstNameToModInstName.get(blockName);
			modInstName2CacheID.put(modInstName, cacheID);

			int implementationIndex = 0;
			
			if(implHelper != null){
				BlockGuide blockGuide = implHelper.getBlock(cacheID);
				if(blockGuide != null){
					BlockInst bi = blockGuide.getInst(modInstName);
					if(bi == null){
						throw new RuntimeException("ERROR: Missing placement for " + modInstName + " in .igf file.");
					}
					implementationIndex = bi.getImplIndex();
				}
			}
			//System.out.println(modInstName + " " + implementationIndex);
			EDIFNetlist tmp = stitched.getNetlist();
			stitched.setNetlist(null);
			ModuleInst mi = stitched.createModuleInst(modInstName, modImpls.get(implementationIndex));
			stitched.setNetlist(tmp);
			miMap.put(mi, modImpls.getNetlist());
			SiteInst anchor = mi.getModule().getAnchor();
			if(anchor != null){
				mi.place(anchor.getSite());
				//System.out.println("Placed: " + mi.place(anchor.getSite(), stitched.getDevice()) + " " + totalBlocks);
			}
			
		}
		// Check for unused impl guide directives
		if(implHelper != null){
			for(String id : unusedImplGuides){
				System.out.println("WARNING: Unused impl guide ID " + id);
			}
		}
		
		
		// Update package in case block loading changed it
		stitched.getDevice().setActivePackage(PartNameTools.getPart(stitcher.partName).getPkg());
		stitched.getNetlist().setDevice(stitched.getDevice());
		
		runtimes[1] = System.currentTimeMillis() - runtimes[1];
		runtimes[2] = System.currentTimeMillis();
		System.out.println("Total Blocks : " + totalBlocks);
		String xdcFileName = args[1].replace(".edf", ".xdc");
		HashMap<String,PackagePinConstraint> constraints = null;
		if(new File(xdcFileName).exists()){
			constraints = XDCParser.parseXDC(xdcFileName,stitched.getDevice());
		}else{
			MessageGenerator.briefError("WARNING: Could not find XDC file " + xdcFileName);
		}
		t.stop().start("Stitch Design");

		stitcher.stitchDesign(stitched, constraints);
		
		Set<String> uniqifiedNetlists = new HashSet<>();
		for(Entry<ModuleInst,EDIFNetlist> e : miMap.entrySet()){
			//System.out.println(" MAPPINGS: " + e.getKey() + " " + e.getValue() + " " + stitcher.instNameToInst.get(e.getKey().getName()) );
			if(uniqifiedNetlists.contains(e.getValue().getName())) continue;
			uniqifiedNetlists.add(e.getValue().getName());
			stitched.repopulateNetlistOfModuleInst(e.getKey(), e.getValue());
		}
		
		EDIFCell top = stitched.getNetlist().getTopCell();
		EDIFLibrary work = stitched.getNetlist().getLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME);
		work.addCell(top);
		for(Entry<ModuleInst,EDIFNetlist> e : miMap.entrySet()){
			EDIFCellInst inst = EDIFTools.getEDIFCellInst(stitched.getNetlist(), e.getKey().getName());//top.getCellInstance(e.getKey().getName());
			if(inst == null) throw new RuntimeException("ERROR: Couldn't update EDIF cell instance.");
			EDIFCell cellType = work.getCell(e.getValue().getName() + "_" + e.getValue().getName());
			if(cellType == null) throw new RuntimeException("ERROR: Couldn't update EDIF cell type " + e.getValue().getName());
			inst.setCellType(cellType);
		}
		
		for(EDIFCell c : stitched.getNetlist().getLibrary("IP_Integrator_Lib").getCells()){
			work.addCell(c);
		}
		
		ArrayList<String> libsToRemove = new ArrayList<>();
		for(EDIFLibrary lib : stitched.getNetlist().getLibraries()){
			if(lib.getName().equals(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME) || 
					lib.getName().equals(EDIFTools.EDIF_LIBRARY_WORK_NAME)) continue;
			libsToRemove.add(lib.getName());
		}
		for(String lib : libsToRemove){
			stitched.getNetlist().removeLibrary(lib);
		}
		t.stop();
		if(DUMP_SYNTH_DCP_ONLY){
			stitched.unplaceDesign();
			String dcpName = args[1].replace(".edf", "_rw_synth.dcp");
			stitched.writeCheckpoint(dcpName,t);
			//EDIFTools.writeEDIFFile(args[1].replace(".edf", "_stitched.edf"), stitched.getNetlist(), stitched.getPartName());
			MessageGenerator.briefMessageAndExit("Wrote Synthesized DCP: " + dcpName);
		}
		
		//XPNWriter.writeXPN(stitched, args[1].replace(".edf", ".xpn"), true);
		//XPNWriter.writeXPN(stitched, args[1].replace(".edf", "_not_flat.xpn"), false);
		//EDIFTools.writeEDIFFile(args[1].replace(".edf", "_stitched.edf"), stitched.getNetlist(), stitched.getPartName());
		
		runtimes[2] = System.currentTimeMillis() - runtimes[2];
		runtimes[3] = System.currentTimeMillis();
		
		if(new File(xdcFileName).exists()){
			for(String line : FileTools.getLinesFromTextFile(xdcFileName)){
				stitched.addXDCConstraint(ConstraintGroup.LATE,line);
			}
		}
		
		if(implHelper != null){
			stitched.setAutoIOBuffers(false);
			stitched.setDesignOutOfContext(true);
			
			int sliceY = 0;  // TODO - Empty blocks are placed in a column so they don't overlap
			t = new CodePerfTracker("CUSTOM PLACER", true);
			t.start("Custom Placement");
			
			for(ModuleInst mi : stitched.getModuleInsts()){				
				BlockGuide blockHelper = implHelper.getBlock(modInstName2CacheID.get(mi.getName()));
				if(blockHelper == null){
					String newAnchor = "SLICE_X167Y" + sliceY++;
					mi.place(stitched.getDevice().getSite(newAnchor));
					continue;
				}
				Site s = blockHelper.getInst(mi.getName()).getPlacement();
				PBlock pb = blockHelper.getImplementations().get(blockHelper.getInst(mi.getName()).getImplIndex());
				boolean success = mi.placeMINearTile(s.getTile(), s.getSiteTypeEnum());
				if(!success){
					MessageGenerator.briefError("ERROR: Couldn't place " + mi.getName() + " on site " + s.getName());
				}
			}
			t.stop().start("Write Stitched EDIF");
			EDIFTools.writeEDIFFile(args[1].replace(".edf", "_stitched.edf"), stitched.getNetlist(), stitched.getPartName());
			t.stop().start("Save Checkpoint");
			stitched.writeCheckpoint(args[1].replace(".edf","_placed.dcp"));
			t.stop();
			t.printSummary();
			if(OPEN_HAND_PLACER) HandPlacer.openDesign(stitched);
			return;
		}else{
			BlockPlacer2 placer = new BlockPlacer2();
			placer.placeDesign(stitched, false);
		}

		// Create an example impl guide file
		if(buildExampleGuideFile){
			ImplGuide ig = new ImplGuide();
			ig.setPart(stitched.getPart());
			ig.setDevice(stitched.getDevice());
			
			for(ModuleImpls m : stitched.getModules()){
				BlockGuide bg = ig.createBlockGuide(m.get(0).getMetaDataMap().get(CACHE_ID));
				for(Module m2 : m){
					bg.addImplementation(m2.getImplementationIndex(), m2.getPBlock());
				}
			}
			
			for(ModuleInst mi : miMap.keySet()){
				if(mi.getModule().getPBlock() == null) continue;
				if(mi.getModule().getAnchor() == null) continue;
				String cacheID = mi.getModule().getMetaDataMap().get(CACHE_ID);
				BlockGuide bg = ig.getBlock(cacheID);
				
				BlockInst bi = new BlockInst();
				bi.setImpl(mi.getModule().getImplementationIndex());
				bi.setName(mi.getName());
				bi.setParent(bg);
				bi.setPlacement(mi.getLowerLeftPlacement());
				bg.addBlockInst(bi);
			}
			
			// Remove any blocks that don't have pblock/implementations
			ig.removeBlocksWithoutPBlocks();
			
			ig.writeImplGuide(args[1].replace(".edf", ".igf.example"));
		}
		
		// Need to remove duplicate sites because IPI will generate the same IOs in multiple IP blocks :-(
		HashMap<String,SiteInst> duplicateCheck = new HashMap<String, SiteInst>();
		ArrayList<SiteInst> removeThese = new ArrayList<SiteInst>();
		for(SiteInst inst : stitched.getSiteInsts()){
			SiteInst match = duplicateCheck.get(inst.getSiteName());
			if(match != null){
				//System.out.println("Found match: " + match.toString() + " " + inst.toString());
				if(match.getNetList().size() == 0){
					removeThese.add(match);
				}else{
					removeThese.add(inst);
					duplicateCheck.put(inst.getSiteName(), match);
				}
				
			}else{
				duplicateCheck.put(inst.getSiteName(), inst);
			}
		}
		for(SiteInst i : removeThese){
			stitched.removeSiteInst(i);
		}
		
		if(OPEN_HAND_PLACER) HandPlacer.openDesign(stitched);
		
		runtimes[3] = System.currentTimeMillis() - runtimes[3];
		runtimes[4] = System.currentTimeMillis();

		String placedDCPName = args[1].replace(".edf", "_placed.dcp");
		stitched.writeCheckpoint(placedDCPName);
		
		String routedDCPName = args[1].replace(".edf", "_routed.dcp");
		if(CREATE_ROUTED_DCP){
			Router router = new Router(stitched);
			router.routeDesign();

			runtimes[4] = System.currentTimeMillis() - runtimes[4];
			runtimes[5] = System.currentTimeMillis();

			
			stitched.writeCheckpoint(routedDCPName);
		}else{
			runtimes[4] = System.currentTimeMillis() - runtimes[4];
		}
		
		//EDIFTools.writeEDIFFile(args[1].replace(".edf", BlockCreator.ROUTED_EDIF_SUFFIX), stitched.getNetlist(), stitched.getPartName());

		runtimes[5] = System.currentTimeMillis() - runtimes[5];
		runtimes[0] = System.currentTimeMillis() - runtimes[0];
		
		System.out.println();
		System.out.println("----------------- SUMMARY --------------------");
		System.out.printf("           Module Loading Time : %8.3fs \n", runtimes[1]/1000.0);
		System.out.printf("                Stitching Time : %8.3fs \n", runtimes[2]/1000.0);
		System.out.printf("                   Placer Time : %8.3fs \n", runtimes[3]/1000.0);
		if(CREATE_ROUTED_DCP) System.out.printf("                   Router Time : %8.3fs \n", runtimes[4]/1000.0);
		System.out.printf("            Saving Design Time : %8.3fs \n", runtimes[CREATE_ROUTED_DCP ? 5 : 4]/1000.0);
	    System.out.println("----------------------------------------------");
		System.out.printf("                 Total Runtime : %8.3fs \n", runtimes[0]/1000.0);
		
		if(CREATE_ROUTED_DCP) System.out.println("Created Routed DCP at: " + routedDCPName);
		else System.out.println("Created Placed DCP at: " + placedDCPName);
	}
}

