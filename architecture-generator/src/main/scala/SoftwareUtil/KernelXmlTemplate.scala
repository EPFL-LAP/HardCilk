package SoftwareUtil

import Descriptors._
import java.io.PrintWriter
import java.nio.file.{Files, Paths}

/** Emits the Vitis RTL-kernel description (`user_0.xml`) and the `v++`
  * connectivity config (`conn_u55c.cfg`) for a HardCilk top-level kernel.
  *
  * Today these two files are hand-maintained per benchmark and have to be
  * corrected by hand every time the port list changes. Everything they need is
  * already computed inside the generator, so we emit them instead and keep them
  * in lockstep with the RTL:
  *
  *   - the `m_axi_*` master list comes straight from `numHbmPortExports` (the
  *     total count of top-level HBM masters, which *includes* the LockServer's
  *     dedicated port appended last by `HardCilk.connectLockServer`);
  *   - the `s_axil_mgmt_hardcilk` register block uses the per-server management
  *     base addresses `(idx << 6) + base` already assigned in the
  *     `FullSysGenDescriptor` constructor (so they match
  *     `FullSysGenDescriptor.h` and the host driver exactly);
  *   - the mFPGA on/off state is read off the descriptor — with the flags off
  *     (single-FPGA BFS) no `m_axis_mFPGA`/`s_axis_mFPGA` ports and no CMAC
  *     stream connects are emitted.
  *
  * The emitter is benchmark-agnostic: the only lock-specific aspect is "one
  * extra `m_axi`", which it gets for free from `numHbmPortExports`. The
  * `toLock`/ `fromLock` lanes never cross the kernel boundary, so nothing is
  * emitted for them.
  */
object KernelXmlTemplate {

  /** Write `user_0.xml` and `conn_u55c.cfg` into `outputDir`.
    *
    * @param descriptor
    *   the parsed system descriptor (post-`validate()`, so the per-server base
    *   addresses are assigned)
    * @param numHbmPortExports
    *   total top-level `m_axi_*` masters, lock port last
    * @param kernelName
    *   Vitis kernel name, e.g. "BFS_0"
    * @param vlnvName
    *   VLNV leaf, e.g. "BFS" -> epfl.ch:hardcilk:BFS:1.0
    * @param outputDir
    *   directory to write both files into
    * @param ramaPortIndices
    *   exact exported memory ports that use Vitis native RAMA after descriptor
    *   overrides and the CLI default have been resolved
    * @param enableRamaStriping
    *   configure the selected RAMA ports for per-memory striping; watcher ports
    *   remain direct and exclusive when present
    */
  def generate(
      descriptor: FullSysGenDescriptor,
      numHbmPortExports: Int,
      kernelName: String,
      vlnvName: String,
      outputDir: String,
      ramaPortIndices: Set[Int] = Set.empty,
      enableRamaStriping: Boolean = false
  ): Unit = {
    Files.createDirectories(Paths.get(outputDir))

    // When a watcher is present it owns the last two (topmost) masters, mapped to
    // the exclusive top HBM channels HBM[16:31]; every other master is confined to
    // HBM[0:15] and its addressable range is the lower 8 GB.
    val hasWatcher = descriptor.watcherConfig.isDefined
    val ramaHookPath = Paths
      .get(outputDir)
      .toAbsolutePath
      .normalize()
      .resolve("rama_configure_xrt.tcl")

    val xml =
      renderKernelXml(
        descriptor,
        numHbmPortExports,
        kernelName,
        vlnvName,
        hasWatcher
      )
    write(s"$outputDir/user_0.xml", xml)

    val cfg =
      renderConnCfg(
        descriptor,
        numHbmPortExports,
        kernelName,
        hasWatcher,
        ramaPortIndices,
        enableRamaStriping,
        if (ramaPortIndices.nonEmpty) Some(ramaHookPath.toString) else None
      )
    write(s"$outputDir/conn_u55c.cfg", cfg)
    if (ramaPortIndices.nonEmpty) {
      val memoryCount = if (hasWatcher) 16 else 32
      write(
        ramaHookPath.toString,
        renderRamaXrtHookTcl(
          expectedRamaCount = ramaPortIndices.size,
          striped = enableRamaStriping,
          memoryCount = memoryCount
        )
      )
    }
  }

  // --- helpers ---------------------------------------------------------------

  private def write(path: String, content: String): Unit = {
    val w = new PrintWriter(path)
    try w.write(content)
    finally w.close()
  }

  private def hex(v: Long): String = f"0x${v.toHexString.toUpperCase}"

  private def nextPow2(v: Long): Long = {
    var p = 1L
    while (p < v) p <<= 1
    p
  }

  private def portName(i: Int): String = f"m_axi_${i}%02d"

  /** Every assigned management-server base address, in `(idx << 6) + base`
    * order. These mirror `FullSysGenDescriptor.h` exactly; the host driver
    * writes raw registers at these offsets, so the args below are packaging
    * bookkeeping only — but we still place them at the true offsets so the XML
    * is self-consistent with the header.
    */
  private def configBaseAddresses(descriptor: FullSysGenDescriptor): Seq[Int] =
    descriptor.taskDescriptors.flatMap { t =>
      t.mgmtBaseAddresses.schedulerServersBaseAddresses ++
        t.mgmtBaseAddresses.spawnerServersBaseAddresses ++
        t.mgmtBaseAddresses.allocationServersBaseAddresses ++
        t.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses
    }.sorted

  // --- user_0.xml ------------------------------------------------------------

  private def renderKernelXml(
      descriptor: FullSysGenDescriptor,
      numMasters: Int,
      kernelName: String,
      vlnvName: String,
      hasWatcher: Boolean
  ): String = {

    val mfpga = descriptor.mFPGASynth || descriptor.mFPGASimulation

    // Per-master addressable range (metadata for package_xo/XRT). `base` is 0x0 and
    // the kernel issues ABSOLUTE physical addresses, so `range` is the highest byte
    // address the port can reach measured from 0 -- NOT a base-relative window size.
    //
    //  - No watcher: every master may address the full 16 GB HBM map (0x3FFFFFFFF).
    //  - With a watcher the top two masters are its telemetry ports, each pinned to
    //    a 4 GB HBM window in the connectivity .cfg, so their reach is bounded:
    //      port A (numMasters-2, m_axi_gmem)  -> HBM[16:23], top 0x2_FFFF_FFFF
    //      port B (numMasters-1, m_axi_gmem1) -> HBM[24:31], top 0x3_FFFF_FFFF
    //    Every compute/server master is confined to HBM[0:15] (lower 8 GB,
    //    0x1_FFFF_FFFF). (The routing itself comes from the .cfg sp= lines; this
    //    only makes the declared range honest instead of the old too-small 8 GB.)
    def portRange(i: Int): String =
      if (!hasWatcher) "0x3FFFFFFFF"
      else if (i == numMasters - 1) "0x3FFFFFFFF" // watcher port B -> HBM[24:31]
      else if (i == numMasters - 2) "0x2FFFFFFFF" // watcher port A -> HBM[16:23]
      else "0x1FFFFFFFF" // compute/server -> HBM[0:15]

    // --- ports ---
    val masterPorts = (0 until numMasters).map { i =>
      s"""      <port name="${portName(
          i
        )}" mode="master" range="${portRange(i)}" dataWidth="256" portType="addressable" base="0x0"/>"""
    }

    // Size the management slave to cover both the per-server register blocks and
    // the mem_* pointer args region (placed past the last server block, >= 0x200
    // to match the existing hand-written XMLs).
    val configAddrs = configBaseAddresses(descriptor)
    val maxConfig = if (configAddrs.nonEmpty) configAddrs.max else 0
    val memArgsBase = math.max(0x200L, (((maxConfig.toLong) >> 6) + 1) << 6)
    val topOffset = memArgsBase + numMasters.toLong * 8
    val slaveRange = math.max(0x1000L, nextPow2(topOffset))

    val mgmtPort =
      s"""      <port name="s_axil_mgmt_hardcilk" mode="slave" range="${hex(
          slaveRange
        )}" dataWidth="32" portType="addressable" base="0x0"/>"""

    val mfpgaPorts =
      if (mfpga)
        Seq(
          """      <port name="m_axis_mFPGA" mode="write_only" dataWidth="512" portType="stream"/>""",
          """      <port name="s_axis_mFPGA" mode="read_only" dataWidth="512" portType="stream"/>"""
        )
      else Seq.empty

    val portsBlock = (masterPorts ++ Seq(mgmtPort) ++ mfpgaPorts).mkString("\n")

    // --- args ---
    // Scalar management args: one per config server base address. (Cosmetic; the
    // driver writes raw registers. Placed at the true (idx<<6)+base offsets.)
    var id = 0
    val configArgs = configAddrs.map { addr =>
      val a =
        s"""      <arg name="cfg_${id}" addressQualifier="0" id="${id}" port="s_axil_mgmt_hardcilk" size="0x8" offset="${hex(
            addr.toLong
          )}" hostOffset="0x0" hostSize="0x8" type="ap_uint&lt;64>"/>"""
      id += 1
      a
    }

    // One mem_N void* arg per master port.
    val memArgs = (0 until numMasters).map { i =>
      val off = memArgsBase + i.toLong * 8
      val a =
        s"""      <arg name="mem_${i}" addressQualifier="1" id="${id}" port="${portName(
            i
          )}" size="0x8" offset="${hex(
            off
          )}" hostOffset="0x0" hostSize="0x8" type="void*"/>"""
      id += 1
      a
    }

    val mfpgaArgs =
      if (mfpga) {
        val msOut =
          s"""      <arg name="m_axis_mFPGA" addressQualifier="4" id="${id}" port="m_axis_mFPGA" size="0x0" offset="0x0" hostOffset="0x0" hostSize="0x0" memSize="0" type="stream&lt;ap_axiu&lt;512,0,0,4>>&amp;"/>"""
        id += 1
        val msIn =
          s"""      <arg name="s_axis_mFPGA" addressQualifier="4" id="${id}" port="s_axis_mFPGA" size="0x0" offset="0x0" hostOffset="0x0" hostSize="0x0" memSize="0" type="stream&lt;ap_axiu&lt;512,0,0,4>>&amp;"/>"""
        id += 1
        Seq(msOut, msIn)
      } else Seq.empty

    val argsBlock = (configArgs ++ memArgs ++ mfpgaArgs).mkString("\n")

    s"""<?xml version="1.0" encoding="UTF-8"?>
<root versionMajor="1" versionMinor="9">
  <kernel name="${kernelName}" language="ip" type="user_managed" vlnv="epfl.ch:hardcilk:${vlnvName}:1.0" attributes="" preferredWorkGroupSizeMultiple="0" workGroupSize="1" hwControlProtocol="user_managed">
    <ports>
${portsBlock}
    </ports>
    <args>
${argsBlock}
    </args>
  </kernel>
</root>
"""
  }

  // --- conn_u55c.cfg ---------------------------------------------------------

  private[SoftwareUtil] def renderConnCfg(
      descriptor: FullSysGenDescriptor,
      numMasters: Int,
      kernelName: String,
      hasWatcher: Boolean,
      ramaPortIndices: Set[Int],
      enableRamaStriping: Boolean,
      ramaHookPath: Option[String] = None
  ): String = {

    val freqHz = descriptor.targetFrequency.toLong * 1000000L
    require(
      numMasters >= 1,
      s"expected at least one AXI master, got $numMasters"
    )

    // The `.N` suffix is the (standard Vivado) HBM pseudo-channel the master binds
    // to within its mapped range; it need not be unique across masters.
    //
    // Default (no watcher): every master maps to the full U55C HBM range. Keep the
    // last master on PC0 (shortest path to HBM0) and give the others nonzero PCs.
    val lockPortIndex = numMasters - 1
    def fullRangeSwitchIndex(i: Int): Int =
      if (i == lockPortIndex) 0 else i + 1

    // With a watcher: the TWO topmost masters are the watcher's telemetry ports and
    // split the exclusive top half HBM[16:31] into two 4 GB windows. Port A
    // (index numMasters-2, m_axi_gmem) -> HBM[16:23] pinned to PC16; port B
    // (index numMasters-1, m_axi_gmem1) -> HBM[24:31] pinned to PC24. Bursts
    // alternate between them so telemetry is spread evenly across both windows.
    // Every compute/server master is confined to HBM[0:15] so the watcher's traffic
    // never crosses theirs.
    val watcherPortB = numMasters - 1 // m_axi_gmem1 -> HBM[24:31]
    val watcherPortA = numMasters - 2 // m_axi_gmem  -> HBM[16:23]
    val nonWatcherPortCount = if (hasWatcher) numMasters - 2 else numMasters
    val invalidRamaPorts = ramaPortIndices.filter(i => i < 0 || i >= nonWatcherPortCount)
    require(
      invalidRamaPorts.isEmpty,
      s"selective RAMA ports must be non-watcher m_axi indices in [0, ${nonWatcherPortCount - 1}], got ${invalidRamaPorts.toSeq.sorted.mkString(", ")}"
    )
    def baseSpTag(i: Int): String =
      if (hasWatcher) {
        if (i == watcherPortB) "HBM[24:31].24"
        else if (i == watcherPortA) "HBM[16:23].16"
        else s"HBM[0:15].${i % 16}"
      } else s"HBM[0:31].${fullRangeSwitchIndex(i)}"
    def spTag(i: Int): String =
      baseSpTag(i) + (if (ramaPortIndices.contains(i)) ".RAMA" else "")

    // Load-bearing ownership invariant. Keep this as an explicit validation even
    // though the mappings above are generated here: a future custom-placement
    // feature must fail loudly instead of silently allowing compute traffic onto
    // the watcher's exclusive HBM[16:31] telemetry windows.
    if (hasWatcher) {
      require(numMasters >= 2, "watcher-enabled design requires two telemetry AXI masters")
      val computeMappings = (0 until watcherPortA).map(i => i -> spTag(i))
      val conflicts = computeMappings.filterNot(_._2.startsWith("HBM[0:15]."))
      require(
        conflicts.isEmpty,
        s"watcher requires exclusive control of HBM[16:31], but compute mappings conflict: ${conflicts.mkString(", ")}"
      )
      require(
        spTag(watcherPortA).startsWith("HBM[16:23].") &&
          spTag(watcherPortB).startsWith("HBM[24:31]."),
        s"watcher telemetry masters must exclusively map to HBM[16:23] and HBM[24:31]"
      )
    }

    val spLines = (0 until numMasters)
      .map { i =>
        s"sp=${kernelName}.${portName(i)}:${spTag(i)}"
      }
      .mkString("\n")

    if (ramaPortIndices.nonEmpty) {
      println(
        s"[KernelXmlTemplate] Native Vitis RAMA enabled on ${ramaPortIndices.size} port(s): " +
          ramaPortIndices.toSeq.sorted.map(portName).mkString(", ")
      )
    }

    // Native Vitis RAMA instances live in a generated child of the HBM memory
    // subsystem. The postSysLink hook wraps generate_target so it can update the
    // final generated RAMA artifacts before Vitis configures the IP-cache and
    // synthesis runs.
    val linkHookBlock = ramaHookPath
      .map(path => s"[linkhook]\ncustom=postSysLink,$path\n\n")
      .getOrElse("")

    s"""[connectivity]
nk=${kernelName}:1:${kernelName}

${spLines}

[clock]
freqHz=${freqHz}:${kernelName}.clock

${linkHookBlock}[vivado]
prop=run.impl_1.strategy=Performance_HighUtilSLRs
"""
  }

  /** Pre-synthesis customization for the RAMA instances inserted by `.RAMA`.
    * Vitis 2024.1 creates the HBM subsystem's child block design after the
    * postSysLink hook and regenerates it during `generate_target`, so changing
    * the child BD directly from that hook is either too early or overwritten.
    * Wrap `generate_target` and patch its final XCI/VHDL products before Vitis
    * configures the IP cache and OOC synthesis runs. Keep every parameter
    * explicit so the linked XCI is directly auditable and matches Mahfouz's
    * configuration.
    */
  private[SoftwareUtil] def renderRamaXrtHookTcl(
      expectedRamaCount: Int,
      striped: Boolean,
      memoryCount: Int
  ): String = {
    require(expectedRamaCount > 0)
    require(memoryCount >= 1 && memoryCount <= 32)
    val interleave = if (striped) "per_memory" else "none"
    val fragmentBytes = if (striped) 64 else 128
    val queueDepth = if (striped) 256 else 128
    val configuredMemoryCount = if (striped) memoryCount else 4

    s"""# Generated by HardCilk: configure native Vitis RAMA after generate_target.
proc hc_replace_file {hc_path hc_map} {
  set hc_in [open $$hc_path r]
  set hc_data [read $$hc_in]
  close $$hc_in
  set hc_new [string map $$hc_map $$hc_data]
  if {$$hc_new ne $$hc_data} {
    set hc_out [open $$hc_path w]
    puts -nonewline $$hc_out $$hc_new
    close $$hc_out
  }
}

# Keep the generated HBM child-BD metadata consistent with its XCI products.
# The child design is read-only when opened through the parent, hence this
# narrow, validated textual update after generation.
proc hc_patch_hmss_bd {hc_hmss_bd} {
  set hc_in [open $$hc_hmss_bd r]
  set hc_data [read $$hc_in]
  close $$hc_in
  set hc_existing [regexp -all {"G_MEM_INTERLEAVE_TYPE"} $$hc_data]
  if {$$hc_existing == $expectedRamaCount} {
    return
  }
  if {$$hc_existing != 0} {
    error "HardCilk found $$hc_existing partial RAMA configurations in $$hc_hmss_bd"
  }
  set hc_out_lines {}
  set hc_rama_candidate 0
  set hc_rama_cell 0
  set hc_patched 0
  foreach hc_line [split $$hc_data "\\n"] {
    if {[regexp {^\\s+"rama_[0-9]+": \\{$$} $$hc_line]} {
      set hc_rama_candidate 1
      set hc_rama_cell 0
    }
    if {$$hc_rama_candidate && [string first {"vlnv": "xilinx.com:ip:rama:1.1"} $$hc_line] >= 0} {
      set hc_rama_cell 1
    }
    lappend hc_out_lines $$hc_line
    if {$$hc_rama_cell && [regexp {^(\\s+)"parameters": \\{$$} $$hc_line -> hc_indent]} {
      set hc_param_indent "$${hc_indent}  "
      lappend hc_out_lines "$${hc_param_indent}\\\"G_AXI_LITE\\\": {\\\"value\\\": \\\"0\\\"},"
      lappend hc_out_lines "$${hc_param_indent}\\\"G_FRAGMENT_SIZE_BYTES\\\": {\\\"value\\\": \\\"$fragmentBytes\\\"},"
      lappend hc_out_lines "$${hc_param_indent}\\\"G_MEM_COUNT\\\": {\\\"value\\\": \\\"$configuredMemoryCount\\\"},"
      lappend hc_out_lines "$${hc_param_indent}\\\"G_MEM_INTERLEAVE_TYPE\\\": {\\\"value\\\": \\\"$interleave\\\"},"
      lappend hc_out_lines "$${hc_param_indent}\\\"G_REORDER_QUEUE_DEPTH\\\": {\\\"value\\\": \\\"$queueDepth\\\"},"
      lappend hc_out_lines "$${hc_param_indent}\\\"ID_WIDTH\\\": {\\\"value\\\": \\\"1\\\"},"
      incr hc_patched
      set hc_rama_candidate 0
      set hc_rama_cell 0
    }
  }
  if {$$hc_patched != $expectedRamaCount} {
    error "HardCilk expected to patch $expectedRamaCount RAMA cells in $$hc_hmss_bd, patched $$hc_patched"
  }
  set hc_out [open $$hc_hmss_bd w]
  puts -nonewline $$hc_out [join $$hc_out_lines "\\n"]
  close $$hc_out
}

proc hc_patch_generated_ramas {} {
  set hc_project_dir [get_property DIRECTORY [current_project]]
  set hc_rama_xcis [glob -nocomplain [file join $$hc_project_dir *.gen * bd * ip *hmss* bd_* ip * *rama*.xci]]
  if {[llength $$hc_rama_xcis] == 0} {
    return
  }
  if {[llength $$hc_rama_xcis] != $expectedRamaCount} {
    error "HardCilk expected $expectedRamaCount generated RAMA XCI files, found [llength $$hc_rama_xcis]: $$hc_rama_xcis"
  }
  set hc_hmss_bds [glob -nocomplain [file join $$hc_project_dir *.gen * bd * ip *hmss* bd_0 *.bd]]
  if {[llength $$hc_hmss_bds] != 1} {
    error "HardCilk expected one generated HBM subsystem BD, found [llength $$hc_hmss_bds]: $$hc_hmss_bds"
  }
  hc_patch_hmss_bd [lindex $$hc_hmss_bds 0]

  set hc_xci_map [list \\
    {"G_REORDER_QUEUE_DEPTH": [ \\{ "value": "128"} {"G_REORDER_QUEUE_DEPTH": [ \\{ "value": "$queueDepth"} \\
    {"G_FRAGMENT_SIZE_BYTES": [ \\{ "value": "128"} {"G_FRAGMENT_SIZE_BYTES": [ \\{ "value": "$fragmentBytes"} \\
    {"G_MEM_INTERLEAVE_TYPE": [ \\{ "value": "none"} {"G_MEM_INTERLEAVE_TYPE": [ \\{ "value": "$interleave"} \\
    {"G_MEM_COUNT": [ \\{ "value": "4"} {"G_MEM_COUNT": [ \\{ "value": "$configuredMemoryCount"}]
  set hc_vhdl_map [list \\
    {G_FRAGMENT_SIZE_BYTES=128} {G_FRAGMENT_SIZE_BYTES=$fragmentBytes} \\
    {G_MEM_COUNT=4} {G_MEM_COUNT=$configuredMemoryCount} \\
    {G_MEM_INTERLEAVE_TYPE=none} {G_MEM_INTERLEAVE_TYPE=$interleave} \\
    {G_REORDER_QUEUE_DEPTH=128} {G_REORDER_QUEUE_DEPTH=$queueDepth} \\
    {G_FRAGMENT_SIZE_BYTES => 128} {G_FRAGMENT_SIZE_BYTES => $fragmentBytes} \\
    {G_MEM_COUNT => 4} {G_MEM_COUNT => $configuredMemoryCount} \\
    {G_MEM_INTERLEAVE_TYPE => "none"} {G_MEM_INTERLEAVE_TYPE => "$interleave"} \\
    {G_REORDER_QUEUE_DEPTH => 128} {G_REORDER_QUEUE_DEPTH => $queueDepth}]
  foreach hc_xci $$hc_rama_xcis {
    hc_replace_file $$hc_xci $$hc_xci_map
    set hc_synth_vhdl [file join [file dirname $$hc_xci] synth "[file rootname [file tail $$hc_xci]].vhd"]
    if {[file exists $$hc_synth_vhdl]} {
      hc_replace_file $$hc_synth_vhdl $$hc_vhdl_map
    }
  }

  set hc_rama_ips [get_ips -quiet -all *rama*]
  if {[llength $$hc_rama_ips] != $expectedRamaCount} {
    error "HardCilk expected $expectedRamaCount RAMA IPs, found [llength $$hc_rama_ips]: $$hc_rama_ips"
  }
  foreach hc_rama $$hc_rama_ips {
    set_property -dict [list \\
      CONFIG.ID_WIDTH {1} \\
      CONFIG.G_AXI_LITE {0} \\
      CONFIG.G_FRAGMENT_SIZE_BYTES {$fragmentBytes} \\
      CONFIG.G_MEM_COUNT {$configuredMemoryCount} \\
      CONFIG.G_MEM_INTERLEAVE_TYPE {$interleave} \\
      CONFIG.G_REORDER_QUEUE_DEPTH {$queueDepth} \\
    ] $$hc_rama
  }
  puts "HardCilk configured [llength $$hc_rama_ips] generated RAMA IP(s): interleave=$interleave memories=$configuredMemoryCount fragment=$fragmentBytes queue=$queueDepth"
}

# There is no Vitis custom hook after generate_target. Wrap the command while
# this VPL process is alive so each parent/sub-design generation is followed by
# the RAMA update, before IP-cache checks and OOC synthesis are created.
if {[llength [info commands hc_generate_target_original]] == 0} {
  rename generate_target hc_generate_target_original
  proc generate_target {args} {
    set hc_result [uplevel 1 [list hc_generate_target_original {*}$$args]]
    hc_patch_generated_ramas
    return $$hc_result
  }
}
puts "HardCilk installed generated-RAMA configuration hook"
"""
  }
}
