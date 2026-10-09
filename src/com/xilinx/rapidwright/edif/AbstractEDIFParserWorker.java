/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

package com.xilinx.rapidwright.edif;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.util.StringPool;

public abstract class AbstractEDIFParserWorker {


    public static final String LEFT_PAREN = "(";
    public static final String RIGHT_PAREN = ")";
    public static final String EDIF = "edif";
    public static final String RENAME = "rename";
    public static final String EDIFVERSION = "edifversion";
    public static final String EDIFLEVEL = "ediflevel";
    public static final String EXTERNAL = "external";
    public static final String KEYWORDMAP = "keywordmap";
    public static final String KEYWORDLEVEL = "keywordlevel";
    public static final String STATUS = "status";
    public static final String WRITTEN = "written";
    public static final String TIMESTAMP = "timestamp";
    public static final String AUTHOR = "author";
    public static final String PROGRAM = "program";
    public static final String VERSION = "version";
    public static final String COMMENT = "comment";
    public static final String LIBRARY = "library";
    public static final String TECHNOLOGY = "technology";
    public static final String NUMBERDEFINITION = "numberdefinition";
    public static final String CELL = "cell";
    public static final String CELLTYPE = "celltype";
    public static final String VIEW = "view";
    public static final String VIEWTYPE = "viewtype";
    public static final String INTERFACE = "interface";
    public static final String PORT = "port";
    public static final String DIRECTION = "direction";
    public static final String ARRAY = "array";
    public static final String CONTENTS = "contents";
    public static final String INSTANCE = "instance";
    public static final String NET = "net";
    public static final String VIEWREF = "viewref";
    public static final String CELLREF = "cellref";
    public static final String LIBRARYREF = "libraryref";
    public static final String PROPERTY = "property";
    public static final String JOINED = "joined";
    public static final String PORTREF = "portref";
    public static final String MEMBER = "member";
    public static final String INSTANCEREF = "instanceref";
    public static final String DESIGN = "design";
    public static final String METAX = "metax";
    public static final String OWNER = "owner";

    protected final EDIFTokenizer tokenizer;
    protected final InputStream in;
    /**
     * The renames recorded by this parser (worker), which are not shared with any other: it is written without
     * synchronization while parsing, and may only be read by other threads once parsing has finished.
     */
    protected final EDIFReadLegalNameCache cache = new EDIFReadLegalNameCache();

    /**
     * Whether a port of the cell being (or last) parsed by {@link #parseEDIFCell(String, String)} was renamed, and
     * so has its rename recorded in {@link #cache}.
     */
    protected boolean cellHasRenamedPorts;

    public AbstractEDIFParserWorker(Path fileName, InputStream in, StringPool uniquifier, int maxTokenLength) {
        this.in = in;
        this.tokenizer = new EDIFTokenizer(fileName, in, uniquifier, maxTokenLength);
    }

    public AbstractEDIFParserWorker(Path fileName, InputStream in, StringPool uniquifier) {
        this(fileName, in, uniquifier, EDIFTokenizer.DEFAULT_MAX_TOKEN_LENGTH);
    }

    public AbstractEDIFParserWorker(Path fileName, StringPool uniquifier) throws FileNotFoundException {
        in = EDIFTools.openEDIFInputStream(fileName);
        tokenizer = new EDIFTokenizer(fileName, in, uniquifier);
    }

    /**
     * Consumes the remainder of the construct currently being parsed, through its
     * matching closing parenthesis. The construct's opening parenthesis and keyword
     * are assumed to have already been consumed.
     */
    protected void skipConstruct() {
        int depth = 1;
        while (depth > 0) {
            String token = requireToken(tokenizer.getOptionalNextTokenString(true));
            // A quoted string is returned without its quotes, so its text can equal
            // "(" or ")" without being a parenthesis
            if (tokenizer.wasLastTokenQuoted()) {
                continue;
            }
            if (LEFT_PAREN.equals(token)) {
                depth++;
            } else if (RIGHT_PAREN.equals(token)) {
                depth--;
            }
        }
    }

    private static <T> T requireToken(T t) {
        if (t==null) {
            throw EDIFParseException.unexpectedEOF();
        }
        return t;
    }

    protected EDIFToken getNextTokenWithOffset(boolean isShortLived) {
        return requireToken(tokenizer.getOptionalNextToken(isShortLived));
    }

    protected String getNextToken(boolean isShortLived) {
        return requireToken(tokenizer.getOptionalNextTokenString(isShortLived));
    }


    /**
     * Parse the name of an object, which may be given as (rename identifier "name"), and set it on the object.
     * The rename (EDIF identifier) is not recorded.
     * @param o The object being named.
     * @return The object's EDIF identifier if it was renamed, else null.
     */
    protected String parseEDIFName(EDIFName o) {
        String currToken = getNextToken(false);
        if (currToken.equals(EDIFParser.LEFT_PAREN)) {
            expect(EDIFParser.RENAME, getNextToken(true));
            String rename = getNextToken(false);
            // Handle issue with names beginning with '[]'
            String name = getNextToken(false);
            if (name.charAt(0) == '[' && name.length() >= 2 &&  name.charAt(1) == ']') {
                String tmpName = name.substring(2);
                name = tokenizer.getUniquifier().uniquifyName(tmpName);
            }
            o.setName(name);
            expect(EDIFParser.RIGHT_PAREN, getNextToken(true));
            return rename;
        }
        o.setName(currToken);
        return null;
    }

    /**
     * Parse the name of an object that may be referenced by its EDIF identifier while parsing (a library or cell),
     * recording its rename (if any) in the cache so that such references can be resolved. Ports' renames are
     * recorded by {@link #parseEDIFPort()} and cell instances' renames are only needed within their parent cell.
     * @param o The object being named.
     * @return The object.
     */
    protected<T extends EDIFName> T parseEDIFNameObject(T o) {
        final String rename = parseEDIFName(o);
        if (rename != null) {
            cache.setRename(o, rename);
        }
        return o;
    }

    /**
     * Parse the name of an object that is never referenced by its EDIF identifier while parsing (e.g. a net, view,
     * property key, design or netlist), so its rename (if any) is not recorded. Export recomputes identifiers.
     * @param o The object being named.
     * @return The object.
     */
    protected<T extends EDIFName> T parseUnreferencedEDIFNameObject(T o) {
        parseEDIFName(o);
        return o;
    }

    protected void expect(String expectedString, String token) {
        if (!expectedString.equalsIgnoreCase(token)) {
            throw new EDIFParseException("Parsing Error: Expected token: " + expectedString +
                    ", encountered: " + token + " before byte offset "+tokenizer.getByteOffset()+".");
        }
    }

    protected EDIFNetlist parseEDIFNetlistHead() {
        expect(LEFT_PAREN, getNextToken(true));
        expect(EDIF, getNextToken(true));
        EDIFNetlist netlist = parseUnreferencedEDIFNameObject(new EDIFNetlist());
        expect(LEFT_PAREN, getNextToken(true));
        expect(EDIFVERSION, getNextToken(true));
        expect("2", getNextToken(true));
        expect("0", getNextToken(true));
        expect("0", getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));
        expect(LEFT_PAREN, getNextToken(true));
        expect(EDIFLEVEL, getNextToken(true));
        expect("0", getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));
        expect(LEFT_PAREN, getNextToken(true));
        expect(KEYWORDMAP, getNextToken(true));
        expect(LEFT_PAREN, getNextToken(true));
        expect(KEYWORDLEVEL, getNextToken(true));
        expect("0", getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));

        return netlist;
    }



    protected EDIFCell parseEDIFCell(String libraryLegalName, String cellToken) {
        expect(CELL, cellToken);
        EDIFCell cell = parseEDIFNameObject(new EDIFCell());
        cell = updateEDIFRefCellMap(libraryLegalName, cell);
        Map<String, EDIFCellInst> instanceLookup = new HashMap<>();
        cellHasRenamedPorts = false;
        expect(LEFT_PAREN, getNextToken(true));
        expect(CELLTYPE, getNextToken(true));
        expect("GENERIC", getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));

        expect(LEFT_PAREN, getNextToken(true));
        // EDIF permits cell-level properties ahead of the view
        String viewOrProperty = getNextToken(true);
        while (PROPERTY.equalsIgnoreCase(viewOrProperty)) {
            parseProperty(cell, viewOrProperty); // Consumes through the property's ')'
            expect(LEFT_PAREN, getNextToken(true));
            viewOrProperty = getNextToken(true);
        }
        expect(VIEW, viewOrProperty);
        cell.setView(parseUnreferencedEDIFNameObject(new EDIFName()));
        expect(LEFT_PAREN, getNextToken(true));
        expect(VIEWTYPE, getNextToken(true));
        expect("NETLIST", getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));

        expect(LEFT_PAREN, getNextToken(true));
        expect(INTERFACE, getNextToken(true));
        String currToken = null;
        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            EDIFPort p = parseEDIFPort();
            cell.addPort(p);
        }
        expect(RIGHT_PAREN, currToken); // Interface end

        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            String contentsOrProperty = getNextToken(true);
            if (contentsOrProperty.equals(CONTENTS)) { // Optional content
                while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
                    String nextToken = getNextToken(true);
                    if (nextToken.equals(INSTANCE)) {
                        cell.addCellInst(parseEDIFCellInst(libraryLegalName, instanceLookup, cell, nextToken));
                    } else if (nextToken.equals(NET)) {
                        parseEDIFNet(cell, instanceLookup, nextToken, cache);
                    } else {
                        expect(INSTANCE + " | " + NET, nextToken);
                    }
                }
                expect(RIGHT_PAREN, currToken); // Content end
            } else if (contentsOrProperty.equals(PROPERTY)) {
                parseProperty(cell, contentsOrProperty);
            } else {
                expect(CONTENTS + " | " + PROPERTY, contentsOrProperty);
            }
        }
        expect(RIGHT_PAREN, currToken); // View end
        expect(RIGHT_PAREN, getNextToken(true)); // Cell end
        return cell;
    }

    protected abstract EDIFCell updateEDIFRefCellMap(String libraryLegalName, EDIFCell cell);

    protected void parseStatus(EDIFNetlist currNetlist) {
        expect(LEFT_PAREN, getNextToken(true));
        expect(WRITTEN, getNextToken(true));
        expect(LEFT_PAREN, getNextToken(true));
        expect(TIMESTAMP, getNextToken(true));
        int year = Integer.parseInt(getNextToken(true));
        int month = Integer.parseInt(getNextToken(true));
        int day = Integer.parseInt(getNextToken(true));
        int hour = Integer.parseInt(getNextToken(true));
        int min = Integer.parseInt(getNextToken(true));
        int sec = Integer.parseInt(getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));

        String currToken;
        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            String writtenEntry = getNextToken(true);
            if (writtenEntry.equals(PROGRAM)) {
                getNextToken(true); // Program name, unused
                // program ::= (program programName {version | comment | userData}),
                // so any number of sub-entries may follow, in any order
                String programEntry;
                while (LEFT_PAREN.equals(programEntry = getNextToken(true))) {
                    if (VERSION.equals(getNextToken(true))) {
                        getNextToken(true); // Version string, unused
                        expect(RIGHT_PAREN, getNextToken(true));
                    } else {
                        // A comment or userData entry, neither of which is modelled
                        skipConstruct();
                    }
                }
                expect(RIGHT_PAREN, programEntry); // Program end
                continue;
            } else if (writtenEntry.equals(AUTHOR)) {
                getNextToken(true); // Author name, unused
            } else if (writtenEntry.equals(COMMENT)) {
                currNetlist.addComment(getNextToken(false));
            } else if (writtenEntry.equals(METAX)) {
                String key = getNextToken(false);
                EDIFPropertyValue value = parsePropertyValue();
                currNetlist.addMetax(key,value);
            } else if (writtenEntry.equals(PROPERTY)) {
                // Discard this property for now
                parseProperty(new EDIFPropertyObject(), writtenEntry);
                continue;
            } else {
                expect(PROGRAM + "|" + AUTHOR + "|" + COMMENT + "|" + METAX + "|" + PROPERTY,
                        writtenEntry);
            }
            expect(RIGHT_PAREN, getNextToken(true));
        }
        expect(RIGHT_PAREN, currToken);
        expect(RIGHT_PAREN, getNextToken(true));
    }

    protected EDIFPropertyValue parsePropertyValue() {
        expect(LEFT_PAREN, getNextToken(true));
        EDIFPropertyValue val = new EDIFPropertyValue();
        val.setType(parseValueType(getNextToken(true)));
        if (val.getType() == EDIFValueType.BOOLEAN) {
            expect(LEFT_PAREN, getNextToken(true));
            val.setValue(getNextToken(false));
            expect(RIGHT_PAREN, getNextToken(true));
        } else {
            val.setValue(getNextToken(false));
        }
        expect(RIGHT_PAREN, getNextToken(true));
        return val;
    }

    /**
     * Get the type of a property value from its keyword, without creating a String for the common spellings.
     * @param type The keyword, e.g. "string".
     * @return The type.
     */
    private static EDIFValueType parseValueType(String type) {
        switch (type) {
            case "string":
                return EDIFValueType.STRING;
            case "integer":
                return EDIFValueType.INTEGER;
            case "boolean":
                return EDIFValueType.BOOLEAN;
            default:
                return EDIFValueType.valueOf(type.toUpperCase());
        }
    }

    protected EDIFLibrary parseEdifLibraryHead() {
        EDIFLibrary library = parseEDIFNameObject(new EDIFLibrary());
        expect(LEFT_PAREN, getNextToken(true));
        expect(EDIFLEVEL, getNextToken(true));
        @SuppressWarnings("unused")
        int level = Integer.parseInt(getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));

        expect(LEFT_PAREN, getNextToken(true));
        expect(TECHNOLOGY, getNextToken(true));
        expect(LEFT_PAREN, getNextToken(true));
        expect(NUMBERDEFINITION, getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));
        expect(RIGHT_PAREN, getNextToken(true));
        return library;
    }

    protected EDIFPropertyObject parseProperty(EDIFPropertyObject o, String nextToken) {
        expect(PROPERTY, nextToken);
        EDIFName key = parseUnreferencedEDIFNameObject(new EDIFName());
        EDIFPropertyValue value = parsePropertyValue();
        o.addProperty(key.getName(),value);
        String paren = getNextToken(true);
        if (paren.equals(RIGHT_PAREN)) {
            // pass - nothing more to do here
        }
        else if (paren.equals(LEFT_PAREN)) {
            expect(OWNER, getNextToken(true));
            value.setOwner(getNextToken(false));
            expect(RIGHT_PAREN,getNextToken(true));
            expect(RIGHT_PAREN,getNextToken(true));
        } else {
            expect(RIGHT_PAREN + "|" + LEFT_PAREN, paren);
        }

        return o;
    }

    protected EDIFNet parseEDIFNet(EDIFCell cell, Map<String, EDIFCellInst> instanceLookup, String netToken, EDIFReadLegalNameCache cache) {
        expect(NET, netToken);
        EDIFNet net = parseUnreferencedEDIFNameObject(new EDIFNet());
        expect(LEFT_PAREN, getNextToken(true));
        expect(JOINED, getNextToken(true));
        String currToken = null;
        cell.addNet(net);
        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            parseEDIFPortInst(cell, instanceLookup,net);
        }
        expect(RIGHT_PAREN, currToken);
        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            parseProperty(net, getNextToken(true));
        }
        expect(RIGHT_PAREN,currToken);


        return net;
    }

    protected abstract void linkEdifPortInstToCellInst(EDIFCell parentCell, EDIFPortInst portInst, EDIFNet net);

    private void parseEDIFPortInst(EDIFCell parentCell, Map<String, EDIFCellInst> instanceLookup, EDIFNet net) {
        expect(PORTREF, getNextToken(true));
        String currToken = getNextToken(false);
        EDIFPortInst portInst = new EDIFPortInst();
        if (currToken.equals(LEFT_PAREN)) {
            expect(MEMBER, getNextToken(true));
            portInst.setName(getNextToken(false));
            portInst.setIndex(Integer.parseInt(getNextToken(true)));
            expect(RIGHT_PAREN, getNextToken(true));
        } else {
            portInst.setName(currToken);
        }

        currToken = getNextToken(true);

        if (currToken.equals(LEFT_PAREN)) {
            expect(INSTANCEREF, getNextToken(true));
            String instanceref = getNextToken(false); //TODO change longevity?
            portInst.setCellInstRaw(getRefEDIFCellInst(instanceref, instanceLookup));
            expect(RIGHT_PAREN, getNextToken(true));
            expect(RIGHT_PAREN, getNextToken(true));
        } else {
            // This is a port to higher level
            expect(RIGHT_PAREN,currToken);
        }

        linkEdifPortInstToCellInst(parentCell, portInst, net);
    }

    /**
     * Get the reference cell instance by the given legal EDIF name.
     * @param edifCellInstName Legal EDIF name for the cell instance.
     * @return The existing cell instance or the reference instance that
     * will be used when the cell instance is fully parsed.
     */
    protected EDIFCellInst getRefEDIFCellInst(String edifCellInstName, Map<String, EDIFCellInst> instanceLookup) {
        EDIFCellInst inst = instanceLookup.get(edifCellInstName);
        if (inst == null) {
            throw new EDIFParseException("ERROR: Bad instance ref "+ edifCellInstName);
        }
        return inst;
    }

    private EDIFPort parseEDIFPort() {
        expect(PORT, getNextToken(true));
        String currToken = getNextToken(false);
        EDIFPort port = null;
        if (currToken.equals(LEFT_PAREN)) {
            currToken = getNextToken(true);
            if (currToken.equals(ARRAY)) {
                port = new EDIFPort();
                setPortRename(port, parseEDIFName(port));
                port.setWidth(Integer.parseInt(getNextToken(true)));
                expect(RIGHT_PAREN, getNextToken(true));
            } else if (currToken.equals(RENAME)) {
                port = new EDIFPort();
                final String rename = getNextToken(false);
                port.setName(getNextToken(false));
                setPortRename(port, rename);
                expect(RIGHT_PAREN, getNextToken(true));
            } else {
                expect(ARRAY + " | " + RENAME, currToken);
            }
        } else {
            port = new EDIFPort();
            port.setName(currToken);
        }
        port.setIsLittleEndian();
        expect(LEFT_PAREN, getNextToken(true));
        expect(DIRECTION, getNextToken(true));
        port.setDirection(EDIFDirection.valueOf(getNextToken(true)));
        expect(RIGHT_PAREN, getNextToken(true));

        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            parseProperty(port, getNextToken(true));
        }
        expect(RIGHT_PAREN, currToken);
        return port;
    }

    /**
     * Record a port's rename (if any), which is needed to find the port by its EDIF identifier when linking port
     * insts to it.
     */
    private void setPortRename(EDIFPort port, String rename) {
        if (rename != null) {
            cache.setRename(port, rename);
            cellHasRenamedPorts = true;
        }
    }

    private EDIFCellInst parseEDIFCellInst(String currentLibraryName, Map<String, EDIFCellInst> instanceLookup, EDIFCell currentCell, String instanceToken) {
        expect(INSTANCE, instanceToken);
        EDIFCellInst inst = new EDIFCellInst();
        inst = updateEDIFRefCellInstMap(inst, parseEDIFName(inst), instanceLookup);
        expect(LEFT_PAREN, getNextToken(true));
        expect(VIEWREF, getNextToken(true));
        inst.setViewref(parseUnreferencedEDIFNameObject(new EDIFName()));
        expect(LEFT_PAREN, getNextToken(true));
        expect(CELLREF, getNextToken(true));
        String cellref = getNextToken(false);

        String nextToken = getNextToken(true);
        if (LEFT_PAREN.equals(nextToken)) {
            expect(LIBRARYREF, getNextToken(true));
            String libraryref = getNextToken(false);
            linkCellInstToCell(inst, cellref, libraryref, currentCell);
            expect(RIGHT_PAREN, getNextToken(true));

            nextToken = getNextToken(true);
        } else {
            linkCellInstToCell(inst, cellref, currentLibraryName, currentCell);
        }

        expect(RIGHT_PAREN, nextToken);
        expect(RIGHT_PAREN, getNextToken(true));
        String currToken = null;
        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            parseProperty(inst, getNextToken(true));
        }
        expect(RIGHT_PAREN, currToken);

        return inst;
    }

    protected abstract void linkCellInstToCell(EDIFCellInst inst, String cellref, String libraryref, EDIFCell currentCell);

    /**
     * This method arbitrates between freshly created {@link EDIFCellInst} and
     * those created temporarily as references.  It will take a freshly created
     * {@link EDIFCellInst} that was generated by parsing the definition in the
     * EDIF file and choose the proper one to populate.
     * @param inst
     * @param rename The instance's EDIF identifier if it was renamed, else null. This is only needed within the
     * parent cell, so it is not recorded in the cache.
     * @return The {@link EDIFCellInst} to be used going forward
     */
    private EDIFCellInst updateEDIFRefCellInstMap(EDIFCellInst inst, String rename, Map<String, EDIFCellInst> instanceLookup) {
        EDIFCellInst existingInst = instanceLookup.get(rename);
        if (existingInst != null) {
            existingInst.setName(inst.getName());
            return existingInst;
        }
        instanceLookup.put(rename != null ? rename : inst.getName(), inst);
        return inst;
    }

    protected static EDIFCell lookupPortCell(EDIFCell parentCell, EDIFPortInst portInst) {
        EDIFCell portCell = parentCell;
        if (portInst.getCellInst() != null) {
            portCell = portInst.getCellInst().getCellType();
        }
        if (portCell ==null) {
            throw new NullPointerException();
        }
        return portCell;
    }

    protected void doLinkPortInstToCellInst(EDIFCell parentCell, EDIFPortInst portInst, EDIFNet net) {
        EDIFCell portCell = lookupPortCell(parentCell, portInst);
        EDIFPort port = portCell.getPortByLegalName(portInst.getName(), cache);

        if (port == null) {
            throw new EDIFParseException("ERROR: Couldn't find EDIFPort for "
                    + "EDIFPortInst " + portInst.getName());
        }
        portInst.setPort(port);
        String portInstName = portInst.getPortInstNameFromPort();
        portInst.setName(portInstName);
        if (portInst.getCellInst() != null) {
            portInst.getCellInst().addPortInst(portInst);
        }
        net.addPortInst(portInst);
    }
}
