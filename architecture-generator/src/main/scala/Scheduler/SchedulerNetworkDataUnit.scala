package Scheduler

import chisel3._
import chisel3.util._
import Util._

// The interface connection for the stealing network data unit.
class SchedulerNetworkDataUnitIO(taskWidth: Int, elastic: Boolean)
    extends Bundle {
  // Connections to other network data units (Shift register and not elastic)
  val taskIn = Input(UInt(taskWidth.W))
  val taskOut = Output(UInt(taskWidth.W))

  val validIn = Input(Bool())
  val validOut = Output(Bool())

  // Elastic-ring backpressure, carried as TWO bits because the upstream hop has
  // to be able to tell the two reasons apart:
  //
  //   Full -- hard. There is physically nowhere to put another task. Overriding
  //     this would drop one, so nothing may.
  //   Want -- soft. A local producer would like this slot. A preference, and an
  //     upstream hop holding forceForward may ignore it.
  //
  // Present only on an elastic ring; a rigid ring does not carry them at all.
  val stopInFull = if (elastic) Some(Input(Bool())) else None
  val stopInWant = if (elastic) Some(Input(Bool())) else None
  val stopOutFull = if (elastic) Some(Output(Bool())) else None
  val stopOutWant = if (elastic) Some(Output(Bool())) else None

  // "Push through a soft stop this cycle", from the local producer. Driven off
  // registered producer state (SpawnerServer.atCapacity), so it does not lengthen
  // the ring path.
  val forceForward = if (elastic) Some(Input(Bool())) else None

  // "My local producer wants to inject here", taken straight from that producer
  // rather than from connSS.qOutTask.valid.  They are the same signal at most
  // nodes, but not where a SchedulerInjectionSwap sits between the producer and
  // the ring: the swap derives its valid from BOTH slots' ready, so reading
  // qOutTask.valid here would make stopOut depend on qOutTask.ready and close a
  // combinational loop around the ring.  Reading the producer keeps stopOut on
  // registered/local state, which is the whole reason the ring is safe to close.
  val injectWanted = if (elastic) Some(Input(Bool())) else None

  // Connections to steal server
  val connSS = new SchedulerNetworkClientData(taskWidth)

  val occupied = Output(
    Bool()
  ) // Indicates that the data unit is occupied to the vss
}

class SchedulerNetworkDataUnit(taskWidth: Int, elastic: Boolean = false)
    extends Module {
  val io = IO(new SchedulerNetworkDataUnitIO(taskWidth, elastic))

  if (!elastic) {
    val taskReg = RegInit(0.U(taskWidth.W))
    val validReg = RegInit(false.B)

    io.connSS.qOutTask.ready := ~io.validIn
    io.connSS.availableTask.bits := io.taskIn
    io.connSS.availableTask.valid := io.validIn

    when(io.connSS.availableTask.ready && io.validIn) {
      validReg := false.B
      taskReg := 0.U
    }.elsewhen(io.connSS.qOutTask.valid && ~io.validIn) {
      validReg := true.B
      taskReg := io.connSS.qOutTask.bits
    }.elsewhen(io.validIn) {
      validReg := io.validIn
      taskReg := io.taskIn
    }.otherwise {
      validReg := false.B
      taskReg := 0.U
    }

    io.taskOut := taskReg
    io.validOut := validReg
    io.occupied := validReg
  } else {
    val slots = Reg(Vec(2, UInt(taskWidth.W)))
    val count = RegInit(0.U(2.W))
    val hasTask = count =/= 0.U
    io.connSS.availableTask.bits := slots(0)
    io.connSS.availableTask.valid := hasTask
    val consumedLocally = io.connSS.availableTask.ready && hasTask
    val downstreamBlocked =
      io.stopInFull.get || (io.stopInWant.get && !io.forceForward.get)
    val forwarded = hasTask && !downstreamBlocked && !consumedLocally
    val removed = forwarded || consumedLocally

    val base = count - removed.asUInt
    val countAfterMovement = base + io.validIn.asUInt
    io.connSS.qOutTask.ready := countAfterMovement === 0.U
    val accepted = io.connSS.qOutTask.valid && io.connSS.qOutTask.ready

    io.stopOutFull.get := count === 2.U
    io.stopOutWant.get := io.injectWanted.get

    val nextCount = countAfterMovement + accepted.asUInt
    assert(nextCount <= 2.U, "elastic ring hop overrun")

    // Shift down on removal, then append whatever arrived (injection only ever lands in an empty
    // hop, per countAfterMovement === 0 above).
    when(accepted) {
      slots(0) := io.connSS.qOutTask.bits
    }.otherwise {
      slots(0) := Mux(
        base === 0.U && io.validIn,
        io.taskIn,
        Mux(removed, slots(1), slots(0))
      )
      when(base === 1.U && io.validIn) { slots(1) := io.taskIn }
    }

    count := nextCount

    io.taskOut := slots(0)
    io.validOut := forwarded
    io.occupied := hasTask
  }
}
