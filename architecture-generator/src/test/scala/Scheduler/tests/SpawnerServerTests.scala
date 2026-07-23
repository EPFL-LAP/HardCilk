package Scheduler.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.SpawnerServer

import scala.collection.mutable

class SpawnerServerTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SpawnerServer"

  private val taskWidth = 64

  it should "repay a forced insertion with a later request and a data hole" in {
    test(new SpawnerServer(taskWidth, queueDepth = 4)) { dut =>
      dut.clock.setTimeout(0)

      dut.io.connNetwork_slave.data.qOutTask.ready.poke(true.B)
      dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(false.B)
      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(false.B)
      dut.io.connNetwork_master.data.availableTask.valid.poke(false.B)
      dut.io.connNetwork_master.data.availableTask.bits.poke(0.U)
      dut.io.connNetwork_master.ctrl.stealReq.ready.poke(false.B)

      // Prime to entries-1 without allowing the local PE ring to drain.
      dut.io.connNetwork_master.data.qOutTask.ready.poke(false.B)
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(false.B)
      for (task <- 0 until 3) {
        dut.io.connNetwork_slave.data.availableTask.valid.poke(true.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(task.U)
        dut.io.connNetwork_slave.data.availableTask.ready.expect(true.B)
        dut.clock.step()
      }

      // Another arriving task makes the saturated spawner force one task without a request.  This
      // must create a balance of -1: one future request has already received its task.
      dut.io.connNetwork_slave.data.availableTask.bits.poke(3.U)
      dut.io.connNetwork_master.data.qOutTask.ready.poke(true.B)
      dut.io.connNetwork_master.data.qOutTask.valid.expect(true.B)
      dut.clock.step()

      // A visible request must not suppress force while source pressure still requires it.  The
      // current request pays for the current forced task, leaving the earlier force debt unchanged.
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(true.B)
      dut.io.connNetwork_master.ctrl.serveStealReq.valid.expect(true.B)
      dut.io.connNetwork_master.data.qOutTask.valid.expect(true.B)
      dut.io.connNetwork_slave.data.availableTask.ready.expect(true.B)
      dut.clock.step()

      // Once forcing is no longer necessary, the next request pays the outstanding force debt and
      // leaves the scheduler its hole.
      dut.io.connNetwork_slave.data.availableTask.valid.poke(false.B)
      dut.io.connNetwork_master.ctrl.serveStealReq.valid.expect(true.B)
      dut.io.connNetwork_master.data.qOutTask.valid.expect(false.B)
      dut.clock.step()

      // With the balance back at zero, a further request creates a positive promise.  Ordinary
      // insertion is authorized by that registered balance on the following cycle, never by a
      // combinational request bypass.
      dut.io.connNetwork_master.ctrl.serveStealReq.valid.expect(true.B)
      dut.io.connNetwork_master.data.qOutTask.valid.expect(false.B)
      dut.clock.step()
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(false.B)
      dut.io.connNetwork_master.data.qOutTask.valid.expect(true.B)
      dut.clock.step()
    }
  }

  // Regression for the relay II=2 bug: a spawner whose own PE never asks (pinned busy by the
  // scheduler server) is a pure relay -- source stream in on availableTask, straight out to a
  // demanding peer on qOutTask. The old `enq := availableTask.valid && !canServePeer` gate blocked
  // intake on every hand-off cycle, so accept and hand-off strictly alternated and the relay capped
  // at one task every OTHER cycle (II=2). Swallow-and-share removed the gate; enq and deq now fire
  // together, so after the queue primes by one, the relay forwards a task every cycle (II=1).
  //
  // Under the old gate this test observes ~half a hand-off per cycle in the steady window and the
  // `steadyDeliveries == steadyWindow` assertion fails.
  it should "relay a saturated source stream to a demanding peer at II=1" in {
    test(new SpawnerServer(taskWidth)) { dut =>
      dut.clock.setTimeout(0)

      // Our own PE never asks -> pure relay (the pinned-PE case that triggered the bug).
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(false.B)
      dut.io.connNetwork_master.data.qOutTask.ready.poke(true.B)

      // A peer is always demanding and the outside-ring injection slot is always free, so every
      // queued task can be handed off the cycle it becomes available.
      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(true.B)
      dut.io.connNetwork_slave.data.qOutTask.ready.poke(true.B)
      // Let our own steal requests land too (keeps desiredSteals balanced; irrelevant to the II).
      dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(true.B)

      val cycles = 60
      val warmup = 20 // saturated long before here (the queue primes on cycle 0)
      val steadyWindow = cycles - warmup

      var nextIn = 0
      val delivered = mutable.ArrayBuffer.empty[Int]
      var steadyDeliveries = 0

      for (c <- 0 until cycles) {
        // Present the next distinct source task.
        dut.io.connNetwork_slave.data.availableTask.valid.poke(true.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(nextIn.U)

        // Combinational outputs for this cycle.
        val srcAccepted =
          dut.io.connNetwork_slave.data.availableTask.ready.peek().litToBoolean
        val handedOff =
          dut.io.connNetwork_slave.data.qOutTask.valid.peek().litToBoolean
        if (handedOff) {
          delivered += dut.io.connNetwork_slave.data.qOutTask.bits
            .peek()
            .litValue
            .toInt
          if (c >= warmup) steadyDeliveries += 1
        }

        dut.clock.step()
        if (srcAccepted) nextIn += 1
      }

      // II=1 after saturation: exactly one hand-off per cycle across the whole steady window.
      assert(
        steadyDeliveries == steadyWindow,
        s"expected II=1 ($steadyWindow hand-offs) after saturation, got $steadyDeliveries " +
          s"(the old !canServePeer gate serialized this to ~${steadyWindow / 2})"
      )

      // Conservation + ordering: the queue is FIFO, so deliveries must be the contiguous prefix
      // 0, 1, 2, ... with nothing lost, duplicated, or reordered.
      assert(delivered.nonEmpty, "no tasks were forwarded at all")
      for (i <- delivered.indices)
        assert(
          delivered(i) == i,
          s"out-of-order/lost/duplicated hand-off at index $i: got ${delivered(i)}"
        )

      println(
        s"+ forwarded ${delivered.size} tasks in $cycles cycles; " +
          s"II=1 confirmed over the last $steadyWindow (steady $steadyDeliveries/$steadyWindow)"
      )
    }
  }
}
