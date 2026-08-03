package Scheduler.tests

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.{SchedulerLocalNetwork, SchedulerLocalRingLayout, SpawnerServer}

/** The PE-local steal ring with real spawners on it and a scheduler server sharing it.
  *
  * Spawners flood -- they offer a task on every cycle they hold one -- so on a rigid ring every
  * injector downstream of a spawner is starved: the slot it needs is occupied every cycle by
  * traffic it cannot displace. The ring is built elastic precisely so an injector can assert
  * qOutTask.valid, have that feed the hop's stopOut, backpressure upstream, and take the slot it
  * vacates. These tests exercise that on the layout the generator actually builds:
  *
  *   vas0 -> vss0 -> pe0 -> vas1 -> pe1 -> vas2 -> pe2 -> vas3 -> pe3
  */
class ElasticStealRingHarness(
    taskWidth: Int,
    peCount: Int,
    queueDepth: Int
) extends Module {
  val io = IO(new Bundle {
    // Co-located source feeding each spawner's outside-ring intake.
    val srcBits = Input(Vec(peCount, UInt(taskWidth.W)))
    val srcValid = Input(Vec(peCount, Bool()))
    val srcAccepted = Output(Vec(peCount, Bool()))
    // The scheduler server's injection port.
    val schedBits = Input(UInt(taskWidth.W))
    val schedValid = Input(Bool())
    val schedAccepted = Output(Bool())
    // Each PE's drain.
    val peReady = Input(Vec(peCount, Bool()))
    val peValid = Output(Vec(peCount, Bool()))
    val peBits = Output(Vec(peCount, UInt(taskWidth.W)))
  })

  val net = Module(
    new SchedulerLocalNetwork(
      peCount = peCount,
      vssCount = 1,
      vasCount = peCount,
      taskWidth = taskWidth,
      queueDepth = queueDepth,
      qRamReadLatency = 1,
      qRamWriteLatency = 1,
      spawnsItself = false,
      successiveNetworkConfig = false
    )
  )

  val spawners = Seq.fill(peCount)(Module(new SpawnerServer(taskWidth, queueDepth)))

  for (i <- 0 until peCount) {
    spawners(i).io.connNetwork_master <> net.io.connVAS(i)
    net.io.vasForceInject(i) := spawners(i).io.forceInject

    val s = spawners(i).io.connNetwork_slave
    s.data.availableTask.valid := io.srcValid(i)
    s.data.availableTask.bits := io.srcBits(i)
    io.srcAccepted(i) := s.data.availableTask.ready
    s.data.qOutTask.ready := true.B
    s.ctrl.serveStealReq.ready := false.B
    s.ctrl.stealReq.ready := true.B

    net.io.connPE(i).pop.ready := io.peReady(i)
    net.io.connPE(i).push.valid := false.B
    net.io.connPE(i).push.bits := 0.U
    io.peValid(i) := net.io.connPE(i).pop.valid
    io.peBits(i) := net.io.connPE(i).pop.bits
  }

  net.io.connVSS(0).data.qOutTask.valid := io.schedValid
  net.io.connVSS(0).data.qOutTask.bits := io.schedBits
  io.schedAccepted := net.io.connVSS(0).data.qOutTask.ready
  net.io.connVSS(0).data.availableTask.ready := false.B
  net.io.connVSS(0).ctrl.serveStealReq.valid := false.B
  net.io.connVSS(0).ctrl.stealReq.valid := false.B
}

class ElasticStealRingTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "elastic PE-local steal ring"

  private val taskWidth = 32
  private val peCount = 4
  private val queueDepth = 8

  // Scheduler tasks are tagged >= schedTag; spawner i's tasks are i + 1.
  private val schedTag = 0x1000

  private def anns = Seq(VerilatorBackendAnnotation)

  /** Drives the ring and returns (pops per PE, scheduler injections accepted, scheduler-tagged pops). */
  private def drive(
      dut: ElasticStealRingHarness,
      cycles: Int,
      schedAsking: Boolean,
      loadedLanes: Int => Boolean,
      peIsReady: (Int, Int) => Boolean
  ): (Array[Int], Int, Int) = {
    dut.io.schedBits.poke(schedTag.U)
    dut.io.schedValid.poke(schedAsking.B)
    for (i <- 0 until peCount) {
      dut.io.srcBits(i).poke((i + 1).U)
      dut.io.srcValid(i).poke(loadedLanes(i).B)
    }

    val pops = Array.fill(peCount)(0)
    var schedAccepted = 0
    var schedPops = 0
    for (cycle <- 0 until cycles) {
      val ready = Array.tabulate(peCount)(i => peIsReady(i, cycle))
      for (i <- 0 until peCount) dut.io.peReady(i).poke(ready(i).B)
      if (dut.io.schedAccepted.peek().litToBoolean) schedAccepted += 1
      for (i <- 0 until peCount)
        if (ready(i) && dut.io.peValid(i).peek().litToBoolean) {
          pops(i) += 1
          if (dut.io.peBits(i).peek().litValue.toInt >= schedTag) schedPops += 1
        }
      dut.clock.step()
    }
    (pops, schedAccepted, schedPops)
  }

  it should "build the layout these tests assume" in {
    val layout = SchedulerLocalRingLayout.build(peCount, 1, peCount)
    assert(
      layout.nodeKinds == Vector("vas0", "vss0", "pe0", "vas1", "pe1", "vas2", "pe2", "vas3", "pe3"),
      s"layout changed: ${layout.nodeKinds.mkString(" -> ")}"
    )
  }

  // The case a rigid ring cannot serve: the spawner immediately upstream of the scheduler is
  // flooding, so on a rigid ring the scheduler's slot is occupied every single cycle and it never
  // injects. On the elastic ring its qOutTask.valid backpressures that spawner and it gets in.
  it should "let the scheduler inject while every spawner floods" in {
    test(new ElasticStealRingHarness(taskWidth, peCount, queueDepth))
      .withAnnotations(anns) { dut =>
        dut.clock.setTimeout(0)
        val cycles = 600
        val (pops, schedAccepted, schedPops) =
          drive(dut, cycles, schedAsking = true, loadedLanes = _ => true, peIsReady = (_, _) => true)

        assert(
          pops.sum > 0,
          "no spawner delivered anything -- the flood path is broken, not the arbitration"
        )
        assert(
          schedAccepted > 0,
          s"the scheduler never landed an injection in $cycles cycles: a flooding spawner starved " +
            "it, which is what the elastic ring exists to prevent"
        )
        assert(schedPops > 0, s"$schedAccepted scheduler injections accepted but none reached a PE")
        println(
          f"+ scheduler landed $schedAccepted injections in $cycles cycles " +
            f"(${100.0 * schedAccepted / cycles}%.1f%%), $schedPops reached a PE; " +
            f"spawners delivered ${pops.sum}"
        )
      }
  }

  // Fan-out case 1: the loaded lane's own PE is slow. Its client fills to minLengthThresh and starts
  // refusing, so the surplus has to walk the ring to a PE that can use it.
  it should "fan out past a slow PE" in {
    test(new ElasticStealRingHarness(taskWidth, peCount, queueDepth))
      .withAnnotations(anns) { dut =>
        dut.clock.setTimeout(0)
        val (pops, _, _) = drive(
          dut,
          cycles = 800,
          schedAsking = false,
          loadedLanes = _ == 0,
          peIsReady = (i, c) => if (i == 0) c % 8 == 0 else true
        )
        assert(pops(0) > 0, "the loaded lane executed nothing at all")
        assert(
          pops.drop(1).sum > 0,
          s"work never left the slow lane: ${pops.mkString(", ")}"
        )
        println(s"+ slow lane 0 fanned out: ${pops.mkString(", ")}")
      }
  }

  // The scheduler and the spawner immediately upstream of it share the same stretch of ring. On a
  // rigid ring that is winner-take-all: whichever one is saturated holds the slot and the other
  // never gets in. Both must make progress here.
  //
  // Note this is a SHARING test, not a fan-out test. With one loaded lane the supply is at most one
  // task per cycle per injector and the co-located PE can absorb it, so there is no surplus to push
  // to another PE and nothing should be expected to travel -- fan-out is covered by the slow-PE
  // case above, where the local client genuinely fills up and starts refusing.
  it should "share the slot between the scheduler and the spawner upstream of it" in {
    test(new ElasticStealRingHarness(taskWidth, peCount, queueDepth))
      .withAnnotations(anns) { dut =>
        dut.clock.setTimeout(0)
        val cycles = 800
        val (pops, schedAccepted, schedPops) = drive(
          dut,
          cycles = cycles,
          schedAsking = true,
          loadedLanes = _ == 0,
          peIsReady = (_, _) => true
        )
        val spawnerPops = pops.sum - schedPops
        assert(
          schedAccepted > 0 && schedPops > 0,
          s"the scheduler was starved by the spawner upstream of it: accepted $schedAccepted, " +
            s"delivered $schedPops"
        )
        assert(
          spawnerPops > 0,
          s"the spawner was starved by the scheduler downstream of it: ${pops.mkString(", ")}"
        )
        println(
          f"+ shared over $cycles cycles: $schedPops scheduler tasks, $spawnerPops spawner tasks " +
            f"(${pops.mkString(", ")})"
        )
      }
  }

  // The whole point, in one measurement: several injectors placing work in the same cycle. On a
  // rigid ring the head spawner monopolises and total placement is capped near one per cycle.
  it should "sustain more than one placement per cycle across contending injectors" in {
    test(new ElasticStealRingHarness(taskWidth, peCount, queueDepth))
      .withAnnotations(anns) { dut =>
        dut.clock.setTimeout(0)
        val cycles = 600
        val (pops, _, _) =
          drive(dut, cycles, schedAsking = true, loadedLanes = _ => true, peIsReady = (_, _) => true)

        val busy = pops.count(_ > 0)
        assert(
          busy >= 3,
          s"only $busy of $peCount PEs were fed; injectors are still serialising: ${pops.mkString(", ")}"
        )
        assert(
          pops.sum > cycles,
          s"total placement ${pops.sum} over $cycles cycles did not exceed one per cycle: " +
            s"${pops.mkString(", ")}"
        )
        println(f"+ ${pops.sum} placements in $cycles cycles (${pops.sum.toDouble / cycles}%.2f/cyc): ${pops.mkString(", ")}")
      }
  }
}
