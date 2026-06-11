package Atomics.tests

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import Atomics.LockServer
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => scalaAssert, _}
import scala.collection.mutable

class LockServerTests extends AnyFlatSpec with ChiselScalatestTester with org.scalatest.ParallelTestExecution {
  behavior of "LockServer"

  private case class Params(n: Int, p: Int, tagStoreSize: Int)

  // The arbiter needs n % (2*p) == 0 (2*p buckets, >= 1 entry each), so the
  // smallest valid configs are n = 2*p.
  private val smallParams = Params(n = 4, p = 2, tagStoreSize = 8)
  private val mediumParams = Params(n = 8, p = 4, tagStoreSize = 16)
  private val mixedParams = Params(n = 8, p = 4, tagStoreSize = 32)
  private val arbiterFairnessParams = Params(n = 32, p = 4, tagStoreSize = 16)
  private val wideParams = Params(n = 16, p = 8, tagStoreSize = 64)

  private val coreParams = Seq(smallParams, mediumParams, mixedParams)
  private val allParams = coreParams :+ wideParams

  // Use Verilator for large circuits so treadle's symbol table doesn't OOM.
  private def testDut(params: Params)(body: LockServer => Unit): Unit = {
    val anns = if (params.n >= 16) Seq(VerilatorBackendAnnotation) else Seq()
    test(new LockServer(params.n, params.p, params.tagStoreSize))
      .withAnnotations(anns)(body)
  }

  private def testDutVerilator(params: Params)(body: LockServer => Unit): Unit =
    test(new LockServer(params.n, params.p, params.tagStoreSize))
      .withAnnotations(Seq(VerilatorBackendAnnotation))(body)

  private case class Request(
      pe: Int,
      isLock: Boolean,
      tag: Int,
      data: BigInt = 0,
      isBlocking: Boolean = false,
      opcodeOverride: Option[Int] = None,
      atomicModeBits: Int = 0,
      meta: Int = 0
  )
  private case class Response(
      pe: Int,
      success: Boolean,
      data: BigInt,
      meta: Int = 0
  )

  // tdata layout (see LockServer): tag in bits 63:0, atomic mode in bits
  // 134:133, isBlocking in bit 132, opcode in bits 131:128, metadata in bits
  // 143:136 (echoed back in the response).
  // Operation.decode maps opcode 0 -> Unlock, 1 -> Lock.
  private def encodeReq(req: Request): BigInt = {
    val opcode = req.opcodeOverride
      .map(BigInt(_))
      .getOrElse(if (req.isLock) BigInt(1) else BigInt(0))
    val blocking = if (req.isBlocking) BigInt(1) else BigInt(0)
    val atomicMode = BigInt(req.atomicModeBits & 0x3)
    (BigInt(req.meta & 0xff) << 136) |
      (atomicMode << 133) | (blocking << 132) | (opcode << 128) |
      (req.data << 64) | BigInt(req.tag)
  }

  private def lockAddNReq(
      pe: Int,
      addr: Int,
      addend: BigInt = 1,
      atomicModeBits: Int = 0
  ): Request =
    Request(
      pe = pe,
      isLock = true,
      tag = addr,
      data = addend,
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
          (tdata >> 64) & ((BigInt(1) << 64) - 1),
          ((tdata >> 136) & 0xff).toInt
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

  // Drives requests in program order per PE: a PE's next request is issued only
  // after its previous request's response arrived. The server may pipeline
  // several requests per PE and complete them out of issue order, so dependent
  // same-tag sequences (lock -> unlock -> relock) must be paced by the
  // testbench; the pipelined-* tests below cover the issue-without-waiting
  // behavior explicitly.
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

    val outstanding = Array.fill(params.n)(Option.empty[Request])
    val matched = (0 until params.n)
      .map(i => i -> mutable.ArrayBuffer.empty[(Request, Response)])
      .toMap

    def submissionDone: Boolean = perPE.values.forall(_.isEmpty)
    def inflightDone: Boolean = outstanding.forall(_.isEmpty)

    var cycles = 0
    while ((!submissionDone || !inflightDone) && cycles < maxCycles) {
      val driving = (0 until params.n).flatMap { pe =>
        if (outstanding(pe).isEmpty) perPE(pe).headOption else None
      }
      driveReqs(dut, driving)
      mem.foreach(_.beforeStep())

      val accepted =
        driving.filter(r => dut.io.req(r.pe).ready.peek().litToBoolean)

      for (resp <- collectResponses(dut)) {
        val req = outstanding(resp.pe)
        scalaAssert(req.nonEmpty, s"unmatched response from PE ${resp.pe}")
        matched(resp.pe) += ((req.get, resp))
        outstanding(resp.pe) = None
      }

      dut.clock.step()
      mem.foreach(_.afterStep())
      cycles += 1

      for (r <- accepted) {
        perPE(r.pe).dequeue()
        outstanding(r.pe) = Some(r)
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
        (0 until params.n).collect {
          case pe if outstanding(pe).nonEmpty => pe -> outstanding(pe).get
        }
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

  // The point of per-PE pipelining: one PE issues a burst of independent locks
  // without waiting for responses, correlating completions via the echoed
  // metadata. The burst is larger than the credit budget (inflightDepth=5) to
  // exercise admission throttling. Each lock is held (never released), so the
  // burst must stay within one lane's tag-store slot bucket: a PE maps to one
  // lane, which draws slots only from its own AvailableSlotTracker bucket of
  // tagStoreSize/p entries. With tagStoreSize=32, p=4 that bucket holds 8 > 6.
  private def runPipelinedLockBurst(dut: LockServer, params: Params): Unit = {
    logStart("pipelined-lock-burst", params)
    scalaAssert(
      params.tagStoreSize / params.p >= 6,
      "burst needs a per-lane slot bucket of at least 6"
    )
    waitForInit(dut, params)
    setRespReady(dut)

    val total =
      6 // just over inflightDepth=5, so admission throttling is exercised
    val pending = mutable.Queue.empty[Request] ++ (0 until total).map(i =>
      Request(pe = 0, isLock = true, tag = 0x500 + i, meta = i)
    )
    val got = mutable.ArrayBuffer.empty[Response]
    var cycles = 0
    while (got.size < total && cycles < 250) {
      if (pending.nonEmpty) driveReqs(dut, Seq(pending.head))
      else driveNoReq(dut)
      val accepted =
        pending.nonEmpty && dut.io.req(0).ready.peek().litToBoolean
      got ++= collectResponses(dut)
      dut.clock.step()
      cycles += 1
      if (accepted) pending.dequeue()
    }
    driveNoReq(dut)

    scalaAssert(
      got.size == total,
      s"expected $total responses, got ${got.size}"
    )
    scalaAssert(
      got.forall(_.success),
      s"every distinct-tag lock should succeed: ${got.filter(!_.success)}"
    )
    scalaAssert(
      got.map(_.meta).sorted.toSeq == (0 until total).toSeq,
      s"metadata should echo each request exactly once, got ${got.map(_.meta).sorted}"
    )
    logDone("pipelined-lock-burst", params)
  }

  // Single PE streams testAndSet (LockSetUnlockAndReturnCurrent) atomics to
  // distinct addresses without waiting: the pipelined forward path end to end
  // (lock commit -> AMU read-modify-write -> UnlockAndRespond release+respond).
  private def runPipelinedTestAndSetStream(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("pipelined-tas-stream", params)
    dut.clock.setTimeout(0)
    waitForInit(dut, params)
    setRespReady(dut)

    val total = 6 // just over inflightDepth=5, but much cheaper than the old 8
    val mem = new GMemModel(dut, latency = 4)
    for (i <- 0 until total) mem.mem(0x800 + 8 * i) = BigInt(10 * i + 3)
    val pending = mutable.Queue.empty[Request] ++ (0 until total).map(i =>
      Request(
        pe = 0,
        isLock = true,
        tag = 0x800 + 8 * i,
        data = BigInt(1000 + i),
        opcodeOverride = Some(2), // LockSetUnlockAndReturnCurrent
        meta = i
      )
    )
    val got = mutable.ArrayBuffer.empty[Response]
    var cycles = 0
    while (got.size < total && cycles < 600) {
      if (pending.nonEmpty) driveReqs(dut, Seq(pending.head))
      else driveNoReq(dut)
      mem.beforeStep()
      val accepted =
        pending.nonEmpty && dut.io.req(0).ready.peek().litToBoolean
      got ++= collectResponses(dut)
      dut.clock.step()
      mem.afterStep()
      cycles += 1
      if (accepted) pending.dequeue()
    }
    driveNoReq(dut)

    scalaAssert(
      got.size == total,
      s"expected $total responses, got ${got.size} after $cycles cycles"
    )
    for (r <- got) {
      scalaAssert(r.success, s"testAndSet should succeed: $r")
      scalaAssert(
        r.data == BigInt(10 * r.meta + 3),
        s"testAndSet ${r.meta} should return previous value ${10 * r.meta + 3}, got $r"
      )
    }
    for (i <- 0 until total) {
      scalaAssert(
        mem.mem(0x800 + 8 * i) == BigInt(1000 + i),
        s"address $i should hold the stored operand, got ${mem.mem(0x800 + 8 * i)}"
      )
    }
    logDone("pipelined-tas-stream", params)
  }

  // PE0 pipelines a full credit budget of blocking locks at once, all on tags
  // PE1 already holds; they spin in the replay queue (impossible in the old
  // one-in-flight design) until PE1 releases the tags, then every one completes.
  // The count is the credit budget: more than that can never be admitted while
  // none can complete, so it is not an admittable scenario. PE1 holds all the
  // tags at once, so its lane's slot bucket (tagStoreSize/p) must cover them --
  // tagStoreSize=32, p=4 gives 8.
  private def runPipelinedBlockingLocksDrain(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("pipelined-blocking-locks-drain", params)
    scalaAssert(
      params.tagStoreSize / params.p >= dut.inflightDepth,
      "test needs a per-lane slot bucket of at least inflightDepth"
    )
    waitForInit(dut, params)

    val tags = 0 until dut.inflightDepth
    val held = runRequests(
      dut,
      params,
      tags.map(t => Request(pe = 1, isLock = true, tag = 0x40 + t))
    )
    scalaAssert(
      held.values.flatten.forall(_._2.success),
      s"setup locks should succeed: $held"
    )

    // Pipeline the blocking locks from PE0 without waiting for responses.
    val pending = mutable.Queue.empty[Request] ++ tags.map(t =>
      Request(
        pe = 0,
        isLock = true,
        tag = 0x40 + t,
        isBlocking = true,
        meta = t
      )
    )
    val got = mutable.ArrayBuffer.empty[Response]
    setRespReady(dut)
    var cycles = 0
    while (pending.nonEmpty && cycles < 120) {
      driveReqs(dut, Seq(pending.head))
      val accepted = dut.io.req(0).ready.peek().litToBoolean
      got ++= collectResponses(dut)
      dut.clock.step()
      cycles += 1
      if (accepted) pending.dequeue()
    }
    driveNoReq(dut)
    scalaAssert(pending.isEmpty, "blocking locks were not all accepted")
    for (_ <- 0 until 24) {
      got ++= collectResponses(dut)
      dut.clock.step()
    }
    scalaAssert(
      got.isEmpty,
      s"blocking locks must not respond while their tags are held: $got"
    )

    // PE1 releases everything; PE0's spinning locks must all complete.
    val releases = mutable.Queue.empty[Request] ++ tags.map(t =>
      Request(pe = 1, isLock = false, tag = 0x40 + t)
    )
    val pe1got = mutable.ArrayBuffer.empty[Response]
    var drain = 0
    while ((got.size < tags.size || pe1got.size < tags.size) && drain < 800) {
      if (releases.nonEmpty) driveReqs(dut, Seq(releases.head))
      else driveNoReq(dut)
      val accepted =
        releases.nonEmpty && dut.io.req(1).ready.peek().litToBoolean
      for (r <- collectResponses(dut)) {
        if (r.pe == 0) got += r else pe1got += r
      }
      dut.clock.step()
      drain += 1
      if (accepted) releases.dequeue()
    }
    driveNoReq(dut)

    scalaAssert(
      pe1got.size == tags.size && pe1got.forall(_.success),
      s"all releases should succeed: $pe1got"
    )
    scalaAssert(
      got.size == tags.size && got.forall(_.success),
      s"every blocking lock should succeed after release: $got"
    )
    scalaAssert(
      got.map(_.meta).sorted.toSeq == tags.toSeq,
      s"metadata mismatch: ${got.map(_.meta).sorted}"
    )
    logDone("pipelined-blocking-locks-drain", params)
  }

  // A credit is released only when a response leaves io.resp. Holding the
  // response channel not-ready stops credits from freeing, so once inflightDepth
  // requests are outstanding admission must stall and the backpressure must
  // reach req.ready. Draining the responses then lets the rest flow. total is
  // chosen above the accept prefix (inflightDepth + the small input FIFO) so
  // some requests are left stranded, and below the per-lane slot bucket
  // (tagStoreSize/p) so every held lock can ultimately commit.
  private def runResponseBackpressureBlocksAccept(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("response-backpressure-blocks-accept", params)
    val depth = dut.inflightDepth
    val total = depth + 5
    scalaAssert(
      params.tagStoreSize / params.p >= total,
      "test needs a per-lane slot bucket of at least total"
    )
    waitForInit(dut, params)

    val pending = mutable.Queue.empty[Request] ++ (0 until total).map(i =>
      Request(pe = 0, isLock = true, tag = 0x600 + i, meta = i)
    )

    // Phase 1: response channel held not-ready everywhere. Credits never free.
    for (i <- 0 until dut.n) dut.io.resp(i).ready.poke(false.B)
    var accepted = 0
    var lastAcceptCycle = 0
    var cyc = 0
    while (cyc < 200) {
      if (pending.nonEmpty) driveReqs(dut, Seq(pending.head)) else driveNoReq(dut)
      val rdy = pending.nonEmpty && dut.io.req(0).ready.peek().litToBoolean
      dut.clock.step()
      cyc += 1
      if (rdy) { pending.dequeue(); accepted += 1; lastAcceptCycle = cyc }
    }
    driveNoReq(dut)

    scalaAssert(
      pending.nonEmpty,
      s"backpressure should have stopped acceptance, but all $total were accepted"
    )
    scalaAssert(
      !dut.io.req(0).ready.peek().litToBoolean,
      "req.ready should be low while the response queue is backed up"
    )
    scalaAssert(
      accepted <= depth + 4,
      s"accepted $accepted with credits frozen; expected at most inflightDepth + input FIFO"
    )
    scalaAssert(
      cyc - lastAcceptCycle >= 100,
      s"acceptance never quiesced: last accept at $lastAcceptCycle of $cyc cycles"
    )

    // Phase 2: drain. Responses flow, credits free, the rest is accepted, and
    // every request completes exactly once.
    setRespReady(dut)
    val got = mutable.ArrayBuffer.empty[Response]
    var drain = 0
    while (got.size < total && drain < 2000) {
      if (pending.nonEmpty) driveReqs(dut, Seq(pending.head)) else driveNoReq(dut)
      val rdy = pending.nonEmpty && dut.io.req(0).ready.peek().litToBoolean
      got ++= collectResponses(dut)
      dut.clock.step()
      drain += 1
      if (rdy) pending.dequeue()
    }
    driveNoReq(dut)

    scalaAssert(
      got.size == total,
      s"expected $total responses after draining, got ${got.size}"
    )
    scalaAssert(
      got.forall(_.success),
      s"all distinct-tag locks should succeed: ${got.filter(!_.success)}"
    )
    scalaAssert(
      got.map(_.meta).sorted.toSeq == (0 until total).toSeq,
      s"metadata mismatch after drain: ${got.map(_.meta).sorted}"
    )
    logDone("response-backpressure-blocks-accept", params)
  }

  // Regression for the arbiter-hold deadlock: with the tag store full, a
  // slotless lock parks in the arbiter and holds its bucket, so nothing that
  // bucket feeds can be selected -- including the AMU return that would free a
  // slot. The urgent lane-local return path must bypass the held bucket.
  //
  // Setup (n=8, p=4, tagStoreSize=16; tracker bucket l = slots 4l..4l+3,
  // offered lowest-free-first to lane l; PE q maps to lane q % 4):
  //  - PE4 locks 3 tags (lane 0 -> slots 0..2); PEs 1/5, 2/6, 3/7 lock 4 tags
  //    per lane (slots 4..15). 15 slots used, only slot 3 (lane 0) free.
  //  - PE0 pipelines a testAndSet (takes slot 3, forwarded to AMU 0; store now
  //    full) immediately followed by a plain lock (no slot anywhere -> parks in
  //    the arbiter and holds PE0's bucket).
  //  - The AMU return must still get through, release the testAndSet's tag,
  //    and hand slot 3 to the parked lock. Without the bypass this wedges.
  private def runStoreFullAmuReturnUnblocksParkedLock(
      dut: LockServer,
      params: Params
  ): Unit = {
    logStart("store-full-amu-return-unblocks-parked-lock", params)
    dut.clock.setTimeout(0)
    waitForInit(dut, params)

    scalaAssert(
      params.n == 8 && params.p == 4 && params.tagStoreSize == 16,
      "test assumes the n=8 p=4 tagStoreSize=16 lane/slot geometry"
    )
    val fill = Seq(
      Request(pe = 4, isLock = true, tag = 100),
      Request(pe = 4, isLock = true, tag = 101),
      Request(pe = 4, isLock = true, tag = 102),
      Request(pe = 1, isLock = true, tag = 110),
      Request(pe = 1, isLock = true, tag = 111),
      Request(pe = 5, isLock = true, tag = 112),
      Request(pe = 5, isLock = true, tag = 113),
      Request(pe = 2, isLock = true, tag = 120),
      Request(pe = 2, isLock = true, tag = 121),
      Request(pe = 6, isLock = true, tag = 122),
      Request(pe = 6, isLock = true, tag = 123),
      Request(pe = 3, isLock = true, tag = 130),
      Request(pe = 3, isLock = true, tag = 131),
      Request(pe = 7, isLock = true, tag = 132),
      Request(pe = 7, isLock = true, tag = 133)
    )
    val fillOut = runRequests(dut, params, fill)
    val fillPairs = fillOut.values.flatten.toSeq
    scalaAssert(
      fillPairs.size == 15 && fillPairs.forall(_._2.success),
      s"all 15 fill locks should succeed: ${fillPairs.filter(!_._2.success)}"
    )

    val mem = new GMemModel(dut, latency = 12)
    mem.mem(0x200) = 7777
    val tas = Request(
      pe = 0,
      isLock = true,
      tag = 0x200,
      data = 999,
      opcodeOverride = Some(2), // LockSetUnlockAndReturnCurrent
      meta = 1
    )
    val parkedLock = Request(pe = 0, isLock = true, tag = 0x201, meta = 2)
    val pending = mutable.Queue(tas, parkedLock)
    val got = mutable.ArrayBuffer.empty[Response]
    setRespReady(dut)
    var cycles = 0
    while (got.size < 2 && cycles < 2000) {
      if (pending.nonEmpty) driveReqs(dut, Seq(pending.head))
      else driveNoReq(dut)
      mem.beforeStep()
      val accepted =
        pending.nonEmpty && dut.io.req(0).ready.peek().litToBoolean
      got ++= collectResponses(dut)
      dut.clock.step()
      mem.afterStep()
      cycles += 1
      if (accepted) pending.dequeue()
    }
    driveNoReq(dut)

    scalaAssert(
      got.size == 2,
      s"expected testAndSet + parked lock to both complete, got $got after $cycles cycles (wedged?)"
    )
    val tasResp = got.find(_.meta == 1).get
    val lockResp = got.find(_.meta == 2).get
    scalaAssert(
      tasResp.success && tasResp.data == 7777,
      s"testAndSet should return previous value 7777, got $tasResp"
    )
    scalaAssert(
      mem.mem(0x200) == 999,
      s"testAndSet should store its operand, got ${mem.mem(0x200)}"
    )
    scalaAssert(
      lockResp.success,
      s"parked lock should acquire the freed slot, got $lockResp"
    )
    logDone("store-full-amu-return-unblocks-parked-lock", params)
  }

  // Two back-to-back add-N atomics on the same address, returning the previous
  // value and adding `addend` each time. addend=1 is the add-one special case.
  private def runAddNReturnCurrent(
      dut: LockServer,
      params: Params,
      addr: Int,
      start: BigInt,
      addend: BigInt
  ): Unit = {
    logStart(s"add-n-return-current addend=$addend", params)
    waitForInit(dut, params)

    val mem = new GMemModel(dut)
    mem.mem(addr) = start
    val first = runRequests(
      dut,
      params,
      Seq(lockAddNReq(pe = 0, addr = addr, addend = addend)),
      mem = Some(mem)
    )
    val firstResp = first(0).head._2
    scalaAssert(
      firstResp.success && firstResp.data == start,
      s"first add-N should return previous value $start, got $firstResp"
    )
    scalaAssert(
      mem.mem(addr) == start + addend,
      s"first add-N should add $addend to memory (${start + addend}), got ${mem.mem(addr)}"
    )

    val retryResponses = mutable.ArrayBuffer.empty[Response]
    var secondSucceeded = false
    var attempts = 0
    while (!secondSucceeded && attempts < 32) {
      val out = runRequests(
        dut,
        params,
        Seq(lockAddNReq(pe = 0, addr = addr, addend = addend)),
        mem = Some(mem)
      )
      val resp = out(0).head._2
      retryResponses += resp
      secondSucceeded = resp.success
      attempts += 1
    }

    scalaAssert(
      secondSucceeded,
      s"second add-N should eventually succeed after retries, got $retryResponses"
    )
    scalaAssert(
      retryResponses.last.data == start + addend,
      s"successful second add-N should return previous value ${start + addend}, got $retryResponses"
    )
    scalaAssert(
      mem.mem(addr) == start + addend + addend,
      s"second add-N should add $addend again (${start + addend + addend}), got ${mem.mem(addr)}"
    )
    logDone(s"add-n-return-current addend=$addend", params)
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
      Seq(lockAddNReq(pe = 0, addr = 0x120)),
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
      mem.arAddrs.contains(BigInt(0x20)) && !mem.arAddrs.contains(
        BigInt(0x120)
      ),
      s"AR address should be truncated to 0x20, got ${mem.arAddrs}"
    )
    scalaAssert(
      mem.awAddrs.contains(BigInt(0x20)) && !mem.awAddrs.contains(
        BigInt(0x120)
      ),
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
      Seq(lockAddNReq(pe = 0, addr = 0x304, atomicModeBits = 2)),
      mem = Some(mem)
    )
    val resp = out(0).head._2
    scalaAssert(
      resp.success && resp.data == BigInt(0x5),
      s"word add-one should return the selected previous word (lane 1 = 0x5) right-justified, got $resp"
    )
    scalaAssert(
      mem.mem(0x300) == BigInt("00000006aaaaaaaa", 16),
      s"word atomic mode should update only upper word, got ${mem.mem(0x300).toString(16)}"
    )
    logDone("atomic-mode-word-packet", params)
  }

  // Reproduces HLS PE.cpp phase 2: every PE hammers the SAME address with
  // blocking LockAddNReturnCurrent (N=1), one request outstanding per PE (the next
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
        data = 1, // add-N with N=1
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
    testDutVerilator(params) { dut =>
      runAtomicModeWordFromPacket(dut, params)
    }
  }

  it should "support single-select mode when n equals p" in {
    val params = Params(n = 4, p = 4, tagStoreSize = 8)
    test(
      new LockServer(
        params.n,
        params.p,
        params.tagStoreSize,
        singleSelect = true
      )
    ) { dut =>
      runDistinctTagsNoContention(dut, params)
    }
  }

  it should "support single-select mode when n is only divisible by p" in {
    val params = Params(n = 6, p = 2, tagStoreSize = 8)
    test(
      new LockServer(
        params.n,
        params.p,
        params.tagStoreSize,
        singleSelect = true
      )
    ) { dut =>
      runDistinctTagsNoContention(dut, params)
    }
  }

  for (params <- allParams) {
    val tag = s"n=${params.n} p=${params.p} tagStoreSize=${params.tagStoreSize}"

    it should s"lock, unlock, then relock the same tag from one PE ($tag)" in {
      testDutVerilator(params) { dut => runLockUnlockRelock(dut, params) }
    }

    it should s"grant every distinct-tag lock with no contention ($tag)" in {
      testDutVerilator(params) { dut =>
        runDistinctTagsNoContention(dut, params)
      }
    }

    it should s"let exactly one PE win when all PEs lock the same tag the same cycle ($tag)" in {
      testDutVerilator(params) { dut => runSameTagSingleCycle(dut, params) }
    }

    it should s"grant locks/unlocks for distinct tags across rounds ($tag)" in {
      testDutVerilator(params) { dut =>
        runMixedDistinctNoConflict(dut, params)
      }
    }
  }

  for (params <- coreParams) {
    val tag = s"n=${params.n} p=${params.p} tagStoreSize=${params.tagStoreSize}"

    it should s"only grant one lock when all PEs hammer the same tag for many cycles ($tag)" in {
      testDutVerilator(params) { dut =>
        runSameTagRepeated(dut, params, repeats = 4)
      }
    }

    it should s"release contention after an unlock so the next race can be won ($tag)" in {
      testDutVerilator(params) { dut =>
        runUnlockReleasesContention(dut, params)
      }
    }

    it should s"accept unlock for an already-released tag ($tag)" in {
      testDutVerilator(params) { dut => runUnlockOfUnlocked(dut, params) }
    }

    it should s"block locks but pass unlocks once the store is full, and unblock after release ($tag)" in {
      testDutVerilator(params) { dut => runFillStoreBlocksLocks(dut, params) }
    }
  }

  it should "eventually grant a free lock when the tag store has room (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    testDutVerilator(params) { dut =>
      runFreeLockEventuallyUsesAvailableRoom(dut, params)
    }
  }

  it should "return the current memory value and increment it for add-N atomics with N=1 (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    testDutVerilator(params) { dut =>
      runAddNReturnCurrent(dut, params, addr = 0x300, start = 9, addend = 1)
    }
  }

  it should "return the current memory value and add N for add-N atomics with N != 1 (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    testDutVerilator(params) { dut =>
      runAddNReturnCurrent(dut, params, addr = 0x300, start = 9, addend = 7)
    }
  }

  it should "pipeline a burst of distinct-tag locks from one PE (n=8 p=4 tagStoreSize=32)" in {
    val params = mixedParams
    testDutVerilator(params) { dut =>
      runPipelinedLockBurst(dut, params)
    }
  }

  it should "stream pipelined testAndSet atomics from one PE (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    testDutVerilator(params) { dut =>
      runPipelinedTestAndSetStream(dut, params)
    }
  }

  it should "drain pipelined blocking locks once their tags are released (n=8 p=4 tagStoreSize=32)" in {
    val params = mixedParams
    testDutVerilator(params) { dut =>
      runPipelinedBlockingLocksDrain(dut, params)
    }
  }

  it should "stop accepting requests when the response queue is backed up (n=8 p=4 tagStoreSize=64)" in {
    val params = Params(n = 8, p = 4, tagStoreSize = 64)
    testDutVerilator(params) { dut =>
      runResponseBackpressureBlocksAccept(dut, params)
    }
  }

  it should "deliver an AMU return past a parked slotless lock when the store is full (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    test(new LockServer(params.n, params.p, params.tagStoreSize)) { dut =>
      runStoreFullAmuReturnUnblocksParkedLock(dut, params)
    }
  }

  it should "retry a blocking lock while the tag is held, then grant it after unlock (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    testDutVerilator(params) { dut =>
      runBlockingLockWaitsForRelease(dut, params)
    }
  }

  it should "ignore the blocking bit on unlock requests (n=8 p=4 tagStoreSize=16)" in {
    val params = mediumParams
    testDutVerilator(params) { dut =>
      runBlockingBitIgnoredForUnlock(dut, params)
    }
  }

  for (freePe <- 4 until 8) {
    it should s"eventually select PE$freePe in the same input-arbiter bucket (n=32 p=4 tagStoreSize=16)" in {
      val params = arbiterFairnessParams
      test(new LockServer(params.n, params.p, params.tagStoreSize))
        .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
          runSameBucketPeEventuallySelected(dut, params, freePe)
        }
    }
  }

  // Reproduces the HLS PE.cpp phase-2 pressure case: all 8 PEs clear the barrier
  // at the same time and then hammer ONE address with blocking
  // LockAddNReturnCurrent (N=1), one request outstanding per PE. This used to
  // wedge when an AMU return was trapped behind a replayed lock waiting for a
  // free tag-store slot. Keep this last because it is the slowest regression.
  it should "avoid AMU-return deadlock under 8-PE blocking add-one contention (n=8 p=4 tagStoreSize=32)" in {
    val params = mixedParams
    testDutVerilator(
      params
    ) { dut =>
      runConcurrentBlockingAddOne(dut, params, perPe = 3, activePes = params.n)
    }
  }
}
