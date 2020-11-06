package com.xilinx.rapidwright.interchange;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.AbstractMap;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.design.SiteInst;

public class EnumerateCellBelMapping {
    private static class StringPairCompare implements Comparator<Map.Entry<String, String>> {
        @Override
        public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
            int result = a.getKey().compareTo(b.getKey());
            if(result != 0) {
                return result;
            }

            return b.getValue().compareTo(b.getValue());
        }
    };

    private static void addSite(Map<SiteTypeEnum, List<Site>> site_map, Site site, SiteTypeEnum site_type) {
        List<Site> sites = site_map.get(site_type);
        if(sites == null) {
            sites = new ArrayList<Site>();
            site_map.put(site_type, sites);
        }

        sites.add(site);
    }

    public static List<List<String>> getParametersFor(String cell_name) {
        List<List<String>> parameter_sets = new ArrayList<List<String>>();
        parameter_sets.add(new ArrayList<String>());
        if(cell_name.equals("RAMB18E1") || cell_name.equals("RAMB18E2")) {
            int[] port_widths = {1, 2, 4, 9, 18};
            for(int read_width_a : port_widths) {
                for(int read_width_b : port_widths) {
                    for(int write_width_a : port_widths) {
                        for(int write_width_b : port_widths) {
                            List<String> parameters = new ArrayList<String>();
                            parameters.add(String.format("READ_WIDTH_A=%d", read_width_a));
                            parameters.add(String.format("READ_WIDTH_B=%d", read_width_b));
                            parameters.add(String.format("WRITE_WIDTH_A=%d", write_width_a));
                            parameters.add(String.format("WRITE_WIDTH_B=%d", write_width_b));

                            parameter_sets.add(parameters);
                        }
                    }
                }
            }
        }

        System.out.printf("%s - %d\n", cell_name, parameter_sets.size());

        return parameter_sets;
    }

    public static void main(String[] args) throws IOException {
        if(args.length != 1) {
            System.out.println("USAGE: <device name>");
            System.out.println("   Example dump of device information for interchange format.");
            return;
        }

        CodePerfTracker t = new CodePerfTracker("Enumerate Cell<->BEL mapping: " + args[0]);
        t.useGCToTrackMemory(true);

        t.start("Load Device");
        Device device = Device.getDevice(args[0]);
        t.stop();

        EDIFLibrary prims = Design.getPrimitivesLibrary();

        Design design = new Design("top", args[0]);

        // 7-series primitive list.
        String series7 = new String("AND2B1L AUTOBUF BSCANE2 BUF BUFG BUFG_LB BUFGCE BUFGCTRL BUFGMUX BUFH BUFHCE BUFIO BUFMR BUFMRCE BUFR CAPTUREE2 CARRY4 CFGLUT5 DCIRESET DNA_PORT DRP_AMS_ADC DRP_AMS_DAC DSP48E1 EFUSE_USR FDCE FDPE FDRE FDSE FIFO18E1 FIFO36E1 FRAME_ECCE2 GND GTHE2_CHANNEL GTHE2_COMMON GTPE2_CHANNEL GTPE2_COMMON GTXE2_CHANNEL GTXE2_COMMON GTZE2_OCTAL IBUF IBUF_IBUFDISABLE IBUF_INTERMDISABLE IBUFDS IBUFDS_DIFF_OUT IBUFDS_GTE2 IBUFDS_IBUFDISABLE_INT IBUFDS_INTERMDISABLE_INT ICAPE2 IDDR IDDR_2CLK IDELAYCTRL IDELAYE2 IDELAYE2_FINEDELAY IN_FIFO INV ISERDESE2 KEEPER LDCE LDPE LUT1 LUT2 LUT3 LUT4 LUT5 LUT6 MMCME2_ADV MMCME2_BASE MUXCY MUXF7 MUXF8 OBUF OBUFDS OBUFT OBUFT_DCIEN OBUFTDS OBUFTDS_DCIEN ODDR ODELAYE2 ODELAYE2_FINEDELAY OR2L OSERDESE2 OUT_FIFO PCIE_2_1 PCIE_3_0 PHASER_IN PHASER_IN_PHY PHASER_OUT PHASER_OUT_PHY PHASER_REF PHY_CONTROL PLLE2_ADV PLLE2_BASE PULLDOWN PULLUP RAMB18E1 RAMB36E1 RAMD32 RAMD64E RAMS32 RAMS64E SRLC16E SRLC32E SRL16E STARTUPE2 USR_ACCESSE2 VCC XADC XORCY ZHOLD_DELAY");

        // US+ primitive list.
        String usp = new String("AND2B1L BITSLICE_CONTROL BSCANE2 BUFCE_LEAF BUFCE_ROW BUFG_GT BUFG_GT_SYNC BUFG_PS BUFGCE BUFGCE_DIV BUFGCTRL CARRY8 CFGLUT5 CMACE4 DCIRESET DIFFINBUF DNA_PORTE2 DPHY_DIFFINBUF DSP_A_B_DATA DSP_ALU DSP_C_DATA DSP_M_DATA DSP_MULTIPLIER DSP_OUTPUT DSP_PREADD DSP_PREADD_DATA EFUSE_USR FDCE FDPE FDRE FDSE FIFO18E2 FIFO36E2 FRAME_ECCE4 GND GTHE4_CHANNEL GTHE4_COMMON GTYE4_CHANNEL GTYE4_COMMON HARD_SYNC HPIO_VREF IBUF_ANALOG IBUFCTRL IBUFDS_GTE4 ICAPE3 IDDRE1 IDELAYCTRL IDELAYE3 ILKNE4 INBUF INV ISERDESE3 KEEPER LDCE LDPE LUT1 LUT2 LUT3 LUT4 LUT5 LUT6 MASTER_JTAG MMCME4_ADV MUXF7 MUXF8 MUXF9 OBUF OBUFDS OBUFDS_DPHY OBUFDS_GTE4 OBUFDS_GTE4_ADV OBUFT OBUFT_DCIEN OBUFTDS OBUFTDS_DCIEN ODELAYE3 OR2L OSERDESE3 PCIE40E4 PLLE4_ADV PS8 PULLDOWN PULLUP RAMB18E2 RAMB36E2 RAMD32 RAMD32M64 RAMD64E RAMS32 RAMS64E RAMS64E1 RIU_OR RX_BITSLICE RXTX_BITSLICE SRLC16E SRLC32E SRL16E STARTUPE3 SYSMONE4 TX_BITSLICE TX_BITSLICE_TRI URAM288 URAM288_BASE USR_ACCESSE2 VCC VCU");

        String s;
        if(design.getPart().isSeries7()) {
            s = series7;
        } else if(design.getPart().isUltraScalePlus()) {
            s = usp;
        } else {
            throw new RuntimeException("Missing primitive list!");
        }

        String[] values = s.split(" ");
        HashSet<String> primsInPart = new HashSet<String>(Arrays.asList(values));

        EDIFNetlist netlist = new EDIFNetlist("netlist");
        netlist.setDevice(device);
        EDIFLibrary library = new EDIFLibrary("work");
        netlist.addLibrary(library);
        netlist.addLibrary(prims);

        EDIFCell top_level = new EDIFCell(library, "top");

        EDIFDesign edif_design = new EDIFDesign("design");
        edif_design.setTopCell(top_level);

        t.start("Enumerate sites in tiles");
        Map<SiteTypeEnum, List<Site>> site_map = new HashMap<SiteTypeEnum, List<Site>>();
        for(Tile[] tiles : device.getTiles()) {
            for(Tile tile : tiles) {
                for(Site site : tile.getSites()) {
                    addSite(site_map, site, site.getSiteTypeEnum());
                    for(SiteTypeEnum site_type : site.getAlternateSiteTypeEnums()) {
                        addSite(site_map, site, site_type);
                    }
                }
            }
        }

        t.stop().start("Enumerate cells in sites");
        for(EDIFCell cell : prims.getCells()) {
            if(!primsInPart.contains(cell.getName())) {
                continue;
            }

            EDIFCellInst cell_inst = new EDIFCellInst("test", cell, top_level);
            Cell phys_cell = design.createCell("test", cell_inst);

            List<Map.Entry<SiteTypeEnum, String>> entries = new ArrayList<>();

            Map<SiteTypeEnum,Set<String>> sites = phys_cell.getCompatiblePlacements();
            for (Map.Entry<SiteTypeEnum,Set<String>> site : sites.entrySet()) {
                for(String bel : site.getValue()) {
                    entries.add(new AbstractMap.SimpleEntry<SiteTypeEnum, String>(site.getKey(), bel));
                }
            }

            design.removeCell(phys_cell);
            top_level.removeCellInst(cell_inst);
            phys_cell = null;
            cell_inst = null;

            Map<List<Map.Entry<String, String>>, List<Site>> all_pin_mapping = new HashMap<List<Map.Entry<String, String>>, List<Site>>();

            for(Map.Entry<SiteTypeEnum, String> possible_site : entries) {
                SiteTypeEnum site_type = possible_site.getKey();
                String bel = possible_site.getValue();
                if(!site_map.containsKey(site_type)) {
                    continue;
                }

                Map<List<Map.Entry<String, String>>, List<Site>> mapping_to_sites = new HashMap<List<Map.Entry<String, String>>, List<Site>>();

                for(List<String> parameters : getParametersFor(cell.getName())) {
                    String parameters_joined = new String("");
                    for(String parameter : parameters) {
                        parameters_joined += " " + parameter;
                    }
                    System.out.printf("Checking cell %s for parameters = %s\n", cell.getName(), parameters_joined);

                    for(Site site : site_map.get(site_type)) {
                        SiteInst site_inst = design.createSiteInst("test_site", site_type, site);

                        String[] parameter_array = parameters.toArray(new String[parameters.size()]);
                        phys_cell = design.createAndPlaceCell("test", Unisim.valueOf(cell.getName()), site.getName() + "/" + bel, parameter_array);

                        HashSet<Map.Entry<String, String>> pin_mapping = new HashSet<Map.Entry<String, String>>();

                        for(Map.Entry<String, String> pin_map : phys_cell.getPinMappingsL2P().entrySet()) {
                            pin_mapping.add(pin_map);
                        }

                        for(Map.Entry<String, String> pin_map : phys_cell.getPinMappingsP2L().entrySet()) {
                            pin_mapping.add(new AbstractMap.SimpleEntry<String, String>(pin_map.getValue(), pin_map.getKey()));
                        }

                        List<Map.Entry<String, String>> pin_map_list = new ArrayList<Map.Entry<String, String>>();
                        for(Map.Entry<String, String> pin_map : pin_mapping) {
                            pin_map_list.add(pin_map);
                        }

                        Collections.sort(pin_map_list, new StringPairCompare());

                        List<Site> sites_for_pin_map = mapping_to_sites.get(pin_map_list);
                        if(sites_for_pin_map == null) {
                            sites_for_pin_map = new ArrayList<Site>();
                            mapping_to_sites.put(pin_map_list, sites_for_pin_map);
                        }
                        sites_for_pin_map.add(site);

                        sites_for_pin_map = all_pin_mapping.get(pin_map_list);
                        if(sites_for_pin_map == null) {
                            sites_for_pin_map = new ArrayList<Site>();
                            all_pin_mapping.put(pin_map_list, sites_for_pin_map);
                        }
                        sites_for_pin_map.add(site);

                        design.removeCell(phys_cell);
                        design.removeSiteInst(site_inst);
                        top_level.removeCellInst("test");
                        design.getTopEDIFCell().removeCellInst("test");

                    }
                    System.out.printf("Have %d pin mappings for cell %s site type %s parameters = %s\n", mapping_to_sites.size(), cell.getName(), site_type.name(), parameters_joined);
                }

                System.out.printf("Have %d pin mappings for cell %s site type %s\n", mapping_to_sites.size(), cell.getName(), site_type.name());
            }

            System.out.printf("Have %d pin mappings for cell %s\n", all_pin_mapping.size(), cell.getName());
        }

        t.stop().printSummary();
    }
}
