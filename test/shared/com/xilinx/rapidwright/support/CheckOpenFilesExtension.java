/*
 * Copyright (c) 2021-2022, Xilinx, Inc.
 * Copyright (c) 2022-2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Xilinx Research Labs.
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

package com.xilinx.rapidwright.support;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.BeforeTestExecutionCallback;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.engine.config.JupiterConfiguration;

/**
 * Check that a testcase does leak open files
 *
 * Only works on Linux. On other OSes it cannot detect errors and will fail silently.
 */
public class CheckOpenFilesExtension implements BeforeTestExecutionCallback, AfterTestExecutionCallback {

    private String getOwnPid() {
        final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        String name = runtimeBean.getName();
        int atPosition = name.indexOf("@");
        if (atPosition<=1) {
            return name;
        }
        return name.substring(0,atPosition);
    }
    private List<String> getOpenFiles() {
        final Path fdList = Paths.get("/proc/" + getOwnPid() + "/fd");
        if (!Files.exists(fdList)) {
            //We are probably not on Linux, fail silently
            return Collections.emptyList();
        }
        try (final Stream<Path> list = Files.list(fdList)) {
            // Since Files.list() '... does not freeze the directory while iterating, so it may
            // (or may not) reflect updates to the directory that occur after returning from
            // this method.'
            return list.filter((p) -> Files.exists(p, LinkOption.NOFOLLOW_LINKS))
                    .flatMap(p -> {
                        try {
                            final Path linkTarget = Files.readSymbolicLink(p);
                            return Stream.of(linkTarget.toString());
                        } catch (IOException e) {
                            System.err.println("Ignoring file descriptor "+p+", could not resolve to actual file: "+e.getMessage());
                            return Stream.empty();
                        }})
                    .filter(this::checkIgnore)
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean checkIgnore(String path) {
        //Ignore Random Device
        if (path.equals("/dev/random") || path.equals("/dev/urandom")) {
            return false;
        }
        //Socket for debugging should be ignored
        if (path.startsWith("socket:")) {
            return false;
        }
        //Ignore JDK internals (may need to load new code during execution)
        if (path.startsWith(System.getProperty("java.home"))) {
            return false;
        }
        // Ignore Sysfs
        if (path.startsWith("/sys")) {
            return false;
        }
        return true;
    }

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create("com", "xilinx", "rapidwright", "checker");
    private static final String OPEN_FILES = "openFiles";

    @Override
    public void afterTestExecution(ExtensionContext extensionContext) {
        final List<String> afterList = getOpenFiles();
        List<String> beforeList = getBeforeList(extensionContext);


        if (!beforeList.equals(afterList)) {
            final Stream<String> newlyOpened = afterList.stream().filter(s -> !beforeList.contains(s)).map(s -> "Newly opened: " + s);
            final Stream<String> closed = beforeList.stream().filter(s -> !afterList.contains(s)).map(s -> "Closed: " + s);

            final String res = Stream.concat(newlyOpened, closed)
                    .collect(Collectors.joining("\n","List of open Files changed: \n", ""));
            Assertions.fail(res);
        }
    }

    private static List<String> getBeforeList(ExtensionContext extensionContext) {
        @SuppressWarnings("unchecked")
        List<String> beforeList = extensionContext.getStore(NAMESPACE).get(OPEN_FILES, List.class);
        return beforeList;
    }

    @Override
    public void beforeTestExecution(ExtensionContext extensionContext) {
        extensionContext.getStore(NAMESPACE).put(OPEN_FILES, getOpenFiles());
    }

    public static void assertExtensionInstalled(ExtensionContext extensionContext) {
        Assertions.assertNotNull(getBeforeList(extensionContext), "Open Files Checker Extension does not seem to be running");
    }

    public static class CheckOpenFilesWorkingExtension implements AfterTestExecutionCallback, ExecutionCondition {
        @Override
        public void afterTestExecution(ExtensionContext context) {
            assertExtensionInstalled(context);
        }

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            // Only run this extension (used by TestCheckOpenFilesInstalled) when extensions
            // are enabled which should be the case when invoked by through Gradle, but not
            // necessarily through an IDE
            Optional<String> extensions = context.getConfigurationParameter(JupiterConfiguration.EXTENSIONS_AUTODETECTION_ENABLED_PROPERTY_NAME);
            if ("true".equals(extensions.orElse("false")))
                return ConditionEvaluationResult.enabled(JupiterConfiguration.EXTENSIONS_AUTODETECTION_ENABLED_PROPERTY_NAME + " == true");
            return ConditionEvaluationResult.disabled(JupiterConfiguration.EXTENSIONS_AUTODETECTION_ENABLED_PROPERTY_NAME + " != true");
        }
    }
}
