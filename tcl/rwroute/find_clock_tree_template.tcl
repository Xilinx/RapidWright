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


# find_clock_tree_template.tcl --
#
#
# Rational:
#
# Extracting delay information of clock network seems simple due to its disjoint nature, 
# ie., a route cannot switch tracks. However, its strict structure, ie., VROUTE -> VDISTR -> HDISTR, 
# makes it so inflexible that setting up test circuits to exact delays of each resource becomes difficult.
# As a result, we resort to capturing clock template for a given set of clock regions used by the clock domain 
# and the driving bufg. Because there are limited set of configurations, this approach is feasible.
# Another reason that make this approach more preferable is that the delay computing relating to MMCM is complicated.
# Using this template approach, such complication becomes oblivious.
#
# This script produces the middle section of a clock tree, ie., the section between the centroid of the footprint of the 
# clock domain to leaf clock buffers. RWRoute will complete the beginning and ending section. 
#
# We could use this script to produce a template just for the given design. The output files obtained from different designs 
# can be combined to be used in another design if applicable. Once the files include all the cases for a device. 
# The files can be used in any design implemented on the device without relying on this script any further.
#
# Usage:
#   1) List clock regions covering the foot print of the target clock domain in a Tcl dictionary, as shown in variable pblocks below.
#   2) List clock regions and list of INT tiles to measure skews, as shown in variable pb2int_tiles below. 
#      The important information is the entry INT tile, where the clock tree entering that clock region. 
#      The entry INT tile will be used to measuring a nominal skew.
#      The entry INT tile MUST be one of the tile in the corresponding list.
#      The given entry is the initial. The script will adjust it to match the clock tree automatically.
#   3) Specify BUFG location (pb_bufg variable) to be used to derived clock skews.   
#     The skew info recorded referencing the centroid of all pblocks. Thus, the skew information can be used even when BUFG location 
#     of a design differ from the one used here. However, better skews might be possible if the exact BUFG location are used.
#   4) Run the flow (find_clock_tree_template).
#      - source find_clock_tree_template.tcl -notrace
#      - find_clock_tree_template $pblocks $pb2int_tiles $pb_bufg  X2Y2-X3Y3_BUFG_X4Y0.txt
#      (if the default inputs are used, the output file should match that under test_data/find_clock_tree_template directory)
#   5) The clock skew information for the given setting will be written out to a file, eg., X2Y2-X3Y3_BUFG_X4Y0.txt.   
#


################################################## Necessary Inputs #####################################################
#
# Please redefine them to match the footprint of the clock domain.

# Specify clock regions and their slices to cover the target footprint of the clock domain. 
set pblocks        {X2Y3 SLICE_X55Y180:SLICE_X87Y239 X3Y3 SLICE_X88Y180:SLICE_X111Y239 \
                    X2Y2 SLICE_X55Y120:SLICE_X87Y179 X3Y2 SLICE_X88Y120:SLICE_X111Y179 }
                    
# Specify all the INT tiles to get skew info under subkey tiles. Pick one of the those tiles to be used as an initial entry.
# In the script, one of the tile will be snapped to the clock entry point of the clock region. The entry will be changed accordingly.
# It is recommended to have at least 3 tiles. Note that p2int_tiles is a 2-level dictionary.
set pb2int_tiles   {X2Y3 {entry INT_X52Y210 tiles {INT_X36Y210 INT_X40Y210 INT_X44Y210 INT_X48Y210 INT_X52Y210 INT_X56Y210}} \
                    X3Y3 {entry INT_X57Y210 tiles {INT_X57Y210 INT_X62Y210 INT_X66Y210 INT_X71Y210}}                         \
                    X2Y2 {entry INT_X52Y150 tiles {INT_X36Y150 INT_X40Y150 INT_X44Y150 INT_X48Y150 INT_X52Y150 INT_X56Y150}} \
                    X3Y2 {entry INT_X57Y150 tiles {INT_X57Y150 INT_X62Y150 INT_X66Y150 INT_X71Y150}}}

# Specify pblock for BUFG. 
set pb_bufg        CLOCKREGION_X4Y0:CLOCKREGION_X4Y0                     


################################################## Main proc #####################################################


# Find the clock tree with minimum clock skew between the given clock regions. 
#   - Use Vivado to build a clock tree for the target clock regions. 
#   - Iteratively adjust buffers to improve minimum negative skew.
#   - Dump the clock template out to a file.
#
# Arguments:
#   pblocks      : Dictionary containing the slices range for each target clock region.
#   pb2int_tiles : Dictionary containing the list of INT tiles to measure skew for each clock region.
#   pb_bufg      : Pblock of a BUFG to build template for.
#   outfile      : The file to write the clock template to.
#   v (optional) : verbose option, default to 0 which is not verbose.
#
proc find_clock_tree_template {pblocks pb2int_tiles pb_bufg outfile {v 0}} {
  variable main_dcp_name

  ;# It was verified over different row/col that this order remains the same, while the delay value can changes.
  ;# It was not verified when spanning over large blocks such as IO or PCIE.
  set tap_order {{0 0} {1 0} {2 0} {0 1} {4 0} \
                 {1 1} {2 1} {0 2} {8 0} {4 1} \
                 {1 2} {2 2} {8 1} {4 2} {0 4} \
                 {8 2} {1 4} {2 4} {4 4} {8 4}}
 

  if {[entry_in_list_of_pb2int_tiles $pb2int_tiles] == 0} {
    puts "ERROR: pb2int_tiles does not meet the requirement. An entry INT tile is not in the list."
    return  
  }


  ;#-------------  Setup design -----------
  ;# Create_project, synth, place and route
  set project_name "chain"
  set clock_net "clk_IBUF_BUFG" 
  lassign [prepare_design $project_name $pblocks $pb2int_tiles $pb_bufg $v] pbs2ffs pb2ffs
  ;# pb2clk_pin does not need updating because it is independent from tile information.
  set pb2clk_pin   [get_pb2clk_pin $pb2ffs]
  set pb2clk_route [store_clock_tree $pb2clk_pin $v]

  ;# Update entry INT tiles for each CR
  ;# Snap an INT tile from the list to the INT tile used by the clock tree to enter the clock region. An entry INT tile will be updated too.
  lassign [update_pb_to_int_tiles $pblocks $pb2int_tiles $pb2ffs $pbs2ffs $pb2clk_pin $v] pb2int_upd pb2ffs_upd pbs2ffs_upd
  if {[entry_in_list_of_pb2int_tiles $pb2int_upd] == 0} {
    puts "ERROR: update_pb_to_int_tiles produces wrong result. Please report this bug."
    return  
  }

  ;# Assign FFs to updated location
  ;# Set the stride to place FFs.
  set slice_increment 1
  helper_proc::assign_ff_to_location $pb2ffs_upd $slice_increment $v


  ;#-------------  Collect clock tree info -----------
  ;# Route again after design update
  ;# Without unrouting, some clock pins are left unrouted!
  route_design -unroute -quiet
  fix_clock_tree $pb2ffs $pb2clk_route $clock_net
  route_design -quiet 

  ;# Record buffers
  lassign [find_pblock_buffer_info $pblocks $pb2ffs_upd] pb2bufs row2pbs


  ;#-------------  Minimize skew -----------
  ;# Consider only entry point of each pblock for optimization.
  ;# RW clock router can run these optimization considering all INT tile if desired because all info are recorded.
  lassign [filter_only_entry_tiles $pb2int_upd $pbs2ffs_upd $pb2bufs] pbs2ffs_entry_only  pb2bufs_entry_only

  ;# Populate the same tap_order for each clock region to be used in optimization
  set pb2tap_list [dict create]
  dict for {pb range} $pblocks {
    dict set pb2tap_list $pb $tap_order
  }

  ;# Close project and start working with dcp only
  write_checkpoint $main_dcp_name.dcp -quiet
  write_edif       $main_dcp_name.edf -quiet
  close_project
  file delete -force $project_name

  ;# Make sure to clear every buffer by using pb2bufs  not just pb2bufs_entry_only
  clear_taps_for_pblocks_on_dcp $pb2bufs $main_dcp_name $v

  open_checkpoint $main_dcp_name.dcp -quiet
  ;# Invariant 1) there is one checkpoint opened. 2) a dcp has a coresponding edif on disk.
  lassign [optimize $pblocks  $pb2bufs_entry_only $pbs2ffs_entry_only $pb2tap_list "" $v] skew2dpb src_dly dst_dly skew_info


  ;#-------------  Write out clock template -----------
  set col_variation [report_setup_paths_skew_variation $pb2int_upd $pbs2ffs_upd $pb2bufs $v]
  set pb2clk_route  [collect_clock_route $pb2int_upd $pb2ffs_upd $v]
  set buf_taps      [get_taps $pb2bufs_entry_only]
  set skew_info     [add_skew_within_cr $skew_info]


  ;# Need a design opened, not necessary the same design that produce the list of nodes. Otherwise, querying a node will cause an error.
  ;#ERROR: [Common 17-53] User Exception: No open design. Please open an elaborated, synthesized or implemented design before executing this command.
  set res [write_clk_info $outfile $pb2int_upd $pb2bufs $pbs2ffs_upd $pb2ffs_upd $skew_info $pb2clk_route $buf_taps $col_variation $pb2tap_list];  

  close_checkpoint
  file delete {*}[glob $main_dcp_name.*]

  puts "\nfind_clock_tree_template finished."

  return $res  
}



################################################## Helper proc #####################################################
#
source [file join [file dirname [info script]] "helper_proc/clock_modeling_util.tcl"] -notrace

# There can be two set of dcp and edif on disk. One is the temporary used by the algorithm. The other is for RapidWright API to see and set taps.
set main_dcp_name "chain"
set temp_dcp_name "temp"
set temp_txt_name "temp"

;# Suppress info
set_msg_config -id "IP_FLOW 19-234"  -suppress
set_msg_config -id "IP_FLOW 19-1704" -suppress
set_msg_config -id "IP_FLOW 19-2313" -suppress
;# Suppress warning
set_msg_config -id "IP_FLOW 19-4830" -suppress



#--------------------------------------------------------------------------------------
#   Helper proc to setup design
#--------------------------------------------------------------------------------------


# Write a design used in measuring clock skew out to the given file name (without extension, .sv will be appended.)
proc write_ff_chain {fname} {
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
 
 
   (* keep = "true" *) logic [LENGTH-1:0] ff;
 
   always @(posedge clk) begin
     for (int i = 0; i < LENGTH ; i = i+1) begin
       ff[i] <= (i == 0) ? in : ff[i-1];
     end
   end
 
   assign out   = ff[LENGTH-1];
 
 endmodule 
 }

 puts $fo $text
 close $fo
}


# Create project containing a design to measure clock skew.
#
# Arguments:
#   pblocks, pb2int_tiles, pb_bufg, v (optional) : Same as defined for find_clock_tree_template.
# Results: (for details see assign_ff_to_pb)
#   pbs2ffs : a pair of ff indices to measure skew between two INT tiles of two clock regions.
#   pb2ffs  : List of ff indices
proc prepare_design {name pblocks pb2int_tiles bufgpb {v 0}} {
  set slice_increment 1  

  write_ff_chain $name  

  lassign [assign_ff_to_pb $pb2int_tiles $v] nff pbs2ffs pb2ffs
  helper_proc::ini_chain $nff $bufgpb $name
  file delete -force "$name.sv"
 
  ;# pblock is only for visual aid. all ff locations will be assigned.
  create_pblock_from_dict $pblocks
  helper_proc::assign_ff_to_location $pb2ffs $slice_increment $v

  place_design -quiet
  route_design -quiet 
  return [list $pbs2ffs $pb2ffs]
}


# Compute the number of ffs needed and determine their assignments to measure skew between all pair of CRs.
# 
# Arguments:
# pb2int_tiles, v : Same as defined for find_clock_tree_template
# Result:
#   pbs2ffs : Pairs of FF indices to measure between two INT tiles of two clock regions,
#             which is a dictionary with spb:int:dpb:int as a key and [list sff dff] as a value, 
#             ie., {X2Y3:INT_X52Y210:X3Y3:INT_X57Y210 {0 1} X3Y3:INT_X57Y210:X2Y3:INT_X52Y210 {1 2}}
#   pb2ffs  : List of FF indices associated with an INT tile, which is a dictionary with  = dict pb tile [list of FFs],
#             ie., {X2Y3 {INT_X36Y210 {30 61 86} INT_X40Y210 {32 63 88}} X3Y3 {INT_X62Y210 {3 71 96} INT_X66Y210 {5 73 98}}}
#                  
proc assign_ff_to_pb {pb2int_tiles {v 0}} {
  set pbs2ffs [dict create]
  set pb2ffs  [dict create]

  set pb_list [dict keys $pb2int_tiles]
  foreach pb $pb_list {
    foreach t [dict get $pb2int_tiles $pb tiles] {  
      dict set pb2ffs $pb $t [list]
    }
  }

  set idx 0
  for {set i 0} {$i < [llength $pb_list]} {incr i} {
    set cpb [lindex $pb_list $i]
    set ct  [dict get $pb2int_tiles $cpb entry]  
    dict with pb2ffs $cpb { lappend $ct $idx}
    incr idx
    for {set j 0} {$j < [llength $pb_list]} {incr j} {
      if {$i == $j} {
        continue
      }
      set opb [lindex $pb_list $j]
      ;# Invariant: idx-1 is at cpb  
      foreach t [dict get $pb2int_tiles $opb tiles] {  
        if {($j < $i) && ($t == [dict get $pb2int_tiles $opb entry])} {
          if {$v} {puts "skip add ff pairs between ${cpb}:${ct} and ${opb}:$t"}
          continue
        }
        dict set pbs2ffs "${cpb}:${ct}:${opb}:$t" [list [expr {$idx-1}] $idx]
        ;# There is one pb that never be cpb. Thus, set here to make sure all pb are in.
        dict with pb2ffs $opb { lappend $t $idx}
        incr idx
        dict set pbs2ffs "${opb}:${t}:${cpb}:${ct}" [list [expr {$idx-1}] $idx]
        dict with pb2ffs $cpb { lappend $ct $idx}
        incr idx
        if {$v} {puts "add ff pairs between ${cpb}:${ct} and ${opb}:$t    [dict get $pbs2ffs ${cpb}:${ct}:${opb}:$t] [dict get $pbs2ffs ${opb}:${t}:${cpb}:${ct}]"}
      }
    }
  }
  if {$v} {puts "number of FFs used is $idx"}

  return [list $idx $pbs2ffs $pb2ffs]
}


# Result: Return 0 if entry INT tile is not in the list of INT tiles.
proc entry_in_list_of_pb2int_tiles {pb2int_tiles} {
  foreach pb [dict keys $pb2int_tiles] {
    set entry [dict get $pb2int_tiles $pb entry]
    if {[lsearch [dict get $pb2int_tiles $pb tiles] $entry] < 0} {
      puts "ERROR: $pb : entry $entry is not in the list [dict get $pb2int_tiles $pb tiles]"
      return 0
    }
  }
  return 1
}


# Create pblock for the given list of pblock definition.
#
# Arguments: pblocks : Same as defined for find_clock_tree_template.
# Results:   valid pblocks in Vivado.
proc create_pblock_from_dict {pblocks} {
  dict for {k v} $pblocks {
    create_pblock $k
    resize_pblock $k -add $v
    set_property IS_SOFT FALSE [get_pblocks $k]
  }
  return
}


# Determine clock entry to each pblock and snap the closet INT tile in the list to the entry point.
# Return the list of updated data.
proc update_pb_to_int_tiles {pblocks pb2int_tiles pb2ffs pbs2ffs pb2clk_pin {v 0}} {
  if {$v} {puts "begin update_pb_to_int_tiles"}

  set new_pb2int_tiles [dict create]
  set new_pb2ffs $pb2ffs  
  dict for {pb range} $pblocks { 
    set t [get_int_tile_close_to_clock_entry $pb $range [dict get $pb2clk_pin $pb] $v]
    if {$v} {puts "pb $pb  entry $t"}  
    lassign [replace_tile_closet_to $t [dict get $pb2int_tiles $pb tiles]] new_tiles replaced_tile
    dict set new_pb2int_tiles $pb entry $t
    dict set new_pb2int_tiles $pb tiles $new_tiles
    if {$v} {
      puts "pb $pb   [dict get $pb2int_tiles $pb tiles]"
      puts "pb $pb  $new_tiles $replaced_tile"
      puts "$new_pb2int_tiles"
    }  
  }

  lassign [assign_ff_to_pb $new_pb2int_tiles $v] nff new_pbs2ffs new_pb2ffs 


  if {$v} {puts "end update_pb_to_int_tiles"}
  return [list $new_pb2int_tiles $new_pb2ffs $new_pbs2ffs]
}


# Return the INT tile closest to the clock entry point and a list of INT tiles to collect skew for the given CR
proc get_int_tile_close_to_clock_entry {pb pb_sl_ranges dst_clk_pin {v 0}} {
  set end_INT_tiles [list]
  set end_slices [split $pb_sl_ranges ":"]
  set sites [get_sites $end_slices]
  set end_tiles [get_tiles -of $sites]
  set end_tiles [lsort -command { apply { {a b} { return [expr {[get_property INT_TILE_X $a] - [get_property INT_TILE_X $b]}]} }} $end_tiles]
  set left_CLB  [lindex $end_tiles 0]
  set right_CLB [lindex $end_tiles 1]
  set left_col  [get_property COLUMN $left_CLB] 
  set right_col [get_property COLUMN $right_CLB]
  if {$v} {puts "CLB  left $left_col  right $right_col"}
  lappend end_INT_tiles [get_closet_INT_tile $left_CLB]
  lappend end_INT_tiles [get_closet_INT_tile $right_CLB]
  if {$v} {puts "end CLBs  $end_INT_tiles"}

  ;# Find connecting INT tile closesg
  set ns [lreverse [helper_proc::get_nodes_to_net_pin $dst_clk_pin]]
  set len [llength $ns]  

  ;# Find last HDIST. Only the last HDIST is within this clock region
  set i 0
  for {} {$i < $len} {incr i} {
    if {[get_property INTENT_CODE_NAME [lindex $ns $i]] == "NODE_GLOBAL_HDISTR"} {
      if {$v} {puts "found last HDISTR [lindex $ns $i]  at $i"}
      break
    }
  }

  ;# The node before that is still NODE_GLOBAL_HDISTR but local to one tile
  incr i  
  ;# Pick any one. All will be on the left or all on the right.
  if {$v} {puts "connecting node at $i   [lindex $ns $i]"}
  set connecting_tile [lindex [get_tiles -of [lindex $ns $i]] 0]
  set connecting_row  [get_property ROW    $connecting_tile]
  set connecting_col  [get_property COLUMN $connecting_tile]
  if {$v} {puts "before adjusting: connecting_col $connecting_col   connecting_row $connecting_row"}
  if       {$connecting_col <= $left_col}  { set connecting_col $left_col
  } elseif {$connecting_col >= $right_col} { set connecting_col $right_col 
  }
  if {$v} {puts "after  adjusting: connecting_col $connecting_col   connecting_row $connecting_row"}
  set connecting_tile     [get_tiles -quiet -filter "COLUMN == $connecting_col  && ROW == $connecting_row"]
  set connecting_INT_tile [get_closet_INT_tile $connecting_tile]

  if {$v} {puts "connecting tile $connecting_tile   connecting INT tile $connecting_INT_tile"}

  return $connecting_INT_tile
}


# Get the closest INT tile from a given tile.
# Assume UltraScale+ and this was tested when t is INT or CLB
proc get_closet_INT_tile {t} {
  if {[get_property TYPE $t] == "INT"} {
    return $t
  } elseif {[string match "CLE*" [get_property TYPE $t]]} { 
    set wt [helper_proc::get_neighbor_tile $t -1 0]
    if {[get_property TYPE $wt] == "INT"} { return $wt}
    set et [helper_proc::get_neighbor_tile $t  1 0]
    if {[get_property TYPE $et] == "INT"} { return $et}
    return -1
  } elseif {[string match "RCLK_*" [get_property TYPE $t]]} { 
    for {set i 1} {$i < 100} {incr i} {
      set nt [helper_proc::get_neighbor_tile $t  $i 1]
      if {[get_property TYPE $nt] == "INT"} { return $nt}
    }
    return -1
  } else {
    return -1
  }
}


# Replace the tile in the given tiles that is closest to tile r. 
proc replace_tile_closet_to { r tiles } {
  set tiles [get_tiles $tiles]
  set dist [list]
  foreach t $tiles {
    lappend dist [expr {abs([get_property INT_TILE_X $t] - [get_property INT_TILE_X $r])}]
  }

  set min_dist [tcl::mathfunc::min {*}$dist]
  set min_idx  [lsearch $dist $min_dist]
  ;# lrepalce throw Type error ! , lset face the same problem.
  ;#[lreplace $tiles $min_idx $min_idx $r]
  set res [list]
  for {set i 0} {$i < [llength $tiles]} {incr i} {
    if {$i == $min_idx} { lappend res $r 
    } else              { lappend res [lindex $tiles $i]
    }
  }
  return [list $res [lindex $tiles $min_idx]]
}



#--------------------------------------------------------------------------------------
#   Helper proc to collect clock tree info
#--------------------------------------------------------------------------------------


# Pick one FF clock pin per clock region
#
# Arguments: pb2ffs is  list of FFs in a clock region (see assign_ff_to_pb)
# Results:   A dictionary with a key is clock regions and value is one clock pin of FF in the clock region.
#
proc get_pb2clk_pin {pb2ffs} {
  set pb2clk_pin [dict create]
  dict for {pb val} $pb2ffs { 
    dict for {t ffs} $val {
      dict set pb2clk_pin $pb "ff_reg\[[lindex $ffs 0]\]/C"
      break  
    }
  }
  return $pb2clk_pin
}


# Retrieve clock route from source to the last HDISTR of each clock region.
#
# Arguments: pb2clk_pin is a dictionary obtained from get_pb2clk_pin.
# Results:   Return a dictionary with key is a clock region and its value is the route.
proc store_clock_tree {pb2clk_pin {v 0}} {
  set res [dict create]

  dict for {pb p} $pb2clk_pin {
    set nodes [lreverse [helper_proc::get_nodes_to_net_pin $p]]

    ;# Find last HDIST. Only the last HDIST is within this clock region
    set i 0
    for {} {$i < [llength $nodes]} {incr i} {
      if {[get_property INTENT_CODE_NAME [lindex $nodes $i]] == "NODE_GLOBAL_HDISTR"} {
        if {$v} {puts "found last HDISTR [lindex $nodes $i]  at $i"}
        break
      }
    }

    dict set res $pb [lreverse [lrange $nodes $i end]]
  }

  return $res
}


# Fix the clock net for each FF listed.
#
# Arguments:
#   pb2ffs : List of FFs associated with INT tiles. (see assign_ff_to_pb) 
#   pb2clk_route : clock route to each clock region (obtained from store_clock_tree)
proc fix_clock_tree {pb2ffs pb2clk_route net} {
  dict for {pb val} $pb2ffs { 
    dict for {t ffs} $val {
      set clk_route [dict get $pb2clk_route $pb]
      foreach idx $ffs {
        set cp [get_pins "ff_reg\[${idx}\]/C"]
        ;# get the site pin to $cp  
        set bp [get_bel_pins -of $cp]
        set sp [get_site_pins -of $bp]
        set n  [get_nodes -of $sp]
        set_property fixed_route [list $clk_route GAP $n ] [get_nets $net]
      }
    }
  }
  return
}


# Collect leaf and row buffers for each clock region.
#
# Results:
#   A list of 
#     a dictionary of clock_region::INT_TILE to [list leaf_buffer row_buffef]
#     a dictionary of row buffer to the list of clock regions whose clock route go through the row buffer. 
proc find_pblock_buffer_info {pblocks pb2ffs} {
  set pb2bufs [dict create]
  set row2pbs [dict create]  

  dict for {pb val} $pb2ffs { 
    dict for {t ffs} $val {
      set dff "ff_reg\[[lindex $ffs 0]\]"
      lassign [get_leaf_row_to $dff] leaf row
      dict set pb2bufs "$pb:$t" [list $leaf $row]
      dict lappend row2pbs $row $pb
    }
  }

  set row2pbs_set [dict create]
  dict for {k v} $row2pbs {
    dict set row2pbs_set $k [lsort -unique $v]
  }
  return [list $pb2bufs $row2pbs_set]
}


# Return [list leaf_buffer row_buffer] of the clock route toward the given FF cell (ff).
#
proc get_leaf_row_to {ff} {
  set key_row BUFCE_ROW_FSR
  set key_leaf LEAF  

  set rows  [list_bufce_to_sink $key_row  $ff/C]
  set leafs [list_bufce_to_sink $key_leaf $ff/C]

  if {[llength $rows] > 1} {
    puts "CRITICAL WARNING: number of BUFCE_ROW_FSR to $ff is [llength $rows]"
  }
  if {[llength $leafs] > 1} {
    puts "CRITICAL WARNING: number of BUFCE_LEAF to $ff is [llength $leafs]"
  }

  set row  [lindex $rows 0]
  set leaf [lindex $leafs 0]
  return [list $leaf $row]
}

# Find a node of a route toward the given sink that match the given key.
proc list_bufce_to_sink {key sink} {
  set res [list]
  set ns [helper_proc::get_nodes_to_net_pin $sink]
  set previous ""  
  foreach n $ns {
    if {[regexp "$key" $n]} {
      set nn [split $n "/"]
      set thenode [get_nodes -of [get_tiles [lindex $nn 0]] -filter "NAME=~$n"]  
      set si [get_sites -of [get_site_pin -of $thenode]]
      if {$si != $previous} {
        lappend res $si
      }
      set previous $si

    }
  }
  return $res
}


#--------------------------------------------------------------------------------------
#   Helper proc to minimize skew
#--------------------------------------------------------------------------------------


# Filter pb2ffs  pb2bufs to contain only entry INT tile.
#
proc filter_only_entry_tiles {pb2int_tiles pbs2ffs pb2bufs {vb 0}} {
  set pbs2ffs_entry_only [dict create]
  dict for {k v} $pbs2ffs {
    lassign [split $k ":"] spb st dpb dt
    if {$vb} { puts "pbs2ffs   $spb $st $dpb $dt : entry [dict get $pb2int_tiles $spb entry]  [dict get $pb2int_tiles $dpb entry]"}
    if {($st == [dict get $pb2int_tiles $spb entry]) && ($dt == [dict get $pb2int_tiles $dpb entry])} {
      dict set pbs2ffs_entry_only "$spb:$dpb" $v
      if {$vb} { puts "   for entry" }  
    }
  }

  set pb2bufs_entry_only [dict create]
  dict for {k v} $pb2bufs {
    lassign [split $k ":"] pb t
    if {$t == [dict get $pb2int_tiles $pb entry]} {
      dict set pb2bufs_entry_only $pb $v
    }
  }

  return [list $pbs2ffs_entry_only  $pb2bufs_entry_only]
}


# Clear (set to 0) delay taps of all buffers listed in $pb2bufs in the given dcp.
# Arguments: A dictionary of clock_region::INT_TILE to [list leaf_buffer row_buffef] obtained from find_pblock_buffer_info
#            to_idx is the last index of buffer list. By default, all buffers will be considered.
proc clear_taps_for_pblocks_on_dcp {pb2bufs dcp_name {to_idx -1} {v 0}} {
  variable temp_txt_name  
  
  set fo [open "$temp_txt_name.txt" "w"]  
  dict for {pb bufs} $pb2bufs {
    for {set i 0} {$i < [llength $bufs]} {incr i} {
      if {$i >= $to_idx} {
        puts $fo "[lindex $bufs $i] 0"
      } else {
        break
      }
    }
  }
  close $fo

  exec java com.xilinx.rapidwright.util.rwroute.ReadSetBufferTap $dcp_name.dcp --set $temp_txt_name.txt ./
    
  file rename -force ${dcp_name}_buffer_set.dcp   $dcp_name.dcp
  file delete -force $temp_txt_name.txt

  return
}


# Same as clear_taps_for_pblocks_on_dcp, but only clear leaf buffer
proc clear_leaf_taps_for_pblocks_on_dcp {pb2bufs dcp_name {v 0}} {
  ;# A leaf buffer is the first in the list
  clear_taps_for_pblocks_on_dcp $pb2bufs $dcp_name 0 
  return
}


# Given a list of buffers and their taps, apply the tap to the design in memory.
proc apply_taps {tap_change {v 0}} {
  variable temp_txt_name  
  variable temp_dcp_name  

  write_checkpoint $temp_dcp_name.dcp -quiet
  write_edif       $temp_dcp_name.edf -quiet

  set fo [open "$temp_txt_name.txt" "w"]  
  dict for {buf val} $tap_change {
    puts $fo "$buf $val"
  }
  close $fo

  exec java com.xilinx.rapidwright.util.rwroute.ReadSetBufferTap $temp_dcp_name.dcp --set $temp_txt_name.txt ./

  close_checkpoint
  open_checkpoint ${temp_dcp_name}_buffer_set.dcp -quiet
    
  file delete {*}[glob $temp_dcp_name.*]
  file delete -force ${temp_dcp_name}_buffer_set.dcp
  file delete -force $temp_txt_name.txt

  return
}

 
# Given a list of buffers and their taps, update the given taps to the dictionary.
proc update_buf_tap_dict {buf_tap_dict tap_change} {
  set res $buf_tap_dict
  dict for {buf val} $tap_change {
    dict set res $buf $val
  }
  return $res
}


# Create an initial buffers to taps dictionary with tap 0.
proc build_ini_buf_tap_dict {pb2bufs} {
  set res [dict create]
  dict for {pb bufs} $pb2bufs {
    foreach buf $bufs {
      dict set res $buf 0
    }
  }
  return $res
}


# Store the current taps by storing them in the main dcp
proc store_taps {pb2bufs {dcp_name ""}} {
  variable main_dcp_name  
  variable temp_txt_name  

  if {$dcp_name == ""} {
    set dcp_name $main_dcp_name
  }

  ;# Invariant 1) there is one checkpoint opened. 2) a dcp has a corresponding edif on disk.
  ;# Thus need -force to override the dcp on disk.
  write_checkpoint $dcp_name.dcp -force -quiet
  write_edif       $dcp_name.edf -force -quiet

  return
}


# Restore the tap of buffers from a dcp
proc restore_taps {{dcp_name ""}} {
  variable main_dcp_name  

  if {$dcp_name == ""} {
    set dcp_name $main_dcp_name
  }

  close_checkpoint
  open_checkpoint $dcp_name.dcp -quiet
  return
}


# Read taps of all buffer of the dcp which must already exists on disk. 
# Arguments: A dictionary of clock_region::INT_TILE to [list leaf_buffer row_buffef] obtained from find_pblock_buffer_info.
# Results: a dictionary similar to the input but with tap associated with each buffer.
proc read_taps_from_dcp {pb2bufs dcp_name} {
  variable temp_txt_name  


  set fo [open "$temp_txt_name.txt" "w"]  
  dict for {pb bufs} $pb2bufs {
    foreach buf $bufs {
      puts $fo "$buf"  
    }
  }
  close $fo

  exec java com.xilinx.rapidwright.util.rwroute.ReadSetBufferTap $dcp_name.dcp --read $temp_txt_name.txt ./

  set buf_tap [dict create]

  ;# fi is not quite small
  set fi [open "${dcp_name}_buffer_tap.txt" "r"]
  set lines [split [read $fi] "\n"]
  close $fi
  foreach line $lines {
    lassign [split $line " "] buf val
    dict set buf_tap $buf $val  
  }

  set res [dict create]
  dict for {pb bufs} $pb2bufs {
    foreach buf $bufs {
      dict set res $pb $buf [dict get $buf_tap $buf]
    }
  }

  file delete -force $temp_txt_name.txt
  file delete -force ${dcp_name}_buffer_tap.txt

  return $res
}


# Return the taps of all listed buffers of the design in memory
# Arguments: A dictionary of clock_region::INT_TILE to [list leaf_buffer row_buffef] obtained from find_pblock_buffer_info
# Results: a dictionary similar to the input but with tap associated with each buffer.
proc get_taps {pb2bufs} {
  variable temp_dcp_name  
  variable temp_txt_name  

  write_checkpoint $temp_dcp_name.dcp -quiet
  write_edif       $temp_dcp_name.edf -quiet

  set res [read_taps_from_dcp $pb2bufs $temp_dcp_name]

  file delete {*}[glob $temp_dcp_name.*]

  return $res
}



# Iteratively improve minimum negative skew between any pair of clock regions.
#
# Arguments:
#   pblocks : clock region and its range (see find_clock_tree_template)
#   pb2bufs : specify buffers for each INT tile of each clock region (see find_pblock_buffer_info) (must contain only entry tile)
#   pbs2ffs : list of FFs associated with INT tiles (see assign_ff_to_pb) (must contain only entry tile)
#   pb2tap_list : a map of clock regions to an ordered list of leaf and row buffer taps in increasing delay.
#   row2pbs : a map of row buffer to the list of clock regions whose clock route go through the row buffer. (see find_pblock_buffer_info) 
#
# Results:  Return a list of clock delay information obtained from report_setup_paths_to_pblocks
proc optimize {pblocks pb2bufs pbs2ffs pb2tap_list row2pbs {v 0}} {
  set buf_tap_dict [build_ini_buf_tap_dict $pb2bufs]

  set npb [llength [dict keys $pblocks]]
  ;# Row and Leaf have 4 and 5 taps, respectively.
  set max_ite [expr {$npb * 4 * 5}]
  ;# It is possible that all pb can't be adjusted.
  set max_num_stuck $npb 


  lassign [report_setup_paths_to_pblocks $pblocks $pbs2ffs $v] skew2dpb
  set msk [lindex [lsort -real [dict keys $skew2dpb]] 0]
  set pb [dict get $skew2dpb $msk]
  store_taps $pb2bufs
  if {$v} {puts "best taps : $best_taps"}


  puts "start optimizing loop"
  set num_stuck 0

  for {set num_ite 1} {($num_ite <= $max_ite) && ($num_stuck < $max_num_stuck)} {incr num_ite} {

    puts "\n*** Iteration $num_ite ***"
    puts "\nworst neg skew $msk to $pb"
    
    lassign [iteration $pblocks $pb2bufs $pbs2ffs $pb2tap_list $row2pbs $pb $msk $buf_tap_dict $v] status pb nmsk skew2pb buf_tap_dict
    if {($status < 0) || ($nmsk < $msk)} {
      break
    } elseif {$nmsk > $msk} {
      set num_stuck 0
      puts "min skew improves."  
      store_taps $pb2bufs
      if {$v} {puts "new best taps : $best_taps"}
    } else {
      incr num_stuck
      puts "min skew does not improve for $num_stuck consecutive iterations."  
    }
    set msk $nmsk
  }

  puts "\n*** end optimization loop at iteration $num_ite ***"

  restore_taps
  return [report_setup_paths_to_pblocks $pblocks $pbs2ffs $v]
}


# Try to improve the minimum negative skew. 
# Select the next taps to increase the clock delay to the destination CR of the current worst skew. 
#
# Arguments:
#   pblocks, pb2bufs, pbs2ffs, pb2tap_list, row2pbs are the same as those for optimize.
#   ipb  : The destination CR of the current worst skew
#   imsk : current min negative skew
# Results: Return a list of
#   - status : -1 if fail to improve
#   - The destination CR of the improved worst skew
#   - The improved min negative skew
#   - A map from a skew and its dest CR, as returned by report_setup_paths_to_pblocks .
#
#TODO: If the next taps will increase rtap of a ROW buffer shared with other sink,
# It will increase delay to multiple sink at once!  Two things can happens.
# 1) It will not degrade any sinks. or 
# 2) It will and thus the next tap whose rtap remain the same should be used if exists.
# Thus, iteration should do both and pick better one.
proc iteration {pblocks pb2bufs pbs2ffs pb2tap_list row2pbs ipb imsk ibuf_tap_dict {v 1}} {
  set buf_tap_dict $ibuf_tap_dict
  store_taps $pb2bufs

  lassign [dict get $pb2bufs $ipb]  leaf row
  set ltap [dict get $buf_tap_dict $leaf]
  set rtap [dict get $buf_tap_dict $row]

  lassign [increase_tap $ipb $pb2bufs $pb2tap_list $row2pbs $ltap $rtap $v] nt_exist rtap_change tap_change
  if {$nt_exist > 0} {
    apply_taps $tap_change
    lassign [report_setup_paths_to_pblocks $pblocks $pbs2ffs $v] skew2dpb
    set msk [lindex [lsort -real [dict keys $skew2dpb]] 0]
    set pb [dict get $skew2dpb $msk]

    if {$msk >= $imsk} {
      set buf_tap_dict [update_buf_tap_dict $buf_tap_dict $tap_change]
      return [list 1 $pb $msk $skew2dpb $buf_tap_dict]
    } else {
      if {$rtap_change > 0} {
        if {$v} {puts "rtap change and get worst skew. try again to find later tap with same rtap"}
        restore_taps
        lassign [increase_tap_same_rtap $ipb $pb2bufs $pb2tap_list $row2pbs $ltap $rtap $v] nt_exist tap_change
        if {$nt_exist > 0} {
          apply_taps $tap_change
          if {$v} {puts "next tap with same rtap exists."}
          lassign [report_setup_paths_to_pblocks $pblocks $pbs2ffs $v] skew2dpb_2
          set msk_2 [lindex [lsort -real [dict keys $skew2dpb]] 0]
          set pb_2 [dict get $skew2dpb $msk]
          if {$msk_2 >= $imsk} {
            if {$v} {puts "next tap with same rtap exists and do not degrade skew."}
            set buf_tap_dict [update_buf_tap_dict $buf_tap_dict $tap_change]
            return [list 1 $pb_2 $msk_2 $skew2dpb_2 $buf_tap_dict]
          } else {
            if {$v} {puts "next tap with same rtap exists but DO degrade skew. restore taps."}
            restore_taps
            return [list -1 0 0 0 $buf_tap_dict]
          }
        } else {
          if {$v} {puts "there is no other taps with same rtap. restore taps."}
          restore_taps
          return [list -1 0 0 0 $buf_tap_dict]
        }  
      } else {
        if {$v} {puts "skew is not improved. restore taps."}
        restore_taps
        return [list -1 0 0 0 $buf_tap_dict]
      }
    }
  } else {
    if {$v} {puts "next tap DOES NOT exist"}
    puts "There is no next tap for $ipb from $ltap $rtap." 
    return [list -1 0 0 0 $buf_tap_dict]
  }
}


# Set new taps if the next leaf and row buffer taps exist.
# Return a pair of indicator for leaf and row buffers. An indicator is -1 if the particular tap does not changes.
proc increase_tap {dpb pb2bufs pb2tap_list row2pbs ltap rtap {v 1}} {
  lassign [dict get $pb2bufs $dpb]  leaf row
  lassign [next_taps $ltap $rtap [dict get $pb2tap_list $dpb]] nltap nrtap 
  set tap_change [dict create]
  dict set tap_change $leaf $nltap
  dict set tap_change $row  $nrtap
  if {$nltap < 0} {
    return [list -1 -1 $tap_change]
  }

  if {$v} {puts "increase_tap to $dpb from $ltap $rtap to $nltap $nrtap"}
  if {$nrtap != $rtap} { 
    return [list 1  1 $tap_change]
  } else {
    return [list 1 -1 $tap_change]
  }
}


# Similar to increase_tap. However, the next set of taps must have the same row buffer tap as the current. 
proc increase_tap_same_rtap {dpb pb2bufs pb2tap_list row2pbs ltap rtap {v 1}} {
  set tap_change [dict create]
  lassign [dict get $pb2bufs $dpb]  leaf row
  set nrtap -1
  set i 0
  for {} {$i < [llength $pb2tap_list]} {incr i} {
    if {$rtap != $nrtap} {
      lassign [next_taps $ltap $rtap [dict get $pb2tap_list $dpb]] nltap nrtap 
      if {$nltap < 0} {
        return [list -1 $tap_change]
      }
    } else {
      break;
    }
  }
  
  if {$i >= [llength $pb2tap_list]} {
    puts "there is no next tap with same rtap."
    return [list -1 $tap_change]
  }


  if {$v} {puts "increase_tap to $dpb from $ltap $rtap to $nltap $nrtap"}
  dict set tap_change $leaf $nltap
  dict set tap_change $row  $nrtap
  return [list 1 $tap_change]
}


# Search for the next taps after the given taps in the ordered tap_list
# Arguments:
#   ltap : current tap of leaf buffer
#   rtap : current tap of row buffer
#   tap_list : an ordered tuples of leaf and row tap in increasing delay
# Results: Return a pair of next leaf and row taps, if exists. Otherwise, return [list -1 -1]
#
# Alternatives are 1) to keep the last used index, and 2) to delete the used entry from the list.
proc next_taps {ltap rtap tap_list} {
  for {set i 0} {$i < [llength $tap_list]} {incr i} {
    set entry [lindex $tap_list $i]
    if {([lindex $entry 0] ==  $ltap) && ([lindex $entry 1] ==  $rtap)} {
      incr i
      if {$i < [llength $tap_list]} {
        set next_ent [lindex $tap_list $i]
        return [list [lindex $next_ent 0] [lindex $next_ent 1]]  
      } else {
        ;# the given taps are at the end of the list.
        return [list -1 -1]
      }
    }
  }
  return [list -1 -1]
}


# Summarize delay on clock path to each clock region.
#
# Arguments:
#
# Results: Return a list of
#   - A map from a skew and its dest CR.
#   - A map from CR to its clock delay when the CR is a SOURCE of a timing path. 
#   - A map from CR to its clock delay when the CR is a DESTINATION of a timing path. 
#   - A list of [src_CR dst_CR skew src_dly dst_dly pessimism_removal]
#
# To be pessimistic, we use the min dst_dly, max src_dly and min pess.
# TODO: to avoid being too pessimistic, pess should not be considered seperatedly from min/max dly.
proc report_setup_paths_to_pblocks {pblocks pbs2ffs {v 1}} {
  if {$v} {puts "report_setup_paths_to_pblocks begin"}
  set lev 1
  set all_pess [list]
  set all_skew [list]
  set src_dly  [dict create]
  set dst_dly  [dict create]
  set skew2dpb [dict create]
  set skew_info [list ]


  set pb_list [dict keys $pblocks]
  foreach spb $pb_list {
    foreach dpb $pb_list {
      if {$spb == $dpb} { continue }
      lassign [dict get $pbs2ffs "$spb:$dpb"] sff dff 
      set sff ff_reg\[$sff\]
      set dff ff_reg\[$dff\]

      lassign [helper_proc::get_setup_timing_path $sff $dff] skew ep_dly ep_edg sp_dly pess
      if {$v>0} {
        puts [format "%s to %s (%-*s %-*s)  sk %6.3f  sp %5.3f  ed %5.3f  ee %5.3f  pess %5.3f" \
                     $spb $dpb  10 $sff 10 $dff  $skew $sp_dly $ep_dly $ep_edg $pess]
      }
      lappend skew_info [list $spb $dpb $skew $sp_dly $ep_dly $pess]
  
      lappend all_pess $pess
      lappend all_skew $skew

      dict set skew2dpb $skew $dpb

      set sp [expr {round(1000*$sp_dly)}]
      if {![dict exists $src_dly $spb]} {
        dict set src_dly $spb $sp 
      } else {
        set val [dict get $src_dly $spb]
        if {$val < $sp} {
          dict set src_dly $spb $sp
        }
      }
      set ep [expr {round(1000*($ep_dly))}]
      if {![dict exists $dst_dly $dpb]} {
        dict set dst_dly $dpb $ep 
      } else {
        set val [dict get $dst_dly $dpb]
        if {$val > $ep} {
          dict set dst_dly $dpb $ep 
        }
      }
    }
  }


  set avg [expr {[tcl::mathop::+ {*}$all_skew 0.0] / max(1, [llength $all_skew])}]
  set avg [expr {double(round(1000*$avg))/1000}]
  puts "  pessimism removal: [tcl::mathfunc::min {*}$all_pess]         [tcl::mathfunc::max {*}$all_pess]" 
  puts "  skew             : [tcl::mathfunc::min {*}$all_skew] $avg [tcl::mathfunc::max {*}$all_skew]" 


  if {$v>0} {
    puts "Delay to src clock region:"
    dict for {cr dly} $src_dly {
      puts "  $cr  $dly"  
    }
    puts "Delay to dst clock region:"
    dict for {cr dly} $dst_dly {
      puts "  $cr  $dly"  
    }
  }

  if {$v} {puts "report_setup_paths_to_pblocks end"}
  return [list $skew2dpb $src_dly $dst_dly $skew_info]
}



#--------------------------------------------------------------------------------------
#   Helper proc to write out clock template
#--------------------------------------------------------------------------------------


# Find delay to each target INT tile of each CR to adjust skew from the nominal value.
#
# Return a 2-level dictionary, ie.,  X2Y3 {INT_X36Y210 {src 2860 dst 2355} INT_X40Y210 {src 2853 dst 2346}}
proc report_setup_paths_skew_variation {pb2int_tiles pbs2ffs pb2bufs {v 1}} {
  variable temp_dcp_name  

  ;# clear leaf tap because only tap of entry tile is set during optimization. 
  ;# measure variation within the same group need all tap to be the same.
  store_taps $pb2bufs
  clear_leaf_taps_for_pblocks  $pb2bufs

  set res [dict create]

  set pb_list [dict keys $pb2int_tiles]
  for {set i 0} {$i < [llength $pb_list]} {incr i} {
    set j [expr {$i+1}]
    if {$j >= [llength $pb_list]} {set j 0}  

    set pb  [lindex $pb_list $i]
    set opb [lindex $pb_list $j]
    set opb_entry [dict get $pb2int_tiles $opb entry]

    foreach t [dict get $pb2int_tiles $pb tiles] {
      ;# src_dly
      lassign [dict get $pbs2ffs "$pb:$t:$opb:$opb_entry"] sff dff 
      if {$v} {puts "process $pb $t as src to $opb nominal tile $opb_entry $sff $dff"}
      set sff ff_reg\[$sff\]
      set dff ff_reg\[$dff\]
      lassign [helper_proc::get_setup_timing_path $sff $dff] skew ep_dly ep_edg sp_dly pess
      dict set res $pb $t src [expr {round(1000*$sp_dly)}]

      ;# dst_dly
      lassign [dict get $pbs2ffs "$opb:$opb_entry:$pb:$t"] sff dff 
      if {$v} {puts "process $pb $t as dst from $opb nominal tile $opb_entry $sff $dff"}
      set sff ff_reg\[$sff\]
      set dff ff_reg\[$dff\]
      lassign [helper_proc::get_setup_timing_path $sff $dff] skew ep_dly ep_edg sp_dly pess
      dict set res $pb $t dst [expr {round(1000*$ep_dly)}]
    }
  }

  restore_taps
  return $res
}


# Clear tap of all leaf buffer listed in pb2bufs
proc clear_leaf_taps_for_pblocks {pb2bufs} {
  variable temp_dcp_name  
  write_checkpoint $temp_dcp_name.dcp -quiet
  write_edif       $temp_dcp_name.edf -quiet
  clear_leaf_taps_for_pblocks_on_dcp $pb2bufs $temp_dcp_name
  restore_taps $temp_dcp_name
  file delete {*}[glob $temp_dcp_name.*]
}


# Collect clock route to each INT tile listed in pb2int_tiles
proc collect_clock_route {pb2int_tiles pb2ffs {v 0}} {
  set pb2clk_route [dict create]

  foreach pb [dict keys $pb2int_tiles] {   
    set entry_t [dict get $pb2int_tiles $pb entry]
    if {$v} {puts "pb $pb  entry_t $entry_t"}
    set idx [lindex [dict get $pb2ffs $pb $entry_t] 0]
    set dst_pin "ff_reg\[$idx\]/C"
    set nodes [helper_proc::get_nodes_to_net_pin $dst_pin]
    dict set pb2clk_route $pb $nodes
    if {$v} {puts "$pb   $nodes"}
  }

  return $pb2clk_route 
}


# Compute skew within the same CR pessimistically.
# Return skew_info with new entries added.
proc add_skew_within_cr {skew_info} {
  set src_dly  [dict create]
  set dst_dly  [dict create]
  set pess     [dict create]

  foreach entry $skew_info {
    set src_cr [lindex $entry 0]
    set dst_cr [lindex $entry 1]  


    dict lappend src_dly $src_cr [lindex $entry 3]
    dict lappend dst_dly $dst_cr [lindex $entry 4]
    dict lappend pess    $src_cr [lindex $entry 5]
    dict lappend pess    $dst_cr [lindex $entry 5]
  }

  set new_skew_info $skew_info
  dict for {cr values} $pess {
    set min_src_dly  [tcl::mathfunc::min {*}[dict get $src_dly $cr]]
    set max_dst_dly  [tcl::mathfunc::max {*}[dict get $dst_dly $cr]]
    set min_pess     [tcl::mathfunc::min {*}[dict get $pess $cr]]
    set skew [expr {double(round(1000*($max_dst_dly - $min_src_dly + $min_pess)))/1000}]
    lappend new_skew_info [list $cr $cr $skew $min_src_dly $max_dst_dly $min_pess]
  }

  return $new_skew_info
}


# Write clk tree info, to the givem file, be used by RW clock router.
proc write_clk_info {fname pb2int_tiles pb2bufs pbs2ffs pb2ffs skew_info pb2clk_route buf_taps col_variation pb2tap_list {v 0}} {
  set res [dict create]
  set fo [open "$fname" "w"]


  puts $fo "skew"
  puts $fo "# src  dst     skew  src_dly dst_dly pess"
  foreach entry $skew_info {
    if {$v} {puts $entry}
    puts $fo [format "%-*s %-*s  %4d  %4d    %4d   %4d" 6 [lindex $entry 0] 6 [lindex $entry 1] \
                                                  [expr {round(1000*[lindex $entry 2])}]  \
                                                  [expr {round(1000*[lindex $entry 3])}]  \
                                                  [expr {round(1000*[lindex $entry 4])}]  \
                                                  [expr {round(1000*[lindex $entry 5])}]  \
             ]
  }


  puts $fo "\nroute"

  set pb_list [dict keys $pb2int_tiles]
  set sample_nodes [dict get $pb2clk_route [lindex $pb_list 0]]
  set shortest_len [llength $sample_nodes]

  if {$v} {puts "shortest_len $shortest_len"}

  ;# fast forward to the node before first vdist
  set i 0
  for {} {$i < $shortest_len} {incr i} {  
    if {[string first "CLK_VDISTR_" [lindex $sample_nodes $i]] != -1} { break }
  }

  if {$i >= $shortest_len} {
    puts "ERROR: the clock tree does not contain CLK_HROUTE."
    return -1  
  }

  ;# i is at VDISTR, rewind
  incr i -1

  if {$v} {puts "write from index $i , for [lindex $sample_nodes $i]"}

  dict for {pb nodes} $pb2clk_route {
    set txt "$pb"
    for {set j $i} {$j < [llength $nodes]} {incr j} {
      if {[string first "CLK_LEAF_SITES_" [lindex $nodes $j]] != -1} { 
        break
      } else {
        append txt "  [lindex $nodes $j]"
      }
    }
    puts $fo "$txt"
    if {$v} {puts "$txt"}
  }


  puts $fo "\ndelay"
  puts $fo "# to       row_buf row_tap leaf_tap"

  foreach pb $pb_list {
    set taps [dict get $buf_taps $pb]
    dict for {buf tap} $taps {  
      if {[string first "LEAF" $buf] >= 0} { 
        set ltap $tap
        set leaf $buf  
      } else {
        set rtap $tap
        set row  $buf  
      }
    }
                                     
    set txt "$pb  $row $rtap  $ltap"
    puts $fo "$txt"
  } 


  puts $fo "\nvariation"
  puts $fo "# pb  nominal_INT    INT src_dly dst_dly        INT src_dly dst_dly ... "

  dict for {pb val} $pb2int_tiles {
    set entry [dict get $pb2int_tiles $pb entry]
    set ref_src [dict get $col_variation $pb $entry src]  
    set ref_dst [dict get $col_variation $pb $entry dst]  

    puts -nonewline $fo "$pb  $entry"

    foreach t [dict get $pb2int_tiles $pb tiles] {
      set d_src [expr {[dict get $col_variation $pb $t src] - $ref_src}]  
      set d_dst [expr {[dict get $col_variation $pb $t dst] - $ref_dst}]  
      puts -nonewline $fo [format "   %12s %+5d %+5d" $t  $d_src  $d_dst]
    }
    puts $fo ""
  }


  puts $fo "\ntap\n#tap order"
  dict for {pb taps} $pb2tap_list {
    puts $fo "$pb $taps"
  }  
    

  close $fo
  return $res  
}


proc close_checkpoint {} {
  close_design -quiet
}


#--------------------------------------------------------------------------------------

