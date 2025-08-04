/*
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel
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

package com.xilinx.rapidwright.ipi;

import java.util.Arrays;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
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
 * Lookup Cells for use in the XDC Parser
 * @param <T>
 */
public abstract class EdifCellLookup<T> {


    @NotNull
    public TclObject toReflectObj(Interp interp, T cell) throws TclException {
        return TclHashIdentifiedObject.createReflectObject(interp, getCellClass(), cell);
    }

    public Stream<T> allCellInsts(T ci) {
        Stream<T> self = Stream.of(ci);
        Stream<? extends T> directChildren = getChildrenOf(ci);;
        Stream<? extends T> allChildren = directChildren.flatMap(this::allCellInsts);
        return Stream.concat(self, allChildren);
    }

    public Stream<T> getHierCellInstsFromWildcardName(String cellName) {
        return getChildBySomeAbsoluteName(cellName, (s, item) -> FilenameUtils.wildcardMatch(getRelativeOriginalName(item), s));
    }
    public abstract T getInstFromOriginalName(String cellName);

    public abstract Stream<? extends T> getChildrenOf(T f);

    public abstract T getChild(T cell, String name);

    private Stream<T> getChildBySomeAbsoluteNameWorker(String[] parts, int level, T current, BiPredicate<String, T> filter) {
        if (level==parts.length) {
            return Stream.of(current);
        }
        return IntStream.range(level, parts.length)
                .boxed().flatMap(end-> {
                    String nameAtCurrentLevel = Arrays.stream(parts, level, end+1).collect(Collectors.joining(EDIFTools.EDIF_HIER_SEP));
                    return getChildrenOf(current)
                            .filter(item->filter.test(nameAtCurrentLevel, item))
                            .flatMap(c->getChildBySomeAbsoluteNameWorker(parts, end+1, c, filter));
                });
    }


    public Stream<T> getChildBySomeAbsoluteName(String name, BiPredicate<String, T> filter) {

        if (name.isEmpty()) return Stream.of(getRoot());

        String[] parts = name.split(EDIFTools.EDIF_HIER_SEP);

        // Sadly, cells can be named 'fred/' instead of 'fred', this code handles this situation
        if (name.charAt(name.length()-1) == '/') {
            parts[parts.length-1] = parts[parts.length-1] + EDIFTools.EDIF_HIER_SEP;
        }

        return getChildBySomeAbsoluteNameWorker(parts, 0, getRoot(), filter);
    }




    public Predicate<T> getAbsoluteRegexFilter(String cellNames) {
        return eci -> getAbsoluteOriginalName(eci).matches(cellNames);
    }

    public Predicate<T> getAbsoluteWildcardFilter(String cellNames) {
        return eci -> FilenameUtils.wildcardMatch(getAbsoluteOriginalName(eci), cellNames);
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
