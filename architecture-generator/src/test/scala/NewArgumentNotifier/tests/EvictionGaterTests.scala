package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.EvictionGater

class EvictionGaterTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "EvictionGater"

  private val lineAddressWidth = 28
  private val continuationSize = 128
  private val serverTagWidth = 1
  private val serverIDWidth = 3
  private val laneWidth = 2

  private def dutGen(
      slowRequestDepth: Int = 64,
      nEvictionSaverLanes: Int = 1,
      saverOf: UInt => UInt = (_: UInt) => 0.U
  ) =
    new EvictionGater(
      lineAddressWidth,
      continuationSize,
      serverTagWidth,
      serverIDWidth,
      laneWidth,
      nEvictionSaverLanes = nEvictionSaverLanes,
      saverOf = saverOf,
      slowRequestDepth = slowRequestDepth,
      counterWidth = 64
    )

  private def init(dut: EvictionGater): Unit = {
    dut.io.coupledIn.valid.poke(false.B)
    dut.io.evictionOut.ready.poke(true.B)
    dut.io.slowUpdateOut.ready.poke(true.B)
    for (w <- dut.io.writeCompleted) {
      w.valid.poke(false.B)
      w.bits.server.poke(0.U)
      w.bits.id.poke(0.U)
      w.bits.lane.poke(0.U)
    }
  }

  private def driveEntry(
      dut: EvictionGater,
      eviction: Boolean,
      update: Boolean,
      evictionId: Int,
      updateId: Int,
      evictionAddress: BigInt = 0x100,
      updateAddress: BigInt = 0x100
  ): Unit = {
    val in = dut.io.coupledIn
    in.bits.spawnValid.poke(false.B)
    in.bits.evictionValid.poke(eviction.B)
    in.bits.eviction.eviction.address.poke(evictionAddress.U)
    in.bits.eviction.eviction.taskData.poke(0x55.U)
    in.bits.eviction.metadata.server.poke(0.U)
    in.bits.eviction.metadata.id.poke(evictionId.U)
    in.bits.eviction.metadata.lane.poke(0.U)
    in.bits.updateValid.poke(update.B)
    in.bits.update.update.address.poke(updateAddress.U)
    in.bits.update.update.dataWrite.poke(0xaa.U)
    in.bits.update.metadata.server.poke(0.U)
    in.bits.update.metadata.id.poke(updateId.U)
    in.bits.update.metadata.lane.poke(0.U)
    in.valid.poke(true.B)
  }

  private def acceptEntry(dut: EvictionGater): Unit = {
    var guard = 0
    while (!dut.io.coupledIn.ready.peek().litToBoolean) {
      dut.clock.step()
      guard += 1
      assert(guard < 30, "coupled entry never accepted")
    }
    dut.clock.step()
    dut.io.coupledIn.valid.poke(false.B)
  }

  // A saver-lane completion is a bare pulse (Valid, no ready/backpressure);
  // which lane it lands on is the only thing that matters to the gater.
  private def complete(dut: EvictionGater, lane: Int = 0): Unit = {
    dut.io.writeCompleted(lane).valid.poke(true.B)
    dut.clock.step()
    dut.io.writeCompleted(lane).valid.poke(false.B)
  }

  it should "give an eviction priority over a same-key update in one entry" in {
    test(dutGen()) { dut =>
      init(dut)
      driveEntry(dut, eviction = true, update = true, evictionId = 1, updateId = 1)
      assert(dut.io.evictionOut.valid.peek().litToBoolean)
      assert(!dut.io.slowUpdateOut.valid.peek().litToBoolean)
      acceptEntry(dut)

      for (_ <- 0 until 4) {
        assert(!dut.io.slowUpdateOut.valid.peek().litToBoolean)
        dut.clock.step()
      }

      complete(dut)
      var guard = 0
      while (!dut.io.slowUpdateOut.valid.peek().litToBoolean) {
        dut.clock.step()
        guard += 1
        assert(guard < 10, "held update did not release")
      }
      dut.io.slowUpdateOut.bits.address.expect(0x100.U)
    }
  }

  it should "hold all later updates behind the preceding completion fence" in {
    test(dutGen()) { dut =>
      init(dut)
      driveEntry(dut, eviction = true, update = true, evictionId = 1, updateId = 1)
      acceptEntry(dut)

      driveEntry(
        dut,
        eviction = false,
        update = true,
        evictionId = 0,
        updateId = 2,
        updateAddress = 0x200
      )
      acceptEntry(dut)

      assert(!dut.io.slowUpdateOut.valid.peek().litToBoolean)
      complete(dut)
      assert(dut.io.slowUpdateOut.valid.peek().litToBoolean)
      dut.io.slowUpdateOut.bits.address.expect(0x100.U)
      dut.clock.step()
      assert(dut.io.slowUpdateOut.valid.peek().litToBoolean)
      dut.io.slowUpdateOut.bits.address.expect(0x200.U)
    }
  }

  it should "release by completion sequence regardless of returned id and lane" in {
    test(dutGen()) { dut =>
      init(dut)
      for (address <- Seq(BigInt(0x100), BigInt(0x180))) {
        driveEntry(
          dut,
          eviction = true,
          update = false,
          evictionId = 3,
          updateId = 0,
          evictionAddress = address
        )
        acceptEntry(dut)
      }
      driveEntry(
        dut,
        eviction = false,
        update = true,
        evictionId = 0,
        updateId = 3
      )
      acceptEntry(dut)

      complete(dut)
      for (_ <- 0 until 3) {
        assert(!dut.io.slowUpdateOut.valid.peek().litToBoolean)
        dut.clock.step()
      }
      complete(dut)
      assert(dut.io.slowUpdateOut.valid.peek().litToBoolean)
    }
  }

  it should "accept more than eight outstanding eviction writes" in {
    test(dutGen()) { dut =>
      init(dut)
      val writeCount = 12
      for (index <- 0 until writeCount) {
        driveEntry(
          dut,
          eviction = true,
          update = false,
          evictionId = index % 8,
          updateId = 0,
          evictionAddress = 0x100 + index
        )
        assert(dut.io.coupledIn.ready.peek().litToBoolean)
        acceptEntry(dut)
      }

      driveEntry(
        dut,
        eviction = false,
        update = true,
        evictionId = 0,
        updateId = 5,
        updateAddress = 0x555
      )
      acceptEntry(dut)

      for (completion <- 0 until writeCount - 1) {
        complete(dut)
        assert(!dut.io.slowUpdateOut.valid.peek().litToBoolean)
      }
      complete(dut)
      assert(dut.io.slowUpdateOut.valid.peek().litToBoolean)
      dut.io.slowUpdateOut.bits.address.expect(0x555.U)
    }
  }

  it should "recover when its slow-request FIFO fills" in {
    test(dutGen(slowRequestDepth = 2)) { dut =>
      init(dut)
      driveEntry(
        dut,
        eviction = true,
        update = false,
        evictionId = 1,
        updateId = 0
      )
      acceptEntry(dut)

      for (address <- Seq(BigInt(0x101), BigInt(0x102))) {
        driveEntry(
          dut,
          eviction = false,
          update = true,
          evictionId = 0,
          updateId = 1,
          updateAddress = address
        )
        acceptEntry(dut)
      }

      driveEntry(
        dut,
        eviction = false,
        update = true,
        evictionId = 0,
        updateId = 1,
        updateAddress = 0x103
      )
      for (_ <- 0 until 3) {
        assert(!dut.io.coupledIn.ready.peek().litToBoolean)
        dut.clock.step()
      }

      // Completion feedback is out-of-band, so it remains consumable despite
      // input backpressure. The newly eligible FIFO head drains on the next
      // edge and restores input space without any table-key dependency.
      complete(dut)
      dut.clock.step()
      assert(dut.io.coupledIn.ready.peek().litToBoolean)
      acceptEntry(dut)
    }
  }

  // ---- Multi-lane (nEvictionSavers > 1) --------------------------------------

  // Route by address bit 0: even -> lane 0, odd -> lane 1.
  private val twoLaneSaverOf: UInt => UInt = (addr: UInt) => addr(0)

  it should "fence an update on its own address's lane only, ignoring completions on other lanes" in {
    test(dutGen(nEvictionSaverLanes = 2, saverOf = twoLaneSaverOf)) { dut =>
      init(dut)
      // Two unrelated evictions land on different lanes: 0x100 -> lane 0,
      // 0x101 -> lane 1.
      driveEntry(dut, eviction = true, update = false, evictionId = 0, updateId = 0, evictionAddress = 0x100)
      acceptEntry(dut)
      driveEntry(dut, eviction = true, update = false, evictionId = 1, updateId = 0, evictionAddress = 0x101)
      acceptEntry(dut)

      // A later pure update for 0x101 (lane 1) depends only on lane 1's fence.
      driveEntry(dut, eviction = false, update = true, evictionId = 0, updateId = 2, updateAddress = 0x101)
      acceptEntry(dut)

      // Completing the WRONG lane (0) must not release it.
      complete(dut, lane = 0)
      for (_ <- 0 until 4) {
        assert(!dut.io.slowUpdateOut.valid.peek().litToBoolean)
        dut.clock.step()
      }

      // Completing its OWN lane (1) releases it.
      complete(dut, lane = 1)
      var guard = 0
      while (!dut.io.slowUpdateOut.valid.peek().litToBoolean) {
        dut.clock.step()
        guard += 1
        assert(guard < 10, "update never released on its own lane's completion")
      }
      dut.io.slowUpdateOut.bits.address.expect(0x101.U)
    }
  }

  it should "still resolve a coupled same-line eviction+update pair correctly with multiple lanes" in {
    test(dutGen(nEvictionSaverLanes = 2, saverOf = twoLaneSaverOf)) { dut =>
      init(dut)
      // Same address in one coupled entry: eviction and update always land on
      // the same lane (here, address 0x101 -> lane 1) by construction.
      driveEntry(dut, eviction = true, update = true, evictionId = 1, updateId = 1, evictionAddress = 0x101, updateAddress = 0x101)
      acceptEntry(dut)

      // A completion on the unrelated lane 0 must not release it.
      complete(dut, lane = 0)
      for (_ <- 0 until 4) {
        assert(!dut.io.slowUpdateOut.valid.peek().litToBoolean)
        dut.clock.step()
      }

      // Its own lane's completion releases it.
      complete(dut, lane = 1)
      var guard = 0
      while (!dut.io.slowUpdateOut.valid.peek().litToBoolean) {
        dut.clock.step()
        guard += 1
        assert(guard < 10, "held update did not release on its own lane")
      }
      dut.io.slowUpdateOut.bits.address.expect(0x101.U)
    }
  }
}
