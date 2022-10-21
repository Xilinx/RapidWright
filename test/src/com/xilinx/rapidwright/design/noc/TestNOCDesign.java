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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

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
}
