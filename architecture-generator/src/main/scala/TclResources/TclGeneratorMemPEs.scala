package TclResources

import Descriptors._

object TclGeneratorMemPEs {

  /**
    * Emit the Vivado block design for the XDMA/PCIe flow.
    *
    * Topology, from the design outwards:
    *
    * {{{
    *   <name>_0/m_axi_XX -> rs0_m_axi_XX -> rs1_m_axi_XX   (SLR crossing)
    *                     -> smartconnect_rama_XX           (width -> 256)
    *                     -> rama_hbm_XX                    (striping, AXI4 -> AXI3)
    *                     -> hbm_0/SAXI_XX_8HI
    *
    *   xdma_0/M_AXI      -> axi_clock_converter_0          (250 MHz -> design clock)
    *                     -> smartconnect_rama_host         (512 -> 256)
    *                     -> rama_host_0                    (same striping config)
    *                     -> hbm_0/SAXI_<numPorts>_8HI
    * }}}
    *
    * The host DMA port gets its own RAMA rather than reaching HBM directly:
    * RAMA's striping permutes addresses, so a host that bypassed it would read
    * and write different physical locations than the PEs do for the same
    * address. This is the same reason the QuestaSim flow puts its memory VIP
    * behind `rama_vip_0`.
    *
    * With `--raw-hbm-ports` the design exports one master per memory unit instead
    * of one per bus group, so a SmartConnect stage is inserted ahead of the
    * register slices to reduce them to `reduce_axi` ports:
    *
    * {{{
    *   <name>_0/m_axi_{03,17,42,...} -> smartconnect_hbm_00   (SLR1/2, NUM_SI=k)
    *                                 -> rs0_m_axi_00 -> rs1_m_axi_00
    *                                 -> rama_hbm_00 -> hbm_0/SAXI_00_8HI
    * }}}
    *
    * Aggregating before the crossing rather than after it keeps the number of SLR
    * crossings at `reduce_axi` instead of one per raw master, at the cost of
    * carrying 256 bits across each. The SmartConnects are floorplanned with the
    * design (see [[TclGeneralConfigs.getFloorplanXdc]]).
    *
    * @param reduce_axi    number of HBM ports to end up with. In raw mode this is
    *                      the `-r` target rather than the design's port count.
    * @param hbmPortWidths native data width of each exported `m_axi_XX`, used to
    *                      size the register slices. Ignored in raw mode, where the
    *                      slices sit downstream of the SmartConnects at 256 bits.
    * @param rawPortLabels `<kind>/<task>` per exported master; non-empty selects
    *                      raw mode
    */
  def generate(
      fullSysGenDescriptor: FullSysGenDescriptor,
      tclFileDirectory: String,
      reduce_axi: Int,
      hbmPortWidths: Seq[Int] = Seq.empty,
      rawPortLabels: Seq[String] = Seq.empty
  ) = {
    val rawMode = rawPortLabels.nonEmpty
    require(
      rawMode || hbmPortWidths.isEmpty || hbmPortWidths.length == reduce_axi,
      s"[TclGeneratorMemPEs] Expected $reduce_axi HBM port width(s), got ${hbmPortWidths.length}."
    )

    // Every design master gets a RAMA on its own HBM port, and the host DMA path
    // needs one more for its own.
    val totalHbmPorts = TclGeneralConfigs.getHbmRamaStripingPortCount(reduce_axi)
    require(
      reduce_axi < 32,
      s"[TclGeneratorMemPEs] The design exports $reduce_axi HBM port(s); the PCIe flow needs one " +
        s"more for the host DMA path, which exceeds the 32 HBM slave ports. Reduce the AXI port " +
        s"count (-r) to at most 31."
    )

    // In raw mode the slices sit behind the aggregating SmartConnects, which
    // already present RAMA's 256-bit bus, so the native master widths do not
    // reach them.
    val widths =
      if (rawMode || hbmPortWidths.isEmpty) Seq.fill(reduce_axi)(256) else hbmPortWidths

    // Which raw masters share each HBM port. Deliberately mixes unit kinds -- see
    // HbmPortDistribution.
    val assignment =
      if (rawMode) Some(HbmPortDistribution.distribute(rawPortLabels, reduce_axi)) else None

    val tclCommands = new StringBuilder()
    def tclWriteln(s: String) = {
      tclCommands.append(s)
      tclCommands.append("\n")
    }

    // Create an instance of the compute system
    tclWriteln(f"create_bd_cell -type module -reference ${fullSysGenDescriptor.name} ${fullSysGenDescriptor.name}_0")

    // Add any tcl generated with the PEs from HLS
    tclWriteln(TclGeneralConfigs.getPEsTcl(fullSysGenDescriptor))

    // Create and configure the xdma
    tclWriteln(TclGeneralConfigs.getXdmaConfigTclSyntax())

    // Create and configure the hbm. Every enabled HBM slave port has to be
    // driven, so enable exactly the ports the RAMA fabric below connects.
    tclWriteln(TclGeneralConfigs.getHBMConfigTclSyntax(totalHbmPorts))

    // Create the clocking wizard and reset for the system. This also wires
    // xdma_0/M_AXI into axi_clock_converter_0 and the AXI-Lite fabric.
    tclWriteln(TclGeneralConfigs.getSytstemClockingAndResetConfigTclSyntax(fullSysGenDescriptor))

    // Raw export: aggregate the design's masters onto one SmartConnect per HBM
    // port first, so only `reduce_axi` buses cross the SLR boundary below.
    assignment.foreach { a =>
      tclWriteln(
        TclGeneralConfigs.getHbmAggregateSmartConnectTcl(
          descriptorName = fullSysGenDescriptor.name,
          assignment = a,
          labels = rawPortLabels,
          clkPin = "[get_bd_pins clk_wiz_0/clk_out1]",
          resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]"
        )
      )
      println(
        s"[TclGeneratorMemPEs] Raw HBM export: ${rawPortLabels.length} master(s) aggregated onto " +
          s"$reduce_axi SmartConnect(s) ahead of the SLR crossing.\n" +
          HbmPortDistribution.summary(a, rawPortLabels)
      )
    }

    // Register slices on every interface that crosses an SLR boundary.
    tclWriteln(
      TclGeneralConfigs.getAxiRegisterSliceTcl(
        descriptorName = fullSysGenDescriptor.name,
        portWidths = widths,
        addrWidth = fullSysGenDescriptor.widthAXIAddress,
        liteAddrWidth = fullSysGenDescriptor.getNumConfigPorts() + 6,
        liteDataWidth = if (fullSysGenDescriptor.isVitisProject) 32 else 64,
        clkPin = "[get_bd_pins clk_wiz_0/clk_out1]",
        resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]",
        masterPinOf =
          if (rawMode) Some((i: Int) => f"[get_bd_intf_pins smartconnect_hbm_${i}%02d/M00_AXI]")
          else None
      )
    )

    // Connect the management port from the xdma to the compute system, through
    // the AXI-Lite register slice. A Vitis descriptor already exposes a 32-bit
    // AXI-Lite slave, so the width converter is only needed for the 64-bit
    // (non-Vitis) management slave.
    if (fullSysGenDescriptor.isVitisProject) {
      tclWriteln(
        "connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins rs_s_axil_mgmt/S_AXI]"
      )
    } else {
      tclWriteln("create_bd_cell -type ip -vlnv xilinx.com:ip:axi_dwidth_converter:2.1 axi_dwidth_converter_0")
      tclWriteln(
        "connect_bd_intf_net [get_bd_intf_pins axi_dwidth_converter_0/M_AXI] [get_bd_intf_pins rs_s_axil_mgmt/S_AXI]"
      )
      tclWriteln(
        "connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_1/M_AXI] [get_bd_intf_pins axi_dwidth_converter_0/S_AXI]"
      )
      // Clocked here rather than by the wildcard guard in
      // getSytstemClockingAndResetConfigTclSyntax: that block runs before this
      // cell exists, so its [get_bd_cells -quiet axi_dwidth_converter*] is empty.
      // It sits downstream of axi_clock_converter_1, so it is in the design clock
      // domain, not the xdma one.
      tclWriteln("connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins axi_dwidth_converter_0/s_axi_aclk]")
      tclWriteln(
        "connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins axi_dwidth_converter_0/s_axi_aresetn]"
      )
    }

    if (fullSysGenDescriptor.hasAXIDMAInput) {
      println(
        "[TclGeneratorMemPEs] WARNING: the design exports s_axi_xdma but the PCIe flow gives the " +
          "host its own RAMA'd HBM port; s_axi_xdma is left unconnected. Set hasAXIDMAInput to " +
          "false in the descriptor to drop the port."
      )
      tclWriteln(
        "puts \"WARNING: s_axi_xdma is left unconnected; the host reaches HBM through its own RAMA.\""
      )
    }

    // Put a RAMA IP on every generated AXI memory master and on the host DMA
    // path. All instances use the same per-memory striping config so host-side
    // and design-side accesses stay address-map coherent. The masters are taken
    // from the far side of the register slices.
    tclWriteln(
      TclGeneralConfigs.getHbmRamaStripingTcl(
        descriptorName = fullSysGenDescriptor.name,
        numPorts = reduce_axi,
        portWidths = widths,
        clkPin = "[get_bd_pins clk_wiz_0/clk_out1]",
        resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]",
        extMasterPin = "[get_bd_intf_pins axi_clock_converter_0/M_AXI]",
        extCellPrefix = "rama_host",
        masterPinOf = Some((i: Int) => f"[get_bd_intf_pins rs1_m_axi_${i}%02d/M_AXI]")
      )
    )

    println(
      s"[TclGeneratorMemPEs] $reduce_axi design memory master(s) and the host DMA path each get a " +
        s"RAMA IP on their own HBM port ($totalHbmPorts of 32 in total), striping 64-byte fragments " +
        s"across all 32 pseudo channels (16 GB)."
    )

    // Assign addresses
    tclWriteln("assign_bd_address")
    tclWriteln(
      f"assign_bd_address -target_address_space /xdma_0/M_AXI_LITE [get_bd_addr_segs ${fullSysGenDescriptor.name}_0/s_axil_mgmt_hardcilk/reg0]"
    )

    // The PCIe driver resets the design by writing this GPIO (RESET_ADDR in
    // pcie_main.cpp), so its segment is pinned rather than auto-assigned.
    tclWriteln("set_property range 32K [get_bd_addr_segs {xdma_0/M_AXI_LITE/SEG_axi_gpio_0_Reg}]")
    tclWriteln("set_property offset 0x0008000 [get_bd_addr_segs {xdma_0/M_AXI_LITE/SEG_axi_gpio_0_Reg}]")

    // Design-specific constraints and hooks, written alongside the project script.
    val floorplanXdcName = s"${fullSysGenDescriptor.name}_floorplan.xdc"
    val drcWaiverName = s"${fullSysGenDescriptor.name}_drc_waivers.tcl"

    writeFile(
      s"${tclFileDirectory}/${floorplanXdcName}",
      TclGeneralConfigs.getFloorplanXdc(fullSysGenDescriptor.name, rawMode)
    )
    writeFile(s"${tclFileDirectory}/${drcWaiverName}", TclGeneralConfigs.getDrcWaiverTcl())

    // The floorplan is implementation-only and has to be read after the design's
    // own constraints, since it matches cells by hierarchical name.
    val extraProjectTcl =
      s"""
        set hc_floorplan_xdc [file join $$script_folder ${floorplanXdcName}]
        add_files -fileset constrs_1 -norecurse $$hc_floorplan_xdc
        set_property USED_IN          {implementation} [get_files $$hc_floorplan_xdc]
        set_property PROCESSING_ORDER LATE             [get_files $$hc_floorplan_xdc]

        set_property STEPS.OPT_DESIGN.TCL.PRE [file join $$script_folder ${drcWaiverName}] [get_runs impl_1]
      """ + TclGeneralConfigs.getImplStrategyTcl()

    // Write the tcl commands to a file
    writeFile(
      s"${tclFileDirectory}/${fullSysGenDescriptor.name}_memPEs.tcl",
      TclGeneralConfigs.getProjectWrapperTCLSyntax(
        tclCommands.toString(),
        fullSysGenDescriptor,
        extraProjectTcl = extraProjectTcl
      )
    )

    // Headless synthesis/P&R/bitstream script, and the shell driver that ties
    // the block design build, the GUI and the batch run together.
    writeFile(
      s"${tclFileDirectory}/${fullSysGenDescriptor.name}_impl.tcl",
      TclGeneralConfigs.getImplRunTcl(fullSysGenDescriptor.name)
    )
    writeFile(
      s"${tclFileDirectory}/build.sh",
      TclGeneralConfigs.getBuildShellScript(fullSysGenDescriptor.name)
    )
    val chmod = new java.lang.ProcessBuilder("chmod", "+x", s"${tclFileDirectory}/build.sh").start()
    chmod.waitFor()

    println(
      s"[TclGeneratorMemPEs] Run ./build.sh in the tcl directory: no flags builds the block " +
        s"design, --gui opens it, --impl runs synthesis, place & route and bitstream headless."
    )
  }

  private def writeFile(path: String, contents: String): Unit = {
    val f = new java.io.PrintWriter(new java.io.File(path))
    try f.write(contents)
    finally f.close()
  }
}
