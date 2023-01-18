/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.design;

import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * Represents a site and keeps tracks of attributes on its BELs.
 * 
 */
public class SiteConfig {

    private Site site;

    private SiteTypeEnum type;

    private Map<BEL, Map<String, BELAttr>> belAttrs;


    public static SiteConfig createSiteConfig(Site site, SiteTypeEnum type) {
        return new SiteConfig(site, type);
    }

    private SiteConfig(Site site, SiteTypeEnum type) {
        this.site = site;
        this.type = type;
    }

    public Site getSite() {
        return site;
    }

    public SiteTypeEnum getSiteTypeEnum() {
        return type;
    }

    public String getName() {
        return toString();
    }

    public String getSiteName() {
        return site.getName();
    }

    public BELAttr addBELAttribute(BEL bel, String name, String value, Net net) {
        if (belAttrs == null) {
            belAttrs = new HashMap<>();
        }
        Map<String, BELAttr> map = belAttrs.computeIfAbsent(bel, m -> new HashMap<>());
        return map.put(name, new BELAttr(name, value, net));
    }

    public BELAttr getBELAttribute(BEL bel, String name) {
        if (belAttrs == null) return null;
        Map<String, BELAttr> map = belAttrs.get(bel);
        if (map != null) {
            return map.get(name);
        }
        return null;
    }

    public Map<BEL, Map<String, BELAttr>> getBELAttributes() {
        return belAttrs;
    }

    public BELAttr removeBELAttribute(BEL bel, String name) {
        if (belAttrs == null) return null;
        Map<String, BELAttr> map = belAttrs.get(bel);
        if (map != null) {
            return map.remove(name);
        }
        return null;
    }
}
