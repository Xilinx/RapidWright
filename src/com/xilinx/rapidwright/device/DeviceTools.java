/*
 * Copyright (c) 2022-2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD AECG Research Labs.
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

package com.xilinx.rapidwright.device;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.util.Utils;

public class DeviceTools {

    /**
     * Maps from the Name Root to a name that is equal for all relocatable tiles
     */
    private static Map<String, String> versalHalfFSRTileTypes;

    /**
     * Map for each series site type to have an input to trace back to a
     * corresponding INT tile
     */
    private static HashMap<Series, HashMap<SiteTypeEnum, String>> traceIntPinNames;

    private static EnumSet<SiteTypeEnum> versalIRIQuadTypes;

    static {

        versalHalfFSRTileTypes = new HashMap<>();
        // URAMs
        versalHalfFSRTileTypes.put("URAM_ROCF_BL_TILE", "URAM_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("URAM_ROCF_TL_TILE", "URAM_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("URAM_LOCF_BL_TILE", "URAM_LOCF_?L_TILE");
        versalHalfFSRTileTypes.put("URAM_LOCF_TL_TILE", "URAM_LOCF_?L_TILE");
        // DSPs
        versalHalfFSRTileTypes.put("DSP_ROCF_B_TILE", "DSP_ROCF_?_TILE");
        versalHalfFSRTileTypes.put("DSP_ROCF_T_TILE", "DSP_ROCF_?_TILE");
        // INTFs
        versalHalfFSRTileTypes.put("INTF_ROCF_BL_TILE", "INTF_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("INTF_ROCF_TL_TILE", "INTF_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("INTF_ROCF_BR_TILE", "INTF_ROCF_?R_TILE");
        versalHalfFSRTileTypes.put("INTF_ROCF_TR_TILE", "INTF_ROCF_?R_TILE");
        // BRAMs
        versalHalfFSRTileTypes.put("BRAM_ROCF_BL_TILE", "BRAM_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("BRAM_ROCF_TL_TILE", "BRAM_ROCF_?L_TILE");
        versalHalfFSRTileTypes.put("BRAM_ROCF_BR_TILE", "BRAM_ROCF_?R_TILE");
        versalHalfFSRTileTypes.put("BRAM_ROCF_TR_TILE", "BRAM_ROCF_?R_TILE");
        versalHalfFSRTileTypes.put("BRAM_LOCF_BR_TILE", "BRAM_LOCF_?R_TILE");
        versalHalfFSRTileTypes.put("BRAM_LOCF_TR_TILE", "BRAM_LOCF_?R_TILE");

        // URAMs on 7 series
        versalHalfFSRTileTypes.put("URAM_URAM_DELAY_FT", "URAM_URAM_FT");

        traceIntPinNames = new HashMap<>();
        HashMap<SiteTypeEnum, String> series7 = new HashMap<>();
        HashMap<SiteTypeEnum, String> ultrascale = new HashMap<>();
        HashMap<SiteTypeEnum, String> ultrascalePlus = new HashMap<>();
        HashMap<SiteTypeEnum, String> versal = new HashMap<>();
        traceIntPinNames.put(Series.Series7, series7);
        traceIntPinNames.put(Series.UltraScale, ultrascale);
        traceIntPinNames.put(Series.UltraScalePlus, ultrascalePlus);
        traceIntPinNames.put(Series.Versal, versal);

        // SLICEL / SLICEM
        series7.put(SiteTypeEnum.SLICEL, "CLK");
        series7.put(SiteTypeEnum.SLICEM, "CLK");
        ultrascale.put(SiteTypeEnum.SLICEL, "CLK_B1");
        ultrascale.put(SiteTypeEnum.SLICEM, "CLK_B1");
        ultrascalePlus.put(SiteTypeEnum.SLICEL, "CLK1");
        ultrascalePlus.put(SiteTypeEnum.SLICEM, "CLK1");
        versal.put(SiteTypeEnum.SLICEL, "AX"); // CLK/RST sometimes trace to CLE_BC (local INT btw b2b
                                               // slices)
        versal.put(SiteTypeEnum.SLICEM, "AX");

        // DSP
        series7.put(SiteTypeEnum.DSP48E1, "CLK");
        ultrascale.put(SiteTypeEnum.DSP48E2, "CLK_B");
        ultrascalePlus.put(SiteTypeEnum.DSP48E2, "CLK");

        // BRAM
        series7.put(SiteTypeEnum.RAMBFIFO36E1, "CLKARDCLKL");
        ultrascale.put(SiteTypeEnum.RAMBFIFO36, "CLKAL_X");
        ultrascalePlus.put(SiteTypeEnum.RAMBFIFO36, "CLKAL");

        series7.put(SiteTypeEnum.RAMB36E1, "CLKARDCLKL");
        ultrascale.put(SiteTypeEnum.RAMB36, "CLKAL_X");
        ultrascalePlus.put(SiteTypeEnum.RAMB36, "CLKAL");
        versal.put(SiteTypeEnum.RAMB36, "ADDRARDADDRL_0_");

        series7.put(SiteTypeEnum.FIFO36E1, "RDCLKL");
        ultrascale.put(SiteTypeEnum.FIFO36, "CLKAL_X");
        ultrascalePlus.put(SiteTypeEnum.FIFO36, "CLKAL");

        ultrascale.put(SiteTypeEnum.RAMBFIFO18, "CLKAL_X");
        ultrascalePlus.put(SiteTypeEnum.RAMBFIFO18, "CLKAL");

        series7.put(SiteTypeEnum.RAMB18E1, "CLKARDCLK");
        ultrascale.put(SiteTypeEnum.RAMB180, "CLKAL_X");
        ultrascalePlus.put(SiteTypeEnum.RAMB180, "CLKAL");
        versal.put(SiteTypeEnum.RAMB18_U, "ADDRARDADDR_0_");

        series7.put(SiteTypeEnum.FIFO18E1, "RDCLK");
        ultrascale.put(SiteTypeEnum.FIFO18_0, "CLKAL_X");
        ultrascalePlus.put(SiteTypeEnum.FIFO18_0, "CLKAL");

        ultrascale.put(SiteTypeEnum.RAMB181, "CLKAU_X");
        ultrascalePlus.put(SiteTypeEnum.RAMB181, "CLKAU");
        versal.put(SiteTypeEnum.RAMB18_L, "ADDRARDADDR_0_");

        ultrascalePlus.put(SiteTypeEnum.URAM288, "CLK");

        ultrascale.put(SiteTypeEnum.LAGUNA, "TX_CLK");
        ultrascalePlus.put(SiteTypeEnum.LAGUNA, "TX_CLK");

        for (SiteTypeEnum t : new SiteTypeEnum[] { SiteTypeEnum.OLOGICE2, SiteTypeEnum.OLOGICE3,
                                SiteTypeEnum.OSERDESE2 }) {
            series7.put(t, "D1");
        }
        for (SiteTypeEnum t : new SiteTypeEnum[] { SiteTypeEnum.PHASER_OUT_PHY,
                                SiteTypeEnum.PHASER_OUT, SiteTypeEnum.PHASER_OUT_ADV,
                                SiteTypeEnum.PHASER_IN_PHY, SiteTypeEnum.PHASER_IN,
                                SiteTypeEnum.PHASER_IN_ADV }) {
            series7.put(t, "SYSCLK");
        }
        for (SiteTypeEnum t : new SiteTypeEnum[] { SiteTypeEnum.IOB33, SiteTypeEnum.IOB33M,
                                SiteTypeEnum.IOB33S }) {
            series7.put(t, "INTERMDISABLE");
        }
        series7.put(SiteTypeEnum.GTHE2_COMMON, "DRPCLK");
        series7.put(SiteTypeEnum.PHY_CONTROL, "PHYCLK");

        ultrascalePlus.put(SiteTypeEnum.HPIOB_M, "IBUF_DISABLE");
        ultrascalePlus.put(SiteTypeEnum.HPIOB_S, "IBUF_DISABLE");
        ultrascalePlus.put(SiteTypeEnum.BITSLICE_RX_TX, "TX_D0");

        ultrascalePlus.put(SiteTypeEnum.BUFG_GT, "CEMASK");

        versal.put(SiteTypeEnum.DSP58_PRIMARY, "A_0_");
        versal.put(SiteTypeEnum.DSP58_CPLX, "A_CPLX_L_0_");
        versal.put(SiteTypeEnum.IRI_QUAD_EVEN, "IMUX_IN0");
        versal.put(SiteTypeEnum.IRI_QUAD_ODD, "IMUX_IN0");
        versal.put(SiteTypeEnum.URAM288, "ADDR_A_0_");
        versalIRIQuadTypes = EnumSet.of(SiteTypeEnum.DSP58_PRIMARY, SiteTypeEnum.DSP58_CPLX,
                SiteTypeEnum.URAM288, SiteTypeEnum.RAMB36, SiteTypeEnum.RAMB18_L,
                SiteTypeEnum.RAMB18_U);
    }

    public static Map<String, Tile[][]> createTileByRootNameCache(Device device) {
        // Figure out the array dimensions we need
        Map<String, Integer> rootNameMaxX = new HashMap<>();
        Map<String, Integer> rootNameMaxY = new HashMap<>();
        for (Tile t : device.getAllTiles()) {
            final String p = versalHalfFSRTileTypes.getOrDefault(t.getRootName(), t.getRootName());
            Integer existingX = rootNameMaxX.get(p);
            if (existingX == null || existingX < t.getTileXCoordinate()) {
                rootNameMaxX.put(p, t.getTileXCoordinate());
            }
            Integer existingY = rootNameMaxY.get(p);
            if (existingY == null || existingY < t.getTileYCoordinate()) {
                rootNameMaxY.put(p, t.getTileYCoordinate());
            }
        }

        // Create the arrays
        Map<String, Tile[][]> tileByRootNameCache = new HashMap<>();
        for (String prefix : rootNameMaxX.keySet()) {
            final int x = rootNameMaxX.get(prefix);
            final int y = rootNameMaxY.get(prefix);
            tileByRootNameCache.put(prefix, new Tile[y + 1][x + 1]);
        }

        // Link Versal Half FSR Tiles
        versalHalfFSRTileTypes.forEach((original, generalized) -> {
            tileByRootNameCache.put(original, tileByRootNameCache.get(generalized));
        });

        // Fill the arrays
        for (Tile t : device.getAllTiles()) {
            final Tile[][] arr = tileByRootNameCache.get(t.getRootName());
            arr[t.getTileYCoordinate()][t.getTileXCoordinate()] = t;
        }

        return tileByRootNameCache;
    }

    private static String pinNameToFollow(Series s, SiteTypeEnum type) {
        return traceIntPinNames.get(s).get(type);
    }

    /**
     * Gets the approximate INT tile to which this site connects. Some site types
     * connect to more than one INT tile, thus the returned INT tile could be
     * arbitrary. Note: This method was moved from {@link Site#getIntTile()} in
     * 2025.1.2.
     * 
     * @return The approximate INT tile connected to this site or null if none could
     *         be found.
     */
    public static Tile getIntTile(Site site) {
        SiteTypeEnum type = site.getSiteTypeEnum();
        Series series = site.getDevice().getSeries();
        String pinName = pinNameToFollow(series, type);
        if (pinName == null && site.getSitePinCount() > 0) {
            // Let's try for a different pin
            pinName = site.getPinName(0);
        }
        int wire = site.getTileWireIndexFromPinName(pinName);
        if (wire < 0) {
            return null;
        }
        Tile tile = site.getTile();
        Node n = Node.getNode(tile, wire);
        if (series == Series.Versal) {
            n = n.getAllUphillNodes().get(0);
            if (versalIRIQuadTypes.contains(type)) {
                // These sites must pass through an IRI_QUAD type first before getting to the
                // INT tile
                SitePin iriQuad = n.getAllUphillNodes().get(0).getSitePin();
                return getIntTile(iriQuad.getSite());
            } else if (type == SiteTypeEnum.DSP58_CPLX) {
                // This must pass through the DSP58_PRIMARY type before getting to the IRI_QUAD
                SitePin dsp58Primary = n.getAllUphillNodes().get(0).getSitePin();
                return getIntTile(dsp58Primary.getSite());
            }
        }
        for (Wire w : n.getAllWiresInNode()) {
            if (Utils.isSwitchBox(w.getTile().getTileTypeEnum())) {
                return w.getTile();
            }
        }
        for (PIP p : n.getTile().getBackwardPIPs(n.getWireIndex())) {
            Wire w = p.getStartWire();
            for (Wire w2 : w.getNode().getAllWiresInNode()) {
                if (Utils.isSwitchBox(w2.getTile().getTileTypeEnum())) {
                    return w2.getTile();
                }
            }
            if (site.getName().startsWith("IOB_")) {
                for (PIP p2 : w.getStartWire().getBackwardPIPs()) {
                    Wire w3 = p2.getStartWire();
                    for (Wire w4 : w3.getNode().getAllWiresInNode()) {
                        if (Utils.isSwitchBox(w4.getTile().getTileTypeEnum())) {
                            return w4.getTile();
                        }
                    }
                }
            }
        }

        for (PIP p : n.getTile().getPIPs(n.getWireIndex())) {
            Wire w = p.getEndWire();
            for (Wire w2 : w.getNode().getAllWiresInNode()) {
                if (Utils.isSwitchBox(w2.getTile().getTileTypeEnum())) {
                    return w2.getTile();
                }
            }
        }

        return null;
    }
}
