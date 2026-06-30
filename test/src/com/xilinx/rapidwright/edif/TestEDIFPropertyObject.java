/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
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

import java.nio.file.Path;
import java.util.Map;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestEDIFPropertyObject {
    @Test
    public void testGetIOStandard() {
        final EDIFNetlist netlist = EDIFTools.createNewNetlist("test");

        EDIFCell top = netlist.getTopCell();
        EDIFCellInst obufds = top.createChildCellInst("obuf", Design.getPrimitivesLibrary().getCell("OBUFDS"));
        Assertions.assertEquals(EDIFNetlist.DEFAULT_PROP_VALUE, obufds.getIOStandard());

        obufds.addProperty("IOSTANDARD", "LVDS");
        Assertions.assertEquals("LVDS", obufds.getIOStandard().getValue());
    }

    /**
     * Test properties backed by the memory-compact
     * {@link EDIFPropertyMap} rather than a {@link java.util.HashMap}.
     */
    @Test
    public void testPropertiesUseCompactMap() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        EDIFCellInst inst = netlist.getTopCell().createChildCellInst("u0",
                Design.getPrimitivesLibrary().getCell("FDRE"));

        // No properties yet -> empty (immutable) map
        Assertions.assertTrue(inst.getPropertiesMap().isEmpty());

        inst.addProperty("INIT", "1'b0");
        inst.addProperty("IS_C_INVERTED", "1'b0");

        Map<String, EDIFPropertyValue> map = inst.getPropertiesMap();
        Assertions.assertTrue(map instanceof EDIFPropertyMap,
                "Expected EDIFPropertyMap backing, got " + map.getClass());
        Assertions.assertEquals(2, map.size());
        Assertions.assertEquals("1'b0", inst.getProperty("INIT").getValue());

        // Behaves like a Map for all standard operations
        Assertions.assertTrue(map.containsKey("INIT"));
        Assertions.assertEquals(2, map.keySet().size());
        Assertions.assertEquals(2, map.entrySet().size());

        // Removal works through the object API
        Assertions.assertEquals("1'b0", inst.removeProperty("INIT").getValue());
        Assertions.assertNull(inst.getProperty("INIT"));
        Assertions.assertEquals(1, inst.getPropertiesMap().size());
    }

    /**
     * The map returned by getPropertiesMap() is a live view over the object's
     * inlined storage: mutations made through the object are visible through a
     * previously-obtained map, and vice versa.
     */
    @Test
    public void testPropertiesMapIsLiveView() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        EDIFCellInst inst = netlist.getTopCell().createChildCellInst("u0",
                Design.getPrimitivesLibrary().getCell("FDRE"));
        inst.addProperty("A", "1");

        Map<String, EDIFPropertyValue> view = inst.getPropertiesMap();
        Assertions.assertEquals(1, view.size());

        // Mutation through the object is reflected in the previously-obtained view
        inst.addProperty("B", "2");
        Assertions.assertEquals(2, view.size());
        Assertions.assertEquals("2", view.get("B").getValue());

        // Mutation through the view is reflected on the object
        view.put("C", new EDIFPropertyValue("3", EDIFValueType.STRING));
        Assertions.assertEquals("3", inst.getProperty("C").getValue());

        inst.removeProperty("A");
        Assertions.assertFalse(view.containsKey("A"));
        Assertions.assertEquals(2, view.size());
    }

    /**
     * setPropertiesMap() must not lose data when handed this object's own live
     * getPropertiesMap() view (it reads the source before replacing storage).
     */
    @Test
    public void testSetPropertiesMapWithOwnLiveView() {
        EDIFNetlist netlist = EDIFTools.createNewNetlist("test");
        EDIFCellInst inst = netlist.getTopCell().createChildCellInst("u0",
                Design.getPrimitivesLibrary().getCell("FDRE"));
        inst.addProperty("A", "1");
        inst.addProperty("B", "2");

        // Passing the object's own live view must preserve all properties.
        Map<String, EDIFPropertyValue> ownView = inst.getPropertiesMap();
        inst.setPropertiesMap(ownView);
        Assertions.assertEquals(2, inst.getPropertyCount());
        Assertions.assertEquals("1", inst.getProperty("A").getValue());
        Assertions.assertEquals("2", inst.getProperty("B").getValue());

        // Passing another object's live view copies it (and does not alias).
        EDIFCellInst other = netlist.getTopCell().createChildCellInst("u1",
                Design.getPrimitivesLibrary().getCell("FDRE"));
        other.setPropertiesMap(inst.getPropertiesMap());
        Assertions.assertEquals(2, other.getPropertyCount());
        // Mutating the source afterwards must not affect the copy.
        inst.addProperty("C", "3");
        Assertions.assertEquals(2, other.getPropertyCount());
        Assertions.assertNull(other.getProperty("C"));

        // Setting an empty map clears.
        inst.setPropertiesMap(new java.util.HashMap<>());
        Assertions.assertEquals(0, inst.getPropertyCount());
    }

    /**
     * Properties must survive an EDIF read/write round-trip
     * unchanged when stored in the compact map.
     */
    @Test
    public void testPropertiesSurviveRoundTrip(@TempDir Path dir) {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();

        java.nio.file.Path out = dir.resolve("roundtrip.edf");
        netlist.exportEDIF(out.toString());
        EDIFNetlist reloaded = EDIFTools.readEdifFile(out);

        // Spot-check that every cell instance in the top cell preserved its
        // property set across the round trip.
        EDIFCell top = netlist.getTopCell();
        EDIFCell topReloaded = reloaded.getTopCell();
        for (EDIFCellInst inst : top.getCellInsts()) {
            EDIFCellInst other = topReloaded.getCellInst(inst.getName());
            Assertions.assertNotNull(other, "Missing inst after round trip: " + inst.getName());
            Map<String, EDIFPropertyValue> a = inst.getPropertiesMap();
            Map<String, EDIFPropertyValue> b = other.getPropertiesMap();
            Assertions.assertEquals(a.keySet(), b.keySet(), "Property keys differ for " + inst.getName());
            for (String key : a.keySet()) {
                Assertions.assertEquals(a.get(key).getValue(), b.get(key).getValue(),
                        "Property value differs for " + inst.getName() + "." + key);
            }
        }
    }
}
