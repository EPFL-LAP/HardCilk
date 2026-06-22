package Atomics.tests

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import Atomics.{AxiStream, LockServer}
import chext.amba.axi4
import chext.amba.axi4.Casts._
import chext.amba.axi4.full.ConnectOp._
import AXIHelpers.AxiUserYanker
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => scalaAssert, _}
import scala.collection.mutable

// Full LockServer -> HBM pipeline, assembled EXACTLY as HardCilk.connectLockServer
// does it:  LockServer.io.gmem (64-bit, id = amuId+log2(p)) -> AxiUserYanker ->
// SlaveBuffer -> ProtocolConverter (id-serialize + 64->256 upscale) ->
// vendored Widen -> a dedicated 256-bit master port.
//
// The LockServer-only chiseltests already pass at inflightDepth=5 with a 64-bit
// out-of-order memory, so any depth-2-PASS / depth-4-FAIL split must come from
// this wrapper -- the interconnect + width conversion that the bare-LockServer
// tests never exercised. This reproduces (or rules out) that split in sim.
class LockPipeline(
    val n: Int,
    val p: Int,
    val tagStoreSize: Int,
    val addrW: Int,
    val inflightDepth: Int,
    val hbmDataW: Int = 256,
    val outIdW: Option[Int] = None
) extends Module {
  val lockServer = Module(
    new LockServer(
      n,
      p,
      tagStoreSize,
      addrW = addrW,
      inflightDepth = inflightDepth
    )
  )

  // Mirror connectLockServer's outputCfg = cfgAxi4HBM.copy(wId = 2).
  // outIdW lets the fix experiment widen the HBM id so the ProtocolConverter
  // carries every AMU id uniquely (no IdSerialize collapse).
  val outputCfg = axi4.Config(
    wId = outIdW.getOrElse(2),
    wAddr = addrW,
    wData = hbmDataW,
    wUserAR = 0,
    wUserR = 0,
    wUserAW = 0,
    wUserW = 0,
    wUserB = 0
  )

  val req = IO(Vec(n, Flipped(Decoupled(new AxiStream(LockServer.ReqWidth)))))
  val resp = IO(Vec(n, Decoupled(new AxiStream(LockServer.RespWidth))))
  val m_axi = IO(axi4.Master(outputCfg)).suggestName("m_axi")

  for (i <- 0 until n) {
    lockServer.io.req(i) <> req(i)
    resp(i) <> lockServer.io.resp(i)
  }

  val pc = Module(
    new axi4.full.components.ProtocolConverter(
      new axi4.full.components.ProtocolConverterConfig(
        axiSlaveCfg = lockServer.io.gmem.cfg
          .copy(wUserAR = 0, wUserR = 0, wUserAW = 0, wUserW = 0, wUserB = 0),
        axiMasterCfg = outputCfg
      )
    )
  )
  axi4.full.SlaveBuffer(
    AxiUserYanker(lockServer.io.gmem.asFull),
    axi4.BufferConfig.all(2)
  ) :=> pc.s_axi

  val widen = Module(
    new axi4.full.components.Widen(
      new axi4.full.components.WidenConfig(outputCfg)
    )
  )
  axi4.full.SlaveBuffer(pc.m_axi, axi4.BufferConfig.all(2)) :=> widen.s_axi
  axi4.full.SlaveBuffer(widen.m_axi, axi4.BufferConfig.all(2)) :=> m_axi.asFull
}

class LockPipelineTests
    extends AnyFlatSpec
    with ChiselScalatestTester
    with org.scalatest.ParallelTestExecution {
  behavior of "LockPipeline"

  private val n = 16
  private val p = 8
  private val tagStoreSize = 64
  private val addrW = 34
  private val hbmBytes = 32 // 256-bit beat

  private case class Request(
      pe: Int,
      tag: Int,
      data: BigInt = 0,
      opcodeOverride: Int = 1,
      atomicModeBits: Int = 0
  )
  private case class Response(pe: Int, success: Boolean, data: BigInt, tag: BigInt)

  private def encodeReq(req: Request): BigInt = {
    val opcode = BigInt(req.opcodeOverride)
    val atomicMode = BigInt(req.atomicModeBits & 0x3)
    (atomicMode << 133) | (opcode << 128) | (req.data << 64) | BigInt(req.tag)
  }

  private def driveNoReq(dut: LockPipeline): Unit =
    for (i <- 0 until dut.n) {
      dut.req(i).valid.poke(false.B)
      dut.req(i).bits.tdata.poke(0.U)
      dut.req(i).bits.tlast.poke(true.B)
    }

  private def driveReqs(dut: LockPipeline, reqs: Iterable[Request]): Unit = {
    driveNoReq(dut)
    for (req <- reqs) {
      dut.req(req.pe).valid.poke(true.B)
      dut.req(req.pe).bits.tdata.poke(encodeReq(req).U)
      dut.req(req.pe).bits.tlast.poke(true.B)
    }
  }

  private def setRespReady(dut: LockPipeline): Unit =
    for (i <- 0 until dut.n) dut.resp(i).ready.poke(true.B)

  private def collectResponses(dut: LockPipeline): Seq[Response] = {
    val out = mutable.ArrayBuffer.empty[Response]
    val mask = (BigInt(1) << 64) - 1
    for (i <- 0 until dut.n)
      if (dut.resp(i).valid.peek().litToBoolean) {
        val tdata = dut.resp(i).bits.tdata.peek().litValue
        out += Response(
          i,
          (tdata & 1) == 1,
          (tdata >> 72) & mask,
          (tdata >> 8) & mask
        )
      }
    out.toSeq
  }

  // 256-bit out-of-order memory on the wrapper's m_axi. Independent random
  // latency per response so reads/writes complete out of issue order -- exactly
  // what the deployed HBM interconnect can produce. Returns/echoes the captured
  // AXI id, so any id corruption inside the wrapper shows up as wrong data.
  // hbmReorder: model the deployed Xilinx HBM switch, which preserves response
  // order for a given AXI id only WITHIN one pseudo-channel; same-id responses
  // to different pseudo-channels may reorder. channelBits selects how finely
  // addresses spread across the 32 channels.
  private class Gmem256Model(
      dut: LockPipeline,
      randomLatency: (Int, Int) = (4, 20),
      seed: Long = 1,
      hbmReorder: Boolean = false
  ) {
    private def channel(addr: BigInt): BigInt =
      if (hbmReorder) (addr >> 5) & BigInt(31) else BigInt(0)
    private val rng = new scala.util.Random(seed)
    private def nextLatency(): Int =
      randomLatency._1 +
        rng.nextInt(math.max(1, randomLatency._2 - randomLatency._1 + 1))

    val mem = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    private val g = dut.m_axi
    // (countdown, id, data, channel)
    private val readResp = mutable.ArrayBuffer.empty[(Int, BigInt, BigInt, BigInt)]
    // (countdown, id, channel)
    private val bResp = mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)]
    private val awQ = mutable.Queue.empty[(BigInt, BigInt)]
    private val wQ = mutable.Queue.empty[(BigInt, BigInt)]

    private var capAr = (false, BigInt(0), BigInt(0))
    private var capAw = (false, BigInt(0), BigInt(0))
    private var capW = (false, BigInt(0), BigInt(0))
    private var capRIdx = -1
    private var capBIdx = -1
    private var capRConsumed = false
    private var capBConsumed = false

    // AXI-compliant pick: an entry may complete only if its latency elapsed AND
    // no OLDER outstanding entry shares its id (same-id must stay in issue
    // order; different ids may reorder freely). entries are kept in issue order.
    // An entry may complete only if its latency elapsed AND no OLDER outstanding
    // entry shares its (id, channel) ordering key. With hbmReorder=false the key
    // is just the id (strict AXI per-id ordering); with hbmReorder=true the key
    // is (id, channel), so same-id responses to different channels may reorder.
    private def pickReady(keys: collection.Seq[(Int, (BigInt, BigInt))]): Int = {
      val blocked = mutable.Set.empty[(BigInt, BigInt)]
      var pick = -1
      var i = 0
      while (i < keys.size && pick < 0) {
        val (cd, key) = keys(i)
        if (cd <= 0 && !blocked.contains(key)) pick = i
        else blocked += key
        i += 1
      }
      pick
    }

    private def beatBase(addr: BigInt): BigInt = addr & ~BigInt(hbmBytes - 1)

    private def applyStrobe(old: BigInt, data: BigInt, strb: BigInt): BigInt =
      (0 until hbmBytes).foldLeft(old) { case (acc, byte) =>
        if (((strb >> byte) & 1) == 1) {
          val clearMask = ~(BigInt(0xff) << (8 * byte))
          val newByte = ((data >> (8 * byte)) & BigInt(0xff)) << (8 * byte)
          (acc & clearMask) | newByte
        } else acc
      }

    def byteAt(addr: BigInt): BigInt =
      (mem(beatBase(addr)) >> (8 * (addr & BigInt(hbmBytes - 1)).toInt)) & 0xff

    def wordAt(addr: BigInt): BigInt =
      (mem(beatBase(addr)) >> (8 * (addr & BigInt(hbmBytes - 1)).toInt)) &
        ((BigInt(1) << 32) - 1)

    def beforeStep(): Unit = {
      g.ARREADY.get.poke(true.B)
      g.AWREADY.get.poke(true.B)
      g.WREADY.get.poke(true.B)

      capRIdx = pickReady(readResp.toSeq.map(e => (e._1, (e._2, e._4))))
      if (capRIdx >= 0) {
        val (_, id, data, _) = readResp(capRIdx)
        g.RVALID.get.poke(true.B)
        g.RID.get.poke(id.U)
        g.RDATA.get.poke(data.U)
        g.RRESP.get.poke(0.U)
        g.RLAST.get.poke(true.B)
      } else {
        g.RVALID.get.poke(false.B)
        g.RID.get.poke(0.U)
        g.RDATA.get.poke(0.U)
        g.RRESP.get.poke(0.U)
        g.RLAST.get.poke(true.B)
      }

      capBIdx = pickReady(bResp.toSeq.map(e => (e._1, (e._2, e._3))))
      if (capBIdx >= 0) {
        val (_, id, _) = bResp(capBIdx)
        g.BVALID.get.poke(true.B)
        g.BID.get.poke(id.U)
        g.BRESP.get.poke(0.U)
      } else {
        g.BVALID.get.poke(false.B)
        g.BID.get.poke(0.U)
        g.BRESP.get.poke(0.U)
      }

      val arFire = g.ARVALID.get.peek().litToBoolean
      capAr = (
        arFire,
        if (arFire) g.ARID.get.peek().litValue else BigInt(0),
        if (arFire) g.ARADDR.get.peek().litValue else BigInt(0)
      )
      val awFire = g.AWVALID.get.peek().litToBoolean
      capAw = (
        awFire,
        if (awFire) g.AWID.get.peek().litValue else BigInt(0),
        if (awFire) g.AWADDR.get.peek().litValue else BigInt(0)
      )
      val wFire = g.WVALID.get.peek().litToBoolean
      capW = (
        wFire,
        if (wFire) g.WDATA.get.peek().litValue else BigInt(0),
        if (wFire) g.WSTRB.get.peek().litValue else BigInt(0)
      )
      capRConsumed = capRIdx >= 0 && g.RREADY.get.peek().litToBoolean
      capBConsumed = capBIdx >= 0 && g.BREADY.get.peek().litToBoolean
    }

    def afterStep(): Unit = {
      if (capRConsumed) readResp.remove(capRIdx)
      if (capBConsumed) bResp.remove(capBIdx)
      for (i <- readResp.indices)
        readResp(i) = (
          math.max(0, readResp(i)._1 - 1),
          readResp(i)._2,
          readResp(i)._3,
          readResp(i)._4
        )
      for (i <- bResp.indices)
        bResp(i) = (math.max(0, bResp(i)._1 - 1), bResp(i)._2, bResp(i)._3)

      if (capAr._1)
        readResp += ((nextLatency(), capAr._2, mem(beatBase(capAr._3)), channel(capAr._3)))
      if (capAw._1) awQ.enqueue((capAw._2, capAw._3))
      if (capW._1) wQ.enqueue((capW._2, capW._3))
      while (awQ.nonEmpty && wQ.nonEmpty) {
        val (id, addr) = awQ.dequeue()
        val (data, strb) = wQ.dequeue()
        val base = beatBase(addr)
        mem(base) = applyStrobe(mem(base), data, strb)
        bResp += ((nextLatency(), id, channel(addr)))
      }
    }
  }

  private def waitForInit(dut: LockPipeline): Unit = {
    setRespReady(dut)
    driveNoReq(dut)
    dut.clock.step((tagStoreSize / p) + 64)
  }

  // BFS level pattern: many distinct visited[] bytes, each test-and-set by
  // several concurrent PEs (same neighbor reached from multiple frontier
  // vertices). Invariant: every address has EXACTLY ONE first visitor.
  private def runLevel(
      dut: LockPipeline,
      inflightDepth: Int,
      distinctAddrs: Int,
      dupPerAddr: Int,
      baseAddr: Int,
      stride: Int = 1,
      latency: (Int, Int) = (4, 20),
      hbmReorder: Boolean = false
  ): Unit = {
    dut.clock.setTimeout(0)
    waitForInit(dut)
    setRespReady(dut)

    val mem =
      new Gmem256Model(dut, randomLatency = latency, hbmReorder = hbmReorder)
    val pending =
      (0 until n).map(pe => pe -> mutable.Queue.empty[Request]).toMap
    var k = 0
    for (a <- 0 until distinctAddrs; _ <- 0 until dupPerAddr) {
      val pe = k % n
      k += 1
      pending(pe).enqueue(
        Request(
          pe = pe,
          tag = baseAddr + a * stride,
          data = 1,
          opcodeOverride = 2, // LockSetUnlockAndReturnCurrent (test-and-set)
          atomicModeBits = 1 // byte
        )
      )
    }
    val total = distinctAddrs * dupPerAddr
    val got = mutable.ArrayBuffer.empty[Response]
    var cycles = 0
    val maxCycles = 400000
    while (got.size < total && cycles < maxCycles) {
      val driving = (0 until n).flatMap(pe => pending(pe).headOption)
      driveReqs(dut, driving)
      mem.beforeStep()
      val accepted =
        driving.filter(r => dut.req(r.pe).ready.peek().litToBoolean).map(_.pe)
      got ++= collectResponses(dut)
      dut.clock.step()
      mem.afterStep()
      cycles += 1
      for (pe <- accepted) pending(pe).dequeue()
    }
    driveNoReq(dut)

    scalaAssert(
      got.size == total,
      s"[depth=$inflightDepth] expected $total responses, got ${got.size} after $cycles cycles"
    )
    val byAddr = got.groupBy(_.tag)
    val bad = (0 until distinctAddrs).flatMap { a =>
      val t = BigInt(baseAddr + a * stride)
      val fv =
        byAddr.getOrElse(t, mutable.ArrayBuffer.empty).count(r => r.success && r.data == 0)
      if (fv != 1) Some((t.toString(16), fv)) else None
    }
    scalaAssert(
      bad.isEmpty,
      s"[depth=$inflightDepth] every address must have exactly one first visitor; " +
        s"offenders (addr->firstVisits)=${bad.take(20).mkString(", ")} (${bad.size} total)"
    )
    // Memory must end with every visited byte set.
    val unset = (0 until distinctAddrs).filter(a => mem.byteAt(baseAddr + a * stride) == 0)
    scalaAssert(
      unset.isEmpty,
      s"[depth=$inflightDepth] ${unset.size} visited bytes never got set, e.g. ${unset.take(20)}"
    )
  }

  // Diagnostic matrix. depth in {2,4}; memory in-order vs OOO; packed (many
  // distinct bytes share a 256-bit beat -> exercises Upscale sub-word strobes)
  // vs spaced (each byte in its own beat -> no sub-word write hazard).
  private case class Scn(
      depth: Int,
      label: String,
      hbm: Boolean,
      outIdW: Option[Int] = None
  )
  private val scenarios = Seq(
    // compliant memory (strict per-id ordering) -- interconnect is fine
    Scn(2, "compliant-ooo", false),
    Scn(4, "compliant-ooo", false),
    // DISABLED (bug reproduction, fails by design): the wId=2 id-collapse path
    // makes same-id responses span HBM pseudo-channels -> corrupt first-visitor
    // results. That collapse path has been removed from the BFS datapath (the
    // interconnect now carries native ids, see "hbm-fix" below), so this repro
    // is no longer exercised. Kept commented as documentation of the failure.
    // Scn(2, "hbm-wId2", true),
    // Scn(4, "hbm-wId2", true),
    // FIX: widen HBM id so every AMU id is carried uniquely (no IdSerialize),
    // giving each HBM id <=1 outstanding op -> same-id reorder impossible
    Scn(2, "hbm-fix", true, Some(6)),
    Scn(4, "hbm-fix", true, Some(6))
  )

  for (s <- scenarios) {
    it should s"keep one first visitor [depth=${s.depth} ${s.label}]" in {
      test(new LockPipeline(n, p, tagStoreSize, addrW, s.depth, outIdW = s.outIdW))
        .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
          runLevel(
            dut,
            inflightDepth = s.depth,
            distinctAddrs = 256,
            dupPerAddr = 4,
            baseAddr = 0x4000,
            hbmReorder = s.hbm
          )
        }
    }
  }
}
