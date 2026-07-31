package TclResources

import Descriptors._

object TclGeneralConfigs {
  private val ramaAxiDataWidth = 256

  /**
    * Pseudo channels the HBM IP exposes, which is also its AXI slave port count.
    *
    * A master attached to one port reaches all of them through the IP's built-in
    * AXI switch: `assign_bd_address` maps 32 x 512 MB = 16 GB of `HBM_MEMxx`
    * segments into every `m_axi_XX` address space. That is what the striping
    * relies on -- RAMA scatters fragments over the 32 memories and the switch
    * routes each one to the pseudo channel that owns it.
    */
  private val hbmPseudoChannelCount = 32


  /**
    * @param extraProjectTcl emitted after the root design is built and the top is
    *                        set -- project-level setup (extra constraint files,
    *                        implementation hooks) that must not run inside
    *                        `create_root_design`
    */
  def getProjectWrapperTCLSyntax(
      functionalTcl: String,
      descriptor: FullSysGenDescriptor,
      isQuestaSim: Boolean = false,
      extraProjectTcl: String = ""
  ): String = {
    val sb = new StringBuilder

    sb.append(
      """
        ################################################################
        # Get the folder of the script
        ################################################################
        namespace eval _tcl {
            proc get_script_folder {} {
                set script_path [file normalize [info script]]
                set script_folder [file dirname $script_path]
                return $script_folder
            }
        }
        variable script_folder
        set script_folder [_tcl::get_script_folder]

        ################################################################
        # Check if script is running in correct Vivado version.
        ################################################################
        set scripts_vivado_version 2024.1
        set current_vivado_version [version -short]

        if { [string first $scripts_vivado_version $current_vivado_version] == -1 } {
            puts ""
            if { [string compare $scripts_vivado_version $current_vivado_version] > 0 } {
                catch {common::send_gid_msg -ssname BD::TCL -id 2042 -severity "ERROR" " This script was generated using Vivado <$scripts_vivado_version> and is being run in <$current_vivado_version> of Vivado. Sourcing the script failed since it was created with a future version of Vivado."}

            } else {
                catch {common::send_gid_msg -ssname BD::TCL -id 2041 -severity "ERROR" "This script was generated using Vivado <$scripts_vivado_version> and is being run in <$current_vivado_version> of Vivado. Please run the script in Vivado <$scripts_vivado_version> then open the design in Vivado <$current_vivado_version>. Upgrade the design by running \"Tools => Report => Report IP Status...\", then run write_bd_tcl to create an updated script."}

            }

            return 1
        }
        ################################################################
        # Create the project
        ################################################################

        
      """
    )
    
    sb.append(f"create_project project_1 ${descriptor.name}_vivado_project -part xcu55c-fsvh2892-2L-e -force\n") 

    sb.append("""
        set_property BOARD_PART xilinx.com:au55c:part0:1.0 [current_project]
        variable design_name
        set design_name design_1
        create_bd_design $design_name
        set errMsg ""
        set nRet 0
        set cur_design [current_bd_design -quiet]
        set list_cells [get_bd_cells -quiet]
    """)
    if(!isQuestaSim)
      sb.append("""
          ################################################################
          # Include the verilog modules and constraints
          ################################################################

          add_files -fileset sources_1 [glob ../rtl/*.v] [glob ../rtl/*.vh] [glob ../rtl/*.sv]
          import_files -fileset sources_1 [glob ../rtl/*.v] [glob ../rtl/*.vh] [glob ../rtl/*.sv]
          

          add_files -fileset sources_1 [glob ../rtl/synth/*.v] 
          import_files -fileset sources_1 [glob ../rtl/synth/*.v] 

          add_files -fileset constrs_1 -norecurse ../rtl/synth/u55c.xdc
          import_files -fileset constrs_1 ../rtl/synth/u55c.xdc
      """)
    else 
      sb.append(
        """
          add_files -fileset sources_1 [glob ../rtl/*.v] [glob ../rtl/*.vh] [glob ../rtl/*.sv]
          import_files -fileset sources_1 [glob ../rtl/*.v] [glob ../rtl/*.vh] [glob ../rtl/*.sv]

          add_files -fileset sources_1 [glob ../rtl/questa/*.sv] 
          import_files -fileset sources_1 [glob ../rtl/questa/*.sv] 
        """
      )

    sb.append("""
        ################################################################
        # Create the root design
        ################################################################
        proc create_root_design { parentCell } {

            variable script_folder
            variable design_name

            if { $parentCell eq "" } {
                set parentCell [get_bd_cells /]
            }

            # Get object for parentCell
            set parentObj [get_bd_cells $parentCell]
            if { $parentObj == "" } {
                catch {common::send_gid_msg -ssname BD::TCL -id 2090 -severity "ERROR" "Unable to find parent cell <$parentCell>!"}
                return
            }

            # Make sure parentObj is hier blk
            set parentType [get_property TYPE $parentObj]
            if { $parentType ne "hier" } {
                catch {common::send_gid_msg -ssname BD::TCL -id 2091 -severity "ERROR" "Parent <$parentObj> has TYPE = <$parentType>. Expected to be <hier>."}
                return
            }

            # Save current instance; Restore later
            set oldCurInst [current_bd_instance .]

            # Set parent object as current
            current_bd_instance $parentObj
        """)

    sb.append(functionalTcl)

    sb.append("""    
                validate_bd_design
                save_bd_design
            }

            create_root_design ""            
          """)

    if(!isQuestaSim)
      sb.append("            set_property top top_pcie [current_fileset]\n")
    else 
      sb.append("            set_property top main_sim [get_filesets sim_1]\n set_property top_lib xil_defaultlib [get_filesets sim_1]\n")

    sb.append("""
      update_compile_order -fileset sources_1
    """)

    sb.append(extraProjectTcl)

    sb.toString()
  }

  def getHBMConfigTclSyntax(totalAXIPorts: Int): String = {
    
    require(totalAXIPorts <= 32)
    
    val sb = new StringBuilder

    sb.append("""
            # 1. Create and configure the HBM
            create_bd_cell -type ip -vlnv xilinx.com:ip:hbm:1.0 hbm_0

            set_property -dict [list \
            CONFIG.USER_APB_EN {false} \
            CONFIG.USER_HBM_DENSITY {16GB} \
            CONFIG.USER_MC0_ECC_BYPASS {true} \
            CONFIG.USER_XSDB_INTF_EN {FALSE} \
            ] [get_bd_cells hbm_0]

            # 2. Create a constant of width 32 bits and value 0x00000000 and connect to the parity input of the HBM
            create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_0
            set_property -dict [list CONFIG.CONST_VAL {0} CONFIG.CONST_WIDTH {32} ] [get_bd_cells xlconstant_0]
                # Connecting the constant to the parity bits of the HBM 

        """)

    for (i <- 0 until math.min(totalAXIPorts, 32)) {
      // Connecting the constant to the parity bits of the HBM
      sb.append("connect_bd_net [get_bd_pins xlconstant_0/dout] [get_bd_pins hbm_0/AXI_" + f"${i}%02d" + "_WDATA_PARITY]\n")
    }

    if (totalAXIPorts < 32) {
      // Create a config for the HBm to remove the extra axi ports
      sb.append("set_property -dict [list \\\n")

      for (i <- totalAXIPorts until 32) {
        sb.append("CONFIG.USER_SAXI_" + f"${i}%02d" + " {false} \\\n")
      }
      sb.append("] [get_bd_cells hbm_0]\n")
    }

    sb.append("""
        #  3. EXPORT HBM_CATTRIP_LS 
        create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 util_vector_logic_1
        set_property -dict [list CONFIG.C_OPERATION {or} CONFIG.C_SIZE {1} ] [get_bd_cells util_vector_logic_1]
        create_bd_port -dir O HBM_CATTRIP_LS
        connect_bd_net [get_bd_ports HBM_CATTRIP_LS] [get_bd_pins util_vector_logic_1/Res]
        connect_bd_net [get_bd_pins util_vector_logic_1/Op1] [get_bd_pins hbm_0/DRAM_0_STAT_CATTRIP]
        connect_bd_net [get_bd_pins util_vector_logic_1/Op2] [get_bd_pins hbm_0/DRAM_1_STAT_CATTRIP]

        # 4. Create the IBUFDS for the clock of the HBM and connect them, export the input differential clock as sysclk2
        create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf_1
        connect_bd_net [get_bd_pins util_ds_buf_1/IBUF_OUT] [get_bd_pins hbm_0/HBM_REF_CLK_*]
        connect_bd_net [get_bd_pins util_ds_buf_1/IBUF_OUT] [get_bd_pins hbm_0/APB_*_PCLK]
        make_bd_intf_pins_external  [get_bd_intf_pins util_ds_buf_1/CLK_IN_D] -name SYSCLK2

        # 5. Create processor system reset for the HBM APB PRESETN
        create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_0
        connect_bd_net [get_bd_pins proc_sys_reset_0/slowest_sync_clk] [get_bd_pins util_ds_buf_1/IBUF_OUT]
        connect_bd_net [get_bd_pins proc_sys_reset_0/peripheral_aresetn] [get_bd_pins hbm_0/APB_*_PRESET_N]
        connect_bd_net [get_bd_ports PCIE_PERST_LS_65] [get_bd_pins proc_sys_reset_0/ext_reset_in]
        """)

    sb.toString()
  }

  /**
    * Striping configuration shared by every RAMA instance.
    *
    * `G_MEM_COUNT` is the pseudo-channel count, so consecutive 64-byte fragments
    * land in consecutive pseudo channels and one master's traffic spreads over
    * all 32 of them (32 x 512 MB = the full 16 GB). The data width is not set
    * here: the IP marks it constant (256 bits) and propagates it from the
    * connected pins, so writing it produces "parameter does not exist".
    */
  private def getRamaStripingConfigTcl(cell: String): String = {
    s"""set_property -dict [list \\
  CONFIG.G_AXI_LITE {0} \\
  CONFIG.G_FRAGMENT_SIZE_BYTES {64} \\
  CONFIG.G_MEM_COUNT {$hbmPseudoChannelCount} \\
  CONFIG.G_MEM_INTERLEAVE_TYPE {per_memory} \\
  CONFIG.G_REORDER_QUEUE_DEPTH {256} \\
] [get_bd_cells $cell]
"""
  }

  /**
    * Narrow a memory master down to the 256-bit bus RAMA expects.
    *
    * A master that is already 256 bits wide is wired straight through. Anything
    * else goes through a 1:1 SmartConnect rather than an `axi_dwidth_converter`:
    * it infers both sides from the connected pins, and it carries the master's
    * outstanding-transaction properties across instead of the fixed tracker depth
    * a dwidth converter builds -- the same job the non-striped HBM path gives it.
    */
  private def getAxiMemoryWidthAdapterTcl(
      upstreamPin: String,
      upstreamWidth: Int,
      downstreamPin: String,
      cell: String,
      clkPin: String,
      resetPin: String
  ): String = {
    val sb = new StringBuilder
    if (upstreamWidth == ramaAxiDataWidth) {
      sb.append(f"connect_bd_intf_net ${upstreamPin} ${downstreamPin}\n")
    } else {
      sb.append(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 ${cell}\n")
      sb.append(
        f"set_property -dict [list CONFIG.NUM_SI {1} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells ${cell}]\n"
      )
      sb.append(f"connect_bd_intf_net ${upstreamPin} [get_bd_intf_pins ${cell}/S00_AXI]\n")
      sb.append(f"connect_bd_intf_net [get_bd_intf_pins ${cell}/M00_AXI] ${downstreamPin}\n")
      sb.append(f"connect_bd_net ${clkPin} [get_bd_pins ${cell}/aclk]\n")
      sb.append(f"connect_bd_net ${resetPin} [get_bd_pins ${cell}/aresetn]\n")
    }
    sb.toString()
  }

  /**
    * HBM AXI slave ports occupied by [[getHbmRamaStripingTcl]] for `numPorts`
    * design masters. Every enabled HBM port must be driven, so this is exactly
    * the number of ports the HBM IP may be configured with.
    */
  def getHbmRamaStripingPortCount(numPorts: Int): Int =
    if (numPorts < hbmPseudoChannelCount) numPorts + 1 else hbmPseudoChannelCount

  /**
    * HBM path where every memory master goes through a RAMA IP using per-memory
    * striping across all 32 HBM pseudo channels.
    *
    * Each exported design master and the one external master get their own RAMA
    * instance with the same striping configuration, so both sides compute the
    * same physical pseudo channel for a given address. RAMA has a single
    * `s_axi`/`m_axi` pair -- it does not fan out -- so the scatter is performed
    * by the address permutation it applies, and the HBM IP's built-in switch
    * routes each fragment to the pseudo channel that owns it. Each RAMA
    * therefore drives one HBM port directly:
    *
    *   {{{ <design m_axi_XX> -> [1:1 SmartConnect] -> rama -> hbm_0/SAXI_XX_8HI }}}
    *
    * RAMA is AXI4 256-bit on its slave side and AXI3 256-bit on its master side,
    * so it also performs the AXI4 -> AXI3 conversion the HBM slave needs; only
    * the data-width conversion is left to a 1:1 SmartConnect, and only for
    * masters that are not already 256 bits wide.
    *
    * The "external master" is the one non-design master that also has to reach
    * HBM: the memory VIP in the QuestaSim flow, and the XDMA host DMA port in
    * the PCIe flow. It has to traverse an identically configured RAMA rather
    * than talk to the HBM directly -- the striping permutes addresses, so a
    * master that skipped it would read and write different physical locations
    * than the PEs do for the same address.
    *
    * With all 32 ports taken by design masters there is none left for the
    * external master, so it then shares port 00 with design master 00 through a
    * 2:1 SmartConnect placed on the AXI4 side, ahead of that port's RAMA.
    */
  def getHbmRamaStripingTcl(
      descriptorName: String,
      numPorts: Int,
      portWidths: Seq[Int],
      clkPin: String,
      resetPin: String,
      extMasterPin: String,
      extWidth: Int = 512,
      extCellPrefix: String = "rama_ext",
      cellPrefix: String = "rama_hbm",
      masterPinOf: Option[Int => String] = None
  ): String = {
    require(
      portWidths.isEmpty || portWidths.length == numPorts,
      s"[TclGeneralConfigs] Expected $numPorts HBM port width(s), got ${portWidths.length}."
    )
    require(
      numPorts <= hbmPseudoChannelCount,
      s"[TclGeneralConfigs] The HBM IP has $hbmPseudoChannelCount AXI slave ports, but the design " +
        s"exports $numPorts memory master(s). Reduce the AXI port count (-r) to at most " +
        s"$hbmPseudoChannelCount."
    )

    val widths =
      if (portWidths.isEmpty) Seq.fill(numPorts)(ramaAxiDataWidth) else portWidths

    // By default the RAMA fabric hangs straight off the design's masters; the
    // PCIe flow instead feeds it from the far side of the SLR register slices.
    val masterPin: Int => String =
      masterPinOf.getOrElse((i: Int) => f"[get_bd_intf_pins ${descriptorName}_0/m_axi_${i}%02d]")

    val sb = new StringBuilder

    // Port 00 is shared with the external master when the design already uses
    // every HBM port; the SmartConnect merging them sits on the AXI4 side, in
    // front of that port's RAMA, so both masters see the same striped address map.
    val extSharesPort0 = numPorts >= hbmPseudoChannelCount
    val extShareConnect = "smartconnect_hbm_rama_share"
    val extRamaCell = f"${extCellPrefix}_0"
    if (extSharesPort0) {
      sb.append(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 ${extShareConnect}\n")
      sb.append(
        f"set_property -dict [list CONFIG.NUM_SI {2} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells ${extShareConnect}]\n"
      )
      sb.append(f"connect_bd_net ${clkPin} [get_bd_pins ${extShareConnect}/aclk]\n")
      sb.append(f"connect_bd_net ${resetPin} [get_bd_pins ${extShareConnect}/aresetn]\n")
    }

    for (i <- 0 until numPorts) {
      val ramaCell = f"${cellPrefix}_${i}%02d"
      sb.append(f"create_bd_cell -type ip -vlnv xilinx.com:ip:rama:1.1 ${ramaCell}\n")
      sb.append(getRamaStripingConfigTcl(ramaCell))
      val ramaSlavePin =
        if (extSharesPort0 && i == 0) f"[get_bd_intf_pins ${extShareConnect}/S00_AXI]"
        else f"[get_bd_intf_pins ${ramaCell}/s_axi]"
      sb.append(
        getAxiMemoryWidthAdapterTcl(
          upstreamPin = masterPin(i),
          upstreamWidth = widths(i),
          downstreamPin = ramaSlavePin,
          cell = f"smartconnect_rama_${i}%02d",
          clkPin = clkPin,
          resetPin = resetPin
        )
      )
      if (extSharesPort0 && i == 0) {
        sb.append(
          f"connect_bd_intf_net [get_bd_intf_pins ${extShareConnect}/M00_AXI] [get_bd_intf_pins ${ramaCell}/s_axi]\n"
        )
      }
      sb.append(
        f"connect_bd_intf_net [get_bd_intf_pins ${ramaCell}/m_axi] [get_bd_intf_pins hbm_0/SAXI_${i}%02d_8HI]\n"
      )
      sb.append(f"connect_bd_net ${clkPin} [get_bd_pins ${ramaCell}/axi_aclk]\n")
      sb.append(f"connect_bd_net ${resetPin} [get_bd_pins ${ramaCell}/axi_aresetn]\n")
    }

    // The external master either gets the last free port with its own RAMA, or
    // joins design master 00 on the shared SmartConnect.
    val extDownstreamPin =
      if (extSharesPort0) f"[get_bd_intf_pins ${extShareConnect}/S01_AXI]"
      else f"[get_bd_intf_pins ${extRamaCell}/s_axi]"
    if (!extSharesPort0) {
      sb.append(f"create_bd_cell -type ip -vlnv xilinx.com:ip:rama:1.1 ${extRamaCell}\n")
      sb.append(getRamaStripingConfigTcl(extRamaCell))
    }
    sb.append(
      getAxiMemoryWidthAdapterTcl(
        upstreamPin = extMasterPin,
        upstreamWidth = extWidth,
        downstreamPin = extDownstreamPin,
        cell = f"smartconnect_${extCellPrefix}",
        clkPin = clkPin,
        resetPin = resetPin
      )
    )
    if (!extSharesPort0) {
      sb.append(
        f"connect_bd_intf_net [get_bd_intf_pins ${extRamaCell}/m_axi] [get_bd_intf_pins hbm_0/SAXI_${numPorts}%02d_8HI]\n"
      )
      sb.append(f"connect_bd_net ${clkPin} [get_bd_pins ${extRamaCell}/axi_aclk]\n")
      sb.append(f"connect_bd_net ${resetPin} [get_bd_pins ${extRamaCell}/axi_aresetn]\n")
    }

    sb.toString()
  }

  /**
    * Aggregate the raw masters of a `--raw-hbm-ports` design onto one
    * SmartConnect per HBM port, without wiring the master side.
    *
    * In raw mode the design exports one `m_axi_XX` per memory master rather than
    * one per bus group, so the reduction to the HBM's port count happens here. A
    * SmartConnect is the right IP for it: unlike the in-design `Mux` it converts
    * data widths on its slave side, so masters of any mix of widths can share
    * one, and it does the AXI4 -> AXI3 conversion further downstream anyway.
    *
    * `assignment` comes from [[HbmPortDistribution.distribute]], which spreads
    * masters of different unit kinds across the SmartConnects on purpose -- see
    * that object for why.
    *
    * `M00_AXI` is deliberately left dangling: the PCIe flow feeds it into the SLR
    * register slices, while the QuestaSim flow takes it straight to the HBM (or
    * to a RAMA), so the caller wires it.
    *
    * @param assignment per HBM port, the indices of the raw masters on it
    * @param labels     `<kind>/<task>` per raw master, used for the TCL comments
    */
  def getHbmAggregateSmartConnectTcl(
      descriptorName: String,
      assignment: Seq[Seq[Int]],
      labels: Seq[String],
      clkPin: String,
      resetPin: String,
      cellPrefix: String = "smartconnect_hbm"
  ): String = {
    val sb = new StringBuilder

    sb.append("# Raw HBM export: every memory master is aggregated onto one of these\n")
    sb.append("# SmartConnects, mixing unit kinds so no single kind owns an HBM port.\n")
    HbmPortDistribution.summary(assignment, labels).linesIterator.foreach { l =>
      sb.append(s"# $l\n")
    }

    for ((masters, port) <- assignment.zipWithIndex) {
      val cell = f"${cellPrefix}_${port}%02d"
      require(
        masters.nonEmpty,
        s"[TclGeneralConfigs] SmartConnect $cell was assigned no master; a SmartConnect with " +
          s"NUM_SI 0 is not a legal cell. This means there are fewer raw masters than HBM ports."
      )
      sb.append(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 ${cell}\n")
      sb.append(
        f"set_property -dict [list CONFIG.NUM_SI {${masters.length}} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells ${cell}]\n"
      )
      for ((master, slot) <- masters.zipWithIndex) {
        sb.append(
          f"connect_bd_intf_net [get_bd_intf_pins ${descriptorName}_0/m_axi_${master}%02d] " +
            f"[get_bd_intf_pins ${cell}/S${slot}%02d_AXI]\n"
        )
      }
      sb.append(f"connect_bd_net ${clkPin} [get_bd_pins ${cell}/aclk]\n")
      sb.append(f"connect_bd_net ${resetPin} [get_bd_pins ${cell}/aresetn]\n")
    }
    sb.toString()
  }

  /**
    * One 1:1 SmartConnect per exported HBM master.
    *
    * The HardCilk top level exports plain AXI4 masters whose data width is the
    * native width of the interfaces muxed behind them (no Widen, no AXI3
    * conversion inside the design). The HBM slaves are 256-bit AXI3, so a
    * SmartConnect with a single SI and a single MI is inserted in between: it
    * infers both sides from the connected pins and performs the data-width
    * conversion and the AXI4 -> AXI3 conversion (including burst splitting)
    * automatically -- the same job v++ does for us in the Vitis flow.
    *
    * Ports listed in `ramaPorts` additionally get a RAMA IP spliced in between
    * the SmartConnect and the HBM slave -- see [[getRamaTcl]].
    *
    * In a `--raw-hbm-ports` design there are more masters than HBM ports, so
    * `assignment` says which raw masters share each SmartConnect and the slave
    * side is built by [[getHbmAggregateSmartConnectTcl]] instead; the master side
    * is wired the same way either way.
    *
    * @param cellPrefix name prefix of the generated SmartConnect cells
    * @param clkPin     TCL object expression for the clock to drive them with
    * @param resetPin   TCL object expression for the active-low reset
    * @param ramaPorts  indices of the exported masters that want a RAMA IP
    * @param assignment raw-mode master-to-port assignment; `None` keeps the 1:1
    *                   mapping of a bus-group-muxed design
    */
  def getHbmSmartConnectTcl(
      descriptorName: String,
      numPorts: Int,
      clkPin: String,
      resetPin: String,
      cellPrefix: String = "smartconnect_hbm",
      ramaPorts: Set[Int] = Set.empty,
      assignment: Option[Seq[Seq[Int]]] = None,
      labels: Seq[String] = Seq.empty
  ): String = {
    val sb = new StringBuilder

    assignment match {
      case Some(a) =>
        require(
          a.length == numPorts,
          s"[TclGeneralConfigs] Expected an assignment for $numPorts HBM port(s), got ${a.length}."
        )
        sb.append(
          getHbmAggregateSmartConnectTcl(descriptorName, a, labels, clkPin, resetPin, cellPrefix)
        )
        for (i <- 0 until numPorts) {
          val cell = f"${cellPrefix}_${i}%02d"
          if (ramaPorts.contains(i)) {
            sb.append(getRamaTcl(i, f"[get_bd_intf_pins ${cell}/M00_AXI]", clkPin, resetPin))
          } else {
            sb.append(
              f"connect_bd_intf_net [get_bd_intf_pins ${cell}/M00_AXI] [get_bd_intf_pins hbm_0/SAXI_${i}%02d_8HI]\n"
            )
          }
        }

      case None =>
        for (i <- 0 until numPorts) {
          val cell = f"${cellPrefix}_${i}%02d"
          sb.append(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 ${cell}\n")
          sb.append(
            f"set_property -dict [list CONFIG.NUM_SI {1} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells ${cell}]\n"
          )
          sb.append(
            f"connect_bd_intf_net [get_bd_intf_pins ${descriptorName}_0/m_axi_${i}%02d] [get_bd_intf_pins ${cell}/S00_AXI]\n"
          )
          if (ramaPorts.contains(i)) {
            sb.append(getRamaTcl(i, f"[get_bd_intf_pins ${cell}/M00_AXI]", clkPin, resetPin))
          } else {
            sb.append(
              f"connect_bd_intf_net [get_bd_intf_pins ${cell}/M00_AXI] [get_bd_intf_pins hbm_0/SAXI_${i}%02d_8HI]\n"
            )
          }
          sb.append(f"connect_bd_net ${clkPin} [get_bd_pins ${cell}/aclk]\n")
          sb.append(f"connect_bd_net ${resetPin} [get_bd_pins ${cell}/aresetn]\n")
        }
    }
    sb.toString()
  }

  /**
    * One RAMA (Random Access Memory Attachment) IP in front of HBM slave port
    * `portIndex`.
    *
    * RAMA buffers and reorders the traffic of the master behind it so that the
    * HBM controller sees longer, better-aligned bursts than a random-access
    * workload would otherwise produce. It is inserted as the last hop before the
    * HBM slave:
    *
    *   {{{ <design m_axi_XX> -> smartconnect -> rama -> hbm_0/SAXI_XX_8HI }}}
    *
    * The SmartConnect ahead of it has already narrowed the master to the 256-bit
    * bus RAMA expects, and RAMA itself is AXI4 on its slave side and AXI3 on its
    * master side, so it also performs the AXI4 -> AXI3 conversion the HBM slave
    * needs (which is why the SmartConnect no longer sees the HBM pin directly).
    *
    * Only worth instantiating on a port that is not shared with other masters --
    * the HBM interconnect guarantees that by giving `generateRAMA` tasks
    * dedicated ports.
    *
    * @param portIndex   HBM slave port (and exported master) index
    * @param upstreamPin TCL object expression for the AXI master pin to attach
    *                    RAMA's slave interface to
    */
  def getRamaTcl(
      portIndex: Int,
      upstreamPin: String,
      clkPin: String,
      resetPin: String
  ): String = {
    val sb = new StringBuilder
    val cell = f"rama_${portIndex}%02d"
    sb.append(f"create_bd_cell -type ip -vlnv xilinx.com:ip:rama:1.1 ${cell}\n")
    // The data width is fixed at 256 bits by the IP (it exists only to feed an
    // HBM pseudo-channel) and the slave-side ID width is inferred from whatever
    // drives it, so neither is set here. ADDR_WIDTH is the 33-bit space a
    // SAXI_XX_8HI slave covers; the remaining values are the IP defaults,
    // spelled out so a future Vivado version cannot silently change them.
    sb.append(
      f"set_property -dict [list " +
        f"CONFIG.ADDR_WIDTH {33} " +
        f"CONFIG.G_AXI_LITE {0} " +
        f"CONFIG.G_MEM_COUNT {4} " +
        f"CONFIG.G_FRAGMENT_SIZE_BYTES {128} " +
        f"CONFIG.G_REORDER_QUEUE_DEPTH {128} " +
        f"] [get_bd_cells ${cell}]\n"
    )
    sb.append(f"connect_bd_intf_net ${upstreamPin} [get_bd_intf_pins ${cell}/s_axi]\n")
    sb.append(
      f"connect_bd_intf_net [get_bd_intf_pins ${cell}/m_axi] [get_bd_intf_pins hbm_0/SAXI_${portIndex}%02d_8HI]\n"
    )
    sb.append(f"connect_bd_net ${clkPin} [get_bd_pins ${cell}/axi_aclk]\n")
    sb.append(f"connect_bd_net ${resetPin} [get_bd_pins ${cell}/axi_aresetn]\n")
    sb.toString()
  }

  /**
    * Fully-registered AXI register slices on every interface that has to cross
    * an SLR boundary.
    *
    * The design lives in SLR1/SLR2 (see [[getFloorplanXdc]]) while the HBM and
    * the PCIe hard block are both physically in SLR0, so every memory master and
    * the management slave cross at least one Laguna boundary. `REG_* = 7` is the
    * IP's multi-SLR crossing mode: every channel is fully registered in both
    * directions, which is what makes the crossing a pure register-to-register
    * hop the placer can absorb into Laguna flops.
    *
    * Two slices per master: `rs0_m_axi_XX` is floorplanned on the design side of
    * the boundary and `rs1_m_axi_XX` on the HBM side, so the long wire sits
    * between two registers rather than between a register and combinational
    * logic. The slices are placed ahead of the width conversion, so the crossing
    * carries the master's native width -- most masters here are narrower than
    * RAMA's 256-bit bus, so widening first would only add wires to the crossing.
    *
    * @param portWidths native data width of each exported `m_axi_XX`
    * @param addrWidth  `widthAXIAddress` of the descriptor
    * @param liteAddrWidth address width of the AXI-Lite management slave
    * @param liteDataWidth data width of the AXI-Lite management slave
    * @param masterPinOf   where each slice pair takes its master from, defaulting
    *                      to the design's own `m_axi_XX`. A `--raw-hbm-ports`
    *                      design has more masters than HBM ports, so it points
    *                      this at the aggregating SmartConnects instead: they are
    *                      floorplanned with the design, and the crossing then
    *                      carries one 256-bit bus per HBM port rather than one
    *                      per raw master.
    */
  def getAxiRegisterSliceTcl(
      descriptorName: String,
      portWidths: Seq[Int],
      addrWidth: Int,
      liteAddrWidth: Int,
      liteDataWidth: Int,
      clkPin: String,
      resetPin: String,
      masterPinOf: Option[Int => String] = None
  ): String = {
    val sb = new StringBuilder
    val masterPin: Int => String =
      masterPinOf.getOrElse((i: Int) => f"[get_bd_intf_pins ${descriptorName}_0/m_axi_${i}%02d]")

    sb.append("set rs_vlnv [lindex [lsort [get_ipdefs xilinx.com:ip:axi_register_slice:*]] end]\n")
    sb.append("""
        proc hc_config_axi_slice {cell protocol aw dw} {
            set_property -dict [list \
                CONFIG.PROTOCOL   $protocol \
                CONFIG.ADDR_WIDTH $aw \
                CONFIG.DATA_WIDTH $dw \
                CONFIG.REG_AW     {7} \
                CONFIG.REG_AR     {7} \
                CONFIG.REG_W      {7} \
                CONFIG.REG_R      {7} \
                CONFIG.REG_B      {7} \
            ] $cell
        }
    """)

    for ((dw, i) <- portWidths.zipWithIndex) {
      val rs0 = f"rs0_m_axi_${i}%02d"
      val rs1 = f"rs1_m_axi_${i}%02d"
      for (rs <- Seq(rs0, rs1)) {
        sb.append(f"create_bd_cell -type ip -vlnv $$rs_vlnv ${rs}\n")
        sb.append(f"hc_config_axi_slice [get_bd_cells ${rs}] {AXI4} {${addrWidth}} {${dw}}\n")
        sb.append(f"connect_bd_net ${clkPin} [get_bd_pins ${rs}/aclk]\n")
        sb.append(f"connect_bd_net ${resetPin} [get_bd_pins ${rs}/aresetn]\n")
      }
      sb.append(f"connect_bd_intf_net ${masterPin(i)} [get_bd_intf_pins ${rs0}/S_AXI]\n")
      sb.append(f"connect_bd_intf_net [get_bd_intf_pins ${rs0}/M_AXI] [get_bd_intf_pins ${rs1}/S_AXI]\n")
    }

    // Management slave. Unlike the Vitis flow this is not fixed at 12/32: a
    // non-Vitis descriptor exposes a 64-bit AXI-Lite slave whose address width
    // follows the number of config ports.
    sb.append("create_bd_cell -type ip -vlnv $rs_vlnv rs_s_axil_mgmt\n")
    sb.append(
      f"hc_config_axi_slice [get_bd_cells rs_s_axil_mgmt] {AXI4LITE} {${liteAddrWidth}} {${liteDataWidth}}\n"
    )
    sb.append(f"connect_bd_net ${clkPin} [get_bd_pins rs_s_axil_mgmt/aclk]\n")
    sb.append(f"connect_bd_net ${resetPin} [get_bd_pins rs_s_axil_mgmt/aresetn]\n")
    sb.append(
      f"connect_bd_intf_net [get_bd_intf_pins rs_s_axil_mgmt/M_AXI] [get_bd_intf_pins ${descriptorName}_0/s_axil_mgmt_hardcilk]\n"
    )

    sb.toString()
  }

  /**
    * Soft floorplan for the PCIe flow, mirroring what `package_kernel.tcl` emits
    * for the Vitis flow.
    *
    * On the xcu55c every PCIE4CE4 site, all sixteen PCIe GTY channels (quads
    * 224-227) and all thirty-two HBM AXI interfaces are in SLR0 -- the Vitis
    * shell places its PCIe block at `PCIE4CE4_X1Y0` in SLR0 for the same reason.
    * So SLR0 is spoken for by the shell-equivalent logic and the generated design
    * is pushed up into SLR1/SLR2.
    *
    * The kernel and AXI-Lite pblocks are soft: they are a hint the placer may
    * violate if timing demands it. The two memory-slice pblocks are hard, because
    * their whole purpose is to sit on a known side of the boundary. No LAGUNA
    * coordinates and no `CONTAIN_ROUTING` -- the same deliberately loose approach
    * the Vitis flow takes.
    *
    * Cell paths are rooted at `design_1_i`: the synthesis top is `top_pcie`
    * (`src/main/resources/top.v`), which instantiates `design_1` directly rather
    * than going through a generated BD wrapper.
    *
    * The filters start `*design_1_i/` with no slash between the wildcard and the
    * instance name. The Vitis flow's equivalent puts a slash there, which works
    * only because its instance sits several levels down. Here `design_1_i` is
    * the top-level instance, so a wildcard-then-slash prefix demands a leading
    * slash that is not in the name, and every pblock silently matches zero cells.
    */
  def getFloorplanXdc(descriptorName: String, rawMode: Boolean = false): String = {
    // In a --raw-hbm-ports design the aggregating SmartConnects sit between the
    // design and the register slices, so they belong on the design side of the
    // crossing. Emitted only in raw mode: the cells do not exist otherwise, and
    // add_cells_to_pblock on an empty list is at best a silent no-op.
    val rawSmartConnectPblock =
      if (!rawMode) ""
      else
        """
# Raw-export aggregation SmartConnects: design side of the crossing, with the kernel.
add_cells_to_pblock \
    [get_pblocks pblock_user_kernel] \
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/smartconnect_hbm_*}]
"""
    s"""# Generated by HardCilk -- floorplan for the XDMA/PCIe flow.
#
# SLR0 holds the PCIe hard block, its GTY quads and the HBM AXI interfaces, all
# of which are fixed there by the device. The generated design is placed above
# it and reaches HBM through the rs0/rs1 register slice pair.

# Design: prefer SLR1+SLR2, but allow escape if timing needs it.
create_pblock pblock_user_kernel
resize_pblock [get_pblocks pblock_user_kernel] -add SLR1
resize_pblock [get_pblocks pblock_user_kernel] -add SLR2
add_cells_to_pblock \\
    [get_pblocks pblock_user_kernel] \\
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/${descriptorName}_0/*}]
set_property IS_SOFT TRUE [get_pblocks pblock_user_kernel]
${rawSmartConnectPblock}
# First AXI slice: design side of the crossing.
create_pblock pblock_axi_rs0
resize_pblock [get_pblocks pblock_axi_rs0] -add SLR1
add_cells_to_pblock \\
    [get_pblocks pblock_axi_rs0] \\
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/rs0_m_axi_*}]
set_property IS_SOFT FALSE [get_pblocks pblock_axi_rs0]

# Second AXI slice: HBM side of the crossing.
create_pblock pblock_axi_rs1
resize_pblock [get_pblocks pblock_axi_rs1] -add SLR0
add_cells_to_pblock \\
    [get_pblocks pblock_axi_rs1] \\
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/rs1_m_axi_*}]
set_property IS_SOFT FALSE [get_pblocks pblock_axi_rs1]

# AXI-Lite slice: flexible, but stay close to the control side.
create_pblock pblock_axilite_slice
resize_pblock [get_pblocks pblock_axilite_slice] -add SLR1
add_cells_to_pblock \\
    [get_pblocks pblock_axilite_slice] \\
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/rs_s_axil_mgmt*}]
set_property IS_SOFT TRUE [get_pblocks pblock_axilite_slice]
"""
  }

  /**
    * Waive the combinatorial-loop DRC on the HLS AXI read-burst buffers.
    *
    * Vitis HLS builds `gmem_m_axi_U`'s burst-request buffer with a loop the DRC
    * flags but which is broken by an enable in practice. The Vitis flow waives
    * the same nets from `clear_drc_errors.tcl`; this is the Vivado-flow
    * equivalent, run as a pre-`opt_design` hook.
    *
    * Matching is by hierarchical wildcard rather than by enumerated PE index, so
    * it holds for any task/PE-count combination -- unlike the 144 hand-written
    * constraints this replaces, which named `triangleCount_0` and `pageRank_0`
    * with fixed `peArray_N` indices.
    */
  def getDrcWaiverTcl(): String = {
    """# Generated by HardCilk -- waive the combinatorial-loop DRC on the HLS AXI
# read-burst request buffers. Run before opt_design.

set hc_req_buffer_nets [get_nets -quiet -hierarchical -filter \
    {NAME =~ */gmem*_m_axi_U/bus_read/rreq_burst_conv/burst_interleave/req_buffer/*}]

if {[llength $hc_req_buffer_nets] == 0} {
    puts "HardCilk: no HLS AXI read-burst buffer nets matched; nothing to waive."
} else {
    set_property ALLOW_COMBINATORIAL_LOOPS TRUE $hc_req_buffer_nets
    puts "HardCilk: waived combinatorial-loop DRC on [llength $hc_req_buffer_nets] net(s)."
}
"""
  }

  /**
    * Implementation strategy, mirroring what the Vitis flow passes to v++ by
    * default in `src/cfg/conn_u55c.cfg`'s `[vivado]` section.
    *
    * Set on the project rather than only inside the batch script, so an
    * interactive GUI run gets the same strategy as a headless one.
    *
    * The Vitis config also carries `param=compiler.worstNegativeSlack=-1`, which
    * has no Vivado equivalent: it tells v++ not to abort packaging when the
    * design misses timing. In this flow `write_bitstream` already proceeds on
    * negative slack (with a warning), so there is nothing to translate.
    */
  def getImplStrategyTcl(): String = {
    """
        # Implementation strategy -- same directives the Vitis flow uses by default.
        set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE                ExtraNetDelay_high [get_runs impl_1]
        set_property STEPS.PHYS_OPT_DESIGN.IS_ENABLED                 true               [get_runs impl_1]
        set_property STEPS.PHYS_OPT_DESIGN.ARGS.DIRECTIVE             AggressiveExplore  [get_runs impl_1]
        set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE                AggressiveExplore  [get_runs impl_1]
        set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED      true               [get_runs impl_1]
        set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE  AggressiveExplore  [get_runs impl_1]
    """
  }

  /**
    * Batch run script: synthesis through bitstream, with a timing summary.
    *
    * Job count comes from `HC_JOBS` so the shell wrapper stays the single place
    * that decides parallelism.
    */
  def getImplRunTcl(descriptorName: String): String = {
    s"""# Generated by HardCilk -- headless synthesis, place & route and bitstream.

set proj ${descriptorName}_vivado_project/project_1.xpr
if {![file exists $$proj]} {
    error "No Vivado project at $$proj -- build the block design first (./build.sh)."
}
open_project $$proj

set jobs 8
if {[info exists ::env(HC_JOBS)]} { set jobs $$::env(HC_JOBS) }
puts "HardCilk: running with $$jobs job(s)"

if {[get_property PROGRESS [get_runs synth_1]] ne "100%"} {
    reset_run synth_1
}
launch_runs synth_1 -jobs $$jobs
wait_on_run synth_1
if {[get_property PROGRESS [get_runs synth_1]] ne "100%"} {
    error "Synthesis failed -- see [get_property DIRECTORY [get_runs synth_1]]/runme.log"
}
puts "HardCilk: synthesis complete"

launch_runs impl_1 -to_step write_bitstream -jobs $$jobs
wait_on_run impl_1
if {[get_property PROGRESS [get_runs impl_1]] ne "100%"} {
    error "Implementation failed -- see [get_property DIRECTORY [get_runs impl_1]]/runme.log"
}
puts "HardCilk: implementation and bitstream complete"

open_run impl_1
report_timing_summary -file timing_summary.rpt
report_utilization -slr -file utilization_slr.rpt
report_drc              -file drc_routed.rpt

set wns [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -setup]]
set whs [get_property SLACK [get_timing_paths -max_paths 1 -nworst 1 -hold]]
puts "HardCilk: WNS = $$wns ns, WHS = $$whs ns"
if {$$wns < 0 || $$whs < 0} {
    puts "HardCilk: WARNING -- design does NOT meet timing; the bitstream is still written."
} else {
    puts "HardCilk: timing met"
}

set bit [glob -nocomplain ${descriptorName}_vivado_project/project_1.runs/impl_1/*.bit]
puts "HardCilk: bitstream = $$bit"
"""
  }

  /**
    * `build.sh` -- one entry point for the three things you actually want to do
    * with a generated project: build the block design, open it in the GUI, or
    * take it all the way to a bitstream headlessly.
    */
  def getBuildShellScript(descriptorName: String, vivadoRoot: String = "/alpha/tools/Xilinx"): String = {
    val n = descriptorName
    s"""#!/bin/bash
# Generated by HardCilk -- build/run driver for the XDMA/PCIe Vivado project.
set -euo pipefail

cd "$$(dirname "$$0")"

PROJECT=${n}_vivado_project
XPR=$$PROJECT/project_1.xpr
BD_TCL=${n}_memPEs.tcl
IMPL_TCL=${n}_impl.tcl

export XILINX_ROOT=$${XILINX_ROOT:-$vivadoRoot}
VIVADO_VERSION=$${VIVADO_VERSION:-2024.1}

MODE=bd
FORCE=0
OPEN_GUI=0
HC_JOBS=$${HC_JOBS:-$$(nproc)}
if [ "$$HC_JOBS" -gt 16 ]; then HC_JOBS=16; fi

usage() {
    cat <<EOF
Usage: ./build.sh [options]

  (default)        Build the block design only, headless.
  -g, --gui        Build the block design, then open it in the Vivado GUI.
  -i, --impl       Build, then run synthesis, place & route and bitstream headless.
                   Add --gui to open the implemented design afterwards.
  -o, --open       Open the existing project in the GUI without rebuilding.
  -f, --force      Delete any existing project and rebuild from scratch.
  -j, --jobs N     Parallel jobs (default: nproc, capped at 16).
  -h, --help       This message.

Environment:
  XILINX_ROOT      Xilinx install root (default: $vivadoRoot)
  VIVADO_VERSION   Vivado version      (default: 2024.1)

Implementation uses the same directives as the Vitis flow: place
ExtraNetDelay_high, phys_opt / route / post-route phys_opt AggressiveExplore.

Outputs after --impl:
  timing_summary.rpt, utilization_slr.rpt, drc_routed.rpt
  $$PROJECT/project_1.runs/impl_1/*.bit
EOF
}

while [ $$# -gt 0 ]; do
    case "$$1" in
        -g|--gui)   OPEN_GUI=1 ;;
        -i|--impl)  MODE=impl ;;
        -o|--open)  MODE=open; OPEN_GUI=1 ;;
        -f|--force) FORCE=1 ;;
        -j|--jobs)  shift; HC_JOBS="$$1" ;;
        -h|--help)  usage; exit 0 ;;
        *) echo "Unknown option: $$1" >&2; usage; exit 1 ;;
    esac
    shift
done
export HC_JOBS

SETTINGS=$$XILINX_ROOT/Vivado/$$VIVADO_VERSION/settings64.sh
if [ ! -f "$$SETTINGS" ]; then
    echo "error: no Vivado settings script at $$SETTINGS" >&2
    echo "       set XILINX_ROOT / VIVADO_VERSION to match your install." >&2
    exit 1
fi
# shellcheck disable=SC1090
source "$$SETTINGS"

if [ "$$MODE" = open ]; then
    [ -f "$$XPR" ] || { echo "error: no project at $$XPR -- run ./build.sh first." >&2; exit 1; }
    exec vivado "$$XPR"
fi

if [ "$$FORCE" = 1 ]; then
    echo "==> removing $$PROJECT"
    rm -rf "$$PROJECT"
fi

if [ ! -f "$$XPR" ]; then
    echo "==> building block design ($$BD_TCL)"
    vivado -mode batch -nojournal -log build_bd.log -source "$$BD_TCL"
else
    echo "==> reusing existing project $$XPR (use --force to rebuild)"
fi

if [ "$$MODE" = impl ]; then
    echo "==> synthesis, place & route and bitstream ($$HC_JOBS jobs)"
    vivado -mode batch -nojournal -log build_impl.log -source "$$IMPL_TCL"
fi

if [ "$$OPEN_GUI" = 1 ]; then
    echo "==> launching GUI"
    exec vivado "$$XPR"
fi

echo "==> done"
"""
  }

  def getXdmaConfigTclSyntax(): String = {
    """
        # 1.Create the xdma
        create_bd_cell -type ip -vlnv xilinx.com:ip:xdma:4.1 xdma_0

        # 2.Configure the xdma
        #
        # The block location and quad are pinned explicitly. The project sets
        # BOARD_PART au55c, and the board file declares block_location
        # PCIE4C_X1Y1; attaching the board interface would force that value and
        # grey out the choice. PCIE4C_X1Y0 with GTY_Quad_227 is what the Vitis
        # shell uses, and is the only U55C location the XDMA IP offers Tandem
        # PCIe on, so stay on the supported one. All four PCIE4CE4 sites and all
        # sixteen PCIe GTY channels are in SLR0 on this device regardless.
        set_property -dict [list \
                    CONFIG.PCIE_BOARD_INTERFACE {Custom} \
                    CONFIG.pcie_blk_locn {PCIE4C_X1Y0} \
                    CONFIG.select_quad {GTY_Quad_227} \
                    CONFIG.axil_master_64bit_en {true} \
                    CONFIG.axilite_master_en {true} \
                    CONFIG.axilite_master_scale {Megabytes} \
                    CONFIG.axilite_master_size {4} \
                    CONFIG.pl_link_cap_max_link_speed {8.0_GT/s} \
                    CONFIG.pl_link_cap_max_link_width {X16} \
                    CONFIG.xdma_pcie_64bit_en {true} \
                    ] [get_bd_cells xdma_0]

        set_property CONFIG.cfg_mgmt_if {false} [get_bd_cells xdma_0]

        # Ground the irq
        create_bd_cell -type ip -vlnv xilinx.com:ip:xlconstant:1.1 xlconstant_1
        set_property CONFIG.CONST_VAL {0} [get_bd_cells xlconstant_1]
        connect_bd_net [get_bd_pins xlconstant_1/dout] [get_bd_pins xdma_0/usr_irq_req]

        # Export the xdma pcie_mgt interface
        make_bd_intf_pins_external  [get_bd_intf_pins xdma_0/pcie_mgt] -name PEX

        # 3. Create the clocking buffer for the xdma
        create_bd_cell -type ip -vlnv xilinx.com:ip:util_ds_buf:2.2 util_ds_buf_0
        set_property CONFIG.C_BUF_TYPE {IBUFDSGTE} [get_bd_cells util_ds_buf_0]

        # Connect the clocks to the XDMA IP
        connect_bd_net [get_bd_pins util_ds_buf_0/IBUF_DS_ODIV2] [get_bd_pins xdma_0/sys_clk]
        connect_bd_net [get_bd_pins util_ds_buf_0/IBUF_OUT] [get_bd_pins xdma_0/sys_clk_gt]

        # Export the differential clock of the buffer
        make_bd_intf_pins_external  [get_bd_intf_pins util_ds_buf_0/CLK_IN_D] -name PCIE_REFCLK1

        # Export the PCIE reset
        make_bd_pins_external  [get_bd_pins xdma_0/sys_rst_n] -name PCIE_PERST_LS_65

        # Make AXI domain clock converter for the xdma memory access
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_0

        # Make AXI domain clock converter for the xdma management access
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_1
        """
  }

  def getSytstemClockingAndResetConfigTclSyntax(descriptor: FullSysGenDescriptor, isQuestaSim: Boolean = false): String = {
    val sb = new StringBuilder

    // Create and configure the clock wizard
    sb.append("create_bd_cell -type ip -vlnv xilinx.com:ip:clk_wiz:6.0 clk_wiz_0")
    sb.append(
      """
        set_property -dict [list \
              CONFIG.CLKOUT1_JITTER {81.911} \
              CONFIG.CLKOUT1_PHASE_ERROR {76.967} \
              """ +
        f"CONFIG.CLKOUT1_REQUESTED_OUT_FREQ ${descriptor.targetFrequency}%.3f" + """\""" +
        """
            CONFIG.PRIM_IN_FREQ {100.000} \
            CONFIG.NUM_OUT_CLKS {2} \
            CONFIG.PRIM_SOURCE {Differential_clock_capable_pin} \
            CONFIG.RESET_PORT {resetn} \
            CONFIG.RESET_TYPE {ACTIVE_LOW} \
            ] [get_bd_cells clk_wiz_0]
        """
    )
    // CLK_IN1_BOARD_INTERFACE is deliberately not set: the input is exported as
    // SYSCLK3 (BK43/BK44) below, not the PCIe refclk the board file would bind.
    // The MMCM divider overrides are dropped too -- they hard-coded 100 x 15/6 =
    // 250 MHz, which contradicts CLKOUT1_REQUESTED_OUT_FREQ; let the IP solve for
    // the requested frequency instead.

    // Export the clock to the external clock
    sb.append("make_bd_intf_pins_external  [get_bd_intf_pins clk_wiz_0/CLK_IN1_D] -name SYSCLK3\n")

    // Connect the axi clocks to this clock
    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins hbm_0/AXI_*_ACLK]\n")
    sb.append(f"connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins ${descriptor.name}_0/clock]\n")
    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_clock_converter_0/m_axi_aclk]\n")
    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_clock_converter_1/m_axi_aclk]\n")

    // Connect the clock wizard reset
    sb.append("connect_bd_net [get_bd_ports PCIE_PERST_LS_65] [get_bd_pins clk_wiz_0/resetn]\n")

    // Create reset system
    sb.append("create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 proc_sys_reset_1\n")

    // Connect it to the clock wizard and the axi resets of the system
    sb.append("connect_bd_net [get_bd_pins clk_wiz_0/locked] [get_bd_pins proc_sys_reset_1/dcm_locked]\n")
    sb.append("connect_bd_net [get_bd_pins proc_sys_reset_1/slowest_sync_clk] [get_bd_pins clk_wiz_0/clk_out1]\n")
    sb.append(f"connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_reset] [get_bd_pins ${descriptor.name}_0/reset]\n")
    sb.append(
      "connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_clock_converter_0/m_axi_aresetn]\n"
    )
    sb.append(
      "connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_clock_converter_1/m_axi_aresetn]\n"
    )
    
    // Create the second reset system
    sb.append("connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins hbm_0/AXI_*_ARESET_N]\n")




    // The AXI-Lite width converter only exists when the management slave is
    // 64-bit wide (non-Vitis descriptors); guard the wildcards so the connect
    // does not fail on an empty object list.
    sb.append("""
        if {[llength [get_bd_cells -quiet axi_dwidth_converter*]] > 0} {
          connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_dwidth_converter*/*clk*]
          connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_dwidth_converter*/*aresetn*]
        }
    """)

    // Reset coming from AXI through the pcie
    sb.append("""
            # Create and configure AXI GPIO
        create_bd_cell -type ip -vlnv xilinx.com:ip:axi_gpio:2.0 axi_gpio_0
        set_property -dict [list \
          CONFIG.C_ALL_OUTPUTS {1} \
          CONFIG.C_DOUT_DEFAULT {0xFFFFFFFF} \
          CONFIG.C_GPIO_WIDTH {1} \
        ] [get_bd_cells axi_gpio_0]

        # Create and configure logic vector
        create_bd_cell -type ip -vlnv xilinx.com:ip:util_vector_logic:2.0 util_vector_logic_0
        set_property -dict [list \
          CONFIG.C_OPERATION {and} \
          CONFIG.C_SIZE {1} \
        ] [get_bd_cells util_vector_logic_0]

        # Create axi interconnect
        create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 smartconnect_32
        set_property -dict [list \
          CONFIG.NUM_MI {2} \
          CONFIG.NUM_SI {1} \
        ] [get_bd_cells smartconnect_32]
        
        """

    )

    if(!isQuestaSim) {
      sb.append(     
          """
          # Connect the axi full of the xdma to the axi clock converter
          connect_bd_intf_net [get_bd_intf_pins xdma_0/M_AXI] [get_bd_intf_pins axi_clock_converter_0/S_AXI]
          connect_bd_net [get_bd_pins xdma_0/axi_aclk] [get_bd_pins axi_clock_converter_0/s_axi_aclk]
          connect_bd_net [get_bd_pins xdma_0/axi_aresetn] [get_bd_pins axi_clock_converter_0/s_axi_aresetn]

          # Connect the xdma axi lite to the axi clock converters and the axi gpio through the smartconnect
          connect_bd_intf_net [get_bd_intf_pins smartconnect_32/S00_AXI] [get_bd_intf_pins xdma_0/M_AXI_LITE]
          connect_bd_net [get_bd_pins xdma_0/axi_aclk] [get_bd_pins axi_clock_converter_1/s_axi_aclk]
          connect_bd_net [get_bd_pins xdma_0/axi_aresetn] [get_bd_pins axi_clock_converter_1/s_axi_aresetn]
          connect_bd_intf_net [get_bd_intf_pins smartconnect_32/M01_AXI] [get_bd_intf_pins axi_gpio_0/S_AXI]
          connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/S_AXI] [get_bd_intf_pins smartconnect_32/M00_AXI]
          connect_bd_net [get_bd_pins xdma_0/axi_aclk] [get_bd_pins smartconnect_32/aclk]
          connect_bd_net [get_bd_pins xdma_0/axi_aresetn] [get_bd_pins smartconnect_32/aresetn]
          connect_bd_net [get_bd_pins xdma_0/axi_aclk] [get_bd_pins axi_gpio_0/s_axi_aclk]
          connect_bd_net [get_bd_pins xdma_0/axi_aresetn] [get_bd_pins axi_gpio_0/s_axi_aresetn]
          
      """)
    } else {
      sb.append("""
        # Connect the axi_vip_1 to the samrtconnect 32
        connect_bd_intf_net [get_bd_intf_pins smartconnect_32/S00_AXI] [get_bd_intf_pins axi_vip_1/M_AXI]
        # Connect master 0 of the smartconnect to the axi clock converter 1
        connect_bd_intf_net [get_bd_intf_pins smartconnect_32/M00_AXI] [get_bd_intf_pins axi_clock_converter_1/S_AXI]
        # Connecy master 1 of the smartconnect to the axi gpio 0
        connect_bd_intf_net [get_bd_intf_pins smartconnect_32/M01_AXI] [get_bd_intf_pins axi_gpio_0/S_AXI]

        connect_bd_net [get_bd_pins axi_vip_clk] [get_bd_pins smartconnect_32/aclk]
        connect_bd_net [get_bd_pins axi_vip_aresetn] [get_bd_pins smartconnect_32/aresetn]
        connect_bd_net [get_bd_pins axi_vip_clk] [get_bd_pins axi_gpio_0/s_axi_aclk]
        connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_gpio_0/s_axi_aresetn]

      """)
    }

    
    sb.append("connect_bd_net [get_bd_ports PCIE_PERST_LS_65] [get_bd_pins util_vector_logic_0/Op2]\n")
    sb.append("connect_bd_net [get_bd_pins util_vector_logic_0/Res] [get_bd_pins proc_sys_reset_1/ext_reset_in]\n")
    sb.append("connect_bd_net [get_bd_pins axi_gpio_0/gpio_io_o] [get_bd_pins util_vector_logic_0/Op1]\n")

    sb.toString()
  }

  def getMastersAddressMapTcl(): String = {
    ""
  }

  def getPEsTcl(descriptor: FullSysGenDescriptor): String = {
    // In the PE HDL path for each task type check if there are any files with .tcl extension
    // read all the tcl files into one string and return it
    
    val sb = new StringBuilder
    for(task <- descriptor.taskDescriptors) {
      val peHDLPath = task.peHDLPath
      // Get all the names of the files in that directory
      val files = new java.io.File(peHDLPath).listFiles.filter(_.getName.endsWith(".tcl"))
      // if files is not empty then read all the files and append them to the string builder
      for(file <- files) {
        val source = scala.io.Source.fromFile(file)
        val lines = try source.mkString finally source.close()
        sb.append(lines)
      }
    }

    sb.toString()
  }

  def getAxiVipConfig(dataVipAddrWidth: Int = 64): String = {
    raw"""
    create_bd_port -dir I -type clk -freq_hz 250000000 axi_vip_clk
    create_bd_port -dir I axi_vip_aresetn

    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 axi_vip_0
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_vip:1.1 axi_vip_1
    
    set_property -dict [list \
      CONFIG.ADDR_WIDTH {64} \
      CONFIG.DATA_WIDTH {32} \
      CONFIG.INTERFACE_MODE {MASTER} \
    ] [get_bd_cells axi_vip_1]

    set_property -dict [list \
      CONFIG.ADDR_WIDTH {$dataVipAddrWidth} \
      CONFIG.DATA_WIDTH {512} \
      CONFIG.INTERFACE_MODE {MASTER} \
    ] [get_bd_cells axi_vip_0]

    # Connect the axi vip to the clock and reset
    connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_vip_1/aclk]
    connect_bd_net [get_bd_ports axi_vip_clk] [get_bd_pins axi_vip_0/aclk]
    connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_vip_1/aresetn]
    connect_bd_net [get_bd_ports axi_vip_aresetn] [get_bd_pins axi_vip_0/aresetn]

    # Make AXI domain clock converter for the xdma memory access
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_0

    # Make AXI domain clock converter for the xdma management access
    create_bd_cell -type ip -vlnv xilinx.com:ip:axi_clock_converter:2.1 axi_clock_converter_1

    # Create the xdma reset port for other components that need it
    create_bd_port -dir I PCIE_PERST_LS_65
    """
  }
}
