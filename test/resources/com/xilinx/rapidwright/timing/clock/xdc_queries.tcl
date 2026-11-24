# Prints the objects that Vivado's object queries return on a design, one query per line, for the XDC reader's
# reference test. Usage: vivado -mode batch -source xdc_queries.tcl -tclargs <design.dcp> [<first query>]
# Output lines: "Q <n> <query>", then "R <n> <count> <sorted names>" or "E <n> <error>"; "S <n> <command>" for the
# commands that set the current instance; "END" after the last query. Some errors end the Vivado session (e.g.
# a pattern given with -of_objects): run it again from the next query, after setting the current instance the
# queries before it set.
open_checkpoint [lindex $argv 0]
set first [expr {[llength $argv] > 1 ? [lindex $argv 1] : 1}]

set queries {
  {get_cells clk*}
  {get_cells sync_reset_inst/sync_reg_reg[*]}
  {get_cells {sync_reset_inst/sync_reg_reg[*]}}
  {get_cells sync_reset_inst/sync_reg_reg[2]}
  {get_cells {core_inst/eth_mac_inst/eth_mac_1g_rgmii_inst/eth_mac_1g_inst/axis_gmii_rx_inst/eth_crc_8/i_/crc_state[2?]_i_2}}
  {get_cells core_inst/eth_mac_inst/*}
  {get_cells}
  {get_cells -hierarchical clk_bufg_inst}
  {get_cells -hierarchical {sync_reg_reg[?]}}
  {get_cells -hierarchical sync_reset_inst/*}
  {get_cells -hierarchical {*/sync_reg_reg[0]}}
  {get_cells -hierarchical {i_/crc_state[29]_i_2}}
  {get_cells -hierarchical {*crc_state[29]_i_2}}
  {get_cells -hierarchical {eth_crc_8/i_/crc_state[29]_i_2}}
  {get_cells -hierarchical}
  {get_cells -nocase CLK_BUFG_INST}
  {get_cells -regexp {clk.*_inst}}
  {get_cells -hierarchical -filter {REF_NAME == BUFG}}
  {get_cells -hierarchical -filter {REF_NAME == bufg}}
  {get_cells -hierarchical -filter {ref_name == BUFG}}
  {get_cells -hierarchical -filter {REF_NAME == "BUFG"}}
  {get_cells -hierarchical -filter {REF_NAME =~ IDELAY* && NAME !~ *_3}}
  {get_cells -hierarchical -filter {PARENT == sync_reset_inst}}
  {get_cells -hierarchical -filter {IS_SEQUENTIAL && PARENT == sync_reset_inst}}
  {get_cells -hierarchical -filter {!IS_PRIMITIVE && NAME =~ core_inst/eth_mac_inst/*}}
  {get_cells -hierarchical -filter {(REF_NAME == BUFG || REF_NAME == IBUF) && NAME =~ clk*}}
  {get_cells -hierarchical -filter {NOSUCH_PROP == x && PARENT == sync_reset_inst}}
  {get_cells -hierarchical -filter {NOSUCH_PROP != x && PARENT == sync_reset_inst}}
  {get_cells -hierarchical -filter {PRIMITIVE_LEVEL != INTERNAL && PARENT == sync_reset_inst}}
  {get_cells -hierarchical -filter {REF_NAME == BUFG garbage}}
  {get_cells -hierarchical -filter {(REF_NAME == BUFG}}
  {get_cells -hierarchical -filter {REF_NAME == BUFG)}}
  {get_cells -of_objects [get_pins clk_bufg_inst/O]}
  {get_cells -of_objects [get_pins clk_bufg_inst/O] clk*}
  {get_cells -of_objects [get_nets sync_reset_inst/sync_reg_reg_n_0_[0]]}
  {get_cells $no_such_variable}
  {get_cells {}}
  {get_cells -hierarchical {}}
  {get_cells [get_cells nosuch]}
  {list [get_cells clk_bufg_inst] [get_cells clk90_bufg_inst]}
  {get_pins clk_bufg_inst/*}
  {get_pins sync_reset_inst/sync_reg_reg[*]/C}
  {get_pins -hierarchical {sync_reg_reg[0]/C}}
  {get_pins -hierarchical {sync_reset_inst/sync_reg_reg[0]/C}}
  {get_pins -hierarchical {i_/crc_state[29]_i_2/O}}
  {get_pins -hierarchical -filter {NAME =~ sync_reset_inst/*/D}}
  {get_pins -of_objects [get_cells clk_mmcm_inst] CLKOUT*}
  {get_pins -of_objects [get_cells clk_mmcm_inst] -filter {DIRECTION == OUT && IS_CONNECTED && REF_PIN_NAME =~ CLK*}}
  {get_pins -of_objects [get_cells {sync_reset_inst/sync_reg_reg[0]}] -filter {REF_PIN_NAME != CINT}}
  {get_pins {sync_reset_inst/sync_reg_reg[0]/CINT}}
  {get_pins -of_objects [list [get_cells clk_bufg_inst] [get_cells clk90_bufg_inst]] -filter {DIRECTION == OUT}}
  {get_pins -of_objects [get_nets clk_int]}
  {get_pins -leaf -of_objects [get_nets {eth_mac_inst/rx_fifo/fifo_inst/m_rst_sync3_reg}]}
  {get_pins}
  {get_pins G}
  {get_nets {eth_mac_inst/rx_fifo/fifo_inst/m_rst_sync3_reg}}
  {get_nets sync_reset_inst/*}
  {get_nets -hierarchical {sync_reset_inst/*}}
  {get_nets -hierarchical {sync_reg_reg_n_0_[?]}}
  {get_nets -of_objects [get_pins clk_bufg_inst/O]}
  {get_nets}
  {get_ports led[3]}
  {get_ports led}
  {get_ports -filter {DIRECTION == IN && NAME =~ btn*}}
  {get_ports -of_objects [get_nets clk]}
  {get_ports}
  {get_ports {}}
  {get_clocks}
  {get_clocks -nocase CLK}
  {get_clocks -of_objects [get_ports clk]}
  {get_clocks -of_objects [get_ports clk] nomatch*}
  {get_clocks -include_generated_clocks clk}
  {get_clocks -of_objects [get_pins clk_bufg_inst/O]}
  {S current_instance core_inst}
  {get_cells eth_mac_inst/*}
  {S current_instance eth_mac_inst}
  {get_cells *}
  {S current_instance ..}
  {get_cells *_inst}
  {S current_instance ../udp_complete_inst}
  {get_cells *}
  {S current_instance nosuch}
  {get_cells -hierarchical *_inst}
  {S current_instance -quiet}
  {S current_instance /core_inst}
  {get_cells eth_mac_inst}
  {S current_instance sync_reset_inst}
  {get_cells -hierarchical *}
  {S current_instance}
}

proc run_query {q} {
  return [uplevel #0 $q]
}

set n 0
foreach q $queries {
  incr n
  if {$n < $first && ![string match "S *" $q]} {
    continue
  }
  if {[string match "S *" $q]} {
    set cmd [string range $q 2 end]
    if {[catch {run_query $cmd} r]} {
      puts "S $n $cmd -> ERROR [string map {\n { }} $r]"
    } else {
      puts "S $n $cmd -> $r"
    }
    continue
  }
  puts "Q $n $q"
  if {[catch {run_query $q} r]} {
    puts "E $n [string map {\n { }} $r]"
    continue
  }
  set names {}
  foreach o $r {
    if {[catch {lappend names [get_property NAME $o]}]} {
      lappend names $o
    }
  }
  set names [lsort $names]
  puts "R $n [llength $names] [join $names { }]"
}
puts "END"
