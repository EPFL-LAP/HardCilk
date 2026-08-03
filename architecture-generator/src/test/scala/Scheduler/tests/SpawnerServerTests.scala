package Scheduler.tests

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.SpawnerServer

import scala.collection.mutable

class SpawnerServerTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SpawnerServer"

  private val taskWidth = 64

  // Park every input that a given test does not drive itself.
  private def quiesce(dut: SpawnerServer): Unit = {
    dut.io.connNetwork_slave.data.availableTask.valid.poke(false.B)
    dut.io.connNetwork_slave.data.availableTask.bits.poke(0.U)
    dut.io.connNetwork_slave.data.qOutTask.ready.poke(false.B)
    dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(false.B)
    dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(false.B)
    dut.io.connNetwork_master.data.availableTask.valid.poke(false.B)
    dut.io.connNetwork_master.data.availableTask.bits.poke(0.U)
    dut.io.connNetwork_master.data.qOutTask.ready.poke(true.B)
    dut.io.connNetwork_master.ctrl.stealReq.ready.poke(false.B)
    dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(false.B)
  }

  // The core of the change: delivery to the co-located PE is no longer gated on a steal request.
  // Under the old protocol the push rate equalled the PE client's steal-credit rate, and that
  // credit is a momentum counter with no reserve -- one lost pop cost a request, which cost another
  // pop five cycles later, forever (VCD hw_emu, countDecoupled memReader PE4: pinned at 4/5 for the
  // whole run while its peers sat at 1.00). With nobody asking at all, the old spawner delivered
  // nothing; the new one delivers every cycle.
  it should "push to the PE ring at II=1 with no steal request ever offered" in {
    test(new SpawnerServer(taskWidth, queueDepth = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      quiesce(dut)

      // Never offer a steal token on the PE ring for the entire test.
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(false.B)

      val cycles = 40
      val warmup = 4 // queue primes on the first cycle; deq has no flow-through
      var nextIn = 0
      val delivered = mutable.ArrayBuffer.empty[Int]
      var steadyPushes = 0

      for (c <- 0 until cycles) {
        dut.io.connNetwork_slave.data.availableTask.valid.poke(true.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(nextIn.U)

        val accepted =
          dut.io.connNetwork_slave.data.availableTask.ready.peek().litToBoolean
        val pushed =
          dut.io.connNetwork_master.data.qOutTask.valid.peek().litToBoolean
        if (pushed) {
          delivered += dut.io.connNetwork_master.data.qOutTask.bits.peek().litValue.toInt
          if (c >= warmup) steadyPushes += 1
        }
        // Nothing may be consumed from a ring that is offering nothing.
        assert(
          !dut.io.connNetwork_master.ctrl.serveStealReq.valid.peek().litToBoolean ||
            !dut.io.connNetwork_master.ctrl.serveStealReq.ready.peek().litToBoolean,
          s"consumed a steal request at cycle $c when none was offered"
        )

        dut.clock.step()
        if (accepted) nextIn += 1
      }

      assert(
        steadyPushes == cycles - warmup,
        s"expected II=1 (${cycles - warmup} pushes) with no requests offered, got $steadyPushes"
      )
      // FIFO order, nothing lost or duplicated.
      for (i <- delivered.indices)
        assert(delivered(i) == i, s"out-of-order/lost push at index $i: got ${delivered(i)}")

      println(s"+ delivered ${delivered.size} tasks at II=1 with zero steal requests offered")
    }
  }

  // The steal ring is demoted, not deleted. Every task that lands on the PE ring still consumes
  // exactly one request when one is resident, so the clients' ledgers stay exact -- the spawner
  // simply never waits for one. Without this the ctrl ring silts up: the clients are the only
  // producers and this is the only consumer, so a client whose requests are never consumed cannot
  // inject (stealReq.ready is gated on the hop being empty) and its desiredSteals walks away.
  it should "consume exactly one steal request per delivered task when requests are offered" in {
    test(new SpawnerServer(taskWidth, queueDepth = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      quiesce(dut)
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(true.B)

      var pushes = 0
      var serves = 0
      var nextIn = 0

      for (_ <- 0 until 60) {
        dut.io.connNetwork_slave.data.availableTask.valid.poke(true.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(nextIn.U)

        val accepted =
          dut.io.connNetwork_slave.data.availableTask.ready.peek().litToBoolean
        if (
          dut.io.connNetwork_master.data.qOutTask.valid.peek().litToBoolean &&
          dut.io.connNetwork_master.data.qOutTask.ready.peek().litToBoolean
        ) pushes += 1
        if (
          dut.io.connNetwork_master.ctrl.serveStealReq.valid.peek().litToBoolean &&
          dut.io.connNetwork_master.ctrl.serveStealReq.ready.peek().litToBoolean
        ) serves += 1

        dut.clock.step()
        if (accepted) nextIn += 1
      }

      assert(pushes > 0, "nothing was delivered at all")
      assert(
        serves == pushes,
        s"ledger drifted: $pushes tasks delivered against $serves requests consumed"
      )
      println(s"+ $pushes delivered, $serves consumed -- one request per task, none wasted")
    }
  }

  // Regression for the half-full intake floor. The rule above the watermark is "keep taking while
  // we are pushing, or while nobody asked", and an EMPTY spawner has nothing to push -- so without
  // the floor, intake collapses to "nobody asked". Measured peer demand at a spawner node is
  // 0.70-0.90 steady state and was 1.00 for the whole 1150-cycle window before spawner 4 came up:
  // a cold lane would decline every task, never acquire one to push, and stay cold forever.
  //
  // Here peer demand is pinned high from the first cycle with an empty queue, which is exactly that
  // trap. The spawner must still take work and reach II=1.
  it should "cold-start under permanent peer demand" in {
    test(new SpawnerServer(taskWidth, queueDepth = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      quiesce(dut)

      // A peer is asking on the outside ring on every single cycle, from empty.
      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(true.B)
      dut.io.connNetwork_slave.data.qOutTask.ready.poke(true.B)

      var nextIn = 0
      var pushes = 0
      val cycles = 40
      val warmup = 8

      for (c <- 0 until cycles) {
        dut.io.connNetwork_slave.data.availableTask.valid.poke(true.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(nextIn.U)

        val accepted =
          dut.io.connNetwork_slave.data.availableTask.ready.peek().litToBoolean
        if (
          c >= warmup &&
          dut.io.connNetwork_master.data.qOutTask.valid.peek().litToBoolean &&
          dut.io.connNetwork_master.data.qOutTask.ready.peek().litToBoolean
        ) pushes += 1

        dut.clock.step()
        if (accepted) nextIn += 1
      }

      assert(
        pushes == cycles - warmup,
        s"cold-start starved under permanent peer demand: ${cycles - warmup} pushes expected, got $pushes"
      )
      println(s"+ cold-started to II=1 with peer demand asserted on every cycle")
    }
  }

  // Above the watermark, a task we cannot use right now belongs to the peer that asked for it.
  // Only ever declined when a request is actually riding the ctrl ring, so a declined task always
  // has a requester waiting downstream -- it is never shed into a ring nobody is draining.
  it should "let a task flow by only when holding surplus and a peer is asking" in {
    test(new SpawnerServer(taskWidth, queueDepth = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      quiesce(dut)

      // Block the PE ring so we are never "pushing this cycle" and the queue can build.
      dut.io.connNetwork_master.data.qOutTask.ready.poke(false.B)

      // Fill to the half-full watermark (4 of 8). Every task must be accepted on the way up, even
      // with a peer asking throughout -- that is the floor doing its job.
      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(true.B)
      for (task <- 0 until 4) {
        dut.io.connNetwork_slave.data.availableTask.valid.poke(true.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(task.U)
        dut.io.connNetwork_slave.data.availableTask.ready.expect(
          true.B,
          s"declined task $task below the half-full watermark"
        )
        dut.clock.step()
      }

      // At the watermark with a peer asking and no push happening: decline.
      dut.io.connNetwork_slave.data.availableTask.valid.poke(true.B)
      dut.io.connNetwork_slave.data.availableTask.bits.poke(0xbb.U)
      dut.io.connNetwork_slave.data.availableTask.ready.expect(false.B)

      // Same state, but nobody is asking: take it. Surplus alone is not a reason to decline.
      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(false.B)
      dut.io.connNetwork_slave.data.availableTask.ready.expect(true.B)

      // Peer asking again, but now the PE ring drains so we ARE pushing: take it. A lane that is
      // keeping up is entitled to its own supply.
      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(true.B)
      dut.io.connNetwork_master.data.qOutTask.ready.poke(true.B)
      dut.io.connNetwork_master.data.qOutTask.valid.expect(true.B)
      dut.io.connNetwork_slave.data.availableTask.ready.expect(true.B)

      println("+ let-flow-by fires only on surplus + peer demand + not draining")
    }
  }

  // Conservation across a full drain: everything that goes in comes out exactly once, in order,
  // whatever the ring is doing to us on the way.
  it should "conserve and order tasks across a stuttering PE ring" in {
    test(new SpawnerServer(taskWidth, queueDepth = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      quiesce(dut)
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(true.B)

      val total = 40
      var nextIn = 0
      val delivered = mutable.ArrayBuffer.empty[Int]

      for (c <- 0 until 200) {
        // Stutter the ring slot and the source on different periods.
        dut.io.connNetwork_master.data.qOutTask.ready.poke((c % 3 != 0).B)
        val offering = nextIn < total && (c % 2 == 0)
        dut.io.connNetwork_slave.data.availableTask.valid.poke(offering.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(nextIn.U)

        val accepted = offering &&
          dut.io.connNetwork_slave.data.availableTask.ready.peek().litToBoolean
        if (
          dut.io.connNetwork_master.data.qOutTask.valid.peek().litToBoolean &&
          dut.io.connNetwork_master.data.qOutTask.ready.peek().litToBoolean
        ) delivered += dut.io.connNetwork_master.data.qOutTask.bits.peek().litValue.toInt

        dut.clock.step()
        if (accepted) nextIn += 1
      }

      assert(nextIn == total, s"source stalled: only $nextIn of $total accepted")
      assert(
        delivered.size == total,
        s"lost tasks: $total in, ${delivered.size} out"
      )
      for (i <- 0 until total)
        assert(delivered(i) == i, s"out-of-order at index $i: got ${delivered(i)}")

      println(s"+ $total tasks conserved and in order across a stuttering ring")
    }
  }

  // REGRESSION: the ctrl ring must not silt up with stranded requests.
  //
  // Delivery is deliberately never gated on holding a steal token, so a push can
  // happen on a cycle when no request is resident at our hop. That task still
  // lands in some client's queue and still fills a slot an outstanding request
  // had reserved -- so if we do not cancel a request for it LATER, the client's
  // token stays on the ctrl ring forever with nothing left to satisfy it.
  //
  // Those strandings accumulate. Measured on the QuestaSim countDecoupled repro
  // (size=200 instances=6000): the adder ring ended with 31 tokens resident out
  // of 34 capacity, every hop asserting hasRequestOut, while all eight clients
  // sat at count=minLengthThresh with desiredSteals=0 -- i.e. 31 promises of
  // space against zero free space. A saturated ctrl ring pins ntwReqArriving
  // high at the scheduler server, which parks its contention sampler in the
  // (req && occupancy) dead zone, so networkCongested never asserts and the
  // absorb-to-HBM relief valve -- hard-gated on it -- never fires. A transient
  // PE stall then becomes a permanent deadlock.
  //
  // So a delivered task must cancel exactly one request EVENTUALLY, not only
  // when a token happens to be resident in the same cycle. Owe the ring a
  // cancellation and pay it off from the backlog.
  it should "owe and later cancel a request for a task pushed with no token resident" in {
    test(new SpawnerServer(taskWidth, queueDepth = 8))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      dut.clock.setTimeout(0)
      quiesce(dut)

      // ---- phase 1: push freely while the ctrl ring offers NOTHING ----------
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(false.B)
      dut.io.connNetwork_master.data.qOutTask.ready.poke(true.B)

      var pushesWithoutToken = 0
      var nextIn = 0
      for (_ <- 0 until 24) {
        dut.io.connNetwork_slave.data.availableTask.valid.poke(true.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(nextIn.U)
        val accepted =
          dut.io.connNetwork_slave.data.availableTask.ready.peek().litToBoolean
        if (
          dut.io.connNetwork_master.data.qOutTask.valid.peek().litToBoolean &&
          dut.io.connNetwork_master.data.qOutTask.ready.peek().litToBoolean
        ) pushesWithoutToken += 1
        dut.clock.step()
        if (accepted) nextIn += 1
      }
      assert(pushesWithoutToken > 0, "test drove no pushes; cannot exercise the debt")

      // ---- phase 2: stop pushing, now the ring finally offers requests ------
      // qOutTask.ready low => pushedTask is false for the rest of the test, so
      // anything consumed here can only come from the outstanding debt.
      dut.io.connNetwork_master.data.qOutTask.ready.poke(false.B)
      dut.io.connNetwork_slave.data.availableTask.valid.poke(false.B)
      dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(true.B)

      var cancelled = 0
      for (_ <- 0 until (pushesWithoutToken + 20)) {
        if (dut.io.connNetwork_master.ctrl.serveStealReq.valid.peek().litToBoolean)
          cancelled += 1
        dut.clock.step()
      }

      assert(
        cancelled == pushesWithoutToken,
        s"ctrl ring silts up: pushed $pushesWithoutToken tasks with no token resident but " +
          s"only cancelled $cancelled requests afterwards -- " +
          s"${pushesWithoutToken - cancelled} client tokens stranded forever"
      )

      // And the debt must not overshoot: once paid off, stop eating requests
      // that belong to somebody else's genuine demand.
      var extra = 0
      for (_ <- 0 until 20) {
        if (dut.io.connNetwork_master.ctrl.serveStealReq.valid.peek().litToBoolean)
          extra += 1
        dut.clock.step()
      }
      assert(extra == 0, s"over-consumed $extra requests beyond the debt")

      println(s"+ owed and cancelled exactly $pushesWithoutToken requests, no overshoot")
    }
  }
}
