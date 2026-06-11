package Atomics.tests

import chisel3._
import chiseltest._
import Atomics.LockServer
import org.scalatest.ParallelTestExecution
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => scalaAssert, _}
import scala.collection.mutable

class LockServerTests
    extends AnyFlatSpec
    with ChiselScalatestTester
    with ParallelTestExecution {
  behavior of "LockServer"

  private case class Params(n: Int, p: Int, tagStoreSize: Int)

  // The arbiter needs n % (2*p) == 0 (2*p buckets, >= 1 entry each), so the
  // smallest valid configs are n = 2*p.
  private val smallParams = Params(n = 4, p = 2, tagStoreSize = 8)
  private val mediumParams = Params(n = 8, p = 4, tagStoreSize = 16)
  private val mixedParams = Params(n = 8, p = 4, tagStoreSize = 32)
  private val arbiterFairnessParams = Params(n = 32, p = 4, tagStoreSize = 16)
  private val wideParams = Params(n = 16, p = 8, tagStoreSize = 64)
  // Exactly the BFS HW lockConfig (taskDescriptors/mfpga/BFS.json): 8 helper
  // lock ports, 4 AMU lanes, 64-entry tag store.
  private val bfsHwParams = Params(n = 8, p = 4, tagStoreSize = 64)

  private val coreParams = Seq(smallParams, mediumParams, mixedParams)
  private val allParams = coreParams :+ wideParams

  private case class Request(
      pe: Int,
      isLock: Boolean,
      tag: Int,
      data: BigInt = 0,
      isBlocking: Boolean = false,
      opcodeOverride: Option[Int] = None,
      atomicModeBits: Int = 0
  )
  private case class Response(pe: Int, success: Boolean, data: BigInt)

  // tdata layout (see LockServer): tag in bits 63:0, atomic mode in bits
  // 134:133, isBlocking in bit 132, opcode in bits 131:128.
  // Operation.decode maps opcode 0 -> Unlock, 1 -> Lock.
  private def encodeReq(req: Request): BigInt = {
    val opcode = req.opcodeOverride
      .map(BigInt(_))
      .getOrElse(if (req.isLock) BigInt(1) else BigInt(0))
    val blocking = if (req.isBlocking) BigInt(1) else BigInt(0)
    val atomicMode = BigInt(req.atomicModeBits & 0x3)
    (atomicMode << 133) | (blocking << 132) | (opcode << 128) |
      (req.data << 64) | BigInt(req.tag)
  }

  private def lockAddOneReq(
      pe: Int,
      addr: Int,
      atomicModeBits: Int = 0
  ): Request =
    Request(
      pe = pe,
      isLock = true,
      tag = addr,
      opcodeOverride = Some(5),
      atomicModeBits = atomicModeBits
    )

  private def driveNoReq(dut: LockServer): Unit = {
    for (i <- 0 until dut.n) {
      dut.io.req(i).valid.poke(false.B)
      dut.io.req(i).bits.tdata.poke(0.U)
      dut.io.req(i).bits.tlast.poke(true.B)
    }
  }

  private def driveReqs(dut: LockServer, reqs: Iterable[Request]): Unit = {
    driveNoReq(dut)
    for (req <- reqs) {
      dut.io.req(req.pe).valid.poke(true.B)
      dut.io.req(req.pe).bits.tdata.poke(encodeReq(req).U)
      dut.io.req(req.pe).bits.tlast.poke(true.B)
    }
  }

  private def setRespReady(dut: LockServer): Unit = {
    for (i <- 0 until dut.n) {
      dut.io.resp(i).ready.poke(true.B)
    }
  }

  private def collectResponses(dut: LockServer): Seq[Response] = {
    val out = mutable.ArrayBuffer.empty[Response]
    for (i <- 0 until dut.n) {
      if (dut.io.resp(i).valid.peek().litToBoolean) {
        val tdata = dut.io.resp(i).bits.tdata.peek().litValue
        out += Response(
          i,
          (tdata & 1) == 1,
          (tdata >> 64) & ((BigInt(1) << 64) - 1)
        )
      }
    }
    out.toSeq
  }

  private class GMemModel(dut: LockServer, latency: Int = 0) {
    val mem = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    val arAddrs = mutable.ArrayBuffer.empty[BigInt]
    val awAddrs = mutable.ArrayBuffer.empty[BigInt]

    private val g = dut.io.gmem
    private val readResp =
      mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)] // (cd, id, data)
    private val bResp = mutable.ArrayBuffer.empty[(Int, BigInt)] // (cd, id)
    private val awQ = mutable.Queue.empty[(BigInt, BigInt)] // (id, addr)
    private val wQ = mutable.Queue.empty[(BigInt, BigInt)]

    private var capAr = (false, BigInt(0), BigInt(0))
    private var capAw = (false, BigInt(0), BigInt(0))
    private var capW = (false, BigInt(0), BigInt(0))
    private var capRIdx = -1
    private var capBIdx = -1
    private var capRConsumed = false
    private var capBConsumed = false

    private def pickReady(cds: collection.Seq[Int]): Int =
      cds.indices.find(i => cds(i) <= 0).getOrElse(-1)

    private def beatBase(addr: BigInt): BigInt = addr & ~BigInt(7)

    private def applyStrobe(old: BigInt, data: BigInt, strb: BigInt): BigInt = {
      (0 until 8).foldLeft(old) { case (acc, byte) =>
        if (((strb >> byte) & 1) == 1) {
          val clearMask = ~(BigInt(0xff) << (8 * byte))
          val newByte = ((data >> (8 * byte)) & BigInt(0xff)) << (8 * byte)
          (acc & clearMask) | newByte
        } else {
          acc
        }
      }
    }

    def beforeStep(): Unit = {
      g.ARREADY.get.poke(true.B)
      g.AWREADY.get.poke(true.B)
      g.WREADY.get.poke(true.B)

      capRIdx = pickReady(readResp.map(_._1))
      if (capRIdx >= 0) {
        val (_, id, data) = readResp(capRIdx)
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

      capBIdx = pickReady(bResp.map(_._1))
      if (capBIdx >= 0) {
        val (_, id) = bResp(capBIdx)
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
        readResp(i) =
          (math.max(0, readResp(i)._1 - 1), readResp(i)._2, readResp(i)._3)
      for (i <- bResp.indices)
        bResp(i) = (math.max(0, bResp(i)._1 - 1), bResp(i)._2)

      if (capAr._1) {
        arAddrs += capAr._3
        readResp += ((latency, capAr._2, mem(beatBase(capAr._3))))
      }
      if (capAw._1) {
        awAddrs += capAw._3
        awQ.enqueue((capAw._2, capAw._3))
      }
      if (capW._1) wQ.enqueue((capW._2, capW._3))
      while (awQ.nonEmpty && wQ.nonEmpty) {
        val (id, addr) = awQ.dequeue()
        val (data, strb) = wQ.dequeue()
        val base = beatBase(addr)
        mem(base) = applyStrobe(mem(base), data, strb)
        bResp += ((latency, id))
      }
    }
  }

  private def waitForInit(dut: LockServer, params: Params): Unit = {
    setRespReady(dut)
    driveNoReq(dut)
    val initCycles = (params.tagStoreSize / params.p) + 32
    dut.clock.step(initCycles)
  }

  private def logStart(name: String, params: Params): Unit =
    println(
      s"[LockServerTests] start $name n=${params.n} p=${params.p} tagStoreSize=${params.tagStoreSize}"
    )

  private def logDone(name: String, params: Params): Unit =
    println(
      s"[LockServerTests] done  $name n=${params.n} p=${params.p} tagStoreSize=${params.tagStoreSize}"
    )

  // Drives one request per PE per cycle (head of that PE's pending queue) and collects
  // responses until all requests have been submitted and all responses have been received.
  private def runRequests(
      dut: LockServer,
      params: Params,
      requests: Seq[Request],
      maxCycles: Int = 4000,
      mem: Option[GMemModel] = None
  ): Map[Int, Seq[(Request, Response)]] = {
    setRespReady(dut)

    val perPE =
      (0 until params.n).map(i => i -> mutable.Queue.empty[Request]).toMap
    for (r <- requests) perPE(r.pe).enqueue(r)

    val inflight =
      (0 until params.n).map(i => i -> mutable.Queue.empty[Request]).toMap
    val matched = (0 until params.n)
      .map(i => i -> mutable.ArrayBuffer.empty[(Request, Response)])
      .toMap

    def submissionDone: Boolean = perPE.values.forall(_.isEmpty)
    def inflightDone: Boolean = inflight.values.forall(_.isEmpty)

    var cycles = 0
    while ((!submissionDone || !inflightDone) && cycles < maxCycles) {
      val driving = perPE.collect { case (pe, q) if q.nonEmpty => q.head }.toSeq
      driveReqs(dut, driving)
      mem.foreach(_.beforeStep())

      val accepted =
        driving.filter(r => dut.io.req(r.pe).ready.peek().litToBoolean)

      for (resp <- collectResponses(dut)) {
        val q = inflight(resp.pe)
        scalaAssert(q.nonEmpty, s"unmatched response from PE ${resp.pe}")
        matched(resp.pe) += ((q.dequeue(), resp))
      }

      dut.clock.step()
      mem.foreach(_.afterStep())
      cycles += 1

      for (r <- accepted) {
        perPE(r.pe).dequeue()
        inflight(r.pe).enqueue(r)
      }
    }

    driveNoReq(dut)

    scalaAssert(
      submissionDone,
      s"requests not submitted within $maxCycles cycles: " +
        perPE.collect { case (pe, q) if q.nonEmpty => pe -> q.length }
    )
    scalaAssert(
      inflightDone,
      s"requests with no response within $maxCycles cycles: " +
        inflight.collect { case (pe, q) if q.nonEmpty => pe -> q.length }
    )

    matched.view.mapValues(_.toSeq).toMap
  }

  private def runLockUnlockRelock(dut: LockServer, params: Params): Unit = {
    logStart("lock-unlock-relock", params)
    waitForInit(dut, params)

    val reqs = Seq(
      Request(pe = 0, isLock = true, tag = 5),
      Request(pe = 0, isLock = false, tag = 5),
      Request(pe = 0, isLock = true, tag = 5)
    )
    val out = runRequests(dut, params, reqs)
    val responses = out(0).map(_._2.success)
    scalaAssert(
      responses == Seq(true, true, true),
      s"lock-unlock-relock expected all success, got $responses"
    )
    logDone("lock-unlock-relock", params)
  }

  private def runDistinctTagsNoContention(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("distinct-tags", params)
    waitForInit(dut, params)

    val reqs =
      Seq.tabulate(params.n)(i => Request(pe = i, isLock = true, tag = 100 + i))
    val out = runRequests(dut, params, reqs)
    val pairs = out.values.flatten.toSeq
    scalaAssert(
      pairs.size == params.n,
      s"expected ${params.n} responses, got ${pairs.size}"
    )
    scalaAssert(
      pairs.forall(_._2.success),
      s"every distinct-tag lock should succeed: ${pairs.filter(!_._2.success)}"
    )
    logDone("distinct-tags", params)
  }

  private def runSameTagSingleCycle(dut: LockServer, params: Params): Unit = {
    logStart("same-tag-single-cycle", params)
    waitForInit(dut, params)

    val reqs =
      Seq.tabulate(params.n)(i => Request(pe = i, isLock = true, tag = 42))
    val out = runRequests(dut, params, reqs)
    val pairs = out.values.flatten.toSeq
    scalaAssert(
      pairs.size == params.n,
      s"expected ${params.n} responses, got ${pairs.size}"
    )
    val successes = pairs.count(_._2.success)
    scalaAssert(
      successes == 1,
      s"exactly one PE should win the same-tag race, got $successes"
    )
    logDone("same-tag-single-cycle", params)
  }

  private def runSameTagRepeated(
      dut: LockServer,
      params: Params,
      repeats: Int
  ): Unit = {
    logStart(s"same-tag-repeated-$repeats", params)
    waitForInit(dut, params)

    val reqs = for {
      _ <- 0 until repeats
      pe <- 0 until params.n
    } yield Request(pe = pe, isLock = true, tag = 77)
    val out = runRequests(dut, params, reqs)
    val pairs = out.values.flatten.toSeq
    scalaAssert(
      pairs.size == params.n * repeats,
      s"expected ${params.n * repeats} responses, got ${pairs.size}"
    )
    val successes = pairs.count(_._2.success)
    scalaAssert(
      successes == 1,
      s"exactly one lock attempt should succeed across $repeats rounds of contention, got $successes"
    )
    logDone(s"same-tag-repeated-$repeats", params)
  }

  private def runUnlockReleasesContention(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("unlock-releases-contention", params)
    waitForInit(dut, params)

    // Round 1: everyone races for tag 9; exactly one wins.
    val round1 =
      Seq.tabulate(params.n)(i => Request(pe = i, isLock = true, tag = 9))
    val out1 = runRequests(dut, params, round1)
    val pairs1 = out1.values.flatten.toSeq
    scalaAssert(
      pairs1.count(_._2.success) == 1,
      s"round 1: expected 1 success, got ${pairs1.count(_._2.success)}"
    )
    val winner = pairs1.find(_._2.success).get._2.pe

    // PE 0 (anyone really) releases the tag.
    runRequests(dut, params, Seq(Request(pe = winner, isLock = false, tag = 9)))

    // Round 2: everyone races again; exactly one wins now that the tag is free.
    val round2 =
      Seq.tabulate(params.n)(i => Request(pe = i, isLock = true, tag = 9))
    val out2 = runRequests(dut, params, round2)
    val pairs2 = out2.values.flatten.toSeq
    scalaAssert(
      pairs2.count(_._2.success) == 1,
      s"round 2: expected 1 success, got ${pairs2.count(_._2.success)}"
    )
    logDone("unlock-releases-contention", params)
  }

  private def runUnlockOfUnlocked(dut: LockServer, params: Params): Unit = {
    logStart("unlock-of-unlocked", params)
    waitForInit(dut, params)

    // Unlock of a tag that was never held still reports success; the subsequent lock/unlock cycle works.
    val reqs = Seq(
      Request(pe = 0, isLock = false, tag = 13),
      Request(pe = 0, isLock = true, tag = 13),
      Request(pe = 0, isLock = false, tag = 13)
    )
    val out = runRequests(dut, params, reqs)
    val responses = out(0).map(_._2.success)
    scalaAssert(
      responses == Seq(true, true, true),
      s"expected all success, got $responses"
    )
    logDone("unlock-of-unlocked", params)
  }

  private def runBlockingLockWaitsForRelease(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("blocking-lock-waits-for-release", params)
    waitForInit(dut, params)

    val setup =
      runRequests(dut, params, Seq(Request(pe = 0, isLock = true, tag = 91)))
    scalaAssert(
      setup(0).head._2.success,
      s"setup lock should succeed, got $setup"
    )

    val blockingLock =
      Request(pe = 1, isLock = true, tag = 91, isBlocking = true)
    var accepted = false
    var safety = 0
    while (!accepted && safety < 20) {
      driveReqs(dut, Seq(blockingLock))
      if (dut.io.req(blockingLock.pe).ready.peek().litToBoolean) accepted = true
      dut.clock.step()
      safety += 1
    }
    scalaAssert(
      accepted,
      "blocking lock was never accepted into its input queue"
    )
    driveNoReq(dut)

    for (_ <- 0 until 60) {
      val rsps = collectResponses(dut)
      scalaAssert(
        rsps.isEmpty,
        s"blocking lock should not produce a failure response while tag is held, got $rsps"
      )
      dut.clock.step()
    }

    val release = Request(pe = 0, isLock = false, tag = 91)
    val collected = mutable.ArrayBuffer.empty[Response]
    var releaseAccepted = false
    var drainCycles = 0
    while (collected.size < 2 && drainCycles < 200) {
      driveReqs(dut, if (releaseAccepted) Seq.empty else Seq(release))
      if (
        !releaseAccepted && dut.io.req(release.pe).ready.peek().litToBoolean
      ) {
        releaseAccepted = true
      }
      collected ++= collectResponses(dut)
      dut.clock.step()
      drainCycles += 1
    }
    driveNoReq(dut)

    scalaAssert(releaseAccepted, "release unlock was never accepted")
    val releaseResp = collected
      .find(_.pe == release.pe)
      .getOrElse(
        scala.sys.error(
          s"no response from release PE ${release.pe}; collected=$collected"
        )
      )
    val lockResp = collected
      .find(_.pe == blockingLock.pe)
      .getOrElse(
        scala.sys.error(
          s"no response from blocking-lock PE ${blockingLock.pe}; collected=$collected"
        )
      )
    scalaAssert(
      releaseResp.success,
      s"release unlock should succeed, got $releaseResp"
    )
    scalaAssert(
      lockResp.success,
      s"blocking lock should succeed after release, got $lockResp"
    )
    logDone("blocking-lock-waits-for-release", params)
  }

  private def runBlockingBitIgnoredForUnlock(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("blocking-bit-ignored-for-unlock", params)
    waitForInit(dut, params)

    val out = runRequests(
      dut,
      params,
      Seq(Request(pe = 0, isLock = false, tag = 123, isBlocking = true))
    )
    val resp = out(0).head._2
    scalaAssert(
      resp.success,
      s"unlock with isBlocking set should still complete normally, got $resp"
    )
    logDone("blocking-bit-ignored-for-unlock", params)
  }

  private def runFillStoreBlocksLocks(dut: LockServer, params: Params): Unit = {
    logStart("fill-store-blocks-locks", params)
    waitForInit(dut, params)

    // Phase 1: lock every tag in the store across the available PEs.
    val phase1 = Seq.tabulate(params.tagStoreSize)(i =>
      Request(pe = i % params.n, isLock = true, tag = i)
    )
    val phase1Out = runRequests(dut, params, phase1)
    val phase1Pairs = phase1Out.values.flatten.toSeq
    scalaAssert(
      phase1Pairs.size == params.tagStoreSize,
      s"phase 1: expected ${params.tagStoreSize} responses, got ${phase1Pairs.size}"
    )
    scalaAssert(
      phase1Pairs.forall(_._2.success),
      s"phase 1: every distinct-tag lock should succeed: ${phase1Pairs.filter(!_._2.success)}"
    )

    // Phase 2: with the store full, a new lock should sit in the input queue with no response.
    val stuckLock =
      Request(pe = 0, isLock = true, tag = params.tagStoreSize + 1)
    var safety = 0
    var stuckAccepted = false
    while (!stuckAccepted && safety < 20) {
      driveReqs(dut, Seq(stuckLock))
      if (dut.io.req(stuckLock.pe).ready.peek().litToBoolean)
        stuckAccepted = true
      dut.clock.step()
      safety += 1
    }
    scalaAssert(stuckAccepted, "stuck lock never got into the input queue")
    driveNoReq(dut)

    for (_ <- 0 until 60) {
      val rsps = collectResponses(dut)
      scalaAssert(
        rsps.isEmpty,
        s"no response should arrive while the store is full and only locks are pending, got $rsps"
      )
      dut.clock.step()
    }

    // Phase 3: an unlock should still go through even though the store is full, and should
    // free up an index so the previously stuck lock can finally fire.
    val unlock =
      Request(pe = if (params.n > 1) 1 else 0, isLock = false, tag = 0)
    safety = 0
    var unlockAccepted = false
    while (!unlockAccepted && safety < 20) {
      driveReqs(dut, Seq(unlock))
      if (dut.io.req(unlock.pe).ready.peek().litToBoolean) unlockAccepted = true
      dut.clock.step()
      safety += 1
    }
    scalaAssert(
      unlockAccepted,
      "unlock never got into the input queue while store was full"
    )
    driveNoReq(dut)

    val collected = mutable.ArrayBuffer.empty[Response]
    var drainCycles = 0
    while (collected.size < 2 && drainCycles < 200) {
      collected ++= collectResponses(dut)
      dut.clock.step()
      drainCycles += 1
    }

    scalaAssert(
      collected.size == 2,
      s"expected 2 responses (unlock + previously stuck lock), got ${collected.size}: $collected"
    )
    val unlockResp = collected
      .find(_.pe == unlock.pe)
      .getOrElse(
        scala.sys.error(
          s"no response from unlock PE ${unlock.pe}; collected=$collected"
        )
      )
    val lockResp = collected
      .find(_.pe == stuckLock.pe)
      .getOrElse(
        scala.sys.error(
          s"no response from stuck-lock PE ${stuckLock.pe}; collected=$collected"
        )
      )
    scalaAssert(
      unlockResp.success,
      "unlock should report success even when the store is full"
    )
    scalaAssert(
      lockResp.success,
      "previously stuck lock should succeed once a tag was freed"
    )
    logDone("fill-store-blocks-locks", params)
  }

  private def runFreeLockEventuallyUsesAvailableRoom(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("free-lock-eventually-uses-available-room", params)
    waitForInit(dut, params)

    scalaAssert(params.n >= 4, "test requires PEs 0 through 3")
    scalaAssert(params.p >= 4, "test requires four selected request lanes")
    scalaAssert(params.tagStoreSize == 16, "test assumes a 16-entry tag store")

    // Fill slots for tags 0..14, leaving exactly one free tag-store slot.
    val fill =
      (0 until 15).map(i => Request(pe = i % params.n, isLock = true, tag = i))
    val fillOut = runRequests(dut, params, fill)
    val fillPairs = fillOut.values.flatten.toSeq
    scalaAssert(
      fillPairs.size == 15,
      s"expected 15 fill responses, got ${fillPairs.size}"
    )
    scalaAssert(
      fillPairs.forall(_._2.success),
      s"every fill lock should succeed: ${fillPairs.filter(!_._2.success)}"
    )

    val busyLocks = Seq(
      Request(pe = 0, isLock = true, tag = 0),
      Request(pe = 1, isLock = true, tag = 0),
      Request(pe = 2, isLock = true, tag = 0)
    )
    val freeLock = Request(pe = 3, isLock = true, tag = 15)
    var freeAccepted = false
    var freeResponse = Option.empty[Response]

    for (_ <- 0 until 200 if freeResponse.isEmpty) {
      val driving = if (freeAccepted) busyLocks else busyLocks :+ freeLock
      driveReqs(dut, driving)

      if (!freeAccepted && dut.io.req(freeLock.pe).ready.peek().litToBoolean) {
        freeAccepted = true
      }

      for (resp <- collectResponses(dut)) {
        if (resp.pe == freeLock.pe) {
          freeResponse = Some(resp)
        }
      }

      dut.clock.step()
    }
    driveNoReq(dut)

    scalaAssert(freeAccepted, "PE3's free lock request was never accepted")
    scalaAssert(
      freeResponse.exists(_.success),
      s"PE3's free lock should eventually succeed because one tag-store slot is available; got $freeResponse"
    )
    logDone("free-lock-eventually-uses-available-room", params)
  }

  private def runSameBucketPeEventuallySelected(
      dut: LockServer,
      params: Params,
      freePe: Int
  ): Unit = {
    logStart(s"same-bucket-pe-$freePe-eventually-selected", params)
    waitForInit(dut, params)

    scalaAssert(params.n == 32 && params.p == 4, "test assumes n=32 p=4")
    scalaAssert(params.tagStoreSize > 2, "test needs room for lock 2")

    val heldLock =
      runRequests(dut, params, Seq(Request(pe = 0, isLock = true, tag = 1)))
    val heldPairs = heldLock.values.flatten.toSeq
    scalaAssert(
      heldPairs.size == 1 && heldPairs.head._2.success,
      s"setup lock for tag 1 should succeed, got $heldPairs"
    )

    // With n=32,p=4, the input arbiter has 8 buckets of 4 PEs. PEs 4..7 share
    // one bucket. There is plenty of tag-store room, so a free lock from any
    // position in that bucket can only get stuck behind same-bucket selection.
    val bucketPes = 4 until 8
    scalaAssert(
      bucketPes.contains(freePe),
      s"PE$freePe is not in the tested bucket"
    )
    val busyLocks = bucketPes
      .filter(_ != freePe)
      .map(pe => Request(pe = pe, isLock = true, tag = 1))
    val freeLock = Request(pe = freePe, isLock = true, tag = 2)
    var freeAccepted = false
    var freeResponse = Option.empty[Response]

    for (_ <- 0 until 200 if freeResponse.isEmpty) {
      val driving = if (freeAccepted) busyLocks else busyLocks :+ freeLock
      driveReqs(dut, driving)

      if (!freeAccepted && dut.io.req(freeLock.pe).ready.peek().litToBoolean) {
        freeAccepted = true
      }

      for (resp <- collectResponses(dut)) {
        if (resp.pe == freeLock.pe) {
          freeResponse = Some(resp)
        }
      }

      dut.clock.step()
    }
    driveNoReq(dut)

    scalaAssert(
      freeAccepted,
      s"PE$freePe's free lock request was never accepted"
    )
    scalaAssert(
      freeResponse.exists(_.success),
      s"PE$freePe's free lock should eventually pass the input arbiter; got $freeResponse"
    )
    logDone(s"same-bucket-pe-$freePe-eventually-selected", params)
  }

  private def runMixedDistinctNoConflict(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("mixed-distinct-no-conflict", params)
    waitForInit(dut, params)

    // Lock distinct tags, then unlock them all, then re-lock with different tags. Everything succeeds.
    val first =
      Seq.tabulate(params.n)(i => Request(pe = i, isLock = true, tag = 200 + i))
    val release = Seq.tabulate(params.n)(i =>
      Request(pe = i, isLock = false, tag = 200 + i)
    )
    val second =
      Seq.tabulate(params.n)(i => Request(pe = i, isLock = true, tag = 300 + i))
    val out = runRequests(dut, params, first ++ release ++ second)
    val pairs = out.values.flatten.toSeq
    scalaAssert(
      pairs.size == 3 * params.n,
      s"expected ${3 * params.n} responses, got ${pairs.size}"
    )
    scalaAssert(
      pairs.forall(_._2.success),
      s"all distinct-tag operations should succeed: ${pairs.filter(!_._2.success)}"
    )
    logDone("mixed-distinct-no-conflict", params)
  }

  private def runAddOneReturnCurrent(dut: LockServer, params: Params): Unit = {
    logStart("add-one-return-current", params)
    waitForInit(dut, params)

    val mem = new GMemModel(dut)
    mem.mem(0x300) = 9
    val first = runRequests(
      dut,
      params,
      Seq(lockAddOneReq(pe = 0, addr = 0x300)),
      mem = Some(mem)
    )
    val firstResp = first(0).head._2
    scalaAssert(
      firstResp.success && firstResp.data == 9,
      s"first add-one should return previous value 9, got $firstResp"
    )
    scalaAssert(
      mem.mem(0x300) == 10,
      s"first add-one should increment memory to 10, got ${mem.mem(0x300)}"
    )

    val retryResponses = mutable.ArrayBuffer.empty[Response]
    var secondSucceeded = false
    var attempts = 0
    while (!secondSucceeded && attempts < 32) {
      val out = runRequests(
        dut,
        params,
        Seq(lockAddOneReq(pe = 0, addr = 0x300)),
        mem = Some(mem)
      )
      val resp = out(0).head._2
      retryResponses += resp
      secondSucceeded = resp.success
      attempts += 1
    }

    scalaAssert(
      secondSucceeded,
      s"second add-one should eventually succeed after retries, got $retryResponses"
    )
    scalaAssert(
      retryResponses.last.data == 10,
      s"successful second add-one should return previous value 10, got $retryResponses"
    )
    scalaAssert(
      mem.mem(0x300) == 11,
      s"second add-one should increment memory to 11, got ${mem.mem(0x300)}"
    )
    logDone("add-one-return-current", params)
  }

  private def runAddrWLowerBitsForLocks(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("addrW-lower-bits-locks", params)
    waitForInit(dut, params)

    val reqs = Seq(
      Request(pe = 0, isLock = true, tag = 0x1ff),
      Request(pe = 1, isLock = true, tag = 0x0ff)
    )
    val out = runRequests(dut, params, reqs)
    val pairs = out.values.flatten.toSeq
    scalaAssert(
      pairs.size == 2,
      s"expected two lock responses, got $pairs"
    )
    scalaAssert(
      pairs.count(_._2.success) == 1,
      s"addrW=8 should make tags 0x1ff and 0x0ff alias, got $pairs"
    )
    logDone("addrW-lower-bits-locks", params)
  }

  private def runAddrWLowerBitsForHbm(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("addrW-lower-bits-hbm", params)
    waitForInit(dut, params)

    val mem = new GMemModel(dut)
    mem.mem(0x20) = 7
    val out = runRequests(
      dut,
      params,
      Seq(lockAddOneReq(pe = 0, addr = 0x120)),
      mem = Some(mem)
    )
    val resp = out(0).head._2
    scalaAssert(
      resp.success && resp.data == 7,
      s"addrW=8 add-one should read lower address 0x20 value 7, got $resp"
    )
    scalaAssert(
      mem.mem(0x20) == 8,
      s"addrW=8 add-one should write lower address 0x20, got ${mem.mem(0x20)}"
    )
    scalaAssert(
      mem.arAddrs.contains(BigInt(0x20)) && !mem.arAddrs.contains(BigInt(0x120)),
      s"AR address should be truncated to 0x20, got ${mem.arAddrs}"
    )
    scalaAssert(
      mem.awAddrs.contains(BigInt(0x20)) && !mem.awAddrs.contains(BigInt(0x120)),
      s"AW address should be truncated to 0x20, got ${mem.awAddrs}"
    )
    logDone("addrW-lower-bits-hbm", params)
  }

  private def runAtomicModeWordFromPacket(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("atomic-mode-word-packet", params)
    waitForInit(dut, params)

    val mem = new GMemModel(dut)
    mem.mem(0x300) = BigInt("00000005aaaaaaaa", 16)
    val out = runRequests(
      dut,
      params,
      Seq(lockAddOneReq(pe = 0, addr = 0x304, atomicModeBits = 2)),
      mem = Some(mem)
    )
    val resp = out(0).head._2
    scalaAssert(
      resp.success && resp.data == BigInt(5),
      s"word add-one should return the selected upper word (5) right-justified, got $resp"
    )
    scalaAssert(
      mem.mem(0x300) == BigInt("00000006aaaaaaaa", 16),
      s"word atomic mode should update only upper word, got ${mem.mem(0x300).toString(16)}"
    )
    logDone("atomic-mode-word-packet", params)
  }

  // Reproduces HLS PE.cpp phase 2: every PE hammers the SAME address with
  // blocking LockAddOneReturnCurrent, one request outstanding per PE (the next
  // is issued only after the previous response). Exercises the AMU forward +
  // self-injected-unlock release path under full contention -- the path the
  // single-PE add-one test never touches.
  private def runConcurrentBlockingAddOne(
      dut: LockServer,
      params: Params,
      perPe: Int,
      activePes: Int,
      // Healthy operation produces a response roughly every ~40 cycles; 500 idle
      // cycles is ~10x that, so a stall this long means a genuine deadlock. Kept
      // small so the test reports the failure in a couple seconds of sim.
      stallLimit: Int = 500
  ): Unit = {
    logStart(
      s"concurrent-blocking-add-one active=$activePes perPe=$perPe",
      params
    )
    dut.clock.setTimeout(0) // we police progress ourselves
    waitForInit(dut, params)

    val addr = 0x1000
    val start = BigInt(400)
    val mem = new GMemModel(dut, latency = 8)
    mem.mem(addr) = start
    setRespReady(dut)

    val pes = 0 until activePes
    val remaining = Array.fill(params.n)(0)
    pes.foreach(pe => remaining(pe) = perPe)
    val awaiting = Array.fill(params.n)(false)
    val got = Array.fill(params.n)(0)
    def addReq(pe: Int) =
      Request(
        pe = pe,
        isLock = true,
        tag = addr,
        isBlocking = true,
        opcodeOverride = Some(5)
      )
    def allDone = pes.forall(pe => remaining(pe) == 0 && !awaiting(pe))

    var cycles = 0
    var lastProgress = 0
    val maxCycles = 300000
    while (
      !allDone && (cycles - lastProgress) < stallLimit && cycles < maxCycles
    ) {
      val driving = pes.collect {
        case pe if !awaiting(pe) && remaining(pe) > 0 => addReq(pe)
      }
      driveReqs(dut, driving.toSeq)
      mem.beforeStep()

      val accepted = driving
        .filter(r => dut.io.req(r.pe).ready.peek().litToBoolean)
        .map(_.pe)
      for (resp <- collectResponses(dut)) {
        scalaAssert(
          awaiting(resp.pe),
          s"response from PE ${resp.pe} with nothing outstanding"
        )
        scalaAssert(
          resp.success,
          s"blocking add-one should only ever respond on success, got $resp"
        )
        awaiting(resp.pe) = false
        got(resp.pe) += 1
        lastProgress = cycles
      }

      dut.clock.step()
      mem.afterStep()
      cycles += 1

      for (pe <- accepted) {
        remaining(pe) -= 1
        awaiting(pe) = true
      }
    }
    driveNoReq(dut)

    scalaAssert(
      allDone,
      s"DEADLOCK after $cycles cycles (last progress @ $lastProgress): " +
        s"remaining=${remaining.mkString(",")} awaiting=${awaiting.mkString(",")} " +
        s"got=${got.mkString(",")} mem=${mem.mem(addr)}"
    )
    scalaAssert(
      mem.mem(addr) == start + activePes * perPe,
      s"counter mismatch: got ${mem.mem(addr)}, expected ${start + activePes * perPe}"
    )
    logDone(s"concurrent-blocking-add-one active=$activePes", params)
  }

  // Faithful reproduction of BFS edgemap_process (hls-processing-elements/mfpga/
  // BFS/BFS.cpp): each helper PE drives a SINGLE in-order request stream that
  // INTERLEAVES two op kinds against one shared LockServer:
  //   - non-blocking byte SET_AND_RETURN on a visited[] address (op 2, byte
  //     mode). Multiple PEs hit the SAME visited tag -> real tag contention; the
  //     winner is forwarded to its AMU and reads previous byte 0, the losers get
  //     an immediate success=0 with no memory write.
  //   - blocking ADD_ONE on the ONE shared nextFChar counter (op 5, dword mode),
  //     maximally contended across all PEs.
  // This is the exact mix the simplified add-one-only repro never exercises. The
  // helper relies on STRICT in-order responses across that mix, so runRequests'
  // per-PE in-order matching mirrors the PE's single inflight FIFO. A dropped or
  // reordered response shows up as runRequests' "no response within N cycles"
  // (i.e. the BFS hang), and the memory checks catch any lost/duplicated atomic.
  private def runMixedVisitAndAppend(
      dut: LockServer,
      params: Params,
      neighborsPerPe: Int,
      sharedVisitedTags: Int
  ): Unit = {
    logStart(
      s"mixed-visit-append neighborsPerPe=$neighborsPerPe shared=$sharedVisitedTags",
      params
    )
    dut.clock.setTimeout(0)
    waitForInit(dut, params)

    val nextFCharAddr = 0x40000
    val visitedBase = 0x10000
    val counterStart = BigInt(1000)
    val mem = new GMemModel(dut, latency = 8)
    mem.mem(nextFCharAddr) = counterStart

    // Build each PE's interleaved script. SET targets are drawn from a small
    // shared pool so different PEs collide on the same visited byte, then every
    // SET is chased by a blocking ADD_ONE -- the worst-case ordering pressure.
    val perPeReqs = (0 until params.n).map { pe =>
      (0 until neighborsPerPe).flatMap { k =>
        val visitedTag = visitedBase + ((pe + k) % sharedVisitedTags) * 8
        Seq(
          Request(
            pe = pe,
            isLock = true,
            tag = visitedTag,
            data = 1,
            isBlocking = false,
            opcodeOverride = Some(2), // LockSetUnlockAndReturnCurrent
            atomicModeBits = 1 // byte
          ),
          Request(
            pe = pe,
            isLock = true,
            tag = nextFCharAddr,
            isBlocking = true,
            opcodeOverride = Some(5) // LockAddOneReturnCurrent
          )
        )
      }
    }
    val allReqs = perPeReqs.flatten

    // Generous bound; deadlock trips this and reports the still-pending PEs.
    val maxCycles = 200 + allReqs.size * 400
    val out = runRequests(dut, params, allReqs, maxCycles = maxCycles, mem = Some(mem))

    // Every request got exactly one in-order response (runRequests asserts this;
    // a BFS-style hang would already have failed above).
    val pairs = out.values.flatten.toSeq
    scalaAssert(
      pairs.size == allReqs.size,
      s"expected ${allReqs.size} responses, got ${pairs.size}"
    )

    // The shared counter must have been incremented exactly once per ADD_ONE,
    // with no lost or double-counted atomics.
    val addOnes = allReqs.count(_.tag == nextFCharAddr)
    scalaAssert(
      mem.mem(nextFCharAddr) == counterStart + addOnes,
      s"nextFChar counter: got ${mem.mem(nextFCharAddr)}, expected ${counterStart + addOnes}"
    )

    // The returned slots from blocking ADD_ONE are the BFS next-frontier indices.
    // They must be a contiguous, collision-free [start, start+addOnes) set -- the
    // exact property BFS.cpp's append loop depends on for non-clobbering writes.
    val addResp = out.values.flatten.collect {
      case (r, resp) if r.tag == nextFCharAddr => resp.data
    }.toSeq
    scalaAssert(
      addResp.toSet.size == addResp.size,
      s"ADD_ONE returned duplicate slots (frontier clobber): ${addResp.sorted}"
    )
    scalaAssert(
      addResp.toSet == (counterStart.toInt until counterStart.toInt + addOnes).map(BigInt(_)).toSet,
      s"ADD_ONE slots not the contiguous range [$counterStart, ${counterStart + addOnes}): ${addResp.sorted}"
    )

    // Every visited byte that any PE set must read back as 1 (test-and-set landed
    // exactly once regardless of which PE won the race).
    for (t <- 0 until sharedVisitedTags) {
      val tag = visitedBase + t * 8
      val base = tag & ~0x7
      val byteShift = (tag & 0x7) * 8
      val b = (mem.mem(base) >> byteShift) & 0xff
      scalaAssert(
        b == 1,
        s"visited byte @0x${tag.toHexString} should be 1, got $b"
      )
    }

    // Exactly one PE may observe the 0->1 transition per visited tag (previous
    // byte 0 AND success); everyone else either lost the race (success=0) or
    // arrived later (previous byte 1).
    for (t <- 0 until sharedVisitedTags) {
      val tag = visitedBase + t * 8
      val byteShift = (tag & 0x7) * 8
      val firstWinners = out.values.flatten.count {
        case (r, resp) =>
          r.tag == tag && resp.success && ((resp.data >> byteShift) & 0xff) == 0
      }
      scalaAssert(
        firstWinners == 1,
        s"visited tag 0x${tag.toHexString}: expected exactly one 0->1 winner, got $firstWinners"
      )
    }

    logDone(
      s"mixed-visit-append neighborsPerPe=$neighborsPerPe shared=$sharedVisitedTags",
      params
    )
  }

  // Reproduces the HLS PE.cpp phase-2 hang: all 8 PEs clear the barrier at the
  // same time and then hammer ONE address with blocking LockAddOneReturnCurrent,
  // one request outstanding per PE. This is the deadlock case -- the single-PE
  // and 2-PE variants pass, but full contention wedges the LockServer (no
  // response output for `stallLimit` cycles -> the test fails with DEADLOCK...).
  // lockTraceCsv prints acquire/release events so the last activity before the
  // wedge is visible in the test log.
  it should "DEADLOCK REPRO: 8 PEs concurrent blocking add-one on one address (n=8 p=4 tagStoreSize=32)" in {
    val params = mixedParams
    test(
      new LockServer(
        params.n,
        params.p,
        params.tagStoreSize,
        lockTraceCsv = true
      )
    ) { dut =>
      runConcurrentBlockingAddOne(dut, params, perPe = 3, activePes = params.n)
    }
  }

  // BFS edgemap_process at the real HW lockConfig: 8 PEs interleaving
  // non-blocking byte visited test-and-set (contended tags) with blocking
  // ADD_ONE on the single nextFChar counter, one in-order stream per PE. This is
  // the closest sim analogue of the workload that hangs on as-skitter.
  it should "BFS MIX: 8 PEs interleave contended visited set + shared blocking add-one (n=8 p=4 tagStoreSize=64)" in {
    val params = bfsHwParams
    test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
      runMixedVisitAndAppend(
        dut,
        params,
        neighborsPerPe = 3,
        sharedVisitedTags = 8
      )
    }
  }

  // Reproduces the real-helper case my other tests miss: a PE is SLOW to drain its
  // responses (fromLock is only read in edgemap_process's P1, so resp.ready drops
  // whenever the helper is staging/sending/writing memory). Each PE streams
  // back-to-back FORWARDED ops (byte SET to its own distinct tags -> every one goes
  // to the AMU). When a forwarded op's AMU return sets respValid(q) AND unmasks the
  // PE, the next op can resolve before the helper drained the first -> the 1-deep
  // per-PE response buffer overflows and a response is DROPPED (the helper then
  // waits forever for it). The LockServer's own assert
  // ("Response buffer overflow...") fires here; in real hardware (asserts stripped)
  // it silently drops, which is the BFS helper hang. A correct LockServer must not
  // need resp.ready to be continuously high.
  private def runSlowDrainNoResponseLoss(
      dut: LockServer,
      params: Params,
      opsPerPe: Int,
      activePes: Int,
      readyLowCycles: Int
  ): Unit = {
    logStart(s"slow-drain active=$activePes readyLow=$readyLowCycles", params)
    dut.clock.setTimeout(0)
    waitForInit(dut, params)
    val mem = new GMemModel(dut, latency = 6)

    val pes = 0 until activePes
    val nextSeq = Array.fill(params.n)(0)         // next op index per PE (drives tag)
    val sent = Array.fill(params.n)(0)            // requests accepted into the server
    val got = Array.fill(params.n)(0)             // responses consumed
    def pendingDone = pes.forall(pe => sent(pe) == opsPerPe && got(pe) == opsPerPe)

    var cycles = 0
    var lastProgress = 0
    val stallLimit = 3000
    while (!pendingDone && (cycles - lastProgress) < stallLimit && cycles < 200000) {
      // Drive a forwarded byte-SET to a distinct per-PE tag whenever this PE still
      // has ops to send (one outstanding at a time is enough; the server masks).
      val driving = pes.collect {
        case pe if sent(pe) < opsPerPe =>
          Request(pe = pe, isLock = true, tag = 0x10000 + pe * 0x1000 + nextSeq(pe),
            data = 1, isBlocking = false, opcodeOverride = Some(2), atomicModeBits = 1)
      }.toSeq
      driveReqs(dut, driving)
      mem.beforeStep()

      // resp.ready LOW for readyLowCycles out of every (readyLowCycles+1): the PE
      // drains only occasionally -- the whole point of the test.
      val drainNow = (cycles % (readyLowCycles + 1)) == readyLowCycles
      for (i <- 0 until dut.n) dut.io.resp(i).ready.poke((drainNow).B)

      val accepted = driving.filter(r => dut.io.req(r.pe).ready.peek().litToBoolean)
      if (drainNow) {
        for (resp <- collectResponses(dut)) {
          got(resp.pe) += 1; lastProgress = cycles
        }
      }

      dut.clock.step()
      mem.afterStep()
      cycles += 1
      for (r <- accepted) { sent(r.pe) += 1; nextSeq(r.pe) += 1; lastProgress = cycles }
    }
    driveNoReq(dut)
    for (i <- 0 until dut.n) dut.io.resp(i).ready.poke(true.B)

    scalaAssert(
      pendingDone,
      s"LOST RESPONSE / stall after $cycles cycles: sent=${sent.mkString(",")} got=${got.mkString(",")}"
    )
    logDone(s"slow-drain active=$activePes readyLow=$readyLowCycles", params)
  }

  it should "RESP-OVERFLOW REPRO: no response lost when PEs drain slowly (n=8 p=4 tagStoreSize=64)" in {
    val params = bfsHwParams
    test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
      runSlowDrainNoResponseLoss(dut, params, opsPerPe = 6, activePes = params.n,
        readyLowCycles = 20)
    }
  }

  it should "use only lower addrW bits for lock tags" in {
    val params = smallParams
    test(new LockServer(params.n, params.p, params.tagStoreSize, addrW = 8)) {
      dut =>
        runAddrWLowerBitsForLocks(dut, params)
    }
  }

  it should "use addrW-truncated addresses for HBM atomics" in {
    val params = smallParams
    test(new LockServer(params.n, params.p, params.tagStoreSize, addrW = 8)) {
      dut =>
        runAddrWLowerBitsForHbm(dut, params)
    }
  }

  it should "decode packet atomicMode bits through to HBM atomics" in {
    val params = smallParams
    test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
      runAtomicModeWordFromPacket(dut, params)
    }
  }

  for (params <- allParams) {
    val tag = s"n=${params.n} p=${params.p} tagStoreSize=${params.tagStoreSize}"

    it should s"lock, unlock, then relock the same tag from one PE ($tag)" in {
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runLockUnlockRelock(dut, params)
      }
    }

    it should s"grant every distinct-tag lock with no contention ($tag)" in {
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runDistinctTagsNoContention(dut, params)
      }
    }

    it should s"let exactly one PE win when all PEs lock the same tag the same cycle ($tag)" in {
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runSameTagSingleCycle(dut, params)
      }
    }

    it should s"grant locks/unlocks for distinct tags across rounds ($tag)" in {
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runMixedDistinctNoConflict(dut, params)
      }
    }
  }

  for (params <- coreParams) {
    val tag = s"n=${params.n} p=${params.p} tagStoreSize=${params.tagStoreSize}"

    it should s"only grant one lock when all PEs hammer the same tag for many cycles ($tag)" in {
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runSameTagRepeated(dut, params, repeats = 4)
      }
    }

    it should s"release contention after an unlock so the next race can be won ($tag)" in {
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runUnlockReleasesContention(dut, params)
      }
    }

    it should s"accept unlock for an already-released tag ($tag)" in {
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runUnlockOfUnlocked(dut, params)
      }
    }

    it should s"block locks but pass unlocks once the store is full, and unblock after release ($tag)" in {
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runFillStoreBlocksLocks(dut, params)
      }
    }
  }

  it should "eventually grant a free lock when the tag store has room (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
      runFreeLockEventuallyUsesAvailableRoom(dut, params)
    }
  }

  it should "return the current memory value and increment it for add-one atomics (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
      runAddOneReturnCurrent(dut, params)
    }
  }

  it should "retry a blocking lock while the tag is held, then grant it after unlock (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
      runBlockingLockWaitsForRelease(dut, params)
    }
  }

  it should "ignore the blocking bit on unlock requests (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
      runBlockingBitIgnoredForUnlock(dut, params)
    }
  }

  for (freePe <- 4 until 8) {
    it should s"eventually select PE$freePe in the same input-arbiter bucket (n=32 p=4 tagStoreSize=16)" in {
      val params = arbiterFairnessParams
      test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
        runSameBucketPeEventuallySelected(dut, params, freePe)
      }
    }
  }
}
