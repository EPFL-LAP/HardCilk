package Scheduler.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => sAssert, _}
import scala.collection.mutable

import Scheduler.SchedulerClient

class SchedulerClientTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SchedulerClient local queue/network protocol"

  private val taskWidth = 64
  private val queueMaxLength = 16
  private val minLengthThresh = 2
  private val maxLengthThresh = 5
  private val networkLength = 4

  private case class StepResult(
      stealFire: Boolean,
      serveFire: Boolean,
      availableFire: Boolean,
      pushFire: Boolean,
      popFire: Boolean,
      popBits: BigInt,
      qOutFire: Boolean,
      qOutBits: BigInt,
      qOutValid: Boolean,
      currLength: Int)

  private def initInputs(dut: SchedulerClient): Unit = {
    val pe = dut.io.toPE.get
    pe.push.valid.poke(false.B)
    pe.push.bits.poke(0.U)
    pe.pop.ready.poke(false.B)
    dut.io.connNetwork.data.availableTask.valid.poke(false.B)
    dut.io.connNetwork.data.availableTask.bits.poke(0.U)
    dut.io.connNetwork.data.qOutTask.ready.poke(false.B)
    dut.io.connNetwork.ctrl.stealReq.ready.poke(false.B)
    dut.io.connNetwork.ctrl.serveStealReq.ready.poke(false.B)
  }

  private def stepWith(
      dut: SchedulerClient,
      pushValid: Boolean = false,
      pushBits: BigInt = 0,
      popReady: Boolean = false,
      availableValid: Boolean = false,
      availableBits: BigInt = 0,
      qOutReady: Boolean = true,
      stealReady: Boolean = true,
      serveReady: Boolean = false): StepResult = {
    val pe = dut.io.toPE.get
    pe.push.valid.poke(pushValid.B)
    pe.push.bits.poke(pushBits.U)
    pe.pop.ready.poke(popReady.B)
    dut.io.connNetwork.data.availableTask.valid.poke(availableValid.B)
    dut.io.connNetwork.data.availableTask.bits.poke(availableBits.U)
    dut.io.connNetwork.data.qOutTask.ready.poke(qOutReady.B)
    dut.io.connNetwork.ctrl.stealReq.ready.poke(stealReady.B)
    dut.io.connNetwork.ctrl.serveStealReq.ready.poke(serveReady.B)

    val stealValid = dut.io.connNetwork.ctrl.stealReq.valid.peek().litToBoolean
    val serveValid = dut.io.connNetwork.ctrl.serveStealReq.valid.peek().litToBoolean
    val availableReady = dut.io.connNetwork.data.availableTask.ready.peek().litToBoolean
    val pushReady = pe.push.ready.peek().litToBoolean
    val popValid = pe.pop.valid.peek().litToBoolean
    val popBits = if (popValid) pe.pop.bits.peek().litValue else BigInt(0)
    val qOutValid = dut.io.connNetwork.data.qOutTask.valid.peek().litToBoolean
    val qOutBits =
      if (qOutValid) dut.io.connNetwork.data.qOutTask.bits.peek().litValue else BigInt(0)
    val currLength = pe.currLength.peek().litValue.toInt

    dut.clock.step()

    StepResult(
      stealFire = stealReady && stealValid,
      serveFire = serveReady && serveValid,
      availableFire = availableValid && availableReady,
      pushFire = pushValid && pushReady,
      popFire = popReady && popValid,
      popBits = popBits,
      qOutFire = qOutReady && qOutValid,
      qOutBits = qOutBits,
      qOutValid = qOutValid,
      currLength = currLength)
  }

  private def pushTask(dut: SchedulerClient, task: BigInt): Unit = {
    var pushed = false
    var guard = 0
    while (!pushed && guard < 20) {
      pushed = stepWith(dut, pushValid = true, pushBits = task, stealReady = false).pushFire
      guard += 1
    }
    sAssert(pushed, s"could not push task 0x${task.toString(16)}")
  }

  private def pushTasks(dut: SchedulerClient, tasks: Seq[BigInt]): Unit =
    tasks.foreach(pushTask(dut, _))

  private def client(): SchedulerClient =
    new SchedulerClient(
      taskWidth,
      queueMaxLength,
      minLengthThresh,
      maxLengthThresh,
      networkLength,
      vssIgnoresRequests = false)

  it should "fill the outstanding steal-request window to the minimum local threshold" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      var stealFires = 0
      for (_ <- 0 until minLengthThresh + 3) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) stealFires += 1
      }

      sAssert(
        stealFires == minLengthThresh,
        s"expected $minLengthThresh steal requests for an empty local queue, saw $stealFires")
    }
  }

  it should "accept visible network tasks while it wants work even if steal requests have not fired" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      var stealFires = 0
      var availableFires = 0
      for (cycle <- 0 until 12) {
        val r = stepWith(
          dut,
          availableValid = true,
          availableBits = BigInt(0x7100 + cycle),
          stealReady = false)
        if (r.stealFire) stealFires += 1
        if (r.availableFire) availableFires += 1
      }

      sAssert(stealFires == 0, s"stealFires=$stealFires")
      sAssert(availableFires == minLengthThresh, s"availableFires=$availableFires")
      sAssert(
        dut.io.toPE.get.currLength.peek().litValue == minLengthThresh,
        "queue did not fill from visible network tasks")
    }
  }

  it should "keep accepting visible network tasks until the desired steal window is satisfied" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      val initialRequests = minLengthThresh - 1
      var stealFires = 0
      while (stealFires < initialRequests) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) stealFires += 1
      }

      var availableFires = 0
      for (cycle <- 0 until 5) {
        val r = stepWith(
          dut,
          availableValid = true,
          availableBits = BigInt(0x7200 + cycle),
          stealReady = false)
        if (r.availableFire) availableFires += 1
      }

      sAssert(stealFires == initialRequests, s"stealFires=$stealFires")
      sAssert(availableFires == minLengthThresh, s"availableFires=$availableFires")
      sAssert(
        dut.io.toPE.get.currLength.peek().litValue == minLengthThresh,
        "network tasks did not satisfy the desired window")
    }
  }

  it should "give PE pushes priority over returned stolen tasks" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      var stealFired = false
      while (!stealFired) {
        stealFired = stepWith(dut, stealReady = true).stealFire
      }

      val contested = stepWith(
        dut,
        pushValid = true,
        pushBits = BigInt("a001", 16),
        availableValid = true,
        availableBits = BigInt("b001", 16),
        stealReady = false)
      sAssert(contested.pushFire, "PE push did not win enqueue priority")
      sAssert(!contested.availableFire, "returned task was accepted during a PE push")

      val accepted = stepWith(
        dut,
        availableValid = true,
        availableBits = BigInt("b001", 16),
        stealReady = false)
      sAssert(accepted.availableFire, "returned task was not accepted after PE push cleared")
      sAssert(dut.io.toPE.get.currLength.peek().litValue == 2, "queue should contain both tasks")
    }
  }

  it should "serve a pending steal request and output one queued task" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      val tasks = Seq(BigInt("5100", 16), BigInt("5101", 16), BigInt("5102", 16), BigInt("5103", 16))
      pushTasks(dut, tasks)

      var serveFires = 0
      val out = mutable.ArrayBuffer.empty[BigInt]
      var guard = 0
      while (out.isEmpty && guard < 20) {
        val r =
          stepWith(dut, qOutReady = true, stealReady = false, serveReady = serveFires == 0)
        if (r.serveFire) serveFires += 1
        if (r.qOutFire) out += r.qOutBits
        guard += 1
      }

      sAssert(serveFires == 1, s"serveFires=$serveFires")
      sAssert(out == Seq(tasks.head), s"out=$out expected=${tasks.head}")
    }
  }

  it should "serve a steal request and output a task in the same cycle when data is ready" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      val tasks = Seq(BigInt("5200", 16), BigInt("5201", 16), BigInt("5202", 16), BigInt("5203", 16))
      pushTasks(dut, tasks)

      val served =
        stepWith(dut, qOutReady = true, stealReady = false, serveReady = true)

      sAssert(served.serveFire, "serve request was not consumed")
      sAssert(served.qOutFire, "task did not output in the same cycle as the serve request")
      sAssert(served.qOutBits == tasks.head, s"qOutBits=${served.qOutBits}")
    }
  }

  it should "use later consumed steal requests to balance overfull PE pushes" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      pushTasks(dut, (0 until minLengthThresh + 2).map(i => BigInt(0x5300 + i)))

      var served = 0
      for (_ <- 0 until 2) {
        val r = stepWith(dut, qOutReady = true, stealReady = false, serveReady = true)
        if (r.serveFire) served += 1
        sAssert(r.qOutFire, "served request did not output its task")
      }
      sAssert(served == 2, s"served=$served")

      var popped = 0
      for (_ <- 0 until 1) {
        val r = stepWith(dut, popReady = true, stealReady = false)
        if (r.popFire) popped += 1
      }
      sAssert(popped == 1, s"popped=$popped")

      var refillSteals = 0
      for (_ <- 0 until 6) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) refillSteals += 1
      }
      sAssert(refillSteals == 1, s"refillSteals=$refillSteals")
    }
  }

  it should "hold qOutTask stable while the data network is backpressured" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      val tasks = Seq(BigInt("6123", 16), BigInt("6124", 16), BigInt("6125", 16), BigInt("6126", 16))
      pushTasks(dut, tasks)

      var served = false
      var guard = 0
      while (!served && guard < 20) {
        val r = stepWith(dut, qOutReady = false, stealReady = false, serveReady = true)
        served = r.serveFire
        sAssert(!r.qOutFire, "task output fired while qOutTask was backpressured")
        guard += 1
      }
      sAssert(served, "serve request was not consumed under qOutTask backpressure")

      var heldCycles = 0
      for (_ <- 0 until 3) {
        val r = stepWith(dut, qOutReady = false, stealReady = false, serveReady = false)
        if (r.qOutValid) {
          sAssert(r.qOutBits == tasks.head, s"held qOutTask bits 0x${r.qOutBits.toString(16)}")
          heldCycles += 1
        }
      }
      sAssert(heldCycles == 3, s"heldCycles=$heldCycles")

      val released = stepWith(dut, qOutReady = true, stealReady = false, serveReady = false)
      sAssert(released.qOutFire, "held task did not fire when qOutTask became ready")
      sAssert(released.qOutBits == tasks.head, s"released=${released.qOutBits}")
    }
  }

  it should "top up the steal-request window when local task consumption opens new slots" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      pushTasks(dut, (0 until minLengthThresh).map(i => BigInt(0x7300 + i)))

      val popped = mutable.ArrayBuffer.empty[BigInt]
      for (_ <- 0 until 2) {
        val r = stepWith(dut, popReady = true, stealReady = false)
        if (r.popFire) popped += r.popBits
      }
      sAssert(popped.size == 2, s"popped=$popped")

      var stealFires = 0
      for (_ <- 0 until 5) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) stealFires += 1
      }

      sAssert(stealFires == 2, s"expected 2 top-up steal requests, saw $stealFires")
    }
  }

  it should "keep topping up when returned tasks and local consumption share the same window" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      var stealFires = 0
      while (stealFires < minLengthThresh) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) stealFires += 1
      }

      val firstReturned = stepWith(
        dut,
        availableValid = true,
        availableBits = BigInt("7400", 16),
        stealReady = false)
      sAssert(firstReturned.availableFire, "first returned task was not accepted")

      val accepted = stepWith(
        dut,
        availableValid = true,
        availableBits = BigInt("7500", 16),
        popReady = true,
        stealReady = true)

      sAssert(accepted.availableFire, "returned task was not accepted")
      sAssert(accepted.popFire, "local task was not consumed")

      var topUps = 0
      for (_ <- 0 until 4) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) topUps += 1
      }

      sAssert(topUps == 1, s"expected one replacement steal request, saw $topUps")
    }
  }

  // Taking a task off the ring never moves desiredSteals, so an opportunistic intake costs no
  // credit. Once the PE pops it the slot is wanted again, which yields one request for the slot
  // itself PLUS one replacement for the demand that intake consumed from whoever did ask for it.
  it should "issue a replacement steal request for each opportunistically accepted task" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      var acceptedTasks = 0
      for (cycle <- 0 until maxLengthThresh + 3) {
        val r = stepWith(
          dut,
          availableValid = true,
          availableBits = BigInt(0x8300 + cycle),
          stealReady = false)
        if (r.availableFire) acceptedTasks += 1
      }
      sAssert(acceptedTasks == minLengthThresh, s"acceptedTasks=$acceptedTasks")

      var poppedTasks = 0
      for (_ <- 0 until minLengthThresh) {
        val r = stepWith(dut, popReady = true, stealReady = false)
        if (r.popFire) poppedTasks += 1
      }
      sAssert(poppedTasks == acceptedTasks, s"poppedTasks=$poppedTasks accepted=$acceptedTasks")

      var stealReqs = 0
      for (_ <- 0 until minLengthThresh + 3) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) stealReqs += 1
      }

      sAssert(
        stealReqs == minLengthThresh + acceptedTasks,
        s"stealReqs=$stealReqs acceptedTasks=$acceptedTasks")
    }
  }

  it should "keep desired-steal bookkeeping balanced over a long mixed sequence" in {
    test(client()) { dut =>
      dut.clock.setTimeout(0)
      initInputs(dut)

      var stealFires = 0
      while (stealFires < minLengthThresh) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) stealFires += 1
      }
      sAssert(stealFires == minLengthThresh, s"stealFires=$stealFires")

      var returnedFires = 0
      for (cycle <- 0 until 5) {
        val r = stepWith(
          dut,
          availableValid = true,
          availableBits = BigInt(0x8100 + cycle),
          stealReady = false)
        if (r.availableFire) returnedFires += 1
      }
      sAssert(returnedFires == minLengthThresh, s"returnedFires=$returnedFires")
      sAssert(dut.io.toPE.get.currLength.peek().litValue == minLengthThresh)

      val popped = mutable.ArrayBuffer.empty[BigInt]
      for (_ <- 0 until 2) {
        val r = stepWith(dut, popReady = true, stealReady = false)
        if (r.popFire) popped += r.popBits
      }
      sAssert(popped == Seq(BigInt("8100", 16), BigInt("8101", 16)), s"popped=$popped")

      var opportunisticFires = 0
      for (cycle <- 0 until 5) {
        val r = stepWith(
          dut,
          availableValid = true,
          availableBits = BigInt(0x8200 + cycle),
          stealReady = false)
        if (r.availableFire) opportunisticFires += 1
      }
      sAssert(opportunisticFires == minLengthThresh, s"opportunisticFires=$opportunisticFires")
      sAssert(dut.io.toPE.get.currLength.peek().litValue == minLengthThresh)

      var blockedServeFires = 0
      for (_ <- 0 until 5) {
        val r = stepWith(dut, qOutReady = false, stealReady = false, serveReady = true)
        if (r.serveFire) blockedServeFires += 1
        sAssert(!r.qOutFire, "qOutTask fired while the data network was backpressured")
      }
      sAssert(blockedServeFires == 0, s"blockedServeFires=$blockedServeFires")

      val out = mutable.ArrayBuffer.empty[BigInt]
      for (_ <- 0 until 5) {
        val r = stepWith(dut, qOutReady = true, stealReady = false, serveReady = false)
        if (r.qOutFire) out += r.qOutBits
      }
      sAssert(out.isEmpty, s"out=$out")

      val finalPops = mutable.ArrayBuffer.empty[BigInt]
      for (_ <- 0 until minLengthThresh) {
        val r = stepWith(dut, popReady = true, stealReady = false)
        if (r.popFire) finalPops += r.popBits
      }
      sAssert(
        finalPops == Seq(BigInt("8200", 16), BigInt("8201", 16)),
        s"finalPops=$finalPops")

      var refillSteals = 0
      for (_ <- 0 until 8) {
        val r = stepWith(dut, stealReady = true)
        if (r.stealFire) refillSteals += 1
      }
      // minLengthThresh for the window itself, plus one replacement per opportunistically accepted
      // task -- those arrived without spending a request, so popping them re-opens the want.
      sAssert(refillSteals == 2 * minLengthThresh, s"refillSteals=$refillSteals")
    }
  }
}
