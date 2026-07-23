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
  // A request consumed at the downstream hop creates a control-ring bubble.
  // Carry that fact through a register so an elastic request buffer cannot
  // hide it from this hop's local server.
  val bubbleIn = Input(Bool())
  val bubbleOut = Output(Bool())

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
  // local serve has priority over forwarding, matching the old control unit.
  // bubbleIn has priority over a resident request for exactly one cycle.  The
  // resident request may continue around the control ring, but the local
  // server must observe a request-free cycle.  For a VAS this is precisely the
  // cycle in which its spawner leaves the matching data-ring hole untouched.
  val serveRequest =
    hasRequest && io.connSS.serveStealReq.valid && !io.bubbleIn

  // A local request has priority over new upstream traffic.  This is not a
  // ready chain: stopOut depends only on local/register state and the local
  // server, so closing the ring cannot create a combinational loop.  The skid
  // slot absorbs the one request that can already be in flight when registered
  // state at this hop first backpressures its upstream neighbour.
  io.stopOut := requestCount === 2.U || io.connSS.stealReq.valid

  val forwardRequest = hasRequest && !serveRequest && !io.stopIn
  val removeRequest = serveRequest || forwardRequest
  io.reqTaskOut := forwardRequest
  io.connSS.serveStealReq.ready := hasRequest && !io.bubbleIn

  // Replay every locally consumed request as a registered bubble at the next
  // hop.  bubbleOut is register-only, while bubbleIn affects only this hop's
  // local arbitration, so this sideband cannot form a combinational ring.
  io.bubbleOut := RegNext(serveRequest, false.B)

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
