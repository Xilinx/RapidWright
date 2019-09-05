package com.xilinx.rapidwright.router;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.Map.Entry;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.PinSwap;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.blocks.PBlock;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.device.PIP;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SitePin;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Wire;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * This class provides a RapidWright front-end usage wrapper for the
 * Vivado-distributed executable evRouter.  evRouter is a SAT Solver
 * wrapped with special capabilities of casting place and route problems
 * as SAT problems.
 * 
 * @author clavin
 * 
 */
public class SATRouter {

	public static final String EV_ROUTER = "evRouter";

	/** evRouter Parameter: -v level:     specify verbosity (default 0) */
	private int verbosity = 2;
	/** evRouter Parameter: -c maxConfl:  specify the maximum number of conflicts allowed before quitting */
	private Integer maxConflicts = null;
	/** evRouter Parameter: -p maxPass:   specify the maximum number of passes allowed before quitting */
	private Integer maxPasses = null;
	/** evRouter Parameter: -o outFile:   output file were the routing solution is dumped when specified */
	private String outputFileName = "evRouter_output.txt";
	/** evRouter Parameter: -dly k:       specify how many delays are initially used for each sink (default 1) */	
	private int delaysPerSink = 1;
	/** evRouter Parameter: -dot dotFile: dump a dot description of the routing solution */	
	private String dottyFile = null;
	/** evRouter Parameter: -cst cstFile: input file specifying net constraints */	
	private String cstFile = null;
	/** evRouter Parameter: -reg regFile: input file specifying node regions */	
	private String regFile = null;
	/** evRouter Parameter: -hierid chip:reg:loc: specify how many chip/region/local ID (default 16/64/16) */	
	private Integer chipID = null;
	/** evRouter Parameter: -hierid chip:reg:loc: specify how many chip/region/local ID (default 16/64/16) */
	private Integer regionID = null;
	/** evRouter Parameter: -hierid chip:reg:loc: specify how many chip/region/local ID (default 16/64/16) */
	private Integer localID = null;
	/** evRouter Parameter: -egp egpFile: input file specifying edge groups */	
	private String edgeGroupsFile = null;
	/** evRouter Parameter: -ngp egpFile: input file specifying net groups */	
	private String netGroupsFile = null;
	/** evRouter Parameter: -vc vcFile:   input file specifying VC assignment for each net */
	private String vcNetAssignmentFile = null;
	/** evRouter Parameter: -opt:         optimize the route utilization */	
	private boolean optRouteUtilization = false;
	/** evRouter Parameter: -dopart:      try partition the graph into node clusters for better scalability */
	private boolean partitionGraph = false;
	/** evRouter Parameter: pipFile:      pipulation file describing the interconnect architecture */
	private String pipFile = "evRouter_pip.txt";
	/** evRouter Parameter: pbFile:       problem file describing the routing problem */	
	private String pbFile = "evRouter_pb.txt";
	/** Standard out/err output file name from evRouter run */
	private String satLogFile = "evRouter.log";
	/** Design on which to solve */
	private Design design; 
	/** Pblock used to constrain routing of the design */
	private PBlock pblock;
	/** Current set of nets to route */
	private Set<Net> netsToRoute;
	/** Nodes to exclude from routing solution */
	private HashSet<Node> excludedNodes;
	/** Adds arbitrary weights to help prioritize faster LUT inputs */
	private boolean useWeightsOnNodes = false;
	/** Flag to indicate if the routing should be fixed */
	private boolean fixRouting = false;
	
	private int commonNodeWeight = 10;
	
	private final long SEED = 82;
	
	private int[] lutInputWeights = new int[]{50, 45, 35, 30, 20, 10};
	/**
	 * Initialize the SAT router with a design and area constraint (pblock) to describe
	 * the routing problem.
	 * @param design A placed design to route
	 * @param pblock The area to use to identify nets to route and to supply 
	 * routing nodes for completing the routes.  All nets that have all 
	 * endpoints physically located inside the pblock will be included in 
	 * the route attempt.
	 */
	public SATRouter(Design design, PBlock pblock){
		init(design,pblock, true);
		populateNetsToRoute();
	}

	/**
	 * Initialize the SAT router with a design and area constraint (pblock) to describe
	 * the routing problem.
	 * @param design A placed design to route
	 * @param pblock The area to use to identify nets to route and to supply 
	 * routing nodes for completing the routes.  All nets that have all 
	 * endpoints physically located inside the pblock will be included in 
	 * the route attempt.
	 * @param unroute Unroutes the design before using the SAT solver (by default, always unroutes)
	 */
	public SATRouter(Design design, PBlock pblock, boolean unroute){
		init(design,pblock, unroute);
		populateNetsToRoute();
	}
	
	/**
	 * Initializes the SAT router with a design, area constraint (pblock) and 
	 * list of nets to route.  
	 * @param design A placed design to route
	 * @param pblock The area to be used for routing the nets
	 * @param netsToRoute List of nets to be routed, their physical endpoints 
	 * must be located within the pblock.
	 */
	public SATRouter(Design design, PBlock pblock, Collection<Net> netsToRoute){
		init(design,pblock, true);
		this.netsToRoute = new HashSet<>(netsToRoute);
	}

	private void init(Design design, PBlock pblock, boolean unroute){
		this.design = design;
		this.setPblock(pblock);
		// Clear out any site routing so we have a fresh start
		/*for(SiteInst i : design.getSiteInsts()){
			i.getCTagMap().clear();
			i.getSiteCTags().clear();
		}*/
		if(unroute) design.unrouteDesign();
	}
	
	public void updateSitePinInsts(){
		EDIFNetlist n = design.getNetlist();
		Map<String,ArrayList<EDIFHierPortInst>> physNetPinMap = n.getPhysicalNetPinMap();
		nextNet: for(Entry<String,ArrayList<EDIFHierPortInst>> netPins : physNetPinMap.entrySet()){
			Net net = design.getNet(netPins.getKey());
			if(net == null){
				net = design.createNet(netPins.getKey());
			}
			EDIFHierPortInst output = null;
			Site outputSite = null;
			for(EDIFHierPortInst p : netPins.getValue()){
				if(p.isOutput() && !p.getPortInst().isPrimitiveStaticSource()){
					output = p;
					Cell c = design.getCell(p.getFullHierarchicalInstName());
					if(c == null){
						// TODO - This is likely a transformed prim, we need to find a way to
						// match it to the inner-prims
						continue nextNet;
					}
					outputSite = c.getSite();
					if(net.getLogicalNet() == null){
						net.setLogicalNet(p.getPortInst().getNet());
					}
				}
			}
			//if(output == null) continue;
			
			for(EDIFHierPortInst p : netPins.getValue()){
				if(p.getPortInst().isPrimitiveStaticSource()){
					continue;
				}
				String instName = p.getFullHierarchicalInstName();
				Cell c = design.getCell(instName);
				if(c == null){
					// TODO - This is likely a transformed prim, we need to find a way to
					// match it to the inner-prims
					continue nextNet;
				}
				SiteInst si = c.getSiteInst();

				String logPortName = p.getPortInst().getName();
				String siteWireName = c.getSiteWireNameFromLogicalPin(logPortName);
				String pinName = null;
				if(siteWireName == null){
					for(Cell otherCell : si.getCells()){
						if(otherCell.getName().equals(c.getName())){
							String physName = otherCell.getPhysicalPinMapping(logPortName);
							if(physName != null){
								BELPin belPin = otherCell.getBEL().getPin(physName);
								pinName = belPin.getConnectedSitePinName(); // Needs to route thru RBELs but not LUTs
								if(pinName == null) continue;
								siteWireName = belPin.getSiteWireName();
								break;
							}
						}
					}
				}
				if(output != null && p != output){
					if(siteWireName == null){
						continue;
					}else if(si.getNetFromSiteWire(siteWireName).equals(net) && outputSite.equals(c.getSite())){
						// Input pin is within same site as output
						continue;
					}
				}
				// Special case for SRLs, must tie certain pins to GND
				if(c.getType().contains("SRL16")){
					for(String name : new String[] {"WA7", "WA8"}){
						BELPin belPin = c.getBEL().getPin(name);
						if(belPin == null) continue;
						String sitePinName = belPin.getConnectedSitePinName();
						SitePinInst pin = si.getSitePinInst(sitePinName);
						if(pin == null){
							pin = design.getGndNet().createPin(p.isOutput(), sitePinName, si);
						}
					}
				}
				
				if(pinName == null)
					pinName = c.getCorrespondingSitePinName(p.getPortInst().getName());
				if(pinName == null){
					continue;
				}
				if(si.getSitePinInst(pinName) == null){
					if(pinName.contains("SRST") && net.getName().equals(Net.GND_NET)) {
						// Connect to VCC instead and invert in Site
						design.getVccNet().createPin(p.isOutput(), pinName, si);
						continue;
					}
					net.createPin(p.isOutput(), pinName, si);
				}
			}
			
		}
	}
	
	/**
	 * Searches the placed design for nets that are physically located within
	 * the area constraint (pblock).  Also analyzes the design for partially/fully
	 * routed nets already consuming routing resources and excludes them
	 * for the SAT solver. 
	 */
	private void populateNetsToRoute(){
		Map<String,String> parentNetMap = design.getNetlist().getParentNetMap();
		for(SiteInst i : design.getSiteInsts()){
			for(Entry<String,Net> e : i.getNetSiteWireMap().entrySet()){
				Net n = e.getValue();
				if(e.getValue().isStaticNet()) continue;
				if(n.getName().equals(Net.USED_NET)) continue;
				String parentNetName = parentNetMap.get(n.getName());
				if(parentNetName == null){
					// Try looking at the connected cell pin and use that net instead
					int siteWireIdx = i.getSite().getSiteWireIndex(e.getKey());
					parentNetName = DesignTools.resolveNetNameFromSiteWire(i, siteWireIdx);
					if(parentNetName == null) continue;
				}else if(!n.getName().equals(parentNetName)){
					Net parentNet = design.getNet(parentNetName);
					if(parentNet == null){
						parentNet = new Net(design.getNetlist().getHierNetFromName(parentNetName));
					}
					for(String cTag : new ArrayList<String>(i.getSiteWiresFromNet(n))){
						BELPin belPin = i.getSite().getBELPins(cTag)[0];
						i.routeIntraSiteNet(parentNet, belPin, belPin);
					}
				}
			}
		}
		design.routeSites();
		//updateSitePinInsts();
		
		// Find nets
		HashSet<Net> visitedNets = new HashSet<>();
		for(Tile t : pblock.getAllTiles()){
			for(Site s : t.getSites()){
				SiteInst si = design.getSiteInstFromSite(s);
				if(si == null) continue;
				for(SitePinInst pin : si.getSitePinInsts()){
					if(pin.getNet() == null) continue;
					visitedNets.add(pin.getNet());
				}
			}
		}
		netsToRoute = new HashSet<>(); 
		nextNet : for(Net n : visitedNets){
			// TODO - Skip GND/VCC
			if(n.isStaticNet()) continue;
			if(n.getSource() == null) continue;
			for(SitePinInst pin : n.getPins()){
				if(!pblock.containsTile(pin.getTile())){
					continue nextNet;
				}
			}
			netsToRoute.add(n);
		}		
		
		// Find used nodes
		excludedNodes = new HashSet<>();
		for(Net n : design.getNets()){
			for(PIP p : n.getPIPs()){
				if(pblock.containsTile(p.getTile())){
					excludedNodes.add(new Node(p.getStartWire()));
					excludedNodes.add(new Node(p.getEndWire()));
				}
			}
		}
	}
	
	/**
	 * Sanity check on nodes to decided if they should be
	 * included in the set of nodes to be used for routing.
	 * @param n The node to check
	 * @return True if the node is safe to use, false otherwise.
	 */
	private boolean includeNode(Node n){
		SitePin sp = n.getSitePin();
		if(sp != null){
			SiteInst si = design.getSiteInstFromSite(sp.getSite());
			if(si == null) return true;
			Net connNet = si.getNetFromSiteWire(sp.getPinName());
			if(connNet == null) return true;
			if(netsToRoute.contains(connNet)) return true;
			if(SitePinInst.isLUTInputPin(si,sp.getPinName())) return true;
			return false;
		}
		return true;
	}
	
	/**
	 * Creates the necessary routing resource graph file
	 * to supply evRouter for routing.
	 */
	public void createPipFile(){
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(pipFile));
			// Find all sites, expand from all outputs within the region
			Set<Tile> tiles = pblock.getAllTiles();
			Set<Node> reported = new HashSet<>();
						
			HashMap<String,Cell> luts = new HashMap<String,Cell>();
			for(Tile t : tiles){
				for(int i=0; i < t.getWireCount(); i++){
					if(IntentCode.isUltraScaleClocking(t, i)) continue;
					Node n = new Node(t,i);					
					if(!includeNode(n)) continue;
					if(reported.contains(n)) continue;
					if(excludedNodes.contains(n)) continue;
					bw.write(n.toString());
					for(Wire w : n.getAllWiresInNode()){
						HashSet<Node> currNodes = new HashSet<Node>();
						for(PIP p : w.getBackwardPIPs()){
							if(p.isRouteThru()) continue;
							String startWireName = p.getStartWireName();
							Node start = new Node(w.getTile(),startWireName);
							if(!currNodes.contains(start) && tiles.contains(start.getTile())){
								bw.write(" " + start + (useWeightsOnNodes ? ":" + commonNodeWeight : ""));
								currNodes.add(start);
							}
						}
					}
					reported.add(n);
					bw.write("\n");
				}
				if(t.getSites() == null) continue;
				for(Site s : t.getSites()){
					SiteInst si = design.getSiteInstFromSite(s);
					if(si == null) continue;
					for(Cell c : si.getCells()){
						if(c.getBELName().contains("LUT")){
							// Check if the 5LUT is used, if it is, we use that instead
							Cell lut5 = si.getCell(si.getBEL(c.getBELName().replace("6", "5")).getName());
							if(lut5 != null) luts.put(lut5.toString(), lut5);
							else luts.put(c.toString(), c);
						}
					}
				}
			}
			
			// Generate full crossbar for LUT inputs, using lut input wire as 2nd stage			
			for(Cell lut : luts.values()){
				int lutSize = lut.getBELName().charAt(1) - 48 /* ASCII 0 */;
				Wire[] wires = new Wire[lutSize];
				Node[] nodes = new Node[lutSize];
				for(int i=0; i < lutSize; i++){
					String pinName = lut.getSiteWireNameFromPhysicalPin("A" + (i+1));
					int wire = lut.getSite().getTileWireIndexFromPinName(pinName);
					wires[i] = new Wire(lut.getSite().getTile(), wire);
					nodes[i] = new Node(wires[i]);
				}
				for(int i=0; i < lutSize; i++){
					bw.write(wires[i].toString());
					for(int j=0; j < lutSize; j++){
						bw.write(" " + nodes[j] + (useWeightsOnNodes ? ":" + lutInputWeights[j] : ""));
					}
					bw.write("\n");
				}
			}
			
			bw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	public static void randomizeLines(String inputFileName, String outputFileName, long seed){
		ArrayList<String> lines = FileTools.getLinesFromTextFile(inputFileName);
		Random rnd = new Random(seed);
		Collections.shuffle(lines, rnd);
		FileTools.writeLinesToTextFile(lines, outputFileName);
	}
	
	public void createNetsFiles(){
		try {
			BufferedWriter bw = new BufferedWriter(new FileWriter(pbFile));
			nextNet: for(Net n : netsToRoute){
				if(n.getSource() == null || n.getPins().size() < 2){
					//throw new RuntimeException("ERROR: Bad net " + n);
					continue nextNet;
				}
				for(SitePinInst p : n.getPins()){
					if(!pblock.containsTile(p.getTile())) continue nextNet;
				}
				
				bw.write(n.getName() + " " + n.getSource().getConnectedNode());
				for(SitePinInst p : n.getSinkPins()){
					if(p.isLUTInputPin()){
						Wire w = new Wire(p.getTile(),p.getConnectedWireIndex());
						bw.write(" " + w);
					}else{
						bw.write(" " + p.getConnectedNode());
					}
					
				}
				bw.write("\n");
			}
			bw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		randomizeLines(pbFile, pbFile, SEED);
	}
	
	/**
	 * Executes evRouter outside of the RapidWright runtime.  Applies
	 * all configured settings.
	 * @return The exit code of the evRouter execution.
	 */
	public int runEvRouter(){
		String vivadoPath = FileTools.getVivadoPath();
		if(vivadoPath == null || vivadoPath.length() == 0){
			throw new RuntimeException("ERROR: Couldn't find vivado, please set PATH environment variable accordingly.");
		}
		String loaderPath = vivadoPath.replace("bin/vivado", "bin/loader");
		List<String> command = new ArrayList<>();
		command.add(loaderPath);
		command.add("-exec");
		command.add(EV_ROUTER);
		if(verbosity > 0){
			command.add("-v");
			command.add(Integer.toString(getVerbosity()));
		}if(maxConflicts != null){
			command.add("-c");
			command.add(maxConflicts.toString());
		}if(maxPasses != null){
			command.add("-p");
			command.add(maxPasses.toString());
		}
		command.add("-o");
		command.add(outputFileName);
		if(dottyFile != null){
			command.add("-dot");
			command.add(dottyFile);
		}if(cstFile != null){
			command.add("-cst");
			command.add(cstFile);
		}if(regFile != null){
			command.add("-reg");
			command.add(regFile);
		}if(edgeGroupsFile != null){
			command.add("-egp");
			command.add(edgeGroupsFile);
		}if(netGroupsFile != null){
			command.add("-ngp");
			command.add(netGroupsFile);
		}if(vcNetAssignmentFile != null){
			command.add("-vc");
			command.add(vcNetAssignmentFile);
		}if(optRouteUtilization){
			command.add("-opt");
		}
		command.add(pipFile);
		command.add(pbFile);
		return FileTools.runCommand(command, satLogFile);
	}
	
	/**
	 * Reads the output of evRouter and applies the routing solution to the 
	 * existing design.  Manages LUT pin input swapping as a result of the
	 * routing solution taking advantage of the LUT input flexibility.
	 */
	public void applyRoutingResult(){
		Device dev = design.getDevice();
		Net currNet = null;
		HashMap<String,HashMap<String,PinSwap>> pinSwaps = new HashMap<>();
		for(String line : FileTools.getLinesFromTextFile(outputFileName)){
			if(line.contains("{") || line.contains("}") || line.contains("\"tree\":")) continue;
			line = line.trim();
			if(line.equals("[") || line.equals("]")) continue;
			if(line.startsWith("[\"")){
				int comma = line.indexOf(',');
				int rightBracket = line.indexOf(']');
				String node0 = line.substring(2, comma-1);
				String node1 = line.substring(comma+3, rightBracket-1);
				Node n0 = new Node(node0,dev);
				Node n1 = new Node(node1,dev);
				if(!node1.equals(n1.toString())){
					if(n0.equals(n1)) {
						// No pin swapping, this is just a pass-thru
						continue;
					}
					// This is a pin swap rather than a PIP
					Wire w = new Wire(dev,node1);
					SitePin oldPin = w.getSitePin();
					SitePinInst p = design.getSiteInstFromSite(oldPin.getSite()).getSitePinInst(oldPin.getPinName());
					SitePin newPin = n0.getSitePin();
					
					// Let's remove the sitewire routing for the pins that are swapping, but we need 
					// to wait before adding them
					p.getSiteInst().unrouteIntraSiteNet(p.getBELPin(), p.getBELPin());
					
					// Update pin mappings on the cell, there may be more than once cell on a BEL site
					// (5LUT/6LUT sharing an input)
					for(BELPin elePin : oldPin.getBELPin().getSiteConns()){
						String belName = elePin.getBEL().getName();
						Cell c = p.getSiteInst().getCell(belName);
						if(c == null) continue;
						String oldPhysicalPinName = elePin.getName();
						String logicalPinName = c.getLogicalPinMapping(oldPhysicalPinName);
						if(logicalPinName == null) continue;
						BELPin newBELPin = null;
						for(BELPin currCxn : newPin.getBELPin().getSiteConns()){
							if(elePin.getBEL().equals(currCxn.getBEL())){
								if(oldPhysicalPinName.startsWith("A")){
									if(currCxn.getName().startsWith("A")){
										newBELPin = currCxn;
										break;										
									}
								}else{
									newBELPin = currCxn;
									break;
								}
							}
						}
						String key = c.getSiteName() + "/" + c.getBELName().charAt(0);
						HashMap<String,PinSwap> ps = pinSwaps.get(key);
						String psKey = oldPhysicalPinName +">"+newBELPin.getName();
						if(ps == null){
							ps = new HashMap<>();
							pinSwaps.put(key, ps);
						}
						PinSwap match = ps.get(psKey);
						if(match != null){
							// Add companion cell mapping
							match.setCompanionCell(c, logicalPinName);
						}else{
							// Create new entry
							String depopulatedLogicalPinName = c.getLogicalPinMapping(newBELPin.getName());
							ps.put(psKey, new PinSwap(c, logicalPinName,oldPhysicalPinName,newBELPin.getName(), depopulatedLogicalPinName, newPin.getPinName()));							
						}
					}
					continue;
				}
				boolean foundPIP = false;
				outer: for(Wire w : n0.getAllWiresInNode()){
					for(PIP p : w.getForwardPIPs()){
						Node n2 = new Node(p.getEndWire());
						if(n1.equals(n2)){
							PIP pip = new PIP(w.getTile(),w.getWireIndex(),p.getEndWireIndex());
							pip.setIsPIPFixed(fixRouting);
							currNet.addPIP(pip);
							foundPIP = true;
							break outer;
						}
					}
				}
				if(!foundPIP){
					throw new RuntimeException("ERROR: Couldn't find pip from line:\n'" + line + "'");
				}
				
			}
			else if(line.startsWith("\"") && line.endsWith("\":")){
				String netName = line.substring(1, line.length()-2);
				currNet = design.getNet(netName);
			}
		}
		
		// Make all pin swaps per LUT site simultaneously
		for(Entry<String,HashMap<String,PinSwap>> e : pinSwaps.entrySet()){
			processPinSwaps(e.getKey(),new ArrayList<>(e.getValue().values()));
		}
	}
	
	/**
	 * For each pair of LUT sites (5LUT/6LUT), swap pins to reflect the 
	 * solution from the SAT solver. 
	 * @param key The name of the site and letter of LUT pair (ex: SLICE_X54Y44/D)
	 * @param pinSwaps The list of pin swaps to be performed on the pair of LUT sites
	 */
	public void processPinSwaps(String key, ArrayList<PinSwap> pinSwaps){
		LinkedHashMap<String,PinSwap> overwrittenPins = new LinkedHashMap<>();
		LinkedHashMap<String,PinSwap> emptySlots = new LinkedHashMap<>();
		for(PinSwap ps : pinSwaps){
			overwrittenPins.put(ps.getNewPhysicalName(),ps);
			emptySlots.put(ps.getOldPhysicalName(),ps);
		}
		for(PinSwap ps : pinSwaps){
			String oldPin = ps.getOldPhysicalName();
			String newPin = ps.getNewPhysicalName();
			if(emptySlots.containsKey(newPin) && overwrittenPins.containsKey(newPin)){
				overwrittenPins.remove(newPin);
				emptySlots.remove(newPin);
			}
			if(emptySlots.containsKey(oldPin) && overwrittenPins.containsKey(oldPin)){
				overwrittenPins.remove(oldPin);
				emptySlots.remove(oldPin);
			}
		}
		
		if(overwrittenPins.size() != emptySlots.size()){
			throw new RuntimeException("ERROR: Couldn't identify proper pin swap for BEL(s) " + key + "LUT");
		}
		String[] oPins = overwrittenPins.keySet().toArray(new String[overwrittenPins.size()]);
		String[] ePins = emptySlots.keySet().toArray(new String[emptySlots.size()]);
		for(int i=0; i < oPins.length; i++){
			String oldPhysicalPin = oPins[i];
			String newPhysicalPin = ePins[i];
			Cell c = emptySlots.get(newPhysicalPin).getCell();
			String newNetPinName = c.getSiteWireNameFromPhysicalPin(newPhysicalPin);
			// Handles special cases 
			if(c.getLogicalPinMapping(oldPhysicalPin) == null){
				Cell neighborLUT = emptySlots.get(newPhysicalPin).checkForCompanionCell();
				if(neighborLUT != null && emptySlots.get(newPhysicalPin).getCompanionCell() == null){
					String neighborLogicalPinMapping = neighborLUT.getLogicalPinMapping(oldPhysicalPin);
					// Makes sure if both LUT sites are occupied, that pin movements
					// are lock-step
					if(neighborLogicalPinMapping != null){
						PinSwap ps = new PinSwap(neighborLUT, neighborLUT.getLogicalPinMapping(oldPhysicalPin),oldPhysicalPin,newPhysicalPin,
								neighborLUT.getLogicalPinMapping(newPhysicalPin),newNetPinName);
						
						pinSwaps.add(ps);
						continue;
					}
				}
				continue;					
			}
			// Make implicit swaps when one of the pins is not being routed
			// or is unconnected for one or both of the cells
			PinSwap ps = new PinSwap(c, c.getLogicalPinMapping(oldPhysicalPin),oldPhysicalPin,newPhysicalPin,
					c.getLogicalPinMapping(newPhysicalPin),newNetPinName);			
			Cell neighborLUT = ps.checkForCompanionCell();
			if(neighborLUT != null){
				if(neighborLUT.getLogicalPinMapping(oldPhysicalPin) != null){
					ps.setCompanionCell(neighborLUT, neighborLUT.getLogicalPinMapping(oldPhysicalPin));
				}
			}
			pinSwaps.add(ps);
		}
		
		// Prepares pins for swapping by removing them 
		Queue<SitePinInst> q = new LinkedList<>();
		for(PinSwap ps : pinSwaps){
			String oldSitePinName = ps.getCell().getSiteWireNameFromPhysicalPin(ps.getOldPhysicalName());
			SitePinInst p = ps.getCell().getSiteInst().getSitePinInst(oldSitePinName);
			q.add(p);
			p.setSiteInst(null,true);
			// Removes pin mappings to prepare for new pin mappings
			ps.getCell().removePinMapping(ps.getOldPhysicalName());
			if(ps.getCompanionCell() != null){
				ps.getCompanionCell().removePinMapping(ps.getOldPhysicalName());
			}
		}
		
		// Perform the actual swap on cell pin mappings
		for(PinSwap ps : pinSwaps){		
			ps.getCell().addPinMapping(ps.getNewPhysicalName(), ps.getLogicalName());
			if(ps.getCompanionCell() != null){
				ps.getCompanionCell().addPinMapping(ps.getNewPhysicalName(), ps.getCompanionLogicalName());
			}
			SitePinInst pinToMove = q.poll();
			pinToMove.setPinName(ps.getNewNetPinName());
			pinToMove.setSiteInst(ps.getCell().getSiteInst());		
		}
	}
	
	/**
	 * Runs the SAT solver to route the current design and problem configuration.
	 * By default, it will route the nets that are fully enclosed by the provided
	 * pblock.   
	 * 
	 */
	public void route(){		
		if(netsToRoute == null || netsToRoute.size() == 0){
			populateNetsToRoute();
			if(netsToRoute.size() == 0){
				MessageGenerator.briefError("ERROR ("+this.getClass().getSimpleName()+".route()): No nets could be defined for the routing problem. "
					+ " Please check your pblock and design accordingly.");
				return;
			}
		}
		
		createNetsFiles();
		createPipFile();
		int result = runEvRouter();
		if(result != 0){
			int lastLineCount = 10;
			List<String> lastLines = FileTools.getLastNLinesFromTextFile(satLogFile, lastLineCount);
			StringBuilder sb = new StringBuilder();
			for(String line : lastLines){
				sb.append("  >> " + line + "\n");
			}
			throw new RuntimeException("\n  ERROR: SAT Routing failed for design '" 
					+ design.getName() + "' with pblock '" + pblock.toString() 
					+ "'. \n  Here are the final "+lastLineCount+" lines of evRouter log ["+satLogFile+"] file:\n\n" + sb.toString());
		}
		
		// Check for errors in evRouter log 
		StringBuilder sb = null;
		for(String line : FileTools.getLinesFromTextFile(satLogFile)){
			if(line.contains("ERROR")){
				sb = new StringBuilder();
			}
			if(sb != null) sb.append("  >> " + line + "\n");
		}
		if(sb != null){
			throw new RuntimeException("\n  ERROR: SAT Routing failed for design '" 
					+ design.getName() + "' with pblock '" + pblock.toString() 
					+ "'. \n  ERROR message lines of evRouter log ["+satLogFile+"] file:\n\n" + sb.toString());
		}
		applyRoutingResult();
	}
	
	/**
	 * @return the verbosity
	 */
	public int getVerbosity() {
		return verbosity;
	}

	/**
	 * @param verbosity the verbosity to set
	 */
	public void setVerbosity(int verbosity) {
		this.verbosity = verbosity;
	}

	/**
	 * @return the maxConflicts
	 */
	public Integer getMaxConflicts() {
		return maxConflicts;
	}

	/**
	 * @param maxConflicts the maxConflicts to set
	 */
	public void setMaxConflicts(Integer maxConflicts) {
		this.maxConflicts = maxConflicts;
	}

	/**
	 * @return the maxPasses
	 */
	public Integer getMaxPasses() {
		return maxPasses;
	}

	/**
	 * @param maxPasses the maxPasses to set
	 */
	public void setMaxPasses(Integer maxPasses) {
		this.maxPasses = maxPasses;
	}

	/**
	 * @return the outputFile
	 */
	public String getOutputFile() {
		return outputFileName;
	}

	/**
	 * @param outputFile the outputFile to set
	 */
	public void setOutputFile(String outputFile) {
		this.outputFileName = outputFile;
	}

	/**
	 * @return the delaysPerSink
	 */
	public int getDelaysPerSink() {
		return delaysPerSink;
	}

	/**
	 * @param delaysPerSink the delaysPerSink to set
	 */
	public void setDelaysPerSink(int delaysPerSink) {
		this.delaysPerSink = delaysPerSink;
	}

	/**
	 * @return the dottyFile
	 */
	public String getDottyFile() {
		return dottyFile;
	}

	/**
	 * @param dottyFile the dottyFile to set
	 */
	public void setDottyFile(String dottyFile) {
		this.dottyFile = dottyFile;
	}

	/**
	 * @return the cstFile
	 */
	public String getCstFile() {
		return cstFile;
	}

	/**
	 * @param cstFile the cstFile to set
	 */
	public void setCstFile(String cstFile) {
		this.cstFile = cstFile;
	}

	/**
	 * @return the regFile
	 */
	public String getRegFile() {
		return regFile;
	}

	/**
	 * @param regFile the regFile to set
	 */
	public void setRegFile(String regFile) {
		this.regFile = regFile;
	}

	/**
	 * @return the chipID
	 */
	public Integer getChipID() {
		return chipID;
	}

	/**
	 * @param chipID the chipID to set
	 */
	public void setChipID(Integer chipID) {
		this.chipID = chipID;
	}

	/**
	 * @return the regionID
	 */
	public Integer getRegionID() {
		return regionID;
	}

	/**
	 * @param regionID the regionID to set
	 */
	public void setRegionID(Integer regionID) {
		this.regionID = regionID;
	}

	/**
	 * @return the localID
	 */
	public Integer getLocalID() {
		return localID;
	}

	/**
	 * @param localID the localID to set
	 */
	public void setLocalID(Integer localID) {
		this.localID = localID;
	}

	/**
	 * @return the edgeGroupsFile
	 */
	public String getEdgeGroupsFile() {
		return edgeGroupsFile;
	}

	/**
	 * @param edgeGroupsFile the edgeGroupsFile to set
	 */
	public void setEdgeGroupsFile(String edgeGroupsFile) {
		this.edgeGroupsFile = edgeGroupsFile;
	}

	/**
	 * @return the netGroupsFile
	 */
	public String getNetGroupsFile() {
		return netGroupsFile;
	}

	/**
	 * @param netGroupsFile the netGroupsFile to set
	 */
	public void setNetGroupsFile(String netGroupsFile) {
		this.netGroupsFile = netGroupsFile;
	}

	/**
	 * @return the vcNetAssignmentFile
	 */
	public String getVcNetAssignmentFile() {
		return vcNetAssignmentFile;
	}

	/**
	 * @param vcNetAssignmentFile the vcNetAssignmentFile to set
	 */
	public void setVcNetAssignmentFile(String vcNetAssignmentFile) {
		this.vcNetAssignmentFile = vcNetAssignmentFile;
	}

	/**
	 * @return the optRouteUtilization
	 */
	public boolean isOptRouteUtilization() {
		return optRouteUtilization;
	}

	/**
	 * @param optRouteUtilization the optRouteUtilization to set
	 */
	public void setOptRouteUtilization(boolean optRouteUtilization) {
		this.optRouteUtilization = optRouteUtilization;
	}

	/**
	 * @return the partitionGraph
	 */
	public boolean isPartitionGraph() {
		return partitionGraph;
	}

	/**
	 * @param partitionGraph the partitionGraph to set
	 */
	public void setPartitionGraph(boolean partitionGraph) {
		this.partitionGraph = partitionGraph;
	}

	/**
	 * @return the pipFile
	 */
	public String getPipFile() {
		return pipFile;
	}

	/**
	 * @param pipFile the pipFile to set
	 */
	public void setPipFile(String pipFile) {
		this.pipFile = pipFile;
	}

	/**
	 * @return the pbFile
	 */
	public String getPbFile() {
		return pbFile;
	}

	/**
	 * @param pbFile the pbFile to set
	 */
	public void setPbFile(String pbFile) {
		this.pbFile = pbFile;
	}

	/**
	 * @return the design
	 */
	public Design getDesign() {
		return design;
	}

	/**
	 * @param design the design to set
	 */
	public void setDesign(Design design) {
		this.design = design;
	}

	/**
	 * @return the pblock
	 */
	public PBlock getPblock() {
		return pblock;
	}

	/**
	 * @param pblock the pblock to set
	 */
	public void setPblock(PBlock pblock) {
		this.pblock = pblock;
	}

	/**
	 * @return the netsToRoute
	 */
	public Collection<Net> getNetsToRoute() {
		return netsToRoute;
	}

	/**
	 * @param netsToRoute the netsToRoute to set
	 */
	public void setNetsToRoute(Collection<Net> netsToRoute) {
		this.netsToRoute = new HashSet<>(netsToRoute);
	}

	/**
	 * @return the excludedNodes
	 */
	public HashSet<Node> getExcludedNodes() {
		return excludedNodes;
	}

	/**
	 * @param excludedNodes the excludedNodes to set
	 */
	public void setExcludedNodes(HashSet<Node> excludedNodes) {
		this.excludedNodes = excludedNodes;
	}

	/**
	 * @return the useWeightsOnNodes
	 */
	public boolean usingWeightsOnNodes() {
		return useWeightsOnNodes;
	}

	/**
	 * @param useWeightsOnNodes the useWeightsOnNodes to set
	 */
	public void setUseWeightsOnNodes(boolean useWeightsOnNodes) {
		this.useWeightsOnNodes = useWeightsOnNodes;
	}	
}
