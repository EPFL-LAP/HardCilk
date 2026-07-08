package Util.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import chext.amba.axi4
import Util.WriteROB

// Scaffolding for the WriteROB. These describe the intended behaviour:
//   - a single-ID, in-order write master (from_master, wId == 0)
//   - re-tagged onto rotating IDs toward the slave (to_slave, wId > 0) so the
//     memory can keep 2^wId writes in flight
//   - B responses reassembled into issue order before returning to the master
// The module is currently a bare skeleton (no datapath), so every case here is
// expected to FAIL until the body is implemented.
class WriteROBTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "WriteROB"

  private val cfgIn = axi4.Config(wId = 0, wAddr = 64, wData = 512, read = false)
  private val cfgOut = cfgIn.copy(wId = 2) // 2 ID bits => 4 outstanding slots
  private val entries = 1 << cfgOut.wId

  // Slave side always ready to accept AW/W; B is driven explicitly per test.
  private def slaveIdleReady(dut: WriteROB): Unit = {
    dut.io.to_slave.AWREADY.get.poke(true.B)
    dut.io.to_slave.WREADY.get.poke(true.B)
    dut.io.to_slave.BVALID.get.poke(false.B)
    dut.io.from_master.BREADY.get.poke(true.B)
  }

  // Drive one single-beat write into the master port; returns the AWID the ROB
  // handed out on the slave side.
  private def pushWrite(dut: WriteROB, addr: BigInt, data: BigInt): BigInt = {
    val fm = dut.io.from_master
    fm.AWADDR.get.poke(addr.U)
    fm.WDATA.get.poke(data.U)
    fm.WLAST.get.poke(true.B)
    fm.AWVALID.get.poke(true.B)
    fm.WVALID.get.poke(true.B)
    var guard = 0
    while (!(fm.AWREADY.get.peek().litToBoolean && fm.WREADY.get.peek().litToBoolean)) {
      dut.clock.step(); guard += 1; assert(guard < 100, "master write never accepted")
    }
    val outId = dut.io.to_slave.AWID.get.peek().litValue
    dut.clock.step()
    fm.AWVALID.get.poke(false.B)
    fm.WVALID.get.poke(false.B)
    outId
  }

  // Return a completion for a given slave ID.
  private def completeOnSlave(dut: WriteROB, id: BigInt): Unit = {
    val ts = dut.io.to_slave
    ts.BID.get.poke(id.U)
    ts.BRESP.get.poke(0.U)
    ts.BVALID.get.poke(true.B)
    var guard = 0
    while (!ts.BREADY.get.peek().litToBoolean) {
      dut.clock.step(); guard += 1; assert(guard < 100, "slave B never accepted")
    }
    dut.clock.step()
    ts.BVALID.get.poke(false.B)
  }

  // Consume one B on the master side, failing if none appears within `within`.
  private def expectMasterB(dut: WriteROB, within: Int = 8): Unit = {
    var guard = 0
    while (!dut.io.from_master.BVALID.get.peek().litToBoolean) {
      dut.clock.step(); guard += 1; assert(guard < within, "expected a master B, saw none")
    }
    dut.clock.step()
  }

  private def noMasterB(dut: WriteROB, forCycles: Int = 8): Unit = {
    for (_ <- 0 until forCycles) {
      assert(!dut.io.from_master.BVALID.get.peek().litToBoolean, "master B fired too early")
      dut.clock.step()
    }
  }

  it should "hand out distinct rotating AXI IDs on the slave side" in {
    test(new WriteROB(cfgIn, cfgOut)) { dut =>
      slaveIdleReady(dut)
      val ids = (0 until entries).map(i => pushWrite(dut, 0x1000 + 0x40 * i, i))
      assert(ids == Seq[BigInt](0, 1, 2, 3), s"unexpected ID sequence: $ids")
    }
  }

  it should "return B responses in issue order despite out-of-order slave completion" in {
    test(new WriteROB(cfgIn, cfgOut)) { dut =>
      slaveIdleReady(dut)
      (0 until entries).foreach(i => pushWrite(dut, 0x2000 + 0x40 * i, i))

      // Complete slot 2 first: head is still slot 0, so nothing may retire yet.
      completeOnSlave(dut, 2)
      noMasterB(dut)

      // Complete slot 0: exactly one B retires (write 0); write 1 still pending.
      completeOnSlave(dut, 0)
      expectMasterB(dut)
      noMasterB(dut)

      // Complete slot 1: writes 1 and 2 (already done) retire in order.
      completeOnSlave(dut, 1)
      expectMasterB(dut)
      expectMasterB(dut)

      // Complete slot 3: final retire.
      completeOnSlave(dut, 3)
      expectMasterB(dut)
    }
  }

  it should "backpressure AW once `entries` writes are outstanding" in {
    test(new WriteROB(cfgIn, cfgOut)) { dut =>
      slaveIdleReady(dut)
      (0 until entries).foreach(i => pushWrite(dut, 0x3000 + 0x40 * i, i))

      // ROB is full: a further AW must not be accepted while nothing has retired.
      val fm = dut.io.from_master
      fm.AWADDR.get.poke(BigInt(0x9000).U)
      fm.AWVALID.get.poke(true.B)
      for (_ <- 0 until 8) {
        assert(!fm.AWREADY.get.peek().litToBoolean, "AW accepted while ROB full")
        dut.clock.step()
      }
      fm.AWVALID.get.poke(false.B)
    }
  }

  it should "pass write data through to the slave unchanged" in {
    test(new WriteROB(cfgIn, cfgOut)) { dut =>
      slaveIdleReady(dut)
      val fm = dut.io.from_master
      val ts = dut.io.to_slave
      val data = BigInt("deadbeef", 16)
      fm.AWADDR.get.poke(BigInt(0x4000).U)
      fm.WDATA.get.poke(data.U)
      fm.WLAST.get.poke(true.B)
      fm.AWVALID.get.poke(true.B)
      fm.WVALID.get.poke(true.B)
      var guard = 0
      while (!ts.WVALID.get.peek().litToBoolean) {
        dut.clock.step(); guard += 1; assert(guard < 100, "no W presented to slave")
      }
      assert(ts.WDATA.get.peek().litValue == data, "W data corrupted through ROB")
    }
  }
}
