package Scheduler

import chisel3._
import Scheduler._
import Util._

// The bundle connection for the stealing network interface.
class SchedulerNetworkIO(taskWidth: Int, tqNum: Int, vssCount: Int) extends Bundle {
  val connSS = Vec(tqNum, new SchedulerNetworkClientIO(taskWidth))
  val ntwDataUnitOccupancyVSS = Vec(vssCount, Output(Bool()))
}

// A class capable of generating a circular stealing network
// parametrizable by task Width and the number of Task Queues.
// NOTE: tqNum should take into account the existence of a virtual TQ.
class SchedulerNetwork(
    taskWidth: Int,
    tqNum: Int,
    vssIndicies: Array[Int]
) extends Module {
  val io = IO(new SchedulerNetworkIO(taskWidth, tqNum, vssIndicies.size))

  // Instantiate data units
  val dataUnits = Seq.fill(tqNum)(Module(new SchedulerNetworkDataUnit(taskWidth)))
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

  // Connecting the controlUnits ring in the opposite direction
  for (i <- 0 until tqNum - 1) {
    ctrlunits(i).io.reqTaskIn := ctrlunits(i + 1).io.reqTaskOut
    io.connSS(i).ctrl <> ctrlunits(i).io.connSS
  }
  // Closing the controlUnits rings.
  ctrlunits(tqNum - 1).io.reqTaskIn := ctrlunits(0).io.reqTaskOut
  io.connSS(tqNum - 1).ctrl <> ctrlunits(tqNum - 1).io.connSS

  var ioDataOccupencyIndex = 0
  for (i <- 0 until vssIndicies.size) {
    io.ntwDataUnitOccupancyVSS(ioDataOccupencyIndex) := dataUnits(
      vssIndicies(i)
    ).io.occupied
    ioDataOccupencyIndex += 1
  }
}
