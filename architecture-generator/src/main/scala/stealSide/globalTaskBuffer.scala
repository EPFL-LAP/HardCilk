package stealSide

import chisel3._
import chisel3.util._
import commonInterfaces._
import chisel3.ChiselEnum


class globalTaskBufferIO(taskWidth : Int) extends Bundle{
    val in = Flipped(DecoupledIO(UInt(taskWidth.W)))
    val connStealNtw = Flipped(new stNwStSrvConn(taskWidth))
}

class globalTaskBuffer(taskWidth:Int, peCount: Int) extends Module{
    object state extends ChiselEnum 
    {
        val readTask           = Value(0.U)
        val writeTaskNtw       = Value(1.U)
    }
    
    val io = IO(new globalTaskBufferIO(taskWidth))

    val buffer = RegInit(0.U(taskWidth.W))
    val stateReg = RegInit(state.readTask)
    val tasksGivenAwayCount = RegInit(0.U(32.W))

    io.connStealNtw.data.qOutTask.bits       := buffer
    io.connStealNtw.data.availableTask.ready := false.B  
    io.connStealNtw.data.qOutTask.valid      := false.B
    io.in.ready                              := false.B
    io.connStealNtw.ctrl.serveStealReq.valid := false.B
    io.connStealNtw.ctrl.stealReq.valid      := false.B


    when(stateReg === state.readTask){
        io.in.ready := true.B
        when(io.in.valid){
            buffer := io.in.bits
            stateReg := state.writeTaskNtw
            when(tasksGivenAwayCount < peCount.U){
                tasksGivenAwayCount := tasksGivenAwayCount + 1.U
            }
        }
    }.elsewhen(stateReg === state.writeTaskNtw){
        io.connStealNtw.data.qOutTask.valid := true.B
        when(io.connStealNtw.data.qOutTask.ready){
            stateReg := state.readTask
        }
    }

    when(tasksGivenAwayCount > 0.U && (stateReg =/= state.readTask || ~io.in.valid)){        
        io.connStealNtw.ctrl.serveStealReq.valid := true.B
        when(io.connStealNtw.ctrl.serveStealReq.ready){
            tasksGivenAwayCount := tasksGivenAwayCount - 1.U
        }
    }

}