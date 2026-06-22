package Atomics.tests

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import chext.amba.axi4
import chext.amba.axi4.full.ConnectOp._
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => scalaAssert, _}
import scala.collection.mutable

// Mirrors HBMInterconnect's per-port chain: each kernel master (64-bit address)
// goes through a ProtocolConverter that converts to the 34-bit HBM address, then
// into the shared Mux. This is the exact path the BFS distance/frontier writes
// take, and the place a 64->34 conversion or converter+mux concurrency bug would
// misroute a write.
class ConvMuxChain(n: Int, kernelCfg: axi4.Config, hbmSlaveCfg: axi4.Config)
    extends Module {
  val s_axi = IO(Vec(n, axi4.full.Slave(kernelCfg)))
  val mux = Module(
    new axi4.full.components.Mux(
      new axi4.full.components.MuxConfig(axiSlaveCfg = hbmSlaveCfg, numSlaves = n)
    )
  )
  for (i <- 0 until n) {
    val pc = Module(
      new axi4.full.components.ProtocolConverter(
        new axi4.full.components.ProtocolConverterConfig(
          axiSlaveCfg = kernelCfg,
          axiMasterCfg = hbmSlaveCfg
        )
      )
    )
    s_axi(i) :=> pc.s_axi
    pc.m_axi :=> mux.s_axi(i)
  }
  val m_axi = IO(axi4.full.Master(mux.m_axi.cfg))
  mux.m_axi :=> m_axi
}

// Direct write-integrity tests for the chext AXI Mux that HardCilk uses to fold
// many kernel m_axi ports onto fewer physical HBM ports (HBMInterconnect) and
// inside the LockServer. The com-orkut BFS corruption looks like a write whose
// DATA (a neighbor id, destined for the frontier buffer) lands at a DIFFERENT
// master's address (the distance buffer) -- i.e. the mux pairing a master's AW
// with another master's W data. AXI4 W has no id, so the mux must serialize W in
// AW-arbitration order; these tests hammer that with concurrent multi-master
// writes and verify every byte lands under the address its own master issued.
class AxiMuxTests extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "axi4.full.components.Mux"

  private def cfg(wData: Int) =
    axi4.Config(wId = 1, wAddr = 32, wData = wData, read = false, write = true)

  private def muxCfg(n: Int, wData: Int) =
    axi4.full.components.MuxConfig(axiSlaveCfg = cfg(wData), numSlaves = n)

  // A single AXI slave memory model on the mux's m_axi master port. It pairs AW
  // with W exactly as real hardware does: W beats belong to the oldest AW not yet
  // completed. If the mux delivers W out of AW order, data lands at the wrong
  // address here -- which is exactly the bug we are hunting.
  private class MemModel(m: axi4.full.Interface, beatBytes: Int) {
    val mem = mutable.Map.empty[BigInt, BigInt].withDefaultValue(BigInt(0))
    private val awQ = mutable.Queue.empty[(BigInt, Int, BigInt)] // (addr, len, id)
    private val bQ = mutable.Queue.empty[BigInt]          // ids awaiting B
    private var curAddr = BigInt(0)
    private var curId = BigInt(0)
    private var curBeat = 0
    private var inBurst = false

    // captured fires for this cycle
    private var awFire = false
    private var awAddr = BigInt(0); private var awLen = 0; private var awId = BigInt(0)
    private var wFire = false
    private var wData = BigInt(0); private var wStrb = BigInt(0); private var wLast = false
    private var bFire = false

    def beforeStep(): Unit = {
      m.aw.ready.poke(true.B)
      m.w.ready.poke(true.B)
      val haveB = bQ.nonEmpty
      m.b.valid.poke(haveB.B)
      if (haveB) {
        m.b.bits.id.poke(bQ.front.U)
        m.b.bits.resp.poke(0.U)
      }
      awFire = m.aw.valid.peek().litToBoolean
      if (awFire) {
        awAddr = m.aw.bits.addr.peek().litValue
        awLen = m.aw.bits.len.peek().litValue.toInt
        awId = m.aw.bits.id.peek().litValue
      }
      wFire = m.w.valid.peek().litToBoolean
      if (wFire) {
        wData = m.w.bits.data.peek().litValue
        wStrb = m.w.bits.strb.peek().litValue
        wLast = m.w.bits.last.peek().litToBoolean
      }
      bFire = haveB && m.b.ready.peek().litToBoolean
    }

    def afterStep(): Unit = {
      if (bFire) bQ.dequeue()
      if (awFire) awQ.enqueue((awAddr, awLen, awId))
      if (wFire) {
        if (!inBurst) {
          scalaAssert(awQ.nonEmpty, "W beat arrived with no outstanding AW")
          val (a, _, id) = awQ.dequeue()
          curAddr = a; curId = id; curBeat = 0; inBurst = true
        }
        // strobed byte write
        val addr = curAddr + curBeat.toLong * beatBytes
        var old = mem(addr)
        for (b <- 0 until beatBytes) {
          if (((wStrb >> b) & 1) == 1) {
            val mask = ~(BigInt(0xff) << (8 * b))
            val nb = ((wData >> (8 * b)) & 0xff) << (8 * b)
            old = (old & mask) | nb
          }
        }
        mem(addr) = old
        curBeat += 1
        if (wLast) { inBurst = false; bQ.enqueue(curId) }
      }
    }
  }

  private case class Burst(addr: BigInt, data: Seq[BigInt])

  // One testbench master driving a mux slave port: a queue of (addr, Seq[data]).
  private class MasterDriver(s: axi4.full.Interface, beatBytes: Int) {
    val pending = mutable.Queue.empty[Burst]
    private var awSent = false
    private var wIdx = 0
    var bGot = 0

    def init(): Unit = {
      s.aw.valid.poke(false.B); s.w.valid.poke(false.B); s.b.ready.poke(true.B)
      s.aw.bits.id.poke(0.U); s.aw.bits.size.poke(log2(beatBytes).U)
      s.aw.bits.burst.poke(1.U) // INCR
    }
    private def log2(x: Int): Int = { var r = 0; var v = x; while (v > 1) { v >>= 1; r += 1 }; r }

    // peeks happen before step; drive combationally
    def drive(): Unit = {
      if (pending.isEmpty) { s.aw.valid.poke(false.B); s.w.valid.poke(false.B); return }
      val b = pending.front
      s.aw.valid.poke((!awSent).B)
      s.aw.bits.addr.poke(b.addr.U)
      s.aw.bits.len.poke((b.data.length - 1).U)
      s.w.valid.poke((wIdx < b.data.length).B)
      if (wIdx < b.data.length) {
        s.w.bits.data.poke(b.data(wIdx).U)
        s.w.bits.strb.poke(((BigInt(1) << beatBytes) - 1).U)
        s.w.bits.last.poke((wIdx == b.data.length - 1).B)
      }
    }
    def sample(): Unit = {
      if (pending.isEmpty) return
      val b = pending.front
      if (!awSent && s.aw.ready.peek().litToBoolean) awSent = true
      if (wIdx < b.data.length && s.w.ready.peek().litToBoolean) wIdx += 1
      if (s.b.valid.peek().litToBoolean) { bGot += 1 }
      if (awSent && wIdx >= b.data.length && s.b.valid.peek().litToBoolean) {
        pending.dequeue(); awSent = false; wIdx = 0
      }
    }
  }

  private def runWriteIntegrity(n: Int, wData: Int, beatsPerBurst: Int,
                               burstsPerMaster: Int): Unit = {
    test(new axi4.full.components.Mux(muxCfg(n, wData)))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(0)
        val beatBytes = wData / 8
        val mem = new MemModel(dut.m_axi, beatBytes)
        val masters = (0 until n).map(i => new MasterDriver(dut.s_axi(i), beatBytes))
        masters.foreach(_.init())

        // Each master writes to a disjoint address region; data encodes
        // (master, burst, beat) so any cross-master delivery is detectable.
        val expected = mutable.Map.empty[BigInt, BigInt]
        for (i <- 0 until n; bnum <- 0 until burstsPerMaster) {
          val base = (BigInt(i) << 20) + (BigInt(bnum) * beatsPerBurst * beatBytes)
          val data = (0 until beatsPerBurst).map { j =>
            // distinctive value, fits in wData bits
            val v = (BigInt(i) << 24) | (BigInt(bnum) << 12) | BigInt(j)
            expected(base + j.toLong * beatBytes) = v
            v
          }
          masters(i).pending.enqueue(Burst(base, data))
        }

        var cycles = 0
        val maxCycles = 200000
        def allDone = masters.forall(_.pending.isEmpty)
        while (!allDone && cycles < maxCycles) {
          masters.foreach(_.drive())
          mem.beforeStep()
          masters.foreach(_.sample())
          dut.clock.step()
          mem.afterStep()
          cycles += 1
        }
        scalaAssert(allDone, s"writes did not drain in $maxCycles cycles")

        var mismatches = 0
        for ((addr, v) <- expected) {
          if (mem.mem(addr) != v) {
            if (mismatches < 10)
              println(f"[AxiMux] MISROUTE addr=0x${addr}%x expected=0x${v}%x got=0x${mem.mem(addr)}%x")
            mismatches += 1
          }
        }
        scalaAssert(mismatches == 0,
          s"$mismatches/${expected.size} written words landed at the wrong place " +
            s"(n=$n wData=$wData beats=$beatsPerBurst) -- AXI mux W/AW misrouting")
      }
  }

  it should "route single-beat concurrent writes from 2 masters correctly" in {
    runWriteIntegrity(n = 2, wData = 64, beatsPerBurst = 1, burstsPerMaster = 64)
  }

  it should "route burst writes from 2 masters correctly" in {
    runWriteIntegrity(n = 2, wData = 64, beatsPerBurst = 8, burstsPerMaster = 16)
  }

  it should "route concurrent writes from 8 masters correctly (256-bit)" in {
    runWriteIntegrity(n = 8, wData = 256, beatsPerBurst = 4, burstsPerMaster = 8)
  }

  // The interconnect path: kernel masters with 64-bit addresses -> ProtocolConverter
  // (64->34) -> Mux. Drive concurrent writes to the REAL BFS buffer base addresses
  // (distance=0x60000000, frontier0=0xa0000000, frontier1=0xc0000000, ...) so any
  // bit-specific 64->34 mis-map or converter+mux concurrency misroute shows up as a
  // write landing under the wrong base.
  it should "preserve addresses through the 64->34 ProtocolConverter + Mux chain" in {
    val kernelCfg = axi4.Config(wId = 1, wAddr = 64, wData = 256, read = false, write = true)
    val hbmCfg = axi4.Config(wId = 2, wAddr = 34, wData = 256, read = false, write = true)
    val bases = Seq(BigInt("40000000", 16), BigInt("60000000", 16),
                    BigInt("80000000", 16), BigInt("a0000000", 16),
                    BigInt("c0000000", 16), BigInt("e0000000", 16),
                    BigInt("100000000", 16), BigInt("160000000", 16))
    val n = bases.length
    test(new ConvMuxChain(n, kernelCfg, hbmCfg))
      .withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
        dut.clock.setTimeout(0)
        val beatBytes = 256 / 8
        val mask34 = (BigInt(1) << 34) - 1
        val mem = new MemModel(dut.m_axi, beatBytes)
        val masters = (0 until n).map(i => new MasterDriver(dut.s_axi(i), beatBytes))
        masters.foreach(_.init())
        val expected = mutable.Map.empty[BigInt, BigInt]
        val beats = 4
        val bursts = 16
        for (i <- 0 until n; bnum <- 0 until bursts) {
          val addr = bases(i) + BigInt(bnum) * beats * beatBytes
          val data = (0 until beats).map { j =>
            val v = (BigInt(i + 1) << 40) | (BigInt(bnum) << 8) | BigInt(j)
            expected((addr + j.toLong * beatBytes) & mask34) = v
            v
          }
          masters(i).pending.enqueue(Burst(addr, data))
        }
        var cycles = 0
        while (!masters.forall(_.pending.isEmpty) && cycles < 300000) {
          masters.foreach(_.drive()); mem.beforeStep(); masters.foreach(_.sample())
          dut.clock.step(); mem.afterStep(); cycles += 1
        }
        scalaAssert(masters.forall(_.pending.isEmpty), s"writes did not drain ($cycles cyc)")
        var bad = 0
        for ((addr, v) <- expected) if (mem.mem(addr) != v) {
          if (bad < 10) println(f"[ConvMux] MISMAP addr=0x$addr%x exp=0x$v%x got=0x${mem.mem(addr)}%x")
          bad += 1
        }
        scalaAssert(bad == 0, s"$bad/${expected.size} writes landed at the wrong 34-bit address")
      }
  }
}
