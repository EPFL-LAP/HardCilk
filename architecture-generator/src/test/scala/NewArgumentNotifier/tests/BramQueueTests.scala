package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import chiseltest.experimental.expose
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.BramQueue

/** The BRAM-backed FIFO that carries ArgumentServer resolutions.
  *
  * The property that motivates it -- never reading a RAM location in the cycle
  * it is written, because a Xilinx block RAM cannot return the new data across
  * ports -- is checked structurally here: an exposed probe asserts the read and
  * write addresses are distinct on every cycle both fire. RTL simulation alone
  * would NOT catch a violation, since the emitted memory model is write-first.
  */
class BramQueueTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "BramQueue"

  private val width = 32

  private class ProbedBramQueue(entries: Int)
      extends BramQueue(UInt(width.W), entries) {
    // Both memory ports fired this cycle on the SAME address: the read-under-
    // write case that must never occur. The module also asserts on it; exposing
    // it lets the test report the offending cycle.
    val exposedCollision = expose(addressCollision)
    val exposedReadEnable = expose(readEnable)
  }

  it should "deliver a single entry with the expected empty-to-nonempty latency" in {
    test(new BramQueue(UInt(width.W), 8)) { dut =>
      dut.io.enq.valid.poke(false.B)
      dut.io.deq.ready.poke(true.B)
      dut.clock.step(2)

      dut.io.enq.bits.poke(0xabcd.U)
      dut.io.enq.valid.poke(true.B)
      dut.io.enq.ready.expect(true.B)
      dut.clock.step()
      dut.io.enq.valid.poke(false.B)

      // Storage is synchronous and the read is deliberately withheld until the
      // entry is committed, so an empty queue takes two cycles to present it.
      var latency = 0
      while (!dut.io.deq.valid.peek().litToBoolean) {
        dut.clock.step(); latency += 1
        assert(latency < 8, "entry never appeared at the output")
      }
      assert(latency == 1, s"unexpected empty->nonempty latency: ${latency + 1}")
      dut.io.deq.bits.expect(0xabcd.U)
      dut.clock.step()
      dut.io.deq.valid.expect(false.B)
    }
  }

  it should "sustain one transfer per cycle in each direction once primed" in {
    val entries = 8
    test(new BramQueue(UInt(width.W), entries)) { dut =>
      dut.io.deq.ready.poke(true.B)
      dut.io.enq.valid.poke(true.B)

      val n = 200
      val out = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var next = 0
      var cycles = 0
      var primed = false
      var bubbles = 0
      while (out.size < n && cycles < 400) {
        dut.io.enq.bits.poke(next.U)
        val accepted = next < n && dut.io.enq.ready.peek().litToBoolean
        dut.io.enq.valid.poke((next < n).B)
        val handed = dut.io.deq.valid.peek().litToBoolean
        if (handed) { out += dut.io.deq.bits.peek().litValue; primed = true }
        else if (primed) bubbles += 1
        if (accepted) next += 1
        dut.clock.step()
        cycles += 1
      }
      assert(out.size == n, s"drained only ${out.size}/$n in $cycles cycles")
      assert(bubbles == 0, s"$bubbles output bubbles once the pipe was primed")
      assert(
        out.toSeq == (0 until n).map(BigInt(_)),
        "BramQueue reordered or corrupted its payloads"
      )
    }
  }

  it should "fill, backpressure, drain and wrap without loss or duplication" in {
    val entries = 6
    test(new BramQueue(UInt(width.W), entries)) { dut =>
      dut.io.deq.ready.poke(false.B)
      dut.io.enq.valid.poke(true.B)

      // Fill to the declared depth. The output stage counts toward it, so the
      // queue must accept exactly `entries` items and then refuse.
      var pushed = 0
      var cycles = 0
      while (dut.io.enq.ready.peek().litToBoolean && cycles < 40) {
        dut.io.enq.bits.poke(pushed.U)
        pushed += 1
        dut.clock.step()
        cycles += 1
      }
      dut.io.enq.valid.poke(false.B)
      assert(pushed == entries, s"accepted $pushed items, expected $entries")
      dut.io.count.expect(entries.U)
      for (_ <- 0 until 5) {
        dut.io.enq.ready.expect(false.B)
        dut.clock.step()
      }

      // Drain fully, then push another lap so both pointers wrap.
      dut.io.deq.ready.poke(true.B)
      val out = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      cycles = 0
      while (out.size < pushed && cycles < 60) {
        if (dut.io.deq.valid.peek().litToBoolean)
          out += dut.io.deq.bits.peek().litValue
        dut.clock.step()
        cycles += 1
      }
      assert(
        out.toSeq == (0 until entries).map(BigInt(_)),
        s"first lap came out wrong: $out"
      )
      dut.io.count.expect(0.U)

      val out2 = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var next = entries
      cycles = 0
      while (out2.size < 3 * entries && cycles < 200) {
        dut.io.enq.bits.poke(next.U)
        dut.io.enq.valid.poke((next < entries + 3 * entries).B)
        val accepted = next < entries + 3 * entries &&
          dut.io.enq.ready.peek().litToBoolean
        if (dut.io.deq.valid.peek().litToBoolean)
          out2 += dut.io.deq.bits.peek().litValue
        if (accepted) next += 1
        dut.clock.step()
        cycles += 1
      }
      assert(
        out2.toSeq == (entries until (entries + 3 * entries)).map(BigInt(_)),
        s"wrapped lap came out wrong: $out2"
      )
    }
  }

  it should "keep a valid head stable across downstream backpressure" in {
    test(new ProbedBramQueue(8)) { dut =>
      dut.io.enq.valid.poke(false.B)
      dut.io.deq.ready.poke(false.B)
      dut.clock.step(2)

      // Leave spare capacity behind the head so the write port can remain
      // active while the retained read address is exercised.
      for (value <- 0 until 3) {
        dut.io.enq.bits.poke((0x100 + value).U)
        dut.io.enq.valid.poke(true.B)
        dut.io.enq.ready.expect(true.B)
        dut.clock.step()
      }
      dut.io.enq.valid.poke(false.B)

      var latency = 0
      while (!dut.io.deq.valid.peek().litToBoolean) {
        dut.clock.step()
        latency += 1
        assert(latency < 8, "stalled head never became valid")
      }

      for (cycle <- 0 until 12) {
        dut.io.deq.valid.expect(true.B)
        dut.io.deq.bits.expect(0x100.U)
        dut.exposedReadEnable.expect(true.B)

        // Continue filling behind the stalled head on alternating cycles.
        dut.io.enq.valid.poke((cycle % 2 == 0).B)
        dut.io.enq.bits.poke((0x200 + cycle).U)
        dut.clock.step()
      }

      dut.io.enq.valid.poke(false.B)
      dut.io.deq.ready.poke(true.B)
      dut.io.deq.valid.expect(true.B)
      dut.io.deq.bits.expect(0x100.U)
      dut.clock.step()
    }
  }

  it should "never read a location in the cycle it is written" in {
    // The whole reason this module exists. A Xilinx BRAM returns the OLD
    // contents on a same-address read/write across its two ports, so a design
    // that relies on the new data (as chisel3.util.Queue with useSyncReadMem
    // does) simulates correctly and fails in hardware. Assert the addresses
    // stay apart under randomised, bursty traffic on both sides.
    val entries = 5
    test(new ProbedBramQueue(entries)) { dut =>
      val rng = new scala.util.Random(0xc01115)
      val pushed = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      val popped = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var next = 0
      var sawSimultaneous = 0

      for (cycle <- 0 until 4000) {
        val offering = rng.nextInt(10) < 7
        val draining = rng.nextInt(10) < 7
        dut.io.enq.bits.poke(next.U)
        dut.io.enq.valid.poke(offering.B)
        dut.io.deq.ready.poke(draining.B)

        dut.exposedCollision.expect(
          false.B,
          s"cycle $cycle: BramQueue read and wrote the same RAM address"
        )

        val enqFires = offering && dut.io.enq.ready.peek().litToBoolean
        val deqFires = draining && dut.io.deq.valid.peek().litToBoolean
        if (enqFires && deqFires) sawSimultaneous += 1
        if (deqFires) popped += dut.io.deq.bits.peek().litValue
        if (enqFires) { pushed += BigInt(next); next += 1 }
        dut.clock.step()
      }
      dut.io.enq.valid.poke(false.B)
      dut.io.deq.ready.poke(true.B)
      for (_ <- 0 until 20) {
        if (dut.io.deq.valid.peek().litToBoolean)
          popped += dut.io.deq.bits.peek().litValue
        dut.clock.step()
      }

      assert(
        sawSimultaneous > 100,
        s"only $sawSimultaneous simultaneous enq+deq cycles: the collision-" +
          "prone case was not exercised"
      )
      assert(
        popped.toSeq == pushed.toSeq.take(popped.size),
        "BramQueue lost, duplicated or reordered payloads under random traffic"
      )
      assert(popped.size >= pushed.size - entries, "queue stalled")
    }
  }
}
