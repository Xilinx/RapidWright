/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Technical University of Darmstadt
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

package com.xilinx.rapidwright.design.xdc.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import org.apache.commons.io.FilenameUtils;
import org.jetbrains.annotations.NotNull;
import tcl.lang.Interp;
import tcl.lang.TclException;
import tcl.lang.TclObject;

/**
 * Lookup Cells for use in the XDC Parser.
 * <p>
 * Cells can have different names in the source XDC versus the actual objects in the design. This allows rewriting
 * constraints to match a possibly restructured design. Cells have an <i>original</i> and a <i>final</i> name to
 * support this.
 * <p>
 * A derived class can specify how to do this mapping. {@link RegularEdifCellLookup} operates on EDIF netlists without
 * rewriting.
 *
 * @param <T> Representation of Cells
 */
public abstract class EdifCellLookup<T> {


    /**
     * Convert a cell to a Tcl Object
     * @param interp the interpreter
     * @param cell the cell
     * @return Tcl object representing the cell
     * @throws TclException
     */
    @NotNull
    public TclObject toReflectObj(Interp interp, T cell) throws TclException {
        return TclHashIdentifiedObject.createReflectObject(interp, getCellClass(), cell);
    }

    /**
     * Stream of the tree of cells rooted at the current cell
     * @param ci base of the tree
     * @return stream of all children
     */
    public Stream<T> allCellInsts(T ci) {
        Stream<T> self = Stream.of(ci);
        Stream<? extends T> directChildren = getChildrenOf(ci);;
        Stream<? extends T> allChildren = directChildren.flatMap(this::allCellInsts);
        return Stream.concat(self, allChildren);
    }

    public Stream<T> getHierCellInstsFromWildcardName(String cellName) {
        if (!cellName.contains("*")) {
            T res = getInstFromOriginalName(cellName);
            if (res == null) {
                return Stream.empty();
            }
            return Stream.of(res);
        }
        // (each part's pattern split into tokens once, not once per child)
        Map<String, WildcardPattern> patterns = new ConcurrentHashMap<>();
        return getChildBySomeAbsoluteName(cellName,
                (s, item) -> wildcardPattern(patterns, s).matches(getRelativeOriginalName(item)),
                isChildLookupByOriginalName());
    }

    public Stream<T> getHierCellInstsFromRegexpName(String cellName) {
        // (each part's pattern compiled once, not once per child)
        Map<String, Pattern> patterns = new ConcurrentHashMap<>();
        return getChildBySomeAbsoluteName(cellName,
                (s, item) -> patterns.computeIfAbsent(s, Pattern::compile).matcher(getRelativeOriginalName(item)).matches());
    }

    /** @return The wildcard pattern of a name part, split into tokens once per lookup. */
    protected static WildcardPattern wildcardPattern(Map<String, WildcardPattern> patterns, String part) {
        return patterns.computeIfAbsent(part, WildcardPattern::new);
    }

    /**
     * Whether {@link #getChild(Object, String)} returns exactly the child whose relative original name is the given
     * name. If so, the wildcard-free parts of a hierarchical name are looked up directly instead of being matched
     * against every child of their parent.
     * @return False here; subclasses whose child lookup is by original name return true.
     */
    protected boolean isChildLookupByOriginalName() {
        return false;
    }

    /**
     * Matches a name against a wildcard pattern exactly as {@link FilenameUtils#wildcardMatch(String, String)} does
     * ('*' any sequence, '?' one character, case-sensitive, including its handling of '?' right after '*'), with
     * the pattern split into tokens once ({@link WildcardPattern}).
     * @param name The name.
     * @param pattern The pattern.
     * @return True if the name matches the pattern.
     */
    public static boolean wildcardMatch(String name, String pattern) {
        if (name == null || pattern == null) {
            return name == null && pattern == null;
        }
        return new WildcardPattern(pattern).matches(name);
    }

    /** A wildcard pattern split into its tokens once, matched as {@link FilenameUtils#wildcardMatch}. */
    public static final class WildcardPattern {
        private final String[] tokens;

        public WildcardPattern(String pattern) {
            List<String> list = new ArrayList<>();
            StringBuilder buffer = new StringBuilder();
            char prev = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char ch = pattern.charAt(i);
                if (ch == '?' || ch == '*') {
                    if (buffer.length() != 0) {
                        list.add(buffer.toString());
                        buffer.setLength(0);
                    }
                    if (ch == '?') {
                        list.add("?");
                    } else if (prev != '*') {
                        list.add("*");
                    }
                } else {
                    buffer.append(ch);
                }
                prev = ch;
            }
            if (buffer.length() != 0 || list.isEmpty()) {
                list.add(buffer.toString());
            }
            tokens = list.toArray(new String[0]);
        }

        /** @return True if the name matches (the algorithm of FilenameUtils#wildcardMatch, case-sensitive). */
        public boolean matches(String name) {
            boolean anyChars = false;
            int textIdx = 0;
            int wcsIdx = 0;
            ArrayDeque<int[]> backtrack = null;
            do {
                if (backtrack != null && !backtrack.isEmpty()) {
                    int[] array = backtrack.pop();
                    wcsIdx = array[0];
                    textIdx = array[1];
                    anyChars = true;
                }
                while (wcsIdx < tokens.length) {
                    String t = tokens[wcsIdx];
                    if (t.equals("?")) {
                        textIdx++;
                        if (textIdx > name.length()) {
                            break;
                        }
                        anyChars = false;
                    } else if (t.equals("*")) {
                        anyChars = true;
                        if (wcsIdx == tokens.length - 1) {
                            textIdx = name.length();
                        }
                    } else {
                        if (anyChars) {
                            textIdx = name.indexOf(t, textIdx);
                            if (textIdx < 0) {
                                break;
                            }
                            int repeat = name.indexOf(t, textIdx + 1);
                            if (repeat >= 0) {
                                if (backtrack == null) {
                                    backtrack = new ArrayDeque<>();
                                }
                                backtrack.push(new int[] {wcsIdx, repeat});
                            }
                        } else if (!name.regionMatches(textIdx, t, 0, t.length())) {
                            break;
                        }
                        textIdx += t.length();
                        anyChars = false;
                    }
                    wcsIdx++;
                }
                if (wcsIdx == tokens.length && textIdx == name.length()) {
                    return true;
                }
            } while (backtrack != null && !backtrack.isEmpty());
            return false;
        }
    }

    public abstract T getInstFromOriginalName(String cellName);

    public abstract Stream<? extends T> getChildrenOf(T f);

    public abstract T getChild(T cell, String name);

    private Stream<T> getChildBySomeAbsoluteNameWorker(String[] parts, int level, T current, BiPredicate<String, T> filter,
                                                       boolean literalByName) {
        if (level==parts.length) {
            return Stream.of(current);
        }
        if (literalByName && parts[level].indexOf('*') < 0 && parts[level].indexOf('?') < 0) {
            // A part without wildcards matches only the child of that name
            T child = getChild(current, parts[level]);
            return child == null ? Stream.empty()
                    : getChildBySomeAbsoluteNameWorker(parts, level + 1, child, filter, literalByName);
        }
        Stream<? extends T> children = literalByName
                ? getChildrenWithName(current, parts[level], filter)
                : getChildrenOf(current).filter(child -> filter.test(parts[level], child));
        return children.flatMap(c->getChildBySomeAbsoluteNameWorker(parts, level+1, c, filter, literalByName));
    }

    /**
     * The children of a cell whose relative original name the filter accepts for a name part. A subclass whose
     * children's relative original names are known before the child objects exist may override this to build
     * only the matching children (see {@link #isChildLookupByOriginalName()}).
     * @param cell The parent cell.
     * @param part The name part (pattern).
     * @param filter The filter of {@link #getChildBySomeAbsoluteName(String, BiPredicate, boolean)}.
     * @return The matching children.
     */
    protected Stream<? extends T> getChildrenWithName(T cell, String part, BiPredicate<String, T> filter) {
        return getChildrenOf(cell).filter(child -> filter.test(part, child));
    }


    public Stream<T> getChildBySomeAbsoluteName(String name, BiPredicate<String, T> filter) {
        return getChildBySomeAbsoluteName(name, filter, false);
    }

    /**
     * As {@link #getChildBySomeAbsoluteName(String, BiPredicate)}.
     * @param literalByName If true, the filter is a wildcard match and {@link #isChildLookupByOriginalName()}
     *        holds: parts without '*' or '?' are looked up with {@link #getChild(Object, String)}.
     */
    protected Stream<T> getChildBySomeAbsoluteName(String name, BiPredicate<String, T> filter, boolean literalByName) {

        if (name.isEmpty()) return Stream.of(getRoot());

        String[] parts = name.split(EDIFTools.EDIF_HIER_SEP);

        // Sadly, cells can be named 'fred/' instead of 'fred', this code handles this situation
        if (name.charAt(name.length()-1) == '/') {
            parts[parts.length-1] = parts[parts.length-1] + EDIFTools.EDIF_HIER_SEP;
        }

        return getChildBySomeAbsoluteNameWorker(parts, 0, getRoot(), filter, literalByName);
    }




    public Predicate<T> getAbsoluteRegexFilter(String cellNames) {
        return eci -> getAbsoluteOriginalName(eci).matches(cellNames);
    }

    public Predicate<T> getAbsoluteWildcardFilter(String cellNames) {
        WildcardPattern p = new WildcardPattern(cellNames);
        return eci -> p.matches(getAbsoluteOriginalName(eci));
    }

    public Stream<T> getAllCellInsts() {
        return allCellInsts(getRoot());
    }

    public abstract EDIFHierCellInst toEdifHierCellInst(T cell);

    public Predicate<T> getCellTypeFilter(Set<String> values) {
        return ci -> values.contains(getCellType(ci));
    }

    public abstract T getRoot();

    public abstract String getAbsoluteFinalName(T current);
    public abstract String getRelativeFinalName(T current);
    public abstract String getAbsoluteOriginalName(T current);
    public abstract String getRelativeOriginalName(T current);

    public abstract String getCellType(T current);

    public abstract Class<?> getCellClass();

    public T castCellInst(Object obj) {
        return (T)getCellClass().cast(obj);
    }

    public abstract T getInstFromFinalName(String s);

    public String getOriginalName(String s) {
        return getAbsoluteOriginalName(getInstFromFinalName(s));
    }
}
