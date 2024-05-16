package deque
import chisel3._
import chisel3.util._
import commonInterfaces._
import dualPortBRAM._
import chisel3.ChiselEnum

class dequeIO(taskWidth: Int, queueMaxLength: Int) extends Bundle {
    val connVec  = Vec(2, new dequeInterfaceConn(taskWidth, queueMaxLength))
}

// Module connected to port 0 has precedence in pop when there is only single item to pop

// Note, this module assumes that ram read or write latency is at least one cycle.

// N.B. queueMaxLength parameter in this code represents a usable (queueMaxLength - 2) as a quick fix for the
// circular queue pointer arithmetic.

class deque(taskWidth: Int, queueMaxLength: Int, ramReadLatency: Int, ramWriteLatency: Int) extends Module {
    val io = IO(new dequeIO(taskWidth, queueMaxLength))
    
    // Create registers to carry the deque info
    val sideReg      = Seq(RegInit(0.U((log2Ceil(queueMaxLength)+1).W)), RegInit(1.U((log2Ceil(queueMaxLength)+1).W)))
    val qMaxLenReg   = RegInit(queueMaxLength.U((log2Ceil(queueMaxLength)+1).W))

    val hardwareReadLatency  = RegInit((ramReadLatency-1).U((log2Ceil(ramReadLatency)+1).W))
    val hardwareWriteLatency = RegInit((ramWriteLatency-1).U((log2Ceil(ramWriteLatency)+1).W))

    val readLatency  = Seq.fill(2)(RegInit((ramReadLatency-1).U((log2Ceil(ramReadLatency)+1).W)))
    val writeLatency = Seq.fill(2)(RegInit((ramWriteLatency-1).U((log2Ceil(ramWriteLatency)+1).W)))

    // BRAM memory instantiation
    // BRAM is word addressable (32-bit word), so we need to divide the taskWidth by 32
    val bramMem      = Module(new DualPortBRAM(log2Ceil(queueMaxLength*taskWidth/32), taskWidth))
    val bramPorts    = Seq(bramMem.io.a, bramMem.io.b)
    bramMem.io.clk   := clock
    bramMem.io.rst   := reset

    // Note: Both head and tail should be pointing to a free entry and they should not cross.
    val currLen = Wire(UInt((log2Ceil(queueMaxLength)+1).W))

    override def desiredName: String = "hw_deque"

    object state extends ChiselEnum 
    {
        val idle        = Value(0.U) 
        val writeMem    = Value(1.U)
        val readMem     = Value(2.U)
        val push        = Value(3.U) 
        val pop         = Value(4.U)     
    }

    // In desiging the two FSMs we give priority to FSM serving Port 0
    val stateRegs = Seq.fill(2)(RegInit(state.idle))

    // Defaults
    for(i <- 0 until 2){
        // BRAM Input values
        bramPorts(i).addr        := 0x0FFFFFFF.U
        bramPorts(i).din         := io.connVec(i).push.bits
        bramPorts(i).wr          := false.B

        // Outputs of the module
        io.connVec(i).currLength := currLen
        io.connVec(i).push.ready := false.B
        io.connVec(i).pop.valid  := false.B
        io.connVec(i).pop.bits   := 0.U
    }

    for (i <- 0 until 2){
        when(stateRegs(i) === state.idle){
            if(i == 0){
                // Make PE precedence to read first not write first
                when((io.connVec(i).pop.ready && currLen > 1.U) || (io.connVec(i).pop.ready && stateRegs(1) === state.idle && currLen > 0.U)){
                    stateRegs(i) := state.readMem
                }.elsewhen(io.connVec(i).push.valid && currLen < qMaxLenReg){
                    stateRegs(i) := state.writeMem
                }.otherwise{
                    stateRegs(i) := state.idle
                }
            } else{
                when(io.connVec(i).push.valid && currLen < (qMaxLenReg - 1.U)){
                    stateRegs(i) := state.writeMem
                }.elsewhen((io.connVec(i).pop.ready && currLen > 1.U) || (io.connVec(i).pop.ready && ~io.connVec(0).pop.ready && currLen > 0.U && stateRegs(0) =/= state.pop)){
                    // stateRegs(0) =/= state.pop
                    // The above part in the condition above is because the peek poke tester pokes at negedge
                    // resulting in conflicts.
                    stateRegs(i) := state.readMem
                }.otherwise{
                    stateRegs(i) := state.idle
                }
            }
        }.elsewhen(stateRegs(i) === state.writeMem){
            // Set next state
            when(writeLatency(i) === 0.U){
                writeLatency(i) := hardwareWriteLatency
                stateRegs(i)    := state.push
            }.otherwise{
                writeLatency(i) := writeLatency(i) - 1.U
                stateRegs(i)    := state.writeMem
            }

            bramPorts(i).addr        := sideReg(i) //<< 2
            bramPorts(i).wr          := true.B

        }.elsewhen(stateRegs(i) === state.readMem){
            // Set next state
            when(readLatency(i) === 0.U){
                readLatency(i)  := hardwareReadLatency
                stateRegs(i)    := state.pop
            }.otherwise{
                readLatency(i)  := readLatency(i) - 1.U
                stateRegs(i)    := state.readMem
            }

            // BRAM Input values
            if(i == 0){
                when(sideReg(i) === qMaxLenReg - 1.U){
                    bramPorts(i).addr := 0.U
                }.otherwise{
                    bramPorts(i).addr := (sideReg(i) + 1.U) //<< 2
                }
            } else{
                when(sideReg(i) === 0.U){
                    bramPorts(i).addr := (qMaxLenReg - 1.U) //<< 2
                }.otherwise{
                    bramPorts(i).addr := (sideReg(i) - 1.U) //<< 2
                }
            }
        }.elsewhen(stateRegs(i) === state.pop){
            // Set next state
            stateRegs(i)             := state.idle

            // Update side pointer (bram read address is the same as the updated value)
            if(i == 0){
                when(sideReg(i) === qMaxLenReg - 1.U){
                    sideReg(i)        := 0.U
                    bramPorts(i).addr := 0.U
                }.otherwise{
                    sideReg(i)        := sideReg(i) + 1.U
                    bramPorts(i).addr := (sideReg(i) + 1.U) //<< 2
                }
            } else{ 
                when(sideReg(i) === 0.U){
                    sideReg(i)        := qMaxLenReg - 1.U
                    bramPorts(i).addr := (qMaxLenReg - 1.U)  //<< 2
                }.otherwise{
                    sideReg(i)        := sideReg(i) - 1.U
                    bramPorts(i).addr := (sideReg(i) - 1.U)  //<< 2
                }
            }
            io.connVec(i).pop.valid  := true.B
            io.connVec(i).pop.bits   := bramPorts(i).dout
        }.elsewhen(stateRegs(i) === state.push){
            // Set next state
            stateRegs(i)             := state.idle

            // Update side pointer
            if(i == 0){
                when(sideReg(i) === 0.U){
                    sideReg(i) := qMaxLenReg - 1.U
                }.otherwise{
                    sideReg(i) := sideReg(i) - 1.U
                }
            } else{ 
                when(sideReg(i) === (qMaxLenReg - 1.U)){
                    sideReg(i) := 0.U
                }.otherwise{
                    sideReg(i) := sideReg(i) + 1.U
                }
            }
            io.connVec(i).push.ready := true.B
        }
    }

    when(sideReg(0) > sideReg(1)){
        currLen := qMaxLenReg + sideReg(1) - sideReg(0)  - 1.U 
    }.otherwise{
        currLen := sideReg(1) - sideReg(0) - 1.U
    }
    
}
