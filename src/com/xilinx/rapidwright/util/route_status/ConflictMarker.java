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

import static com.xilinx.rapidwright.util.route_status.NetStatus.ANTENNAS;
import static com.xilinx.rapidwright.util.route_status.NetStatus.CONFLICTS;
import static com.xilinx.rapidwright.util.route_status.NetStatus.LOGICAL_ONLY;
import static com.xilinx.rapidwright.util.route_status.NetStatus.PARTIAL;
import static com.xilinx.rapidwright.util.route_status.NetStatus.ROUTED;
import static com.xilinx.rapidwright.util.route_status.NetStatus.STATIC_CLAIMS;
import static com.xilinx.rapidwright.util.route_status.NetStatus.UNROUTED;
import static com.xilinx.rapidwright.util.route_status.NetStatus.has;
import static com.xilinx.rapidwright.util.route_status.RouteStatusEngine.parallelFor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Marks the nets that use the same nodes (through their routing, or their site pins) as conflicting,
 * by sorting the keys of the nodes used by all nets.
 */
final class ConflictMarker {

    private ConflictMarker() {
    }

    /**
     * Marks nets that use the same node as another net as conflicting, by sorting the keys of all
     * used nodes, each tagged with the index of its net.
     * @param pack Whether to pack the node keys and entries into longs where they fit (else as for
     * huge designs, see {@link #markFallback(Entries)}).
     */
    static void mark(Entries entries, boolean pack) {
        long total = 0;
        long maxTile = 0;
        long maxWire = 0;
        for (long[] keys : entries.nodeKeys) {
            if (keys == null) {
                continue;
            }
            total += keys.length;
            for (long k : keys) {
                maxTile = Math.max(maxTile, k >>> 32);
                maxWire = Math.max(maxWire, k & 0xffffffffL);
            }
        }
        if (total == 0) {
            return;
        }
        // Compact the node keys (tile address and wire index) to make room for the entry index
        int entryBits = 64 - Long.numberOfLeadingZeros(Math.max(1, entries.nodeKeys.length - 1));
        int tileBits = 64 - Long.numberOfLeadingZeros(maxTile);
        int wireBits = 64 - Long.numberOfLeadingZeros(maxWire);
        // (one more bit flags the nodes of site pins)
        if (!pack || tileBits + wireBits + entryBits + 1 > 63 || total > Integer.MAX_VALUE - 8) {
            markFallback(entries);
            return;
        }
        markPacked(entries, (int) total, wireBits, entryBits);
    }

    /**
     * Marks conflicts by sorting the node keys packed with their entries: tile address, wire index
     * (in the given numbers of bits), entry, and whether the node is that of a site pin.
     */
    private static void markPacked(Entries entries, int total, int wireBits, int entryBits) {
        int numEntries = entries.nodeKeys.length;
        long[] tagged = new long[total];
        int[] offsets = new int[numEntries + 1];
        for (int i = 0; i < numEntries; i++) {
            offsets[i + 1] = offsets[i] + (entries.nodeKeys[i] == null ? 0 : entries.nodeKeys[i].length);
        }
        parallelFor(numEntries, (i) -> {
            long[] keys = entries.nodeKeys[i];
            if (keys == null) {
                return;
            }
            int o = offsets[i];
            int routing = (entries.numRoutingKeys == null || entries.numRoutingKeys[i] < 0) ? keys.length
                    : entries.numRoutingKeys[i];
            for (int j = 0; j < keys.length; j++) {
                long k = keys[j];
                long compact = ((k >>> 32) << wireBits) | (k & 0xffffffffL);
                tagged[o++] = (((compact << entryBits) | i) << 1) | (j >= routing ? 1 : 0);
            }
        });
        Arrays.parallelSort(tagged);
        long entryMask = (1L << entryBits) - 1;
        int keyShift = entryBits + 1;
        int[] users = new int[16];
        for (int start = 0; start < tagged.length; ) {
            int end = start + 1;
            while (end < tagged.length && (tagged[end] >>> keyShift) == (tagged[start] >>> keyShift)) {
                end++;
            }
            if (end - start > 1) {
                if (users.length < end - start) {
                    users = new int[end - start];
                }
                for (int t = start; t < end; t++) {
                    users[t - start] = (int) ((tagged[t] >>> 1) & entryMask) << 1 | (int) (tagged[t] & 1);
                }
                markNodeUsers(entries.status, users, end - start);
            }
            start = end;
        }
    }

    /** Marks conflicts the same way, without packing keys and entries into longs (for huge designs). */
    static void markFallback(Entries entries) {
        Map<Long, List<Integer>> users = new HashMap<>();
        for (int i = 0; i < entries.nodeKeys.length; i++) {
            long[] keys = entries.nodeKeys[i];
            if (keys == null) {
                continue;
            }
            int routing = (entries.numRoutingKeys == null || entries.numRoutingKeys[i] < 0) ? keys.length : entries.numRoutingKeys[i];
            for (int j = 0; j < keys.length; j++) {
                users.computeIfAbsent(keys[j], (k) -> new ArrayList<>(2)).add(i << 1 | (j >= routing ? 1 : 0));
            }
        }
        for (List<Integer> list : users.values()) {
            if (list.size() > 1) {
                markNodeUsers(entries.status, list.stream().mapToInt(Integer::intValue).toArray(), list.size());
            }
        }
    }

    /**
     * Marks the consequences of a node being used by several nets. The result does not depend on
     * the order of the users.
     * @param status The per-entry statuses to update.
     * @param users The users of the node: entry index (shifted left by 1), and 1 if the node is that
     * of an input site pin used by the site routing of the entry, or claimed by its placed pins (see
     * {@link NetStatus#LOGICAL_ONLY}), 0 if used by its routing.
     * @param count The number of users.
     */
    static void markNodeUsers(int[] status, int[] users, int count) {
        boolean otherRouting = false;
        boolean staticClaims = false;
        for (int u = 0; u < count; u++) {
            staticClaims |= isStaticClaim(status, users[u]);
            otherRouting |= (users[u] & 1) == 0 && !has(status[users[u] >> 1], STATIC_CLAIMS);
        }
        if (staticClaims && !otherRouting) {
            // The site pins of static nets only conflict with the routing of other (not static)
            // nets: leave their claims out
            int kept = 0;
            int[] others = new int[count];
            for (int u = 0; u < count; u++) {
                if (!isStaticClaim(status, users[u])) {
                    others[kept++] = users[u];
                }
            }
            users = others;
            count = kept;
        }
        boolean shared = false;
        boolean hasPhysicalOwner = false;
        for (int u = 0; u < count; u++) {
            shared |= (users[u] >> 1) != (users[0] >> 1);
            hasPhysicalOwner |= (users[u] & 1) != 0 && !has(status[users[u] >> 1], LOGICAL_ONLY);
        }
        if (!shared) {
            return;
        }
        for (int u = 0; u < count; u++) {
            int e = users[u] >> 1;
            status[e] = (status[e] | CONFLICTS) & ~ROUTED;
            if ((users[u] & 1) != 0 && has(status[e], LOGICAL_ONLY)) {
                // A site pin that only the placement of a net without a physical net claims: the
                // net is not unrouted, but in conflict
                status[e] &= ~UNROUTED;
            }
        }
        if (hasPhysicalOwner) {
            markRoutingThroughSitePin(status, users, count);
        }
    }

    /** Checks whether a user of a node is a site pin of a static net (see {@link NetStatus#STATIC_CLAIMS}). */
    private static boolean isStaticClaim(int[] status, int user) {
        return (user & 1) != 0 && has(status[user >> 1], STATIC_CLAIMS);
    }

    /**
     * Marks the routing of nets through the site pin (with a physical owner) of other nets: it leads
     * nowhere, and the site pin of an unrouted net (or a site pin of a static net) is reached by
     * another net's routing, leaving it partially routed.
     */
    private static void markRoutingThroughSitePin(int[] status, int[] users, int count) {
        for (int u = 0; u < count; u++) {
            if ((users[u] & 1) != 0) {
                continue;
            }
            int e = users[u] >> 1;
            if (!isSitePinOwner(users, count, e)) {
                status[e] |= ANTENNAS;
            }
            for (int o = 0; o < count; o++) {
                int owner = users[o] >> 1;
                if ((users[o] & 1) == 0 || owner == e) {
                    continue;
                }
                if (has(status[owner], STATIC_CLAIMS) || has(status[owner], UNROUTED)) {
                    // A site pin of a static net used by the routing of another net is unrouted, and
                    // the site pin of an unrouted net is reached, if by the routing of another net:
                    // the net is only partially unrouted
                    status[owner] = (status[owner] & ~UNROUTED) | PARTIAL;
                }
            }
        }
    }

    private static boolean isSitePinOwner(int[] users, int count, int entry) {
        for (int u = 0; u < count; u++) {
            if (users[u] == (entry << 1 | 1)) {
                return true;
            }
        }
        return false;
    }
}
