package Scheduler.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.BufferServerInput

import scala.Predef.{assert => sAssert, _}
import scala.collection.mutable
import scala.util.Random

class BufferServerInputTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "BufferServerInput"

  private val taskWidth = 64

  private def init(dut: BufferServerInput): Unit = {
    dut.io.connNetwork_slave.data.availableTask.valid.poke(false.B)
    dut.io.connNetwork_slave.data.availableTask.bits.poke(0.U)
    dut.io.connNetwork_slave.data.qOutTask.ready.poke(false.B)
    dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(false.B)
    dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(false.B)

    dut.io.connTaskSource.data.availableTask.ready.poke(false.B)
    dut.io.connTaskSource.data.qOutTask.valid.poke(false.B)
    dut.io.connTaskSource.data.qOutTask.bits.poke(0.U)
    dut.io.connTaskSource.ctrl.stealReq.valid.poke(false.B)
    dut.io.connTaskSource.ctrl.serveStealReq.valid.poke(false.B)

    dut.io.connSpawnerServer.data.availableTask.ready.poke(false.B)
    dut.io.connSpawnerServer.data.qOutTask.valid.poke(false.B)
    dut.io.connSpawnerServer.data.qOutTask.bits.poke(0.U)
    dut.io.connSpawnerServer.ctrl.stealReq.valid.poke(false.B)
    dut.io.connSpawnerServer.ctrl.serveStealReq.valid.poke(false.B)
  }

  it should "hold a task under backpressure and deliver it locally exactly once" in {
    test(new BufferServerInput(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      init(dut)

      val task = BigInt("a100", 16)
      dut.io.connTaskSource.data.qOutTask.valid.poke(true.B)
      dut.io.connTaskSource.data.qOutTask.bits.poke(task.U)
      dut.clock.step()
      dut.io.connTaskSource.data.qOutTask.valid.poke(false.B)

      for (_ <- 0 until 5) {
        dut.io.connSpawnerServer.data.availableTask.valid.expect(true.B)
        dut.io.connSpawnerServer.data.availableTask.bits.expect(task.U)
        // The held task is presented locally to the spawner only -- it is never offered onto the
        // ring. (The direct buffer-forward path is gone; the spawner swallow-and-shares it once it
        // has room.)
        dut.io.connNetwork_slave.data.qOutTask.valid.expect(false.B)
        dut.clock.step()
      }

      dut.io.connSpawnerServer.data.availableTask.ready.poke(true.B)
      dut.io.connSpawnerServer.data.availableTask.valid.expect(true.B)
      dut.io.connSpawnerServer.data.availableTask.bits.expect(task.U)
      dut.clock.step()

      dut.io.connSpawnerServer.data.availableTask.valid.expect(false.B)
      dut.io.connNetwork_slave.data.qOutTask.valid.expect(false.B)
    }
  }

  it should "preserve tasks when the spawner is forwarding while a buffered task waits" in {
    test(new BufferServerInput(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      init(dut)

      val buffered = BigInt("b100", 16)
      val spawnerTask = BigInt("b200", 16)
      dut.io.connTaskSource.data.qOutTask.valid.poke(true.B)
      dut.io.connTaskSource.data.qOutTask.bits.poke(buffered.U)
      dut.clock.step()
      dut.io.connTaskSource.data.qOutTask.valid.poke(false.B)

      dut.io.connSpawnerServer.data.qOutTask.valid.poke(true.B)
      dut.io.connSpawnerServer.data.qOutTask.bits.poke(spawnerTask.U)
      dut.io.connNetwork_slave.data.qOutTask.ready.poke(true.B)

      dut.io.connNetwork_slave.data.qOutTask.valid.expect(true.B)
      dut.io.connNetwork_slave.data.qOutTask.bits.expect(spawnerTask.U)
      dut.io.connSpawnerServer.data.qOutTask.ready.expect(true.B)
      dut.clock.step()
      dut.io.connSpawnerServer.data.qOutTask.valid.poke(false.B)

      dut.io.connSpawnerServer.data.availableTask.ready.poke(true.B)
      dut.io.connSpawnerServer.data.availableTask.valid.expect(true.B)
      dut.io.connSpawnerServer.data.availableTask.bits.expect(buffered.U)
      dut.clock.step()
      dut.io.connSpawnerServer.data.availableTask.valid.expect(false.B)
    }
  }

  it should "consume a new local steal request before injecting it into the ring" in {
    test(new BufferServerInput(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      init(dut)

      dut.io.connTaskSource.ctrl.serveStealReq.valid.poke(true.B)
      dut.io.connSpawnerServer.ctrl.stealReq.valid.poke(true.B)

      dut.io.connTaskSource.ctrl.serveStealReq.ready.expect(true.B)
      dut.io.connSpawnerServer.ctrl.stealReq.ready.expect(true.B)
      dut.io.connNetwork_slave.ctrl.stealReq.valid.expect(false.B)
      dut.clock.step()
    }
  }

  it should "offer an incoming ring request to the spawner, not to the task source" in {
    test(new BufferServerInput(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      init(dut)

      // A steal token is passing on the ring and the co-located source has a task available.
      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(true.B)
      dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(true.B)
      dut.io.connTaskSource.ctrl.serveStealReq.valid.poke(true.B)
      dut.io.connSpawnerServer.ctrl.stealReq.valid.poke(true.B)

      // Only the spawner may answer it: it is the only participant here that can inject a task into
      // the ring, so it is the only one whose serve actually reaches the requester.
      dut.io.connSpawnerServer.ctrl.serveStealReq.ready.expect(true.B)

      // The source feeds the co-located spawner, so the spawner's own request is satisfied locally
      // and must not be duplicated onto the ring.
      dut.io.connTaskSource.ctrl.serveStealReq.ready.expect(true.B)
      dut.io.connSpawnerServer.ctrl.stealReq.ready.expect(true.B)
      dut.io.connNetwork_slave.ctrl.stealReq.valid.expect(false.B)
      dut.clock.step()
    }
  }

  it should "inject the local steal request into the ring when the source cannot serve it" in {
    test(new BufferServerInput(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      init(dut)

      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(true.B)
      dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(true.B)
      dut.io.connTaskSource.ctrl.serveStealReq.valid.poke(false.B)
      dut.io.connSpawnerServer.ctrl.stealReq.valid.poke(true.B)

      dut.io.connNetwork_slave.ctrl.stealReq.valid.expect(true.B)
      dut.io.connSpawnerServer.ctrl.stealReq.ready.expect(true.B)
      dut.clock.step()
    }
  }

  // Regression: a ring steal request used to be consumed whenever the task source merely *could*
  // serve, but that source's task is routed to the co-located spawner and never onto the ring --
  // so the requester's demand was destroyed and it received nothing.
  it should "leave a ring steal request untouched unless a task is injected for it" in {
    test(new BufferServerInput(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      init(dut)

      dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(true.B)
      dut.io.connTaskSource.ctrl.serveStealReq.valid.poke(true.B)
      dut.io.connSpawnerServer.ctrl.serveStealReq.valid.poke(false.B)

      dut.io.connNetwork_slave.ctrl.serveStealReq.valid.expect(false.B)
      dut.clock.step()

      // Once the spawner does inject, the request is consumed in the same cycle.
      dut.io.connSpawnerServer.ctrl.serveStealReq.valid.poke(true.B)
      dut.io.connNetwork_slave.ctrl.serveStealReq.valid.expect(true.B)
    }
  }

  it should "conserve randomized task traffic under backpressure" in {
    test(new BufferServerInput(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      init(dut)

      val random = new Random(0x5eedL)
      val outstanding = mutable.Map.empty[BigInt, Int].withDefaultValue(0)
      var sourceTask = Option.empty[BigInt]
      var ringTask = Option.empty[BigInt]
      var spawnerTask = Option.empty[BigInt]
      var nextSource = BigInt("100000", 16)
      var nextRing = BigInt("200000", 16)
      var nextSpawner = BigInt("300000", 16)

      def add(task: BigInt): Unit = outstanding(task) += 1
      def remove(task: BigInt): Unit = {
        sAssert(outstanding(task) > 0, s"duplicated or unknown output task 0x${task.toString(16)}")
        if (outstanding(task) == 1) outstanding.remove(task)
        else outstanding(task) -= 1
      }

      def cycle(generate: Boolean): Unit = {
        if (generate && sourceTask.isEmpty && random.nextBoolean()) {
          sourceTask = Some(nextSource); nextSource += 1
        }
        if (generate && ringTask.isEmpty && random.nextInt(3) == 0) {
          ringTask = Some(nextRing); nextRing += 1
        }
        if (generate && spawnerTask.isEmpty && random.nextInt(3) == 0) {
          spawnerTask = Some(nextSpawner); nextSpawner += 1
        }

        dut.io.connTaskSource.data.qOutTask.valid.poke(sourceTask.nonEmpty.B)
        dut.io.connTaskSource.data.qOutTask.bits.poke(sourceTask.getOrElse(BigInt(0)).U)
        dut.io.connNetwork_slave.data.availableTask.valid.poke(ringTask.nonEmpty.B)
        dut.io.connNetwork_slave.data.availableTask.bits.poke(ringTask.getOrElse(BigInt(0)).U)
        dut.io.connSpawnerServer.data.qOutTask.valid.poke(spawnerTask.nonEmpty.B)
        dut.io.connSpawnerServer.data.qOutTask.bits.poke(spawnerTask.getOrElse(BigInt(0)).U)
        dut.io.connSpawnerServer.data.availableTask.ready.poke((!generate || random.nextBoolean()).B)
        dut.io.connNetwork_slave.data.qOutTask.ready.poke((!generate || random.nextBoolean()).B)

        val sourceFire = sourceTask.nonEmpty &&
          dut.io.connTaskSource.data.qOutTask.ready.peek().litToBoolean
        val ringFire = ringTask.nonEmpty &&
          dut.io.connNetwork_slave.data.availableTask.ready.peek().litToBoolean
        val spawnerFire = spawnerTask.nonEmpty &&
          dut.io.connSpawnerServer.data.qOutTask.ready.peek().litToBoolean
        val localFire = dut.io.connSpawnerServer.data.availableTask.valid.peek().litToBoolean &&
          dut.io.connSpawnerServer.data.availableTask.ready.peek().litToBoolean
        val networkFire = dut.io.connNetwork_slave.data.qOutTask.valid.peek().litToBoolean &&
          dut.io.connNetwork_slave.data.qOutTask.ready.peek().litToBoolean
        val localBits = dut.io.connSpawnerServer.data.availableTask.bits.peek().litValue
        val networkBits = dut.io.connNetwork_slave.data.qOutTask.bits.peek().litValue

        if (sourceFire) add(sourceTask.get)
        if (ringFire) add(ringTask.get)
        if (spawnerFire) add(spawnerTask.get)
        if (localFire) remove(localBits)
        if (networkFire) remove(networkBits)

        dut.clock.step()
        if (sourceFire) sourceTask = None
        if (ringFire) ringTask = None
        if (spawnerFire) spawnerTask = None
      }

      for (_ <- 0 until 500) cycle(generate = true)

      var guard = 0
      while (
        (sourceTask.nonEmpty || ringTask.nonEmpty || spawnerTask.nonEmpty || outstanding.nonEmpty) &&
        guard < 100
      ) {
        cycle(generate = false)
        guard += 1
      }

      sAssert(sourceTask.isEmpty, "task source did not drain")
      sAssert(ringTask.isEmpty, "incoming ring task did not drain")
      sAssert(spawnerTask.isEmpty, "spawner task did not drain")
      sAssert(outstanding.isEmpty, s"tasks were dropped: $outstanding")
    }
  }

  it should "conserve randomized steal requests under backpressure" in {
    test(new BufferServerInput(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      init(dut)

      val random = new Random(0xc0ffeeL)
      var incomingRequest = false
      var localRequest = false
      var taskSourceCanServe = false
      var spawnerCanServe = false
      var acceptedRequests = 0
      var consumedOrForwardedRequests = 0

      for (_ <- 0 until 1000) {
        if (!incomingRequest && random.nextInt(3) == 0) incomingRequest = true
        if (!localRequest && random.nextInt(3) == 0) localRequest = true
        if (!taskSourceCanServe && random.nextInt(3) == 0) taskSourceCanServe = true
        if (!spawnerCanServe && random.nextInt(3) == 0) spawnerCanServe = true

        val networkAcceptsRequest = random.nextBoolean()
        dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(incomingRequest.B)
        dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(networkAcceptsRequest.B)
        dut.io.connSpawnerServer.ctrl.stealReq.valid.poke(localRequest.B)
        dut.io.connTaskSource.ctrl.serveStealReq.valid.poke(taskSourceCanServe.B)
        dut.io.connSpawnerServer.ctrl.serveStealReq.valid.poke(spawnerCanServe.B)

        val incomingFire = incomingRequest &&
          dut.io.connNetwork_slave.ctrl.serveStealReq.valid.peek().litToBoolean
        val localFire = localRequest &&
          dut.io.connSpawnerServer.ctrl.stealReq.ready.peek().litToBoolean
        val sourceServeFire = taskSourceCanServe &&
          dut.io.connTaskSource.ctrl.serveStealReq.ready.peek().litToBoolean
        val spawnerServeFire = spawnerCanServe &&
          dut.io.connSpawnerServer.ctrl.serveStealReq.ready.peek().litToBoolean
        val forwardedFire = networkAcceptsRequest &&
          dut.io.connNetwork_slave.ctrl.stealReq.valid.peek().litToBoolean

        acceptedRequests += Seq(incomingFire, localFire).count(identity)
        consumedOrForwardedRequests +=
          Seq(sourceServeFire, spawnerServeFire, forwardedFire).count(identity)
        sAssert(
          acceptedRequests == consumedOrForwardedRequests,
          s"steal-request mismatch: accepted=$acceptedRequests handled=$consumedOrForwardedRequests"
        )

        dut.clock.step()
        if (incomingFire) incomingRequest = false
        if (localFire) localRequest = false
        if (sourceServeFire) taskSourceCanServe = false
        if (spawnerServeFire) spawnerCanServe = false
      }
    }
  }
}
