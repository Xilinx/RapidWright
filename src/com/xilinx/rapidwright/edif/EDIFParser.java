/*
 * 
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

import java.io.BufferedInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.WeakHashMap;

import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.MessageGenerator;

/**
 * An EDIF parser created especially for RapidWright.  It is only intended to 
 * read EDIF generated from Xilinx Vivado.  It is not intended to be a fully 
 * general purpose EDIF parser.  If you have other EDIF that you want to read into
 * RapidWright, load it into Vivado first and then write it out from Vivado.
 * Created on: May 10, 2017
 */
public class EDIFParser implements AutoCloseable{

	private Path fileName;
	
	private InputStream in;
	
	private int lineNumber;
	
	private char[] buffer = new char[8192*16*18];
	
	private int ch = -1;
	
	private int idx = 0;
	
	private boolean inQuote = false;
	
	private Queue<String> nextTokens;
	
	private Map<String,String> stringPool;
	
	private EDIFNetlist currNetlist;
	
	private Map<String,Map<String,EDIFCell>> edifInstCellMap;
	
	private Map<String, EDIFCellInst> instanceLookup;
	
	private static boolean debug = false;
	
	private static final String LEFT_PAREN = "(";
	private static final String RIGHT_PAREN = ")";
	private static final String EDIF = "edif";
	private static final String RENAME = "rename";
	private static final String EDIFVERSION = "edifversion";
	private static final String EDIFLEVEL = "ediflevel";
	private static final String EXTERNAL = "external";
	private static final String KEYWORDMAP = "keywordmap";
	private static final String KEYWORDLEVEL = "keywordlevel";
	private static final String STATUS = "status";
	private static final String WRITTEN = "written";
	private static final String TIMESTAMP = "timestamp";
	private static final String PROGRAM = "program";
	private static final String VERSION = "version";
	private static final String COMMENT = "comment";
	private static final String LIBRARY = "library";
	private static final String TECHNOLOGY = "technology";
	private static final String NUMBERDEFINITION = "numberdefinition";
	private static final String CELL = "cell";
	private static final String CELLTYPE = "celltype";
	private static final String VIEW = "view";
	private static final String VIEWTYPE = "viewtype";
	private static final String INTERFACE = "interface";
	private static final String PORT = "port";
	private static final String DIRECTION = "direction";
	private static final String ARRAY = "array";
	private static final String CONTENTS = "contents";
	private static final String INSTANCE = "instance";
	private static final String NET = "net";
	private static final String VIEWREF = "viewref";
	private static final String CELLREF = "cellref";
	private static final String LIBRARYREF = "libraryref";
	private static final String PROPERTY = "property";
	private static final String JOINED = "joined";
	private static final String PORTREF = "portref";
	private static final String MEMBER = "member";
	private static final String INSTANCEREF = "instanceref";
	private static final String DESIGN = "design";
	private static final String METAX = "metax";
	private static final String OWNER = "owner";

	public EDIFParser(Path fileName) throws FileNotFoundException {
		this.fileName = fileName;
		try {
			in = new BufferedInputStream(Files.newInputStream(this.fileName));
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		init();
	}

	public EDIFParser(String fileName) throws FileNotFoundException {
		this(Paths.get(fileName));
	}

	public EDIFParser(InputStream in){
		this.in = in;
		init();
	}
	
	private void init(){
		lineNumber = 1;
		nextTokens = new LinkedList<>();
		stringPool = new WeakHashMap<>();
		edifInstCellMap = new HashMap<String, Map<String,EDIFCell>>();
	}
	
	/**
	 * Gets the reference EDIFCell for the given name.  This is to enable
	 * references rather than strings to be used to connect the netlist model.
	 * @param edifCellName Legal EDIF name of the cell
	 * @param libraryName Legal EDIF name of the library
	 * @return The existing EDIFCell or a newly created one that will be populated  
	 * when the cell is parsed.
	 */
	private EDIFCell getRefEDIFCell(String edifCellName, String libraryName){
		Map<String,EDIFCell> lib = edifInstCellMap.get(libraryName);
		if(lib == null){
			lib = new HashMap<>();
			edifInstCellMap.put(libraryName, lib);
		}
		EDIFCell cell = lib.get(edifCellName);
		if(cell == null){
			cell = new EDIFCell();
			cell.setEDIFRename(edifCellName);
			lib.put(edifCellName, cell);
		}
		return cell;
	}
	
	/**
	 * Get the reference cell instance by the given legal EDIF name. 
	 * @param edifCellInstName Legal EDIF name for the cell instance.
	 * @return The existing cell instance or the reference instance that
	 * will be used when the cell instance is fully parsed.
	 */
	private EDIFCellInst getRefEDIFCellInst(String edifCellInstName){
		EDIFCellInst inst = instanceLookup.get(edifCellInstName);
		if(inst == null || inst.getCellType() == null) {
		    throw new RuntimeException("ERROR: Bad instance ref "+ edifCellInstName+" on line: " 
		                               + lineNumber);
		}
		return inst;
	}
	
	private String peekNextToken(){
		if(nextTokens.isEmpty()){
			String next = getNextToken();
			if(nextTokens.isEmpty()){
				nextTokens.add(next);
			}else {
				String tmp = nextTokens.poll();
				nextTokens.add(next);
				nextTokens.add(tmp);
			}
		}
		return nextTokens.peek();
	}
	
	private String debugToken(String token){
		if(debug) System.out.println("<" + token + "> : " + lineNumber);
		return token;
	}
	
	private String getUniqueString(char[] buffer, int offset, int count){
		String tmp = new String(buffer, offset, count);
		return stringPool.computeIfAbsent(tmp, k->tmp);
	}
	
	public Map<String,String> getStringPool(){
		return stringPool;
	}
	
	private String getNextToken(){
		if(!nextTokens.isEmpty()){
			return debugToken(nextTokens.poll());
		}
		String returnToken = null;
		try{
			outer: while((ch = in.read()) != -1){
				if(ch == '\n') lineNumber++;
				switch(ch){
					case '"':
						if(inQuote){
							inQuote = false;
							buffer[idx++] = (char) ch;
							int end = idx;
							idx = 0;
							returnToken = getUniqueString(buffer,1, end-2);
							break outer;
						}else{
							buffer[idx++] = (char) ch;
							inQuote = true;							
						}
						break;
					case '(':
						if(inQuote) {
							buffer[idx++] = (char) ch;
							break;
						}
						if(idx > 0){
							int end = idx;
							idx = 0;
							returnToken = getUniqueString(buffer,0, end);
							nextTokens.add("(");
							break outer;
						}
						idx = 0;	
						returnToken = "(";
						break outer;
					case ')':
						if(inQuote) {
							buffer[idx++] = (char) ch;
							break;
						}
						if(idx > 0){
							int end = idx;
							idx = 0;
							returnToken = getUniqueString(buffer,0, end);
							nextTokens.add(")");
							break outer;
						}
						idx = 0;
						returnToken = ")";
						break outer;
					case ' ':
					case '\n':
					case '\r':
					case '\t':
						if(inQuote) {
							buffer[idx++] = (char) ch;
							break;
						}
						if(idx > 0){
							int end = idx;
							idx = 0;
							returnToken = getUniqueString(buffer,0, end);
							break outer;
						}
						break;
					default:
						buffer[idx++] = (char) ch;
				}
			}
		}
		catch(IOException e){
			throw new UncheckedIOException("ERROR: IOException while reading EDIF file: " 
			        + fileName, e);
		} 
		catch(ArrayIndexOutOfBoundsException e ){
			if(idx >= buffer.length) 
				MessageGenerator.briefError("ERROR: String buffer overflow on line " + 
						lineNumber + " parsing token starting with \n\t'" + 
						new String(buffer,0,128) + "...'. \n\tPlease revisit why this EDIF token "
						+ "is so long or increase the buffer in " + this.getClass().getCanonicalName());
			throw e;
		}
		return debugToken(returnToken);
	}
	
	private EDIFName parseEDIFNameObject(EDIFName o){
		String currToken = getNextToken();
		if(currToken.equals(LEFT_PAREN)){
			expect(RENAME, getNextToken());
			o.setEDIFRename(getNextToken());
			// Handle issue with names beginning with '[]'
			String name = getNextToken();
			if(name.charAt(0) == '[' && name.length() >= 2 &&  name.charAt(1) == ']'){
				String tmpName = name.substring(2);
				name = stringPool.computeIfAbsent(tmpName, k -> tmpName);
			}
			o.setName(name);
			expect(RIGHT_PAREN, getNextToken());
		} else {
			o.setName(currToken);
		}
		return o;
	}

	private void parseStatus() {
		expect(STATUS, getNextToken());
		expect(LEFT_PAREN, getNextToken());
		expect(WRITTEN, getNextToken());
		expect(LEFT_PAREN, getNextToken());
		expect(TIMESTAMP, getNextToken());
		int year = Integer.parseInt(getNextToken());
		int month = Integer.parseInt(getNextToken());
		int day = Integer.parseInt(getNextToken());
		int hour = Integer.parseInt(getNextToken());
		int min = Integer.parseInt(getNextToken());
		int sec = Integer.parseInt(getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		expect(LEFT_PAREN, getNextToken());
		expect(PROGRAM, getNextToken());
		String progName = getNextToken();
		expect(LEFT_PAREN, getNextToken());
		expect(VERSION, getNextToken());
		String ver = getNextToken();
		expect(RIGHT_PAREN, getNextToken());
		expect(RIGHT_PAREN, getNextToken());

		String currToken;
		while(LEFT_PAREN.equals(currToken = getNextToken())){
			String commentOrMetax = getNextToken();
			if(commentOrMetax.equals(COMMENT)){
				currNetlist.addComment(getNextToken());

			}else if(commentOrMetax.equals(METAX)){
				String key = getNextToken();
				EDIFPropertyValue value = parsePropertyValue();
				currNetlist.addMetax(key,value);
			}else{
				expect(COMMENT + "|" + METAX, commentOrMetax);
			}
			expect(RIGHT_PAREN, getNextToken());
		}
		expect(RIGHT_PAREN, currToken);
		expect(RIGHT_PAREN, getNextToken());
	}
	
	@SuppressWarnings("unused")
	public EDIFNetlist parseEDIFNetlist(){
		expect(LEFT_PAREN, getNextToken());
		expect(EDIF, getNextToken());
		currNetlist = (EDIFNetlist) parseEDIFNameObject(new EDIFNetlist());
		expect(LEFT_PAREN, getNextToken());
		expect(EDIFVERSION, getNextToken());
		expect("2", getNextToken());
		expect("0", getNextToken());
		expect("0", getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		expect(LEFT_PAREN, getNextToken());
		expect(EDIFLEVEL, getNextToken());
		expect("0", getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		expect(LEFT_PAREN, getNextToken());
		expect(KEYWORDMAP, getNextToken());
		expect(LEFT_PAREN, getNextToken());
		expect(KEYWORDLEVEL, getNextToken());
		expect("0", getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		expect(RIGHT_PAREN, getNextToken());

		String currToken;
		
		while(LEFT_PAREN.equals(currToken = getNextToken())){
			String nextToken = peekNextToken();
			if (nextToken.equalsIgnoreCase(STATUS)) {
				parseStatus();
			} else if(nextToken.equalsIgnoreCase(LIBRARY) || nextToken.equalsIgnoreCase(EXTERNAL)){
				currNetlist.addLibrary(parseEDIFLibrary());
			} else if(nextToken.equalsIgnoreCase(COMMENT)){
				// Final Comment on Reference To The Cell Of Highest Level
				expect(COMMENT, getNextToken());
				String comment = getNextToken();
				expect(RIGHT_PAREN, getNextToken());
			} else if(nextToken.equalsIgnoreCase(DESIGN)){
				expect(DESIGN, getNextToken());
				EDIFDesign design = (EDIFDesign)parseEDIFNameObject(new EDIFDesign());
				currNetlist.setDesign(design);
				expect(LEFT_PAREN, getNextToken());
				expect(CELLREF, getNextToken());
				String cellref = getNextToken();
				expect(LEFT_PAREN, getNextToken());
				expect(LIBRARYREF, getNextToken());
				String libraryref = getNextToken();
				design.setTopCell(getRefEDIFCell(cellref, libraryref));
				expect(RIGHT_PAREN, getNextToken());
				expect(RIGHT_PAREN, getNextToken());
				currToken = null;
				while(LEFT_PAREN.equals(currToken = getNextToken())){
					parseProperty(design); 
				}
				expect(RIGHT_PAREN, currToken);

			} else {
				expect(LIBRARY + " | " + COMMENT + " | " + DESIGN + " | " + STATUS+ " | " + EXTERNAL, nextToken);
			}
			 
		}
		expect(RIGHT_PAREN, currToken);  // edif end
		
		return currNetlist;
	}
	
	private EDIFLibrary parseEDIFLibrary(){
		String token = getNextToken();
		if (!token.equalsIgnoreCase(EXTERNAL) && !token.equalsIgnoreCase(LIBRARY) ) {
			expect(LIBRARY + "|" + EXTERNAL, token);
		}
		EDIFLibrary library = (EDIFLibrary) parseEDIFNameObject(new EDIFLibrary());
		expect(LEFT_PAREN, getNextToken());
		expect(EDIFLEVEL, getNextToken());
		@SuppressWarnings("unused")
		int level = Integer.parseInt(getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		
		expect(LEFT_PAREN, getNextToken());
		expect(TECHNOLOGY, getNextToken());
		expect(LEFT_PAREN, getNextToken());
		expect(NUMBERDEFINITION, getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		
		String currToken = null;
		while(LEFT_PAREN.equals(currToken = getNextToken())){
			library.addCell(parseEDIFCell(library)); 
		}
		expect(RIGHT_PAREN, currToken);
		return library;
	}
	
	/**
	 * This method will arbitrate between existing temporary cells created
	 * for their reference and newly created cells as parsed in the file.  
	 * This is to avoid storing strings for references and have actual
	 * object references in the netlist structure. This method should be
	 * called immediately after creating a new EDIFCell parsed directly
	 * from the file.  
	 * @param lib The current library the cell belongs to.
	 * @param cell The freshly created EDIFCell from parsing.
	 * @return The reference cell to be used going forward.
	 */
	private EDIFCell updateEDIFRefCellMap(EDIFLibrary lib, EDIFCell cell){
		Map<String,EDIFCell> map = edifInstCellMap.get(lib.getLegalEDIFName());
		if(map == null){
			map = new HashMap<>();
			edifInstCellMap.put(lib.getLegalEDIFName(), map);
		}
		EDIFCell existingCell = map.get(cell.getLegalEDIFName());
		if(existingCell != null){
			existingCell.setName(cell.getName());
			existingCell.setEDIFRename(cell.getEDIFName());
			return existingCell;
		}
		map.put(cell.getLegalEDIFName(), cell);
		return cell;
	}
	
	/**
	 * This method arbitrates between freshly created {@link EDIFCellInst} and 
	 * those created temporarily as references.  It will take a freshly created 
	 * {@link EDIFCellInst} that was generated by parsing the definition in the 
	 * EDIF file and choose the proper one to populate.
	 * @param inst
	 * @return The {@link EDIFCellInst} to be used going forward
	 */
	private EDIFCellInst updateEDIFRefCellInstMap(EDIFCellInst inst){
		EDIFCellInst existingInst = instanceLookup.get(inst.getLegalEDIFName());
		if(existingInst != null){
			existingInst.setName(inst.getName());
			existingInst.setEDIFRename(inst.getEDIFName());
			return existingInst;
		}
		instanceLookup.put(inst.getLegalEDIFName(), inst);
		return inst;
	}
	
	private EDIFCell parseEDIFCell(EDIFLibrary lib){
		expect(CELL, getNextToken());
		EDIFCell cell = (EDIFCell) parseEDIFNameObject(new EDIFCell());
		cell = updateEDIFRefCellMap(lib, cell);
		instanceLookup = new HashMap<>();
		expect(LEFT_PAREN, getNextToken());
		expect(CELLTYPE, getNextToken());
		expect("GENERIC", getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		
		expect(LEFT_PAREN, getNextToken());
		expect(VIEW, getNextToken());
		cell.setView(parseEDIFNameObject(new EDIFName()));
		expect(LEFT_PAREN, getNextToken());
		expect(VIEWTYPE, getNextToken());
		expect("NETLIST", getNextToken());
		expect(RIGHT_PAREN, getNextToken());
		
		expect(LEFT_PAREN, getNextToken());
		expect(INTERFACE, getNextToken());
		String currToken = null;
		while(LEFT_PAREN.equals(currToken = getNextToken())){
			EDIFPort p = parseEDIFPort();
			cell.addPort(p); 
		}
		expect(RIGHT_PAREN, currToken); // Interface end
		
		while(LEFT_PAREN.equals(currToken = getNextToken())){ 			
			String contentsOrProperty = peekNextToken();		
			if(contentsOrProperty.equals(CONTENTS)){ // Optional content 
				expect(CONTENTS, getNextToken());
				while(LEFT_PAREN.equals(currToken = getNextToken())){
					String nextToken = peekNextToken();
					if(nextToken.equals(INSTANCE)){
						cell.addCellInst(parseEDIFCellInst(lib.getLegalEDIFName()));
					} else if(nextToken.equals(NET)){
						parseEDIFNet(cell);
					} else {
						expect(INSTANCE + " | " + NET, nextToken);
					}
				}
				expect(RIGHT_PAREN, currToken); // Content end
			}else if (contentsOrProperty.equals(PROPERTY)){
					parseProperty(cell);
			}else{
				expect(CONTENTS + " | " + PROPERTY, contentsOrProperty);
			}
		}
		expect(RIGHT_PAREN, currToken); // View end
		expect(RIGHT_PAREN, getNextToken()); // Cell end
		return cell;
	}
	
	private EDIFNet parseEDIFNet(EDIFCell cell){
		expect(NET, getNextToken());
		EDIFNet net = (EDIFNet) parseEDIFNameObject(new EDIFNet());
		expect(LEFT_PAREN, getNextToken());
		expect(JOINED, getNextToken());
		String currToken = null;
		cell.addNet(net);
		while(LEFT_PAREN.equals(currToken = getNextToken())){
			net.addPortInst(parseEDIFPortInst(cell));
		}
		expect(RIGHT_PAREN, currToken);
		while(LEFT_PAREN.equals(currToken = getNextToken())){
			parseProperty(net);
		}
		expect(RIGHT_PAREN,currToken);
		
		
		return net;
	}
	
	private EDIFPropertyObject parseProperty(EDIFPropertyObject o){
		expect(PROPERTY, getNextToken());
		EDIFName key = parseEDIFNameObject(new EDIFName());
		EDIFPropertyValue value = parsePropertyValue();
		o.addProperty(key,value);
		String paren = getNextToken();
		if(paren.equals(RIGHT_PAREN)) {
			// pass - nothing more to do here
		}
		else if(paren.equals(LEFT_PAREN)){
			expect(OWNER, getNextToken());
			o.setOwner(getNextToken());
			expect(RIGHT_PAREN,getNextToken());
			expect(RIGHT_PAREN,getNextToken());
		}else{
			expect(RIGHT_PAREN + "|" + LEFT_PAREN, paren);
		}

		return o;
	}
	
	private EDIFPortInst parseEDIFPortInst(EDIFCell parentCell){
		expect(PORTREF,getNextToken());
		String currToken = getNextToken();
		EDIFPortInst portInst = new EDIFPortInst();
		if(currToken.equals(LEFT_PAREN)){
			expect(MEMBER,getNextToken());
			portInst.setName(getNextToken());
			portInst.setIndex(Integer.parseInt(getNextToken()));					
			expect(RIGHT_PAREN,getNextToken());
		}else{
			portInst.setName(currToken);
		}
		
		currToken = getNextToken();
		EDIFCell portCell = parentCell;
		if(currToken.equals(LEFT_PAREN)){
			expect(INSTANCEREF,getNextToken());
			String instanceref = getNextToken();
			portInst.setCellInstRaw(getRefEDIFCellInst(instanceref));
			portCell = portInst.getCellInst().getCellType();
			expect(RIGHT_PAREN,getNextToken());
			expect(RIGHT_PAREN,getNextToken());
		}else{
			// This is a port to higher level
			expect(RIGHT_PAREN,currToken);
		}
		EDIFPort port = portCell.getPort(portInst.getName());
		if(port == null) {
		    // Finding by EDIFName is O(n), but n is generally small and alternative to building
		    // a map for this single search ends up taking longer 
		    for(Entry<String,EDIFPort> e : portCell.getPortMap().entrySet()) {
		        if(e.getValue().getLegalEDIFName().equals(portInst.getName())) {
		            port = e.getValue();
		            break;
		        }
		    }
		}

		if(port == null) {
		    throw new RuntimeException("ERROR: Couldn't find EDIFPort for "
		            + "EDIFPortInst " + portInst.getName() + " on line number " + lineNumber);
		}
		portInst.setPort(port);
		String portInstName = portInst.getPortInstNameFromPort();
		portInst.setName(stringPool.computeIfAbsent(portInstName, k -> portInstName));
		if(portInst.getCellInst() != null) {
		    portInst.getCellInst().addPortInst(portInst);
		}
		return portInst;
	}
	
	private EDIFCellInst parseEDIFCellInst(String currentLibraryName){
		expect(INSTANCE, getNextToken());
		EDIFCellInst inst = (EDIFCellInst) parseEDIFNameObject(new EDIFCellInst());
		inst = updateEDIFRefCellInstMap(inst);
		expect(LEFT_PAREN,getNextToken());
		expect(VIEWREF,getNextToken());
		inst.setViewref(parseEDIFNameObject(new EDIFName()));
		expect(LEFT_PAREN,getNextToken());
		expect(CELLREF,getNextToken());
		String cellref = getNextToken();

		if (LEFT_PAREN.equals(peekNextToken())) {
			expect(LEFT_PAREN, getNextToken());
			expect(LIBRARYREF, getNextToken());
			String libraryref = getNextToken();
			inst.setCellType(getRefEDIFCell(cellref, libraryref));
			expect(RIGHT_PAREN, getNextToken());
		} else {
			inst.setCellType(getRefEDIFCell(cellref, currentLibraryName));
		}

		expect(RIGHT_PAREN,getNextToken());
		expect(RIGHT_PAREN,getNextToken());
		String currToken = null;
		while(LEFT_PAREN.equals(currToken = getNextToken())){
			parseProperty(inst);
		}
		expect(RIGHT_PAREN, currToken);

		return inst;
	}
	
	private EDIFPropertyValue parsePropertyValue(){
		expect(LEFT_PAREN,getNextToken());
		EDIFPropertyValue val = new EDIFPropertyValue();
		val.setType(EDIFValueType.valueOf(getNextToken().toUpperCase()));
		if(val.getType() == EDIFValueType.BOOLEAN){
			expect(LEFT_PAREN,getNextToken());
			val.setValue(getNextToken());
			expect(RIGHT_PAREN,getNextToken());
		}else {
			val.setValue(getNextToken());
		}
		expect(RIGHT_PAREN,getNextToken());
		return val;
	}
	
	private EDIFPort parseEDIFPort(){
		expect(PORT, getNextToken());
		String currToken = getNextToken();
		EDIFPort port = null;
		if(currToken.equals(LEFT_PAREN)){
			currToken = getNextToken();
			if(currToken.equals(ARRAY)){
				port = (EDIFPort) parseEDIFNameObject(new EDIFPort());
				port.setWidth(Integer.parseInt(getNextToken()));
				expect(RIGHT_PAREN, getNextToken());
			} else if(currToken.equals(RENAME)){
				port = new EDIFPort();
				port.setEDIFRename(getNextToken());
				port.setName(getNextToken());
				expect(RIGHT_PAREN, getNextToken());
			} else {
				expect(ARRAY + " | " + RENAME, currToken);
			}
		}else{
			port = new EDIFPort();
			port.setName(currToken);
		}
		port.setIsLittleEndian();
		expect(LEFT_PAREN, getNextToken());
		expect(DIRECTION, getNextToken());
		port.setDirection(EDIFDirection.valueOf(getNextToken()));
		expect(RIGHT_PAREN, getNextToken());
		
		while(LEFT_PAREN.equals(currToken = getNextToken())){
			parseProperty(port);
		}
		expect(RIGHT_PAREN, currToken);
		return port;
	}
	
	private void expect(String expectedString, String token){
		if(!expectedString.equals(token)){
			if(expectedString.equals(token.toLowerCase())) return;
			throw new RuntimeException("Parsing Error: Expected token: " + expectedString +
					", encountered: " + token + " on line: " + lineNumber + ".");
		}
	}
	
	public static void main(String[] args) throws FileNotFoundException {
		CodePerfTracker p = new CodePerfTracker("Read/Write EDIF",true);
		p.start("Parse EDIF");
		EDIFParser e = new EDIFParser(args[0]);
		EDIFNetlist n = e.parseEDIFNetlist();
		p.stop().start("Write EDIF");
		if(args.length > 1) n.exportEDIF(args[1]);
		p.stop().printSummary();
	}

	@Override
	public void close() throws IOException {
		if(in!=null) {
			in.close();
		}
	}
}
