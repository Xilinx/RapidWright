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

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.timing.ultrascaleplus.UltraScalePlusTimingFamily;
import com.xilinx.rapidwright.timing.versal.VersalTimingFamily;

public class TestInterconnectParams {

    /** The parameters of every generated data set (UltraScale+, Versal gen1 and gen2) at every corner it has */
    static List<InterconnectParams> getAllParams() {
        List<InterconnectParams> all = new ArrayList<>();
        for (Corner corner : Corner.values()) {
            addIfAny(all, () -> UltraScalePlusTimingFamily.getInterconnectParams(corner));
            for (VersalTimingFamily.Generation gen : VersalTimingFamily.Generation.values()) {
                addIfAny(all, () -> VersalTimingFamily.getInterconnectParams(gen, corner));
            }
        }
        return all;
    }

    private interface ParamsSupplier {
        InterconnectParams get();
    }

    private static void addIfAny(List<InterconnectParams> all, ParamsSupplier s) {
        try {
            all.add(s.get());
        } catch (UnsupportedOperationException e) {
            // No data at this corner
        }
    }

    /**
     * Parameters with every term set to something other than its default, so that a term that
     * {@link InterconnectParams#toBuilder(String)} does not carry shows in a round trip.
     */
    static InterconnectParams createFullParams() {
        return new InterconnectParams.Builder("synthetic interconnect v1", Corner.SLOW_MIN, "2",
                new String[]{"NONE", "A", "B"})
                .setColClasses(new String[]{"C0", "C1"}, new String[]{"^C0_", "^C1_"})
                .setRowClasses(new String[]{"R0"}, new String[]{"^R0_"})
                .setClassDelays(new float[]{0, 10.5f, 20.25f}, new float[]{0, 1.5f, 2}, new float[]{0, 3, 4})
                .setFanoutLoadDelays(new float[]{0, 5, 6})
                .setFanoutCrossingDelays(new float[]{7, 8, 9})
                .setCrossingWeights(new float[][]{{0, 0, 0}, {1, 2, 3}, {4, 5, 6}})
                .setTransitions(new int[]{1, 2}, new int[]{2, 1}, new float[]{11, -12})
                .setLookahead(30, 10, 10.5f, 3)
                .setPreRouteDelay(90)
                .setSpeedScales(new String[]{"1", "3"}, new float[]{1.2f, 0.9f})
                .setFanoutSpeedScales(new String[]{"1"}, new float[]{1.1f})
                .setDeviceScales(new String[]{"xcvu3p"}, new float[]{1.01f})
                .setPinDeltas(new String[]{"SLICEL.A1", "DSP48E2.A", "DSP48E2.A3"}, new short[]{15, -7, 2},
                        new String[]{"SLICEL.AQ"}, new short[]{3})
                .setTrackDeltas(new String[]{"C1", "R0"}, new String[]{"TILE_A.WIRE_0", "TILE_B.WIRE.1"},
                        new short[]{25, -4})
                .build();
    }

    /**
     * {@code toBuilder(v).build()} equals the parameters, for every generated data set and corner: in every getter
     * over every index, speed grade, device and pin it knows (see {@link #assertSameGetters}), and in every field
     * (see {@link #getDifferentFields}), which covers all the content the data checksums cover.
     */
    @Test
    public void testToBuilderRoundTrip() {
        List<InterconnectParams> all = getAllParams();
        // UltraScale+, Versal gen1 and gen2 at four corners each
        Assertions.assertEquals(12, all.size());
        for (InterconnectParams p : all) {
            InterconnectParams copy = p.toBuilder(p.getDataVersion()).build();
            String what = p.getDataVersion() + " " + p.getCorner();
            Assertions.assertEquals(Collections.emptyList(), getDifferentFields(p, copy), what);
            assertSameGetters(p, copy, what);
        }
    }

    /**
     * The synthetic parameters set every field to something other than its default (a field added later without a
     * setter call here fails this), and they survive a round trip: a term that toBuilder leaves out fails here even
     * if no generated data set uses it yet.
     */
    @Test
    public void testToBuilderCarriesEveryTerm() throws IllegalAccessException {
        InterconnectParams p = createFullParams();
        for (Field f : getInstanceFields()) {
            Assertions.assertFalse(isDefault(f.get(p)), "the synthetic parameters leave " + f.getName()
                    + " at its default");
        }
        InterconnectParams copy = p.toBuilder(p.getDataVersion()).build();
        Assertions.assertEquals(Collections.emptyList(), getDifferentFields(p, copy));
        assertSameGetters(p, copy, "synthetic");
        Assertions.assertTrue(copy.hasTrackDeltas());
        Assertions.assertArrayEquals(new int[]{1, 2}, copy.getTrackDeltaClasses());
        Assertions.assertEquals(1.1f, copy.getFanoutSpeedScale("-1"));
        Assertions.assertEquals(-0.7f + 0.2f, copy.getSinkPinDelta(SiteTypeEnum.DSP48E2, "A3"), 1e-6f);
    }

    /**
     * Parameters derived through toBuilder differ from the originals in exactly the terms set on the builder, and
     * the originals (shared, generated data) do not change.
     */
    @Test
    public void testToBuilderDerivesWithoutChangingOriginal() {
        InterconnectParams p = UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX);
        float[] base = new float[p.getNumNodeClasses()];
        float[] length = new float[base.length];
        float[] fanout = new float[base.length];
        for (int i = 0; i < base.length; i++) {
            base[i] = p.getBaseDelay(i);
            length[i] = p.getLengthWeight(i);
            fanout[i] = p.getFanoutDelay(i);
        }
        int cls = p.getNodeClassNames().indexOf("SINGLE_E");
        Assertions.assertTrue(cls > 0);
        float original = base[cls];
        base[cls] += 5;
        InterconnectParams.Builder b = p.toBuilder(p.getDataVersion() + ", derived");
        InterconnectParams derived = b.setClassDelays(base, length, fanout).build();
        Assertions.assertEquals(Arrays.asList("base", "dataVersion"), getDifferentFields(p, derived));
        Assertions.assertEquals(original + 5, derived.getBaseDelay(cls));
        Assertions.assertEquals(original, p.getBaseDelay(cls));
        Assertions.assertSame(p, UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX));
        Assertions.assertEquals(p.getDataVersion() + ", derived", derived.getDataVersion());
        // Each builder is a separate copy
        Assertions.assertEquals(Collections.emptyList(),
                getDifferentFields(p, p.toBuilder(p.getDataVersion()).build()));
    }

    /** The track deltas cannot be modified through getTrackDeltas(). */
    @Test
    public void testTrackDeltasAreReadOnly() {
        InterconnectParams p = UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX);
        Assertions.assertTrue(p.hasTrackDeltas());
        Map<String, Map<String, Float>> deltas = p.getTrackDeltas();
        Assertions.assertFalse(deltas.isEmpty());
        String tileType = deltas.keySet().iterator().next();
        Assertions.assertThrows(UnsupportedOperationException.class, () -> deltas.remove(tileType));
        for (Map<String, Float> wires : deltas.values()) {
            Assertions.assertFalse(wires.isEmpty());
            Assertions.assertThrows(UnsupportedOperationException.class, () -> wires.put("X", 1f));
        }
        Assertions.assertSame(deltas, p.getTrackDeltas());
    }

    /**
     * The pin delta views hold what getSinkPinDelta() and getSourcePinDelta() combine (a pin's own delta plus its
     * bus's), and cannot be modified.
     */
    @Test
    public void testPinDeltaViews() {
        InterconnectParams p = UltraScalePlusTimingFamily.getInterconnectParams(Corner.SLOW_MAX);
        Assertions.assertTrue(p.hasPinDeltas());
        int n = 0;
        for (boolean sink : new boolean[]{true, false}) {
            Map<SiteTypeEnum, Map<String, Float>> view = sink ? p.getSinkPinDeltas() : p.getSourcePinDeltas();
            Assertions.assertFalse(view.isEmpty());
            for (Map.Entry<SiteTypeEnum, Map<String, Float>> e : view.entrySet()) {
                Map<String, Float> pins = e.getValue();
                for (Map.Entry<String, Float> pin : pins.entrySet()) {
                    Assertions.assertNotEquals(0f, pin.getValue());
                    String bus = InterconnectParams.getPinDeltaBusName(e.getKey(), pin.getKey());
                    float expected = pin.getValue() + (bus.equals(pin.getKey()) ? 0f : pins.getOrDefault(bus, 0f));
                    float actual = sink ? p.getSinkPinDelta(e.getKey(), pin.getKey())
                            : p.getSourcePinDelta(e.getKey(), pin.getKey());
                    Assertions.assertEquals(expected, actual, e.getKey() + "." + pin.getKey());
                    n++;
                }
                Assertions.assertThrows(UnsupportedOperationException.class, () -> pins.put("X", 1f));
            }
            Assertions.assertThrows(UnsupportedOperationException.class, () -> view.remove(SiteTypeEnum.SLICEL));
            Assertions.assertSame(view, sink ? p.getSinkPinDeltas() : p.getSourcePinDeltas());
        }
        Assertions.assertTrue(n > 10);
        // Versal data has none
        InterconnectParams v = VersalTimingFamily.getInterconnectParams(VersalTimingFamily.Generation.GEN1,
                Corner.SLOW_MAX);
        Assertions.assertTrue(v.getSinkPinDeltas().isEmpty());
        Assertions.assertTrue(v.getSourcePinDeltas().isEmpty());
    }

    // ------------------------------------------------------------------------------------------
    // Comparison of all getters and all fields
    // ------------------------------------------------------------------------------------------

    private static List<Field> getInstanceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : InterconnectParams.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                fields.add(f);
            }
        }
        Assertions.assertTrue(fields.size() > 20);
        return fields;
    }

    /** @return The names of the fields whose values differ between two parameters, sorted. */
    private static List<String> getDifferentFields(InterconnectParams a, InterconnectParams b) {
        List<String> different = new ArrayList<>();
        try {
            for (Field f : getInstanceFields()) {
                if (!deepEquals(f.get(a), f.get(b))) {
                    different.add(f.getName());
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        Collections.sort(different);
        return different;
    }

    /** Equality of arrays by content, of maps and collections by their elements' deep equality, of patterns by regex */
    private static boolean deepEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.getClass().isArray()) {
            if (!b.getClass().isArray() || Array.getLength(a) != Array.getLength(b)) {
                return false;
            }
            for (int i = 0; i < Array.getLength(a); i++) {
                if (!deepEquals(Array.get(a, i), Array.get(b, i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Pattern) {
            return b instanceof Pattern && ((Pattern) a).pattern().equals(((Pattern) b).pattern())
                    && ((Pattern) a).flags() == ((Pattern) b).flags();
        }
        if (a instanceof Map) {
            Map<?, ?> ma = (Map<?, ?>) a;
            Map<?, ?> mb = (Map<?, ?>) b;
            if (!ma.keySet().equals(mb.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!deepEquals(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof List) {
            return b instanceof List && deepEquals(((List<?>) a).toArray(), ((List<?>) b).toArray());
        }
        if (a instanceof Set) {
            return a.equals(b);
        }
        // (Float.equals compares bits, NaN included)
        return a.equals(b);
    }

    /**
     * Whether a field value is that of an unset term: null, 0, false, an empty collection or map, or an array whose
     * elements are all such values (e.g. the zero-filled array of a per-class term that the builder starts with)
     */
    private static boolean isDefault(Object v) {
        if (v == null) {
            return true;
        }
        if (v.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(v); i++) {
                if (!isDefault(Array.get(v, i))) {
                    return false;
                }
            }
            return true;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue() == 0;
        }
        if (v instanceof Boolean) {
            return !((Boolean) v);
        }
        if (v instanceof Collection) {
            return ((Collection<?>) v).isEmpty();
        }
        if (v instanceof Map) {
            return ((Map<?, ?>) v).isEmpty();
        }
        return v instanceof String && ((String) v).isEmpty();
    }

    /**
     * Compares every public getter of two parameters (every public instance method with a result, but toBuilder),
     * with every argument it can take: indices of node and crossing classes, the speed grades and devices of both,
     * their pins. A getter with other parameter types fails the test, until this method gives it arguments.
     */
    private static void assertSameGetters(InterconnectParams a, InterconnectParams b, String what) {
        int numIndices = Math.max(Math.max(a.getNumNodeClasses(), a.getNumCrossingClasses()),
                Math.max(b.getNumNodeClasses(), b.getNumCrossingClasses()));
        Set<String> grades = new TreeSet<>();
        Set<String> names = new TreeSet<>();
        for (InterconnectParams p : new InterconnectParams[]{a, b}) {
            for (String g : p.getSpeedGrades()) {
                grades.add(g);
                grades.add("-" + g);
            }
            names.addAll(getStringKeys(p));
        }
        names.addAll(grades);
        // An unknown speed grade or device
        names.add("9Z");
        List<Object[]> pins = new ArrayList<>();
        for (InterconnectParams p : new InterconnectParams[]{a, b}) {
            for (Map<SiteTypeEnum, Map<String, Float>> m : Arrays.asList(p.getSinkPinDeltas(),
                    p.getSourcePinDeltas())) {
                for (Map.Entry<SiteTypeEnum, Map<String, Float>> e : m.entrySet()) {
                    for (String pin : e.getValue().keySet()) {
                        pins.add(new Object[]{e.getKey(), pin});
                        pins.add(new Object[]{e.getKey(), pin + "0"});
                    }
                }
            }
        }
        pins.add(new Object[]{SiteTypeEnum.SLICEL, "NO_SUCH_PIN"});
        int numGetters = 0;
        for (Method m : InterconnectParams.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())
                    || m.getReturnType() == void.class || m.getName().equals("toBuilder")) {
                continue;
            }
            numGetters++;
            List<Object[]> argLists = new ArrayList<>();
            Class<?>[] types = m.getParameterTypes();
            if (types.length == 0) {
                argLists.add(new Object[0]);
            } else if (Arrays.equals(types, new Class<?>[]{int.class})) {
                for (int i = 0; i < numIndices; i++) {
                    argLists.add(new Object[]{i});
                }
            } else if (Arrays.equals(types, new Class<?>[]{int.class, int.class})) {
                for (int i = 0; i < numIndices; i++) {
                    for (int j = 0; j < numIndices; j++) {
                        argLists.add(new Object[]{i, j});
                    }
                }
            } else if (Arrays.equals(types, new Class<?>[]{String.class})) {
                for (String s : names) {
                    argLists.add(new Object[]{s});
                }
            } else if (Arrays.equals(types, new Class<?>[]{String.class, String.class})) {
                for (String s : grades) {
                    argLists.add(new Object[]{s, "test data"});
                }
            } else if (Arrays.equals(types, new Class<?>[]{SiteTypeEnum.class, String.class})) {
                argLists.addAll(pins);
            } else {
                Assertions.fail("No arguments for the getter " + m + "; add them to assertSameGetters");
            }
            for (Object[] args : argLists) {
                Object ra = invoke(m, a, args);
                Object rb = invoke(m, b, args);
                Assertions.assertTrue(deepEquals(ra, rb), () -> what + ": " + m.getName() + Arrays.toString(args)
                        + " " + toString(ra) + " vs " + toString(rb));
            }
        }
        Assertions.assertTrue(numGetters > 30, "getters " + numGetters);
    }

    /** The result of a getter, or the class of the exception it throws (e.g. for an index out of range) */
    private static Object invoke(Method m, Object target, Object[] args) {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            return e.getCause().getClass();
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static String toString(Object o) {
        if (o != null && o.getClass().isArray()) {
            List<Object> l = new ArrayList<>();
            for (int i = 0; i < Array.getLength(o); i++) {
                l.add(toString(Array.get(o, i)));
            }
            return l.toString();
        }
        return String.valueOf(o);
    }

    /** The String keys of the parameters' String-keyed maps (speed grades, devices), which some getters take */
    private static Set<String> getStringKeys(InterconnectParams p) {
        Set<String> keys = new TreeSet<>();
        try {
            for (Field f : getInstanceFields()) {
                Object v = f.get(p);
                if (v instanceof Map) {
                    for (Object k : ((Map<?, ?>) v).keySet()) {
                        if (k instanceof String) {
                            keys.add((String) k);
                        }
                    }
                }
            }
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
        return keys;
    }
}
