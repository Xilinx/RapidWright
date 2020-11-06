package com.xilinx.rapidwright.interchange;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.SiteTypeEnum;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFDesign;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFLibrary;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.ConstraintGroup;

public class EnumerateCellBelMapping {

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

        // 7-series primitive list.
        String s = new String("AND2B1L AUTOBUF BSCANE2 BUF BUFG BUFG_LB BUFGCE BUFGCTRL BUFGMUX BUFH BUFHCE BUFIO BUFMR BUFMRCE BUFR CAPTUREE2 CARRY4 CFGLUT5 DCIRESET DNA_PORT DRP_AMS_ADC DRP_AMS_DAC DSP48E1 EFUSE_USR FDCE FDPE FDRE FDSE FIFO18E1 FIFO36E1 FRAME_ECCE2 GND GTHE2_CHANNEL GTHE2_COMMON GTPE2_CHANNEL GTPE2_COMMON GTXE2_CHANNEL GTXE2_COMMON GTZE2_OCTAL IBUF IBUF_IBUFDISABLE IBUF_INTERMDISABLE IBUFDS IBUFDS_DIFF_OUT IBUFDS_GTE2 IBUFDS_IBUFDISABLE_INT IBUFDS_INTERMDISABLE_INT ICAPE2 IDDR IDDR_2CLK IDELAYCTRL IDELAYE2 IDELAYE2_FINEDELAY IN_FIFO INV ISERDESE2 KEEPER LDCE LDPE LUT1 LUT2 LUT3 LUT4 LUT5 LUT6 MMCME2_ADV MMCME2_BASE MUXCY MUXF7 MUXF8 OBUF OBUFDS OBUFT OBUFT_DCIEN OBUFTDS OBUFTDS_DCIEN ODDR ODELAYE2 ODELAYE2_FINEDELAY OR2L OSERDESE2 OUT_FIFO PCIE_2_1 PCIE_3_0 PHASER_IN PHASER_IN_PHY PHASER_OUT PHASER_OUT_PHY PHASER_REF PHY_CONTROL PLLE2_ADV PLLE2_BASE PULLDOWN PULLUP RAMB18E1 RAMB36E1 RAMD32 RAMD64E RAMS32 RAMS64E SRLC16E SRLC32E SRL16E STARTUPE2 USR_ACCESSE2 VCC XADC XORCY ZHOLD_DELAY");
        String[] values = s.split(" ");
        HashSet<String> primsInPart = new HashSet<String>(Arrays.asList(values));

        EDIFLibrary prims = Design.getPrimitivesLibrary();

        Design design = new Design("top", args[0]);

        EDIFNetlist netlist = new EDIFNetlist("netlist");
        netlist.setDevice(device);
        EDIFLibrary library = new EDIFLibrary("work");
        netlist.addLibrary(library);

        EDIFCell top_level = new EDIFCell(library, "top");

        EDIFDesign edif_design = new EDIFDesign("design");
        edif_design.setTopCell(top_level);

        for(EDIFCell cell : prims.getCells()) {
            if(!primsInPart.contains(cell.getName())) {
                continue;
            }

            EDIFCellInst cell_inst = new EDIFCellInst("test", cell, top_level);
            Cell phys_cell = design.createCell("test", cell_inst);

            Map<SiteTypeEnum,Set<String>> sites = phys_cell.getCompatiblePlacements();
            for (Map.Entry<SiteTypeEnum,Set<String>> site : sites.entrySet()) {
                for(String bel : site.getValue()) {
                    System.out.printf("%s -> %s / %s\n", cell.getName(), site.getKey().name(), bel);
                }
            }

            design.removeCell(phys_cell);
            top_level.removeCellInst(cell_inst);
        }

        t.stop().printSummary();
    }
}

