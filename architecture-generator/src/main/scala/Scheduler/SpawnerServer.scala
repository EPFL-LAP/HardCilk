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

  val forceInject = Output(Bool())
}

// The spawner floods its PE-local ring.

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

  val peerDemand = io.connNetwork_slave.ctrl.serveStealReq.ready
  val spillWatermark = taskQueue.io.entries / 2
  val hasSurplus = taskQueue.io.count >= spillWatermark.U

  // Only ever decline when a peer request is actually present, so a declined task always has a
  // requester waiting downstream. A task is never shed into a ring nobody is draining.

  val drainingThisCycle = Wire(Bool())

  val drainingForIntake = Wire(Bool())
  val letFlowBy = peerDemand && hasSurplus && !drainingForIntake

  taskQueue.io.enq.valid :=
    io.connNetwork_slave.data.availableTask.valid && !letFlowBy
  taskQueue.io.enq.bits := io.connNetwork_slave.data.availableTask.bits
  io.connNetwork_slave.data.availableTask.ready :=
    taskQueue.io.enq.ready && !letFlowBy

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
