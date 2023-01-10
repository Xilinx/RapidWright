/*
 *
 * Copyright (c) 2017-2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.Params;
import com.xilinx.rapidwright.util.StringPool;

/**
 * An EDIF parser created especially for RapidWright.  It is only intended to
 * read EDIF generated from Xilinx Vivado.  It is not intended to be a fully
 * general purpose EDIF parser.  If you have other EDIF that you want to read into
 * RapidWright, load it into Vivado first and then write it out from Vivado.
 * Created on: May 10, 2017
 */
public class EDIFParser extends AbstractEDIFParserWorker implements AutoCloseable{

    private Map<String,Map<String,EDIFCell>> edifInstCellMap = new HashMap<>();



    public EDIFParser(Path fileName) throws FileNotFoundException {
        super(fileName, StringPool.singleThreadedPool(), EDIFReadLegalNameCache.createSingleThreaded());
    }

    public EDIFParser(String fileName) throws FileNotFoundException {
        this(Paths.get(fileName));
    }

    public EDIFParser(InputStream in) {
        super(null, in, StringPool.singleThreadedPool(), EDIFReadLegalNameCache.createSingleThreaded());
    }

    /**
     * Gets the reference EDIFCell for the given name.  This is to enable
     * references rather than strings to be used to connect the netlist model.
     * @param edifCellName Legal EDIF name of the cell
     * @param libraryName Legal EDIF name of the library
     * @return The existing EDIFCell or a newly created one that will be populated
     * when the cell is parsed.
     */
    private EDIFCell getRefEDIFCell(String edifCellName, String libraryName) {
        Map<String, EDIFCell> lib = edifInstCellMap.computeIfAbsent(libraryName, k -> new HashMap<>());
        EDIFCell cell = lib.get(edifCellName);
        if (cell == null) {
            cell = new EDIFCell();
            lib.put(edifCellName, cell);
        }
        return cell;
    }

    @SuppressWarnings("unused")
    public EDIFNetlist parseEDIFNetlist() {
        EDIFNetlist currNetlist = parseEDIFNetlistHead();

        String currToken;

        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            String nextToken = getNextToken(true);
            if (nextToken.equalsIgnoreCase(STATUS)) {
                parseStatus(currNetlist);
            } else if (nextToken.equalsIgnoreCase(LIBRARY) || nextToken.equalsIgnoreCase(EXTERNAL)) {
                currNetlist.addLibrary(parseEDIFLibrary());
            } else if (nextToken.equalsIgnoreCase(COMMENT)) {
                // Final Comment on Reference To The Cell Of Highest Level
                String comment = getNextToken(true);
                expect(RIGHT_PAREN, getNextToken(true));
            } else if (nextToken.equalsIgnoreCase(DESIGN)) {
                EDIFDesign design = parseEDIFNameObject(new EDIFDesign());
                currNetlist.setDesign(design);
                expect(LEFT_PAREN, getNextToken(true));
                expect(CELLREF, getNextToken(true));
                String cellref = getNextToken(false);
                expect(LEFT_PAREN, getNextToken(true));
                expect(LIBRARYREF, getNextToken(true));
                String libraryref = getNextToken(false);
                design.setTopCell(getRefEDIFCell(cellref, libraryref));
                expect(RIGHT_PAREN, getNextToken(true));
                expect(RIGHT_PAREN, getNextToken(true));
                currToken = null;
                while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
                    parseProperty(design, getNextToken(true));
                }
                expect(RIGHT_PAREN, currToken);

            } else {
                expect(LIBRARY + " | " + COMMENT + " | " + DESIGN + " | " + STATUS+ " | " + EXTERNAL, nextToken);
            }

        }
        expect(RIGHT_PAREN, currToken);  // edif end

        final EDIFToken token = tokenizer.getOptionalNextToken(true);
        if (token != null) {
            throw new EDIFParseException(token, "Expected EOF but found "+token);
        }

        Path fileName = tokenizer.getFileName();
        if (fileName != null && fileName.toString().endsWith(".gz")
                && Params.RW_DECOMPRESS_GZIPPED_EDIF_TO_DISK) {
            try {
                Files.delete(
                        FileTools.getDecompressedGZIPFileName(tokenizer.getFileName()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return currNetlist;
    }

    private EDIFLibrary parseEDIFLibrary() {
        EDIFLibrary library = parseEdifLibraryHead();

        String currToken;
        while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
            final EDIFCell cell = parseEDIFCell(library.getName(), getNextToken(true));
            library.addCellRenameDuplicates(cell, cache.getEDIFRename(cell));
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
     * @param libraryLegalName The current library's name the cell belongs to.
     * @param cell The freshly created EDIFCell from parsing.
     * @return The reference cell to be used going forward.
     */
    @Override
    protected EDIFCell updateEDIFRefCellMap(String libraryLegalName, EDIFCell cell) {
        Map<String, EDIFCell> map = edifInstCellMap.computeIfAbsent(libraryLegalName, k -> new HashMap<>());
        final String legalEDIFName = cache.getLegalEDIFName(cell);
        EDIFCell existingCell = map.get(legalEDIFName);
        if (existingCell != null) {
            existingCell.setName(cell.getName());
            return existingCell;
        }
        map.put(legalEDIFName, cell);
        return cell;
    }

    @Override
    protected void linkCellInstToCell(EDIFCellInst inst, String cellref, String libraryref, EDIFCell currentCell) {
        inst.setCellType(getRefEDIFCell(cellref, libraryref));
    }

    public static void main(String[] args) throws FileNotFoundException {
        CodePerfTracker p = new CodePerfTracker("Read/Write EDIF",true);
        p.start("Parse EDIF");
        EDIFParser e = new EDIFParser(args[0]);
        EDIFNetlist n = e.parseEDIFNetlist();
        p.stop().start("Write EDIF");
        if (args.length > 1) n.exportEDIF(args[1]);
        p.stop().printSummary();
    }

    @Override
    public void close() throws IOException {
        tokenizer.close();
    }

    @Override
    protected void linkEdifPortInstToCellInst(EDIFCell parentCell, EDIFPortInst portInst, EDIFNet net) {
        doLinkPortInstToCellInst(parentCell, portInst, net);
    }

}
