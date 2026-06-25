/*
 *
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
package com.xilinx.rapidwright.edif.validate;

/**
 * A single inconsistency found by {@link NetlistValidator}. Issues are
 * immutable and carry a stable {@link IssueCode}, a {@link Severity}, a
 * human-readable location string and a descriptive message.
 */
public class ValidationIssue {

    private final Severity severity;
    private final IssueCode code;
    private final String location;
    private final String message;

    public ValidationIssue(Severity severity, IssueCode code, String location, String message) {
        this.severity = severity;
        this.code = code;
        this.location = location;
        this.message = message;
    }

    public ValidationIssue(IssueCode code, String location, String message) {
        this(code.getDefaultSeverity(), code, location, message);
    }

    public Severity getSeverity() {
        return severity;
    }

    public IssueCode getCode() {
        return code;
    }

    public String getLocation() {
        return location;
    }

    public String getMessage() {
        return message;
    }

    @Override
    public String toString() {
        return "[" + severity + "] " + code + " " + location + " — " + message;
    }
}
