package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.ArgumentServer

// The delay-line cache. Key behaviors under test:
//  * inserts hand out (id, lane) metadata,
//  * matched updates write their aligned payload slot and decrement the count,
//  * a line is RESOLVED when the write pointer wraps back to it, or when an
//    idle lane contains completed work and begins an ordered fallback drain,
//  * updates that cannot be applied safely are diverted to the slow path
//    instead of being silently dropped.
//
// The payload stores are BRAM: the base line and the update row are both
// synchronous-read memories, and an update is a MASKED write of one row element
// rather than a read-modify-write. Consequently the suite runs the compact
// configuration (payload + explicit offset) rather than full-line payloads, and
// slot semantics are last-write-wins, not OR-accumulate.
class ArgumentServerCacheTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "ArgumentServer (new, delay-line cache)"

  // Verilator throughout. Two reasons, and the first is not optional: a porch at
  // or above Util.DelayLine's URAM threshold stores its payload in a blackbox,
  // and the default backend cannot simulate one -- it reads the output as zero,
  // which surfaces as a spawn carrying a zeroed base line rather than as an
  // error. The second is that it is simply faster for a suite this size.
  private def anns = Seq(VerilatorBackendAnnotation)

  private val counterWidth = 8
  private val lineAddressWidth = 28
  private val serverTagWidth = 1
  private val serverIDWidth = 2 // 4 slots -> quick wrap
  private val continuationSize = 128
  private val slots = 1 << serverIDWidth

  // Compact updates: four 32-bit payload slots per continuation line.
  private val updatePayloadWidth = 32
  private val updateSlots = continuationSize / updatePayloadWidth
  private val updateOffsetWidth = 2

  private val mkLine = NanTestUtil.line(counterWidth, continuationSize) _

  /** The `remainder` bits (i.e. line bits above the counter field) produced by
    * writing `payload` into row element `offset`.
    */
  private def contribution(offset: Int, payload: BigInt): BigInt = {
    require(offset >= 0 && offset < updateSlots)
    require(payload < (BigInt(1) << updatePayloadWidth))
    val bits = payload << (offset * updatePayloadWidth)
    require(
      (bits & ((BigInt(1) << counterWidth) - 1)) == 0,
      "test payload would land in the counter field, which the merge discards"
    )
    bits >> counterWidth
  }

  private def dutGen(
      nNew: Int = 1,
      nUpd: Int = 2,
      cacheDelayCycles: Int = 0,
      porchUramThreshold: Int = Util.DelayLine.defaultUramThreshold,
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
      porchUramThreshold = porchUramThreshold,
      missedUpdateExtra = missedUpdateExtra,
      updatePayloadWidth = updatePayloadWidth,
      updateOffsetWidth = updateOffsetWidth
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

  /** Drive one compact update: `payload` into row element `offset`. */
  private def update(
      dut: ArgumentServer,
      inputIdx: Int,
      lane: Int,
      id: BigInt,
      addr: BigInt,
      offset: Int,
      payload: BigInt
  ): Unit = {
    val port = dut.io.contUpdateInput(inputIdx)
    driveUpdate(dut, inputIdx, lane, id, addr, offset, payload)
    var guard = 0
    while (!port.ready.peek().litToBoolean) {
      dut.clock.step(); guard += 1; assert(guard < 50, "update never accepted")
    }
    dut.clock.step()
    port.valid.poke(false.B)
  }

  /** Assert a compact update without waiting for it to be accepted. */
  private def driveUpdate(
      dut: ArgumentServer,
      inputIdx: Int,
      lane: Int,
      id: BigInt,
      addr: BigInt,
      offset: Int,
      payload: BigInt
  ): Unit = {
    val port = dut.io.contUpdateInput(inputIdx)
    port.bits.address.poke(addr.U)
    port.bits.metadata.server.poke(0.U)
    port.bits.metadata.id.poke(id.U)
    port.bits.metadata.lane.poke(lane.U)
    port.bits.payload.poke(payload.U)
    port.bits.offset.get.poke(offset.U)
    port.valid.poke(true.B)
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
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      assert(insert(dut, 0, 0x100, 1, 0x1) == BigInt(0))
      assert(insert(dut, 0, 0x200, 1, 0x2) == BigInt(1))
      assert(insert(dut, 0, 0x300, 1, 0x3) == BigInt(2))
    }
  }

  it should "advance porch entries through bubbles before committing them" in {
    val delay = 3
    test(dutGen(cacheDelayCycles = delay)).withAnnotations(anns) { dut =>
      init(dut, 1, 2)

      val id = insert(dut, 0, 0x100, counter = 1, remainder = 0x1)
      assert(id == 0)

      // No further requests arrive. The enabled porch must still shift its
      // valid bit through the intervening bubbles and make the later update a
      // cache hit. If bubbles froze the porch, this update would be diverted.
      dut.clock.step(delay)
      update(dut, 0, 0, id, 0x100, offset = 1, payload = 0x10)
      expectSpawn(
        dut,
        0,
        mkLine(0, 0x1 | contribution(1, 0x10)),
        within = 10 + slots + 10
      )
    }
  }

  // Every porch above is shallow enough to stay a shift register. The real ones
  // are 72 and 75 cycles, which Util.DelayLine puts in a URAM circular buffer
  // instead -- a different storage, a different read path, and an off-by-one in
  // the read pointer away from delivering a neighbouring entry. The porch
  // properties therefore have to be re-checked at a depth that selects it.
  // URAM is off by default (Util.DelayLine.defaultUramThreshold), so these two
  // force it on explicitly -- the path is kept, so it stays tested.
  private val uramPorchDelay = 70
  private val forceUram = Util.DelayLine.validatedUramThreshold
  require(
    Util.DelayLine.usesUram(uramPorchDelay, forceUram),
    "the deep-porch tests must actually exercise the URAM variant"
  )

  it should "commit a deep (URAM-backed) porch entry intact" in {
    test(dutGen(cacheDelayCycles = uramPorchDelay, porchUramThreshold = forceUram)).withAnnotations(anns) { dut =>
      init(dut, 1, 2)

      val id = insert(dut, 0, 0x100, counter = 1, remainder = 0x1)
      assert(id == 0)

      // Same shape as the bubble test above: nothing else arrives, so the entry
      // crosses 70 cycles of memory alone and must still be searchable when its
      // update lands. A payload that came back corrupted, early or late would
      // miss and divert to the slow path instead of merging.
      dut.clock.step(uramPorchDelay)
      update(dut, 0, 0, id, 0x100, offset = 1, payload = 0x10)
      expectSpawn(
        dut,
        0,
        mkLine(0, 0x1 | contribution(1, 0x10)),
        within = 10 + slots + 10
      )
    }
  }

  it should "keep back-to-back entries distinct across a deep porch" in {
    test(dutGen(cacheDelayCycles = uramPorchDelay, porchUramThreshold = forceUram)).withAnnotations(anns) { dut =>
      init(dut, 1, 2)

      // Distinct addresses AND distinct base remainders, so an entry delivered
      // in place of its neighbour shows up either as a miss (wrong address) or
      // as a merge onto the wrong base line (wrong remainder). One insert per
      // cycle fills consecutive circular-buffer slots, which is the case a
      // pointer that trails by the wrong amount gets wrong.
      val addrs = Seq(BigInt(0x100), BigInt(0x200), BigInt(0x300))
      val bases = Seq(BigInt(0x1), BigInt(0x2), BigInt(0x4))
      val ids = addrs.zip(bases).map { case (addr, base) =>
        insert(dut, 0, addr, counter = 1, remainder = base)
      }
      assert(ids == Seq(BigInt(0), BigInt(1), BigInt(2)))

      dut.clock.step(uramPorchDelay + 10)

      // Ring order, so the completed entries drain in the order they went in.
      for (((addr, base), id) <- addrs.zip(bases).zip(ids)) {
        update(dut, 0, 0, id, addr, offset = 1, payload = 0x10)
        expectSpawn(
          dut,
          0,
          mkLine(0, base | contribution(1, 0x10)),
          within = 10 + slots + 10
        )
      }
    }
  }

  it should "absorb spawn backpressure without stalling accepted porch entries" in {
    val delay = 12
    test(dutGen(cacheDelayCycles = delay)).withAnnotations(anns) { dut =>
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
        update(dut, 0, 0, ids(k), addrs(k), offset = 1, payload = 1)
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
    test(dutGen(nNew = 1, nUpd = 2, cacheDelayCycles = delay)).withAnnotations(anns) { dut =>
      init(dut, 1, 2)

      val port = dut.io.newContInput(0)
      port.req.valid.poke(true.B)
      for (cycle <- 0 until cycles) {
        port.req.bits.address.poke(BigInt(0x1000 + cycle * 0x10).U)
        // A zero counter makes every valid cache resolution a spawn. Both the
        // spawn and slow-path consumers remain ready from init(), so there is
        // no legitimate source of admission backpressure in this test. The
        // BRAM-backed coupled queue must sustain a same-cycle enqueue and
        // dequeue for the whole run, wrapping its pointers many times over.
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
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, counter = 2, remainder = 0x1)
      insert(dut, 0, 0x200, counter = 7, remainder = 0x2) // shields A from the head-1 guard
      dut.clock.step(2)

      // Two matched updates into DISTINCT row elements: counter 2 - 2 = 0, and
      // both masked writes survive into the merge.
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0x10)
      update(dut, 0, 0, idA, 0x100, offset = 2, payload = 0x20)
      dut.clock.step(4)

      // Wrap: with 4 slots, the 4th insert inspects slot 0 (A) and spawns it.
      insert(dut, 0, 0x300, 7, 0x3)
      insert(dut, 0, 0x400, 7, 0x4)
      expectSpawn(
        dut,
        0,
        mkLine(0, 0x1 | contribution(1, 0x10) | contribution(2, 0x20))
      )
    }
  }

  it should "merge every masked slot of a row with the base line" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      // The base occupies slot 0 (above the counter); the three remaining row
      // elements are each written by their own update.
      val base = BigInt(0x77)
      val idA = insert(dut, 0, 0x100, counter = 3, remainder = base)
      insert(dut, 0, 0x200, 7, 0x2)
      dut.clock.step(2)

      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0xa)
      update(dut, 0, 0, idA, 0x100, offset = 2, payload = 0xb)
      update(dut, 0, 0, idA, 0x100, offset = 3, payload = 0xc)
      dut.clock.step(4)

      insert(dut, 0, 0x300, 7, 0x3)
      insert(dut, 0, 0x400, 7, 0x4)
      expectSpawn(
        dut,
        0,
        mkLine(
          0,
          base | contribution(1, 0xa) | contribution(2, 0xb) |
            contribution(3, 0xc)
        )
      )
    }
  }

  it should "apply last-write-wins when two updates share one row slot" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, counter = 2, remainder = 0x1)
      insert(dut, 0, 0x200, 7, 0x2)
      dut.clock.step(2)

      // Same slot twice. The masked write replaces the element (there is no
      // read-modify-write any more), so only the SECOND payload survives -- but
      // BOTH updates still count against the join counter.
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0xaa)
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0x55)
      dut.clock.step(4)

      insert(dut, 0, 0x300, 7, 0x3)
      insert(dut, 0, 0x400, 7, 0x4)
      expectSpawn(dut, 0, mkLine(0, 0x1 | contribution(1, 0x55)))
    }
  }

  it should "clear a recycled row's stale slices on its first update" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)

      // Tenant A fills slots 1 and 2 of the row at cache id 0 and resolves.
      val idA = insert(dut, 0, 0x100, counter = 2, remainder = 0x0)
      assert(idA == 0)
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0xaaaa)
      update(dut, 0, 0, idA, 0x100, offset = 2, payload = 0x5555)
      dut.clock.step(6) // let both masked writes commit before the wrap
      for (k <- 1 until slots) insert(dut, 0, 0x100 + 0x100 * k, 7, k)
      expectSpawn(
        dut,
        0,
        mkLine(0, contribution(1, 0xaaaa) | contribution(2, 0x5555))
      )

      // Tenant B recycles the SAME row and writes only slot 3. The insert does
      // not zero the memory (it only retires deltaValid), so B's first update
      // must rewrite the entire row and wipe A's slices with it.
      val idB = insert(dut, 0, 0x900, counter = 1, remainder = 0x0)
      assert(idB == idA, "test needs tenant B to recycle tenant A's row")
      update(dut, 0, 0, idB, 0x900, offset = 3, payload = 0xf0f0)
      dut.clock.step(6)
      for (k <- 1 until slots) insert(dut, 0, 0xa00 + 0x100 * k, 7, k)
      expectSpawn(dut, 0, mkLine(0, contribution(3, 0xf0f0)))
    }
  }

  it should "contribute no update data from a row whose delta is stale" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)

      // Tenant A leaves a fully populated row behind.
      val idA = insert(dut, 0, 0x100, counter = 2, remainder = 0x0)
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0xaaaa)
      update(dut, 0, 0, idA, 0x100, offset = 2, payload = 0x5555)
      dut.clock.step(6) // let both masked writes commit before the wrap
      for (k <- 1 until slots) insert(dut, 0, 0x100 + 0x100 * k, 7, k)
      expectSpawn(
        dut,
        0,
        mkLine(0, contribution(1, 0xaaaa) | contribution(2, 0x5555))
      )

      // Tenant B recycles the row and is NEVER updated, so its deltaValid stays
      // false. It must be evicted with exactly its base line and its full
      // counter -- no residue from A, no phantom counter decrements.
      val base = BigInt(0xabc)
      insert(dut, 0, 0x900, counter = 5, remainder = base)
      for (k <- 1 until slots) insert(dut, 0, 0xa00 + 0x100 * k, 7, k)
      // The first lap's filler lines evict ahead of B, so scan for B's own.
      val slow = dut.io.coupledSlowPath(0)
      var guard = 0
      var found = false
      while (!found) {
        if (slow.valid.peek().litToBoolean &&
          slow.bits.evictionValid.peek().litToBoolean &&
          slow.bits.eviction.eviction.address.peek().litValue == BigInt(0x900)) {
          slow.bits.eviction.eviction.taskData.expect(mkLine(5, base).U)
          found = true
        }
        dut.clock.step(); guard += 1
        assert(guard < 40, "tenant B never evicted")
      }
    }
  }

  it should "count every accepted update independently of payload storage" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      // Three updates, ALL to the same row element. Payload storage keeps only
      // the last of them, but the countdown lives in its own register file and
      // must retire all three, so the line completes and spawns.
      val idA = insert(dut, 0, 0x100, counter = 3, remainder = 0x1)
      insert(dut, 0, 0x200, 7, 0x2)
      dut.clock.step(2)
      update(dut, 0, 0, idA, 0x100, offset = 2, payload = 0x11)
      update(dut, 0, 0, idA, 0x100, offset = 2, payload = 0x22)
      update(dut, 0, 0, idA, 0x100, offset = 2, payload = 0x33)
      dut.clock.step(4)

      insert(dut, 0, 0x300, 7, 0x3)
      insert(dut, 0, 0x400, 7, 0x4)
      expectSpawn(dut, 0, mkLine(0, 0x1 | contribution(2, 0x33)))
    }
  }

  it should "evict a still-counting line with its address on wrap" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, counter = 2, remainder = 0x1)
      insert(dut, 0, 0x200, 7, 0x2)
      dut.clock.step(2)

      // Only ONE of the two expected updates arrives.
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0x10)
      dut.clock.step(4)

      insert(dut, 0, 0x300, 7, 0x3)
      insert(dut, 0, 0x400, 7, 0x4)
      expectEvict(dut, 0, 0x100, mkLine(1, 0x1 | contribution(1, 0x10)))
    }
  }

  it should "evict an untouched line with its full counter" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      insert(dut, 0, 0x100, counter = 5, remainder = 0xab)
      for (k <- 1 until slots) insert(dut, 0, 0x100 + 0x100 * k, 7, k)
      expectEvict(dut, 0, 0x100, mkLine(5, 0xab))
    }
  }

  it should "divert an update whose address does not match its slot" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      insert(dut, 0, 0x100, 2, 0x1)
      insert(dut, 0, 0x200, 7, 0x2)
      dut.clock.step(2)

      // id 0 holds address 0x100; an update claiming 0x999 there must go to
      // the slow path (its line was evicted and the slot reused).
      update(dut, 0, 0, 0, addr = 0x999, offset = 1, payload = 0x10)
      expectSlowDivert(dut, 0, 0x999)
    }
  }

  // An update reaching the cache lookup on the exact cycle its own line is
  // committed is NOT a miss: the line has never been evicted and has no HBM
  // backing, so diverting it would read unsaved memory. It must be held at the
  // lookup and retried on the next cycle, when the slot is valid.
  it should "retry an update that collides with its own line's insertion" in {
    class ExposedCollisionServer
        extends ArgumentServer(
          counterWidth,
          lineAddressWidth,
          serverTagWidth,
          serverIDWidth,
          continuationSize,
          NParallelNew = 1,
          NParallelUpdate = 2,
          cacheDelayCycles = 0,
          missedUpdateExtra = 8,
          updatePayloadWidth = updatePayloadWidth,
          updateOffsetWidth = updateOffsetWidth
        ) {
      val exposedInsertCollision = expose(insertCollisions(0))
    }

    test(new ExposedCollisionServer).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val payload = BigInt(0x37)
      // gap = cycles between asserting the update and committing the insert.
      // Exactly one alignment lands the update at the lookup on the commit
      // cycle; the sweep finds it without hard-coding the pipeline depth.
      val collided = scala.collection.mutable.ArrayBuffer.empty[Int]
      val spawnedAt = scala.collection.mutable.ArrayBuffer.empty[Int]
      val divertedAt = scala.collection.mutable.ArrayBuffer.empty[Int]

      for (gap <- 0 until 10) {
        val addr = BigInt(0x2000 + 0x100 * gap)
        var sawCollision = false
        var sawSpawn = false
        var sawDivert = false
        var updatePending = false
        val expected = mkLine(0, contribution(1, payload))

        // Steps one cycle while sampling the outputs, and retires the one-shot
        // update as soon as the input port accepts it. Leaving `valid` asserted
        // would inject a second update and over-decrement the join counter.
        def observe(): Unit = {
          if (dut.exposedInsertCollision.peek().litToBoolean) sawCollision = true
          val sp = dut.io.spawnTaskOutputs(0)
          if (sp.valid.peek().litToBoolean &&
            sp.bits.taskData.peek().litValue == expected) sawSpawn = true
          val cp = dut.io.coupledSlowPath(0)
          if (cp.valid.peek().litToBoolean &&
            cp.bits.updateValid.peek().litToBoolean &&
            cp.bits.update.update.address.peek().litValue == addr)
            sawDivert = true
          val taken = updatePending &&
            dut.io.contUpdateInput(0).ready.peek().litToBoolean
          dut.clock.step()
          if (taken) {
            dut.io.contUpdateInput(0).valid.poke(false.B)
            updatePending = false
          }
        }

        // The lane is quiet, so the next id is the running head; the target
        // line is inserted with counter 1 and completed by this one update.
        val nextId = dut.io.newContInput(0).assignedId.peek().litValue
        driveUpdate(dut, 0, 0, nextId, addr, offset = 1, payload = payload)
        updatePending = true
        for (_ <- 0 until gap) observe()

        val port = dut.io.newContInput(0)
        port.req.bits.address.poke(addr.U)
        port.req.bits.taskBaseData.poke(mkLine(1, 0x0).U)
        port.req.valid.poke(true.B)
        assert(port.req.ready.peek().litToBoolean, "insert unexpectedly stalled")
        observe()
        port.req.valid.poke(false.B)

        // Let the update drain, then wrap the ring so the line resolves.
        for (_ <- 0 until 8) observe()
        dut.io.contUpdateInput(0).valid.poke(false.B)
        updatePending = false
        for (k <- 1 until slots) {
          port.req.bits.address.poke((addr + 0x10 * k).U)
          port.req.bits.taskBaseData.poke(mkLine(7, 0).U)
          port.req.valid.poke(true.B)
          while (!port.req.ready.peek().litToBoolean) observe()
          observe()
          port.req.valid.poke(false.B)
        }
        for (_ <- 0 until 12) observe()

        if (sawCollision) collided += gap
        if (sawSpawn) spawnedAt += gap
        if (sawDivert) divertedAt += gap
      }

      assert(
        collided.nonEmpty,
        "the sweep never produced an insert collision; widen the gap range"
      )
      for (gap <- collided) {
        assert(
          spawnedAt.contains(gap),
          s"gap $gap collided but the retried update never completed its line"
        )
        assert(
          !divertedAt.contains(gap),
          s"gap $gap diverted an update that collided with its own insertion " +
            "-- the slow path would read unsaved HBM and lose the release"
        )
      }
    }
  }

  // An update reaching the lookup on the cycle its line is RESOLVED is a real
  // miss: the line is leaving the cache. It must be diverted AND coupled to that
  // line's own eviction in one atomic slow-path entry, so the handler orders it
  // behind the eviction's HBM write.
  it should "divert a resolution-colliding update coupled with its own eviction" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val addr = BigInt(0x100)
      var coupled = false

      for (gap <- 0 until 10 if !coupled) {
        // Fresh lap: the target line at the head, then two filler lines, so the
        // fourth insert (below) is what resolves the target.
        val base = addr + 0x1000 * gap
        val idA = insert(dut, 0, base, counter = 2, remainder = 0x1)
        for (k <- 1 until slots - 1) insert(dut, 0, base + 0x10 * k, 7, k)

        driveUpdate(dut, 0, 0, idA, base, offset = 1, payload = 0x5)
        var updatePending = true
        val port = dut.io.newContInput(0)

        def observe(): Unit = {
          val cp = dut.io.coupledSlowPath(0)
          if (cp.valid.peek().litToBoolean &&
            cp.bits.updateValid.peek().litToBoolean &&
            cp.bits.evictionValid.peek().litToBoolean &&
            cp.bits.update.update.address.peek().litValue == base &&
            cp.bits.eviction.eviction.address.peek().litValue == base)
            coupled = true
          val taken = updatePending &&
            dut.io.contUpdateInput(0).ready.peek().litToBoolean
          dut.clock.step()
          if (taken) {
            dut.io.contUpdateInput(0).valid.poke(false.B)
            updatePending = false
          }
        }

        for (_ <- 0 until gap) observe()
        // This insert resolves idA.
        port.req.bits.address.poke((base + 0x900).U)
        port.req.bits.taskBaseData.poke(mkLine(7, 0).U)
        port.req.valid.poke(true.B)
        while (!port.req.ready.peek().litToBoolean) observe()
        observe()
        port.req.valid.poke(false.B)

        for (_ <- 0 until 20) observe()
        dut.io.contUpdateInput(0).valid.poke(false.B)
        updatePending = false
      }

      assert(
        coupled,
        "no alignment produced an update coupled to its own eviction; the " +
          "resolution-collision divert is not sharing the slow-path entry"
      )
    }
  }

  it should "update and idle-flush the newest line without another insertion" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, 1, 0x1)
      dut.clock.step(2)
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0x10)
      expectSpawn(
        dut,
        0,
        mkLine(0, 0x1 | contribution(1, 0x10)),
        within = 10 + slots + 10
      )
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
          missedUpdateExtra = 8,
          updatePayloadWidth = updatePayloadWidth,
          updateOffsetWidth = updateOffsetWidth
        ) {
      val exposedFlushHoles = expose(flushHoles(0))
    }

    test(new ExposedFlushHolesArgumentServer).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, 1, 0x1)

      // Initial fill resolves an invalid slot, but it did not create a flush
      // hole and therefore must not underflow the accounting counter.
      dut.exposedFlushHoles.expect(0.U)

      dut.clock.step(2)
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0x10)

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
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val idA = insert(dut, 0, 0x100, 2, 0x1)
      dut.clock.step(2)
      update(dut, 0, 0, idA, 0x100, offset = 1, payload = 0x10)

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
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)

      // A is incomplete but precedes completed B in ring order. Once B makes
      // doneCount nonzero, the fallback drain must evict A before spawning B.
      insert(dut, 0, 0x100, counter = 2, remainder = 0x1)
      val idB = insert(dut, 0, 0x200, counter = 1, remainder = 0x2)
      dut.clock.step(2)
      update(dut, 0, 0, idB, 0x200, offset = 1, payload = 0x20)

      expectEvict(
        dut,
        0,
        addr = 0x100,
        taskData = mkLine(2, 0x1),
        within = 10 + slots + 10
      )
      expectSpawn(
        dut,
        0,
        mkLine(0, 0x2 | contribution(1, 0x20)),
        within = slots + 10
      )

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
    test(dutGen(nNew, nUpd)).withAnnotations(anns) { dut =>
      init(dut, nNew, nUpd)

      // Insert on LANE 1; the update arrives on input port 2 but carries
      // lane=1 metadata, so the crossbar must deliver it to lane 1's cache.
      val idA = insert(dut, 1, 0x100, counter = 1, remainder = 0x1)
      insert(dut, 1, 0x200, 7, 0x2)
      dut.clock.step(2)
      update(dut, 2, 1, idA, 0x100, offset = 1, payload = 0x40)
      dut.clock.step(4)

      insert(dut, 1, 0x300, 7, 0x3)
      insert(dut, 1, 0x400, 7, 0x4)
      expectSpawn(dut, 1, mkLine(0, 0x1 | contribution(1, 0x40)))

      // Lane 0 must have stayed silent throughout.
      assert(!dut.io.spawnTaskOutputs(0).valid.peek().litToBoolean)
    }
  }

  it should "alternate local priority with round-robin priority among other update inputs" in {
    val nNew = 4
    val nUpd = 5
    val targetLane = 1
    test(dutGen(nNew, nUpd, missedUpdateExtra = 16)).withAnnotations(anns) { dut =>
      init(dut, nNew, nUpd)

      // All updates deliberately miss the empty cache. Their addresses identify
      // the input selected by the lane arbiter when they emerge on the slow path.
      for (i <- 0 until nUpd) {
        val port = dut.io.contUpdateInput(i)
        port.bits.address.poke((0x100 + i).U)
        port.bits.metadata.server.poke(0.U)
        port.bits.metadata.id.poke(0.U)
        port.bits.metadata.lane.poke(targetLane.U)
        port.bits.payload.poke(0.U)
        port.bits.offset.get.poke(0.U)
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
    test(dutGen(nNew, nUpd, missedUpdateExtra = 16)).withAnnotations(anns) { dut =>
      init(dut, nNew, nUpd)

      for (i <- Seq(1, 4)) {
        val port = dut.io.contUpdateInput(i)
        port.bits.address.poke((0x200 + i).U)
        port.bits.metadata.server.poke(0.U)
        port.bits.metadata.id.poke(0.U)
        port.bits.metadata.lane.poke(targetLane.U)
        port.bits.payload.poke(0.U)
        port.bits.offset.get.poke(0.U)
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

  // A missed update leaves the server in its COMPACT form -- payload plus slot
  // offset, byte for byte what arrived. Nothing between here and the
  // SlowArgumentHandler reads the data, so expanding it to a full line at this
  // boundary would only inflate every queue on the path. The late expansion
  // itself is checked by `expanded` below and by SlowArgumentHandlerTests.
  it should "carry a missed update to the slow path without expanding it" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val slow = dut.io.coupledSlowPath(0)
      for (offset <- 0 until updateSlots) {
        val addr = BigInt(0x5000 + offset)
        val payload = BigInt(0x100) + offset // no bits inside the counter field
        driveUpdate(dut, 0, 0, 0, addr, offset = offset, payload = payload)
        var guard = 0
        while (!(slow.valid.peek().litToBoolean &&
          slow.bits.updateValid.peek().litToBoolean &&
          slow.bits.update.update.address.peek().litValue == addr)) {
          dut.clock.step(); guard += 1
          assert(guard < 40, s"missed update for offset $offset never appeared")
        }
        slow.bits.update.update.payload.expect(payload.U)
        slow.bits.update.update.offset.get.expect(offset.U)
        dut.io.contUpdateInput(0).valid.poke(false.B)
        dut.clock.step(4)
      }
    }
  }

  // The compact pair must still denote the exact line-shaped write the cache
  // would have merged on a hit, so that a miss and a hit are indistinguishable
  // once the handler places it. This is the arithmetic the handler's single
  // expander performs.
  it should "place a compact missed update at its aligned line slot" in {
    test(dutGen()).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val slow = dut.io.coupledSlowPath(0)
      for (offset <- 0 until updateSlots) {
        val addr = BigInt(0x6000 + offset)
        val payload = BigInt(0x100) + offset
        driveUpdate(dut, 0, 0, 0, addr, offset = offset, payload = payload)
        var guard = 0
        while (!(slow.valid.peek().litToBoolean &&
          slow.bits.updateValid.peek().litToBoolean &&
          slow.bits.update.update.address.peek().litValue == addr)) {
          dut.clock.step(); guard += 1
          assert(guard < 40, s"missed update for offset $offset never appeared")
        }
        val p = slow.bits.update.update.payload.peek().litValue
        val o = slow.bits.update.update.offset.get.peek().litValue.toInt
        assert(
          (p << (o * updatePayloadWidth)) ==
            (payload << (offset * updatePayloadWidth)),
          s"compact pair for offset $offset does not denote the aligned line write"
        )
        dut.io.contUpdateInput(0).valid.poke(false.B)
        dut.clock.step(4)
      }
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
    val admitCap = (slots - 1) + 2 + 4
    test(dutGen(missedUpdateExtra = extra)).withAnnotations(anns) { dut =>
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

  // The coupled FIFO is now a BRAM-backed Queue rather than a LUTRAM
  // BankedQueue, so its ordering, wraparound and empty/full corners are worth
  // exercising directly. A bursty consumer drives the queue repeatedly through
  // empty -> nonempty (synchronous-read latency) and full -> draining, while
  // hundreds of resolutions must come out in exact insert order.
  it should "carry resolutions through the BRAM coupled queue in order under a bursty consumer" in {
    val extra = 8
    test(dutGen(missedUpdateExtra = extra)).withAnnotations(anns) { dut =>
      init(dut, 1, 2)
      val port = dut.io.newContInput(0)
      val slow = dut.io.coupledSlowPath(0)
      val rng = new scala.util.Random(0x5eed)

      val offered = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val drained = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val target = 300
      var cycles = 0
      var emptyToNonEmpty = 0
      var wasIdle = true

      while (drained.size < target && cycles < 40000) {
        // Alternate a FILL phase (consumer blocked, inserts offered until the
        // admission cap) with a DRAIN phase (consumer open, no inserts). The
        // queue therefore runs all the way to full and all the way back to
        // empty on every phase pair, instead of settling into a steady stream
        // that would never exercise either corner. `rng` jitters the phase
        // lengths so the two never lock into one fixed alignment.
        val filling = (cycles / (10 + rng.nextInt(6))) % 2 == 0
        slow.ready.poke((!filling).B)

        val addr = BigInt(0x10000 + 0x10 * offered.size)
        port.req.bits.address.poke(addr.U)
        port.req.bits.taskBaseData.poke(mkLine(7, offered.size).U) // always evicts
        port.req.valid.poke(filling.B)
        val accepted = filling && port.req.ready.peek().litToBoolean

        val hasBeat = slow.valid.peek().litToBoolean
        if (hasBeat && wasIdle) emptyToNonEmpty += 1
        wasIdle = !hasBeat
        if (hasBeat && !filling && slow.bits.evictionValid.peek().litToBoolean) {
          drained += slow.bits.eviction.eviction.address.peek().litValue
        }

        if (accepted) offered += addr
        dut.clock.step()
        cycles += 1
      }
      port.req.valid.poke(false.B)
      slow.ready.poke(true.B)

      assert(
        drained.size >= target,
        s"queue stalled: only ${drained.size}/$target evictions drained in $cycles cycles"
      )
      assert(
        emptyToNonEmpty > 5,
        s"the queue never really emptied ($emptyToNonEmpty refills); the " +
          "empty->nonempty read latency was not exercised"
      )
      // The ring keeps (slots-1) lines resident, so evictions are exactly the
      // oldest prefix of the offered addresses, in order and without repeats.
      assert(
        drained.toSeq == offered.toSeq.take(drained.size),
        "coupled queue lost, duplicated or reordered a resolution"
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
    test(dutGen(cacheDelayCycles = delay, missedUpdateExtra = extra)).withAnnotations(anns) { dut =>
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
      update(dut, 0, 0, stragglerId, stragglerAddr, offset = 1, payload = 1)

      // Release and observe. The straggler's update must have HIT the cache and
      // driven its counter to zero, so it spawns. If the porch froze it, the update
      // shows up diverted on the slow path instead -- where HBM holds nothing for
      // it and the release is lost.
      dut.io.coupledSlowPath(0).ready.poke(true.B)
      val diverted = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var spawned = false
      val expectedSpawn = mkLine(0, contribution(1, 1))
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
          dut.io.spawnTaskOutputs(0).bits.taskData.peek().litValue == expectedSpawn
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
    test(dutGen(cacheDelayCycles = delay, missedUpdateExtra = extra)).withAnnotations(anns) { dut =>
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
        update(dut, 0, 0, ids(k), addrs(k), offset = 1, payload = 1)
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
    test(dutGen(cacheDelayCycles = delay, missedUpdateExtra = 8)).withAnnotations(anns) { dut =>
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
        p.bits.payload.poke(0.U)
        p.bits.offset.get.poke(0.U)
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

  // A cache insert and a hitting update are independently sourced (front porch
  // vs perLaneFIFO) and both touch the per-slot update record. The insert's half
  // retires the recycled slot's stale row; the update's half is the masked
  // write. They coincide on some alignments, and the row payload lives in a
  // single-write-port memory, so a design that lets one silently win corrupts
  // the other's slot. The same applies to a resolution READ landing on the cycle
  // of an update WRITE to a different row -- the BRAM's two ports must stay
  // independent. Insert cadence and update delay are deliberately out of phase
  // so the alignment drifts through every relative offset; the test asserts it
  // actually observed both coincidences rather than trusting that it did.
  it should "keep insert, resolution and update effects separate when they coincide" in {
    class ExposedCollisionArgumentServer
        extends ArgumentServer(
          counterWidth,
          lineAddressWidth,
          serverTagWidth,
          serverIDWidth,
          continuationSize,
          NParallelNew = 1,
          NParallelUpdate = 2,
          cacheDelayCycles = 0,
          missedUpdateExtra = 8,
          updatePayloadWidth = updatePayloadWidth,
          updateOffsetWidth = updateOffsetWidth
        ) {
      val exposedInsertFire = expose(cacheInsertFires(0))
      val exposedUpdateApplied = expose(updateApplied(0))
      val exposedResolutionIssued = expose(resolutionIssued(0))
    }

    test(new ExposedCollisionArgumentServer).withAnnotations(anns) { dut =>
      init(dut, 1, 2)

      val lines = 240
      // One-hot payloads: a spawn or eviction carrying more than one bit proves
      // a stale row slice leaked across a slot's tenants.
      val payloadBits = 12
      val payloadSlot = 1
      val shift = payloadSlot * updatePayloadWidth - counterWidth
      var insertCollisions = 0
      var readWriteCollisions = 0
      var spawns = 0
      var evictions = 0
      var diverts = 0

      def step(n: Int = 1): Unit = for (_ <- 0 until n) {
        val applied = dut.exposedUpdateApplied.peek().litToBoolean
        if (dut.exposedInsertFire.peek().litToBoolean && applied)
          insertCollisions += 1
        if (dut.exposedResolutionIssued.peek().litToBoolean && applied)
          readWriteCollisions += 1

        val sp = dut.io.spawnTaskOutputs(0)
        if (sp.valid.peek().litToBoolean) {
          spawns += 1
          val (counter, remainder) =
            NanTestUtil.splitLine(counterWidth, continuationSize)(
              sp.bits.taskData.peek().litValue
            )
          assert(counter == 0, s"spawned a line with counter $counter")
          assert(
            remainder.bitCount == 1 && (remainder >> shift) << shift == remainder,
            s"spawn carried $remainder: a stale row slice leaked into this slot"
          )
        }

        val cp = dut.io.coupledSlowPath(0)
        if (cp.valid.peek().litToBoolean) {
          if (cp.bits.evictionValid.peek().litToBoolean) {
            evictions += 1
            val (_, remainder) =
              NanTestUtil.splitLine(counterWidth, continuationSize)(
                cp.bits.eviction.eviction.taskData.peek().litValue
              )
            assert(
              remainder == 0,
              s"eviction carried $remainder: a stale row slice leaked into this slot"
            )
          }
          if (cp.bits.updateValid.peek().litToBoolean) diverts += 1
        }
        dut.clock.step()
      }

      def doInsert(addr: BigInt, remainder: BigInt): BigInt = {
        val port = dut.io.newContInput(0)
        port.req.bits.address.poke(addr.U)
        port.req.bits.taskBaseData.poke(mkLine(1, remainder).U)
        port.req.valid.poke(true.B)
        var guard = 0
        while (!port.req.ready.peek().litToBoolean) {
          step(); guard += 1; assert(guard < 50, "insert never accepted")
        }
        val id = port.assignedId.peek().litValue
        step()
        port.req.valid.poke(false.B)
        id
      }

      def doUpdate(id: BigInt, addr: BigInt, payload: BigInt): Unit = {
        val port = dut.io.contUpdateInput(0)
        driveUpdate(dut, 0, 0, id, addr, offset = payloadSlot, payload = payload)
        var guard = 0
        while (!port.ready.peek().litToBoolean) {
          step(); guard += 1; assert(guard < 50, "update never accepted")
        }
        step()
        port.valid.poke(false.B)
      }

      for (k <- 0 until lines) {
        val addr = BigInt(0x1000 + 0x10 * k)
        val payload = BigInt(1) << (k % payloadBits)
        val id = doInsert(addr, 0x0)
        step(k % 3)
        doUpdate(id, addr, payload)
        // The masked write trails the update handshake by a fixed pipeline
        // latency, so THIS gap is the one that walks it across the next
        // insert's commit cycle. Sweeping the gap above only moves both
        // together.
        step(k % 5)
      }
      step(40)

      assert(
        insertCollisions > 0,
        "stimulus never landed an insert and an update in the same cycle: " +
          "the single-write-port hazard was not exercised"
      )
      assert(
        readWriteCollisions > 0,
        "stimulus never landed a tail read and an update write in the same " +
          "cycle: the memory's independent-port assumption was not exercised"
      )
      // Each line joins on exactly one argument. A line whose update hit the
      // cache completes and spawns; a line whose update missed is diverted to
      // the slow path and its still-counting body is evicted. Nothing else may
      // be evicted, so a lost insert-clear (which corrupts a tenant's counter
      // and turns its spawn into an eviction) shows up as this imbalance.
      assert(
        evictions == diverts,
        s"$evictions evictions vs $diverts slow-path diverts " +
          s"(insert collisions observed: $insertCollisions)"
      )
      assert(
        spawns + evictions >= lines - slots,
        s"only ${spawns + evictions} of $lines lines resolved"
      )
    }
  }
}
