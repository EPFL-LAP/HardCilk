package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.ArgumentServer

// The delay-line cache. Key behaviors under test:
//  * inserts hand out (id, lane) metadata,
//  * matched updates accumulate (OR payload, count decrements),
//  * a line is RESOLVED when the write pointer wraps back to it, or when an
//    idle lane contains completed work and begins an ordered fallback drain,
//  * updates that cannot be applied safely are diverted to the slow path
//    instead of being silently dropped.
class ArgumentServerCacheTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "ArgumentServer (new, delay-line cache)"

  private val counterWidth = 8
  private val lineAddressWidth = 28
  private val serverTagWidth = 1
  private val serverIDWidth = 2 // 4 slots -> quick wrap
  private val continuationSize = 128
  private val slots = 1 << serverIDWidth

  private val mkLine = NanTestUtil.line(counterWidth, continuationSize) _

  private def dutGen(
      nNew: Int = 1,
      nUpd: Int = 2,
      cacheDelayCycles: Int = 0,
      missedUpdateExtra: Int = 8
  ) =
    new ArgumentServer(
      counterWidth,
      lineAddressWidth,
      serverTagWidth,
      serverIDWidth,
      continuationSize,
      nNew,
      nUpd,
      cacheDelayCycles = cacheDelayCycles,
      missedUpdateExtra = missedUpdateExtra
    )

  private def init(dut: ArgumentServer, nNew: Int, nUpd: Int): Unit = {
    for (i <- 0 until nNew) {
      dut.io.newContInput(i).req.valid.poke(false.B)
      dut.io.spawnTaskOutputs(i).ready.poke(true.B)
      dut.io.coupledSlowPath(i).ready.poke(true.B)
    }
    for (i <- 0 until nUpd) {
      dut.io.contUpdateInput(i).valid.poke(false.B)
    }
  }

  // Insert a line on `lane` and return the assigned cache id.
  private def insert(
      dut: ArgumentServer,
      lane: Int,
      addr: BigInt,
      counter: BigInt,
      remainder: BigInt
  ): BigInt = {
    val port = dut.io.newContInput(lane)
    port.req.bits.address.poke(addr.U)
    port.req.bits.taskBaseData.poke(mkLine(counter, remainder).U)
    port.req.valid.poke(true.B)
    var guard = 0
    while (!port.req.ready.peek().litToBoolean) {
      dut.clock.step(); guard += 1; assert(guard < 50, "insert never accepted")
    }
    val id = port.assignedId.peek().litValue
    port.assignedLane.expect(lane.U)
    dut.clock.step()
    port.req.valid.poke(false.B)
    id
  }

  private def update(
      dut: ArgumentServer,
      inputIdx: Int,
      lane: Int,
      id: BigInt,
      addr: BigInt,
      data: BigInt,
      strobe: BigInt
  ): Unit = {
    val port = dut.io.contUpdateInput(inputIdx)
    port.bits.address.poke(addr.U)
    port.bits.metadata.server.poke(0.U)
    port.bits.metadata.id.poke(id.U)
    port.bits.metadata.lane.poke(lane.U)
    // Test payload offsets are relative to the bytes after the counter. The
    // physical continuation ABI places that payload above counterWidth.
    port.bits.dataWrite.poke((data << counterWidth).U)
    port.bits.dataWriteStrobe.poke((strobe << counterWidth).U)
    port.valid.poke(true.B)
    var guard = 0
    while (!port.ready.peek().litToBoolean) {
      dut.clock.step(); guard += 1; assert(guard < 50, "update never accepted")
    }
    dut.clock.step()
    port.valid.poke(false.B)
  }

  private def expectSpawn(
      dut: ArgumentServer,
      lane: Int,
      taskData: BigInt,
      within: Int = 30
  ): Unit = {
    val port = dut.io.spawnTaskOutputs(lane)
    var guard = 0
    while (!port.valid.peek().litToBoolean) {
      dut.clock.step(); guard += 1
      assert(guard < within, "spawn never appeared")
    }
    port.bits.taskData.expect(taskData.U)
    dut.clock.step()
  }

  private def expectEvict(
      dut: ArgumentServer,
      lane: Int,
      addr: BigInt,
      taskData: BigInt,
      within: Int = 30
  ): Unit = {
    val port = dut.io.coupledSlowPath(lane)
    var guard = 0
    while (!(port.valid.peek().litToBoolean &&
      port.bits.evictionValid.peek().litToBoolean)) {
      dut.clock.step(); guard += 1
      assert(guard < within, "eviction never appeared")
    }
    port.bits.eviction.eviction.address.expect(addr.U)
    port.bits.eviction.eviction.taskData.expect(taskData.U)
    dut.clock.step()
  }

  private def expectSlowDivert(
      dut: ArgumentServer,
      lane: Int,
      addr: BigInt,
      within: Int = 30
  ): Unit = {
    val port = dut.io.coupledSlowPath(lane)
    var guard = 0
    while (!(port.valid.peek().litToBoolean &&
      port.bits.updateValid.peek().litToBoolean)) {
      dut.clock.step(); guard += 1
      assert(guard < within, "diverted update never appeared")
    }
    port.bits.update.update.address.expect(addr.U)
    dut.clock.step()
  }

  it should "assign consecutive cache ids on insert" in {
    test(dutGen()) { dut =>
      init(dut, 1, 2)
      assert(insert(dut, 0, 0x100, 1, 0x1) == BigInt(0))
      assert(insert(dut, 0, 0x200, 1, 0x2) == BigInt(1))
      assert(insert(dut, 0, 0x300, 1, 0x3) == BigInt(2))
    }
  }

  it should "advance porch entries through bubbles before committing them" in {
    val delay = 3
    test(dutGen(cacheDelayCycles = delay)) { dut =>
      init(dut, 1, 2)

      val id = insert(dut, 0, 0x100, counter = 1, remainder = 0x1)
      assert(id == 0)

      // No further requests arrive. The enabled porch must still shift its
      // valid bit through the intervening bubbles and make the later update a
      // cache hit. If bubbles froze the porch, this update would be diverted.
      dut.clock.step(delay)
      update(dut, 0, 0, id, 0x100, data = 0x10, strobe = 0xff)
      expectSpawn(dut, 0, mkLine(0, 0x11), within = 10 + slots + 10)
    }
  }

  it should "absorb spawn backpressure without stalling accepted porch entries" in {
    val delay = 12
    test(dutGen(cacheDelayCycles = delay)) { dut =>
      init(dut, 1, 2)
      dut.io.spawnTaskOutputs(0).ready.poke(false.B)
      dut.io.coupledSlowPath(0).ready.poke(false.B)

      val port = dut.io.newContInput(0)
      val n = (slots - 1) + delay
      val addrs = (0 until n).map(k => BigInt(0x1000 + 0x100 * k))
      val ids = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var next = 0
      var cycles = 0
      while (next < n && cycles < 600) {
        port.req.bits.address.poke(addrs(next).U)
        // Older lines resolve as spawns and fill the blocked spawn path. The final
        // cache lap remains counting so we can probe whether it became searchable.
        val counter = if (next >= n - (slots - 1)) 7 else 0
        port.req.bits.taskBaseData.poke(mkLine(counter, next).U)
        port.req.valid.poke(true.B)
        if (port.req.ready.peek().litToBoolean) {
          ids += port.assignedId.peek().litValue
          next += 1
        }
        dut.clock.step()
        cycles += 1
      }
      port.req.valid.poke(false.B)
      assert(next == n, s"spawn backpressure stopped admission early: $next/$n")

      // All accepted entries must mature despite the blocked spawn consumer.
      // In the old design, the four-entry spawnQ stopped cache insertion and these
      // newest updates missed because their continuations remained in the porch.
      dut.clock.step(delay + 20)
      val guaranteedResident = addrs.takeRight(slots - 1)
      for (k <- (n - (slots - 1)) until n) {
        update(dut, 0, 0, ids(k), addrs(k), data = 1, strobe = 0xff)
      }

      dut.io.spawnTaskOutputs(0).ready.poke(true.B)
      dut.io.coupledSlowPath(0).ready.poke(true.B)
      val diverted = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      for (_ <- 0 until 300) {
        val slow = dut.io.coupledSlowPath(0)
        if (
          slow.valid.peek().litToBoolean &&
          slow.bits.updateValid.peek().litToBoolean
        ) diverted += slow.bits.update.update.address.peek().litValue
        dut.clock.step()
      }
      assert(
        diverted.intersect(guaranteedResident).isEmpty,
        s"accepted continuations remained stuck in the porch behind spawn backpressure: $diverted"
      )
    }
  }

  it should "sustain II=1 admission indefinitely when completed spawns drain at II=1" in {
    val delay = 12
    val cycles = 500
    test(dutGen(nNew = 1, nUpd = 2, cacheDelayCycles = delay)) { dut =>
      init(dut, 1, 2)

      val port = dut.io.newContInput(0)
      port.req.valid.poke(true.B)
      for (cycle <- 0 until cycles) {
        port.req.bits.address.poke(BigInt(0x1000 + cycle * 0x10).U)
        // A zero counter makes every valid cache resolution a spawn. Both the
        // spawn and slow-path consumers remain ready from init(), so there is
        // no legitimate source of admission backpressure in this test.
        port.req.bits.taskBaseData.poke(mkLine(0, cycle).U)
        assert(
          port.req.ready.peek().litToBoolean,
          s"II=1 admission bubble at cycle $cycle"
        )
        dut.clock.step()
      }
      port.req.valid.poke(false.B)
    }
  }

  it should "spawn a fully-updated line when the pointer wraps back to it" in {
    test(dutGen()) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, counter = 2, remainder = 0x1)
      insert(dut, 0, 0x200, counter = 7, remainder = 0x2) // shields A from the head-1 guard
      dut.clock.step(2)

      // Two matched updates: counter 2 - 2 = 0, remainder ORs in.
      update(dut, 0, 0, idA, 0x100, data = 0x10, strobe = 0xff)
      update(dut, 0, 0, idA, 0x100, data = BigInt(0x20) << 8, strobe = BigInt(0xff) << 8)
      dut.clock.step(4)

      // Wrap: with 4 slots, the 4th insert inspects slot 0 (A) and spawns it.
      insert(dut, 0, 0x300, 7, 0x3)
      insert(dut, 0, 0x400, 7, 0x4)
      expectSpawn(dut, 0, mkLine(0, 0x1 | 0x10 | (BigInt(0x20) << 8)))
    }
  }

  it should "evict a still-counting line with its address on wrap" in {
    test(dutGen()) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, counter = 2, remainder = 0x1)
      insert(dut, 0, 0x200, 7, 0x2)
      dut.clock.step(2)

      // Only ONE of the two expected updates arrives.
      update(dut, 0, 0, idA, 0x100, data = 0x10, strobe = 0xff)
      dut.clock.step(4)

      insert(dut, 0, 0x300, 7, 0x3)
      insert(dut, 0, 0x400, 7, 0x4)
      expectEvict(dut, 0, 0x100, mkLine(1, 0x11))
    }
  }

  it should "evict an untouched line with its full counter" in {
    test(dutGen()) { dut =>
      init(dut, 1, 2)
      insert(dut, 0, 0x100, counter = 5, remainder = 0xab)
      for (k <- 1 until slots) insert(dut, 0, 0x100 + 0x100 * k, 7, k)
      expectEvict(dut, 0, 0x100, mkLine(5, 0xab))
    }
  }

  it should "divert an update whose address does not match its slot" in {
    test(dutGen()) { dut =>
      init(dut, 1, 2)
      insert(dut, 0, 0x100, 2, 0x1)
      insert(dut, 0, 0x200, 7, 0x2)
      dut.clock.step(2)

      // id 0 holds address 0x100; an update claiming 0x999 there must go to
      // the slow path (its line was evicted and the slot reused).
      update(dut, 0, 0, 0, addr = 0x999, data = 0x10, strobe = 0xff)
      expectSlowDivert(dut, 0, 0x999)
    }
  }

  it should "update and idle-flush the newest line without another insertion" in {
    test(dutGen()) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, 1, 0x1)
      dut.clock.step(2)
      update(dut, 0, 0, idA, 0x100, data = 0x10, strobe = 0xff)
      expectSpawn(dut, 0, mkLine(0, 0x11), within = 10 + slots + 10)
    }
  }

  it should "charge an idle-flush hole until an insertion absorbs it" in {
    class ExposedFlushHolesArgumentServer
        extends ArgumentServer(
          counterWidth,
          lineAddressWidth,
          serverTagWidth,
          serverIDWidth,
          continuationSize,
          NParallelNew = 1,
          NParallelUpdate = 2,
          cacheDelayCycles = 0,
          missedUpdateExtra = 8
        ) {
      val exposedFlushHoles = expose(flushHoles(0))
    }

    test(new ExposedFlushHolesArgumentServer) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, 1, 0x1)

      // Initial fill resolves an invalid slot, but it did not create a flush
      // hole and therefore must not underflow the accounting counter.
      dut.exposedFlushHoles.expect(0.U)

      dut.clock.step(2)
      update(dut, 0, 0, idA, 0x100, data = 0x10, strobe = 0xff)

      // The idle scanner eventually clears the now-completed valid slot. The
      // continuation may immediately leave coupledQ, but its empty cache slot
      // must remain charged against admission.
      var guard = 0
      while (dut.exposedFlushHoles.peek().litValue == 0 && guard < 40) {
        dut.clock.step()
        guard += 1
      }
      dut.exposedFlushHoles.expect(1.U)

      // The next insertion's clear step lands on an already-invalid slot. That
      // absorbs one outstanding hole instead of creating a queue resolution.
      insert(dut, 0, 0x200, counter = 7, remainder = 0x2)
      dut.exposedFlushHoles.expect(0.U)
    }
  }

  it should "not idle-flush a lane with no completed continuation" in {
    test(dutGen()) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, 2, 0x1)
      dut.clock.step(2)
      update(dut, 0, 0, idA, 0x100, data = 0x10, strobe = 0xff)

      for (_ <- 0 until (10 + 2 * slots + 5)) {
        assert(!dut.io.spawnTaskOutputs(0).valid.peek().litToBoolean)
        assert(
          !dut.io.coupledSlowPath(0).valid.peek().litToBoolean ||
            !dut.io.coupledSlowPath(0).bits.evictionValid.peek().litToBoolean
        )
        dut.clock.step()
      }
    }
  }

  it should "drain in ring order only while completed continuations remain" in {
    test(dutGen()) { dut =>
      init(dut, 1, 2)

      // A is incomplete but precedes completed B in ring order. Once B makes
      // doneCount nonzero, the fallback drain must evict A before spawning B.
      insert(dut, 0, 0x100, counter = 2, remainder = 0x1)
      val idB = insert(dut, 0, 0x200, counter = 1, remainder = 0x2)
      dut.clock.step(2)
      update(dut, 0, 0, idB, 0x200, data = 0x20, strobe = 0xff)

      expectEvict(
        dut,
        0,
        addr = 0x100,
        taskData = mkLine(2, 0x1),
        within = 10 + slots + 10
      )
      expectSpawn(dut, 0, mkLine(0, 0x22), within = slots + 10)

      // B consumed the lane's only done entry, so the scanner must stop.
      for (_ <- 0 until (2 * slots + 5)) {
        assert(!dut.io.spawnTaskOutputs(0).valid.peek().litToBoolean)
        assert(
          !dut.io.coupledSlowPath(0).valid.peek().litToBoolean ||
            !dut.io.coupledSlowPath(0).bits.evictionValid.peek().litToBoolean
        )
        dut.clock.step()
      }
    }
  }

  it should "route updates from any input port to the lane selected by metadata" in {
    val nNew = 2
    val nUpd = 3
    test(dutGen(nNew, nUpd)) { dut =>
      init(dut, nNew, nUpd)

      // Insert on LANE 1; the update arrives on input port 2 but carries
      // lane=1 metadata, so the crossbar must deliver it to lane 1's cache.
      val idA = insert(dut, 1, 0x100, counter = 1, remainder = 0x1)
      insert(dut, 1, 0x200, 7, 0x2)
      dut.clock.step(2)
      update(dut, 2, 1, idA, 0x100, data = 0x40, strobe = 0xff)
      dut.clock.step(4)

      insert(dut, 1, 0x300, 7, 0x3)
      insert(dut, 1, 0x400, 7, 0x4)
      expectSpawn(dut, 1, mkLine(0, 0x41))

      // Lane 0 must have stayed silent throughout.
      assert(!dut.io.spawnTaskOutputs(0).valid.peek().litToBoolean)
    }
  }

  it should "alternate local priority with round-robin priority among other update inputs" in {
    val nNew = 4
    val nUpd = 5
    val targetLane = 1
    test(dutGen(nNew, nUpd, missedUpdateExtra = 16)) { dut =>
      init(dut, nNew, nUpd)

      // All updates deliberately miss the empty cache. Their addresses identify
      // the input selected by the lane arbiter when they emerge on the slow path.
      for (i <- 0 until nUpd) {
        val port = dut.io.contUpdateInput(i)
        port.bits.address.poke((0x100 + i).U)
        port.bits.metadata.server.poke(0.U)
        port.bits.metadata.id.poke(0.U)
        port.bits.metadata.lane.poke(targetLane.U)
        port.bits.dataWrite.poke(0.U)
        port.bits.dataWriteStrobe.poke(0.U)
        port.valid.poke(true.B)
      }

      val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
      var cycles = 0
      while (seen.size < 8 && cycles < 80) {
        val slow = dut.io.coupledSlowPath(targetLane)
        if (
          slow.valid.peek().litToBoolean &&
          slow.bits.updateValid.peek().litToBoolean
        ) {
          seen += (slow.bits.update.update.address.peek().litValue.toInt - 0x100)
        }
        dut.clock.step()
        cycles += 1
      }

      assert(
        seen.toSeq == Seq(1, 2, 1, 3, 1, 4, 1, 0),
        s"unexpected local/other arbitration order: $seen"
      )
    }
  }

  it should "give a sole other requester every other contested grant" in {
    val nNew = 4
    val nUpd = 5
    val targetLane = 1
    test(dutGen(nNew, nUpd, missedUpdateExtra = 16)) { dut =>
      init(dut, nNew, nUpd)

      for (i <- Seq(1, 4)) {
        val port = dut.io.contUpdateInput(i)
        port.bits.address.poke((0x200 + i).U)
        port.bits.metadata.server.poke(0.U)
        port.bits.metadata.id.poke(0.U)
        port.bits.metadata.lane.poke(targetLane.U)
        port.bits.dataWrite.poke(0.U)
        port.bits.dataWriteStrobe.poke(0.U)
        port.valid.poke(true.B)
      }

      val seen = scala.collection.mutable.ArrayBuffer.empty[Int]
      var cycles = 0
      while (seen.size < 6 && cycles < 80) {
        val slow = dut.io.coupledSlowPath(targetLane)
        if (
          slow.valid.peek().litToBoolean &&
          slow.bits.updateValid.peek().litToBoolean
        ) {
          seen += (slow.bits.update.update.address.peek().litValue.toInt - 0x200)
        }
        dut.clock.step()
        cycles += 1
      }

      assert(
        seen.toSeq == Seq(1, 4, 1, 4, 1, 4),
        s"sole other requester did not alternate with local: $seen"
      )
    }
  }

  it should "throttle inserts at the admission cap instead of dropping resolutions" in {
    // The porch deliberately does NOT gate on resolution-queue occupancy any more:
    // that gate is what froze the porch whenever a drained porch met HBM
    // backpressure, which delayed a continuation past its update and lost the
    // release. Admission at the ENTRANCE is now the only backpressure, and the
    // resolution share is sized so a resolution never meets a full queue.
    val extra = 7
    // admitCap = residentCapacity + resolutionPoolDepth + resolutionTailCredits
    val admitCap = (slots - 1) + 2 + 3
    test(dutGen(missedUpdateExtra = extra)) { dut =>
      init(dut, 1, 2)
      dut.io.coupledSlowPath(0).ready.poke(false.B)

      // Offer inserts continuously with the eviction drain blocked.
      val port = dut.io.newContInput(0)
      val addrs = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var cycles = 0
      while (addrs.size < admitCap && cycles < 200) {
        val addr = BigInt(0x1000 + 0x100 * addrs.size)
        port.req.bits.address.poke(addr.U)
        port.req.bits.taskBaseData.poke(mkLine(7, addrs.size).U) // counter 7 => evicts
        port.req.valid.poke(true.B)
        if (port.req.ready.peek().litToBoolean) addrs += addr
        dut.clock.step()
        cycles += 1
      }
      assert(
        addrs.size == admitCap,
        s"entrance throttled before the admission cap: only ${addrs.size}/$admitCap accepted"
      )

      // At the cap the entrance must throttle and STAY throttled, rather than admit
      // work whose resolution would have nowhere to go.
      val stalledAddr = BigInt(0x9000)
      port.req.bits.address.poke(stalledAddr.U)
      port.req.bits.taskBaseData.poke(mkLine(7, 0x11).U)
      port.req.valid.poke(true.B)
      for (_ <- 0 until 10) {
        assert(
          !port.req.ready.peek().litToBoolean,
          "insert accepted past the admission cap"
        )
        dut.clock.step()
      }

      // Release the consumer: parked evictions drain IN ORDER, the stalled insert
      // completes, and none are lost or duplicated.
      dut.io.coupledSlowPath(0).ready.poke(true.B)
      val seenAddrs = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var inserted = false
      for (_ <- 0 until 80) {
        val p = dut.io.coupledSlowPath(0)
        if (
          p.valid.peek().litToBoolean &&
          p.bits.evictionValid.peek().litToBoolean
        ) {
          seenAddrs += p.bits.eviction.eviction.address.peek().litValue
        }
        if (!inserted && port.req.ready.peek().litToBoolean) inserted = true
        dut.clock.step()
        if (inserted && port.req.valid.peek().litToBoolean) {
          port.req.valid.poke(false.B)
        }
      }
      assert(inserted, "throttled insert never completed after drain")
      assert(
        seenAddrs.distinct.length == seenAddrs.length,
        s"eviction duplicated on drain: $seenAddrs"
      )
      // Evictions leave oldest-first, in insert order, with none skipped.
      val allAddrs = addrs.toSeq :+ stalledAddr
      assert(
        seenAddrs.toSeq == allAddrs.take(seenAddrs.size),
        s"evictions lost, duplicated or reordered: $seenAddrs"
      )
    }
  }

  it should "insert a straggler on time when a drained porch meets a stacked resolution queue" in {
    // The exact hardware corner that the admission cap alone does NOT cover, and
    // the one that still lost 14/6000 releases on real HBM. A BURSTY entrance lets
    // the porch drain to a lone straggler while downstream backpressure stacks
    // resolutions. The old porch gate (resolutionInCq <= resolutionPoolDepth-2)
    // then went false and FROZE that straggler mid-porch. A frozen entry is
    // inserted later than dispatch+delay, so its memReader-timed update finds a
    // line still in the porch -- which has NO HBM backing -- gets diverted to the
    // slow path, reads unsaved memory, completes=false, and the release dies.
    //
    // The pre-existing backpressure test below keeps the entrance FULL, which pins
    // the porch full and holds resolutionInCq near zero (porch+cache consume the
    // whole admission budget), so it can never reach this state. That is why the
    // shipped build passed the suite and still stalled on hardware.
    val delay = 12
    val extra = 8
    // 15 admits: with the porch drained these become cache(3) + 12 parked
    // resolutions -- past the old gate's threshold of resolutionPoolDepth-2 = 10,
    // while inFlight (15) is still under admitCap (3+12+3 = 18) so the straggler
    // is genuinely admitted rather than throttled at the entrance.
    val burst = (slots - 1) + delay
    test(dutGen(cacheDelayCycles = delay, missedUpdateExtra = extra)) { dut =>
      init(dut, 1, 2)
      dut.io.coupledSlowPath(0).ready.poke(false.B)
      val port = dut.io.newContInput(0)

      var next = 0
      var cycles = 0
      while (next < burst && cycles < 400) {
        port.req.bits.address.poke(BigInt(0x1000 + 0x100 * next).U)
        port.req.bits.taskBaseData.poke(mkLine(7, next).U) // counter 7 => evicts
        port.req.valid.poke(true.B)
        if (port.req.ready.peek().litToBoolean) next += 1
        dut.clock.step()
        cycles += 1
      }
      port.req.valid.poke(false.B)
      assert(next == burst, s"burst admission stalled: $next/$burst")

      // Go QUIET: the porch drains into the cache and every matured entry becomes a
      // parked resolution. This is what lifts resolutionInCq past the old gate
      // while leaving the porch empty.
      dut.clock.step(delay + 8)
      assert(
        port.req.ready.peek().litToBoolean,
        "entrance throttled before the admission cap -- retune `burst` so the " +
          "straggler is still admitted while resolutions are stacked"
      )

      // The straggler must traverse the porch in exactly `delay` cycles despite the
      // stacked resolution queue, so that its update lands on a resident line.
      val stragglerAddr = BigInt(0x7000)
      val stragglerId = insert(dut, 0, stragglerAddr, counter = 1, remainder = 0)
      dut.clock.step(delay + 2)
      update(dut, 0, 0, stragglerId, stragglerAddr, data = 1, strobe = 0xff)

      // Release and observe. The straggler's update must have HIT the cache and
      // driven its counter to zero, so it spawns. If the porch froze it, the update
      // shows up diverted on the slow path instead -- where HBM holds nothing for
      // it and the release is lost.
      dut.io.coupledSlowPath(0).ready.poke(true.B)
      val diverted = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var spawned = false
      for (_ <- 0 until 400) {
        val p = dut.io.coupledSlowPath(0)
        if (
          p.valid.peek().litToBoolean &&
          p.bits.updateValid.peek().litToBoolean
        ) {
          diverted += p.bits.update.update.address.peek().litValue
        }
        if (
          dut.io.spawnTaskOutputs(0).valid.peek().litToBoolean &&
          dut.io.spawnTaskOutputs(0).bits.taskData.peek().litValue == mkLine(0, 1)
        ) spawned = true
        dut.clock.step()
      }
      assert(
        !diverted.contains(stragglerAddr),
        "the straggler's update was diverted to the slow path: the porch froze it " +
          "past its update -> unsaved-HBM read -> release lost"
      )
      assert(spawned, "the straggler never spawned after its completing update")
    }
  }

  // ---- Non-backpressuring front porch (the release-loss fix) -----------------
  // The porch must NOT stall internally when the eviction path is backpressured;
  // instead admission throttles at the ENTRANCE only once the reservation cap is
  // reached. On the OLD porch, a backpressured (depth-8) coupledQ jammed inserts
  // after ~cacheDepth + 8; the fix keeps accepting up to admitCap = cacheDepth +
  // eviction pool, and loses no eviction on drain.
  it should "keep accepting inserts through eviction backpressure (non-backpressuring porch)" in {
    // Make the porch deeper than the old fixed depth-8 coupledQ. With the drain
    // blocked, all cache+porch admissions must still reach the cache and place
    // their evictions in the one-for-one reserved share.
    val delay = 12
    val extra = 8
    val n = (slots - 1) + delay
    test(dutGen(cacheDelayCycles = delay, missedUpdateExtra = extra)) { dut =>
      init(dut, 1, 2)
      // Block the eviction drain so every produced eviction piles in the coupledQ.
      dut.io.coupledSlowPath(0).ready.poke(false.B)
      val port = dut.io.newContInput(0)
      val addrs = (0 until n).map(k => BigInt(0x1000 + 0x100 * k))
      val ids = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var next = 0
      var cycles = 0
      while (next < n && cycles < 600) {
        port.req.bits.address.poke(addrs(next).U)
        port.req.bits.taskBaseData.poke(mkLine(7, next).U) // counter 7 => evicts
        port.req.valid.poke(true.B)
        if (port.req.ready.peek().litToBoolean) {
          ids += port.assignedId.peek().litValue
          next += 1
        }
        dut.clock.step()
        cycles += 1
      }
      port.req.valid.poke(false.B)
      assert(
        next == n,
        s"porch stalled under eviction backpressure: only $next/$n inserts accepted " +
          "-- the internal-stall bug (a stalled porch is what delays a continuation " +
          "past its update and causes the unsaved-HBM read)"
      )

      // Give every accepted porch entry time to mature. Then update the newest
      // cache lap. If the porch stalled internally behind the old depth-8
      // eviction queue, at least one of these accepted lines would still be in
      // the porch and its update would be diverted as a slow-path miss.
      dut.clock.step(delay + 20)
      // Resolution checks id+1, so the insertion completing a four-ID lap
      // legitimately evicts the oldest member of that lap. The newest
      // (slots-1) entries are the set guaranteed to remain searchable.
      val guaranteedResident = addrs.takeRight(slots - 1)
      for (k <- (n - (slots - 1)) until n) {
        update(dut, 0, 0, ids(k), addrs(k), data = 1, strobe = 0xff)
      }

      // Release the drain: every produced eviction must come out, none lost/duped.
      dut.io.coupledSlowPath(0).ready.poke(true.B)
      val drained = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val diverted = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      for (_ <- 0 until 400) {
        val p = dut.io.coupledSlowPath(0)
        if (p.valid.peek().litToBoolean) {
          if (p.bits.evictionValid.peek().litToBoolean)
            drained += p.bits.eviction.eviction.address.peek().litValue
          if (p.bits.updateValid.peek().litToBoolean)
            diverted += p.bits.update.update.address.peek().litValue
        }
        dut.clock.step()
      }
      assert(
        diverted.intersect(guaranteedResident).isEmpty,
        s"updates to accepted porch entries missed the cache after eviction backpressure: $diverted"
      )
      assert(
        drained.forall(addrs.contains),
        s"eviction carried an address never inserted: $drained"
      )
      assert(
        drained.distinct.length == drained.length,
        s"eviction duplicated on drain: $drained"
      )
      // The ring keeps (slots-1) lines resident; every older line must be evicted.
      assert(
        drained.length >= n - (slots - 1),
        s"evictions lost: only ${drained.length} drained, expected >= ${n - (slots - 1)}"
      )
    }
  }

  // A missed-update backlog (slow path backpressured) must NOT stall eviction
  // production: pure updates draw the separate missed pool, evictions their own,
  // so the two never contend. Here we backpressure the coupled output but keep
  // driving inserts that EVICT; on the OLD shared-depth queue a backlog wedged
  // inserts, on the fix the eviction pool is independent.
  it should "not let a missed-update backlog block eviction production" in {
    val delay = 3
    test(dutGen(cacheDelayCycles = delay, missedUpdateExtra = 8)) { dut =>
      init(dut, 1, 2)
      // Fill the cache with still-counting lines so subsequent inserts evict.
      for (k <- 0 until slots) insert(dut, 0, 0x500 + 0x40 * k, 7, k)
      // Now drive updates whose address does NOT match their slot (forced diverts
      // into the missed pool) while the coupled output is blocked, so misses pile.
      dut.io.coupledSlowPath(0).ready.poke(false.B)
      for (k <- 0 until 6) {
        val p = dut.io.contUpdateInput(0)
        p.bits.address.poke((0x9000 + k).U) // no slot holds this addr => divert
        p.bits.metadata.server.poke(0.U)
        p.bits.metadata.id.poke((k % slots).U)
        p.bits.metadata.lane.poke(0.U)
        p.bits.dataWrite.poke(0.U)
        p.bits.dataWriteStrobe.poke(0.U)
        p.valid.poke(true.B)
        dut.clock.step()
      }
      dut.io.contUpdateInput(0).valid.poke(false.B)
      // Despite the missed backlog, an insert that evicts must still be ACCEPTED
      // (its eviction has its own reserved pool). On the old shared queue this
      // insert would jam behind the diverted updates.
      val port = dut.io.newContInput(0)
      port.req.bits.address.poke(BigInt(0x700).U)
      port.req.bits.taskBaseData.poke(mkLine(7, 0x3f).U)
      port.req.valid.poke(true.B)
      var accepted = false
      for (_ <- 0 until 60 if !accepted) {
        accepted = port.req.ready.peek().litToBoolean
        dut.clock.step()
      }
      port.req.valid.poke(false.B)
      assert(
        accepted,
        "eviction production blocked by a missed-update backlog (pools not separated)"
      )
    }
  }
}
