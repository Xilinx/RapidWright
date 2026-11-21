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

package com.xilinx.rapidwright.timing.clock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;

/**
 * Finds the design objects matching the name patterns of XDC object queries in a netlist, the way
 * Vivado's get_cells, get_pins, get_ports and get_nets do (simplified):
 * <ul>
 * <li>Patterns use the '*' and '?' wildcards only; brackets are literal (bus indices). With -regexp
 * (get_cells, get_pins), a pattern is a regular expression matching the whole name relative to the
 * current instance (with -hierarchical, of the objects at any level; else of those of the current
 * instance's level).</li>
 * <li>Without -hierarchical, a pattern is a path relative to the current instance, each '*' only
 * matching within one level of hierarchy.</li>
 * <li>With -hierarchical, a pattern is compared to the local names of the cells (or nets) at every
 * level of hierarchy below the current instance, '*' and '?' matching any character: as in Vivado,
 * a pattern containing '/' only matches local names that contain '/' (from a flattened
 * hierarchy), not a path across levels. With -regexp -hierarchical, a regular expression matches
 * the whole names relative to the current instance.</li>
 * </ul>
 * Without a netlist, queries return objects by name only, without expanding wildcards.
 */
final class XdcObjectFinder {

    private final EDIFNetlist netlist;
    private final EDIFHierCellInst top;
    /** The cell instances of the netlist, built on first use */
    private Index index;
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    XdcObjectFinder(EDIFNetlist netlist) {
        this.netlist = netlist;
        this.top = netlist == null ? null : netlist.getTopHierCellInst();
    }

    /** Scans of at least this many items run in parallel */
    private static final int PARALLEL_MIN = 1 << 14;

    /** The indices i in [from, to) where a predicate holds, in increasing order (in parallel for long ranges). */
    static int[] filter(int from, int to, IntPredicate p) {
        IntStream s = IntStream.range(from, to);
        return (to - from >= PARALLEL_MIN ? s.parallel() : s).filter(p).toArray();
    }

    /**
     * All the cell instances of the netlist below the top (leaf and hierarchical), each with its full name, numbered
     * in the order of a by-local-name listing: the local names in the order in which a depth-first traversal (a stack,
     * the children of an instance in the order of their cell's instances) first meets them, the instances of each
     * name in the order met. The instances of one local name have consecutive numbers. Queries that scan cells go
     * over these arrays (in parallel) instead of the netlist, and list their results in this order.
     */
    static final class Index {
        /** The instances, by number */
        final EDIFHierCellInst[] cells;
        /** Their full hierarchical names */
        final String[] names;
        /** Distinct local names, in order */
        final String[] localNames;
        /** The instances of local name k are numbered keyStart[k] to keyStart[k + 1] - 1 */
        final int[] keyStart;
        /** Local name to its position in localNames */
        final Map<String, Integer> keyOf;
        /** Instance numbers sorted by full name, built on first use */
        private volatile int[] byName;

        /** The children of an instance and, per child that has children, the block of its own children */
        private static final class Block {
            final EDIFHierCellInst[] kids;
            final String[] names;
            final Block[] sub;

            Block(int n) {
                kids = new EDIFHierCellInst[n];
                names = new String[n];
                sub = new Block[n];
            }
        }

        /** Builds the blocks below an instance, in parallel over its hierarchical children */
        private static final class BlockTask extends java.util.concurrent.RecursiveTask<Block> {
            private static final long serialVersionUID = 1L;
            private final EDIFHierCellInst inst;
            private final String name;

            BlockTask(EDIFHierCellInst inst, String name) {
                this.inst = inst;
                this.name = name;
            }

            @Override
            protected Block compute() {
                EDIFCell type = inst.getCellType();
                if (type == null || type.isLeafCellOrBlackBox()) {
                    return null;
                }
                java.util.Collection<EDIFCellInst> children = type.getCellInsts();
                Block b = new Block(children.size());
                List<BlockTask> tasks = new ArrayList<>();
                List<Integer> at = new ArrayList<>();
                int j = 0;
                for (EDIFCellInst child : children) {
                    EDIFHierCellInst c = inst.getChild(child);
                    String full = name.isEmpty() ? child.getName() : name + "/" + child.getName();
                    b.kids[j] = c;
                    b.names[j] = full;
                    EDIFCell ct = child.getCellType();
                    if (ct != null && !ct.isLeafCellOrBlackBox()) {
                        BlockTask t = new BlockTask(c, full);
                        t.fork();
                        tasks.add(t);
                        at.add(j);
                    }
                    j++;
                }
                for (int t = tasks.size() - 1; t >= 0; t--) {
                    b.sub[at.get(t)] = tasks.get(t).join();
                }
                return b;
            }
        }

        // Numbering by traversal (the order a depth-first traversal with a stack meets the instances: the children of
        // the top, then for each of them in reverse order its children and, recursively, theirs)
        private int next;
        private EDIFHierCellInst[] cellOfId;
        private String[] nameOfId;

        private void flatten(Block b) {
            for (int j = 0; j < b.kids.length; j++) {
                int id = next++;
                cellOfId[id] = b.kids[j];
                nameOfId[id] = b.names[j];
            }
            for (int j = b.kids.length - 1; j >= 0; j--) {
                if (b.sub[j] != null) {
                    flatten(b.sub[j]);
                }
            }
        }

        private static int count(Block b) {
            if (b == null) return 0;
            int n = b.kids.length;
            for (Block s : b.sub) n += count(s);
            return n;
        }

        Index(EDIFHierCellInst top) {
            // In the pool of the caller (the common pool unless called from a pool's task)
            Block root = new BlockTask(top, "").invoke();
            int n = count(root);
            cellOfId = new EDIFHierCellInst[n];
            nameOfId = new String[n];
            next = 0;
            if (root != null) flatten(root);
            // Local names numbered in the order of their first instance
            EDIFHierCellInst[] cl = cellOfId;
            Map<String, Integer> first = new ConcurrentHashMap<>();
            IntStream.range(0, n).parallel().forEach(id -> first.merge(cl[id].getInst().getName(), id, Math::min));
            String[] keys = first.keySet().toArray(new String[0]);
            sort(keys, (x, y) -> Integer.compare(first.get(x), first.get(y)));
            Map<String, Integer> key = new HashMap<>(keys.length * 2);
            for (int k = 0; k < keys.length; k++) key.put(keys[k], k);
            int nk = keys.length;
            int[] keyOfId = new int[n];
            IntStream.range(0, n).parallel().forEach(id -> keyOfId[id] = key.get(cl[id].getInst().getName()));
            // Number by (first occurrence of the local name, id)
            keyStart = new int[nk + 1];
            for (int id = 0; id < n; id++) keyStart[keyOfId[id] + 1]++;
            for (int k = 0; k < nk; k++) keyStart[k + 1] += keyStart[k];
            int[] fill = Arrays.copyOf(keyStart, nk);
            int[] rankOfId = new int[n];
            for (int id = 0; id < n; id++) rankOfId[id] = fill[keyOfId[id]]++;
            cells = new EDIFHierCellInst[n];
            names = new String[n];
            IntStream.range(0, n).parallel().forEach(id -> {
                cells[rankOfId[id]] = cl[id];
                names[rankOfId[id]] = nameOfId[id];
            });
            localNames = keys;
            keyOf = key;
            cellOfId = null;
            nameOfId = null;
        }

        int size() {
            return cells.length;
        }

        int[] byName() {
            int[] s = byName;
            if (s == null) {
                Integer[] boxed = new Integer[names.length];
                for (int i = 0; i < boxed.length; i++) boxed[i] = i;
                sort(boxed, (x, y) -> names[x].compareTo(names[y]));
                s = new int[boxed.length];
                for (int i = 0; i < s.length; i++) s[i] = boxed[i];
                byName = s;
            }
            return s;
        }

        /**
         * The first position from lo on in byName whose name does not start with a prefix (lo being the first whose
         * name is not less than the prefix, the names that start with it are consecutive from lo on)
         */
        int prefixEnd(int[] sorted, int lo, String prefix) {
            int hi = sorted.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (names[sorted[mid]].startsWith(prefix)) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }

        /** The first position in byName whose name is not less than a key */
        int lowerBound(int[] sorted, String key) {
            int lo = 0;
            int hi = sorted.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (names[sorted[mid]].compareTo(key) < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid;
                }
            }
            return lo;
        }
    }

    /**
     * Sorts an array, in parallel unless called from a task of a pool of one thread (where Arrays.parallelSort can
     * spin without making progress, observed on Java 17).
     */
    static <T> void sort(T[] a, java.util.Comparator<? super T> c) {
        java.util.concurrent.ForkJoinPool pool = java.util.concurrent.ForkJoinTask.getPool();
        if (pool != null && pool.getParallelism() < 2) {
            Arrays.sort(a, c);
        } else {
            Arrays.parallelSort(a, c);
        }
    }

    private Index index() {
        if (index == null) {
            index = new Index(top);
        }
        return index;
    }

    /** The literal text of a glob before its first wildcard */
    static String literalPrefix(String glob) {
        int i = 0;
        while (i < glob.length() && glob.charAt(i) != '*' && glob.charAt(i) != '?') i++;
        return glob.substring(0, i);
    }

    /** The longest run of literal text of a glob */
    static String longestLiteral(String glob) {
        String best = "";
        for (String part : glob.split("[*?]")) {
            if (part.length() > best.length()) best = part;
        }
        return best;
    }

    /**
     * The numbers (in increasing order) of the cell instances whose full name matches a glob in which '*' and '?'
     * match across hierarchy levels and that are below an instance ("" for any).
     */
    int[] cellsByFullName(String glob, String under) {
        Index ix = index();
        Pattern p = globToRegex(glob, true);
        String pre = literalPrefix(glob);
        String lit = longestLiteral(glob);
        String up = prefix(under);
        if (pre.length() < up.length() ? !up.startsWith(pre) : !pre.startsWith(up)) {
            return new int[0];
        }
        String need = pre.length() >= up.length() ? pre : up;
        if (!need.isEmpty()) {
            // A name range of the sorted names
            int[] sorted = ix.byName();
            int lo = ix.lowerBound(sorted, need);
            int hi = ix.prefixEnd(sorted, lo, need);
            String[] names = ix.names;
            int[] r = filter(lo, hi, i -> p.matcher(names[sorted[i]]).matches());
            for (int i = 0; i < r.length; i++) r[i] = sorted[r[i]];
            Arrays.sort(r);
            return r;
        }
        String[] names = ix.names;
        return filter(0, names.length, i -> names[i].contains(lit) && p.matcher(names[i]).matches());
    }

    /**
     * Finds the pins whose full name matches a glob in which '*' and '?' match across hierarchy levels, as a
     * {@code NAME =~ <glob>} filter does: the pins of all cells below the current instance with -hierarchical, else
     * those of its children.
     * @param glob The glob.
     * @param hier Whether -hierarchical was given.
     * @param currentInstance The current instance ("" for the top).
     * @return The matching pins.
     */
    List<XdcObject> findPinsByFullName(String glob, boolean hier, String currentInstance) {
        List<XdcObject> out = new ArrayList<>();
        if (netlist == null) {
            return out;
        }
        int slash = glob.lastIndexOf('/');
        String pinGlob = slash < 0 ? null : glob.substring(slash + 1);
        // With a pin name without wildcards, a pin matches if its name is that name and its cell matches the rest
        boolean split = pinGlob != null && !hasWildcard(pinGlob);
        Pattern fullRegex = split ? null : globToRegex(glob, true);
        List<EDIFHierCellInst> cells = new ArrayList<>();
        List<String> cellNames = new ArrayList<>();
        if (hier) {
            // The cells below the current instance in the index (in its order); with a literal pin name, those whose
            // full name matches the rest of the glob; else those whose name is compatible with the glob's literal
            // prefix
            Index ix = index();
            int[] cand;
            if (split) {
                cand = cellsByFullName(glob.substring(0, slash), currentInstance);
            } else {
                String pre = prefix(currentInstance);
                String lit = literalPrefix(glob);
                cand = filter(0, ix.size(), r -> ix.names[r].startsWith(pre)
                        && (ix.names[r].startsWith(lit) || lit.startsWith(ix.names[r] + "/")));
            }
            for (int r : cand) {
                cells.add(ix.cells[r]);
                cellNames.add(ix.names[r]);
            }
        } else {
            for (EDIFHierCellInst c : childrenOf(currentInstance)) {
                String cellName = c.getFullHierarchicalInstName();
                if (relativeName(cellName, currentInstance) == null || split
                        && !globToRegex(glob.substring(0, slash), true).matcher(cellName).matches()) {
                    continue;
                }
                cells.add(c);
                cellNames.add(cellName);
            }
        }
        // The pins of each candidate cell (in parallel over many cells), in the order of the cells
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<XdcObject>[] parts = new List[cells.size()];
        IntStream r = IntStream.range(0, cells.size());
        (cells.size() >= PARALLEL_MIN / 16 ? r.parallel() : r).forEach(i -> {
            EDIFHierCellInst c = cells.get(i);
            String cellName = cellNames.get(i);
            List<XdcObject> l = null;
            for (EDIFPortInst pi : c.getInst().getPortInsts()) {
                if (split ? pinGlob.equals(pi.getName()) : fullRegex.matcher(cellName + "/" + pi.getName()).matches()) {
                    if (l == null) l = new ArrayList<>(1);
                    l.add(XdcObject.ofPin(cellName + "/" + pi.getName(), new EDIFHierPortInst(c.getParent(), pi)));
                }
            }
            parts[i] = l;
        });
        for (List<XdcObject> l : parts) {
            if (l != null) out.addAll(l);
        }
        return out;
    }

    EDIFNetlist getNetlist() {
        return netlist;
    }

    static boolean hasWildcard(String pattern) {
        return pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
    }

    /**
     * Converts a glob pattern to a regular expression (cached).
     * @param glob The pattern, with '*' and '?' wildcards.
     * @param crossLevels Whether '*' matches '/' too.
     */
    Pattern globToRegex(String glob, boolean crossLevels) {
        String key = (crossLevels ? "1" : "0") + glob;
        Pattern p = patternCache.get(key);
        if (p == null) {
            p = compileGlob(glob, crossLevels, 0);
            patternCache.put(key, p);
        }
        return p;
    }

    /**
     * Compiles a glob pattern to a regular expression.
     * @param glob The pattern, with '*' and '?' wildcards (everything else is literal).
     * @param crossLevels Whether '*' and '?' match '/' too.
     * @param flags Flags of {@link Pattern#compile(String, int)}.
     */
    static Pattern compileGlob(String glob, boolean crossLevels, int flags) {
        StringBuilder sb = new StringBuilder();
        StringBuilder lit = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                if (lit.length() > 0) {
                    sb.append(Pattern.quote(lit.toString()));
                    lit.setLength(0);
                }
                if (c == '*') {
                    sb.append(crossLevels ? ".*" : "[^/]*");
                } else {
                    sb.append(crossLevels ? "." : "[^/]");
                }
            } else {
                lit.append(c);
            }
        }
        if (lit.length() > 0) {
            sb.append(Pattern.quote(lit.toString()));
        }
        return Pattern.compile(sb.toString(), Pattern.DOTALL | flags);
    }

    boolean globMatches(String glob, String name, boolean crossLevels) {
        if (!hasWildcard(glob)) {
            return glob.equals(name);
        }
        return globToRegex(glob, crossLevels).matcher(name).matches();
    }

    /** Gets the instance of the current instance path, null if it does not exist. */
    EDIFHierCellInst getInstance(String path) {
        if (path == null || path.isEmpty()) {
            return top;
        }
        return netlist.getHierCellInstFromName(path);
    }

    private static String prefix(String currentInstance) {
        return currentInstance == null || currentInstance.isEmpty() ? "" : currentInstance + "/";
    }

    private static String relativeName(String fullName, String currentInstance) {
        String p = prefix(currentInstance);
        if (p.isEmpty()) {
            return fullName;
        }
        return fullName.startsWith(p) ? fullName.substring(p.length()) : null;
    }

    // ------------------------------------------------------------------------------------------
    // Cells
    // ------------------------------------------------------------------------------------------

    /**
     * Finds the cell instances matching a pattern.
     * @param pattern The pattern.
     * @param hier Whether -hierarchical was given.
     * @param currentInstance The current instance ("" for the top).
     * @return The matching cells, in a deterministic order.
     */
    List<EDIFHierCellInst> findCells(String pattern, boolean hier, String currentInstance) {
        List<EDIFHierCellInst> result = new ArrayList<>();
        if (netlist == null) {
            return result;
        }
        if (!hier) {
            if (!hasWildcard(pattern)) {
                EDIFHierCellInst inst = netlist.getHierCellInstFromName(prefix(currentInstance) + pattern);
                if (inst != null) {
                    result.add(inst);
                }
            } else {
                EDIFHierCellInst start = getInstance(currentInstance);
                if (start != null) {
                    walk(start, pattern.split("/"), 0, result);
                }
            }
            return result;
        }
        Index ix = index();
        for (int r : findCellsHier(pattern, currentInstance)) {
            result.add(ix.cells[r]);
        }
        return result;
    }

    /** As {@link #findCells}, as objects. */
    List<XdcObject> findCellObjects(String pattern, boolean hier, String currentInstance) {
        List<XdcObject> result = new ArrayList<>();
        if (netlist != null && hier) {
            Index ix = index();
            for (int r : findCellsHier(pattern, currentInstance)) {
                result.add(XdcObject.ofCell(ix.names[r], ix.cells[r]));
            }
            return result;
        }
        for (EDIFHierCellInst c : findCells(pattern, hier, currentInstance)) {
            result.add(XdcObject.ofCell(c));
        }
        return result;
    }

    /** As {@link #findCellObjects} for the cells given by number. */
    List<XdcObject> cellObjects(int[] cells) {
        Index ix = index();
        List<XdcObject> result = new ArrayList<>(cells.length);
        for (int r : cells) {
            result.add(XdcObject.ofCell(ix.names[r], ix.cells[r]));
        }
        return result;
    }

    /** The cells below an instance ("" for all), by number. */
    int[] allCells(String currentInstance) {
        Index ix = index();
        String p = prefix(currentInstance);
        if (p.isEmpty()) {
            return filter(0, ix.size(), r -> true);
        }
        return filter(0, ix.size(), r -> ix.names[r].startsWith(p));
    }

    /**
     * A -hierarchical cell query: the cells below the current instance whose local name matches the pattern.
     * @return The numbers of the matching cells, in the order of the by-local-name listing (see {@link Index}).
     */
    int[] findCellsHier(String pattern, String currentInstance) {
        Index ix = index();
        String[] localNames = ix.localNames;
        int[] keys;
        if (!hasWildcard(pattern)) {
            Integer k = ix.keyOf.get(pattern);
            keys = k == null ? new int[0] : new int[]{k};
        } else if (pattern.equals("*")) {
            keys = filter(0, localNames.length, k -> true);
        } else {
            Pattern regex = globToRegex(pattern, true);
            String lit = longestLiteral(pattern);
            keys = filter(0, localNames.length, k -> localNames[k].contains(lit)
                    && regex.matcher(localNames[k]).matches());
        }
        int n = 0;
        for (int k : keys) n += ix.keyStart[k + 1] - ix.keyStart[k];
        int[] cand = new int[n];
        n = 0;
        for (int k : keys) {
            for (int r = ix.keyStart[k]; r < ix.keyStart[k + 1]; r++) cand[n++] = r;
        }
        String p = prefix(currentInstance);
        if (p.isEmpty()) return cand;
        String[] names = ix.names;
        int[] sel = filter(0, cand.length, i -> names[cand[i]].startsWith(p));
        for (int i = 0; i < sel.length; i++) sel[i] = cand[sel[i]];
        return sel;
    }

    /**
     * Finds the cell instances whose names relative to the current instance a regular expression matches.
     * @param regex The regular expression (Tcl/Java syntax), matching whole names.
     * @param hier Whether -hierarchical was given (else only the current instance's children).
     * @param nocase Whether -nocase was given.
     * @param currentInstance The current instance ("" for the top).
     * @return The matching cells.
     */
    List<EDIFHierCellInst> findCellsRegexp(String regex, boolean hier, boolean nocase, String currentInstance) {
        List<EDIFHierCellInst> result = new ArrayList<>();
        Pattern p = compileRegexp(regex, nocase);
        if (p == null || netlist == null) {
            return result;
        }
        if (hier) {
            Index ix = index();
            String pre = prefix(currentInstance);
            for (int r : filter(0, ix.size(), r -> ix.names[r].startsWith(pre)
                    && p.matcher(ix.names[r].substring(pre.length())).matches())) {
                result.add(ix.cells[r]);
            }
            return result;
        }
        for (EDIFHierCellInst c : childrenOf(currentInstance)) {
            String rel = relativeName(c.getFullHierarchicalInstName(), currentInstance);
            if (rel != null && p.matcher(rel).matches()) {
                result.add(c);
            }
        }
        return result;
    }

    /**
     * Finds the pins whose names (cell name relative to the current instance, '/', pin name) a regular
     * expression matches.
     */
    List<XdcObject> findPinsRegexp(String regex, boolean hier, boolean nocase, String currentInstance) {
        List<XdcObject> result = new ArrayList<>();
        Pattern p = compileRegexp(regex, nocase);
        if (p == null || netlist == null) {
            return result;
        }
        if (hier) {
            // The matching pins of each cell below the current instance (in parallel over many cells), in the
            // order of the cells
            Index ix = index();
            String pre = prefix(currentInstance);
            @SuppressWarnings({"unchecked", "rawtypes"})
            List<XdcObject>[] parts = new List[ix.size()];
            IntStream r = IntStream.range(0, ix.size());
            (ix.size() >= PARALLEL_MIN ? r.parallel() : r).forEach(i -> {
                if (!ix.names[i].startsWith(pre)) return;
                String rel = ix.names[i].substring(pre.length());
                for (EDIFPortInst pi : ix.cells[i].getInst().getPortInsts()) {
                    if (p.matcher(rel + "/" + pi.getName()).matches()) {
                        if (parts[i] == null) parts[i] = new ArrayList<>(1);
                        parts[i].add(XdcObject.ofPin(new EDIFHierPortInst(ix.cells[i].getParent(), pi)));
                    }
                }
            });
            for (List<XdcObject> l : parts) {
                if (l != null) result.addAll(l);
            }
            return result;
        }
        for (EDIFHierCellInst c : childrenOf(currentInstance)) {
            String rel = relativeName(c.getFullHierarchicalInstName(), currentInstance);
            if (rel == null) continue;
            for (EDIFPortInst pi : c.getInst().getPortInsts()) {
                if (p.matcher(rel + "/" + pi.getName()).matches()) {
                    result.add(XdcObject.ofPin(new EDIFHierPortInst(c.getParent(), pi)));
                }
            }
        }
        return result;
    }

    /** A -regexp pattern, null if not a valid regular expression (no match) */
    private static Pattern compileRegexp(String regex, boolean nocase) {
        try {
            return Pattern.compile(regex, nocase ? Pattern.CASE_INSENSITIVE : 0);
        } catch (java.util.regex.PatternSyntaxException e) {
            return null;
        }
    }

    /** The children of the current instance */
    private List<EDIFHierCellInst> childrenOf(String currentInstance) {
        List<EDIFHierCellInst> cells = new ArrayList<>();
        if (netlist == null) {
            return cells;
        }
        EDIFHierCellInst start = getInstance(currentInstance);
        if (start != null && start.getCellType() != null && !start.getCellType().isLeafCellOrBlackBox()) {
            for (EDIFCellInst child : start.getCellType().getCellInsts()) {
                cells.add(start.getChild(child));
            }
        }
        return cells;
    }

    /** The children of a cell type, for level-by-level matching */
    private static final class Children {
        final EDIFCellInst[] insts;
        /** The number of hierarchy levels of each child's name (names may contain '/') */
        final int[] levels;
        /** The children whose names have one level, by name */
        final Map<String, Integer> single = new HashMap<>();
        /** The children whose names have several levels, in order */
        final int[] multi;

        Children(EDIFCell type) {
            insts = type.getCellInsts().toArray(new EDIFCellInst[0]);
            levels = new int[insts.length];
            int nm = 0;
            for (int c = 0; c < insts.length; c++) {
                String name = insts[c].getName();
                int k = 1;
                for (int j = name.indexOf('/'); j >= 0; j = name.indexOf('/', j + 1)) {
                    k++;
                }
                levels[c] = k;
                if (k == 1) {
                    single.put(name, c);
                } else {
                    nm++;
                }
            }
            multi = new int[nm];
            nm = 0;
            for (int c = 0; c < insts.length; c++) {
                if (levels[c] > 1) multi[nm++] = c;
            }
        }
    }

    private final Map<EDIFCell, Children> childrenOfType = new java.util.IdentityHashMap<>();

    /** Matches the levels of a pattern from level i down the hierarchy under an instance. */
    private void walk(EDIFHierCellInst inst, String[] levels, int i, List<EDIFHierCellInst> result) {
        EDIFCell type = inst.getCellType();
        if (type == null || type.isLeafCellOrBlackBox()) {
            return;
        }
        Children ch = childrenOfType.computeIfAbsent(type, Children::new);
        String level = levels[i];
        int[] cand;
        Pattern levelRegex = null;
        if (!hasWildcard(level)) {
            // The child of that name, and the children with multi-level names, in the order of the cell's instances
            Integer c = ch.single.get(level);
            if (c == null) {
                cand = ch.multi;
            } else {
                cand = Arrays.copyOf(ch.multi, ch.multi.length + 1);
                cand[ch.multi.length] = c;
                Arrays.sort(cand);
            }
        } else {
            cand = null;
            levelRegex = globToRegex(level, false);
        }
        int n = cand == null ? ch.insts.length : cand.length;
        for (int x = 0; x < n; x++) {
            int c = cand == null ? x : cand[x];
            EDIFCellInst child = ch.insts[c];
            String name = child.getName();
            int k = ch.levels[c];
            if (i + k > levels.length) continue;
            if (k == 1) {
                if (levelRegex != null ? !levelRegex.matcher(name).matches() : !level.equals(name)) continue;
            } else {
                String pat = String.join("/", Arrays.copyOfRange(levels, i, i + k));
                if (!globMatches(pat, name, false) && !globMatches(pat, name, true)) continue;
            }
            EDIFHierCellInst childInst = inst.getChild(child);
            if (i + k == levels.length) {
                result.add(childInst);
            } else {
                walk(childInst, levels, i + k, result);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Pins
    // ------------------------------------------------------------------------------------------

    /**
     * Finds the pins matching a pattern "cellPattern/pinPattern".
     */
    List<XdcObject> findPins(String pattern, boolean hier, String currentInstance) {
        List<XdcObject> result = new ArrayList<>();
        int slash = pattern.lastIndexOf('/');
        if (netlist == null) {
            result.add(XdcObject.ofPin(prefix(currentInstance) + pattern, null));
            return result;
        }
        if (slash < 0) {
            if (!hier) {
                return result;
            }
            // e.g. get_pins -hier *: the pins of all cells
            slash = -1;
        }
        String cellPattern = slash < 0 ? "*" : pattern.substring(0, slash);
        String pinPattern = pattern.substring(slash + 1);
        if (!hier && !hasWildcard(pattern)) {
            EDIFHierPortInst p = netlist.getHierPortInstFromName(prefix(currentInstance) + pattern);
            if (p != null) {
                result.add(XdcObject.ofPin(p));
            } else if (slash > 0) {
                // A bus name stands for the bus's bits (as Vivado's get_pins), else a pin named as in Vivado
                EDIFHierCellInst cell = netlist.getHierCellInstFromName(prefix(currentInstance) + cellPattern);
                if (cell != null && !cell.isTopLevelInst()) {
                    addBusPinsOfCell(cell, pinPattern, result);
                    String alias = result.isEmpty() ? getPinAlias(cell.getCellType(), pinPattern) : null;
                    if (alias != null) addPinsOfCell(cell, alias, result);
                }
            }
            return result;
        }
        for (EDIFHierCellInst cell : findCells(cellPattern, hier, currentInstance)) {
            addPinsOfCell(cell, pinPattern, result);
        }
        return result;
    }

    /** Adds the connected bit pins of the bus ports of a cell whose bus name is a literal name. */
    private void addBusPinsOfCell(EDIFHierCellInst cell, String busName, List<XdcObject> result) {
        for (EDIFPortInst pi : cell.getInst().getPortInsts()) {
            EDIFPort port = pi.getPort();
            if (port != null && port.isBus() && port.getBusName().equals(busName)) {
                result.add(XdcObject.ofPin(new EDIFHierPortInst(cell.getParent(), pi)));
            }
        }
    }

    /** Adds the pins of a cell whose names (or, for the bits of a bus, bus names) match a pattern (null for all). */
    void addPinsOfCell(EDIFHierCellInst cell, String pinPattern, List<XdcObject> result) {
        int n = result.size();
        for (EDIFPortInst pi : cell.getInst().getPortInsts()) {
            if (pinPattern == null || globMatches(pinPattern, pi.getName(), true)
                    || pi.getPort() != null && pi.getPort().isBus() && globMatches(pinPattern, pi.getPort().getBusName(), true)) {
                result.add(XdcObject.ofPin(new EDIFHierPortInst(cell.getParent(), pi)));
            }
        }
        String alias = pinPattern == null || result.size() > n ? null
                : getPinAlias(cell.getCellType(), pinPattern);
        if (alias != null) {
            addPinsOfCell(cell, alias, result);
        }
    }

    /** The processing system primitives, whose pins {@link #getPinAlias(EDIFCell, String)} renames */
    private static final Pattern PROCESSING_SYSTEM = Pattern.compile("PS\\d+");

    /**
     * Gets the netlist name of a processing system pin that Vivado's constraints name differently (see
     * {@link #getPinAlias(String)}): for a pin name that is literal before its bus index, on a processing system
     * primitive (e.g. PS9, PS11).
     * @param type The cell type of the pin's cell.
     * @param pinName The pin name of the constraints.
     * @return The netlist name, or null if none.
     */
    static String getPinAlias(EDIFCell type, String pinName) {
        int b = pinName.indexOf('[');
        if (type == null || !PROCESSING_SYSTEM.matcher(type.getName()).matches()
                || hasWildcard(b < 0 ? pinName : pinName.substring(0, b))) {
            return null;
        }
        return getPinAlias(pinName);
    }

    /**
     * Gets the netlist name of a hard block pin that Vivado's constraints name differently: Vivado's
     * internal clock pins of the processing systems (e.g. PS11 PMCRCLKCLKINT[0]) are the netlist's
     * pins without "INT" (PMCRCLKCLK[0]).
     * @param pinName The pin name (or pattern) of the constraints.
     * @return The netlist name, or null if none.
     */
    static String getPinAlias(String pinName) {
        int b = pinName.indexOf('[');
        String base = b < 0 ? pinName : pinName.substring(0, b);
        if (base.endsWith("INT") && base.length() > 3) {
            return base.substring(0, base.length() - 3) + (b < 0 ? "" : pinName.substring(b));
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Ports and nets
    // ------------------------------------------------------------------------------------------

    /** Finds the top-level ports (bits of buses, or whole bus names) a glob matches. */
    List<XdcObject> findPorts(String pattern) {
        if (netlist == null) {
            List<XdcObject> result = new ArrayList<>();
            result.add(XdcObject.ofPort(pattern));
            return result;
        }
        return findPorts(name -> globMatches(pattern, name, true));
    }

    /** Finds the top-level ports (bits of buses, or whole bus names) a regular expression matches. */
    List<XdcObject> findPortsRegexp(String regex, boolean nocase) {
        Pattern p = compileRegexp(regex, nocase);
        if (p == null || netlist == null) {
            return new ArrayList<>();
        }
        return findPorts(name -> p.matcher(name).matches());
    }

    /** The top-level ports whose names (bit names, or bus names for all their bits) a predicate accepts */
    private List<XdcObject> findPorts(Predicate<String> matches) {
        List<XdcObject> result = new ArrayList<>();
        for (EDIFPort port : netlist.getTopCell().getPorts()) {
            if (port.isBus()) {
                boolean all = matches.test(port.getBusName());
                for (int i = 0; i < port.getWidth(); i++) {
                    String bit = port.getPortInstNameFromPort(i);
                    if (all || matches.test(bit)) {
                        result.add(XdcObject.ofPort(bit));
                    }
                }
            } else if (matches.test(port.getName())
                    || bitOf(port.getName()) != null && matches.test(bitOf(port.getName()))) {
                result.add(XdcObject.ofPort(port.getName()));
            }
        }
        return result;
    }

    /**
     * The bus name of a scalar port named as a bus bit ("name[3]"), else null. Netlists may hold the bits of a bus
     * as scalar ports (e.g. a one-bit bus "clk_p[0:0]" written as the port "clk_p[0]"); Vivado's get_ports matches
     * the bus name ("clk_p") to its bits.
     */
    static String bitOf(String portName) {
        int n = portName.length();
        if (n < 4 || portName.charAt(n - 1) != ']') return null;
        int b = portName.lastIndexOf('[');
        if (b <= 0 || b == n - 2) return null;
        for (int i = b + 1; i < n - 1; i++) {
            if (!Character.isDigit(portName.charAt(i))) return null;
        }
        return portName.substring(0, b);
    }

    /**
     * The pins of the current instance whose names match a pattern, as get_ports -scoped_to_current_instance gives
     * them: each traversed to the top-level port its net connects to (if traverse and there is one), else the
     * hierarchical pin itself.
     */
    List<XdcObject> findScopedPorts(String pattern, boolean regexp, boolean nocase, String currentInstance,
                                    boolean traverse) {
        List<XdcObject> result = new ArrayList<>();
        EDIFHierCellInst inst = getInstance(currentInstance);
        if (inst == null || inst.isTopLevelInst() || inst.getCellType() == null) {
            return result;
        }
        Pattern re = regexp ? compileRegexp(pattern, nocase) : null;
        if (regexp && re == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (EDIFPort port : inst.getCellType().getPorts()) {
            for (int i = 0; i < port.getWidth(); i++) {
                String bit = port.isBus() ? port.getPortInstNameFromPort(i) : port.getName();
                String bus = port.isBus() ? port.getBusName() : bitOf(port.getName());
                boolean match = regexp ? re.matcher(bit).matches() || bus != null && re.matcher(bus).matches()
                        : globMatches(pattern, bit, true) || bus != null && globMatches(pattern, bus, true);
                if (!match) continue;
                EDIFPortInst pi = inst.getInst().getPortInst(bit);
                if (pi == null) continue;
                EDIFHierPortInst hp = new EDIFHierPortInst(inst.getParent(), pi);
                String top = traverse ? topLevelPortOf(hp.getHierarchicalNet()) : null;
                XdcObject obj = top != null ? XdcObject.ofPort(top) : XdcObject.ofPin(hp);
                if (seen.add(obj.getKind() + ":" + obj.getName())) result.add(obj);
            }
        }
        return result;
    }

    /** The top-level ports on a net (any of its aliases across the hierarchy), in order */
    private List<String> topLevelPortsOf(EDIFHierNet hn) {
        List<String> ports = new ArrayList<>();
        if (hn == null || hn.getNet() == null) return ports;
        for (EDIFHierNet alias : netlist.getNetAliases(hn)) {
            if (!alias.getHierarchicalInst().isTopLevelInst()) continue;
            for (EDIFPortInst pi : alias.getNet().getPortInsts()) {
                if (pi.isTopLevelPort() && !ports.contains(pi.getName())) ports.add(pi.getName());
            }
        }
        return ports;
    }

    /** The top-level port on the net a hierarchical net is part of, or null */
    private String topLevelPortOf(EDIFHierNet hn) {
        List<String> ports = topLevelPortsOf(hn);
        return ports.isEmpty() ? null : ports.get(0);
    }

    /** The top-level ports on the nets of nets or pins (get_ports -of_objects) */
    List<XdcObject> portsOf(List<XdcObject> objects) {
        Set<String> seen = new LinkedHashSet<>();
        List<XdcObject> result = new ArrayList<>();
        for (XdcObject o : objects) {
            EDIFHierNet hn = o.getKind() == XdcObject.Kind.NET ? o.getNet()
                    : o.getKind() == XdcObject.Kind.PIN && o.getPin() != null ? o.getPin().getHierarchicalNet() : null;
            for (String port : topLevelPortsOf(hn)) {
                if (seen.add(port)) result.add(XdcObject.ofPort(port));
            }
        }
        return result;
    }

    /** Collects the selected nets of a parent instance */
    private interface NetSelector {
        void collect(EDIFHierCellInst parent, List<XdcObject> out);
    }

    /** The nets a selector picks in each parent, in the order of the parents (in parallel over many parents). */
    private static List<XdcObject> collectNets(List<EDIFHierCellInst> parents, NetSelector sel) {
        List<XdcObject> result = new ArrayList<>();
        if (parents.size() < 1024) {
            for (EDIFHierCellInst parent : parents) {
                sel.collect(parent, result);
            }
            return result;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        List<XdcObject>[] parts = new List[parents.size()];
        IntStream.range(0, parents.size()).parallel().forEach(i -> {
            List<XdcObject> l = new ArrayList<>(0);
            sel.collect(parents.get(i), l);
            parts[i] = l;
        });
        for (List<XdcObject> l : parts) {
            result.addAll(l);
        }
        return result;
    }

    /** The hierarchical (not leaf) cell instances below an instance, in the order of the by-local-name listing */
    private List<EDIFHierCellInst> hierarchicalCells(String currentInstance) {
        Index ix = index();
        String pre = prefix(currentInstance);
        List<EDIFHierCellInst> out = new ArrayList<>();
        for (int r : filter(0, ix.size(), r -> ix.names[r].startsWith(pre) && ix.cells[r].getCellType() != null
                && !ix.cells[r].getCellType().isLeafCellOrBlackBox())) {
            out.add(ix.cells[r]);
        }
        return out;
    }

    /** Whether a cell instance of the netlist has a property. */
    boolean hasCellProperty(String property) {
        Index ix = index();
        return filter(0, ix.size(), r -> ix.cells[r].getInst().getProperty(property) != null).length > 0;
    }

    /**
     * Finds the nets whose names relative to the current instance a regular expression matches: with
     * -hierarchical at any level, else those of the current instance.
     */
    List<XdcObject> findNetsRegexp(String regex, boolean hier, boolean nocase, String currentInstance) {
        Pattern p = compileRegexp(regex, nocase);
        if (p == null || netlist == null) {
            return new ArrayList<>();
        }
        List<EDIFHierCellInst> parents = new ArrayList<>();
        EDIFHierCellInst start = getInstance(currentInstance);
        if (start != null) {
            parents.add(start);
        }
        if (hier) {
            parents.addAll(hierarchicalCells(currentInstance));
        }
        String pre = prefix(currentInstance);
        return collectNets(parents, (parent, out) -> {
            for (EDIFNet net : parent.getCellType().getNets()) {
                EDIFHierNet hn = new EDIFHierNet(parent, net);
                String name = hn.getHierarchicalNetName();
                if (name.startsWith(pre) && p.matcher(name.substring(pre.length())).matches()) {
                    out.add(XdcObject.ofNet(hn));
                }
            }
        });
    }

    /**
     * Finds the nets matching a pattern: with -hierarchical, the nets of the current instance and of the
     * hierarchical cells below it whose local names match the pattern; else, the nets of the current instance (or,
     * for a pattern with '/', of the cells the part before the last '/' matches) whose names match the last level.
     */
    List<XdcObject> findNets(String pattern, boolean hier, String currentInstance) {
        if (netlist == null) {
            return new ArrayList<>();
        }
        if (!hasWildcard(pattern) && !hier) {
            List<XdcObject> result = new ArrayList<>();
            EDIFHierNet net = netlist.getHierNetFromName(prefix(currentInstance) + pattern);
            if (net != null) {
                result.add(XdcObject.ofNet(net));
            }
            return result;
        }
        int slash = hier ? -1 : pattern.lastIndexOf('/');
        String netPattern = slash < 0 ? pattern : pattern.substring(slash + 1);
        List<EDIFHierCellInst> parents = new ArrayList<>();
        if (slash < 0) {
            EDIFHierCellInst start = getInstance(currentInstance);
            if (start != null) {
                parents.add(start);
            }
            if (hier) {
                parents.addAll(hierarchicalCells(currentInstance));
            }
        } else {
            parents.addAll(findCells(pattern.substring(0, slash), false, currentInstance));
        }
        Pattern local = hasWildcard(netPattern) ? globToRegex(netPattern, true) : null;
        return collectNets(parents, (parent, out) -> {
            if (local == null) {
                EDIFNet net = parent.getCellType().getNet(netPattern);
                if (net != null) {
                    out.add(XdcObject.ofNet(new EDIFHierNet(parent, net)));
                }
                return;
            }
            for (EDIFNet net : parent.getCellType().getNets()) {
                if (local.matcher(net.getName()).matches()) {
                    out.add(XdcObject.ofNet(new EDIFHierNet(parent, net)));
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // -of_objects
    // ------------------------------------------------------------------------------------------

    /** The cells of pins, or the leaf cells on nets. */
    List<XdcObject> cellsOf(List<XdcObject> objects) {
        Set<String> seen = new LinkedHashSet<>();
        List<XdcObject> result = new ArrayList<>();
        for (XdcObject o : objects) {
            List<EDIFHierCellInst> cells = new ArrayList<>();
            if (o.getKind() == XdcObject.Kind.PIN && o.getPin() != null) {
                cells.add(o.getPin().getHierarchicalInst().getChild(o.getPin().getPortInst().getCellInst()));
            } else if (o.getKind() == XdcObject.Kind.NET && o.getNet() != null) {
                for (EDIFHierPortInst p : o.getNet().getLeafHierPortInsts(true, true)) {
                    cells.add(p.getFullHierarchicalInst());
                }
            }
            for (EDIFHierCellInst c : cells) {
                if (c != null && seen.add(c.getFullHierarchicalInstName())) {
                    result.add(XdcObject.ofCell(c));
                }
            }
        }
        return result;
    }

    /**
     * The pins of cells, or of nets: with leaf (get_pins -leaf), the leaf pins of the whole net across the
     * hierarchy; else, as Vivado, the pins on the net itself (of the cells at its level, leaf or hierarchical).
     */
    List<XdcObject> pinsOf(List<XdcObject> objects, boolean leaf) {
        List<XdcObject> result = new ArrayList<>();
        for (XdcObject o : objects) {
            if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                addPinsOfCell(o.getCell(), null, result);
            } else if (o.getKind() == XdcObject.Kind.NET && o.getNet() != null) {
                if (leaf) {
                    for (EDIFHierPortInst p : o.getNet().getLeafHierPortInsts(true, true)) {
                        result.add(XdcObject.ofPin(p));
                    }
                    continue;
                }
                EDIFHierCellInst parent = o.getNet().getHierarchicalInst();
                for (EDIFPortInst pi : o.getNet().getNet().getPortInsts()) {
                    if (pi.getCellInst() != null) {
                        result.add(XdcObject.ofPin(new EDIFHierPortInst(parent, pi)));
                    }
                }
            }
        }
        return result;
    }

    /** The nets of pins, or the nets connected to the pins of cells. */
    List<XdcObject> netsOf(List<XdcObject> objects) {
        Set<String> seen = new LinkedHashSet<>();
        List<XdcObject> result = new ArrayList<>();
        for (XdcObject o : objects) {
            List<EDIFHierPortInst> pins = new ArrayList<>();
            if (o.getKind() == XdcObject.Kind.PIN && o.getPin() != null) {
                pins.add(o.getPin());
            } else if (o.getKind() == XdcObject.Kind.CELL && o.getCell() != null) {
                pins.addAll(o.getCell().getHierPortInsts());
            }
            for (EDIFHierPortInst p : pins) {
                EDIFHierNet n = p.getHierarchicalNet();
                if (n != null && n.getNet() != null && seen.add(n.getHierarchicalNetName())) {
                    result.add(XdcObject.ofNet(n));
                }
            }
        }
        return result;
    }
}
