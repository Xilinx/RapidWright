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
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Checks that the EDIF parser accepts the constructs the EDIF 2.0.0 grammar
 * permits, rather than only the subset Vivado happens to emit.
 */
public class TestEDIFParserGrammar {

    /**
     * Builds a minimal but complete netlist, varying only the status block so each
     * test can exercise one shape of (written ...).
     */
    protected static String netlist(String status) {
        return "(edif test\n"
                + "  (edifVersion 2 0 0)\n"
                + "  (edifLevel 0)\n"
                + "  (keywordMap (keywordLevel 0))\n"
                + status
                + "  (library work\n"
                + "    (edifLevel 0)\n"
                + "    (technology (numberDefinition))\n"
                + "    (cell sub (cellType GENERIC)\n"
                + "      (view netlist (viewType NETLIST)\n"
                + "        (interface (port i (direction INPUT)))\n"
                + "      )\n"
                + "    )\n"
                + "    (cell top (cellType GENERIC)\n"
                + "      (view netlist (viewType NETLIST)\n"
                + "        (interface\n"
                + "          (port a (direction INPUT))\n"
                + "          (port b (direction OUTPUT))\n"
                + "        )\n"
                + "        (contents\n"
                + "          (instance inst1 (viewRef netlist (cellRef sub (libraryRef work))))\n"
                + "          (net n1 (joined (portRef a) (portRef i (instanceRef inst1))))\n"
                + "        )\n"
                + "      )\n"
                + "    )\n"
                + "  )\n"
                + "  (design top (cellRef top (libraryRef work)))\n"
                + ")\n";
    }

    protected static String defaultStatus() {
        return "  (status (written (timeStamp 2024 1 1 0 0 0)"
                + " (program \"Vivado\" (version \"2024.1\"))))\n";
    }

    protected static String simple() {
        return netlist(defaultStatus());
    }

    protected static EDIFNetlist parse(Path dir, String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content.getBytes(StandardCharsets.UTF_8));
        try (EDIFParser parser = new EDIFParser(p)) {
            return parser.parseEDIFNetlist();
        }
    }

    protected static void assertWellFormed(EDIFNetlist netlist) {
        Assertions.assertNotNull(netlist.getDesign());
        EDIFCell top = netlist.getDesign().getTopCell();
        Assertions.assertEquals("top", top.getName());
        Assertions.assertEquals(2, top.getPorts().size());
        Assertions.assertEquals(1, top.getCellInsts().size());
        Assertions.assertEquals(1, top.getNets().size());
        EDIFCell sub = top.getCellInst("inst1").getCellType();
        Assertions.assertEquals("sub", sub.getName());
        Assertions.assertEquals(1, sub.getPorts().size());
    }

    /** The baseline fixture must parse, so later assertions mean something. */
    @Test
    public void testBaseline(@TempDir Path dir) throws IOException {
        assertWellFormed(parse(dir, "baseline.edf", simple()));
    }

    /** (author ...) may precede (program ...), and the version is optional. */
    @Test
    public void testWrittenAuthorAndOptionalProgram(@TempDir Path dir) throws IOException {
        String status = "  (status (written (timeStamp 2024 1 1 0 0 0)\n"
                + "    (author \"Some Tool\")\n"
                + "    (program \"NoVersionTool\")))\n";
        assertWellFormed(parse(dir, "author.edf", netlist(status)));
    }

    /** (program ...) may still carry a version, in any position. */
    @Test
    public void testWrittenProgramWithVersionAfterAuthor(@TempDir Path dir) throws IOException {
        String status = "  (status (written (timeStamp 2024 1 1 0 0 0)\n"
                + "    (author \"Some Tool\")\n"
                + "    (program \"Tool\" (version \"1.2\"))))\n";
        assertWellFormed(parse(dir, "authorver.edf", netlist(status)));
    }

    /**
     * Bodies the EDIF grammar permits inside (written ...). Every entry is optional
     * and repeatable, and the order between them is not fixed.
     */
    static Stream<Arguments> writtenBodies() {
        return Stream.of(
                Arguments.of("author only, no program",
                        "    (author \"Some Tool\")\n"),
                Arguments.of("neither author nor program",
                        ""),
                Arguments.of("program ahead of author",
                        "    (program \"Tool\" (version \"1.2\")) (author \"Some Tool\")\n"),
                Arguments.of("author repeated",
                        "    (author \"First\") (author \"Second\")\n"),
                Arguments.of("author text containing spaces and parentheses",
                        "    (author \"A B (x)\")\n"),
                Arguments.of("program carrying a comment instead of a version",
                        "    (program \"Tool\" (comment \"built somewhere\"))\n"),
                Arguments.of("program carrying both a version and a comment",
                        "    (program \"Tool\" (version \"1.2\") (comment \"note\"))\n"),
                Arguments.of("program carrying nested userData",
                        "    (program \"Tool\" (userData ud (inner (deeper 1))))\n"),
                Arguments.of("program comment holding several strings",
                        "    (program \"Tool\" (comment \"one\" \"two\"))\n"),
                Arguments.of("program comment whose text is a parenthesis",
                        "    (program \"Tool\" (comment \")\"))\n"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writtenBodies")
    public void testWrittenBodyVariants(String description, String writtenBody, @TempDir Path dir)
            throws IOException {
        String status = "  (status (written (timeStamp 2024 1 1 0 0 0)\n" + writtenBody + "  ))\n";
        assertWellFormed(parse(dir, "written.edf", netlist(status)));
    }

    /**
     * A comment inside (written ...) is retained on the netlist. An author has
     * nowhere to be stored and is dropped, so it must not be mistaken for one.
     */
    @Test
    public void testWrittenCommentRetainedAlongsideAuthor(@TempDir Path dir) throws IOException {
        String status = "  (status (written (timeStamp 2024 1 1 0 0 0)\n"
                + "    (author \"Some Tool\")\n"
                + "    (comment \"a retained comment\")))\n";
        EDIFNetlist netlist = parse(dir, "comment.edf", netlist(status));
        assertWellFormed(netlist);
        Assertions.assertEquals(1, netlist.getComments().size());
        Assertions.assertEquals("a retained comment", netlist.getComments().get(0));
    }
}
