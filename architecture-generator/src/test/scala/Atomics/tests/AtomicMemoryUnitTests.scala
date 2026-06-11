package Atomics.tests

import chisel3._
import chiseltest._
import Atomics.{AtomicMemoryUnit, AtomicMode, Operation}
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => scalaAssert, _}
import scala.collection.mutable

class AtomicMemoryUnitTests extends AnyFlatSpec with ChiselScalatestTester with org.scalatest.ParallelTestExecution {
  behavior of "AtomicMemoryUnit"

  // n=8 sizes the requestingPE field; the slot table is 2 entries, allocated
  // from a free list (no PE-to-slot binding).
  private val n = 8
  private val tableSize = 2

  private case class AReq(
      pe: Int,
      addr: BigInt,
      operand: BigInt,
      op: Operation.Type,
      mode: AtomicMode.Type = AtomicMode.DoubleWord
  )

  // A simple AXI4 (chext full) memory slave for the AMU's master port. Reads
  // return current memory; writes apply on the AW+W pair; B confirms. `latency`
  // delays read/write responses; `reverse` drives ready responses newest-first to
  // exercise out-of-order completion. Split into beforeStep/afterStep so the
  // caller can sample DUT outputs while the slave drives, then step.
  private class MemModel(
      dut: AtomicMemoryUnit,
      latency: Int = 0,
      reverse: Boolean = false
  ) {
    val mem = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    var sawWrite = false
    val arSizes = mutable.ArrayBuffer.empty[BigInt]
    val awSizes = mutable.ArrayBuffer.empty[BigInt]
    val wStrbs = mutable.ArrayBuffer.empty[BigInt]
    val wDatas = mutable.ArrayBuffer.empty[BigInt]

    private val g = dut.io.gmem
    private val readResp = mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)] // (cd, id, data)
    private val bResp = mutable.ArrayBuffer.empty[(Int, BigInt)] // (cd, id)
    private val awQ = mutable.Queue.empty[(BigInt, BigInt)] // (id, addr)
    private val wQ = mutable.Queue.empty[(BigInt, BigInt)] // (data, strb)

    private var capAr = (false, BigInt(0), BigInt(0), BigInt(0))
    private var capAw = (false, BigInt(0), BigInt(0), BigInt(0))
    private var capW = (false, BigInt(0), BigInt(0))
    private var capRIdx = -1
    private var capBIdx = -1
    private var capRConsumed = false
    private var capBConsumed = false

    private def pickReady(cds: collection.Seq[Int]): Int = {
      val idxs = cds.indices.filter(i => cds(i) <= 0)
      if (idxs.isEmpty) -1 else if (reverse) idxs.last else idxs.head
    }

    private def beatBase(addr: BigInt): BigInt = addr & ~BigInt(7)

    private def applyStrobe(old: BigInt, data: BigInt, strb: BigInt): BigInt = {
      (0 until 8).foldLeft(old) { case (acc, byte) =>
        if (((strb >> byte) & 1) == 1) {
          val clearMask = ~(BigInt(0xff) << (8 * byte))
          val newByte = ((data >> (8 * byte)) & BigInt(0xff)) << (8 * byte)
          (acc & clearMask) | newByte
        } else {
          acc
        }
      }
    }

    def beforeStep(): Unit = {
      g.ar.ready.poke(true.B)
      g.aw.ready.poke(true.B)
      g.w.ready.poke(true.B)

      capRIdx = pickReady(readResp.map(_._1))
      if (capRIdx >= 0) {
        val (_, id, data) = readResp(capRIdx)
        g.r.valid.poke(true.B)
        g.r.bits.id.poke(id.U)
        g.r.bits.data.poke(data.U)
        g.r.bits.resp.poke(0.U)
        g.r.bits.last.poke(true.B)
      } else g.r.valid.poke(false.B)

      capBIdx = pickReady(bResp.map(_._1))
      if (capBIdx >= 0) {
        val (_, id) = bResp(capBIdx)
        g.b.valid.poke(true.B)
        g.b.bits.id.poke(id.U)
        g.b.bits.resp.poke(0.U)
      } else g.b.valid.poke(false.B)

      val arFire = g.ar.valid.peek().litToBoolean
      capAr = (arFire,
        if (arFire) g.ar.bits.id.peek().litValue else BigInt(0),
        if (arFire) g.ar.bits.addr.peek().litValue else BigInt(0),
        if (arFire) g.ar.bits.size.peek().litValue else BigInt(0))
      val awFire = g.aw.valid.peek().litToBoolean
      capAw = (awFire,
        if (awFire) g.aw.bits.id.peek().litValue else BigInt(0),
        if (awFire) g.aw.bits.addr.peek().litValue else BigInt(0),
        if (awFire) g.aw.bits.size.peek().litValue else BigInt(0))
      val wFire = g.w.valid.peek().litToBoolean
      capW = (wFire,
        if (wFire) g.w.bits.data.peek().litValue else BigInt(0),
        if (wFire) g.w.bits.strb.peek().litValue else BigInt(0))
      capRConsumed = capRIdx >= 0 && g.r.ready.peek().litToBoolean
      capBConsumed = capBIdx >= 0 && g.b.ready.peek().litToBoolean
    }

    def afterStep(): Unit = {
      if (capRConsumed) readResp.remove(capRIdx)
      if (capBConsumed) bResp.remove(capBIdx)
      for (i <- readResp.indices)
        readResp(i) = (math.max(0, readResp(i)._1 - 1), readResp(i)._2, readResp(i)._3)
      for (i <- bResp.indices)
        bResp(i) = (math.max(0, bResp(i)._1 - 1), bResp(i)._2)

      if (capAr._1) {
        arSizes += capAr._4
        readResp += ((latency, capAr._2, mem(beatBase(capAr._3))))
      }
      if (capAw._1) {
        awSizes += capAw._4
        awQ.enqueue((capAw._2, capAw._3)); sawWrite = true
      }
      if (capW._1) {
        wDatas += capW._2
        wStrbs += capW._3
        wQ.enqueue((capW._2, capW._3))
      }
      while (awQ.nonEmpty && wQ.nonEmpty) {
        val (id, addr) = awQ.dequeue()
        val (data, strb) = wQ.dequeue()
        val base = beatBase(addr)
        mem(base) = applyStrobe(mem(base), data, strb)
        bResp += ((latency, id))
      }
    }
  }

  private def driveNoReq(dut: AtomicMemoryUnit): Unit = {
    dut.io.req.valid.poke(false.B)
    dut.io.req.bits.tag.poke(0.U)
    dut.io.req.bits.data.poke(0.U)
    dut.io.req.bits.operation.poke(Operation.Unlock)
    dut.io.req.bits.isValid.poke(true.B)
    dut.io.req.bits.requestingPE.poke(0.U)
    dut.io.req.bits.atomicMode.poke(AtomicMode.DoubleWord)
  }

  private def pokeReq(dut: AtomicMemoryUnit, r: AReq): Unit = {
    dut.io.req.valid.poke(true.B)
    dut.io.req.bits.tag.poke(r.addr.U)
    dut.io.req.bits.data.poke(r.operand.U)
    dut.io.req.bits.operation.poke(r.op)
    dut.io.req.bits.isValid.poke(true.B)
    dut.io.req.bits.requestingPE.poke(r.pe.U)
    dut.io.req.bits.atomicMode.poke(r.mode)
  }

  // Submit `reqs` (one accepted per cycle), service gmem, collect (pe, returnedData).
  private def run(
      dut: AtomicMemoryUnit,
      mem: MemModel,
      reqs: Seq[AReq],
      maxCycles: Int = 2000
  ): Seq[(Int, BigInt)] = {
    val pending = mutable.Queue.empty[AReq] ++ reqs
    val responses = mutable.ArrayBuffer.empty[(Int, BigInt)]
    var cycles = 0
    dut.io.resp.ready.poke(true.B)
    while ((pending.nonEmpty || responses.size < reqs.size) && cycles < maxCycles) {
      if (pending.nonEmpty) pokeReq(dut, pending.front) else driveNoReq(dut)
      mem.beforeStep()
      val accepted = pending.nonEmpty && dut.io.req.ready.peek().litToBoolean
      if (dut.io.resp.valid.peek().litToBoolean) {
        responses += ((dut.io.resp.bits.requestingPE.peek().litValue.toInt,
          dut.io.resp.bits.data.peek().litValue))
      }
      dut.clock.step()
      mem.afterStep()
      cycles += 1
      if (accepted) pending.dequeue()
    }
    driveNoReq(dut)
    scalaAssert(responses.size == reqs.size,
      s"expected ${reqs.size} responses, got ${responses.size} in $cycles cycles")
    responses.toSeq
  }

  private def mkDut = new AtomicMemoryUnit(n, tableSize)

  it should "set-unlock: return previous value and write the operand" in {
    test(mkDut) { dut =>
      val mem = new MemModel(dut)
      mem.mem(0x40) = 7
      val out = run(dut, mem, Seq(
        AReq(pe = 0, addr = 0x40, operand = 99, op = Operation.LockSetUnlockAndReturnCurrent)))
      scalaAssert(out == Seq((0, BigInt(7))), s"should return previous value 7, got $out")
      scalaAssert(mem.mem(0x40) == 99, s"memory should now hold 99, got ${mem.mem(0x40)}")
    }
  }

  it should "ifGreater: write when operand > current, else skip (incl. equal)" in {
    test(mkDut) { dut =>
      val m1 = new MemModel(dut); m1.mem(0x80) = 5
      val r1 = run(dut, m1, Seq(AReq(0, 0x80, 10, Operation.LockSetIfGreaterUnlockAndReturnCurrent)))
      scalaAssert(r1 == Seq((0, BigInt(5))) && m1.mem(0x80) == 10 && m1.sawWrite,
        s"ifGreater taken failed: r=$r1 mem=${m1.mem(0x80)} sawWrite=${m1.sawWrite}")
    }
    test(mkDut) { dut =>
      val m = new MemModel(dut); m.mem(0x80) = 5
      val r = run(dut, m, Seq(AReq(0, 0x80, 3, Operation.LockSetIfGreaterUnlockAndReturnCurrent)))
      scalaAssert(r == Seq((0, BigInt(5))) && m.mem(0x80) == 5 && !m.sawWrite,
        s"ifGreater skip failed: r=$r mem=${m.mem(0x80)} sawWrite=${m.sawWrite}")
    }
    test(mkDut) { dut =>
      val m = new MemModel(dut); m.mem(0x80) = 5
      val r = run(dut, m, Seq(AReq(0, 0x80, 5, Operation.LockSetIfGreaterUnlockAndReturnCurrent)))
      scalaAssert(r == Seq((0, BigInt(5))) && m.mem(0x80) == 5 && !m.sawWrite,
        s"ifGreater equal should skip: r=$r mem=${m.mem(0x80)} sawWrite=${m.sawWrite}")
    }
  }

  it should "ifLess: write when operand < current, else skip (incl. equal)" in {
    test(mkDut) { dut =>
      val m1 = new MemModel(dut); m1.mem(0x80) = 5
      val r1 = run(dut, m1, Seq(AReq(0, 0x80, 3, Operation.LockSetIfSignedLessUnlockAndReturnCurrent)))
      scalaAssert(r1 == Seq((0, BigInt(5))) && m1.mem(0x80) == 3 && m1.sawWrite,
        s"ifLess taken failed: r=$r1 mem=${m1.mem(0x80)}")
    }
    test(mkDut) { dut =>
      val m = new MemModel(dut); m.mem(0x80) = 5
      val r = run(dut, m, Seq(AReq(0, 0x80, 9, Operation.LockSetIfSignedLessUnlockAndReturnCurrent)))
      scalaAssert(r == Seq((0, BigInt(5))) && m.mem(0x80) == 5 && !m.sawWrite,
        s"ifLess skip failed: r=$r mem=${m.mem(0x80)}")
    }
    test(mkDut) { dut =>
      val m = new MemModel(dut); m.mem(0x80) = 5
      val r = run(dut, m, Seq(AReq(0, 0x80, 5, Operation.LockSetIfSignedLessUnlockAndReturnCurrent)))
      scalaAssert(r == Seq((0, BigInt(5))) && m.mem(0x80) == 5 && !m.sawWrite,
        s"ifLess equal should skip: r=$r mem=${m.mem(0x80)}")
    }
  }

  it should "add-N with N=1: return previous value and increment memory" in {
    test(mkDut) { dut =>
      val mem = new MemModel(dut)
      mem.mem(0x48) = 41
      val first = run(dut, mem, Seq(
        AReq(pe = 0, addr = 0x48, operand = 1, op = Operation.LockAddNReturnCurrent)))
      val second = run(dut, mem, Seq(
        AReq(pe = 0, addr = 0x48, operand = 1, op = Operation.LockAddNReturnCurrent)))
      scalaAssert(first ++ second == Seq((0, BigInt(41)), (0, BigInt(42))),
        s"each add-one (N=1) should return the previous value, got ${first ++ second}")
      scalaAssert(mem.mem(0x48) == 43,
        s"memory should be incremented twice to 43, got ${mem.mem(0x48)}")
    }
  }

  it should "add-N with N != 1: return previous value and add N to memory" in {
    test(mkDut) { dut =>
      val mem = new MemModel(dut)
      mem.mem(0x50) = 100
      val first = run(dut, mem, Seq(
        AReq(pe = 0, addr = 0x50, operand = 5, op = Operation.LockAddNReturnCurrent)))
      val second = run(dut, mem, Seq(
        AReq(pe = 0, addr = 0x50, operand = 17, op = Operation.LockAddNReturnCurrent)))
      scalaAssert(first ++ second == Seq((0, BigInt(100)), (0, BigInt(105))),
        s"each add-N should return the previous value, got ${first ++ second}")
      scalaAssert(mem.mem(0x50) == 122,
        s"memory should be 100 + 5 + 17 = 122, got ${mem.mem(0x50)}")
    }
  }

  it should "apply byte and word atomic modes without clobbering neighboring bytes" in {
    test(mkDut) { dut =>
      val mem = new MemModel(dut)
      mem.mem(0x40) = BigInt("1122334455667788", 16)

      val byteOut = run(dut, mem, Seq(
        AReq(
          pe = 0,
          addr = 0x43,
          operand = 0xaa,
          op = Operation.LockSetUnlockAndReturnCurrent,
          mode = AtomicMode.Byte
        )))
      scalaAssert(byteOut == Seq((0, BigInt(0x55))),
        s"byte op should return the selected previous byte (lane 3 = 0x55) right-justified, got $byteOut")
      scalaAssert(mem.mem(0x40) == BigInt("11223344aa667788", 16),
        s"byte op should only replace byte lane 3, got ${mem.mem(0x40).toString(16)}")

      run(dut, mem, Seq(
        AReq(
          pe = 0,
          addr = 0x44,
          operand = 1,
          op = Operation.LockAddNReturnCurrent,
          mode = AtomicMode.Word
        )))
      scalaAssert(mem.mem(0x40) == BigInt("11223345aa667788", 16),
        s"word add (N=1) should only increment the upper 32-bit word, got ${mem.mem(0x40).toString(16)}")
      scalaAssert(mem.arSizes.takeRight(2) == Seq(BigInt(0), BigInt(2)),
        s"AR sizes should be byte then word, got ${mem.arSizes}")
      scalaAssert(mem.awSizes.takeRight(2) == Seq(BigInt(0), BigInt(2)),
        s"AW sizes should be byte then word, got ${mem.awSizes}")
      scalaAssert(mem.wStrbs.takeRight(2) == Seq(BigInt(0x08), BigInt(0xf0)),
        s"WSTRB should target only selected byte lanes, got ${mem.wStrbs}")
    }
  }

  it should "sign-extend the selected atomic width for signed-less" in {
    test(mkDut) { dut =>
      val mem = new MemModel(dut)
      mem.mem(0x40) = 0x7f
      val taken = run(dut, mem, Seq(
        AReq(
          pe = 0,
          addr = 0x40,
          operand = 0xff,
          op = Operation.LockSetIfSignedLessUnlockAndReturnCurrent,
          mode = AtomicMode.Byte
        )))
      scalaAssert(taken == Seq((0, BigInt(0x7f))) && mem.mem(0x40) == 0xff && mem.sawWrite,
        s"byte signed-less should treat 0xff as -1 and write: r=$taken mem=${mem.mem(0x40)}")
    }
    test(mkDut) { dut =>
      val mem = new MemModel(dut)
      mem.mem(0x40) = 0xff
      val skipped = run(dut, mem, Seq(
        AReq(
          pe = 0,
          addr = 0x40,
          operand = 0x7f,
          op = Operation.LockSetIfSignedLessUnlockAndReturnCurrent,
          mode = AtomicMode.Byte
        )))
      scalaAssert(skipped == Seq((0, BigInt(0xff))) && mem.mem(0x40) == 0xff && !mem.sawWrite,
        s"byte signed-less should treat current 0xff as -1 and skip: r=$skipped mem=${mem.mem(0x40)}")
    }
  }

  it should "not respond until the write B response arrives" in {
    test(mkDut) { dut =>
      val mem = new MemModel(dut, latency = 8)
      mem.mem(0x10) = 1
      // Accept exactly one request.
      dut.io.resp.ready.poke(true.B)
      driveNoReq(dut)
      pokeReq(dut, AReq(0, 0x10, 2, Operation.LockSetUnlockAndReturnCurrent))
      var accepted = false
      var c = 0
      while (!accepted && c < 50) {
        mem.beforeStep()
        accepted = dut.io.req.ready.peek().litToBoolean
        scalaAssert(!dut.io.resp.valid.peek().litToBoolean, "resp fired before request accepted")
        dut.clock.step()
        mem.afterStep()
        c += 1
      }
      driveNoReq(dut)
      // For several cycles (< read+write latency) the response must stay low.
      var fired = false
      for (_ <- 0 until 6) {
        mem.beforeStep()
        if (dut.io.resp.valid.peek().litToBoolean) fired = true
        dut.clock.step(); mem.afterStep()
      }
      scalaAssert(!fired, "resp fired before the delayed write B response")
      var got = Option.empty[BigInt]
      var c2 = 0
      while (got.isEmpty && c2 < 100) {
        mem.beforeStep()
        if (dut.io.resp.valid.peek().litToBoolean) got = Some(dut.io.resp.bits.data.peek().litValue)
        dut.clock.step(); mem.afterStep()
        c2 += 1
      }
      scalaAssert(got.contains(BigInt(1)), s"expected delayed response value 1, got $got")
      scalaAssert(mem.mem(0x10) == 2, s"memory should hold 2, got ${mem.mem(0x10)}")
    }
  }

  it should "track multiple outstanding requests (both slots, out-of-order, with latency)" in {
    test(mkDut) { dut =>
      val mem = new MemModel(dut, latency = 5, reverse = true)
      mem.mem(0x100) = 11
      mem.mem(0x200) = 22
      val reqs = Seq(
        AReq(pe = 0, addr = 0x100, operand = 111, op = Operation.LockSetUnlockAndReturnCurrent),
        AReq(pe = 4, addr = 0x200, operand = 222, op = Operation.LockSetUnlockAndReturnCurrent))
      val out = run(dut, mem, reqs).toMap
      scalaAssert(out == Map(0 -> BigInt(11), 4 -> BigInt(22)),
        s"each PE must get its own previous value, got $out")
      scalaAssert(mem.mem(0x100) == 111 && mem.mem(0x200) == 222,
        s"both memories must be updated, got ${mem.mem(0x100)}, ${mem.mem(0x200)}")
    }
  }
}
