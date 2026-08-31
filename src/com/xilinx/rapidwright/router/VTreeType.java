/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: RapidWright Development Team
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
package com.xilinx.rapidwright.router;

/**
 * USER_CLOCK_VTREE_TYPE values supported by Vivado for Versal clock routing.
 */
public enum VTreeType {
    BALANCED("balanced", 0),
    INTER_SLR("interSLR", 1),
    INTRA_SLR("intraSLR", 2);

    private final String vivadoName;
    private final int keyBits;

    VTreeType(String vivadoName, int keyBits) {
        this.vivadoName = vivadoName;
        this.keyBits = keyBits;
    }

    public String getVivadoName() {
        return vivadoName;
    }

    public int getKeyBits() {
        return keyBits;
    }

    public static VTreeType fromVivadoName(String name) {
        if (name == null || name.isEmpty()) {
            return BALANCED;
        }
        for (VTreeType type : values()) {
            if (type.vivadoName.equals(name)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported USER_CLOCK_VTREE_TYPE: " + name);
    }

    public static VTreeType fromKeyBits(int keyBits) {
        for (VTreeType type : values()) {
            if (type.keyBits == keyBits) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported USER_CLOCK_VTREE_TYPE key bits: " + keyBits);
    }
}
