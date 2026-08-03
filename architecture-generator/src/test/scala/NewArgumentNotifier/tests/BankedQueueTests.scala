package NewArgumentNotifier.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.{BankedQueue, BankedRRArbiter}

import scala.collection.mutable
import scala.util.Random

class BankedQueueTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "BankedQueue"

  it should "preserve Queue ordering, capacity, and backpressure across banks" in {
    val width = 173
    val entries = 5
    test(new BankedQueue(UInt(width.W), entries, bankWidth = 32)) { dut =>
      val random = new Random(0x51a7eL)
      val expected = mutable.Queue.empty[BigInt]
      val mask = (BigInt(1) << width) - 1

      dut.io.enq.valid.poke(false.B)
      dut.io.deq.ready.poke(false.B)
      dut.clock.step()

      for (cycle <- 0 until 1000) {
        val offer = cycle < 850 && random.nextBoolean()
        val value = BigInt(width, random) & mask
        val take = cycle >= 850 || random.nextBoolean()
        dut.io.enq.valid.poke(offer.B)
        dut.io.enq.bits.poke(value.U)
        dut.io.deq.ready.poke(take.B)

        dut.io.count.expect(expected.size.U)
        dut.io.enq.ready.expect((expected.size < entries).B)
        dut.io.deq.valid.expect(expected.nonEmpty.B)
        if (expected.nonEmpty) {
          dut.io.deq.bits.expect(expected.front.U)
        }

        val enqFire = offer && dut.io.enq.ready.peek().litToBoolean
        val deqFire = take && expected.nonEmpty
        dut.clock.step()

        if (deqFire) expected.dequeue()
        if (enqFire) expected.enqueue(value)
      }
      assert(expected.isEmpty, s"queue did not drain: ${expected.size} entries remain")
    }
  }

  behavior of "BankedRRArbiter"

  it should "retain BasicArbiter's round-robin order and initial choice" in {
    test(new BankedRRArbiter(UInt(16.W), 4, bankWidth = 8)) { dut =>
      for (i <- 0 until 4) {
        dut.io.sources(i).valid.poke(true.B)
        dut.io.sources(i).bits.poke(i.U)
      }
      dut.io.sink.ready.poke(true.B)

      val expected = Seq(1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3, 0)
      val seen = mutable.ArrayBuffer.empty[Int]
      var guard = 0
      while (seen.size < expected.size) {
        if (dut.io.sink.valid.peek().litToBoolean) {
          seen += dut.io.sink.bits.peek().litValue.toInt
        }
        dut.clock.step()
        guard += 1
        assert(guard < 40, s"arbiter stalled after ${seen.size} outputs")
      }
      assert(seen.toSeq == expected)
    }
  }
}
