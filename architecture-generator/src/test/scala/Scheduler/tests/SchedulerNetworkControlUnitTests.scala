package Scheduler.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.{SchedulerNetwork, SchedulerNetworkControlUnit}

class SchedulerControlRingHarness(n: Int) extends Module {
  val io = IO(new Bundle {
    val request = Input(Vec(n, Bool()))
    val requestAccepted = Output(Vec(n, Bool()))
    val serve = Input(Vec(n, Bool()))
    val requestPresent = Output(Vec(n, Bool()))
  })

  private val network = Module(new SchedulerNetwork(8, n, Array.empty[Int]))
  for (i <- 0 until n) {
    network.io.connSS(i).ctrl.stealReq.valid := io.request(i)
    io.requestAccepted(i) := network.io.connSS(i).ctrl.stealReq.ready
    network.io.connSS(i).ctrl.serveStealReq.valid := io.serve(i)
    io.requestPresent(i) := network.io.connSS(i).ctrl.serveStealReq.ready

    network.io.connSS(i).data.qOutTask.valid := false.B
    network.io.connSS(i).data.qOutTask.bits := 0.U
    network.io.connSS(i).data.availableTask.ready := false.B
  }
}

/** The hop keeps its backpressure and skid, which exist for GUARANTEED LOCAL INJECTION -- a node
  * whose hop is busy with passing requests must still be able to get its own request onto the ring,
  * or the nodes furthest from the supply starve systematically (measured: last lane of the feedback
  * ring at 616 of 1000 cycles without it, II=1 with it).
  *
  * What went is the bubble sideband. Its only job was to replay a request-free cycle at the next
  * hop so a consumed request kept a matching hole on the DATA ring; the data ring is elastic now
  * and makes its own holes, so there is nothing left for it to preserve.
  */
class SchedulerNetworkControlUnitTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SchedulerNetworkControlUnit local-priority flow control"

  private def init(dut: SchedulerNetworkControlUnit): Unit = {
    dut.io.reqTaskIn.poke(false.B)
    dut.io.stopIn.poke(false.B)
    dut.io.connSS.stealReq.valid.poke(false.B)
    dut.io.connSS.serveStealReq.valid.poke(false.B)
  }

  it should "give a continuous local requester II=1 and stop upstream" in {
    test(new SchedulerNetworkControlUnit) { dut =>
      init(dut)

      for (_ <- 0 until 8) {
        dut.io.connSS.stealReq.valid.poke(true.B)
        dut.io.stopOut.expect(true.B)
        dut.io.connSS.stealReq.ready.expect(true.B)
        // After the first insertion, the preceding local request advances as
        // the next one replaces it in the same cycle.
        dut.clock.step()
      }

      dut.io.connSS.stealReq.valid.poke(false.B)
      dut.io.stopOut.expect(false.B)
      dut.io.reqTaskOut.expect(true.B)
      dut.clock.step()
      dut.io.connSS.serveStealReq.ready.expect(false.B)
    }
  }

  it should "hold a local request until the resident ring request advances" in {
    test(new SchedulerNetworkControlUnit) { dut =>
      init(dut)

      // Put one transit request in the normal slot.
      dut.io.reqTaskIn.poke(true.B)
      dut.clock.step()
      dut.io.reqTaskIn.poke(false.B)

      // Downstream is blocked.  Local demand immediately stops upstream but
      // cannot overwrite the resident request.
      dut.io.stopIn.poke(true.B)
      dut.io.connSS.stealReq.valid.poke(true.B)
      dut.io.stopOut.expect(true.B)
      dut.io.connSS.stealReq.ready.expect(false.B)
      dut.clock.step(3)

      // As soon as downstream reopens, the old token advances and the local
      // token replaces it in the same cycle.
      dut.io.stopIn.poke(false.B)
      dut.io.reqTaskOut.expect(true.B)
      dut.io.connSS.stealReq.ready.expect(true.B)
      dut.clock.step()
      dut.io.connSS.stealReq.valid.poke(false.B)
      dut.io.connSS.serveStealReq.ready.expect(true.B)
    }
  }

  it should "consume a resident request locally without forwarding it" in {
    test(new SchedulerNetworkControlUnit) { dut =>
      init(dut)
      dut.io.reqTaskIn.poke(true.B)
      dut.clock.step()
      dut.io.reqTaskIn.poke(false.B)

      dut.io.connSS.serveStealReq.valid.poke(true.B)
      dut.io.connSS.serveStealReq.ready.expect(true.B)
      dut.io.reqTaskOut.expect(false.B)
      dut.clock.step()
      dut.io.connSS.serveStealReq.ready.expect(false.B)
    }
  }

  it should "let the nearest upstream requester capture the only released slot at II=1" in {
    val n = 4
    test(new SchedulerControlRingHarness(n)) { dut =>
      for (i <- 0 until n) {
        dut.io.request(i).poke(true.B)
        dut.io.serve(i).poke(false.B)
        dut.io.requestAccepted(i).expect(true.B)
      }
      dut.clock.step() // one request in every normal ring slot

      for (i <- 0 until n) dut.io.request(i).poke(false.B)
      // Requests move from node 2 -> 1 -> 0.  Nodes 1 and 2 both want each
      // slot consumed at node 0; node 1 must retain it and backpressure node 2.
      dut.io.serve(0).poke(true.B)
      dut.io.request(1).poke(true.B)
      dut.io.request(2).poke(true.B)

      // Node 0 consumes and replaces its resident request in the same cycle,
      // so the nearest requester runs at II=1 without exposing the slot to
      // node 2.
      for (_ <- 0 until 8) {
        dut.io.requestAccepted(1).expect(true.B)
        dut.io.requestAccepted(2).expect(false.B)
        dut.clock.step()
      }

      // Releasing the nearest requester exposes the skid slot immediately.
      dut.io.request(1).poke(false.B)
      dut.io.requestAccepted(2).expect(true.B)
      dut.clock.step()
    }
  }

}
