/* 
 * Copyright (c) 2022 Xilinx, Inc. 
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
 
/**
 * 
 */
package com.xilinx.rapidwright.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Generated on: Wed May 04 13:05:08 2022
 * by: com.xilinx.rapidwright.release.UploadFilesToAzure
 * 
 * Versioned list of data files to use in current RapidWright environment
 */
public class DataVersions {
    public static Map<String,Pair<String,String>> dataVersionMap;
    static {
        dataVersionMap = new HashMap<>();
        dataVersionMap.put("data/cell_pin_defaults.dat", new Pair<>("cell-pin-defaults-dat", "fe524bb877b7f4359bf4e21b5a860b21"));
        dataVersionMap.put("data/devices/artix7/xa7a100t_db.dat", new Pair<>("xa7a100t-db-dat", "fd2e86ccea377461269f1152a1d82bb6"));
        dataVersionMap.put("data/devices/artix7/xa7a12t_db.dat", new Pair<>("xa7a12t-db-dat", "b79f1f17124df039e981ff29d5481cde"));
        dataVersionMap.put("data/devices/artix7/xa7a15t_db.dat", new Pair<>("xa7a15t-db-dat", "f88a40de7acc0b4af64f40786e1cf115"));
        dataVersionMap.put("data/devices/artix7/xa7a25t_db.dat", new Pair<>("xa7a25t-db-dat", "0813212addec07fb23a23670c3bd9e2f"));
        dataVersionMap.put("data/devices/artix7/xa7a35t_db.dat", new Pair<>("xa7a35t-db-dat", "ae5698f84f79f8258a18fa0ddb08da54"));
        dataVersionMap.put("data/devices/artix7/xa7a50t_db.dat", new Pair<>("xa7a50t-db-dat", "49b36a022b5007ff1e67e81ca0d5a1f3"));
        dataVersionMap.put("data/devices/artix7/xa7a75t_db.dat", new Pair<>("xa7a75t-db-dat", "6611ffe67510798ea4e377839f282bec"));
        dataVersionMap.put("data/devices/artix7/xc7a100t_db.dat", new Pair<>("xc7a100t-db-dat", "6e19368ce24199cfd17e93d9e202aa00"));
        dataVersionMap.put("data/devices/artix7/xc7a100ti_db.dat", new Pair<>("xc7a100ti-db-dat", "acb4fe01660dd668757da8fe42246986"));
        dataVersionMap.put("data/devices/artix7/xc7a100tl_db.dat", new Pair<>("xc7a100tl-db-dat", "b4b3e57c164962b65a7f85214e0264b1"));
        dataVersionMap.put("data/devices/artix7/xc7a12t_db.dat", new Pair<>("xc7a12t-db-dat", "345248ae6adb9d33c2ce2f7434f926d4"));
        dataVersionMap.put("data/devices/artix7/xc7a12ti_db.dat", new Pair<>("xc7a12ti-db-dat", "534ad82faab8128ae115c48b70a90a2c"));
        dataVersionMap.put("data/devices/artix7/xc7a12tl_db.dat", new Pair<>("xc7a12tl-db-dat", "6925f3df1fe171dc2ede5fe19ff3efae"));
        dataVersionMap.put("data/devices/artix7/xc7a15t_db.dat", new Pair<>("xc7a15t-db-dat", "5c7c62a4d2124c9e6b8d7c5e936bc82c"));
        dataVersionMap.put("data/devices/artix7/xc7a15ti_db.dat", new Pair<>("xc7a15ti-db-dat", "16847fb25e7151be7efb048df3db2efd"));
        dataVersionMap.put("data/devices/artix7/xc7a15tl_db.dat", new Pair<>("xc7a15tl-db-dat", "0e2fe7d89c01dcf858f39fac21862918"));
        dataVersionMap.put("data/devices/artix7/xc7a200t_db.dat", new Pair<>("xc7a200t-db-dat", "2b4a0c191437c7bf29141e42b1835c88"));
        dataVersionMap.put("data/devices/artix7/xc7a200ti_db.dat", new Pair<>("xc7a200ti-db-dat", "23754f97270df76d5b652361d1b91fcb"));
        dataVersionMap.put("data/devices/artix7/xc7a200tl_db.dat", new Pair<>("xc7a200tl-db-dat", "15d8b17c41a24cb87352df323d477725"));
        dataVersionMap.put("data/devices/artix7/xc7a25t_db.dat", new Pair<>("xc7a25t-db-dat", "5c65e998fa72408ade622bfa828524b0"));
        dataVersionMap.put("data/devices/artix7/xc7a25ti_db.dat", new Pair<>("xc7a25ti-db-dat", "9dd9e09fea4510bbcf3cac2195b20af8"));
        dataVersionMap.put("data/devices/artix7/xc7a25tl_db.dat", new Pair<>("xc7a25tl-db-dat", "1f935cb9d2ad1c6fe29559752101880c"));
        dataVersionMap.put("data/devices/artix7/xc7a35t_db.dat", new Pair<>("xc7a35t-db-dat", "44ad528dcb92c0787f98655e8beedcca"));
        dataVersionMap.put("data/devices/artix7/xc7a35ti_db.dat", new Pair<>("xc7a35ti-db-dat", "de6dbf269f16c9926438c3b0b34d3a94"));
        dataVersionMap.put("data/devices/artix7/xc7a35tl_db.dat", new Pair<>("xc7a35tl-db-dat", "4c42394ddd8b4b194339ce5d2d5d4f7f"));
        dataVersionMap.put("data/devices/artix7/xc7a50t_db.dat", new Pair<>("xc7a50t-db-dat", "b9931dc6aacef73a7180af48b3561012"));
        dataVersionMap.put("data/devices/artix7/xc7a50ti_db.dat", new Pair<>("xc7a50ti-db-dat", "089e516dbe0459c3b7f5ecde5e4c772e"));
        dataVersionMap.put("data/devices/artix7/xc7a50tl_db.dat", new Pair<>("xc7a50tl-db-dat", "824a5c1d542009982b780197156f38fe"));
        dataVersionMap.put("data/devices/artix7/xc7a75t_db.dat", new Pair<>("xc7a75t-db-dat", "a384509f1c4239a67b140271fd487b3e"));
        dataVersionMap.put("data/devices/artix7/xc7a75ti_db.dat", new Pair<>("xc7a75ti-db-dat", "4d9cbe3a1f9ad2cb9758b479e4eb367d"));
        dataVersionMap.put("data/devices/artix7/xc7a75tl_db.dat", new Pair<>("xc7a75tl-db-dat", "dea07607d5fe0362eb663a29c3bb0630"));
        dataVersionMap.put("data/devices/artix7/xq7a100t_db.dat", new Pair<>("xq7a100t-db-dat", "d37a674715fa412ab45c66826329a9fe"));
        dataVersionMap.put("data/devices/artix7/xq7a200t_db.dat", new Pair<>("xq7a200t-db-dat", "45737018a3bf7b8221569626b47c271d"));
        dataVersionMap.put("data/devices/artix7/xq7a50t_db.dat", new Pair<>("xq7a50t-db-dat", "61d2dfc9bb6625491c9de6c8ae7a8fcc"));
        dataVersionMap.put("data/devices/kintex7/xa7k160t_db.dat", new Pair<>("xa7k160t-db-dat", "7f11b9ffc390ee52f831ede069a90ca6"));
        dataVersionMap.put("data/devices/kintex7/xc7k160t_db.dat", new Pair<>("xc7k160t-db-dat", "6c9999263ded490cf5e803327aa1875e"));
        dataVersionMap.put("data/devices/kintex7/xc7k160ti_db.dat", new Pair<>("xc7k160ti-db-dat", "60bf24a6e393334ea5c58f2560078268"));
        dataVersionMap.put("data/devices/kintex7/xc7k160tl_db.dat", new Pair<>("xc7k160tl-db-dat", "8891b7c50869a38dfa6abf09ac9c6497"));
        dataVersionMap.put("data/devices/kintex7/xc7k325t_db.dat", new Pair<>("xc7k325t-db-dat", "e06654f691f43b7fa25e2d8c713ae228"));
        dataVersionMap.put("data/devices/kintex7/xc7k325ti_db.dat", new Pair<>("xc7k325ti-db-dat", "0828f114aa4a081bb9987d10fe309afa"));
        dataVersionMap.put("data/devices/kintex7/xc7k325tl_db.dat", new Pair<>("xc7k325tl-db-dat", "f5d9ffc7e5f5624de1a6e45de245b862"));
        dataVersionMap.put("data/devices/kintex7/xc7k355t_db.dat", new Pair<>("xc7k355t-db-dat", "e920362f605b3365ed7d6dde9970d5cb"));
        dataVersionMap.put("data/devices/kintex7/xc7k355ti_db.dat", new Pair<>("xc7k355ti-db-dat", "e4b551c4ede49ba8f1075c3144df4f96"));
        dataVersionMap.put("data/devices/kintex7/xc7k355tl_db.dat", new Pair<>("xc7k355tl-db-dat", "65e5cf2a8b57585b4b0f78bc386be5d6"));
        dataVersionMap.put("data/devices/kintex7/xc7k410t_db.dat", new Pair<>("xc7k410t-db-dat", "38710c60ad6f603cc86b7fb4e1703917"));
        dataVersionMap.put("data/devices/kintex7/xc7k410ti_db.dat", new Pair<>("xc7k410ti-db-dat", "feea31c01bf2c392ca5764a8b7c4d99c"));
        dataVersionMap.put("data/devices/kintex7/xc7k410tl_db.dat", new Pair<>("xc7k410tl-db-dat", "a60629342e5eaf124f8e854283c5ddd9"));
        dataVersionMap.put("data/devices/kintex7/xc7k420t_db.dat", new Pair<>("xc7k420t-db-dat", "8a938e3eaa22211a4955d2da39a4fb37"));
        dataVersionMap.put("data/devices/kintex7/xc7k420ti_db.dat", new Pair<>("xc7k420ti-db-dat", "58523d66a28c4ab82df772773c4878ab"));
        dataVersionMap.put("data/devices/kintex7/xc7k420tl_db.dat", new Pair<>("xc7k420tl-db-dat", "9489d1846380ad76858b625909b1f1fe"));
        dataVersionMap.put("data/devices/kintex7/xc7k480t_db.dat", new Pair<>("xc7k480t-db-dat", "060fc872285ce6bfe619cf2538126109"));
        dataVersionMap.put("data/devices/kintex7/xc7k480ti_db.dat", new Pair<>("xc7k480ti-db-dat", "09ef287e793632a73d877c815eddbd67"));
        dataVersionMap.put("data/devices/kintex7/xc7k480tl_db.dat", new Pair<>("xc7k480tl-db-dat", "9c1a0065892ae4cec4b89a2c6c211c34"));
        dataVersionMap.put("data/devices/kintex7/xc7k70t_db.dat", new Pair<>("xc7k70t-db-dat", "cc007fa28451a5ca6c6b9bcd3ae2c3e4"));
        dataVersionMap.put("data/devices/kintex7/xc7k70tl_db.dat", new Pair<>("xc7k70tl-db-dat", "43bb7f7761ffad9f0f3346bdf8c0f030"));
        dataVersionMap.put("data/devices/kintex7/xq7k325t_db.dat", new Pair<>("xq7k325t-db-dat", "0a1dda35cf24511bf71eb4d55cd8f72e"));
        dataVersionMap.put("data/devices/kintex7/xq7k325tl_db.dat", new Pair<>("xq7k325tl-db-dat", "7f9de0ba82322223253ec1992d835296"));
        dataVersionMap.put("data/devices/kintex7/xq7k410t_db.dat", new Pair<>("xq7k410t-db-dat", "f17f9c5453833774cc235cb32cdb5eee"));
        dataVersionMap.put("data/devices/kintex7/xq7k410tl_db.dat", new Pair<>("xq7k410tl-db-dat", "b9854a84a72150daa764f35a00e56a6e"));
        dataVersionMap.put("data/devices/kintexu/xcku025_db.dat", new Pair<>("xcku025-db-dat", "7263d5be8949e450a5825c928eeebadb"));
        dataVersionMap.put("data/devices/kintexu/xcku035_db.dat", new Pair<>("xcku035-db-dat", "2400ea5d0984f1509a7e17beda37c27b"));
        dataVersionMap.put("data/devices/kintexu/xcku040_db.dat", new Pair<>("xcku040-db-dat", "7d773d726219e914465f1b2218b3dc30"));
        dataVersionMap.put("data/devices/kintexu/xcku060_CIV_db.dat", new Pair<>("xcku060-civ-db-dat", "afd0332c6e88e63e179f20ea36fbaa96"));
        dataVersionMap.put("data/devices/kintexu/xcku060_db.dat", new Pair<>("xcku060-db-dat", "a2cf02cb599647551346fbafc6ce4a81"));
        dataVersionMap.put("data/devices/kintexu/xcku085_CIV_db.dat", new Pair<>("xcku085-civ-db-dat", "a72dd643bb85d6562b2dd6ee3ae0bfd3"));
        dataVersionMap.put("data/devices/kintexu/xcku085_db.dat", new Pair<>("xcku085-db-dat", "252d2d69c530a2eb5027321f5377a60e"));
        dataVersionMap.put("data/devices/kintexu/xcku095_CIV_db.dat", new Pair<>("xcku095-civ-db-dat", "5e35ff10dd618c39d14bd5f55775458b"));
        dataVersionMap.put("data/devices/kintexu/xcku095_db.dat", new Pair<>("xcku095-db-dat", "390461800a17e14d7d114d94704f57ca"));
        dataVersionMap.put("data/devices/kintexu/xcku115_CIV_db.dat", new Pair<>("xcku115-civ-db-dat", "61a0efc19c2cec4282298d85c6ae56df"));
        dataVersionMap.put("data/devices/kintexu/xcku115_db.dat", new Pair<>("xcku115-db-dat", "1f13fa8e90009afbdd29b1affde33252"));
        dataVersionMap.put("data/devices/kintexu/xqku040_db.dat", new Pair<>("xqku040-db-dat", "af51933bea1e6177ed9f03d005a01bbd"));
        dataVersionMap.put("data/devices/kintexu/xqku060_db.dat", new Pair<>("xqku060-db-dat", "ebee8d28bcc9a6f95658faf8660f8b81"));
        dataVersionMap.put("data/devices/kintexu/xqku095_db.dat", new Pair<>("xqku095-db-dat", "a66978f5ceeb0fd46d7275eb49a25ba4"));
        dataVersionMap.put("data/devices/kintexu/xqku115_db.dat", new Pair<>("xqku115-db-dat", "a21b4d53ab7ffeb6912a26c570635f7b"));
        dataVersionMap.put("data/devices/kintexu/xqrku060_db.dat", new Pair<>("xqrku060-db-dat", "1c0e3949067523a6010213c72c427ca0"));
        dataVersionMap.put("data/devices/kintexuplus/xcau10p_db.dat", new Pair<>("xcau10p-db-dat", "7d8d7604840221fcc37a132fbe7e45fd"));
        dataVersionMap.put("data/devices/kintexuplus/xcau15p_db.dat", new Pair<>("xcau15p-db-dat", "ac229de3e33665cddab1fb81d57b0e6d"));
        dataVersionMap.put("data/devices/kintexuplus/xcau20p_db.dat", new Pair<>("xcau20p-db-dat", "58f456c63016da1300841e298d146282"));
        dataVersionMap.put("data/devices/kintexuplus/xcau25p_db.dat", new Pair<>("xcau25p-db-dat", "6781e16bca9029d657308e9d6a756422"));
        dataVersionMap.put("data/devices/kintexuplus/xcku11p_CIV_db.dat", new Pair<>("xcku11p-civ-db-dat", "b7dbfbbccbff1ff66361771f3da74bde"));
        dataVersionMap.put("data/devices/kintexuplus/xcku11p_db.dat", new Pair<>("xcku11p-db-dat", "ff5bd1cab3a42b6b8f69141aa0079d63"));
        dataVersionMap.put("data/devices/kintexuplus/xcku13p_db.dat", new Pair<>("xcku13p-db-dat", "a359893fd031d2dcbe5160890d766c60"));
        dataVersionMap.put("data/devices/kintexuplus/xcku15p_CIV_db.dat", new Pair<>("xcku15p-civ-db-dat", "3f97418c0436ebc9c8658a822a858f53"));
        dataVersionMap.put("data/devices/kintexuplus/xcku15p_db.dat", new Pair<>("xcku15p-db-dat", "d330233aec1188b705128f5dcac368e1"));
        dataVersionMap.put("data/devices/kintexuplus/xcku19p_CIV_db.dat", new Pair<>("xcku19p-civ-db-dat", "f2e7b905253b8ffdbc5e83e9a4f6058f"));
        dataVersionMap.put("data/devices/kintexuplus/xcku19p_db.dat", new Pair<>("xcku19p-db-dat", "2c8fbce387d3ebd22226c49db598553a"));
        dataVersionMap.put("data/devices/kintexuplus/xcku3p_db.dat", new Pair<>("xcku3p-db-dat", "ab1ede2c532e040c9d9a053502b8a4df"));
        dataVersionMap.put("data/devices/kintexuplus/xcku5p_db.dat", new Pair<>("xcku5p-db-dat", "a2476dd55b46b877205b5df9b9db1cb6"));
        dataVersionMap.put("data/devices/kintexuplus/xcku9p_db.dat", new Pair<>("xcku9p-db-dat", "7150c29bd0e076f5d5e904cf4b153351"));
        dataVersionMap.put("data/devices/kintexuplus/xqku15p_db.dat", new Pair<>("xqku15p-db-dat", "505c1ee1e12551213a4751800b44bd86"));
        dataVersionMap.put("data/devices/kintexuplus/xqku5p_db.dat", new Pair<>("xqku5p-db-dat", "800abf14620856b8595d17549700f826"));
        dataVersionMap.put("data/devices/spartan7/xa7s100_db.dat", new Pair<>("xa7s100-db-dat", "aedf0accf31981f0a22a452918ee8bd2"));
        dataVersionMap.put("data/devices/spartan7/xa7s15_db.dat", new Pair<>("xa7s15-db-dat", "fea21ad66591982e98187d4ec282da5e"));
        dataVersionMap.put("data/devices/spartan7/xa7s25_db.dat", new Pair<>("xa7s25-db-dat", "4e03862775e9f561a1ceb73444fb176d"));
        dataVersionMap.put("data/devices/spartan7/xa7s50_db.dat", new Pair<>("xa7s50-db-dat", "de12e45f567330a8f697dd7543a6ba5d"));
        dataVersionMap.put("data/devices/spartan7/xa7s6_db.dat", new Pair<>("xa7s6-db-dat", "b7f4e3e92d8e5159de9b4505bee04a6d"));
        dataVersionMap.put("data/devices/spartan7/xa7s75_db.dat", new Pair<>("xa7s75-db-dat", "145234500fef1035bb062a5b3fa37384"));
        dataVersionMap.put("data/devices/spartan7/xc7s100_db.dat", new Pair<>("xc7s100-db-dat", "8af7df525813b11528d093748db31520"));
        dataVersionMap.put("data/devices/spartan7/xc7s15_db.dat", new Pair<>("xc7s15-db-dat", "a92f9d7c68c4c129917c58da85d2f9ba"));
        dataVersionMap.put("data/devices/spartan7/xc7s25_db.dat", new Pair<>("xc7s25-db-dat", "2c1a09a2c4a5d54834e8e3548c9c8f18"));
        dataVersionMap.put("data/devices/spartan7/xc7s50_db.dat", new Pair<>("xc7s50-db-dat", "9cc4717b5a98df9e8c372e9d4089aa43"));
        dataVersionMap.put("data/devices/spartan7/xc7s6_db.dat", new Pair<>("xc7s6-db-dat", "851f46fa81b9d35c52336aec4ded2899"));
        dataVersionMap.put("data/devices/spartan7/xc7s75_db.dat", new Pair<>("xc7s75-db-dat", "6e95c41abe0a82f35d32712661bcf5a0"));
        dataVersionMap.put("data/devices/versal/xcvc1502_db.dat", new Pair<>("xcvc1502-db-dat", "f8dbd40bea1ebfe6288e32357fb72297"));
        dataVersionMap.put("data/devices/versal/xcvc1702_db.dat", new Pair<>("xcvc1702-db-dat", "4ed292855857e33d960672e31433e4e6"));
        dataVersionMap.put("data/devices/versal/xcvc1802_db.dat", new Pair<>("xcvc1802-db-dat", "67a62aaf1fedaa4ef8174bb70665133e"));
        dataVersionMap.put("data/devices/versal/xcvc1902_db.dat", new Pair<>("xcvc1902-db-dat", "c6a94b67a20bfd978c8717c8ef9c5f5f"));
        dataVersionMap.put("data/devices/versal/xcve1752_db.dat", new Pair<>("xcve1752-db-dat", "2b94e45c16d9e6ee1c7847f0b421743f"));
        dataVersionMap.put("data/devices/versal/xcvm1302_db.dat", new Pair<>("xcvm1302-db-dat", "c215d5c02471c1542503f3cdcfecc28a"));
        dataVersionMap.put("data/devices/versal/xcvm1402_db.dat", new Pair<>("xcvm1402-db-dat", "cbcbbccb0f059f8350ea878e14fda933"));
        dataVersionMap.put("data/devices/versal/xcvm1502_db.dat", new Pair<>("xcvm1502-db-dat", "083ef41688af184e17469d0c1c02e12e"));
        dataVersionMap.put("data/devices/versal/xcvm1802_db.dat", new Pair<>("xcvm1802-db-dat", "a8b634c77c67deb2877d3f993d8fa5d1"));
        dataVersionMap.put("data/devices/versal/xcvp1202_db.dat", new Pair<>("xcvp1202-db-dat", "9d28ed65515363440fc5d14c5e2fc2c1"));
        dataVersionMap.put("data/devices/versal/xqrvc1902_db.dat", new Pair<>("xqrvc1902-db-dat", "851f4b04c0d5ced1021f266e7e4b3605"));
        dataVersionMap.put("data/devices/versal/xqvc1902_db.dat", new Pair<>("xqvc1902-db-dat", "769051a67bfc59761eccb115b4294738"));
        dataVersionMap.put("data/devices/versal/xqvm1802_db.dat", new Pair<>("xqvm1802-db-dat", "05063ede342b45d59b1a9cf10c610d1c"));
        dataVersionMap.put("data/devices/virtex7/xc7v2000t_db.dat", new Pair<>("xc7v2000t-db-dat", "0bc87c148ef78908d71b315a4dc5b1aa"));
        dataVersionMap.put("data/devices/virtex7/xc7v585t_db.dat", new Pair<>("xc7v585t-db-dat", "6ffe82312ad4a36a9926a2f776c90830"));
        dataVersionMap.put("data/devices/virtex7/xc7vh580t_db.dat", new Pair<>("xc7vh580t-db-dat", "eb6445ce9bb7b7b9e7301363d538ca8c"));
        dataVersionMap.put("data/devices/virtex7/xc7vh870t_db.dat", new Pair<>("xc7vh870t-db-dat", "08459bfa546b74c05f8938729c73d2c5"));
        dataVersionMap.put("data/devices/virtex7/xc7vx1140t_db.dat", new Pair<>("xc7vx1140t-db-dat", "e6183c10e97eda744d21c2c46beafc86"));
        dataVersionMap.put("data/devices/virtex7/xc7vx330t_db.dat", new Pair<>("xc7vx330t-db-dat", "b876f305043da119e74890a2eeb019b0"));
        dataVersionMap.put("data/devices/virtex7/xc7vx415t_CIV_db.dat", new Pair<>("xc7vx415t-civ-db-dat", "1c88e36ef4f444247327e89944e4cf1b"));
        dataVersionMap.put("data/devices/virtex7/xc7vx415t_db.dat", new Pair<>("xc7vx415t-db-dat", "ed7f4c6f9937371f80d234a9eda73af3"));
        dataVersionMap.put("data/devices/virtex7/xc7vx485t_db.dat", new Pair<>("xc7vx485t-db-dat", "2f6af637a28de678efb042977cbf2642"));
        dataVersionMap.put("data/devices/virtex7/xc7vx550t_CIV_db.dat", new Pair<>("xc7vx550t-civ-db-dat", "ec1342e28ec5f2c563e672daa2ea2761"));
        dataVersionMap.put("data/devices/virtex7/xc7vx550t_db.dat", new Pair<>("xc7vx550t-db-dat", "2e40b2533d3a1eb3656a3b7ff6ed509f"));
        dataVersionMap.put("data/devices/virtex7/xc7vx690t_CIV_db.dat", new Pair<>("xc7vx690t-civ-db-dat", "d54c303b33ffd2cf69729c702c54236a"));
        dataVersionMap.put("data/devices/virtex7/xc7vx690t_db.dat", new Pair<>("xc7vx690t-db-dat", "7890af9aa996d1698b5c429d1672ff29"));
        dataVersionMap.put("data/devices/virtex7/xc7vx980t_db.dat", new Pair<>("xc7vx980t-db-dat", "0e89c2a421376fa37821b9034c129d9a"));
        dataVersionMap.put("data/devices/virtex7/xq7v585t_db.dat", new Pair<>("xq7v585t-db-dat", "0ff97bfd7942e0be1c6efbdf3acad1eb"));
        dataVersionMap.put("data/devices/virtex7/xq7vx330t_db.dat", new Pair<>("xq7vx330t-db-dat", "e1321201732828453fd09c3081b6e6e5"));
        dataVersionMap.put("data/devices/virtex7/xq7vx485t_db.dat", new Pair<>("xq7vx485t-db-dat", "cddf5bcb205f558ca11d74584b8f5f40"));
        dataVersionMap.put("data/devices/virtex7/xq7vx690t_db.dat", new Pair<>("xq7vx690t-db-dat", "78167f6adccb624a316134010a5988c4"));
        dataVersionMap.put("data/devices/virtex7/xq7vx980t_db.dat", new Pair<>("xq7vx980t-db-dat", "f9c1af2deb1f3313e84ebc034e4e063a"));
        dataVersionMap.put("data/devices/virtexu/xcvu065_CIV_db.dat", new Pair<>("xcvu065-civ-db-dat", "3c67283675de7518b8e0b8c3495028a3"));
        dataVersionMap.put("data/devices/virtexu/xcvu065_db.dat", new Pair<>("xcvu065-db-dat", "6a1aecc99a9ceaa43cfca8e2f445f8c0"));
        dataVersionMap.put("data/devices/virtexu/xcvu080_CIV_db.dat", new Pair<>("xcvu080-civ-db-dat", "1c5d18afe0a766b8872131705cf90838"));
        dataVersionMap.put("data/devices/virtexu/xcvu080_db.dat", new Pair<>("xcvu080-db-dat", "fa76d9ac6166d9558922556be70c4d7f"));
        dataVersionMap.put("data/devices/virtexu/xcvu095_CIV_db.dat", new Pair<>("xcvu095-civ-db-dat", "9c4e1638c15563f81d186a517aa9e099"));
        dataVersionMap.put("data/devices/virtexu/xcvu095_db.dat", new Pair<>("xcvu095-db-dat", "ccde5af5bb1c561c452feffa0e03b352"));
        dataVersionMap.put("data/devices/virtexu/xcvu125_CIV_db.dat", new Pair<>("xcvu125-civ-db-dat", "035ab81b63532a906586f4f30272c56b"));
        dataVersionMap.put("data/devices/virtexu/xcvu125_db.dat", new Pair<>("xcvu125-db-dat", "e7d9d3a015ac2f2549ac80e73f8d8916"));
        dataVersionMap.put("data/devices/virtexu/xcvu160_CIV_db.dat", new Pair<>("xcvu160-civ-db-dat", "784dd3fb14f54ea7422c55db686be55c"));
        dataVersionMap.put("data/devices/virtexu/xcvu160_db.dat", new Pair<>("xcvu160-db-dat", "7b6f25b05ee5869bebb38fcad60da8c5"));
        dataVersionMap.put("data/devices/virtexu/xcvu190_CIV_db.dat", new Pair<>("xcvu190-civ-db-dat", "d24debb7e948ebe519aa37aaf4123bbb"));
        dataVersionMap.put("data/devices/virtexu/xcvu190_db.dat", new Pair<>("xcvu190-db-dat", "fb072bc2a649a8b4b80f6ee3794b04f7"));
        dataVersionMap.put("data/devices/virtexu/xcvu440_CIV_db.dat", new Pair<>("xcvu440-civ-db-dat", "7f9994b01c57861cfd4bb05d4512b5b0"));
        dataVersionMap.put("data/devices/virtexu/xcvu440_db.dat", new Pair<>("xcvu440-db-dat", "17f67d5f0e9d8f539fb4cb5248d5c7a1"));
        dataVersionMap.put("data/devices/virtexuplus/xcu200_db.dat", new Pair<>("xcu200-db-dat", "24b8b2f2969c798bbbe867ec402ea8ad"));
        dataVersionMap.put("data/devices/virtexuplus/xcu250_db.dat", new Pair<>("xcu250-db-dat", "0ba805e45cea88c25b9e5e6635e54de2"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu11p_CIV_db.dat", new Pair<>("xcvu11p-civ-db-dat", "a45116ac323c700e280856284fdec20b"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu11p_db.dat", new Pair<>("xcvu11p-db-dat", "2183d772dbc0b8319ca6a39388e5f494"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu13p_CIV_db.dat", new Pair<>("xcvu13p-civ-db-dat", "93fa276ab7ca8d5ad0a8994d699648e4"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu13p_db.dat", new Pair<>("xcvu13p-db-dat", "19b7276f765e2b6bb20375e199d5864a"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu19p_CIV_db.dat", new Pair<>("xcvu19p-civ-db-dat", "9c18fb993e4c966ad1805b332701a93d"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu19p_db.dat", new Pair<>("xcvu19p-db-dat", "d0bc8923130a7dfbfcfa9fc0de771700"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu3p_CIV_db.dat", new Pair<>("xcvu3p-civ-db-dat", "01ae266e4dd2bb8736127e78ab9251fa"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu3p_db.dat", new Pair<>("xcvu3p-db-dat", "2367588298f9ae4c7a338116dceb0346"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu5p_CIV_db.dat", new Pair<>("xcvu5p-civ-db-dat", "d8ce786a9180b35608dc6cd0b92834aa"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu5p_db.dat", new Pair<>("xcvu5p-db-dat", "01867830f230fbd9228594b3cb28b742"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu7p_CIV_db.dat", new Pair<>("xcvu7p-civ-db-dat", "e789ef41ff75c1dcef585f97dac94ceb"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu7p_db.dat", new Pair<>("xcvu7p-db-dat", "99721616de694aa92b9d0515bb902ff7"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu9p_CIV_db.dat", new Pair<>("xcvu9p-civ-db-dat", "fd5cef122feab42106d263b32084fb79"));
        dataVersionMap.put("data/devices/virtexuplus/xcvu9p_db.dat", new Pair<>("xcvu9p-db-dat", "65d6ed36a56de99f6344264c3fcbd206"));
        dataVersionMap.put("data/devices/virtexuplus/xqvu11p_db.dat", new Pair<>("xqvu11p-db-dat", "a94bc84250120b99789684f3a5efe073"));
        dataVersionMap.put("data/devices/virtexuplus/xqvu13p_db.dat", new Pair<>("xqvu13p-db-dat", "a836461e4a9f4f1e1c18a5a431faaee7"));
        dataVersionMap.put("data/devices/virtexuplus/xqvu3p_db.dat", new Pair<>("xqvu3p-db-dat", "fc9aff73ffe0cfb48d1d61a73b04a32b"));
        dataVersionMap.put("data/devices/virtexuplus/xqvu7p_db.dat", new Pair<>("xqvu7p-db-dat", "86582c3daadc6ce2ce1c453a708d10d0"));
        dataVersionMap.put("data/devices/virtexuplus/xqvu9p_db.dat", new Pair<>("xqvu9p-db-dat", "4b2b64b30be7859b0676c19bba6bf103"));
        dataVersionMap.put("data/devices/virtexuplus58g/xcu26_db.dat", new Pair<>("xcu26-db-dat", "265f0a75745ba6f693ba33a271f710b5"));
        dataVersionMap.put("data/devices/virtexuplus58g/xcux35_db.dat", new Pair<>("xcux35-db-dat", "80a5209a1e9e8f7a080f887d757b70d7"));
        dataVersionMap.put("data/devices/virtexuplus58g/xcvu23p_CIV_db.dat", new Pair<>("xcvu23p-civ-db-dat", "d544951e89a41d34b2513990f2e968da"));
        dataVersionMap.put("data/devices/virtexuplus58g/xcvu23p_db.dat", new Pair<>("xcvu23p-db-dat", "b69d7871846673b0528e491b3e0aade3"));
        dataVersionMap.put("data/devices/virtexuplus58g/xcvu27p_db.dat", new Pair<>("xcvu27p-db-dat", "a1703c1ce38da29c63da4c16eec4e646"));
        dataVersionMap.put("data/devices/virtexuplus58g/xcvu29p_CIV_db.dat", new Pair<>("xcvu29p-civ-db-dat", "5d133648fdc31f57d68f6701b210b0d1"));
        dataVersionMap.put("data/devices/virtexuplus58g/xcvu29p_db.dat", new Pair<>("xcvu29p-db-dat", "ade2489bdf7341f1f9fb46c4e30bc400"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcu280_db.dat", new Pair<>("xcu280-db-dat", "2b3cada340105d7f6ac59e6e31afda05"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcu50_db.dat", new Pair<>("xcu50-db-dat", "e08ef40617d1ec4c2cdb8d8c6a54a5be"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcu55c_db.dat", new Pair<>("xcu55c-db-dat", "a5ef42f025643336d3c9e55c6c869999"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcu55n_db.dat", new Pair<>("xcu55n-db-dat", "81d6b32bb9f8b64e16179d9feaaaaeb9"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu31p_CIV_db.dat", new Pair<>("xcvu31p-civ-db-dat", "cb41be786989009e573f0949e52f8096"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu31p_db.dat", new Pair<>("xcvu31p-db-dat", "58cda5c8ba501c52edd7ab5bcbcc16a3"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu33p_CIV_db.dat", new Pair<>("xcvu33p-civ-db-dat", "1f151aa34eeca64b665fa012a03acb73"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu33p_db.dat", new Pair<>("xcvu33p-db-dat", "356b71b828af7a7501df1b5ee0423d1d"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu35p_CIV_db.dat", new Pair<>("xcvu35p-civ-db-dat", "923c297524f053aadf7705ea5251c8e6"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu35p_db.dat", new Pair<>("xcvu35p-db-dat", "9bc381a834f5af03a11e6c29158a4354"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu37p_CIV_db.dat", new Pair<>("xcvu37p-civ-db-dat", "4e9192559171b3fde5905ab04527b1fd"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu37p_db.dat", new Pair<>("xcvu37p-db-dat", "c465d1cc38d3db508a2ebba851b25547"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu45p_CIV_db.dat", new Pair<>("xcvu45p-civ-db-dat", "72c3968f396818fd65291b8a92725cc5"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu45p_db.dat", new Pair<>("xcvu45p-db-dat", "036829b856546cf4e270b937d3fb9fcd"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu47p_CIV_db.dat", new Pair<>("xcvu47p-civ-db-dat", "1c0f53ce1bf33cc607b52416e4b47ca8"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu47p_db.dat", new Pair<>("xcvu47p-db-dat", "bef4d9d9fefe1debdb131ec46510b11d"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu57p_CIV_db.dat", new Pair<>("xcvu57p-civ-db-dat", "37c2911eca1b991b7bc1e943a7e8b060"));
        dataVersionMap.put("data/devices/virtexuplushbm/xcvu57p_db.dat", new Pair<>("xcvu57p-db-dat", "48e522ca06e2157ac8fe86c48f1d7225"));
        dataVersionMap.put("data/devices/virtexuplushbm/xqvu37p_db.dat", new Pair<>("xqvu37p-db-dat", "6d577998219694f11b450f373adfa3c8"));
        dataVersionMap.put("data/devices/virtexuplushbmes1/xcu280-es1_db.dat", new Pair<>("xcu280-es1-db-dat", "b92c16ac11a7797fed461a93e170f0a6"));
        dataVersionMap.put("data/devices/zynq/xa7z010_db.dat", new Pair<>("xa7z010-db-dat", "8d544160994736fa070ee44ad1e8238d"));
        dataVersionMap.put("data/devices/zynq/xa7z020_db.dat", new Pair<>("xa7z020-db-dat", "a1f6045c34eb9887fe24e0bcbdea4849"));
        dataVersionMap.put("data/devices/zynq/xa7z030_db.dat", new Pair<>("xa7z030-db-dat", "994c0fbeee74de1e4f4b1baa8f03a497"));
        dataVersionMap.put("data/devices/zynq/xc7z007s_db.dat", new Pair<>("xc7z007s-db-dat", "468745784c322327cf423a7ecc232c91"));
        dataVersionMap.put("data/devices/zynq/xc7z010_db.dat", new Pair<>("xc7z010-db-dat", "d4e8ec879ea478d304c871b21d9b4568"));
        dataVersionMap.put("data/devices/zynq/xc7z010i_db.dat", new Pair<>("xc7z010i-db-dat", "460d658593005f27c369d07f81cb387d"));
        dataVersionMap.put("data/devices/zynq/xc7z012s_db.dat", new Pair<>("xc7z012s-db-dat", "e00e73ffdc62cbbda5e4a483a18a4958"));
        dataVersionMap.put("data/devices/zynq/xc7z014s_db.dat", new Pair<>("xc7z014s-db-dat", "6d92d9475da7f0eadb00cfeba2bc4f98"));
        dataVersionMap.put("data/devices/zynq/xc7z015_db.dat", new Pair<>("xc7z015-db-dat", "0554bd519236e7edacccd87c9edf7e31"));
        dataVersionMap.put("data/devices/zynq/xc7z015i_db.dat", new Pair<>("xc7z015i-db-dat", "9365cd60c7f8d129ccdbcf260b0c0d3c"));
        dataVersionMap.put("data/devices/zynq/xc7z020_db.dat", new Pair<>("xc7z020-db-dat", "21ae2dcc32925b47170239c53821cacd"));
        dataVersionMap.put("data/devices/zynq/xc7z020i_db.dat", new Pair<>("xc7z020i-db-dat", "d59f5809eee17863fc27b4499c94e4f8"));
        dataVersionMap.put("data/devices/zynq/xc7z030_db.dat", new Pair<>("xc7z030-db-dat", "63ef2bf376316aa1150dd470d1c9f432"));
        dataVersionMap.put("data/devices/zynq/xc7z030i_db.dat", new Pair<>("xc7z030i-db-dat", "f3ab67444c3a8a61f6ca979b08e66e6c"));
        dataVersionMap.put("data/devices/zynq/xc7z035_db.dat", new Pair<>("xc7z035-db-dat", "5a2b7096c626a9468543c7031a0b0647"));
        dataVersionMap.put("data/devices/zynq/xc7z035i_db.dat", new Pair<>("xc7z035i-db-dat", "7372e68252d5a0c18a6e06b062109d1b"));
        dataVersionMap.put("data/devices/zynq/xc7z045_db.dat", new Pair<>("xc7z045-db-dat", "ec74196dd68ade5e426478aca22b6a37"));
        dataVersionMap.put("data/devices/zynq/xc7z045i_db.dat", new Pair<>("xc7z045i-db-dat", "66aa1bb55d5a66d25827c63361c59dab"));
        dataVersionMap.put("data/devices/zynq/xc7z100_db.dat", new Pair<>("xc7z100-db-dat", "4cde7319b26cf0ff7d97de64838ee655"));
        dataVersionMap.put("data/devices/zynq/xc7z100i_db.dat", new Pair<>("xc7z100i-db-dat", "c5884bfbe5bb83b9e795440641f2f1ee"));
        dataVersionMap.put("data/devices/zynq/xq7z020_db.dat", new Pair<>("xq7z020-db-dat", "0153a48023d44cf598fc4bff64b24c5c"));
        dataVersionMap.put("data/devices/zynq/xq7z030_db.dat", new Pair<>("xq7z030-db-dat", "849be420f07fa3b48a7635f700b2e49b"));
        dataVersionMap.put("data/devices/zynq/xq7z045_db.dat", new Pair<>("xq7z045-db-dat", "d5ffbb2851b382b5be51852b3306c12d"));
        dataVersionMap.put("data/devices/zynq/xq7z100_db.dat", new Pair<>("xq7z100-db-dat", "cef35df94622413f5ba908bd3e23db9a"));
        dataVersionMap.put("data/devices/zynquplus/xazu11eg_db.dat", new Pair<>("xazu11eg-db-dat", "f29e3a74b8ee9bd2fcc82f0191c8f9d7"));
        dataVersionMap.put("data/devices/zynquplus/xazu1eg_db.dat", new Pair<>("xazu1eg-db-dat", "c698fe4b588ed2717a556f6728150853"));
        dataVersionMap.put("data/devices/zynquplus/xazu2eg_db.dat", new Pair<>("xazu2eg-db-dat", "1c03760ed04cf57e9ea815bca79c7516"));
        dataVersionMap.put("data/devices/zynquplus/xazu3eg_db.dat", new Pair<>("xazu3eg-db-dat", "9870791813eff7a61ffe27b2ae0d9e79"));
        dataVersionMap.put("data/devices/zynquplus/xazu4ev_db.dat", new Pair<>("xazu4ev-db-dat", "74e5145d072cb5982214d5dbd0deed21"));
        dataVersionMap.put("data/devices/zynquplus/xazu5ev_db.dat", new Pair<>("xazu5ev-db-dat", "77a9290e22051791ceaa33639a7856fd"));
        dataVersionMap.put("data/devices/zynquplus/xazu7ev_db.dat", new Pair<>("xazu7ev-db-dat", "fea4926f50bc856f7b6e05028728f8f3"));
        dataVersionMap.put("data/devices/zynquplus/xck26_db.dat", new Pair<>("xck26-db-dat", "8850ec0fc9cd3856ea4405af0985d233"));
        dataVersionMap.put("data/devices/zynquplus/xcu25_db.dat", new Pair<>("xcu25-db-dat", "b0b8a8774e9a8302afc0665d8273aa06"));
        dataVersionMap.put("data/devices/zynquplus/xcu30_db.dat", new Pair<>("xcu30-db-dat", "9c7e4c9576b58066b01f620ba3dcaaf5"));
        dataVersionMap.put("data/devices/zynquplus/xczu11eg_db.dat", new Pair<>("xczu11eg-db-dat", "b4ee472fa70d8d555f1f22ae5a485f64"));
        dataVersionMap.put("data/devices/zynquplus/xczu15eg_db.dat", new Pair<>("xczu15eg-db-dat", "47b1879994afb26298c4fb6415721efe"));
        dataVersionMap.put("data/devices/zynquplus/xczu17eg_db.dat", new Pair<>("xczu17eg-db-dat", "a5e2a0d3b49c15b9205d7de873df9310"));
        dataVersionMap.put("data/devices/zynquplus/xczu19eg_db.dat", new Pair<>("xczu19eg-db-dat", "24f184e593a4436236312eefe05559e6"));
        dataVersionMap.put("data/devices/zynquplus/xczu1cg_db.dat", new Pair<>("xczu1cg-db-dat", "85b613c31cb15a372ec19052aa207874"));
        dataVersionMap.put("data/devices/zynquplus/xczu1eg_db.dat", new Pair<>("xczu1eg-db-dat", "7f5c239e103db296af813ab415f830ae"));
        dataVersionMap.put("data/devices/zynquplus/xczu2cg_db.dat", new Pair<>("xczu2cg-db-dat", "8427235b3dfd74be40c17aa36e9e4449"));
        dataVersionMap.put("data/devices/zynquplus/xczu2eg_db.dat", new Pair<>("xczu2eg-db-dat", "4e303681923054b6795d7a7204707590"));
        dataVersionMap.put("data/devices/zynquplus/xczu3cg_db.dat", new Pair<>("xczu3cg-db-dat", "e7a9a01c9856cc20b771bfb47e3f45d7"));
        dataVersionMap.put("data/devices/zynquplus/xczu3eg_db.dat", new Pair<>("xczu3eg-db-dat", "88adbd69f5b62b833e54cadf6d91cd2d"));
        dataVersionMap.put("data/devices/zynquplus/xczu4cg_db.dat", new Pair<>("xczu4cg-db-dat", "2ccb482eeb45d9a78d243b0658084a09"));
        dataVersionMap.put("data/devices/zynquplus/xczu4eg_db.dat", new Pair<>("xczu4eg-db-dat", "178c9d6172db22438f1c056765247385"));
        dataVersionMap.put("data/devices/zynquplus/xczu4ev_db.dat", new Pair<>("xczu4ev-db-dat", "8e2fd0e0100ffacc4ccce805467608fd"));
        dataVersionMap.put("data/devices/zynquplus/xczu5cg_db.dat", new Pair<>("xczu5cg-db-dat", "f9f2026fd5f894b7803484ba32952e33"));
        dataVersionMap.put("data/devices/zynquplus/xczu5eg_db.dat", new Pair<>("xczu5eg-db-dat", "79819673d1e1beb6b633b11b7cd4c83a"));
        dataVersionMap.put("data/devices/zynquplus/xczu5ev_db.dat", new Pair<>("xczu5ev-db-dat", "99a5887b74f4d2de69e237a443bc4760"));
        dataVersionMap.put("data/devices/zynquplus/xczu6cg_db.dat", new Pair<>("xczu6cg-db-dat", "4db28e2ccafba42d77ffff0826ce69bc"));
        dataVersionMap.put("data/devices/zynquplus/xczu6eg_db.dat", new Pair<>("xczu6eg-db-dat", "abc624fd449bf26b1707c3cca4403224"));
        dataVersionMap.put("data/devices/zynquplus/xczu7cg_db.dat", new Pair<>("xczu7cg-db-dat", "057899a59bac541ae3f9e19d814609aa"));
        dataVersionMap.put("data/devices/zynquplus/xczu7eg_db.dat", new Pair<>("xczu7eg-db-dat", "c6de07d7e98a42c416ac71772d39816e"));
        dataVersionMap.put("data/devices/zynquplus/xczu7ev_db.dat", new Pair<>("xczu7ev-db-dat", "4272e6ee775e6a58fb4bfb2be7d1fc4d"));
        dataVersionMap.put("data/devices/zynquplus/xczu9cg_db.dat", new Pair<>("xczu9cg-db-dat", "106bdf66cbde70e90bb5ceabca97e782"));
        dataVersionMap.put("data/devices/zynquplus/xczu9eg_db.dat", new Pair<>("xczu9eg-db-dat", "8934fedd905301956c7ac621a0a07d52"));
        dataVersionMap.put("data/devices/zynquplus/xqzu11eg_db.dat", new Pair<>("xqzu11eg-db-dat", "47c0c6142ee9cd2271d9393c76f5cba7"));
        dataVersionMap.put("data/devices/zynquplus/xqzu15eg_db.dat", new Pair<>("xqzu15eg-db-dat", "fbb84f95e94420883612efdde7009c43"));
        dataVersionMap.put("data/devices/zynquplus/xqzu19eg_db.dat", new Pair<>("xqzu19eg-db-dat", "3cc0d6195a924fa5c746dce3855a4c91"));
        dataVersionMap.put("data/devices/zynquplus/xqzu3eg_db.dat", new Pair<>("xqzu3eg-db-dat", "34c82fdc474f0efd61883c0f169dfcd1"));
        dataVersionMap.put("data/devices/zynquplus/xqzu4eg_db.dat", new Pair<>("xqzu4eg-db-dat", "ff09c57b005d4f9a97f89a013c919bb9"));
        dataVersionMap.put("data/devices/zynquplus/xqzu5ev_db.dat", new Pair<>("xqzu5ev-db-dat", "4fb2c9d243516788a1b700008095dc47"));
        dataVersionMap.put("data/devices/zynquplus/xqzu7ev_db.dat", new Pair<>("xqzu7ev-db-dat", "4f45d8354611a0fb3f84c5d3a145fdea"));
        dataVersionMap.put("data/devices/zynquplus/xqzu9eg_db.dat", new Pair<>("xqzu9eg-db-dat", "a5fb1fa6cd93f1585392da08f9ba4dbf"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu21dr_db.dat", new Pair<>("xczu21dr-db-dat", "f0b2e57cb09179ab33d4a49ab623a062"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu25dr_db.dat", new Pair<>("xczu25dr-db-dat", "a2dfe337ad15c7ed76c4680d964f5a90"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu27dr_db.dat", new Pair<>("xczu27dr-db-dat", "6e81b8fb6e8f0a41fde3afe85b11bf70"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu28dr_db.dat", new Pair<>("xczu28dr-db-dat", "b26deb389807c91853caa29e656dc12e"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu29dr_db.dat", new Pair<>("xczu29dr-db-dat", "ff57060aaa46d627489c160a81d1b04a"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu39dr_db.dat", new Pair<>("xczu39dr-db-dat", "4da322490ff6bb9f83e8bfc1da943d76"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu42dr_db.dat", new Pair<>("xczu42dr-db-dat", "28b04abb07c880f9fe0a9b9ceae180e2"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu43dr_db.dat", new Pair<>("xczu43dr-db-dat", "9d1bffd1d084dc39ef6f0514312e3f67"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu46dr_db.dat", new Pair<>("xczu46dr-db-dat", "43eaf81a6e45a0cd83da1aa87fc6a2df"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu47dr_db.dat", new Pair<>("xczu47dr-db-dat", "05c5831e5fa22a6d4f83ed57c46165b3"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu48dr_db.dat", new Pair<>("xczu48dr-db-dat", "86487702b53b050c7ba8acbb0eb8d8ca"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xczu49dr_db.dat", new Pair<>("xczu49dr-db-dat", "a836065a7edb52d27d763530f1ea9150"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xqzu21dr_db.dat", new Pair<>("xqzu21dr-db-dat", "c702fee9852a2c16140da7f5fccdb0e8"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xqzu28dr_db.dat", new Pair<>("xqzu28dr-db-dat", "008ceb450b4c15818277d1ce07e9a3af"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xqzu29dr_db.dat", new Pair<>("xqzu29dr-db-dat", "987560ef69d1460e675580cac0e2bfb6"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xqzu48dr_db.dat", new Pair<>("xqzu48dr-db-dat", "bd8a39aa650c0ef44d6c1bb2872c3ae3"));
        dataVersionMap.put("data/devices/zynquplusrfsoc/xqzu49dr_db.dat", new Pair<>("xqzu49dr-db-dat", "87bcb60fd18bc8f7919567ed218f44f6"));
        dataVersionMap.put("data/partdump.csv", new Pair<>("partdump-csv", "7603dac93d02d96847280bee5a3c13d8"));
        dataVersionMap.put("data/parts.db", new Pair<>("parts-db", "bdf58c7fc465a0b1ae0077307959f24c"));
        dataVersionMap.put("data/unisim_data.dat", new Pair<>("unisim-data-dat", "959cf8542aba0584ec6c59130bbabd9e"));
    }
}
