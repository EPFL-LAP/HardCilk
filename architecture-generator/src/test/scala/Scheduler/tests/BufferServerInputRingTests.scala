package Scheduler.tests

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.{BufferServerInput, GlobalTaskBuffer, SchedulerNetwork, SpawnerServer}

import scala.Predef.{assert => sAssert, _}

/** A closed outside-spawn ring with one BufferServerInput and one task source
  * per simplified PE. PE 0 is held off while its source injects the initial
  * work; every executed task returns to its colocated source after a fixed,
  * fully-pipelined delay.
  */
class BufferServerInputFeedbackRing(
    n: Int,
    taskWidth: Int,
    initialTasks: Int,
    returnDelay: Int,
    requestDepth: Int = 16
) extends Module {
  require(n > 1)
  require(initialTasks > 0)
  require(returnDelay > 0)

  val io = IO(new Bundle {
    val executed = Output(Vec(n, Bool()))
    val executedMask = Output(UInt(n.W))
    val pe0Released = Output(Bool())
    val acceptedSeedCount = Output(UInt(log2Ceil(initialTasks + 1).W))
    val workCounts = Output(Vec(n, UInt(32.W)))
  })

  private val network = Module(new SchedulerNetwork(taskWidth, n, Array.empty[Int]))
  private val inputs = Seq.fill(n)(Module(new BufferServerInput(taskWidth)))
  private val taskSources =
    Seq.fill(n)(Module(new GlobalTaskBuffer(taskWidth, peCount = n)))
  private val spawners =
    Seq.fill(n)(Module(new SpawnerServer(taskWidth)))

  for (i <- 0 until n) {
    inputs(i).io.connNetwork_slave <> network.io.connSS(i)
    inputs(i).io.connTaskSource <> taskSources(i).io.connStealNtw
    inputs(i).io.connSpawnerServer <> spawners(i).io.connNetwork_slave
  }

  private val seedCountWidth = log2Ceil(initialTasks + 1)
  private val seedCount = RegInit(0.U(seedCountWidth.W))
  private val seedValid = seedCount < initialTasks.U
  io.acceptedSeedCount := seedCount
  io.executedMask := io.executed.asUInt

  // Keep PE 0 backpressured until the root stream has seeded every other PE.
  // Once a PE's delayed feedback starts, its local source takes priority and
  // the still-circulating root work advances to the next unseeded PE.
  private val seenWork = RegInit(0.U(n.W))
  seenWork := seenWork | io.executed.asUInt
  io.pe0Released := seenWork(n - 1, 1).andR

  for (i <- 0 until n) {
    val localPeRing = spawners(i).io.connNetwork_master
    val returnStages = Seq.fill(returnDelay)(
      Module(new Queue(UInt(taskWidth.W), 2))
    )

    for (stage <- 1 until returnDelay) {
      returnStages(stage).io.enq <> returnStages(stage - 1).io.deq
    }

    // PE 0 rejects its colocated source until every other PE has started. This
    // forces the initial work into the ring, where the other PEs can take it.
    val peEnabled = if (i == 0) io.pe0Released else true.B
    returnStages.head.io.enq.valid :=
      localPeRing.data.qOutTask.valid && peEnabled
    returnStages.head.io.enq.bits := localPeRing.data.qOutTask.bits
    localPeRing.data.qOutTask.ready :=
      returnStages.head.io.enq.ready && peEnabled

    io.executed(i) := returnStages.head.io.enq.fire
    val workCount = RegInit(0.U(32.W))
    when(io.executed(i)) { workCount := workCount + 1.U }
    io.workCounts(i) := workCount

    // The simplified spawner maintains the same bounded outstanding-request
    // window as a real SpawnerServer, but immediately sends accepted work to
    // its PE instead of buffering it on the local scheduler network.
    val outstandingRequests = RegInit(0.U(log2Ceil(requestDepth + 1).W))
    localPeRing.ctrl.serveStealReq.ready :=
      peEnabled && outstandingRequests < requestDepth.U
    val requestAccepted =
      localPeRing.ctrl.serveStealReq.valid && localPeRing.ctrl.serveStealReq.ready
    val taskAccepted = localPeRing.data.qOutTask.fire
    when(requestAccepted && !taskAccepted) {
      outstandingRequests := outstandingRequests + 1.U
    }.elsewhen(taskAccepted && !requestAccepted && outstandingRequests > 0.U) {
      outstandingRequests := outstandingRequests - 1.U
    }

    // The simplified PE never injects tasks or requests through the local
    // scheduler ring; executed work reappears only through its delayed,
    // colocated task-source path below.
    localPeRing.data.availableTask.valid := false.B
    localPeRing.data.availableTask.bits := 0.U
    localPeRing.ctrl.stealReq.ready := false.B

    if (i == 0) {
      val sourceArbiter = Module(new Arbiter(UInt(taskWidth.W), 2))
      sourceArbiter.io.in(0) <> returnStages.last.io.deq
      sourceArbiter.io.in(1).valid := seedValid
      sourceArbiter.io.in(1).bits := seedCount
      taskSources(i).io.in <> sourceArbiter.io.out
      when(sourceArbiter.io.in(1).fire) {
        seedCount := seedCount + 1.U
      }
    } else {
      taskSources(i).io.in <> returnStages.last.io.deq
    }
  }
}

class BufferServerInputRingTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "BufferServerInput feedback ring"

  it should "spread one source across every PE and converge to II=1" in {
    val n = 8
    val returnDelay = 6
    val sustainedCycles = 32
    val initialTasks = n * (returnDelay + 4)

    test(
      new BufferServerInputFeedbackRing(
        n = n,
        taskWidth = 32,
        initialTasks = initialTasks,
        returnDelay = returnDelay
      )
    ) { dut =>
      dut.clock.setTimeout(0)

      var cycles = 0
      var sawWorkAwayFromPe0WhileBlocked = false
      var consecutiveFullRateCycles = 0
      val workPerPe = Array.fill(n)(0)

      while (consecutiveFullRateCycles < sustainedCycles && cycles < 1000) {
        val pe0Released = dut.io.pe0Released.peek().litToBoolean

        val firedMask = dut.io.executedMask.peek().litValue
        val fired = (0 until n).map(i => firedMask.testBit(i))
        for (i <- 0 until n if fired(i)) workPerPe(i) += 1

        if (!pe0Released) {
          sAssert(!fired.head, s"PE 0 accepted work while backpressured at cycle $cycles")
          if (fired.tail.exists(identity)) sawWorkAwayFromPe0WhileBlocked = true
        }

        if (fired.forall(identity)) consecutiveFullRateCycles += 1
        else consecutiveFullRateCycles = 0

        dut.clock.step()
        cycles += 1
      }

      val finalSeedCount = dut.io.acceptedSeedCount.peek().litValue
      val finalWorkCounts =
        (0 until n).map(i => dut.io.workCounts(i).peek().litValue)
      sAssert(
        finalSeedCount >= n,
        s"the root source accepted only $finalSeedCount/$initialTasks tasks; work=$finalWorkCounts"
      )
      sAssert(
        sawWorkAwayFromPe0WhileBlocked,
        "initial tasks never escaped the backpressured colocated spawner"
      )
      for (i <- 0 until n) {
        sAssert(workPerPe(i) > 0, s"PE $i never received work: ${workPerPe.toSeq}")
      }
      sAssert(
        consecutiveFullRateCycles >= sustainedCycles,
        s"ring did not reach II=1 on all $n PEs; work=${workPerPe.toSeq}, cycles=$cycles"
      )
    }
  }
}
