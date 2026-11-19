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
 * Severity of an {@link ValidationIssue}.
 */
public enum Severity {
    /** A definite inconsistency that will corrupt or fail on Vivado read-back. */
    ERROR,
    /** A likely problem or a construct that is legal only in narrow cases. */
    WARNING,
    /** Informational; usually harmless but often an artifact of a bug. */
    INFO
}
