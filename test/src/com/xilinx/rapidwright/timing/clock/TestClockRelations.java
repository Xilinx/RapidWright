/*
 *
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

package com.xilinx.rapidwright.timing.clock;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.edif.EDIFNetlist;

public class TestClockRelations {

    private static Clock clock(int id, double periodNs, double riseNs, double fallNs) {
        return Clock.createPrimary(id, "c" + id, periodNs * 1000, new double[]{riseNs * 1000, fallNs * 1000}, "src");
    }

    private static void assertRelation(Clock launch, boolean lf, Clock capture, boolean cf, double setupNs,
                                       double holdNs) {
        double[] r = ClockRelations.compute(launch, lf, capture, cf);
        Assertions.assertEquals(setupNs * 1000, r[0], 1e-6, "setup " + launch + " -> " + capture);
        Assertions.assertEquals(holdNs * 1000, r[1], 1e-6, "hold " + launch + " -> " + capture);
    }

    @Test
    public void testEdgeAlignment() {
        // Same clock: one period, hold 0
        Clock c10 = clock(0, 10, 0, 5);
        assertRelation(c10, false, c10, false, 10, 0);
        // Rise to fall of the same clock: half a period, hold -half
        Clock c6 = clock(1, 6, 0, 3);
        assertRelation(c6, false, c6, true, 3, -3);
        assertRelation(c6, true, c6, false, 3, -3);
        // 2:3 and 3:2 ratios
        Clock c2 = clock(2, 2, 0, 1);
        Clock c3 = clock(3, 3, 0, 1.5);
        assertRelation(c2, false, c3, false, 1, 0);
        assertRelation(c3, false, c2, false, 1, 0);
        // Vivado: 4 ns -> 5 ns: 1.000 / 0.000; 12.8 ns -> 4 ns: 0.800; 2.5 ns -> 8 ns {0 2}: 0.500
        assertRelation(clock(4, 4, 0, 2), false, clock(5, 5, 0, 2.5), false, 1, 0);
        assertRelation(clock(6, 12.8, 0, 6.4), false, clock(7, 4, 0, 2), false, 0.8, 0);
        assertRelation(clock(8, 2.5, 0, 1.25), false, clock(9, 8, 0, 2), false, 0.5, 0);
        // Inverted waveform {2 4} of a 4 ns clock: Vivado 2.000 / -2.000, and 0.500 to a 7.5 ns clock
        Clock inv = clock(10, 4, 2, 4);
        assertRelation(clock(11, 4, 0, 2), false, inv, false, 2, -2);
        assertRelation(inv, false, clock(12, 7.5, 0, 3.75), false, 0.5, 0);
        // Shifted waveform {1 3.5}
        assertRelation(clock(13, 5, 0, 2.5), false, clock(14, 5, 1, 3.5), false, 1, -4);
        // Generated /2 of a 10 ns clock, and 2.5 ns to it (Vivado: 2.500)
        Clock div2 = Clock.createGenerated(15, "div2", c10, 20000, new double[]{0, 10000}, 0);
        assertRelation(c10, false, div2, false, 10, 0);
        assertRelation(div2, false, c10, false, 10, 0);
        assertRelation(clock(16, 2.5, 0, 1.25), false, div2, false, 2.5, 0);
        // A non-integer ratio found by its common period: 10 ns and 6.667 ns (3 x 6.667 = 2 x 10)
        assertRelation(c10, false, clock(17, 20.0 / 3, 0, 10.0 / 3), false, 10.0 / 3, 0);
    }

    @Test
    public void testMulticycle() {
        List<Clock> clocks = Arrays.asList(clock(0, 4, 0, 2), clock(1, 6, 0, 3));
        ClockRelations rel = new ClockRelations(clocks);
        // set_multicycle_path 3 -setup; set_multicycle_path 2 -hold -end (Vivado: 12.000 / 0.000)
        Assertions.assertEquals(12000, rel.getSetup(0, 0, 3, false), 1e-6);
        Assertions.assertEquals(8000, rel.getHold(0, 0, 3, false, 0, false), 1e-6);
        Assertions.assertEquals(0, rel.getHold(0, 0, 3, false, 2, true), 1e-6);
        // 4 ns -> 6 ns: setup 2; -setup -start 2 moves the launch back one launch period (Vivado: 6.000),
        // -hold -start 1 brings hold back to 0
        Assertions.assertEquals(2000, rel.getSetup(0, 2), 1e-6);
        Assertions.assertEquals(6000, rel.getSetup(0, 2, 2, true), 1e-6);
        Assertions.assertEquals(4000, rel.getHold(0, 2, 2, true, 0, false), 1e-6);
        Assertions.assertEquals(0, rel.getHold(0, 2, 2, true, 1, false), 1e-6);
        // Setup 2 only: default hold one period later (Vivado: 10.000 / 5.000 for a 5 ns clock)
        Assertions.assertEquals(8000, rel.getSetup(0, 0, 2, false), 1e-6);
        Assertions.assertEquals(4000, rel.getHold(0, 0, 2, false, 0, false), 1e-6);
    }

    @Test
    public void testFalsePairs() {
        TimingConstraints tc = TimingConstraints.parse((EDIFNetlist) null, Arrays.asList(
                "create_clock -period 4 -name clk1 [get_ports clk1]",
                "create_clock -period 6 -name clk2 [get_ports clk2]",
                "create_clock -period 8 -name clk4 [get_ports clk4]",
                "create_clock -period 7 -name clk4b -add [get_ports clk4]",
                "create_clock -period 3 -name clk5 [get_ports clk1]",
                "create_clock -period 5 -name clk6 -add [get_ports clk1]",
                "set_clock_groups -logically_exclusive -group [get_clocks clk4] -group [get_clocks clk4b]",
                "set_false_path -from [get_clocks clk6] -to [get_clocks clk1]",
                "set_false_path -setup -to [get_clocks clk5]"));
        ClockNetwork cn = ClockNetwork.build((EDIFNetlist) null, tc);
        // clk5 replaces clk1 on the port (no -add)
        Assertions.assertNull(cn.getClock("clk1"));
        int c2 = cn.getClock("clk2").getId();
        int c4 = cn.getClock("clk4").getId();
        int c4b = cn.getClock("clk4b").getId();
        int c5 = cn.getClock("clk5").getId();
        int c6 = cn.getClock("clk6").getId();
        ClockRelations rel = ClockRelations.build(cn);
        // Groups, both ways
        Assertions.assertTrue(rel.isFalseSetup(c4, c4b));
        Assertions.assertTrue(rel.isFalseHold(c4b, c4));
        Assertions.assertEquals(ClockRelations.REASON_GROUPS, rel.getFalseReason(c4, c4b));
        Assertions.assertFalse(rel.isFalseSetup(c4, c4));
        // Unrelated clocks are false, unless timed as in Vivado
        Assertions.assertTrue(rel.isFalseSetup(c2, c4));
        Assertions.assertEquals(ClockRelations.REASON_UNRELATED, rel.getFalseReason(c2, c4));
        // clk5 and clk6 share their port: related
        Assertions.assertFalse(rel.isFalseSetup(c6, c5) && rel.isFalseHold(c6, c5));
        // -setup false path to clk5: hold still timed
        Assertions.assertTrue(rel.isFalseSetup(c6, c5));
        Assertions.assertFalse(rel.isFalseHold(c6, c5));
        Assertions.assertTrue(rel.isFalseSetup(c5, c5));
        rel.setUnrelatedFalse(false);
        Assertions.assertFalse(rel.isFalseSetup(c2, c4));
        Assertions.assertTrue(rel.isFalseSetup(c4, c4b));
    }
}
