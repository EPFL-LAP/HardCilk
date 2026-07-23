package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import chext.amba.axi4

import NewArgumentNotifier._

import scala.collection.mutable

// End-to-end tests of the whole new argument-notifier network:
//   PE-side AXI writes -> bridges -> servers (+ redirect ring)
//   evictions -> eviction ring -> CacheEvictionSaver -> "HBM"
//   missed updates -> EvictionGater -> cut network -> SlowArgumentHandler RMW
//   completed continuations -> spawner-network clients
// A single mutable map plays HBM behind BOTH the eviction saver and the slow
// handler, so the eviction-then-slow-update sequence is checked for real
// memory consistency, not just port activity.
class ArgumentNetworksTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "ArgumentNetworks (end to end)"

  private val cfg = ArgumentNetworksConfig(
    nServers = 2,
    newLanesPerServer = 1,
    updateLanesPerServer = 1,
    nSlowHandlers = 1,
    nEvictionSavers = 1,
    counterWidth = 8,
    sysAddressWidth = 64,
    realAddressWidth = 32,
    serverIDWidth = 2, // 4 slots per server -> quick wrap
    continuationSize = 128,
    updateDataWidth = 32,
    slowCutCount = 2
  )

  private val mkLine = NanTestUtil.line(cfg.counterWidth, cfg.continuationSize) _
  private val strbAll = (BigInt(1) << (cfg.continuationSize / 8)) - 1

  it should "keep every idle update input ready without inspecting its payload" in {
    test(new ArgumentNetworks(cfg)) { dut =>
      val env = new E2EEnv(dut)
      env.init()
      env.step(2)

      for (update <- dut.s_update) {
        update.valid.expect(false.B)
        update.ready.expect(true.B)
      }
    }
  }

  it should "expose one scheduler-ring leg per fast spawn lane" in {
    val twoLaneCfg = cfg.copy(
      nServers = 1,
      newLanesPerServer = 2,
      updateLanesPerServer = 2,
      slowCutCount = 1
    )
    test(new ArgumentNetworks(twoLaneCfg)) { dut =>
      assert(dut.connStealNtw.size == 3) // two fast lanes + one slow handler
    }
  }

  private class E2EEnv(dut: ArgumentNetworks) {
    val mem = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    var evictWrites = 0
    val evictWritesByPort = Array.fill(dut.m_axi_evict.size)(0)
    var slowWrites = 0
    var slowReads = 0
    val spawned =
      Array.fill(dut.connStealNtw.size)(mutable.ArrayBuffer.empty[BigInt])
    val metas = Array.fill(cfg.nSourcePEs)(
      mutable.ArrayBuffer.empty[(BigInt, BigInt, BigInt)]
    )
    val newContB = Array.fill(cfg.nSourcePEs)(0)

    // Arrays indexed by eviction-saver-port; degenerates to the old
    // single-port behavior when dut.m_axi_evict.size == 1.
    private val nEvictPorts = dut.m_axi_evict.size
    private val evictAwQ = Array.fill(nEvictPorts)(mutable.Queue.empty[BigInt])
    private val evictWQ = Array.fill(nEvictPorts)(mutable.Queue.empty[BigInt])
    private val evictBPending = Array.fill(nEvictPorts)(0)
    private val slowAwQ = mutable.Queue.empty[BigInt]
    private val slowWQ = mutable.Queue.empty[BigInt]
    private var slowBPending = 0
    // (remaining latency, address, AXI id) of in-flight slow reads, in
    // order. The id must be echoed back on `r.bits.id` -- SlowArgumentHandler
    // looks up its per-request state by that id (concurrent outstanding
    // requests get distinct ids), not by response order alone.
    private val slowRQ = mutable.Queue.empty[(Int, BigInt, BigInt)]

    def init(): Unit = {
      for (p <- dut.s_axi_newCont) {
        p.AWVALID.get.poke(false.B)
        p.WVALID.get.poke(false.B)
        p.BREADY.get.poke(true.B)
      }
      for (m <- dut.m_continuation) m.ready.poke(true.B)
      for (u <- dut.s_update) u.valid.poke(false.B)

      for (ev <- dut.m_axi_evict) {
        ev.aw.ready.poke(true.B)
        ev.w.ready.poke(true.B)
        ev.b.valid.poke(false.B)
      }

      val sl = dut.m_axi_slow(0)
      sl.aw.ready.poke(true.B)
      sl.w.ready.poke(true.B)
      sl.ar.ready.poke(true.B)
      sl.b.valid.poke(false.B)
      sl.r.valid.poke(false.B)
      sl.r.bits.last.poke(true.B)

      for (c <- dut.connStealNtw) {
        c.data.qOutTask.ready.poke(true.B)
        c.data.availableTask.valid.poke(false.B)
        c.data.availableTask.bits.poke(0.U)
        c.ctrl.serveStealReq.ready.poke(true.B)
        c.ctrl.stealReq.ready.poke(false.B)
      }
    }

    def step(n: Int = 1): Unit = {
      for (_ <- 0 until n) {
        val sl = dut.m_axi_slow(0)

        // Drive this cycle's responses from the model state.
        for (k <- 0 until nEvictPorts)
          dut.m_axi_evict(k).b.valid.poke((evictBPending(k) > 0).B)
        sl.b.valid.poke((slowBPending > 0).B)
        val rReady = slowRQ.headOption.exists(_._1 <= 0)
        sl.r.valid.poke(rReady.B)
        if (rReady) {
          sl.r.bits.data.poke(mem(slowRQ.head._2).U)
          sl.r.bits.id.poke(slowRQ.head._3.U)
        }

        // Capture everything that fires in this cycle, per eviction port.
        val evAw = Array.fill(nEvictPorts)(false)
        val evAwAddr = Array.fill(nEvictPorts)(BigInt(0))
        val evW = Array.fill(nEvictPorts)(false)
        val evWData = Array.fill(nEvictPorts)(BigInt(0))
        val evB = Array.fill(nEvictPorts)(false)
        for (k <- 0 until nEvictPorts) {
          val ev = dut.m_axi_evict(k)
          evAw(k) = ev.aw.valid.peek().litToBoolean &&
            ev.aw.ready.peek().litToBoolean
          if (evAw(k)) evAwAddr(k) = ev.aw.bits.addr.peek().litValue
          evW(k) = ev.w.valid.peek().litToBoolean &&
            ev.w.ready.peek().litToBoolean
          if (evW(k)) evWData(k) = ev.w.bits.data.peek().litValue
          evB(k) = evictBPending(k) > 0 && ev.b.ready.peek().litToBoolean
        }

        val slAw = sl.aw.valid.peek().litToBoolean &&
          sl.aw.ready.peek().litToBoolean
        val slAwAddr = if (slAw) sl.aw.bits.addr.peek().litValue else BigInt(0)
        val slW = sl.w.valid.peek().litToBoolean &&
          sl.w.ready.peek().litToBoolean
        val slWData = if (slW) sl.w.bits.data.peek().litValue else BigInt(0)
        val slAr = sl.ar.valid.peek().litToBoolean &&
          sl.ar.ready.peek().litToBoolean
        val slArAddr = if (slAr) sl.ar.bits.addr.peek().litValue else BigInt(0)
        val slArId = if (slAr) sl.ar.bits.id.peek().litValue else BigInt(0)
        val slB = slowBPending > 0 && sl.b.ready.peek().litToBoolean
        val slR = rReady && sl.r.ready.peek().litToBoolean

        for (i <- metas.indices) {
          val m = dut.m_continuation(i)
          if (m.valid.peek().litToBoolean) {
            metas(i) += ((
              m.bits.metadata.server.peek().litValue,
              m.bits.metadata.id.peek().litValue,
              m.bits.metadata.lane.peek().litValue
            ))
          }
        }
        for (k <- spawned.indices) {
          val q = dut.connStealNtw(k).data.qOutTask
          if (q.valid.peek().litToBoolean) spawned(k) += q.bits.peek().litValue
        }
        for (i <- newContB.indices) {
          if (dut.s_axi_newCont(i).BVALID.get.peek().litToBoolean)
            newContB(i) += 1
        }
        dut.clock.step()

        // Commit the model's side effects.
        for (k <- 0 until nEvictPorts) {
          if (evAw(k)) evictAwQ(k).enqueue(evAwAddr(k))
          if (evW(k)) evictWQ(k).enqueue(evWData(k))
          while (evictAwQ(k).nonEmpty && evictWQ(k).nonEmpty) {
            mem(evictAwQ(k).dequeue()) = evictWQ(k).dequeue()
            evictWrites += 1
            evictWritesByPort(k) += 1
            evictBPending(k) += 1
          }
          if (evB(k)) evictBPending(k) -= 1
        }

        if (slAw) slowAwQ.enqueue(slAwAddr)
        if (slW) slowWQ.enqueue(slWData)
        while (slowAwQ.nonEmpty && slowWQ.nonEmpty) {
          mem(slowAwQ.dequeue()) = slowWQ.dequeue()
          slowWrites += 1
          slowBPending += 1
        }
        if (slB) slowBPending -= 1

        if (slAr) {
          slowRQ.enqueue((2, slArAddr, slArId))
          slowReads += 1
        }
        if (slR) {
          slowRQ.dequeue()
        } else if (slowRQ.nonEmpty && slowRQ.head._1 > 0) {
          val (d, a, id) = slowRQ.dequeue()
          slowRQ.prepend((d - 1, a, id))
        }
      }
    }

    def waitUntil(cond: => Boolean, max: Int, what: String): Unit = {
      var guard = 0
      while (!cond) {
        step()
        guard += 1
        assert(guard < max, s"timeout waiting for: $what")
      }
    }

    // One single-beat AXI write on a PE-side slave port; AW and W acceptance
    // may happen on different cycles.
    def axiWrite(
        port: axi4.RawInterface,
        addr: BigInt,
        data: BigInt,
        strb: BigInt
    ): Unit = {
      port.AWADDR.get.poke(addr.U)
      port.AWVALID.get.poke(true.B)
      port.WDATA.get.poke(data.U)
      port.WSTRB.get.poke(strb.U)
      port.WLAST.get.poke(true.B)
      port.WVALID.get.poke(true.B)
      var aw = false
      var w = false
      var guard = 0
      while (!(aw && w)) {
        val awNow = port.AWREADY.get.peek().litToBoolean
        val wNow = port.WREADY.get.peek().litToBoolean
        step()
        if (!aw && awNow) { aw = true; port.AWVALID.get.poke(false.B) }
        if (!w && wNow) { w = true; port.WVALID.get.poke(false.B) }
        guard += 1
        assert(guard < 100, s"AXI write to 0x${addr.toString(16)} never accepted")
      }
    }

    def axiUpdate(
        index: Int,
        addr: BigInt,
        data: BigInt,
        strb: BigInt,
        server: BigInt,
        id: BigInt,
        lane: BigInt
    ): Unit = {
      val update = dut.s_update(index)
      val bitOffset = (addr & (cfg.lineBytes - 1)) * 8
      var bitStrobe = BigInt(0)
      for (byte <- 0 until cfg.updateDataWidth / 8) {
        if (((strb >> byte) & 1) != 0)
          bitStrobe |= BigInt(0xff) << (bitOffset.toInt + byte * 8)
      }
      update.bits.address.poke((addr >> cfg.lineShift).U)
      update.bits.metadata.server.poke(server.U)
      update.bits.metadata.id.poke(id.U)
      update.bits.metadata.lane.poke(lane.U)
      update.bits.dataWrite.poke((data << bitOffset.toInt).U)
      update.bits.dataWriteStrobe.poke(bitStrobe.U)
      update.valid.poke(true.B)
      var guard = 0
      while (!update.ready.peek().litToBoolean) {
        step()
        guard += 1
        assert(guard < 100, "direct update never accepted")
      }
      step()
      update.valid.poke(false.B)
    }
  }

  it should "complete a continuation in the fast path with direct and ring-redirected updates" in {
    test(new ArgumentNetworks(cfg)) { dut =>
      val env = new E2EEnv(dut)
      env.init()
      env.step(2)

      // Parent (source PE 0, statically attached to server 0) inserts a
      // continuation expecting 2 children.
      env.axiWrite(dut.s_axi_newCont(0), 0x100, mkLine(2, 0x1), strbAll)
      env.waitUntil(env.metas(0).nonEmpty, 50, "insert metadata token")
      assert(env.metas(0).head == ((BigInt(0), BigInt(0), BigInt(0))))

      // Dummy insert so the line leaves the head-1 update-guard window.
      env.axiWrite(dut.s_axi_newCont(0), 0x200, mkLine(7, 0x2), strbAll)

      // Child 1 writes through update PE 0 -> lands directly at server 0.
      env.axiUpdate(0, 0x101, 0x30, 0xf, server = 0, id = 0, lane = 0)
      // Child 2 writes through update PE 1 (attached to server 1!): the
      // network must split it off into the redirect ring and deliver it to
      // server 0's ring lane.
      env.axiUpdate(1, 0x105, 0x40, 0xf, server = 0, id = 0, lane = 0)
      env.step(30)

      // Two more inserts wrap the 4-slot cache; the wrap inspects the line,
      // finds its counter exhausted, and spawns it toward the spawner network.
      env.axiWrite(dut.s_axi_newCont(0), 0x300, mkLine(7, 0x3), strbAll)
      env.axiWrite(dut.s_axi_newCont(0), 0x400, mkLine(7, 0x4), strbAll)

      val expected = mkLine(0, 0x1 | 0x30 | (BigInt(0x40) << 32))
      env.waitUntil(env.spawned(0).nonEmpty, 150, "fast-path spawn")
      assert(env.spawned(0).toSeq == Seq(expected))

      env.step(10)
      // Fast path only: memory untouched, slow handler idle.
      assert(env.evictWrites == 0, "unexpected eviction")
      assert(env.slowReads == 0, "unexpected slow-path activity")
      // Every PE-side write was answered.
      assert(env.newContB(0) == 4)
      // Metadata ids follow the server's insertion order.
      assert(env.metas(0).map(_._2).toSeq == Seq(0, 1, 2, 3).map(BigInt(_)))
      // No stray spawns anywhere else, done latched.
      assert(env.spawned(1).isEmpty && env.spawned(2).isEmpty)
      assert(dut.done.peek().litToBoolean)
    }
  }

  it should "recover a late update via eviction, slow-handler RMW and spawn" in {
    test(new ArgumentNetworks(cfg)) { dut =>
      val env = new E2EEnv(dut)
      env.init()
      env.step(2)

      // Insert a 1-child continuation, then wrap the cache before the child
      // reports: the line is evicted and must be persisted to memory.
      env.axiWrite(dut.s_axi_newCont(0), 0x500, mkLine(1, 0x5), strbAll)
      for ((a, r) <- Seq((0x600, 0x6), (0x700, 0x7), (0x800, 0x8)))
        env.axiWrite(dut.s_axi_newCont(0), a, mkLine(7, r), strbAll)

      env.waitUntil(env.mem.contains(BigInt(0x500)), 150, "eviction reaching memory")
      assert(env.mem(BigInt(0x500)) == mkLine(1, 0x5))
      assert(env.evictWrites == 1)

      // The child's update now misses the cache, is diverted onto the slow
      // ring, and the slow handler resolves it against the evicted line.
      env.axiUpdate(0, 0x501, 0x50, 0xf, server = 0, id = 0, lane = 0)
      env.waitUntil(env.spawned(2).nonEmpty, 400, "slow-path spawn")

      assert(env.spawned(2).toSeq == Seq(mkLine(0, 0x55)))
      assert(env.slowReads == 1)
      assert(env.slowWrites == 0, "completed line was written back instead of spawned")
      assert(env.spawned(0).isEmpty && env.spawned(1).isEmpty)
      assert(dut.done.peek().litToBoolean)
    }
  }

  it should "hold an update whose eviction is still backed up in the ring" in {
    test(new ArgumentNetworks(cfg)) { dut =>
      val env = new E2EEnv(dut)
      env.init()
      env.step(2)

      // Stop HBM eviction writes so several earlier lines occupy the saver and
      // ring.  The target is inserted after one cache wrap and is therefore
      // behind older evictions when it is itself pushed out.
      dut.m_axi_evict(0).aw.ready.poke(false.B)
      dut.m_axi_evict(0).w.ready.poke(false.B)
      for (i <- 0 until 9) {
        val address = BigInt(0x3000 + i * 0x100)
        val counter = if (i == 4) BigInt(1) else BigInt(7)
        env.axiWrite(
          dut.s_axi_newCont(0),
          address,
          mkLine(counter, i),
          strbAll
        )
      }
      env.step(4)

      // Slot 0 has been reused, so this update misses the cache.  It must not
      // issue a slow read until the target eviction (metadata 0/0/0) and its
      // earlier conservative alias have both completed.
      env.axiUpdate(
        0,
        addr = 0x3401,
        data = 0x40,
        strb = 0xf,
        server = 0,
        id = 0,
        lane = 0
      )
      env.step(30)
      assert(env.evictWrites == 0)
      assert(
        env.slowReads == 0,
        "slow update overtook an eviction that was still upstream in the ring"
      )

      dut.m_axi_evict(0).aw.ready.poke(true.B)
      dut.m_axi_evict(0).w.ready.poke(true.B)
      env.waitUntil(env.spawned(2).nonEmpty, 800, "gated slow-path spawn")
      assert(env.spawned(2).contains(mkLine(0, 0x44)))
    }
  }

  it should "write a partially-updated slow-path line back to memory" in {
    test(new ArgumentNetworks(cfg)) { dut =>
      val env = new E2EEnv(dut)
      env.init()
      env.step(2)

      // 2-child continuation, evicted untouched, then ONE late update: the
      // slow handler must write the merged line back, not spawn it.
      env.axiWrite(dut.s_axi_newCont(0), 0x900, mkLine(2, 0x9), strbAll)
      for ((a, r) <- Seq((0xa00, 0xa), (0xb00, 0xb), (0xc00, 0xc)))
        env.axiWrite(dut.s_axi_newCont(0), a, mkLine(7, r), strbAll)

      env.waitUntil(env.mem.contains(BigInt(0x900)), 150, "eviction reaching memory")
      assert(env.mem(BigInt(0x900)) == mkLine(2, 0x9))

      env.axiUpdate(0, 0x901, 0x90, 0xf, server = 0, id = 0, lane = 0)
      env.waitUntil(env.slowWrites == 1, 400, "slow write-back")
      env.step(10)

      assert(env.mem(BigInt(0x900)) == mkLine(1, 0x99))
      assert(env.slowReads == 1)
      assert(env.spawned.forall(_.isEmpty), "nothing should have spawned")
      assert(!dut.done.peek().litToBoolean)
    }
  }

  it should "serve both servers independently" in {
    test(new ArgumentNetworks(cfg)) { dut =>
      val env = new E2EEnv(dut)
      env.init()
      env.step(2)

      // One 1-child continuation per server, both completed in the fast path.
      env.axiWrite(dut.s_axi_newCont(0), 0x1000, mkLine(1, 0x1), strbAll)
      env.axiWrite(dut.s_axi_newCont(1), 0x2000, mkLine(1, 0x2), strbAll)
      env.waitUntil(env.metas(1).nonEmpty, 50, "server-1 metadata token")
      assert(env.metas(1).head == ((BigInt(1), BigInt(0), BigInt(0))))

      // Shield dummies on both servers.
      env.axiWrite(dut.s_axi_newCont(0), 0x1100, mkLine(7, 0), strbAll)
      env.axiWrite(dut.s_axi_newCont(1), 0x2100, mkLine(7, 0), strbAll)

      // Each child reports through ITS OWN server's update PE (no redirect).
      env.axiUpdate(0, 0x1001, 0x11, 0xf, server = 0, id = 0, lane = 0)
      env.axiUpdate(1, 0x2001, 0x22, 0xf, server = 1, id = 0, lane = 0)
      env.step(20)

      // Wrap both caches.
      for (k <- 0 until 2) {
        env.axiWrite(dut.s_axi_newCont(0), 0x1200 + k * 0x100, mkLine(7, 0), strbAll)
        env.axiWrite(dut.s_axi_newCont(1), 0x2200 + k * 0x100, mkLine(7, 0), strbAll)
      }

      env.waitUntil(
        env.spawned(0).nonEmpty && env.spawned(1).nonEmpty,
        200,
        "spawns from both servers"
      )
      assert(env.spawned(0).toSeq == Seq(mkLine(0, 0x11 | 0x1)))
      assert(env.spawned(1).toSeq == Seq(mkLine(0, 0x22 | 0x2)))
      assert(env.evictWrites == 0 && env.slowReads == 0)
    }
  }

  it should "route evictions to independent saver lanes and resolve both via the slow path" in {
    // Two eviction-saver lanes, address-demuxed (evictionSaverOf = line address
    // % nEvictionSavers). Regression coverage for the ring-reorder bug this
    // network replaces: with a real ring, a saver-lane stall could let a
    // later same-source eviction overtake an earlier one and corrupt the
    // per-lane completion fence (EvictionGater). Here two lines are pushed to
    // DIFFERENT lanes and both resolved through the slow path, checking each
    // physical AXI port saw exactly its own write and each update released
    // off its own lane's completion.
    val multiSaverCfg = cfg.copy(nEvictionSavers = 2, evictCutCount = 1)
    test(new ArgumentNetworks(multiSaverCfg)) { dut =>
      val env = new E2EEnv(dut)
      env.init()
      env.step(2)

      // Line addresses 0x50 (even -> lane 0) and 0x51 (odd -> lane 1).
      env.axiWrite(dut.s_axi_newCont(0), 0x500, mkLine(1, 0x5), strbAll) // A
      env.axiWrite(dut.s_axi_newCont(0), 0x510, mkLine(1, 0x6), strbAll) // B
      // Three dummy inserts wrap the 4-slot cache: the 4th insert evicts A
      // (slot 0), the 5th (wrapped id) evicts B (slot 1).
      for ((a, r) <- Seq((0x600, 0x7), (0x700, 0x8), (0x800, 0x9)))
        env.axiWrite(dut.s_axi_newCont(0), a, mkLine(7, r), strbAll)

      env.waitUntil(
        env.mem.contains(BigInt(0x500)) && env.mem.contains(BigInt(0x510)),
        200,
        "both evictions reaching memory"
      )
      assert(env.mem(BigInt(0x500)) == mkLine(1, 0x5))
      assert(env.mem(BigInt(0x510)) == mkLine(1, 0x6))
      assert(env.evictWrites == 2)
      assert(env.evictWritesByPort(0) == 1, "A (even line addr) should land on saver lane 0")
      assert(env.evictWritesByPort(1) == 1, "B (odd line addr) should land on saver lane 1")

      // Both children now report late, missing the cache, diverted to the
      // slow path, and resolved against their own line's evicted data.
      env.axiUpdate(0, 0x501, 0x50, 0xf, server = 0, id = 0, lane = 0)
      env.axiUpdate(0, 0x511, 0x60, 0xf, server = 0, id = 0, lane = 0)
      env.waitUntil(env.spawned(2).size == 2, 800, "both slow-path spawns")

      assert(env.spawned(2).toSet == Set(mkLine(0, 0x55), mkLine(0, 0x66)))
      assert(env.slowReads == 2)
      assert(env.slowWrites == 0, "both lines should have spawned, not written back")
      assert(env.spawned(0).isEmpty && env.spawned(1).isEmpty)
      assert(dut.done.peek().litToBoolean)
    }
  }
}
