package Scheduler

import chisel3._
import Util._

// The interface connection for the stealing network ctrl unit.
class SchedulerNetworkControlUnitIO extends Bundle {
  // Connections to other network ctrl units
  val reqTaskIn = Input((Bool()))
  val reqTaskOut = Output(Bool())
  // Registered-ring backpressure.  stopIn comes from the downstream hop;
  // stopOut prevents the upstream hop from advancing into this one.
  val stopIn = Input(Bool())
  val stopOut = Output(Bool())
  // "I am holding a request." Tapped by a downstream scheduler node as the request WAITING at its
  // door.
  val hasRequestOut = Output(Bool())

  // Connections to steal server
  val connSS = new SchedulerNetworkClientRequest
}

class SchedulerNetworkControlUnit extends Module {
  val io = IO(new SchedulerNetworkControlUnitIO)

  // One normal ring slot plus one one-bit skid slot.  Requests carry no
  // payload, so a two-bit occupancy counter is the entire elastic buffer.
  val requestCount = RegInit(0.U(2.W))
  val hasRequest = requestCount =/= 0.U

  // An existing request may either be consumed here or advance one hop.  A
  // local serve has priority over forwarding.
  val serveRequest = hasRequest && io.connSS.serveStealReq.valid

  // A local request has priority over new upstream traffic.
  io.stopOut := requestCount === 2.U || io.connSS.stealReq.valid

  io.hasRequestOut := hasRequest
  val forwardRequest = hasRequest && !serveRequest && !io.stopIn
  val removeRequest = serveRequest || forwardRequest
  io.reqTaskOut := forwardRequest
  io.connSS.serveStealReq.ready := hasRequest

  // Upstream honours stopOut combinationally from registered/local state, so
  // reqTaskIn is zero while a local producer is asking.  A local request is
  // accepted only when all older ring requests resident at this hop leave;
  // the skid slot is never exposed as extra local ring capacity.
  val ringCountAfterMovement =
    requestCount - removeRequest.asUInt + io.reqTaskIn.asUInt
  io.connSS.stealReq.ready := ringCountAfterMovement === 0.U
  val acceptLocal =
    io.connSS.stealReq.valid && io.connSS.stealReq.ready

  val nextRequestCount = ringCountAfterMovement + acceptLocal.asUInt
  assert(nextRequestCount <= 2.U)
  requestCount := nextRequestCount
}
