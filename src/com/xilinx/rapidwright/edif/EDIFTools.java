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
package com.xilinx.rapidwright.edif;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.PinType;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;



/**
 * A collection of utility methods for extracting information from BYU EDIF tool 
 * netlists.
 * Created on: Dec 2, 2015
 */
public class EDIFTools {

	public static String EDIF_HIER_SEP = "/";
	
	public static final String EDIF_LIBRARY_HDI_PRIMITIVES_NAME = "hdi_primitives";

	public static final String MACRO_PRIMITIVES_LIB = "macro_primitives";
	
	public static final String EDIF_LIBRARY_WORK_NAME = "work";
	
	public static final Set<String> edifKeywordSet = 
		new HashSet<>(Arrays.asList(
		    "abs", "acload", "actual", "after", "and", "annotate", "apply", 
		    "arc", "array", "arraymacro", "arrayrelatedinfo", "arraysite",
		    "assign", "atleast", "atmost", "author", "basearray", "becomes",
		    "between", "block", "boolean", "booleandisplay", "booleanmap", 
		    "borderpattern", "borderwidth", "boundingbox","build", "ceiling",
		    "cell", "cellref", "celltype", "change", "circle","collector",
		    "color", "comment", "commentgraphics", "compound", "concat",
		    "connectlocation", "constant", "constraint", "contents",
		    "cornertype","criticality", "currentmap", "curve", "cycle", 
		    "dataorigin", "dcfaninload", "dcfanoutload", "dcmaxfanin", 
		    "dcmaxfanout", "delay", "delta", "derivation", "design", 
		    "designator", "difference", "direction", "display", "divide", 
		    "dominates", "dot", "duration", "e", "edif", "ediflevel",
		    "edifversion", "else", "enclosuredistance", "endtype", "entry", 
		    "equal", "escape", "event", "exactly", "external", "fabricate", 
		    "false", "figure", "figurearea", "figuregroup", 
		    "figuregroupobject", "figuregroupoverride", "figuregroupref", 
		    "figureperimeter", "figurewidth", "fillpattern", "fix", "floor",
		    "follow", "forbiddenevent", "foreach", "formal", "formallist", 
		    "generate", "globalportref", "greaterthan", "gridmap", "if", 
		    "ignore", "includefiguregroup", "increasing", "initial",
		    "instance", "instancebackannotate", "instancegroup", "instancemap",
		    "instanceref", "integer", "integerdisplay", "interface",
		    "interfiguregroupspacing", "intersection", 
		    "intrafiguregroupspacing", "inverse", "isolated", "iterate", 
		    "joined", "justify", "keywordalias", "keyworddefine", 
		    "keyworddisplay", "keywordlevel", "keywordmap", 
		    "keywordparameters", "lessthan", "library", "libraryref", 
		    "listofnets", "listofports", "literal", "loaddelay", "logicassign",
		    "logicinput", "logiclist", "logicmapinput", "logicmapoutput",
		    "logiconeof", "logicoutput", "logicport", "logicref", "logicvalue",
		    "logicwaveform", "maintain", "match", "max", "member", "min",
		    "minomax", "minomaxdisplay", "mnm", "mod", "multiplevalueset", 
		    "mustjoin", "name", "negate", "net", "netbackannotate", 
		    "netbundle", "netdelay", "netgroup", "netmap", "netref", 
		    "nochange", "nonpermutable", "not", "notallowed", "notchspacing",
		    "number", "numberdefinition", "numberdisplay", "offpageconnector",
		    "offsetevent", "openshape", "optional", "or", "orientation",
		    "origin", "overhangdistance", "overlapdistance", "oversize",
		    "owner", "page", "pagesize", "parameter", "parameterassign",
		    "parameterdisplay", "path", "pathdelay", "pathwidth", "permutable",
		    "physicaldesignrule", "plug", "point", "pointdisplay", "pointlist",
		    "pointsubtract", "pointsum", "polygon", "port", "portbackannotate",
		    "portbundle", "portdelay", "portgroup", "portimplementation",
		    "portinstance", "portlist", "portlistalias", "portmap", "portref",
		    "product", "program", "property", "propertydisplay", 
		    "protectionframe", "pt", "rangevector", "rectangle",
		    "rectanglesize", "rename", "resolves", "scale", "scalex", "scaley",
		    "section", "shape", "simulate", "simulationinfo", "singlevalueset",
		    "site", "socket", "socketset", "status", "steady",
		    "strictlyincreasing", "string", "stringdisplay", "strong",
		    "subtract", "sum", "symbol", "symmetry", "table", "tabledefault",
		    "technology", "textheight", "then", "timeinterval", "timestamp",
		    "timing", "transform", "transition", "trigger", "true",
		    "unconstrained", "undefined", "union", "unit", "unused",
		    "userdata", "variable", "version", "view", "viewlist", "viewmap",
		    "viewref", "viewtype", "visible", "voltagemap", "wavevalue",
		    "weak", "weakjoined", "when", "while", "written", "xcoord", "xor",
		    "ycoord"			
		)); 

	public static final String LOGICAL_VCC_NET_NAME = "<const1>";
	public static final String LOGICAL_GND_NET_NAME = "<const0>";

	/** Flag to switch EDIF files to KRYO files to make Java debugging faster  (must run once without debugging mode first, once set to true) */
	public static final boolean EDIF_DEBUG = false;
	
	/**
	 * Helper method to get the part name from an EDIF netlist.
	 * @param edif The EDIF netlist from which to extract the part name.
	 * @return The part name or null if none was found.
	 */
	public static String getPartName(EDIFNetlist edif){
		EDIFName key = new EDIFName("part");
		EDIFPropertyValue p = edif.getDesign().getProperties().get(key);
		if(p == null) {
			key.setName("PART");
			p = edif.getDesign().getProperties().get(key);
			if(p == null) return null;
		}
		return p.getValue();
	}
	
	public static boolean ensureCellInLibraries(EDIFNetlist e, EDIFCell c){
		EDIFCell libCell = e.getCell(c.getName());
		if(libCell == null){
			// Just add it
			return matchCellToEDIFLibrary(e, c).addCell(c) != null;
		}
		return true;
	}
	
	private static EDIFLibrary matchCellToEDIFLibrary(EDIFNetlist n, EDIFCell c){
		String libName = c.isPrimitive() ? EDIF_LIBRARY_HDI_PRIMITIVES_NAME : EDIF_LIBRARY_WORK_NAME;
		EDIFLibrary lib = n.getLibrary(libName);
		if(lib != null) return lib;
		Collection<EDIFLibrary> libs = n.getLibraries();
		if(libs.size() == 0){
			// No libraries exist, so create one
			EDIFLibrary libToAdd = new EDIFLibrary(libName);
			n.addLibrary(libToAdd);
			return libs.iterator().next();
		}
		// Not sure which one to use, so we'll just choose the first
		return libs.iterator().next();
	}
	
	public static void consolidateLibraries(EDIFNetlist edif){
		HashMap<String,EDIFCell> moveToWork = new HashMap<String,EDIFCell>();
		EDIFLibrary workLib = edif.getLibrary(EDIF_LIBRARY_WORK_NAME);
		if(workLib == null){
			workLib = new EDIFLibrary(EDIF_LIBRARY_WORK_NAME);
			edif.addLibrary(workLib);
		}

		for(EDIFLibrary lib : edif.getLibraries()){
			for(EDIFLibrary extLib : lib.getExternallyReferencedLibraries()){
				if(extLib.getName().equals(workLib.getName())){
					for(EDIFCell c : extLib.getCells()){
						EDIFCell duplicate = moveToWork.put(c.toString(), c);
						if(duplicate != null){
							throw new RuntimeException("ERROR: duplicate library cell " + c.toString());
						}
					}
				}
			}
		}
		for(EDIFCell c : moveToWork.values()){
			c.moveToLibrary(workLib);
			workLib.addCell(c);
		}
		EDIFLibrary hdiPrims = edif.getLibrary(EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
		if(hdiPrims == null){
			hdiPrims = new EDIFLibrary(EDIF_LIBRARY_HDI_PRIMITIVES_NAME);
		}
		for(EDIFCell c : workLib.getExternallyReferencedCells()){
			if(c.getLibrary().getName().equals(hdiPrims.getName())){
				if(!hdiPrims.containsCell(c.getName())) {
					c.moveToLibrary(hdiPrims);
					hdiPrims.addCell(c);					
				}
			}
		}
	}
	
	public static void connectPortBus(EDIFCell topCell, EDIFCellInst src, EDIFCellInst snk, 
									  String srcPrefix, String snkPrefix, int width,
									  Map<String,EDIFPortInst> srcPortMap,
									  Map<String,EDIFPortInst> snkPortMap){
		for(int i=0; i < width; i++){
			String suffix = "["+i+"]";
			String outputPortName = srcPrefix + suffix;
			String inputPortName = snkPrefix + suffix; 
			
			EDIFNet net = null;
			net = new EDIFNet("conn_net_" + EDIFTools.makeNameEDIFCompatible(outputPortName), topCell);

			EDIFPortInst outputPortInst = srcPortMap.get(outputPortName);
			EDIFPortInst inputPortInst = snkPortMap.get(inputPortName);
			
			@SuppressWarnings("unused")
			EDIFPortInst outputPort = new EDIFPortInst(outputPortInst.getPort(),net,outputPortInst.getIndex(),src);
			@SuppressWarnings("unused")
			EDIFPortInst inputPort = new EDIFPortInst(inputPortInst.getPort(),net,inputPortInst.getIndex(),snk);
			
			//net.addPortInst(outputPort);
			//net.addPortInst(inputPort);
		}
	}
	
	/**
	 * Creates a map for each EDIF cell type to all its instances, starting with the top cell instance.
	 * @param topCellInst The top cell instance from which to start the search.
	 * @return A map where the key is the cell type and the value is a list of all instances of the type.
	 */
	public static HashMap<EDIFCell, ArrayList<EDIFCellInst>> generateCellInstMap(EDIFCellInst topCellInst){
		HashMap<EDIFCell, ArrayList<EDIFCellInst>> instanceMap = new HashMap<EDIFCell, ArrayList<EDIFCellInst>>();
		
		Queue<EDIFCellInst> q = new LinkedList<EDIFCellInst>();
		q.add(topCellInst);
		
		while(!q.isEmpty()){
			EDIFCellInst eci = q.poll();

			ArrayList<EDIFCellInst> insts = instanceMap.get(eci.getCellType());
			if(insts == null){
				insts = new ArrayList<EDIFCellInst>();
				instanceMap.put(eci.getCellType(), insts);
			}
			insts.add(eci);

			for(EDIFCellInst child : eci.getCellType().getCellInsts()){
				q.add(child);
			}
		}
		
		return instanceMap;
	}
	
	public static EDIFCellInst getEDIFCellInst(EDIFNetlist netlist, String hierarchicalName){
		String[] names = hierarchicalName.split(EDIF_HIER_SEP);
		EDIFCellInst curr = netlist.getTopCellInst();
		
		for(String name : names){
			curr = curr.getCellType().getCellInst(name);
		}
		
		return curr;
	}
	
	/**
	 * Gets the EDIFNet corresponding to the hierarchical name of a net.
	 * @param hierarchicalName The full path name to the het
	 * @return The net or null if none was found
	 */
	public static EDIFNet getNet(EDIFNetlist e, String hierarchicalName){
		String[] parts = hierarchicalName.split(EDIF_HIER_SEP);
		
		EDIFCellInst currInst = e.getTopCellInst();
		for(int i=0; i < parts.length-1; i++){
			currInst = currInst.getCellType().getCellInst(EDIFTools.makeNameEDIFCompatible(parts[i]));
		}
		String netName = makeNameEDIFCompatible(parts[parts.length-1]);
		return currInst.getCellType().getNet(netName);
	}
	
	public static EDIFNet addNet(EDIFNetlist e, String hierarchicalName){
		String[] parts = hierarchicalName.split(EDIF_HIER_SEP);
		EDIFCellInst currInst = e.getTopCellInst();
		for(int i=0; i < parts.length-1; i++){
			currInst = currInst.getCellType().getCellInst(EDIFTools.makeNameEDIFCompatible(parts[i]));
		}
		String netName = makeNameEDIFCompatible(parts[parts.length-1]);
		EDIFNet newEDIFNet = null;

		newEDIFNet = new EDIFNet(netName,currInst.getCellType());
		currInst.getCellType().addNet(newEDIFNet);

		return newEDIFNet;
	}
	
	/**
	 * Tries to make a string EDIF compatible by replacing invalid characters 
	 * with an underscore.  
	 * @param currName Existing name to be made legal
	 * @return A new string that has been legalized to EDIF standards. 
	 */
	public static String makeNameEDIFCompatible(String currName){
		char[] newName = currName.toCharArray();
		int len = lengthOfNameWithoutBus(newName);
		for(int i=0; i < len; i++){
			switch (newName[i]){
			case '[':
			case ']':
			case '<':
			case '>':
			case '.':
			case '/':
			case '\\':
			case '(':
			case ')':
			case '{':
			case '}':
			case '?':
			case ';':
			case '\'':
			case '`':
			case ':':
			case '\"':
			case '!':
			case '|':
			case '~':
			case '*':
			case '^':
			case '=':
			case '-':
			case '+':
			case ',':
			case '%':
			case '#':
			case '@':
			case '$':
			case '&':
				newName[i] = '_';
			default:
				// Keep the same
			}
		}
		if(newName[0] == '_' || Character.isDigit(newName[0])) return "&" + new String(newName,0,len); 
		return new String(newName,0,len);
	}

	/**
	 * Strips off bracket index in a bussed name (ex: "data[0]" --> "data").
	 * @param name Bracketed bussed name.
	 * @return Name of bus with brackets removed
	 */
	public static String getRootBusName(String name){
		int bracket = name.lastIndexOf('[');
		if(bracket == -1) return name;
		return name.substring(0, bracket);
	}
	
	public static String getHierarchicalRootFromPinName(String s){
		int idx = s.lastIndexOf(EDIF_HIER_SEP);
		if(idx < 0) return "";
		return s.substring(0, idx);
	}
	
	/**
	 * Determines if the char[] ends with the pattern [#:#] where # are positive
	 * bus values (e.g., [7:0]) and then returns the length of the string without
	 * the bus suffix (if it exists).  If the name does not end with the bus 
	 * pattern, it returns the original length of the char[].
	 * @param name
	 * @return The length of the string 
	 */
	public static int lengthOfNameWithoutBus(char[] name){
		int len = name.length;
		int i = len-1;
		if(name[i--] != ']') return len;
		while(Character.isDigit(name[i])){
			i--;
		}
		if(name[i--] != ':') return len;
		while(Character.isDigit(name[i])){
			i--;
		}
		if(name[i] != '[') return len;
		return i;
	}
	
	public static int getPortIndexFromName(String name){
		int lengthRootName = name.lastIndexOf('[');
		String tmp = name.substring(lengthRootName+1, name.length()-1);
		return Integer.parseInt(tmp);
	}
	
	/**
	 * 
	 * @param portName
	 * @return
	 */
	public static int getWidthOfPortFromName(String name){
		int lengthRootName = lengthOfNameWithoutBus(name.toCharArray());
		if(lengthRootName == name.length()) return 1;
		int colonIdx = -1;
		int leftBracket = -1;
		for(int i=name.length()-3; i >= 0; i--){
			char c = name.charAt(i);
			if(c == ':') colonIdx = i;
			else if(c == '[') {
				leftBracket = i;
				break;
			}
		}
		if(colonIdx == -1 || leftBracket == -1){
			throw new RuntimeException("ERROR: Interpreting port " + name + ", couldn't identify indicies.");
		}
		
		int left = Integer.parseInt(name.substring(leftBracket+1, colonIdx));
		int right = Integer.parseInt(name.substring(colonIdx+1, name.length()-1));
		return Math.abs(left - right) + 1;
	}
	
	/**
	 * Specialized function to connect a debug port within an EDIF netlist.  
	 * @param topPortNet The top-level net that connects to the debug core's input port.
	 * @param routedNetName The name of the routed net who's source is the net we need to connect to
	 * @param newPortName The name of the port to be added at each level of hierarchy
	 * @param parentInst The instance where topPortNet resides
	 * @param instMap The map of the design created by {@link EDIFTools#generateCellInstMap(EDIFCellInst)} 
	 */
	public static void connectDebugProbe(EDIFNet topPortNet, String routedNetName, String newPortName, 
			EDIFHierCellInst parentInst, EDIFNetlist n, HashMap<EDIFCell, ArrayList<EDIFCellInst>> instMap){
		EDIFNet currNet = topPortNet;
		String currParentName = parentInst.getHierarchicalInstName();
		EDIFCellInst currInst = parentInst.getInst();
		// Need to check if we need to move up levels of hierarchy before we move down
		while(!routedNetName.startsWith(currParentName)){
			EDIFPort port = currInst.getCellType().createPort(newPortName, EDIFDirection.INPUT, 1);
			currNet.createPortInst(port);
			EDIFCellInst prevInst = currInst;
			currParentName = currParentName.substring(0, currParentName.lastIndexOf(EDIFTools.EDIF_HIER_SEP));
			currInst = n.getCellInstFromHierName(currParentName);
			currNet = new EDIFNet(newPortName, currInst.getCellType());
			currNet.createPortInst(newPortName, prevInst);
		}
		
		
		
		
		String[] parts = routedNetName.split(EDIFTools.EDIF_HIER_SEP);
		int idx = 0;
		if(!n.getTopCell().equals(currInst.getCellType())){
			while( idx < parts.length){
				if(parts[idx++].equals(currInst.getName())){
					break;
				}
			}
			if(idx == parts.length){
				throw new RuntimeException("ERROR: Couldn't find instance " +
					currInst.getName() + " from routed net name " + routedNetName);
			}
		}
		
		for(int i=idx; i <= parts.length-2; i++){
			currInst = currInst.getCellType().getCellInst(parts[i]);
			EDIFCell type = currInst.getCellType();
			if(instMap != null && instMap.get(type).size() > 1){
				// TODO Replicate cell type and create new
			}
			EDIFPort newPort = currInst.getCellType().createPort(newPortName, EDIFDirection.OUTPUT, 1);
			EDIFPortInst portInst = new EDIFPortInst(newPort, currNet, currInst);
			currNet.addPortInst(portInst);
			if(i == parts.length-2){
				EDIFNet targetNet = currInst.getCellType().getNet(parts[parts.length-1]);
				targetNet.createPortInst(newPort);
			}else{
				EDIFNet childNet = new EDIFNet(topPortNet.getName(), currInst.getCellType());			
				childNet.createPortInst(newPort);
				currNet = childNet;
			}
		}
	}
	
	/**
	 * Specialized function to add enable a debug probe connection.  Adds the external port on the debug core and 
	 * a new net at the top level that can be used to as a sink to probe a user signal.  Used in conjunction with
	 * EDIFTools.connectDebugProbe(...) 
	 * @param newDebugNetName Name of the debug net to add at the top level
	 * @param topCell Name of the cell where the net and port should exists
	 * @param currPort The existing port on the debug core
	 * @param debugCore The instance of the debug core already within the EDIF design
	 * @return The newly created net with the debug port attached.
	 */
	public static EDIFNet addDebugPortAndNet(String newDebugNetName, EDIFCell topCell, EDIFPortInst currPort, EDIFCellInst debugCore){
		// Create a new net for the port connection
		EDIFNet net = topCell.createNet(newDebugNetName);
		addDebugPort(net,topCell,currPort,debugCore);
		return net;
	}
	
	/**
	 * Adds the debug port to the debug core in the EDIF netlist
	 * @param net The net to which the port should connect
	 * @param topCell The top cell which has the debug core as a subcell 
	 * @param currPort The current port to add
	 * @param debugCore The debug core
	 * @return The newly created {@link EDIFPortInst}
	 */
	public static EDIFPortInst addDebugPort(EDIFNet net, EDIFCell topCell, EDIFPortInst currPort, EDIFCellInst debugCore){
		// Add the actual external port on the debug core
		EDIFPortInst probeInput = new EDIFPortInst(currPort.getPort(), net, debugCore);
		net.addPortInst(probeInput);

		
		if(topCell.getNet(net) == null){
			topCell.addNet(net);				
		}
		return probeInput;
	}

	/**
	 * Creates and/or gets the static net (GND/VCC) in the specified cell.  
	 * @param type The type of net to get or create
	 * @param cell The cell that should have a static net
	 * @param netlist The netlist of interest
	 * @return An existing or newly created static net for the cell provided.
	 */
	public static EDIFNet getStaticNet(NetType type, EDIFCell cell, EDIFNetlist netlist){
		return getStaticNet(type, cell, netlist, type == NetType.GND ? LOGICAL_GND_NET_NAME : LOGICAL_VCC_NET_NAME);		
	}
	
	/**
	 * Creates and/or gets the static net (GND/VCC) in the specified cell.  
	 * @param type The type of net to get or create
	 * @param cell The cell that should have a static net
	 * @param netlist The netlist of interest
	 * @param netName If the net is to be created, what is the desired name
	 * @return An existing or newly created static net for the cell provided.
	 */
	public static EDIFNet getStaticNet(NetType type, EDIFCell cell, EDIFNetlist netlist, String netName){
		String staticTypeName = type.toString();
		EDIFCellInst staticInst = cell.getCellInst(staticTypeName);
		String portName = staticTypeName.equals("GND") ? "G" : "P";
		if(staticInst == null){
			EDIFCell staticSrc = netlist.getLibrary(EDIF_LIBRARY_HDI_PRIMITIVES_NAME).getCell(staticTypeName);
			if(staticSrc == null){
				staticSrc = new EDIFCell(netlist.getLibrary(EDIF_LIBRARY_HDI_PRIMITIVES_NAME), staticTypeName);
				staticSrc.addPort(new EDIFPort(portName, EDIFDirection.OUTPUT,1));
			}
			staticInst = new EDIFCellInst(staticTypeName, staticSrc, cell);
			cell.addCellInst(staticInst);
		}
		EDIFPortInst outputPortInst = staticInst.getPortInstMap().get(portName);
		if(outputPortInst == null){
			outputPortInst = new EDIFPortInst(staticInst.getPort(portName),null,staticInst);
		}
		EDIFNet staticNet = outputPortInst.getNet();
		
		if(staticNet == null){
			String debugNetName = netName;
			staticNet = cell.getNet(debugNetName);
			if(staticNet == null){
				staticNet = cell.createNet(debugNetName);
			}
			staticNet.addPortInst(outputPortInst);
		}
		return staticNet;
	}
	
	public static void main(String[] args) {
		CodePerfTracker t = new CodePerfTracker("EDIF Read", true);
		String edifFileName = args[0];
		EDIFNetlist edif;
		
		if(EDIFTools.EDIF_DEBUG && FileTools.isFileNewer(edifFileName + ".dat", edifFileName)){
			t.start("Read EDIF from Kryo");
			edif = FileTools.readObjectFromKryoFile(edifFileName + ".dat", EDIFNetlist.class);
			t.stop();
		}else{
			t.start("Read EDIF from ASCII EDIF file");
			edif = EDIFTools.loadEDIFFile(edifFileName);
			t.stop(); 
			if(!(new File(edifFileName + ".dat").exists()) || FileTools.isFileNewer(edifFileName, edifFileName + ".dat") ){
				t.start("Write EDIF Kryo (.dat) file");
				if(EDIFTools.EDIF_DEBUG) FileTools.writeObjectToKryoFile(edifFileName + ".dat", edif);
				t.stop();
			}
			
		}
		t.start("Write EDIF file");
		EDIFTools.writeEDIFFile(edifFileName.replace(".edf", "_byu.edf"), edif, "partname");
		t.stop().printSummary();
	}

	public static EDIFNetlist loadEDIFFile(String fileName){
		EDIFParser p = null;
		try {
			p = new EDIFParser(fileName);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			throw new RuntimeException("ERROR: Couldn't read file : " + fileName);
		}
		return p.parseEDIFNetlist();
	}

	public static void ensureCorrectPartInEDIF(EDIFNetlist edif, String partName){
		Map<EDIFName, EDIFPropertyValue> propMap = edif.getDesign().getProperties();
		if(propMap == null){
			edif.getDesign().addProperty("PART", partName);
			return;
		}
		boolean modified = false;
		for(Entry<EDIFName,EDIFPropertyValue> p : propMap.entrySet()){
			String val = p.getValue().toString();
			if(val.contains("intex") || val.contains("irtex")){
				EDIFPropertyValue v = new EDIFPropertyValue();
				v.setType(EDIFValueType.STRING);
				v.setValue(partName);
				p.setValue(v);
				modified = true;
				break;
			}
		}
		if(!modified){
			edif.getDesign().addProperty("PART", partName);
		}
	}

	public static EDIFNetlist readEdifFile(String edifFileName){
		EDIFNetlist edif;
		if(EDIFTools.EDIF_DEBUG && FileTools.isFileNewer(edifFileName + ".dat", edifFileName)){
			edif = FileTools.readObjectFromKryoFile(edifFileName + ".dat", EDIFNetlist.class);
		}else{
			edif = loadEDIFFile(edifFileName);
			if(!(new File(edifFileName + ".dat").exists()) || FileTools.isFileNewer(edifFileName, edifFileName + ".dat") ){
				if(EDIFTools.EDIF_DEBUG) FileTools.writeObjectToKryoFile(edifFileName + ".dat", edif);			
			}
		}
		return edif;
	}

	public static void writeEDIFFile(String fileName, EDIFNetlist edif, String partName){
		ensureCorrectPartInEDIF(edif, partName);
		edif.exportEDIF(fileName);
	}

	public static void writeEDIFFile(OutputStream out, EDIFNetlist edif, String partName){
		String hiddenEDIFFileName = ".temp_edif_" + FileTools.getUniqueProcessAndHostID() + ".edf";
		writeEDIFFile(hiddenEDIFFileName, edif, partName);
		Path path = FileSystems.getDefault().getPath(hiddenEDIFFileName);
		try {
			Files.copy(path, out);
			Files.delete(path);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public static EDIFNetlist readEdifFromDcpFile(String dcpFileName){
		return EDIFTools.loadEDIFFile(FileTools.getInputStreamFromZipOrDcpFile(dcpFileName, ".edf"));
	}

	public static EDIFNetlist loadEDIFFile(InputStream is){
		EDIFParser p = new EDIFParser(is);
		return p.parseEDIFNetlist();
	}
	
	public static EDIFNetlist createNewNetlist(String topName){
		return createNewNetlist(topName, true);
	}
	
	public static EDIFNetlist createNewNetlist(String topName, boolean addINV){
		EDIFNetlist n = new EDIFNetlist(topName);
		n.generateBuildComments();
		EDIFLibrary primLib = n.addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_HDI_PRIMITIVES_NAME));
		if(addINV) primLib.addCell(Design.getUnisimCell(Unisim.INV));
		EDIFLibrary workLib = n.addLibrary(new EDIFLibrary(EDIFTools.EDIF_LIBRARY_WORK_NAME));
		EDIFCell top = new EDIFCell(workLib, topName);
		EDIFDesign eDesign = new EDIFDesign(topName);
		n.setDesign(eDesign);
		eDesign.setTopCell(top);
		
		return n;
	}
	
	public static int[] bitBlastBus(String busSuffix){
		int colon = busSuffix.indexOf(':');
		int left = Integer.parseInt(busSuffix.substring(1,colon));
		int right = Integer.parseInt(busSuffix.substring(colon+1,busSuffix.length()-1));
		int inc = left > right ? -1 : 1;
		int[] idxs = new int[Math.abs(left-right) + 1];
		int ii = left;
		for(int i=0; i < idxs.length; i++ ){
			idxs[i] = ii;
			ii += inc;
		}
		return idxs;
	}
	
	/**
	 * Bit blasts the shorthand bus name (ex: "data[0:2]" --> ["data0", "data1", "data2"])
	 * @param bussedSignal The bussed name with bracketed indices
	 * @return A fully expanded array of strings names or the original name if no brackets are present
	 */
	public static String[] bitBlast(String bussedSignal){
		int lastLeftBracket = bussedSignal.lastIndexOf('[');
		int colon = bussedSignal.lastIndexOf(':');
		if(lastLeftBracket == -1 || colon == -1) return new String[]{bussedSignal};
		int[] indices = EDIFTools.bitBlastBus(bussedSignal.substring(lastLeftBracket));
		String[] signals = new String[indices.length];
		String base = bussedSignal.substring(0, lastLeftBracket);
		for(int i=0; i < indices.length; i++){
			signals[i] = base +"[" + indices[i] + "]";
		}
		return signals;
	}

	/**
	 * Creates a top level port (if it doesn't already exist) and an array of corresponding {@link EDIFPortInst}s 
	 * to be used to connect to an array of EDIFNets
	 * @param parent Top level cell to which the port should be created
	 * @param name Root name of the port to create and corresponding {@link EDIFPortInst}s (no brackets) 
	 * @param dir Direction of the port.
	 * @param width Width of the port
	 * @return An array [0:width-1] of port refs.
	 */
	public static EDIFPortInst[] createPortInsts(EDIFCell parent, String name, EDIFDirection dir, int width){
		String portName = name +"[" + (width-1) +":0]";
		EDIFPort port = parent.createPort(portName, dir, width);
		EDIFPortInst[] portInsts = new EDIFPortInst[width];
		for(int i=0; i < width; i++){
			portInsts[i] = new EDIFPortInst(port,null,i);
		}
		return portInsts;
	}

	/**
	 * Creates an array of {@link EDIFPortInst}s from an existing port on an {@link EDIFCellInst}.
	 * @param name Root name of the port (no brackets) 
	 * @param dir Direction of the port
	 * @param width Width of the port
	 * @param eci The existing cell instance with the port to create the {@link EDIFPortInst}s.
	 * @return The newly created array of {@link EDIFPortInst}s corresponding the port and {@link EDIFCellInst}.
	 */
	public static EDIFPortInst[] createPortInsts(String name, EDIFDirection dir, int width, EDIFCellInst eci){
		EDIFPort resultPort = eci.getPort(name);
		EDIFPortInst[] resultPortInsts = new EDIFPortInst[width];
		for(int i=0; i < width; i++){
			resultPortInsts[i] = new EDIFPortInst(resultPort,null,i,eci);
		}
		return resultPortInsts;
	}
	
	
	/**
	 * Traverse all connected EDIFNets to find all leaf sink portrefs as part of the physical net. 
	 * @param startingInput Search begins at this portref and searches below in higher levels of hierarchy.  Doesn't search backwards.
	 * @return The list of all leaf cell pin (portrefs) found below the provided starting input pin. Or null if the portref provided is not an input.
	 */
	public static ArrayList<EDIFHierPortInst> findSinks(EDIFHierPortInst startingInput){
		if(!startingInput.isInput()) return null;
		Queue<EDIFHierPortInst> q = new LinkedList<>();
		q.add(startingInput);
		ArrayList<EDIFHierPortInst> sinks = new ArrayList<>();
		while(!q.isEmpty()){
			EDIFHierPortInst curr = q.poll();
			EDIFNet internalNet = curr.getPortInst().getCellInst().getCellType().getInternalNet(curr.getPortInst());
			for(EDIFPortInst p : internalNet.getPortInsts()){
				if(!p.isInput()) continue;
				if(p.getCellInst() == null) continue;
				EDIFHierPortInst newPortInst = new EDIFHierPortInst(curr.getFullHierarchicalInstName(), p);
				if(p.getCellInst().getCellType().isPrimitive()){					
					sinks.add(newPortInst);
				}else {
					q.add(newPortInst);
				}
			}
		}
		
		return sinks;
	}

	/**
	 * Creates (or gets if it already exists) a top level port instance.
	 * @param d Current design with the netlist
	 * @param name Name of the top level port
	 * @param dir The desired port directionality
	 * @return The top level port instance (created or retrieved) from the top cell.
	 */
	public static EDIFPortInst createTopLevelPortInst(Design d, String name, PinType dir){
		EDIFNetlist n = d.getNetlist();
		EDIFPort port = n.getTopCell().getPort(name);
		if(port == null && name.contains("[") && name.contains("]")){
			// check if this is a part of a bus
			port = n.getTopCell().getPort(getRootBusName(name));
		}
		if(port == null){
			port = d.getTopEDIFCell().createPort(name, EDIFDirection.getDir(dir), 1);
		}
		EDIFPortInst pr = n.getTopCellInst().getPortInst(name);
		if(pr == null){
			int idx = -1;
			if(port.getWidth() > 1){
				idx = getPortIndexFromName(name);
				if(port.isLittleEndian()) idx = (port.getWidth()-1) - idx;
			}
			pr = new EDIFPortInst(port, null, idx);
		}
		return pr;
	}

	/**
	 * Creates (or gets) a top level port by the specified name.  It will
	 * also ensure that the port is connected to the specified {@link EDIFCellInst} 
	 * which should be part of an IBUF or OBUF.
	 * @param d Current design with netlist
	 * @param name Name of the port (and net if none exists)
	 * @param i IBUF (or INBUF in UltraScale devices) or OBUF instances
	 * @param dir Direction of the pin on the IBUF/INBUF/OBUF instance to connect. 
	 */
	public static void addTopLevelPort(Design d, String name, EDIFCellInst i, PinType dir){
		EDIFPortInst pr = createTopLevelPortInst(d, name, dir);
		EDIFNet portNet = pr.getNet();
		if(portNet == null){
			portNet = d.getTopEDIFCell().getNet(name);
		}
		if(portNet == null){
			portNet = d.getTopEDIFCell().createNet(name);
		}
		String portName = dir == PinType.IN ? "I" : "O";
		EDIFPortInst bufPortInst = portNet.getPortInst(i.getName() + EDIFTools.EDIF_HIER_SEP + portName);
		if(bufPortInst == null)
			portNet.createPortInst(portName, i);
		if(pr.getNet() == null) 
			portNet.addPortInst(pr);
	}
}
