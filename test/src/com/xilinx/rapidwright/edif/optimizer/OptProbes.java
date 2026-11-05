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

package com.xilinx.rapidwright.edif.optimizer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.xilinx.rapidwright.design.NetType;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFPropertyValue;
import com.xilinx.rapidwright.edif.EDIFTools;

/**
 * Small hand-built netlists that exercise individual constant propagation and sweep rules. They
 * are used both as Vivado black-box probes (to learn what opt_design -propconst -sweep does) and as
 * JUnit inputs.
 */
public class OptProbes {

    public static final String PART = "xcvp1552-vsva3340-2MHP-i-S";

    /** Builder helper wrapping a netlist under construction. */
    public static class B {
        public final EDIFNetlist nl;
        public final EDIFCell top;

        public B(String name) {
            nl = EDIFTools.createNewNetlist(name, false);
            top = nl.getTopCell();
            EDIFTools.ensureCorrectPartInEDIF(nl, PART);
        }

        /** Creates a child cell definition in the work library. */
        public EDIFCell cell(String name) {
            return new EDIFCell(nl.getWorkLibrary(), name);
        }

        public EDIFNet net(EDIFCell c, String name) {
            if (name.equals("0")) {
                return EDIFTools.getStaticNet(NetType.GND, c, nl);
            }
            if (name.equals("1")) {
                return EDIFTools.getStaticNet(NetType.VCC, c, nl);
            }
            EDIFNet n = c.getNet(name);
            return n != null ? n : c.createNet(name);
        }

        /** Creates input ports on cell c, each connected to a net of the same name. */
        public void in(EDIFCell c, String... names) {
            port(c, EDIFDirection.INPUT, names);
        }

        public void out(EDIFCell c, String... names) {
            port(c, EDIFDirection.OUTPUT, names);
        }

        public void in(String... names) {
            in(top, names);
        }

        public void out(String... names) {
            out(top, names);
        }

        private void port(EDIFCell c, EDIFDirection dir, String... names) {
            for (String name : names) {
                EDIFPort p = c.createPort(name, dir, 1);
                net(c, name).createPortInst(p);
            }
        }

        /**
         * Creates an instance in cell c. Connections are "PIN=net" strings; net "0"/"1" are
         * GND/VCC; "PIN=" leaves the pin unconnected. Properties are "@KEY=value" strings.
         */
        public EDIFCellInst instIn(EDIFCell c, Object type, String name, String... conns) {
            EDIFCell t = type instanceof Unisim ? nl.getHDIPrimitive((Unisim) type)
                    : (EDIFCell) type;
            EDIFCellInst ci = c.createChildCellInst(name, t);
            for (String conn : conns) {
                int eq = conn.indexOf('=');
                String key = conn.substring(0, eq);
                String val = conn.substring(eq + 1);
                if (key.startsWith("@")) {
                    ci.addProperty(key.substring(1), val);
                } else if (!val.isEmpty()) {
                    net(c, val).createPortInst(key, ci);
                }
            }
            return ci;
        }

        public EDIFCellInst inst(Object type, String name, String... conns) {
            return instIn(top, type, name, conns);
        }

        /** Creates a LUT with the given INIT (hex digits) in cell c, inputs I0.. from ins. */
        public EDIFCellInst lut(EDIFCell c, String name, String init, String o, String... ins) {
            int size = ins.length;
            String[] conns = new String[size + 2];
            conns[0] = "@INIT=" + (1 << size) + "'h" + init;
            conns[1] = "O=" + o;
            for (int i = 0; i < size; i++) {
                conns[i + 2] = "I" + i + "=" + ins[i];
            }
            return instIn(c, Unisim.valueOf("LUT" + size), name, conns);
        }

        public EDIFCellInst lut(String name, String init, String o, String... ins) {
            return lut(top, name, init, o, ins);
        }

        public EDIFCellInst fdre(EDIFCell c, String name, String d, String ce, String r, String q,
                                 String init) {
            return instIn(c, Unisim.FDRE, name, "C=clk", "D=" + d, "CE=" + ce, "R=" + r, "Q=" + q,
                    "@INIT=1'b" + init);
        }

        public EDIFCellInst fdre(String name, String d, String ce, String r, String q,
                                 String init) {
            return fdre(top, name, d, ce, r, q, init);
        }
    }

    private static final Map<String, Supplier<EDIFNetlist>> PROBES = new LinkedHashMap<>();

    private static void probe(String name, Supplier<EDIFNetlist> s) {
        PROBES.put(name, s);
    }

    public static Map<String, Supplier<EDIFNetlist>> getProbes() {
        return PROBES;
    }

    static {
        // ---------------- LUTs ----------------
        probe("lut4_i2_gnd", () -> {
            B b = new B("lut4_i2_gnd");
            b.in("a", "b", "c");
            b.out("o");
            b.lut("L", "6996", "o", "a", "b", "0", "c");
            return b.nl;
        });
        probe("lut4_i0_vcc", () -> {
            B b = new B("lut4_i0_vcc");
            b.in("a", "b", "c");
            b.out("o");
            b.lut("L", "8E2B", "o", "1", "a", "b", "c");
            return b.nl;
        });
        probe("lut6_two_const", () -> {
            B b = new B("lut6_two_const");
            b.in("a", "b", "c", "d");
            b.out("o");
            b.lut("L", "123456789ABCDEF0", "o", "a", "1", "b", "c", "0", "d");
            return b.nl;
        });
        probe("lut_to_const_fanout", () -> {
            B b = new B("lut_to_const_fanout");
            b.in("clk", "a", "b", "c");
            b.out("o1", "o2", "o3", "o4");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y");
            b.out(sub, "z");
            b.lut(sub, "SL", "6", "z", "x", "y");
            b.lut("AND0", "8", "k", "0", "a");
            b.lut("L2", "E", "o1", "k", "b");
            b.fdre("FF", "k", "1", "0", "o2", "0");
            b.inst(sub, "u_sub", "x=k", "y=c", "z=o3");
            b.lut("L3", "2", "o4", "k");
            return b.nl;
        });
        probe("lut_const_to_output", () -> {
            B b = new B("lut_const_to_output");
            b.in("a", "b");
            b.out("o", "p");
            b.lut("AND0", "8", "o", "0", "a");
            b.lut("OR1", "E", "p", "1", "b");
            return b.nl;
        });
        probe("lut_init_const", () -> {
            B b = new B("lut_init_const");
            b.in("a", "b");
            b.out("o", "p");
            b.lut("Z", "0", "k", "a", "b");
            b.lut("U", "6", "o", "k", "b");
            b.lut("F", "F", "p", "a", "b");
            return b.nl;
        });
        probe("lut_to_buf", () -> {
            B b = new B("lut_to_buf");
            b.in("a", "b", "c");
            b.out("o", "p");
            b.lut("LB", "8", "m", "a", "1");
            b.lut("L2", "6", "o", "m", "b");
            b.lut("L3", "E", "p", "m", "c");
            return b.nl;
        });
        probe("lut_to_buf_out", () -> {
            B b = new B("lut_to_buf_out");
            b.in("a");
            b.out("o");
            b.lut("LB", "8", "o", "a", "1");
            return b.nl;
        });
        probe("lut_to_inv", () -> {
            B b = new B("lut_to_inv");
            b.in("a", "b");
            b.out("o");
            b.lut("LI", "7", "m", "a", "1");
            b.lut("L2", "6", "o", "m", "b");
            return b.nl;
        });
        probe("lut_dontcare", () -> {
            B b = new B("lut_dontcare");
            b.in("a", "b", "c");
            b.out("o");
            b.lut("L", "66", "o", "a", "b", "c");
            return b.nl;
        });
        probe("lut_dup_pins", () -> {
            B b = new B("lut_dup_pins");
            b.in("a", "b");
            b.out("o", "p");
            b.lut("X", "6", "o", "a", "a");
            b.lut("Y", "E8", "p", "a", "b", "a");
            return b.nl;
        });
        probe("lut_undriven", () -> {
            B b = new B("lut_undriven");
            b.in("a", "b");
            b.out("o", "p");
            b.inst(Unisim.LUT2, "U1", "@INIT=4'h6", "I0=a", "I1=", "O=o");
            b.inst(Unisim.LUT2, "U2", "@INIT=4'h6", "I0=b", "I1=floating", "O=p");
            return b.nl;
        });
        probe("lut_chain", () -> {
            B b = new B("lut_chain");
            b.in("a", "b", "c", "d", "e");
            b.out("o");
            b.lut("C0", "8", "n0", "0", "a");
            b.lut("C1", "E", "n1", "n0", "b");
            b.lut("C2", "8", "n2", "n1", "c");
            b.lut("C3", "6", "n3", "n2", "d");
            b.lut("C4", "1", "n4", "n0", "n3");
            b.lut("C5", "6", "o", "n4", "e");
            return b.nl;
        });
        probe("lut1_const", () -> {
            B b = new B("lut1_const");
            b.in("a");
            b.out("o", "p");
            b.lut("B1", "2", "m", "0");
            b.lut("L2", "6", "o", "m", "a");
            b.lut("I1", "1", "p", "1");
            return b.nl;
        });
        probe("lut_all_const", () -> {
            B b = new B("lut_all_const");
            b.in("a");
            b.out("o");
            b.lut("K", "6", "m", "0", "1");
            b.lut("L2", "8", "o", "m", "a");
            return b.nl;
        });
        probe("lut_shared_const_net", () -> {
            B b = new B("lut_shared_const_net");
            b.in("a", "b", "c", "d");
            b.out("o", "p", "q");
            b.lut("K", "8", "k", "0", "a");
            b.lut("L1", "E", "o", "k", "b");
            b.lut("L2", "6", "p", "c", "k");
            b.lut("L3", "96", "q", "k", "d", "a");
            return b.nl;
        });
        // ---------------- LUT6_2 ----------------
        probe("lut62_i5_vcc", () -> {
            B b = new B("lut62_i5_vcc");
            b.in("a", "b", "c", "d", "e");
            b.out("o5", "o6");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'hF0F0CCCC96966996", "I0=a", "I1=b", "I2=c",
                    "I3=d", "I4=e", "I5=1", "O5=o5", "O6=o6");
            return b.nl;
        });
        probe("lut62_i4_gnd", () -> {
            B b = new B("lut62_i4_gnd");
            b.in("a", "b", "c", "d");
            b.out("o5", "o6");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'hF0F0CCCC96966996", "I0=a", "I1=b", "I2=c",
                    "I3=d", "I4=0", "I5=1", "O5=o5", "O6=o6");
            return b.nl;
        });
        probe("lut62_o5_unused", () -> {
            B b = new B("lut62_o5_unused");
            b.in("a", "b", "c", "d", "e");
            b.out("o6");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'hF0F0CCCC96966996", "I0=a", "I1=b", "I2=c",
                    "I3=d", "I4=e", "I5=1", "O6=o6");
            return b.nl;
        });
        probe("lut62_o6_unused", () -> {
            B b = new B("lut62_o6_unused");
            b.in("a", "b", "c", "d", "e");
            b.out("o5");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'hF0F0CCCC96966996", "I0=a", "I1=b", "I2=c",
                    "I3=d", "I4=e", "I5=1", "O5=o5", "O6=");
            return b.nl;
        });
        probe("lut62_o5_const", () -> {
            B b = new B("lut62_o5_const");
            b.in("a", "b", "c", "d", "e");
            b.out("o5", "o6");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'hF0F0CCCC00000000", "I0=a", "I1=b", "I2=c",
                    "I3=d", "I4=e", "I5=1", "O5=o5", "O6=o6");
            return b.nl;
        });
        // ---------------- MUXF ----------------
        probe("muxf7_s_gnd", () -> {
            B b = new B("muxf7_s_gnd");
            b.in("a", "b", "c", "d");
            b.out("o");
            b.lut("LA", "6", "x0", "a", "b");
            b.lut("LB", "8", "x1", "c", "d");
            b.inst(Unisim.MUXF7, "M", "I0=x0", "I1=x1", "S=0", "O=o");
            return b.nl;
        });
        probe("muxf7_s_vcc", () -> {
            B b = new B("muxf7_s_vcc");
            b.in("a", "b", "c", "d");
            b.out("o");
            b.lut("LA", "6", "x0", "a", "b");
            b.lut("LB", "8", "x1", "c", "d");
            b.inst(Unisim.MUXF7, "M", "I0=x0", "I1=x1", "S=1", "O=o");
            return b.nl;
        });
        probe("muxf7_in_equal_const", () -> {
            B b = new B("muxf7_in_equal_const");
            b.in("s", "a");
            b.out("o");
            b.inst(Unisim.MUXF7, "M", "I0=0", "I1=0", "S=s", "O=m");
            b.lut("L", "E", "o", "m", "a");
            return b.nl;
        });
        probe("muxf7_in_opposite_const", () -> {
            B b = new B("muxf7_in_opposite_const");
            b.in("s", "a");
            b.out("o");
            b.inst(Unisim.MUXF7, "M", "I0=0", "I1=1", "S=s", "O=m");
            b.lut("L", "6", "o", "m", "a");
            return b.nl;
        });
        probe("muxf7_one_const", () -> {
            B b = new B("muxf7_one_const");
            b.in("s", "a", "b");
            b.out("o");
            b.lut("LA", "6", "x0", "a", "b");
            b.inst(Unisim.MUXF7, "M", "I0=x0", "I1=0", "S=s", "O=o");
            return b.nl;
        });
        probe("muxf8_s_gnd", () -> {
            B b = new B("muxf8_s_gnd");
            b.in("a", "b", "c", "d", "e", "f", "s");
            b.out("o");
            b.lut("LA", "6", "x0", "a", "b");
            b.lut("LB", "8", "x1", "c", "d");
            b.lut("LC", "6", "x2", "a", "e");
            b.lut("LD", "8", "x3", "f", "d");
            b.inst(Unisim.MUXF7, "M0", "I0=x0", "I1=x1", "S=s", "O=y0");
            b.inst(Unisim.MUXF7, "M1", "I0=x2", "I1=x3", "S=s", "O=y1");
            b.inst(Unisim.MUXF8, "M8", "I0=y0", "I1=y1", "S=0", "O=o");
            return b.nl;
        });
        probe("muxf9_s_vcc", () -> {
            B b = new B("muxf9_s_vcc");
            b.in("a", "b", "c", "d", "s", "t");
            b.out("o");
            String[] x = new String[8];
            for (int i = 0; i < 8; i++) {
                x[i] = "x" + i;
                b.lut("L" + i, Integer.toHexString(i + 1), x[i], i % 2 == 0 ? "a" : "c",
                        i < 4 ? "b" : "d");
            }
            for (int i = 0; i < 4; i++) {
                b.inst(Unisim.MUXF7, "M7_" + i, "I0=" + x[2 * i], "I1=" + x[2 * i + 1], "S=s",
                        "O=y" + i);
            }
            b.inst(Unisim.MUXF8, "M8_0", "I0=y0", "I1=y1", "S=t", "O=z0");
            b.inst(Unisim.MUXF8, "M8_1", "I0=y2", "I1=y3", "S=t", "O=z1");
            b.inst(Unisim.MUXF9, "M9", "I0=z0", "I1=z1", "S=1", "O=o");
            return b.nl;
        });
        // ---------------- INV / BUF ----------------
        probe("inv_const", () -> {
            B b = new B("inv_const");
            b.in("a");
            b.out("o");
            b.inst(Unisim.INV, "I", "I=0", "O=m");
            b.lut("L", "8", "o", "m", "a");
            return b.nl;
        });
        probe("buf_nonconst", () -> {
            B b = new B("buf_nonconst");
            b.in("a", "b");
            b.out("o");
            b.inst(Unisim.BUF, "BF", "I=a", "O=m");
            b.lut("L", "6", "o", "m", "b");
            return b.nl;
        });
        probe("inv_nonconst", () -> {
            B b = new B("inv_nonconst");
            b.in("a", "b");
            b.out("o");
            b.inst(Unisim.INV, "IV", "I=a", "O=m");
            b.lut("L", "6", "o", "m", "b");
            return b.nl;
        });
        // ---------------- Flops ----------------
        probe("fdre_ce0", () -> {
            B b = new B("fdre_ce0");
            b.in("clk", "a", "r");
            b.out("q", "q1");
            b.fdre("F0", "a", "0", "r", "q", "0");
            b.fdre("F1", "a", "0", "r", "q1", "1");
            return b.nl;
        });
        probe("fdre_ce0_r0", () -> {
            B b = new B("fdre_ce0_r0");
            b.in("clk", "a");
            b.out("q", "q1");
            b.fdre("F0", "a", "0", "0", "q", "0");
            b.fdre("F1", "a", "0", "0", "q1", "1");
            return b.nl;
        });
        probe("fdre_d_eq_init", () -> {
            B b = new B("fdre_d_eq_init");
            b.in("clk", "ce", "r");
            b.out("q", "q1");
            b.fdre("F0", "0", "ce", "r", "q", "0");
            b.fdre("F1", "1", "ce", "0", "q1", "1");
            return b.nl;
        });
        probe("fdre_d_eq_init_r0", () -> {
            B b = new B("fdre_d_eq_init_r0");
            b.in("clk", "ce");
            b.out("q");
            b.fdre("F0", "0", "ce", "0", "q", "0");
            return b.nl;
        });
        probe("fdre_d_ne_init", () -> {
            B b = new B("fdre_d_ne_init");
            b.in("clk", "ce");
            b.out("q", "q1");
            b.fdre("F0", "1", "ce", "0", "q", "0");
            b.fdre("F1", "0", "1", "0", "q1", "1");
            return b.nl;
        });
        probe("fdre_r1", () -> {
            B b = new B("fdre_r1");
            b.in("clk", "a", "ce");
            b.out("q", "q1");
            b.fdre("F0", "a", "ce", "1", "q", "0");
            b.fdre("F1", "a", "ce", "1", "q1", "1");
            return b.nl;
        });
        probe("fdse_s1", () -> {
            B b = new B("fdse_s1");
            b.in("clk", "a", "ce");
            b.out("q", "q1", "q2");
            b.inst(Unisim.FDSE, "F0", "C=clk", "D=a", "CE=ce", "S=1", "Q=q", "@INIT=1'b1");
            b.inst(Unisim.FDSE, "F1", "C=clk", "D=a", "CE=0", "S=0", "Q=q1", "@INIT=1'b1");
            b.inst(Unisim.FDSE, "F2", "C=clk", "D=1", "CE=ce", "S=0", "Q=q2", "@INIT=1'b1");
            return b.nl;
        });
        probe("fdce_clr1", () -> {
            B b = new B("fdce_clr1");
            b.in("clk", "a", "ce", "clr");
            b.out("q", "q1", "q2");
            b.inst(Unisim.FDCE, "F0", "C=clk", "D=a", "CE=ce", "CLR=1", "Q=q", "@INIT=1'b0");
            b.inst(Unisim.FDCE, "F1", "C=clk", "D=a", "CE=0", "CLR=clr", "Q=q1", "@INIT=1'b0");
            b.inst(Unisim.FDCE, "F2", "C=clk", "D=0", "CE=ce", "CLR=clr", "Q=q2", "@INIT=1'b0");
            return b.nl;
        });
        probe("fdpe_pre1", () -> {
            B b = new B("fdpe_pre1");
            b.in("clk", "a", "ce", "pre");
            b.out("q", "q1", "q2");
            b.inst(Unisim.FDPE, "F0", "C=clk", "D=a", "CE=ce", "PRE=1", "Q=q", "@INIT=1'b1");
            b.inst(Unisim.FDPE, "F1", "C=clk", "D=a", "CE=0", "PRE=pre", "Q=q1", "@INIT=1'b1");
            b.inst(Unisim.FDPE, "F2", "C=clk", "D=1", "CE=ce", "PRE=pre", "Q=q2", "@INIT=1'b1");
            return b.nl;
        });
        probe("fdre_c_const", () -> {
            B b = new B("fdre_c_const");
            b.in("a", "ce");
            b.out("q");
            b.inst(Unisim.FDRE, "F0", "C=0", "D=a", "CE=ce", "R=0", "Q=q", "@INIT=1'b0");
            return b.nl;
        });
        probe("fdre_inverted", () -> {
            B b = new B("fdre_inverted");
            b.in("clk", "a", "ce");
            b.out("q", "q1", "q2");
            b.inst(Unisim.FDRE, "F0", "C=clk", "D=a", "CE=0", "R=1", "Q=q", "@INIT=1'b0",
                    "@IS_R_INVERTED=1'b1");
            b.inst(Unisim.FDRE, "F1", "C=clk", "D=1", "CE=ce", "R=0", "Q=q1", "@INIT=1'b0",
                    "@IS_D_INVERTED=1'b1");
            b.inst(Unisim.FDRE, "F2", "C=clk", "D=a", "CE=ce", "R=0", "Q=q2", "@INIT=1'b0",
                    "@IS_R_INVERTED=1'b1");
            return b.nl;
        });
        probe("ff_chain", () -> {
            B b = new B("ff_chain");
            b.in("clk", "a");
            b.out("o");
            b.fdre("F0", "0", "1", "0", "q0", "0");
            b.fdre("F1", "q0", "1", "0", "q1", "0");
            b.fdre("F2", "q1", "1", "0", "q2", "0");
            b.lut("L", "6", "o", "q2", "a");
            return b.nl;
        });
        probe("ff_chain_ne", () -> {
            B b = new B("ff_chain_ne");
            b.in("clk", "a");
            b.out("o");
            b.fdre("F0", "1", "1", "0", "q0", "0");
            b.fdre("F1", "q0", "1", "0", "q1", "1");
            b.lut("L", "6", "o", "q1", "a");
            return b.nl;
        });
        probe("ff_ce_from_const_lut", () -> {
            B b = new B("ff_ce_from_const_lut");
            b.in("clk", "a", "b");
            b.out("q");
            b.lut("K", "8", "ce", "0", "b");
            b.fdre("F0", "a", "ce", "0", "q", "0");
            return b.nl;
        });
        probe("ldce", () -> {
            B b = new B("ldce");
            b.in("g", "a", "clr");
            b.out("q", "q1");
            b.inst(Unisim.LDCE, "L0", "G=g", "D=a", "GE=0", "CLR=clr", "Q=q", "@INIT=1'b0");
            b.inst(Unisim.LDCE, "L1", "G=g", "D=a", "GE=1", "CLR=1", "Q=q1", "@INIT=1'b0");
            return b.nl;
        });
        // ---------------- Sweep ----------------
        probe("sweep_unloaded", () -> {
            B b = new B("sweep_unloaded");
            b.in("clk", "a", "b", "c");
            b.out("o");
            b.lut("L0", "6", "o", "a", "b");
            b.lut("D0", "8", "d0", "a", "c");
            b.lut("D1", "6", "d1", "d0", "b");
            b.fdre("DF", "d1", "1", "0", "dq", "0");
            b.inst(Unisim.LUT2, "D2", "@INIT=4'h6", "I0=a", "I1=b");
            return b.nl;
        });
        probe("sweep_loop", () -> {
            B b = new B("sweep_loop");
            b.in("clk", "a", "b");
            b.out("o");
            b.lut("L0", "6", "o", "a", "b");
            b.lut("X0", "6", "x0", "x1", "a");
            b.fdre("X1", "x0", "1", "0", "x1", "0");
            return b.nl;
        });
        probe("sweep_unused_static", () -> {
            B b = new B("sweep_unused_static");
            b.in("a", "b");
            b.out("o");
            b.lut("L0", "6", "o", "a", "b");
            b.inst(Unisim.GND, "GX", "G=gx");
            b.inst(Unisim.VCC, "VX", "P=vx");
            return b.nl;
        });
        probe("sweep_half_used_ff", () -> {
            B b = new B("sweep_half_used_ff");
            b.in("clk", "a", "b");
            b.out("o");
            b.lut("L0", "6", "d", "a", "b");
            b.fdre("F0", "d", "1", "0", "q0", "0");
            b.fdre("F1", "d", "1", "0", "o", "0");
            return b.nl;
        });
        // ---------------- Attributes ----------------
        probe("dont_touch_cell", () -> {
            B b = new B("dont_touch_cell");
            b.in("a", "b", "c");
            b.out("o", "p");
            b.lut("DT", "6", "o", "a", "0").addProperty("DONT_TOUCH", "TRUE");
            b.lut("DU", "6", "du", "a", "b").addProperty("DONT_TOUCH", "TRUE");
            b.lut("DK", "8", "p", "0", "c").addProperty("dont_touch", "true");
            return b.nl;
        });
        probe("dont_touch_net", () -> {
            B b = new B("dont_touch_net");
            b.in("a", "b", "c");
            b.out("o", "p");
            b.lut("K", "8", "k", "0", "a");
            b.net(b.top, "k").addProperty("DONT_TOUCH", "TRUE");
            b.lut("L", "E", "o", "k", "b");
            b.lut("U", "6", "u", "a", "c");
            b.net(b.top, "u").addProperty("DONT_TOUCH", "TRUE");
            b.lut("P", "6", "p", "b", "c");
            return b.nl;
        });
        probe("mark_debug_net", () -> {
            B b = new B("mark_debug_net");
            b.in("a", "b", "c");
            b.out("o");
            b.lut("K", "8", "k", "0", "a");
            b.net(b.top, "k").addProperty("MARK_DEBUG", "TRUE");
            b.lut("L", "E", "o", "k", "b");
            b.lut("U", "6", "u", "a", "c");
            b.net(b.top, "u").addProperty("mark_debug", "true");
            return b.nl;
        });
        probe("keep_attrs", () -> {
            B b = new B("keep_attrs");
            b.in("a", "b", "c");
            b.out("o", "p");
            b.lut("KC", "8", "o", "0", "a").addProperty("KEEP", "TRUE");
            b.lut("KU", "6", "ku", "a", "b").addProperty("KEEP", "TRUE");
            b.lut("K", "8", "k", "0", "c");
            b.net(b.top, "k").addProperty("KEEP", "TRUE");
            b.lut("L", "E", "p", "k", "b");
            b.lut("U", "6", "u", "a", "c");
            b.net(b.top, "u").addProperty("KEEP", "TRUE");
            return b.nl;
        });
        // ---------------- Hierarchy ----------------
        probe("hier_const_in_shared", () -> {
            B b = new B("hier_const_in_shared");
            b.in("a", "b", "c");
            b.out("o0", "o1");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y");
            b.out(sub, "z");
            b.lut(sub, "SL", "6", "m", "x", "y");
            b.lut(sub, "SM", "8", "z", "m", "y");
            b.inst(sub, "u0", "x=0", "y=a", "z=o0");
            b.inst(sub, "u1", "x=b", "y=c", "z=o1");
            return b.nl;
        });
        probe("hier_const_in_same", () -> {
            B b = new B("hier_const_in_same");
            b.in("a", "b");
            b.out("o0", "o1");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y");
            b.out(sub, "z");
            b.lut(sub, "SL", "6", "z", "x", "y");
            b.inst(sub, "u0", "x=0", "y=a", "z=o0");
            b.inst(sub, "u1", "x=0", "y=b", "z=o1");
            return b.nl;
        });
        probe("hier_const_out", () -> {
            B b = new B("hier_const_out");
            b.in("a", "b");
            b.out("o");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x");
            b.out(sub, "z", "w");
            b.lut(sub, "SL", "8", "z", "x", "0");
            b.lut(sub, "SW", "1", "w", "x");
            b.inst(sub, "u0", "x=a", "z=k", "w=wq");
            b.lut("L", "6", "m", "k", "b");
            b.lut("L2", "6", "o", "m", "wq");
            return b.nl;
        });
        probe("hier_feedthrough", () -> {
            B b = new B("hier_feedthrough");
            b.in("a", "b");
            b.out("o", "p");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x");
            b.out(sub, "z");
            sub.getNet("x").createPortInst(sub.getPort("z"));
            b.inst(sub, "u0", "x=0", "z=k");
            b.lut("L", "E", "o", "k", "a");
            b.inst(sub, "u1", "x=b", "z=p");
            return b.nl;
        });
        probe("hier_dangling", () -> {
            B b = new B("hier_dangling");
            b.in("a", "b", "c");
            b.out("o");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y");
            b.out(sub, "z", "w");
            b.lut(sub, "SZ", "6", "z", "x", "y");
            b.lut(sub, "SW", "8", "w", "x", "y");
            b.inst(sub, "u0", "x=a", "y=b", "z=o", "w=unused");
            return b.nl;
        });
        probe("hier_empty", () -> {
            B b = new B("hier_empty");
            b.in("a", "b", "c");
            b.out("o");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y");
            b.out(sub, "z");
            b.lut(sub, "SZ", "6", "z", "x", "y");
            b.inst(sub, "u0", "x=a", "y=b", "z=unused");
            b.lut("L", "6", "o", "a", "c");
            return b.nl;
        });
        probe("hier_nested", () -> {
            B b = new B("hier_nested");
            b.in("a", "b", "c");
            b.out("o0", "o1");
            EDIFCell leaf = b.cell("leafc");
            b.in(leaf, "x", "y");
            b.out(leaf, "z");
            b.lut(leaf, "LL", "6", "z", "x", "y");
            EDIFCell mid = b.cell("mid");
            b.in(mid, "p", "q");
            b.out(mid, "r");
            b.instIn(mid, leaf, "l0", "x=p", "y=q", "z=r");
            b.inst(mid, "m0", "p=0", "q=a", "r=o0");
            b.inst(mid, "m1", "p=b", "q=c", "r=o1");
            return b.nl;
        });
        probe("hier_dont_touch", () -> {
            B b = new B("hier_dont_touch");
            b.in("a", "b");
            b.out("o0", "o1");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y");
            b.out(sub, "z");
            b.lut(sub, "SL", "6", "z", "x", "y");
            EDIFCell sub2 = b.cell("sub2");
            b.in(sub2, "x", "y");
            b.out(sub2, "z");
            b.lut(sub2, "SL", "6", "z", "x", "y");
            b.inst(sub, "u0", "x=0", "y=a", "z=o0").addProperty("DONT_TOUCH", "TRUE");
            b.inst(sub2, "u1", "x=0", "y=b", "z=o1").addProperty("KEEP_HIERARCHY", "TRUE");
            return b.nl;
        });
        probe("hier_dont_touch_unused", () -> {
            B b = new B("hier_dont_touch_unused");
            b.in("a", "b");
            b.out("o");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y");
            b.out(sub, "z");
            b.lut(sub, "SL", "6", "z", "x", "y");
            b.inst(sub, "u0", "x=a", "y=b", "z=unused").addProperty("DONT_TOUCH", "TRUE");
            b.lut("L", "6", "o", "a", "b");
            return b.nl;
        });
        probe("hier_const_pin_from_parent_gnd", () -> {
            B b = new B("hier_const_pin_from_parent_gnd");
            b.in("a", "b");
            b.out("o");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y", "k");
            b.out(sub, "z");
            b.lut(sub, "SL", "96", "z", "x", "y", "k");
            b.inst(sub, "u0", "x=a", "y=b", "k=0", "z=o");
            return b.nl;
        });
        // ---------------- Hard blocks / misc ----------------
        probe("unloaded_hard", () -> {
            B b = new B("unloaded_hard");
            b.in("clk", "a", "b");
            b.out("o");
            b.lut("L", "6", "o", "a", "b");
            b.inst(Unisim.BUFGCE, "BG", "I=clk", "CE=1", "O=gclk");
            b.inst(Unisim.SRL16E, "SR", "CLK=clk", "D=a", "CE=1", "A0=0", "A1=0", "A2=0",
                    "A3=0", "Q=srq", "@INIT=16'h0000");
            return b.nl;
        });
        probe("srl_const", () -> {
            B b = new B("srl_const");
            b.in("clk", "a", "b");
            b.out("o", "p");
            b.inst(Unisim.SRL16E, "SR0", "CLK=clk", "D=a", "CE=0", "A0=1", "A1=0", "A2=0",
                    "A3=0", "Q=o", "@INIT=16'h0002");
            b.inst(Unisim.SRLC32E, "SR1", "CLK=clk", "D=b", "CE=1", "A[0]=0", "A[1]=0",
                    "A[2]=0", "A[3]=0", "A[4]=0", "Q=p", "Q31=", "@INIT=32'h00000000");
            return b.nl;
        });
    }

    static {
        // ---------------- v2: inverters / buffers without constants ----------------
        probe("v2_lut1_inv_plain", () -> {
            B b = new B("v2_lut1_inv_plain");
            b.in("a", "b");
            b.out("o");
            b.lut("IV", "1", "m", "a");
            b.lut("L", "6", "o", "m", "b");
            return b.nl;
        });
        probe("v2_lut1_buf_plain", () -> {
            B b = new B("v2_lut1_buf_plain");
            b.in("a", "b");
            b.out("o");
            b.lut("BF", "2", "m", "a");
            b.lut("L", "6", "o", "m", "b");
            return b.nl;
        });
        probe("v2_inv_to_ff", () -> {
            B b = new B("v2_inv_to_ff");
            b.in("clk", "a", "b");
            b.out("q", "q1");
            b.lut("IV", "1", "m", "a");
            b.fdre("F", "m", "1", "0", "q", "0");
            b.inst(Unisim.INV, "IP", "I=b", "O=n");
            b.fdre("F1", "n", "1", "0", "q1", "0");
            return b.nl;
        });
        probe("v2_inv_to_port", () -> {
            B b = new B("v2_inv_to_port");
            b.in("a");
            b.out("o");
            b.lut("IV", "1", "o", "a");
            return b.nl;
        });
        probe("v2_inv_to_multi", () -> {
            B b = new B("v2_inv_to_multi");
            b.in("clk", "a", "b", "c");
            b.out("o", "p", "q");
            b.lut("IV", "1", "m", "a");
            b.lut("L1", "6", "o", "m", "b");
            b.lut("L2", "8", "p", "m", "c");
            b.fdre("F", "m", "1", "0", "q", "0");
            return b.nl;
        });
        probe("v2_inv_two_luts", () -> {
            B b = new B("v2_inv_two_luts");
            b.in("a", "b", "c");
            b.out("o", "p");
            b.lut("IV", "1", "m", "a");
            b.lut("L1", "6", "o", "m", "b");
            b.lut("L2", "8", "p", "m", "c");
            return b.nl;
        });
        probe("v2_inv_to_lut6", () -> {
            B b = new B("v2_inv_to_lut6");
            b.in("a", "b", "c", "d", "e", "f");
            b.out("o");
            b.lut("IV", "1", "m", "a");
            b.lut("L", "123456789ABCDEF0", "o", "b", "c", "m", "d", "e", "f");
            return b.nl;
        });
        probe("v2_inv_to_lut62", () -> {
            B b = new B("v2_inv_to_lut62");
            b.in("a", "b", "c", "d", "e");
            b.out("o5", "o6");
            b.lut("IV", "1", "m", "a");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'hF0F0CCCC96966996", "I0=m", "I1=b", "I2=c",
                    "I3=d", "I4=e", "I5=1", "O5=o5", "O6=o6");
            return b.nl;
        });
        probe("v2_inv_same_var", () -> {
            B b = new B("v2_inv_same_var");
            b.in("a", "b");
            b.out("o");
            b.lut("IV", "1", "m", "a");
            b.lut("L", "E8", "o", "a", "m", "b");
            return b.nl;
        });
        probe("v2_inv_hier_out", () -> {
            B b = new B("v2_inv_hier_out");
            b.in("a", "b");
            b.out("o");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x");
            b.out(sub, "z");
            b.lut(sub, "IV", "1", "z", "x");
            b.inst(sub, "u0", "x=a", "z=m");
            b.lut("L", "6", "o", "m", "b");
            return b.nl;
        });
        probe("v2_inv_hier_in", () -> {
            B b = new B("v2_inv_hier_in");
            b.in("a", "b");
            b.out("o");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x", "y");
            b.out(sub, "z");
            b.lut(sub, "L", "6", "z", "x", "y");
            b.lut("IV", "1", "m", "a");
            b.inst(sub, "u0", "x=m", "y=b", "z=o");
            return b.nl;
        });
        probe("v2_buf_hier", () -> {
            B b = new B("v2_buf_hier");
            b.in("a", "b");
            b.out("o", "p");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "x");
            b.out(sub, "z");
            b.lut(sub, "BF", "2", "z", "x");
            b.inst(sub, "u0", "x=a", "z=m");
            b.lut("L", "6", "o", "m", "b");
            b.inst(sub, "u1", "x=b", "z=p");
            return b.nl;
        });
        probe("v2_inv_dont_touch_load", () -> {
            B b = new B("v2_inv_dont_touch_load");
            b.in("a", "b");
            b.out("o");
            b.lut("IV", "1", "m", "a");
            b.lut("L", "6", "o", "m", "b").addProperty("DONT_TOUCH", "TRUE");
            return b.nl;
        });
        probe("v2_inv_dont_touch", () -> {
            B b = new B("v2_inv_dont_touch");
            b.in("a", "b");
            b.out("o");
            b.lut("IV", "1", "m", "a").addProperty("DONT_TOUCH", "TRUE");
            b.lut("L", "6", "o", "m", "b");
            return b.nl;
        });
        probe("v2_lut62_both_const_in", () -> {
            B b = new B("v2_lut62_both_const_in");
            b.in("a", "b", "c", "d");
            b.out("o5", "o6");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'hE8E8FEE896966996", "I0=0", "I1=a", "I2=b",
                    "I3=c", "I4=d", "I5=1", "O5=o5", "O6=o6");
            return b.nl;
        });
        probe("v2_lut62_dontcare", () -> {
            B b = new B("v2_lut62_dontcare");
            b.in("a", "b", "c", "d", "e");
            b.out("o5", "o6");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'hE8E8E8E896969696", "I0=a", "I1=b", "I2=c",
                    "I3=d", "I4=e", "I5=1", "O5=o5", "O6=o6");
            return b.nl;
        });
        probe("v2_lut62_inv_out", () -> {
            B b = new B("v2_lut62_inv_out");
            b.in("a", "b", "c", "d", "e", "f");
            b.out("o5", "o");
            b.inst(Unisim.LUT6_2, "L", "@INIT=64'h5555555596966996", "I0=a", "I1=b", "I2=c",
                    "I3=d", "I4=e", "I5=1", "O5=o5", "O6=m");
            b.lut("L2", "6", "o", "m", "f");
            return b.nl;
        });
        probe("v2_lut_dup_nonconst", () -> {
            B b = new B("v2_lut_dup_nonconst");
            b.in("a", "b", "c");
            b.out("o");
            b.lut("L", "1E5A", "o", "a", "b", "a", "c");
            return b.nl;
        });
        probe("v2_lut1_inv_chain", () -> {
            B b = new B("v2_lut1_inv_chain");
            b.in("a", "b");
            b.out("o");
            b.lut("IV1", "1", "m1", "a");
            b.lut("IV2", "1", "m2", "m1");
            b.lut("L", "6", "o", "m2", "b");
            return b.nl;
        });
        probe("v2_inv_to_lut6_2load", () -> {
            B b = new B("v2_inv_to_lut6_2load");
            b.in("a", "b", "c", "d", "e", "f", "g");
            b.out("o", "p");
            b.lut("IV", "1", "m", "a");
            b.lut("L", "123456789ABCDEF0", "o", "b", "c", "m", "d", "e", "f");
            b.lut("L2", "6", "p", "m", "g");
            return b.nl;
        });
        probe("v2_ff_to_inv_lut", () -> {
            B b = new B("v2_ff_to_inv_lut");
            b.in("clk", "a", "b");
            b.out("o");
            b.fdre("F", "a", "1", "0", "q", "0");
            b.lut("IV", "1", "m", "q");
            b.lut("L", "6", "o", "m", "b");
            return b.nl;
        });
        probe("v2_lut_mixed_load", () -> {
            B b = new B("v2_lut_mixed_load");
            b.in("clk", "a", "b", "c");
            b.out("o", "q");
            b.lut("IV", "7", "m", "a", "b");
            b.lut("L", "6", "o", "m", "c");
            b.fdre("F", "m", "1", "0", "q", "0");
            return b.nl;
        });
    }

    static {
        // ---------------- v3: inverter absorption into special pins, pull-back ----------------
        probe("v3_inv_ff_pins", () -> {
            B b = new B("v3_inv_ff_pins");
            b.in("clk", "a", "b", "c", "d", "e", "f", "x");
            b.out("q0", "q1", "q2", "q3", "q4", "q5");
            b.lut("IA", "1", "na", "a");
            b.lut("IB", "1", "nb", "b");
            b.lut("IC", "1", "nc", "c");
            b.lut("ID", "1", "nd", "d");
            b.lut("IE", "1", "ne", "e");
            b.lut("IF", "1", "nf", "f");
            b.fdre("F0", "x", "1", "na", "q0", "0");
            b.inst(Unisim.FDSE, "F1", "C=clk", "D=x", "CE=1", "S=nb", "Q=q1", "@INIT=1'b1");
            b.inst(Unisim.FDCE, "F2", "C=clk", "D=x", "CE=1", "CLR=nc", "Q=q2", "@INIT=1'b0");
            b.inst(Unisim.FDPE, "F3", "C=clk", "D=x", "CE=1", "PRE=nd", "Q=q3", "@INIT=1'b1");
            b.inst(Unisim.FDRE, "F4", "C=ne", "D=x", "CE=1", "R=0", "Q=q4", "@INIT=1'b0");
            b.fdre("F5", "x", "nf", "0", "q5", "0");
            return b.nl;
        });
        probe("v3_inv_r_and_d", () -> {
            B b = new B("v3_inv_r_and_d");
            b.in("clk", "a", "x");
            b.out("q0", "q1");
            b.lut("IV", "1", "n", "a");
            b.fdre("F0", "x", "1", "n", "q0", "0");
            b.fdre("F1", "n", "1", "0", "q1", "0");
            return b.nl;
        });
        probe("v3_inv_r_and_lut", () -> {
            B b = new B("v3_inv_r_and_lut");
            b.in("clk", "a", "x");
            b.out("q0", "o");
            b.lut("IV", "1", "n", "a");
            b.fdre("F0", "x", "1", "n", "q0", "0");
            b.lut("L", "6", "o", "n", "x");
            return b.nl;
        });
        probe("v3_pullback_ce", () -> {
            B b = new B("v3_pullback_ce");
            b.in("clk", "a", "b", "c", "x");
            b.out("q", "o");
            b.lut("X", "7", "nx", "a", "b");
            b.lut("IV", "1", "n", "nx");
            b.fdre("F", "x", "n", "0", "q", "0");
            b.lut("L", "6", "o", "nx", "c");
            return b.nl;
        });
        probe("v3_pullback_d", () -> {
            B b = new B("v3_pullback_d");
            b.in("clk", "a", "b", "c");
            b.out("q", "o");
            b.lut("X", "7", "nx", "a", "b");
            b.lut("IV", "1", "n", "nx");
            b.fdre("F", "n", "1", "0", "q", "0");
            b.lut("L", "6", "o", "nx", "c");
            return b.nl;
        });
        probe("v3_pullback_single", () -> {
            B b = new B("v3_pullback_single");
            b.in("clk", "a", "b");
            b.out("q");
            b.lut("X", "7", "nx", "a", "b");
            b.lut("IV", "1", "n", "nx");
            b.fdre("F", "n", "1", "0", "q", "0");
            return b.nl;
        });
        probe("v3_pullback_xff", () -> {
            B b = new B("v3_pullback_xff");
            b.in("clk", "a", "b", "x");
            b.out("q", "q2");
            b.lut("X", "7", "nx", "a", "b");
            b.lut("IV", "1", "n", "nx");
            b.fdre("F", "x", "n", "0", "q", "0");
            b.fdre("F2", "nx", "1", "0", "q2", "0");
            return b.nl;
        });
        probe("v3_pullback_port", () -> {
            B b = new B("v3_pullback_port");
            b.in("clk", "a", "b", "x");
            b.out("q", "nx");
            b.lut("X", "7", "nx", "a", "b");
            b.lut("IV", "1", "n", "nx");
            b.fdre("F", "x", "n", "0", "q", "0");
            return b.nl;
        });
        probe("v3_pullback_from_ff", () -> {
            B b = new B("v3_pullback_from_ff");
            b.in("clk", "a", "c", "x");
            b.out("q", "o");
            b.fdre("F0", "a", "1", "0", "nx", "0");
            b.lut("IV", "1", "n", "nx");
            b.fdre("F", "x", "n", "0", "q", "0");
            b.lut("L", "6", "o", "nx", "c");
            return b.nl;
        });
        probe("v3_pullback_two_iv", () -> {
            B b = new B("v3_pullback_two_iv");
            b.in("clk", "a", "b", "x", "y");
            b.out("q", "q2");
            b.lut("X", "7", "nx", "a", "b");
            b.lut("IV1", "1", "n1", "nx");
            b.lut("IV2", "1", "n2", "nx");
            b.fdre("F", "x", "n1", "0", "q", "0");
            b.fdre("F2", "y", "n2", "0", "q2", "0");
            return b.nl;
        });
        probe("v3_pullback_lut62", () -> {
            B b = new B("v3_pullback_lut62");
            b.in("clk", "a", "b", "c", "d", "e", "x");
            b.out("q", "o5");
            b.inst(Unisim.LUT6_2, "X", "@INIT=64'hF0F0CCCC96966996", "I0=a", "I1=b", "I2=c",
                    "I3=d", "I4=e", "I5=1", "O5=o5", "O6=nx");
            b.lut("IV", "1", "n", "nx");
            b.fdre("F", "x", "n", "0", "q", "0");
            return b.nl;
        });
        probe("v3_pullback_hier", () -> {
            B b = new B("v3_pullback_hier");
            b.in("clk", "a", "b", "c", "x");
            b.out("q", "o");
            EDIFCell sub = b.cell("sub");
            b.in(sub, "p", "r");
            b.out(sub, "z");
            b.lut(sub, "X", "7", "z", "p", "r");
            b.inst(sub, "u0", "p=a", "r=b", "z=nx");
            b.lut("IV", "1", "n", "nx");
            b.fdre("F", "x", "n", "0", "q", "0");
            b.lut("L", "6", "o", "nx", "c");
            return b.nl;
        });
        probe("v3_reduced_inv_to_ff", () -> {
            B b = new B("v3_reduced_inv_to_ff");
            b.in("clk", "a");
            b.out("q");
            b.lut("X", "7", "n", "a", "1");
            b.fdre("F", "n", "1", "0", "q", "0");
            return b.nl;
        });
        probe("v3_inv_r_dt", () -> {
            B b = new B("v3_inv_r_dt");
            b.in("clk", "a", "x");
            b.out("q");
            b.lut("IV", "1", "n", "a");
            b.fdre("F", "x", "1", "n", "q", "0").addProperty("DONT_TOUCH", "TRUE");
            return b.nl;
        });
        probe("v3_inv_chain_r", () -> {
            B b = new B("v3_inv_chain_r");
            b.in("clk", "a", "x");
            b.out("q");
            b.lut("IV1", "1", "n1", "a");
            b.lut("IV2", "1", "n2", "n1");
            b.fdre("F", "x", "1", "n2", "q", "0");
            return b.nl;
        });
        probe("v3_inv_chain_ce", () -> {
            B b = new B("v3_inv_chain_ce");
            b.in("clk", "a", "x");
            b.out("q");
            b.lut("IV1", "1", "n1", "a");
            b.lut("IV2", "1", "n2", "n1");
            b.fdre("F", "x", "n2", "0", "q", "0");
            return b.nl;
        });
        probe("v3_clr_pre_init", () -> {
            B b = new B("v3_clr_pre_init");
            b.in("clk", "a", "ce");
            b.out("q", "q1");
            b.inst(Unisim.FDCE, "F0", "C=clk", "D=a", "CE=ce", "CLR=1", "Q=q", "@INIT=1'b1");
            b.inst(Unisim.FDPE, "F1", "C=clk", "D=a", "CE=ce", "PRE=1", "Q=q1", "@INIT=1'b0");
            return b.nl;
        });
        probe("v3_dt_const_out", () -> {
            B b = new B("v3_dt_const_out");
            b.in("a", "b");
            b.out("o");
            b.lut("DT", "8", "k", "0", "a").addProperty("DONT_TOUCH", "TRUE");
            b.lut("L", "E", "o", "k", "b");
            return b.nl;
        });
        probe("v3_inv_lut_and_port", () -> {
            B b = new B("v3_inv_lut_and_port");
            b.in("a", "b");
            b.out("o", "n");
            b.lut("IV", "1", "n", "a");
            b.lut("L", "6", "o", "n", "b");
            return b.nl;
        });
        probe("v3_x_inv_lut", () -> {
            B b = new B("v3_x_inv_lut");
            b.in("a", "b", "c", "d");
            b.out("o", "p");
            b.lut("X", "7", "nx", "a", "b");
            b.lut("IV", "1", "n", "nx");
            b.lut("L1", "6", "o", "nx", "c");
            b.lut("L2", "8", "p", "n", "d");
            return b.nl;
        });
        probe("v5_r_and_two_d", () -> {
            B b = new B("v5_r_and_two_d");
            b.in("clk", "a", "x");
            b.out("q0", "q1", "q2");
            b.lut("IV", "1", "n", "a");
            b.fdre("F0", "x", "1", "n", "q0", "0");
            b.fdre("F1", "n", "1", "0", "q1", "0");
            b.fdre("F2", "n", "1", "0", "q2", "0");
            return b.nl;
        });
        probe("v5_r_and_one_ce", () -> {
            B b = new B("v5_r_and_one_ce");
            b.in("clk", "a", "x", "y");
            b.out("q0", "q1");
            b.lut("IV", "1", "n", "a");
            b.fdre("F0", "x", "1", "n", "q0", "0");
            b.fdre("F1", "y", "n", "0", "q1", "0");
            return b.nl;
        });
        probe("v5_r_and_two_ce", () -> {
            B b = new B("v5_r_and_two_ce");
            b.in("clk", "a", "x", "y");
            b.out("q0", "q1", "q2");
            b.lut("IV", "1", "n", "a");
            b.fdre("F0", "x", "1", "n", "q0", "0");
            b.fdre("F1", "y", "n", "0", "q1", "0");
            b.fdre("F2", "x", "n", "0", "q2", "0");
            return b.nl;
        });
        probe("v5_lut_and_one_ce", () -> {
            B b = new B("v5_lut_and_one_ce");
            b.in("clk", "a", "x", "y");
            b.out("o", "q1");
            b.lut("IV", "1", "n", "a");
            b.lut("L", "6", "o", "n", "x");
            b.fdre("F1", "y", "n", "0", "q1", "0");
            return b.nl;
        });
        probe("v5_lut_and_ce_and_d", () -> {
            B b = new B("v5_lut_and_ce_and_d");
            b.in("clk", "a", "x", "y");
            b.out("o", "q1", "q2");
            b.lut("IV", "1", "n", "a");
            b.lut("L", "6", "o", "n", "x");
            b.fdre("F1", "y", "n", "0", "q1", "0");
            b.fdre("F2", "n", "1", "0", "q2", "0");
            return b.nl;
        });
        probe("v5_srl_ce0", () -> {
            B b = new B("v5_srl_ce0");
            b.in("clk", "a", "b");
            b.out("o", "p");
            b.inst(Unisim.SRL16E, "SR0", "CLK=clk", "D=a", "CE=0", "A0=b", "A1=0", "A2=0",
                    "A3=0", "Q=o", "@INIT=16'h0000");
            b.inst(Unisim.SRL16E, "SR1", "CLK=clk", "D=1", "CE=b", "A0=a", "A1=0", "A2=0",
                    "A3=0", "Q=p", "@INIT=16'hFFFF");
            return b.nl;
        });
        probe("v4_keep_yes_unloaded", () -> {
            B b = new B("v4_keep_yes_unloaded");
            b.in("a", "b");
            b.out("o");
            b.lut("L", "6", "o", "a", "b");
            b.lut("KY", "8", "ky", "a", "b").addProperty("KEEP", "yes");
            b.lut("KT", "8", "kt", "a", "b").addProperty("KEEP", "TRUE");
            b.lut("KC", "8", "o2", "0", "b").addProperty("KEEP", "yes");
            return b.nl;
        });
        probe("v3_dsp_rst_inv", () -> {
            B b = new B("v3_dsp_rst_inv");
            b.in("clk", "a", "r", "s");
            b.out("p0");
            b.lut("IV", "1", "n", "r");
            b.lut("IV2", "1", "n2", "s");
            b.inst(Unisim.DSP58, "D", "CLK=clk", "A[0]=a", "B[0]=a", "RSTA=n", "RSTP=n",
                    "CEP=n2", "CEA2=1", "CEB2=1", "P[0]=p0");
            return b.nl;
        });
    }

    /** Renames the EDIF design of a netlist, keeping its top cell and properties. */
    private static void renameDesign(EDIFNetlist nl, String name) {
        EDIFDesign design = new EDIFDesign(name);
        design.setTopCell(nl.getTopCell());
        for (Map.Entry<String, EDIFPropertyValue> e
                : nl.getDesign().getPropertiesMap().entrySet()) {
            design.addProperty(e.getKey(), e.getValue());
        }
        nl.setDesign(design);
    }

    /**
     * Derives a probe from a synthesized netlist by tying top-level input port bits to constants.
     * @param synEdf Synthesized EDIF file.
     * @param name Name of the new probe (and its top cell).
     * @param ties Strings of the form "port[i]=0" or "port=1".
     * @return The modified netlist.
     */
    public static EDIFNetlist derive(Path synEdf, String name, String... ties) {
        EDIFNetlist nl = EDIFTools.readEdifFile(synEdf);
        EDIFCell top = nl.getTopCell();
        top.rename(name);
        renameDesign(nl, name);
        for (String tie : ties) {
            if (tie.startsWith("!")) {
                // Disconnect a top-level port from its net
                EDIFNet net = top.getInternalNet(tie.substring(1));
                if (net != null) {
                    for (EDIFPortInst pi : new ArrayList<>(net.getPortInsts())) {
                        if (pi.isTopLevelPort()) {
                            net.removePortInst(pi);
                            top.removeInternalPortMapEntry(pi.getName());
                        }
                    }
                }
                continue;
            }
            if (tie.startsWith("@")) {
                // Set a property on an instance: @inst:KEY=VAL
                int colon = tie.indexOf(':');
                int eq = tie.indexOf('=', colon);
                EDIFCellInst ci = top.getCellInst(tie.substring(1, colon));
                ci.addProperty(tie.substring(colon + 1, eq), tie.substring(eq + 1));
                continue;
            }
            String pin = tie.substring(0, tie.indexOf('='));
            NetType type = tie.endsWith("=0") ? NetType.GND : NetType.VCC;
            EDIFNet net = top.getInternalNet(pin);
            EDIFNet cnet = EDIFTools.getStaticNet(type, top, nl);
            if (net == null) {
                continue;
            }
            List<EDIFPortInst> pis = new ArrayList<>(net.getPortInsts());
            for (EDIFPortInst pi : pis) {
                net.removePortInst(pi);
                if (pi.isTopLevelPort()) {
                    // Leave the top-level port dangling on its own net
                    top.createNet(pin + "_unused").addPortInst(pi);
                } else {
                    cnet.addPortInst(pi);
                }
            }
            top.removeNet(net);
        }
        return nl;
    }

    /**
     * Derives a probe from a synthesized netlist by inserting a LUT1 inverter after each of the
     * named top-level input ports.
     */
    public static EDIFNetlist deriveInv(Path synEdf, String name, String... ports) {
        EDIFNetlist nl = EDIFTools.readEdifFile(synEdf);
        EDIFCell top = nl.getTopCell();
        top.rename(name);
        renameDesign(nl, name);
        EDIFCell lut1 = nl.getHDIPrimitive(Unisim.LUT1);
        int i = 0;
        for (String port : ports) {
            EDIFNet net = top.getInternalNet(port);
            EDIFPortInst topPi = null;
            for (EDIFPortInst pi : net.getPortInsts()) {
                if (pi.isTopLevelPort()) {
                    topPi = pi;
                }
            }
            net.removePortInst(topPi);
            EDIFNet in = top.createNet(port + "_inv_in");
            in.addPortInst(topPi);
            EDIFCellInst iv = top.createChildCellInst("INVP" + (i++), lut1);
            iv.addProperty("INIT", "2'h1");
            in.createPortInst("I0", iv);
            net.createPortInst("O", iv);
        }
        return nl;
    }

    private static void derived(Path synDir) {
        String[][] invDefs = {
            {"bram", "d_inv_bram_we", "we"},
            {"lutram", "d_inv_lutram_we", "we"},
            {"srl32", "d_inv_srl_ce", "ce"},
            {"cnt8", "d_inv_cnt_rst_ce", "rst", "ce"},
            {"add16r", "d_inv_add_a3", "a[3]"},
            {"uram", "d_inv_uram_we", "we"},
            {"mul16", "d_inv_mul_b0", "b[0]"},
        };
        for (String[] def : invDefs) {
            final String[] ports = java.util.Arrays.copyOfRange(def, 2, def.length);
            final Path edf = synDir.resolve("syn_" + def[0] + ".edf");
            probe(def[1], () -> deriveInv(edf, def[1], ports));
        }
        String[][] defs = {
            {"add16r", "d_add_lo0", "b[0]=0", "b[1]=0", "b[2]=0", "b[3]=0"},
            {"add16r", "d_add_hi1", "b[12]=1", "b[13]=1", "b[14]=1", "b[15]=1"},
            {"add16r", "d_add_const", "b[0]=1", "b[1]=0", "b[2]=0", "b[3]=0", "b[4]=0",
                "b[5]=0", "b[6]=0", "b[7]=0", "b[8]=0", "b[9]=0", "b[10]=0", "b[11]=0",
                "b[12]=0", "b[13]=0", "b[14]=0", "b[15]=0"},
            {"add16r", "d_add_a0b0", "a[0]=0", "b[0]=0", "a[1]=0", "b[1]=0"},
            {"cmp16", "d_cmp_const", "b[0]=1", "b[1]=0", "b[2]=1", "b[3]=1", "b[4]=0",
                "b[5]=0", "b[6]=0", "b[7]=0", "b[8]=1", "b[9]=0", "b[10]=0", "b[11]=0",
                "b[12]=0", "b[13]=0", "b[14]=0", "b[15]=0"},
            {"sub12", "d_sub_a0", "a[0]=0", "a[1]=0", "a[2]=0", "a[3]=0", "a[4]=0", "a[5]=0",
                "a[6]=0", "a[7]=0", "a[8]=0", "a[9]=0", "a[10]=0", "a[11]=0"},
            {"cnt8", "d_cnt_ce0", "ce=0"},
            {"cnt8", "d_cnt_rst1", "rst=1"},
            {"cnt8", "d_cnt_ce1", "ce=1"},
            {"srl32", "d_srl_ce0", "ce=0"},
            {"srl32", "d_srl_a0", "a[0]=0", "a[1]=0", "a[2]=0", "a[3]=0", "a[4]=0"},
            {"lutram", "d_lutram_we0", "we=0"},
            {"lutram", "d_lutram_ra0", "ra[0]=0", "ra[1]=0", "ra[2]=0", "ra[3]=0", "ra[4]=0"},
            {"mul16", "d_mul_b0", "b[0]=0", "b[1]=0", "b[2]=0", "b[3]=0", "b[4]=0", "b[5]=0",
                "b[6]=0", "b[7]=0", "b[8]=0", "b[9]=0", "b[10]=0", "b[11]=0", "b[12]=0",
                "b[13]=0", "b[14]=0", "b[15]=0"},
            {"mul16", "d_mul_b1", "b[0]=1"},
            {"bram", "d_bram_we0", "we=0"},
            {"uram", "d_uram_we0", "we=0"},
            {"mux16", "d_mux_s", "s[0]=0", "s[1]=1"},
            {"mux64", "d_mux64_s", "s[0]=0", "s[1]=1", "s[5]=0"},
            {"add8", "d_add8_b0", "b[0]=0", "b[1]=0", "b[2]=0"},
            {"add16r", "d4_add_hi_unused", "!s[8]", "!s[9]", "!s[10]", "!s[11]", "!s[12]",
                "!s[13]", "!s[14]", "!s[15]"},
            {"add16r", "d4_add_hi_unused_keep", "!s[8]", "!s[9]", "!s[10]", "!s[11]", "!s[12]",
                "!s[13]", "!s[14]", "!s[15]", "@s_reg[14]_i_2:KEEP=yes"},
            {"add16r", "d4_add_top_unused", "!s[15]", "!s[14]"},
            {"add16r", "d4_add_top_unused_keep", "!s[15]", "!s[14]",
                "@s_reg[14]_i_2:KEEP=yes"},
            {"cnt8", "d4_cnt_unused_keep", "!q[7]", "@q_reg[7]:KEEP=yes", "@q[7]_i_1:KEEP=yes"},
        };
        for (String[] def : defs) {
            final String[] ties = java.util.Arrays.copyOfRange(def, 2, def.length);
            final Path edf = synDir.resolve("syn_" + def[0] + ".edf");
            probe(def[1], () -> derive(edf, def[1], ties));
        }
    }

    /**
     * Probes whose Vivado result depends on link-time retargeting (MUXF/INV to LUT) or on
     * default properties Vivado adds to hand-instantiated hard blocks; excluded from
     * comparisons.
     */
    public static boolean isLinkDependent(String name) {
        return name.startsWith("muxf") || name.equals("v2_inv_to_ff")
                || name.equals("v3_dsp_rst_inv") || name.equals("hier_feedthrough");
    }

    /**
     * Writes the normalized leaf dumps of Vivado's results for all builder probes into one
     * file, used as JUnit expectations. Usage: -expected <out_file> <vivado_out_dir>...
     */
    private static void writeExpected(String[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String name : PROBES.keySet()) {
            if (isLinkDependent(name)) {
                continue;
            }
            for (int i = 2; i < args.length; i++) {
                Path gold = Paths.get(args[i]).resolve(name + ".ps.edf");
                if (Files.exists(gold)) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    com.xilinx.rapidwright.edif.compare.EDIFLeafNetlistComparator.dump(
                            EDIFTools.readEdifFile(gold), new java.io.PrintStream(bos));
                    sb.append("### ").append(name).append("\n").append(bos);
                    break;
                }
            }
        }
        Files.write(Paths.get(args[1]), sb.toString().getBytes());
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("-expected")) {
            writeExpected(args);
            return;
        }
        Path out = Paths.get(args[0]);
        Files.createDirectories(out);
        if (args.length > 2) {
            derived(Paths.get(args[2]));
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, Supplier<EDIFNetlist>> e : PROBES.entrySet()) {
            if (args.length > 1 && !e.getKey().matches(args[1])) {
                continue;
            }
            EDIFNetlist nl = e.getValue().get();
            nl.exportEDIF(out.resolve(e.getKey() + ".edf"));
            names.add(e.getKey());
        }
        Files.write(out.resolve("probes.txt"), names);
        System.out.println("Wrote " + names.size() + " probes to " + out);
    }
}
