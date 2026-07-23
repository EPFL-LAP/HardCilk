package Scheduler.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => sAssert, _}

import Scheduler.GlobalTaskBuffer
import Scheduler.SpawnerServer
import Scheduler.WriteTaskToNetwork

class SchedulerRingInvariantTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Scheduler ring task/request invariant"

  private val taskWidth = 64

  private case class SpawnerStep(
      slaveAvailableFire: Boolean,
      slaveStealFire: Boolean,
      slaveServeFire: Boolean,
      slaveQOutFire: Boolean,
      masterServeFire: Boolean,
      masterQOutFire: Boolean,
      masterQOutBits: BigInt)

  private def initGlobalTaskBuffer(dut: GlobalTaskBuffer): Unit = {
    dut.io.in.valid.poke(false.B)
    dut.io.in.bits.poke(0.U)
    dut.io.connStealNtw.data.availableTask.valid.poke(false.B)
    dut.io.connStealNtw.data.availableTask.bits.poke(0.U)
    dut.io.connStealNtw.data.qOutTask.ready.poke(false.B)
    dut.io.connStealNtw.ctrl.stealReq.ready.poke(false.B)
    dut.io.connStealNtw.ctrl.serveStealReq.ready.poke(false.B)
  }

  private def stepGlobalTaskBuffer(
      dut: GlobalTaskBuffer,
      inValid: Boolean = false,
      inBits: BigInt = 0,
      qOutReady: Boolean = true,
      serveReady: Boolean = false): (Boolean, Boolean, BigInt, Boolean) = {
    dut.io.in.valid.poke(inValid.B)
    dut.io.in.bits.poke(inBits.U)
    dut.io.connStealNtw.data.qOutTask.ready.poke(qOutReady.B)
    dut.io.connStealNtw.ctrl.serveStealReq.ready.poke(serveReady.B)

    val inFire = inValid && dut.io.in.ready.peek().litToBoolean
    val qOutValid = dut.io.connStealNtw.data.qOutTask.valid.peek().litToBoolean
    val qOutFire = qOutReady && qOutValid
    val qOutBits =
      if (qOutValid) dut.io.connStealNtw.data.qOutTask.bits.peek().litValue else BigInt(0)
    val serveFire =
      serveReady && dut.io.connStealNtw.ctrl.serveStealReq.valid.peek().litToBoolean
    dut.clock.step()
    (inFire, qOutFire, qOutBits, serveFire)
  }

  private def initWriteTaskToNetwork(dut: WriteTaskToNetwork): Unit = {
    dut.io.connNetwork.data.availableTask.valid.poke(false.B)
    dut.io.connNetwork.data.availableTask.bits.poke(0.U)
    dut.io.connNetwork.data.qOutTask.ready.poke(false.B)
    dut.io.connNetwork.ctrl.stealReq.ready.poke(false.B)
    dut.io.connNetwork.ctrl.serveStealReq.ready.poke(false.B)
    dut.io.s_axis_task.valid.poke(false.B)
    dut.io.s_axis_task.bits.poke(0.U)
    dut.io.fpgaId.poke(0.U)
    dut.io.startToken.valid.poke(false.B)
    dut.io.startToken.bits.poke(0.U)
    dut.io.numTasksToStealOrServe.poke(0.U)
  }

  private def stepWriteTaskToNetwork(
      dut: WriteTaskToNetwork,
      taskValid: Boolean = false,
      taskBits: BigInt = 0,
      qOutReady: Boolean = true,
      serveReady: Boolean = false,
      startValid: Boolean = false,
      taskCount: Int = 0): (Boolean, Boolean, Boolean) = {
    dut.io.s_axis_task.valid.poke(taskValid.B)
    dut.io.s_axis_task.bits.poke(taskBits.U)
    dut.io.connNetwork.data.qOutTask.ready.poke(qOutReady.B)
    dut.io.connNetwork.ctrl.serveStealReq.ready.poke(serveReady.B)
    dut.io.startToken.valid.poke(startValid.B)
    dut.io.startToken.bits.poke(0.U)
    dut.io.numTasksToStealOrServe.poke(taskCount.U)

    val taskInFire = taskValid && dut.io.s_axis_task.ready.peek().litToBoolean
    val qOutFire =
      qOutReady && dut.io.connNetwork.data.qOutTask.valid.peek().litToBoolean
    val serveFire =
      serveReady && dut.io.connNetwork.ctrl.serveStealReq.valid.peek().litToBoolean
    dut.clock.step()
    (taskInFire, qOutFire, serveFire)
  }

  private def initSpawner(dut: SpawnerServer): Unit = {
    dut.io.connNetwork_slave.data.availableTask.valid.poke(false.B)
    dut.io.connNetwork_slave.data.availableTask.bits.poke(0.U)
    dut.io.connNetwork_slave.data.qOutTask.ready.poke(true.B)
    dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(true.B)
    dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(false.B)

    dut.io.connNetwork_master.data.availableTask.valid.poke(false.B)
    dut.io.connNetwork_master.data.availableTask.bits.poke(0.U)
    dut.io.connNetwork_master.data.qOutTask.ready.poke(false.B)
    dut.io.connNetwork_master.ctrl.stealReq.ready.poke(false.B)
    dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(false.B)
  }

  private def stepSpawnerSlave(
      dut: SpawnerServer,
      availableValid: Boolean = false,
      availableBits: BigInt = 0,
      stealReady: Boolean = true,
      serveReady: Boolean = false,
      qOutReady: Boolean = true): (Boolean, Boolean, Boolean, Boolean) = {
    val r = stepSpawner(
      dut,
      slaveAvailableValid = availableValid,
      slaveAvailableBits = availableBits,
      slaveStealReady = stealReady,
      slaveServeReady = serveReady,
      slaveQOutReady = qOutReady)
    (r.slaveAvailableFire, r.slaveStealFire, r.slaveServeFire, r.slaveQOutFire)
  }

  private def stepSpawner(
      dut: SpawnerServer,
      slaveAvailableValid: Boolean = false,
      slaveAvailableBits: BigInt = 0,
      slaveStealReady: Boolean = true,
      slaveServeReady: Boolean = false,
      slaveQOutReady: Boolean = true,
      masterServeReady: Boolean = false,
      masterQOutReady: Boolean = false): SpawnerStep = {
    dut.io.connNetwork_slave.data.availableTask.valid.poke(slaveAvailableValid.B)
    dut.io.connNetwork_slave.data.availableTask.bits.poke(slaveAvailableBits.U)
    dut.io.connNetwork_slave.ctrl.stealReq.ready.poke(slaveStealReady.B)
    dut.io.connNetwork_slave.ctrl.serveStealReq.ready.poke(slaveServeReady.B)
    dut.io.connNetwork_slave.data.qOutTask.ready.poke(slaveQOutReady.B)
    dut.io.connNetwork_master.ctrl.serveStealReq.ready.poke(masterServeReady.B)
    dut.io.connNetwork_master.data.qOutTask.ready.poke(masterQOutReady.B)

    val availableFire =
      slaveAvailableValid && dut.io.connNetwork_slave.data.availableTask.ready.peek().litToBoolean
    val stealFire =
      slaveStealReady && dut.io.connNetwork_slave.ctrl.stealReq.valid.peek().litToBoolean
    val serveFire =
      slaveServeReady && dut.io.connNetwork_slave.ctrl.serveStealReq.valid.peek().litToBoolean
    val qOutFire =
      slaveQOutReady && dut.io.connNetwork_slave.data.qOutTask.valid.peek().litToBoolean
    val masterServeFire =
      masterServeReady && dut.io.connNetwork_master.ctrl.serveStealReq.valid.peek().litToBoolean
    val masterQOutValid = dut.io.connNetwork_master.data.qOutTask.valid.peek().litToBoolean
    val masterQOutFire = masterQOutReady && masterQOutValid
    val masterQOutBits =
      if (masterQOutValid) dut.io.connNetwork_master.data.qOutTask.bits.peek().litValue
      else BigInt(0)
    dut.clock.step()
    SpawnerStep(
      availableFire,
      stealFire,
      serveFire,
      qOutFire,
      masterServeFire,
      masterQOutFire,
      masterQOutBits)
  }

  it should "backpressure and forward GlobalTaskBuffer input tasks in order" in {
    test(new GlobalTaskBuffer(taskWidth, peCount = 4)) { dut =>
      dut.clock.setTimeout(0)
      initGlobalTaskBuffer(dut)

      val first = BigInt("9100", 16)
      val second = BigInt("9101", 16)
      val third = BigInt("9102", 16)

      val (firstIn, firstOut, _, _) =
        stepGlobalTaskBuffer(dut, inValid = true, inBits = first, qOutReady = false)
      sAssert(firstIn, "first input task was not buffered")
      sAssert(!firstOut, "task output fired while qOutTask was backpressured")

      val (secondIn, secondOut, _, _) =
        stepGlobalTaskBuffer(dut, inValid = true, inBits = second, qOutReady = false)
      sAssert(secondIn, "second input task was not buffered")
      sAssert(!secondOut, "task output fired while qOutTask was backpressured")

      val (thirdBlocked, thirdOutBlocked, _, _) =
        stepGlobalTaskBuffer(dut, inValid = true, inBits = third, qOutReady = false)
      sAssert(!thirdBlocked, "third input task was accepted into a full two-entry buffer")
      sAssert(!thirdOutBlocked, "task output fired while qOutTask was backpressured")

      val (thirdAccepted, firstQOut, firstBits, _) =
        stepGlobalTaskBuffer(dut, inValid = true, inBits = third, qOutReady = true)
      sAssert(thirdAccepted, "pipelined queue did not accept while dequeuing from full")
      sAssert(firstQOut, "first buffered task did not output")
      sAssert(firstBits == first, s"firstBits=0x${firstBits.toString(16)}")

      val (_, secondQOut, secondBits, _) =
        stepGlobalTaskBuffer(dut, qOutReady = true)
      sAssert(secondQOut, "second task did not output")
      sAssert(secondBits == second, s"secondBits=0x${secondBits.toString(16)}")

      val (_, thirdQOut, thirdBits, _) =
        stepGlobalTaskBuffer(dut, qOutReady = true)
      sAssert(thirdQOut, "third task did not output")
      sAssert(thirdBits == third, s"thirdBits=0x${thirdBits.toString(16)}")
    }
  }

  it should "consume a steal request backed by a buffered task before data-ring insertion" in {
    test(new GlobalTaskBuffer(taskWidth, peCount = 2)) { dut =>
      dut.clock.setTimeout(0)
      initGlobalTaskBuffer(dut)

      val task = BigInt("9200", 16)
      val (accepted, pushedImmediately, _, servedImmediately) =
        stepGlobalTaskBuffer(
          dut,
          inValid = true,
          inBits = task,
          qOutReady = false,
          serveReady = true)
      sAssert(accepted, "task was not accepted into the empty buffer")
      sAssert(!pushedImmediately, "task entered the blocked data ring")
      sAssert(!servedImmediately, "same-cycle request consumption was not requested")

      val (_, pushedWhileBlocked, _, servedEarly) =
        stepGlobalTaskBuffer(dut, qOutReady = false, serveReady = true)
      sAssert(!pushedWhileBlocked, "task entered the blocked data ring")
      sAssert(servedEarly, "buffered task did not consume the parked steal request")

      val (_, stillBlocked, _, servedTwice) =
        stepGlobalTaskBuffer(dut, qOutReady = false, serveReady = true)
      sAssert(!stillBlocked, "task entered the blocked data ring")
      sAssert(!servedTwice, "one buffered task consumed more than one steal request")

      val (_, pushed, pushedBits, servedOnPush) =
        stepGlobalTaskBuffer(dut, qOutReady = true, serveReady = true)
      sAssert(pushed, "reserved task did not enter the available data ring")
      sAssert(pushedBits == task, s"pushedBits=0x${pushedBits.toString(16)}")
      sAssert(!servedOnPush, "reserved task consumed a second steal request when pushed")
    }
  }

  it should "forward SpawnerServer slave tasks through the master network port" in {
    test(new SpawnerServer(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      initSpawner(dut)

      val tasks = (0 until 4).map(i => BigInt(0xb100 + i))
      var accepted = 0
      var guard = 0
      while (accepted < tasks.size && guard < 40) {
        val r = stepSpawner(
          dut,
          slaveAvailableValid = true,
          slaveAvailableBits = tasks(accepted))
        if (r.slaveAvailableFire) accepted += 1
        guard += 1
      }
      sAssert(accepted == tasks.size, s"accepted=$accepted expected=${tasks.size}")

      val out = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var masterServes = 0
      guard = 0
      while (out.size < tasks.size && guard < 120) {
        val r =
          stepSpawner(dut, masterServeReady = true, masterQOutReady = true)
        if (r.masterServeFire) masterServes += 1
        if (r.masterQOutFire) out += r.masterQOutBits
        guard += 1
      }

      sAssert(masterServes >= tasks.size, s"masterServes=$masterServes expected at least ${tasks.size}")
      sAssert(out.toSeq == tasks, s"out=$out expected=$tasks")
    }
  }

  it should "require GlobalTaskBuffer to consume one steal request per output task" in {
    test(new GlobalTaskBuffer(taskWidth, peCount = 2)) { dut =>
      dut.clock.setTimeout(0)
      initGlobalTaskBuffer(dut)

      val tasks = (0 until 4).map(i => BigInt(0x9000 + i))
      var inIdx = 0
      var qOutFires = 0
      var guard = 0
      while ((inIdx < tasks.size || qOutFires < tasks.size) && guard < 80) {
        val (inFire, qOutFire, _, _) =
          stepGlobalTaskBuffer(
            dut,
            inValid = inIdx < tasks.size,
            inBits = if (inIdx < tasks.size) tasks(inIdx) else 0,
            qOutReady = true,
            serveReady = false)
        if (inFire) inIdx += 1
        if (qOutFire) qOutFires += 1
        guard += 1
      }
      sAssert(qOutFires == tasks.size, s"qOutFires=$qOutFires expected=${tasks.size}")

      var serveFires = 0
      for (_ <- 0 until 16) {
        val (_, _, _, serveFire) =
          stepGlobalTaskBuffer(dut, qOutReady = false, serveReady = true)
        if (serveFire) serveFires += 1
      }

      sAssert(serveFires == qOutFires, s"serveFires=$serveFires qOutFires=$qOutFires")
    }
  }

  it should "allow WriteTaskToNetwork to output only tasks backed by consumed steal requests" in {
    test(new WriteTaskToNetwork(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      initWriteTaskToNetwork(dut)

      val taskCount = 3
      var started = false
      var guard = 0
      while (!started && guard < 8) {
        val startReady = dut.io.startToken.ready.peek().litToBoolean
        stepWriteTaskToNetwork(dut, startValid = true, taskCount = taskCount)
        started = startReady
        guard += 1
      }
      sAssert(started, "start token was not accepted")

      for (cycle <- 0 until 4) {
        val (_, qOutFire, _) =
          stepWriteTaskToNetwork(
            dut,
            taskValid = true,
            taskBits = BigInt(0xa000 + cycle),
            qOutReady = true,
            serveReady = false)
        sAssert(!qOutFire, "qOutTask fired before any steal request was consumed")
      }

      var serveFires = 0
      while (serveFires < taskCount && guard < 40) {
        val (_, _, serveFire) =
          stepWriteTaskToNetwork(dut, qOutReady = false, serveReady = true)
        if (serveFire) serveFires += 1
        guard += 1
      }
      sAssert(serveFires == taskCount, s"serveFires=$serveFires")

      val tasks = (0 until taskCount).map(i => BigInt(0xa100 + i))
      var taskIdx = 0
      var qOutFires = 0
      guard = 0
      while (qOutFires < taskCount && guard < 40) {
        val (taskInFire, qOutFire, _) =
          stepWriteTaskToNetwork(
            dut,
            taskValid = taskIdx < tasks.size,
            taskBits = if (taskIdx < tasks.size) tasks(taskIdx) else 0,
            qOutReady = true,
            serveReady = false)
        if (taskInFire) taskIdx += 1
        if (qOutFire) qOutFires += 1
        guard += 1
      }

      sAssert(qOutFires == serveFires, s"qOutFires=$qOutFires serveFires=$serveFires")
    }
  }

  it should "require SpawnerServer slave task intake to issue matching steal requests" in {
    test(new SpawnerServer(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      initSpawner(dut)

      var availableFires = 0
      var stealFires = 0
      for (cycle <- 0 until 8) {
        val (availableFire, stealFire, _, _) =
          stepSpawnerSlave(
            dut,
            availableValid = true,
            availableBits = BigInt(0xb000 + cycle),
            stealReady = true)
        if (availableFire) availableFires += 1
        if (stealFire) stealFires += 1
      }

      sAssert(availableFires > 0, "test did not exercise SpawnerServer slave availableTask intake")
      sAssert(stealFires == availableFires, s"stealFires=$stealFires availableFires=$availableFires")
    }
  }

  it should "require SpawnerServer slave request consumption to output matching tasks" in {
    test(new SpawnerServer(taskWidth)) { dut =>
      dut.clock.setTimeout(0)
      initSpawner(dut)

      var serveFires = 0
      var qOutFires = 0
      for (_ <- 0 until 8) {
        val (_, _, serveFire, qOutFire) =
          stepSpawnerSlave(dut, serveReady = true, qOutReady = true)
        if (serveFire) serveFires += 1
        if (qOutFire) qOutFires += 1
      }

      sAssert(serveFires == qOutFires, s"serveFires=$serveFires qOutFires=$qOutFires")
    }
  }
}
