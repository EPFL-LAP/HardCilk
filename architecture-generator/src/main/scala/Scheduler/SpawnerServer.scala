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

  // Push a task through a downstream node's soft stop on the PE-local ring. Exactly the old force
  // condition -- our queue is at capacity -- now expressed against the ring rather than against a
  // steal token. It can only ever fill a slot that was genuinely free: the hard "I am full" stop is
  // never overridable, so nothing can be dropped by it.
  val forceInject = Output(Bool())
}

// The spawner floods its PE-local ring.
//
// It used to gate every injection on a steal request consumed from that ring, which made the
// delivery rate to the co-located PE equal to the PE client's steal-credit rate. That credit is a
// pure momentum counter (SchedulerClient: +1 per pop, -1 per send) and it equilibrates at exactly 1
// with no reserve, so a single lost pop drops it to zero, skips one request, and -- after the
// 5-cycle request/task round trip -- costs another pop. VCD hw_emu, countDecoupled memReader PE4:
// that hole circulated with a period of exactly 5 for the whole run, pinning the PE at 4/5 = 80%
// while PEs 0-3 sat at 1.00, and nothing in the protocol could re-arm it.
//
// Flooding removes the credit from the delivery path entirely. It is also barely a change in
// steady state: measured ring data-slot occupancy on the same run was 1.000 at the healthy spawner
// nodes (they already inject every cycle) and 0.000 at every PE node (no task has ever travelled
// past its co-located PE -- the client consumes each injection combinationally). The ring is
// already eight point-to-point links; this just stops asking permission to use them.
//
// The steal ring is not removed, only demoted: we still consume one request per delivered task so
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
  // Offer whenever we hold a task. Arbitration for the slot is the ring's job now: the PE-local
  // ring is elastic, so asserting qOutTask.valid IS the request for a hole -- the hop feeds it into
  // stopOut and backpressures upstream until our slot frees. That replaces the point-to-point
  // schedulerWantsSlot wire, and it generalises: a scheduler server, a client offloading surplus
  // and a peer spawner all contend on equal terms, instead of only the one adjacent pair.
  val offeringToPE = taskQueue.io.deq.valid

  io.connNetwork_master.data.qOutTask.valid := offeringToPE
  io.connNetwork_master.data.qOutTask.bits := taskQueue.io.deq.bits
  val pushedTask =
    offeringToPE && io.connNetwork_master.data.qOutTask.ready

  // --------------------------------------------------------------------------
  // INTAKE FROM THE OUTSIDE RING
  // --------------------------------------------------------------------------
  //
  // Below half full we take everything. Above it we keep taking only while we are actually
  // draining into our PE, or while nobody else has asked -- otherwise we decline and let the task
  // continue to the peer whose request is riding the ctrl ring.
  //
  // The half-full floor is load-bearing, not a tuning knob. Without it the rule reads "take it if
  // we are pushing or nobody asked", and an empty spawner has nothing to push, so intake collapses
  // to "nobody asked". Measured peer demand at a spawner node is 0.70-0.90 in steady state and was
  // 1.00 for the entire 1150-cycle window before spawner 4 came up: a cold lane would decline every
  // task, never acquire one to push, and stay cold. That is the PE4 starvation reproduced one level
  // out. Always accepting below the watermark makes the trap unreachable -- by the time the rule
  // can decline anything we are holding half a queue and certainly pushing.
  val peerDemand = io.connNetwork_slave.ctrl.serveStealReq.ready
  val spillWatermark = taskQueue.io.entries / 2
  val hasSurplus = taskQueue.io.count >= spillWatermark.U

  // Only ever decline when a peer request is actually present, so a declined task always has a
  // requester waiting downstream. A task is never shed into a ring nobody is draining.
  //
  // "Draining" is a task actually LANDING this cycle, on EITHER ring -- and the peer hand-off
  // counts. Keying this on the PE ring alone is how you get II=2 at the watermark: sitting at half
  // full we would decline intake and hand one sideways, dropping below the watermark, then accept
  // on the next cycle and climb back, then decline again -- accept and share strictly alternating
  // at one task every other cycle. Exactly the failure the old `enq := availableTask.valid &&
  // !canServePeer` gate had, rebuilt out of different parts.
  //
  // enq and deq are independent ports on the queue, so eating and sharing in the same cycle costs
  // nothing: at the watermark we take one in and hand one out every cycle, occupancy holds, and
  // both sides run at II=1.
  val drainingThisCycle = Wire(Bool())
  // The intake gate uses its own "draining" term, and the difference is not
  // cosmetic. drainingThisCycle counts handingToPeer, which is gated on the
  // outside ring's qOutTask.ready -- fine while that ring was rigid, because a
  // rigid hop drives qOutTask.ready from validIn alone. An ELASTIC hop derives
  // it from availableTask.ready instead (a hop that is emptying is injectable
  // now, not a cycle later), so feeding that ready back into availableTask.ready
  // here closes a combinational cycle straight through the hop.
  //
  // Offering to a peer is enough for this gate. Intake stays bounded by
  // taskQueue.io.enq.ready, so treating an offer the ring did not accept as a
  // drain can only make us keep a task we could have passed on -- never
  // overflow. It also preserves what drainingThisCycle was introduced for: at
  // the watermark we still take one in and hand one out on the same cycle
  // rather than alternating at II=2.
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
  // One steal request cancelled per task that actually lands on the ring. Delivery is still never
  // gated on holding a token -- the push happens whether or not a request is resident -- so the
  // ledger must not decide the rate. But it DOES have to balance eventually, which is what this
  // debt counter buys.
  //
  // A push on a cycle with no resident request still puts a task into a client's queue, and that
  // task still fills a slot some outstanding request had reserved. Cancelling nothing for it leaves
  // that request on the ctrl ring with nothing left to satisfy it: a spawner will later serve it and
  // emit ANOTHER task for a slot that is now occupied, so the strandings pile up on both rings.
  //
  // The clients are the only producers on this ring and this is the only consumer, so if we under-
  // consume, the ring silts up. Measured on the QuestaSim countDecoupled repro before this fix:
  // 31 of 34 token slots resident, every hop asserting hasRequestOut, while all eight clients sat at
  // count == minLengthThresh with desiredSteals == 0 -- 31 promises of space against zero free
  // space. That pins ntwReqArriving high at the scheduler server, parking its contention sampler in
  // the (request && occupancy) dead zone so networkCongested never asserts, which disables the
  // absorb-to-HBM relief valve and turns a transient PE stall into a permanent deadlock.
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
  // What went is the sharingMode LATCH, not the hand-off. That register armed at half full and
  // disarmed only at empty, so a lane that once backed up kept shedding for the rest of the run:
  // measured on countDecoupled spawner 4 it was high on 100% of cycles, pinned the queue at exactly
  // 6, and so parked it one below atCapacity (7) where the force path could never fire. It handed
  // 76 of every 100 arriving tasks sideways while its own PE sat idle.
  //
  // The hand-off itself stays, and stays HERE rather than moving into BufferServerInput, because
  // the outside ring's steal-request accounting is then all in one place: one module decides to
  // give a task away and consumes the request that authorized it, on the same cycle, from the same
  // state. BufferServerInput drives its ring injection straight off ours, so this is the only
  // injector at a paired node -- without it the outside data ring has no producer at all.
  //
  // Three live conditions, no state:
  //
  //   peerDemand      -- somebody actually asked; we never shed into a ring nobody is draining
  //   !pushedTask     -- our own PE outranks a peer on every cycle it can physically take the task
  //   hasSurplus      -- we fill ourselves to half before we start giving anything away
  //
  // The watermark is the same one the intake rule uses, and it means the same thing on both sides:
  // one or two tasks in flight is not surplus, it is the pipeline. Below half full we aggressively
  // keep everything for our own PE; at or above it we start forwarding and letting flow by.
  //
  // What this deliberately does NOT do is chase the last entries/2 - 1 tasks. A bare threshold
  // strands that residual whenever the local PE cannot take it, and sharingMode's hysteresis
  // existed to rescue it -- at the cost of arming at half full and disarming only at empty, so a
  // lane that once backed up kept shedding for the rest of the run (countDecoupled spawner 4: high
  // on 100% of cycles, queue pinned at exactly 6, one below atCapacity where force could have
  // fired, handing 76 of every 100 arriving tasks sideways while its own PE sat idle). The residual
  // is not lost and it is not deadlocked: it drains to the local PE as soon as the inner ring frees
  // a slot, which under flooding is essentially every cycle. Holding a few tasks briefly is the
  // cheaper trade.
  // Hysteretic, not a bare threshold: arm at half full, disarm only once drained to empty.
  //
  // A bare threshold leaves an imbalance permanent wherever the work is a closed loop per lane
  // (countDecoupled is exactly that: initiator_i -> memReader_i -> adder_i -> initiator_i). A lane
  // running at II=1 pushes every cycle, so `!pushedTask` blocks its hand-off and it never has
  // surplus to give; a lane that came out of seeding short can ask forever and nobody can answer.
  // Measured on the feedback ring: lanes 0-6 pinned at exactly 250/250 per window while lane 7 sat
  // at 166/250 -- a stable 2/3, not a ramp -- because its loop held ~7 tasks instead of 10 and
  // nothing could rebalance it. Draining to empty before re-arming is what flushes work down the
  // ring during seeding and evens the lanes out.
  //
  // This is the latch that used to starve the co-located PE, and it is safe now for a specific
  // reason: back then the local-delivery term was `deliveringToPE`, gated on a CONSUMED STEAL
  // REQUEST, so a PE that had merely run out of credits for a cycle looked idle and lost its work
  // sideways -- spawner 4 shed 76 of every 100 arriving tasks while its own PE sat idle. Flooding
  // removes the credit: `pushedTask` now means the PE ring genuinely took the task, so the local PE
  // wins every cycle it physically can and the latch only ever acts on cycles it could not.
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
