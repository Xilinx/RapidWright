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


# find_clock_route_template.tcl --
#
#
# Rational:
#
# A high fanout net consumes a lot of routing resource and can be the timing bottleneck 
# if it is routed in regular resource. Virtually all commerical router is able to route 
# such a net in a clock network to alleviate congestion on the regular resource and improve timing results.
#
# However, the clock resource is inflexible, making it difficult to extract the delay of its resource. 
# Therefore, instead of extracting the delays, we will build templates using Viado to be used in RapidWright router.
# 
# Routing a signal in the clock network contains 3 route sections. 
# 1) Route from the net drive to an INT tile accessible to BUFGCE (its location was choosen before routing).
# 2) Route from the INT tile to the BUFGCE input.
# 3) Route from the BUFGCE output to each of the sinks.
# The first step is a regular routing. Thus, we only need to produce templates for the last 2 steps.
#
# This script will extract the route from a given INT tile to a given BUFGCE. It also extract the delay and route from 
# the given BUFGCE to each INT tile in the given list of INT tile. 
#
# We could use this script to produce a template just for the given design. The output files obtained from different designs 
# can be combined to be used in another design if applicable. Once the files include all the cases for a device. 
# The files can be used in any design implemented on the device without relying on this script any further.

#
# Usage:
#   1) Specify BUFG location (bufg_site)
#   2) Specify an INT tile to access BUFG. (src_int_tiles) (TODO: better enforce this access point.)
#   3) Specify the list of sink INT tiles (dst_int_tiles)
#   4) Run the script in Vivado from tcl/rwroute directory.  (Otherwise, an appropriate prefix must be added to reach the Tcl file.)
#      - source find_clock_route_template.tcl -notrace
#      - find_clock_route_template  $bufg_site  $src_int_tiles  $dst_int_tiles  X36Y157-X36Y157_X0Y58_X44Y150-X52Y270.txt
# 
# Note: 
#   1) Assume delay from leaf buf to any CE pins in the half column are the same. 
#   2) bufg delay can be added to either its input or output net. Add to output net for easy visual inspection of the delay of input nets.
#
#
# Prerequisite:
# set environment variables RAPIDWRIGHT_PATH and CLASSPATH as mentioned in step 4 and 5 of the installation guide  https://www.rapidwright.io/docs/Manual_Install.html#manual-install


################################################## Necessary Inputs #####################################################
#
# Please redefine them to match the use case.

# list of bufg to collect delay for
set bufg_site {BUFGCE_X0Y58}

# List of INT accessible to BUFG list under $bufg_site 
set src_int_tiles {INT_X36Y157}

# List of the int_tiles to be used as sinks from each bufg
set dst_int_tiles [list]
for {set x 44} {$x <= 52} {incr x} {
  for {set y 150} {$y <= 270} {incr y 60} {
    lappend dst_int_tiles "INT_X${x}Y${y}"
  }
}


################################################## Main proc #####################################################


# Find the clock routes and their delays. There are two types of routes. 
# 1) Those from each INT tile in $src_int_tiles to each of the BUFG in $bufg_site.
# 2) Those from each BUFG output to a sink in each target INT tiles ($dst_int_tiles).
#
# Arguments:
#   bufg_site     : List of BUFG
#   src_int_tiles : List of INT tiles accessible to a BUFG in $bufg_site
#   dst_int_tiles : List of INT tiles containing sinks of a BUFG
#   fname         : The file name to store the output.
#   v (optional)  : verbose option, default to 0 which is not verbose.
# Results: Write the routes of both types as well as their delay into the file. 
#
proc find_clock_route_template {bufg_site src_int_tiles dst_int_tiles fname {v 0}} {
  set number_of_locs_for_src 1

  ;#-------------  Create design -----------
  set name "chain_bufg"
  set nff    [create_design_for_collecting_clk_tree_delay $name $dst_int_tiles] 
  set pb2ffs [build_ff_tile_info $dst_int_tiles]  
  
  ;#-------------  Setup an initial implementation -----------
  set slice_increment 1
  ;# place FFs to location according to pb2ffs
  helper_proc::assign_ff_to_location $pb2ffs $slice_increment 
  place_design -quiet
  route_design -quiet 

  ;#-------------  Collect data -----------
  set int_bufg [list]
  set bufg_int [dict create]

  ;# main loop to setup and collect data
  foreach buf_loc $bufg_site {
    puts "Process BUFG at $buf_loc"
    place_cell BUFGCE_inst $buf_loc
    foreach t $src_int_tiles {
      puts "    Process accessing $buf_loc from $t"
      regexp {INT_X(\d+)Y(\d+)} $t -> x y
      set locs [helper_proc::build_ff_loc_list "INT_X${x}Y${y}" 1 $slice_increment]
      for {set j 0} {$j < [llength $locs]} {incr j} {
        if {[helper_proc::place_cell_at "ff_reg\[0\]" [lindex $locs $j]]} { 
          if {$v} {puts "ff_reg\[0\]  is placed at [lindex $locs $j]"}
          incr j
          break 
        }
      }
      if {$j >= [llength $locs]} {
        puts "***ERROR***: Can't place the src FF. Please increase the value of number_of_locs_for_src in proc collect_clock_tree_delays." 
        return  
      }


      route_design -unroute -quiet 
      route_design -quiet 


      ;# Get delay to bufg 
      set rpt [report_timing -through  [get_pins {BUFGCE_inst/I}] -delay_type max -max_paths 1 -return_string -quiet]
      lassign [extract_delays $rpt] ff_bufg_n_dly bufg_l_dly bufg_ff_n_dly
      if {$v} {puts "delay to bufg $t $buf_loc $ff_bufg_n_dly $bufg_l_dly $bufg_ff_n_dly"}

      write_checkpoint $name.dcp -quiet -force
      write_edif       $name.edf -quiet -force 

      set RW $::env(RAPIDWRIGHT_PATH)
      exec javac $RW/src/com/xilinx/rapidwright/util/rwroute/SourceToSinkINTTileDelayWriter.java
      exec java -cp $RW/src -cp $::env(CLASSPATH) com/xilinx/rapidwright/util/rwroute/SourceToSinkINTTileDelayWriter  $name.dcp --net ff[0] ./

      set delay_to_int [read_delay_to_bufg $name]


      ;# Get route to bufg 
      set nodes [helper_proc::get_nodes_to_net_pin BUFGCE_inst/I]
      set nodes [trim_front_to_IMUX $nodes]
      set int_tile [file dir [lindex $nodes 0]]
      lappend int_bufg [list $int_tile $buf_loc [expr {round(1000*$ff_bufg_n_dly - $delay_to_int)}] $nodes]
      set bufg_logic_delay $bufg_l_dly


      ;# Get delay and route from bufg 
      dict for {pb val} $pb2ffs { 
        dict for {t ffs} $val {
          ;# there is only one FF for a target tile
          set idx [lindex $ffs 0]
          set dst_pin [get_pins "ff_reg\[$idx\]/CE"]
          set rpt [report_timing -through $dst_pin  -delay_type max -max_paths 1 -return_string -quiet]
          lassign [extract_delays $rpt] ff_bufg_n_dly bufg_l_dly bufg_ff_n_dly
          set store_delay [expr {round(1000*($bufg_ff_n_dly + $bufg_logic_delay))}]
          if {$v} {puts "$t $dst_pin $bufg_ff_n_dly $bufg_logic_delay $store_delay"}
          set nodes [helper_proc::get_nodes_to_net_pin $dst_pin]
          set nodes [trim_end_from_last_HDISTR $nodes]
          dict set bufg_int $buf_loc $t  [list  $store_delay $nodes]
        }
      }

    }
  }


  set fo [open "$fname" "w"]
  puts $fo "int_bufg"  
  foreach entry $int_bufg { puts $fo $entry }
  puts $fo "\nbufg_int"  
  dict for {buf_loc val} $bufg_int {
    dict for {t dly_nodes} $val {
      puts $fo "$buf_loc  $t   $dly_nodes"
    }
  }
  close $fo


  close_project
  file delete {*}[glob $name.*]
  file delete -force   $name

  puts "\nfind_clock_route_template finished."
}


################################################## Helper proc #####################################################

source [file join [file dirname [info script]] "helper_proc/clock_modeling_util.tcl"] -notrace

;# Supppress info
set_msg_config -id "IP_FLOW 19-234"  -suppress
set_msg_config -id "IP_FLOW 19-1704" -suppress
set_msg_config -id "IP_FLOW 19-2313" -suppress
;# Suppress warning
set_msg_config -id "IP_FLOW 19-4830" -suppress
;# domain name
set_msg_config -id "IP_FLOW 19-3899" -suppress
;# Vivado tclapp
set_msg_config -id "COMMON 17-1496" -suppress



# Write a design used in measuring clock delay out to the given file name (without extension, .sv will be appended.)
proc write_ff_chain_with_bufg {fname} {
 set fo [open "$fname.sv" "w"]
 puts $fo " module $fname"
 set text {
 (
   in,out,clk
 );
   parameter LENGTH = 6;
   
   input   in;
   output  out;
   input   clk;
   
   BUFGCE #(
   .CE_TYPE("SYNC"),
   .IS_CE_INVERTED(1'b0),
   .IS_I_INVERTED(1'b0)
   )
   BUFGCE_inst (
   .O(bufg_out),
   .CE(1'b1),
   .I(ff[0])
   );
   
   (* keep = "true" *) logic [LENGTH-1:0] ff;
   always @(posedge clk) begin
     ff[0] <= in;
   end
   
   always @(posedge clk) begin
     for (int i = 1; i < LENGTH ; i = i+1) begin
       if (bufg_out)
         ff[i] <= ff[i-1];
     end
   end
   
   assign out   = ff[LENGTH-1];
   
 endmodule
 }

 puts $fo $text
 close $fo
}


# Create synthesized design 
proc create_design_for_collecting_clk_tree_delay {name int_tiles} {
  write_ff_chain_with_bufg $name  
  set ooc 1  
  set nff [expr {[llength $int_tiles] +1}]
  helper_proc::ini_chain $nff "" $name $ooc
  file delete -force "$name.sv"
  return $nff
}


# Assign ff index to each target INT tile
#
#Note: This could be just a list.  However, to reuse assign_ff_to_location, need to put in this format. 
proc build_ff_tile_info {dst_int_tiles} {
  set pb2ffs  [dict create]
  ;# pb is not need, but use this format to reuse existing code.
  set pb "dummy"  
    
  foreach t $dst_int_tiles {
    dict set pb2ffs $pb $t [list]
  }

  ;# ff_reg[0] is used as source and its placement will change over runs.
  set idx 1  
  foreach t $dst_int_tiles {  
    dict with pb2ffs $pb { lappend $t $idx}
    incr idx
  }

  return $pb2ffs
}


# Extract delays from the routing result.
# Arguments: 
#   rpt : a report obtained from report_timing Tcl command 
# Results: A list of
#   The net delay from the source FF to the input of BUFG
#   The logic delay of the BUFG
#   The net delay from BUFG output to the sink FF
#
# With the report below, return 0.357, 0.028, 1.188
#
#                         net (fo=2, routed)           0.357     0.464    ff[0]
#    BUFGCE_X0Y72         BUFGCE (Prop_BUFCE_BUFGCE_I_O)
#                                                      0.028     0.492 r  BUFGCE_inst/O
#                         net (fo=27, routed)          1.188     1.680    bufg_out
#
proc extract_delays {rpt} {
  set lines [split $rpt "\n"]
  set len   [llength $lines]  
  set i 0

  ;# extract net delay from source to through
  for {} {$i < $len} {incr i} {
    if {[regexp {([.\d]+)\s+([.\d]+)\s+.+ff\[0\]} [lindex $lines $i] -> net1_delay acc_delay]} {
      break  
    }
  }

  ;# extract net delay from source to through
  for {} {$i < $len} {incr i} {
    if {[regexp {([.\d]+)\s+([.\d]+)\s+.+BUFGCE_inst/O} [lindex $lines $i] -> bufg_delay acc_delay]} {
      break  
    }
  }
 
  ;# extract net delay from source to through
  for {} {$i < $len} {incr i} {
    if {[regexp {([.\d]+)\s+([.\d]+)\s+.+bufg_out} [lindex $lines $i] -> net2_delay acc_delay]} {
      break  
    }
  }

  return [list $net1_delay $bufg_delay $net2_delay]
}


# Trim nodes specific to the current source FF out so that the remaining route can be used for other cases.
proc trim_front_to_IMUX {nodes {key IMUX_\[EW\]\\d}} {
  for {set i 0} {$i < [llength $nodes]} {incr i} {
    if {[regexp "$key" [lindex $nodes $i]]} { 
      return [lrange $nodes $i end]
    }
  }
}


# This is a faster way to get nodes than tcl command get_nodes before 2020. Still use it for portability.
proc get_nodes_fast { name } {return [get_nodes -of [get_tiles [file dir $name]] -filter "NAME=~$name"]}


# Trim nodes specific to the FF out so that the remaining route can be used for other FFs.
proc trim_end_from_last_HDISTR {nodes {v 0}} {
  set nodes [lreverse $nodes]

  ;# find last HDIST. 
  set i 0
  for {} {$i < [llength $nodes]} {incr i} {
    set node [get_nodes_fast [lindex $nodes $i]]
    if {[get_property INTENT_CODE_NAME $node] == "NODE_GLOBAL_HDISTR"} {
      if {$v} {puts "found last HDISTR $node  at $i"}
      break
    }
  }
  if {$i < [llength $nodes]} {
    return [lreverse [lrange $nodes $i end]] 
  } else {
    puts "***ERROR  trim_end_from_last_HDISTR cannot HDISTR"
  }
}


# Read delay from an INT tile to IMUX of a route toward a BUFG
# Must execute GetDelayFromSourceToSinkINT before calling this.
proc read_delay_to_bufg {name} {
  set fname "${name}_getDelayToSinkINT.txt"
  set fi [open "$fname" "r"]
  set lines [split [read $fi] "\n"]
  close $fi
  foreach line $lines {
    lassign [split $line " "] buf val
    break  
  }
  file delete -force $fname

  return $val
}
