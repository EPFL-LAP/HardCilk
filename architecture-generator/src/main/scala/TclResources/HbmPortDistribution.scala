package TclResources

/**
  * Deals the raw memory masters of a `--raw-hbm-ports` design onto the
  * aggregating SmartConnects that reduce them to the HBM's port count.
  *
  * The point of the deal is *diversity*: a SmartConnect arbitrates between the
  * masters on its slave side, so putting several masters of the same kind on one
  * of them recreates the bottleneck the in-design bus-group muxing had. All the
  * `m_axi_gmem` masters of one task tend to be busy at the same time, and the
  * scheduler/allocator/notifier ports tend to be busy at other times; mixing
  * them means a burst of activity from any one kind can reach every HBM port,
  * and the ports whose other tenants are idle absorb it.
  */
object HbmPortDistribution {

  /** SmartConnect's `NUM_SI` maximum. */
  val maxSlavesPerSmartConnect = 16

  /**
    * Assign every master index `0 until labels.length` to one of `numPorts`
    * SmartConnects.
    *
    * The deal has three steps:
    *
    *  1. group the masters by label (their `<kind>/<task>`), ordering the groups
    *     largest first so the biggest kind is spread the widest;
    *  2. interleave the groups into one flat sequence by taking one master from
    *     each non-empty group per round, rotating which group starts the round.
    *     Neighbours in that sequence therefore come from different kinds, and the
    *     rotation stops the pattern from re-aligning when the number of kinds
    *     divides `numPorts` -- without it, a design with 4 kinds and 8 ports
    *     would put kind 0 on ports 0 and 4 only;
    *  3. deal the sequence round-robin: element `j` goes to SmartConnect
    *     `j % numPorts`.
    *
    * Consequences: any kind with at most `numPorts` masters lands entirely on
    * distinct SmartConnects, loads differ by at most one master, every master is
    * used exactly once, and the result depends only on the labels -- so a rerun
    * of the generator produces the same block design.
    *
    * @param labels   `<kind>/<task>` of each raw master, in `m_axi_XX` order
    * @param numPorts number of SmartConnects (i.e. of HBM ports) to deal onto
    * @return one sequence of master indices per SmartConnect, ascending within
    *         each so the `S00_AXI`, `S01_AXI`, ... slots are assigned in a
    *         predictable order
    */
  def distribute(labels: Seq[String], numPorts: Int): Seq[Seq[Int]] = {
    require(labels.nonEmpty, "[HbmPortDistribution] no masters to distribute.")
    require(numPorts >= 1, s"[HbmPortDistribution] numPorts must be >= 1, got $numPorts.")
    require(
      labels.length <= maxSlavesPerSmartConnect * numPorts,
      s"[HbmPortDistribution] ${labels.length} raw master(s) cannot be reduced to $numPorts HBM " +
        s"port(s): a SmartConnect takes at most $maxSlavesPerSmartConnect slaves, so at most " +
        s"${maxSlavesPerSmartConnect * numPorts} masters fit. Raise -r, or drop --raw-hbm-ports " +
        s"and let the design mux them by bus group."
    )

    // Largest kind first, ties broken by label so the order never depends on the
    // hash order of the grouping.
    val groups: Seq[collection.mutable.Queue[Int]] =
      labels.zipWithIndex
        .groupBy(_._1)
        .toSeq
        .map { case (label, entries) => (label, entries.map(_._2).sorted) }
        .sortBy { case (label, idxs) => (-idxs.length, label) }
        .map { case (_, idxs) => collection.mutable.Queue(idxs: _*) }

    val dealt = collection.mutable.ArrayBuffer[Int]()
    var round = 0
    while (groups.exists(_.nonEmpty)) {
      // Rotate the starting group each round, so a kind does not keep the same
      // position in the sequence and therefore the same set of ports.
      val start = round % groups.length
      for (k <- groups.indices) {
        val q = groups((start + k) % groups.length)
        if (q.nonEmpty) dealt.addOne(q.dequeue())
      }
      round += 1
    }

    val buckets = Array.fill(numPorts)(collection.mutable.ArrayBuffer[Int]())
    dealt.zipWithIndex.foreach { case (master, j) => buckets(j % numPorts).addOne(master) }
    buckets.toSeq.map(_.sorted.toSeq)
  }

  /**
    * Human-readable rendering of a [[distribute]] result, one line per
    * SmartConnect. Emitted both to the console and as comments in the generated
    * TCL, so the mapping a block design was built with is recoverable from the
    * script alone.
    */
  def summary(assignment: Seq[Seq[Int]], labels: Seq[String]): String =
    assignment.zipWithIndex
      .map { case (masters, port) =>
        val members = masters.map(m => f"m_axi_$m%02d(${labels(m)})").mkString(", ")
        f"SmartConnect $port%02d <- ${masters.length}%2d master(s): $members"
      }
      .mkString("\n")
}
