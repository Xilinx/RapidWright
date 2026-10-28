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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A retiming certificate: for each register a retiming created, moved or
 * duplicated, the combinational point in the other netlist whose value it
 * holds on every cycle.
 *
 * In a file, one entry per line:
 *
 * <pre>
 * # forward retiming across lut_f: the new register holds what lut_f computes
 * IMP core/r_fwd = REF core/lut_f/O
 * # backward retiming: the old register equals the new logic's output
 * REF core/q_reg = IMP core/lut_g/O
 * </pre>
 *
 * The left side names a register, by full hierarchical instance name, in one
 * netlist; the right side names a leaf output pin in the other. The register
 * must equal, at every clock edge, the combinational function at that pin of
 * the other netlist's register outputs. A register's own {@code Q} is a valid
 * pin, which describes a duplicated or renamed register. Blank lines and lines
 * starting with {@code #} are ignored; names may not contain whitespace.
 *
 * The certificate is a hint, not a trusted fact: the checker proves every
 * entry, and a wrong one makes the run fail.
 */
public class RetimingMap {

    /** One of the two netlists being compared. */
    public enum Side {
        REF, IMP;

        /** @return The other netlist. */
        public Side other() {
            return this == REF ? IMP : REF;
        }
    }

    /** One certificate entry: a register and the pin that defines it. */
    public static class Entry {

        private final Side registerSide;

        private final String register;

        private final String definingInst;

        private final String definingPin;

        private final String source;

        Entry(Side registerSide, String register, String definingInst, String definingPin,
                String source) {
            this.registerSide = registerSide;
            this.register = register;
            this.definingInst = definingInst;
            this.definingPin = definingPin;
            this.source = source;
        }

        /** @return The netlist holding the register. */
        public Side getRegisterSide() {
            return registerSide;
        }

        /** @return The register's full hierarchical instance name. */
        public String getRegister() {
            return register;
        }

        /** @return The instance owning the defining pin, in the other netlist. */
        public String getDefiningInst() {
            return definingInst;
        }

        /** @return The defining pin's name on its instance, such as {@code O}. */
        public String getDefiningPin() {
            return definingPin;
        }

        /** @return The defining pin's full hierarchical name. */
        public String getDefiningPinKey() {
            return TerminalKey.forPin(definingInst, definingPin);
        }

        /** @return Where the entry came from, such as {@code map.txt:12}. */
        public String getSource() {
            return source;
        }

        @Override
        public String toString() {
            return registerSide + " " + register + " = " + registerSide.other() + " "
                    + getDefiningPinKey();
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /** Reads a certificate file. */
    public static RetimingMap read(Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new LecException("Cannot read retiming map '" + file + "': " + e.getMessage());
        }
        return parse(lines, file.toString());
    }

    /**
     * Parses certificate text.
     *
     * @param lines  The lines of the certificate.
     * @param source A name for error messages, such as the file name.
     */
    public static RetimingMap parse(List<String> lines, String source) {
        RetimingMap map = new RetimingMap();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String where = source + ":" + (i + 1);
            String[] tokens = line.split("\\s+");
            if (tokens.length != 5 || !tokens[2].equals("=")) {
                throw new LecException(where + ": expected '<REF|IMP> <register> = <REF|IMP> "
                        + "<instance>/<pin>', found '" + line + "'");
            }
            map.add(sideOf(tokens[0], where), tokens[1], sideOf(tokens[3], where), tokens[4],
                    where);
        }
        return map;
    }

    /**
     * Adds an entry.
     *
     * @param registerSide The netlist holding the register.
     * @param register     The register's full hierarchical instance name.
     * @param definingPin  The full hierarchical name of a leaf output pin in the
     *                     other netlist, such as {@code core/lut_f/O}.
     */
    public void define(Side registerSide, String register, String definingPin) {
        add(registerSide, register, registerSide.other(), definingPin, "entry " + register);
    }

    private void add(Side registerSide, String register, Side pinSide, String pin,
            String where) {
        if (pinSide == registerSide) {
            throw new LecException(where + ": a register must be defined by a pin in the other "
                    + "netlist, but both sides are " + registerSide);
        }
        int slash = pin.lastIndexOf('/');
        if (slash <= 0 || slash == pin.length() - 1) {
            throw new LecException(where + ": '" + pin + "' is not an <instance>/<pin> name");
        }
        String key = registerSide + " " + register;
        Entry previous = entries.get(key);
        if (previous != null) {
            throw new LecException(where + ": " + key + " is already defined at "
                    + previous.getSource());
        }
        entries.put(key, new Entry(registerSide, register, pin.substring(0, slash),
                pin.substring(slash + 1), where));
    }

    private static Side sideOf(String token, String where) {
        if (token.equals("REF")) {
            return Side.REF;
        }
        if (token.equals("IMP")) {
            return Side.IMP;
        }
        throw new LecException(where + ": expected REF or IMP, found '" + token + "'");
    }

    /** @return Every entry, in the order given. */
    public List<Entry> getEntries() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    /** @return The registers the certificate defines in one netlist. */
    public Set<String> registersOn(Side side) {
        Set<String> registers = new LinkedHashSet<>();
        for (Entry e : entries.values()) {
            if (e.getRegisterSide() == side) {
                registers.add(e.getRegister());
            }
        }
        return registers;
    }

    /** @return The defining pins that live in one netlist, by full name. */
    public Set<String> pinsOn(Side side) {
        Set<String> pins = new LinkedHashSet<>();
        for (Entry e : entries.values()) {
            if (e.getRegisterSide() != side) {
                pins.add(e.getDefiningPinKey());
            }
        }
        return pins;
    }
}
