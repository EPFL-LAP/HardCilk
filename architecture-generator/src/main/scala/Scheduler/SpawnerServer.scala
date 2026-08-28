package Scheduler

import chisel3._
import chisel3.util._
import Util._

import chext.amba.axi4s
import chext.amba.axi4
import chext.elastic
import axi4s.Casts._
import axi4.lite.components.RegisterBlock
import axi4.Ops._

class SpawnerServerIO(taskWidth: Int) extends Bundle {

  val connNetwork_slave = Flipped(
    new SchedulerNetworkClientIO(taskWidth)
  ) // Connection to the stealing Network
  val connNetwork_master = Flipped(
    new SchedulerNetworkClientIO(taskWidth)
  ) // Connection to the stealing Network

  // Push a task through a downstream node's soft stop on the PE-local ring: our queue is at
  // capacity. It can only ever fill a slot that was genuinely free -- the hard "I am full" stop is
  // never overridable, so nothing can be dropped by it.
  val forceInject = Output(Bool())
}

// The spawner floods its PE-local ring.
//
// Injection is not gated on a steal request consumed from that ring. That credit is a pure momentum
// counter (SchedulerClient: +1 per pop, -1 per send) and it equilibrates at exactly 1 with no
// reserve, so a single lost pop drops it to zero, skips one request, and nothing in the protocol
// re-arms it. Flooding removes the credit from the delivery path entirely, and costs almost nothing
// in steady state: the healthy spawner nodes already inject every cycle, and no task travels past
// its co-located PE because the client consumes each injection combinationally. The ring is eight
// point-to-point links; this just stops asking permission to use them.
//
// The steal ring is not removed, only demoted: one request is still consumed per delivered task so
// the ledger the clients keep stays exact, we simply never wait for one.
class SpawnerServer(
    taskWidth: Int,
    queueDepth: Int = 16
) extends Module {

  val io = IO(new SpawnerServerIO(taskWidth))

  // Local queue to absorb bumps between the outside source and the PE ring.
  val taskQueue = Module(
    new Queue(UInt(taskWidth.W), queueDepth)
  )

  val atCapacity = taskQueue.io.count >= (taskQueue.io.entries - 1).U
  io.forceInject := atCapacity

  // --------------------------------------------------------------------------
  // PUSH ONTO THE PE-LOCAL RING
  // --------------------------------------------------------------------------
  //
  // Offer whenever we hold a task. Arbitration for the slot is the ring's job: the PE-local ring is
  // elastic, so asserting qOutTask.valid IS the request for a hole -- the hop feeds it into stopOut
  // and backpressures upstream until our slot frees. A scheduler server, a client offloading surplus
  // and a peer spawner all contend on equal terms.
  val offeringToPE = taskQueue.io.deq.valid

  io.connNetwork_master.data.qOutTask.valid := offeringToPE
  io.connNetwork_master.data.qOutTask.bits := taskQueue.io.deq.bits
  val pushedTask =
    offeringToPE && io.connNetwork_master.data.qOutTask.ready

  // --------------------------------------------------------------------------
  // INTAKE FROM THE OUTSIDE RING
  // --------------------------------------------------------------------------
  //
  // Below half full we take everything. Above it we keep taking only while we are actually draining
  // into our PE, or while nobody else has asked -- otherwise we decline and let the task continue to
  // the peer whose request is riding the ctrl ring.
  //
  // The half-full floor is load-bearing, not a tuning knob. Without it the rule reads "take it if we
  // are pushing or nobody asked", and an empty spawner has nothing to push, so intake collapses to
  // "nobody asked": a cold lane declines every task, never acquires one to push, and stays cold.
  // Always accepting below the watermark makes that trap unreachable -- by the time the rule can
  // decline anything we are holding half a queue and certainly pushing.
  val peerDemand = io.connNetwork_slave.ctrl.serveStealReq.ready
  val spillWatermark = taskQueue.io.entries / 2
  val hasSurplus = taskQueue.io.count >= spillWatermark.U

  // Only ever decline when a peer request is actually present, so a declined task always has a
  // requester waiting downstream. A task is never shed into a ring nobody is draining.
  //
  // "Draining" is a task actually LANDING this cycle, on EITHER ring -- the peer hand-off counts.
  // Keying this on the PE ring alone gives II=2 at the watermark: sitting at half full we would
  // decline intake and hand one sideways, drop below the watermark, accept on the next cycle and
  // climb back, with accept and share strictly alternating.
  //
  // enq and deq are independent ports on the queue, so eating and sharing in the same cycle costs
  // nothing: at the watermark we take one in and hand one out every cycle, occupancy holds, and both
  // sides run at II=1.
  val drainingThisCycle = Wire(Bool())
  // The intake gate uses its own "draining" term, and the difference is not cosmetic.
  // drainingThisCycle counts handingToPeer, which is gated on the outside ring's qOutTask.ready. An
  // elastic hop derives that ready from availableTask.ready -- a hop that is emptying is injectable
  // now, not a cycle later -- so feeding it back into availableTask.ready here would close a
  // combinational cycle straight through the hop.
  //
  // Offering to a peer is enough for this gate. Intake stays bounded by taskQueue.io.enq.ready, so
  // treating an offer the ring did not accept as a drain can only make us keep a task we could have
  // passed on, never overflow, while still taking one in and handing one out on the same cycle at
  // the watermark.
  val drainingForIntake = Wire(Bool())
  val letFlowBy = peerDemand && hasSurplus && !drainingForIntake

  taskQueue.io.enq.valid :=
    io.connNetwork_slave.data.availableTask.valid && !letFlowBy
  taskQueue.io.enq.bits := io.connNetwork_slave.data.availableTask.bits
  io.connNetwork_slave.data.availableTask.ready :=
    taskQueue.io.enq.ready && !letFlowBy

  // --------------------------------------------------------------------------
  // ACCOUNTING
  // --------------------------------------------------------------------------
  //
  // One steal request cancelled per task that actually lands on the ring. Delivery is never gated on
  // holding a token -- the push happens whether or not a request is resident -- so the ledger must
  // not decide the rate. It does have to balance eventually, which is what the debt counter buys.
  //
  // A push on a cycle with no resident request still puts a task into a client's queue, and that
  // task still fills a slot some outstanding request had reserved. Cancelling nothing for it leaves
  // that request on the ctrl ring with nothing left to satisfy it: a spawner will later serve it and
  // emit another task for a slot that is now occupied, so the strandings pile up on both rings.
  //
  // The clients are the only producers on this ring and this is the only consumer, so under-
  // consuming silts it up. Resident tokens then promise space that does not exist, which pins
  // ntwReqArriving high at the scheduler server and parks its contention sampler in the
  // (request && occupancy) dead zone, so networkCongested never asserts and the absorb-to-HBM relief
  // valve stays disabled.
  //
  // So: owe a cancellation for every task delivered without one, and hold serveStealReq asserted
  // until the backlog is paid off. Cancelling without delivering is exactly right -- the task for
  // that request was already handed over earlier, so the requester is not starved by it.
  val requestDebt = RegInit(0.U(32.W))
  val cancelledRequest =
    io.connNetwork_master.ctrl.serveStealReq.valid &&
      io.connNetwork_master.ctrl.serveStealReq.ready
  io.connNetwork_master.ctrl.serveStealReq.valid := pushedTask || requestDebt =/= 0.U

  when(pushedTask && !cancelledRequest) {
    requestDebt := requestDebt + 1.U
  }.elsewhen(!pushedTask && cancelledRequest) {
    requestDebt := requestDebt - 1.U
  }

  io.connNetwork_master.data.availableTask.ready := false.B
  io.connNetwork_master.ctrl.stealReq.valid := false.B

  // --------------------------------------------------------------------------
  // LATERAL HAND-OFF TO A PEER SPAWNER
  // --------------------------------------------------------------------------
  //
  // The hand-off lives here rather than in BufferServerInput so the outside ring's steal-request
  // accounting stays in one place: one module decides to give a task away and consumes the request
  // that authorized it, on the same cycle, from the same state. BufferServerInput drives its ring
  // injection straight off ours, so this is the only injector at a paired node -- without it the
  // outside data ring has no producer at all.
  //
  //   peerDemand   -- somebody actually asked; we never shed into a ring nobody is draining
  //   !pushedTask  -- our own PE outranks a peer on every cycle it can physically take the task
  //   sharingMode  -- we fill ourselves to half before we start giving anything away
  //
  // The watermark is the same one the intake rule uses, and it means the same thing on both sides:
  // one or two tasks in flight is not surplus, it is the pipeline.
  //
  // Hysteretic, not a bare threshold: arm at half full, disarm only once drained to empty. A bare
  // threshold leaves an imbalance permanent wherever the work is a closed loop per lane
  // (countDecoupled is exactly that: initiator_i -> memReader_i -> adder_i -> initiator_i). A lane
  // running at II=1 pushes every cycle, so `!pushedTask` blocks its hand-off and it never has
  // surplus to give, while a lane that came out of seeding short can ask forever and nobody can
  // answer. Draining to empty before re-arming flushes work down the ring during seeding and evens
  // the lanes out.
  //
  // The latch cannot starve the co-located PE: `pushedTask` means the PE ring genuinely took the
  // task, so the local PE wins every cycle it physically can and the latch only ever acts on cycles
  // it could not.
  val sharingMode = RegInit(false.B)
  when(hasSurplus) {
    sharingMode := true.B
  }.elsewhen(taskQueue.io.count === 0.U) {
    sharingMode := false.B
  }

  val canServePeer =
    peerDemand && !pushedTask && taskQueue.io.deq.valid && sharingMode
  val handingToPeer =
    canServePeer && io.connNetwork_slave.data.qOutTask.ready

  drainingThisCycle := pushedTask || handingToPeer
  // Same signal minus the outside ring's ready, so the intake gate stays clear
  // of the elastic hop's ready-from-ready path. See drainingForIntake above.
  drainingForIntake := pushedTask || canServePeer

  io.connNetwork_slave.data.qOutTask.valid := canServePeer
  io.connNetwork_slave.data.qOutTask.bits := taskQueue.io.deq.bits
  // Consume the peer's request only when the task actually entered the ring, so a request is never
  // destroyed without a matching delivery.
  io.connNetwork_slave.ctrl.serveStealReq.valid := handingToPeer

  // --------------------------------------------------------------------------
  // OUTSIDE-RING SUPPLY REQUESTS (unchanged)
  // --------------------------------------------------------------------------
  //
  // How many more steal requests we still want to put on the outside ring, one per slot we intend
  // to fill: -1 when we send one, +1 when a task leaves our queue. A task ARRIVING is deliberately
  // net zero -- the slot moves from "task on the ring" into our queue and both sides of the
  // conservation sum drop together. Supply from the co-located source is already paid for, because
  // that handshake asserts our stealReq.ready (BufferServerInput.locallyServedRequest) and so
  // spends a request here.
  val desiredSteals = RegInit((queueDepth - 1).S(32.W))
  io.connNetwork_slave.ctrl.stealReq.valid := desiredSteals > 0.S
  val stealReqSent =
    io.connNetwork_slave.ctrl.stealReq.valid && io.connNetwork_slave.ctrl.stealReq.ready

  // Pop for whichever side actually took the task. Mutually exclusive by construction:
  // canServePeer is gated on !pushedTask.
  taskQueue.io.deq.ready := drainingThisCycle

  desiredSteals := desiredSteals +
    Mux(drainingThisCycle, 1.S(32.W), 0.S(32.W)) -
    Mux(stealReqSent, 1.S(32.W), 0.S(32.W))
}

object SpawnerServerEmitter extends App {
  emitVerilog(new SpawnerServer(256), Array("--target-dir", "output"))
}
