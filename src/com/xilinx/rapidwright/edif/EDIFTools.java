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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
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

	public static final String EDIF_PART_PROP = "PART";
	
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

	public static final String LOAD_TCL_SUFFIX = "_load.tcl";
	
	public static int UNIQUE_COUNT = 0;

	/**
	 * Flag to set a feature where any .edf file that is attempted to be loaded will check if an
	 * existing binary EDIF has already been generated and will load that instead (faster).  This 
	 * will also enable generation of binary EDIF files after a successful EDIF file loading to be
	 * used on the next load.
	 */
	public static final boolean RW_ENABLE_EDIF_BINARY_CACHING = 
	        System.getenv("RW_ENABLE_EDIF_BINARY_CACHING") != null;
	
	private static String getUniqueSuffix() {
	    return "_rw_created" + UNIQUE_COUNT++;
	}
	
	/**
	 * Helper method to get the part name from an EDIF netlist.
	 * @param edif The EDIF netlist from which to extract the part name.
	 * @return The part name or null if none was found.
	 */
	public static String getPartName(EDIFNetlist edif){
		EDIFName key = new EDIFName(EDIF_PART_PROP);
		EDIFPropertyValue p = edif.getDesign().getProperties().get(key);
		if(p == null) {
			key.setName(EDIF_PART_PROP.toLowerCase());
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
									  String srcPrefix, String snkPrefix, int width){
		for(int i=0; i < width; i++){
			String suffix = "["+i+"]";
			String outputPortName = srcPrefix + suffix;
			String inputPortName = snkPrefix + suffix; 
			
			EDIFNet net = null;
			net = new EDIFNet("conn_net_" + EDIFTools.makeNameEDIFCompatible(outputPortName), topCell);

			EDIFPortInst outputPortInst = src.getPortInst(outputPortName);
			EDIFPortInst inputPortInst = snk.getPortInst(inputPortName);
			
			@SuppressWarnings("unused")
			EDIFPortInst outputPort = new EDIFPortInst(outputPortInst.getPort(),net,outputPortInst.getIndex(),src);
			@SuppressWarnings("unused")
			EDIFPortInst inputPort = new EDIFPortInst(inputPortInst.getPort(),net,inputPortInst.getIndex(),snk);
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
			EDIFCellInst next = curr.getCellType().getCellInst(name);
			if (next == null) {
				throw new NullPointerException("Did not find cell "+name+" in "+curr+" while trying to resolve hierarchical name "+hierarchicalName);
			}
			curr = next;
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
	 * Strips off bracket index in a bussed name (ex: {@code "data[0]" --> "data"}).
	 * @param name Bracketed bussed name.
	 * @return Name of bus with brackets removed
	 */
	public static String getRootBusName(String name){
		int bracket = name.lastIndexOf('[');
		if(bracket == -1) return name;
		return name.substring(0, bracket);
	}

	/**
	 * Get the previous level of hierarchy
	 *
	 * This cannot handle slashes within names and is therefore deprecated. Use {@link EDIFHierCellInst} instead.
	 */
	@Deprecated
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
	 * @param name
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

	private static EDIFNet createUniqueNet(EDIFCell parentCell, String netName) {
	    if(parentCell.getNet(netName) != null) {
            netName += getUniqueSuffix();
        }
        return new EDIFNet(netName, parentCell);
	}
	
	/**
	 * Connects two existing logical port insts together by creating new ports and nets on all cells
	 * instantiated between their levels of hierarchy.  It assumes the netlist cells involved only
	 * have one instance (does not differentiate cells when creating the ports).  This assumption 
	 * can be enforce by calling {@link #flattenNetlist(Design)}. If the src or snk
	 * port insts do net have nets connected, it will create them and connect them in their parent
	 * cell definition.
	 * @param src The logical port inst driver or source  
	 * @param snk The logical port inst sink
	 * @param netlist The EDIF netlist of the design
	 * @param newName A unique name to be used in creating the ports and nets
	 */
	public static void connectPortInstsThruHier(EDIFHierPortInst src, EDIFHierPortInst snk, 
	        EDIFNetlist netlist, String newName) {
	    EDIFHierCellInst commonAncestor = 
	            src.getHierarchicalInst().getCommonAncestor(snk.getHierarchicalInst());
	    EDIFHierPortInst finalSrc = src;
	    EDIFHierPortInst finalSnk = snk;
	    // Trace existing connections or make new connections between src and snk to the common 
	    // ancestor.   
	    for(EDIFHierPortInst hierPortInst : new EDIFHierPortInst[] {src, snk}) {
	        EDIFHierCellInst hierParentInst = hierPortInst.getHierarchicalInst();
	        EDIFNet currNet = hierPortInst.getNet();
	        if(currNet == null && !(hierParentInst.equals(commonAncestor) && hierPortInst == snk)) {
	            currNet = createUniqueNet(hierParentInst.getCellType(), newName);
	            currNet.addPortInst(hierPortInst.getPortInst());
	        }

	        while(hierParentInst.getInst() != commonAncestor.getInst()) {
	            EDIFPortInst exitPath = currNet.getTopLevelPortInst();
	            EDIFPortInst outerPortInst = null;
	            if(exitPath != null) {
	                // Follow existing connection to parent instance
	                outerPortInst = hierParentInst.getInst().getPortInst(exitPath.getName());
	                if(outerPortInst == null) {
	                    outerPortInst = new EDIFPortInst(exitPath.getPort(), null,
	                            exitPath.getIndex(), hierParentInst.getInst());
	                }
	                hierParentInst = hierParentInst.getParent();
	                currNet = outerPortInst.getNet();
	                if(currNet == null) {
	                    currNet = createUniqueNet(hierParentInst.getCellType(), newName);
	                    currNet.addPortInst(outerPortInst);
	                }
	            }else {
	                // no port to the parent cell above exists, create one
	                EDIFCell cellType = hierParentInst.getCellType();
	                String newPortName = newName;
	                if(cellType.getPort(newPortName) != null) {
	                    newPortName += getUniqueSuffix(); 
	                }
	                EDIFPort port = cellType.createPort(newPortName, 
	                        hierPortInst == src ? EDIFDirection.OUTPUT : EDIFDirection.INPUT, 1);

	                currNet.createPortInst(port);
	                EDIFCellInst prevInst = hierParentInst.getInst();
	                hierParentInst = hierParentInst.getParent();
	                if(hierParentInst.equals(commonAncestor) && hierPortInst == snk) {
	                    // We don't need to create another net, just connect to the src's net
	                    currNet = finalSrc.getNet();
	                } else {
	                    currNet = createUniqueNet(hierParentInst.getCellType(), newName);
	                }
	                outerPortInst = currNet.createPortInst(port, prevInst);
	            }
	            EDIFHierPortInst currPortInst = new EDIFHierPortInst(hierParentInst, outerPortInst);
	            if(hierPortInst == src) {
	                finalSrc = currPortInst;
	            } else {
	                finalSnk = currPortInst;
	            }
	        }
	    }
	    // Disconnect sink from existing net if connected
	    EDIFNet snkNet = finalSnk.getNet();
	    if(snkNet != null) {
	        snkNet.removePortInst(finalSnk.getPortInst());
	    }
	    // Make final connection in the common ancestor instance
	    finalSrc.getNet().addPortInst(finalSnk.getPortInst());   
	}

	/**
	 * Specialized function to connect a debug port within an EDIF netlist.  
	 * @param topPortNet The top-level net that connects to the debug core's input port.
	 * @param routedNetName The name of the routed net who's source is the net we need to connect to
	 * @param newPortName The name of the port to be added at each level of hierarchy
	 * @param parentInst The instance where topPortNet resides
	 * @param netlist The current netlist 
	 * @param instMap The map of the design created by {@link EDIFTools#generateCellInstMap(EDIFCellInst)} 
	 */
	public static void connectDebugProbe(EDIFNet topPortNet, String routedNetName, String newPortName, 
			EDIFHierCellInst parentInst, EDIFNetlist netlist, HashMap<EDIFCell, ArrayList<EDIFCellInst>> instMap){
		EDIFNet currNet = topPortNet;
		String currParentName = parentInst.getParent().getFullHierarchicalInstName();
		EDIFCellInst currInst = parentInst.getInst();
		// Need to check if we need to move up levels of hierarchy before we move down
		while(!routedNetName.startsWith(currParentName)){
			EDIFPort port = currInst.getCellType().createPort(newPortName, EDIFDirection.INPUT, 1);
			currNet.createPortInst(port);
			EDIFCellInst prevInst = currInst;
			currParentName = currParentName.substring(0, currParentName.lastIndexOf(EDIFTools.EDIF_HIER_SEP));
			currInst = netlist.getCellInstFromHierName(currParentName);
			currNet = new EDIFNet(newPortName, currInst.getCellType());
			currNet.createPortInst(newPortName, prevInst);
		}
		
		String[] parts = routedNetName.split(EDIFTools.EDIF_HIER_SEP);
		int idx = 0;
		if(!netlist.getTopCell().equals(currInst.getCellType())){
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
		EDIFPortInst outputPortInst = staticInst.getPortInst(portName);
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
		
		t.start("Read EDIF from ASCII EDIF file");
		edif = EDIFTools.loadEDIFFile(edifFileName);
		t.stop(); 
		t.start("Write EDIF file");
		EDIFTools.writeEDIFFile(edifFileName.replace(".edf", "_out.edf"), edif, "partname");
		t.stop().printSummary();
	}

	public static EDIFNetlist loadEDIFFile(Path fileName) {
		try (EDIFParser p = new EDIFParser(fileName)) {
			return p.parseEDIFNetlist();
		} catch (IOException e) {
			throw new UncheckedIOException("ERROR: Couldn't read file : " + fileName, e);
		}
	}

	public static EDIFNetlist loadEDIFFile(String fileName){
		return loadEDIFFile(Paths.get(fileName));
	}

	public static void ensureCorrectPartInEDIF(EDIFNetlist edif, String partName){
		Map<EDIFName, EDIFPropertyValue> propMap = edif.getDesign().getProperties();
		if(propMap == null){
			edif.getDesign().addProperty(EDIF_PART_PROP, partName);
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
			edif.getDesign().addProperty(EDIF_PART_PROP, partName);
		}
	}

	public static EDIFNetlist readEdifFile(Path edifFileName) {
	    Path parent = edifFileName.getParent();
	    if(parent == null) {
	        parent = Paths.get(System.getProperty("user.dir"));
	    }
	    if(RW_ENABLE_EDIF_BINARY_CACHING) {
	        Path bedif = parent.resolve(
	                        edifFileName.getFileName().toString().replace(".edf", ".bedf"));
	        if(Files.exists(bedif) && FileTools.isFileNewer(bedif, edifFileName)) {
	            EDIFNetlist netlist = null;
	            try {
	                netlist = BinaryEDIFReader.readBinaryEDIF(bedif);
	                return netlist;
	            } catch(Exception e) {
	                System.out.println("WARNING: Unable to read Binary EDIF: " + bedif.toString()
	                        + ", falling back to reading EDIF: " + edifFileName.toString());
	            }
	        }
	    }
		EDIFNetlist edif;
		File edifFile = edifFileName.toFile();
		String edifDirectoryName = parent.toAbsolutePath().toString();
		if(edifDirectoryName == null) {
			try {
				File canEdifFile = edifFile.getCanonicalFile();
				if(canEdifFile != null) {
					edifDirectoryName = canEdifFile.getParent();
				}
			} catch (IOException e) {
				// Unable to determine EDIF source directory - not sure if 
				// this is worth throwing an error as we are only checking for EDN files
				System.err.println("WARNING: Could not determine source directory for EDIF. "
					+ "If it contained encrypted cells \n(present as .edn files), they will not "
					+ "be passed to resulting DCP load script.");
			}
		}
		edif = loadEDIFFile(edifFileName);
		if(edifDirectoryName != null) {
			File origDir = new File(edifDirectoryName);
			edif.setOrigDirectory(edifDirectoryName);
			String[] ednFiles = origDir.list(FileTools.getEDNFilenameFilter()); 
			if(ednFiles != null && ednFiles.length > 0) {
				edifDirectoryName = edifDirectoryName + File.separator;
				for(int i=0; i < ednFiles.length; i++) {
					ednFiles[i] = edifDirectoryName + ednFiles[i];
				}
				
			}
			edif.setEncryptedCells(new ArrayList<>(Arrays.asList(ednFiles)));
		}
		if(RW_ENABLE_EDIF_BINARY_CACHING) {
		    Path bedif = parent.resolve(
                    edifFileName.getFileName().toString().replace(".edf", ".bedf"));
		    try {
		        BinaryEDIFWriter.writeBinaryEDIF(bedif, edif);
		    }
		    catch (Exception e) {
		        System.out.println("INFO: Unable to write Binary EDIF file: " + bedif.toString());
		    }
		}
		return edif;
	}

	public static EDIFNetlist readEdifFile(String edifFileName){
		return readEdifFile(Paths.get(edifFileName));
	}

	public static void writeEDIFFile(Path fileName, EDIFNetlist edif, String partName){
		ensureCorrectPartInEDIF(edif, partName);
		edif.exportEDIF(fileName);
	}

	public static void writeEDIFFile(String fileName, EDIFNetlist edif, String partName){
		writeEDIFFile(Paths.get(fileName), edif, partName);
	}

	public static void writeEDIFFile(OutputStream out, EDIFNetlist edif, String partName) {
		writeEDIFFile(out, (Path) null, edif, partName);
	}

	
	/**
	 * Write out EDIF to a stream.  Also checks if netlist has potential encrypted cells and 
	 * creates a Tcl script to help re-import design into Vivado intact.
	 * @param out The output stream
	 * @param dcpFileName The name of the DCP file associated with this netlist
	 * @param edif The netlist of the design 
	 * @param partName The target part for this design
	 */
	public static void writeEDIFFile(OutputStream out, Path dcpFileName, EDIFNetlist edif,
										String partName){
		try {
			ensureCorrectPartInEDIF(edif, partName);
			edif.exportEDIF(out);
			if(dcpFileName != null && edif.getEncryptedCells() != null) {
				if(edif.getEncryptedCells().size() > 0) {
					writeTclLoadScriptForPartialEncryptedDesigns(edif, dcpFileName, partName);
				}				
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * Write out EDIF to a stream.  Also checks if netlist has potential encrypted cells and
	 * creates a Tcl script to help re-import design into Vivado intact.
	 * @param out The output stream
	 * @param dcpFileName The name of the DCP file associated with this netlist
	 * @param edif The netlist of the design
	 * @param partName The target part for this design
	 */
	public static void writeEDIFFile(OutputStream out, String dcpFileName, EDIFNetlist edif,
									 String partName){
		if (dcpFileName == null) {
			writeEDIFFile(out, (Path) null, edif, partName);
		} else {
			writeEDIFFile(out, Paths.get(dcpFileName), edif, partName);
		}
	}
	
	public static void writeTclLoadScriptForPartialEncryptedDesigns(EDIFNetlist edif, 
															Path dcpFileName, String partName) {
		ArrayList<String> lines = new ArrayList<String>();
		for(String cellName : edif.getEncryptedCells()) {
			lines.add("read_edif " + cellName);
		}
		Path pathDCPFileName = dcpFileName.toAbsolutePath();
		
		lines.add("read_checkpoint " + pathDCPFileName);
		lines.add("set_property top "+edif.getName()+" [current_fileset]");
		lines.add("link_design -part " + partName);
		Path tclFileName = FileTools.replaceExtension(pathDCPFileName.getFileName(), LOAD_TCL_SUFFIX);
		try {
			Files.write(tclFileName, lines);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		System.out.println("INFO: Design Checkpoint \'"+ pathDCPFileName + "\'" 
				+ "\n      may contain encrypted cells. To correctly load the design into Vivado, "
				+ "\n      please source this Tcl script to open the checkpoint: "
				+ "\n\n      source " + tclFileName + "\n");
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

	/**
	 * Creates a new netlist from an existing EDIFCellInst in a netlist.  This operation is 
	 * destructive to the source netlist.   
	 * @param cellInst The new top cell/top cell inst in the netlist.
	 * @return The newly created netlist from the provided cell inst.
	 */
	public static EDIFNetlist createNewNetlist(EDIFCellInst cellInst){
	    EDIFNetlist n = new EDIFNetlist(cellInst.getName());
	    n.generateBuildComments();
	    EDIFDesign eDesign = new EDIFDesign(cellInst.getName());
	    n.setDesign(eDesign);
	    eDesign.setTopCell(cellInst.getCellType());
	    n.migrateCellAndSubCells(cellInst.getCellType());
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
	 * Bit blasts the shorthand bus name (ex: {@code "data[0:2]" --> ["data0", "data1", "data2"]})
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
	 * @param startingInput Search begins at this portref and searches below in inner levels of hierarchy.  Doesn't search backwards.
	 * @return The list of all leaf cell pin (portrefs) found below the provided starting input pin. Or null if the portref provided is not an input.
	 */
	public static ArrayList<EDIFHierPortInst> findSinks(EDIFHierPortInst startingInput){
		if(!startingInput.isInput()) return null;
		Queue<EDIFHierPortInst> q = new LinkedList<>();
		q.add(startingInput);
		ArrayList<EDIFHierPortInst> sinks = new ArrayList<>();
		while(!q.isEmpty()){
			EDIFHierPortInst curr = q.poll();
			final EDIFHierCellInst cellInst = curr.getHierarchicalInst().getChild(curr.getPortInst().getCellInst());

			EDIFHierNet internalNet = curr.getInternalNet();
			for(EDIFPortInst p : internalNet.getNet().getPortInsts()){
				if(!p.isInput()) continue;
				if(p.getCellInst() == null) continue;
				EDIFHierPortInst newPortInst = new EDIFHierPortInst(cellInst, p);
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
		EDIFPortInst bufPortInst = portNet.getPortInst(i, portName);
		if(bufPortInst == null)
			portNet.createPortInst(portName, i);
		if(pr.getNet() == null) 
			portNet.addPortInst(pr);
	}

	public static List<String> getMacroLeafCellNames(EDIFCell cell) {
		Queue<EDIFHierCellInst> q = new LinkedList<>();
		for(EDIFCellInst inst : cell.getCellInsts()) {
			q.add(EDIFHierCellInst.createRelative(inst));
		}
		ArrayList<String> leafCells = new ArrayList<String>();
		while(!q.isEmpty()) {
			EDIFHierCellInst inst = q.remove();
			if(inst.getCellType().isPrimitive()) {
				leafCells.add(inst.getFullHierarchicalInstName());
			}else {
				for(EDIFCellInst i : inst.getCellType().getCellInsts()) {
					q.add(inst.getChild(i));
				}
			}
		}
		return leafCells;
	}
	
	/**
	 * Creates a map of all cells in the netlist and a mapping to a list of all the hierarchical 
	 * instances of that cell.  Helpful in finding multiple instances of the same cell.
	 * @param netlist The netlist to build the map from.
	 * @return The populated map of cells to list of hierarchical instances.
	 */
	public static HashMap<EDIFLibrary, HashMap<EDIFCell, ArrayList<EDIFHierCellInst>>> 
							createCellInstanceMap(EDIFNetlist netlist) {
		HashMap<EDIFLibrary, HashMap<EDIFCell, ArrayList<EDIFHierCellInst>>> cellInstMap = 
				new HashMap<EDIFLibrary, HashMap<EDIFCell, ArrayList<EDIFHierCellInst>>>();
		
		Queue<EDIFHierCellInst> toProcess = new LinkedList<EDIFHierCellInst>();
		netlist.getTopHierCellInst().addChildren(toProcess);
		
		while(!toProcess.isEmpty()){
			EDIFHierCellInst curr = toProcess.poll();
			
			EDIFLibrary lib = curr.getCellType().getLibrary();
			HashMap<EDIFCell, ArrayList<EDIFHierCellInst>> cellMap = cellInstMap.computeIfAbsent(lib, k -> new HashMap<>());

			EDIFCell cell = curr.getCellType();
			ArrayList<EDIFHierCellInst> insts = cellMap.computeIfAbsent(cell, k -> new ArrayList<>());
			insts.add(curr);

			if(curr.getInst().getCellType().getCellInsts() == null) {
				continue;
			}
			for(EDIFCellInst i : curr.getInst().getCellType().getCellInsts()){
				toProcess.add(curr.getChild(i));
			}
		}
	
		return cellInstMap;
	}
	
	/**
	 * Flattens the netlist by creating unique cells within each library except primitives and 
	 * macros.  It also updates references throughout the logical and physical netlist so all 
	 * references are self-consistent.  This transformation is useful when performing netlist 
	 * manipulations such as adding/removing cells, ports or nets within a design.
	 * @param design The design containing the netlist to flatten.
	 */
	public static void flattenNetlist(Design design) {
		if(design.getModuleInsts().size() > 0) {
			System.err.println("ERROR: Cannot flatten netlist, design contains ModuleInstances. "
					+ "Please call Design.flattenDesign() first.");
			return;
		}
		EDIFNetlist netlist = design.getNetlist();
		EDIFLibrary macros = Design.getMacroPrimitives(design.getDevice().getSeries());
		HashMap<EDIFLibrary, HashMap<EDIFCell, ArrayList<EDIFHierCellInst>>> instMap = 
																	createCellInstanceMap(netlist);
		// Don't uniqueify primitive cell instances
		instMap.remove(netlist.getHDIPrimitivesLibrary());
		
		HashMap<EDIFCell, ArrayList<EDIFHierCellInst>> toUniqueify = new HashMap<EDIFCell, ArrayList<EDIFHierCellInst>>();
		
		for(EDIFLibrary lib : instMap.keySet()) {
			HashMap<EDIFCell, ArrayList<EDIFHierCellInst>> cellMap = instMap.get(lib);
			for(Entry<EDIFCell,ArrayList<EDIFHierCellInst>> e : cellMap.entrySet()) {
				// Also skip macros
				if(macros.containsCell(e.getKey())) continue;
				// Identify multiple instantiated cells
				if(e.getValue().size() > 1) {
					toUniqueify.put(e.getKey(),e.getValue());					
				}
			}
		}
		
		for(EDIFCell curr : new ArrayList<>(toUniqueify.keySet())) {
			duplicateMultiInstCell(design, curr, toUniqueify);
		}
	}
	
	
	private static int unique = 1;
	
	private static void duplicateMultiInstCell(Design design, EDIFCell cell, 
									HashMap<EDIFCell, ArrayList<EDIFHierCellInst>> toUniqueify) {
		EDIFNetlist netlist = design.getNetlist();
		// Check that all higher level cells don't have multiple shared cell definitions, before
		// duplicating this one		
		ArrayList<EDIFHierCellInst> insts = toUniqueify.get(cell);
		if(insts == null) {
			// Already processed, or no duplicates
			return;
		}
		for(EDIFHierCellInst inst : insts) {
			String[] instParents = inst.getFullHierarchicalInstName().split(EDIF_HIER_SEP);
			StringBuilder sb = new StringBuilder(instParents[0]);
			for(int i=1; i < instParents.length; i++) {
				EDIFCellInst parent = netlist.getCellInstFromHierName(sb.toString());
				if(parent != null) {
					ArrayList<EDIFHierCellInst> parentDuplicates = toUniqueify.get(parent.getCellType());
					if(parentDuplicates != null) {
						duplicateMultiInstCell(design, parent.getCellType(), toUniqueify);
					}					
				}
				sb.append(EDIF_HIER_SEP + instParents[i]);
			}
		}

		// Duplicate cell definitions for all but first instance
		boolean first = true;
		for(EDIFHierCellInst cellInst : insts) {
			if(first) {
				first = false;
				continue;
			}
			// Perform cell duplication
			EDIFCell origCell = cellInst.getCellType();
			EDIFCell newCell = new EDIFCell(origCell.getLibrary(), origCell, origCell.getName() 
					+ "_RW" + unique++);
			cellInst.getInst().setCellType(newCell);
			
			// Update any physical cell references
			for(EDIFCellInst inst : newCell.getCellInsts()) {
				String potentialLeafCell = cellInst.getFullHierarchicalInstName() 
						+ EDIF_HIER_SEP + inst.getName();
				Cell physCell = design.getCell(potentialLeafCell);
				if(physCell != null) {
					physCell.setEDIFCellInst(inst);
				}
			}
			
			// Update any physical net references
			for(EDIFNet net : newCell.getNets()) {
				String potentialLeafCell = cellInst.getFullHierarchicalInstName() 
						+ EDIF_HIER_SEP + net.getName();
				Net physNet = design.getNet(potentialLeafCell);
				if(physNet != null) {
					physNet.setLogicalNet(net);
				}
			}
		}
		toUniqueify.remove(cell);
	}
	
	/**
	 * Connects an existing logical net to another instances by creating intermediate logical
	 * nets and ports.  Design must be fully flattened (see {@link EDIFTools#flattenNetlist(Design)}.
	 *
	 * Note: EDIF cell instances and nets can contain '/' within their name. This function currently does not support
	 * designs containing these constructs.
	 *
	 * @param sinkParentInstName Hierarchical name of destination parent instance for net 
	 * @param srcParentInstName Hierarchical name of source parent instance of net
	 * @param parentInstNameToLogNet A map from hierarchical instance name to equivalent logical net
	 * in that corresponding cell instance.  Needs to be populated with the source parent instance
	 * name to work.  
	 * @param netlist Current working netlist.
	 * @return The final logical net in the destination cell instance.  Note also that the 
	 * parentInstNameToLogNet map is fully populated with each intermediate logical net created 
	 * through the process.  Thus it can be used for multiple fan out paths.
	 */
	public static EDIFNet connectLogicalNetAcrossHierarchy(String sinkParentInstName, 
			String srcParentInstName,
			Map<String, EDIFNet> parentInstNameToLogNet, 
			EDIFNetlist netlist) {
		String currParentInstName = srcParentInstName;
		String srcDupNetName = parentInstNameToLogNet.get(srcParentInstName).getName();
		EDIFNet currNet = null;

		while(!currParentInstName.equals(sinkParentInstName)) {
			currNet = parentInstNameToLogNet.get(currParentInstName);
			// Go up or down by one level of hierarchy
			String nextInstName = EDIFNetlist.getNextHierChildName(currParentInstName, sinkParentInstName);
			boolean goingUpInHier = false;
			if(nextInstName == null) {
				nextInstName = EDIFNetlist.getHierParentName(currParentInstName);
				goingUpInHier = true;
			}

			EDIFNet prevNet = currNet;
			currNet = parentInstNameToLogNet.get(nextInstName);

			// This instance doesn't have a logical net yet, let's add a port and create it
			if(currNet == null) {
				// Create a new net and port, port instance for this current instance
				EDIFCell currParent = netlist.getCellInstFromHierName(nextInstName).getCellType();
				int i=0;
				while(currParent.getNet(srcDupNetName + "_" + i) != null) {
					i++;
				}
				currNet = currParent.createNet(srcDupNetName + "_" + i);
				parentInstNameToLogNet.put(nextInstName, currNet);

				String portName = srcDupNetName +"_port";
				if(goingUpInHier) {
					// We need to go up in hierarchy, create output port out of parent
					EDIFCellInst currInst = netlist.getCellInstFromHierName(currParentInstName);
					EDIFCell currCell = currInst.getCellType();
					int j=0; 
					while(currCell.getPort(portName + "_" + j) != null) {
						j++;
					}
					EDIFPort port = currInst.getCellType().createPort(portName + "_" + j, EDIFDirection.OUTPUT, 1);
					prevNet.createPortInst(port);				 
					currNet.createPortInst(port, currInst);
				} else {
					// We are going down in hierarchy, create input port towards target 
					EDIFCellInst nextInst = netlist.getCellInstFromHierName(nextInstName);
					EDIFCell nextCell = nextInst.getCellType();
					int j=0; 
					while(nextCell.getPort(portName + "_" + j) != null) {
						j++;
					}
					EDIFPort port = nextInst.getCellType().createPort(portName + "_" + j, EDIFDirection.INPUT, 1);
					prevNet.createPortInst(port, nextInst);
					currNet.createPortInst(port);
				}

			}
			currParentInstName = nextInstName;
		}
		
		// Update Parent Net Map after creating new nets
		Map<EDIFHierNet,EDIFHierNet> parentNetMap = netlist.getParentNetMap();
		EDIFHierNet srcNetName = netlist.getHierCellInstFromName(srcParentInstName).getNet(srcDupNetName);
		EDIFHierNet parentNetName = parentNetMap.get(srcNetName);
		if(parentNetName == null) {
			parentNetName = srcNetName;
		}
		for(Entry<String,EDIFNet> e : parentInstNameToLogNet.entrySet()) {
			EDIFHierNet currNetName = netlist.getHierCellInstFromName(e.getKey()).getNet(e.getValue().getName());
			parentNetMap.put(currNetName, parentNetName);
		}

		return currNet;
	}
	
	public static void printHierSepNames(EDIFNetlist netlist) {
		for(EDIFLibrary lib : netlist.getLibraries()) {
			for(EDIFCell cell : lib.getCells()) {
				if(cell.getName().contains(EDIF_HIER_SEP)) {
					System.out.println("CELL: " + lib.getName() + "," + cell.getName());
				}
				
				for(EDIFPort port : cell.getPorts()) {
					if(port.getName().contains(EDIF_HIER_SEP)){
						System.out.println("PORT: " + lib.getName() + "," + cell.getName() 
						+"," + port.getName());
					}
				}
				
				for(EDIFNet net : cell.getNets()) {
					if(net.getName().contains(EDIF_HIER_SEP)) {
						System.out.println("NET: " + lib.getName() + "," + cell.getName() 
						+"," + net.getName());
					}
				}
				
				for(EDIFCellInst inst : cell.getCellInsts()) {
					if(inst.getName().contains(EDIF_HIER_SEP)) {
						System.out.println("INST: " + lib.getName() + "," + cell.getName() 
						+ "," + inst.getName());
					}
				}
			}
		}
	}
	
	public static void printLibraries(EDIFNetlist netlist) {
	    for(EDIFLibrary lib : netlist.getLibraries()) {
	        System.out.println("LIBRARY: " + lib.getName());
	        for(Entry<String,EDIFCell> entry : lib.getCellMap().entrySet()) {
	            System.out.println("  CELL: " + entry.getValue().getLegalEDIFName() + " /// " + entry.getKey());
	            for(EDIFCellInst inst : entry.getValue().getCellInsts()) {
	                System.out.println("    INST: " + inst.getCellType().getLegalEDIFName() + "("+inst.getLegalEDIFName()+")");
	            }
	        }
	    }
	}
}
