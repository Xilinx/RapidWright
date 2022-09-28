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

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.reporting.ReportEntry;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/**
 * Delegating TestExecutionListener that filters events to parents / children of one specific UniqueId
 */
public class ModifyingExecutionListener implements TestExecutionListener {
    private final UniqueId filterID;
    private final List<TestExecutionListener> children;
    private Function<TestIdentifier, TestIdentifier> mapper;

    private TestPlan currentPlan;

    public ModifyingExecutionListener(UniqueId filterID, List<TestExecutionListener> children, BiFunction<TestIdentifier, TestPlan, TestIdentifier> mapper) {
        this.filterID = filterID;
        this.children = children;
        this.mapper = identifier -> mapper.apply(identifier, currentPlan);
    }

    public static TestIdentifier modifyLegacyName(TestIdentifier id, TestPlan testPlan) {
        TestDescriptor parent = id.getParentId().map(parentId -> new CustomTestDescriptor(
                UniqueId.parse(parentId), null, null, null
        )).orElse(null);
        return TestIdentifier.from(new CustomTestDescriptor(
                UniqueId.parse(id.getUniqueId()),
                id.getDisplayName(),
                id.getType(),
                parent,
                id.getSource().orElse(null),
                id.getTags(),
                makeReportingName(id, testPlan)
        ));
    }

    private static String makeReportingName(TestIdentifier id, TestPlan testPlan) {
        if (!id.getParentId().isPresent()) {
            return id.getLegacyReportingName();
        }
        String parentLastSegmentType = UniqueId.parse(id.getParentId().get()).getLastSegment().getType();
        String prefix;
        if (parentLastSegmentType.equals("engine") || parentLastSegmentType.equals("class")) {
            prefix="";
        } else {
            TestIdentifier parentIdentifier = testPlan.getTestIdentifier(id.getParentId().get().toString());
            prefix = makeReportingName(parentIdentifier, testPlan)+ "/";
        }
        return prefix + id.getDisplayName();
    }

    private boolean testFilter(String idString) {
        if (filterID == null) {
            return true;
        }
        final UniqueId id = UniqueId.parse(idString);
        return filterID.hasPrefix(id) || id.hasPrefix(filterID);
    }

    @Override
    public void testPlanExecutionStarted(TestPlan testPlan) {
        currentPlan = testPlan;
        ModifyingTestPlan fp = new ModifyingTestPlan(testPlan, this::testFilter, mapper);
        children.forEach(c->c.testPlanExecutionStarted(fp));
    }

    @Override
    public void testPlanExecutionFinished(TestPlan testPlan) {
        currentPlan = null;
        ModifyingTestPlan fp = new ModifyingTestPlan(testPlan, this::testFilter, mapper);
        children.forEach(c->c.testPlanExecutionFinished(fp));
    }

    @Override
    public void dynamicTestRegistered(TestIdentifier testIdentifier) {
        if (testFilter(testIdentifier.getUniqueId())) {
            children.forEach(c->c.dynamicTestRegistered(mapper.apply(testIdentifier)));
        }
    }

    @Override
    public void executionSkipped(TestIdentifier testIdentifier, String reason) {
        if (testFilter(testIdentifier.getUniqueId())) {
            children.forEach(c->c.executionSkipped(mapper.apply(testIdentifier), reason));
        }
    }

    @Override
    public void executionStarted(TestIdentifier testIdentifier) {
        if (testFilter(testIdentifier.getUniqueId())) {
            children.forEach(c->c.executionStarted(mapper.apply(testIdentifier)));
        }
    }

    @Override
    public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
        if (testFilter(testIdentifier.getUniqueId())) {
            children.forEach(c->c.executionFinished(mapper.apply(testIdentifier), testExecutionResult));
        }
    }

    @Override
    public void reportingEntryPublished(TestIdentifier testIdentifier, ReportEntry entry) {
        if (testFilter(testIdentifier.getUniqueId())) {
            children.forEach(c->c.reportingEntryPublished(mapper.apply(testIdentifier), entry));
        }
    }
}
