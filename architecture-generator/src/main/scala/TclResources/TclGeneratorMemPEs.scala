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
      enableRamaStriping: Boolean = false,
      ramaPorts: Set[Int] = Set.empty,
      rawPortLabels: Seq[String] = Seq.empty,
      rawPortAxi3: Seq[Boolean] = Seq.empty,
      peInstanceNames: Seq[(String, Seq[String])] = Seq.empty,
      peIfaceBuffers: Seq[(String, Seq[String])] = Seq.empty
  ) = {
    val rawMode = rawPortLabels.nonEmpty
    require(
      rawMode || hbmPortWidths.isEmpty || hbmPortWidths.length == reduce_axi,
      s"[TclGeneratorMemPEs] Expected $reduce_axi HBM port width(s), got ${hbmPortWidths.length}."
    )

    // The host DMA path always takes an HBM port of its own, striped or not, so
    // the design can use at most 31.
    require(
      reduce_axi < 32,
      s"[TclGeneratorMemPEs] The design exports $reduce_axi HBM port(s); the PCIe flow needs one " +
        s"more for the host DMA path, which exceeds the 32 HBM slave ports. Reduce the AXI port " +
        s"count (-r) to at most 31."
    )

    // HBM port that carries the host DMA traffic, and the number of slave ports
    // to enable. Striping mode may fold the host onto port 00 when all 32 are
    // taken, which is what getHbmRamaStripingPortCount encodes.
    val hostHbmPort = reduce_axi
    val totalHbmPorts =
      if (enableRamaStriping) TclGeneralConfigs.getHbmRamaStripingPortCount(reduce_axi)
      else hostHbmPort + 1

    // Raw mode shares each HBM port between several masters, so no port is a
    // single `generateRAMA` task's own and the per-port RAMAs are dropped -- use
    // --rama-striping to put a RAMA on every port instead.
    val effectiveRamaPorts = if (rawMode || enableRamaStriping) Set.empty[Int] else ramaPorts
    if (!enableRamaStriping && effectiveRamaPorts.nonEmpty) {
      // A per-port RAMA permutes addresses within its port, but without striping
      // the host reaches HBM through a plain SmartConnect and so does not see the
      // same permutation. Striping mode avoids this by putting the host behind an
      // identically-configured RAMA of its own.
      println(
        "[TclGeneratorMemPEs] WARNING: this design has generateRAMA task(s) on HBM port(s) " +
          s"${effectiveRamaPorts.toSeq.sorted.map(i => f"$i%02d").mkString(", ")}, but " +
          "--rama-striping was not given. The host DMA path reaches HBM directly while those " +
          "ports go through a RAMA that permutes addresses, so data the host loads into their " +
          "address range will not be read back by the PEs at the same addresses. Add " +
          "--rama-striping to put the host behind a matching RAMA."
      )
    }

    // In raw mode the slices sit behind the aggregating SmartConnects, which
    // already present RAMA's 256-bit bus, so the native master widths do not
    // reach them.
    val widths =
      if (rawMode || hbmPortWidths.isEmpty) Seq.fill(reduce_axi)(256) else hbmPortWidths

    // Which raw masters share each HBM port. Deliberately mixes unit kinds -- see
    // HbmPortDistribution.
    val assignment =
      if (rawMode) Some(HbmPortDistribution.distribute(rawPortLabels, reduce_axi)) else None

    // A raw port with a single 256-bit AXI4 master already presents the bus the
    // register slices and RAMA take, so its SmartConnect would only add latency.
    val bypassPorts =
      assignment
        .map(TclGeneralConfigs.rawPortsBypassingSmartConnect(_, hbmPortWidths, rawPortAxi3))
        .getOrElse(Set.empty[Int])

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
          resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]",
          bypassPorts = bypassPorts
        )
      )
      println(
        s"[TclGeneratorMemPEs] Raw HBM export: ${rawPortLabels.length} master(s) aggregated onto " +
          s"${reduce_axi - bypassPorts.size} SmartConnect(s) ahead of the SLR crossing" +
          (if (bypassPorts.isEmpty) ""
           else
             s"; ${bypassPorts.size} port(s) with a single 256-bit AXI4 master go straight to " +
               s"their register slice (${bypassPorts.toSeq.sorted.map(p => f"$p%02d").mkString(", ")})") +
          ".\n" + HbmPortDistribution.summary(a, rawPortLabels)
      )
    }

    // Register slices on every interface that crosses an SLR boundary.
    tclWriteln(
      TclGeneralConfigs.getAxiRegisterSliceTcl(
        descriptorName = fullSysGenDescriptor.name,
        portWidths = widths,
        addrWidth = fullSysGenDescriptor.widthAXIAddress,
        liteAddrWidth = fullSysGenDescriptor.getNumConfigPorts() + 6,
        liteDataWidth = 64,
        clkPin = "[get_bd_pins clk_wiz_0/clk_out1]",
        resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]",
        masterPinOf = assignment.map(a =>
          TclGeneralConfigs.rawPortMasterPin(fullSysGenDescriptor.name, a, bypassPorts)
        )
      )
    )

    // Connect the management port from the xdma to the compute system, through
    // the AXI-Lite register slice. This flow always builds the design with the
    // native 64-bit AXI-Lite management slave (the 32-bit Vitis convention
    // belongs to the -x flow, which has its own RTL elaboration and never reaches
    // this generator), so the width converter is unconditional.
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

    // Both memory paths take their masters from the far side of the SLR-crossing
    // register slices, so raw and bus-group-muxed designs look the same here: one
    // `rs1_m_axi_XX` per HBM port.
    val hbmMasterPin = (i: Int) => f"[get_bd_intf_pins rs1_m_axi_${i}%02d/M_AXI]"

    if (enableRamaStriping) {
      // Put a RAMA IP on every generated AXI memory master and on the host DMA
      // path. All instances use the same per-memory striping config so host-side
      // and design-side accesses stay address-map coherent.
      tclWriteln(
        TclGeneralConfigs.getHbmRamaStripingTcl(
          descriptorName = fullSysGenDescriptor.name,
          numPorts = reduce_axi,
          portWidths = widths,
          clkPin = "[get_bd_pins clk_wiz_0/clk_out1]",
          resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]",
          extMasterPin = "[get_bd_intf_pins axi_clock_converter_0/M_AXI]",
          extCellPrefix = "rama_host",
          masterPinOf = Some(hbmMasterPin)
        )
      )

      println(
        s"[TclGeneratorMemPEs] Striped RAMA mode enabled: $reduce_axi design memory master(s) and " +
          s"the host DMA path each get a RAMA IP on their own HBM port ($totalHbmPorts of 32 in " +
          s"total), striping 64-byte fragments across all 32 pseudo channels (16 GB)."
      )
    } else {
      // No striping: each port reaches its own HBM slave through a 1:1
      // SmartConnect, which does the data-width and AXI4 -> AXI3 conversion RAMA
      // would otherwise have done. Ports belonging to a `generateRAMA` task still
      // get their own RAMA spliced in behind the SmartConnect.
      tclWriteln(
        TclGeneralConfigs.getHbmSmartConnectTcl(
          descriptorName = fullSysGenDescriptor.name,
          numPorts = reduce_axi,
          clkPin = "[get_bd_pins clk_wiz_0/clk_out1]",
          resetPin = "[get_bd_pins proc_sys_reset_1/peripheral_aresetn]",
          ramaPorts = effectiveRamaPorts,
          masterPinOf = Some(hbmMasterPin)
        )
      )

      // Same conversion for the host DMA path on its own spare HBM port.
      val hostSmartConnect = f"smartconnect_hbm_${hostHbmPort}%02d"
      tclWriteln(f"create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 ${hostSmartConnect}")
      tclWriteln(
        f"set_property -dict [list CONFIG.NUM_SI {1} CONFIG.NUM_MI {1} CONFIG.NUM_CLKS {1}] [get_bd_cells ${hostSmartConnect}]"
      )
      tclWriteln(
        f"connect_bd_intf_net [get_bd_intf_pins axi_clock_converter_0/M_AXI] [get_bd_intf_pins ${hostSmartConnect}/S00_AXI]"
      )
      tclWriteln(
        f"connect_bd_intf_net [get_bd_intf_pins ${hostSmartConnect}/M00_AXI] [get_bd_intf_pins hbm_0/SAXI_${hostHbmPort}%02d_8HI]"
      )
      tclWriteln(f"connect_bd_net [get_bd_pins clk_wiz_0/clk_out1] [get_bd_pins ${hostSmartConnect}/aclk]")
      tclWriteln(
        f"connect_bd_net [get_bd_pins proc_sys_reset_1/peripheral_aresetn] [get_bd_pins ${hostSmartConnect}/aresetn]"
      )

      println(
        s"[TclGeneratorMemPEs] $reduce_axi design memory master(s) and the host DMA path each get " +
          s"their own HBM port ($totalHbmPorts of 32 in total) through a 1:1 SmartConnect. Pass " +
          s"--rama-striping to spread every port's traffic across all 32 pseudo channels instead."
      )
    }

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
      TclGeneralConfigs.getFloorplanXdc(
        fullSysGenDescriptor, rawMode, peInstanceNames, peIfaceBuffers)
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
      """ + TclGeneralConfigs.getImplStrategyTcl(fullSysGenDescriptor)

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
      s"[TclGeneratorMemPEs] Run ./build.sh in the pcie directory: no flags builds the block " +
        s"design, --gui opens it, --impl runs synthesis, place & route and bitstream headless."
    )
  }

  private def writeFile(path: String, contents: String): Unit = {
    val f = new java.io.PrintWriter(new java.io.File(path))
    try f.write(contents)
    finally f.close()
  }
}
