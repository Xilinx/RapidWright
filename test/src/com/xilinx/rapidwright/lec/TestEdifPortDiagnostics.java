/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Advanced Micro Devices, Inc.
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

package com.xilinx.rapidwright.lec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the scan that explains an EDIF file connecting to undeclared ports.
 */
public class TestEdifPortDiagnostics {

    /**
     * An undeclared port must be found on a renamed instance too, whose quoted
     * original name sits between its legal name and its viewref.
     */
    @Test
    public void testUndeclaredPortOnRenamedInstance(@TempDir Path dir) throws IOException {
        Path edif = dir.resolve("bad.edf");
        Files.write(edif, Arrays.asList(
                "(edif top",
                "  (library hdi_primitives",
                "    (cell LUT1 (celltype GENERIC)",
                "      (view netlist (viewtype NETLIST)",
                "        (interface (port O (direction OUTPUT)) (port I0 (direction INPUT))))))",
                "  (library work",
                "    (cell top (celltype GENERIC)",
                "      (view netlist (viewtype NETLIST)",
                "        (contents",
                "          (instance (rename u_0 \"u/0\") (viewref netlist (cellref LUT1 "
                        + "(libraryref hdi_primitives))))",
                "          (net n (joined (portref I7 (instanceref u_0))))))))))"));
        List<EdifPortDiagnostics.Finding> findings = EdifPortDiagnostics.scan(edif, 10);
        Assertions.assertEquals(1, findings.size(), findings.toString());
        Assertions.assertEquals("I7", findings.get(0).getPortName());
        Assertions.assertEquals("LUT1", findings.get(0).getReferencedCell());
    }
}
