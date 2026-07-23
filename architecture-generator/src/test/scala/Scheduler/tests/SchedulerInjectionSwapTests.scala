package Scheduler.tests

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.{SchedulerInjectionSwap, SchedulerNetwork}

// A minimal VAS -> VSS -> PE segment of the PE-local ring (nodes 0, 1, 2).
// Node 0 is the spawner injection point, node 1 the scheduler injection point,
// node 2 the PE.  qOutTask for the two injectors optionally passes through a
// SchedulerInjectionSwap, exactly as SchedulerLocalNetwork wires it.
class SwapRingHarness(taskWidth: Int, useSwap: Boolean) extends Module {
  val io = IO(new Bundle {
    val vasIn = Flipped(Decoupled(UInt(taskWidth.W))) // spawner task (node 0)
    val vssIn = Flipped(Decoupled(UInt(taskWidth.W))) // scheduler task (node 1)
    val peOut = Decoupled(UInt(taskWidth.W)) // task seen at the PE (node 2)
  })

  private val net = Module(new SchedulerNetwork(taskWidth, 3, Array.empty[Int]))

  if (useSwap) {
    val swap = Module(new SchedulerInjectionSwap(taskWidth))
    swap.io.upstreamIn <> io.vasIn
    swap.io.downstreamIn <> io.vssIn
    swap.io.upstreamOut <> net.io.connSS(0).data.qOutTask
    swap.io.downstreamOut <> net.io.connSS(1).data.qOutTask
  } else {
    net.io.connSS(0).data.qOutTask <> io.vasIn
    net.io.connSS(1).data.qOutTask <> io.vssIn
  }

  // The PE consumes at node 2; the two injector nodes never consume.
  io.peOut.valid := net.io.connSS(2).data.availableTask.valid
  io.peOut.bits := net.io.connSS(2).data.availableTask.bits
  net.io.connSS(2).data.availableTask.ready := io.peOut.ready
  net.io.connSS(0).data.availableTask.ready := false.B
  net.io.connSS(1).data.availableTask.ready := false.B
  net.io.connSS(2).data.qOutTask.valid := false.B
  net.io.connSS(2).data.qOutTask.bits := 0.U

  // The control ring is irrelevant to this test.
  for (i <- 0 until 3) {
    net.io.connSS(i).ctrl.stealReq.valid := false.B
    net.io.connSS(i).ctrl.serveStealReq.valid := false.B
  }
}

class SchedulerInjectionSwapTests extends AnyFlatSpec with ChiselScalatestTester {
  private val A = 0xaa // spawner (upstream) task
  private val B = 0xbb // scheduler (downstream) task

  behavior of "SchedulerInjectionSwap"

  it should "swap destinations when both would fire" in {
    test(new SchedulerInjectionSwap(8)) { dut =>
      dut.io.upstreamIn.valid.poke(true.B)
      dut.io.upstreamIn.bits.poke(A.U)
      dut.io.downstreamIn.valid.poke(true.B)
      dut.io.downstreamIn.bits.poke(B.U)
      dut.io.upstreamOut.ready.poke(true.B)
      dut.io.downstreamOut.ready.poke(true.B)

      // Spawner (upstream) takes the PE-adjacent downstream slot.
      dut.io.downstreamOut.valid.expect(true.B)
      dut.io.downstreamOut.bits.expect(A.U)
      dut.io.upstreamOut.valid.expect(true.B)
      dut.io.upstreamOut.bits.expect(B.U)
      // Both producers see their task accepted.
      dut.io.upstreamIn.ready.expect(true.B)
      dut.io.downstreamIn.ready.expect(true.B)
    }
  }

  it should "pass straight through when the spawner slot is blocked" in {
    test(new SchedulerInjectionSwap(8)) { dut =>
      dut.io.upstreamIn.valid.poke(true.B)
      dut.io.upstreamIn.bits.poke(A.U)
      dut.io.downstreamIn.valid.poke(true.B)
      dut.io.downstreamIn.bits.poke(B.U)
      dut.io.upstreamOut.ready.poke(false.B) // spawner's slot busy
      dut.io.downstreamOut.ready.poke(true.B)

      // No swap: scheduler fires into its own slot, spawner waits.
      dut.io.downstreamOut.bits.expect(B.U)
      dut.io.downstreamIn.ready.expect(true.B)
      dut.io.upstreamIn.ready.expect(false.B)
    }
  }

  it should "pass straight through when only the spawner injects" in {
    test(new SchedulerInjectionSwap(8)) { dut =>
      dut.io.upstreamIn.valid.poke(true.B)
      dut.io.upstreamIn.bits.poke(A.U)
      dut.io.downstreamIn.valid.poke(false.B)
      dut.io.upstreamOut.ready.poke(true.B)
      dut.io.downstreamOut.ready.poke(true.B)

      dut.io.upstreamOut.valid.expect(true.B)
      dut.io.upstreamOut.bits.expect(A.U)
      dut.io.downstreamOut.valid.expect(false.B)
      dut.io.upstreamIn.ready.expect(true.B)
    }
  }

  behavior of "SchedulerInjectionSwap on the VAS -> VSS -> PE ring"

  private def injectBothAndReadOrder(dut: SwapRingHarness): (Int, Int) = {
    dut.io.vasIn.valid.poke(true.B)
    dut.io.vasIn.bits.poke(A.U)
    dut.io.vssIn.valid.poke(true.B)
    dut.io.vssIn.bits.poke(B.U)
    dut.io.peOut.ready.poke(true.B)
    // Both empty ring slots accept this cycle.
    dut.io.vasIn.ready.expect(true.B)
    dut.io.vssIn.ready.expect(true.B)
    dut.clock.step()
    dut.io.vasIn.valid.poke(false.B)
    dut.io.vssIn.valid.poke(false.B)

    dut.io.peOut.valid.expect(true.B)
    val first = dut.io.peOut.bits.peek().litValue.toInt
    dut.clock.step()
    dut.io.peOut.valid.expect(true.B)
    val second = dut.io.peOut.bits.peek().litValue.toInt
    (first, second)
  }

  it should "without the swap deliver the scheduler task to the PE first" in {
    test(new SwapRingHarness(8, useSwap = false)) { dut =>
      val (first, second) = injectBothAndReadOrder(dut)
      assert(first == B, s"expected scheduler task first, got 0x${first.toHexString}")
      assert(second == A, s"expected spawner task second, got 0x${second.toHexString}")
    }
  }

  it should "with the swap deliver the spawner task to the PE first" in {
    test(new SwapRingHarness(8, useSwap = true)) { dut =>
      val (first, second) = injectBothAndReadOrder(dut)
      assert(first == A, s"expected spawner task first, got 0x${first.toHexString}")
      assert(second == B, s"expected scheduler task second, got 0x${second.toHexString}")
    }
  }
}
