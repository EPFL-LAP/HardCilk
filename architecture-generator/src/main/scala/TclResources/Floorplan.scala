package TclResources

import Descriptors._

/**
  * Occupancy-aware SLR floorplan generator for the XDMA/PCIe flow.
  *
  * Replaces the static XDC that put the whole kernel in one *soft* pblock
  * spanning SLR1+SLR2. That was fine while the kernel fitted in a single die, and
  * catastrophic once it did not: the placer did not respond by moving whole PEs
  * into SLR2, it cut PEs in half across the die boundary, and a PE's internal
  * critical path is not built to cross an SLL. Most failing paths ended up
  * being split PEs talking to themselves across the die.
  *
  * The rule implemented here is therefore "never straddle": every PE is assigned
  * whole, to a whole SLR, with `IS_SOFT FALSE`, because spilling across the
  * boundary is precisely the failure being removed.
  */
object Floorplan {

  /**
    * A task with more PEs than this has its instances split between the hub SLR
    * and the spill SLR. At or below it the task stays with the hub logic: moving
    * a handful of PEs to another die buys less than the SLL crossings cost.
    */
  val splitThreshold: Int = 4

  /**
    * ===========================================================================
    * PER-PE AREA ESTIMATION
    * ===========================================================================
    *
    * The estimate is read out of the Vitis HLS synthesis results that are
    * already on disk when the generator runs: `build_hls.sh` has run before the
    * architecture generator, and every module Vitis HLS emits carries its own
    * post-synthesis resource numbers in a `CORE_GENERATION_INFO` attribute on
    * the module declaration:
    *
    *   (* CORE_GENERATION_INFO="applyFn_applyFn,hls_ip_2024_1,{ ...
    *      HLS_SYN_FF=32397,HLS_SYN_LUT=3194,HLS_SYN_MEM=6,HLS_SYN_DSP=0,...}" *)
    *
    * THIS REPLACES A BYTE-COUNT PROXY, AND WHY
    * -----------------------------------------
    * The previous basis was the byte size of the PE's generated Verilog, scaled
    * by a constant fitted on one design. It failed the moment the compiler
    * gained a transformation that rewrites a deep pipeline into an HLS DATAFLOW
    * region: that trades ~100 stages of pipeline registers for a BRAM FIFO, so
    * the PE gets much SMALLER in flops while emitting many more, smaller `.v`
    * files. Measured per applyFn PE of pageRank_overlap:
    *
    *   flat pipeline   FF 113834  LUT  7414  BRAM18 7   -> byte proxy said  8093 CLB
    *   dataflow        FF  17541  LUT 13148  BRAM18 51  -> byte proxy said 13689 CLB
    *
    * i.e. the proxy called the dataflow PE 1.7x BIGGER when it has 6.5x fewer
    * flops, printed >100% occupancy for it, and would have steered users off the
    * better design. Source size is not area.
    *
    * WHAT IS SUMMED
    * --------------
    * `peHDLPath` for a collapsed OVERLAP wrapper is a directory holding SEVERAL
    * HLS modules -- applyFn, applyFn_reentry1, applyFn_reentry1_cont0/1,
    * applyFn_exit1 -- each instantiated exactly once by the hand-written
    * wrapper. The PE's area is therefore the SUM of the attribute over every
    * `.v` in the directory that carries one.
    *
    * UNDER-COUNTING, AND HOW IT IS COMPENSATED
    * -----------------------------------------
    * Files with no attribute contribute nothing to that sum: the hand-written
    * `*_overlap_wrapper.v` itself and everything it defines (the memory reader,
    * the context cache, the skid buffers, the arbiters, the state FIFOs), plus
    * the `*_regslice_both`, `*_gmem_m_axi` and `*_control_s_axi` helpers.
    *
    * That omission is charged back as a FIXED ALLOWANCE PER WRAPPED PE, not as a
    * multiplier, because the wrapper is a structurally fixed block: its size is
    * set by the stream count, the context-cache depth and the bus width, not by
    * how big the HLS core inside it happens to be. Multiplying instead would
    * penalise exactly the case that motivated this rewrite -- a DATAFLOW core
    * legitimately spending more LUTs would have its wrapper scaled up with it.
    *
    * Measured, `report_utilization -slr` on the routed pageRank_overlap result
    * whose SLR2 holds four applyFn PEs and nothing else: 72514 LUTs and 523033
    * flops for four PEs, i.e. 18128 LUT and 130758 FF each, against per-PE HLS
    * attribute totals of 7414 LUT and 113834 FF. So the wrapper is worth about
    * 10700 LUTs and about 17000 flops per PE.
    *
    * Only the LUT allowance is charged. The flop side is left at the raw HLS
    * total because the wrapper's +17k flops are cancelled by Vivado
    * SRL-compressing HLS's deep pipeline registers: the wrapper's state FIFOs
    * are `(* ram_style = "block" *)` and cost BRAM rather than flops, and on
    * randomWalk_overlap -- three ~70-stage pipelines -- the whole kernel routed
    * to 144897 flops against an HLS total of 174671, i.e. 17% UNDER the HLS
    * estimate. Charging a wrapper flop allowance on top of that would compound
    * an overestimate. See the residuals noted on [[clbPackingFactor]].
    */
  private val wrapperLutPerPe: Long = 10700L

  /**
    * Name suffix identifying the hand-written OVERLAP wrapper inside a
    * `peHDLPath`. Its presence is what makes [[wrapperLutPerPe]] apply: a plain
    * spawner PE's directory holds only its HLS module and a register slice, and
    * must not be charged for a wrapper it does not have.
    */
  private val wrapperFileSuffix: String = "_overlap_wrapper.v"

  /**
    * CLBs actually consumed per "full" CLB of demand, where demand is
    * `max(LUT/8, FF/16)` on xcu55c (one CLB = 8 LUTs + 16 flip-flops).
    *
    * Placement never fills a CLB: control-set fragmentation, carry chains and
    * routability all spread logic out. Measured kernel-wide on routed results:
    *
    *   pageRank_overlap   82249 CLB / max(25312, 59285) = 1.387
    *   randomWalk_overlap 12056 CLB / max( 3145,  9056) = 1.331
    *
    * RESIDUAL ERROR OF THE WHOLE ESTIMATE, against measured builds:
    *
    *   pageRank_overlap (flat, the build the SLR2 numbers above come from)
    *     9605 CLB/PE estimated vs 10016 measured        -4%
    *   randomWalk_overlap
    *     14683 CLB for its one applyFn PE, against 12056 CLB measured for the
    *     WHOLE of SLR1 (that PE, two spawner PEs and all the hub logic)  +22%
    *
    * The randomWalk overshoot is HLS's own flop estimate for very deep
    * pipelines, which Vivado then SRL-compresses; there is no way to predict
    * that here. It errs toward warning too early, which is the safe direction
    * for a guard whose failure mode is an unplaceable floorplan.
    */
  private val clbPackingFactor: Double = 1.35

  /**
    * Fallback per-PE area when no `CORE_GENERATION_INFO` can be read -- an empty
    * or missing `peHDLPath`, an unsynthesised tree, or hand-written RTL that
    * never went through Vitis HLS. Deliberately pessimistic: it is roughly the
    * size of a real compute PE in this design family, so a design that would
    * not fit still trips the guard rather than sliding through unwarned.
    */
  private val fallbackClbPerPe: Int = 6000

  /** `HLS_SYN_*` totals for one PE, and whether they could be read at all. */
  private case class PeArea(
      clb: Int,
      ff: Long,
      lut: Long,
      mem: Long,
      modules: Int,
      wrapped: Boolean,
      /** V80: set when the figures are measured ones from HC_LUTRAM_PROFILE. */
      measured: String = ""
  ) {
    /** Which basis the numbers came from, for the printed report. */
    def basis: String =
      if (measured.nonEmpty) measured
      else if (modules <= 0) "FALLBACK, no HLS data"
      else s"HLS, $modules module(s)${if (wrapped) " + OVERLAP wrapper allowance" else ""}"
  }

  /**
    * Reconstruct the Chisel instance names of a task's PEs.
    *
    * Only used when the elaborated names were not handed in. `HardCilk.peMap` is
    * built from `HardCilkBuilder.defineBlueprint`'s `peFactories`, which is
    * `taskDescriptors.filter(_.peHDLPath.nonEmpty).map(t => t.name -> ...).toMap`.
    * Chisel names its elements `peMap_<entry>_2_<pe>`, where `<entry>` is the
    * entry's position in the Map's iteration order and `_2` is the value side of
    * the (key, value) tuple. Rebuilding a Map with the same keys inserted in the
    * same order reproduces that iteration order exactly.
    */
  def reconstructPeInstanceNames(desc: FullSysGenDescriptor): Seq[(String, Seq[String])] = {
    val tasks = desc.taskDescriptors.filter(_.peHDLPath.nonEmpty)
    val byName = tasks.map(t => t.name -> t).toMap
    byName.keys.toSeq.zipWithIndex.map { case (taskName, entryIdx) =>
      val n = byName(taskName).numProcessingElements
      taskName -> (0 until n).map(i => s"peMap_${entryIdx}_2_$i")
    }
  }

  /** Matches the whole `CORE_GENERATION_INFO="..."` payload of one module. */
  private val coreGenInfo = """CORE_GENERATION_INFO\s*=\s*"([^"]*)"""".r

  /** Matches one `HLS_SYN_<KEY>=<value>` pair inside that payload. */
  private val hlsSynKv = """HLS_SYN_([A-Z]+)=(-?\d+)""".r

  /**
    * Per-PE area for `task`, summed over every Vitis-HLS module under
    * `peHDLPath`. Never throws: any unreadable file, missing directory or
    * absent attribute simply contributes nothing, and a PE with no attribute at
    * all falls back to [[fallbackClbPerPe]]. See the comment block above for
    * what is summed, what is missed and how the inflation factors compensate.
    */
  private def estimateClbPerPe(task: TaskDescriptor): PeArea = {
    val dir = new java.io.File(task.peHDLPath)
    val files =
      if (task.peHDLPath.nonEmpty && dir.isDirectory)
        Option(dir.listFiles())
          .getOrElse(Array.empty[java.io.File])
          .filter(f => f.isFile && f.getName.endsWith(".v"))
          .sortBy(_.getName)
          .toSeq
      else Seq.empty

    var ff, lut, mem = 0L
    var modules = 0
    files.foreach { f =>
      val text =
        try {
          val src = scala.io.Source.fromFile(f, "ISO-8859-1")
          try src.mkString
          finally src.close()
        } catch { case _: Throwable => "" }
      // One attribute per generated module; a directory may hold several.
      coreGenInfo.findAllMatchIn(text).foreach { m =>
        modules += 1
        hlsSynKv.findAllMatchIn(m.group(1)).foreach { kv =>
          val v = try kv.group(2).toLong catch { case _: Throwable => 0L }
          kv.group(1) match {
            case "FF"  => ff += math.max(0L, v)
            case "LUT" => lut += math.max(0L, v)
            case "MEM" => mem += math.max(0L, v)
            case _     => // CLOCK, LAT, TPT, DSP: not CLB demand
          }
        }
      }
    }

    val wrapped = files.exists(_.getName.endsWith(wrapperFileSuffix))

    if (modules == 0) PeArea(fallbackClbPerPe, 0L, 0L, 0L, 0, wrapped)
    else {
      // One CLB on this device = 8 LUTs + 16 flip-flops; a PE needs whichever
      // of the two runs out first, then spread by the measured packing factor.
      val lutClb = (lut + (if (wrapped) wrapperLutPerPe else 0L)) / 8.0
      val ffClb = ff / 16.0
      val clb = math.max(1L, (math.max(lutClb, ffClb) * clbPackingFactor).round).toInt
      PeArea(clb, ff, lut, mem, modules, wrapped)
    }
  }

  /** How a single task's PE instances were divided between the two dice. */
  case class TaskSplit(
      taskName: String,
      hubPes: Seq[String],
      spillPes: Seq[String],
      clbPerPe: Int,
      /** How `clbPerPe` was obtained, and the raw HLS totals behind it. */
      basis: String = "",
      hlsFf: Long = 0L,
      hlsLut: Long = 0L,
      hlsMem: Long = 0L,
      /** Multi-spill devices only: PEs placed on each further spill SLR. */
      extraSpillPes: Seq[(String, Seq[String])] = Seq.empty
  )

  /** The whole assignment, plus the numbers the caller reports and warns on. */
  case class Plan(
      device: DeviceProfile,
      splits: Seq[TaskSplit],
      /** true when nothing was split and the hub SLR alone cannot hold the design. */
      widenedHub: Boolean
  ) {
    /**
      * Every PE off the hub die. On a multi-spill device this includes the
      * further spill dice: they share the RTL's single spill reset leaf, which
      * the floorplan pins to `spillSlr` (adjacent to them).
      */
    def spillPes: Seq[String] = splits.flatMap(_.spillPes) ++ splits.flatMap(_.extraSpillPes.flatMap(_._2))
    def hubClb: Int = splits.map(s => s.hubPes.length * s.clbPerPe).sum + device.clbHubBase
    def spillClb: Int = splits.map(s => s.spillPes.length * s.clbPerPe).sum
    def hubFraction: Double = hubClb.toDouble / device.slr(device.hubSlr).clbCount
    def spillFraction: Double = spillClb.toDouble / device.slr(device.spillSlr).clbCount

    /** PEs on each further spill SLR (multi-spill devices), merged over tasks. */
    def extraSpill: Seq[(String, Seq[String])] =
      device.extraSpillSlrs.map(slr => slr -> splits.flatMap(_.extraSpillPes.toMap.getOrElse(slr, Seq.empty)))

    /** Estimated CLBs per SLR, including the reserved (shell) share. */
    def slrClb(slr: String): Int = {
      val pes = splits.map { s =>
        val n =
          if (slr == device.hubSlr) s.hubPes.length
          else if (slr == device.spillSlr) s.spillPes.length
          else s.extraSpillPes.toMap.getOrElse(slr, Seq.empty).length
        n * s.clbPerPe
      }.sum
      pes + device.reservedClb.getOrElse(slr, 0) + (if (slr == device.hubSlr) device.clbHubBase else 0)
    }
  }

  /**
    * Decide, for every task, which of its PE instances go to the spill SLR.
    *
    * A task with more than [[splitThreshold]] PEs is divided evenly: `n/2` stay
    * with the hub logic and `n - n/2` move to the spill die. Schedulers,
    * allocators and notifiers are never moved -- they fan out to every PE, so the
    * middle die keeps both groups one hop away.
    */
  def plan(
      desc: FullSysGenDescriptor,
      peInstanceNames: Seq[(String, Seq[String])]
  ): Plan = {
    val device = DeviceProfile.forModel(desc.fpgaModel)
    if (device.extraSpillSlrs.nonEmpty) return planMultiSpill(desc, device, peInstanceNames)
    val byName = desc.taskDescriptors.map(t => t.name -> t).toMap

    val splits = peInstanceNames.map { case (taskName, instances) =>
      val task = byName(taskName)
      val n = instances.length
      val area = estimateClbPerPe(task)
      val (hub, spill) =
        if (n > splitThreshold) instances.splitAt(n / 2) else (instances, Seq.empty[String])
      TaskSplit(taskName, hub, spill, area.clb, area.basis, area.ff, area.lut, area.mem)
    }

    val provisional = Plan(device, splits, widenedHub = false)
    // Nothing was big enough to split, but the estimate says the hub die alone
    // cannot hold the design. Confining it there with a hard pblock would fail
    // placement outright, so fall back to the old permissive shape (both dice,
    // soft) rather than emit a floorplan that cannot place.
    val widen = provisional.spillPes.isEmpty &&
      provisional.hubFraction > device.occupancyWarnFraction
    provisional.copy(widenedHub = widen)
  }

  /** Generation-time occupancy warnings. Returns the lines it printed. */
  def report(plan: Plan): Seq[String] = {
    val d = plan.device
    val pct = (f: Double) => f"${f * 100}%.1f%%"
    val lines = scala.collection.mutable.ArrayBuffer[String]()

    lines += s"[Floorplan] device ${d.model}: ${d.slrs.length} SLR(s), hub=${d.hubSlr}, " +
      s"spill=${d.spillSlr}, memory side=${d.memSideSlr}"
    plan.splits.foreach { s =>
      lines += s"[Floorplan] task ${s.taskName}: ${s.hubPes.length} PE(s) in ${d.hubSlr}, " +
        s"${s.spillPes.length} in ${d.spillSlr} (~${s.clbPerPe} CLB each, estimated)"
      lines += s"[Floorplan]   basis ${s.basis}: HLS_SYN_FF=${s.hlsFf}, HLS_SYN_LUT=${s.hlsLut}, " +
        s"HLS_SYN_MEM=${s.hlsMem} per PE" +
        (if (s.basis.startsWith("HLS"))
           f" (max(LUT/8, FF/16) x $clbPackingFactor%.2f packing" +
             (if (s.basis.contains("wrapper")) s", +$wrapperLutPerPe LUT for the un-annotated wrapper"
              else "") +
             "; HLS_SYN_MEM excludes the wrapper's block-RAM FIFOs)"
         else " -- no CORE_GENERATION_INFO found under peHDLPath, using the fixed fallback")
    }
    lines += s"[Floorplan] estimated occupancy: ${d.hubSlr} ${plan.hubClb} CLB " +
      s"(${pct(plan.hubFraction)}), ${d.spillSlr} ${plan.spillClb} CLB (${pct(plan.spillFraction)}); " +
      s"warning threshold ${pct(d.occupancyWarnFraction)}"

    Seq((d.hubSlr, plan.hubFraction), (d.spillSlr, plan.spillFraction)).foreach {
      case (slrName, frac) =>
        if (frac > 1.0)
          lines += s"[Floorplan] ERROR-LEVEL WARNING: $slrName is estimated at ${pct(frac)} of its " +
            s"CLBs -- above 100%. This floorplan will very likely fail place_design. Reduce the " +
            s"design or add PEs to another SLR."
        else if (frac > d.occupancyWarnFraction)
          lines += s"[Floorplan] WARNING: $slrName is estimated at ${pct(frac)} of its CLBs, above " +
            s"the ${pct(d.occupancyWarnFraction)} threshold. Measured on this device: 67.7% closed " +
            s"at +0.028 ns, 80.0% failed at -1.909 ns."
    }

    if (plan.widenedHub)
      lines += s"[Floorplan] NOTE: no task has more than $splitThreshold PEs, so nothing was split, " +
        s"but ${d.hubSlr} alone is estimated over threshold. Falling back to a soft pblock spanning " +
        s"${d.hubSlr}+${d.spillSlr} for the whole kernel."

    lines.foreach(println)
    lines.toSeq
  }

  /**
    * Emit the floorplan XDC.
    *
    * Cell paths are rooted at `design_1_i`: the synthesis top is `top_pcie`
    * (`src/main/resources/top.v`), which instantiates `design_1` directly rather
    * than going through a generated BD wrapper. The filters start `*design_1_i/`
    * with no slash between the wildcard and the instance name -- `design_1_i` is
    * the top-level instance, so a wildcard-then-slash prefix would demand a
    * leading slash that is not in the name and every pblock would silently match
    * zero cells.
    *
    * @param rawMode `--raw-hbm-ports`, which adds aggregating SmartConnects that
    *                belong on the design side of the crossing
    */
  def xdc(
      descriptorName: String,
      plan: Plan,
      rawMode: Boolean,
      peIfaceBuffers: Seq[(String, Seq[String])] = Seq.empty
  ): String = {
    val d = plan.device
    val sb = new StringBuilder

    val parentSlrs =
      if (plan.widenedHub) Seq(d.hubSlr, d.spillSlr) else Seq(d.hubSlr)
    val parentSoft = plan.widenedHub

    sb ++= s"""# Generated by HardCilk -- floorplan for the XDMA/PCIe flow.
# Device profile: ${d.model} (TclResources.DeviceProfile).
#
# ${d.memSideSlr} holds the PCIe hard block, its GTY quads and the HBM AXI interfaces, all
# of which are fixed there by the device. The generated design is placed above
# it and reaches HBM through the rs0/rs1 register slice pair.
#
# WHY WHOLE PEs GO TO WHOLE SLRs
# ------------------------------
# The previous floorplan put the entire kernel in ONE soft pblock spanning
# ${d.hubSlr}+${d.spillSlr}. Once the kernel outgrew a single die the placer did not respond
# by moving whole PEs across -- it CUT PEs IN HALF across the SLR boundary, and
# most failing paths became a split PE talking to itself across the die. A PE's
# internal critical path is not built to cross an SLL.
#
# So each PE is assigned whole, to one SLR, hard. Tasks with more than
# ${splitThreshold} PEs are split evenly; smaller tasks stay with the hub logic. Schedulers,
# allocators and notifiers stay in ${d.hubSlr} -- they fan out to every PE, so the
# middle die keeps both groups one hop away.
#
# NOTE ON FORM: the kernel is assigned as ONE hierarchical cell, then the spilled
# PEs are re-assigned underneath it (the nested-pblock idiom). Assigning leaf
# cells with `=~ */peMap_x_y_z/*` filters instead does NOT work here: Vivado
# collapses the larger leaf set back up to the parent 'inst' cell, which then
# re-claims the PEs and silently leaves the ${d.spillSlr} pblock with 0 cells. That was
# verified against this netlist -- do not "simplify" this back to leaf filters
# without re-checking pblock membership. Note also that the per-PE filters below
# have NO trailing /*: they select the hierarchical cell itself.
#
# WHY THE PARENT FILTER HAS NO /inst
# ----------------------------------
# The kernel is brought into the block design as a MODULE REFERENCE
# (`create_bd_cell -type module -reference ${descriptorName} ${descriptorName}_0`),
# and synthesis runs with the default `-flatten_hierarchy rebuilt`: the netlist is
# flattened and the hierarchy is then REBUILT, which re-parents a large fraction
# of the kernel's cells from `${descriptorName}_0/inst/...` up to the wrapper level
# `${descriptorName}_0/...`, as siblings of `inst` -- both flat registers and whole
# rebuilt subtrees such as `design_1_i/${descriptorName}_0/peMap_2_2_1/pe/...`.
# A filter ending in `/inst` matches NONE of those, so they are unconstrained and
# the placer drops many of them into ${d.memSideSlr}. Vivado reports it at placer Phase 1.2:
#   WARNING: [Place 30-439] A collection of cells which have restrictive placement
#   or routing requirements has some cells within some area groups and some cells
#   outside ... The area groups involved are "pblock_user_kernel"
# The parent filter therefore selects the BD wrapper cell `${descriptorName}_0`
# itself, which is never dissolved, and so owns `inst` AND every hoisted sibling.
# Do NOT append `/inst` (or `/*`) to it.
#
# For the same reason each spilled PE is matched in BOTH forms -- `*/inst/<pe>`
# and `*/${descriptorName}_0/<pe>` -- in a single filter: which of the two exists
# depends on whether `rebuilt` hoisted that PE, and only one of them will match.
#
# Estimated occupancy at generation time (coarse, from the Vitis HLS
# CORE_GENERATION_INFO attributes under each task's peHDLPath):
#   ${d.hubSlr} ${plan.hubClb} CLB (${f"${plan.hubFraction * 100}%.1f"}%), ${d.spillSlr} ${plan.spillClb} CLB (${f"${plan.spillFraction * 100}%.1f"}%)
"""

    sb ++= s"""
# ---------------------------------------------------------------------------
# Whole kernel -> ${parentSlrs.mkString("+")}${if (plan.spillPes.nonEmpty) s", then override ${plan.spillPes.length} PE(s) into ${d.spillSlr} below." else "."}
# ---------------------------------------------------------------------------
create_pblock pblock_user_kernel
"""
    parentSlrs.foreach { s =>
      sb ++= s"resize_pblock [get_pblocks pblock_user_kernel] -add $s\n"
    }
    sb ++= s"""add_cells_to_pblock \\
    [get_pblocks pblock_user_kernel] \\
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/${descriptorName}_0}]
set_property IS_SOFT ${if (parentSoft) "TRUE" else "FALSE"} [get_pblocks pblock_user_kernel]
"""

    if (rawMode) {
      sb ++= """
# Raw-export aggregation SmartConnects: design side of the crossing, with the kernel.
add_cells_to_pblock \
    [get_pblocks pblock_user_kernel] \
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/smartconnect_hbm_*}]
"""
    }

    if (plan.spillPes.nonEmpty) {
      val perTask = plan.splits
        .filter(_.spillPes.nonEmpty)
        .map(s => s"${s.spillPes.length} of ${s.taskName}")
        .mkString(", ")
      sb ++= s"""
# ---------------------------------------------------------------------------
# ${plan.spillPes.length} PE(s) -> ${d.spillSlr} ($perTask).
# Assigned after the parent so these win ownership.
# ---------------------------------------------------------------------------
create_pblock pblock_kernel_${d.spillSlr.toLowerCase}
resize_pblock [get_pblocks pblock_kernel_${d.spillSlr.toLowerCase}] -add ${d.spillSlr}
"""
      plan.spillPes.foreach { pe =>
        sb ++= s"""add_cells_to_pblock \\
    [get_pblocks pblock_kernel_${d.spillSlr.toLowerCase}] \\
    [get_cells -hierarchical -filter {NAME =~ *inst/$pe || NAME =~ *${descriptorName}_0/$pe}]
"""
      }
      // The spilled PEs' interface queues have to follow them across.
      //
      // HBMInterconnect puts a Queue(2) on every channel of every PE memory
      // master, elaborated in the HardCilk top -- so they are SIBLINGS of the
      // peMap_* they serve, and pblock_user_kernel above keeps them in the hub
      // SLR while the PE moves to the spill SLR. A Queue's enq handshake is
      // COMBINATIONAL with the PE, so that splits one tight loop across two
      // dice and leaves the placer no freedom whatsoever: without this block,
      // paths between two flops of the same PE's m_axi adapter crossed the SLR
      // boundary several times and set the design WNS.
      val spilled = plan.spillPes.toSet
      val ifaceForSpilled = peIfaceBuffers.filter { case (pe, _) => spilled.contains(pe) }
      if (ifaceForSpilled.isEmpty) {
        sb ++= s"""
# NOTE: no interface-queue names were handed to the floorplan generator, so the
# spilled PEs' `iface_*Buffer` queues stay in ${d.hubSlr} with the rest of the kernel.
# That is a known timing hazard -- see TclResources.Floorplan. Rebuild the RTL so
# HardCilkEmitterUtil.parsePeIfaceBuffers can supply them.
"""
      } else {
        sb ++= s"""
# ---------------------------------------------------------------------------
# Interface queues of the spilled PEs -> ${d.spillSlr}, with the PE they serve.
#
# Assigned after the parent so these win ownership. No trailing /* -- the filter
# selects the hierarchical cell itself.
#
# `-quiet` on both commands is load-bearing. Synthesis runs
# `-flatten_hierarchy rebuilt`, which can DISSOLVE a queue and leave no trace of
# its name anywhere in the netlist. `get_cells` then matches nothing and an
# unguarded `add_cells_to_pblock` ERRORS OUT, aborting the rest of this file --
# including the pblocks below. The RTL now carries `keep_hierarchy` on these
# instances (Util.HardCilkEmitterUtil.annotateIfaceBufferHierarchy) so they
# should survive, but -quiet keeps one dissolved queue from costing a run.
# ---------------------------------------------------------------------------
"""
        ifaceForSpilled.foreach { case (pe, bufs) =>
          sb ++= s"# $pe: ${bufs.length} queue(s)\n"
          bufs.foreach { b =>
            sb ++= s"""add_cells_to_pblock -quiet [get_pblocks pblock_kernel_${d.spillSlr.toLowerCase}] \\
    [get_cells -quiet -hierarchical -filter {NAME =~ *inst/$b || NAME =~ *${descriptorName}_0/$b}]
"""
          }
        }
      }
      sb ++= s"""
# ---------------------------------------------------------------------------
# The spill die's reset leaf.
#
# The RTL splits the kernel reset into a per-SLR pipeline
# (Util.HardCilkEmitterUtil.pipelinePerSlrReset): instances on ${d.hubSlr} are driven
# from hc_reset_slr1 / hc_resetn_slr1, instances here from hc_reset_slr2 /
# hc_resetn_slr2, every leaf the same depth so nothing changes about WHEN the
# design comes out of reset.
#
# That only buys anything if the ${d.spillSlr} leaf is actually placed in ${d.spillSlr}. Left to
# the parent pblock it sits in ${d.hubSlr} and its output is once again a single net
# crossing the die to every flop it drives -- the exact 0-logic-level, pure-route
# shape the pipeline exists to remove.
#
# Assigned after the parent so the child wins ownership. The active-low leaf is
# a separate register and needs the same treatment. hc_reset_p0/p1_* and the
# ${d.hubSlr} leaves are deliberately left to the parent: ${d.hubSlr} is the middle die and is
# one hop from both groups.
add_cells_to_pblock -quiet [get_pblocks pblock_kernel_${d.spillSlr.toLowerCase}] \\
    [get_cells -quiet -hierarchical -filter {NAME =~ *inst/hc_reset_slr2_reg || NAME =~ *${descriptorName}_0/hc_reset_slr2_reg}]
add_cells_to_pblock -quiet [get_pblocks pblock_kernel_${d.spillSlr.toLowerCase}] \\
    [get_cells -quiet -hierarchical -filter {NAME =~ *inst/hc_resetn_slr2_reg || NAME =~ *${descriptorName}_0/hc_resetn_slr2_reg}]
"""
      sb ++= s"set_property IS_SOFT FALSE [get_pblocks pblock_kernel_${d.spillSlr.toLowerCase}]\n"
    }

    sb ++= s"""
# First AXI slice: design side of the crossing.
# Soft: with PEs on two dice the slices no longer all want the same place, so each
# should drift toward the PE it serves while staying in ${d.hubSlr}, which keeps the
# rs0 -> rs1 (${d.memSideSlr}) hop short. Pinning them hard would tie the ${d.spillSlr} group's
# slices to the wrong end of the die.
create_pblock pblock_axi_rs0
resize_pblock [get_pblocks pblock_axi_rs0] -add ${d.hubSlr}
add_cells_to_pblock \\
    [get_pblocks pblock_axi_rs0] \\
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/rs0_m_axi_*}]
set_property IS_SOFT TRUE [get_pblocks pblock_axi_rs0]

# Second AXI slice: HBM side of the crossing. Hard -- sitting on a known side of
# the boundary is its whole purpose.
create_pblock pblock_axi_rs1
resize_pblock [get_pblocks pblock_axi_rs1] -add ${d.memSideSlr}
add_cells_to_pblock \\
    [get_pblocks pblock_axi_rs1] \\
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/rs1_m_axi_*}]
set_property IS_SOFT FALSE [get_pblocks pblock_axi_rs1]

# RAMA: keep it on the memory side, next to the HBM ports it reorders.
#
# Soft, and deliberately not merged into pblock_axi_rs1: RAMA sits between rs1
# and the HBM AXI interface, so each instance should drift toward the port it
# serves rather than pile up wherever rs1 landed.
#
# Without this the RAMA cores are the only unconstrained block left in the
# design and the placer scatters them, producing zero-logic, almost pure-wire
# reset paths (reset_synchroniser -> rama_read_core) across the die.
#
# NOT GUARDED WITH `if`. A design built without RAMA (no --rama-striping and no
# task setting generateRAMA) has no such cells, and the obvious `if {[llength
# [get_cells ...]] > 0} {...}` wrapper does not work: Tcl control flow is NOT
# legal in an XDC. Vivado parses the file, prints
#   CRITICAL WARNING: [Designutils 20-1307] Command 'if' is not supported in the
#   xdc constraint file
# and SKIPS THE WHOLE BLOCK, so the guard silently removes the fix it guards.
# `-quiet` on both commands is the XDC-legal equivalent: an empty collection
# downgrades add_cells_to_pblock's error to a warning, leaving an empty pblock,
# which is harmless.
create_pblock pblock_rama
resize_pblock [get_pblocks pblock_rama] -add ${d.memSideSlr}
add_cells_to_pblock -quiet \\
    [get_pblocks pblock_rama] \\
    [get_cells -quiet -hierarchical -filter {NAME =~ *design_1_i/rama_*}]
set_property IS_SOFT TRUE [get_pblocks pblock_rama]

# AXI-Lite slice: flexible, but stay close to the control side.
create_pblock pblock_axilite_slice
resize_pblock [get_pblocks pblock_axilite_slice] -add ${d.hubSlr}
add_cells_to_pblock \\
    [get_pblocks pblock_axilite_slice] \\
    [get_cells -hierarchical -filter {NAME =~ *design_1_i/rs_s_axil_mgmt*}]
set_property IS_SOFT TRUE [get_pblocks pblock_axilite_slice]
"""

    sb.toString()
  }

  /**
    * Multi-spill planner, for devices whose profile lists `extraSpillSlrs`
    * (the Alveo V80: hub SLR1, PEs spread over SLR1, SLR2 and SLR0).
    *
    * Same "never straddle" rule as the two-die plan: each PE goes whole to one
    * SLR. Tasks with at most [[splitThreshold]] PEs stay with the hub logic.
    * The PEs of larger tasks are dealt out one at a time, biggest PEs first, to
    * the first die in the order hub, `spillSlr`, `extraSpillSlrs` whose
    * estimated occupancy (CLBs over the SLR's CLBs, counting the hub base on
    * the hub die and each profile's `reservedClb`) stays within
    * `occupancyWarnFraction`; if none does, to the least occupied one.
    *
    * Filling rather than balancing is deliberate on the V80: the hub die is the
    * HBM die, and every PE moved off it costs SLLs for all of its memory and
    * stream interfaces (about 2.1k per pageRank_overlap applyFn PE; SLR1-SLR2 has
    * 18870), while a PE on the hub die costs none.
    */

  /**
    * SLLs one PE moved off the hub die needs per boundary it crosses: its
    * stream and memory interfaces to the hub, estimated as
    * [[sllPortBitFactor]] x the bit count of the PE top module's ports (see
    * [[estimatePortBits]]); [[fallbackSllPerPe]] when that cannot be read.
    *
    * Calibration (V80, hub on SLR2, pageRank_overlap applyFn PEs): SLR1<->SLR2
    * used 6301 / 14703 / 18862 SLLs with 3 / 7 / 9 PEs on SLR1 (16/20/22-PE
    * builds), i.e. 2095-2100 per PE, against 2515 port bits on
    * applyFn_overlap_wrapper -> 0.833; a later 9-PE build needed 18883
    * (2098 per PE, Place 30-5425) and the 28-PE LUTRAM build 18773 placed, so
    * 0.835 (2100 per PE). Unused outputs (xUSER, LOCK, REGION, ...) are
    * optimised away, hence < 1. The budget margin is [[multiSpillSllFraction]].
    */
  private val sllPortBitFactor: Double = 0.835
  private val fallbackSllPerPe: Int = 2100

  /**
    * Fraction of a boundary's SLLs the planner lets spilled PEs use,
    * `HC_SLL_MARGIN` (default 0.97). History: at 1.0 the 22-PE
    * pageRank_overlap build routed at 18862 / 18870 (99.96%) once, and the
    * same 13/9 split failed placement in a later build ("18883 SLLs required
    * ... only 18870 available", Place 30-5425) after the guard / 4K splitter /
    * deskew changes: the per-PE estimate is good to ~0.5%, so a full boundary
    * has no margin. 0.97 keeps ~560 SLLs in reserve.
    */
  private def multiSpillSllFraction: Double =
    sys.env.get("HC_SLL_MARGIN").flatMap(v => scala.util.Try(v.toDouble).toOption)
      .filter(f => f > 0 && f <= 1.0).getOrElse(0.97)

  /**
    * Bits on the ports of a PE's top module (clock/reset excluded): the
    * `<peHDLPath>/<x>_overlap_wrapper.v` module if there is one, else
    * `<peHDLPath>/<task>.v`. Parameterised ranges (`[P-1:0]`, `[(P/8)-1:0]`)
    * are evaluated with the module's parameter defaults. 0 if unreadable.
    */
  private def estimatePortBits(task: TaskDescriptor, keep: String => Boolean = _ => true): Int = {
    val dir = new java.io.File(task.peHDLPath)
    if (task.peHDLPath.isEmpty || !dir.isDirectory) return 0
    val files = Option(dir.listFiles()).getOrElse(Array.empty[java.io.File]).filter(_.isFile)
    val top = files
      .find(_.getName.endsWith(wrapperFileSuffix))
      .orElse(files.find(_.getName == s"${task.name}.v"))
    top.map { f =>
      try {
        val src = scala.io.Source.fromFile(f, "ISO-8859-1")
        val text = try src.mkString finally src.close()
        val mod = f.getName.stripSuffix(".v")
        val hdr = raw"(?ms)^module\s+${java.util.regex.Pattern.quote(mod)}\b(.*?)\);".r
          .findFirstMatchIn(text).map(_.group(1)).getOrElse("")
        val params = scala.collection.mutable.Map[String, String]()
        raw"parameter\s+(?:integer\s+)?(\w+)\s*=\s*([^,;\n)]+)".r.findAllMatchIn(text).foreach { m =>
          if (!params.contains(m.group(1))) params(m.group(1)) = m.group(2).trim
        }
        def ev(e: String, depth: Int = 0): Long =
          if (depth > 10) 0L
          else {
            val subst = raw"[A-Za-z_]\w*".r.replaceAllIn(e, m =>
              "(" + ev(params.getOrElse(m.matched, "0"), depth + 1) + ")")
            ArithEval(raw"\d+'[dhb]?".r.replaceAllIn(subst, ""))
          }
        val portRe = raw"(?m)^\s*(input|output)\s+(?:wire\s+|reg\s+)?(\[([^\]:]+):([^\]]+)\])?\s*(\w+)".r
        val skip = Set("ap_clk", "ap_rst_n", "ap_rst", "clock", "reset")
        val bits = (body: String) =>
          portRe.findAllMatchIn(body).filterNot(m => skip.contains(m.group(5))).filter(m => keep(m.group(5))).map { m =>
            if (m.group(2) == null) 1L else math.abs(ev(m.group(3)) - ev(m.group(4))) + 1
          }.sum
        val b = bits(hdr)
        (if (b > 0) b else bits(text)).toInt
      } catch { case _: Throwable => 0 }
    }.getOrElse(0)
  }

  /**
    * HC_NOC_PES: HLS top ports that still go to the hub when the PE's memory
    * goes through its own die's NoC NMUs -- the task / argument-notification
    * streams. Excluded: the m_axi masters, the (unconnected) s_axi_control, and
    * the argDataOut / spawnNext streams, which the PE wrapper turns into memory
    * writes (m_axi_argOut / m_axi_spawnNext).
    */
  private def nocStreamPort(port: String): Boolean =
    !Seq("m_axi_", "s_axi_", "argDataOut", "spawnNext").exists(port.startsWith)

  /** Integer + - * / ( ) evaluator for Verilog range expressions; 0 on anything else. */
  private object ArithEval {
    def apply(e: String): Long =
      try {
        val toks = raw"\d+|[-+*/()]".r.findAllIn(e.replaceAll("\\s", "")).toVector
        var i = 0
        def peek = if (i < toks.length) toks(i) else ""
        def atom(): Long = peek match {
          case "(" => i += 1; val v = sum(); i += 1; v
          case "-" => i += 1; -atom()
          case t   => i += 1; t.toLong
        }
        def prod(): Long = {
          var v = atom()
          while (peek == "*" || peek == "/") {
            val op = peek; i += 1; val r = atom()
            v = if (op == "*") v * r else if (r == 0) 0 else v / r
          }
          v
        }
        def sum(): Long = {
          var v = prod()
          while (peek == "+" || peek == "-") {
            val op = peek; i += 1; val r = prod()
            v = if (op == "+") v + r else v - r
          }
          v
        }
        sum()
      } catch { case _: Throwable => 0L }
  }

  /**
    * RAMB36 the OVERLAP wrapper adds to each PE beyond HLS_SYN_MEM: its
    * `(* ram_style = "block" *)` FIFOs, estimated as ceil(WIDTH/72) x
    * ceil(DEPTH/512) per `*_fifo #(.WIDTH(..), .DEPTH(..))` instance in the
    * wrapper source, times [[wrapperRambFactor]]; [[fallbackWrapperRamb36]] if
    * the wrapper cannot be parsed.
    *
    * Calibration (V80): pageRank_overlap 78 RAMB36 per PE with HLS_SYN_MEM 58
    * -> wrapper 49, FIFO estimate 67 (0.73); triangleCount_overlap ~62 per PE
    * with HLS_SYN_MEM 4 -> wrapper ~60, FIFO estimate 98 (0.61). 0.73 is the
    * larger, so the planner errs high (triangleCount_overlap with 20/23 PEs on
    * SLR2 at the old fixed 49 over-filled pblock_SLR2: 1247 and 1433 of 1200
    * RAMB36, DRC UTLZ-1).
    */
  private val wrapperRambFactor: Double = 0.73
  private val fallbackWrapperRamb36: Long = 62L

  private def estimateWrapperRamb36(task: TaskDescriptor): Long = {
    val dir = new java.io.File(task.peHDLPath)
    val f = Option(dir.listFiles()).getOrElse(Array.empty[java.io.File])
      .find(x => x.isFile && x.getName.endsWith(wrapperFileSuffix))
    f.map { file =>
      try {
        val src = scala.io.Source.fromFile(file, "ISO-8859-1")
        val text = try src.mkString finally src.close()
        val params = scala.collection.mutable.Map[String, String]()
        raw"(?:localparam|parameter)\s+(?:integer\s+)?(\w+)\s*=\s*([^,;\n)]+)".r.findAllMatchIn(text).foreach { m =>
          if (!params.contains(m.group(1))) params(m.group(1)) = m.group(2).trim
        }
        def ev(e: String, depth: Int = 0): Long =
          if (depth > 10) 0L
          else ArithEval(raw"[A-Za-z_]\w*".r.replaceAllIn(e, m => "(" + ev(params.getOrElse(m.matched, "0"), depth + 1) + ")"))
        val inst = raw"_(?:fifo|msr_fifo|mrc_fifo)\s*#\(\s*\.WIDTH\(([^()]*(?:\([^()]*\))?[^()]*)\)\s*,\s*\.DEPTH\(([^()]*)\)".r
        val est = inst.findAllMatchIn(text).map { m =>
          val w = ev(m.group(1)); val d = ev(m.group(2))
          ((w + 71) / 72) * math.max(1L, (d + 511) / 512)
        }.sum
        if (est > 0) math.round(est * wrapperRambFactor) else fallbackWrapperRamb36
      } catch { case _: Throwable => fallbackWrapperRamb36 }
    }.getOrElse(fallbackWrapperRamb36)
  }

  /** Fraction of an SLR's RAMB36 the multi-spill planner fills (the pblock DRC limit is 100%). */
  private val multiSpillBramFraction: Double = 0.9

  private def planMultiSpill(
      desc: FullSysGenDescriptor,
      device: DeviceProfile,
      peInstanceNames: Seq[(String, Seq[String])]
  ): Plan = {
    val byName = desc.taskDescriptors.map(t => t.name -> t).toMap
    val dies = Seq(device.hubSlr, device.spillSlr) ++ device.extraSpillSlrs
    val load = scala.collection.mutable.Map[String, Long]()
    dies.foreach(d => load(d) = device.reservedClb.getOrElse(d, 0).toLong)
    load(device.hubSlr) += device.clbHubBase

    // Measured per-PE figures (post-synthesis LUT/FF, BRAM tiles after the
    // LUTRAM conversion when it is on) from HC_LUTRAM_PROFILE, see V80Lutram.
    val measured = peInstanceNames.map { case (t, inst) => t -> V80Lutram.forTask(t, inst) }.toMap
    val areas = peInstanceNames.map { case (t, _) =>
      val hls = estimateClbPerPe(byName(t))
      t -> measured(t).fold(hls) { p =>
        val clb = math.max(1L, (math.max(p.lut / 8.0, p.ff / 16.0) * clbPackingFactor).round).toInt
        hls.copy(clb = clb, ff = p.ff, lut = p.lut,
          measured = f"MEASURED (HC_LUTRAM_PROFILE, LUTRAM ${V80Lutram.mode}): LUT ${p.lut}, FF ${p.ff}, " +
            f"${p.bramTiles}%.1f BRAM tiles per PE")
      }
    }.toMap
    // RAMB36 per PE: HLS_SYN_MEM counts BRAM_18K; the OVERLAP wrapper's FIFOs
    // and context cache are not in it (measured: 78 RAMB36 per pageRank applyFn
    // PE against HLS_SYN_MEM = 58 BRAM_18K, i.e. ~49 RAMB36 of wrapper).
    val wrapperRamb = peInstanceNames.map { case (t, _) =>
      t -> (if (areas(t).wrapped) estimateWrapperRamb36(byName(t)) else 0L)
    }.toMap
    val ramb = (t: String) =>
      measured(t).fold((areas(t).mem + 1) / 2 + wrapperRamb(t))(p => math.ceil(p.bramTiles).toLong)
    val bram = scala.collection.mutable.Map[String, Long]()
    dies.foreach(d => bram(d) = 0L)
    val small = peInstanceNames.filter(_._2.length <= splitThreshold)
    small.foreach { case (t, inst) =>
      load(device.hubSlr) += inst.length.toLong * areas(t).clb
      bram(device.hubSlr) += inst.length.toLong * ramb(t)
    }

    val assigned = scala.collection.mutable.Map[String, Map[String, Seq[String]]]()
    // Occupancy cap per die (estimated CLB incl. the shell's reservedClb, and
    // BRAM via bramFrac): HC_SLR_CAP, default the device's occupancyWarnFraction.
    val slrCap = sys.env.get("HC_SLR_CAP").flatMap(v => scala.util.Try(v.toDouble).toOption)
      .filter(f => f > 0 && f <= 1.0).getOrElse(device.occupancyWarnFraction)
    val overflowKeepsCap = sys.env.get("HC_FLOORPLAN_OVERFLOW").exists(_.trim.toLowerCase == "cap")
    val overCap = scala.collection.mutable.Map[String, Int]()
    val overSll = scala.collection.mutable.Map[String, Int]()
    // Estimated SLLs used on boundary b (between dies(b-1) and dies(b)), b = 1..
    val crossings = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    var overfilled = false
    val sllPerTask = scala.collection.mutable.LinkedHashMap[String, Int]()
    peInstanceNames
      .filter(_._2.length > splitThreshold)
      .sortBy { case (t, _) => -areas(t).clb }
      .foreach { case (t, inst) =>
        val clb = areas(t).clb.toLong
        val per = scala.collection.mutable.LinkedHashMap[String, Seq[String]]()
        dies.foreach(d => per(d) = Seq.empty)
        val rb = ramb(t)
        val portBits = estimatePortBits(byName(t))
        val sllPerSpilledPe =
          if (portBits > 0) math.round(portBits * sllPortBitFactor).toInt else fallbackSllPerPe
        sllPerTask(t) = sllPerSpilledPe
        // HC_NOC_PES (Util.NocPePolicy): the NoC PEs go to their die first; their
        // memory masters use that die's NoC NMUs, so only their stream ports
        // (task in/out, argument notifications) cross the boundaries to the hub.
        val nocSlr = (i: Int) => Util.NocPePolicy.slrOf(desc, t, i)
        val nocIdx = inst.indices.filter(i => nocSlr(i).isDefined).toSet
        if (nocIdx.nonEmpty) {
          val streamBits = estimatePortBits(byName(t), nocStreamPort)
          val sllNoc0 = if (streamBits > 0) math.round(streamBits * sllPortBitFactor).toInt else fallbackSllPerPe / 2
          // HC_SCHED_SLR_LOCAL: the PE's streams end on its own die; only the few
          // per-die links of the scheduler / notifier cross (not charged per PE).
          val sllNoc =
            if (Util.NocPePolicy.schedSlrLocal(desc) && Util.NocPePolicy.task(desc).contains(t)) 0 else sllNoc0
          // HC_SLR_HBM_MEM: these PEs' memory masters cross to the hub's HBM pool.
          val sllMem = math.max(0, sllPerSpilledPe - sllNoc0)
          nocIdx.toSeq.sorted.foreach { i =>
            val d = nocSlr(i).get
            val k = dies.indexOf(d)
            (1 to k).foreach(b => crossings(b) += sllNoc +
              (if (Util.NocPePolicy.hbmMemDies.contains(d)) sllMem else 0))
            load(d) += clb
            bram(d) += rb
            per(d) = per(d) :+ inst(i)
          }
          println(s"[Floorplan] HC_NOC_PES: ${nocIdx.size} PE(s) of $t pre-assigned " +
            nocIdx.toSeq.groupBy(i => nocSlr(i).get).toSeq.sortBy(_._1).map { case (d, l) => s"${l.size} to $d" }.mkString(", ") +
            s" (NoC memory ports; $streamBits stream bits -> $sllNoc SLLs per boundary per PE, vs $sllPerSpilledPe with HBM-NMU memory)")
          if (Util.NocPePolicy.hbmMemDies.nonEmpty)
            println(s"[Floorplan] HC_SLR_HBM_MEM: PEs on ${Util.NocPePolicy.hbmMemDies.toSeq.sorted.mkString("/")} keep " +
              s"HBM-NMU memory: +$sllMem SLLs per boundary per PE")
        }
        inst.zipWithIndex.filterNot { case (_, i) => nocIdx.contains(i) }.map(_._1).foreach { pe =>
          val bramFrac = (d: String) => {
            val cap = device.slr(d).ramb36Count
            if (cap <= 0) 0.0 else (bram(d) + rb).toDouble / (cap * multiSpillBramFraction)
          }
          val frac = (d: String) =>
            math.max((load(d) + clb).toDouble / device.slr(d).clbCount, bramFrac(d) * slrCap)
          // Fill in die order (hub first: it is also the memory side, so its
          // PEs cost no SLR crossings) up to the occupancy target, then spill.
          // SLL budget: a PE on die k (k hops from the hub, dies in order of
          // distance) crosses every boundary between it and the hub.
          val sllOk = (d: String) =>
            device.sllPerBoundary <= 0 || {
              val k = dies.indexOf(d)
              (1 to k).forall { b =>
                crossings(b) + sllPerSpilledPe <= device.sllPerBoundary * multiSpillSllFraction
              }
            }
          val fits = dies.find(d => frac(d) <= slrCap && sllOk(d))
          // Nothing fits both the occupancy cap and the SLL budget: break one.
          // HC_FLOORPLAN_OVERFLOW=sll (default) keeps the SLL budget and
          // overfills (usually the hub die); =cap keeps the cap where it can
          // and overruns the SLL budget instead.
          val best0 = fits.getOrElse {
            if (overflowKeepsCap) dies.minBy(frac)
            else dies.filter(sllOk).minBy(frac)
          }
          // HC_SCHED_SLR_LOCAL: the per-die scheduler serves the PEs that are not
          // NoC PEs from the hub die, so that is where they must be.
          val best =
            if (Util.NocPePolicy.schedSlrLocal(desc) && Util.NocPePolicy.task(desc).contains(t)) device.hubSlr
            else best0
          if (fits.isEmpty) {
            if (frac(best) > slrCap) overCap(t) = overCap.getOrElse(t, 0) + 1
            if (!sllOk(best)) overSll(t) = overSll.getOrElse(t, 0) + 1
          }
          if (frac(best) > slrCap) overfilled = true
          val k = dies.indexOf(best)
          (1 to k).foreach(b => crossings(b) += sllPerSpilledPe)
          load(best) += clb
          bram(best) += rb
          per(best) = per(best) :+ pe
        }
        assigned(t) = per.toMap
      }

    val splits = peInstanceNames.map { case (t, inst) =>
      val a = areas(t)
      assigned.get(t) match {
        case None =>
          TaskSplit(t, inst, Seq.empty, a.clb, a.basis, a.ff, a.lut, a.mem)
        case Some(per) =>
          TaskSplit(
            t,
            per(device.hubSlr),
            per(device.spillSlr),
            a.clb,
            a.basis,
            a.ff,
            a.lut,
            a.mem,
            extraSpillPes = device.extraSpillSlrs.map(d => d -> per(d))
          )
      }
    }
    // Occupancy cap vs. SLL budget, per task: how many PEs fit within both.
    sllPerTask.foreach { case (t, sllPe) =>
      val n = peInstanceNames.find(_._1 == t).map(_._2.length).getOrElse(0)
      val bad = math.max(overCap.getOrElse(t, 0), overSll.getOrElse(t, 0))
      println(f"[Floorplan] trade-off $t: $n PEs requested, ${n - bad} fit within the ${slrCap * 100}%.0f%% occupancy " +
        s"cap (HC_SLR_CAP) and the ${(device.sllPerBoundary * multiSpillSllFraction).toInt}-SLL budget ($sllPe SLLs per PE off the hub, at most " +
        s"${if (sllPe > 0) (device.sllPerBoundary * multiSpillSllFraction / sllPe).toInt else 0} off the hub " +
        f"at HC_SLL_MARGIN ${multiSpillSllFraction}%.2f)" +
        (if (bad > 0) s"; the other $bad " + (if (overflowKeepsCap) s"overrun the SLL budget (${overSll.getOrElse(t, 0)}) / cap (${overCap.getOrElse(t, 0)})"
          else s"overfill a die past the cap (HC_FLOORPLAN_OVERFLOW=sll; =cap trades SLLs instead)") else ""))
    }
    if (overfilled)
      println(
        s"[Floorplan] WARNING: no die had room within ${slrCap} occupancy and the " +
          s"SLL budget (${sllPerTask.map { case (t, n) => s"$t $n" }.mkString(", ")} SLLs per spilled PE, ${multiSpillSllFraction} of " +
          s"${device.sllPerBoundary} per boundary); the remaining PEs went to the least occupied die " +
          "that keeps the SLL budget. This design is probably larger than the device can route."
      )
    if (device.sllPerBoundary > 0)
      println(
        "[Floorplan] estimated SLLs for spilled PEs: " +
          (1 until dies.length).map(b => s"${dies(b - 1)}<->${dies(b)} ${crossings(b)}/${device.sllPerBoundary}").mkString(", ") +
          " (per PE: " + sllPerTask.map { case (t, n) => s"$t $n" }.mkString(", ") + ")"
      )
    println(
      "[Floorplan] estimated RAMB36: " + dies.map(d => s"$d ${bram(d)}/${device.slr(d).ramb36Count}").mkString(", ") +
        " (per PE: " + peInstanceNames.filter(_._2.length > splitThreshold).map { case (t, _) => s"$t ${ramb(t)}" }.mkString(", ") + ")"
    )
    // Which resource binds each die for this PE count (BRAM after the LUTRAM
    // conversion when HC_LUTRAM_PROFILE is given).
    dies.foreach { d =>
      val cap = device.slr(d).ramb36Count
      if (cap > 0) {
        val bf = bram(d).toDouble / cap
        val cf = load(d).toDouble / device.slr(d).clbCount
        val bind =
          if (bf > multiSpillBramFraction) "BRAM (over the 90% target)"
          else if (bf * device.occupancyWarnFraction / multiSpillBramFraction > cf) "BRAM"
          else "CLB"
        println(f"[Floorplan] $d binding resource: $bind -- RAMB36 ${bram(d)}/$cap (${bf * 100}%.1f%%, margin " +
          f"${(cap * multiSpillBramFraction - bram(d)).toLong} to the ${multiSpillBramFraction * 100}%.0f%% target), " +
          f"CLB ${load(d)}/${device.slr(d).clbCount} (${cf * 100}%.1f%%)")
      }
    }
    Plan(device, splits, widenedHub = false)
  }

  /** Generation-time report for a multi-spill plan. Returns the lines it printed. */
  def reportMultiSpill(plan: Plan): Seq[String] = {
    val d = plan.device
    val dies = Seq(d.hubSlr, d.spillSlr) ++ d.extraSpillSlrs
    val pct = (f: Double) => f"${f * 100}%.1f%%"
    val lines = scala.collection.mutable.ArrayBuffer[String]()
    lines += s"[Floorplan] device ${d.model}: ${d.slrs.length} SLR(s), hub=${d.hubSlr}, " +
      s"PEs spread over ${dies.mkString(", ")}, memory side=${d.memSideSlr}"
    plan.splits.foreach { s =>
      val counts = Seq(d.hubSlr -> s.hubPes.length, d.spillSlr -> s.spillPes.length) ++
        s.extraSpillPes.map { case (slr, p) => slr -> p.length }
      lines += s"[Floorplan] task ${s.taskName}: " +
        counts.map { case (slr, n) => s"$n in $slr" }.mkString(", ") +
        s" (~${s.clbPerPe} CLB each, estimated; ${s.basis})"
    }
    lines += "[Floorplan] estimated occupancy: " + dies.map { slr =>
      val c = plan.slrClb(slr)
      s"$slr $c CLB (${pct(c.toDouble / d.slr(slr).clbCount)})"
    }.mkString(", ") + s"; warning threshold ${pct(d.occupancyWarnFraction)}"
    dies.foreach { slr =>
      val f = plan.slrClb(slr).toDouble / d.slr(slr).clbCount
      if (f > 1.0)
        lines += s"[Floorplan] ERROR-LEVEL WARNING: $slr is estimated at ${pct(f)} of its CLBs -- " +
          "above 100%. This floorplan will very likely fail place_design."
      else if (f > d.occupancyWarnFraction)
        lines += s"[Floorplan] WARNING: $slr is estimated at ${pct(f)} of its CLBs, above the " +
          s"${pct(d.occupancyWarnFraction)} threshold."
    }
    lines.foreach(println)
    lines.toSeq
  }

  /**
    * Floorplan XDC for the Alveo V80 PCIe flow (AVED shell, BD instance `top_i`).
    *
    * Uses the shell's own hard SLR pblocks from AVED's `impl.xdc`
    * (`pblock_SLR0/1/2`, `IS_SOFT FALSE`) instead of creating overlapping ones:
    * this file is read LATE, after `impl.xdc`, so they exist.
    *
    *  - the whole kernel cell -> hub SLR, then PEs (and their interface queues)
    *    re-assigned to the spill dice underneath it (nested idiom, see [[xdc]]);
    *  - the spill reset leaf -> `spillSlr`;
    *  - rs0 (design side of the HBM crossing) -> hub SLR, rs1 and the 512-bit
    *    downsizers -> the memory side (SLR2, below the HBM NMUs).
    *
    * The management path (NoC M_AXI, its SmartConnect, the reset GPIO) is left
    * to the placer: the NoC compiler chooses where its NSU lands.
    */
  def v80Xdc(
      desc: FullSysGenDescriptor,
      rawMode: Boolean,
      peInstanceNames: Seq[(String, Seq[String])],
      peIfaceBuffers: Seq[(String, Seq[String])]
  ): String = {
    val names =
      if (peInstanceNames.nonEmpty) peInstanceNames else reconstructPeInstanceNames(desc)
    val plan = this.plan(desc, names)
    reportMultiSpill(plan)
    val d = plan.device
    val top = d.bdInstance
    val n = desc.name
    val ifaceByPe = peIfaceBuffers.toMap
    val sb = new StringBuilder
    val pb = (slr: String) => s"pblock_$slr"

    sb ++= s"""# Generated by HardCilk -- floorplan for the Alveo V80 PCIe flow (AVED shell).
# Device profile: ${d.model} (TclResources.DeviceProfile). Read LATE, after AVED's
# impl.xdc, whose hard pblocks pblock_SLR0/1/2 are reused here.
#
# Hub (schedulers, allocators, notifiers, HBM interconnect): ${d.hubSlr}.
# PEs are assigned whole to one SLR each (see TclResources.Floorplan): the kernel
# cell goes to ${pb(d.hubSlr)} first, then the PEs that belong elsewhere are re-assigned
# underneath it. Filters select hierarchical cells (no trailing /*) and match a
# PE both under inst/ and directly under the wrapper (synthesis -flatten rebuilt).
#
# Estimated occupancy: ${(Seq(d.hubSlr, d.spillSlr) ++ d.extraSpillSlrs).map(s => s"$s ${plan.slrClb(s)} CLB").mkString(", ")}
add_cells_to_pblock [get_pblocks ${pb(d.hubSlr)}] [get_cells -hierarchical -filter {NAME =~ *$top/${n}_0}]
"""
    if (rawMode)
      sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(d.hubSlr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *$top/smartconnect_hbm_* || NAME =~ *$top/shim_raw_*}]\n"

    val groups = Seq(d.spillSlr -> plan.splits.flatMap(_.spillPes)) ++ plan.extraSpill
    groups.filter(_._2.nonEmpty).foreach { case (slr, pes) =>
      sb ++= s"\n# ${pes.length} PE(s) -> $slr\n"
      pes.foreach { pe =>
        sb ++= s"add_cells_to_pblock [get_pblocks ${pb(slr)}] [get_cells -hierarchical -filter {NAME =~ *inst/$pe || NAME =~ *${n}_0/$pe}]\n"
        ifaceByPe.getOrElse(pe, Seq.empty).foreach { b =>
          sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(slr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *inst/$b || NAME =~ *${n}_0/$b}]\n"
        }
      }
    }
    if (plan.spillPes.nonEmpty)
      sb ++= s"""
# Spill reset leaf (Util.HardCilkEmitterUtil.pipelinePerSlrReset) next to the PEs it drives.
add_cells_to_pblock -quiet [get_pblocks ${pb(d.spillSlr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *inst/hc_reset_slr2_reg || NAME =~ *${n}_0/hc_reset_slr2_reg}]
add_cells_to_pblock -quiet [get_pblocks ${pb(d.spillSlr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *inst/hc_resetn_slr2_reg || NAME =~ *${n}_0/hc_resetn_slr2_reg}]
"""
    sb ++= s"""
# HBM path: rs0 on the kernel side, rs1 (+ width SmartConnects) next to the HBM NMUs.
add_cells_to_pblock -quiet [get_pblocks ${pb(d.hubSlr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *$top/rs0_m_axi_*}]
add_cells_to_pblock -quiet [get_pblocks ${pb(d.memSideSlr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *$top/rs1_m_axi_*}]
"""
    sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(d.memSideSlr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *$top/shim_in_* || NAME =~ *$top/smartconnect_sizer_* || NAME =~ *$top/split4k_* || NAME =~ *$top/rs_nmu_*}]\n"
    sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(d.hubSlr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *$top/rs_s_axil_mgmt}]\n"
    sb ++= nocXdc(n, top, pb)
    sb ++= resetMcpXdc
    sb.toString
  }

  /**
    * HC_NOC_PES (Util.NocPePolicy) part of the V80 floorplan: the per-die NoC
    * memory pools, the role-named skid stages of the NoC PEs' streams, the
    * SLR0 reset leaf and the block-design cells of each NoC port go to their
    * die. Empty when the policy is off. Read after the generic lines above, so
    * these assignments win (e.g. over the `split4k_*` -> memory-side wildcard).
    */
  /**
    * HC_RESET_MCP=1 (opt-in): 2-cycle setup multicycle (hold 1) on the hops
    * BETWEEN the per-SLR reset relay flops (hc_reset_p0 / p1_* -> the next relay
    * or leaf register; Util.HardCilkEmitterUtil.pipelinePerSlrReset). These are
    * pure register-to-register stages whose value changes only at reset
    * assertion / release, so a two-cycle budget only lets one die's leaf see the
    * release one cycle later; nothing runs until the host starts the kernel
    * long after. It removes the single long SLR1 -> SLR0 route of
    * hc_reset_p1_0 -> hc_reset(n)_slr0 that was the worst path of the 3-die
    * overlap builds. The leaves' fan-out into the logic is NOT relaxed.
    */
  private def resetMcpXdc: String =
    if (!sys.env.get("HC_RESET_MCP").exists(_.trim == "1")) ""
    else {
      val from = "[get_cells -quiet -hierarchical -filter {NAME =~ *hc_reset_p*_reg}]"
      val to = "[get_cells -quiet -hierarchical -filter {NAME =~ *hc_reset_p*_reg || NAME =~ *hc_reset_slr*_reg || NAME =~ *hc_resetn_slr*_reg}]"
      "\n# HC_RESET_MCP=1: reset relay -> relay/leaf hops get two cycles (pure pipeline, static after reset).\n" +
        s"set_multicycle_path -setup 2 -from $from -to $to\n" +
        s"set_multicycle_path -hold 1 -from $from -to $to\n"
    }

  private def nocXdc(n: String, top: String, pb: String => String): String = {
    val portSlr = Util.NocPePolicy.exportedPortSlr
    if (portSlr.isEmpty) return ""
    val sb = new StringBuilder
    val cell = (pat: String) => s"[get_cells -quiet -hierarchical -filter {NAME =~ *inst/$pat || NAME =~ *${n}_0/$pat}]"
    sb ++= "\n# HC_NOC_PES (Util.NocPePolicy): NoC memory pools, skid stages per die, SLR0 reset leaf, NoC port chains.\n"
    portSlr.filter(_ != "HBM").distinct.foreach { slr =>
      sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(slr)}] ${cell(Util.NocPePolicy.poolInstance(slr))}\n"
    }
    Seq("slr0" -> "SLR0", "slr1p" -> "SLR1", "slr1h" -> "SLR1", "slr1" -> "SLR1").foreach { case (role, slr) =>
      sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(slr)}] ${cell(s"skid_*_$role")}\n"
    }
    sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb("SLR1")}] ${cell("hc_reset_p1_0_reg")}\n"
    sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb("SLR0")}] ${cell("hc_reset_slr0_reg")}\n"
    sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb("SLR0")}] ${cell("hc_resetn_slr0_reg")}\n"
    // HC_RESET_PER_INST: the per-instance relays follow their leaf's die.
    if (sys.env.get("HC_RESET_PER_INST").exists(_.trim == "1")) {
      Seq("hc_rst_slr2_*" -> "SLR1", "hc_rstn_slr2_*" -> "SLR1", "hc_rst_slr0_*" -> "SLR0", "hc_rstn_slr0_*" -> "SLR0")
        .foreach { case (pat, slr) => sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(slr)}] ${cell(pat)}\n" }
    }
    // HC_SCHED_SLR_LOCAL (Util.SlrLocal): per-die scheduler blocks, notifier
    // segments and their link stages, at any depth, by the role their name ends in.
    if (sys.env.get("HC_SCHED_SLR_LOCAL").exists(_.trim == "1")) {
      sb ++= "# HC_SCHED_SLR_LOCAL: hcslr_<what>_<role> cells (per-die scheduler / notifier blocks, link stages).\n"
      Seq("slr0" -> "SLR0", "slr1p" -> "SLR1", "slr1h" -> "SLR1", "slr1" -> "SLR1", "slr2" -> "SLR2").foreach { case (role, slr) =>
        sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(slr)}] [get_cells -quiet -hierarchical -filter {NAME =~ *${n}_0/*hcslr_*_$role}]\n"
      }
    }
    portSlr.zipWithIndex.filter(_._1 != "HBM").foreach { case (slr, i) =>
      val cells = Seq("rs_noc_", "shim_in_", "smartconnect_sizer_", "split4k_", "stripe_", "rs_nmu_")
        .map(c => f"NAME =~ *$top/$c$i%02d").mkString(" || ")
      sb ++= s"add_cells_to_pblock -quiet [get_pblocks ${pb(slr)}] [get_cells -quiet -hierarchical -filter {$cells}]\n"
    }
    sb.toString
  }
}
