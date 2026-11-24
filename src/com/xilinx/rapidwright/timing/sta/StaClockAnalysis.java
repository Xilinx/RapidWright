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

package com.xilinx.rapidwright.timing.sta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicLongArray;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.SLR;
import com.xilinx.rapidwright.timing.clock.Clock;
import com.xilinx.rapidwright.timing.clock.ClockModel;
import com.xilinx.rapidwright.timing.clock.ClockRelations;
import com.xilinx.rapidwright.timing.family.Corner;
import com.xilinx.rapidwright.util.ParallelismTools;

/**
 * Static timing analysis over a {@link StaGraph}, with the clocks, clock insertion delays, timing
 * checks and exceptions of a {@link StaClockBinding}: a design's clocks, or the single ideal clock
 * of a design without clocks ({@link StaClockBinding#singleClock(StaGraph, float)}). The passes
 * sweep the levels of the graph forward (arrival times) then backward (required times), each level
 * in parallel chunks of at least {@link #MIN_CHUNK} pins when it is large enough; each pin's times
 * only depend on the previous levels, so the results do not depend on the number of threads. The
 * times are kept per <i>tag</i>.
 * <p>
 * <b>Tags.</b> A tag is a launch clock edge and an exception state (the set of path exceptions
 * whose -from matches the startpoint). Each pin has the sorted tags of the startpoints reaching
 * it, in the flat arrays {@code tagStart[pin]..tagStart[pin+1]} of {@code tagId}; arrival times
 * ({@code arr}), required times ({@code req}) and the launch clock pin of the arrival's arg-max
 * ({@code lcp}, for CRPR) are kept per tag slot. As the topology is fixed, the tags are computed
 * once.
 * <p>
 * <b>Setup.</b> The arrival time of a startpoint is the launch clock pin's late (SLOW_MAX) insertion
 * delay plus the clock's latency: its source latency (unless the {@link ClockModel} includes it) and its user
 * source latency (set_clock_latency -source); an ideal clock (set_clock_latency without -source) has its
 * network latency instead of insertion delays, and no CRPR. It propagates with the max delays of the edges (clock to
 * output arcs included). Each endpoint tag is checked against each clock edge of its capture clock
 * pin that the relations, clock groups and exceptions do not make false; the required time of a
 * check is
 * <pre>
 *   relation + capture insertion (SLOW_MIN) + capture latency - t_setup - uncertainty
 *            + CRPR(launch clock pin of the arg-max, capture clock pin)
 * </pre>
 * the relation including multicycle paths. Required times propagate backwards with the max delays.
 * <p>
 * <b>Criticality.</b> Checks are grouped by (launch clock, capture clock); the group g has the
 * requirement T_g (its smallest setup relation) and the shift {@code shift_g = max(0, -WNS_g)}.
 * The backward pass propagates the shifted required time {@code req - T_g + D_g}, with
 * {@code D_g = T_g + shift_g}, keeping the group of the minimum; the criticality of a connection is
 * the maximum over its edges, tags and groups of {@code 1 - (slack + shift_g)/(T_g + shift_g)} (raised to
 * the exponent, times the maximum criticality, see {@link #getCriticality(float, float, float, float)}).
 * With a single ideal clock, D_g is the larger of the requirement and the largest arrival time, and a
 * connection's criticality that of the smallest slack of its edges. Groups of different D_g get separate
 * backward passes for the criticality, one per value of D_g.
 * <p>
 * <b>Hold.</b> On demand ({@link #updateHold()}): min arrival times with the min delays of the
 * edges, from the launch clock pin's early (FAST_MIN) insertion, against the late (FAST_MAX)
 * capture insertion plus the hold relation, hold time and uncertainty, minus the hold CRPR. If the
 * graph has a second min corner ({@link StaGraph#hasSecondMinCorner()}), the hold analysis also runs at
 * the slow corner (SLOW_MIN data and launch insertion, SLOW_MAX capture insertion, the SLOW_MAX - SLOW_MIN
 * credit); the hold slacks are the minimum over the two corners ({@link #hasSlowHold()}). The endpoint
 * slots whose hold slack is below {@link #HOLD_REFINE_SLACK} are refined: the exact worst launch, searched
 * back from the endpoint over the launches arriving within {@link #HOLD_REFINE_SPREAD} of the earliest, each
 * with its own credit (a later launch sharing less of the clock tree with the capture can be worse).
 * <p>
 * <b>Multi-SLR.</b> With an inter-SLR compensation factor ({@link StaClockBinding#setInterSlrProrate}),
 * the setup and hold credits also lose the inter-SLR compensation of the checks whose launch and
 * capture clock pins are in different SLRs or whose arg-max data path crosses an SLR boundary
 * (a connection with its source and sink in different SLRs), kept per tag slot like {@code lcp}.
 * <p>
 * <b>Launch candidates.</b> The worst check of an endpoint need not launch from the arg-max arrival: a
 * launch with a smaller CRPR credit (e.g. from another SLR, also losing the inter-SLR compensation)
 * can be worse with a lower arrival, as can a path crossing an SLR boundary (losing the compensation).
 * When the clock pins are in several SLRs, each tag slot also keeps an alternate candidate, the latest
 * arrival (earliest for hold) of another key than the arg-max's, the key being the launch clock pin's SLR
 * and whether the data path crosses an SLR boundary (the best two launches of distinct keys propagate
 * exactly); the checks take the worse of the two (see {@link Check#alternateLaunch}).
 * <p>
 * <b>Updates.</b> {@link #setConnectionRouteDelay(int, float, float)} marks connections dirty;
 * {@link #update()} then recomputes the arrival times of the fan-out cone of the dirty connections
 * only (level by level), and all the endpoints and required times.
 */
public final class StaClockAnalysis {

    /** Minimum number of pins (or connections) of a chunk of a parallel pass */
    static final int MIN_CHUNK = 2048;

    /** Required time of the pins without fanout that are not endpoints: they constrain nothing */
    static final float DANGLING_REQUIRED = Float.POSITIVE_INFINITY;

    private static final float NEG_INF = Float.NEGATIVE_INFINITY;
    private static final float POS_INF = Float.POSITIVE_INFINITY;

    private final StaGraph g;
    private final StaClockBinding b;
    private final int numClocks;
    /** The clocks' latencies added to the insertion delays at the min (early) and max (late) corners */
    private final double[] latencyEarly;
    private final double[] latencyLate;
    /** Ideal clocks (set_clock_latency network latency): no insertion delay, no CRPR */
    private final boolean[] ideal;

    // Tags
    private int numTags;
    private int[] tagEdge = new int[16];
    private int[] tagState = new int[16];
    private final List<int[]> states = new ArrayList<>();
    private final int[] tagStart;
    private final int[] tagId;

    // Setup
    private final float[] arr;
    private final float[] req;
    private final int[] lcp;
    private final int[] reqGroup;

    /**
     * With an inter-SLR compensation (see {@link StaClockBinding#setInterSlrProrate(float)}): whether
     * each connection's source and sink are in different SLRs, and whether the arg-max arrival of each
     * tag slot crossed an SLR boundary; null otherwise
     */
    private final boolean[] connCrossesSlr;
    private final boolean[] xslr;

    /**
     * The alternate launch candidate of each tag slot (see the class description): the key (SLR) of each
     * clock pin, and the arrival, launch clock pin and SLR crossing of the latest launch whose key differs
     * from the arg-max's; null when there is a single key
     */
    private int[] cpinKey;
    private final float[] arrAlt;
    private final int[] lcpAlt;
    private final boolean[] xslrAlt;

    // Buffers of updateIncremental(), reused from pass to pass
    private int[][] incBuckets;
    private int[] incBucketSize;
    private boolean[] incQueued;
    private float[] incOldArr = new float[16];
    private int[] incOldLcp = new int[16];
    private boolean[] incOldX = new boolean[16];
    private float[] incOldArrAlt = new float[16];
    private int[] incOldLcpAlt = new int[16];
    /** Whether the min route delays are placeholders (the max ones) that {@link #holdDelayRefresher} replaces */
    private boolean holdDelaysStale;
    /** Sets the min route delays from the routing before a hold query, see {@link #setHoldDelayRefresher} */
    private Runnable holdDelayRefresher;

    /**
     * The hold analyses per corner: the fast one (FAST_MIN data and launch, FAST_MAX capture) and, if the graph
     * has the delays of a second min corner (SLOW_MIN), the slow one (SLOW_MIN data and launch, SLOW_MAX
     * capture), as Vivado times hold at both process corners: the min arrival times and launches per tag slot (and
     * their alternates, see {@link #arrAlt}), the hold slacks and check bases of the endpoint slots, and the
     * refined worst launches. The hold queries and kernels work on the active one ({@link #activeHold}).
     */
    private static final class HoldCorner {
        final boolean slow;
        float[] arrMin;
        int[] lcpMin;
        boolean[] xslrMin;
        float[] arrMinAlt;
        int[] lcpMinAlt;
        boolean[] xslrMinAlt;
        float[] epHoldSlack;
        float[] chkHoldBase;
        /** The launch clock pin (-1 if none) and arrival of the exact worst launch of the refined endpoint slots */
        int[] refLcp;
        float[] refArr;
        boolean valid;

        HoldCorner(boolean slow) {
            this.slow = slow;
        }
    }

    /**
     * Hold refinement: the endpoint slots whose hold slack is below HOLD_REFINE_SLACK (ps) get the exact worst
     * launch, searched back from the endpoint over the launches whose arrival is within HOLD_REFINE_SPREAD (ps)
     * of the earliest (a later launch with a smaller CRPR credit can be worse: a launch arriving later by d is
     * worse if its credit is smaller by more than d). The other endpoint slots keep the slack of the
     * propagation's launch: refinement lowers a slack by less than the credit difference of two launches, so
     * their slack stays positive unless two launches differ in credit by more than HOLD_REFINE_SLACK.
     */
    static final float HOLD_REFINE_SLACK = 500f;
    static final float HOLD_REFINE_SPREAD = 600f;

    /**
     * Gets the hold slack below which the analysis refines the hold slack of an endpoint: it then searches the
     * exact worst launch, among the launches whose arrival is within {@link #getHoldRefineSpread()} of the
     * earliest, instead of keeping that of the propagation's launch (a later launch with a smaller CRPR credit can
     * be worse). This is a parameter of the analysis, which may change between releases.
     * @return The slack in ps.
     */
    public static float getHoldRefineSlack() {
        return HOLD_REFINE_SLACK;
    }

    /**
     * Gets the arrival spread of the launches that the hold refinement searches (see {@link #getHoldRefineSlack()}):
     * those whose arrival at the endpoint is within this of the earliest. This is a parameter of the analysis, which
     * may change between releases.
     * @return The spread in ps.
     */
    public static float getHoldRefineSpread() {
        return HOLD_REFINE_SPREAD;
    }

    /** Whether the hold slacks are refined (see {@link #HOLD_REFINE_SLACK}); tests turn it off */
    boolean refineHold = true;
    private final HoldCorner fastHold = new HoldCorner(false);
    private final HoldCorner slowHold;
    /** The hold corner of the hold kernels and queries, see {@link #useHold(HoldCorner)} */
    private HoldCorner activeHold = fastHold;

    // Endpoints: endpoint k is pin endPin[k]; its slots map to epBase[k] + (slot - tagStart[pin])
    private final int numEnds;
    private final int[] endPin;
    private final int[] epBase;
    private final int[] pinEnd;
    // Checks of the endpoint slots
    private final int[] chkStart;
    private final int[] chkEdge;
    private final int[] chkGroup;
    private final float[] chkSetupBase;
    private final float[] chkRelSetup;
    private final float[] chkRelHold;
    private final float[] chkReq;

    // Groups (launch clock x capture clock)
    private final int numGroups;
    private final float[] groupT;
    private final float[] groupD;
    private final float[] groupMaxAdj;

    private float[] connCrit;
    /**
     * The normalization class of each group for the criticality: groups with the same D_g share one
     * (-1 for the groups without checks); see {@link #computeCriticality(float, float)}
     */
    private int[] groupClass;
    /** The shifted required times of the tags of the pins for one normalization class (scratch) */
    private float[] classReq;
    private final int[] pinLevel;

    private volatile boolean dirty = true;
    private volatile boolean fullNeeded = true;
    private final AtomicLongArray dirtyConns;
    /** The sink pins of the edges whose fixed delays changed (see {@link #setEdgeFixedDelays}) */
    private int[] dirtyPins = new int[16];
    private int numDirtyPins;

    private final ForkJoinPool pool;
    private boolean parallel;

    private int numFullPasses;
    private int numIncrementalPasses;
    private long passNanos;

    /** The largest number of (pin, tag) slots, that of the largest array */
    private static final int MAX_SLOTS = Integer.MAX_VALUE - 8;

    /**
     * Creates the analysis, running its parallel passes on the common pool if parallel processing is
     * enabled (see {@link ParallelismTools#getParallel()}).
     */
    public StaClockAnalysis(StaClockBinding binding) {
        this(binding, ForkJoinPool.commonPool());
        parallel = ParallelismTools.getParallel();
    }

    /**
     * Creates the analysis.
     * @param binding The clocks bound to the graph.
     * @param pool The pool of the parallel passes.
     */
    StaClockAnalysis(StaClockBinding binding, ForkJoinPool pool) {
        this(binding, pool, true);
    }

    /**
     * Creates the analysis.
     * @param binding The clocks bound to the graph.
     * @param pool The pool of the parallel passes.
     * @param launchCandidates Whether the tag slots keep an alternate launch candidate when the clock pins are in
     *                         several SLRs (see the class description); false keeps the arg-max only (tests).
     */
    StaClockAnalysis(StaClockBinding binding, ForkJoinPool pool, boolean launchCandidates) {
        this.b = binding;
        this.g = binding.g;
        this.pool = pool;
        this.parallel = pool.getParallelism() > 1;
        this.numClocks = binding.clocks.size();
        latencyEarly = new double[numClocks];
        latencyLate = new double[numClocks];
        ideal = new boolean[numClocks];
        setLatencies();
        indexExceptions();
        int n = g.numPins;
        pinLevel = new int[n];
        for (int l = 0; l < g.getNumLevels(); l++) {
            for (int p = g.levelStart[l]; p < g.levelStart[l + 1]; p++) {
                pinLevel[p] = l;
            }
        }

        // 1. Tags of each pin, in levelized order (= pin order)
        tagStart = new int[n + 1];
        int[][] pinTags = new int[n][];
        Map<Key, int[]> interned = new HashMap<>();
        Map<Key, Integer> stateIds = new HashMap<>();
        Map<Long, Integer> tagIds = new HashMap<>();
        int[] empty = new int[0];
        states.add(empty);
        stateIds.put(new Key(empty), 0);
        int numSlots = 0;
        for (int p = 0; p < n; p++) {
            int[] tags;
            if (g.inStart[p] == g.inStart[p + 1]) {
                int cp = b.launchCpin[p];
                if (cp < 0 || !g.isPinKind(p, StaGraph.PIN_START)) {
                    tags = empty;
                } else {
                    int state = stateOf(p, stateIds);
                    int[] edges = b.clockPinEdges[cp];
                    int[] t = new int[edges.length];
                    for (int i = 0; i < edges.length; i++) {
                        // A fall-triggered clock pin launches at the opposite edge
                        t[i] = internTag(b.fallLaunch[cp] ? edges[i] ^ 1 : edges[i], state, tagIds);
                    }
                    Arrays.sort(t);
                    tags = intern(dedup(t), interned);
                }
            } else {
                int[] acc = null;
                boolean shared = true;
                for (int e = g.inStart[p]; e < g.inStart[p + 1]; e++) {
                    int[] q = pinTags[g.eFrom[e]];
                    if (q.length == 0) continue;
                    if (acc == null) {
                        acc = q;
                    } else if (acc != q) {
                        acc = merge(acc, q);
                        shared = false;
                    }
                }
                tags = acc == null ? empty : (shared ? acc : intern(acc, interned));
            }
            pinTags[p] = tags;
            tagStart[p] = numSlots;
            if (tags.length > MAX_SLOTS - numSlots) {
                // E.g. many -from exceptions on converging startpoints, each set of them a tag of its own
                throw new IllegalStateException("Too many timing tags: more than " + MAX_SLOTS + " (pin, tag) slots"
                        + " by pin " + p + " of " + n + ", which has " + tags.length + " tags (launch clock edges and"
                        + " sets of the path exceptions whose -from objects contain the startpoint)");
            }
            numSlots += tags.length;
        }
        tagStart[n] = numSlots;
        tagId = new int[numSlots];
        for (int p = 0; p < n; p++) {
            System.arraycopy(pinTags[p], 0, tagId, tagStart[p], pinTags[p].length);
        }
        pinTags = null;
        arr = new float[numSlots];
        req = new float[numSlots];
        lcp = new int[numSlots];
        reqGroup = new int[numSlots];
        connCrossesSlr = b.getInterSlrProrate() == 0f ? null : getConnectionsCrossingSlrs(g);
        xslr = connCrossesSlr == null ? null : new boolean[numSlots];
        cpinKey = launchCandidates ? getLaunchKeys(b.clockModel) : null;
        arrAlt = cpinKey == null ? null : new float[numSlots];
        lcpAlt = cpinKey == null ? null : new int[numSlots];
        xslrAlt = cpinKey == null || xslr == null ? null : new boolean[numSlots];

        // 2. Endpoints and their checks
        numGroups = Math.max(1, numClocks * numClocks);
        groupT = new float[numGroups];
        Arrays.fill(groupT, POS_INF);
        groupD = new float[numGroups];
        groupMaxAdj = new float[numGroups];
        pinEnd = new int[n];
        Arrays.fill(pinEnd, -1);
        List<Integer> ends = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            if (b.captureCpin[p] >= 0) {
                pinEnd[p] = ends.size();
                ends.add(p);
            }
        }
        numEnds = ends.size();
        endPin = new int[numEnds];
        epBase = new int[numEnds + 1];
        for (int k = 0; k < numEnds; k++) {
            int p = ends.get(k);
            endPin[k] = p;
            epBase[k + 1] = epBase[k] + (tagStart[p + 1] - tagStart[p]);
        }
        int numEpSlots = epBase[numEnds];
        chkStart = new int[numEpSlots + 1];
        ChecksBuilder cb = new ChecksBuilder();
        for (int k = 0; k < numEnds; k++) {
            int p = endPin[k];
            int cp = b.captureCpin[p];
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                int es = epBase[k] + (i - tagStart[p]);
                chkStart[es] = cb.size;
                addChecks(cb, p, cp, tagId[i]);
            }
        }
        chkStart[numEpSlots] = cb.size;
        chkEdge = Arrays.copyOf(cb.edge, cb.size);
        chkGroup = Arrays.copyOf(cb.group, cb.size);
        chkSetupBase = Arrays.copyOf(cb.setupBase, cb.size);
        fastHold.chkHoldBase = Arrays.copyOf(cb.holdBase, cb.size);
        slowHold = g.hasSecondMinCorner() ? new HoldCorner(true) : null;
        chkRelSetup = Arrays.copyOf(cb.relSetup, cb.size);
        chkRelHold = Arrays.copyOf(cb.relHold, cb.size);
        chkReq = new float[cb.size];
        dirtyConns = new AtomicLongArray((g.numConns + 63) >>> 6);
    }

    // ------------------------------------------------------------------------------------------
    // Construction helpers
    // ------------------------------------------------------------------------------------------

    /** The clocks' latencies, see {@link ClockModel#getClockLatency(Clock, boolean, boolean)} */
    private void setLatencies() {
        boolean included = b.clockModel.includesSourceLatency();
        for (Clock c : b.clocks) {
            int id = c.getId();
            ideal[id] = c.isIdeal();
            latencyEarly[id] = ClockModel.getClockLatency(c, false, included);
            latencyLate[id] = ClockModel.getClockLatency(c, true, included);
        }
    }

    /**
     * Gets the key of each clock pin for the alternate launch candidates of the analysis (see the class
     * description): each tag slot keeps, besides the latest arrival (earliest for hold), that of another key, the
     * key of a launch being that of its launch clock pin and whether its data path crosses an SLR boundary. The key
     * of a clock pin is its SLR. How the analysis keys launches is a parameter of the analysis, which may change
     * between releases.
     * @param cm The clock model of the analysis.
     * @return A new array of the keys by clock pin ({@link ClockModel#getSlr(int)}: -1 if unknown), or null if the
     * clock pins are in fewer than two SLRs (the analysis then keeps no alternate launches).
     */
    public static int[] getLaunchKeys(ClockModel cm) {
        int n = cm.getNumClockPins();
        int[] keys = new int[n];
        int first = Integer.MIN_VALUE;
        boolean several = false;
        for (int i = 0; i < n; i++) {
            keys[i] = cm.getSlr(i);
            if (keys[i] < 0) continue;
            if (first == Integer.MIN_VALUE) {
                first = keys[i];
            } else if (keys[i] != first) {
                several = true;
            }
        }
        return several ? keys : null;
    }

    /**
     * The key of a launch candidate: the launch clock pin's SLR and whether the data path crosses an SLR
     * boundary; -1 for no launch
     */
    private int launchKey(int clockPin, boolean crossing) {
        if (clockPin < 0) return -1;
        return 2 * cpinKey[clockPin] + (crossing ? 1 : 0);
    }

    /** The insertion delay of a clock pin for a clock: 0 for an ideal clock */
    private float insertion(ClockModel cm, int clockPin, int clock, Corner corner) {
        return ideal[clock] ? 0f : cm.getInsertion(clockPin, clock, corner);
    }

    /** An int[] compared by contents */
    private static final class Key {
        final int[] a;
        final int hash;

        Key(int[] a) {
            this.a = a;
            this.hash = Arrays.hashCode(a);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key && Arrays.equals(a, ((Key) o).a);
        }
    }

    private static int[] intern(int[] a, Map<Key, int[]> interned) {
        Key k = new Key(a);
        int[] x = interned.get(k);
        if (x == null) {
            interned.put(k, a);
            x = a;
        }
        return x;
    }

    private static int[] dedup(int[] sorted) {
        int m = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) sorted[m++] = sorted[i];
        }
        return m == sorted.length ? sorted : Arrays.copyOf(sorted, m);
    }

    private static int[] merge(int[] a, int[] c) {
        int[] out = new int[a.length + c.length];
        int i = 0;
        int j = 0;
        int m = 0;
        while (i < a.length || j < c.length) {
            int v;
            if (j >= c.length || i < a.length && a[i] < c[j]) {
                v = a[i++];
            } else if (i >= a.length || c[j] < a[i]) {
                v = c[j++];
            } else {
                v = a[i++];
                j++;
            }
            out[m++] = v;
        }
        return m == out.length ? out : Arrays.copyOf(out, m);
    }

    private int internTag(int edge, int state, Map<Long, Integer> tagIds) {
        long key = ((long) edge << 32) | state;
        Integer t = tagIds.get(key);
        if (t == null) {
            t = numTags++;
            if (t == tagEdge.length) {
                tagEdge = Arrays.copyOf(tagEdge, t * 2);
                tagState = Arrays.copyOf(tagState, t * 2);
            }
            tagEdge[t] = edge;
            tagState[t] = state;
            tagIds.put(key, t);
        }
        return t;
    }

    /** The exceptions (indices, increasing) by -from startpoint and by -to endpoint, and those without -to pins */
    private final Map<Integer, int[]> exByFromPin = new HashMap<>();
    private final Map<Integer, int[]> exByToPin = new HashMap<>();
    private int[] exWithoutToPins = new int[0];

    /** Indexes the path exceptions by their -from and -to pins (instead of testing each one at each check) */
    private void indexExceptions() {
        List<StaClockBinding.PathException> ex = b.exceptions;
        List<Integer> noTo = new ArrayList<>();
        Map<Integer, List<Integer>> from = new HashMap<>();
        Map<Integer, List<Integer>> to = new HashMap<>();
        for (int i = 0; i < ex.size(); i++) {
            StaClockBinding.PathException e = ex.get(i);
            if (e.fromPins != null) {
                for (int p : e.fromPins) from.computeIfAbsent(p, k -> new ArrayList<>()).add(i);
            }
            if (e.toPins != null) {
                for (int p : e.toPins) to.computeIfAbsent(p, k -> new ArrayList<>()).add(i);
            } else {
                noTo.add(i);
            }
        }
        for (Map.Entry<Integer, List<Integer>> e : from.entrySet()) exByFromPin.put(e.getKey(), toArray(e.getValue()));
        for (Map.Entry<Integer, List<Integer>> e : to.entrySet()) exByToPin.put(e.getKey(), toArray(e.getValue()));
        exWithoutToPins = toArray(noTo);
    }

    private static int[] toArray(List<Integer> l) {
        int[] a = new int[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    /** The indices of the exceptions that may apply to an endpoint, increasing: those to it, and without -to pins */
    private int[] exceptionsTo(int pin) {
        int[] t = exByToPin.get(pin);
        if (t == null) {
            return exWithoutToPins;
        }
        int[] out = new int[t.length + exWithoutToPins.length];
        int i = 0;
        int j = 0;
        int k = 0;
        while (i < t.length || j < exWithoutToPins.length) {
            out[k++] = j >= exWithoutToPins.length || i < t.length && t[i] < exWithoutToPins[j] ? t[i++]
                    : exWithoutToPins[j++];
        }
        return out;
    }

    /** The exception state of a startpoint: the exceptions whose -from pins include it */
    private int stateOf(int pin, Map<Key, Integer> stateIds) {
        int[] s = exByFromPin.get(pin);
        if (s == null || s.length == 0) {
            return 0;
        }
        Key k = new Key(s);
        Integer id = stateIds.get(k);
        if (id == null) {
            id = states.size();
            states.add(s);
            stateIds.put(k, id);
        }
        return id;
    }

    /** Growing arrays of checks */
    private static final class ChecksBuilder {
        int size;
        int[] edge = new int[64];
        int[] group = new int[64];
        float[] setupBase = new float[64];
        float[] holdBase = new float[64];
        float[] relSetup = new float[64];
        float[] relHold = new float[64];

        void add(int e, int gr, float sb, float hb, float rs, float rh) {
            if (size == edge.length) {
                int n = size * 2;
                edge = Arrays.copyOf(edge, n);
                group = Arrays.copyOf(group, n);
                setupBase = Arrays.copyOf(setupBase, n);
                holdBase = Arrays.copyOf(holdBase, n);
                relSetup = Arrays.copyOf(relSetup, n);
                relHold = Arrays.copyOf(relHold, n);
            }
            edge[size] = e;
            group[size] = gr;
            setupBase[size] = sb;
            holdBase[size] = hb;
            relSetup[size] = rs;
            relHold[size] = rh;
            size++;
        }
    }

    /** Adds the checks of an endpoint tag: one per capture clock edge that is timed. */
    private void addChecks(ChecksBuilder cb, int pin, int capCpin, int tag) {
        int le = tagEdge[tag];
        int lc = le >> 1;
        int state = tagState[tag];
        ClockRelations rel = b.relations;
        ClockModel cm = b.clockModel;
        float tSetup = b.getSetupTime(pin);
        float tHold = b.getHoldTime(pin);
        for (int ce : b.clockPinEdges[capCpin]) {
            int cc = ce >> 1;
            boolean setupOn = !rel.isFalseSetup(lc, cc);
            boolean holdOn = !rel.isFalseHold(lc, cc);
            int ns = 1;
            boolean nsStart = false;
            int nh = 0;
            boolean nhEnd = false;
            StaClockBinding.PathException bestS = null;
            StaClockBinding.PathException bestH = null;
            List<StaClockBinding.PathException> ex = b.exceptions;
            for (int i : exceptionsTo(pin)) {
                StaClockBinding.PathException e = ex.get(i);
                boolean from = e.anyFrom() || e.fromClocks != null && e.fromClocks.get(lc)
                        || e.fromPins != null && Arrays.binarySearch(states.get(state), i) >= 0;
                if (!from) continue;
                boolean to = e.anyTo() || e.toClocks != null && e.toClocks.get(cc)
                        || e.toPins != null && e.toPins.contains(pin);
                if (!to) continue;
                if (e.falsePath) {
                    if (e.setup) setupOn = false;
                    if (e.hold) holdOn = false;
                    continue;
                }
                if (e.setup && (bestS == null || e.specificity() >= bestS.specificity())) {
                    bestS = e;
                }
                if (e.hold && (bestH == null || e.specificity() >= bestH.specificity())) {
                    bestH = e;
                }
            }
            if (bestS != null) {
                ns = bestS.multiplier;
                nsStart = Boolean.TRUE.equals(bestS.start);
            }
            if (bestH != null) {
                nh = bestH.multiplier;
                nhEnd = Boolean.FALSE.equals(bestH.start);
            }
            if (!setupOn && !holdOn) continue;
            float relS = (float) rel.getSetup(le, ce, ns, nsStart);
            float relH = (float) rel.getHold(le, ce, ns, nsStart, nh, nhEnd);
            int gr = lc * numClocks + cc;
            float sb = Float.NaN;
            float hb = Float.NaN;
            if (setupOn) {
                sb = setupBase(relS, capCpin, lc, cc, tSetup, cm);
                // A group's requirement: its smallest positive setup relation (else the capture period)
                float tCand = relS > 0 ? relS : (float) b.clocks.get(cc).getPeriod();
                if (tCand < groupT[gr]) {
                    groupT[gr] = tCand;
                }
            }
            if (holdOn) {
                hb = holdBase(relH, capCpin, lc, cc, tHold, cm);
            }
            cb.add(ce, gr, sb, hb, relS, relH);
        }
    }

    /** The static part of a setup check's required time: all but the CRPR */
    private float setupBase(float relS, int capCpin, int lc, int cc, float tSetup, ClockModel cm) {
        return relS + insertion(cm, capCpin, cc, Corner.SLOW_MIN) + (float) latencyEarly[cc] - tSetup
                - b.uncertainty.getSetup(lc, cc);
    }

    /** The static part of a hold check's required time: all but the CRPR (fast corner) */
    private float holdBase(float relH, int capCpin, int lc, int cc, float tHold, ClockModel cm) {
        return holdBase(relH, capCpin, lc, cc, tHold, cm, false);
    }

    /** The static part of a hold check's required time at the fast or slow corner: late capture insertion */
    private float holdBase(float relH, int capCpin, int lc, int cc, float tHold, ClockModel cm, boolean slow) {
        return relH + insertion(cm, capCpin, cc, slow ? Corner.SLOW_MAX : Corner.FAST_MAX) + (float) latencyLate[cc]
                + tHold + b.uncertainty.getHold(lc, cc);
    }

    /** The hold check bases of the slow corner (slow capture insertion and hold times) */
    private float[] slowHoldBases() {
        ClockModel cm = b.clockModel;
        float[] base = new float[chkEdge.length];
        Arrays.fill(base, Float.NaN);
        for (int k = 0; k < numEnds; k++) {
            int p = endPin[k];
            int cap = b.captureCpin[p];
            float tHold = b.getHoldTime(p, true);
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                int lc = tagEdge[tagId[i]] >> 1;
                int es = epBase[k] + (i - tagStart[p]);
                for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                    if (!Float.isNaN(fastHold.chkHoldBase[c])) {
                        base[c] = holdBase(chkRelHold[c], cap, lc, chkEdge[c] >> 1, tHold, cm, true);
                    }
                }
            }
        }
        return base;
    }

    /** Makes a hold corner the active one, that of the hold kernels and queries */
    private void useHold(HoldCorner h) {
        activeHold = h;
    }

    /** @return Whether the hold analysis also times the slow corner (the graph has SLOW_MIN delays). */
    public boolean hasSlowHold() {
        return slowHold != null;
    }

    /**
     * Recomputes the clock-dependent parts of the timing checks (capture insertion delays), e.g. after
     * the clock nets were re-routed and the binding given a new {@link ClockModel}
     * ({@link StaClockBinding#setClockModel(ClockModel)}); the next {@link #update()} is a full pass.
     */
    public void refreshClockTiming() {
        setLatencies();
        if (cpinKey != null) {
            int[] keys = getLaunchKeys(b.clockModel);
            if (keys != null && keys.length == cpinKey.length) {
                cpinKey = keys;
            }
        }
        ClockModel cm = b.clockModel;
        for (int k = 0; k < numEnds; k++) {
            int p = endPin[k];
            int cap = b.captureCpin[p];
            float tSetup = b.getSetupTime(p);
            float tHold = b.getHoldTime(p);
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                int lc = tagEdge[tagId[i]] >> 1;
                int es = epBase[k] + (i - tagStart[p]);
                for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                    int cc = chkEdge[c] >> 1;
                    if (!Float.isNaN(chkSetupBase[c])) {
                        chkSetupBase[c] = setupBase(chkRelSetup[c], cap, lc, cc, tSetup, cm);
                    }
                    if (!Float.isNaN(fastHold.chkHoldBase[c])) {
                        fastHold.chkHoldBase[c] = holdBase(chkRelHold[c], cap, lc, cc, tHold, cm);
                    }
                }
            }
        }
        if (slowHold != null && slowHold.chkHoldBase != null) {
            slowHold.chkHoldBase = slowHoldBases();
        }
        markAllDirty();
    }

    // ------------------------------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------------------------------

    public StaGraph getGraph() {
        return g;
    }

    public StaClockBinding getBinding() {
        return b;
    }

    void setParallel(boolean parallel) {
        this.parallel = parallel;
    }

    int getNumTags() {
        return numTags;
    }

    /** @return The launch clock edge id of a tag (2 * clock + falling). */
    int getTagClockEdge(int tag) {
        return tagEdge[tag];
    }

    /** @return The number of tag slots (pins times their tags). */
    int getNumSlots() {
        return tagId.length;
    }

    int getNumPinTags(int pin) {
        return tagStart[pin + 1] - tagStart[pin];
    }

    /** @return The i-th tag of a pin. */
    int getPinTag(int pin, int i) {
        return tagId[tagStart[pin] + i];
    }

    /** @return The arrival time of the i-th tag of a pin (setup analysis), -infinity if none. */
    float getArrival(int pin, int i) {
        return arr[tagStart[pin] + i];
    }

    /** @return The required time of the i-th tag of a pin (shifted by its group, see the class description). */
    float getShiftedRequired(int pin, int i) {
        return req[tagStart[pin] + i];
    }

    /** @return The launch clock pin of the arrival of the i-th tag of a pin. */
    int getLaunchClockPin(int pin, int i) {
        return lcp[tagStart[pin] + i];
    }

    /** @return The worst arrival time of a pin over its tags, -infinity if it has none. */
    public float getMaxArrival(int pin) {
        float m = NEG_INF;
        for (int i = tagStart[pin]; i < tagStart[pin + 1]; i++) {
            m = Math.max(m, arr[i]);
        }
        return m;
    }

    /** @return Whether a pin is a clocked endpoint (has timing checks). */
    boolean isEndpoint(int pin) {
        return pinEnd[pin] >= 0;
    }

    public int getNumEndpoints() {
        return numEnds;
    }

    public int getEndpointPin(int k) {
        return endPin[k];
    }

    public int getNumGroups() {
        return numGroups;
    }

    /** @return The requirement T_g of a group (its smallest setup relation), +infinity if it has no check. */
    float getGroupRequirement(int group) {
        return groupT[group];
    }

    /** @return D_g = T_g + max(0, -WNS_g) of a group, after a pass. */
    public float getGroupNormalization(int group) {
        return groupD[group];
    }

    public int getNumFullPasses() {
        return numFullPasses;
    }

    public int getNumIncrementalPasses() {
        return numIncrementalPasses;
    }

    long getPassNanos() {
        return passNanos;
    }

    /** Bytes of the analysis arrays (tags, times, checks), for memory accounting. */
    long getMemoryBytes() {
        long slots = tagId.length;
        long checks = chkEdge.length;
        return 4L * (tagStart.length + pinLevel.length + pinEnd.length) + 16L * slots + 4L * slots
                + (fastHold.arrMin != null ? 8L * slots : 0) + (arrAlt != null ? 8L * slots : 0)
                + (fastHold.arrMinAlt != null ? 8L * slots : 0) + 28L * checks + 8L * (numEnds * 2L);
    }

    // ------------------------------------------------------------------------------------------
    // Updates
    // ------------------------------------------------------------------------------------------

    /**
     * Sets the route delay of a connection at both corners, marking it dirty. Different
     * connections may be set from several threads at once.
     */
    public void setConnectionRouteDelay(int conn, float delayMax, float delayMin) {
        boolean changed = false;
        if (g.connRouteMax[conn] != delayMax) {
            g.connRouteMax[conn] = delayMax;
            changed = true;
        }
        if (g.connRouteMin[conn] != delayMin) {
            g.connRouteMin[conn] = delayMin;
            invalidateHold(false);
            changed = true;
        }
        if (changed) {
            dirty = true;
            long bit = 1L << (conn & 63);
            int word = conn >>> 6;
            long old;
            do {
                old = dirtyConns.get(word);
            } while ((old & bit) == 0 && !dirtyConns.compareAndSet(word, old, old | bit));
        }
    }

    /**
     * Sets the route delay of a connection at the second min corner (SLOW_MIN, see {@link #hasSlowHold()}),
     * for the slow-corner hold analysis only.
     */
    public void setConnectionRouteDelayMin2(int conn, float delayMin2) {
        if (g.connRouteMin2 != null && g.connRouteMin2[conn] != delayMin2) {
            g.connRouteMin2[conn] = delayMin2;
            invalidateHold(true);
        }
    }

    private void invalidateHold(boolean slow) {
        HoldCorner h = slow ? slowHold : fastHold;
        if (h != null) {
            h.valid = false;
        }
    }

    /**
     * Sets the fixed delays of an edge (e.g. those of a LUT arc whose input moved to another pin of the
     * LUT), marking its sink pin dirty.
     * @param edge The edge.
     * @param delayMax Its fixed delay at the max corner, in ps.
     * @param delayMin Its fixed delay at the min corner, in ps.
     * @param delayMin2 Its fixed delay at the second min corner, if the graph has one (see
     *                  {@link StaGraph#hasSecondMinCorner()}), in ps.
     */
    public synchronized void setEdgeFixedDelays(int edge, float delayMax, float delayMin, float delayMin2) {
        boolean changed = false;
        if (g.eFixedMax[edge] != delayMax) {
            g.eFixedMax[edge] = delayMax;
            changed = true;
        }
        if (g.eFixedMin[edge] != delayMin) {
            g.eFixedMin[edge] = delayMin;
            invalidateHold(false);
        }
        if (g.eFixedMin2 != null && g.eFixedMin2[edge] != delayMin2) {
            g.eFixedMin2[edge] = delayMin2;
            invalidateHold(true);
        }
        if (changed) {
            if (numDirtyPins == dirtyPins.length) {
                dirtyPins = Arrays.copyOf(dirtyPins, numDirtyPins * 2);
            }
            dirtyPins[numDirtyPins++] = g.eTo[edge];
            dirty = true;
        }
    }

    /** Marks everything dirty, e.g. after the graph's delays were changed by other means. */
    void markAllDirty() {
        dirty = true;
        fullNeeded = true;
        invalidateHold(false);
        invalidateHold(true);
    }

    boolean isDirty() {
        return dirty;
    }

    /**
     * Updates the setup times if anything changed: incrementally (the fan-out cone of the dirty
     * connections) unless a full pass is needed.
     * @return True if a pass was run.
     */
    public boolean update() {
        if (!dirty) {
            return false;
        }
        if (fullNeeded) {
            updateFull();
        } else {
            updateIncremental();
        }
        return true;
    }

    /** Runs a full setup pass. */
    void updateFull() {
        long t0 = System.nanoTime();
        clearDirty();
        int numLevels = g.getNumLevels();
        for (int l = 0; l < numLevels; l++) {
            runRange(this::forwardRange, g.levelStart[l], g.levelStart[l + 1]);
        }
        finishPass();
        fullNeeded = false;
        numFullPasses++;
        passNanos += System.nanoTime() - t0;
    }

    /**
     * Runs an incremental setup pass: the arrival times of the fan-out cone of the dirty connections
     * (level by level, stopping where they do not change), then all the endpoints and required times.
     */
    void updateIncremental() {
        if (fullNeeded) {
            updateFull();
            return;
        }
        long t0 = System.nanoTime();
        int numLevels = g.getNumLevels();
        if (incBuckets == null) {
            // Reused by every incremental pass (and left cleared by it)
            incBuckets = new int[numLevels][];
            incBucketSize = new int[numLevels];
            incQueued = new boolean[g.numPins];
        }
        final int[][] buckets = incBuckets;
        final int[] bucketSize = incBucketSize;
        final boolean[] queued = incQueued;
        int minLevel = numLevels;
        for (int w = 0; w < dirtyConns.length(); w++) {
            long bits = dirtyConns.get(w);
            while (bits != 0) {
                int c = (w << 6) + Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                for (int i = g.connEdgeStart[c]; i < g.connEdgeStart[c + 1]; i++) {
                    int p = g.eTo[g.connEdges[i]];
                    if (!queued[p]) {
                        queued[p] = true;
                        minLevel = Math.min(minLevel, enqueue(buckets, bucketSize, p));
                    }
                }
            }
        }
        synchronized (this) {
            for (int i = 0; i < numDirtyPins; i++) {
                int p = dirtyPins[i];
                if (!queued[p]) {
                    queued[p] = true;
                    minLevel = Math.min(minLevel, enqueue(buckets, bucketSize, p));
                }
            }
        }
        clearDirty();
        float[] oldArr = incOldArr;
        int[] oldLcp = incOldLcp;
        boolean[] oldX = incOldX;
        float[] oldArrAlt = incOldArrAlt;
        int[] oldLcpAlt = incOldLcpAlt;
        for (int l = minLevel; l < numLevels; l++) {
            for (int k = 0; k < bucketSize[l]; k++) {
                int p = buckets[l][k];
                int s = tagStart[p];
                int t = tagStart[p + 1];
                int m = t - s;
                if (oldArr.length < m) {
                    oldArr = incOldArr = new float[m];
                    oldLcp = incOldLcp = new int[m];
                    oldX = incOldX = new boolean[m];
                    oldArrAlt = incOldArrAlt = new float[m];
                    oldLcpAlt = incOldLcpAlt = new int[m];
                }
                System.arraycopy(arr, s, oldArr, 0, m);
                System.arraycopy(lcp, s, oldLcp, 0, m);
                if (xslr != null) {
                    System.arraycopy(xslr, s, oldX, 0, m);
                }
                if (arrAlt != null) {
                    System.arraycopy(arrAlt, s, oldArrAlt, 0, m);
                    System.arraycopy(lcpAlt, s, oldLcpAlt, 0, m);
                }
                forwardPin(p);
                boolean changed = false;
                for (int i = 0; i < m; i++) {
                    if (Float.floatToIntBits(arr[s + i]) != Float.floatToIntBits(oldArr[i]) || lcp[s + i] != oldLcp[i]
                            || xslr != null && xslr[s + i] != oldX[i]
                            || arrAlt != null && (lcpAlt[s + i] != oldLcpAlt[i]
                            || Float.floatToIntBits(arrAlt[s + i]) != Float.floatToIntBits(oldArrAlt[i]))) {
                        changed = true;
                        break;
                    }
                }
                if (!changed) continue;
                for (int i = g.outStart[p]; i < g.outStart[p + 1]; i++) {
                    int q = g.eTo[g.outEdge[i]];
                    if (!queued[q]) {
                        queued[q] = true;
                        enqueue(buckets, bucketSize, q);
                    }
                }
            }
        }
        // Leave the reused buffers cleared
        for (int l = minLevel; l < numLevels; l++) {
            for (int k = 0; k < bucketSize[l]; k++) {
                queued[buckets[l][k]] = false;
            }
            bucketSize[l] = 0;
        }
        finishPass();
        numIncrementalPasses++;
        passNanos += System.nanoTime() - t0;
    }

    private int enqueue(int[][] buckets, int[] size, int p) {
        int l = pinLevel[p];
        if (buckets[l] == null) {
            buckets[l] = new int[8];
        } else if (size[l] == buckets[l].length) {
            buckets[l] = Arrays.copyOf(buckets[l], size[l] * 2);
        }
        buckets[l][size[l]++] = p;
        return l;
    }

    private void clearDirty() {
        dirty = false;
        synchronized (this) {
            numDirtyPins = 0;
        }
        for (int i = 0; i < dirtyConns.length(); i++) {
            dirtyConns.set(i, 0);
        }
    }

    /** Endpoint checks, group normalizations and required times, after the arrival times. */
    private void finishPass() {
        runRange(this::evalEndpointsRange, 0, numEnds);
        Arrays.fill(groupMaxAdj, NEG_INF);
        for (int k = 0; k < numEnds; k++) {
            int p = endPin[k];
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                float a = arr[i];
                if (a == NEG_INF) continue;
                int es = epBase[k] + (i - tagStart[p]);
                for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                    float r = chkReq[c];
                    if (r == POS_INF) continue;
                    int gr = chkGroup[c];
                    float adj = a - (r - groupT[gr]);
                    if (adj > groupMaxAdj[gr]) {
                        groupMaxAdj[gr] = adj;
                    }
                }
            }
        }
        for (int gr = 0; gr < numGroups; gr++) {
            groupD[gr] = groupT[gr] == POS_INF ? POS_INF : Float.max(groupMaxAdj[gr], groupT[gr]);
        }
        for (int l = g.getNumLevels() - 1; l >= 0; l--) {
            runRange(this::backwardRange, g.levelStart[l], g.levelStart[l + 1]);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Kernels
    // ------------------------------------------------------------------------------------------

    private void forwardRange(int from, int to) {
        for (int p = from; p < to; p++) {
            forwardPin(p);
        }
    }

    /** Arrival times of the tags of a pin: max over its in-edges of the same tag, or its launch. */
    private void forwardPin(int p) {
        int s = tagStart[p];
        int t = tagStart[p + 1];
        if (s == t) {
            return;
        }
        int es = g.inStart[p];
        int et = g.inStart[p + 1];
        if (es == et) {
            // A startpoint: launch insertion (late) plus the clock's source latency
            int cp = b.launchCpin[p];
            for (int i = s; i < t; i++) {
                int clk = tagEdge[tagId[i]] >> 1;
                // The clock's own insertion (e.g. through its clock mux input), as for the capture
                arr[i] = insertion(b.clockModel, cp, clk, Corner.SLOW_MAX) + (float) latencyLate[clk];
                lcp[i] = cp;
            }
            if (xslr != null) {
                Arrays.fill(xslr, s, t, false);
            }
            if (arrAlt != null) {
                Arrays.fill(arrAlt, s, t, NEG_INF);
                Arrays.fill(lcpAlt, s, t, -1);
            }
            return;
        }
        if (arrAlt != null) {
            forwardPinCandidates(p, s, t, es, et);
            return;
        }
        final int[] eFrom = g.eFrom;
        final int[] eConn = g.eConn;
        final float[] fixed = g.eFixedMax;
        final float[] route = g.connRouteMax;
        final boolean[] xs = xslr;
        if (t - s == 1) {
            // A single tag (the common case): every tagged source pin has that tag only
            float max = NEG_INF;
            int lp = -1;
            boolean x = false;
            for (int e = es; e < et; e++) {
                int qs = tagStart[eFrom[e]];
                if (qs == tagStart[eFrom[e] + 1]) continue;
                int c = eConn[e];
                float v = arr[qs] + (c < 0 ? fixed[e] : fixed[e] + route[c]);
                if (max < v) {
                    max = v;
                    lp = lcp[qs];
                    if (xs != null) {
                        x = xs[qs] || c >= 0 && connCrossesSlr[c];
                    }
                }
            }
            arr[s] = max;
            lcp[s] = lp;
            if (xs != null) {
                xs[s] = x;
            }
            return;
        }
        for (int i = s; i < t; i++) {
            arr[i] = NEG_INF;
            lcp[i] = -1;
        }
        if (xs != null) {
            Arrays.fill(xs, s, t, false);
        }
        for (int e = es; e < et; e++) {
            int q = eFrom[e];
            int qs = tagStart[q];
            int qt = tagStart[q + 1];
            if (qs == qt) continue;
            int c = eConn[e];
            float d = c < 0 ? fixed[e] : fixed[e] + route[c];
            // Merge the sorted tag lists
            int i = s;
            for (int j = qs; j < qt; j++) {
                int tag = tagId[j];
                while (i < t && tagId[i] < tag) i++;
                if (i == t) break;
                float v = arr[j] + d;
                if (arr[i] < v) {
                    arr[i] = v;
                    lcp[i] = lcp[j];
                    if (xs != null) {
                        xs[i] = xs[j] || c >= 0 && connCrossesSlr[c];
                    }
                }
            }
        }
    }

    /**
     * The arrival times of the tags of a pin with the alternate launch candidates: for each tag slot,
     * the latest arrival (arg-max) and the latest among the launches of another key than the arg-max's.
     */
    private void forwardPinCandidates(int p, int s, int t, int es, int et) {
        final int[] eFrom = g.eFrom;
        final int[] eConn = g.eConn;
        final float[] fixed = g.eFixedMax;
        final float[] route = g.connRouteMax;
        final boolean[] xs = xslr;
        final boolean[] xa = xslrAlt;
        for (int i = s; i < t; i++) {
            arr[i] = NEG_INF;
            lcp[i] = -1;
            arrAlt[i] = NEG_INF;
            lcpAlt[i] = -1;
        }
        if (xs != null) {
            Arrays.fill(xs, s, t, false);
            Arrays.fill(xa, s, t, false);
        }
        for (int e = es; e < et; e++) {
            int q = eFrom[e];
            int qs = tagStart[q];
            int qt = tagStart[q + 1];
            if (qs == qt) continue;
            int c = eConn[e];
            float d = c < 0 ? fixed[e] : fixed[e] + route[c];
            boolean cross = xs != null && c >= 0 && connCrossesSlr[c];
            int i = s;
            for (int j = qs; j < qt; j++) {
                int tag = tagId[j];
                while (i < t && tagId[i] < tag) i++;
                if (i == t) break;
                if (tagId[i] != tag) continue;
                if (lcp[j] >= 0 || arr[j] != NEG_INF) {
                    insertLate(i, arr[j] + d, lcp[j], xs != null && (xs[j] || cross));
                }
                if (lcpAlt[j] >= 0) {
                    insertLate(i, arrAlt[j] + d, lcpAlt[j], xs != null && (xa[j] || cross));
                }
            }
        }
    }

    /** Inserts a launch candidate into a tag slot's arg-max and alternate (latest of another key) */
    private void insertLate(int i, float v, int lp, boolean x) {
        int k = launchKey(lp, x);
        int pk = launchKey(lcp[i], xslr != null && xslr[i]);
        if (arr[i] < v) {
            if (lcp[i] >= 0 && pk != k) {
                arrAlt[i] = arr[i];
                lcpAlt[i] = lcp[i];
                if (xslrAlt != null) xslrAlt[i] = xslr[i];
            }
            arr[i] = v;
            lcp[i] = lp;
            if (xslr != null) xslr[i] = x;
        } else if (lp >= 0 && k != pk && arrAlt[i] < v) {
            arrAlt[i] = v;
            lcpAlt[i] = lp;
            if (xslrAlt != null) xslrAlt[i] = x;
        }
    }

    /** Inserts a launch candidate into a tag slot's arg-min (hold) and alternate (earliest of another key) */
    private void insertEarly(int i, float v, int lp, boolean x) {
        final float[] arrMin = activeHold.arrMin;
        final int[] lcpMin = activeHold.lcpMin;
        final boolean[] xslrMin = activeHold.xslrMin;
        final float[] arrMinAlt = activeHold.arrMinAlt;
        final int[] lcpMinAlt = activeHold.lcpMinAlt;
        final boolean[] xslrMinAlt = activeHold.xslrMinAlt;
        int k = launchKey(lp, x);
        int pk = launchKey(lcpMin[i], xslrMin != null && xslrMin[i]);
        if (arrMin[i] > v) {
            if (lcpMin[i] >= 0 && pk != k) {
                arrMinAlt[i] = arrMin[i];
                lcpMinAlt[i] = lcpMin[i];
                if (xslrMinAlt != null) xslrMinAlt[i] = xslrMin[i];
            }
            arrMin[i] = v;
            lcpMin[i] = lp;
            if (xslrMin != null) xslrMin[i] = x;
        } else if (lp >= 0 && k != pk && arrMinAlt[i] > v) {
            arrMinAlt[i] = v;
            lcpMinAlt[i] = lp;
            if (xslrMinAlt != null) xslrMinAlt[i] = x;
        }
    }

    /**
     * The setup credit of a tag slot's checks, with the alternate launch if it makes them worse: the
     * minimum over the candidates of credit + (arg-max arrival - candidate arrival).
     */
    private float slotSetupCredit(ClockModel cm, int i, int cap) {
        int lp = lcp[i];
        if (lp < 0 || ideal[tagEdge[tagId[i]] >> 1]) {
            return 0f;
        }
        int clk = tagEdge[tagId[i]] >> 1;
        float credit = setupCredit(cm, lp, cap, xslr != null && xslr[i], clk);
        if (arrAlt != null && lcpAlt[i] >= 0) {
            float alt = setupCredit(cm, lcpAlt[i], cap, xslrAlt != null && xslrAlt[i], clk) + (arr[i] - arrAlt[i]);
            credit = Math.min(credit, alt);
        }
        return credit;
    }

    /** As {@link #slotSetupCredit}, for hold: the minimum of credit + (candidate arrival - arg-min arrival) */
    private float slotHoldCredit(ClockModel cm, int i, int cap) {
        final float[] arrMin = activeHold.arrMin;
        final int[] lcpMin = activeHold.lcpMin;
        final boolean[] xslrMin = activeHold.xslrMin;
        final float[] arrMinAlt = activeHold.arrMinAlt;
        final int[] lcpMinAlt = activeHold.lcpMinAlt;
        final boolean[] xslrMinAlt = activeHold.xslrMinAlt;
        int lp = lcpMin[i];
        if (lp < 0 || ideal[tagEdge[tagId[i]] >> 1]) {
            return 0f;
        }
        int clk = tagEdge[tagId[i]] >> 1;
        float credit = holdCredit(cm, lp, cap, xslrMin != null && xslrMin[i], arrMin[i], clk);
        if (arrMinAlt != null && lcpMinAlt[i] >= 0) {
            float alt = holdCredit(cm, lcpMinAlt[i], cap, xslrMinAlt != null && xslrMinAlt[i], arrMinAlt[i], clk)
                    + (arrMinAlt[i] - arrMin[i]);
            credit = Math.min(credit, alt);
        }
        return credit;
    }

    /** Whether a tag slot's alternate setup launch makes its checks worse than the arg-max's */
    private boolean alternateIsWorse(ClockModel cm, int i, int cap, boolean setup) {
        final float[] arrMin = activeHold.arrMin;
        final int[] lcpMin = activeHold.lcpMin;
        final boolean[] xslrMin = activeHold.xslrMin;
        final float[] arrMinAlt = activeHold.arrMinAlt;
        final int[] lcpMinAlt = activeHold.lcpMinAlt;
        final boolean[] xslrMinAlt = activeHold.xslrMinAlt;
        int clk = tagEdge[tagId[i]] >> 1;
        if (setup) {
            if (arrAlt == null || lcpAlt[i] < 0 || lcp[i] < 0) return false;
            float main = setupCredit(cm, lcp[i], cap, xslr != null && xslr[i], clk);
            float alt = setupCredit(cm, lcpAlt[i], cap, xslrAlt != null && xslrAlt[i], clk) + (arr[i] - arrAlt[i]);
            return alt < main;
        }
        if (arrMinAlt == null || lcpMinAlt[i] < 0 || lcpMin[i] < 0) return false;
        float main = holdCredit(cm, lcpMin[i], cap, xslrMin != null && xslrMin[i], arrMin[i], clk);
        float alt = holdCredit(cm, lcpMinAlt[i], cap, xslrMinAlt != null && xslrMinAlt[i], arrMinAlt[i], clk)
                + (arrMinAlt[i] - arrMin[i]);
        return alt < main;
    }

    /**
     * The credit of a setup check between two clock pins: its CRPR (signed, e.g. negative behind an
     * MMCM with ZHOLD) less the inter-SLR compensation at the capture corner (see
     * {@link ClockModel#getInterSlrCompensation}).
     */
    private float setupCredit(ClockModel cm, int launchPin, int capturePin, boolean dataCrossesSlr, int clock) {
        float prorate = b.getInterSlrProrate();
        float credit = cm.getSetupCrpr(launchPin, capturePin, clock);
        return prorate == 0f ? credit
                : credit - cm.getInterSlrCompensation(launchPin, capturePin, Corner.SLOW_MIN, prorate, dataCrossesSlr,
                        clock);
    }

    /**
     * The credit of a hold check between two clock pins at the active hold corner, see {@link #setupCredit}:
     * the CRPR of the corner (FAST_MAX - FAST_MIN, or SLOW_MAX - SLOW_MIN at the slow corner) less the
     * inter-SLR compensation: (DCD - CCD) * PF at the fast corner, (SCD + data path - CCD) * PF, i.e. of the
     * arrival, at the slow corner (as Vivado's).
     * @param arrival The arrival time of the check's data at its endpoint.
     */
    private float holdCredit(ClockModel cm, int launchPin, int capturePin, boolean dataCrossesSlr, float arrival,
                             int clock) {
        boolean slow = activeHold.slow;
        float prorate = b.getInterSlrProrate();
        float credit = slow ? cm.getSetupCrpr(launchPin, capturePin, clock)
                : cm.getHoldCrpr(launchPin, capturePin, clock);
        if (prorate == 0f) {
            return credit;
        }
        return credit - (slow
                ? cm.getInterSlrCompensationOfArrival(launchPin, capturePin, arrival, Corner.SLOW_MIN, prorate,
                        dataCrossesSlr, clock)
                : cm.getInterSlrCompensation(launchPin, capturePin, Corner.FAST_MAX, prorate, dataCrossesSlr, clock));
    }

    /**
     * Whether each connection of a graph has its source and sink in different SLRs (a data path through
     * it crosses an SLR boundary, which makes its checks get the inter-SLR compensation).
     */
    private static boolean[] getConnectionsCrossingSlrs(StaGraph g) {
        boolean[] cross = new boolean[g.getNumConnections()];
        for (int c = 0; c < cross.length; c++) {
            SitePinInst sink = g.getConnectionSink(c);
            Net net = sink == null ? null : sink.getNet();
            SitePinInst source = sink instanceof StaGraphBuilder.DetachedSitePin
                    ? ((StaGraphBuilder.DetachedSitePin) sink).getSource() : net == null ? null : net.getSource();
            if (source == null || source.getTile() == null || sink.getTile() == null) continue;
            SLR a = source.getTile().getSLR();
            SLR z = sink.getTile().getSLR();
            cross[c] = a != null && z != null && a.getId() != z.getId();
        }
        return cross;
    }

    private void evalEndpointsRange(int from, int to) {
        ClockModel cm = b.clockModel;
        for (int k = from; k < to; k++) {
            int p = endPin[k];
            int cap = b.captureCpin[p];
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                int es = epBase[k] + (i - tagStart[p]);
                float crpr = slotSetupCredit(cm, i, cap);
                for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                    float base = chkSetupBase[c];
                    chkReq[c] = Float.isNaN(base) ? POS_INF : base + (ideal[chkEdge[c] >> 1] ? 0f : crpr);
                }
            }
        }
    }

    /** Shifted required times of the tags of a range of pins, with the group of their minimum */
    private void backwardRange(int from, int to) {
        final int[] outStart = g.outStart;
        final int[] outEdge = g.outEdge;
        final int[] eTo = g.eTo;
        final int[] eConn = g.eConn;
        final float[] fixed = g.eFixedMax;
        final float[] route = g.connRouteMax;
        for (int p = to - 1; p >= from; p--) {
            int s = tagStart[p];
            int t = tagStart[p + 1];
            if (s == t) continue;
            int k = pinEnd[p];
            int os = outStart[p];
            int ot = outStart[p + 1];
            for (int i = s; i < t; i++) {
                float min = POS_INF;
                int grp = -1;
                if (k >= 0) {
                    int es = epBase[k] + (i - s);
                    for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                        float r = chkReq[c];
                        if (r == POS_INF) continue;
                        int gr = chkGroup[c];
                        float v = (r - groupT[gr]) + groupD[gr];
                        if (v < min) {
                            min = v;
                            grp = gr;
                        }
                    }
                }
                // A pin without fanout that is no endpoint constrains nothing: +infinity and no group
                // (DANGLING_REQUIRED), so that it never wins the minimum of a real fan-in cone
                req[i] = min;
                reqGroup[i] = grp;
            }
            for (int o = os; o < ot; o++) {
                int e = outEdge[o];
                int r = eTo[e];
                int rs = tagStart[r];
                int rt = tagStart[r + 1];
                if (rs == rt) continue;
                int c = eConn[e];
                float d = c < 0 ? fixed[e] : fixed[e] + route[c];
                if (t - s == 1 && rt - rs == 1) {
                    // A single tag on both pins: the same one
                    float v = req[rs] - d;
                    if (v < req[s]) {
                        req[s] = v;
                        reqGroup[s] = reqGroup[rs];
                    }
                    continue;
                }
                int j = rs;
                for (int i = s; i < t; i++) {
                    int tag = tagId[i];
                    while (j < rt && tagId[j] < tag) j++;
                    if (j == rt) break;
                    if (tagId[j] != tag) continue;
                    float v = req[j] - d;
                    if (v < req[i]) {
                        req[i] = v;
                        reqGroup[i] = reqGroup[j];
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Criticality
    // ------------------------------------------------------------------------------------------

    /**
     * Computes the criticality of each connection (see the class description). Runs
     * {@link #update()} first.
     * @param maxCriticality The maximum criticality.
     * @param criticalityExponent The criticality exponent.
     */
    public void computeCriticality(final float maxCriticality, final float criticalityExponent) {
        update();
        if (connCrit == null) {
            connCrit = new float[g.numConns];
        }
        int n = g.numConns;
        float[] classD = getNormalizationClasses();
        if (classD.length <= 1) {
            // A single normalization D_g: the minimum shifted required time is that of the largest criticality
            Kernel k = (from, to) -> {
                for (int c = from; c < to; c++) {
                    connCrit[c] = connectionCriticality(c, maxCriticality, criticalityExponent);
                }
            };
            runConnections(k, n);
            return;
        }
        // Several normalizations: the criticality 1 - (slack + shift_g)/D_g of a group is not monotonic in the
        // shifted required time across groups of different D_g, so each class of groups with the same D_g gets
        // its own backward pass (in one scratch array), and a connection the maximum over the classes
        Arrays.fill(connCrit, 0f);
        if (classReq == null) {
            classReq = new float[req.length];
        }
        for (int cls = 0; cls < classD.length; cls++) {
            final int k = cls;
            final float d = classD[cls];
            for (int l = g.getNumLevels() - 1; l >= 0; l--) {
                runRange((from, to) -> backwardClassRange(from, to, k), g.levelStart[l], g.levelStart[l + 1]);
            }
            runConnections((from, to) -> {
                for (int c = from; c < to; c++) {
                    float crit = connectionCriticality(c, classReq, d, maxCriticality, criticalityExponent);
                    if (crit > connCrit[c]) {
                        connCrit[c] = crit;
                    }
                }
            }, n);
        }
    }

    private void runConnections(Kernel k, int n) {
        if (parallel && n >= 2 * MIN_CHUNK) {
            pool.invoke(new RangeTask(k, 0, n, chunkSize(n)));
        } else {
            k.run(0, n);
        }
    }

    /**
     * Sorts the groups with checks into classes of equal normalization D_g (see {@link #groupClass}).
     * @return The D_g of each class.
     */
    private float[] getNormalizationClasses() {
        if (groupClass == null) {
            groupClass = new int[numGroups];
        }
        float[] classD = new float[0];
        for (int gr = 0; gr < numGroups; gr++) {
            float d = groupD[gr];
            groupClass[gr] = -1;
            if (d == POS_INF) continue;
            for (int k = 0; k < classD.length; k++) {
                if (classD[k] == d) {
                    groupClass[gr] = k;
                    break;
                }
            }
            if (groupClass[gr] < 0) {
                classD = Arrays.copyOf(classD, classD.length + 1);
                classD[classD.length - 1] = d;
                groupClass[gr] = classD.length - 1;
            }
        }
        return classD;
    }

    /** As {@link #backwardRange(int, int)}, into {@link #classReq}, for the checks of one normalization class */
    private void backwardClassRange(int from, int to, int cls) {
        final int[] outStart = g.outStart;
        final int[] outEdge = g.outEdge;
        final int[] eTo = g.eTo;
        final int[] eConn = g.eConn;
        final float[] fixed = g.eFixedMax;
        final float[] route = g.connRouteMax;
        final float[] creq = classReq;
        for (int p = to - 1; p >= from; p--) {
            int s = tagStart[p];
            int t = tagStart[p + 1];
            if (s == t) continue;
            int k = pinEnd[p];
            for (int i = s; i < t; i++) {
                float min = POS_INF;
                if (k >= 0) {
                    int es = epBase[k] + (i - s);
                    for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                        float r = chkReq[c];
                        if (r == POS_INF) continue;
                        int gr = chkGroup[c];
                        if (groupClass[gr] != cls) continue;
                        float v = (r - groupT[gr]) + groupD[gr];
                        if (v < min) {
                            min = v;
                        }
                    }
                }
                creq[i] = min;
            }
            for (int o = outStart[p]; o < outStart[p + 1]; o++) {
                int e = outEdge[o];
                int r = eTo[e];
                int rs = tagStart[r];
                int rt = tagStart[r + 1];
                if (rs == rt) continue;
                int c = eConn[e];
                float d = c < 0 ? fixed[e] : fixed[e] + route[c];
                int j = rs;
                for (int i = s; i < t; i++) {
                    int tag = tagId[i];
                    while (j < rt && tagId[j] < tag) j++;
                    if (j == rt) break;
                    if (tagId[j] != tag) continue;
                    float v = creq[j] - d;
                    if (v < creq[i]) {
                        creq[i] = v;
                    }
                }
            }
        }
    }

    /** The criticality of a connection for one normalization class, from its shifted required times */
    private float connectionCriticality(int conn, float[] creq, float classD, float maxCriticality, float exponent) {
        float best = 0f;
        for (int x = g.connEdgeStart[conn]; x < g.connEdgeStart[conn + 1]; x++) {
            int e = g.connEdges[x];
            int q = g.eFrom[e];
            int r = g.eTo[e];
            int qs = tagStart[q];
            int qt = tagStart[q + 1];
            int rs = tagStart[r];
            int rt = tagStart[r + 1];
            if (qs == qt || rs == rt) continue;
            float d = g.getEdgeDelayMax(e);
            int j = rs;
            for (int i = qs; i < qt; i++) {
                int tag = tagId[i];
                while (j < rt && tagId[j] < tag) j++;
                if (j == rt) break;
                if (tagId[j] != tag || creq[j] == POS_INF) continue;
                float crit = criticality(creq[j] - arr[i] - d, classD, maxCriticality, exponent);
                if (crit > best) {
                    best = crit;
                }
            }
        }
        return best;
    }

    private float connectionCriticality(int conn, float maxCriticality, float exponent) {
        float best = 0f;
        for (int x = g.connEdgeStart[conn]; x < g.connEdgeStart[conn + 1]; x++) {
            int e = g.connEdges[x];
            int q = g.eFrom[e];
            int r = g.eTo[e];
            int qs = tagStart[q];
            int qt = tagStart[q + 1];
            int rs = tagStart[r];
            int rt = tagStart[r + 1];
            if (qs == qt || rs == rt) continue;
            float d = g.getEdgeDelayMax(e);
            int j = rs;
            for (int i = qs; i < qt; i++) {
                int tag = tagId[i];
                while (j < rt && tagId[j] < tag) j++;
                if (j == rt) break;
                if (tagId[j] != tag) continue;
                int gr = reqGroup[j];
                if (gr < 0 || req[j] == POS_INF) continue;
                float slack = req[j] - arr[i] - d;
                float crit = criticality(slack, groupD[gr], maxCriticality, exponent);
                if (crit > best) {
                    best = crit;
                }
            }
        }
        return best;
    }

    /**
     * Gets the criticality of a slack: {@code (1 - max(minSlack, 0) / maxDelay)^exponent * maxCriticality}, 0 if
     * not positive or if the slack is not below maxDelay.
     * @param minSlack The slack, e.g. the minimum slack of a connection's edges (Float.MAX_VALUE if none), shifted
     *                 by its group's WNS (see the class description).
     * @param maxDelay The normalization D_g of the slack's group.
     * @param maxCriticality The maximum criticality.
     * @param exponent The criticality exponent.
     * @return The criticality.
     */
    public static float getCriticality(float minSlack, float maxDelay, float maxCriticality, float exponent) {
        return criticality(minSlack, maxDelay, maxCriticality, exponent);
    }

    private static float criticality(float minSlack, float maxDelay, float maxCriticality, float exponent) {
        if (!(minSlack < maxDelay)) {
            return 0;
        }
        minSlack = Math.max(minSlack, 0);
        float criticality = (1 - minSlack / maxDelay);
        criticality = (float) Math.pow(criticality, exponent) * maxCriticality;
        return criticality > 0 ? criticality : 0;
    }

    /** @return The criticality of a connection computed by {@link #computeCriticality(float, float)}. */
    public float getCriticality(int conn) {
        return connCrit[conn];
    }

    // ------------------------------------------------------------------------------------------
    // Hold
    // ------------------------------------------------------------------------------------------

    /**
     * Runs the hold analysis (min arrival times with the min delays) if a min delay changed since
     * the last one.
     * @return True if a pass was run.
     */
    public boolean updateHold() {
        boolean ran = updateHoldCorner(fastHold);
        if (slowHold != null) {
            ran |= updateHoldCorner(slowHold);
            useHold(fastHold);
        }
        return ran;
    }

    /** Runs the hold analysis of a corner if needed, leaving it active */
    private boolean updateHoldCorner(HoldCorner h) {
        useHold(h);
        if (h.slow && h.chkHoldBase == null) {
            h.chkHoldBase = slowHoldBases();
        }
        if (h.valid && h.arrMin != null) {
            return false;
        }
        if (h.arrMin == null) {
            h.arrMin = new float[tagId.length];
            h.lcpMin = new int[tagId.length];
            h.xslrMin = connCrossesSlr == null ? null : new boolean[tagId.length];
            if (cpinKey != null) {
                h.arrMinAlt = new float[tagId.length];
                h.lcpMinAlt = new int[tagId.length];
                h.xslrMinAlt = h.xslrMin == null ? null : new boolean[tagId.length];
            }
            h.epHoldSlack = new float[epBase[numEnds]];
        }
        for (int l = 0; l < g.getNumLevels(); l++) {
            runRange(this::forwardMinRange, g.levelStart[l], g.levelStart[l + 1]);
        }
        runRange(this::evalHoldRange, 0, numEnds);
        if (refineHold) {
            if (h.refLcp == null) {
                h.refLcp = new int[epBase[numEnds]];
                h.refArr = new float[epBase[numEnds]];
            }
            Arrays.fill(h.refLcp, -1);
            runRange(this::refineHoldRange, 0, numEnds);
        }
        h.valid = true;
        return true;
    }

    /**
     * Refines the hold slack of the endpoint slots below {@link #HOLD_REFINE_SLACK}: the exact worst launch,
     * searched back from the endpoint (best first by lower bound of arrival) over the launches whose arrival
     * at the endpoint is within {@link #HOLD_REFINE_SPREAD} of the earliest, each with its own CRPR credit.
     */
    private void refineHoldRange(int from, int to) {
        final float[] arrMin = activeHold.arrMin;
        final float[] epHoldSlack = activeHold.epHoldSlack;
        final float[] chkHoldBase = activeHold.chkHoldBase;
        final int[] refLcp = activeHold.refLcp;
        final float[] refArr = activeHold.refArr;
        final boolean slow = activeHold.slow;
        final float[] fixed = slow ? g.eFixedMin2 : g.eFixedMin;
        final float[] route = slow ? g.connRouteMin2 : g.connRouteMin;
        ClockModel cm = b.clockModel;
        PinDistances best = new PinDistances();
        PriorityQueue<HoldSearchEntry> heap = new PriorityQueue<>((x, y) -> Float.compare(x.key, y.key));
        for (int k = from; k < to; k++) {
            int p = endPin[k];
            int cap = b.captureCpin[p];
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                int es = epBase[k] + (i - tagStart[p]);
                if (!(epHoldSlack[es] < HOLD_REFINE_SLACK) || arrMin[i] == POS_INF) continue;
                if (ideal[tagEdge[tagId[i]] >> 1]) continue;
                int tag = tagId[i];
                float bound = arrMin[i] + HOLD_REFINE_SPREAD;
                best.clear();
                heap.clear();
                best.put(p, 0f);
                heap.add(new HoldSearchEntry(arrMin[i], p, 0f));
                float worstSlack = epHoldSlack[es];
                int worstLcp = -1;
                float worstArr = 0f;
                while (!heap.isEmpty()) {
                    HoldSearchEntry top = heap.poll();
                    if (top.key > bound) break;
                    int q = top.pin;
                    float dist = top.dist;
                    if (best.get(q) < dist) continue;
                    if (g.inStart[q] == g.inStart[q + 1]) {
                        // A startpoint: its launch with its own credit
                        int lp = b.launchCpin[q];
                        int j = slotOf(q, tag);
                        if (lp < 0 || j < 0) continue;
                        float arrival = arrMin[j] + dist;
                        float credit = holdCredit(cm, lp, cap, false, arrival, tagEdge[tag] >> 1);
                        for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                            float hb = chkHoldBase[c];
                            if (Float.isNaN(hb)) continue;
                            float sl = arrival - (hb - (ideal[chkEdge[c] >> 1] ? 0f : credit));
                            if (sl < worstSlack) {
                                worstSlack = sl;
                                worstLcp = lp;
                                worstArr = arrival;
                            }
                        }
                        continue;
                    }
                    for (int e = g.inStart[q]; e < g.inStart[q + 1]; e++) {
                        int r = g.eFrom[e];
                        int j = slotOf(r, tag);
                        if (j < 0 || arrMin[j] == POS_INF) continue;
                        int cn = g.eConn[e];
                        float nd = dist + (cn < 0 ? fixed[e] : fixed[e] + route[cn]);
                        if (best.contains(r) && best.get(r) <= nd) continue;
                        float key = arrMin[j] + nd;
                        if (key > bound) continue;
                        best.put(r, nd);
                        heap.add(new HoldSearchEntry(key, r, nd));
                    }
                }
                if (worstLcp >= 0) {
                    epHoldSlack[es] = worstSlack;
                    refLcp[es] = worstLcp;
                    refArr[es] = worstArr;
                }
            }
        }
    }

    /**
     * The distances of the pins reached by a search of {@link #refineHoldRange}: an open-addressing map of pin to
     * float, cleared in constant time.
     */
    static final class PinDistances {
        private int[] keys = new int[64];
        private float[] values = new float[64];
        /** The clear count at which each slot was set (a slot of another count is empty) */
        private int[] stamps = new int[64];
        private int stamp = 1;
        private int size;

        void clear() {
            size = 0;
            if (++stamp == Integer.MAX_VALUE) {
                Arrays.fill(stamps, 0);
                stamp = 1;
            }
        }

        private int slot(int key) {
            int mask = keys.length - 1;
            int i = (key * 0x9E3779B9) >>> 7 & mask;
            while (stamps[i] == stamp && keys[i] != key) {
                i = (i + 1) & mask;
            }
            return i;
        }

        boolean contains(int key) {
            return stamps[slot(key)] == stamp;
        }

        /** @return The distance of a pin that is in the map. */
        float get(int key) {
            return values[slot(key)];
        }

        void put(int key, float value) {
            int i = slot(key);
            if (stamps[i] != stamp) {
                if (2 * (size + 1) > keys.length) {
                    grow();
                    i = slot(key);
                }
                stamps[i] = stamp;
                keys[i] = key;
                size++;
            }
            values[i] = value;
        }

        private void grow() {
            int[] k = keys;
            float[] v = values;
            int[] st = stamps;
            int old = stamp;
            keys = new int[k.length * 2];
            values = new float[k.length * 2];
            stamps = new int[k.length * 2];
            stamp = 1;
            for (int i = 0; i < k.length; i++) {
                if (st[i] == old) {
                    int j = slot(k[i]);
                    stamps[j] = stamp;
                    keys[j] = k[i];
                    values[j] = v[i];
                }
            }
        }
    }

    /**
     * An entry of the search of {@link #refineHoldRange}: a pin (its exact id, which a float would round above
     * 2^24 pins), its lower bound of arrival and its distance to the endpoint.
     */
    static final class HoldSearchEntry {
        final float key;
        final int pin;
        final float dist;

        HoldSearchEntry(float key, int pin, float dist) {
            this.key = key;
            this.pin = pin;
            this.dist = dist;
        }
    }

    private void evalHoldRange(int from, int to) {
        final float[] arrMin = activeHold.arrMin;
        final float[] epHoldSlack = activeHold.epHoldSlack;
        final float[] chkHoldBase = activeHold.chkHoldBase;
        ClockModel cm = b.clockModel;
        for (int k = from; k < to; k++) {
            int p = endPin[k];
            int cap = b.captureCpin[p];
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                int es = epBase[k] + (i - tagStart[p]);
                float worstReq = NEG_INF;
                float crpr = slotHoldCredit(cm, i, cap);
                for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                    float hb = chkHoldBase[c];
                    if (Float.isNaN(hb)) continue;
                    worstReq = Math.max(worstReq, hb - (ideal[chkEdge[c] >> 1] ? 0f : crpr));
                }
                epHoldSlack[es] = worstReq == NEG_INF || arrMin[i] == POS_INF ? POS_INF : arrMin[i] - worstReq;
            }
        }
    }

    private void forwardMinRange(int from, int to) {
        final float[] arrMin = activeHold.arrMin;
        final int[] lcpMin = activeHold.lcpMin;
        final boolean[] xslrMin = activeHold.xslrMin;
        final float[] arrMinAlt = activeHold.arrMinAlt;
        final int[] lcpMinAlt = activeHold.lcpMinAlt;
        final boolean[] xslrMinAlt = activeHold.xslrMinAlt;
        final boolean slow = activeHold.slow;
        final int[] eFrom = g.eFrom;
        final int[] eConn = g.eConn;
        final float[] fixed = slow ? g.eFixedMin2 : g.eFixedMin;
        final float[] route = slow ? g.connRouteMin2 : g.connRouteMin;
        final boolean[] xs = xslrMin;
        for (int p = from; p < to; p++) {
            int s = tagStart[p];
            int t = tagStart[p + 1];
            if (s == t) continue;
            int es = g.inStart[p];
            int et = g.inStart[p + 1];
            if (es == et) {
                int cp = b.launchCpin[p];
                Corner corner = slow ? Corner.SLOW_MIN : Corner.FAST_MIN;
                for (int i = s; i < t; i++) {
                    int clk = tagEdge[tagId[i]] >> 1;
                    arrMin[i] = insertion(b.clockModel, cp, clk, corner) + (float) latencyEarly[clk];
                    lcpMin[i] = cp;
                }
                if (xs != null) {
                    Arrays.fill(xs, s, t, false);
                }
                if (arrMinAlt != null) {
                    Arrays.fill(arrMinAlt, s, t, POS_INF);
                    Arrays.fill(lcpMinAlt, s, t, -1);
                }
                continue;
            }
            for (int i = s; i < t; i++) {
                arrMin[i] = POS_INF;
                lcpMin[i] = -1;
            }
            if (xs != null) {
                Arrays.fill(xs, s, t, false);
            }
            if (arrMinAlt != null) {
                Arrays.fill(arrMinAlt, s, t, POS_INF);
                Arrays.fill(lcpMinAlt, s, t, -1);
                if (xslrMinAlt != null) {
                    Arrays.fill(xslrMinAlt, s, t, false);
                }
                for (int e = es; e < et; e++) {
                    int q = eFrom[e];
                    int qs = tagStart[q];
                    int qt = tagStart[q + 1];
                    if (qs == qt) continue;
                    int c = eConn[e];
                    float d = c < 0 ? fixed[e] : fixed[e] + route[c];
                    boolean cross = xs != null && c >= 0 && connCrossesSlr[c];
                    int i = s;
                    for (int j = qs; j < qt; j++) {
                        int tag = tagId[j];
                        while (i < t && tagId[i] < tag) i++;
                        if (i == t) break;
                        if (tagId[i] != tag) continue;
                        if (lcpMin[j] >= 0 || arrMin[j] != POS_INF) {
                            insertEarly(i, arrMin[j] + d, lcpMin[j], xs != null && (xs[j] || cross));
                        }
                        if (lcpMinAlt[j] >= 0) {
                            insertEarly(i, arrMinAlt[j] + d, lcpMinAlt[j],
                                    xs != null && (xslrMinAlt[j] || cross));
                        }
                    }
                }
                continue;
            }
            for (int e = es; e < et; e++) {
                int q = eFrom[e];
                int qs = tagStart[q];
                int qt = tagStart[q + 1];
                if (qs == qt) continue;
                int c = eConn[e];
                float d = c < 0 ? fixed[e] : fixed[e] + route[c];
                int i = s;
                for (int j = qs; j < qt; j++) {
                    int tag = tagId[j];
                    while (i < t && tagId[i] < tag) i++;
                    if (i == t) break;
                    float v = arrMin[j] + d;
                    if (arrMin[i] > v) {
                        arrMin[i] = v;
                        lcpMin[i] = lcpMin[j];
                        if (xs != null) {
                            xs[i] = xs[j] || c >= 0 && connCrossesSlr[c];
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Slacks and reports
    // ------------------------------------------------------------------------------------------

    /** A timing check of an endpoint tag, with its slack and breakdown (ps) */
    public static final class Check {
        int endpoint;
        int tag;
        int launchEdge;
        int captureEdge;
        int launchClockPin;
        int captureClockPin;
        float relation;
        float launchInsertion;
        float launchLatency;
        float captureInsertion;
        float captureLatency;
        float crpr;
        float uncertainty;
        float checkTime;
        float arrival;
        float required;
        float slack;
        /** Whether the check launches from the tag slot's alternate candidate (not the arg-max arrival) */
        boolean alternateLaunch;
        /**
         * The corner of the data path: SLOW_MAX for setup, FAST_MIN or SLOW_MIN for hold (whose capture clock
         * is at FAST_MAX or SLOW_MAX)
         */
        Corner corner;
        /** Whether this is a setup check (else a hold check) */
        boolean setup;

        Check() {
        }

        /** @return The endpoint pin of the check. */
        public int getEndpoint() {
            return endpoint;
        }

        /** @return The launch clock edge: 2 * clock id + 1 if falling. */
        public int getLaunchEdge() {
            return launchEdge;
        }

        /** @return The capture clock edge: 2 * clock id + 1 if falling. */
        public int getCaptureEdge() {
            return captureEdge;
        }

        /** @return The time between the launch and capture clock edges (multicycle paths included). */
        public float getRelation() {
            return relation;
        }

        /** @return The CRPR credit of the check (less the inter-SLR compensation, if any). */
        public float getCrpr() {
            return crpr;
        }

        /** @return The clock uncertainty of the check. */
        public float getUncertainty() {
            return uncertainty;
        }

        /** @return The setup or hold time of the endpoint. */
        public float getCheckTime() {
            return checkTime;
        }

        /** @return The arrival time of the data at the endpoint, from the launch clock edge. */
        public float getArrival() {
            return arrival;
        }

        /** @return The required time of the data at the endpoint. */
        public float getRequired() {
            return required;
        }

        /** @return The slack: required - arrival for setup, arrival - required for hold. */
        public float getSlack() {
            return slack;
        }

        /**
         * @return The corner of the data path: SLOW_MAX for setup, FAST_MIN or SLOW_MIN for hold (whose capture
         * clock is at FAST_MAX or SLOW_MAX).
         */
        public Corner getCorner() {
            return corner;
        }

        /** @return Whether this is a setup check (else a hold check). */
        public boolean isSetup() {
            return setup;
        }

        /**
         * @return The clock skew: capture clock delay - launch clock delay, plus the CRPR for a setup
         * check, minus it for a hold check.
         */
        public float getSkew() {
            float d = captureInsertion + captureLatency - launchInsertion - launchLatency;
            return setup ? d + crpr : d - crpr;
        }

        /** @return The data path delay: arrival - launch clock delay. */
        public float getDataPathDelay() {
            return arrival - launchInsertion - launchLatency;
        }
    }

    /**
     * Gets the worst setup check of an endpoint pin, after {@link #update()}.
     * @param pin The endpoint pin.
     * @return The check of least slack, or null if it has none.
     */
    public Check getWorstSetupCheck(int pin) {
        int k = pinEnd[pin];
        if (k < 0) return null;
        Check worst = null;
        for (int i = tagStart[pin]; i < tagStart[pin + 1]; i++) {
            if (arr[i] == NEG_INF) continue;
            int es = epBase[k] + (i - tagStart[pin]);
            for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                if (chkReq[c] == POS_INF) continue;
                float slack = chkReq[c] - arr[i];
                if (worst == null || slack < worst.slack) {
                    worst = makeCheck(k, i, c, true);
                }
            }
        }
        return worst;
    }

    /**
     * Gets the worst setup check of the design, after {@link #update()}: that of the endpoint of least setup slack
     * (the first such endpoint), whose slack is the WNS of the analysis.
     * @return The check, or null if no endpoint has a setup check.
     */
    public Check getWorstSetupCheck() {
        int worst = -1;
        float worstSlack = POS_INF;
        for (int k = 0; k < numEnds; k++) {
            float slack = getEndpointSetupSlack(k);
            if (slack != POS_INF && (worst < 0 || slack < worstSlack)) {
                worst = k;
                worstSlack = slack;
            }
        }
        return worst < 0 ? null : getWorstSetupCheck(endPin[worst]);
    }

    /**
     * Gets the worst hold check of an endpoint pin, after {@link #updateHold()}.
     * @param pin The endpoint pin.
     * @return The check of least hold slack, or null if it has none.
     */
    public Check getWorstHoldCheck(int pin) {
        int k = pinEnd[pin];
        if (k < 0) return null;
        refreshHold();
        Check worst = null;
        for (HoldCorner h : holdCorners()) {
            useHold(h);
            if (h.arrMin == null) continue;
            for (int i = tagStart[pin]; i < tagStart[pin + 1]; i++) {
                int es = epBase[k] + (i - tagStart[pin]);
                if (h.epHoldSlack[es] == POS_INF) continue;
                for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                    if (Float.isNaN(h.chkHoldBase[c])) continue;
                    Check ch = makeCheck(k, i, c, false);
                    if (worst == null || ch.slack < worst.slack) {
                        worst = ch;
                    }
                }
            }
        }
        useHold(fastHold);
        return worst;
    }

    /** The hold corners analyzed: fast, and slow if any */
    private List<HoldCorner> holdCorners() {
        return slowHold == null ? Collections.singletonList(fastHold) : Arrays.asList(fastHold, slowHold);
    }

    /**
     * Sets the means to replace the min route delays from the routing before a hold query, when a
     * caller sets placeholder min delays (see {@link #markHoldDelaysStale()}), e.g. the max delays of
     * connections being routed.
     * @param refresher Sets the min delays of all connections (through
     *                  {@link #setConnectionRouteDelay(int, float, float)}), or null.
     */
    public void setHoldDelayRefresher(Runnable refresher) {
        this.holdDelayRefresher = refresher;
    }

    /**
     * Marks the min route delays as placeholders: the next hold query runs the hold delay refresher
     * (see {@link #setHoldDelayRefresher(Runnable)}) first.
     */
    public void markHoldDelaysStale() {
        holdDelaysStale = true;
    }

    /**
     * Brings the hold analysis up to date before a hold query: refreshes the min route delays if they
     * are placeholders, then runs {@link #updateHold()} if needed.
     * @throws IllegalStateException If the min delays are placeholders and there is no refresher.
     */
    private void refreshHold() {
        update();
        if (holdDelaysStale) {
            if (holdDelayRefresher == null) {
                throw new IllegalStateException("The min (hold) route delays are placeholders: set them "
                        + "(e.g. TimingManager.updateHoldDelays()) before querying hold slacks");
            }
            holdDelaysStale = false;
            holdDelayRefresher.run();
        }
        updateHold();
    }

    /**
     * The hold slack of one check of an endpoint slot at the active hold corner: as the slot's (see
     * evalHoldRange and refineHoldRange), for that check only.
     */
    private float checkHoldSlack(ClockModel cm, int es, int i, int cap, int c) {
        final float[] arrMin = activeHold.arrMin;
        final float[] chkHoldBase = activeHold.chkHoldBase;
        final int[] refLcp = activeHold.refLcp;
        final float[] refArr = activeHold.refArr;
        float hb = chkHoldBase[c];
        if (Float.isNaN(hb) || arrMin == null || arrMin[i] == POS_INF) {
            return POS_INF;
        }
        boolean idealEdge = ideal[chkEdge[c] >> 1];
        if (refLcp != null && refLcp[es] >= 0) {
            float arrival = refArr[es];
            float credit = idealEdge ? 0f : holdCredit(cm, refLcp[es], cap, false, arrival, tagEdge[tagId[i]] >> 1);
            return arrival - (hb - credit);
        }
        return arrMin[i] - (hb - (idealEdge ? 0f : slotHoldCredit(cm, i, cap)));
    }

    /** The hold slack of an endpoint slot over the hold corners (+infinity if none) */
    private float slotHoldSlack(int es) {
        float s = fastHold.epHoldSlack == null ? POS_INF : fastHold.epHoldSlack[es];
        if (slowHold != null && slowHold.epHoldSlack != null) {
            s = Math.min(s, slowHold.epHoldSlack[es]);
        }
        return s;
    }

    /**
     * Gets all the timed checks of an endpoint pin (one per tag and capture clock edge), after
     * {@link #update()} (and {@link #updateHold()} for hold checks).
     * @param pin The endpoint pin.
     * @param setup Setup checks (true) or hold checks.
     * @return The checks, empty if none.
     */
    List<Check> getChecks(int pin, boolean setup) {
        List<Check> out = new ArrayList<>();
        int k = pinEnd[pin];
        if (k < 0) return out;
        if (!setup && fastHold.arrMin != null) {
            refreshHold();
        }
        for (HoldCorner h : setup ? Collections.singletonList(fastHold) : holdCorners()) {
            useHold(h);
            if (!setup && h.arrMin == null) continue;
            for (int i = tagStart[pin]; i < tagStart[pin + 1]; i++) {
                int es = epBase[k] + (i - tagStart[pin]);
                for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                    if (setup ? Float.isNaN(chkSetupBase[c]) : Float.isNaN(h.chkHoldBase[c])) continue;
                    out.add(makeCheck(k, i, c, setup));
                }
            }
        }
        useHold(fastHold);
        return out;
    }

    private Corner holdLaunchCorner() {
        return activeHold.slow ? Corner.SLOW_MIN : Corner.FAST_MIN;
    }

    private Corner holdCaptureCorner() {
        return activeHold.slow ? Corner.SLOW_MAX : Corner.FAST_MAX;
    }

    private Check makeCheck(int k, int slot, int c, boolean setup) {
        final float[] arrMin = activeHold.arrMin;
        final int[] lcpMin = activeHold.lcpMin;
        final boolean[] xslrMin = activeHold.xslrMin;
        final float[] arrMinAlt = activeHold.arrMinAlt;
        final int[] lcpMinAlt = activeHold.lcpMinAlt;
        final boolean[] xslrMinAlt = activeHold.xslrMinAlt;
        final float[] chkHoldBase = activeHold.chkHoldBase;
        final int[] refLcp = activeHold.refLcp;
        final float[] refArr = activeHold.refArr;
        int p = endPin[k];
        Check ch = new Check();
        ch.endpoint = p;
        ch.tag = tagId[slot];
        ch.launchEdge = tagEdge[ch.tag];
        ch.captureEdge = chkEdge[c];
        int lc = ch.launchEdge >> 1;
        int cc = ch.captureEdge >> 1;
        ClockModel cm = b.clockModel;
        ch.captureClockPin = b.captureCpin[p];
        ch.launchLatency = (float) (setup ? latencyLate[lc] : latencyEarly[lc]);
        ch.captureLatency = (float) (setup ? latencyEarly[cc] : latencyLate[cc]);
        ch.setup = setup;
        ch.corner = setup ? Corner.SLOW_MAX : holdLaunchCorner();
        boolean alt = !ideal[lc] && !ideal[cc] && alternateIsWorse(cm, slot, ch.captureClockPin, setup);
        ch.alternateLaunch = alt;
        if (setup && alt) {
            ch.launchClockPin = lcpAlt[slot];
            ch.relation = chkRelSetup[c];
            ch.launchInsertion = insertion(cm, ch.launchClockPin, lc, Corner.SLOW_MAX);
            ch.captureInsertion = insertion(cm, ch.captureClockPin, cc, Corner.SLOW_MIN);
            ch.crpr = setupCredit(cm, ch.launchClockPin, ch.captureClockPin, xslrAlt != null && xslrAlt[slot], lc);
            ch.uncertainty = b.uncertainty.getSetup(lc, cc);
            ch.checkTime = b.getSetupTime(p);
            ch.arrival = arrAlt[slot];
            // chkReq holds the alternate's credit plus its arrival deficit: the alternate's own required time
            ch.required = chkReq[c] - (arr[slot] - arrAlt[slot]);
            ch.slack = ch.required - ch.arrival;
        } else if (!setup && alt && (refLcp == null || refLcp[epBase[k] + (slot - tagStart[p])] < 0)) {
            ch.launchClockPin = lcpMinAlt[slot];
            ch.relation = chkRelHold[c];
            ch.launchInsertion = insertion(cm, ch.launchClockPin, lc, holdLaunchCorner());
            ch.captureInsertion = insertion(cm, ch.captureClockPin, cc, holdCaptureCorner());
            ch.crpr = holdCredit(cm, ch.launchClockPin, ch.captureClockPin, xslrMinAlt != null && xslrMinAlt[slot],
                    arrMinAlt[slot], lc);
            ch.uncertainty = b.uncertainty.getHold(lc, cc);
            ch.checkTime = b.getHoldTime(p, activeHold.slow);
            ch.arrival = arrMinAlt[slot];
            ch.required = chkHoldBase[c] - ch.crpr;
            ch.slack = ch.arrival - ch.required;
        } else if (setup) {
            ch.launchClockPin = lcp[slot];
            ch.relation = chkRelSetup[c];
            ch.launchInsertion = ch.launchClockPin < 0 ? 0f : insertion(cm, ch.launchClockPin, lc, Corner.SLOW_MAX);
            ch.captureInsertion = insertion(cm, ch.captureClockPin, cc, Corner.SLOW_MIN);
            ch.crpr = ch.launchClockPin < 0 || ideal[lc] || ideal[cc] ? 0f
                    : setupCredit(cm, ch.launchClockPin, ch.captureClockPin, xslr != null && xslr[slot], lc);
            ch.uncertainty = b.uncertainty.getSetup(lc, cc);
            ch.checkTime = b.getSetupTime(p);
            ch.arrival = arr[slot];
            ch.required = chkReq[c];
            ch.slack = ch.required - ch.arrival;
        } else if (refLcp != null && refLcp[epBase[k] + (slot - tagStart[p])] >= 0) {
            // The refined worst launch
            int es = epBase[k] + (slot - tagStart[p]);
            ch.launchClockPin = refLcp[es];
            ch.relation = chkRelHold[c];
            ch.launchInsertion = insertion(cm, ch.launchClockPin, lc, holdLaunchCorner());
            ch.captureInsertion = insertion(cm, ch.captureClockPin, cc, holdCaptureCorner());
            ch.crpr = ideal[cc] ? 0f
                    : holdCredit(cm, ch.launchClockPin, ch.captureClockPin, false, refArr[es], lc);
            ch.uncertainty = b.uncertainty.getHold(lc, cc);
            ch.checkTime = b.getHoldTime(p, activeHold.slow);
            ch.arrival = refArr[es];
            ch.required = chkHoldBase[c] - ch.crpr;
            ch.slack = ch.arrival - ch.required;
        } else {
            ch.launchClockPin = lcpMin[slot];
            ch.relation = chkRelHold[c];
            ch.launchInsertion = ch.launchClockPin < 0 ? 0f
                    : insertion(cm, ch.launchClockPin, lc, holdLaunchCorner());
            ch.captureInsertion = insertion(cm, ch.captureClockPin, cc, holdCaptureCorner());
            ch.crpr = ch.launchClockPin < 0 || ideal[lc] || ideal[cc] ? 0f
                    : holdCredit(cm, ch.launchClockPin, ch.captureClockPin, xslrMin != null && xslrMin[slot],
                    arrMin[slot], lc);
            ch.uncertainty = b.uncertainty.getHold(lc, cc);
            ch.checkTime = b.getHoldTime(p, activeHold.slow);
            ch.arrival = arrMin[slot];
            ch.required = chkHoldBase[c] - ch.crpr;
            ch.slack = ch.arrival - ch.required;
        }
        return ch;
    }

    /**
     * Gets the worst setup slack of each endpoint over its tags and checks.
     * @return The slacks by endpoint index (see {@link #getEndpointPin(int)}), +infinity if none.
     */
    public float[] getEndpointSetupSlacks() {
        float[] out = new float[numEnds];
        for (int k = 0; k < numEnds; k++) {
            out[k] = getEndpointSetupSlack(k);
        }
        return out;
    }

    /** The worst setup slack of an endpoint over its tags and checks, +infinity if none */
    private float getEndpointSetupSlack(int k) {
        int p = endPin[k];
        float worst = POS_INF;
        for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
            if (arr[i] == NEG_INF) continue;
            int es = epBase[k] + (i - tagStart[p]);
            for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                if (chkReq[c] == POS_INF) continue;
                worst = Math.min(worst, chkReq[c] - arr[i]);
            }
        }
        return worst;
    }

    /**
     * Gets the worst hold slack of each endpoint (after {@link #updateHold()}).
     * @return The slacks by endpoint index, +infinity if none.
     */
    public float[] getEndpointHoldSlacks() {
        refreshHold();
        float[] out = new float[numEnds];
        for (int k = 0; k < numEnds; k++) {
            int p = endPin[k];
            float worst = POS_INF;
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                int es = epBase[k] + (i - tagStart[p]);
                worst = Math.min(worst, slotHoldSlack(es));
            }
            out[k] = worst;
        }
        return out;
    }

    /** Summary of a group of checks (launch clock to capture clock) */
    public static final class GroupSummary {
        final int launchClock;
        final int captureClock;
        float requirement;
        float wns = POS_INF;
        float tns;
        int failing;
        int endpoints;
        float whs = POS_INF;
        float ths;
        int holdFailing;
        int holdEndpoints;

        GroupSummary(int launchClock, int captureClock) {
            this.launchClock = launchClock;
            this.captureClock = captureClock;
        }

        /** @return The id of the launch clock. */
        public int getLaunchClock() {
            return launchClock;
        }

        /** @return The id of the capture clock. */
        public int getCaptureClock() {
            return captureClock;
        }

        /** @return The requirement of the group: its smallest setup relation. */
        public float getRequirement() {
            return requirement;
        }

        /** @return The worst setup slack, +infinity if none. */
        public float getWNS() {
            return wns;
        }

        /** @return The total negative setup slack (0 or less). */
        public float getTNS() {
            return tns;
        }

        /** @return The number of endpoints with a negative setup slack. */
        public int getNumFailing() {
            return failing;
        }

        /** @return The number of endpoints with a setup check in the group. */
        public int getNumEndpoints() {
            return endpoints;
        }

        /** @return The worst hold slack, +infinity if none (or if the hold analysis was not run). */
        public float getWHS() {
            return whs;
        }

        /** @return The total negative hold slack (0 or less). */
        public float getTHS() {
            return ths;
        }

        /** @return The number of endpoints with a negative hold slack. */
        public int getNumHoldFailing() {
            return holdFailing;
        }

        /** @return The number of endpoints with a hold check in the group. */
        public int getNumHoldEndpoints() {
            return holdEndpoints;
        }
    }

    /**
     * Summarizes the setup (and, if run, hold) slacks per group of launch and capture clocks: worst
     * and total negative slack, failing and total endpoints; an endpoint counts once per group, with
     * its worst check in that group. Runs {@link #update()} first.
     * @return The summaries of the groups that have checks.
     */
    public List<GroupSummary> getGroupSummaries() {
        update();
        boolean hold = fastHold.arrMin != null || (slowHold != null && slowHold.arrMin != null);
        if (hold) {
            refreshHold();
        }
        ClockModel cm = b.clockModel;
        List<HoldCorner> corners = holdCorners();
        Map<Integer, GroupSummary> map = new java.util.TreeMap<>();
        float[] worstS = new float[numGroups];
        float[] worstH = new float[numGroups];
        int[] touched = new int[numGroups];
        int stamp = 0;
        int[] stampOf = new int[numGroups];
        for (int k = 0; k < numEnds; k++) {
            stamp++;
            int nt = 0;
            int p = endPin[k];
            int cap = b.captureCpin[p];
            for (int i = tagStart[p]; i < tagStart[p + 1]; i++) {
                int es = epBase[k] + (i - tagStart[p]);
                for (int c = chkStart[es]; c < chkStart[es + 1]; c++) {
                    int gr = chkGroup[c];
                    if (stampOf[gr] != stamp) {
                        stampOf[gr] = stamp;
                        worstS[gr] = POS_INF;
                        worstH[gr] = POS_INF;
                        touched[nt++] = gr;
                    }
                    if (arr[i] != NEG_INF && chkReq[c] != POS_INF) {
                        worstS[gr] = Math.min(worstS[gr], chkReq[c] - arr[i]);
                    }
                    if (hold) {
                        // The hold slack of this check (its group's), over the hold corners
                        for (HoldCorner h : corners) {
                            useHold(h);
                            worstH[gr] = Math.min(worstH[gr], checkHoldSlack(cm, es, i, cap, c));
                        }
                        useHold(fastHold);
                    }
                }
            }
            for (int x = 0; x < nt; x++) {
                int gr = touched[x];
                GroupSummary s = map.computeIfAbsent(gr, key -> new GroupSummary(key / numClocks, key % numClocks));
                s.requirement = groupT[gr];
                if (worstS[gr] != POS_INF) {
                    s.endpoints++;
                    s.wns = Math.min(s.wns, worstS[gr]);
                    if (worstS[gr] < 0) {
                        s.tns += worstS[gr];
                        s.failing++;
                    }
                }
                if (worstH[gr] != POS_INF) {
                    s.holdEndpoints++;
                    s.whs = Math.min(s.whs, worstH[gr]);
                    if (worstH[gr] < 0) {
                        s.ths += worstH[gr];
                        s.holdFailing++;
                    }
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    /**
     * Gets the path of largest arrival time of a tag to a pin, walking back along the in-edges of
     * the same tag that give its arrival time.
     * @param pin The last pin.
     * @param tag The tag.
     * @return The edges of the path from its startpoint.
     */
    int[] getPath(int pin, int tag) {
        List<Integer> edges = new ArrayList<>();
        int p = pin;
        int slot = slotOf(p, tag);
        while (slot >= 0 && g.inStart[p] < g.inStart[p + 1]) {
            int best = -1;
            int bestSlot = -1;
            float bestArr = NEG_INF;
            for (int e = g.inStart[p]; e < g.inStart[p + 1]; e++) {
                int q = g.eFrom[e];
                int j = slotOf(q, tag);
                if (j < 0) continue;
                float a = arr[j] + g.getEdgeDelayMax(e);
                if (a == arr[slot]) {
                    best = e;
                    bestSlot = j;
                    break;
                }
                if (best < 0 || a > bestArr) {
                    best = e;
                    bestSlot = j;
                    bestArr = a;
                }
            }
            if (best < 0) break;
            edges.add(best);
            p = g.eFrom[best];
            slot = bestSlot;
        }
        int[] path = new int[edges.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = edges.get(path.length - 1 - i);
        }
        return path;
    }

    /**
     * Gets the data path of a check: that of its launch candidate (the arg-max arrival, or the alternate
     * launch, see {@link Check#alternateLaunch}) for setup checks.
     * @param check A check.
     * @return The edges of the path from its startpoint.
     */
    public int[] getPath(Check check) {
        if (!check.setup || !check.alternateLaunch) {
            return getPath(check.endpoint, check.tag);
        }
        // Back along the edges reproducing the alternate arrival from the same launch clock pin
        List<Integer> edges = new ArrayList<>();
        int p = check.endpoint;
        int slot = slotOf(p, check.tag);
        float target = check.arrival;
        int lp = check.launchClockPin;
        while (slot >= 0 && g.inStart[p] < g.inStart[p + 1]) {
            int best = -1;
            int bestSlot = -1;
            float bestArr = NEG_INF;
            for (int e = g.inStart[p]; e < g.inStart[p + 1]; e++) {
                int j = slotOf(g.eFrom[e], check.tag);
                if (j < 0) continue;
                float d = g.getEdgeDelayMax(e);
                for (int cand = 0; cand < 2; cand++) {
                    if ((cand == 0 ? lcp[j] : lcpAlt[j]) != lp) continue;
                    float a = (cand == 0 ? arr[j] : arrAlt[j]) + d;
                    if (best < 0 || Math.abs(a - target) < Math.abs(bestArr - target)) {
                        best = e;
                        bestSlot = j;
                        bestArr = a;
                    }
                }
            }
            if (best < 0) break;
            edges.add(best);
            target = bestArr - g.getEdgeDelayMax(best);
            p = g.eFrom[best];
            slot = bestSlot;
        }
        int[] path = new int[edges.size()];
        for (int i = 0; i < path.length; i++) {
            path[i] = edges.get(path.length - 1 - i);
        }
        return path;
    }

    private int slotOf(int pin, int tag) {
        int i = Arrays.binarySearch(tagId, tagStart[pin], tagStart[pin + 1], tag);
        return i >= 0 ? i : -1;
    }

    // ------------------------------------------------------------------------------------------
    // Parallel ranges
    // ------------------------------------------------------------------------------------------

    @FunctionalInterface
    private interface Kernel {
        void run(int from, int to);
    }

    private int chunkSize(int n) {
        int chunks = Math.max(1, Math.min(n / MIN_CHUNK, pool.getParallelism() * 4));
        return (n + chunks - 1) / chunks;
    }

    private void runRange(Kernel k, int from, int to) {
        int n = to - from;
        if (parallel && n >= 2 * MIN_CHUNK) {
            pool.invoke(new RangeTask(k, from, to, chunkSize(n)));
        } else {
            k.run(from, to);
        }
    }

    private static final class RangeTask extends RecursiveAction {
        private static final long serialVersionUID = 1L;
        private final transient Kernel k;
        private final int from;
        private final int to;
        private final int chunk;

        RangeTask(Kernel k, int from, int to, int chunk) {
            this.k = k;
            this.from = from;
            this.to = to;
            this.chunk = chunk;
        }

        @Override
        protected void compute() {
            if (to - from <= chunk) {
                k.run(from, to);
                return;
            }
            int numChunks = (to - from + chunk - 1) / chunk;
            int mid = from + (numChunks / 2) * chunk;
            invokeAll(new RangeTask(k, from, mid, chunk), new RangeTask(k, mid, to, chunk));
        }
    }
}
