package Util

import chisel3._
import chisel3.util._

import chext.elastic


import chext.amba.axi4
import chext.amba.axi4s
import chext.amba.axi4s.Casts._
import chext.bundles.Bundle2



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
class remoteMemRequestReplyType(dataWidth: Int) extends Bundle {
  val fpgaId = UInt(4.W)
  val taskId = UInt(4.W)
  val globalFunctionId = globalFunctionIds()
  val readWriteData = UInt(dataWidth.W)
  val address = UInt(64.W)
  val isRequest = Bool()
  val axiSize = UInt((32).W) // Assuming max 256 bits data width
  val isWrite = Bool()
  val originatingLocalModuleId = UInt(4.W) // Assuming max 16 local modules

  val padding = UInt((512 - fpgaId.getWidth - taskId.getWidth - globalFunctionId.getWidth - readWriteData.getWidth - isRequest.getWidth - axiSize.getWidth - isWrite.getWidth - originatingLocalModuleId.getWidth - address.getWidth).W)  
  assert(this.getWidth == 512, "taskRequestReplyType width must be 512 bits")
}


// Create a configuration class for the RemoteStreamToMem
case class RemoteStreamToMemConfig(
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

// Define MemReq and MemResp bundles
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


class RemoteStreamToMem(val cfg: RemoteStreamToMemConfig) extends Module {
  import cfg._

  // Define the IO for the module
  val io = IO(new Bundle {
    val s_axis_remote = axi4s.Slave(axis_cfg)
    val m_axis_remote = axi4s.Master(axis_cfg)

    val mem_req_in = Flipped(Vec(localModulesCount, DecoupledIO(new MemReq(addressWidth, axiDataWidth))))
    val mem_resp_out = Vec(localModulesCount, DecoupledIO(new MemResp(256)))
    val write_resp_out = Vec(localModulesCount, DecoupledIO(new WriteResp()))

    val m_axi_mem = axi4.full.Master(axi4.Config(wId = 0, wAddr = addressWidth, wData = axiDataWidth))
    val fpgaIndex = Input(UInt(4.W))
  })

  // Create an arbiter to arbitrate between sending requests and responses
  val mfpga_master_arbiter = Module(new elastic.BasicArbiter(chiselTypeOf(io.m_axis_remote.asFull.bits), 2, chooserFn = elastic.Chooser.rr))

  mfpga_master_arbiter.io.select.nodeq()
  when(mfpga_master_arbiter.io.select.valid) {
    mfpga_master_arbiter.io.select.deq()
  }
  elastic.SourceBuffer(mfpga_master_arbiter.io.sink) <> io.m_axis_remote.asFull


  



  // The logic of requesting remote memory access

    // First create an arbiter for the incoming requests from local modules
    val local_modules_req_arbiter = Module (new elastic.BasicArbiter(chiselTypeOf(io.mem_req_in(0).bits), localModulesCount, chooserFn = elastic.Chooser.rr))
    local_modules_req_arbiter.io.select.nodeq()

    for (i <- 0 until localModulesCount) {
      local_modules_req_arbiter.io.sources(i) <> io.mem_req_in(i)
    }

    val buffered_requests = elastic.SourceBuffer(local_modules_req_arbiter.io.sink)
    val buffered_selects = elastic.SourceBuffer(local_modules_req_arbiter.io.select)
    private val joined_requests_with_index = Wire(DecoupledIO(new Bundle2(chiselTypeOf(local_modules_req_arbiter.io.sink.bits), chiselTypeOf(local_modules_req_arbiter.io.select.bits))))
    new elastic.Join(joined_requests_with_index) {
      protected def onJoin: Unit = {
        out._1 := join(buffered_requests)
        out._2 := join(buffered_selects)
      }
    }

    new elastic.Transform(joined_requests_with_index, mfpga_master_arbiter.io.sources(0)) {
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
          requestPacket.originatingLocalModuleId := in._2
          requestPacket.address := in._1.address
          requestPacket.padding := 0.U
          
          val destFPGAId = in._1.address(addressWidth - 5, 56)
          out.data := requestPacket.asUInt
          out.dest.get := destFPGAId
          out.last := true.B
          out.keep := "hFFFFFFFFFFFFFFFF".U
          out.strobe := "hFFFFFFFFFFFFFFFF".U
          
      }
    }

  // Create a Demux to route the incoming remote messages as either requests or responses

  val requests = Wire(chiselTypeOf(io.s_axis_remote))
  val responses = Wire(chiselTypeOf(io.s_axis_remote))
  new elastic.Fork(io.s_axis_remote.asFull) {
    override def onFork(): Unit = {
      val requests_responses_demux = elastic.Demux(
        source = fork(in),
        sinks =  Seq(responses.asFull, requests.asFull),
        // select based on the isRequest bit in TDATA
        select = fork(in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).isRequest)
      )
    }
  }

  // The logic of steering responses
    // Create a Demux to separate write and read responses
    val write_responses = Wire(chiselTypeOf(responses))
    val read_responses = Wire(chiselTypeOf(responses))
    new elastic.Fork(responses.asFull) {
      override def onFork(): Unit = {
        val write_read_demux = elastic.Demux(
          source = fork(in),
          sinks =  Seq(read_responses.asFull, write_responses.asFull),
          // select based on the isWrite bit in TDATA
          select = fork(in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).isWrite)
        )
      }
    }

    // Transform the read responses to MemResp format + destination ID extraction
    val read_responses_with_index = Wire(DecoupledIO(new Bundle2(chiselTypeOf(io.mem_resp_out(0).bits), UInt(log2Ceil(localModulesCount).W))))
    new elastic.Transform(read_responses.asFull, read_responses_with_index) {
      protected def onTransform: Unit = {
        val data = in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).readWriteData
        val originatingLocalModuleId = in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).originatingLocalModuleId // Extract originating local module ID  #TODO check bit range
        out._1.data := data
        out._2 := originatingLocalModuleId
      }
    }
    // Steer the read response to the correct local module
    val read_response_sinks = io.mem_resp_out.map(resp => {
      val sink = Wire(chiselTypeOf(resp))
      resp <> sink
      sink
    })
    new elastic.Fork(read_responses_with_index) {
      protected def onFork: Unit = {
        elastic.Demux(
          source = fork(in._1),
          sinks = read_response_sinks.toSeq,
          select = fork(in._2)
        )
      }
    }

    // Transform the write responses to WriteResp format + destination ID extraction
    val write_responses_with_index = Wire(DecoupledIO(new Bundle2(chiselTypeOf(io.write_resp_out(0).bits), UInt(log2Ceil(localModulesCount).W))))
    new elastic.Transform(write_responses.asFull, write_responses_with_index) {
      protected def onTransform: Unit = {
        val originatingLocalModuleId = in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).originatingLocalModuleId 
        out._1.success := 1.U
        out._2 := originatingLocalModuleId
      }
    }
    // Steer the write response to the correct local module
    val write_response_sinks = io.write_resp_out.map(resp => {
      val sink = Wire(chiselTypeOf(resp))
      resp <> sink
      sink
    })
    new elastic.Fork(write_responses_with_index) {
      protected def onFork: Unit = {
        elastic.Demux(
          source = fork(in._1),
          sinks = write_response_sinks.toSeq,
          select = fork(in._2)
        )
      }
    }

    
  // The logic of serving the remote requests

    // Create an arbiter to arbitrate between the responses to the read and write requests
    val requests_response_arbiter = Module (new elastic.BasicArbiter(chiselTypeOf(mfpga_master_arbiter.io.sources(1).bits), 2, chooserFn = elastic.Chooser.rr))
    requests_response_arbiter.io.select.nodeq()
    when(requests_response_arbiter.io.select.valid) {
      requests_response_arbiter.io.select.deq()
    }
    elastic.SourceBuffer(requests_response_arbiter.io.sink) <> mfpga_master_arbiter.io.sources(1)

    // Separate the write and read requests
    val write_requests = Wire(chiselTypeOf(requests))
    val read_requests = Wire(chiselTypeOf(requests))
    new elastic.Fork(requests.asFull) {
      override def onFork(): Unit = {
        val mem_write_read_demux = elastic.Demux(
          source = fork(in),
          sinks =  Seq(read_requests.asFull, write_requests.asFull),
          // select based on the isWrite bit in TDATA
          select = fork(in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).isWrite)
        )
      }
    }

    // Create a queue/buffer to save the IDs of the originating local modules and originating FPGA IDs for matching responses
    // The queue is bundles together the originating local module ID and originating FPGA ID
    // Make it a Chisel Bundle and Chisel Queue for easier handling
    class OriginatingInfo extends Bundle {
      val localModuleId = UInt(log2Ceil(localModulesCount).W)
      val remoteFPGAId = UInt(4.W)
    }
    val writes_originating_info_queue = Module(new Queue(new OriginatingInfo(), 16))
    val reads_originating_info_queue = Module(new Queue(new OriginatingInfo(), 16))


    // Forward the write requests to the m_axi_mem port for w and aw channels
    // Note: for simplicity, we assume single beat writes (len = 0)
    new elastic.Fork(elastic.SourceBuffer(write_requests.asFull)) {
      protected def onFork: Unit = {
        new elastic.Transform(fork(), io.m_axi_mem.aw) {
          protected def onTransform: Unit = {
            out := 0.U.asTypeOf(out)
            out.addr := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).address // Extract address  #TODO check bit range
            out.burst := axi4.BurstType.INCR
            out.size := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).axiSize //5.U // 256 bits = 2^5 bytes
            out.len := 0.U // Single beat write
          }
        }
        new elastic.Transform(fork(), io.m_axi_mem.w) {
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
            out.localModuleId := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).originatingLocalModuleId // Extract originating local module ID
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
            out.localModuleId := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).originatingLocalModuleId // Extract originating local module ID
            out.remoteFPGAId := in.data.asTypeOf(new remoteMemRequestReplyType(axiDataWidth)).fpgaId  // Extract remote FPGA ID 
          }
        }
      }
    }

    // Join the memory reads with the originating info
    private val joined_mem_reads_with_index = Wire(DecoupledIO(new Bundle2(chiselTypeOf(io.m_axi_mem.r.bits), chiselTypeOf(reads_originating_info_queue.io.deq.bits))))
    new elastic.Join(joined_mem_reads_with_index) {
      protected def onJoin: Unit = {
        out._1 := join(io.m_axi_mem.r)
        out._2 := join(reads_originating_info_queue.io.deq)
      }
    }
    

    // Forward the read responses from m_axi_mem r channel to the remote FPGA through the requests_response_arbiter
    new elastic.Transform(elastic.SourceBuffer(joined_mem_reads_with_index), requests_response_arbiter.io.sources(0)) {
      protected def onTransform: Unit = {
        // pop the read originating info
        val originatingInfo = in._2

        // Create a read response packet
        val responsePacket = Wire(new remoteMemRequestReplyType(axiDataWidth))
        responsePacket.fpgaId := io.fpgaIndex
        responsePacket.taskId := taskId.U(4.W)
        responsePacket.globalFunctionId := globalFunctionIds.remoteMemAccess
        responsePacket.isRequest := false.B
        responsePacket.isWrite := false.B
        responsePacket.readWriteData := in._1.data // The read data from memory
        responsePacket.axiSize := 0.U
        responsePacket.originatingLocalModuleId := originatingInfo.localModuleId
        responsePacket.address := 0.U
        responsePacket.padding := 0.U

        out.data := responsePacket.asUInt
        out.dest.get := originatingInfo.remoteFPGAId
        out.last := true.B
        out.keep := "hFFFFFFFFFFFFFFFF".U
        out.strobe := "hFFFFFFFFFFFFFFFF".U
      }
    }

    // Join the memory read responses with the writes originating info
    private val joined_mem_writes_with_index = Wire(DecoupledIO(new Bundle2(chiselTypeOf(io.m_axi_mem.b.bits), chiselTypeOf(writes_originating_info_queue.io.deq.bits))))
    new elastic.Join(joined_mem_writes_with_index) {
      protected def onJoin: Unit = {
        out._1 := join(io.m_axi_mem.b)
        out._2 := join(writes_originating_info_queue.io.deq)
      }
    }


    // Forward the write responses from m_axi_mem b channel to the remote FPGA through the requests_response_arbiter
    new elastic.Transform(elastic.SourceBuffer(joined_mem_writes_with_index), requests_response_arbiter.io.sources(1)) {
      protected def onTransform: Unit = {
        // pop the write originating info
        val originatingInfo = in._2

        // Create a write response packet
        val responsePacket = Wire(new remoteMemRequestReplyType(axiDataWidth))
        responsePacket.fpgaId := io.fpgaIndex
        responsePacket.taskId := taskId.U(4.W)
        responsePacket.globalFunctionId := globalFunctionIds.remoteMemAccess
        responsePacket.isRequest := false.B
        responsePacket.isWrite := true.B
        responsePacket.readWriteData := 0.U
        responsePacket.axiSize := 0.U
        responsePacket.originatingLocalModuleId := originatingInfo.localModuleId
        responsePacket.address := 0.U
        responsePacket.padding := 0.U
        out.data := responsePacket.asUInt
        out.dest.get := originatingInfo.remoteFPGAId
        out.last := true.B
        out.keep := "hFFFFFFFFFFFFFFFF".U
        out.strobe := "hFFFFFFFFFFFFFFFF".U
      }
    }
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