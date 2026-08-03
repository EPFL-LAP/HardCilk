package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier._

object BridgeTestCfg {
  val cfg = ArgumentNetworksConfig(
    nServers = 2,
    newLanesPerServer = 2,
    updateLanesPerServer = 2,
    nSlowHandlers = 1,
    nEvictionSavers = 1,
    counterWidth = 8,
    sysAddressWidth = 64,
    realAddressWidth = 32,
    serverIDWidth = 3,
    continuationSize = 128,
    updatePayloadWidth = 32,
    updateOffsetWidth = 2,
    slowCutCount = 1
  )
}
// The bridge that replaces the WriteROB of the spawnNext WriteBufferCounter:
// AXI write in -> cache insert out, B on accept, (server, id, lane) metadata
// token captured in the fire cycle.
class NewContinuationBridgeTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "NewContinuationBridge"

  import BridgeTestCfg.cfg

  private val data0 = BigInt("0123456789abcdef0011223344556677", 16)

  private def init(dut: NewContinuationBridge): Unit = {
    val fm = dut.io.from_master
    fm.AWVALID.get.poke(false.B)
    fm.WVALID.get.poke(false.B)
    fm.BREADY.get.poke(true.B)
    dut.io.newContReq.ready.poke(false.B)
    dut.io.continuationOut.ready.poke(true.B)
    dut.io.assignedId.poke(0.U)
    dut.io.assignedLane.poke(0.U)
  }

  // Present one AW+W pair and hold it until both channels are accepted.
  private def driveWrite(
      dut: NewContinuationBridge,
      addr: BigInt,
      data: BigInt,
      maxCycles: Int = 50
  ): Unit = {
    val fm = dut.io.from_master
    fm.AWADDR.get.poke(addr.U)
    fm.AWVALID.get.poke(true.B)
    fm.WDATA.get.poke(data.U)
    fm.WSTRB.get.poke(((BigInt(1) << (cfg.continuationSize / 8)) - 1).U)
    fm.WLAST.get.poke(true.B)
    fm.WVALID.get.poke(true.B)
    var aw = false
    var w = false
    var guard = 0
    while (!(aw && w)) {
      val awNow = fm.AWREADY.get.peek().litToBoolean
      val wNow = fm.WREADY.get.peek().litToBoolean
      dut.clock.step()
      if (!aw && awNow) { aw = true; fm.AWVALID.get.poke(false.B) }
      if (!w && wNow) { w = true; fm.WVALID.get.poke(false.B) }
      guard += 1
      assert(guard < maxCycles, "AXI write never fully accepted")
    }
  }

  private def reqFires(dut: NewContinuationBridge): Boolean =
    dut.io.newContReq.valid.peek().litToBoolean &&
      dut.io.newContReq.ready.peek().litToBoolean

  it should "hold the write (and B) until the server accepts, then emit both exactly once" in {
    test(new NewContinuationBridge(cfg, serverIndex = 1)) { dut =>
      init(dut)
      dut.io.assignedId.poke(5.U)
      dut.io.assignedLane.poke(1.U)

      // Server not ready: request must be presented but nothing may complete.
      val fm = dut.io.from_master
      fm.AWADDR.get.poke(BigInt(0x1000).U)
      fm.AWVALID.get.poke(true.B)
      fm.WDATA.get.poke(data0.U)
      fm.WLAST.get.poke(true.B)
      fm.WVALID.get.poke(true.B)

      var guard = 0
      while (!dut.io.newContReq.valid.peek().litToBoolean) {
        dut.clock.step(); guard += 1
        assert(guard < 20, "insert request never presented")
      }
      dut.io.newContReq.bits.address.expect(BigInt(0x1000 >> cfg.lineShift).U)
      dut.io.newContReq.bits.taskBaseData.expect(data0.U)
      for (_ <- 0 until 5) {
        assert(!fm.BVALID.get.peek().litToBoolean, "B before server accept")
        assert(!dut.io.continuationOut.valid.peek().litToBoolean, "reference before accept")
        dut.clock.step()
      }

      // Accept: over the next cycles exactly ONE B and one metadata token
      // (captured at fire time) must appear, and the AXI write retires.
      dut.io.newContReq.ready.poke(true.B)
      var bCount = 0
      var metaSeen = false
      var aw = false
      var w = false
      for (_ <- 0 until 15) {
        if (fm.BVALID.get.peek().litToBoolean) bCount += 1
        if (dut.io.continuationOut.valid.peek().litToBoolean && !metaSeen) {
          dut.io.continuationOut.bits.address.expect((0x1000 >> cfg.lineShift).U)
          dut.io.continuationOut.bits.metadata.server.expect(1.U)
          dut.io.continuationOut.bits.metadata.id.expect(5.U)
          dut.io.continuationOut.bits.metadata.lane.expect(1.U)
          metaSeen = true
        }
        val awNow = fm.AWREADY.get.peek().litToBoolean
        val wNow = fm.WREADY.get.peek().litToBoolean
        dut.clock.step()
        if (!aw && awNow) { aw = true; fm.AWVALID.get.poke(false.B) }
        if (!w && wNow) { w = true; fm.WVALID.get.poke(false.B) }
      }
      assert(aw && w, "AXI write never retired after accept")
      assert(metaSeen, "metadata token never appeared")
      assert(bCount == 1, s"expected exactly one B response, saw $bCount")
    }
  }

  it should "capture per-write metadata in order" in {
    test(new NewContinuationBridge(cfg, serverIndex = 0)) { dut =>
      init(dut)
      dut.io.newContReq.ready.poke(true.B)

      // Emulate the server's head pointer: id follows the accept count.
      for (i <- 0 until 3) {
        dut.io.assignedId.poke(i.U)
        driveWrite(dut, 0x2000 + 0x10 * i, data0 + i)
        // continuationOut.ready is high, so each token pops as it appears.
        var guard = 0
        while (!dut.io.continuationOut.valid.peek().litToBoolean) {
          dut.clock.step(); guard += 1
          assert(guard < 10, s"metadata token $i never appeared")
        }
        dut.io.continuationOut.bits.metadata.id.expect(i.U)
        dut.io.continuationOut.bits.metadata.server.expect(0.U)
        dut.clock.step()
      }
    }
  }

  it should "backpressure the master when metadata tokens are not drained" in {
    test(new NewContinuationBridge(cfg, serverIndex = 0)) { dut =>
      init(dut)
      dut.io.newContReq.ready.poke(true.B)
      dut.io.continuationOut.ready.poke(false.B)

      // The 4-deep continuation-reference queue lets 4 writes through...
      for (i <- 0 until 4) {
        driveWrite(dut, 0x3000 + 0x10 * i, data0 + i)
      }

      // ...the 5th must stall (no accept => no fire on the insert port).
      val fm = dut.io.from_master
      fm.AWADDR.get.poke(BigInt(0x5000).U)
      fm.AWVALID.get.poke(true.B)
      fm.WDATA.get.poke(data0.U)
      fm.WLAST.get.poke(true.B)
      fm.WVALID.get.poke(true.B)
      for (_ <- 0 until 10) {
        assert(!reqFires(dut), "insert fired with the reference queue full")
        dut.clock.step()
      }

      // Draining the tokens unblocks the stalled write.
      dut.io.continuationOut.ready.poke(true.B)
      var guard = 0
      var done = false
      while (!done) {
        if (reqFires(dut)) done = true
        dut.clock.step(); guard += 1
        assert(guard < 20, "stalled write never completed after drain")
      }
      fm.AWVALID.get.poke(false.B)
      fm.WVALID.get.poke(false.B)
    }
  }
}
