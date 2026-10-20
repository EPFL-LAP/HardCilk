package Util

import Descriptors._

/**
  * Opt-in (Alveo V80 only): PEs placed on a die other than the hub die that
  * reach HBM through regular NoC NMUs (NOC_NMU512) on THEIR OWN die instead of
  * through the HBM NMUs next to the hub.
  *
  * Enabled with `HC_NOC_PES=<SLR>:<n>[,<SLR>:<n>]` at generation time, e.g.
  * `HC_NOC_PES=SLR0:16`. Unset (the default) changes nothing: the Chisel
  * elaboration, the floorplan and the block design are byte-identical to a
  * build without this file.
  *
  * WHAT IT DOES, PER NOC PE
  * ------------------------
  *  - Chisel ([[HardCilk.HasHBMInterconnect]]): the PE's memory masters
  *    (`m_axi_gmem*`, `m_axi_argOut*`, `m_axi_spawnNext*`) are taken out of the
  *    hub's HBM muxing pool and muxed, among the NoC PEs of the same die only,
  *    inside one sub-module `noc_mem_pool_<slr>` whose outputs are exported as
  *    extra `m_axi_XX` ports after the HBM ones ([[exportedPortSlr]]).
  *  - Chisel ([[HardCilk.HardCilkBuilder]]): every hub <-> PE stream gets a
  *    chain of [[SkidBuffer]]s instead of one, one stage per die it passes
  *    through (plus one per SLL boundary on the receiving side), named
  *    `skid_<link>_<role>` with the roles of [[skidRoles]], so the floorplan can
  *    pin each stage to its die and every SLR crossing is flop-to-flop.
  *  - Floorplan: the PEs (the LAST `n` instances of [[task]]; with two groups
  *    SLR0 takes the last ones, SLR1 the ones before) are assigned to their die
  *    before the normal packing; their SLL cost is only the stream bits.
  *  - Block design: each exported NoC port goes through the usual shim / sizer
  *    / 4KB split / stripe chain into an `axi_noc_cips/Sxx_AXI` (category pl,
  *    PHYSICAL_LOC on a NOC_NMU512 site of that die), connected to all 32 HBM
  *    pseudo channels exactly like an HBM NMU (and like the CPM path).
  *
  * Other knobs: `HC_NOC_PORTS=<n>` NoC ports per die (default: one per two NoC
  * PEs, at most 8), `HC_NOC_BW=<MB/s>` booked per (NMU, pseudo channel) and direction
  * (default 100).
  */
object NocPePolicy {

  /** The hub (and HBM) die of the V80 floorplan. */
  val hubSlr: String = "SLR2"

  private val allowed = Seq("SLR0", "SLR1")

  /** `(slr, count)` groups from `HC_NOC_PES`, in the order given. */
  lazy val groups: Seq[(String, Int)] =
    (if (sys.env.get("HC_NOC_PES").exists(_.trim.nonEmpty)) sys.env.get("HC_NOC_PES")
     else slrPes.map { case Seq(_, mid, far) => s"SLR1:$mid,SLR0:$far" })
      .map(_.trim).filter(_.nonEmpty).toSeq.flatMap { spec =>
      spec.split(",").toSeq.map(_.trim).filter(_.nonEmpty).map { g =>
        g.split(":") match {
          case Array(s, n) if allowed.contains(s.trim.toUpperCase) && n.trim.matches("\\d+") =>
            s.trim.toUpperCase -> n.trim.toInt
          case _ =>
            throw new IllegalArgumentException(
              s"HC_NOC_PES=$spec: expected <SLR>:<n>[,<SLR>:<n>] with SLR in ${allowed.mkString("/")}")
        }
      }
    }.filter(_._2 > 0)

  /**
    * `HC_SLR_PES=<hub>,<mid>,<far>`: PEs of the largest task on SLR2 (HBM, hub),
    * SLR1 and SLR0. Shorthand for `HC_NOC_PES=SLR1:<mid>,SLR0:<far>` (which wins
    * when both are set); the hub count is checked against the task's PE count.
    */
  lazy val slrPes: Option[Seq[Int]] =
    sys.env.get("HC_SLR_PES").map(_.trim).filter(_.nonEmpty).map { spec =>
      val a = spec.split(",").toSeq.map(_.trim)
      require(a.length == 3 && a.forall(_.matches("\\d+")),
        s"HC_SLR_PES=$spec: expected <SLR2 PEs>,<SLR1 PEs>,<SLR0 PEs>")
      a.map(_.toInt)
    }

  /**
    * `HC_SCHED_SLR_LOCAL=1` (V80, needs HC_NOC_PES / HC_SLR_PES): the scheduler
    * of [[task]] and the argument notifiers its PEs feed are split per die (see
    * [[DataFlowScheduler.SlrLocalDataFlowScheduler]] and
    * [[ArgumentNotifier.SlrLocalArgumentNotifierNetwork]]). Unset: nothing changes.
    */
  def schedSlrLocal(desc: FullSysGenDescriptor): Boolean =
    enabled(desc) && sys.env.get("HC_SCHED_SLR_LOCAL").exists(_.trim == "1")

  /** Ways per node of the die-local distribution trees (`HC_SCHED_FANOUT`, default 8). */
  def schedFanout: Int =
    sys.env.get("HC_SCHED_FANOUT").flatMap(v => scala.util.Try(v.trim.toInt).toOption).filter(_ >= 2).getOrElse(8)

  /** Die of a PE of any task under [[schedSlrLocal]]: its NoC die, else the hub. */
  def dieOf(desc: FullSysGenDescriptor, taskName: String, peIndex: Int): String =
    slrOf(desc, taskName, peIndex).getOrElse(hubSlr)

  def enabled(desc: FullSysGenDescriptor): Boolean =
    groups.nonEmpty && desc.fpgaModel == "ALVEO_V80"

  /** The task whose PEs are moved: the one with the most PEs (ties: by name). */
  def task(desc: FullSysGenDescriptor): Option[String] =
    if (!enabled(desc)) None
    else
      desc.taskDescriptors
        .filter(t => t.peHDLPath.nonEmpty && t.numProcessingElements > 4)
        .sortBy(t => (-t.numProcessingElements, t.name))
        .headOption
        .map(_.name)

  /** PE index -> die, for the NoC PEs of [[task]]. */
  def assignment(desc: FullSysGenDescriptor): Map[Int, String] = {
    val t = task(desc)
    if (t.isEmpty) return Map.empty
    val n = desc.taskDescriptors.find(_.name == t.get).get.numProcessingElements
    // SLR0 (farthest from the hub) takes the highest indices, SLR1 the ones before.
    val ordered = groups.sortBy { case (s, _) => s }
    var hi = n
    ordered.flatMap { case (slr, cnt) =>
      val lo = math.max(0, hi - cnt)
      val r = (lo until hi).map(_ -> slr)
      hi = lo
      r
    }.toMap.ensuring(_.size < n, s"HC_NOC_PES moves every PE of ${t.get} off the hub die")
     .ensuring(m => slrPes.forall(sp => sys.env.get("HC_NOC_PES").exists(_.trim.nonEmpty) || sp.head == n - m.size),
       s"HC_SLR_PES=${slrPes.map(_.mkString(",")).getOrElse("")}: the counts must add up to the ${n} PEs of ${t.get}")
  }

  def slrOf(desc: FullSysGenDescriptor, taskName: String, peIndex: Int): Option[String] =
    if (task(desc).contains(taskName)) assignment(desc).get(peIndex) else None

  /**
    * `HC_SLR_HBM_MEM=<SLR>[,<SLR>]` (opt-in, with HC_SLR_PES / HC_NOC_PES): the
    * PEs moved to these dies keep their die (pblock, skid chain, SLR-local
    * scheduler / notifier block) but NOT the NoC memory: their masters stay in
    * the hub's HBM pool (HBM NMUs, dedicated `// hot` ports) and cross the SLLs,
    * like the PEs the floorplan spills in a flat design. Unset: empty.
    */
  lazy val hbmMemDies: Set[String] =
    sys.env.get("HC_SLR_HBM_MEM").map(_.split(",").map(_.trim.toUpperCase).filter(_.nonEmpty).toSet)
      .getOrElse(Set.empty)
      .ensuring(_.forall(allowed.contains), s"HC_SLR_HBM_MEM: expected dies among ${allowed.mkString("/")}")

  /** Die of the NoC memory pool a PE's masters go to: [[slrOf]] minus [[hbmMemDies]]. */
  def nocMemSlrOf(desc: FullSysGenDescriptor, taskName: String, peIndex: Int): Option[String] =
    slrOf(desc, taskName, peIndex).filterNot(hbmMemDies.contains)

  /**
    * Skid stage roles, in SOURCE -> SINK order, for a stream between the hub
    * (SLR2) and a NoC PE on `slr`. One stage on the hub die, one on each side
    * of every SLL boundary on the way (so each crossing is flop-to-flop and can
    * use the Laguna registers), one on the PE die.
    */
  def skidRoles(slr: String, peIsSource: Boolean): Seq[String] = {
    val hubToPe = slr match {
      case "SLR1" => Seq("slr2", "slr1")
      case _      => Seq("slr2", "slr1h", "slr1p", "slr0")
    }
    if (peIsSource) hubToPe.reverse else hubToPe
  }

  /** Die of a skid stage from its role suffix. */
  def slrOfRole(role: String): String = role.take(4).toUpperCase

  /**
    * NoC ports per die (`HC_NOC_PORTS`), default one per two NoC PEs but at
    * most 8 (and at least one per bus group, enforced by the pool). Measured on
    * the AVED shell (randomWalk, 2025.1): 8 SLR0 NMUs x 32 HBM pseudo channels
    * route (one bandwidth-relaxation warning, as with the 32 HBM NMUs alone);
    * 16 fail NoC place-and-route (Ipconfig 75-4217) even at 100 MB/s per path.
    */
  def portsFor(numPes: Int): Int =
    sys.env.get("HC_NOC_PORTS").flatMap(v => scala.util.Try(v.trim.toInt).toOption).filter(_ > 0)
      .getOrElse(math.min(8, math.max(1, (numPes + 1) / 2)))

  /**
    * `HC_NOC_HOT_PORTS=<n>|auto` (opt-in): of the `numPorts` ports of each NoC
    * memory pool, `n` (auto: half, rounded up) carry only the masters the PE's
    * Verilog marked `// hot` (the overlap wrappers' shared readers); the other
    * masters share the rest. Unset (None): the pool reduces all masters by
    * count alone, so a bus group of hot masters gets as few ports as any other.
    */
  def hotPortsFor(numPorts: Int): Option[Int] =
    sys.env.get("HC_NOC_HOT_PORTS").map(_.trim.toLowerCase).filter(_.nonEmpty).map {
      case "auto" => (numPorts + 1) / 2
      case v => scala.util.Try(v.toInt).toOption.filter(_ > 0)
        .getOrElse(throw new IllegalArgumentException(s"HC_NOC_HOT_PORTS=$v: expected a port count or auto"))
    }

  /** Requested bandwidth per (NoC NMU, HBM pseudo-channel port) and direction, MB/s. */
  def bwPerTarget: Int =
    sys.env.get("HC_NOC_BW").flatMap(v => scala.util.Try(v.trim.toInt).toOption).filter(_ > 0).getOrElse(100)

  /**
    * Die of each exported `m_axi_XX`, in export order: "HBM" for the hub's
    * HBM-NMU ports, the SLR name for NoC ports. Filled during elaboration by
    * the HBM interconnect; empty when the policy is off.
    */
  @volatile var exportedPortSlr: Seq[String] = Seq.empty

  /** Instance name of the per-die NoC memory pool module. */
  def poolInstance(slr: String): String = s"noc_mem_pool_${slr.toLowerCase}"
}
