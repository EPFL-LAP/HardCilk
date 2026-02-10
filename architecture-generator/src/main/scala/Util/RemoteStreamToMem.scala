package Util

import chisel3._
import chisel3.util._

import chext.elastic


import chext.amba.axi4
import chext.amba.axi4s
import chext.amba.axi4s.Casts._
import chext.bundles.Bundle2
import chext.amba.axi4
import chext.amba.axi4.lite.components.RegisterBlock
import axi4.Ops._
import scala.annotation.nowarn




/**
  * A class that converts a stream message to a memory write/read
  * 
  * Inputs:
  *  - Stream interface from remote FPGAs (axi4s.Slave) (512 bits data width) + 4 bit TDEST
  *  - Read Request interface from modules that requires the remote memory access (MemReq) includes (address (64 bits)) address top bits indicate the remote FPGA id
  *  - Write Request interface from modules that requires the remote memory access (MemReq) includes (address (64 bits), data (Max 256 bits)) address top bits indicate the remote FPGA id
  * Outputs:
  *  - Stream interface to remote FPGAs (axi4s.Master) (512 bits data width) + 4 bit TDEST
  *  - Read Response interface to modules that requires the remote memory access (MemResp) includes (data (Max 256 bits))
  *  - Write Response interface to modules that requires the remote memory access (WriteResp)
  *  - AXI Port to the local memory system (axi4.full.Master)
  * 
  * Functionality:
  *  - The address top bytes indicate the remote FPGA id
  *  - The module receives the read/write requests from local modules, and sends them to the remote FPGA through the stream interface
  *  - The module receives the read/write responses from the remote FPGA through the stream interface, and sends them to the local modules
  *  - The module handles multiple remote FPGAs by using the TDEST field in the stream interface
  *  - The module uses multi-level arbiters to handle multiple requests and responses #TODO
  *  
  */

import HardCilk.globalFunctionIds
import os.read


//////////////////////////////////////////////////////////////////
/////////// Class types and congigurations
case class RemoteStreamToMemConfig(
    // A configuration class for the RemoteStreamToMem
    addressWidth: Int,
    localModulesCount: Int,
    taskId: Int,
    axiDataWidth: Int, // It is good to have this so that the axi transaction is correctly generated
    axis_cfg: axi4s.Config = axi4s.Config(wData = 512, wDest = 4)
    ) {
      require(localModulesCount >= 0 && localModulesCount <= 16, "localModulesCount must be between 0 and 16")
      require(addressWidth == 64, "addressWidth must be either 64")
      require(axiDataWidth <= 256, "axiDataWidth must be <= 256")
}
class MemReq(addressWidth: Int, dataWidth: Int) extends Bundle {
  val address = UInt(addressWidth.W)
  val data = UInt(dataWidth.W)
  val isWrite = Bool()
  val axiSize = UInt((dataWidth / 8).W) // Assuming max 256 bits data width
}
class MemResp(dataWidth: Int) extends Bundle {
  val data = UInt(dataWidth.W)
}
class WriteResp() extends Bundle {
  val success = Bool()
}
//////////////////////////////////////////////////////////////////


class remoteMemRequestReplyType(dataWidth: Int) extends Bundle {
  val fpgaId = UInt(4.W)
  val taskId = UInt(4.W)
  val globalFunctionId = globalFunctionIds()
  val readWriteData = UInt(dataWidth.W)
  val address = UInt(64.W)
  val isRequest = Bool()
  val axiSize = UInt((32).W) // Assuming max 256 bits data width
  val isWrite = Bool()

  val padding = UInt((512 - fpgaId.getWidth - taskId.getWidth - globalFunctionId.getWidth - readWriteData.getWidth - isRequest.getWidth - axiSize.getWidth - isWrite.getWidth - address.getWidth).W)  
  assert(this.getWidth == 512, "taskRequestReplyType width must be 512 bits")
}


class RemoteStreamToMem(val cfg: RemoteStreamToMemConfig) extends Module {
  import cfg._

  val inFlightMax = 256

  //////////////////////////////////////////////////////////////////
  /////////// RegBlock DEFENITION (For collecting stats from host)
  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
  val received_mem_req_count = RegInit(0.U(64.W))
  val received_mem_ack_count = RegInit(0.U(64.W))
  val sent_mem_req_count = RegInit(0.U(64.W))
  val sent_mem_ack_count = RegInit(0.U(64.W))
  val memoryWritesInFlight = RegInit(0.U(64.W))
  val receiveQueueSize = RegInit(0.U(64.W))
  val readyValidIndicator = RegInit(0.U(64.W))
  regBlock.base(0x00)
  regBlock.reg(received_mem_req_count, read = true, write = true, desc = "Number of memory requests received")
  regBlock.reg(received_mem_ack_count, read = true, write = true, desc = "Number of memory requests sent acknowledged")
  regBlock.reg(sent_mem_req_count, read = true, write = true, desc = "Number of memory requests sent")
  regBlock.reg(sent_mem_ack_count, read = true, write = true, desc = "Number of memory requests received acknowledged")
  regBlock.reg(memoryWritesInFlight, read = true, write = true, desc = "Number of memory writes in flight")
  regBlock.reg(receiveQueueSize, read = true, write = true, desc = "Size of the receive queue")
  regBlock.reg(readyValidIndicator, read = true, write = true, desc = "Ever received a read request")
  when(regBlock.rdReq) {
    regBlock.rdOk()
  }
  when(regBlock.wrReq) {
    regBlock.wrOk()
  }
  //////////////////////////////////////////////////////////////////


  //////////////////////////////////////////////////////////////////
  /////////// IO DEFENITIONS
  val io = IO(new Bundle {
    val s_axis_remote = axi4s.Slave(axis_cfg)
    val m_axis_remote = axi4s.Master(axis_cfg)

    val mem_req_in = Flipped(Vec(localModulesCount, DecoupledIO(new MemReq(addressWidth, axiDataWidth))))
    val mem_resp_out = Vec(localModulesCount, DecoupledIO(new MemResp(axiDataWidth)))
    val write_resp_out = Vec(localModulesCount, DecoupledIO(new WriteResp()))

    val m_axi_mem = axi4.full.Master(axi4.Config(wId = 0, wAddr = addressWidth, wData = axiDataWidth))
    val fpgaIndex = Input(UInt(4.W))
    val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  })
  io.axi_mgmt.suggestName("S_AXI_MGMT")
  io.axi_mgmt :=> regBlock.s_axil
  //////////////////////////////////////////////////////////////////


  //////////////////////////////////////////////////////////////////
  /////////// mfpga arbiter used for both requests and replies
  val mfpga_master_arbiter = Module(new elastic.BasicArbiter(chiselTypeOf(io.m_axis_remote.asFull.bits), 3, chooserFn = elastic.Chooser.rr))

  mfpga_master_arbiter.io.select.nodeq()
  when(mfpga_master_arbiter.io.select.valid) {
    mfpga_master_arbiter.io.select.deq()
  }
  elastic.SourceBuffer(mfpga_master_arbiter.io.sink, 64) <> io.m_axis_remote.asFull
  //////////////////////////////////////////////////////////////////


  //////////////////////////////////////////////////////////////////
  /////////// The logic of requesting remote memory access
  // First create an arbiter for the incoming requests from local modules
  val local_modules_req_arbiter = Module (new elastic.BasicArbiter(chiselTypeOf(io.mem_req_in(0).bits), localModulesCount, chooserFn = elastic.Chooser.rr))
  local_modules_req_arbiter.io.select.nodeq()
  for (i <- 0 until localModulesCount) {
    local_modules_req_arbiter.io.sources(i) <> io.mem_req_in(i)
  }

  

  // Create a master port to send the requests to the remote FPGA through the arbiter
  val mfpga_master_requests = Wire(chiselTypeOf(mfpga_master_arbiter.io.sources(0)))
  elastic.SinkBuffer(mfpga_master_arbiter.io.sources(0), inFlightMax) <> mfpga_master_requests

  // Join the selected request index with the request
  private val joined_requests_with_index = Wire(DecoupledIO(new Bundle2(chiselTypeOf(local_modules_req_arbiter.io.sink.bits), chiselTypeOf(local_modules_req_arbiter.io.select.bits))))
  new elastic.Join(joined_requests_with_index) {
    protected def onJoin: Unit = {
      out._1 := join(local_modules_req_arbiter.io.sink)
      out._2 := join(local_modules_req_arbiter.io.select)
    }
  }


  // Create two local queues to carry the originating local module info of sent requests
  // The size of this module indicates the cap of inflight requests! (r+w)
  val local_read_modules_info = Module(new Queue(UInt(4.W), 8));
  val local_write_modules_info = Module(new Queue(UInt(4.W), inFlightMax));




  new elastic.Fork(joined_requests_with_index){
    @nowarn("cat=unused")
    override def onFork(): Unit = {
      new elastic.Transform(fork(in), mfpga_master_requests) {
        protected def onTransform: Unit = {
          // Here we create a request to be sent to the remote FPGA
            val requestPacket = Wire(new remoteMemRequestReplyType(dataWidth = axiDataWidth))
            requestPacket.fpgaId := io.fpgaIndex
            requestPacket.taskId := taskId.U(4.W)
            requestPacket.globalFunctionId := globalFunctionIds.remoteMemAccess
            requestPacket.isRequest := true.B
            requestPacket.isWrite := in._1.isWrite
            requestPacket.readWriteData := in._1.data
            requestPacket.axiSize := in._1.axiSize
            requestPacket.address := in._1.address
            requestPacket.padding := 0.U
            
            val destFPGAId = in._1.address(addressWidth - 5, 56)
            out.data := requestPacket.asUInt
            out.dest.get := destFPGAId
            out.last := true.B
            out.keep := Fill(out.keep.getWidth, 1.U(1.W))
            out.strobe := Fill(out.strobe.getWidth, 1.U(1.W))
        }
      }
      val s_axis_demux= elastic.Demux(
        source = fork(in._2),
        sinks =  Seq(local_read_modules_info.io.enq, local_write_modules_info.io.enq),
        select = fork(in._1.isWrite)
      )
    }
  }

  // Create a Demux to route the incoming remote messages as either rd/wr responses or rd/wr requests
  val write_responses = Wire(chiselTypeOf(io.s_axis_remote))
  val read_responses = Wire(chiselTypeOf(io.s_axis_remote))
  val write_requests = Wire(chiselTypeOf(io.s_axis_remote))
  val read_requests = Wire(chiselTypeOf(io.s_axis_remote))

  val receiveQueue = Module(new Queue(chiselTypeOf(io.s_axis_remote.asFull.bits), 1))
  io.s_axis_remote.asFull <> receiveQueue.io.enq
  receiveQueueSize := receiveQueue.io.count

  new elastic.Fork(elastic.SourceBuffer(receiveQueue.io.deq,1)) {
    @nowarn("cat=unused")
    override def onFork(): Unit = {
      val s_axis_demux= elastic.Demux(
        source = fork(in),
        sinks =  Seq(elastic.SinkBuffer(read_responses.asFull, inFlightMax + 16), 
                      elastic.SinkBuffer(write_responses.asFull,inFlightMax + 16), 
                      elastic.SinkBuffer(read_requests.asFull,inFlightMax + 16), 
                      elastic.SinkBuffer(write_requests.asFull, inFlightMax + 16)),
        select = elastic.SourceBuffer (fork(Cat(
          in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).isRequest,
          in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).isWrite 
        )), 32)
      )
    }
  }



    // Transform the read responses to MemResp format + destination ID extraction

    // Steer the read response to the correct local module
    val read_response_sinks = io.mem_resp_out.map(resp => {
      val sink = Wire(chiselTypeOf(resp))
      resp <> sink
      sink
    })
    
    val extracted_read_response = Wire(chiselTypeOf((read_response_sinks.head)))
    new elastic.Transform(read_responses.asFull, extracted_read_response){
      protected def onTransform: Unit = {
        out := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).readWriteData.asTypeOf(new MemResp(axiDataWidth))
      }
    }
    elastic.Demux(
      source = extracted_read_response,
      sinks = read_response_sinks.toSeq,
      select = local_read_modules_info.io.deq
    )
  

    // Transform the write responses to WriteResp format + destination ID extraction
    // Steer the write response to the correct local module
    val write_response_sinks = io.write_resp_out.map(resp => {
      val sink = Wire(chiselTypeOf(resp))
      resp <> sink
      sink
    })
    
    val extracted_write_response = Wire(chiselTypeOf((write_response_sinks.head)))
    new elastic.Transform(write_responses.asFull, extracted_write_response){
      protected def onTransform: Unit = {
        out := 1.U.asTypeOf(out)
      }
    }    


    // TODO: Check if this demux is actually ready to receive and 
    // what can be blocking it.
    // Make triangle project while you syntheize for this!
    elastic.Demux(
      source = elastic.SourceBuffer(extracted_write_response),
      sinks = write_response_sinks.toSeq,
      select = local_write_modules_info.io.deq
    )
   
  // The logic of serving the remote requests
    // Create a queue/buffer to save the IDs of the originating local modules and originating FPGA IDs for matching responses
    // The queue is bundles together the originating local module ID and originating FPGA ID
    // Make it a Chisel Bundle and Chisel Queue for easier handling
    class OriginatingInfo extends Bundle {
      val remoteFPGAId = UInt(4.W)
    }
    val writes_originating_info_queue = Module(new Queue(new OriginatingInfo(), inFlightMax + 16))
    val reads_originating_info_queue = Module(new Queue(new OriginatingInfo(), inFlightMax + 16))


    // Forward the write requests to the m_axi_mem port for w and aw channels
    // Note: for simplicity, we assume single beat writes (len = 0)
    new elastic.Fork(elastic.SourceBuffer(write_requests.asFull, 1)) {
      protected def onFork: Unit = {
        new elastic.Transform(fork(), elastic.SinkBuffer(io.m_axi_mem.aw, inFlightMax)) {
          protected def onTransform: Unit = {
            out := 0.U.asTypeOf(out)
            out.addr := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).address // Extract address  #TODO check bit range
            out.burst := axi4.BurstType.INCR
            out.size := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).axiSize //5.U // 256 bits = 2^5 bytes
            out.len := 0.U // Single beat write
          }
        }
        new elastic.Transform(fork(), elastic.SinkBuffer(io.m_axi_mem.w, inFlightMax)) {
          protected def onTransform: Unit = {
            out := 0.U.asTypeOf(out)
            out.data := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).readWriteData // Extract data
            out.last := true.B
            out.strb := Fill(axiDataWidth/8, 1.U(1.W)) //Strb is always all the data width
          }
        }
        // Enqueue the originating info for matching responses later
        new elastic.Transform(fork(), writes_originating_info_queue.io.enq) {
          protected def onTransform: Unit = {
            out := 0.U.asTypeOf(out)
            out.remoteFPGAId := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).fpgaId // Extract remote FPGA ID #TODO check bit range
          }
        }
      }
    }

    // Forward the read requests to the m_axi_mem port for ar channel
    new elastic.Fork(elastic.SourceBuffer(read_requests.asFull)) {
      protected def onFork: Unit = {
        new elastic.Transform(fork(), io.m_axi_mem.ar) {
          protected def onTransform: Unit = {
            out := 0.U.asTypeOf(out)
            out.addr := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).address // Extract address  #TODO check bit range
            out.burst := axi4.BurstType.INCR
            out.size := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).axiSize
            out.len := 0.U // Single beat read
          }
        }
        // Enqueue the originating info for matching responses later
        new elastic.Transform(fork(), reads_originating_info_queue.io.enq) {
          protected def onTransform: Unit = {
            out := 0.U.asTypeOf(out)
            out.remoteFPGAId := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).fpgaId  // Extract remote FPGA ID 
          }
        }
      }
    }

    // Forward the read responses from m_axi_mem r channel to the remote FPGA through the requests_response_arbiter
    new elastic.Transform(elastic.SourceBuffer(io.m_axi_mem.r, inFlightMax), mfpga_master_arbiter.io.sources(1)) {
      protected def onTransform: Unit = {
        // pop the read originating info

        // Create a read response packet
        val responsePacket = Wire(new remoteMemRequestReplyType(axiDataWidth))
        responsePacket.fpgaId := io.fpgaIndex
        responsePacket.taskId := taskId.U(4.W)
        responsePacket.globalFunctionId := globalFunctionIds.remoteMemAccess
        responsePacket.isRequest := false.B
        responsePacket.isWrite := false.B
        responsePacket.readWriteData := in.data // The read data from memory
        responsePacket.axiSize := 0.U
        responsePacket.address := 0.U
        responsePacket.padding := 0.U

        out.data := responsePacket.asUInt
        out.dest.get := reads_originating_info_queue.io.deq.bits.asUInt
        out.last := true.B
        out.keep := Fill(out.keep.getWidth, 1.U(1.W))  
        out.strobe := Fill(out.strobe.getWidth, 1.U(1.W))
      }
    }
    reads_originating_info_queue.io.deq.ready := mfpga_master_arbiter.io.sources(1).fire


    // Forward the write responses from m_axi_mem b channel to the remote FPGA through the requests_response_arbiter
    new elastic.Transform(elastic.SourceBuffer(io.m_axi_mem.b, inFlightMax), mfpga_master_arbiter.io.sources(2)) {
      protected def onTransform: Unit = {
      
        // Create a write response packet
        val responsePacket = Wire(new remoteMemRequestReplyType(axiDataWidth))
        responsePacket.fpgaId := io.fpgaIndex
        responsePacket.taskId := taskId.U(4.W)
        responsePacket.globalFunctionId := globalFunctionIds.remoteMemAccess
        responsePacket.isRequest := false.B
        responsePacket.isWrite := true.B
        responsePacket.readWriteData := 0.U
        responsePacket.axiSize := 0.U
        responsePacket.address := 0.U
        responsePacket.padding := 0.U
        out.data := responsePacket.asUInt
        out.dest.get := writes_originating_info_queue.io.deq.bits.asUInt
        out.last := true.B
        out.keep := Fill(out.keep.getWidth, 1.U(1.W))
        out.strobe := Fill(out.strobe.getWidth, 1.U(1.W))
      }
    }
    writes_originating_info_queue.io.deq.ready := mfpga_master_arbiter.io.sources(2).fire



    //////////////////////////////////////////////////////////////////
    /////////// STAT COLLECTION
    when(joined_requests_with_index.fire){
      sent_mem_req_count := sent_mem_req_count + 1.U
    }

    when(write_requests.asFull.fire){
      received_mem_req_count := received_mem_req_count + 1.U
    }

    when(write_responses.asFull.fire){
      received_mem_ack_count := received_mem_ack_count + 1.U
    }

    when(mfpga_master_arbiter.io.sources(2).fire){
      sent_mem_ack_count := sent_mem_ack_count + 1.U
    }

    when(io.m_axi_mem.aw.fire && ~io.m_axi_mem.b.fire){
      memoryWritesInFlight := memoryWritesInFlight + 1.U
    }

    when(io.m_axi_mem.b.fire && ~io.m_axi_mem.aw.fire){
      memoryWritesInFlight := memoryWritesInFlight - 1.U
    }


  // val io = IO(new Bundle {
  //   val s_axis_remote = axi4s.Slave(axis_cfg)
  //   val m_axis_remote = axi4s.Master(axis_cfg)

  //   val mem_req_in = Flipped(Vec(localModulesCount, DecoupledIO(new MemReq(addressWidth, axiDataWidth))))
  //   val mem_resp_out = Vec(localModulesCount, DecoupledIO(new MemResp(axiDataWidth)))
  //   val write_resp_out = Vec(localModulesCount, DecoupledIO(new WriteResp()))

  //   val m_axi_mem = axi4.full.Master(axi4.Config(wId = 0, wAddr = addressWidth, wData = axiDataWidth))
  //   val fpgaIndex = Input(UInt(4.W))
  //   val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  // })

    val writeResponsesSinksRV = Wire(Vec(localModulesCount * 2, UInt((1).W)))         
    var j = 0
    
    for(i <- 0 until localModulesCount){
      writeResponsesSinksRV(j) := write_response_sinks(i).valid
      writeResponsesSinksRV(j + 1) := write_response_sinks(i).ready
      j += 2
    }


  

    readyValidIndicator := Cat(
      writeResponsesSinksRV.asUInt,
      local_write_modules_info.io.deq.valid,
      local_write_modules_info.io.deq.ready,
      io.s_axis_remote.TVALID,
      io.s_axis_remote.TREADY,
      io.m_axis_remote.TVALID,
      io.m_axis_remote.TREADY,
      io.m_axi_mem.w.valid,
      io.m_axi_mem.w.ready,
      io.m_axi_mem.r.valid,
      io.m_axi_mem.r.ready,
      io.m_axi_mem.b.valid,
      io.m_axi_mem.b.ready,
      io.m_axi_mem.aw.valid,
      io.m_axi_mem.aw.ready,
      io.m_axi_mem.ar.valid,
      io.m_axi_mem.ar.ready,
      local_modules_req_arbiter.io.select.valid,
      local_modules_req_arbiter.io.select.ready,
      local_modules_req_arbiter.io.sink.valid,
      local_modules_req_arbiter.io.sink.ready,
      mfpga_master_arbiter.io.sources(0).valid,
      mfpga_master_arbiter.io.sources(0).ready,
      mfpga_master_arbiter.io.sources(1).valid,
      mfpga_master_arbiter.io.sources(1).ready,
      mfpga_master_arbiter.io.sources(2).valid,
      mfpga_master_arbiter.io.sources(2).ready,
      write_responses.TVALID,
      write_responses.TREADY,
      read_responses.TVALID,
      read_responses.TREADY,
      read_requests.TVALID,
      read_requests.TREADY,
      write_requests.TVALID,
      write_requests.TREADY,
      receiveQueue.io.enq.valid,
      receiveQueue.io.enq.ready,
      receiveQueue.io.deq.valid,
      receiveQueue.io.deq.ready
    )


    //////////////////////////////////////////////////////////////////
}


object RemoteStreamToMemEmitter extends App {
  emitVerilog(new RemoteStreamToMem(new  RemoteStreamToMemConfig(
    addressWidth = 64,
    localModulesCount = 4,
    axiDataWidth = 256,
    taskId = 0
  )),
    Array(
      "--target-dir=output/remoteStreamToMem/"
    )
  )
}