/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.interchange;

import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.SiteTypeEnum;

import java.util.List;
import java.util.Map;

/**
 * Class for caching BELPin lookups, given a SiteInst object, and string
 * indices for the BEL and BELPin names.
 */
public class BELPinCache {
    private static class Key {
        private final SiteTypeEnum siteTypeEnum;
        private final int belStringIdx;
        private final int pinStringIdx;

        public Key(SiteInst siteInst, int belStringIdx, int pinStringIdx) {
            siteTypeEnum = siteInst.getSiteTypeEnum();
            this.belStringIdx = belStringIdx;
            this.pinStringIdx = pinStringIdx;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + siteTypeEnum.ordinal();
            result = prime * result + belStringIdx;
            result = prime * result + pinStringIdx;
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Key other = (Key) obj;
            return belStringIdx == other.belStringIdx &&
                    pinStringIdx == other.pinStringIdx &&
                    siteTypeEnum == other.siteTypeEnum;
        }
    }

    private final Map<Key, BELPin> map;

    private final List<String> strings;

    public BELPinCache(Map<Key, BELPin> map, List<String> strings) {
        this.map = map;
        this.strings = strings;
    }

    public BELPin getBELPin(SiteInst siteInst, int belStringIdx, int pinStringIdx) {
        Key key = new Key(siteInst, belStringIdx, pinStringIdx);
        return map.computeIfAbsent(key, (k) -> {
            String belName = strings.get(k.belStringIdx);
            BEL bel = siteInst.getBEL(belName);
            if (bel == null) {
                throw new RuntimeException(String.format("ERROR: Failed to get BEL %s", belName));
            }

            String belPinName = strings.get(k.pinStringIdx);
            BELPin belPin = bel.getPin(belPinName);
            if (belPin == null) {
                throw new RuntimeException(String.format("ERROR: Failed to get BEL pin %s/%s", belName, belPinName));
            }

            return belPin;
        });
    }
}
