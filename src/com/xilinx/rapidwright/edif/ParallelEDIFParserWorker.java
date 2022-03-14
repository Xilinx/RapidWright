/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.xilinx.rapidwright.util.Pair;

public class ParallelEDIFParserWorker extends AbstractEDIFParser implements AutoCloseable{
    private static final boolean printStats = false;

    //Data to limit parsing to part of the file
    protected final long offset;
    private EDIFToken stopCellToken;

    private EDIFToken firstCellToken = null;

    //Parse results
    protected boolean stopTokenMismatch = false;
    protected EDIFParseException parseException = null;

    protected EDIFNetlist netlist = null;
    protected final List<EDIFCell> parsedCells = new ArrayList<>();
    protected final List<Pair<EDIFToken, EDIFLibrary>> libraryCache = new ArrayList<>();
    List<LinkPortInstData> linkPortInstCache = new ArrayList<>();
    protected final List<CellReferenceData> linkCellReference = new ArrayList<>();
    protected EDIFDesign edifDesign = null;
    protected final List<LibraryOrCellResult> librariesAndCells = new ArrayList<>();

    public ParallelEDIFParserWorker(Path fileName, InputStream in, long offset, NameUniquifier uniquifier, int maxTokenLength) {
        super(fileName, in, uniquifier, maxTokenLength);
        this.offset = offset;
    }

    public boolean isFirstParser() {
        return offset == 0;
    }

    public boolean parseFirstToken() {
        tokenizer.skip(offset);
        if (isFirstParser()) {
            parseToFirstCell();
        } else {
            try {
                if (!advanceToFirstCell()) {
                    return false;
                }
            } catch (EDIFParseException e) {
                parseException = e;
                return false;
            }
        }
        firstCellToken = peekNextTokenWithOffset();
        return true;
    }

    boolean inLibrary;

    /**
     * Continue parsing until we hit the next cell.
     * @return true if there is a next cell, false if we reached the end of the library
     */
    private boolean parseToNextCellWithinLibrary() {

        String currToken;
        if (LEFT_PAREN.equals(currToken = getNextToken())){
            return true;
        }
        expect(RIGHT_PAREN, currToken);
        return false;
    }


    /**
     * Continue parsing until we hit the next cell.
     * @return true if there is a next cell, false if we reached the end of the file
     */
    private boolean parseToNextCell() {
        if (inLibrary) {
            if (parseToNextCellWithinLibrary()) {
                return true;
            }
            inLibrary = false;
        }

        String currToken;
        while(LEFT_PAREN.equals(currToken = getNextToken())){
            String nextToken = peekNextToken();
            if (nextToken.equalsIgnoreCase(STATUS)) {
                parseStatus(netlist);
            } else if(nextToken.equalsIgnoreCase(LIBRARY) || nextToken.equalsIgnoreCase(EXTERNAL)){
                EDIFToken libraryStartToken = peekNextTokenWithOffset();
                EDIFLibrary library = parseEdifLibraryHead();
                libraryCache.add(new Pair<>(libraryStartToken, library));
                librariesAndCells.add(new LibraryResult(libraryStartToken, library));
                if (parseToNextCellWithinLibrary()) {
                    inLibrary = true;
                    return true;
                }
            } else if(nextToken.equalsIgnoreCase(COMMENT)){
                // Final Comment on Reference To The Cell Of Highest Level
                expect(COMMENT, getNextToken());
                String comment = getNextToken();
                expect(RIGHT_PAREN, getNextToken());
            } else if(nextToken.equalsIgnoreCase(DESIGN)){
                expect(DESIGN, getNextToken());
                edifDesign = parseEDIFNameObject(new EDIFDesign());
                expect(LEFT_PAREN, getNextToken());
                expect(CELLREF, getNextToken());
                String cellref = getNextToken();
                expect(LEFT_PAREN, getNextToken());
                expect(LIBRARYREF, getNextToken());
                String libraryref = getNextToken();
                linkCellReference.add(new CellReferenceData(edifDesign::setTopCell, cellref, libraryref, null));
                expect(RIGHT_PAREN, getNextToken());
                expect(RIGHT_PAREN, getNextToken());
                currToken = null;
                while(LEFT_PAREN.equals(currToken = getNextToken())){
                    parseProperty(edifDesign);
                }
                expect(RIGHT_PAREN, currToken);

            } else {
                expect(LIBRARY + " | " + COMMENT + " | " + DESIGN + " | " + STATUS+ " | " + EXTERNAL, nextToken);
            }

        }
        expect(RIGHT_PAREN, currToken);  // edif end
        return false;
    }

    private void parseToFirstCell() {
        netlist = parseEDIFNetlistHead();

        inLibrary = false;
        parseToNextCell();
    }

    private boolean advanceToFirstCell() {
        while (true) {
            final EDIFToken currentToken = tokenizer.getOptionalNextToken();
            if (currentToken == null) {
                return false;
            }

            EDIFToken next = tokenizer.peekOptionalNextToken();
            if (next == null) {
                return false;
            }

            if (currentToken.text.equals("(") && "cell".equals(next.text)) {
                inLibrary = true;
                return true;
            }
        }
    }

    public EDIFToken getFirstCellToken() {
        return firstCellToken;
    }

    private long startTimestamp;
    private long stopTimestamp;

    public void printParseStats(long fileSize) {
        if (!printStats) {
            return;
        }
        double duration = (stopTimestamp-startTimestamp)/1E9;

        long endOffset = stopCellToken != null ? stopCellToken.byteOffset : fileSize;
        double percentage;
        if (firstCellToken == null) {
            percentage = 0;
        } else {
            long startOffset = firstCellToken.byteOffset;;
            if (isFirstParser()) {
                startOffset = 0;
            }
            percentage = (endOffset - startOffset)*100.0/fileSize;
        }

        System.out.println(this+" took "+duration+"s to parse "+percentage+"%, seeing "+librariesAndCells.size()+" items");
    }


    public void doParse() {
        //We have seeked to a random place inside the EDIF, so we cannot be absolutely sure that we correctly detected
        // token boundaries. Catch all EDIFParseExceptions and figure out later which ones are correct
        try {
            stopTokenMismatch = false;
            //Already reached EOF while trying to find first cell?
            if (firstCellToken == null) {
                return;
            }
            startTimestamp = System.nanoTime();
            while (true) {
                final EDIFToken next = peekNextTokenWithOffset();
                if (next.equals(stopCellToken)) {
                    stopTimestamp = System.nanoTime();
                    return;
                }
                if (stopCellToken != null && next.byteOffset >= stopCellToken.byteOffset) {
                    stopTokenMismatch = true;
                    stopTimestamp = System.nanoTime();
                    return;
                }
                EDIFCell cell = parseEDIFCell(null);
                parsedCells.add(cell);
                librariesAndCells.add(new CellResult(next, cell));
                if (!parseToNextCell()) {
                    if (stopCellToken != null) {
                        stopTokenMismatch = true;
                    }
                    stopTimestamp = System.nanoTime();
                    //EOF
                    return;
                }
            }
        } catch (EDIFParseException e) {
            parseException = e;
        }

    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    @Override
    public String toString() {
        return "Parser@"+offset;
    }

    public void setStopCellToken(EDIFToken stopCellToken) {
        this.stopCellToken = stopCellToken;
    }


    @Override
    protected EDIFCell updateEDIFRefCellMap(String libraryLegalName, EDIFCell cell) {
        //Nothing to do :)
        return cell;
    }


    @Override
    protected void linkEdifPortInstToCellInst(EDIFCell parentCell, EDIFPortInst portInst, EDIFNet net) {
        linkPortInstCache.add(new LinkPortInstData(parentCell, portInst, net));
    }

    public void linkCellReferences(Map<String, EDIFLibrary> librariesByLegalName) {
        for (ParallelEDIFParserWorker.CellReferenceData cellReferenceData : linkCellReference) {
            cellReferenceData.apply(librariesByLegalName);
        }
    }

    public void linkPortInsts() {
        for (ParallelEDIFParserWorker.LinkPortInstData linkData : linkPortInstCache) {
            linkData.apply(tokenizer.uniquifier);
        }
    }

    public static class CellReferenceData {
        public final Consumer<EDIFCell> cellSetter;
        public final String cellref;
        public final String libraryref;
        private final EDIFCell currentCell;

        private CellReferenceData(Consumer<EDIFCell> cellReference, String cellref, String libraryref, EDIFCell currentCell) {
            this.cellSetter = cellReference;
            this.cellref = cellref;
            this.libraryref = libraryref;
            this.currentCell = currentCell;
        }

        public void apply(Map<String, EDIFLibrary> librariesByLegalName) {
            EDIFLibrary library;
            if (libraryref == null) {
                if (currentCell == null) {
                    throw new IllegalStateException("Cannot reference a cell without current cell and library ref");
                }
                library = currentCell.getLibrary();
            } else {
                library = Objects.requireNonNull(librariesByLegalName.get(libraryref), ()-> "No library with name "+libraryref);
            }

            final EDIFCell cell = library.getCell(cellref);
            cellSetter.accept(cell);
        }
    }

    static class LinkPortInstData {
        private final EDIFCell parentCell;
        private final EDIFPortInst portInst;
        private final EDIFNet net;


        LinkPortInstData(EDIFCell parentCell, EDIFPortInst portInst, EDIFNet net) {
            this.parentCell = parentCell;
            this.portInst = portInst;
            this.net = net;
        }
        public void apply(NameUniquifier uniquifier) {
            doLinkPortInstToCellInst(parentCell, portInst, uniquifier, net);
        }
    }

    static abstract class LibraryOrCellResult {
        private final EDIFToken token;

        LibraryOrCellResult(EDIFToken token) {
            this.token = token;
        }

        public EDIFToken getToken() {
            return token;
        }

        public abstract EDIFLibrary addToNetlist(EDIFNetlist netlist, EDIFLibrary currentLibrary);
    }

    static class LibraryResult extends LibraryOrCellResult {
        private final EDIFLibrary library;

        LibraryResult(EDIFToken token, EDIFLibrary library) {
            super(token);
            this.library = library;
        }

        @Override
        public EDIFLibrary addToNetlist(EDIFNetlist netlist, EDIFLibrary currentLibrary) {
            netlist.addLibrary(library);
            return library;
        }
    }

    static class CellResult extends LibraryOrCellResult {
        private final EDIFCell cell;
        CellResult(EDIFToken token, EDIFCell cell) {
            super(token);
            this.cell = cell;
        }

        @Override
        public EDIFLibrary addToNetlist(EDIFNetlist netlist, EDIFLibrary currentLibrary) {
            if (currentLibrary == null) {
                throw new IllegalStateException("Saw first cell before first library");
            }
            currentLibrary.addCell(cell);
            return currentLibrary;
        }
    }


    @Override
    protected void linkCellInstToCell(EDIFCellInst inst, String cellref, String libraryref, EDIFCell currentCell) {
        linkCellReference.add(new CellReferenceData(inst::setCellType, cellref, libraryref, currentCell));
    }
}
