package NewArgumentNotifier

import chisel3._
import chisel3.util._

import chext.amba.axi4
import chext.amba.axi4.Ops._
import chext.elastic
import chext.elastic.ConnectOp._

import Util._

//Top-level routing for cache updates, evictions, slow memory updates and spawns.
//
// Internal networks:
//  1. Update-redirect ring: an update that arrived at the wrong server
//     (metadata server # mismatch) is injected here and circulates until the
//     matching server taps it off on its extra (ring) update lane.
//  2. Coupled slow-path FIFOs + EvictionGaters: each cache lane preserves the
//     order between its evictions and missed updates; the per-server gater keeps
//     one ordered completion fence PER EVICTION-SAVER LANE (see evictionSaverOf)
//     to hold an update behind the retirement of its own line's eviction.
//  3. Eviction cut/demux network: still-counting lines are address-demuxed
//     across the CacheEvictionSaver lanes and written to HBM. A backpressured
//     merge, NOT a ring -- a ring can let a same-source entry overtake an
//     earlier one during a lap, reordering that source's writes and silently
//     invalidating the per-lane completion fence above.
//  4. Cut collection lines: released cache-missed updates terminate in one
//     demux per cut and are sent to the SlowArgumentHandler owning the address.
//
// External connections:
//  * s_axi_newCont  - one write-only AXI slave per continuation-source PE.
//    Terminates the PE spawnNext write buffer in place of its WriteROB; the
//    write becomes a cache insert at the statically attached server and the
//    B response is the accept. m_continuation returns the compact line address
//    and explicit (server #, id, lane) metadata captured at fire time.
//  * s_update       - one internal continuation-width update packet per
//    update-source PE: compact address, explicit metadata, full data and full
//    bit strobe. It connects directly to PE argOut and may exceed 1024 bits.
//  * m_axi_slow / m_axi_evict - 1 AXI port per SlowArgumentHandler and 1 per
//    CacheEvictionSaver; saver completions return metadata to the originating
//    EvictionGater.
//  * connStealNtw   - one spawner-network client per server and per slow
//    handler (servers first), replacing the old ArgumentServer spawn path.

case class ArgumentNetworksConfig(
    nServers: Int,
    /** Continuation-source PEs (and therefore "new" lanes) per server. */
    newLanesPerServer: Int,
    /** Update-source PEs directly attached per server; the server gets one
      * extra update lane fed from the redirect ring (base case 4 -> 5 lanes).
      */
    updateLanesPerServer: Int,
    nSlowHandlers: Int,
    nEvictionSavers: Int,
    counterWidth: Int,
    /** PE/write-buffer AXI address width (usually 64). */
    sysAddressWidth: Int,
    /** Physical byte-address width emitted by AllocatorServer/HBM. */
    realAddressWidth: Int,
    serverIDWidth: Int,
    continuationSize: Int,
    /** Data width of the PE argOut write buffer (the update writes). */
    updateDataWidth: Int,
    ringInjectQueueDepth: Int = 4,
    slowAxiIdWidth: Int = 6,
    slowCutCount: Int = 1,
    // Cut count for the eviction collection network (servers -> eviction-saver
    // lanes), same trade-off as slowCutCount. Applies uniformly across every
    // eviction-saver lane -- there is no per-lane JSON knob.
    evictCutCount: Int = 1,
    slowRequestQueueDepth: Int = 64,
    cacheDelayCycles: Int = 0,
    // Extra coupledQ slots for missed updates with no co-cycle eviction (throughput
    // knob for the non-backpressuring front porch; see ArgumentServer).
    missedUpdateExtra: Int = 64
) {
  require(nServers >= 1)
  require(newLanesPerServer >= 1 && updateLanesPerServer >= 1)
  require(nSlowHandlers >= 1 && nEvictionSavers >= 1)
  require(isPow2(continuationSize) && continuationSize >= 8)
  require(slowAxiIdWidth >= 1)
  require(cacheDelayCycles >= 0)
  require(slowCutCount >= 1 && slowCutCount <= nServers)
  require(evictCutCount >= 1 && evictCutCount <= nServers)
  require(slowRequestQueueDepth >= 1)

  val serverTagWidth = math.max(1, log2Ceil(nServers))
  val laneWidth = ArgumentNotifierHelpers.laneWidth(newLanesPerServer)
  require(realAddressWidth <= sysAddressWidth)

  val nSourcePEs = nServers * newLanesPerServer
  val nUpdatePEs = nServers * updateLanesPerServer

  val lineBytes = continuationSize / 8
  val lineShift = log2Ceil(lineBytes)
  val lineAddressWidth = realAddressWidth - lineShift
  require(lineAddressWidth >= 1)

  def metadataType =
    new ContinuationMetadata(serverTagWidth, serverIDWidth, laneWidth)

  def referenceType = new ContinuationReference(
    lineAddressWidth,
    serverTagWidth,
    serverIDWidth,
    laneWidth
  )

  /** Keep exactly the significant AllocatorServer pointer bits. */
  def lineAddressOf(byteAddr: UInt): UInt =
    byteAddr(realAddressWidth - 1, lineShift)

  /** Reconstruct an HBM byte address only at an AXI memory boundary. */
  def memoryAddressOf(lineAddr: UInt): UInt =
    lineAddr ## 0.U(lineShift.W)

  // The PE-side write masters are single-ID and write-only, exactly like the
  // WriteROB input they replace.
  val cfgAxiNewCont = axi4.Config(
    wAddr = sysAddressWidth,
    wData = continuationSize,
    wId = 0,
    read = false
  )
  val cfgAxiUpdate = axi4.Config(
    wAddr = sysAddressWidth,
    wData = updateDataWidth,
    wId = 0,
    read = false
  )
}

/** An explicitly tagged update routed directly or through the redirect ring. */
class RoutedContinuationUpdate(cfg: ArgumentNetworksConfig) extends Bundle {
  val upd = new ContinuationUpdate(
    cfg.lineAddressWidth,
    cfg.serverTagWidth,
    cfg.serverIDWidth,
    cfg.laneWidth,
    cfg.continuationSize
  )
}

/** One registered stage of a closed ring (same shift-register style as the
  * scheduler/allocator networks). A passing entry that `matches` is offered to
  * `tap`; if the tap is not ready the entry keeps circulating. When the slot is
  * (or becomes) empty, a pending `inject` entry fills it. Occupied slots always
  * have priority so in-flight entries are never dropped.
  */
class ArgumentRingNode[T <: Data](gen: T, matches: T => Bool) extends Module {
  val io = IO(new Bundle {
    val dataIn = Input(gen)
    val validIn = Input(Bool())
    val dataOut = Output(gen)
    val validOut = Output(Bool())

    val inject = Flipped(Decoupled(gen))
    val tap = Decoupled(gen)
  })

  private val dataReg = RegInit(0.U(gen.getWidth.W).asTypeOf(gen))
  private val validReg = RegInit(false.B)

  io.tap.bits := io.dataIn
  io.tap.valid := io.validIn && matches(io.dataIn)
  private val taken = io.tap.valid && io.tap.ready

  io.inject.ready := false.B

  when(io.validIn && !taken) {
    dataReg := io.dataIn
    validReg := true.B
  }.elsewhen(io.inject.valid) {
    dataReg := io.inject.bits
    validReg := true.B
    io.inject.ready := true.B
  }.otherwise {
    dataReg := 0.U(gen.getWidth.W).asTypeOf(gen)
    validReg := false.B
  }

  io.dataOut := dataReg
  io.validOut := validReg
}

/** One merge point in a cut collection line, matching the topology of the old
  * ArgumentNotifierNetworkUnit. Local traffic is buffered and periodically wins
  * over traffic arriving from farther down the line.
  */
class ArgumentCutLineUnit[T <: Data](gen: T, priority: Int) extends Module {
  val io = IO(new Bundle {
    val lineIn = Flipped(Decoupled(gen))
    val localIn = Flipped(Decoupled(gen))
    val lineOut = Decoupled(gen)
  })

  private val priorityReg = RegInit(priority.U)
  private val chooseLocal = io.localIn.valid &&
    (priorityReg === 0.U || !io.lineIn.valid)
  private val selected = Wire(Decoupled(gen))
  selected.valid := io.lineIn.valid || io.localIn.valid
  selected.bits := Mux(chooseLocal, io.localIn.bits, io.lineIn.bits)
  io.lineIn.ready := !chooseLocal && selected.ready
  io.localIn.ready := chooseLocal && selected.ready
  when(selected.fire) {
    priorityReg := Mux(priorityReg === 0.U, priority.U, priorityReg - 1.U)
  }
  io.lineOut <> Queue(selected, 2)
}

/** Cut collection network used for slow updates and eviction notifications.
  * Sources are divided among `cutCount` independent linear merge lines. Each
  * line terminates in exactly one address-selected demux, and every sink only
  * arbitrates among the `cutCount` queued demux outputs.
  */
class ArgumentCutDemuxNetwork[T <: Data](
    gen: T,
    sourceCount: Int,
    sinkCount: Int,
    cutCount: Int,
    select: T => UInt,
    sourceQueueDepth: Int = 2,
    perCutSinkQueueDepth: Int = 8
) extends Module {
  require(sourceCount >= 1 && sinkCount >= 1)
  require(cutCount >= 1 && cutCount <= sourceCount)

  val io = IO(new Bundle {
    val sources = Vec(sourceCount, Flipped(Decoupled(gen)))
    val sinks = Vec(sinkCount, Decoupled(gen))
  })

  private val sourceQs = Seq.fill(sourceCount)(
    Module(new Queue(chiselTypeOf(io.sources(0).bits), sourceQueueDepth))
  )
  io.sources.zip(sourceQs).foreach { case (source, q) => q.io.enq <> source }

  private val baseSize = sourceCount / cutCount
  private val remainder = sourceCount % cutCount
  private val groups = (0 until cutCount).map { cut =>
    val start = cut * baseSize + math.min(cut, remainder)
    val size = baseSize + (if (cut < remainder) 1 else 0)
    start until (start + size)
  }

  private val perCutSinkQs = Seq.fill(cutCount, sinkCount)(
    Module(new Queue(chiselTypeOf(io.sources(0).bits), perCutSinkQueueDepth))
  )

  for ((indices, cut) <- groups.zipWithIndex) {
    val units = indices.zipWithIndex.map { case (sourceIndex, position) =>
      val priority = indices.size - position - 1
      val unit = Module(
        new ArgumentCutLineUnit(chiselTypeOf(io.sources(0).bits), priority)
      )
      sourceQs(sourceIndex).io.deq :=> unit.io.localIn
      unit
    }
    for (position <- 0 until units.size - 1) {
      units(position + 1).io.lineOut :=> units(position).io.lineIn
    }
    units.last.io.lineIn.valid := false.B
    units.last.io.lineIn.bits := 0.U.asTypeOf(units.last.io.lineIn.bits)

    if (sinkCount == 1) {
      units.head.io.lineOut :=> perCutSinkQs(cut)(0).io.enq
    } else {
      val demux = Module(
        new elastic.Demux(chiselTypeOf(io.sources(0).bits), sinkCount)
      )
      demux.io.sinks.zipWithIndex.foreach { case (sink, sinkIndex) =>
        sink :=> perCutSinkQs(cut)(sinkIndex).io.enq
      }
      new elastic.Fork(units.head.io.lineOut) {
        protected def onFork: Unit = {
          fork() :=> demux.io.source
          new elastic.Transform(fork(), demux.io.select) {
            protected def onTransform: Unit = out := select(in)
          }
        }
      }
    }
  }

  for (sinkIndex <- 0 until sinkCount) {
    if (cutCount == 1) {
      perCutSinkQs(0)(sinkIndex).io.deq :=> io.sinks(sinkIndex)
    } else {
      val arb = Module(
        new elastic.BasicArbiter(
          chiselTypeOf(io.sources(0).bits),
          cutCount,
          chooserFn = elastic.Chooser.rr
        )
      )
      arb.io.select.deq()
      for (cut <- 0 until cutCount) {
        perCutSinkQs(cut)(sinkIndex).io.deq :=> arb.io.sources(cut)
      }
      arb.io.sink :=> io.sinks(sinkIndex)
    }
  }
}

/** Replaces the WriteROB of a continuation-source PE's spawnNext
  * WriteBufferCounter. The single-ID write master lands here instead of at the
  * memory: each AW+W pair becomes a cache insert at the statically attached
  * ArgumentServer, and the B response is returned when the server accepts. The
  * compact line address and assigned metadata are captured in the fire cycle
  * and emitted on `continuationOut`, one token per write.
  */
class NewContinuationBridge(cfg: ArgumentNetworksConfig, serverIndex: Int)
    extends Module {
  import cfg._

  val io = IO(new Bundle {
    val from_master = axi4.Slave(cfgAxiNewCont)

    val newContReq =
      Decoupled(new NewContinuationReq(lineAddressWidth, continuationSize))
    val assignedId = Input(UInt(serverIDWidth.W))
    val assignedLane = Input(UInt(laneWidth.W))

    val continuationOut = Decoupled(referenceType)
  })

  private val s = io.from_master.asFull

  private val joined = Wire(
    Decoupled(new NewContinuationReq(lineAddressWidth, continuationSize))
  )
  new elastic.Join(joined) {
    protected def onJoin: Unit = {
      val aw = join(s.aw)
      val w = join(s.w)
      out.address := lineAddressOf(aw.addr)
      out.taskBaseData := w.data
    }
  }

  // The request, its B response and its metadata token fire in the SAME
  // cycle: assignedId/assignedLane are only meaningful right when the server
  // accepts (the head pointer moves on the next edge).
  private val bQ = Module(new Queue(Bool(), 4))
  private val continuationQ = Module(new Queue(referenceType, 4))

  io.newContReq.valid := joined.valid && bQ.io.enq.ready && continuationQ.io.enq.ready
  io.newContReq.bits := joined.bits
  joined.ready := io.newContReq.ready && bQ.io.enq.ready && continuationQ.io.enq.ready

  bQ.io.enq.valid := io.newContReq.fire
  bQ.io.enq.bits := true.B
  continuationQ.io.enq.valid := io.newContReq.fire
  continuationQ.io.enq.bits.address := joined.bits.address
  continuationQ.io.enq.bits.metadata.server := serverIndex.U
  continuationQ.io.enq.bits.metadata.id := io.assignedId
  continuationQ.io.enq.bits.metadata.lane := io.assignedLane

  s.b.bits := DontCare
  s.b.bits.resp := axi4.ResponseFlag.OKAY
  s.b.valid := bQ.io.deq.valid
  bQ.io.deq.ready := s.b.ready

  io.continuationOut <> continuationQ.io.deq
}

/** Replaces the WriteROB of an update-source PE's argOut WriteBuffer. The
  * child's narrow argument write is widened into a full-line, bit-strobed
  * continuation update: its ContinuationReference arrives on a separate,
  * traceable channel; the AXI address contributes only the in-line byte offset
  * used to shift data and strobe into position, and the B response is returned
  * when the update is accepted downstream. Assumes each child issues exactly
  * ONE such write per continuation (one write == one counter decrement).
  */
class ContinuationUpdateBridge(cfg: ArgumentNetworksConfig) extends Module {
  import cfg._

  val io = IO(new Bundle {
    val from_master = axi4.Slave(cfgAxiUpdate)
    val continuationIn = Flipped(Decoupled(referenceType))
    val updateOut = Decoupled(new RoutedContinuationUpdate(cfg))
  })

  private val s = io.from_master.asFull

  private val joined = Wire(Decoupled(new RoutedContinuationUpdate(cfg)))
  new elastic.Join(joined) {
    protected def onJoin: Unit = {
      val aw = join(s.aw)
      val w = join(s.w)
      val continuation = join(io.continuationIn)

      val byteOffset = aw.addr(lineShift - 1, 0)
      val bitShift = byteOffset ## 0.U(3.W)

      out.upd.metadata := continuation.metadata
      out.upd.address := continuation.address
      out.upd.dataWrite :=
        (w.data.pad(continuationSize) << bitShift)(continuationSize - 1, 0)
      out.upd.dataWriteStrobe :=
        (FillInterleaved(8, w.strb).pad(continuationSize) << bitShift)(
          continuationSize - 1,
          0
        )
    }
  }

  private val bQ = Module(new Queue(Bool(), 4))

  io.updateOut.valid := joined.valid && bQ.io.enq.ready
  io.updateOut.bits := joined.bits
  joined.ready := io.updateOut.ready && bQ.io.enq.ready
  bQ.io.enq.valid := io.updateOut.fire
  bQ.io.enq.bits := true.B

  s.b.bits := DontCare
  s.b.bits.resp := axi4.ResponseFlag.OKAY
  s.b.valid := bQ.io.deq.valid
  bQ.io.deq.ready := s.b.ready
}

/** Adapts one completed-continuation lane to one independent scheduler-ring
  * client. No arbitration belongs here: preserving one adapter per cache lane
  * is what lets separately configured lanes enter the ring at different points.
  */
class SpawnLaneAdapter(taskWidth: Int) extends Module {
  val io = IO(new Bundle {
    val spawnIn = Flipped(Decoupled(UInt(taskWidth.W)))
    val connStealNtw = Flipped(new SchedulerNetworkClientIO(taskWidth))
  })

  io.connStealNtw.data.availableTask.nodeq()

  private val rTaskCount = Module(new chext.util.Counter(1 << 16))
  rTaskCount.noInc()
  rTaskCount.noDec()

  new elastic.Arrival(io.spawnIn, io.connStealNtw.data.qOutTask) {
    protected def onArrival: Unit = {
      when(rTaskCount.notFull) {
        rTaskCount.inc()
        out := in
        accept()
      }
    }
  }

  io.connStealNtw.ctrl.stealReq.valid := false.B
  io.connStealNtw.ctrl.serveStealReq.valid := false.B
  when(rTaskCount.notZero) {
    io.connStealNtw.ctrl.serveStealReq.valid := true.B
    when(io.connStealNtw.ctrl.serveStealReq.ready) {
      rTaskCount.dec()
    }
  }
}

class ArgumentNetworks(val cfg: ArgumentNetworksConfig) extends Module {
  import cfg._

  // ---- External connections ------------------------------------------------
  /** Per continuation-source PE: terminates the spawnNext write buffer's
    * single-ID master (the slot its WriteROB used to occupy).
    */
  val s_axi_newCont = IO(Vec(nSourcePEs, axi4.Slave(cfgAxiNewCont)))

  /** Per continuation-source PE: compact address plus explicit metadata for
    * each accepted write, in write order, for tagging released child tasks.
    */
  val m_continuation = IO(Vec(nSourcePEs, Decoupled(referenceType)))

  /** Per update-source PE: one internal, continuation-width update packet. This
    * is intentionally not AXI; its complete data and bit-strobe fields make the
    * packet wider than 1024 bits for a 512-bit continuation.
    */
  val s_update = IO(
    Vec(
      nUpdatePEs,
      Flipped(
        Decoupled(
          new ContinuationUpdate(
            lineAddressWidth,
            serverTagWidth,
            serverIDWidth,
            laneWidth,
            continuationSize
          )
        )
      )
    )
  )

  /** 1 AXI port per SlowArgumentHandler / CacheEvictionSaver. */
  val m_axi_slow = IO(
    Vec(
      nSlowHandlers,
      axi4.full.Master(
        axi4.Config(
          wAddr = realAddressWidth,
          wData = continuationSize,
          wId = slowAxiIdWidth
        )
      )
    )
  )
  val m_axi_evict = IO(
    Vec(
      nEvictionSavers,
      axi4.full.Master(
        axi4.Config(
          wAddr = realAddressWidth,
          wData = continuationSize,
          wId = 1,
          read = false
        )
      )
    )
  )

  /** Spawner-network clients: one independent leg per ArgumentServer cache
    * lane, followed by one independent leg per slow handler. Fast-lane index is
    * `server * newLanesPerServer + lane`.
    */
  private val nFastSpawnLegs = nServers * newLanesPerServer
  val connStealNtw = IO(
    Vec(
      nFastSpawnLegs + nSlowHandlers,
      Flipped(new SchedulerNetworkClientIO(continuationSize))
    )
  )

  val done = IO(Output(Bool()))

  /** Observational ready/valid taps for the telemetry watcher. Each UInt is
    * packed as {ready, valid}; these outputs never participate in flow control.
    */
  val watcherSlowUpdates = IO(Output(Vec(nSlowHandlers, UInt(2.W))))
  val watcherEvictions = IO(Output(Vec(nEvictionSavers, UInt(2.W))))
  val watcherFastSpawns =
    IO(Output(Vec(nServers * newLanesPerServer, UInt(2.W))))

  // ---- Servers ---------------------------------------------------------------
  val servers = Seq.tabulate(nServers) { serverIndex =>
    Module(
      new ArgumentServer(
        counterWidth,
        lineAddressWidth,
        serverTagWidth,
        serverIDWidth,
        continuationSize,
        newLanesPerServer,
        updateLanesPerServer + 1, // +1: lane fed from the redirect ring
        serverIndex = serverIndex,
        cacheDelayCycles = cacheDelayCycles,
        missedUpdateExtra = missedUpdateExtra
      )
    )
  }

  // ---- New-continuation path (replaces the spawnNext ROB) --------------------
  for (s <- 0 until nServers; j <- 0 until newLanesPerServer) {
    val idx = s * newLanesPerServer + j
    val bridge = Module(new NewContinuationBridge(cfg, s))

    s_axi_newCont(idx) :=> bridge.io.from_master
    bridge.io.newContReq :=> servers(s).io.newContInput(j).req
    bridge.io.assignedId := servers(s).io.newContInput(j).assignedId
    bridge.io.assignedLane := servers(s).io.newContInput(j).assignedLane
    bridge.io.continuationOut :=> m_continuation(idx)
  }

  // ---- Update path (replaces the argOut ROB + old notifier network) ----------
  private val updateRingNodes = Seq.tabulate(nServers) { s =>
    Module(
      new ArgumentRingNode(
        new RoutedContinuationUpdate(cfg),
        (u: RoutedContinuationUpdate) => u.upd.metadata.server === s.U
      )
    )
  }

  for (s <- 0 until nServers) {
    val wrongInputs = Wire(
      Vec(updateLanesPerServer, Decoupled(new RoutedContinuationUpdate(cfg)))
    )
    val wrongOut = Wire(Decoupled(new RoutedContinuationUpdate(cfg)))
    if (updateLanesPerServer == 1) {
      wrongInputs(0) :=> wrongOut
    } else {
      val wrongArb = Module(
        new elastic.BasicArbiter(
          new RoutedContinuationUpdate(cfg),
          updateLanesPerServer,
          chooserFn = elastic.Chooser.rr
        )
      )
      wrongArb.io.select.deq()
      wrongInputs.zip(wrongArb.io.sources).foreach { case (in, source) =>
        in :=> source
      }
      wrongArb.io.sink :=> wrongOut
    }

    for (j <- 0 until updateLanesPerServer) {
      val idx = s * updateLanesPerServer + j

      // Screen: matching server # goes straight into this server's direct
      // lane; anything else is split off into the redirect ring.
      val out = s_update(idx)
      val direct = servers(s).io.contUpdateInput(j)
      val wrong = wrongInputs(j)
      val isLocal = out.bits.metadata.server === s.U

      direct.valid := out.valid && isLocal
      direct.bits := out.bits
      wrong.valid := out.valid && !isLocal
      wrong.bits.upd := out.bits
      // AXIS/HLS payload bits are don't-care while valid is low. In particular,
      // the PE may drive metadata.server as X in RTL simulation while idle. Do
      // not let that invalid routing key contaminate TREADY (and observational
      // taps such as the watcher): an idle Decoupled input can always advertise
      // ready. Valid packets still select exactly the same local/redirect sink.
      out.ready := !out.valid || Mux(isLocal, direct.ready, wrong.ready)
    }

    elastic.SourceBuffer(wrongOut, ringInjectQueueDepth) :=>
      updateRingNodes(s).io.inject

    // Ring tap feeds the server's extra (last) update lane.
    new elastic.Transform(
      elastic.SourceBuffer(updateRingNodes(s).io.tap, 2),
      servers(s).io.contUpdateInput(updateLanesPerServer)
    ) {
      protected def onTransform: Unit = {
        out := in.upd
      }
    }
  }

  connectRing(updateRingNodes)

  // ---- Coupled slow path -> gaters -------------------------------------------
  private val coupledType = new CoupledSlowPathEntry(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneWidth
  )
  private val evictType = new TaggedEvictedContinuation(
    lineAddressWidth,
    continuationSize,
    serverTagWidth,
    serverIDWidth,
    laneWidth
  )
  private val slowType = new SlowUpdate(lineAddressWidth, continuationSize)

  // Address -> eviction-saver-lane function. MUST be the same function the
  // eviction network below uses to physically route a write, so each gater's
  // per-lane completion fence (EvictionGater) matches the lane order that
  // actually retires. Not tied to the slow-handler count/demux: they are
  // independent, each with its own demux (see slowNetwork below).
  private val evictSaverWidth = math.max(1, log2Ceil(nEvictionSavers))
  private def evictionSaverOf(address: UInt): UInt =
    if (nEvictionSavers == 1) 0.U(evictSaverWidth.W)
    else (address % nEvictionSavers.U)(evictSaverWidth - 1, 0)

  val evictionGaters = Seq.fill(nServers)(
    Module(
      new EvictionGater(
        lineAddressWidth,
        continuationSize,
        serverTagWidth,
        serverIDWidth,
        laneWidth,
        nEvictionSaverLanes = nEvictionSavers,
        saverOf = evictionSaverOf,
        slowRequestDepth = slowRequestQueueDepth,
        counterWidth = 64
      )
    )
  )

  for (s <- 0 until nServers) {
    val selected = Wire(Decoupled(coupledType))
    if (newLanesPerServer == 1) {
      servers(s).io.coupledSlowPath(0) :=> selected
    } else {
      val arb = Module(
        new elastic.BasicArbiter(
          coupledType,
          newLanesPerServer,
          chooserFn = elastic.Chooser.rr
        )
      )
      arb.io.select.deq()
      servers(s).io.coupledSlowPath.zip(arb.io.sources).foreach {
        case (output, input) => output :=> input
      }
      arb.io.sink :=> selected
    }
    selected :=> evictionGaters(s).io.coupledIn
  }

  for (s <- 0 until nServers; lane <- 0 until newLanesPerServer) {
    val spawn = servers(s).io.spawnTaskOutputs(lane)
    watcherFastSpawns(s * newLanesPerServer + lane) :=
      Cat(spawn.ready, spawn.valid)
  }

  // ---- Eviction network -> CacheEvictionSavers -------------------------------
  // Order-preserving cut/demux network (same topology as the slow-update
  // network below), NOT a ring: a ring lets a blocked entry recirculate a
  // full lap while a later same-source entry slips through on a free lap,
  // reordering that source's evictions -- which silently breaks the gater's
  // completion fence (it assumes each lane's B responses retire in the order
  // its evictions were accepted). A backpressured merge preserves per-source
  // order into each sink, matching what EvictionGater's per-lane fence
  // requires.

  val evictionSavers = Seq.tabulate(nEvictionSavers) { k =>
    val saver = Module(
      new CacheEvictionSaver(
        realAddressWidth,
        lineAddressWidth,
        lineShift,
        continuationSize,
        serverTagWidth,
        serverIDWidth,
        laneWidth
      )
    )
    saver.m_axi :=> m_axi_evict(k)
    saver
  }

  private val evictNetwork = Module(
    new ArgumentCutDemuxNetwork(
      evictType,
      nServers,
      nEvictionSavers,
      evictCutCount,
      (e: TaggedEvictedContinuation) => evictionSaverOf(e.eviction.address)
    )
  )
  for (s <- 0 until nServers) {
    evictionGaters(s).io.evictionOut :=> evictNetwork.io.sources(s)
  }
  evictNetwork.io.sinks.zip(evictionSavers).foreach { case (source, saver) =>
    source :=> saver.io.evictionIn
  }

  // ---- SlowArgumentHandlers --------------------------------------------------
  val slowHandlers = Seq.tabulate(nSlowHandlers) { k =>
    val handler = Module(
      new SlowArgumentHandler(
        counterWidth,
        realAddressWidth,
        lineAddressWidth,
        lineShift,
        continuationSize,
        axiIdWidth = slowAxiIdWidth
      )
    )
    handler.m_axi :=> m_axi_slow(k)
    handler
  }

  // Each server gater contributes one source. Sources are split into cutCount
  // collection lines; each line ends in one address demux, exactly like the
  // old ArgumentNotifierNetwork.
  private val slowNetwork = Module(
    new ArgumentCutDemuxNetwork(
      slowType,
      nServers,
      nSlowHandlers,
      slowCutCount,
      (update: SlowUpdate) =>
        if (nSlowHandlers == 1) 0.U else update.address % nSlowHandlers.U
    )
  )
  for (s <- 0 until nServers) {
    evictionGaters(s).io.slowUpdateOut :=> slowNetwork.io.sources(s)
  }
  slowNetwork.io.sinks.zip(slowHandlers).foreach { case (source, handler) =>
    source :=> handler.io.slowUpdateIn
  }

  for (k <- 0 until nSlowHandlers) {
    watcherSlowUpdates(k) := Cat(
      slowHandlers(k).io.slowUpdateIn.ready,
      slowHandlers(k).io.slowUpdateIn.valid
    )
  }
  for (k <- 0 until nEvictionSavers) {
    watcherEvictions(k) := Cat(
      evictionSavers(k).io.evictionIn.ready,
      evictionSavers(k).io.evictionIn.valid
    )
  }

  // Saver B responses return the compact key to the gater belonging to the
  // originating server, on the writeCompleted(saverIndex) lane matching the
  // saver that produced it. No arbitration needed: a completion belongs to
  // exactly one (server, lane) pair, and every gater lane is always-ready
  // (see EvictionGater), so this path can never contend with or be blocked
  // by the coupled FIFO.
  for (saverIndex <- 0 until nEvictionSavers) {
    val saver = evictionSavers(saverIndex)
    val owner = saver.io.writeCompleted.bits.server
    saver.io.writeCompleted.ready := true.B
    for (serverIndex <- 0 until nServers) {
      evictionGaters(serverIndex).io.writeCompleted(saverIndex).valid :=
        saver.io.writeCompleted.valid && owner === serverIndex.U
      evictionGaters(serverIndex).io.writeCompleted(saverIndex).bits :=
        saver.io.writeCompleted.bits
    }
  }

  // ---- Spawn path (replaces the old notifier-to-spawner connection) ----------
  private val doneReg = RegInit(false.B)
  done := doneReg

  for (s <- 0 until nServers) {
    for (j <- 0 until newLanesPerServer) {
      val leg = s * newLanesPerServer + j
      val adapter = Module(new SpawnLaneAdapter(continuationSize))
      val sp = servers(s).io.spawnTaskOutputs(j)
      new elastic.Transform(sp, adapter.io.spawnIn) {
        protected def onTransform: Unit = {
          out := in.taskData
        }
      }
      when(sp.fire) { doneReg := true.B }
      adapter.io.connStealNtw <> connStealNtw(leg)
    }
  }

  for (k <- 0 until nSlowHandlers) {
    val adapter = Module(new SpawnLaneAdapter(continuationSize))
    slowHandlers(k).io.spawnOut :=> adapter.io.spawnIn
    when(slowHandlers(k).io.spawnOut.fire) { doneReg := true.B }
    adapter.io.connStealNtw <> connStealNtw(nFastSpawnLegs + k)
  }

  // ---- Ring plumbing helpers --------------------------------------------------
  private def connectRing[T <: Data](nodes: Seq[ArgumentRingNode[T]]): Unit = {
    val n = nodes.size
    for (i <- 0 until n) {
      val prev = nodes((i + n - 1) % n)
      nodes(i).io.dataIn := prev.io.dataOut
      nodes(i).io.validIn := prev.io.validOut
    }
  }

}

object ArgumentNetworksEmitter extends App {
  import _root_.circt.stage.ChiselStage

  ChiselStage.emitSystemVerilogFile(
    new ArgumentNetworks(
      ArgumentNetworksConfig(
        nServers = 2,
        newLanesPerServer = 4,
        updateLanesPerServer = 4,
        nSlowHandlers = 1,
        nEvictionSavers = 1,
        counterWidth = 8,
        sysAddressWidth = 64,
        realAddressWidth = 34,
        serverIDWidth = 6,
        continuationSize = 256,
        updateDataWidth = 32
      )
    ),
    Array(
      "--target-dir=output/newArgumentNotifier/"
    ),
    Array("--disable-all-randomization")
  )
}
