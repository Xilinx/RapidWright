/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
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

package com.xilinx.rapidwright.timing.sdf;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

/**
 * Thrown by {@link SdfAnnotator#annotateOrThrow} when some part of an SDF could not be applied to
 * the timing graph, or when some edge of the graph received no delay from it.
 *
 * The message carries the full {@link SdfAnnotationReport} summary, since the useful information is
 * which categories were non-empty and a few examples from each.
 */
public class SdfAnnotationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SdfAnnotationReport report;

    /**
     * @param report The report describing what could not be applied.
     */
    public SdfAnnotationException(SdfAnnotationReport report) {
        super(buildMessage(report));
        this.report = report;
    }

    private static String buildMessage(SdfAnnotationReport report) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream ps = new PrintStream(bytes, true, StandardCharsets.UTF_8.name())) {
            ps.println("ERROR: SDF annotation was incomplete.");
            report.printSummary(ps);
        } catch (UnsupportedEncodingException e) {
            // UTF-8 is required to be present on every JVM.
            throw new IllegalStateException(e);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * @return The report describing what could not be applied.
     */
    public SdfAnnotationReport getReport() {
        return report;
    }
}
