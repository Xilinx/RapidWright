############################################################################## 
# Copyright (c) 2018 Xilinx, Inc. 
# All rights reserved.
#
# Author: Chris Lavin, Xilinx Research Labs.
#  
# This file is part of RapidWright. 
# 
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#     http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
############################################################################### 


# Parameter to support IP Integrator EDIF generation 
set_param bd.writeEdif 1

#
proc compileBlocks {dirName} {
    foreach dir [glob -directory $dirName -type d *] {
        set dcpFile [glob -directory $dir *_[0-9].dcp];
        if {[string match "*_routed.dcp" $dcpFile]} { continue }
        compileBlock $dcpFile
    }
}

proc compileBlock { dcpFile } {
    set blockImplCount [compile_block_dcp $dcpFile]
}

proc compile_block_dcp { dcpFile } {
    set rootDcpFileName [string map {".dcp" ""} $dcpFile]
    
    puts "Loading $dcpFile";

    open_checkpoint $dcpFile

    add_debug_hub_ports_on_ILAs

# Work around to get clock constraints loaded
    set unzipDir .unzip_${dcpFile}
    file mkdir ${unzipDir}
    set rwpath ${::env(RAPIDWRIGHT_PATH)}
    set cpath ${::env(CLASSPATH)}
    puts "RAPIDWRIGHT_PATH=$rwpath"
    puts "CLASSPATH=$cpath"
    exec java com.xilinx.rapidwright.util.Unzip ${dcpFile} ${unzipDir}
    read_xdc ${unzipDir}/${rootDcpFileName}_in_context.xdc
    file delete -force ${unzipDir}
# END Work around
    
    opt_design

    set optDcpFile [string map {".dcp" "_opt.dcp"} $dcpFile]
    write_checkpoint -force $optDcpFile

    set pBlockVal {}
    set designCells {}

    set urptName [string map {".dcp" "_utilization.report"} $dcpFile]
    report_utilization -packthru -file $urptName
    
    # Only generate a pblock for designs with actual logic (concat won't have any)
    set cells [get_cells]
    if { $cells != {} } {
        # We need shape sizes before determining pBlock size, unfortunately we have to run the placer to get them
        set shapesFileName [string map {".dcp" "_shapes.txt"} $dcpFile]
        set_param place.debugShape $shapesFileName
        place_design -directive Quick
        set_param place.debugShape ""
        place_design -unplace
        
        # Generate constraint
        puts "args $urptName $shapesFileName"
	if { [catch {set pBlockVal [exec java --illegal-access=deny -Xmx2G com.xilinx.rapidwright.design.blocks.PBlockGenerator -u $urptName -s $shapesFileName -c 1]}] } {
	    set pBlockVal "PBlockGenerator Failed!"
	}
        puts "pBlock = $pBlockVal, from: $urptName $shapesFileName"
        set fp [open [string map {".dcp" "_pblock.txt"} $dcpFile] "w"]
        puts $fp "$pBlockVal \n#Created from: com.xilinx.rapidwright.design.blocks.PBlockGenerator -u $urptName -s $shapesFileName -c 1"
        close $fp

        set designCells [get_cells -filter {NAME!=VCC && NAME!=GND}]
    }

    write_edif -force [string map {".dcp" "_routed.edf"} $dcpFile]
    puts "SUCCESSFUL_COMPLETION OF ${dcpFile}"
}

proc update_routed_dcp { dcpFile implIndex } {
    set routedDcpFile [string map ".dcp _${implIndex}_routed.dcp" $dcpFile]
    set dcpDir [string range $dcpFile 0 [string last "/" $dcpFile]]
    report_timing -file "${dcpDir}/route_timing${implIndex}.twr"
    generate_metadata $dcpFile "false" $implIndex
    write_checkpoint -force $routedDcpFile 
}

proc write_cache_impl { dcpFile implIdx } {
    report_timing -file "route_timing${implIdx}.twr"
    generate_metadata $dcpFile "false" $implIdx
    set routedDcpFile [string map ".dcp _${implIdx}_routed.dcp" $dcpFile]
    write_checkpoint -force $routedDcpFile
    exportTest $routedDcpFile
}

proc generate_metadata { dcpFile includePaths implIdx } {
    puts "Entering generate_metadata with $dcpFile"
    set tmp [string map {".dcp" ""} $dcpFile]
    set blockname [string range $tmp [string last "/" $tmp]+1 end]
    set mdname [string map ".dcp _${implIdx}_metadata.txt" $dcpFile]
    set md [open $mdname "w"]
    puts $md "begin block"
    puts $md "  name $blockname"
    puts $md "  pblocks [llength [get_pblocks]]"
    puts $md "  clocks [llength [get_clocks]]"
    puts $md "  inputs [llength [get_ports -filter { DIRECTION==IN } ]]"
    puts $md "  outputs [llength [get_ports -filter { DIRECTION==OUT } ]]"
    puts $md ""
    foreach p [get_pblocks] {
        puts $md "  begin pblock"
        puts $md "    name $p "
        puts $md "    grid_ranges [get_property GRID_RANGES $p]"
        puts $md "  end pblock"
    }
    foreach clk [get_clocks] {
        puts $md "  begin clock"
        puts $md "    name $clk "
        puts $md "    period [get_property PERIOD $clk]"
        puts $md "  end clock"
    }
    set inports [get_ports -filter { DIRECTION==IN }]
    set outports [get_ports -filter { DIRECTION==OUT }]
    foreach dir [list "IN" "OUT"] {

        puts $md ""
        set inout "input"
        set tofrom "from"
        set ports $inports
        if { $dir == "OUT" } {
            set inout "output"
            set tofrom "to"
            set ports $outports
        }
        foreach port $ports {
            puts $md "  begin $inout"
            puts $md "    name $port"
            set pplocs [get_property -quiet HD.ASSIGNED_PPLOCS $port]
            if { $pplocs != "" } {
                puts $md "    pplocs $pplocs"
            }
            set type "unknown"

            # types go here
            #  1. *    Input port drives a cell (type is input and numprims == 1)
            #  2. *    Input port drives multiple cells (type is input and numprims > 1)
            #  3. *    Input port drives no cells (type is input and numprims == 0)
            #  4. *    Input port is driven by GND/VDD (type is input [power|ground])
            #  5. *    Input port is a CLK  (type is input clock [local|global|regional])
            #  6.      Input port drives output port
            #  7.      Input port drives output port and one or more cells
            #  8. *    Output port is driven by a cell (type is output and numprims == 1)
            #  9. *    Output port is driven by GND/VDD originating from within the block (type is output [power|ground])
            #  10.     Output port is on a physical net that has other sinks inside block
            #  11.*    Output port is a CLK (type is output clock [local|global|regional])

            set net [get_nets -of_objects $port]
            if {$net == ""} {
                set type "$inout unconnected"
            } else {
                set nettype [get_property TYPE $net]
                set netname [get_property NAME $net]
                puts $md "    netname $netname"
                if {$nettype == "LOCAL_CLOCK"} {
                    set type "$inout clock local"
                } elseif {$nettype == "GLOBAL_CLOCK"} {
                    set type "$inout clock global"
                } elseif {$nettype == "REGIONAL_CLOCK"} {
                    set type "$inout clock regional"
                } elseif {$nettype == "POWER"} {
                    set type "$inout power"
                } elseif {$nettype == "GROUND"} {
                    set type "$inout ground"
                } elseif {$nettype == "DONT_CARE"} {
                    set type "$inout dontcare"
                } else {
                    set type "$inout signal"
                }
                set cells [get_cells -quiet -filter { IS_PRIMITIVE==1 } -of [get_nets -hier $netname]]
                puts $md "    numprims [llength $cells]"
            }

            puts $md "    type $type"
            set maxdelay 0.000
            set maxnetdelay 0.000
            set maxpath ""
            set paths [get_timing_paths -quiet -nworst 10 -$tofrom $port]
            if {[llength $paths] > 0} {
                foreach path $paths {
                    set delay [get_property DATAPATH_DELAY $path]
                    set netdelay [get_property DATAPATH_NET_DELAY $path]
                    if {$delay >= $maxdelay} {
                        set maxdelay $delay
                        set maxnetdelay $netdelay
                        set maxpath $path
                    }
                    #puts $md "DEBUG: delay $delay maxdelay $maxdelay"
                }
            }
            puts $md "    maxdelay $maxdelay"
            if {[string match "*unconnected*" $type]} {
                puts $md "  end $inout"
                continue
            }

            if {$maxdelay > 0.0} {
                if {$includePaths == "true" && [llength $paths] > 0} {
                    puts $md "    maxnetdelay $maxnetdelay"
                    puts $md "    maxpath $maxpath"
                    set paths [lsort -unique $paths]
                    foreach path $paths {
                        set delay [get_property DATAPATH_DELAY $path]
                        set netdelay [get_property DATAPATH_NET_DELAY $path]
                        set uncertainty [get_property UNCERTAINTY $path]
                        set sclk [get_property STARTPOINT_CLOCK $path]
                        set eclk [get_property ENDPOINT_CLOCK $path]
                        puts $md "    begin path"
                        puts $md "      name $path"
                        if {$sclk != ""} {
                            puts $md "      clock $sclk"
                        }
                        if {$eclk != ""} {
                            puts $md "      clock $eclk"
                        }
                        puts $md "      delay $delay"
                        puts $md "      netdelay $netdelay"
                        if {$uncertainty != ""} {
                            puts $md "      uncertainty $uncertainty"
                        }
                        puts $md "    end path"
                    }
                }
            }
            puts $md "    begin connections"
            set cports [get_ports -quiet -of [get_nets -segments $netname]]
            foreach cport $cports {
                if {$cport != $port} {
                    puts $md "      port $cport"
                }
            }

            set cpins [get_pins -quiet -leaf -of [get_nets -segments $netname]]
            foreach cpin $cpins {
                set ccell [get_cells -of [get_pins $cpin]]
                set loc [get_property -quiet LOC $ccell]
                set sitePin [get_site_pins -quiet -of $cpin]
                puts $md "      pin $cpin $loc $sitePin"
            }
            puts $md "    end connections"
            puts $md "  end $inout"
        }
    }

    puts $md "\nend block"
    close $md
}

proc get_placement { cell } {
    set site [get_property SITE $cell]
    set bel_toks [split [get_property BEL $cell] "."]
    set bel [lindex $bel_toks end]
    return "${site}/${bel}"
}

proc print_all_placements {} {
    foreach cell [get_cells -hierarchical -filter {PRIMITIVE_LEVEL==LEAF && STATUS!=UNPLACED}] { puts "$cell [get_placement $cell]" }
}

proc get_unplaced_cells {} {
    get_cells -hierarchical -filter {LOC=="" && PRIMITIVE_LEVEL=="LEAF" && REF_NAME!=GND && REF_NAME!=VCC}
}

proc get_time_string {} {
    set sysTime [clock seconds]
    return [clock format $sysTime -format {[%a %b %d %H:%M:%S %Y]}]
}

proc add_debug_hub_ports_on_ILAs {} {
    set inst "[get_cells -filter {IS_PRIMITIVE != 1}]"
    if { $inst == {} } {
        return
    }
    set type [get_property ORIG_REF_NAME $inst]
    if { [string match "*ila_v*" $type] } {
        puts "Exposing sl_ports on ILA..."
        set gndNet [get_nets <const0>]
        # INPUTS
        create_port -direction IN -from 0 -to 36 sl_iport0
        foreach net [get_nets -hierarchical -filter "NAME =~ ${inst}/sl_iport0*"] {
            set myPin [get_pins -hierarchical -filter "NAME == $net"]
            disconnect_net -net $gndNet -objects $myPin
            set_property DONT_TOUCH false $net
            connect_net -hierarchical -net $net -objects [get_port [get_property REF_PIN_NAME $myPin]]
        }
        #OUTPUTS
        create_port -direction OUT -from 0 -to 16 sl_oport0
        foreach net [get_nets -hierarchical -filter "NAME =~ ${inst}/sl_oport0*"] {
            set myPin [get_pins -hierarchical -filter "NAME == $net"]
            set_property DONT_TOUCH false $net
            connect_net -hierarchical -net $net -objects [get_port [get_property REF_PIN_NAME $myPin]]
        }
    }
}

proc rapid_compile_ipi { } {
    prep_for_block_stitcher

    run_block_stitcher
}

proc run_block_stitcher { } {
    set directory [get_property DIRECTORY [current_project]]
    set bdName [get_property NAME [current_bd_design]]
    
    set ipsFileName "${directory}/${bdName}_ips.txt"
    set topLevelEdifFileName "[pwd]/${bdName}.edf"
    set cachePath [get_property IP_OUTPUT_REPO [current_project]]
    
    puts "java -Xss16M com.xilinx.rapidwright.ipi.BlockStitcher ${cachePath}[cache_version_dir] $topLevelEdifFileName $ipsFileName"
    puts [exec java -Xss16M com.xilinx.rapidwright.ipi.BlockStitcher "${cachePath}[cache_version_dir]" $topLevelEdifFileName $ipsFileName]    
}

proc check_if_lsf_available {} {
    return [string equal [exec java com.xilinx.rapidwright.util.JobQueue -lsf_available] "true"]
}

proc prep_for_block_stitcher {} {
    set cachePath ""
    if { [info exists ::env(IP_CACHE_PATH)] } {
        set cachePath ${::env(IP_CACHE_PATH)}
    } else {
        set cachePath ${::env(HOME)}/blockCache
        puts "INFO-> IP_CACHE_PATH environment variable not set, using $cachePath"
    }
    config_ip_cache -import_from_project -use_cache_location $cachePath
    update_ip_catalog
    puts "Using IP Cache at $cachePath"

    cd [get_property DIRECTORY [current_project]]

    make_top_ipi_edif

    set directory [get_property DIRECTORY [current_project]]
    set projName [get_property NAME [current_project]]
    set bdName [get_property NAME [current_bd_design]]
    set bdFileName [get_property FILE_NAME [current_bd_design]]
    set filePath "${directory}/${projName}.srcs/sources_1/bd/${bdName}/${bdFileName}"

    set_property synth_checkpoint_mode Hierarchical [get_files  $filePath]
    generate_target all [get_files  $filePath]
    create_ip_run [get_files -of_objects [get_fileset sources_1] $filePath]

    set runs_needed {}
    set opt_runs_needed {}
    array set uniqueIPs []
    array set uniqueImplIPs []

    set ipsFileName "${directory}/${bdName}_ips.txt"
    set fp [open $ipsFileName w]
    puts $fp [get_property PART [current_project]]

    foreach ip_cell [get_bd_cells -hierarchical -filter TYPE==ip] {
        set ip [get_ips -all [get_property CONFIG.Component_Name $ip_cell]]
        if { [llength $ip] > 1 } {
            set ip [lindex $ip 0]
        }
        set name [get_property CONFIG.Component_Name $ip]
        set ip_run [get_runs "${name}_synth_1"]
        set id [config_ip_cache -get_id $ip]
        puts $fp "$name $ip $id $ip_cell"
        if { [config_ip_cache -cache_has_match $ip] == {} } {
            if { ![info exists uniqueIPs($id)] && $ip_run != {} } {
                set uniqueIPs($id) $name
                set uniqueImplIPs($id) $ip
                lappend runs_needed $ip_run
                lappend opt_runs_needed $ip
                puts "WILL RUN: $ip_run $name $ip $id $ip_cell"
            }
        } elseif { [needs_impl_run $cachePath $ip] && ![info exists uniqueImplIPs($id)] } {
            puts "OPT RUN: $ip"
            lappend opt_runs_needed $ip
            set uniqueImplIPs($id) $ip
        }
    }

    close $fp

    if {[llength $runs_needed] > 0} {
        puts "Launching ip builds..."
        foreach synthRun $runs_needed {
            reset_run $synthRun
        }
        if {[check_if_lsf_available]} {
            set resource [exec java com.xilinx.rapidwright.util.JobQueue -lsf_resource]
            set queue [exec java com.xilinx.rapidwright.util.JobQueue -lsf_queue]
            launch_runs -lsf "bsub -R $resource -N -q $queue" $runs_needed
        } else {
            launch_runs $runs_needed
        }
        
        foreach synthRun $runs_needed {
            puts "synthRun=$synthRun"
            wait_on_run $synthRun
        }
    }
    if {[llength $opt_runs_needed] > 0} {
        set jobs_file_name "${directory}/${bdName}.jobs"
        set fp_jobs [open $jobs_file_name w]     
        foreach ip $opt_runs_needed {       
            set name [get_property CONFIG.Component_Name $ip]
            set ip_run [get_runs "${name}_synth_1"]
            set dir "${cachePath}[cache_version_dir]/[config_ip_cache -get_id $ip]"
            file mkdir $dir
            set post_tcl_name "$dir/post.tcl"
            set fp [open $post_tcl_name "w"]
			puts $fp [subst -nocommands -novariables {if { [info procs rapid_compile_ipi] == "" } \{}]
			puts $fp [subst -nocommands -novariables {	if {[info exists env(RAPIDWRIGHT_PATH)]} \{}]
			puts $fp {		set rw_path $::env(RAPIDWRIGHT_PATH)}
			puts $fp {		source ${rw_path}/tcl/rapidwright.tcl}
			puts $fp [subst -nocommands -novariables {	\} else \{}]
			puts $fp {		error "Please set the environment variable RAPIDWRIGHT_PATH to point to your RapidWright installation."}
			puts $fp [subst -nocommands -novariables {	\}}]
			puts $fp [subst -nocommands -novariables {\}}]
            puts $fp "# $ip $ip_run"
            puts $fp "set dir $dir"
            puts $fp "set dcpName ${ip}.dcp"

	    puts $fp [subst -nocommands -novariables {set rwpath ${::env(RAPIDWRIGHT_PATH)}}]
	    puts $fp [subst -nocommands -novariables {set cpath ${::env(CLASSPATH)}}]
	    puts $fp [subst -nocommands -novariables {puts "RAPIDWRIGHT_PATH=$rwpath"}]
	    puts $fp [subst -nocommands -novariables {puts "CLASSPATH=$cpath"}]

            puts $fp {compileBlock $dcpName}
            close $fp
            set vivado_path [exec java com.xilinx.rapidwright.util.FileTools --get_vivado_path]
            puts $fp_jobs "$vivado_path -mode batch -source $post_tcl_name # $dir"
        }
        close $fp_jobs
        puts "Running opt_design jobs..."
	puts "java com.xilinx.rapidwright.util.JobQueue $jobs_file_name"
        puts [exec java com.xilinx.rapidwright.util.JobQueue $jobs_file_name]
    }    
}

proc create_block_post_tcl { ip  cachePath } {
    set name [get_property CONFIG.Component_Name $ip]
    set ip_run [get_runs "${name}_synth_1"]
    
    set cache_id [config_ip_cache -get_id $ip]
    set dir "${cachePath}[cache_version_dir]/${cache_id}"
    file mkdir $dir
    
    set post_tcl_name "$dir/post.tcl"
    set fp [open $post_tcl_name "w"]
    puts $fp "# $ip $ip_run"
    puts $fp "set dir $dir"
    puts $fp "set dcpName ${dir}/${ip}.dcp"
    puts $fp {compile_block $dcpName}
    close $fp
    return $post_tcl_name
}

proc ultra_clear_cache { } {
    config_ip_cache -clear_output_repo
    config_ip_cache -clear_local_cache

    set directory [get_property DIRECTORY [current_project]]
    set projName [get_property NAME [current_project]]
    set bdName [get_property NAME [current_bd_design]]
    set bdFileName [get_property FILE_NAME [current_bd_design]]

    set filePath "${directory}/${projName}.srcs/sources_1/bd/${bdName}/${bdFileName}"
    reset_target all [get_files $filePath]
}

proc cache_version_dir { } {
    if { [version -short] < 2018.3 } { 
    	return "/[version -short]" 
    } else { 
    	return "" 
    }
}

# Creates an EDIF file of the top level connectivity of the IPI design
proc make_top_ipi_edif { } {
    set directory [get_property DIRECTORY [current_project]]
    set projName [get_property NAME [current_project]]
    set bdName [get_property NAME [current_bd_design]]
    set bdFileName [get_property FILE_NAME [current_bd_design]]
    set filePath "$directory/${projName}.srcs/sources_1/bd/${bdName}/${bdFileName}"
    
    set targLang [get_property TARGET_LANGUAGE [current_project]]
    set wrapperExt "vhd"
    if { $targLang == "Verilog" } {
        set wrapperExt "v"
    }
    set wrapperPath "$directory/${projName}.srcs/sources_1/bd/${bdName}/hdl/${bdName}_wrapper.${wrapperExt}"
    if {[file exists $wrapperPath]} {
        puts "Removing old file"
        file delete -force $wrapperPath
    }
    make_wrapper -top -files [get_files $filePath]
}

proc sleep N {
    after [expr {int($N * 1000)}]
}

proc get_lines_matching {keyword file_name} {
    set fp [open $file_name]
    set results {} 
    while {[gets $fp line] >= 0} {
        if { [string first $keyword $line] != -1 } {
            lappend results $line
        }
    }
    close $fp
    return $results
}


proc needs_impl_run { cachePath ip } {
    set cache_id [config_ip_cache -get_id $ip]
    set cacheIPDir "${cachePath}[cache_version_dir]/$cache_id"
    if { ! [file exists $cacheIPDir] } {
        puts "OPT NEEDED: $cacheIPDir does not exist"
        return true
    }
    set existingIP [lindex [get_lines_matching instanceName ${cacheIPDir}/${cache_id}.xci] 0]
    set existingIP [string map {"<spirit:instanceName>" ""} $existingIP]
    set existingIP [string map {"</spirit:instanceName>" ""} $existingIP]
    set existingIP [string trim $existingIP]

    set existingEDF "${cacheIPDir}/${existingIP}_routed.edf"
    if { ! [file exists $existingEDF] } {
		puts "OPT NEEDED: ${cacheIPDir}/${existingIP}_routed.edf does not exist" 
        return true
    }
    if { [file mtime "${cacheIPDir}/${existingIP}.dcp"] > [file mtime "${existingEDF}"] } {
		puts "OPT NEEDED: ${cacheIPDir}/${existingIP}.dcp is newer than ${existingEDF}" 
        return true
    }
    return false
}

# create_preimplemented_ila_dcp xczu9eg-ffvb1156-2-i "SLICE_X44Y91:SLICE_X55Y118 RAMB36_X6Y19:RAMB36_X6Y22" 2 1024
proc create_preimplemented_ila_dcp { part probe_count probe_depth output_dcp} {
# Static parameters
    set proj_name ila
    set proj_loc .${proj_name}
    set bd_design design_1
    set synth_run synth_1
    set pblock pblock_1
    
# recipe for a pre-implemented ILA+Debug Hub     
    create_project -force $proj_name $proj_loc -part $part
    create_bd_design "design_1"
    update_compile_order -fileset sources_1
    create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0
    make_bd_pins_external  [get_bd_pins xlconstant_0/dout]
    make_wrapper -files [get_files ${proj_loc}/${proj_name}.srcs/sources_1/bd/${bd_design}/${bd_design}.bd] -top
    add_files -norecurse ${proj_loc}/${proj_name}.srcs/sources_1/bd/${bd_design}/hdl/${bd_design}_wrapper.v
    set_property -name {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} -value {-mode out_of_context} -objects [get_runs $synth_run]
    #launch_runs $synth_run -lsf {bsub -R "select[osdistro=rhel && (osver=ws7)]" -N -q medium}
    launch_runs $synth_run 
    wait_on_run $synth_run
    
    open_run $synth_run -name $synth_run
    
    set ilaInst u_ila_0
    create_debug_core $ilaInst ila
    set_property C_DATA_DEPTH $probe_depth [get_debug_cores $ilaInst]
    set_property C_TRIGIN_EN false [get_debug_cores $ilaInst]
    set_property C_TRIGOUT_EN false [get_debug_cores $ilaInst]
    set_property C_ADV_TRIGGER false [get_debug_cores $ilaInst]
    set_property C_INPUT_PIPE_STAGES 3 [get_debug_cores $ilaInst]
    set_property C_EN_STRG_QUAL false [get_debug_cores $ilaInst]
    set_property ALL_PROBE_SAME_MU true [get_debug_cores $ilaInst]
    set_property ALL_PROBE_SAME_MU_CNT 1 [get_debug_cores $ilaInst]
    set_property port_width 1 [get_debug_ports $ilaInst/clk]
    set_property port_width $probe_count [get_debug_ports $ilaInst/probe0]
    set_property PROBE_TYPE DATA_AND_TRIGGER [get_debug_ports $ilaInst/probe0]    
    
    set clk_net clk
    set x_1 [expr $probe_count - 1]
    
    create_port -direction IN $clk_net
    create_net $clk_net
    connect_net -net $clk_net -objects [list [get_ports $clk_net] [get_pins $ilaInst/clk] [get_pins dbg_hub/clk]]

    set probe_net probes
    create_port -direction IN -from 0 -to $x_1 $probe_net
    create_net $probe_net -from 0 -to $x_1
    for {set i 0} {$i < $probe_count} {incr i} {
        connect_net -net ${probe_net}\[$i\] -objects [list [get_ports ${probe_net}\[$i\] ] [get_pins $ilaInst/probe0\[$i\] ]]
    }
    
    remove_cell ${bd_design}_i
    remove_net dout
    remove_port dout
    
    file mkdir ${proj_loc}/${proj_name}.srcs/constrs_1/new
    set constr_file ${proj_loc}/${proj_name}.srcs/constrs_1/new/ila_pblock.xdc
    close [ open $constr_file w ]
    add_files -fileset constrs_1 $constr_file
    set_property target_constrs_file $constr_file [current_fileset -constrset]
    save_constraints -force
    
    implement_debug_core
    write_rw_checkpoint $output_dcp
}

proc fix_all_placed_cells { } {
    foreach c [get_cells -hierarchical -filter {IS_PRIMITIVE==1 && LOC != ""}] {
        set_property -quiet IS_LOC_FIXED 1 $c
    }
}

proc write_rw_checkpoint { filename } {
    write_checkpoint -force $filename
    set output_edf [string map {".dcp" ".edf"} $filename]
    write_edif -force $output_edf
}

proc get_x_coord { name } {
	set idx [string last "_X" $name]
	set idx2 [string last "Y" $name]
	return [string range $name $idx+2 $idx2-1]
}

proc get_y_coord { name } {
	set idx [string last "Y" $name]
	return [string range $name $idx+1 [string length $name]]
}

proc offset_dsps { count } {
	foreach c [get_cells -hierarchical -filter REF_NAME==DSP48E2] {
		set s [get_property NAME [get_sites -of $c]] 
		set y [get_y_coord $s]
		if [expr {$y % 24} != 0] {
			continue
		}
		set new_site "DSP48E2_X[get_x_coord $s]Y[expr [get_y_coord $s] - $count ]" 
		set_property LOC $new_site $c
		puts "Moving $c from $s to $new_site"
	}
}
