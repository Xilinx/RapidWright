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

import java.io.PrintWriter;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener;

/**
 * Writes a junit report
 */
public class StaticReportGenerator {

    public static void writeMasterXmlLog(String message, Map<String, String> perTestMessages, TestPlan testPlan) {
        LegacyXmlReportGeneratingListener xmlListener = new LegacyXmlReportGeneratingListener(Paths.get("."), new PrintWriter(System.out));
        ModifyingExecutionListener listener = new ModifyingExecutionListener(null, Collections.singletonList(xmlListener), ModifyingExecutionListener::modifyLegacyName);
        TestDescriptor engineDescriptor = new CustomTestDescriptor(UniqueId.forEngine("rapidwright-junit-runner"),"Rapidwright Junit Runner", TestDescriptor.Type.CONTAINER, null);
        TestDescriptor testDescriptor = new CustomTestDescriptor(engineDescriptor.getUniqueId().append("test", "execution-message"),"Test Execution", TestDescriptor.Type.TEST, engineDescriptor);
        engineDescriptor.addChild(testDescriptor);

        TestIdentifier engine = TestIdentifier.from(engineDescriptor);
        TestIdentifier test = TestIdentifier.from(testDescriptor);

        TestPlan dummyPlan = new TestPlan(true) {
            @Override
            public Set<TestIdentifier> getRoots() {
                return Collections.singleton(engine);
            }

            @Override
            public Optional<TestIdentifier> getParent(TestIdentifier child) {
                if (child == test) {
                    return Optional.of(engine);
                }
                return Optional.empty();
            }

            @Override
            public Set<TestIdentifier> getChildren(TestIdentifier parent) {
                if (parent == engine) {
                    return Collections.singleton(test);
                }
                return Collections.emptySet();
            }

            @Override
            public Set<TestIdentifier> getChildren(String parentId) {
                if (parentId.equals(engine.getUniqueId())) {
                    return Collections.singleton(test);
                }
                return Collections.emptySet();
            }

            @Override
            public TestIdentifier getTestIdentifier(String uniqueId) throws PreconditionViolationException {
                if (uniqueId.equals(test.getUniqueId())) {
                    return test;
                }
                if (uniqueId.equals(engine.getUniqueId())) {
                    return engine;
                }
                throw new PreconditionViolationException("id not found");
            }

            @Override
            public Set<TestIdentifier> getDescendants(TestIdentifier parent) {
                return getChildren(parent);
            }
        };
        listener.testPlanExecutionStarted(dummyPlan);
        listener.executionStarted(engine);
        listener.executionStarted(test);
        listener.executionFinished(test, TestExecutionResult.failed(new RuntimeException(message)));
        listener.executionFinished(engine, TestExecutionResult.successful());
        listener.testPlanExecutionFinished(dummyPlan);

        if (!perTestMessages.isEmpty()) {
            ModifyingTestPlan filteredPlan = new ModifyingTestPlan(testPlan, other -> perTestMessages.keySet().stream().anyMatch(id -> UniqueId.parse(id).hasPrefix(UniqueId.parse(other))), Function.identity());
            listener.testPlanExecutionStarted(filteredPlan);
            doFakeExecution(filteredPlan, perTestMessages, listener);
            listener.testPlanExecutionFinished(filteredPlan);
        }
    }

    private static void doFakeExecution(TestPlan filteredPlan, Map<String, String> perTestMessages, TestExecutionListener listener) {
        for (TestIdentifier root : filteredPlan.getRoots()) {
            doFakeExecution(filteredPlan, perTestMessages, listener, root);
        }
    }

    private static void doFakeExecution(TestPlan filteredPlan, Map<String, String> perTestMessages, TestExecutionListener listener, TestIdentifier testIdentifier) {
        listener.executionStarted(testIdentifier);

        for (TestIdentifier child : filteredPlan.getChildren(testIdentifier)) {
            doFakeExecution(filteredPlan, perTestMessages, listener, child);
        }

        String message = perTestMessages.get(testIdentifier.getUniqueId());
        if (message == null) {
            listener.executionFinished(testIdentifier, TestExecutionResult.successful());
        } else {
            listener.executionFinished(testIdentifier, TestExecutionResult.failed(new AssertionError(message)));
        }
    }

}
