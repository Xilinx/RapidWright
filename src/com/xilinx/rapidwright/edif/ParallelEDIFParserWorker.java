/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.xilinx.rapidwright.util.StringPool;

/**
 * Worker thread inside the parallel EDIF parser
 */
public class ParallelEDIFParserWorker extends AbstractEDIFParserWorker implements AutoCloseable{
    /**
     * Number of ports in a cell above which a map is used for port name lookup
     */
    private static final int PORT_LOOKUP_MAP_THRESHOLD = 300;

    //Data to limit parsing to part of the file
    protected final long offset;
    private EDIFToken stopCellToken;

    private EDIFToken firstCellToken = null;

    //Parse results
    protected boolean stopTokenMismatch = false;
    private EDIFToken actualStopCellToken = null;
    protected EDIFParseException parseException = null;

    protected EDIFNetlist netlist = null;
    protected final List<LibraryOrCellResult> librariesAndCells = new ArrayList<>();
    protected EDIFDesign edifDesign = null;
    protected final List<CellReferenceData> linkCellReference = new ArrayList<>();
    protected final List<List<LinkPortInstData>> linkPortInstData = new ArrayList<>();
    protected final EDIFReadLegalNameCache cache;

    public ParallelEDIFParserWorker(Path fileName, InputStream in, long offset, StringPool uniquifier, int maxTokenLength, EDIFReadLegalNameCache cache) {
        super(fileName, in, uniquifier, maxTokenLength, cache);
        this.offset = offset;
        this.cache = cache;
    }

    public boolean isFirstParser() {
        return offset == 0;
    }

    /**
     * Skip to our offset, then advance to the next cell
     * @return true if successful
     */
    public boolean parseFirstToken() {
        tokenizer.skip(offset);
        if (isFirstParser()) {
            parseToFirstCell();
            firstCellToken = getNextTokenWithOffset(true);
            return firstCellToken!=null;
        } else {
            try {
                firstCellToken = advanceToFirstCell();
                return firstCellToken != null;
            } catch (EDIFParseException e) {
                parseException = e;
                return false;
            }
        }
    }

    boolean inLibrary;

    /**
     * Continue parsing until we hit the next cell while we are currently inside a library.
     * @return true if there is a next cell, false if we reached the end of the library
     */
    private boolean parseToNextCellWithinLibrary() {
        String currToken = getNextToken(true);
        if (LEFT_PAREN.equals(currToken)) {
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

        String currToken = getNextToken(true);
        while (LEFT_PAREN.equals(currToken)) {
            EDIFToken nextToken = getNextTokenWithOffset(true);
            if (nextToken.text.equalsIgnoreCase(STATUS)) {
                parseStatus(netlist);
            } else if (nextToken.text.equalsIgnoreCase(LIBRARY) || nextToken.text.equalsIgnoreCase(EXTERNAL)) {
                EDIFLibrary library = parseEdifLibraryHead();
                librariesAndCells.add(new LibraryResult(nextToken, library));
                if (parseToNextCellWithinLibrary()) {
                    inLibrary = true;
                    return true;
                }
            } else if (nextToken.text.equalsIgnoreCase(COMMENT)) {
                // Final Comment on Reference To The Cell Of Highest Level
                String comment = getNextToken(true);
                expect(RIGHT_PAREN, getNextToken(true));
            } else if (nextToken.text.equalsIgnoreCase(DESIGN)) {
                edifDesign = parseEDIFNameObject(new EDIFDesign());
                expect(LEFT_PAREN, getNextToken(true));
                expect(CELLREF, getNextToken(true));
                String cellref = getNextToken(false);
                expect(LEFT_PAREN, getNextToken(true));
                expect(LIBRARYREF, getNextToken(true));
                String libraryref = getNextToken(false);
                linkCellReference.add(new CellReferenceData(edifDesign::setTopCell, cellref, libraryref, null));
                expect(RIGHT_PAREN, getNextToken(true));
                expect(RIGHT_PAREN, getNextToken(true));
                currToken = null;
                while (LEFT_PAREN.equals(currToken = getNextToken(true))) {
                    parseProperty(edifDesign, getNextToken(true));
                }
                expect(RIGHT_PAREN, currToken);

            } else {
                expect(LIBRARY + " | " + COMMENT + " | " + DESIGN + " | " + STATUS+ " | " + EXTERNAL, nextToken.text);
            }

            currToken = getNextToken(true);
        }
        expect(RIGHT_PAREN, currToken);  // edif end
        return false;
    }

    private void parseToFirstCell() {
        netlist = parseEDIFNetlistHead();

        inLibrary = false;
        parseToNextCell();
    }

    /**
     * Advance to the first cell
     * @return the cell token or null if found EOF
     */
    private EDIFToken advanceToFirstCell() {
        while (true) {
            final EDIFToken currentToken = tokenizer.getOptionalNextToken(true);
            if (currentToken == null) {
                return null;
            }

            if (!currentToken.text.equals("(")) {
                continue;
            }

            EDIFToken next = tokenizer.getOptionalNextToken(true);
            if (next == null) {
                return null;
            }

            if ("cell".equalsIgnoreCase(next.text)) {
                inLibrary = true;
                return next;
            }
        }
    }

    public EDIFToken getFirstCellToken() {
        return firstCellToken;
    }

    public void doParse(boolean rerun) {
        //We have seeked to a random place inside the EDIF, so we cannot be absolutely sure that we correctly detected
        // token boundaries. Catch all EDIFParseExceptions and figure out later which ones are correct
        try {
            stopTokenMismatch = false;
            //Already reached EOF while trying to find first cell?
            if (firstCellToken == null) {
                return;
            }
            if (rerun && actualStopCellToken==null) {
                //Already hit eof
                return;
            }
            EDIFToken next = rerun ? actualStopCellToken : firstCellToken;
            while (true) {
                if (next.equals(stopCellToken)) {
                    return;
                }
                if (stopCellToken != null && next.byteOffset >= stopCellToken.byteOffset) {
                    stopTokenMismatch = true;
                    actualStopCellToken = next;
                    return;
                }
                EDIFCell cell = parseEDIFCell(null, next.text);
                librariesAndCells.add(new CellResult(next, cell));
                if (!parseToNextCell()) {
                    if (stopCellToken != null) {
                        stopTokenMismatch = true;
                    }
                    //EOF
                    return;
                }
                next = getNextTokenWithOffset(true);
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


    private EDIFCell currentParentCell = null;
    private List<LinkPortInstData> currentLinks = new ArrayList<>();

    @Override
    protected void linkEdifPortInstToCellInst(EDIFCell parentCell, EDIFPortInst portInst, EDIFNet net) {
        if (parentCell != currentParentCell) {
            currentParentCell = parentCell;
            if (!currentLinks.isEmpty()) {
                linkPortInstData.add(currentLinks);
            }
            currentLinks = new ArrayList<>();
        }
        currentLinks.add(new LinkPortInstData(parentCell, portInst, net));
    }

    public Stream<CellReferenceData> streamCellReferences() {
        return linkCellReference.stream();
    }

    public void finish() {
        if (!currentLinks.isEmpty()) {
            linkPortInstData.add(currentLinks);
        }
    }

    public void linkSmallPorts(Map<EDIFCell, Collection<LinkPortInstData>> largeCellMap) {
        for (List<LinkPortInstData> data : linkPortInstData) {
            for (LinkPortInstData d : data) {
                final EDIFCell cell = d.mapPortCell();
                if (cell.getPorts().size() < PORT_LOOKUP_MAP_THRESHOLD) {
                    d.portInst.setPort(cell.getPortByLegalName(d.portInst.getName(), cache));
                } else {
                    largeCellMap.computeIfAbsent(cell, x-> new ConcurrentLinkedQueue<>()).add(d);
                }
            }
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

        public void apply(Map<String, EDIFLibrary> librariesByLegalName, Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName) {
            EDIFLibrary library;
            if (libraryref == null) {
                if (currentCell == null) {
                    throw new IllegalStateException("Cannot reference a cell without current cell and library ref");
                }
                library = currentCell.getLibrary();
            } else {
                library = Objects.requireNonNull(librariesByLegalName.get(libraryref), ()-> "No library with name "+libraryref);
            }

            final EDIFCell cell = cellsByLegalName.get(library).get(cellref);
            if (cell == null) {
                throw new RuntimeException("did not find cell "+cellref+" in library "+libraryref);
            }
            cellSetter.accept(cell);
        }
    }

    class LinkPortInstData {
        private final EDIFCell parentCell;
        private final EDIFPortInst portInst;
        private final EDIFNet net;


        LinkPortInstData(EDIFCell parentCell, EDIFPortInst portInst, EDIFNet net) {
            this.parentCell = parentCell;
            this.portInst = portInst;
            this.net = net;
        }
        public void apply() {
            doLinkPortInstToCellInst(parentCell, portInst, net);
        }

        public EDIFCell mapPortCell() {
            return lookupPortCell(parentCell, portInst);
        }

        public void enterPort(EDIFPortCache edifPortCache) {
            EDIFPort port;
            if (edifPortCache != null) {
                port = edifPortCache.getPort(portInst.getName());
                if (port == null) {
                    throw new RuntimeException("did not find port "+portInst.getName()+" in cache");
                }
            } else {
                final EDIFCell portCell = lookupPortCell(parentCell, portInst);
                port = portCell.getPortByLegalName(portInst.getName(), cache);
                if (port == null) {

                    throw new RuntimeException("did not find port "+portInst.getName()+" on cell "+portCell);
                }
            }
            portInst.setPort(port);
        }

        public void name(StringPool uniquifier) {
            // Here we must accommodate single bit busses that have collided with their
            // namesake and update the port instance to reference a single bit bussed port
            if (portInst.getIndex() == -1 && portInst.getPort().isBus() && portInst.getPort().getWidth() == 1) {
                portInst.setIndex(0);
            }
            String portInstName = portInst.getPortInstNameFromPort();
            portInst.setName(uniquifier.uniquifyName(portInstName));
        }

        public void add() {
            if (portInst.getCellInst() != null) {
                portInst.getCellInst().addPortInst(portInst);
            }
            net.addPortInst(portInst);
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

        public abstract EDIFLibrary addToNetlist(EDIFNetlist netlist, EDIFLibrary currentLibrary, Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName, EDIFReadLegalNameCache cache);
    }

    static class LibraryResult extends LibraryOrCellResult {
        private final EDIFLibrary library;

        LibraryResult(EDIFToken token, EDIFLibrary library) {
            super(token);
            this.library = library;
        }

        @Override
        public EDIFLibrary addToNetlist(EDIFNetlist netlist, EDIFLibrary currentLibrary, Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName, EDIFReadLegalNameCache cache) {
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
        public EDIFLibrary addToNetlist(EDIFNetlist netlist, EDIFLibrary currentLibrary, Map<EDIFLibrary, Map<String, EDIFCell>> cellsByLegalName, EDIFReadLegalNameCache cache) {
            if (currentLibrary == null) {
                throw new IllegalStateException("Saw first cell before first library");
            }
            currentLibrary.addCellRenameDuplicates(cell, cache.getEDIFRename(cell));

            cellsByLegalName.computeIfAbsent(currentLibrary, x-> new HashMap<>()).put(cache.getLegalEDIFName(cell), cell);

            return currentLibrary;
        }
    }


    @Override
    protected void linkCellInstToCell(EDIFCellInst inst, String cellref, String libraryref, EDIFCell currentCell) {
        linkCellReference.add(new CellReferenceData(inst::setCellType, cellref, libraryref, currentCell));
    }
}
