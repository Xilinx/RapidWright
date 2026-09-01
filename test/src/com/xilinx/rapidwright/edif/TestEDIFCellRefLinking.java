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

package com.xilinx.rapidwright.edif;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that a (cellRef ... (libraryRef ...)) resolves to the cell it names,
 * including when the library it names carries a (rename ...).
 */
public class TestEDIFCellRefLinking {

    private static String netlist(String libraryNameDef, String libraryRef) {
        return "(edif test\n"
                + "  (edifVersion 2 0 0)\n"
                + "  (edifLevel 0)\n"
                + "  (keywordMap (keywordLevel 0))\n"
                + "  (status (written (timeStamp 2024 1 1 0 0 0)"
                + " (program \"Vivado\" (version \"2024.1\"))))\n"
                + "  (library " + libraryNameDef + "\n"
                + "    (edifLevel 0)\n"
                + "    (technology (numberDefinition))\n"
                + "    (cell sub (cellType GENERIC)\n"
                + "      (view netlist (viewType NETLIST)\n"
                + "        (interface (port i (direction INPUT)) (port o (direction OUTPUT)))\n"
                + "      )\n"
                + "    )\n"
                + "    (cell top (cellType GENERIC)\n"
                + "      (view netlist (viewType NETLIST)\n"
                + "        (interface (port a (direction INPUT)))\n"
                + "        (contents\n"
                + "          (instance inst1 (viewRef netlist"
                + " (cellRef sub (libraryRef " + libraryRef + "))))\n"
                + "        )\n"
                + "      )\n"
                + "    )\n"
                + "  )\n"
                + "  (design top (cellRef top (libraryRef " + libraryRef + ")))\n"
                + ")\n";
    }

    private static EDIFNetlist parse(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        try (EDIFParser parser = new EDIFParser(p)) {
            return parser.parseEDIFNetlist();
        }
    }

    private static void assertCellRefResolved(EDIFNetlist netlist) {
        EDIFCell sub = netlist.getDesign().getTopCell().getCellInst("inst1").getCellType();
        Assertions.assertEquals("sub", sub.getName());
        // An unresolved reference leaves behind a placeholder cell with no ports
        Assertions.assertEquals(2, sub.getPorts().size());
    }

    @Test
    public void testPlainLibrary(@TempDir Path dir) throws IOException {
        assertCellRefResolved(parse(dir, "plain.edf", netlist("work", "work")));
    }

    @Test
    public void testRenamedLibrary(@TempDir Path dir) throws IOException {
        assertCellRefResolved(parse(dir, "renamed.edf",
                netlist("(rename work_lib \"Work Library\")", "work_lib")));
    }
}
