package Scheduler.tests

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => sAssert, _}

import Scheduler.{SchedulerLocalRingLayout, SchedulerNetwork}

class SchedulerLocalRingLayoutTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "SchedulerLocalRingLayout"

  it should "map each countDecoupled spawner to the same-index downstream PE" in {
    val layout = SchedulerLocalRingLayout.build(peCount = 8, vssCount = 1, vasCount = 8)

    assert(
      layout.nodeKinds == Vector(
        "vas0", "vss0", "pe0",
        "vas1", "pe1",
        "vas2", "pe2",
        "vas3", "pe3",
        "vas4", "pe4",
        "vas5", "pe5",
        "vas6", "pe6",
        "vas7", "pe7"
      )
    )

    for (i <- 0 until 8) {
      val nodesAfterSpawner = Iterator
        .iterate((layout.vasNodes(i) + 1) % layout.nodeKinds.size)(n => (n + 1) % layout.nodeKinds.size)
      val firstPe = nodesAfterSpawner.map(layout.nodeKinds).find(_.startsWith("pe")).get
      assert(firstPe == s"pe$i", s"spawner $i first reaches $firstPe")
    }
  }

  it should "put a co-located spawner before the scheduler and scheduler before the PE" in {
    val layout = SchedulerLocalRingLayout.build(peCount = 4, vssCount = 2, vasCount = 2)
    assert(layout.nodeKinds == Vector("vas0", "vss0", "pe0", "pe1", "vas1", "vss1", "pe2", "pe3"))
  }

  it should "retain configurations with more spawners than PEs" in {
    val layout = SchedulerLocalRingLayout.build(peCount = 1, vssCount = 1, vasCount = 2)
    assert(layout.nodeKinds == Vector("vas0", "vas1", "vss0", "pe0"))
  }

  it should "let finite saturated spawner and scheduler injections both drain" in {
    val layout = SchedulerLocalRingLayout.build(peCount = 1, vssCount = 1, vasCount = 1)
    test(new SchedulerNetwork(taskWidth = 32, tqNum = 3, layout.vssNodes.toArray)) { dut =>
      dut.clock.setTimeout(0)

      for (node <- 0 until 3) {
        dut.io.connSS(node).data.qOutTask.valid.poke(false.B)
        dut.io.connSS(node).data.qOutTask.bits.poke(0.U)
        dut.io.connSS(node).data.availableTask.ready.poke(false.B)
        dut.io.connSS(node).ctrl.stealReq.valid.poke(false.B)
        dut.io.connSS(node).ctrl.serveStealReq.valid.poke(false.B)
      }
      dut.io.connSS(layout.peNodes.head).data.availableTask.ready.poke(true.B)

      val perSource = 16
      var vasSent = 0
      var vssSent = 0
      val received = scala.collection.mutable.ArrayBuffer.empty[BigInt]
      var cycles = 0

      while ((vasSent < perSource || vssSent < perSource || received.size < 2 * perSource) && cycles < 160) {
        val vasValid = vasSent < perSource
        val vssValid = vssSent < perSource
        dut.io.connSS(layout.vasNodes.head).data.qOutTask.valid.poke(vasValid.B)
        dut.io.connSS(layout.vasNodes.head).data.qOutTask.bits.poke((0x1000 + vasSent).U)
        dut.io.connSS(layout.vssNodes.head).data.qOutTask.valid.poke(vssValid.B)
        dut.io.connSS(layout.vssNodes.head).data.qOutTask.bits.poke((0x2000 + vssSent).U)

        if (vasValid && dut.io.connSS(layout.vasNodes.head).data.qOutTask.ready.peek().litToBoolean)
          vasSent += 1
        if (vssValid && dut.io.connSS(layout.vssNodes.head).data.qOutTask.ready.peek().litToBoolean)
          vssSent += 1
        if (dut.io.connSS(layout.peNodes.head).data.availableTask.valid.peek().litToBoolean)
          received += dut.io.connSS(layout.peNodes.head).data.availableTask.bits.peek().litValue

        dut.clock.step()
        cycles += 1
      }

      sAssert(vasSent == perSource, s"spawner injected $vasSent/$perSource tasks")
      sAssert(vssSent == perSource, s"scheduler injected $vssSent/$perSource tasks")
      sAssert(received.size == 2 * perSource, s"PE received ${received.size}/${2 * perSource} tasks")
      sAssert(received.toSet ==
        ((0 until perSource).map(i => BigInt(0x1000 + i)) ++
          (0 until perSource).map(i => BigInt(0x2000 + i))).toSet)
    }
  }
}
