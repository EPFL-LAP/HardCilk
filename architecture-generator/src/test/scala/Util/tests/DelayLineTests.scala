package Util.tests

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec

import Util.DelayLine

import scala.collection.mutable
import scala.util.Random

/** The porch payload, shaped like the real one: an id, a line address and a
  * full continuation. 1058 bits at delay 72/75 is the case that motivated the
  * URAM variant, so it is the case the equivalence test runs.
  */
class PorchLike(idWidth: Int, addrWidth: Int, dataWidth: Int) extends Bundle {
  val id = UInt(idWidth.W)
  val address = UInt(addrWidth.W)
  val taskBaseData = UInt(dataWidth.W)
}

/** Both implementations, same clock, same stimulus, outputs side by side.
  *
  * The thresholds are forced rather than defaulted so each instance's variant is
  * unambiguous regardless of what [[DelayLine.defaultUramThreshold]] happens to
  * be: `delay + 1` can never be reached, `2` always is.
  */
class DelayLinePair[T <: Data](gen: T, delay: Int) extends Module {
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val in = Input(gen)
    val srlValid = Output(Bool())
    val srl = Output(gen)
    val uramValid = Output(Bool())
    val uram = Output(gen)
  })

  private val srl = Module(new DelayLine(gen, delay, uramThreshold = delay + 1))
  private val uram = Module(new DelayLine(gen, delay, uramThreshold = 2))
  require(!srl.useUram, "the SRL instance must not have picked URAM")
  require(uram.useUram, "the URAM instance must not have picked the shift register")

  for (d <- Seq(srl, uram)) {
    d.io.inValid := io.inValid
    d.io.in := io.in
  }
  io.srlValid := srl.io.outValid
  io.srl := srl.io.out
  io.uramValid := uram.io.outValid
  io.uram := uram.io.out
}

/** A single line, for the reference-model checks. */
class DelayLineWrapper[T <: Data](gen: T, delay: Int, threshold: Int)
    extends Module {
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val in = Input(gen)
    val outValid = Output(Bool())
    val out = Output(gen)
  })
  private val line = Module(new DelayLine(gen, delay, threshold))
  line.io.inValid := io.inValid
  line.io.in := io.in
  io.outValid := line.io.outValid
  io.out := line.io.out
}

class DelayLineTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Util.DelayLine"

  private def anns = Seq(VerilatorBackendAnnotation)

  // Verilator is needed throughout: the URAM variant is a blackbox.
  private val idW = 7
  private val addrW = 27
  private val dataW = 1024

  /** One stimulus stream, reused so the SRL and URAM runs see identical input.
    * Mixes dense traffic, sparse traffic and long idle gaps, because the two
    * implementations differ precisely in what they hold in unqualified slots and
    * only a stream with holes in it can expose that.
    */
  private def stimulus(n: Int, seed: Int): Seq[(Boolean, BigInt, BigInt, BigInt)] = {
    val rng = new Random(seed)
    (0 until n).map { i =>
      val valid = i % 37 match {
        case x if x < 20 => true // dense burst
        case x if x < 30 => rng.nextBoolean() // ragged
        case _ => false // idle gap
      }
      (
        valid,
        BigInt(idW, rng),
        BigInt(addrW, rng),
        BigInt(dataW, rng)
      )
    }
  }

  private def poke[T <: Data](
      port: PorchLike,
      id: BigInt,
      address: BigInt,
      data: BigInt
  ): Unit = {
    port.id.poke(id.U)
    port.address.poke(address.U)
    port.taskBaseData.poke(data.U)
  }

  /** The property that defines the module: out[t] == in[t-delay], and outValid
    * follows inValid with the same alignment. Checked against a software queue
    * rather than against the other implementation, so a shared bug in both would
    * still be caught.
    */
  private def checkAgainstModel(delay: Int, threshold: Int, seed: Int): Unit = {
    val gen = new PorchLike(idW, addrW, dataW)
    test(new DelayLineWrapper(gen, delay, threshold))
      .withAnnotations(anns) { dut =>
        val pending = mutable.Queue[(Boolean, BigInt, BigInt, BigInt)]()
        // The line starts empty: `delay` invalid beats are already in flight.
        for (_ <- 0 until delay) pending.enqueue((false, BigInt(0), BigInt(0), BigInt(0)))

        for ((valid, id, address, data) <- stimulus(400, seed)) {
          dut.io.inValid.poke(valid.B)
          poke(dut.io.in, id, address, data)

          val (expValid, expId, expAddr, expData) = pending.dequeue()
          dut.io.outValid.expect(expValid.B, s"outValid mismatch at delay $delay")
          if (expValid) {
            dut.io.out.id.expect(expId.U)
            dut.io.out.address.expect(expAddr.U)
            dut.io.out.taskBaseData.expect(expData.U)
          }

          pending.enqueue((valid, id, address, data))
          dut.clock.step()
        }
      }
  }

  it should "delay a qualified payload by exactly `delay` cycles (shift register)" in {
    checkAgainstModel(delay = 75, threshold = 76, seed = 1)
  }

  it should "delay a qualified payload by exactly `delay` cycles (URAM)" in {
    checkAgainstModel(delay = 75, threshold = 2, seed = 1)
  }

  /** The one that matters: whatever the URAM path does, it must be
    * indistinguishable from the shift register the porch used to be, cycle by
    * cycle, at the real width and the two real depths.
    */
  private def checkEquivalence(delay: Int, seed: Int, cycles: Int = 600): Unit = {
    val gen = new PorchLike(idW, addrW, dataW)
    test(new DelayLinePair(gen, delay)).withAnnotations(anns) { dut =>
      for (((valid, id, address, data), cycle) <- stimulus(cycles, seed).zipWithIndex) {
        dut.io.inValid.poke(valid.B)
        poke(dut.io.in, id, address, data)

        val srlValid = dut.io.srlValid.peek().litToBoolean
        val uramValid = dut.io.uramValid.peek().litToBoolean
        assert(
          srlValid == uramValid,
          s"outValid diverged at cycle $cycle (delay $delay): " +
            s"srl=$srlValid uram=$uramValid"
        )
        // Only qualified beats are compared. Unqualified payload is don't-care
        // by construction and the two implementations deliberately differ there.
        if (srlValid) {
          val a = (
            dut.io.srl.id.peek().litValue,
            dut.io.srl.address.peek().litValue,
            dut.io.srl.taskBaseData.peek().litValue
          )
          val b = (
            dut.io.uram.id.peek().litValue,
            dut.io.uram.address.peek().litValue,
            dut.io.uram.taskBaseData.peek().litValue
          )
          assert(a == b, s"payload diverged at cycle $cycle (delay $delay)")
        }
        dut.clock.step()
      }
    }
  }

  it should "be bit-identical to the shift register at the 75-cycle porch depth" in {
    checkEquivalence(delay = 75, seed = 2)
  }

  it should "be bit-identical to the shift register at the 72-cycle porch depth" in {
    checkEquivalence(delay = 72, seed = 3)
  }

  // 65 is the shallowest depth the default threshold sends to URAM, and 64 sits
  // exactly on it; both exercise the depth rounding (65 -> 128, 64 -> 128) and
  // the read pointer's initial offset, which is where an off-by-one would live.
  it should "be bit-identical at the threshold boundary" in {
    checkEquivalence(delay = 64, seed = 4, cycles = 400)
    checkEquivalence(delay = 65, seed = 5, cycles = 400)
  }

  // A power-of-two depth is the case where `1 << log2Ceil(delay + 1)` doubles;
  // getting that wrong aliases the read and write addresses.
  it should "be bit-identical when the delay is a power of two" in {
    checkEquivalence(delay = 128, seed = 6, cycles = 500)
  }

  it should "still work at the shallow depth that stays a shift register" in {
    checkAgainstModel(delay = 8, threshold = DelayLine.defaultUramThreshold, seed = 7)
  }

  behavior of "Util.DelayLine implementation selection"

  it should "keep every real porch depth on the shift register by default" in {
    // URAM is disabled: it freed area on fullTriangleCountDecoupled and made the
    // design unroutable, by forcing ~8,500 nets across an SLR boundary into SLL
    // columns already at 200% demand. See DelayLine.defaultUramThreshold.
    // Guards against the default being re-armed without that being deliberate.
    for (delay <- Seq(8, 32, 64, 65, 72, 75, 128, 4096)) {
      assert(
        !DelayLine.usesUram(delay),
        s"delay $delay must stay a shift register under the default threshold"
      )
    }
  }

  it should "still select URAM when the validated threshold is asked for" in {
    val t = DelayLine.validatedUramThreshold
    // The depths this design instantiates, against the threshold the URAM
    // variant was validated at.
    assert(DelayLine.usesUram(75, t))
    assert(DelayLine.usesUram(72, t))
    assert(!DelayLine.usesUram(8, t))
    // A single SRL32E per bit lane is the densest storage on the die; never
    // spend a URAM on it.
    assert(!DelayLine.usesUram(32, t))
    assert(DelayLine.usesUram(t, t))
    assert(!DelayLine.usesUram(t - 1, t))
  }
}
