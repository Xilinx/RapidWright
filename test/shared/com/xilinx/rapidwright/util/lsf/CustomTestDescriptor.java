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

import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;

class CustomTestDescriptor implements TestDescriptor {
    private final UniqueId id;
    private final String displayName;
    private final Type type;
    private final TestDescriptor parent;
    private final Set<TestDescriptor> children = new HashSet<>();
    private final TestSource source;
    private final Set<TestTag> tags;
    private final String legacyReportingName;

    CustomTestDescriptor(UniqueId id, String displayName, Type type, TestDescriptor parent, TestSource source, Set<TestTag> tags, String legacyReportingName) {
        this.id = id;
        this.displayName = displayName;
        this.type = type;
        this.parent = parent;
        this.source = source;
        this.tags = tags;
        this.legacyReportingName = legacyReportingName;
    }

    CustomTestDescriptor(UniqueId id, String displayName, Type type, TestDescriptor parent) {
        this(id, displayName, type, parent, null, Collections.emptySet(), displayName);
    }

    @Override
    public UniqueId getUniqueId() {
        return id;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public Set<TestTag> getTags() {
        return tags;
    }

    @Override
    public Optional<TestSource> getSource() {
        return Optional.ofNullable(source);
    }

    @Override
    public Optional<TestDescriptor> getParent() {
        return Optional.ofNullable(parent);
    }

    @Override
    public void setParent(TestDescriptor parent) {
        throw new RuntimeException("not supported");
    }

    @Override
    public Set<? extends TestDescriptor> getChildren() {
        return children;
    }

    @Override
    public void addChild(TestDescriptor descriptor) {
        children.add(descriptor);
    }

    @Override
    public void removeChild(TestDescriptor descriptor) {
        throw new RuntimeException("not supported");

    }

    @Override
    public void removeFromHierarchy() {
        throw new RuntimeException("not supported");

    }

    @Override
    public Type getType() {
        return type;
    }

    @Override
    public Optional<? extends TestDescriptor> findByUniqueId(UniqueId uniqueId) {
        throw new RuntimeException("not supported");
    }

    @Override
    public String getLegacyReportingName() {
        return legacyReportingName;
    }
}
