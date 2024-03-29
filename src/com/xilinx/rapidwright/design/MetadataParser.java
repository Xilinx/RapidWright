/*
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
package com.xilinx.rapidwright.design;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Parses metadata file generated by the Tcl command in rapidwright.tcl/generate_metadata.
 *
 * Created on: May 10, 2016
 */
public class MetadataParser {

    private String line;

    private String nextLine;

    private int lineNumber;

    private String[] tokens;

    private String[] nextTokens;

    private BufferedReader br;

    private String fileName;

    private MDParserState currState = MDParserState.BLOCK_BEGIN;

    private static final String BEGIN = "begin";
    private static final String BLOCK = "block";
    private static final String NAME = "name";
    private static final String CLOCKS = "clocks";
    private static final String PBLOCKS = "pblocks";
    private static final String INPUTS = "inputs";
    private static final String OUTPUTS = "outputs";
    private static final String PBLOCK = "pblock";
    private static final String GRID_RANGES = "grid_ranges";
    private static final String CLOCK = "clock";
    private static final String PERIOD = "period";
    private static final String END = "end";
    private static final String INPUT = "input";
    private static final String OUTPUT = "output";
    private static final String NETNAME = "netname";
    private static final String NUMPRIMS = "numprims";
    private static final String TYPE = "type";
    private static final String MAXDELAY = "maxdelay";
    private static final String MAXNETDELAY = "maxnetdelay";
    private static final String MAXPATH = "maxpath";
    private static final String CONNECTIONS = "connections";
    private static final String PATH = "path";
    private static final String PIN = "pin";
    private static final String PORT = "port";
    private static final String PPLOCS = "pplocs";
    private static final String _ARROW = "-->";

    private enum MDParserState {BLOCK_BEGIN, BLOCK_NAME, BLOCK_PBLOCKS, BLOCK_CLOCKS, BLOCK_INPUTS, BLOCK_OUTPUTS,
                                PBLOCK_BEGIN, PBLOCK_NAME, PBLOCK_GRID_RANGES, PBLOCK_END,
                                CLOCK_BEGIN, CLOCK_NAME, CLOCK_PERIOD, CLOCK_END,
                                PORT_BEGIN, PORT_NAME, PORT_NET, PORT_NUMPRIMS, PORT_TYPE, PORT_MAXDELAY,
                                PORT_CONNS_BEGIN, PORT_CONNS_PIN, PORT_CONNS_END, PORT_END, PORT_PPLOCS, BLOCK_END};

    private Device dev;

    private Module m;

    public MetadataParser(Device dev, Module m) {
        this.dev = dev;
        this.m = m;
    }

    public String getOriginalInstName(String metadataFileName) {
        File dir = new File(metadataFileName).getParentFile();
        File xciFile = new File(dir.getAbsolutePath() + "/" + dir.getName() + ".xci");
        try {
            BufferedReader br = new BufferedReader(new FileReader(xciFile));
            String line = null;
            while ((line = br.readLine()) != null) {
                if (line.contains("instanceName")) {
                    br.close();
                    return line.substring(line.indexOf('>')+1, line.indexOf("</", 0));
                }
            }
            br.close();
        } catch (FileNotFoundException e) {
            return null;
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    public void parse(String metadataFileName) {
        String originalInstName = getOriginalInstName(metadataFileName);
        this.fileName = metadataFileName;
        int clockCount = -1;
        int pblockCount = -1;
        int inputCount = -1;
        int outputCount = -1;
        int currPrimCount = -1;
        Port currPort = null;
        String currPBlockName = null;
        String currClockName = null;
        Net currPortNet = null;
        br = FileTools.getProperInputStream(metadataFileName);
        getNextLine();
        getNextLine();
        outer: while (line != null) {
            switch(currState) {
                case BLOCK_BEGIN:{
                    expect(BEGIN, tokens[0]);
                    expect(BLOCK, tokens[1]);
                    currState = MDParserState.BLOCK_NAME;
                    break;
                }
                case BLOCK_NAME:{
                    expect(NAME, tokens[1]);
                    if (originalInstName != null) expect(originalInstName,tokens[2]);
                    if (nextTokens[1].equals(CLOCKS)) {
                        currState = MDParserState.BLOCK_CLOCKS;
                    } else {
                        currState = MDParserState.BLOCK_PBLOCKS;
                    }
                    break;
                }
                case BLOCK_PBLOCKS:{
                    expect(PBLOCKS, tokens[1]);
                    pblockCount = Integer.parseInt(tokens[2]);
                    currState = MDParserState.BLOCK_CLOCKS;
                    break;
                }
                case BLOCK_CLOCKS:{
                    expect(CLOCKS, tokens[1]);
                    clockCount = Integer.parseInt(tokens[2]);
                    currState = MDParserState.BLOCK_INPUTS;
                    break;
                }
                case BLOCK_INPUTS:{
                    expect(INPUTS, tokens[1]);
                    inputCount = Integer.parseInt(tokens[2]);
                    currState = MDParserState.BLOCK_OUTPUTS;
                    break;
                }
                case BLOCK_OUTPUTS:{
                    expect(OUTPUTS, tokens[1]);
                    outputCount = Integer.parseInt(tokens[2]);
                    if (nextTokens[2].equals(PBLOCK)) {
                        currState = MDParserState.PBLOCK_BEGIN;
                    } else if (nextTokens[2].equals(CLOCK)) {
                        currState = MDParserState.CLOCK_BEGIN;
                    } else {
                        currState = MDParserState.PORT_BEGIN;
                    }
                    break;
                }

                case PBLOCK_BEGIN:{
                    expect(BEGIN,tokens[1]);
                    expect(PBLOCK,tokens[2]);
                    currState = MDParserState.PBLOCK_NAME;
                    break;
                }
                case PBLOCK_NAME:{
                    expect(NAME,tokens[1]);
                    currPBlockName = tokens[2];
                    currState = MDParserState.PBLOCK_GRID_RANGES;
                    break;
                }
                case PBLOCK_GRID_RANGES:{
                    expect(GRID_RANGES, tokens[1]);
                    m.setPBlock(line.replace(GRID_RANGES, "").trim());
                    currState = MDParserState.PBLOCK_END;
                    break;
                }
                case PBLOCK_END:{
                    expect(END, tokens[1]);
                    expect(PBLOCK, tokens[2]);
                    pblockCount--;
                    if (pblockCount == 0) {
                        if (clockCount > 0) {
                            currState = MDParserState.CLOCK_BEGIN;
                        } else {
                            currState = MDParserState.PORT_BEGIN;
                        }
                    } else {
                        currState = MDParserState.PBLOCK_BEGIN;
                    }
                    break;
                }

                case CLOCK_BEGIN:{
                    expect(BEGIN,tokens[1]);
                    expect(CLOCK,tokens[2]);
                    currState = MDParserState.CLOCK_NAME;
                    break;
                }
                case CLOCK_NAME:{
                    expect(NAME,tokens[1]);
                    currClockName = tokens[2];
                    currState = MDParserState.CLOCK_PERIOD;
                    break;
                }
                case CLOCK_PERIOD:{
                    expect(PERIOD, tokens[1]);
                    m.addClock(currClockName, Float.parseFloat(tokens[2]));
                    currState = MDParserState.CLOCK_END;
                    break;
                }
                case CLOCK_END:{
                    expect(END, tokens[1]);
                    expect(CLOCK, tokens[2]);
                    clockCount--;
                    if (clockCount == 0) {
                        currState = MDParserState.PORT_BEGIN;
                    } else {
                        currState = MDParserState.CLOCK_BEGIN;
                    }
                    break;
                }
                case PORT_BEGIN:{
                    expect(BEGIN,tokens[1]);
                    currPort = new Port();
                    if (inputCount > 0) {
                        expect(INPUT, tokens[2]);
                        inputCount--;
                        currPort.setOutputPort(false);
                    } else if (outputCount > 0) {
                        expect(OUTPUT, tokens[2]);
                        currPort.setOutputPort(true);
                        outputCount--;
                    } else {
                        throw new RuntimeException("More ports than expected");
                    }
                    currState = MDParserState.PORT_NAME;
                    break;
                }
                case PORT_NAME:{
                    expect(NAME,tokens[1]);
                    currPort.setName(tokens[2]);
                    if (nextTokens[1].equals(TYPE)) {
                        currState = MDParserState.PORT_TYPE;
                    } else if (nextTokens[1].equals(PPLOCS)) {
                        currState = MDParserState.PORT_PPLOCS;
                    } else {
                        currState = MDParserState.PORT_NET;
                    }
                    break;
                }
                case PORT_PPLOCS:{
                    expect(PPLOCS, tokens[1]);
                    currPort.setPartitionPinLoc(dev.getTile(tokens[2]));
                    if (nextTokens[1].equals(TYPE)) {
                        currState = MDParserState.PORT_TYPE;
                    } else {
                        currState = MDParserState.PORT_NET;
                    }
                    break;
                }
                case PORT_NET:{
                    expect(NETNAME, tokens[1]);
                    currPortNet = m.getNet(tokens[2]);
                    /*if (currPortNet == null) {
                        expect("<Net Name>",tokens[2]);
                    }*/
                    currState = MDParserState.PORT_NUMPRIMS;
                    break;
                }
                case PORT_NUMPRIMS:{
                    expect(NUMPRIMS, tokens[1]);
                    currPrimCount = Integer.parseInt(tokens[2]);
                    currState = MDParserState.PORT_TYPE;
                    break;
                }
                case PORT_TYPE:{
                    expect(TYPE, tokens[1]);
                    expect(currPort.isOutPort() ? OUTPUT : INPUT,tokens[2]);
                    String type = tokens[3];
                    if (type.equals(CLOCK)) {
                        if (tokens[4].equals("local")) currPort.setType(PortType.LOCAL_CLOCK);
                        else if (tokens[4].equals("global")) currPort.setType(PortType.GLOBAL_CLOCK);
                        else if (tokens[4].equals("regional")) currPort.setType(PortType.REGIONAL_CLOCK);
                        else expect("<local|global|regional>", tokens[4]);
                    }
                    else if (type.equals("signal")) currPort.setType(PortType.SIGNAL);
                    else if (type.equals("ground")) currPort.setType(PortType.GROUND);
                    else if (type.equals("power")) currPort.setType(PortType.POWER);
                    else if (type.equals("unconnected")) currPort.setType(PortType.UNCONNECTED);
                    else if (type.equals("dontcare")) currPort.setType(PortType.DONT_CARE);
                    else if (type.equals("unknown")) currPort.setType(PortType.UNKNOWN);
                    else expect("<signal|ground|power|dontcare|unknown>",tokens[3]);
                    currState = MDParserState.PORT_MAXDELAY;
                    break;
                }
                case PORT_MAXDELAY:{
                    expect(MAXDELAY,tokens[1]);
                    currPort.setWorstCasePortDelay(Float.parseFloat(tokens[2]));
                    if (nextTokens[1].equals(END)) {
                        currState = MDParserState.PORT_END;
                    } else {
                        currState = MDParserState.PORT_CONNS_BEGIN;
                    }
                    break;
                }
                case PORT_CONNS_BEGIN:{
                    expect(BEGIN,tokens[1]);
                    expect(CONNECTIONS, tokens[2]);
                    if (nextTokens[1].equals(END)) {
                        currPort.setType(PortType.UNCONNECTED);
                        currState = MDParserState.PORT_CONNS_END;
                    }
                    else currState = MDParserState.PORT_CONNS_PIN;
                    break;
                }
                case PORT_CONNS_PIN:{
                    if (tokens[1].equals(PIN)) {
                        String logPinName = tokens[2];
                        if (tokens.length > 4) {
                            String siteName = tokens.length > 3 ? tokens[3] : null;
                            String sitePinName = tokens.length > 4 ? tokens[4] : null;
                            String pinName = sitePinName.substring(sitePinName.indexOf('/')+1);
                            if (tokens.length > 5 && currPort.isOutPort()) {
                                SiteInst i = m.getSiteInstAtSite(dev.getSite(siteName));
                                if (i.getSitePinInst(pinName) == null) {
                                    sitePinName = tokens.length > 5 ? tokens[5] : sitePinName;
                                    pinName = sitePinName.substring(sitePinName.indexOf('/')+1);
                                }
                            }
                            SiteInst si = m.getSiteInstAtSite(dev.getSite(siteName));

                            SitePinInst p = si.getSitePinInst(pinName);
                            if (p == null) {
                                Net n = si.getNetFromSiteWire(pinName);
                                if (n == null) {
                                    // Check if alternate site type
                                    if (si.getSiteTypeEnum() != si.getSite().getSiteTypeEnum()) {
                                        String altPinName = si.getAlternateSitePinName(pinName);
                                        n = si.getNetFromSiteWire(altPinName);
                                    }
                                    if (n == null)
                                        throw new RuntimeException("ERROR: Don't know net of " + sitePinName);
                                }
                                p = new SitePinInst(si.getSite().isOutputPin(pinName), pinName, si);
                                n.addPin(p);
                            }
                            //Skip inputs on an output port. Happens if the output signal is used internally
                            if (p.isOutPin() == currPort.isOutPort()) {
                                currPort.addSitePinInst(p);
                            } else {
                                if (!currPort.isOutPort()) {
                                    throw new RuntimeException("Output pin on input port "+currPort.getName()+": "+p);
                                }
                            }
                        }
                    } else if (tokens[1].equals(PORT)) {
                        currPort.addPassThruPortName(tokens[2]);
                    } else {
                        expect("<pin|port>",tokens[1]);
                    }
                    if (nextTokens[1].equals(END)) {
                        currState = MDParserState.PORT_CONNS_END;
                    }
                    break;
                }
                case PORT_CONNS_END:{
                    expect(END, tokens[1]);
                    expect(CONNECTIONS, tokens[2]);
                    currState = MDParserState.PORT_END;
                    break;
                }
                case PORT_END:{
                    expect(END,tokens[1]);
                    expect(currPort.isOutPort() ? OUTPUT : INPUT, tokens[2]);
                    m.addPort(currPort);
                    currPort = null;
                    if (nextTokens[1].equals(BEGIN)) {
                        currState = MDParserState.PORT_BEGIN;
                    }
                    else if (nextTokens[0].equals(END)) {
                        currState = MDParserState.BLOCK_END;
                    }
                    break;
                }
                case BLOCK_END:{
                    expect(END,tokens[0]);
                    expect(BLOCK,tokens[1]);
                    break outer;
                }
                default:{
                    break;
                }
            }
            getNextLine();
        }
        try {
            br.close();
        } catch (IOException e) {
            throw new RuntimeException("ERROR: IOException encountered on closing file " + fileName + "\nStack Trace:");
        }
    }

    private boolean expect(String expected, String found) {
        if (!expected.equals(found)) {
            throw new RuntimeException("\nERROR: While parsing "+fileName+":\n   '" +
                line + "' (line number " + lineNumber + ")\n" + "   Expected: '" +
                    expected + "'\n      Found: '" + found + "'\nStack Trace:");
        }
        return true;
    }

    private void getNextLine() {
        try {
            tokens = nextTokens;
            line = nextLine;
            nextLine = br.readLine();
            lineNumber++;
            while (nextLine != null && (nextLine.trim().isEmpty() || nextLine.startsWith("#"))) {
                nextLine = br.readLine();
                lineNumber++;
            }
            if (nextLine != null) {
                nextLine = nextLine.replace("\\\\", "\\");
                nextTokens = nextLine.split("\\s+");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        // Test parser
        if (args.length != 2) {
            System.out.println("USAGE: <module.dcp> <metadata.txt>");
            return;
        }
        Design d = Design.readCheckpoint(args[0]);
        Module m = new Module(d,args[1]);
    }
}
