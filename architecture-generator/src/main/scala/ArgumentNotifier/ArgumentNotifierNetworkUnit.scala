package ArgumentNotifier

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

class ArgumentNotifierNetworkUnitIO(addrWidth: Int) extends Bundle {
  val addressIn = Flipped(
    DecoupledIO(UInt(addrWidth.W))
  ) // Input from the previous unit
  val peAddress = Flipped(
    DecoupledIO(UInt(addrWidth.W))
  ) // Input from the PE Queue
  val addressOut = DecoupledIO(UInt(addrWidth.W)) // Output to the next unit
}

class ArgumentNotifierNetworkUnit(addrWidth: Int, priority: Int)
    extends Module {
  val io = IO(new ArgumentNotifierNetworkUnitIO(addrWidth))

  val priorityReg = RegInit(priority.U)

  // TRUE = PE, FALSE = Ring
  val winner = Wire(Bool())
  when(io.peAddress.valid && (priorityReg === 0.U || !io.addressIn.valid)) {
    winner := true.B
  }.otherwise {
    winner := false.B
  }

  val selected = Wire(DecoupledIO(UInt(addrWidth.W)))
  selected.valid := io.addressIn.valid || io.peAddress.valid
  selected.bits := Mux(winner, io.peAddress.bits, io.addressIn.bits)

  io.addressIn.ready := ~winner && selected.ready
  io.peAddress.ready := winner && selected.ready

  when(selected.fire) {
    priorityReg := Mux(priorityReg === 0.U, priority.U, priorityReg - 1.U)
  }

  // Buffer output to provent long combinational ring
  val outputBuffer = Module(new Queue(UInt(addrWidth.W), 2))
  outputBuffer.io.enq <> selected
  io.addressOut <> outputBuffer.io.deq

}
