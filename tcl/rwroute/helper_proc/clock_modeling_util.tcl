namespace eval helper_proc {



# Create project, synth, add clock, and set BUFG location
proc ini_chain {{length 4000} {bufgpb ""} {name chain} {ooc 0}} {

  create_project $name $name -part xcvu3p-ffvc1517-2-e -force
  import_files -norecurse $name.sv
  update_compile_order -fileset sources_1
  if {$ooc} {
    synth_design -top $name -generic "LENGTH=$length" -quiet -mode out_of_context
  } else {
    synth_design -top $name -generic "LENGTH=$length" -quiet
  }

  create_clock -name clk -period 2.0 [get_ports clk]

  if {($ooc == 0) && ($bufgpb != "")} {
    create_pblock pb_bufg
    resize_pblock pb_bufg -add $bufgpb
    set_property IS_SOFT FALSE [get_pblocks pb_bufg]
    add_cells_to_pblock pb_bufg [get_cells [list clk_IBUF_BUFG_inst clk_IBUF_inst]]
  }

  return
}


# Report delay between source FF f and sink FF t.
# Results: A list of skew, clock delay to 
proc get_setup_timing_path {f t} {
  set p [get_timing_paths -through [get_pins $f/Q] -through [get_pins $t/D] -setup]
  set skew   [get_property SKEW $p]
  set ep_dly [get_property ENDPOINT_CLOCK_DELAY $p]
  set ep_edg [get_property ENDPOINT_CLOCK_EDGE  $p]
  set sp_dly [get_property STARTPOINT_CLOCK_DELAY $p]
  set pess   [get_property CLOCK_PESSIMISM $p]
  return [list $skew $ep_dly $ep_edg $sp_dly $pess]
}


# Place FFs on slices associated with an INT tile.
#
# Arguments:
#   pb2ffs          : list of FF indices for an INT tile. (see assign_ff_to_pb)
#   slice_increment : Slice stride.
proc assign_ff_to_location {pb2ffs slice_increment {v 0}} {
  dict for {pb val} $pb2ffs { 
    dict for {t ffs} $val {
      set locs [build_ff_loc_list $t [llength $ffs] $slice_increment]
      if {$v} {puts "assign_ff_to_location $pb $t $ffs" }

      set j 0
      for {set i 0} {$i < [llength $ffs]} {incr i} {
        set ff "ff_reg\[[lindex $ffs $i]\]"
        if {$j < [llength $locs]} {
          for {} {$j < [llength $locs]} {incr j} {
            if {[place_cell_at $ff [lindex $locs $j]]} { 
              if {$v} {puts "ff_reg\[${i}\]  is placed at [lindex $locs $j]"}
              incr j
              break 
            }
          }
        } else {
          puts "ERROR: place_ff_near_clk_entry do not have enough loc. Either not enough locs or some locs are occupied."
        }
      }
    }
  }
  return
}


# Build a list of ff loc starting from the given slice(tile). 
#
# Arguments:
#   t       : the tile of the slice to place FF into.
#   num_ffs : the number of FF loc needed.
#   sl_inc  : stride to determine the next slice, ie., going to the slice below use sl_inc -1.
# Results: Return a list of FF locs whose size >= num_ffs.  
# 
proc build_ff_loc_list {t num_ffs {sl_inc 1}} {
  set ff_list {AFF BFF CFF DFF EFF FFF GFF HFF AFF2 BFF2 CFF2 DFF2 EFF2 FFF2 GFF2 HFF2}
  set sl [get_nearby_slice [get_tiles $t]]
  regexp {X(\d+)Y(\d+)} $sl -> x y
  set slice_prefix SLICE_X${x}

  set num_added 0
  set ff_loc [list]  
  for {} {$num_ffs > 0} {incr num_ffs -$num_added} {
    set sl ${slice_prefix}Y$y

    set num_added 0
    foreach f $ff_list {
      if {[get_cells -of [get_bels ${sl}/$f] -quiet] == ""} {
        lappend ff_loc $sl/$f
        incr num_added
      }
    }
    incr y $sl_inc
  }
  return $ff_loc
}


# Get a nearby slice of the given INT tile
proc get_nearby_slice {t} {
  set wt [get_neighbor_tile $t -1 0]
  if {[string first "CLE" [get_property TYPE $wt]] != -1} { return [get_sites -of $wt] }
  set et [get_neighbor_tile $t  1 0]
  if {[string first "CLE" [get_property TYPE $et]] != -1} { return [get_sites -of $et] }
  return -1
}


# Get the neighbor tile of the given tile t at the distance specified.
proc get_neighbor_tile {t dx dy} {
  set sx [get_property TILE_X $t]
  set sy [get_property TILE_Y $t]
  set tx [expr {$sx + $dx}]
  set ty [expr {$sy + $dy}]
  return [get_tiles -quiet -filter "TILE_X == $tx  && TILE_Y == $ty"]
} 


# Return the nodes of the route to the given destination pin. The nodes will be in order from src to dst.
# If multiple output sitepins are used for the net, only the sitepin driving the given dst will be returned.
# Note: cell pin ADR0 of LUTRAM placed on H5LUT or H6LUT will mapped to 2 pins. Thus, get_site_pins -of [get_pins <that_pin>] will return 2 site pins, though the same. 
proc get_nodes_to_net_pin {dst_pin} {
  set d_p [get_pins -quiet $dst_pin]
  return [get_nodes -to [lindex [lsort -unique [get_site_pins -of $d_p]] 0] -of [get_nets -of $d_p]]
}


# Place the cell at the given loc. Return 0, if cannot place.
proc place_cell_at {c loc} {
  if {[get_cells -of [get_bels $loc] -quiet] == ""} {
    place_cell -quiet $c $loc
    return 1
  } else {
    return 0
  }
}


# end namespace
}

