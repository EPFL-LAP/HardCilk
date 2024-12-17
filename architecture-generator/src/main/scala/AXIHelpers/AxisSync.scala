package AXIHelpers

import chisel3._
import chisel3.util._

import chext.amba.axi4s
import _root_.circt.stage.ChiselStage

// A module that makes sure that the data of each input module is passed only when all the

class AxisSyncIO(dataWidth: Int, numberPorts: Int) extends Bundle {

  implicit val axisCfgSlave: axi4s.Config = axi4s.Config(wData = dataWidth, onlyRV = true)
  implicit val axisCfgMaster: axi4s.Config = axi4s.Config(wData = dataWidth, onlyRV = true)

  val dataIn = Vec(numberPorts, axi4s.Slave(axisCfgSlave))
  val dataOut = Vec(numberPorts, axi4s.Master(axisCfgMaster))

}

class AxisSync(dataWidth:Int, numberPorts:Int) extends Module {

    assert(isPow2(dataWidth))
    assert(numberPorts >= 2)

    val io = IO(new AxisSyncIO(dataWidth, numberPorts))

    object state extends ChiselEnum {
        val waitData = Value(0.U)
        val writeDataToOutput = Value(1.U)
    }

    val stateReg = RegInit(state.waitData)

    // Create a number of fifos equal to the number of ports of depth 8
    val fifos = Seq.fill(numberPorts)(Module(new Queue(UInt(dataWidth.W), 8)))

   
    // Connect the output TDATA to the output of the fifo
    when(stateReg === state.waitData) {
        // Check if all the size of all fifos >= 1
        when(fifos.map(fifo => fifo.io.count >= 1.U).reduce(_ && _)) {
            stateReg := state.writeDataToOutput
        }
    }.elsewhen(stateReg === state.writeDataToOutput) {
        // Check if any fifo size is 1 and corresponding dataIn input is not valid and corresponding dataOut output is ready
        for (i <- 0 until numberPorts) {
            when(fifos(i).io.count === 1.U && io.dataIn(i).TVALID && io.dataOut(i).TREADY) {
                stateReg := state.waitData
            }
        }
    }
    

    when(stateReg === state.waitData) {
        // The valid of the output is all false in this state
        io.dataOut.foreach(_.TVALID := false.B)

        // Assign the fifo ready to all false
        for (i <- 0 until numberPorts) {
            fifos(i).io.deq.ready := false.B
        }

    }.elsewhen(stateReg === state.writeDataToOutput) {
        // The valid of the output is all true in this state
        io.dataOut.foreach(_.TVALID := true.B)

        // Assign the fifo ready to the output ready
        for (i <- 0 until numberPorts) {
            fifos(i).io.deq.ready := io.dataOut(i).TREADY
        }
    }.otherwise {
        // The valid of the output is all false in this state
        io.dataOut.foreach(_.TVALID := false.B)

        // Assign the fifo ready to all false
        for (i <- 0 until numberPorts) {
            fifos(i).io.deq.ready := false.B
        }
    }


    // Connect the input data to the fifo input
    for (i <- 0 until numberPorts) {
        fifos(i).io.enq.valid := io.dataIn(i).TVALID
        fifos(i).io.enq.bits := io.dataIn(i).TDATA
        io.dataIn(i).TREADY := fifos(i).io.enq.ready
    }

    // Assign the data of the fifo to the output
    for (i <- 0 until numberPorts) {
        io.dataOut(i).TDATA := fifos(i).io.deq.bits
    }

}

object AxisSync extends App {
  
  ChiselStage.emitSystemVerilogFile(
      {
        val module = new AxisSync(128, 2)
        module
      },
      Array(f"--target-dir=${"output"}"),
      Array("--disable-all-randomization")
  )
}

