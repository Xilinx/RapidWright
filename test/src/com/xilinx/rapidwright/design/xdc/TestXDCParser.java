/*
 * Copyright (c) 2025, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Jakob Wenzel, Technical University of Darmstadt
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

package com.xilinx.rapidwright.design.xdc;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.xilinx.rapidwright.design.ConstraintGroup;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.xdc.parser.CellObject;
import com.xilinx.rapidwright.design.xdc.parser.DesignObject;
import com.xilinx.rapidwright.design.xdc.parser.EdifCellLookup;
import com.xilinx.rapidwright.design.xdc.parser.RegularEdifCellLookup;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tcl.lang.Interp;
import tcl.lang.TCL;
import tcl.lang.TclException;
import tcl.lang.TclObject;

public class TestXDCParser {

    private Design ethernetDesign;

    private Design getEthernetDesign() {
        if (ethernetDesign == null) {
            ethernetDesign = RapidWrightDCP.loadDCP("verilog_ethernet.dcp", true);
        }
        return ethernetDesign;
    }


    public static List<Arguments> getSimpleArgs() {
        return Arrays.asList(
                Arguments.of(
                        (Function<EDIFNetlist, EdifCellLookup<?>>) RegularEdifCellLookup::new,
                        Arrays.asList(
                                "set_property IDELAY_VALUE 0 [get_cells {phy_rx_ctl_idelay}]",
                                "set_property IDELAY_VALUE 0 [get_cells {phy_rxd_idelay_0}]",
                                "set_property IDELAY_VALUE 0 [get_cells {phy_rxd_idelay_1}]",
                                "set_property IDELAY_VALUE 0 [get_cells {phy_rxd_idelay_2}]",
                                "set_property IDELAY_VALUE 0 [get_cells {phy_rxd_idelay_3}]",
                                "set_property PACKAGE_PIN AA14 [get_ports phy_tx_clk]",
                                "set_property IOSTANDARD LVCMOS25 [get_ports phy_tx_clk]",
                                "set_property SLEW FAST [get_ports phy_tx_clk]",
                                "set_property DRIVE 16 [get_ports phy_tx_clk]",
                                "set_property DUMMY 0 [get_cells {core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[0]}]",
                                "set_input_delay 0 [get_ports uart_rxd]",
                                "set_false_path -to [get_ports {phy_reset_n test_asdf}]",
                                "set_output_delay 0 [get_ports phy_reset_n]"
                        ),
                        5,
                        6,
                        1
                ),
                Arguments.of(
                        (Function<EDIFNetlist, EdifCellLookup<?>>) netlist -> null,
                        Arrays.asList(
                                "set_property IDELAY_VALUE 0 [get_cells {phy_rx_ctl_idelay}]",
                                "set_property IDELAY_VALUE 0 [get_cells {phy_rxd_idelay_*}]",
                                "set_property PACKAGE_PIN AA14 [get_ports phy_tx_clk]",
                                "set_property IOSTANDARD LVCMOS25 [get_ports phy_tx_clk]",
                                "set_property SLEW FAST [get_ports phy_tx_clk]",
                                "set_property DRIVE 16 [get_ports phy_tx_clk]",
                                "set_property DUMMY 0 [get_cells [get_cells core_inst/eth_mac_inst/rx_fifo/fifo_inst]/rd_ptr_reg_reg[0]]",
                                "set_input_delay 0 [get_ports uart_rxd]",
                                "set_false_path -to [get_ports {phy_reset_n test_asdf}]",
                                "set_output_delay 0 [get_ports phy_reset_n]"
                        ),
                        6,
                        2,
                        1
                )
        );

    }

    @MethodSource("getSimpleArgs")
    @ParameterizedTest
    public void testSimple(Function<EDIFNetlist, EdifCellLookup<?>> lookupFactory, List<String> expectedOutput, int unsupportedConstraints, int cellProperties, int pinConstraints) {
        XDCConstraints xdcConstraints = XDCParser.parseXDC(getEthernetDesign().getDevice(), Arrays.asList(
                "set_property IDELAY_VALUE 0 [get_cells {phy_rx_ctl_idelay phy_rxd_idelay_*}]",
                "set_property -dict {LOC AA14 IOSTANDARD LVCMOS25 SLEW FAST DRIVE 16} [get_ports phy_tx_clk]",
                "set_input_delay 0 [get_ports {uart_rxd}]",
                "#comment",
                "set_false_path -to [get_ports {phy_reset_n test_asdf}]",
                "set_output_delay 0 [get_ports {phy_reset_n}]",
                "set fifo_inst [get_cells core_inst/eth_mac_inst/rx_fifo/fifo_inst]",
                "set_property DUMMY 0 [get_cells \"$fifo_inst/rd_ptr_reg_reg[0]\"]"
        ), lookupFactory.apply(getEthernetDesign().getNetlist()));
        List<String> actual = xdcConstraints.getAllAsXdc().sorted().collect(Collectors.toList());
        Collections.sort(expectedOutput);
        Assertions.assertEquals(new NicerStringifyList<>(expectedOutput), new NicerStringifyList<>(actual));
        Assertions.assertEquals(unsupportedConstraints, xdcConstraints.getUnsupportedConstraints().size());
        Assertions.assertEquals(cellProperties, xdcConstraints.getCellProperties().size());
        Assertions.assertEquals(pinConstraints, xdcConstraints.getPinConstraints().size());
    }

    
    @FunctionalInterface
    interface TclObjConsumer<T> {
        void accept(Interp interp, TclObject obj, EDIFNetlist netlist, EdifCellLookup<T> lookup) throws TclException;
    }

    private void eval(String tcl, XDCConstraints constraints, TclObjConsumer<EDIFHierCellInst> downstream) {
        EDIFNetlist netlist = getEthernetDesign().getNetlist();
        RegularEdifCellLookup cellLookup = new RegularEdifCellLookup(netlist);
        eval(tcl, constraints, downstream, netlist, cellLookup);
    }


    private static <T> void eval(String tcl, XDCConstraints constraints, TclObjConsumer<T> downstream, EDIFNetlist netlist, EdifCellLookup<T> cellLookup) {
        Interp interp = XDCParser.makeTclInterp(constraints, netlist.getDevice(), cellLookup);
        try {
            interp.eval(tcl);
            downstream.accept(interp, interp.getResult(), netlist, cellLookup);
        } catch (TclException ex) {
            int code = ex.getCompletionCode();
            switch (code) {
                case TCL.ERROR:
                    throw new RuntimeException(interp.getResult().toString()+" in line "+interp.getErrorLine(), ex);
                case TCL.BREAK:
                    throw new RuntimeException(
                            "invoked \"break\" outside of a loop", ex);
                case TCL.CONTINUE:
                    throw new RuntimeException(
                            "invoked \"continue\" outside of a loop", ex);
                default:
                    throw new RuntimeException(
                            "command returned bad error code: " + code, ex);
            }
        } finally {
            interp.dispose();
        }
    }

    private static Stream<Arguments> getComplexGetterArgs() {
        return Stream.of(
                Arguments.of(
                        "get_cells -hier -filter {(ORIG_REF_NAME == eth_mac_1g_rgmii || REF_NAME == eth_mac_1g_rgmii)}",
                        Arrays.asList("core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst")
                ),
                Arguments.of(
                        "set refname eth_mac_1g_rgmii\n get_cells -hier -filter \"(ORIG_REF_NAME == $refname || REF_NAME == $refname)\"",
                        Arrays.asList("core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst")
                ),
                Arguments.of(
                        "get_cells -hier -filter {(ORIG_REF_NAME == axis_async_fifo || REF_NAME == axis_async_fifo)}",
                        Arrays.asList("core_inst/eth_mac_inst/rx_fifo/fifo_inst","core_inst/eth_mac_inst/tx_fifo/fifo_inst")
                ),
                Arguments.of(
                        "get_cells -quiet -hier -regexp \".*/s_rst_sync\\[23\\]_reg_reg\" -filter \"PARENT == core_inst/eth_mac_inst/rx_fifo/fifo_inst\"",
                        Arrays.asList("core_inst/eth_mac_inst/rx_fifo/fifo_inst/s_rst_sync2_reg_reg", "core_inst/eth_mac_inst/rx_fifo/fifo_inst/s_rst_sync3_reg_reg")
                ),
                Arguments.of(
                        "set parent core_inst/eth_mac_inst/rx_fifo/fifo_inst\nget_cells -quiet -hier -regexp \".*/s_rst_sync\\[23\\]_reg_reg\" -filter \"PARENT == $parent\"",
                        Arrays.asList("core_inst/eth_mac_inst/rx_fifo/fifo_inst/s_rst_sync2_reg_reg", "core_inst/eth_mac_inst/rx_fifo/fifo_inst/s_rst_sync3_reg_reg")
                ),
                Arguments.of(
                        "set parent [get_cells core_inst/eth_mac_inst/rx_fifo/fifo_inst]\nget_cells -quiet -hier -regexp \".*/s_rst_sync\\[23\\]_reg_reg\" -filter \"PARENT == $parent\"",
                        Arrays.asList("core_inst/eth_mac_inst/rx_fifo/fifo_inst/s_rst_sync2_reg_reg", "core_inst/eth_mac_inst/rx_fifo/fifo_inst/s_rst_sync3_reg_reg")
                ),
                Arguments.of(
                        "get_cells -quiet -hier -regexp \".*/wr_ptr_update(_ack)?_sync\\[123\\]_reg_reg\" -filter \"PARENT == core_inst/eth_mac_inst/rx_fifo/fifo_inst\"",
                        Arrays.asList("core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_update_ack_sync1_reg_reg", "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_update_ack_sync2_reg_reg", "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_update_sync1_reg_reg", "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_update_sync2_reg_reg", "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_update_sync3_reg_reg")
                ),
                Arguments.of(
                        "set fifo_inst [get_cells core_inst/eth_mac_inst/rx_fifo/fifo_inst]\n" +
                                "get_cells \"$fifo_inst/rd_ptr_reg_reg[*] $fifo_inst/rd_ptr_gray_reg_reg[*]\"",
                        Arrays.asList(
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[0]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[10]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[11]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[12]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[1]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[2]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[3]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[4]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[5]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[6]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[7]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[8]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[9]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[0]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[10]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[11]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[12]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[1]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[2]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[3]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[4]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[5]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[6]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[7]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[8]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[9]"
                        )
                ),
                Arguments.of(
                        "set fifo_inst [get_cells core_inst/eth_mac_inst/rx_fifo/fifo_inst]\n" +
                                "get_cells \"$fifo_inst/rd_ptr_reg_reg[2] $fifo_inst/rd_ptr_gray_reg_reg[3]\"",
                        Arrays.asList(
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[3]",
                                "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[2]"
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("getComplexGetterArgs")
    public void testComplexGetters(String tcl, List<String> expected) {
        eval(tcl, null, (interp, res, netlist, cellLookup) -> {
            DesignObject<EDIFHierCellInst> designObject = DesignObject.<EDIFHierCellInst>unwrapTclObject(interp, interp.getResult(), cellLookup).orElseThrow(()->new RuntimeException("expected design object"));
            List<EDIFHierCellInst> actual = ((CellObject<EDIFHierCellInst>) designObject).getCells();
            List<String> actualStr = actual.stream().map(EDIFHierCellInst::toString).sorted().collect(Collectors.toList());
            Assertions.assertEquals(expected,actualStr);
        });
    }


    private static Stream<Arguments> getComplexSetterArgs() {
        return Stream.of(
                Arguments.of(
                        "set if_inst core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst\n" +
                                "set src_clk [get_clocks -of_objects [get_pins $if_inst/rgmii_tx_clk_1_reg/C]]\n" +
                                "set_max_delay -from [get_cells $if_inst/rgmii_tx_clk_1_reg] -to " +
                                "[get_cells $if_inst/clk_oddr_inst/oddr[0].oddr_inst] -datapath_only " +
                                "[expr [get_property -min PERIOD $src_clk]/4]",
                        Arrays.asList(
                                "set_max_delay -from [get_cells core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/rgmii_tx_clk_1_reg]" +
                                        " -to [get_cells {core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/clk_oddr_inst/oddr[0].oddr_inst}]" +
                                        " -datapath_only [expr [get_property -min PERIOD " +
                                        "[get_clocks -of_objects [get_pins core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/rgmii_tx_clk_1_reg/C]]]/4]"
                        )
                ),
                Arguments.of(
                        "set_max_delay [expr [get_property asdf]/[get_property asdf]]",
                        Arrays.asList("set_max_delay [expr [get_property asdf]/[get_property asdf]]")
                ),
                Arguments.of(
                        "set inst [get_cells core_inst/eth_mac_inst/rx_fifo/fifo_inst]\nset_max_delay [get_pins $inst/x/y]",
                        Arrays.asList("set_max_delay [get_pins core_inst/eth_mac_inst/rx_fifo/fifo_inst/x/y]")
                ),
                Arguments.of(
                        "foreach fifo_inst [get_cells -hier -filter {(ORIG_REF_NAME == axis_async_fifo || REF_NAME == axis_async_fifo)}] {\n" +
                                "set pin [get_pins $fifo_inst/rd_ptr_reg_reg[0]/C]\n" +
                                "set_max_delay $pin\n" +
                                "}",
                        Arrays.asList(
                                "set_max_delay [get_pins core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[0]/C]",
                                "set_max_delay [get_pins core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[0]/C]"
                        )
                )
        );
    }

    @ParameterizedTest
    @MethodSource("getComplexSetterArgs")
    public void testComplexSetters(String tcl, List<String> expected) {
        XDCConstraints constraints = new XDCConstraints();
        eval(tcl, constraints, (interp, res, netlist, cellLookup) -> {
            List<String> actual = constraints.getAllAsXdc().collect(Collectors.toList());
            Assertions.assertEquals(new NicerStringifyList<>(expected), new NicerStringifyList<>(actual));
        });
    }


    @Test
    void compareToVivadoConstraints() throws IOException {
        EDIFNetlist netlist = getEthernetDesign().getNetlist();
        String xdcFilename = RapidWrightDCP.getString("verilog_ethernet_source_constraints.xdc");
        XDCConstraints xdcConstraints = XDCParser.parseXDC(netlist.getDevice(), FileTools.getLinesFromTextFile(xdcFilename), new RegularEdifCellLookup(netlist));


        Map<String, String> actualReplacements = getReplacementsForActual();

        List<String> actual = expandReplacements(
                xdcConstraints.getAllAsXdc()
                        .filter(l->!l.contains("PACKAGE_PIN") && !l.contains("IOSTANDARD") &&!l.startsWith("set_property")),
                                actualReplacements);
        List<String> reference = expandReferenceConstraints();
        Assertions.assertEquals(new NicerStringifyList<>(reference), new NicerStringifyList<>(actual));

    }

    /**
     * This returns string replacements to clean up some formatting differences and replace the output of unsupported
     * commands by hardcoding their result as returned by running them manually in Vivado's Tcl console
     * @return replacers
     */
    private static @NotNull Map<String, String> getReplacementsForActual() {
        Map<String, String> actualReplacements = new HashMap<>();
        actualReplacements.put(
                "set_input_delay 0 ",
                "set_input_delay 0.0 "
        );
        actualReplacements.put(
                "set_output_delay 0 ",
                "set_output_delay 0.0 "
        );
        actualReplacements.put(
                "set_max_delay -from",
                "set_max_delay -datapath_only -from"
        );
        actualReplacements.put(
                "[get_property -min PERIOD clk_mmcm_out]",
                "8.0"
        );
        actualReplacements.put(
                "[get_clocks -of_objects [get_pins core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[0]/C]]",
                "clk_mmcm_out"
        );


        actualReplacements.put(
                "[get_property -min PERIOD phy_rx_clk]",
                "8.0"
        );
        actualReplacements.put(
                "[get_clocks -of_objects [get_pins core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[0]/C]]",
                "phy_rx_clk"
        );

        actualReplacements.put(
                "[get_clocks -of_objects [get_pins core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[0]/C]]",
                "clk_mmcm_out"
        );

        actualReplacements.put(
                "[get_clocks -of_objects [get_pins core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[0]/C]]",
                "clk_mmcm_out"
        );
        actualReplacements.put(
                "[get_clocks -of_objects [get_pins core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/mii_select_reg_reg/C]]",
                "clk_mmcm_out"
        );
        actualReplacements.put(
                "[get_clocks -of_objects [get_pins core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rx_prescale_reg[2]/C]]",
                "phy_rx_clk"
        );
        actualReplacements.put(
                "[get_clocks -of_objects [get_pins core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/rgmii_tx_clk_1_reg/C]]",
                "clk_mmcm_out"
        );

        actualReplacements.put(
                "[get_clocks -of_objects [get_cells {" +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[9] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[9] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[9]}]]",
                "clk_mmcm_out"
        );

        actualReplacements.put(
                "[get_clocks -of_objects [get_cells {core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[9] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[9] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[9]}]]",
                "phy_rx_clk"
        );

        actualReplacements.put(
                "[get_clocks -of_objects [get_cells {core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[9] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[9] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[9]}]]",
                "clk_mmcm_out"
        );

        actualReplacements.put(
                "[get_clocks -of_objects [get_cells {core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[9] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[9] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[9]}]]",
                "clk_mmcm_out"
        );

        actualReplacements.put(
                "[if {[llength clk_mmcm_out]} {get_property -min PERIOD clk_mmcm_out} {expr 1.0}]",
                "8.0"
        );
        actualReplacements.put(
                "[if {[llength phy_rx_clk]} {get_property -min PERIOD phy_rx_clk} {expr 1.0}]",
                "8.0"
        );


        actualReplacements.put(
                "[expr {8.0/4}]",
                "2.0"
        );
        actualReplacements.put(
                "[expr 8.0/4]",
                "2.0"
        );


        actualReplacements.put(
                "] -datapath_only",
                "]"
        );

        actualReplacements.put(".000", ".0");
        return actualReplacements;
    }


    String streamExpand(String line, String from, String to, boolean[] changed) {
        int i = line.indexOf(from);
        if (i==-1) {
            return line;
        }
        String before = line.substring(0, i);
        String after = line.substring(i+from.length());
        if (to==null) {
            if (!before.isEmpty() || !after.isEmpty()) {
                throw new RuntimeException("tried to delete partial line "+from+" in "+line);
            }
            return null;
        }

        changed[0] = true;

        return before+to+after;
    }

    List<String> expandReferenceConstraints() throws IOException {

        //Vivado leaves some wildcards in the stored constraints. Replace them with the list of matches
        Map<String, String> expands = new HashMap<>();
        expands.put("get_cells -hier -regexp {.*/(rx|tx)_rst_reg_reg\\[\\d\\]} -filter {PARENT == core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst}",
                "get_cells {core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/rx_rst_reg_reg[0] " +
                        "core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/rx_rst_reg_reg[1] " +
                        "core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/rx_rst_reg_reg[2] " +
                        "core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/rx_rst_reg_reg[3] " +
                        "core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/tx_rst_reg_reg[0] " +
                        "core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/tx_rst_reg_reg[1] " +
                        "core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/tx_rst_reg_reg[2] " +
                        "core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/rgmii_phy_if_inst/tx_rst_reg_reg[3]}"
        );
        expands.put(
                "get_cells {core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[*]}",
                "get_cells {core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[9]}"
        );
        expands.put(
                "get_cells {{core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[*]} {core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[*]}}",
                "get_cells {core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_reg_reg[9] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/rd_ptr_gray_reg_reg[9]}"
        );
        expands.put(
                "get_cells {core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[*]}",
                "get_cells {core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[9]}"
        );
        expands.put(
                "get_cells {core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[*]}",
                "get_cells {core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_sync1_reg_reg[9]}"
        );
        expands.put(
                "get_cells {{core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[*]} " +
                        "{core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[*]}}",
                "get_cells {core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_reg_reg[9] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/rd_ptr_gray_reg_reg[9]}"
        );
        expands.put(
                "get_cells {core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[*]}",
                "get_cells {core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_sync1_reg_reg[9]}"
        );
        expands.put(
                "get_cells -quiet {{core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[*]} " +
                        "{core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[*]} " +
                        "{core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[*]}}",
                "get_cells {core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_reg_reg[9] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_gray_reg_reg[9] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/rx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[9]}"
        );
        expands.put(
                "get_cells -quiet {{core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[*]} " +
                        "{core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[*]} " +
                        "{core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[*]}}",
                "get_cells {core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_reg_reg[9] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_gray_reg_reg[9] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[0] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[10] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[11] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[12] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[1] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[2] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[3] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[4] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[5] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[6] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[7] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[8] " +
                        "core_inst/eth_mac_inst/tx_fifo/fifo_inst/wr_ptr_sync_gray_reg_reg[9]}"
        );
        expands.put(
                "get_cells -quiet -hier -regexp {.*/sync_reg_reg\\[\\d+\\]} -filter {PARENT == sync_reset_inst}",
                "get_cells {sync_reset_inst/sync_reg_reg[0] sync_reset_inst/sync_reg_reg[1] sync_reset_inst/sync_reg_reg[2] sync_reset_inst/sync_reg_reg[3]}"
        );
        expands.put(".000", ".0");

        List<String> lines = getEthernetDesign().getXDCConstraints(ConstraintGroup.NORMAL);
            return expandReplacements(lines.stream().filter(l->{
                String trimmed = l.trim();
                return !trimmed.isEmpty() && !trimmed.startsWith("#") && !l.contains("PACKAGE_PIN") && !l.contains("IOSTANDARD") &&!l.startsWith("set_property");
            }), expands);
    }

    /**
     * Repeatedly apply a list of String replacements on all lines until a stable point is reached
     * @param lines the lines to modify
     * @param expands replacements as map of from-to-pairs
     * @return lines with replacements applied
     */
    @NotNull
    private List<String> expandReplacements(Stream<String> lines, Map<String, String> expands) {

        return lines.flatMap(line -> {

                    boolean[] changes = new boolean[]{false};
                    int iterations = 0;
                    do {
                        iterations++;
                        if (iterations>10000) {
                            Assertions.fail("Too many iterations");
                        }
                        changes[0] = false;
                        for (Map.Entry<String, String> entry : expands.entrySet()) {
                            line = streamExpand(line, entry.getKey(), entry.getValue(), changes);
                            if (line == null) {
                                return Stream.empty();
                            }
                        }

                    } while (changes[0]);
                    return Stream.of(line);
                })
                .sorted()
                .collect(Collectors.toList());
    }
}
