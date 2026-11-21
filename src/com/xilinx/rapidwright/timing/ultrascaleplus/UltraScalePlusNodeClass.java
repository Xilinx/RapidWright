/*
 *
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.timing.ultrascaleplus;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.family.WireClasses;

/**
 * The classes that the UltraScale+ timing model groups nodes into. Nodes of a class share one set
 * of delay parameters. General interconnect wires are split by type and direction; the remaining
 * classes follow the node's intent code and, where one intent code covers nodes of different roles,
 * its base wire name. Nodes that carry no delay in the model, such as the nodes of block input
 * pins and clock nodes, are {@link #OTHER}: of the nodes with interconnect intent codes, only the pin feeds of
 * the clock leaf sites (CLK_LEAF_SITES_*: clock enables and cascades of the BUFCE_LEAFs) and the entries from
 * the interconnect into the clock network (INT_RCLK_TO_CLK_*) of RCLK_INT tiles.
 * <p>
 * Unlike Versal, the output and input pins of slices share their node with the interconnect: a
 * slice output pin's node is a {@link #CLE_OUTPUT} node and a slice input pin's node an
 * {@link #IMUX}, {@link #CTRL}, {@link #BYPASS} or {@link #BOUNCE_FT} node.
 */
enum UltraScalePlusNodeClass {
    OTHER,
    /** Single-length wires, e.g. EE1_E_BEG0, NN1_W_BEG3 */
    SINGLE_E, SINGLE_W, SINGLE_N, SINGLE_S,
    /** Single-length connections between the two halves of an INT tile (INT_INT_SDQ_*) */
    SINGLE_INT,
    DOUBLE_E, DOUBLE_W, DOUBLE_N, DOUBLE_S,
    HQUAD_E, HQUAD_W,
    VQUAD_N, VQUAD_S,
    HLONG_E, HLONG_W,
    VLONG_N, VLONG_S,
    /** INT_NODE_SDQ_*: local nodes that feed general wires */
    SDQNODE,
    /** SDQNODE_[EW]_*_FT*: SDQ nodes that feed through to a vertically adjacent INT tile */
    SDQNODE_FT,
    /**
     * INT_NODE_IMUX_*: local nodes that feed IMUX, CTRL and bounce nodes; also those of RCLK_INT tiles (intent
     * NODE_PINFEED), which feed the clock enables of the clock leaf sites
     */
    INODE,
    /** INODE_[EW]_*_FT*: INODEs that feed through to a vertically adjacent INT tile */
    INODE_FT,
    /** INT_NODE_GLOBAL_*: local nodes that feed CTRL nodes */
    GNODE,
    /** IMUX_[EW]*: input multiplexers (the pin nodes of slice data inputs) */
    IMUX,
    /** CTRL_[EW]*: control inputs (clock, clock enable, set/reset) */
    CTRL,
    /** BYPASS_[EW]*: bounce nodes that are also the pin nodes of slice bypass inputs */
    BYPASS,
    /** BOUNCE_[EW]_*_FT*: bounce nodes that feed through to a vertically adjacent INT tile */
    BOUNCE_FT,
    /** Slice output pins (their nodes reach into the INT tile) */
    CLE_OUTPUT,
    /** Output pins of other sites, e.g. DSP, RAMB, URAM and Laguna flip-flops */
    OUTPUT,
    /** LOGIC_OUTS_[LR]* of INT_INTF tiles: block outputs into the interconnect */
    INT_INTF,
    OPTDELAY,
    /** Dedicated (cascade) connections, including carry chains */
    DEDICATED,
    /** LAG_MUX_ATOM_*_TXOUT: Laguna transmit multiplexers that drive super long lines */
    LAGUNA_TXOUT,
    /** UBUMP*: super long lines between the Laguna tiles of adjacent SLRs */
    LAGUNA_DATA,
    /** LAG_LAGUNA_SITE_*_RXD*: Laguna receive pins that super long lines land on */
    LAGUNA_RXD,
    /** RXD*: Laguna receive nodes that enter the interconnect */
    LAGUNA_RX;

    private static final UltraScalePlusNodeClass[] VALUES = values();

    private static final WireClasses WIRE_CLASSES = new WireClasses("UltraScale+", (ic, name) -> {
        UltraScalePlusNodeClass c = classify(ic, name);
        return c == OTHER && isInterconnectIntent(ic) && !(ic == IntentCode.NODE_PINFEED && isClockPinFeed(name))
                ? -1 : c.ordinal();
    });

    /**
     * Gets the class of a node.
     * @param node The node in question.
     * @return The node's class.
     */
    static UltraScalePlusNodeClass of(Node node) {
        return VALUES[ordinalOf(node)];
    }

    /**
     * Gets the ordinal of the class of a node.
     * @param node The node in question.
     * @return The ordinal of the node's class.
     */
    static int ordinalOf(Node node) {
        byte[][] classes = getWireClasses(node.getTile().getDevice());
        return classes[node.getTile().getTileTypeEnum().ordinal()][node.getWireIndex()];
    }

    /**
     * Gets the classes of the wires of a device, as nodes with those base wires would have (see
     * {@link WireClasses}).
     * @param device The device in question.
     * @return The ordinals of the classes, indexed by tile type ordinal, then wire index.
     * @throws IllegalStateException If a wire with an interconnect intent code has no class (other than the
     * clock pin feeds of RCLK_INT tiles, see {@link #OTHER}), which means that the device has wires this model does
     * not know.
     */
    public static byte[][] getWireClasses(Device device) {
        return WIRE_CLASSES.get(device);
    }

    /** Whether a NODE_PINFEED wire of an RCLK_INT tile is a pin feed of the clock network, which carries no delay */
    private static boolean isClockPinFeed(String wireName) {
        return wireName.startsWith("CLK_LEAF_SITES_") || wireName.startsWith("INT_RCLK_TO_CLK_");
    }

    private static boolean isInterconnectIntent(IntentCode ic) {
        switch (ic) {
            case NODE_SINGLE:
            case NODE_DOUBLE:
            case NODE_HQUAD:
            case NODE_VQUAD:
            case NODE_HLONG:
            case NODE_VLONG:
            case NODE_LOCAL:
            case NODE_PINFEED:
            case NODE_PINBOUNCE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Classifies the base wire of a node.
     * @param ic The intent code of the wire.
     * @param wireName The name of the wire.
     * @return The class of nodes with such a base wire.
     */
    public static UltraScalePlusNodeClass classify(IntentCode ic, String wireName) {
        switch (ic) {
            case NODE_SINGLE:
                if (wireName.startsWith("INT_INT_SDQ_")) {
                    return SINGLE_INT;
                }
                return direction(wireName, SINGLE_E, SINGLE_W, SINGLE_N, SINGLE_S);
            case NODE_DOUBLE:
                return direction(wireName, DOUBLE_E, DOUBLE_W, DOUBLE_N, DOUBLE_S);
            case NODE_HQUAD:
                return wireName.startsWith("EE") ? HQUAD_E : HQUAD_W;
            case NODE_VQUAD:
                return wireName.startsWith("NN") ? VQUAD_N : VQUAD_S;
            case NODE_HLONG:
                return wireName.startsWith("EE") ? HLONG_E : HLONG_W;
            case NODE_VLONG:
                return wireName.startsWith("NN") ? VLONG_N : VLONG_S;
            case NODE_LOCAL:
                if (wireName.startsWith("INT_NODE_SDQ_")) {
                    return SDQNODE;
                }
                if (wireName.startsWith("SDQNODE_")) {
                    return SDQNODE_FT;
                }
                if (wireName.startsWith("INT_NODE_IMUX_")) {
                    return INODE;
                }
                if (wireName.startsWith("INODE_")) {
                    return INODE_FT;
                }
                if (wireName.startsWith("INT_NODE_GLOBAL_")) {
                    return GNODE;
                }
                return OTHER;
            case NODE_PINFEED:
                if (wireName.startsWith("IMUX_")) {
                    return IMUX;
                }
                if (wireName.startsWith("CTRL_")) {
                    return CTRL;
                }
                if (wireName.startsWith("INT_NODE_IMUX_")) {
                    // Those of RCLK_INT tiles, which drive the clock enables of the clock leaf sites
                    return INODE;
                }
                // The clock pin feeds of RCLK_INT tiles, e.g. CLK_LEAF_SITES_*_CE_INT (see isClockPinFeed())
                return OTHER;
            case NODE_PINBOUNCE:
                if (wireName.startsWith("BYPASS_")) {
                    return BYPASS;
                }
                return wireName.startsWith("BOUNCE_") ? BOUNCE_FT : OTHER;
            case NODE_CLE_OUTPUT:
                // COUT goes up the carry chain only
                return wireName.endsWith("_COUT") ? DEDICATED : CLE_OUTPUT;
            case NODE_OUTPUT:
                return OUTPUT;
            case NODE_INT_INTERFACE:
                return INT_INTF;
            case NODE_OPTDELAY:
                return OPTDELAY;
            case NODE_DEDICATED:
                return DEDICATED;
            case NODE_LAGUNA_OUTPUT:
                return wireName.startsWith("RXD") ? LAGUNA_RX : LAGUNA_TXOUT;
            case NODE_LAGUNA_DATA:
                return LAGUNA_DATA;
            case INTENT_DEFAULT:
                if (wireName.startsWith("LAG_LAGUNA_SITE_") && wireName.contains("_RXD")) {
                    return LAGUNA_RXD;
                }
                return OTHER;
            default:
                // Includes the clock network (NODE_GLOBAL_*)
                return OTHER;
        }
    }

    // General wires are named <EE|WW|NN|SS><length>_..., e.g. EE2_E_BEG0
    private static UltraScalePlusNodeClass direction(String wireName, UltraScalePlusNodeClass e, UltraScalePlusNodeClass w,
                                                     UltraScalePlusNodeClass n, UltraScalePlusNodeClass s) {
        if (wireName.startsWith("EE")) {
            return e;
        }
        if (wireName.startsWith("WW")) {
            return w;
        }
        return wireName.startsWith("NN") ? n : s;
    }
}
