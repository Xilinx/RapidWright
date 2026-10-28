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

import java.util.Arrays;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Checks the retiming certificate format and its error messages. */
public class TestRetimingMap {

    @Test
    public void testParsesEntriesAndComments() {
        RetimingMap map = RetimingMap.parse(Arrays.asList(
                "# forward retiming",
                "",
                "IMP core/r_fwd = REF core/lut_f/O",
                "  REF core/q_reg   =   IMP core/lut_g/O  "), "map.txt");
        Assertions.assertEquals(2, map.size());
        RetimingMap.Entry forward = map.getEntries().get(0);
        Assertions.assertEquals(RetimingMap.Side.IMP, forward.getRegisterSide());
        Assertions.assertEquals("core/r_fwd", forward.getRegister());
        Assertions.assertEquals("core/lut_f", forward.getDefiningInst());
        Assertions.assertEquals("O", forward.getDefiningPin());
        Assertions.assertEquals("map.txt:3", forward.getSource());
        Assertions.assertEquals(Arrays.asList("core/r_fwd"),
                Arrays.asList(map.registersOn(RetimingMap.Side.IMP).toArray()));
        Assertions.assertEquals(Arrays.asList("core/lut_f/O"),
                Arrays.asList(map.pinsOn(RetimingMap.Side.REF).toArray()));
        Assertions.assertEquals(Arrays.asList("core/lut_g/O"),
                Arrays.asList(map.pinsOn(RetimingMap.Side.IMP).toArray()));
    }

    @Test
    public void testErrorsNameTheLine() {
        assertRejected("IMP r = REF", "map.txt:1");
        assertRejected("IMP r : REF lut/O", "map.txt:1");
        assertRejected("NEW r = REF lut/O", "expected REF or IMP");
        assertRejected("IMP r = IMP lut/O", "both sides are IMP");
        assertRejected("IMP r = REF lut", "not an <instance>/<pin> name");
        LecException duplicate = Assertions.assertThrows(LecException.class,
                () -> RetimingMap.parse(Arrays.asList("IMP r = REF a/O", "IMP r = REF b/O"),
                        "map.txt"));
        Assertions.assertTrue(duplicate.getMessage().contains("map.txt:2"));
        Assertions.assertTrue(duplicate.getMessage().contains("map.txt:1"));
    }

    @Test
    public void testApiDefinesTheSameEntries() {
        RetimingMap map = new RetimingMap();
        map.define(RetimingMap.Side.REF, "q_reg", "lut_g/O");
        Assertions.assertEquals("REF q_reg = IMP lut_g/O", map.getEntries().get(0).toString());
    }

    private static void assertRejected(String line, String expectedText) {
        LecException e = Assertions.assertThrows(LecException.class,
                () -> RetimingMap.parse(Arrays.asList(line), "map.txt"));
        Assertions.assertTrue(e.getMessage().contains(expectedText), e.getMessage());
    }
}
