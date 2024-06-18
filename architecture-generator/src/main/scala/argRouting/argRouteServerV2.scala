package argRouting

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum
import commonInterfaces._
import os.stat

import chext.axi4
import chext.axis4

import axi4.Ops._
import axi4.lite.components.RegisterBlock

import axis4.Casts._

class argRouteVirtServerV2IO(taskWidth: Int, counterWidth: Int, sysAddressWidth: Int) extends Bundle {
    val connNetwork   = Flipped(DecoupledIO(UInt(sysAddressWidth.W)))
    val connStealNtw  = Flipped(new stNwStSrvConn(taskWidth))

    val read_address  = DecoupledIO(UInt(sysAddressWidth.W)) 
    val read_data     = Flipped(DecoupledIO(UInt(counterWidth.W)))
    val write_address = DecoupledIO(UInt(sysAddressWidth.W))
    val write_data    = DecoupledIO(UInt(counterWidth.W))

    val read_address_task     = DecoupledIO(UInt(sysAddressWidth.W)) 
    val read_data_task        = Flipped(DecoupledIO(UInt(counterWidth.W)))
}

class argRouteVirtServerV2(taskWidth: Int,
                           counterWidth: Int, 
                           sysAddressWidth: Int, 
                           tagBitsShift: Int,
                           noContinuations: Boolean = false,
                           bufferQueueDepth:Int = 16) extends Module {

    object state extends ChiselEnum 
    {
        val readNetwork          = Value(1.U)
        val readCounterWAddr     = Value(2.U)
        val readCounterRData     = Value(3.U)
        val writeCounterWAddr    = Value(4.U)
        val writeCounterWData    = Value(5.U)
        val readTaskAddress      = Value(6.U)
        val pushTaskAddress      = Value(7.U)
        val pushTaskAddresstoMem = Value(8.U)
        val readTask             = Value(9.U)
        val writeTaskToLocalQ    = Value(10.U)
        val readTaskFromLocalQ   = Value(11.U)
        val writeTaskNtw         = Value(12.U)
    }

    val io = IO(new argRouteVirtServerV2IO(taskWidth, counterWidth, sysAddressWidth))

    val addrNtwInQueue = Module(new Queue(UInt(), bufferQueueDepth))
    
    //val readableCounterAddressesQueue = Module(new Queue(UInt(), bufferQueueDepth))
    //val writableCounterAddressesAndValueQueue = Module(new Queue(UInt(), bufferQueueDepth))
    
    val addressesOfReadyTasks = Module(new Queue(UInt(), bufferQueueDepth))
    val readyTasksQueue = Module(new Queue(UInt(), bufferQueueDepth)) 


    addrNtwInQueue.io.enq <> io.connNetwork

/*
*  Read an address from the network and feed it to the read counter if the address is not in the writable counter queue
*/
    // when(counterInStateReg === state.readNetwork){
    //     when(addrNtwInQueue.io.deq.valid) {
    //         counterInStateReg  := state.readCounterWAddr
    //         counterAddrIn      := addrNtwInQueue.io.deq.bits & addrMask
    //     }
    // }
  



/*
*  Counter State Machine: reading and updating the counter atomically
*  Ready tasks are written to internal queue to be read by another FSM
*/

    // Counter related registers
    private val counterStateReg      = RegInit(state.readNetwork)
    private val counterReg           = RegInit(0.U(64.W))
    private val currReadAddr         = RegInit(0.U(64.W))
    private val counterAddr          = RegInit(0.U(64.W))
    private val addrMask             = RegInit(0.U(64.W))
    private val helpGarbageCollector = RegInit(0.U)
    addrMask                         := ((~(0.U(64.W))) << tagBitsShift.U)

    // Init the io of the queue that takes addresses from the network and feeds them to the counter checker state machine
    addrNtwInQueue.io.deq.ready  := false.B
    
    addressesOfReadyTasks.io.enq.valid := false.B
    addressesOfReadyTasks.io.enq.bits  := currReadAddr
    
    // Init the io realated to reading and writing the counter
    io.read_address.bits  := counterAddr
    io.write_address.bits := counterAddr

    when(helpGarbageCollector === 1.U){
        io.write_data.bits    := 0x1000000.U
    }.otherwise{
        io.write_data.bits    := counterReg
    }

    io.read_address.valid := false.B
    io.write_address.valid:= false.B
    io.write_data.valid   := false.B
    io.read_data.ready    := false.B

    // Transitions
    when(counterStateReg === state.readNetwork){
        when(addrNtwInQueue.io.deq.valid){
            counterStateReg    := state.readCounterWAddr
            counterAddr := addrNtwInQueue.io.deq.bits & addrMask
        }
    }.elsewhen(counterStateReg === state.readCounterWAddr){
        when(io.read_address.ready){
            counterStateReg := state.readCounterRData
        }
    }.elsewhen(counterStateReg === state.readCounterRData){
        when(io.read_data.valid && io.read_data.bits === 1.U){ 
            //taskRegisters(readDataCount-1.U) := io.read_data.bits
            //readDataCount := readDataCount - 1.U
            currReadAddr := counterAddr + (counterWidth/8).U
            counterStateReg := state.pushTaskAddress
        }.elsewhen(io.read_data.valid){
            counterReg := io.read_data.bits - 1.U
            counterStateReg := state.writeCounterWAddr
        }
    }.elsewhen(counterStateReg === state.writeCounterWAddr){
        when(io.write_address.ready){
            counterStateReg := state.writeCounterWData
        }
    }.elsewhen(counterStateReg === state.writeCounterWData){
        when(io.write_data.ready){
            counterStateReg := state.readNetwork
            helpGarbageCollector := 0.U
        }
    }.elsewhen(counterStateReg === state.pushTaskAddress){
        when(addressesOfReadyTasks.io.enq.ready){
            counterStateReg := state.writeCounterWAddr
            helpGarbageCollector := 1.U
        }
    }

    // Output
    when(counterStateReg === state.readNetwork){
        addrNtwInQueue.io.deq.ready := true.B
    }.elsewhen(counterStateReg === state.readCounterWAddr){
        io.read_address.valid := true.B
    }.elsewhen(counterStateReg === state.readCounterRData){
        io.read_data.ready := true.B
    }.elsewhen(counterStateReg === state.writeCounterWAddr){
        io.write_address.valid := true.B
    }.elsewhen(counterStateReg === state.writeCounterWData){
        io.write_data.valid := true.B
    }.elsewhen(counterStateReg === state.pushTaskAddress){
        addressesOfReadyTasks.io.enq.valid := true.B
    }


/** End of Counter State Machine */


/* 
* Ready Addresses State Machine: read the addresses from the local queue and feed them to the memory interface
*/  

    // Ready Addresses related registers
    private val taskReadAddressStateReg = RegInit(state.readTaskAddress)
    private val taskAddr                = RegInit(0.U(64.W))
    private val taskReadAddressCount    = RegInit(((taskWidth / counterWidth)-1).U)

    // Init the io of the queue that takes addresses of ready tasks and feeds them to the memory interface
    addressesOfReadyTasks.io.deq.ready := false.B

    // Init the readAddress memory interface
    io.read_address_task.bits  := 0.U
    io.read_address_task.valid := false.B

    when(taskReadAddressStateReg === state.readTaskAddress){
        when(addressesOfReadyTasks.io.deq.valid){
            taskReadAddressStateReg := state.pushTaskAddresstoMem
            taskAddr := addressesOfReadyTasks.io.deq.bits
            // taskReadAddressCount := ((taskWidth / counterWidth)-1).U
        }
    }.elsewhen(taskReadAddressStateReg === state.pushTaskAddresstoMem){
        /*when(io.read_address_task.ready && taskReadAddressCount > 0.U){
            taskAddr := taskAddr + (counterWidth/8).U 
            taskReadAddressCount := taskReadAddressCount - 1.U
        }

        when(taskReadAddressCount === 1.U && io.read_address_task.ready){
            taskReadAddressStateReg         := state.readTaskAddress
        }*/

        when(io.read_address_task.ready){
            taskReadAddressStateReg         := state.readTaskAddress
        }
    }

    when(taskReadAddressStateReg === state.readTaskAddress){
        addressesOfReadyTasks.io.deq.ready := true.B
    }.elsewhen(taskReadAddressStateReg === state.pushTaskAddresstoMem){
        io.read_address_task.valid := true.B
        io.read_address_task.bits  := taskAddr
    }

/** End of Ready Addresses State Machine */

  
/*
* ReadTask State Machine: read the task from the memory and send it to the network
*/

    // Tasks related registers
    private val taskReadStateReg = RegInit(state.readTask)
    private val taskReadCount    = RegInit(((taskWidth / counterWidth)-1).U)
    private val taskRegisters = RegInit(VecInit(Seq.fill(taskWidth / counterWidth)(0.U(counterWidth.W))))

    // Init the io of the queue that takes ready tasks and feeds them to the steal network
    readyTasksQueue.io.enq.valid := false.B
    readyTasksQueue.io.enq.bits  := taskRegisters.reduce(Cat(_, _))

    // Init the readTask memory interface
    io.read_data_task.ready := false.B

    when(taskReadStateReg === state.readTask){
        
        when(io.read_data_task.valid){
            taskRegisters(taskReadCount-1.U) := io.read_data_task.bits
            taskReadCount := taskReadCount - 1.U
        }

        when(taskReadCount === 1.U && io.read_data_task.valid){
            taskReadStateReg         := state.writeTaskToLocalQ
        }

    }.elsewhen(taskReadStateReg === state.writeTaskToLocalQ){
        when(readyTasksQueue.io.enq.ready){
            taskReadStateReg := state.readTask
            taskReadCount    := ((taskWidth / counterWidth)-1).U
        }
    }

    when(taskReadStateReg === state.readTask){
        io.read_data_task.ready := true.B
    }.elsewhen(taskReadStateReg === state.writeTaskToLocalQ){
        readyTasksQueue.io.enq.valid := true.B
    }

/** End of ReadTask State Machine */

/*
*  Feeding the data network of the steal side with ready tasks
*/
    // registers related to feeding the data network of the steal side with ready tasks
    private val tasksGivenAwayCount   = RegInit(0.U(32.W))
    private val taskReg = RegInit(0.U(taskWidth.W))
    private val taskWriteStateReg = RegInit(state.readTaskFromLocalQ)

    readyTasksQueue.io.deq.ready := false.B

    // Init the steal network data-network outputs
    io.connStealNtw.data.qOutTask.bits       := taskReg
    io.connStealNtw.data.availableTask.ready := false.B  
    io.connStealNtw.data.qOutTask.valid      := false.B

    when(taskWriteStateReg === state.readTaskFromLocalQ){
        when(readyTasksQueue.io.deq.valid){
            taskWriteStateReg := state.writeTaskNtw
            taskReg := readyTasksQueue.io.deq.bits
        }
    }.elsewhen(taskWriteStateReg === state.writeTaskNtw){
        when(io.connStealNtw.data.qOutTask.ready){
            taskWriteStateReg := state.readTaskFromLocalQ
            tasksGivenAwayCount := tasksGivenAwayCount + 1.U
        }
    }

    when(taskWriteStateReg === state.readTaskFromLocalQ){
        readyTasksQueue.io.deq.ready := true.B
    }.elsewhen(taskWriteStateReg === state.writeTaskNtw){
        io.connStealNtw.data.qOutTask.valid := true.B
    }




/** End of Feeding the data network of the steal side with ready tasks */


/*
* Handling the ctrl network of the steal side
*/    

    // Init the steal network ctrl-network outputs
    io.connStealNtw.ctrl.stealReq.valid      := false.B
    io.connStealNtw.ctrl.serveStealReq.valid := false.B

    when(tasksGivenAwayCount > 0.U && taskWriteStateReg =/= state.writeTaskNtw){        
        io.connStealNtw.ctrl.serveStealReq.valid := true.B
        when(io.connStealNtw.ctrl.serveStealReq.ready){
            tasksGivenAwayCount := tasksGivenAwayCount - 1.U
        }
    }
/** End handling the ctrl network */ 

}