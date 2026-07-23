package NewArgumentNotifier.tests

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

import NewArgumentNotifier.{ArgumentCutDemuxNetwork, ArgumentRingNode}

import scala.collection.mutable

// A closed 4-node ring of ArgumentRingNodes. Entries are 16-bit values routed
// by their low 2 bits (value % 4 == node index), which mirrors how the update
// ring routes on the metadata server tag.
class RingHarness(n: Int, width: Int) extends Module {
  val io = IO(new Bundle {
    val inject = Vec(n, Flipped(Decoupled(UInt(width.W))))
    val tap = Vec(n, Decoupled(UInt(width.W)))
  })

  private val nodes = Seq.tabulate(n) { k =>
    Module(new ArgumentRingNode(UInt(width.W), (u: UInt) => u(1, 0) === k.U))
  }
  for (k <- 0 until n) {
    nodes(k).io.inject <> io.inject(k)
    io.tap(k) <> nodes(k).io.tap
    val prev = nodes((k + n - 1) % n)
    nodes(k).io.dataIn := prev.io.dataOut
    nodes(k).io.validIn := prev.io.validOut
  }
}

class ArgumentCutDemuxNetworkTests
    extends AnyFlatSpec
    with ChiselScalatestTester {
  behavior of "ArgumentCutDemuxNetwork"

  it should "collect sources into cuts and route each cut through its terminal demux" in {
    test(
      new ArgumentCutDemuxNetwork(
        UInt(16.W),
        sourceCount = 4,
        sinkCount = 2,
        cutCount = 2,
        select = (value: UInt) => value(0)
      )
    ) { dut =>
      for (source <- dut.io.sources) source.valid.poke(false.B)
      for (sink <- dut.io.sinks) sink.ready.poke(true.B)

      val pending = Array(
        mutable.Queue(BigInt(0x10), BigInt(0x12)),
        mutable.Queue(BigInt(0x14), BigInt(0x16)),
        mutable.Queue(BigInt(0x21), BigInt(0x23)),
        mutable.Queue(BigInt(0x25), BigInt(0x27))
      )
      val seen = Array.fill(2)(mutable.ArrayBuffer.empty[BigInt])

      var guard = 0
      while (pending.exists(_.nonEmpty) || seen.map(_.size).sum < 8) {
        for (i <- pending.indices) {
          dut.io.sources(i).valid.poke(pending(i).nonEmpty.B)
          if (pending(i).nonEmpty) dut.io.sources(i).bits.poke(pending(i).front.U)
        }
        for (sink <- 0 until 2) {
          if (dut.io.sinks(sink).valid.peek().litToBoolean) {
            seen(sink) += dut.io.sinks(sink).bits.peek().litValue
          }
        }
        val fires = pending.indices.map { i =>
          pending(i).nonEmpty && dut.io.sources(i).ready.peek().litToBoolean
        }
        dut.clock.step()
        for (i <- pending.indices if fires(i)) pending(i).dequeue()
        guard += 1
        assert(guard < 100, s"cut network stalled: ${seen.map(_.toSeq).toSeq}")
      }

      assert(seen(0).toSet == Set(0x10, 0x12, 0x14, 0x16).map(BigInt(_)))
      assert(seen(1).toSet == Set(0x21, 0x23, 0x25, 0x27).map(BigInt(_)))
    }
  }
}

class ArgumentRingNodeTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "ArgumentRingNode (closed ring)"

  private val N = 4
  private val W = 16

  private def init(dut: RingHarness): Unit = {
    for (k <- 0 until N) {
      dut.io.inject(k).valid.poke(false.B)
      dut.io.inject(k).bits.poke(0.U)
      dut.io.tap(k).ready.poke(false.B)
    }
  }

  private def inject(dut: RingHarness, node: Int, value: BigInt): Unit = {
    dut.io.inject(node).bits.poke(value.U)
    dut.io.inject(node).valid.poke(true.B)
    var guard = 0
    while (!dut.io.inject(node).ready.peek().litToBoolean) {
      dut.clock.step(); guard += 1
      assert(guard < 100, s"inject at node $node never accepted")
    }
    dut.clock.step()
    dut.io.inject(node).valid.poke(false.B)
  }

  // Wait for one tap fire at `node` (its ready must already be true) and
  // return the value. Asserts that no OTHER node's tap goes valid meanwhile.
  private def expectTap(
      dut: RingHarness,
      node: Int,
      within: Int = 20
  ): BigInt = {
    var guard = 0
    while (!dut.io.tap(node).valid.peek().litToBoolean) {
      for (other <- 0 until N if other != node) {
        assert(
          !dut.io.tap(other).valid.peek().litToBoolean,
          s"tap $other went valid for an entry routed to $node"
        )
      }
      dut.clock.step(); guard += 1
      assert(guard < within, s"tap $node never became valid")
    }
    val v = dut.io.tap(node).bits.peek().litValue
    dut.clock.step()
    v
  }

  it should "deliver an injected entry to the matching node's tap" in {
    test(new RingHarness(N, W)) { dut =>
      init(dut)
      dut.io.tap(2).ready.poke(true.B)
      inject(dut, node = 0, value = 0x12) // 0x12 % 4 == 2
      assert(expectTap(dut, 2) == BigInt(0x12))
    }
  }

  it should "route to the tap even when injected at the node just after it" in {
    test(new RingHarness(N, W)) { dut =>
      init(dut)
      dut.io.tap(1).ready.poke(true.B)
      // Injected at node 2, must travel 2 -> 3 -> 0 -> 1 around the ring.
      inject(dut, node = 2, value = 0x21) // 0x21 % 4 == 1
      assert(expectTap(dut, 1) == BigInt(0x21))
    }
  }

  it should "keep a blocked entry circulating instead of dropping it" in {
    test(new RingHarness(N, W)) { dut =>
      init(dut)
      inject(dut, node = 0, value = 0x13) // routed to node 3, tap not ready
      // More than two full laps with the tap blocked: nothing may fire.
      for (_ <- 0 until 3 * N) {
        for (k <- 0 until N) {
          assert(
            !(dut.io.tap(k).valid.peek().litToBoolean &&
              dut.io.tap(k).ready.peek().litToBoolean),
            "an entry was consumed while every tap was blocked"
          )
        }
        dut.clock.step()
      }
      dut.io.tap(3).ready.poke(true.B)
      assert(expectTap(dut, 3) == BigInt(0x13))
    }
  }

  it should "give circulating entries priority over injection and drop nothing" in {
    test(new RingHarness(N, W)) { dut =>
      init(dut)
      // Fill the ring with 4 entries for node 3 while its tap is blocked.
      val values = Seq(0x13, 0x17, 0x1b, 0x1f).map(BigInt(_)) // all % 4 == 3
      values.foreach(v => inject(dut, node = 0, value = v))

      // Ring is now fully occupied by circulating entries: a 5th injection
      // must not be accepted anywhere.
      dut.io.inject(1).bits.poke(0x23.U) // also routed to node 3
      dut.io.inject(1).valid.poke(true.B)
      for (_ <- 0 until 3 * N) {
        assert(
          !dut.io.inject(1).ready.peek().litToBoolean,
          "injection accepted while every ring slot was occupied"
        )
        dut.clock.step()
      }

      // Unblock the tap: all 4 circulating entries plus the 5th (which can
      // now inject into a freed slot) must arrive, none lost, none duplicated.
      dut.io.tap(3).ready.poke(true.B)
      val seen = mutable.ArrayBuffer.empty[BigInt]
      var guard = 0
      while (seen.size < 5) {
        if (dut.io.tap(3).valid.peek().litToBoolean) {
          seen += dut.io.tap(3).bits.peek().litValue
        }
        if (
          dut.io.inject(1).valid.peek().litToBoolean &&
          dut.io.inject(1).ready.peek().litToBoolean
        ) {
          dut.clock.step()
          dut.io.inject(1).valid.poke(false.B)
        } else {
          dut.clock.step()
        }
        guard += 1
        assert(guard < 200, s"only ${seen.size} of 5 entries arrived")
      }
      assert(seen.toSet == (values :+ BigInt(0x23)).toSet, s"got $seen")
    }
  }
}
