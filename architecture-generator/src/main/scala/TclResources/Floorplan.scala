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
      wrapped: Boolean
  ) {
    /** Which basis the numbers came from, for the printed report. */
    def basis: String =
      if (modules <= 0) "FALLBACK, no HLS data"
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
      hlsMem: Long = 0L
  )

  /** The whole assignment, plus the numbers the caller reports and warns on. */
  case class Plan(
      device: DeviceProfile,
      splits: Seq[TaskSplit],
      /** true when nothing was split and the hub SLR alone cannot hold the design. */
      widenedHub: Boolean
  ) {
    def spillPes: Seq[String] = splits.flatMap(_.spillPes)
    def hubClb: Int = splits.map(s => s.hubPes.length * s.clbPerPe).sum + device.clbHubBase
    def spillClb: Int = splits.map(s => s.spillPes.length * s.clbPerPe).sum
    def hubFraction: Double = hubClb.toDouble / device.slr(device.hubSlr).clbCount
    def spillFraction: Double = spillClb.toDouble / device.slr(device.spillSlr).clbCount
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
}
