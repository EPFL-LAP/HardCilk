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
  // door. Deliberately not reqTaskOut, which is gated on being able to move and so reads zero
  // exactly when the ring is jammed or the reader is backpressuring.
  val hasRequestOut = Output(Bool())

  // Connections to steal server
  val connSS = new SchedulerNetworkClientRequest
}

/** One hop of the request ring.
  *
  * This hop is elastic, and the reason is GUARANTEED LOCAL INJECTION -- not hole preservation on
  * the data ring. That was the job of the `bubbleIn`/`bubbleOut` sideband, which is gone: the data
  * ring is elastic now and an injector makes its own hole, so nothing needs a matching request-free
  * cycle replayed at the next hop.
  *
  * The two are easy to conflate, so spell out why the rest has to stay. A plain one-bit
  * always-flowing hop conserves requests perfectly well -- they carry no payload, so a hop holding
  * one can refuse a second while still forwarding the one it has, and the count is kept. What it
  * cannot do is guarantee a node ever gets its OWN request onto the ring: `stealReq.ready` is then
  * just "my hop is empty", so a node whose hop is busy with other nodes' requests passing through
  * is simply never able to inject. On a ring, the nodes furthest from the supply lose systematically.
  *
  * Measured on the BufferServerInput feedback ring (8 lanes, one seeded source): with the plain hop
  * the last lane ran 616 of 1000 cycles while lanes 0-6 sat at exactly 1000, and it never recovered
  * -- it could not get enough steal requests out to be served, and once the ring went quiet its
  * shortfall was frozen in. With this hop the same test reaches II=1 on all eight lanes. So
  * `stopOut` -- "a local request has priority over new upstream traffic" -- is load-bearing on its
  * own, independently of anything the data ring does.
  *
  * stopOut depends only on registered state and the local server, never on stopIn, so closing the
  * ring cannot create a combinational loop. The skid slot (a count of 2) absorbs the one request
  * that can already be in flight when this hop first backpressures its upstream neighbour.
  */
class SchedulerNetworkControlUnit extends Module {
  val io = IO(new SchedulerNetworkControlUnitIO)

  // One normal ring slot plus one one-bit skid slot.  Requests carry no
  // payload, so a two-bit occupancy counter is the entire elastic buffer.
  val requestCount = RegInit(0.U(2.W))
  val hasRequest = requestCount =/= 0.U

  // An existing request may either be consumed here or advance one hop.  A
  // local serve has priority over forwarding.
  val serveRequest = hasRequest && io.connSS.serveStealReq.valid

  // A local request has priority over new upstream traffic.  This is not a
  // ready chain: stopOut depends only on local/register state and the local
  // server, so closing the ring cannot create a combinational loop.
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
