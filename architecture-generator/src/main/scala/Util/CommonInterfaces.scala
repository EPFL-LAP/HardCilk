package Util
import chisel3._
import chisel3.util._

// Connections between control steal network and steal server
class SchedulerNetworkClientRequest extends Bundle {
  // Note: we consider the stealing server the producer for
  // both steal request and their serves.

  // Indicates to the steal server whether it should push an
  // available task to the data network or not.
  val serveStealReq = Flipped(new HandShake)

  // The steal server telling the network that it wants to
  // steal a task.
  val stealReq = Flipped(new HandShake)
}

// Connections between data steal network and steal server
class SchedulerNetworkClientData(taskWidth: Int) extends Bundle {
  // The task in the network seen by the steal server
  val availableTask = Decoupled(UInt(taskWidth.W)) // Elastic Output of steal network
  // The task that is outputed from a queue to the communication network
  val qOutTask = Flipped(Decoupled(UInt(taskWidth.W))) // Elastic Input to steal network
}

class SchedulerNetworkClientIO(taskWidth: Int) extends Bundle {
  val ctrl = new SchedulerNetworkClientRequest
  val data = new SchedulerNetworkClientData(taskWidth)
}

class DequeInterface(taskWidth: Int, queueMaxLength: Int) extends Bundle {
  val currLength = Output(UInt((log2Ceil(queueMaxLength) + 1).W))
  val push = Flipped(DecoupledIO(UInt(taskWidth.W)))
  val pop = DecoupledIO(UInt(taskWidth.W))
}

class HandShake extends Bundle {
  val valid = Output(Bool())
  val ready = Input(Bool())
}