/*
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

package com.xilinx.rapidwright.util.route_status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.design.Net;

/**
 * The nets that the route status engine reports (its entries): the flat (logical) nets, then the
 * physical nets without a flat net, then GND and VCC. Their properties are kept in arrays indexed by
 * entry, rather than in an object per net, for memory and locality on designs with millions of nets.
 */
final class Entries {

    /** Per entry: its flat net (null for physical-only and static nets) */
    final List<FlatNet> flats = new ArrayList<>();

    /** Per entry: its physical net (null if none) */
    final List<Net> nets = new ArrayList<>();

    /** Per entry without a flat net (physical-only and static nets): its name */
    final List<String> names = new ArrayList<>();

    /** Per entry, physical nets named after other segments of its flat net, with site routing */
    final Map<Integer, List<Net>> aliasNets = new HashMap<>();

    /** Per entry: its status (see {@link NetStatus}) */
    int[] status;

    /** Per entry: whether it is counted in the summary (not only listed) */
    boolean[] counted;

    /** Per entry, the sorted distinct keys of the nodes used by its PIPs (null if none) */
    long[][] nodeKeys;

    /**
     * Per entry, the sorted distinct keys of the nodes of the input site pins through which its
     * site routing (or that of its aliases) enters a site, i.e. which need routing outside the site
     * even without a SitePinInst (null if none). I/O pads driven by top level ports are excluded.
     */
    long[][] sitePinKeys;

    /**
     * Per entry, the sorted keys of the nodes of the site pins that its placed pins use regardless
     * of its routing (null if none): the output site pins of drivers its routing does not start
     * from, and, for a net without a physical net, the input site pins of loads whose site wires
     * carry another net.
     */
    long[][] driverPinKeys;

    /** Per entry with site pin nodes added to its node keys: how many of them are routing nodes (first) */
    int[] numRoutingKeys;

    /**
     * Per static entry: the keys of the site pins of its macro ties (see
     * {@link SitePinClaims#addStaticLoadSitePins})
     */
    final Map<Integer, long[]> staticTieKeys = new HashMap<>();

    /** Gets the number of entries. */
    int size() {
        return nets.size();
    }

    /** Adds an entry. */
    void add(FlatNet flat, Net net, String name) {
        flats.add(flat);
        nets.add(net);
        names.add(name);
    }

    /** Gets the name of an entry, as Vivado names the net. */
    String getName(int entry) {
        FlatNet flat = flats.get(entry);
        return (flat != null) ? flat.getName() : names.get(entry);
    }

    /** Gets the entry of each physical net (of an entry, or an alias of one). */
    Map<Net, Integer> getEntryOfNet() {
        Map<Net, Integer> entryOf = new IdentityHashMap<>(nets.size());
        for (int i = 0; i < nets.size(); i++) {
            if (nets.get(i) != null) {
                entryOf.put(nets.get(i), i);
            }
        }
        for (Map.Entry<Integer, List<Net>> e : aliasNets.entrySet()) {
            for (Net alias : e.getValue()) {
                entryOf.put(alias, e.getKey());
            }
        }
        return entryOf;
    }
}
