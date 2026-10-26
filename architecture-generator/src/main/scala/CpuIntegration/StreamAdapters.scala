package CpuIntegration

import chisel3._
import chisel3.util._
import chext.amba.axi4s

/** Raw AXIS boundary primitives. A complete task is a single native HardCilk
  * beat; CPU packets are least-significant chunk first. Malformed packets fail
  * closed until reset, rather than silently shifting subsequent task boundaries.
  */
class TaskSerializer(taskWidth: Int, chunkWidth: Int) extends Module {
  require(taskWidth > 0 && taskWidth % chunkWidth == 0)
  val input = IO(axi4s.Slave(axi4s.Config(wData = taskWidth, onlyRV = true)))
  val output = IO(axi4s.Master(axi4s.Config(wData = chunkWidth, onlyRV = true)))
  private val beats = taskWidth / chunkWidth
  private val remaining = RegInit(0.U(math.max(1, log2Ceil(beats + 1)).W))
  private val data = Reg(UInt(taskWidth.W))
  input.TREADY := remaining === 0.U
  output.TVALID := remaining =/= 0.U
  output.TDATA := data(chunkWidth - 1, 0)
  when(input.TVALID && input.TREADY) { data := input.TDATA; remaining := beats.U }
  when(output.TVALID && output.TREADY) { data := data >> chunkWidth; remaining := remaining - 1.U }
}

class TaskAssembler(chunkWidth: Int, taskWidth: Int) extends Module {
  require(taskWidth > 0 && taskWidth % chunkWidth == 0)
  val input = IO(axi4s.Slave(axi4s.Config(wData = chunkWidth)))
  val output = IO(axi4s.Master(axi4s.Config(wData = taskWidth, onlyRV = true)))
  val fault = IO(Output(Bool()))
  private val beats = taskWidth / chunkWidth
  private val index = RegInit(0.U(math.max(1, log2Ceil(beats)).W))
  private val words = Reg(Vec(beats, UInt(chunkWidth.W)))
  private val full = RegInit(false.B)
  private val failed = RegInit(false.B)
  fault := failed
  input.TREADY := !full && !failed
  output.TVALID := full && !failed
  output.TDATA := words.asUInt
  when(input.TVALID && input.TREADY) {
    val correctLast = input.TLAST.get === (index === (beats - 1).U)
    when(!correctLast || !input.TKEEP.get.andR || !input.TSTRB.get.andR) {
      failed := true.B
    }.otherwise {
      words(index) := input.TDATA
      when(index === (beats - 1).U) { full := true.B; index := 0.U }
        .otherwise { index := index + 1.U }
    }
  }
  when(output.TVALID && output.TREADY) { full := false.B }
}

/** Consume a complete header beat; route subsequent payload through TLAST.
  * Route IDs are 1..N, in JSON spawnList order with self excluded.
  */
class TaskRouter(chunkWidth: Int, widths: Seq[Int]) extends Module {
  require(widths.nonEmpty && BigInt(widths.size) < (BigInt(1) << chunkWidth))
  val input = IO(axi4s.Slave(axi4s.Config(wData = chunkWidth)))
  val outputs = widths.zipWithIndex.map { case (width, i) =>
    IO(axi4s.Master(axi4s.Config(wData = width, onlyRV = true))).suggestName(s"output_$i")
  }
  val fault = IO(Output(Bool()))
  private val active = RegInit(false.B)
  private val route = Reg(UInt(math.max(1, log2Ceil(widths.size)).W))
  private val failed = RegInit(false.B)
  private val packers = widths.map(w => Module(new TaskAssembler(chunkWidth, w)))
  fault := failed || packers.map(_.fault).reduce(_ || _)
  input.TREADY := !fault && Mux(active, VecInit(packers.map(_.input.TREADY))(route), true.B)
  for ((packer, i) <- packers.zipWithIndex) {
    packer.input.TVALID := input.TVALID && active && route === i.U && !fault
    packer.input.TDATA := input.TDATA
    packer.input.TLAST.get := input.TLAST.get
    packer.input.TKEEP.get := input.TKEEP.get
    packer.input.TSTRB.get := input.TSTRB.get
    outputs(i) <> packer.output
  }
  when(input.TVALID && input.TREADY) {
    when(!active) {
      when(input.TDATA === 0.U || input.TDATA > widths.size.U || input.TLAST.get ||
           !input.TKEEP.get.andR || !input.TSTRB.get.andR) { failed := true.B }
        .otherwise { route := input.TDATA - 1.U; active := true.B }
    }.elsewhen(input.TLAST.get) { active := false.B }
  }
}

/** One destination needs no tag. With N>1, use the high ceil(log2(N))
  * bits as the zero-based destination; preserve all remaining address bits.
  */
class ArgumentRouter(count: Int) extends Module {
  require(count > 0)
  private val bits = log2Ceil(count)
  val input = IO(axi4s.Slave(axi4s.Config(wData = 64, onlyRV = true)))
  val outputs = IO(Vec(count, axi4s.Master(axi4s.Config(wData = 64, onlyRV = true))))
  val fault = IO(Output(Bool()))
  private val route = if (bits == 0) 0.U else input.TDATA(63, 64 - bits)
  private val failed = RegInit(false.B)
  fault := failed
  input.TREADY := !failed && route < count.U && VecInit(outputs.map(_.TREADY))(route)
  when(input.TVALID && route >= count.U) { failed := true.B }
  for ((port, i) <- outputs.zipWithIndex) {
    port.TVALID := input.TVALID && !failed && route === i.U
    port.TDATA := (if (bits == 0) input.TDATA else input.TDATA(63 - bits, 0))
  }
}
