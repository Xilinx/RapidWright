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

package com.xilinx.rapidwright.timing.family;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.TileTypeEnum;

/**
 * The node classes of the wires of each device, as the node class enum of a family (e.g. VersalNodeClass)
 * assigns them: a class per wire of each tile type, which is that of the nodes with that base wire. Computed once
 * per device and kept for the life of the process, as Device's own cache of loaded devices: one entry (a byte per
 * wire of each tile type, about a megabyte) per device name looked at, so bounded by the devices a process opens.
 * Tile types are keyed per device since the same tile type can have different wires in different devices.
 */
public final class WireClasses {

    /** Classifies the wires of a tile type */
    @FunctionalInterface
    public interface Classifier {
        /**
         * Classifies a wire.
         * @param ic The intent code of the wire.
         * @param wireName The name of the wire.
         * @return The ordinal of the class of nodes with such a base wire, or -1 for a routing wire that the model
         * does not know.
         */
        int classify(IntentCode ic, String wireName);
    }

    private final String model;
    private final Classifier classifier;
    private final Map<String, byte[][]> classes = new ConcurrentHashMap<>();

    /**
     * Creates the cache of a model.
     * @param model The name of the model in the error about an unknown wire, e.g. "Versal".
     * @param classifier The classifier of the wires.
     */
    public WireClasses(String model, Classifier classifier) {
        this.model = model;
        this.classifier = classifier;
    }

    /**
     * Gets the classes of the wires of a device, as nodes with those base wires would have.
     * @param device The device in question.
     * @return The ordinals of the classes, indexed by tile type ordinal, then wire index.
     * @throws IllegalStateException If a routing wire of the device has no class, which means that the device has
     * wires the model does not know.
     */
    public byte[][] get(Device device) {
        return classes.computeIfAbsent(device.getName(), k -> {
            byte[][] c = new byte[TileTypeEnum.values().length][];
            for (Tile tile : device.getAllTiles()) {
                int type = tile.getTileTypeEnum().ordinal();
                if (c[type] != null) {
                    continue;
                }
                byte[] wireClasses = new byte[tile.getWireCount()];
                for (int i = 0; i < wireClasses.length; i++) {
                    IntentCode ic = tile.getWireIntentCode(i);
                    int cls = classifier.classify(ic, tile.getWireName(i));
                    if (cls < 0) {
                        throw new IllegalStateException("Unknown " + model + " interconnect wire "
                                + tile.getWireName(i) + " (" + ic + ") of " + tile.getTileTypeEnum() + " tiles in "
                                + device.getName());
                    }
                    wireClasses[i] = (byte) cls;
                }
                c[type] = wireClasses;
            }
            return c;
        });
    }
}
