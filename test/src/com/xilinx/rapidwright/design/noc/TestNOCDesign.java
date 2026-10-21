/*
 * Copyright (c) 2022, Xilinx, Inc.
 * Copyright (c) 2022, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Chris Lavin, Xilinx Research Labs.
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

package com.xilinx.rapidwright.design.noc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.json.JSONObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.support.RapidWrightDCP;

public class TestNOCDesign {

    public void sanityChecks(Design d) {
        Device dev = d.getDevice();
        NOCDesign nocDesign = d.getNOCDesign();
        for (NOCConnection conn : nocDesign.getAllConnections()) {
            for (Entry<ChannelType,NOCChannel> e : conn.getChannels().entrySet()) {
                NOCChannel ch = e.getValue();
                Assertions.assertNotNull(ch);
                Assertions.assertEquals(ch.getRequiredLatency(), 300);
                Assertions.assertNotNull(ch.getChannelPath());
                Assertions.assertEquals(e.getKey(), ch.getChannelType());
            }
        }

        for (Entry<String,NOCClient> e : nocDesign.getClients().entrySet()) {
            Assertions.assertEquals(e.getKey(), e.getValue().getName());
            NOCClient client = e.getValue();
            Assertions.assertNotNull(client);
            Assertions.assertNotNull(dev.getSite(client.getLocation()));
        }
    }

    public void testClientsAreEqual(NOCClient gold, NOCClient test) {
        Assertions.assertEquals(gold.getName(),test.getName());
        Assertions.assertEquals(gold.getComponentType(),test.getComponentType());
        Assertions.assertEquals(gold.getConnections().size(),test.getConnections().size());
        Assertions.assertEquals(gold.getLocation(),test.getLocation());
        Assertions.assertEquals(gold.getProtocol(),test.getProtocol());
        Assertions.assertEquals(gold.isDDRC(),test.isDDRC());
        Assertions.assertEquals(gold.isFabricClient(),test.isFabricClient());
    }

    @Test
    public void testNOCDesign(@TempDir Path tempDir) {
        String dcpName = "noc_tutorial_routed.dcp";
        Design d = RapidWrightDCP.loadDCP(dcpName);
        sanityChecks(d);

        Path testDCP = tempDir.resolve(dcpName);
        d.writeCheckpoint(testDCP);

        Design d2 = Design.readCheckpoint(testDCP);
        sanityChecks(d2);

        NOCDesign nocGold = d.getNOCDesign();
        NOCDesign nocTest = d2.getNOCDesign();

        Assertions.assertEquals(nocGold.getFrequency(), nocTest.getFrequency());
        List<NOCConnection> goldConns = nocGold.getAllConnections();
        List<NOCConnection> testConns = nocTest.getAllConnections();
        Assertions.assertEquals(goldConns.size(), testConns.size());
        for (int i=0; i < goldConns.size(); i++) {
            NOCConnection goldConn = goldConns.get(i);
            NOCConnection testConn = testConns.get(i);

            Assertions.assertEquals(goldConn.getCommType(),testConn.getCommType());
            Assertions.assertEquals(goldConn.getEstimatedReadBandwidth(),testConn.getEstimatedReadBandwidth());
            Assertions.assertEquals(goldConn.getEstimatedWriteBandwidth(),testConn.getEstimatedWriteBandwidth());
            Assertions.assertEquals(goldConn.getPort(),testConn.getPort());
            Assertions.assertEquals(goldConn.getReadBandwidth(),testConn.getReadBandwidth());
            Assertions.assertEquals(goldConn.getReadLatency(),testConn.getReadLatency());
            Assertions.assertEquals(goldConn.getWriteBandwidth(),testConn.getWriteBandwidth());
            Assertions.assertEquals(goldConn.getWriteLatency(),testConn.getWriteLatency());
            Assertions.assertEquals(goldConn.isRouted(),testConn.isRouted());

            NOCMaster goldMaster = goldConn.getSource();
            NOCMaster testMaster = testConn.getSource();
            testClientsAreEqual(goldMaster, testMaster);
            Assertions.assertEquals(goldMaster.getReadTC(),testMaster.getReadTC());
            Assertions.assertEquals(goldMaster.getWriteTC(),testMaster.getWriteTC());

            NOCSlave goldSlave = goldConn.getDest();
            NOCSlave testSlave = testConn.getDest();
            testClientsAreEqual(goldSlave, testSlave);
            Assertions.assertEquals(goldSlave.getPorts(), testSlave.getPorts());


            Map<ChannelType, NOCChannel> goldMap = goldConn.getChannels();
            Map<ChannelType, NOCChannel> testMap = goldConn.getChannels();
            Assertions.assertEquals(goldMap.size(), testMap.size());
            for (Entry<ChannelType, NOCChannel> e : goldMap.entrySet()) {
                Assertions.assertTrue(testMap.containsKey(e.getKey()));
                NOCChannel goldCh = e.getValue();
                NOCChannel testCh = testMap.get(e.getKey());
                Assertions.assertEquals(goldCh.getChannelPath(),testCh.getChannelPath());
                Assertions.assertEquals(goldCh.getChannelType(),testCh.getChannelType());
                Assertions.assertEquals(goldCh.getEstimatedBandwidth(),testCh.getEstimatedBandwidth());
                Assertions.assertEquals(goldCh.getEstimatedLatency(),testCh.getEstimatedLatency());
                Assertions.assertEquals(goldCh.getRequiredBandwidth(),testCh.getRequiredBandwidth());
                Assertions.assertEquals(goldCh.getRequiredLatency(),testCh.getRequiredLatency());
            }
        }
    }

    /**
     * A design without NOC clients, such as a reconfigurable module, has a traffic file with no logical
     * instances or paths. What RapidWright writes for it must be readable again, by RapidWright as well.
     */
    @Test
    public void testTrafficWithoutClients() {
        String vivadoTraffic = "{\n"
                + "  \"SystemProperties\": {\"Frequency\": 960, \"DeviceName\": \"xcvp1902\"},\n"
                + "  \"LogicalInstances\": [],\n"
                + "  \"Paths\": []\n"
                + "}";
        NOCDesign noc = new NOCDesign();
        noc.loadTraffic(new ByteArrayInputStream(vivadoTraffic.getBytes(StandardCharsets.UTF_8)));
        Assertions.assertEquals(960, noc.getFrequency());
        Assertions.assertTrue(noc.getClients().isEmpty());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        noc.writeTraffic(out);
        JSONObject written = new JSONObject(out.toString(StandardCharsets.UTF_8));
        Assertions.assertTrue(written.getJSONArray("LogicalInstances").length() == 0);
        Assertions.assertTrue(written.getJSONArray("Paths").length() == 0);
        Assertions.assertEquals(960, written.getJSONObject("SystemProperties").getInt("Frequency"));
        Assertions.assertEquals("xcvp1902", written.getJSONObject("SystemProperties").getString("DeviceName"));

        NOCDesign reread = new NOCDesign();
        reread.loadTraffic(new ByteArrayInputStream(out.toByteArray()));
        Assertions.assertEquals(960, reread.getFrequency());
        Assertions.assertTrue(reread.getClients().isEmpty());

        // Without the arrays at all, as RapidWright wrote them before
        NOCDesign old = new NOCDesign();
        old.loadTraffic(new ByteArrayInputStream("{\"SystemProperties\": {\"Frequency\": 1000}}"
                .getBytes(StandardCharsets.UTF_8)));
        Assertions.assertTrue(old.getClients().isEmpty());
        Assertions.assertTrue(old.getAllConnections().isEmpty());
    }

    /**
     * Abridged Versal Gen 2 (NoC2) traffic, as written by Vivado 2025.2 for xc2ve3858: a PL
     * NMU, a DDRMC5E memory controller with two ports and a PL NSU, including fields
     * RapidWright does not model.
     */
    private static final String GEN2_TRAFFIC = "{\n"
            + "  \"SystemProperties\": {\"Frequency\": 1000, \"DeviceName\": \"xc2ve3858\"},\n"
            + "  \"LogicalInstances\": [\n"
            + "    {\"Name\": \"noc/S00_AXI_nmu/NOC2_NMU512_INST\", \"DesignName\": \"/noc_0/S00_AXI\",\n"
            + "     \"IsMaster\": true, \"CompType\": \"PL_NMU_2\", \"Protocol\": \"AXI_MM\", \"ReadTC\": \"BE\",\n"
            + "     \"WriteTC\": \"BE\", \"AxiDataWidth\": 128, \"SysAddresses\": [],\n"
            + "     \"SimMetaData\": {\"IPName\": \"S00_AXI_nmu\"}},\n"
            + "    {\"Name\": \"noc/MC0_ddrc/DDRMC5_COMP\", \"DesignName\": \"/noc_0/MC0\", \"IsMaster\": false,\n"
            + "     \"CompType\": \"DDR5E\", \"Protocol\": \"AXI_MM\", \"Ports\": [\"PORT0\", \"PORT1\"],\n"
            + "     \"InterleaveSize\": 128, \"InterleaveSel\": \"4\",\n"
            + "     \"SysAddresses\": [{\"Base\": \"0x0\", \"Size\": \"0x80000000\"}],\n"
            + "     \"MemoryParams\": {\"MC_TYPE\": \"LPDDR5X\"}},\n"
            + "    {\"Name\": \"noc/M00_AXI_nsu/NOC2_NSU512_INST\", \"DesignName\": \"/noc_0/M00_AXI\",\n"
            + "     \"IsMaster\": false, \"CompType\": \"PL_NSU_2\", \"Protocol\": \"AXI_MM\", \"AxiDataWidth\": 128,\n"
            + "     \"SysAddresses\": [{\"Base\": \"0x80000000000\", \"Size\": \"0x4000\"}]}\n"
            + "  ],\n"
            + "  \"Paths\": [\n"
            + "    {\"Phase\": 0, \"From\": \"noc/S00_AXI_nmu/NOC2_NMU512_INST\", \"To\": \"noc/MC0_ddrc/DDRMC5_COMP\",\n"
            + "     \"Port\": \"PORT0\", \"CommType\": \"MM_ReadWrite\", \"ReadBW\": 500, \"ReadLatency\": 300,\n"
            + "     \"ReadAvgBurst\": 16, \"WriteBW\": 500, \"WriteLatency\": 300, \"WriteAvgBurst\": 16,\n"
            + "     \"WriteOrder\": \"relaxed\"}\n"
            + "  ]\n"
            + "}";

    private static String gen2Net(String type, int vc, String... hops) {
        StringBuilder sb = new StringBuilder("{\"PhyInstanceStart\": \"" + hops[0] + "\", \"PhyInstanceEnd\": \""
                + hops[hops.length - 2] + "\", \"VC\": " + vc + ", \"CommType\": \"" + type + "\", \"Connections\": [");
        for (int i = 0; i < hops.length; i++) {
            sb.append(i > 0 ? ", " : "").append('"').append(hops[i]).append('"');
        }
        return sb.append("], \"RequiredBW\": 562, \"AchievedBW\": 562, \"RequiredLatency\": 300, "
                + "\"AchievedLatency\": 24}").toString();
    }

    /** Abridged NoC2 solution for {@link #GEN2_TRAFFIC} (routes shortened to one switch) */
    private static final String GEN2_SOLUTION = "{\n"
            + "  \"SolutionType\": \"OPTIMAL\", \"LockAllDestIds\": false,\n"
            + "  \"Paths\": [{\"Phase\": 0, \"From\": \"noc/S00_AXI_nmu/NOC2_NMU512_INST\", \"FromLocked\": false,\n"
            + "    \"To\": \"noc/MC0_ddrc/DDRMC5_COMP\", \"ToLocked\": false, \"Port\": \"PORT0\", \"ReadTC\": \"BE\",\n"
            + "    \"WriteTC\": \"BE\", \"ReadBW\": 500, \"WriteBW\": 500, \"ReadAchievedBW\": 500,\n"
            + "    \"WriteAchievedBW\": 500, \"ReadLatency\": 300, \"WriteLatency\": 300,\n"
            + "    \"ReadBestPossibleLatency\": 300, \"WriteBestPossibleLatency\": 300, \"PathLocked\": false,\n"
            + "    \"Nets\": [\n      "
            + gen2Net("WRITE", 5, "NOC2_NMU512_X1Y5", "req_out", "NOC2_NPS5555_X17Y5", "port3_in",
                    "NOC2_NPS5555_X17Y5", "port2_out", "DDRMC5E_X0Y0", "Port0_req") + ",\n      "
            + gen2Net("WRITE_RESP", 3, "DDRMC5E_X0Y0", "Port0_resp", "NOC2_NPS5555_X17Y5", "port2_in",
                    "NOC2_NPS5555_X17Y5", "port3_out", "NOC2_NMU512_X1Y5", "resp_in") + ",\n      "
            + gen2Net("READ_REQ", 0, "NOC2_NMU512_X1Y5", "req_out", "NOC2_NPS5555_X17Y5", "port3_in",
                    "NOC2_NPS5555_X17Y5", "port2_out", "DDRMC5E_X0Y0", "Port0_req") + ",\n      "
            + gen2Net("READ", 6, "DDRMC5E_X0Y0", "Port0_resp", "NOC2_NPS5555_X17Y5", "port2_in",
                    "NOC2_NPS5555_X17Y5", "port3_out", "NOC2_NMU512_X1Y5", "resp_in") + "\n"
            + "    ]}],\n"
            + "  \"Components\": [\n"
            + "    {\"Name\": \"NOC2_NMU512_X1Y5\", \"TrafficLInst\": \"noc/S00_AXI_nmu/NOC2_NMU512_INST\", \"DestId\": 0},\n"
            + "    {\"Name\": \"NOC2_NMU512_X0Y5\", \"DestId\": 0},\n"
            + "    {\"Name\": \"DDRMC5E_X0Y0\", \"TrafficLInst\": \"noc/MC0_ddrc/DDRMC5_COMP\", \"DestId\": 1216, \"PortIndex\": 0},\n"
            + "    {\"Name\": \"DDRMC5E_X0Y0\", \"TrafficLInst\": \"noc/MC0_ddrc/DDRMC5_COMP\", \"DestId\": 1280, \"PortIndex\": 1},\n"
            + "    {\"Name\": \"DDRMC5E_X4Y0\", \"DestId\": 0, \"PortIndex\": 0},\n"
            + "    {\"Name\": \"DDRMC5E_X4Y0\", \"DestId\": 0, \"PortIndex\": 1},\n"
            + "    {\"Name\": \"NOC2_NSU512_X2Y4\", \"TrafficLInst\": \"noc/M00_AXI_nsu/NOC2_NSU512_INST\", \"DestId\": 576},\n"
            + "    {\"Name\": \"NOC2_NPS5555_X17Y5\", \"DestId\": 0}\n"
            + "  ]\n"
            + "}";

    private static Map<String, List<JSONObject>> componentsBySite(JSONObject solution) {
        Map<String, List<JSONObject>> map = new java.util.HashMap<>();
        org.json.JSONArray comps = solution.getJSONArray("Components");
        for (int i = 0; i < comps.length(); i++) {
            JSONObject c = comps.getJSONObject(i);
            map.computeIfAbsent(c.getString("Name"), k -> new java.util.ArrayList<>()).add(c);
        }
        return map;
    }

    @Test
    public void testGen2TrafficAndSolution() {
        Design d = new Design("noc2", "xc2ve3858");
        NOCDesign noc = new NOCDesign(d);
        noc.loadTraffic(new ByteArrayInputStream(GEN2_TRAFFIC.getBytes(StandardCharsets.UTF_8)));
        noc.loadSolution(new ByteArrayInputStream(GEN2_SOLUTION.getBytes(StandardCharsets.UTF_8)));

        NOCMaster nmu = noc.getMasterClients().get("noc/S00_AXI_nmu/NOC2_NMU512_INST");
        NOCSlave mc = noc.getSlaveClients().get("noc/MC0_ddrc/DDRMC5_COMP");
        NOCSlave nsu = noc.getSlaveClients().get("noc/M00_AXI_nsu/NOC2_NSU512_INST");
        Assertions.assertEquals(ComponentType.PL_NMU_2, nmu.getComponentType());
        Assertions.assertTrue(nmu.isFabricClient());
        Assertions.assertEquals(128, nmu.getAxiDataWidth());
        Assertions.assertTrue(mc.isDDRC());
        Assertions.assertEquals(1280, mc.getPortDestID("PORT1"));
        Assertions.assertTrue(nsu.isFabricClient());
        Assertions.assertEquals("NOC2_NSU512_X2Y4", nsu.getLocation());
        NOCConnection conn = noc.getAllConnections().get(0);
        Assertions.assertTrue(conn.isRouted());
        Assertions.assertEquals(4, conn.getChannels().size());

        // Fields RapidWright does not model are written back unchanged
        ByteArrayOutputStream traffic = new ByteArrayOutputStream();
        noc.writeTraffic(traffic);
        JSONObject t = new JSONObject(traffic.toString(StandardCharsets.UTF_8));
        JSONObject orig = new JSONObject(GEN2_TRAFFIC);
        // Clients are written in no particular order
        Map<String, JSONObject> origClients = new java.util.HashMap<>();
        for (int i = 0; i < orig.getJSONArray("LogicalInstances").length(); i++) {
            JSONObject c = orig.getJSONArray("LogicalInstances").getJSONObject(i);
            origClients.put(c.getString("Name"), c);
        }
        Assertions.assertEquals(origClients.size(), t.getJSONArray("LogicalInstances").length());
        for (int i = 0; i < t.getJSONArray("LogicalInstances").length(); i++) {
            JSONObject c = t.getJSONArray("LogicalInstances").getJSONObject(i);
            Assertions.assertTrue(origClients.get(c.getString("Name")).similar(c), c.toString());
        }
        Assertions.assertTrue(orig.getJSONArray("Paths").similar(t.getJSONArray("Paths")));
        Assertions.assertTrue(orig.getJSONObject("SystemProperties").similar(t.getJSONObject("SystemProperties")));

        // The solution keeps every component: 2 ports per DDRMC5E, unused ports included
        ByteArrayOutputStream solution = new ByteArrayOutputStream();
        noc.writeSolution(solution);
        Map<String, List<JSONObject>> comps = componentsBySite(new JSONObject(solution.toString(StandardCharsets.UTF_8)));
        Map<String, List<JSONObject>> origComps = componentsBySite(new JSONObject(GEN2_SOLUTION));
        Assertions.assertTrue(comps.keySet().containsAll(origComps.keySet()));
        for (String site : comps.keySet()) {
            if (!origComps.containsKey(site)) {
                // Unlisted NoC switches are written out unused (e.g. NOC_NCRB)
                Assertions.assertEquals(0, comps.get(site).get(0).getInt("DestId"), site);
            }
        }
        Assertions.assertEquals(2, comps.get("DDRMC5E_X0Y0").size());
        Assertions.assertEquals(2, comps.get("DDRMC5E_X4Y0").size());

        // Moving the NMU to a free site: one entry per site, the vacated site is freed
        nmu.setLocation("NOC2_NMU512_X0Y5");
        solution.reset();
        noc.writeSolution(solution);
        comps = componentsBySite(new JSONObject(solution.toString(StandardCharsets.UTF_8)));
        Assertions.assertEquals(1, comps.get("NOC2_NMU512_X0Y5").size());
        Assertions.assertEquals(nmu.getName(), comps.get("NOC2_NMU512_X0Y5").get(0).getString("TrafficLInst"));
        Assertions.assertEquals(1, comps.get("NOC2_NMU512_X1Y5").size());
        Assertions.assertFalse(comps.get("NOC2_NMU512_X1Y5").get(0).has("TrafficLInst"));
        Assertions.assertEquals(0, comps.get("NOC2_NMU512_X1Y5").get(0).getInt("DestId"));
    }
}
