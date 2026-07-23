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
}

class SpawnerServer(
    taskWidth: Int,
    queueDepth: Int = 16
) extends Module {

  val io = IO(new SpawnerServerIO(taskWidth))

  // Create the local tasks queue to absorb bumps in the ring. Somebody must ALWAYS be consuming,
  // so once this saturates we force-drain it even if nobody wants tasks
  val taskQueue = Module(
    new Queue(UInt(taskWidth.W), queueDepth)
  )

  // Try to fill the taskQueue

  // How many more steal requests we still want to put on the ring, one per slot we intend to fill.
  // Same ledger SchedulerClient keeps, for the same reason: it is a count of requests we WANT to
  // send, not of requests we believe are outstanding, so it is driven by events we can actually
  // observe locally.
  //
  //   -1 when we send a request        (that slot is now spoken for)
  //   +1 when a task leaves our queue  (that slot needs refilling)
  //
  // A task ARRIVING is deliberately net zero, requested or not: the slot simply moves from "task on
  // the ring" into our queue, and both sides of the conservation sum drop together.
  val desiredSteals = RegInit((queueDepth - 1).S(32.W))
  io.connNetwork_slave.ctrl.stealReq.valid := desiredSteals > 0.S
  val stealReqSent =
    io.connNetwork_slave.ctrl.stealReq.valid && io.connNetwork_slave.ctrl.stealReq.ready

  // Grab a task whenever there is space -- unconditionally (bar the one letFlowBy case below), even
  // on a cycle we are also handing one to a peer (canServePeer, driven with the drain logic below).
  // Enq and deq are independent queue ports, so accepting and shedding in the same cycle costs
  // nothing. This matters most for a co-located source: BufferServerInput presents its buffer task
  // here as availableTask, and that task can ONLY enter through this spawner. When our own PE is
  // busy (e.g. saturated by the scheduler server) we act as a pure relay for that source, and
  // gating intake on canServePeer forces the relay to alternate accept / hand-off -- capping the
  // source at II=2 for no benefit.
  //
  // For a task riding the outside ring, consume and inject do share one SchedulerNetworkDataUnit
  // slot, but that is already mutually exclusive by construction (availableTask.valid = validIn,
  // qOutTask.ready = ~validIn): if a task is passing we swallow it and cannot inject this cycle; the
  // peer's steal token is left untouched (serveStealReq is consumed only when qOutTask actually
  // fires), so we serve that peer out of our queue on the next clear slot. Swallow-and-share, not
  // let-pass -- the peer still gets served, one slot later, and conservation is unaffected because
  // an arrival never moves desiredSteals.
  //
  // letFlowBy is the sole let-pass exception. If swallowing this task would immediately force it
  // down our own PE ring (we are at capacity) AND a peer steal request is riding the ctrl ring,
  // decline the task rather than swallow it. The data and ctrl rings counter-rotate
  // (SchedulerNetwork.scala:39), so a request resident here originated downstream on the data ring:
  // an un-swallowed task flows straight to that requester and cancels its demand there -- a better
  // landing than forcing it onto our own, already-saturated, PE ring. We leave the request untouched
  // (an intermediate hungry hop may take the task first, so it must stay free to be served for its
  // true originator); the only cost is bounded, benign over-provisioning.
  val peerDemand = io.connNetwork_slave.ctrl.serveStealReq.ready
  val atCapacity = taskQueue.io.count >= (taskQueue.io.entries - 1).U
  val incomingTask = io.connNetwork_slave.data.availableTask.valid
  val letFlowBy = atCapacity && incomingTask && peerDemand

  val canServePeer = Wire(Bool())
  taskQueue.io.enq.valid := io.connNetwork_slave.data.availableTask.valid && !letFlowBy
  taskQueue.io.enq.bits := io.connNetwork_slave.data.availableTask.bits
  io.connNetwork_slave.data.availableTask.ready := taskQueue.io.enq.ready && !letFlowBy
  val pulledTask = taskQueue.io.enq.valid && taskQueue.io.enq.ready

  // Try to drain the taskQueue

  // Keep the task/request invariant in both directions:
  //
  //   requestBalance = consumed steal requests - tasks pushed onto the PE ring
  //
  // A positive balance is a promise: we consumed requests before their tasks could be pushed, so
  // that many queued tasks are RESERVED and may not be handed sideways.  A negative balance is the
  // converse: force pushed tasks before their matching requests arrived.  Those future requests
  // must be consumed without pushing another task, paying the force debt back by leaving a data
  // hole for the scheduler.  The old unsigned servedRequestCount could represent only the first
  // half and silently discarded a forced push at zero.
  val requestBalance = RegInit(0.S(33.W))
  val owePromise = requestBalance > 0.S
  val hasPrepaidTask = requestBalance < 0.S
  val unreservedTasks = requestBalance <= 0.S || taskQueue.io.count.zext > requestBalance

  // The two ctrl rings this module sits between. The names are easy to misread, so spell them out:
  //   connNetwork_master -> the PE-local steal network, i.e. OUR OWN PEs   (Scheduler.scala:213)
  //   connNetwork_slave  -> the outside-spawn ring, i.e. PEER spawners     (Scheduler.scala:158)
  val ourPeAsking = io.connNetwork_master.ctrl.serveStealReq.ready

  // Negative balance means a previously pushed task is waiting for its request, so consumption is
  // always legal.  Otherwise cap early request consumption at the number of queued tasks backing
  // those promises.  This is the same two-sided ledger used by GlobalTaskBuffer.
  io.connNetwork_master.ctrl.serveStealReq.valid := requestBalance < taskQueue.io.count.zext
  val servedRequest =
    io.connNetwork_master.ctrl.serveStealReq.valid && io.connNetwork_master.ctrl.serveStealReq.ready

  // If one of our PEs is asking and we have a task, it gets it. Full stop -- nothing may stand
  // between a request and a task we are holding.
  // Ordinary insertion is authorized only by a positive registered balance: we previously
  // consumed a request and therefore owe the PE ring a task.  A current request first updates the
  // ledger; it does not bypass it combinationally.  Force is the sole exception below.
  val promisedSendToPE = taskQueue.io.deq.valid && owePromise

  // The task only goes to our own PE if it actually lands on the ring this cycle. Gating on
  // ourPeAsking instead would stall us whenever a token merely passes while the data slot is
  // blocked: we would deliver nothing to our PE and still refuse to spill sideways.
  val deliveringToPE = promisedSendToPE && io.connNetwork_master.data.qOutTask.ready

  // If the data slot is blocked, consuming a request reserves one additional queued task.  Do not
  // simultaneously hand that same last unreserved task to a peer.  Surplus tasks may still spill,
  // and a request paying negative force debt creates no new reservation at all.
  val acceptingPromise = servedRequest && !hasPrepaidTask && !deliveringToPE
  val peerHasUnreservedTask = Mux(
    acceptingPromise,
    taskQueue.io.count.zext > requestBalance + 1.S,
    unreservedTasks
  )

  // If instead somebody else on the local network is starved, hand them the top of our queue rather
  // than forcing it down the PE steal ring. The only guard is that we are not already delivering to
  // our own PE this cycle, which outranks a peer.
  //
  // Deliberately NOT gated on occupancy. This queue is a handoff buffer, not a reservoir: the work
  // stealing proper happens on the PE-local network, and this ring exists so that more tasks can
  // reach that network at once, injected next to different PEs. Gating the handoff on saturation
  // would make it the exact complement of stealReq.valid below -- we would be either asking for
  // work or willing to pass it on, never both -- so a spawner holding fewer than entries-1 tasks
  // would sit on them indefinitely while peers starved.
  // unreservedTasks is the reservation: tasks already promised to accepted PE steal requests are
  // subtracted from what we are willing to give away, so a peer can only ever take a spare.
  canServePeer :=
    peerDemand &&
      !deliveringToPE &&
      taskQueue.io.deq.valid &&
      peerHasUnreservedTask

  // Only a handoff that actually lands may suppress the local drain. canServePeer says a peer is
  // starved and we are willing, but the outside ring slot can be occupied by a passing task, in
  // which case the injection does not happen -- and if that still blocked the force path we would
  // sit holding work for a peer we cannot reach. Symmetric with deliveringToPE on the PE side.
  val handingToPeer = canServePeer && io.connNetwork_slave.data.qOutTask.ready

  // And if nobody wants them, we force -- but ONLY to make room. At entries-1 with another task
  // already arriving we must pop so that task can come in; that is what keeps the river flowing,
  // and without it the upstream globalTaskBuffer and writeBuffer back up and spawning PEs get
  // backpressured.
  //
  // Forcing merely because we hold work would be wrong: with one source and several spawners all
  // asking, every one of them would dump its queue at a PE that never asked when only the spawner
  // actually under pressure needs to. So we wait until we can see the incoming task rather than
  // shedding pre-emptively. (This is why the data unit must report availableTask.valid from slot
  // occupancy alone -- otherwise the arriving task is invisible until we have already taken it.)
  // Force is purely a capacity escape and has no accounting guard.  The signed ledger records the
  // push, but may never prevent it: keeping the incoming task river flowing has priority.  The one
  // brake is letFlowBy: with a peer request on the ctrl ring we shed this task down the outside ring
  // to that requester instead of forcing it onto our own PE ring.
  val forceSend = taskQueue.io.deq.valid && atCapacity && incomingTask && !letFlowBy
  val sendToPE =
    promisedSendToPE || (forceSend && !handingToPeer)
  // Gate the injection so the two destinations stay mutually exclusive and the task can never be
  // offered to both rings at once.
  val pushToPE = sendToPE && !handingToPeer
  io.connNetwork_master.data.qOutTask.valid := pushToPE
  io.connNetwork_master.data.qOutTask.bits := taskQueue.io.deq.bits

  io.connNetwork_master.data.availableTask.ready := false.B
  io.connNetwork_master.ctrl.stealReq.valid := false.B
  val pushedTask =
    io.connNetwork_master.data.qOutTask.valid && io.connNetwork_master.data.qOutTask.ready
  when(servedRequest =/= pushedTask) {
    requestBalance :=
      requestBalance + servedRequest.asUInt.zext - pushedTask.asUInt.zext
  }

  // Every request consumed early remains backed by a real task in the queue.  Negative balances
  // need no queue backing: their tasks have already entered the PE ring.
  assert(requestBalance <= taskQueue.io.count.zext)

  io.connNetwork_slave.data.qOutTask.valid := canServePeer
  io.connNetwork_slave.data.qOutTask.bits := taskQueue.io.deq.bits
  // Only consume the peer's steal req if the task actually entered the network this cycle
  io.connNetwork_slave.ctrl.serveStealReq.valid :=
    io.connNetwork_slave.data.qOutTask.valid && io.connNetwork_slave.data.qOutTask.ready

  // Pop the queue for whichever side actually took the task (pushToPE and handingToPeer are
  // mutually exclusive by construction, so the task can never go to both)
  taskQueue.io.deq.ready := pushedTask || handingToPeer

  // Every task that leaves the queue frees a slot we want to refill; every request we send spends
  // one; an unrequested arrival fills one for free.
  val poppedTask = taskQueue.io.deq.valid && taskQueue.io.deq.ready
  // Taking a task off the ring does NOT move the counter -- see SchedulerClient for the
  // conservation argument. Supply from our co-located source is already paid for, because that
  // handshake asserts our stealReq.ready (locallyServedRequest) and so spends a request here.
  desiredSteals := desiredSteals +
    Mux(poppedTask, 1.S(32.W), 0.S(32.W)) -
    Mux(stealReqSent, 1.S(32.W), 0.S(32.W))
}

object SpawnerServerEmitter extends App {
  emitVerilog(new SpawnerServer(256), Array("--target-dir", "output"))
}
