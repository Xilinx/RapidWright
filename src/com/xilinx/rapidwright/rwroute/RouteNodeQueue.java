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

package com.xilinx.rapidwright.rwroute;

import java.util.Arrays;

/**
 * The priority queue of the router's search: a binary min-heap of {@link RouteNode}s ordered by
 * {@link RouteNode#compareTo(RouteNode)}, with the same sift-up and sift-down steps as
 * {@link java.util.PriorityQueue}, so that nodes come out in the same order, ties included.
 * Unlike {@link java.util.PriorityQueue#clear()}, which clears every slot in use, {@link #clear()}
 * takes constant time: a search that stops at its target leaves millions of nodes queued, and
 * clearing them dominated the runtime of timing-driven routing on some designs. The slots beyond
 * the size keep stale references, to rnodes that the routing graph holds anyway.
 */
public class RouteNodeQueue {

    private RouteNode[] heap = new RouteNode[64];
    private int size;

    /**
     * Adds a rnode.
     * @param rnode The rnode.
     */
    public void add(RouteNode rnode) {
        int k = size;
        if (k == heap.length) {
            heap = Arrays.copyOf(heap, k < 64 ? k * 2 + 2 : k + (k >> 1));
        }
        RouteNode[] es = heap;
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            RouteNode e = es[parent];
            if (rnode.compareTo(e) >= 0) {
                break;
            }
            es[k] = e;
            k = parent;
        }
        es[k] = rnode;
        size++;
    }

    /**
     * Removes the rnode of lowest cost.
     * @return That rnode, or null if the queue is empty.
     */
    public RouteNode poll() {
        if (size == 0) {
            return null;
        }
        RouteNode[] es = heap;
        RouteNode result = es[0];
        int n = --size;
        RouteNode x = es[n];
        // Release the vacated slot
        es[n] = null;
        if (n > 0) {
            int k = 0;
            int half = n >>> 1;
            while (k < half) {
                int child = (k << 1) + 1;
                RouteNode c = es[child];
                int right = child + 1;
                if (right < n && c.compareTo(es[right]) > 0) {
                    c = es[child = right];
                }
                if (x.compareTo(c) <= 0) {
                    break;
                }
                es[k] = c;
                k = child;
            }
            es[k] = x;
        }
        return result;
    }

    /**
     * Gets the rnode of lowest cost without removing it.
     * @return That rnode, or null if the queue is empty.
     */
    public RouteNode peek() {
        return size == 0 ? null : heap[0];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Empties the queue, in constant time. */
    public void clear() {
        size = 0;
    }
}
