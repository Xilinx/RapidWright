/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, AMD Research and Advanced Development.
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

/**
 * An interface to enable methods to operate on the common methods of a Wire
 * object as well as a Node object. At their heart, they both specify a Tile and
 * a wire (in the case of a Node it is the base wire).
 * 
 * @since 2023.2.1
 */
public interface WireInterface {

    /**
     * Gets the tile corresponding to this wire/base wire.
     * 
     * @return The tile of this wire/base wire.
     * @since 2023.2.1
     */
    Tile getTile();

    /**
     * Gets the tile name corresponding to this wire/base wire.
     * 
     * @return The tile name of this wire/base wire.
     * @since 2023.2.1
     */
    default String getTileName() {
        return getTile().getName();
    }

    /**
     * Gets the wire index corresponding to this wire/base wire.
     * 
     * @return The wire index of this wire/base wire.
     * @since 2023.2.1
     */
    int getWireIndex();

    /**
     * Gets the wire name corresponding to this wire/base wire.
     * 
     * @return The wire name of this wire/base wire.
     * @since 2023.2.1
     */
    default String getWireName() {
        return getTile().getWireName(getWireIndex());
    }

    /**
     * Gets the intent code corresponding to this wire/base wire.
     * 
     * @return The intent code of this wire/base wire.
     * @since 2023.2.1
     */
    IntentCode getIntentCode();

    /**
     * Gets the corresponding site pin (if any) to this wire/base wire.
     * 
     * @return The site pin connected to this wire/base wire, or null if not
     *         present.
     * @since 2023.2.1
     */
    SitePin getSitePin();

    /**
     * Produces a hash code based on the tile and wire of the object.
     * 
     * @return A hash code derived from the tile and wire.
     * @since 2023.2.1
     */
    int hashCode();

    /**
     * Checks equality (based on tile and wire values) between this and another
     * WireInterface
     * 
     * @param w The other object to check against for equality.
     * @return True if the two objects have the same tile and wire index, false
     *         otherwise.
     * @since 2023.2.1
     */
    boolean equals(WireInterface w);
}
