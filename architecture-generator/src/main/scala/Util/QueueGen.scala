package Util

import chisel3._
import chisel3.util._
import Util._

import chext.amba.axi4s
import chext.amba.axi4
import chext.elastic
import axi4s.Casts._
import axi4.lite.components.RegisterBlock
import axi4.Ops._
import chext.amba.axi4s
import axi4s.Casts._
import chext.elastic

class QueueGen extends  Module(){
  val queue = Module(new Queue(UInt(256.W), 4))

  val taskIn = IO(axi4s.Slave(chext.amba.axi4s.Config(wData = 256, onlyRV = true))).suggestName("taskIn")
  val taskOut = IO(axi4s.Master(chext.amba.axi4s.Config(wData = 256, onlyRV = true))).suggestName("taskOut")

  queue.io.enq.bits := taskIn.TDATA
  queue.io.enq.valid := taskIn.TVALID
  taskIn.TREADY := queue.io.enq.ready
  

  taskOut.TDATA := queue.io.deq.bits
  taskOut.TVALID := queue.io.deq.valid
  queue.io.deq.ready := taskOut.TREADY 
}

object QueueGenEmitter extends App {
  emitVerilog(new QueueGen)
}


