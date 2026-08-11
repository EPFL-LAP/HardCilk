package Allocator.tests

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import scala.Predef.{assert => sAssert, _}
import scala.collection.mutable

import chext.amba.axi4
import chext.amba.axi4s
import axi4.Ops._
import axi4s.Casts._

import Allocator.{Allocator, AllocatorServer, RecycleWriter, ResolutionCollector}

/** Exposes the Allocator's axi4-stream closure ports and its HBM master as
  * plain Decoupled/axi4 so a chiseltest can drive them.
  */
class AllocatorRecycleHarness(
    addrWidth: Int,
    peCount: Int,
    vcasCount: Int,
    queueDepth: Int,
    recycleSourceCount: Int
) extends Module {
  val dut = Module(
    new Allocator(
      addrWidth = addrWidth,
      peCount = peCount,
      vcasCount = vcasCount,
      queueDepth = queueDepth,
      pePortWidth = 64,
      recycleSourceCount = recycleSourceCount
    )
  )

  val closureOut = IO(Vec(peCount, Decoupled(UInt(64.W))))
  val recycleIn = IO(Vec(recycleSourceCount, Flipped(Valid(UInt(addrWidth.W)))))
  val leaked = IO(Output(UInt(64.W)))
  val paused = IO(Output(Bool()))
  val axi_mgmt = IO(Vec(vcasCount, axi4.lite.Slave(dut.io_internal.axi_mgmt_vcas(0).cfg)))
  val mem = IO(Vec(vcasCount, axi4.full.Master(dut.io_internal.vcas_axi_full(0).cfg)))

  for (i <- 0 until peCount) closureOut(i) <> dut.io_export.closureOut(i).asLite
  for (i <- 0 until recycleSourceCount) dut.io_recycle.get(i) := recycleIn(i)
  for (i <- 0 until vcasCount) {
    axi_mgmt(i) :=> dut.io_internal.axi_mgmt_vcas(i)
    dut.io_internal.vcas_axi_full(i) :=> mem(i)
  }
  leaked := dut.io_leaked.get
  paused := dut.io_paused
}

class ContinuationRecyclingTests extends AnyFlatSpec with ChiselScalatestTester {

  private val beatWidth = 256
  private val sysAddressWidth = 34
  private val alignBits = 5                        // log2(256/8)
  private val contBits = sysAddressWidth - alignBits // 29
  private val packedPerBeat = beatWidth / contBits   // 8
  private val burstBeats = 16
  private val contsPerBurst = burstBeats * packedPerBeat // 128
  private val burstBytes = burstBeats * (beatWidth / 8)  // 512

  private def packBeat(lanes: Seq[BigInt]): BigInt =
    lanes.zipWithIndex.map { case (v, i) => v << (i * contBits) }.reduce(_ | _)

  private def unpackBeat(beat: BigInt): Seq[BigInt] =
    Seq.tabulate(packedPerBeat)(i =>
      (beat >> (i * contBits)) & ((BigInt(1) << contBits) - 1)
    )

  // ---------------------------------------------------------------- collector

  behavior of "ResolutionCollector"

  it should "pack whole beats in the order BeatUnpacker expects" in {
    test(new ResolutionCollector(beatWidth, sysAddressWidth)) { dut =>
      val n = 8 * packedPerBeat
      val fed = mutable.ArrayBuffer[BigInt]()
      val got = mutable.ArrayBuffer[BigInt]()
      dut.io.beatOut.ready.poke(true.B)
      dut.io.in.valid.poke(false.B)
      dut.clock.step()

      for (i <- 0 until n) {
        val addr = BigInt(0x100000 + i * 256)
        dut.io.in.bits.poke(addr.U)
        dut.io.in.valid.poke(true.B)
        fed += (addr >> alignBits)
        if (dut.io.beatOut.valid.peek().litToBoolean)
          got ++= unpackBeat(dut.io.beatOut.bits.peek().litValue)
        dut.clock.step()
      }
      dut.io.in.valid.poke(false.B)
      for (_ <- 0 until 20) {
        if (dut.io.beatOut.valid.peek().litToBoolean)
          got ++= unpackBeat(dut.io.beatOut.bits.peek().litValue)
        dut.clock.step()
      }

      sAssert(dut.io.leaked.peek().litValue == 0, "leaked with a draining sink")
      sAssert(
        got == fed,
        s"packing mismatch: got ${got.take(10)} want ${fed.take(10)}"
      )
    }
  }

  it should "drop and count addresses instead of backpressuring a full sink" in {
    test(new ResolutionCollector(beatWidth, sysAddressWidth, beatQueueDepth = 2)) {
      dut =>
        // Sink never drains, so after the queue fills every completing beat's
        // address must be dropped -- and counted -- rather than stalling.
        dut.io.beatOut.ready.poke(false.B)
        val n = 200
        for (i <- 0 until n) {
          dut.io.in.bits.poke(BigInt(0x200000 + i * 256).U)
          dut.io.in.valid.poke(true.B)
          dut.clock.step()
        }
        dut.io.in.valid.poke(false.B)
        dut.clock.step()

        val leaked = dut.io.leaked.peek().litValue
        val queued = 2 * packedPerBeat // beats parked in the queue
        val residue = dut.io.residue.peek().litValue
        sAssert(leaked > 0, "a permanently full sink must produce drops")
        sAssert(
          leaked + queued + residue == n,
          s"closure balance broken: leaked=$leaked queued=$queued " +
            s"residue=$residue total=$n"
        )
    }
  }

  // ------------------------------------------------------------------- writer

  behavior of "RecycleWriter"

  it should "never absorb a beat it has no reserved slot for" in {
    test(new RecycleWriter(beatWidth, sysAddressWidth, burstLength = 15)) { dut =>
      // Only three slots are writable and nothing is ever committed, so the
      // writer must stop absorbing after exactly three beats no matter how long
      // the ring keeps offering.
      dut.io.server.running.poke(true.B)
      dut.io.server.writableBeats.poke(3.U)
      dut.io.server.capacityBeats.poke(1024.U)
      dut.io.server.baseAddress.poke(0x1000.U)
      dut.io.write_address.ready.poke(true.B)
      dut.io.write_data.ready.poke(true.B)
      dut.io.write_done.poke(false.B)

      var absorbed = 0
      for (_ <- 0 until 100) {
        dut.io.beatIn.bits.poke(0.U)
        dut.io.beatIn.valid.poke(true.B)
        if (dut.io.beatIn.ready.peek().litToBoolean) absorbed += 1
        dut.clock.step()
      }
      sAssert(absorbed == 3, s"absorbed $absorbed beats against 3 writable slots")
    }
  }

  it should "write full bursts at the tail and wrap without splitting" in {
    test(new RecycleWriter(beatWidth, sysAddressWidth, burstLength = 15)) { dut =>
      val base = BigInt(0x2000)
      val capacityBeats = 48 // three bursts
      dut.io.server.running.poke(true.B)
      dut.io.server.writableBeats.poke(capacityBeats.U)
      dut.io.server.capacityBeats.poke(capacityBeats.U)
      dut.io.server.baseAddress.poke(base.U)
      dut.io.write_address.ready.poke(true.B)
      dut.io.write_data.ready.poke(true.B)
      dut.io.write_done.poke(false.B)

      val addrs = mutable.ArrayBuffer[BigInt]()
      val beatsPerBurst = mutable.ArrayBuffer[Int]()
      var beatsThisBurst = 0
      var fed = 0

      for (_ <- 0 until 400) {
        dut.io.beatIn.bits.poke(BigInt(fed + 1).U)
        dut.io.beatIn.valid.poke(true.B)
        val absorb = dut.io.beatIn.ready.peek().litToBoolean
        if (dut.io.write_address.valid.peek().litToBoolean)
          addrs += dut.io.write_address.bits.peek().litValue
        if (dut.io.write_data.valid.peek().litToBoolean) {
          beatsThisBurst += 1
          if (dut.io.write_last.peek().litToBoolean) {
            beatsPerBurst += beatsThisBurst
            beatsThisBurst = 0
          }
        }
        // Retire each burst immediately so slots keep freeing up.
        dut.io.write_done.poke(
          (dut.io.write_data.valid.peek().litToBoolean &&
            dut.io.write_last.peek().litToBoolean).B
        )
        if (absorb) fed += 1
        dut.clock.step()
      }

      sAssert(addrs.length >= 4, s"only ${addrs.length} bursts issued")
      sAssert(
        beatsPerBurst.forall(_ == burstBeats),
        s"burst lengths not all $burstBeats: ${beatsPerBurst.take(6)}"
      )
      // Tail walks base, base+512, base+1024, then wraps back to base.
      val expected = Seq(base, base + 512, base + 1024, base)
      sAssert(
        addrs.take(4) == expected,
        s"tail walk ${addrs.take(4)} != $expected"
      )
      sAssert(
        addrs.forall(a => (a - base) % burstBytes == 0),
        "a burst started off a burst boundary; it could cross a 4KB line"
      )
    }
  }

  // ------------------------------------------------------------------- server

  behavior of "AllocatorServer with recycling"

  private def liteWrite(clock: Clock, m: axi4.lite.Interface, off: Int, data: BigInt): Unit = {
    var awDone = false; var wDone = false
    m.aw.bits.addr.poke(off.U); m.aw.bits.prot.poke(0.U)
    m.w.bits.data.poke(data.U); m.w.bits.strb.poke(0xff.U)
    m.aw.valid.poke(true.B); m.w.valid.poke(true.B); m.b.ready.poke(true.B)
    var g = 0
    while ((!awDone || !wDone) && g < 100) {
      if (!awDone && m.aw.ready.peek().litToBoolean) awDone = true
      if (!wDone && m.w.ready.peek().litToBoolean) wDone = true
      clock.step(); g += 1
      if (awDone) m.aw.valid.poke(false.B)
      if (wDone) m.w.valid.poke(false.B)
    }
    m.aw.valid.poke(false.B); m.w.valid.poke(false.B)
    g = 0
    while (!m.b.valid.peek().litToBoolean && g < 100) { clock.step(); g += 1 }
    clock.step(); m.b.ready.poke(false.B)
  }

  private def liteRead(clock: Clock, m: axi4.lite.Interface, off: Int): BigInt = {
    m.ar.bits.addr.poke(off.U); m.ar.bits.prot.poke(0.U)
    m.ar.valid.poke(true.B); m.r.ready.poke(true.B)
    var g = 0
    while (!m.ar.ready.peek().litToBoolean && g < 100) { clock.step(); g += 1 }
    clock.step(); m.ar.valid.poke(false.B)
    g = 0
    while (!m.r.valid.peek().litToBoolean && g < 100) { clock.step(); g += 1 }
    val v = m.r.bits.data.peek().litValue
    clock.step(); m.r.ready.poke(false.B)
    v
  }

  it should "wrap the read pointer and only expose commits after they land" in {
    test(
      new AllocatorServer(
        dataWidth = beatWidth,
        sysAddressWidth = sysAddressWidth,
        burstLength = 15,
        enableRecycling = true
      )
    ) { dut =>
      val base = BigInt(0x1000)
      val capacity = 2 * contsPerBurst // exactly two bursts
      val m = dut.io.axi_mgmt

      dut.io.read_address.ready.poke(false.B)
      dut.io.read_data.valid.poke(false.B)
      dut.io.dataOut.ready.poke(true.B)
      dut.io.recycle.get.commit.poke(false.B)
      dut.clock.step(5)

      liteWrite(dut.clock, m, 0x08, base)     // rAddr
      liteWrite(dut.clock, m, 0x18, capacity) // capacity
      liteWrite(dut.clock, m, 0x10, capacity) // occupancy
      liteWrite(dut.clock, m, 0x00, 0)        // release

      val ars = mutable.ArrayBuffer[BigInt]()
      dut.io.read_address.ready.poke(true.B)
      // Return read data so the slots become writable and head can advance.
      var pending = 0
      for (_ <- 0 until 60) {
        if (dut.io.read_address.valid.peek().litToBoolean) {
          ars += dut.io.read_address.bits.peek().litValue
          pending += burstBeats
        }
        dut.io.read_data.valid.poke((pending > 0).B)
        dut.io.read_data.bits.poke(0.U)
        if (pending > 0 && dut.io.read_data.ready.peek().litToBoolean) pending -= 1
        dut.clock.step()
      }

      sAssert(ars.length == 2, s"expected exactly 2 bursts before drying up, got ${ars.length}")
      sAssert(ars == Seq(base, base + 512), s"read walk ${ars} unexpected")
      // Draining is backpressure here, not a terminal condition. A recycling
      // allocator deliberately never latches rPause (AllocatorServer guards the
      // self-pause with `if (!enableRecycling)`): a pool sized as an admission
      // cap is SUPPOSED to sit at zero free addresses whenever the cap is
      // saturated, and pausing on that would be self-inflicted deadlock. So the
      // engine must simply stop issuing, and must still be running when it does.
      sAssert(
        !dut.io.paused.peek().litToBoolean,
        "a recycling allocator must never self-pause on an empty free pool"
      )
      sAssert(
        !dut.io.read_address.valid.peek().litToBoolean,
        "should stop issuing read bursts once the pool is drained"
      )
      sAssert(
        liteRead(dut.clock, m, 0x10) == 0,
        "occupancy should be zero after handing out the whole pool"
      )
      // Low water is only meaningful while running; it must have seen the dip.
      val low = liteRead(dut.clock, m, 0x20)
      sAssert(low == contsPerBurst, s"low water $low != $contsPerBurst")
    }
  }

  it should "hand the same slots out again after a commit wraps the pointer" in {
    test(
      new AllocatorServer(
        dataWidth = beatWidth,
        sysAddressWidth = sysAddressWidth,
        burstLength = 15,
        enableRecycling = true
      )
    ) { dut =>
      val base = BigInt(0x1000)
      val bursts = 4
      val capacity = bursts * contsPerBurst
      val m = dut.io.axi_mgmt

      dut.io.read_address.ready.poke(false.B)
      dut.io.read_data.valid.poke(false.B)
      dut.io.dataOut.ready.poke(true.B)
      dut.io.recycle.get.commit.poke(false.B)
      dut.clock.step(5)

      liteWrite(dut.clock, m, 0x08, base)
      liteWrite(dut.clock, m, 0x18, capacity)
      liteWrite(dut.clock, m, 0x10, capacity)
      liteWrite(dut.clock, m, 0x00, 0)

      val ars = mutable.ArrayBuffer[BigInt]()
      dut.io.read_address.ready.poke(true.B)
      var pending = 0
      var returnedInBurst = 0
      var committed = 0

      // A perfect recycler: every burst that comes back is immediately handed
      // to the writer and committed, so occupancy oscillates but never dries up
      // and the read pointer is free to lap the region.
      for (_ <- 0 until 400) {
        if (dut.io.read_address.valid.peek().litToBoolean) {
          ars += dut.io.read_address.bits.peek().litValue
          pending += burstBeats
        }
        dut.io.read_data.valid.poke((pending > 0).B)
        dut.io.read_data.bits.poke(0.U)
        var commitNow = false
        if (pending > 0 && dut.io.read_data.ready.peek().litToBoolean) {
          pending -= 1
          returnedInBurst += 1
          if (returnedInBurst == burstBeats) {
            returnedInBurst = 0
            commitNow = true
          }
        }
        dut.io.recycle.get.commit.poke(commitNow.B)
        if (commitNow) committed += 1
        dut.clock.step()
      }

      sAssert(committed >= bursts, s"only $committed bursts recycled")
      sAssert(
        ars.length > bursts,
        s"recycled slots were not handed out again (only ${ars.length} bursts)"
      )
      // The pointer laps the region: slot addresses repeat in order.
      val slots = Seq.tabulate(bursts)(i => base + i * 512)
      sAssert(
        ars.take(2 * bursts) == slots ++ slots,
        s"wrap sequence wrong: ${ars.take(2 * bursts)} != ${slots ++ slots}"
      )
      sAssert(!dut.io.paused.peek().litToBoolean, "recycling should have kept it running")
    }
  }

  // -------------------------------------------------------------- integration

  behavior of "Allocator with recycling end to end"

  it should "circulate a small pool indefinitely without ever double-issuing a live address" in {
    val peCount = 2
    val recycleSources = 2
    // Comfortably above the server's read-ahead (8 bursts = 1024 addresses sit
    // in its local FIFO) plus everything in transit, per the pool sizing rule.
    val capacity = 32 * contsPerBurst // 4096 addresses, 512 beats, 32 bursts
    val base = BigInt(0x4000)

    test(
      new AllocatorRecycleHarness(
        addrWidth = sysAddressWidth,
        peCount = peCount,
        vcasCount = 1,
        queueDepth = 32,
        recycleSourceCount = recycleSources
      )
    ).withAnnotations(Seq(VerilatorBackendAnnotation)) { dut =>
      // Backing store, one entry per beat of the region.
      val memBeats = mutable.Map[BigInt, BigInt]()
      val poolAddrs = Seq.tabulate(capacity)(i => BigInt(0x100000 + i * 256))
      for (b <- 0 until capacity / packedPerBeat) {
        val lanes = poolAddrs.slice(b * packedPerBeat, (b + 1) * packedPerBeat)
        memBeats(base + b * (beatWidth / 8)) = packBeat(lanes.map(_ >> alignBits))
      }

      val m = dut.axi_mgmt(0)
      for (i <- 0 until peCount) dut.closureOut(i).ready.poke(false.B)
      for (i <- 0 until recycleSources) dut.recycleIn(i).valid.poke(false.B)
      dut.mem(0).ar.ready.poke(false.B)
      dut.mem(0).aw.ready.poke(false.B)
      dut.mem(0).w.ready.poke(false.B)
      dut.mem(0).r.valid.poke(false.B)
      dut.mem(0).b.valid.poke(false.B)
      dut.clock.step(5)

      liteWrite(dut.clock, m, 0x08, base)
      liteWrite(dut.clock, m, 0x18, capacity)
      liteWrite(dut.clock, m, 0x10, capacity)
      liteWrite(dut.clock, m, 0x00, 0)

      // --- simple in-order AXI model -------------------------------------
      val readQueue = mutable.Queue[(Int, BigInt)]() // (readyAtCycle, beat)
      val writeAddrQueue = mutable.Queue[BigInt]()
      var writeCursor: Option[BigInt] = None
      val bQueue = mutable.Queue[Int]()
      val LAT = 20

      // --- bookkeeping -----------------------------------------------------
      val live = mutable.Set[BigInt]()
      val inFlightBack = mutable.Queue[(Int, BigInt)]()
      var handedOut = 0
      var recycled = 0
      var doubleIssue = 0
      var nextSource = 0
      val rnd = new scala.util.Random(1234)

      dut.mem(0).ar.ready.poke(true.B)
      dut.mem(0).aw.ready.poke(true.B)
      dut.mem(0).w.ready.poke(true.B)

      for (cyc <- 0 until 12000) {
        // ---- AR: queue up a burst of beats from the model memory
        if (dut.mem(0).ar.valid.peek().litToBoolean) {
          val a = dut.mem(0).ar.bits.addr.peek().litValue
          for (k <- 0 until burstBeats)
            readQueue.enqueue(
              (cyc + LAT + k, memBeats.getOrElse(a + k * (beatWidth / 8), BigInt(0)))
            )
        }
        // ---- R
        val rReady = readQueue.nonEmpty && readQueue.head._1 <= cyc
        dut.mem(0).r.valid.poke(rReady.B)
        if (rReady) {
          dut.mem(0).r.bits.data.poke(readQueue.head._2.U)
          dut.mem(0).r.bits.last.poke(false.B)
          dut.mem(0).r.bits.resp.poke(0.U)
          dut.mem(0).r.bits.id.poke(0.U)
        }
        if (rReady && dut.mem(0).r.ready.peek().litToBoolean) readQueue.dequeue()

        // ---- AW / W / B
        if (dut.mem(0).aw.valid.peek().litToBoolean)
          writeAddrQueue.enqueue(dut.mem(0).aw.bits.addr.peek().litValue)
        if (dut.mem(0).w.valid.peek().litToBoolean) {
          if (writeCursor.isEmpty && writeAddrQueue.nonEmpty)
            writeCursor = Some(writeAddrQueue.dequeue())
          writeCursor.foreach { addr =>
            memBeats(addr) = dut.mem(0).w.bits.data.peek().litValue
            if (dut.mem(0).w.bits.last.peek().litToBoolean) {
              writeCursor = None
              bQueue.enqueue(cyc + LAT)
            } else writeCursor = Some(addr + (beatWidth / 8))
          }
        }
        val bReady = bQueue.nonEmpty && bQueue.head <= cyc
        dut.mem(0).b.valid.poke(bReady.B)
        if (bReady) {
          dut.mem(0).b.bits.resp.poke(0.U)
          dut.mem(0).b.bits.id.poke(0.U)
        }
        if (bReady && dut.mem(0).b.ready.peek().litToBoolean) bQueue.dequeue()

        // ---- PEs consume closures
        for (i <- 0 until peCount) {
          val ready = rnd.nextDouble() < 0.5
          dut.closureOut(i).ready.poke(ready.B)
          if (ready && dut.closureOut(i).valid.peek().litToBoolean) {
            val a = dut.closureOut(i).bits.peek().litValue
            if (live.contains(a)) doubleIssue += 1
            live += a
            handedOut += 1
            // Hand it back after a short "computation".
            inFlightBack.enqueue((cyc + 30 + rnd.nextInt(40), a))
          }
        }

        // ---- resolutions feed addresses back in
        for (i <- 0 until recycleSources) dut.recycleIn(i).valid.poke(false.B)
        if (inFlightBack.nonEmpty && inFlightBack.head._1 <= cyc) {
          val (_, a) = inFlightBack.dequeue()
          live -= a
          recycled += 1
          dut.recycleIn(nextSource).valid.poke(true.B)
          dut.recycleIn(nextSource).bits.poke(a.U)
          nextSource = (nextSource + 1) % recycleSources
        }

        dut.clock.step()
      }

      val leaked = dut.leaked.peek().litValue
      val occupancy = liteRead(dut.clock, m, 0x10)
      val lowWater = liteRead(dut.clock, m, 0x20)
      val handedOutReg = liteRead(dut.clock, m, 0x30)

      info(
        s"handedOut=$handedOut recycled=$recycled live=${live.size} " +
          s"leaked=$leaked occupancy=$occupancy lowWater=$lowWater " +
          s"handedOutReg=$handedOutReg"
      )

      // The hardware counter is the host's proof that recycling ran: only
      // `capacity` distinct addresses exist, so exceeding it means the pool
      // wrapped. It leads the PE-observed count by whatever is still parked in
      // the read-ahead buffer and the ring.
      sAssert(
        handedOutReg > capacity,
        s"handedOut register $handedOutReg did not exceed capacity $capacity"
      )
      sAssert(
        handedOutReg >= handedOut,
        s"handedOut register $handedOutReg is behind the $handedOut addresses " +
          "the PEs actually took"
      )

      sAssert(doubleIssue == 0, s"$doubleIssue addresses issued while still live")
      sAssert(!dut.paused.peek().litToBoolean, "pool ran dry despite recycling")
      sAssert(leaked == 0, s"$leaked addresses dropped by the collectors")
      // The whole point. Only `capacity` distinct addresses exist and none is
      // ever issued while live, so serving more than that many closures is proof
      // that addresses came back and were handed out again.
      sAssert(
        handedOut > 2 * capacity,
        s"only $handedOut closures served from a $capacity-entry pool; " +
          "recycling is not circulating"
      )
      sAssert(
        occupancy <= capacity,
        s"occupancy $occupancy exceeded capacity $capacity"
      )
      sAssert(
        poolAddrs.toSet.union(live.toSet) == poolAddrs.toSet,
        "an address outside the pool was handed out"
      )
    }
  }
}
