#/*
# *
# * Copyright (c) 2021 Xilinx, Inc.
# * All rights reserved.
# *
# * Author: Pongstorn Maidee, Xilinx Research Labs.
# *
# * This file is part of RapidWright.
# *
# * Licensed under the Apache License, Version 2.0 (the "License");
# * you may not use this file except in compliance with the License.
# * You may obtain a copy of the License at
# *
# *     http://www.apache.org/licenses/LICENSE-2.0
# *
# * Unless required by applicable law or agreed to in writing, software
# * distributed under the License is distributed on an "AS IS" BASIS,
# * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# * See the License for the specific language governing permissions and
# * limitations under the License.
# *
# */


# dump_all_dsp_delay.tcl --
#   This file implements the Tcl code to write logic delays of DSPs within a placed design, loaded in Vivado, out to files.
#
#
# Rational:
#   A DSP can be configured in many ways. The DSP is represented in timing graph of RapidWright, 
#   depending on its configuration, ie., an output pin configured with FF will be used as a timing endpoint.
#   An example for each configuration is required to extract the delays. Because we do not have an access
#   to every example for each configuration, we resort to dumping logic delays after placement by Vivado.
#   As we accumulate more and more configurations, we will have enough database, eliminating the use of this script.
#
#   In the current RapidWright release, we do not have a way for the community to contribute DSP logic files 
#   back into RapidWright. We will provide that mechanism in the future.
#
#   The database could be recorded for the whole DSP or its subblocks, which is more scalable. 
#   The current version use the former approach.  
#   Although Rapidwright sees connections between subblocks, there is no way to identify the unused portion of each bus.
#   Without trimming unused pins from the timing graph, the resulting timing analysis will be very pessimistic.
#   Ones could derive the unused portions from the DSP, by identifying if a pin connects to VCC or GND, and propagate that 
#   to each subblock. However, doing that requires deep knowledge of the DSP and thus is left for future work. 
#     
#
#
# Usage:
#   1) Load a placed design into memory either through read_checkpoint or place_design.
#   2) At Vivado Tcl console, source dump_all_dsp_delay
#
# Output:
#   The logic delays of a DSP will be written to a file named {dsp_name}.txt. 
#   The number of files is equal to the number of DSPs in the design.
#
# Observations:
#   In some cases, there is no delay information for some pins. Thus, there is no delay info for those pins. 
#   In general case, these pins may be used. The two known cases are below.
#     1) Some pins of an DSP instance may be unconnected. 
#     2) Some pins are connected to ports and no timing path is available.


set property_list [list     \
  ACASCREG                  \
  ADREG                     \
  ALUMODEREG                \
  AMULTSEL                  \
  AREG                      \
  AUTORESET_PATDET          \
  AUTORESET_PRIORITY        \
  A_INPUT                   \
  BCASCREG                  \
  BMULTSEL                  \
  BREG                      \
  B_INPUT                   \
  CARRYINREG                \
  CARRYINSELREG             \
  CREG                      \
  DREG                      \
  INMODEREG                 \
  IS_ALUMODE_INVERTED       \
  IS_CARRYIN_INVERTED       \
  IS_CLK_INVERTED           \
  IS_INMODE_INVERTED        \
  IS_MATCHED                \
  IS_OPMODE_INVERTED        \
  IS_RSTALLCARRYIN_INVERTED \
  IS_RSTALUMODE_INVERTED    \
  IS_RSTA_INVERTED          \
  IS_RSTB_INVERTED          \
  IS_RSTCTRL_INVERTED       \
  IS_RSTC_INVERTED          \
  IS_RSTD_INVERTED          \
  IS_RSTINMODE_INVERTED     \
  IS_RSTM_INVERTED          \
  IS_RSTP_INVERTED          \
  MASK                      \
  MREG                      \
  OPMODEREG                 \
  OPT_MODIFIED              \
  PATTERN                   \
  PREADDINSEL               \
  PREG                      \
  RND                       \
  SEL_MASK                  \
  SEL_PATTERN               \
  USE_MULT                  \
  USE_PATTERN_DETECT        \
  USE_SIMD                  \
  USE_WIDEXOR               \
  XORSIMD                   \
  ]


# Dump logic delay of each DSP into a file.
proc dump_all_dsp_delay [list [list prop_list $::property_list]] {
  set dsps [get_cells -hier -filter {REF_NAME=="DSP48E2"}]
  set num_dsps   [llength $dsps]
  puts "Dumping delay of $num_dsps dsps"  
  for {set i 0} {$i < $num_dsps} {incr i} {
    puts "\nDump delay for [lindex $dsps $i]. ([expr {$i+1}] of $num_dsps)"
    dump_dsp_delay [lindex $dsps $i] $prop_list
  }
  return
}



################################################## Helper proc #####################################################


# Dump logic delay of the given DSP cell into a file. 
#
# Arguments:
#   c            : DSP cell obtained by get_cells (Vivado tcl command).
#   v (optional) : verbose option, default to 0 which is not verbose.
#
proc dump_dsp_delay { c prop_list {v 0}} {

  set start_time [clock format [clock seconds] -format "%y-%m-%d %H:%M:%S"]
  set dsp_name [string map {/ -} $c]
  set fo [open "${dsp_name}.txt" "w"]

  ;# record properties
  foreach p $prop_list {
    puts $fo "#property $p [get_property $p $c]"
  }
  puts $fo "#"


  set pis [get_pins -of $c -filter {DIRECTION=="IN"}]
  set pos [get_pins -of $c -filter {DIRECTION=="OUT"}]  


  puts "    Start $start_time"
  puts "    Number of pins: input [llength $pis]   output [llength $pos]"
  puts "    Dumping clk to out delay"
  set cnt 0
  if {[llength $pos] > 0} {
    foreach out $pos {
      if {$out == ""} {continue}
      lassign [get_delay_clk_to_out $out $v] dly clkp
      if {$dly < 0} {continue}   
      # The pin of DSP48E2 is clk. $clkp is a clock pin on a subblock.
      puts $fo [format "clk    %-*s    %7.2f" 10 [file tail $out] $dly]
      incr cnt  
    }
    flush $fo
  }
  puts "        Found $cnt paths"


  puts "    Dumping in to clk delay"
  set cnt 0
  if {[llength $pis] > 0} {
    foreach in $pis {
      if {$in == ""} {continue}
      lassign [get_delay_in_to_clk $in $v] dly setup clkp
      if {$dly < 0} {continue}   
      puts $fo [format "%-*s    clk    %7.2f    %7.2f" 5 [file tail $in] $dly $setup]
      incr cnt  
    }
    flush $fo
  }
  puts "        Found $cnt paths"


  puts "    Dumping in to out delay (combinational)"
  set cnt 0
  if {([llength $pos] > 0) && ([llength $pis] > 0)} {

    set bus_i [group_bus $pis]
    set bus_o [group_bus $pos]
    dict for {ki vis} $bus_i {
      dict for {ko vos} $bus_o {
        ;# There are too many individual pins. Let test if there is any path between the two buses first.
        set path [report_timing  -through  ${ki}* -through ${ko}* -delay_type max -max_paths 1 -return_string -quiet ]
        if {![isEmptyPath [split $path "\n"]]} {
          ;# Using delay between a pair of one bits assumption that bit i of input will connect to bit j >= i of output works for most cases but not all.
          ;# For example, path C[19] -> P[21] can exist even when C[0] -> P[1] does not. Thus, we need to check each pair of in/out bit.
          ;# TODO: Combine results back into bus to reduce disk space if they have similar values.
          foreach vi $vis {
            foreach vo $vos {
              lassign [get_delay_in_to_out $vi $vo $v] dly 
              if {$dly < 0} {continue}   
              puts $fo [format "%-*s    %-*s    %7.2f" 5 [file tail $vi] 5 [file tail $vo] $dly]
              incr cnt  
            }
          }
        }  
      }
    }

  }
  puts "        Found $cnt paths"


  set end_time [clock format [clock seconds] -format "%y-%m-%d %H:%M:%S"]
  puts "    End   $end_time"
  puts "    Finish dumping delay for $dsp_name"

  close $fo
  return
}


# Find delays for an input pin to a sequential element (a timing endpoint). 
#
# Arguments:
#   in           : a DSP input pin.
#   v (optional) : verbose option, default to 0 which is not verbose.
# Results:
#   A list of 
#     delay from in to a sequential element (delay -1 signifies an empty path), 
#     setup time of the sequential element and 
#     the clock pin of the subblock, where the sequential element resides in.
#
# See Example 1 (the end of this file) for a timing path to help understand this proc.
#
proc get_delay_in_to_clk { in {v 0}} {
  if {$v} {puts "get_delay_in_to_clk $in"}
  set path [report_timing -through  $in -delay_type max -max_paths 1 -return_string -quiet] 
  set lines [split $path "\n"]

  if {[isEmptyPath $lines]} {
    return [list -1 -1 ""]
  }

  set len [llength $lines]  
  ;# Look for Destination: from the report to skip the name that may show up before the main report.
  ;# 
  set i 0
  for {} {$i < $len} {incr i} {
    if {[regexp {Destination:\s+([\w\/\[\]]+)} [lindex $lines $i] -> clkp]} {
      break
    }
  }

  ;# The clock source is noted on sub block
  set clk_src [file dir [file dir $clkp]]
  if {[file dir $in] != $clk_src} {
    return [list -1 $clkp]
  }

  incr i  
  lassign [look_for_delay_at_pin $in $lines $i] acc_in i
  ;# Get arrival time  
  for {} {$i < $len} {incr i} {
    ;# Arrival time is listed as - because it will be subtracted from req to get slack. 
    ;# We need just arrival time without -. - is not captured by the regexp below.  
    if {[regexp {arrival time\s+[-]*([.\d]+)} [lindex $lines $i] -> arr]} {
      break
    }
  }

  set dly [expr {$arr - $acc_in}]

  ;# Find clk_to_d
  ;# From observation, last line mentioning the dsp will contain clk-to-d
  set o_i [file dir  $in]  
  for {set i 0} {$i < $len} {incr i} {
    if {[regexp "${o_i}" [lindex $lines $i]]} {
      regexp {\s+([-.\d]+)\s+([.\d]+)} [lindex $lines $i] -> inc acc
    }
  }
  set d2q [expr {-1*$inc}]
  set d2q [expr {double(round(1000*$d2q))/1000}]

  return [list $dly $d2q $clkp]  
}


# Find combinational delays between an input to an output pins. 
#
# Arguments:
#   in           : a DSP input pin, not of a subblock. For example, bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_fu_88_p2/P[16]  
#   out          : a DSP output pin.
#   v (optional) : verbose option, default to 0 which is not verbose.
# Results:
#   The combinational delay between the given input and output pins. -1 if the path does not exists.
#
# See Example 2 (the end of this file) for a timing path to help understand this proc.
#
proc get_delay_in_to_out { in out {v 0}} {
  if {$v} {puts "get_delay_in_to_out $in $out"}
  set path [report_timing -through $in -through $out -delay_type max -max_paths 1 -return_string -quiet] 
  set lines [split $path "\n"]

  if {[isEmptyPath $lines]} {
    return -1
  }

    
  set len [llength $lines]  

  ;# Look for source. Skip if timing path begins in the same DSP.
  set i 0
  for {} {$i < $len} {incr i} {
    if {[regexp {Source:\s+([\w\/\[\]]+)} [lindex $lines $i] -> clkp]} {
      break
    }
  }

  if {[file dir [file dir $clkp]] == [file dir $in]} {
    # Skip. This will be listed in get_delay_in_to_clk.
    return -1
  }


  ;# Look for Destination 
  for {} {$i < $len} {incr i} {
    if {[regexp {Destination:\s+([\w\/\[\]]+)} [lindex $lines $i] -> clkp]} {
      break
    }
  }

  ;# If timing path ended in the same DSP it will show as 
  ;# Destination:            bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_32_fu_254_p2/DSP_OUTPUT_INST/ALU_OUT[0]
  if {[file dir [file dir $clkp]] == [file dir $in]} {
    # Skip. This will be listed in get_delay_in_to_clk
    return -1
  }


  lassign [look_for_delay_at_pin $in  $lines $i] st_acc i
  lassign [look_for_delay_at_pin $out $lines $i] sp_acc i
  set acc [expr {$sp_acc - $st_acc}]

  return [expr {double(round(1000*$acc))/1000}]
}


# Find delays for a clock to an output pin, equivalent to clock to q of a FF. 
#
# Arguments:
#   out          : a DSP output pin.
#   v (optional) : verbose option, default to 0 which is not verbose.
# Results:
#   A list of 
#     delay from a clock pin to the given output pin. If the clock source of the path is not from the same dsp as the output pin, return -1.
#     the clock pin of the subblock, where the sequential element resides in.
#
proc get_delay_clk_to_out { out {v 0}} {
  if {$v} {puts "get_delay_clk_to_out $out"}
  set path [report_timing -through  $out -delay_type max -max_paths 1 -return_string -quiet] 
  set lines [split $path "\n"]


  if {[isEmptyPath $lines]} {
    return [list -1 ""]
  }

  set len [llength $lines]  
  ;# Look for source 
  set i 0
  for {} {$i < $len} {incr i} {
    if {[regexp {Source:\s+([\w\/\[\]]+)} [lindex $lines $i] -> clkp]} {
      break
    }
  }

  ;# The clock source is noted on sub block
  set clk_src [file dir [file dir $clkp]]
  if {[file dir $out] != $clk_src} {
    return [list -1 $clkp]
  }

  lassign [look_for_delay_at_pin $out $lines $i] acc i
  return [list $acc $clkp]
}


# Extract a accumulative delay at a pin from the list of lines started from the given line number.
#
# Arguments:
#   pin    : the pin to get accumulate delay at.
#   lines  : a result of report_timing after splitting into lines.
#   i      : the first line to start looking.

# Results:
#   A list of 
#     the accumulate delay at the pin.
#     the line that the pin was found.
#
proc look_for_delay_at_pin {pin lines i} {
  ;# $pin is DSP pin, but the pin in the report is for sub block.
  set o_i [file dir  $pin]  
  set o_p [file tail $pin]
  ;# Insert \ in front of [ and ]
  set o_i [string map {\[ \\\[}  $o_i] 
  set o_i [string map {\] \\\]}  $o_i] 
  set o_p [string map {\[ \\\[}  $o_p] 
  set o_p [string map {\] \\\]}  $o_p] 

  set len [llength $lines]
  for {} {$i < $len} {incr i} {
    if {[regexp "${o_i}.*${o_p}" [lindex $lines $i]]} {
      regexp {\s+([.\d]+)\s+([.\d]+)} [lindex $lines $i] -> inc acc
      return [list $acc $i]
    }
  }
  return [list -1 $i]
}


# Group individual bits into bus.
#
# Arguments:
#   pins   : a list of pins
# Results:  
#   A dictionary where each entry is a pair of a bus name and a list of bit-wise pin names.
#   For example, an entry can be {ret_V_28_fu_206_p2/P  ret_V_28_fu_206_p2/P[16]}
proc group_bus { pins } {
  set bus [dict create]
  foreach p $pins {
    regexp {([\w\/]+)\[\d+\]} $p -> k
    dict lappend bus $k $p  
  }
  return $bus
}


# Check if the report timing has no path
#
# Arguments:
#   lines is a result of report_timing after splitting into lines.
# Results:
#   Return 1 if the report timing contain no path
proc isEmptyPath {lines} {
  set len [llength $lines]  
  for {set i 0} {$i < $len} {incr i} {
    if {[regexp {No timing paths found} [lindex $lines $i]]} {
      return 1
    }
  }
  return 0
}



################################################## Examples to understand key proc #####################################################


# Example 1 for get_delay_in_to_clk 
#
# An example obtained by 
# report_timing  -through  bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_2_fu_128_p2/CEA2 -delay_type max -max_paths 1 -return_string -quiet
#
#Slack (MET) :             3.384ns  (required time - arrival time)
#  Source:                 bd_0_i/hls_inst/inst/ap_CS_fsm_reg[3]/C
#                            (rising edge-triggered cell FDRE clocked by clk  {rise@0.000ns fall@2.000ns period=4.000ns})
#  Destination:            bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_2_fu_128_p2/DSP_A_B_DATA_INST/CEA2
#                            (rising edge-triggered cell DSP_A_B_DATA clocked by clk  {rise@0.000ns fall@2.000ns period=4.000ns})
#  Path Group:             clk
#  Path Type:              Setup (Max at Slow Process Corner)
#  Requirement:            4.000ns  (clk rise@4.000ns - clk rise@0.000ns)
#  Data Path Delay:        0.436ns  (logic 0.079ns (18.119%)  route 0.357ns (81.881%))
#  Logic Levels:           0  
#  Clock Path Skew:        0.014ns (DCD - SCD + CPR)
#    Destination Clock Delay (DCD):    0.043ns = ( 4.043 - 4.000 ) 
#    Source Clock Delay      (SCD):    0.029ns
#    Clock Pessimism Removal (CPR):    0.000ns
#  Clock Uncertainty:      0.035ns  ((TSJ^2 + TIJ^2)^1/2 + DJ) / 2 + PE
#    Total System Jitter     (TSJ):    0.071ns
#    Total Input Jitter      (TIJ):    0.000ns
#    Discrete Jitter          (DJ):    0.000ns
#    Phase Error              (PE):    0.000ns
#
#    Location             Delay type                Incr(ns)  Path(ns)    Netlist Resource(s)
#  -------------------------------------------------------------------    -------------------
#                         (clock clk rise edge)        0.000     0.000 r  
#                                                      0.000     0.000 r  ap_clk (IN)
#                         net (fo=1982, unset)         0.029     0.029    bd_0_i/hls_inst/inst/ap_clk
#    SLICE_X91Y106        FDRE                                         r  bd_0_i/hls_inst/inst/ap_CS_fsm_reg[3]/C
#  -------------------------------------------------------------------    -------------------
#    SLICE_X91Y106        FDRE (Prop_EFF_SLICEL_C_Q)
#                                                      0.079     0.108 r  bd_0_i/hls_inst/inst/ap_CS_fsm_reg[3]/Q
#                         net (fo=28, routed)          0.357     0.465    bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_2_fu_128_p2/CEA2
#    DSP48E2_X10Y46       DSP_A_B_DATA                                 r  bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_2_fu_128_p2/DSP_A_B_DATA_INST/CEA2
#  -------------------------------------------------------------------    -------------------
#
#                         (clock clk rise edge)        4.000     4.000 r  
#                                                      0.000     4.000 r  ap_clk (IN)
#                         net (fo=1982, unset)         0.043     4.043    bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_2_fu_128_p2/CLK
#    DSP48E2_X10Y46       DSP_A_B_DATA                                 r  bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_2_fu_128_p2/DSP_A_B_DATA_INST/CLK
#                         clock pessimism              0.000     4.043    
#                         clock uncertainty           -0.035     4.008    
#    DSP48E2_X10Y46       DSP_A_B_DATA (Setup_DSP_A_B_DATA_DSP48E2_CLK_CEA2)
#                                                     -0.159     3.849    bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_2_fu_128_p2/DSP_A_B_DATA_INST
#  -------------------------------------------------------------------
#                         required time                          3.849    
#                         arrival time                          -0.465    
#  -------------------------------------------------------------------
#                         slack                                  3.384  



# Example 2 for get_delay_in_to_out 
#
#         In the example below, 3.277-1.763 will be returned.
#    SLICE_X80Y105        LUT3 (Prop_C6LUT_SLICEM_I0_O)
#                                                      0.051     1.390 f  bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2_i_10/O
#                         net (fo=22, routed)          0.373     1.763    bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/A[13]
#    DSP48E2_X9Y42        DSP_A_B_DATA (Prop_DSP_A_B_DATA_DSP48E2_A[13]_A2_DATA[13])
#                                                      0.192     1.955 r  bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_A_B_DATA_INST/A2_DATA[13]
#                         net (fo=1, routed)           0.000     1.955    bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_A_B_DATA.A2_DATA<13>
#    DSP48E2_X9Y42        DSP_PREADD_DATA (Prop_DSP_PREADD_DATA_DSP48E2_A2_DATA[13]_A2A1[13])
#                                                      0.076     2.031 r  bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_PREADD_DATA_INST/A2A1[13]
#                         net (fo=1, routed)           0.000     2.031    bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_PREADD_DATA.A2A1<13>
#    DSP48E2_X9Y42        DSP_MULTIPLIER (Prop_DSP_MULTIPLIER_DSP48E2_A2A1[13]_U[16])
#                                                      0.505     2.536 f  bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_MULTIPLIER_INST/U[16]
#                         net (fo=1, routed)           0.000     2.536    bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_MULTIPLIER.U<16>
#    DSP48E2_X9Y42        DSP_M_DATA (Prop_DSP_M_DATA_DSP48E2_U[16]_U_DATA[16])
#                                                      0.047     2.583 r  bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_M_DATA_INST/U_DATA[16]
#                         net (fo=1, routed)           0.000     2.583    bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_M_DATA.U_DATA<16>
#    DSP48E2_X9Y42        DSP_ALU (Prop_DSP_ALU_DSP48E2_U_DATA[16]_ALU_OUT[16])
#                                                      0.585     3.168 f  bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_ALU_INST/ALU_OUT[16]
#                         net (fo=1, routed)           0.000     3.168    bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_ALU.ALU_OUT<16>
#    DSP48E2_X9Y42        DSP_OUTPUT (Prop_DSP_OUTPUT_DSP48E2_ALU_OUT[16]_P[16])
#                                                      0.109     3.277 r  bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/DSP_OUTPUT_INST/P[16]

################################################## Test #####################################################


# Basic testing of the key proc. Assume that dcp of 3d-rendering was loaded.
#
proc test {} {
    set nE 0

    set in  bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_2_fu_128_p2/CEA2 
    lassign [get_delay_in_to_clk $in] dly setup clkp
    if {$dly != 0.0} { 
      puts "*ERROR get_delay_in_to_clk get delay $dly expect 0.0"
      incr nE  
    }
    if {$setup != 0.159} { 
      puts "*ERROR get_delay_in_to_clk get setup $setup expect 0.0"
      incr nE  
    }

    set out bd_0_i/hls_inst/inst/call_ret_projection_fu_3263/mul_ln1371_fu_88_p2/P[16]
    lassign [get_delay_clk_to_out $out] dly clkp
    if {$dly != 1.616} { 
      puts "*ERROR get_delay_clk_to_out get delay $dly expect 0.0"
      incr nE  
    }

    set i bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/A[13]
    set o bd_0_i/hls_inst/inst/grp_rasterization1_fu_3239/ret_V_28_fu_206_p2/P[16]

    lassign [get_delay_in_to_out $i $o] dly 
    if {$dly != 1.514} { 
      puts "*ERROR get_delay_in_to_out get delay $dly expect 0.0"
      incr nE  
    }

    puts "test found $nE errors."
}
