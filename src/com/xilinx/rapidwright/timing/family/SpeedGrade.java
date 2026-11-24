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

package com.xilinx.rapidwright.timing.family;

import java.io.Serializable;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.xilinx.rapidwright.device.Part;

/**
 * The speed grade of a part, e.g. -2 for an UltraScale+ part or -2MP for a Versal one. The grade
 * is kept without its leading dash: {@link #getName()} of -2MP is "2MP".
 */
public final class SpeedGrade implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;

    private SpeedGrade(String name) {
        this.name = name;
    }

    /**
     * Gets a speed grade from its name.
     * @param speedGrade The name, with or without a leading dash, e.g. -2MP or 2MP.
     * @return The speed grade, or null if the name is null or empty.
     */
    public static SpeedGrade of(String speedGrade) {
        if (speedGrade == null) {
            return null;
        }
        String name = speedGrade.startsWith("-") ? speedGrade.substring(1) : speedGrade;
        return name.isEmpty() ? null : new SpeedGrade(name);
    }

    /**
     * Gets the speed grade of a part.
     * @param part The part.
     * @return The part's speed grade, or null if the part has none.
     */
    public static SpeedGrade of(Part part) {
        return part == null ? null : of(part.getSpeed());
    }

    /** The (table, grade) pairs already warned about by {@link #resolve} */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /**
     * Gets the speed digit of a grade name: its leading digits, e.g. 2 for 2LHP.
     * @return The digits, or -1 if the name does not start with one.
     */
    private static int getSpeedDigit(String name) {
        int i = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            i++;
        }
        return i == 0 ? -1 : Integer.parseInt(name.substring(0, i));
    }

    /**
     * Gets the voltage class of a grade name: the letters after its speed digit, e.g. LHP for 2LHP
     * and the empty string for 2.
     */
    private static String getVoltageClass(String name) {
        int i = 0;
        while (i < name.length() && Character.isDigit(name.charAt(i))) {
            i++;
        }
        return name.substring(i);
    }

    private static int commonPrefixLength(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /**
     * Finds the listed grade nearest to a grade that has no data: a grade of
     * the same voltage class (the letters after the speed digit, e.g. L, LV or LHP) with the nearest
     * speed digit, the slower one on a tie; else a grade of the same speed digit, whose voltage class
     * shares the longest prefix with the grade's (then the shortest, then the first in alphabetical
     * order).
     * @param grade The grade name, with or without a leading dash, e.g. 2LE.
     * @param known The names of the grades that have data, without leading dashes.
     * @return The grade itself if listed, else the nearest one, or null if none has the same voltage
     * class or speed digit.
     */
    public static String nearest(String grade, Collection<String> known) {
        String name = grade.startsWith("-") ? grade.substring(1) : grade;
        if (known.contains(name)) {
            return name;
        }
        int digit = getSpeedDigit(name);
        String voltage = getVoltageClass(name);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String k : known) {
            int d = getSpeedDigit(k);
            if (d < 0 || digit < 0 || !getVoltageClass(k).equals(voltage)) continue;
            int distance = Math.abs(d - digit);
            if (distance < bestDistance || (distance == bestDistance && d < getSpeedDigit(best))) {
                best = k;
                bestDistance = distance;
            }
        }
        if (best != null || digit < 0) {
            return best;
        }
        int bestPrefix = -1;
        for (String k : known) {
            if (getSpeedDigit(k) != digit) continue;
            String v = getVoltageClass(k);
            int prefix = commonPrefixLength(v, voltage);
            if (best == null || prefix > bestPrefix
                    || (prefix == bestPrefix && (v.length() < getVoltageClass(best).length()
                    || (v.length() == getVoltageClass(best).length() && k.compareTo(best) < 0)))) {
                best = k;
                bestPrefix = prefix;
            }
        }
        return best;
    }

    /**
     * Resolves the grade whose data a table uses for a grade (see {@link #nearest}), with one warning
     * per table and grade when it is not listed.
     * @param grade The grade name, with or without a leading dash.
     * @param known The names of the grades that have data in the table, including its reference grade.
     * @param reference The reference grade of the table, used if no grade is near (factor 1).
     * @param table What the table holds, for the warning, e.g. "UltraScale+ delay data".
     * @return The grade name to use, without a leading dash.
     */
    public static String resolve(String grade, Collection<String> known, String reference, String table) {
        String name = grade.startsWith("-") ? grade.substring(1) : grade;
        if (known.contains(name)) {
            return name;
        }
        String use = nearest(name, known);
        if (WARNED.add(table + "|" + name)) {
            System.out.println("WARNING: No " + table + " for speed grade -" + name + ", using that of "
                    + (use == null ? "the reference speed grade -" + reference : "the nearest speed grade -" + use));
        }
        return use == null ? reference : use;
    }

    /**
     * @return The name of the speed grade without a leading dash, e.g. 2MP.
     */
    public String getName() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SpeedGrade && ((SpeedGrade) o).name.equals(name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    /**
     * @return The speed grade with a leading dash, e.g. -2MP.
     */
    @Override
    public String toString() {
        return "-" + name;
    }
}
