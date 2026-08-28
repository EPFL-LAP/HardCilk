package Scheduler

import chisel3._
import Scheduler._
import Util._

// The bundle connection for the stealing network interface.
class SchedulerNetworkIO(
    taskWidth: Int,
    tqNum: Int,
    vssCount: Int,
    elasticData: Boolean
) extends Bundle {
  val connSS = Vec(tqNum, new SchedulerNetworkClientIO(taskWidth))

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
    // downstream of it.
    elasticData: Boolean = false
) extends Module {
  val io = IO(
    new SchedulerNetworkIO(taskWidth, tqNum, vssIndicies.size, elasticData)
  )

  // Instantiate data units
  val dataUnits =
    Seq.fill(tqNum)(
      Module(new SchedulerNetworkDataUnit(taskWidth, elasticData))
    )
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
      dataUnits(upstream).io.stopInFull.get := dataUnits(
        downstream
      ).io.stopOutFull.get
      dataUnits(upstream).io.stopInWant.get := dataUnits(
        downstream
      ).io.stopOutWant.get
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
