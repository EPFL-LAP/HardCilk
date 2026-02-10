package Scheduler

import chisel3._
import chisel3.util._
import Util._

import chext.amba.axi4s
import chext.amba.axi4
import chext.elastic
import axi4s.Casts._
import axi4.lite.components.RegisterBlock
import axi4.Ops._


class SpawnerServerIO(taskWidth: Int, regBlock: RegisterBlock, axiCfg : axi4.Config) extends Bundle {
  
  val m_axi = axi4.Master(cfg = axiCfg)

  val connNetwork_slave = Flipped(new SchedulerNetworkClientIO(taskWidth)) // Connection to the stealing Network
  val connNetwork_master = Flipped(new SchedulerNetworkClientIO(taskWidth)) // Connection to the stealing Network
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  val serveRemote = Output(Bool())         // A signal from the VSS to the RemoteTaskServer
}

class CircularQueueRegisterInc(width:Int) extends Module{
  val io = IO(new Bundle{
    val currValue = Input(UInt(width.W))
    val maxLen = Input(UInt(width.W))
    val nextvalue = Output(UInt(width.W))
    val incrValue = Input(UInt(width.W))
  })
  when((io.currValue + io.incrValue) < io.maxLen){
    io.nextvalue := (io.currValue + io.incrValue)
  }.otherwise{
    io.nextvalue := (io.currValue + io.incrValue - io.maxLen)
  }
}


object CircularQueueRegisterInc{
  def apply(currValue: UInt, maxLen: UInt, incrValue: UInt, width: Int): UInt = {
    val c = Module(new CircularQueueRegisterInc(width))
    c.io.currValue := currValue
    c.io.maxLen := maxLen
    c.io.incrValue := incrValue
    c.io.nextvalue
  }
}



class SpawnerServer(
    taskWidth: Int,
    queueDepth: Int = 16
) extends Module {

  private def connectZeros[T <: Data](bits: T) = {
    bits := 0.U(bits.getWidth.W).asTypeOf(bits)
  }

  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
  val axiCfg = axi4.Config(wId = 0, wAddr = 64, wData = taskWidth)
  val io = IO(new SpawnerServerIO(taskWidth, regBlock, axiCfg))
  io.axi_mgmt :=> regBlock.s_axil

  // Initialize the m_axi to zeros
  connectZeros(io.m_axi.asFull.ar.bits)
  connectZeros(io.m_axi.asFull.ar.valid)
  connectZeros(io.m_axi.asFull.aw.bits)
  connectZeros(io.m_axi.asFull.aw.valid)
  connectZeros(io.m_axi.asFull.w.bits)
  connectZeros(io.m_axi.asFull.w.valid)
  connectZeros(io.m_axi.asFull.b.ready)
  connectZeros(io.m_axi.asFull.r.ready)
  
  io.m_axi.asFull.aw.bits.burst := axi4.BurstType.INCR
  io.m_axi.asFull.ar.bits.burst := axi4.BurstType.INCR
  io.m_axi.asFull.aw.bits.size := log2Ceil(taskWidth / 8).U
  io.m_axi.asFull.ar.bits.size := log2Ceil(taskWidth / 8).U
  io.m_axi.asFull.w.bits.strb := (-1).S(axiCfg.wStrobe.W).asUInt

  // Create two queus of depth queueDepth
  val queue_read = Module(new Queue(UInt(taskWidth.W), queueDepth))
  val queue_write = Module(new Queue(UInt(taskWidth.W), queueDepth))

  queue_read.io.enq.valid := false.B
  queue_read.io.enq.bits := 0.U

  queue_write.io.deq.ready := false.B

  val s_axis_slave = Wire(axi4s.Slave(axi4s.Config(wData = taskWidth, onlyRV = true)))
  elastic.SinkBuffer(s_axis_slave.asLite) <> io.connNetwork_slave.data.availableTask


  // Connect the other ports of connNetwork_slave, always digest requests
  io.connNetwork_slave.data.qOutTask.bits := 0.U
  io.connNetwork_slave.data.qOutTask.valid := false.B
  io.connNetwork_slave.ctrl.stealReq.valid := false.B
  io.connNetwork_slave.ctrl.serveStealReq.valid := true.B

  // Create WriteTaskToNetwork
  val writeTaskToNetwork = Module(new WriteTaskToNetwork(taskWidth)).io
  writeTaskToNetwork.connNetwork <> io.connNetwork_master
  writeTaskToNetwork.s_axis_task <> queue_read.io.deq
  writeTaskToNetwork.fpgaId := 0.U
  writeTaskToNetwork.numTasksToStealOrServe := 0.U
  writeTaskToNetwork.startToken.valid := false.B
  writeTaskToNetwork.startToken.bits := 0.U


  val queue_is_reading = RegInit(false.B)

  new elastic.Fork(s_axis_slave.asLite){
    override def onFork(): Unit = {
      val toReadQueue = (queue_read.io.count < queueDepth.U) && !queue_is_reading 
      elastic.Demux(
        source = fork(in),
        sinks = Seq(queue_write.io.enq, queue_read.io.enq),
        select = fork(toReadQueue)
      )
    }
  }

  private val rPause = RegInit("hFFFFFFFFFFFFFFFF".U(64.W))
  private val rAddr = RegInit(0.U(64.W))
  private val maxLength = RegInit(0.U(64.W))
  private val fifoTailReg = RegInit(0.U(64.W))
  private val fifoHeadReg = RegInit(0.U(64.W))
  private val currLen = RegInit(0.U(64.W))
  private val procInterrupt = RegInit(0.U(64.W))
  private val addrShift = RegInit((log2Ceil(taskWidth / 8)).U)  
  
  regBlock.base(0x00)
  regBlock.reg(rPause, read = true, write = true, desc = "Register to indicate whether the FSM is paused or not.")
  regBlock.reg(rAddr, read = true, write = true, desc = "Base address of virtual FIFO")
  regBlock.reg(maxLength, read = true, write = true, desc = "Max length currently available for the FIFO")
  regBlock.reg(fifoTailReg, read = true, write = true, desc = "The tail register of the FIFO")
  regBlock.reg(fifoHeadReg, read = true, write = true, desc = "The head register of the FIFO")
  regBlock.reg(procInterrupt, read = true, write = true, desc = "A register that allows the processor to interrupt the FSM")
  regBlock.reg(currLen, read = true, write = true, desc = "A register that holds the current length of the FIFO")

  when(currLen > 32.U){
    io.serveRemote := true.B
  }.otherwise{
    io.serveRemote := false.B
  }
  
  val writeAddressDone = RegInit(false.B)
  val writeDataDone = RegInit(false.B)
  val writeTasksCounterWriting = RegInit(0.U(64.W))
  val writeTasksCounterBvalid = RegInit(0.U(64.W))

  val readAddressDone = RegInit(false.B)

  when(maxLength < (currLen + queueDepth.U)) {
    rPause := "hFFFFFFFFFFFFFFFF".U(64.W)
  }

  // Only execute when rPause is 0
  when(rPause === 0.U){

    // A process for the writer queue
    when(queue_write.io.deq.valid && !writeAddressDone && !(maxLength < (currLen + queueDepth.U))){
      io.m_axi.asFull.aw.valid := true.B
      io.m_axi.asFull.aw.bits.addr := rAddr + (fifoTailReg << addrShift)
      
      writeTasksCounterWriting := queue_write.io.count
      writeTasksCounterBvalid := queue_write.io.count

      io.m_axi.asFull.aw.bits.len := queue_write.io.count - 1.U
      when(queue_write.io.count  < queueDepth.U){
        io.m_axi.asFull.aw.bits.len := queue_write.io.count - 1.U
      }.otherwise{
        io.m_axi.asFull.aw.bits.len := (queueDepth.U) - 1.U
      }

      when(io.m_axi.asFull.aw.ready){
        writeAddressDone := true.B
      }
    }

    when(writeAddressDone && !writeDataDone){
      io.m_axi.asFull.w.valid := queue_write.io.deq.valid
      io.m_axi.asFull.w.bits.data := queue_write.io.deq.bits
      io.m_axi.asFull.w.bits.last := (writeTasksCounterWriting === 1.U)
      queue_write.io.deq.ready := io.m_axi.asFull.w.ready
      when(io.m_axi.asFull.w.ready && queue_write.io.deq.valid){
        writeTasksCounterWriting := writeTasksCounterWriting - 1.U
        when(writeTasksCounterWriting === 1.U){
          writeDataDone := true.B
        }
      }
    }

    when(writeAddressDone && writeDataDone &&(/**protect currLen in read*/ !readAddressDone)){
      // assert bready and wait for bvalid
      io.m_axi.asFull.b.ready := true.B
      when(io.m_axi.asFull.b.valid){
          writeAddressDone := false.B
          writeDataDone := false.B
          fifoTailReg := CircularQueueRegisterInc(fifoTailReg, maxLength, writeTasksCounterBvalid, 64)
          currLen := currLen + writeTasksCounterBvalid
      }
    }

    // A process for the reader queue (only start reading if the queue is empty and currLen > 0)
    // Also s_axis_slave valid should be false. When start reading set queue_is_reading to true
    when(queue_read.io.count === 0.U && currLen > 0.U && !s_axis_slave.asLite.valid && !readAddressDone){
      io.m_axi.asFull.ar.valid := true.B
      io.m_axi.asFull.ar.bits.addr := rAddr + (fifoHeadReg << addrShift)


      when(currLen < queueDepth.U){
        io.m_axi.asFull.ar.bits.len := currLen - 1.U
      }.otherwise{
        io.m_axi.asFull.ar.bits.len := (queueDepth.U) - 1.U
      }

      
      when(io.m_axi.asFull.ar.ready){
        readAddressDone := true.B
        queue_is_reading := true.B
      }
    }

    when(readAddressDone){
      io.m_axi.asFull.r.ready := queue_read.io.enq.ready
      queue_read.io.enq.valid := io.m_axi.asFull.r.valid
      queue_read.io.enq.bits := io.m_axi.asFull.r.bits.data
      when(io.m_axi.asFull.r.valid && queue_read.io.enq.ready){
        when(io.m_axi.asFull.r.bits.last){
          readAddressDone := false.B
          queue_is_reading := false.B
        }
        fifoHeadReg := CircularQueueRegisterInc(fifoHeadReg, maxLength, 1.U, 64)
        currLen := currLen - 1.U
      }
    }

    // Give the write token to the writeTaskToNetwork module when the queue_read has data
    when(queue_read.io.deq.valid){
      writeTaskToNetwork.startToken.valid := true.B
      writeTaskToNetwork.startToken.bits := queue_read.io.deq.bits
      writeTaskToNetwork.numTasksToStealOrServe := queue_read.io.count
    }
  }

  // // // Print for debugging
  val fireInCounter = RegInit(0.U(64.W))
  when(io.connNetwork_slave.data.availableTask.fire){
    fireInCounter := fireInCounter + 1.U
    //printf("fireInCounter: %d\n", fireInCounter)
  }

  val fireOutCounter = RegInit(0.U(64.W))
  when(io.connNetwork_master.data.qOutTask.fire){
    fireOutCounter := fireOutCounter + 1.U
    //printf("fireOutCounter: %d\n", fireOutCounter)
  }

  // print each 1000 cycles
  val cyclesCounter = RegInit(0.U(64.W))
  cyclesCounter := cyclesCounter + 1.U
  when(cyclesCounter === 100000.U){
    printf("_______\n")
    printf("FPGA ID: %d, fireInCounter: %d, fireOutCounter: %d\n", 0.U, fireInCounter, fireOutCounter)
    printf("_______\n")
    cyclesCounter := 0.U
  }


  // Reply to axi management operations.
  when(regBlock.rdReq) {
    regBlock.rdOk()
  }

  when(regBlock.wrReq) {
    regBlock.wrOk()
  }

}

object SpawnerServerEmitter extends App {
  emitVerilog(new SpawnerServer(256), Array("--target-dir", "output"))
}
