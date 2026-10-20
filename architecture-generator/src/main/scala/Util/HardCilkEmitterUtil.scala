package Util

import _root_.circt.stage.ChiselStage
import Descriptors._
import HardCilk.HardCilk


object HardCilkEmitterUtil {

  /**
    * What the downstream generators need to know about the HBM masters the RTL
    * ended up exporting.
    *
    * @param numHbmPortExports number of `m_axi_XX` masters on the top level
    * @param hbmPortWidths     data width of each exported `m_axi_XX` master
    * @param ramaPortIndices   the subset of those indices that belong to a
    *                          `generateRAMA` task and want a RAMA IP in front of
    *                          their HBM slave
    * @param hbmPortLabels     `<kind>/<task>` of the unit behind each exported
    *                          master (`pe.gmem/pageRank`, `sched.vss/update`,
    *                          ...). Only filled in `--raw-hbm-ports` mode, where
    *                          each exported port is exactly one master; the TCL
    *                          generators use it to spread different unit kinds
    *                          over the aggregating SmartConnects.
    */
  case class RtlGenResult(
      numHbmPortExports: Int,
      hbmPortWidths: Seq[Int],
      ramaPortIndices: Seq[Int],
      hbmPortLabels: Seq[String] = Seq.empty,
      hbmPortAxi3: Seq[Boolean] = Seq.empty,
      /**
        * Elaborated Chisel instance name of every PE, grouped by task and kept in
        * `peMap` iteration order (`taskName -> Seq("peMap_2_2_0", ...)`).
        *
        * Taken from the elaborated module rather than reconstructed, so the
        * floorplan's `get_cells` filters cannot drift from the names firtool
        * actually emits -- a filter that matches zero cells silently empties a
        * pblock and costs a full implementation run.
        */
      peInstanceNames: Seq[(String, Seq[String])] = Seq.empty,
      /**
        * The interface queues belonging to each PE, keyed by the PE's own Chisel
        * instance name (`"peMap_2_2_6" -> Seq("iface_sourceBuffer_72", ...)`).
        *
        * Read out of the emitted Verilog rather than reconstructed, for the same
        * reason as [[peInstanceNames]]: the allocation order of these queues is
        * an artefact of Chisel's naming counter across every unit in the design,
        * and a filter that guesses wrong silently empties a pblock.
        */
      peIfaceBuffers: Seq[(String, Seq[String])] = Seq.empty
  )

  def basename(path: String): String = path.split("/").last.split("\\.").head


  def readFile(path: String): String = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Path}
    Files.readString(Path.of(path), StandardCharsets.UTF_8)
  }

  def writeFile(path: String, data: String): Unit = {
    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Path}
    Files.writeString(Path.of(path), data, StandardCharsets.UTF_8)
  }

  /**
    * Tag each exported `m_axi_XX` master with the number of transactions it can
    * keep in flight.
    *
    * A block design instantiates the HardCilk top with `create_bd_cell -type
    * module`, so Vivado has no IP-XACT to read and infers the AXI interfaces from
    * the port names alone. The inferred interfaces come out with
    * NUM_READ_OUTSTANDING = NUM_WRITE_OUTSTANDING = 2, and those properties are
    * read-only on a module-reference pin -- the block-design TCL cannot raise
    * them. Every SmartConnect on the HBM path then sizes its transaction tracker
    * for two outstanding transactions and throttles the design accordingly.
    *
    * The supported override is a Verilog attribute on one port of the interface,
    * which Vivado picks up when it infers the interface and then propagates
    * downstream (SmartConnect, RAMA, ...) during `validate_bd_design`.
    *
    * @param verilog          the post-sv2v top-level Verilog
    * @param numPorts         number of exported `m_axi_XX` masters
    * @param numOutstanding   value to advertise for reads and writes
    */
  def annotateAxiOutstanding(verilog: String, numPorts: Int, numOutstanding: Int): String = {
    val attribute =
      s"""(* X_INTERFACE_PARAMETER = "NUM_READ_OUTSTANDING $numOutstanding, """ +
        s"""NUM_WRITE_OUTSTANDING $numOutstanding" *)"""

    (0 until numPorts).foldLeft(verilog) { case (text, i) =>
      val port = f"m_axi_${i}%02d"
      // Anchor on the port *declaration* (`output wire [33:0] m_axi_00_ARADDR;`),
      // not the earlier port list or the later instance connections. Which signal
      // comes first depends on the bus flavour -- a read-only master has no AW*
      // ports -- so take whichever declaration of this interface appears first.
      val decl = raw"(?m)^([ \t]*)((?:input|output)\s+wire\s+(?:\[[^\]]*\]\s*)?" +
        java.util.regex.Pattern.quote(port) + raw"_\w+\s*;)"
      decl.r.findFirstMatchIn(text) match {
        case Some(m) =>
          val indent = m.group(1)
          text.patch(m.start, s"$indent$attribute\n$indent${m.group(2)}", m.end - m.start)
        case None =>
          println(
            s"[HardCilkEmitter] WARNING: no port declaration found for $port; it keeps Vivado's " +
              s"default of 2 outstanding transactions, which will throttle its SmartConnect."
          )
          text
      }
    }
  }

  /**
    * Keep the PE interface queues as real hierarchy so the floorplan can name them.
    *
    * `HBMInterconnect` puts a `Queue(2)` on every channel of every PE memory
    * master (`iface_sourceBuffer_N` on ar/aw/w, `iface_sinkBuffer_N` on r/b).
    * Those queues are elaborated in the HardCilk top, i.e. as SIBLINGS of the
    * `peMap_*` they serve -- so when [[TclResources.Floorplan]] pins a PE to the
    * spill SLR, `pblock_user_kernel` keeps the PE's own queues in the hub SLR.
    *
    * That matters because a `Queue`'s enq handshake is COMBINATIONAL with the PE
    * (see the header of [[Util.SkidBuffer]]): the PE's `ARVALID` reaches the
    * queue's ready logic and comes straight back into the HLS m_axi adapter's
    * `burst_interleave` clock enables. Splitting that loop across two dice leaves
    * the placer no freedom at all: short paths inside one PE end up crossing the
    * SLR boundary several times.
    *
    * The floorplan fixes this by assigning each spilled PE's queues alongside it,
    * which needs the queue INSTANCE NAMES to survive into the netlist. They do
    * not by default: synthesis runs `-flatten_hierarchy rebuilt`, which dissolves
    * a large share of them and leaves no trace of their names in the netlist --
    * `get_cells` for a dissolved queue matches zero cells, so no pblock filter
    * can ever reach it.
    *
    * `keep_hierarchy` on the instantiation preserves that boundary through
    * synthesis. The cost is that Vivado no longer optimises across the queue's
    * edge; these are 2-entry buffers whose whole purpose is to be a clean
    * boundary, so there is little to lose there, and a deterministic floorplan is
    * worth more than the handful of LUTs cross-boundary merging would save.
    *
    * @param verilog the post-sv2v top-level Verilog
    */
  def annotateIfaceBufferHierarchy(verilog: String): String = {
    // `\tQueue2_ReadAddressChannel_14 iface_sourceBuffer_36(` -- the module name
    // varies with channel and config, the instance name is what identifies these.
    val inst = raw"(?m)^([ \t]*)(\w+\s+iface_(?:source|sink)Buffer(?:_\d+)?\s*\()".r
    var n = 0
    val out = inst.replaceAllIn(
      verilog,
      m => {
        n += 1
        java.util.regex.Matcher.quoteReplacement(
          s"""${m.group(1)}(* keep_hierarchy = "yes" *)\n${m.group(1)}${m.group(2)}"""
        )
      }
    )
    if (n == 0)
      println(
        "[HardCilkEmitter] WARNING: no iface_*Buffer instantiations found to tag with " +
          "keep_hierarchy. Synthesis may dissolve them, and the floorplan cannot then " +
          "assign a spilled PE's interface queues alongside it."
      )
    else println(s"[HardCilkEmitter] tagged $n iface_*Buffer instance(s) with keep_hierarchy")
    out
  }

  /**
    * HC_NOC_PES (see [[NocPePolicy]]): `keep_hierarchy` on the per-die NoC
    * memory pools (`noc_mem_pool_<slr>`) and on the role-named skid stages of
    * the NoC PEs' streams (`skid_<link>_<role>`), so the floorplan can pin each
    * to its die. Returns the annotated text and `(instance, role)` per cell;
    * the pool's role is its die (`slr0`, `slr1`).
    */
  def annotateNocHierarchy(verilog: String): (String, Seq[(String, String)]) = {
    val inst = raw"(?m)^([ \t]*)(\w+\s+((?:skid_\w+_(slr0|slr1h|slr1p|slr1|slr2))|(?:noc_mem_pool_(slr[01])))\s*\()".r
    val cells = scala.collection.mutable.ArrayBuffer[(String, String)]()
    val out = inst.replaceAllIn(
      verilog,
      m => {
        cells += (m.group(3) -> Option(m.group(4)).getOrElse(m.group(5)))
        java.util.regex.Matcher.quoteReplacement(
          s"""${m.group(1)}(* keep_hierarchy = "yes" *)\n${m.group(1)}${m.group(2)}"""
        )
      }
    )
    println(s"[HardCilkEmitter] HC_NOC_PES: kept hierarchy on ${cells.count(_._1.startsWith("skid_"))} skid stage(s) and " +
      s"${cells.count(_._1.startsWith("noc_mem_pool_"))} NoC memory pool(s)")
    (out, cells.toSeq)
  }

  /**
    * HC_SCHED_SLR_LOCAL (see [[SlrLocal]]): `keep_hierarchy` on every
    * `hcslr_<what>_<role>` instance (per-die scheduler blocks, notifier
    * segments and their link skid stages), at any depth, so the floorplan can
    * pin each one to the die of its role.
    */
  def annotateSlrLocalHierarchy(verilog: String): String = {
    val inst = raw"(?m)^([ \t]*)(\w+\s+hcslr_\w+_(?:slr0|slr1h|slr1p|slr1|slr2)\s*\()".r
    var n = 0
    val out = inst.replaceAllIn(
      verilog,
      m => {
        n += 1
        java.util.regex.Matcher.quoteReplacement(
          s"""${m.group(1)}(* keep_hierarchy = "yes" *)\n${m.group(1)}${m.group(2)}"""
        )
      }
    )
    println(s"[HardCilkEmitter] HC_SCHED_SLR_LOCAL: kept hierarchy on $n hcslr_* instance(s)")
    out
  }

  /**
    * Keep the HLS compute modules of a PE from being hoisted out of it.
    *
    * The floorplan assigns whole PEs to whole SLRs, and every filter it writes
    * is anchored on the PE instance name (`*inst/peMap_2_2_9`). A cell that
    * synthesis lifts OUT of the PE hierarchy is matched by none of them: it
    * stays in the parent kernel pblock on the hub die while the rest of its PE
    * is on the spill die, and its paths then cross the SLL twice.
    *
    * This has been seen in practice: part of an HLS carry chain hoisted out of a
    * spilled PE became the design's worst path, going SLR2 -> SLR1 -> SLR2
    * entirely inside one PE. `keep_hierarchy` on the module removed it.
    *
    * Applied to the HLS compute blocks (`*_reentryN`, `*_exitN`, `*_contN` and
    * the OVERLAP wrapper's decompositions of them), not to the AXI/control
    * infrastructure -- `*_regslice_both`, `*_control_s_axi` and `*_m_axi` are
    * boundary plumbing that gains nothing from a preserved hierarchy and is
    * better left free to merge.
    *
    * @param verilog  contents of one HLS PE source file
    * @param fileName that file's name, used only for reporting
    * @return the file with `keep_hierarchy` on the qualifying module definitions
    */
  def annotatePeComputeHierarchy(verilog: String, fileName: String): (String, Int) = {
    // Module definitions only: `module applyFn_reentry0 (`. HLS writes one
    // module per line start, so anchoring at column 0 is enough to avoid
    // instantiations (which HLS always indents).
    val modRe = raw"(?m)^module\s+(\w+)\s*\(".r
    val infra = Seq("_regslice_both", "_control_s_axi", "_m_axi", "_fifo", "_mux")
    // `matches()` anchors on its own, so no trailing `$` is needed (and in a
    // Scala interpolator `$"` would not parse).
    val compute = raw".*_(?:reentry|exit|cont)\d*(?:_(?:reentry|exit|cont)\d*)*".r
    var n = 0
    val out = modRe.replaceAllIn(
      verilog,
      m => {
        val name = m.group(1)
        val isInfra = infra.exists(name.contains)
        val isCompute = compute.pattern.matcher(name).matches()
        if (!isInfra && isCompute) {
          n += 1
          java.util.regex.Matcher.quoteReplacement(
            s"""(* keep_hierarchy = "yes" *)\n${m.matched}"""
          )
        } else java.util.regex.Matcher.quoteReplacement(m.matched)
      }
    )
    if (n > 0) println(s"[HardCilkEmitter] $fileName: kept hierarchy on $n HLS compute module(s)")
    (out, n)
  }

  /**
    * Split the kernel's flat reset into a per-SLR pipeline.
    *
    * Chisel wires every child module's reset straight to the kernel's `reset`
    * port, and the HLS cores' active-low `io_ap_rst_n` to `~reset` on top of
    * that. `proc_sys_reset` sits in SLR0 next to the PCIe hard block, so on a
    * multi-die floorplan that single net has to reach every flop in SLR1 AND
    * SLR2 combinationally.
    *
    * Even after `opt_design` replicates it, the result is zero-logic-level paths
    * made almost entirely of SLR0 -> SLR2 routing, which can dominate the failing
    * endpoints of a multi-die build, and `phys_opt` has nothing left to improve.
    *
    * Fanout attributes alone (MAX_FANOUT / FORCE_MAX_FANOUT) do not fix this --
    * the delay is the SLR0 -> SLR2 DISTANCE, not the load count. What is needed
    * is a registered hop per die, which is what this builds:
    *
    * {{{
    *   reset -> hc_reset_p0 -+-> hc_reset_p1_1 -> hc_reset_slr1  (hub die)
    *                         |                 -> hc_resetn_slr1
    *                         +-> hc_reset_p1_2 -> hc_reset_slr2  (spill die)
    *                                           -> hc_resetn_slr2
    * }}}
    *
    * EVERY leaf is the same depth (3 stages), so all instances still come out
    * of reset on the SAME cycle -- this is a placement change, not a sequencing
    * change. Only the last stage is duplicated, so the floorplan can put each
    * copy in the die it drives (see `TclResources.Floorplan`, which pins
    * `hc_reset_slr2_reg` into the spill pblock).
    *
    * The active-low leaves are registered rather than emitted as
    * `~hc_reset_slrN` so that no combinational inverter ends up sitting on a
    * high-fanout net. They matter more than the active-high ones: `io_ap_rst_n`
    * feeds the HLS core of each PE, which holds most of the reset's loads.
    *
    * @param verilog        the post-sv2v top-level Verilog
    * @param topName        the kernel top module name (the system descriptor's name)
    * @param spillInstances PE instance names assigned to the spill SLR, and the
    *                       interface queues that go with them -- i.e. everything
    *                       the floorplan moves off the hub die. Taken from the
    *                       SAME `Floorplan.plan` the XDC is built from, so the two
    *                       cannot disagree about which die an instance is on.
    */
  def pipelinePerSlrReset(
      verilog: String,
      topName: String,
      spillInstances: Set[String],
      slr0Instances: Set[String] = Set.empty
  ): String = {
    // Kernel top only: child modules have their own `reset` ports and must not
    // be touched. Bound the edit to `module <top> (` .. `endmodule`.
    val modRe = raw"(?m)^module\s+${java.util.regex.Pattern.quote(topName)}\s*\(".r
    val startOpt = modRe.findFirstMatchIn(verilog).map(_.start)
    if (startOpt.isEmpty) {
      println(
        s"[HardCilkEmitter] WARNING: kernel top module '$topName' not found; the reset " +
          "stays flat and every PE will be driven from one net across the SLR boundary."
      )
      return verilog
    }
    val start = startOpt.get
    val endIdx = verilog.indexOf("\nendmodule", start)
    val end = if (endIdx < 0) verilog.length else endIdx
    val head = verilog.substring(0, start)
    val body = verilog.substring(start, end)
    val tail = verilog.substring(end)

    // Walk the body tracking the enclosing instance, so each reset connection is
    // rewired to the leaf for the die that instance is floorplanned onto.
    val instRe = raw"^\t(\w+)\s+(\w+)\($$".r
    val resetRe = raw"^\t\t\.reset\(reset\)(,?)$$".r
    val rstNRe = raw"^\t\t\.io_ap_rst_n\(~reset\)(,?)$$".r
    var cur = ""
    var nHub, nSpill, nHubN, nSpillN, nSlr0 = 0
    // HC_NOC_PES: a third leaf for instances floorplanned onto SLR0.
    val leafOf = (inst: String) =>
      if (slr0Instances.contains(inst)) "slr0" else if (spillInstances.contains(inst)) "slr2" else "slr1"
    // HC_RESET_PER_INST=1 (opt-in): one more relay flop per instance after its
    // die leaf, so a leaf drives a few hundred relays instead of every reset
    // flop on the die. Every instance gets one, so the depth stays the same for
    // all; named hc_rst[n]_<leaf>_<inst> so the floorplan can pin them per die.
    val perInst = sys.env.get("HC_RESET_PER_INST").exists(_.trim == "1")
    val perInstRegs = scala.collection.mutable.ArrayBuffer[(String, String)]() // (reg, its leaf source)
    val rewired = body.split("\n", -1).map { line =>
      instRe.findFirstMatchIn(line).foreach(m => cur = m.group(2))
      resetRe.findFirstMatchIn(line) match {
        case Some(m) =>
          val leaf = leafOf(cur)
          if (leaf == "slr0") nSlr0 += 1 else if (leaf == "slr2") nSpill += 1 else nHub += 1
          if (perInst) {
            val r = s"hc_rst_${leaf}_$cur"; perInstRegs += (r -> s"hc_reset_$leaf")
            s"\t\t.reset($r)${m.group(1)}"
          } else
          s"\t\t.reset(hc_reset_$leaf)${m.group(1)}"
        case None =>
          rstNRe.findFirstMatchIn(line) match {
            case Some(m) =>
              val leaf = leafOf(cur)
              if (leaf == "slr0") nSlr0 += 1 else if (leaf == "slr2") nSpillN += 1 else nHubN += 1
              if (perInst) {
                val r = s"hc_rstn_${leaf}_$cur"; perInstRegs += (r -> s"hc_resetn_$leaf")
                s"\t\t.io_ap_rst_n($r)${m.group(1)}"
              } else
              s"\t\t.io_ap_rst_n(hc_resetn_$leaf)${m.group(1)}"
            case None => line
          }
      }
    }.mkString("\n")

    if (nHub + nSpill + nHubN + nSpillN == 0) {
      println(
        "[HardCilkEmitter] WARNING: no reset connections matched in the kernel top; " +
          "the per-SLR reset pipeline was NOT applied."
      )
      return verilog
    }

    val pipeline0 =
      """	// ---------------------------------------------------------------------
	// Per-SLR reset pipeline (Util.HardCilkEmitterUtil.pipelinePerSlrReset).
	//
	// The flat `reset` port drove every child instance from one net. Sourced at
	// proc_sys_reset in SLR0, that net produced 0-logic-level paths made of pure
	// routing into the spill die, which phys_opt cannot improve; the fix is
	// structural.
	//
	// Every leaf is the SAME depth, so all instances still deassert on the same
	// cycle. Only the last stage is duplicated per SLR, so the floorplan can
	// place each copy in the die it drives. DONT_TOUCH stops opt_design merging
	// the leaves back into one net, which would undo all of this.
	(* DONT_TOUCH = "yes" *) reg hc_reset_p0    = 1'b1;
	(* DONT_TOUCH = "yes" *) reg hc_reset_p1_1  = 1'b1;
	(* DONT_TOUCH = "yes" *) reg hc_reset_p1_2  = 1'b1;
	(* DONT_TOUCH = "yes" *) reg hc_reset_slr1  = 1'b1;
	(* DONT_TOUCH = "yes" *) reg hc_reset_slr2  = 1'b1;
	// Active-low leaves for the HLS cores (.io_ap_rst_n). Registered rather than
	// `~hc_reset_slrN` so no combinational inverter sits on a 17k-load net.
	(* DONT_TOUCH = "yes" *) reg hc_resetn_slr1 = 1'b0;
	(* DONT_TOUCH = "yes" *) reg hc_resetn_slr2 = 1'b0;
	always @(posedge clock) begin
		hc_reset_p0    <= reset;
		hc_reset_p1_1  <= hc_reset_p0;
		hc_reset_p1_2  <= hc_reset_p0;
		hc_reset_slr1  <= hc_reset_p1_1;
		hc_reset_slr2  <= hc_reset_p1_2;
		hc_resetn_slr1 <= ~hc_reset_p1_1;
		hc_resetn_slr2 <= ~hc_reset_p1_2;
	end
	// ---------------------------------------------------------------------
"""
    // HC_NOC_PES: SLR0 leaf, same depth (p0 -> p1_0 [SLR1] -> slr0 [SLR0]).
    val pipeline =
      if (slr0Instances.isEmpty) pipeline0
      else pipeline0 + """	// HC_NOC_PES (Util.NocPePolicy): SLR0 leaf for the NoC PEs, same depth;
	// hc_reset_p1_0 is pinned to SLR1 and the leaves to SLR0 by the floorplan.
	(* DONT_TOUCH = "yes" *) reg hc_reset_p1_0  = 1'b1;
	(* DONT_TOUCH = "yes" *) reg hc_reset_slr0  = 1'b1;
	(* DONT_TOUCH = "yes" *) reg hc_resetn_slr0 = 1'b0;
	always @(posedge clock) begin
		hc_reset_p1_0  <= hc_reset_p0;
		hc_reset_slr0  <= hc_reset_p1_0;
		hc_resetn_slr0 <= ~hc_reset_p1_0;
	end
"""
    // Declarations go in front of the first instantiation: after the port
    // declarations and every wire the instances connect to.
    val pipelineAll =
      if (!perInst || perInstRegs.isEmpty) pipeline
      else {
        val regs = perInstRegs.distinct
        pipeline + "\t// HC_RESET_PER_INST: one relay per instance after its die leaf.\n" +
          regs.map { case (r, src) =>
            val init = if (r.startsWith("hc_rstn_")) "1'b0" else "1'b1"
            s"\t(* DONT_TOUCH = \"yes\" *) reg $r = $init;\n"
          }.mkString +
          "\talways @(posedge clock) begin\n" +
          regs.map { case (r, src) => s"\t\t$r <= $src;\n" }.mkString +
          "\tend\n"
      }
    if (perInst) println(s"[HardCilkEmitter] HC_RESET_PER_INST: ${perInstRegs.distinct.length} per-instance reset relay(s)")
    val firstInst = raw"(?m)^\t\w+\s+\w+\($$".r.findFirstMatchIn(rewired)
    val withPipe = firstInst match {
      case Some(m) => rewired.substring(0, m.start) + pipelineAll + rewired.substring(m.start)
      case None    => rewired
    }

    // A connection left on the raw port means an instance was missed, and that
    // instance is then the one long combinational net this exists to remove.
    val leftover = raw"\([~!]?reset\)".r.findAllIn(withPipe).size
    if (leftover > 0)
      println(
        s"[HardCilkEmitter] WARNING: $leftover connection(s) in the kernel top still drive " +
          "off the raw reset port. Those instances keep the flat cross-SLR reset."
      )
    println(
      s"[HardCilkEmitter] per-SLR reset: .reset hub=$nHub spill=$nSpill, " +
        s".io_ap_rst_n hub=$nHubN spill=$nSpillN" +
        (if (slr0Instances.nonEmpty) s", SLR0 leaf (HC_NOC_PES)=$nSlr0" else "")
    )
    head + withPipe + tail
  }

  /**
    * Map each PE instance to the interface queues wired to its memory master(s).
    *
    * `HBMInterconnect` creates these 1:1 with the PE's `m_axi_gmem*` ports, so
    * the association is unambiguous in the emitted Verilog: every `iface_*Buffer`
    * instantiation connects to exactly one `_<pe>_io_m_axi_*` signal. Reading it
    * back from the Verilog is deliberate -- it is the same text the floorplan's
    * `get_cells` filters have to match, so the two cannot drift.
    *
    * An OVERLAP wrapper exposes one master per collapsed sub-PE, hence 5 queues
    * (ar/aw/w + r/b) per master: 10 for a two-master PE, 20 for a four-master one.
    */
  def parsePeIfaceBuffers(verilog: String): Seq[(String, Seq[String])] = {
    // `\tQueue2_ReadAddressChannel_14 iface_sourceBuffer_36(\n ... \n\t);`
    val instRe = raw"(?s)\n\t\w+\s+(iface_(?:source|sink)Buffer(?:_\d+)?)\(\n(.*?)\n\t\);".r
    val peRe = raw"_?(peMap_\d+_\d+_\d+)_io".r
    val acc = scala.collection.mutable.LinkedHashMap[String, scala.collection.mutable.ArrayBuffer[String]]()
    instRe.findAllMatchIn(verilog).foreach { m =>
      val inst = m.group(1)
      peRe.findAllMatchIn(m.group(2)).map(_.group(1)).toSeq.distinct.foreach { pe =>
        acc.getOrElseUpdate(pe, scala.collection.mutable.ArrayBuffer.empty[String]) += inst
      }
    }
    acc.toSeq.map { case (pe, bufs) => pe -> bufs.distinct.sorted.toSeq }
  }

  /**
  * A method to generate RTL called by HardCilk Emitter
  */
  def generateRTL(
      systemDescriptor: FullSysGenDescriptor,
      pathInputJsonFile: String,
      outputDirPathRTL: String,
      flags: BuilderConfig,
      isSimulation: Boolean
  ): RtlGenResult = {
    // for task in system descriptor copy all the files in the peHDLPath to the outputDirRTL
    var nKeptPeModules = 0
    systemDescriptor.taskDescriptors.foreach { task =>
      val peHDLPath = task.peHDLPath
      if(peHDLPath != ""){
        val peHDLPathFiles = new java.io.File(peHDLPath).listFiles()
        peHDLPathFiles.foreach { file =>
          val fileName = file.getName()
          val fileContent = readFile(file.getAbsolutePath())
          // Pin the HLS compute blocks inside their PE so the floorplan's
          // per-PE filters can reach every cell -- see annotatePeComputeHierarchy.
          val (annotated, kept) =
            if (fileName.endsWith(".v") || fileName.endsWith(".sv"))
              annotatePeComputeHierarchy(fileContent, fileName)
            else (fileContent, 0)
          nKeptPeModules += kept
          writeFile(s"$outputDirPathRTL/$fileName", annotated)
        }
      }
    }
    if (nKeptPeModules == 0)
      println(
        "[HardCilkEmitter] WARNING: no HLS compute modules were tagged with keep_hierarchy. " +
          "Synthesis may hoist cells out of a PE, where no floorplan filter can reach them."
      )

    // Copy all the files in the src/main/resources/ to the outputDirRTL except the DualPortBRAM_sim.v
    val resourcesPath = "src/main/resources/"
    val synthDirectory = f"${outputDirPathRTL}/synth"
    val questaDirectory = f"${outputDirPathRTL}/questa"
    new java.io.File(synthDirectory).mkdirs()
    new java.io.File(questaDirectory).mkdirs()

    val resourcesFiles = new java.io.File(resourcesPath).listFiles()
    
    val listOfFilesForRTL = List("DualPortBRAM_sim.v", "DualPortBRAM_xpm.v", "top.v", "u55c.xdc")
    val listOfFilesForQuesta = List("top_sim.sv", "main_sim.sv")

    writeFile(s"$outputDirPathRTL/empty.vh", "")
    writeFile(s"$outputDirPathRTL/empty.sv", "")
    resourcesFiles.foreach { file =>
      val fileName = file.getName()
      val fileContent = readFile(file.getAbsolutePath())

      if (fileName.startsWith("DualPortBRAM")) {
        if (
          (isSimulation && fileName == "DualPortBRAM_sim.v") || (!isSimulation && fileName == "DualPortBRAM_xpm.v")
        ) {
          writeFile(s"$outputDirPathRTL/DualPortBRAM.v", fileContent)
        }
      } else if (listOfFilesForQuesta.contains(fileName)) {
        writeFile(s"$questaDirectory/$fileName", fileContent)
      } else {
        writeFile(s"$synthDirectory/$fileName", fileContent)
      }
    }

    var numHbmPortExports = 0
    var hbmPortWidths = Seq.empty[Int]
    var ramaPortIndices = Seq.empty[Int]
    var hbmPortLabels = Seq.empty[String]
    var hbmPortAxi3 = Seq.empty[Boolean]
    var peInstanceNames = Seq.empty[(String, Seq[String])]
    var peIfaceBuffers = Seq.empty[(String, Seq[String])]
    var elaborated: HardCilk = null
    ChiselStage.emitSystemVerilogFile(
      {
        val module = new HardCilk(
          fullSysGenDescriptor = systemDescriptor,
          outputDirPathRTL = outputDirPathRTL,
          debug = flags.debug,
          reduceAxi = flags.hbmPorts,
          unitedHbm = true,
          isSimulation = isSimulation,
          argumentNotifierCutCount = 1,
          rawHbmPorts = flags.rawHbmPorts
        )
        numHbmPortExports = module.numHbmPortExports
        // Data width of each exported HBM master, in m_axi_00, m_axi_01, ... order.
        // The Vitis flow generator reads this back to size register slices/ports.
        hbmPortWidths = module.axiOuts.map(_.cfg.wData).toSeq
        ramaPortIndices = module.ramaPortIndices
        hbmPortLabels = module.hbmPortLabels
        // AXI3-compatible masters (4-bit AxLEN) need a protocol conversion before
        // anything AXI4 downstream, so a raw port that is AXI3 can never bypass
        // its SmartConnect. Only `--raw-hbm-ports` exports the masters' own
        // flavour; the muxed path normalises every port to AXI4.
        hbmPortAxi3 = module.axiOuts.map(_.cfg.axi3Compat).toSeq
        // Keep the elaborated module so the PE instance names can be read off it
        // once elaboration is finished -- `instanceName` is not resolvable while
        // the module is still being constructed.
        elaborated = module
        module
      },
      Array(f"--target-dir=${outputDirPathRTL}"),
      Array("--disable-all-randomization")
    )

    // Take the PE instance names from the elaborated module rather than
    // reconstructing Chisel's naming rule, so the floorplan's `get_cells` filters
    // cannot drift from the names firtool actually emits: a filter matching zero
    // cells silently empties a pblock and costs a full implementation run.
    if (elaborated != null) {
      peInstanceNames = elaborated.peMap.map { case (taskName, pes) =>
        taskName -> pes.map(_.instanceName)
      }.toSeq
      println(
        "[HardCilkEmitter] PE instances: " +
          peInstanceNames.map { case (t, n) => s"$t -> [${n.mkString(", ")}]" }.mkString("; ")
      )
    }

    // Emit a small metadata file describing the exported HBM port widths so that
    // the (separately-invoked) Vitis flow generator can build width-correct
    // register slices and interface ports instead of assuming a fixed 256 bits.
    writeFile(s"$outputDirPathRTL/hbm_port_widths.txt", hbmPortWidths.mkString("\n"))

    // Same idea for the exported masters that asked for a RAMA IP: one m_axi_XX
    // index per line (empty file when no task sets "generateRAMA").
    writeFile(s"$outputDirPathRTL/hbm_rama_ports.txt", ramaPortIndices.mkString("\n"))

    // HC_NOC_PES: die of each exported master ("HBM" or the NoC PEs' SLR). Only
    // written when the policy is on.
    val nocFile = new java.io.File(s"$outputDirPathRTL/hbm_port_noc.txt")
    if (NocPePolicy.exportedPortSlr.nonEmpty) writeFile(nocFile.getPath, NocPePolicy.exportedPortSlr.mkString("\n"))
    else if (nocFile.exists()) nocFile.delete()

    // And the producing unit behind each exported master, one `<kind>/<task>` per
    // line in m_axi_00, m_axi_01, ... order. Empty unless --raw-hbm-ports is set,
    // since a muxed port has no single producer.
    writeFile(s"$outputDirPathRTL/hbm_port_labels.txt", hbmPortLabels.mkString("\n"))

    // Bus flavour of each exported master, `axi3` or `axi4` per line. Only
    // --raw-hbm-ports can produce an axi3 port (it exports the master's own
    // flavour); it decides whether a port may reach an AXI4-only consumer such
    // as RAMA without a converting SmartConnect in front of it.
    writeFile(
      s"$outputDirPathRTL/hbm_port_protocols.txt",
      hbmPortAxi3.map(if (_) "axi3" else "axi4").mkString("\n")
    )

    // For the file in the outputDirRTL with the name of the systemDescriptor.name run sv2v on it using os.system, then remove the original file
    import sys.process._
    val svFilePath = s"$outputDirPathRTL/${systemDescriptor.name}.sv"
    val vFilePath = s"$outputDirPathRTL/${systemDescriptor.name}.v"

    // Check if the SystemVerilog file exists
    val svFile = new java.io.File(svFilePath)
    if (svFile.exists()) {
      val sv2vCommand = s"sv2v $svFilePath"
      // Get the ouput of the command instead of stdout
      val sv2vOutput = sv2vCommand.!!
      val rmCommand = s"rm $svFilePath"
      rmCommand.!

      // Write the output of sv2v to the verilog file
      val base = annotateIfaceBufferHierarchy(
        annotateAxiOutstanding(sv2vOutput, numHbmPortExports, systemDescriptor.axiNumOutstanding)
      )

      // Parse the same text the floorplan will have to match against.
      peIfaceBuffers = parsePeIfaceBuffers(base)
      println(
        s"[HardCilkEmitter] interface queues: ${peIfaceBuffers.length} PE(s) mapped, " +
          s"${peIfaceBuffers.map(_._2.length).sum} queue(s) total"
      )

      // Which instances the floorplan will move off the hub die. Derived from
      // the SAME Floorplan.plan the XDC is built from (TclGeneralConfigs calls
      // it again with these same names), so the RTL's idea of what lives on the
      // spill die cannot drift from the constraints'. If they disagreed, a PE
      // would be reset from the wrong die and the fix would be worse than
      // useless on exactly the PEs it targets.
      val names =
        if (peInstanceNames.nonEmpty) peInstanceNames
        else TclResources.Floorplan.reconstructPeInstanceNames(systemDescriptor)
      val spillPes = TclResources.Floorplan.plan(systemDescriptor, names).spillPes.toSet
      val ifaceByPe = peIfaceBuffers.toMap
      val spillInstances = spillPes ++ spillPes.flatMap(ifaceByPe.getOrElse(_, Seq.empty))

      // HC_NOC_PES (Util.NocPePolicy): keep the NoC pools and the multi-stage
      // skid chains as hierarchy (the floorplan pins them per die) and give the
      // SLR0 ones an SLR0 reset leaf. Nothing changes when the policy is off.
      val (base2, spillInst2, slr0Inst) =
        if (NocPePolicy.exportedPortSlr.isEmpty) (base, spillInstances, Set.empty[String])
        else {
          val (v, cells) = annotateNocHierarchy(base)
          val t = NocPePolicy.task(systemDescriptor).get
          val inst = names.find(_._1 == t).map(_._2).getOrElse(Seq.empty)
          val pesOn = (slr: String) => NocPePolicy.assignment(systemDescriptor).collect {
            case (i, s) if s == slr && i < inst.length => inst(i)
          }.toSet
          val onDie = (slr: String) => {
            val pes = pesOn(slr)
            pes ++ pes.flatMap(ifaceByPe.getOrElse(_, Seq.empty)) ++
              cells.collect { case (c, r) if NocPePolicy.slrOfRole(r) == slr => c }
          }
          val s0 = onDie("SLR0")
          // HC_SCHED_SLR_LOCAL: keep the per-die scheduler / notifier blocks (nested
          // in their parents, so not reset leaves here: they retime their own reset).
          val v2 = if (NocPePolicy.schedSlrLocal(systemDescriptor)) annotateSlrLocalHierarchy(v) else v
          (v2, (spillInstances ++ onDie("SLR1")) -- s0, s0)
        }

      val annotated =
        if (spillPes.isEmpty) {
          // Single-die design: one reset leaf is all there is to place, and the
          // pipeline would only add latency for nothing.
          println("[HardCilkEmitter] per-SLR reset: no spilled PEs, reset left flat")
          base2
        } else pipelinePerSlrReset(base2, systemDescriptor.name, spillInst2, slr0Inst)

      writeFile(vFilePath, annotated)

    } else {
      println(s"Error: File $svFilePath does not exist.")
    }

    RtlGenResult(
      numHbmPortExports, hbmPortWidths, ramaPortIndices, hbmPortLabels, hbmPortAxi3,
      peInstanceNames, peIfaceBuffers)
  }
}
