/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Xilinx Research Labs.
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

package com.xilinx.rapidwright.util.lsf;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Delegating Test Plan that allows filtering and mapping
 */
public class ModifyingTestPlan extends TestPlan {
    private final TestPlan original;
    private final Predicate<String> filter;
    private Function<TestIdentifier, TestIdentifier> mapper;

    protected ModifyingTestPlan(TestPlan original, Predicate<String> filter, Function<TestIdentifier, TestIdentifier> mapper) {
        super(original.containsTests());
        this.original = original;
        this.filter = filter;
        this.mapper = mapper;
    }

    private Set<TestIdentifier> modify(Set<TestIdentifier> o) {
        return o.stream()
                .filter(t -> filter.test(t.getUniqueId()))
                .map(mapper)
                .collect(Collectors.toSet());
    }

    @Override
    public Set<TestIdentifier> getRoots() {
        return modify(original.getRoots());
    }

    @Override
    public Optional<TestIdentifier> getParent(TestIdentifier child) {
        //TODO maybe need to map back?
        original.getParent(child).flatMap(parent->filter.test(parent.getUniqueId()) ? Optional.of(mapper.apply(parent)) : Optional.empty());
        return super.getParent(child);
    }

    @Override
    public Set<TestIdentifier> getChildren(TestIdentifier parent) {
        return modify(original.getChildren(parent));
    }

    @Override
    public Set<TestIdentifier> getChildren(String parentId) {
        return modify(original.getChildren(parentId));
    }

    @Override
    public TestIdentifier getTestIdentifier(String uniqueId) throws PreconditionViolationException {
        return mapper.apply(original.getTestIdentifier(uniqueId));
    }

    @Override
    public long countTestIdentifiers(Predicate<? super TestIdentifier> predicate) {
        return original.countTestIdentifiers(p->predicate.test(mapper.apply(p)));
    }

    @Override
    public Set<TestIdentifier> getDescendants(TestIdentifier parent) {
        return modify(original.getDescendants(parent));
    }

    @Override
    public boolean containsTests() {
        return original.containsTests();
    }
}
