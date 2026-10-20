package ArgumentNotifier

import chisel3._
import chisel3.util._
import Allocator.AllocatorBuffer

import chext.elastic
import chext.elastic.ConnectOp._

import Util.{NocPePolicy, SlrLocal, SlrSkid}

/**
  * One die's segment of an SLR-local argument-notifier network: the input
  * queue of every argument port on that die and the chain of
  * [[ArgumentNotifierNetworkUnit]]s that merges them, exactly as one cut of
  * [[ArgumentNotifierNetwork]] (same unit, same priority rule). The chain head
  * is the segment's only output.
  */
class ArgumentNotifierSegment(addrWidth: Int, n: Int, queueDepth: Int) extends Module {
  override def desiredName: String = "hcArgNotifierSegment"

  val io = IO(new Bundle {
    val connPE = Vec(n, Flipped(DecoupledIO(UInt(addrWidth.W))))
    val head = DecoupledIO(UInt(addrWidth.W))
  })

  private val rr = SlrLocal.relayedReset(reset)
  withReset(rr) {
    val queues = Seq.fill(n)(Module(new AllocatorBuffer(addrWidth, queueDepth)))
    val units = Seq.tabulate(n)(j => Module(new ArgumentNotifierNetworkUnit(addrWidth, (n - j - 1) % n)))
    for (i <- 0 until n) {
      io.connPE(i) <> queues(i).io.addressIn
      units(i).io.peAddress <> queues(i).io.addressOut
    }
    for (j <- 0 until n - 1) units(j).io.addressIn <> units(j + 1).io.addressOut
    units(n - 1).io.addressIn.valid := false.B
    units(n - 1).io.addressIn.bits := 0.U
    io.head <> units(0).io.addressOut
  }
}

/**
  * HC_SCHED_SLR_LOCAL: [[ArgumentNotifierNetwork]] cut per die.
  *
  * The argument ports of each die (from [[NocPePolicy]]) form one segment,
  * `hcslr_argseg_<die>`, pinned to that die, so a far PE's notification queue
  * and chain unit sit next to it. Each segment head reaches the hub through a
  * registered skid chain (`hcslr_alink<k>_<role>`, one stage per die/boundary),
  * and the heads are merged per argument server exactly as the cuts of the flat
  * network are: tag demux, then a round-robin arbiter over the segments.
  *
  * Same IO as [[ArgumentNotifierNetwork]].
  *
  * @param portSlr die of every argument port (connPE index)
  */
class SlrLocalArgumentNotifierNetwork(
    addrWidth: Int,
    taskWidth: Int,
    peCount: Int,
    vasNum: Int,
    queueDepth: Int,
    multiDecrease: Boolean,
    portSlr: Seq[String]
) extends Module with HasArgumentNotifierNetworkIO {
  override def desiredName: String = "ArgumentNotifierNetworkSlrLocal"
  require(portSlr.length == peCount)
  require(isPow2(addrWidth) || multiDecrease == true)

  val io = IO(new ArgumentNotifierNetworkIO(addrWidth, peCount, vasNum))

  val tagBitsShift = log2Ceil(taskWidth / 8)

  private val dieOrder = Seq(NocPePolicy.hubSlr, "SLR1", "SLR0")
  val groups: Seq[(String, Seq[Int])] =
    dieOrder.map(d => d -> portSlr.indices.filter(portSlr(_) == d)).filter(_._2.nonEmpty)
  require(groups.map(_._2.length).sum == peCount)

  println(
    s"[SlrLocalArgumentNotifierNetwork] " +
      groups.map { case (d, l) => s"$d ${l.length} port(s)" }.mkString(", ")
  )

  val arbs = io.connVAS.map(x =>
    Module(new elastic.BasicArbiter(chiselTypeOf(x.bits), groups.length, chooserFn = elastic.RoundRobinChooser()))
  )
  arbs.zip(io.connVAS).foreach(x => x._1.io.sink <> x._2)
  arbs.foreach(x => x.io.select.deq())

  groups.zipWithIndex.foreach { case ((die, ports), k) =>
    val outward =
      if (die == NocPePolicy.hubSlr) Seq(NocPePolicy.hubSlr.toLowerCase)
      else NocPePolicy.skidRoles(die, peIsSource = false)
    val resets = SlrLocal.resetChain(reset, s"alink$k", outward)
    val seg = withReset(resets(outward.last))(Module(new ArgumentNotifierSegment(addrWidth, ports.length, queueDepth)))
    seg.suggestName(s"${SlrLocal.prefix}argseg_${die.toLowerCase}")
    ports.zipWithIndex.foreach { case (p, j) => seg.io.connPE(j) <> io.connPE(p) }

    val head = SlrSkid.chain(seg.io.head, s"alink$k", outward.reverse, resets)

    val demux = Module(new elastic.Demux(UInt(addrWidth.W), vasNum))
    arbs.zip(demux.io.sinks).foreach(x => x._2 :=> x._1.io.sources(k))
    new elastic.Fork(head) {
      protected def onFork: Unit = {
        fork() :=> demux.io.source
        new elastic.Transform(fork(), demux.io.select) {
          protected def onTransform: Unit = {
            out := (in >> tagBitsShift.U) % vasNum.U
          }
        }
      }
    }
  }
}
