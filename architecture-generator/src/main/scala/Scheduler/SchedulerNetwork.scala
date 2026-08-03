package Scheduler

import chisel3._
import Scheduler._
import Util._

// The bundle connection for the stealing network interface.
class SchedulerNetworkIO(taskWidth: Int, tqNum: Int, vssCount: Int, elasticData: Boolean)
    extends Bundle {
  val connSS = Vec(tqNum, new SchedulerNetworkClientIO(taskWidth))
  // Observed at the DOOR of each scheduler node: what the upstream hop is HOLDING for it, on each
  // ring. A node's own injections land in its own slots, so they are invisible to it until they
  // have travelled all the way round -- which is the point at which a request genuinely means
  // somebody downstream has a free slot, rather than meaning "I just freed one myself".
  //
  // "Holding", not "handing over": the forwarding signals (validOut, reqTaskOut) are gated on being
  // able to move, so they read zero precisely when the ring is jammed or when this node is
  // backpressuring its upstream to make itself a hole. Tapping those made maximum congestion
  // indistinguishable from an idle ring (measured: congestion never detected at all).
  //
  // Each ring also reports whether it ADVANCED, and the two are kept separate. On an elastic ring a
  // sample per CYCLE measures how long an item dwelt rather than what the ring holds -- a stalled
  // hop is re-counted every cycle. Sampling per advance restores the original meaning, where a
  // window of ringLength samples is one rotation of distinct content. The rings counter-rotate and
  // stall independently, so they need independent windows; sharing one would age each ring's
  // history at the other's rate.
  val ntwDataUnitOccupancyVSS = Vec(vssCount, Output(Bool()))
  val ntwReqArrivingVSS = Vec(vssCount, Output(Bool()))
  // Per-node "the producer attached here wants to inject". Must come from the producer itself, not
  // from connSS(i).data.qOutTask.valid, wherever anything sits between the two that derives valid
  // from ready (SchedulerInjectionSwap does) -- see SchedulerNetworkDataUnit.injectWanted.
  val injectWanted = if (elasticData) Some(Vec(tqNum, Input(Bool()))) else None
  // Per-node "push through a soft stop this cycle". Only the spawners drive this, off their own
  // atCapacity; every other node ties it low.
  val forceForward = if (elasticData) Some(Vec(tqNum, Input(Bool()))) else None
}

// A class capable of generating a circular stealing network
// parametrizable by task Width and the number of Task Queues.
// NOTE: tqNum should take into account the existence of a virtual TQ.
class SchedulerNetwork(
    taskWidth: Int,
    tqNum: Int,
    vssIndicies: Array[Int],
    // Elastic data ring: hops can backpressure upstream so an injector can make itself a hole
    // instead of waiting for one. Needed wherever several injectors contend for the same ring --
    // the PE-local steal ring, where a flooding spawner would otherwise starve every injector
    // downstream of it. The outside-spawn ring has one injector per node and no such contention,
    // so it stays rigid and pays nothing.
    elasticData: Boolean = false
) extends Module {
  val io = IO(new SchedulerNetworkIO(taskWidth, tqNum, vssIndicies.size, elasticData))

  // Instantiate data units
  val dataUnits =
    Seq.fill(tqNum)(Module(new SchedulerNetworkDataUnit(taskWidth, elasticData)))
  // Instantiate ctrl units
  val ctrlunits = Seq.fill(tqNum)(Module(new SchedulerNetworkControlUnit))

  // Connecting the dataUnits ring
  for (i <- 1 until tqNum) {
    dataUnits(i).io.taskIn := dataUnits(i - 1).io.taskOut
    dataUnits(i).io.validIn := dataUnits(i - 1).io.validOut
    io.connSS(i).data <> dataUnits(i).io.connSS
  }
  // Closing the dataUnits rings.
  dataUnits(0).io.taskIn := dataUnits(tqNum - 1).io.taskOut
  dataUnits(0).io.validIn := dataUnits(tqNum - 1).io.validOut
  io.connSS(0).data <> dataUnits(0).io.connSS

  // Backpressure travels against the data, one hop: hop i tells hop i-1 to hold.
  if (elasticData) {
    def linkStop(upstream: Int, downstream: Int): Unit = {
      dataUnits(upstream).io.stopInFull.get := dataUnits(downstream).io.stopOutFull.get
      dataUnits(upstream).io.stopInWant.get := dataUnits(downstream).io.stopOutWant.get
    }
    for (i <- 1 until tqNum) linkStop(i - 1, i)
    linkStop(tqNum - 1, 0)
    for (i <- 0 until tqNum) {
      dataUnits(i).io.injectWanted.get := io.injectWanted.get(i)
      dataUnits(i).io.forceForward.get := io.forceForward.get(i)
    }
  }

  // Connecting the controlUnits ring in the opposite direction
  for (i <- 0 until tqNum - 1) {
    ctrlunits(i).io.reqTaskIn := ctrlunits(i + 1).io.reqTaskOut
    ctrlunits(i + 1).io.stopIn := ctrlunits(i).io.stopOut
    io.connSS(i).ctrl <> ctrlunits(i).io.connSS
  }
  // Closing the controlUnits rings.
  ctrlunits(tqNum - 1).io.reqTaskIn := ctrlunits(0).io.reqTaskOut
  ctrlunits(0).io.stopIn := ctrlunits(tqNum - 1).io.stopOut
  io.connSS(tqNum - 1).ctrl <> ctrlunits(tqNum - 1).io.connSS

  for (i <- 0 until vssIndicies.size) {
    val node = vssIndicies(i)
    // Data flows node-1 -> node.
    val dataUpstream = if (node == 0) tqNum - 1 else node - 1
    io.ntwDataUnitOccupancyVSS(i) := dataUnits(dataUpstream).io.occupied
    // Requests counter-rotate: node+1 -> node.
    val ctrlUpstream = if (node == tqNum - 1) 0 else node + 1
    io.ntwReqArrivingVSS(i) := ctrlunits(ctrlUpstream).io.hasRequestOut
  }
}
