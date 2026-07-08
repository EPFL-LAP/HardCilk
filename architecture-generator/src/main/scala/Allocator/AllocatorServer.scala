package Allocator

import chisel3._
import chisel3.util._
import chisel3.ChiselEnum

import chext.amba.axi4
import axi4.lite.components.RegisterBlock

class AllocatorServerIO(
    dataWidth: Int,
    regBlock: RegisterBlock,
    sysAddressWidth: Int,
    pePortWidth: Int,
    outPorts: Int
) extends Bundle {
  val dataOut = Vec(outPorts, DecoupledIO(UInt(pePortWidth.W)))
  val axi_mgmt = axi4.lite.Slave(regBlock.cfgAxi)
  val read_address = DecoupledIO(UInt(sysAddressWidth.W))
  val read_data = Flipped(DecoupledIO(UInt(dataWidth.W)))
  val paused = Output(Bool())
}

class AllocatorServer(
    dataWidth: Int,        // memory/task/HBM-beat width (e.g. 256): read_data + packing basis
    sysAddressWidth: Int,  // HBM address width (e.g. 34): read_address + compact significant bits
    pePortWidth: Int,      // output pointer width to the PE (e.g. 64); addresses zero-extended
    burstLength: Int,
    numOutputPorts: Int
) extends Module {

  assert(burstLength <= 15) // 15 is equivalent to 16 beats

  // Continuations point to dataWidth-bit (dataWidth/8-byte) aligned task closures,
  // so the low log2(dataWidth/8) address bits are always zero and are dropped. Each
  // pointer packs into sysAddressWidth - log2(dataWidth/8) significant bits =>
  // dataWidth/that per beat (any remaining beat bits are left zero); on unpack it is
  // shifted back and zero-extended to the pePortWidth pointer the PE expects.
  private val addressAlignmentBits = log2Ceil(dataWidth / 8)
  private val continuationAddressBits = sysAddressWidth - addressAlignmentBits
  private val numPackedPerBeat = dataWidth / continuationAddressBits
  require(
    numPackedPerBeat % numOutputPorts == 0
  ) // We MUST be able to cleanly divide the number packed per beat by the number of output ports

  val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
  val io = IO(new AllocatorServerIO(dataWidth, regBlock, sysAddressWidth, pePortWidth, numOutputPorts))

  io.axi_mgmt.suggestName("0_S_AXI_MGMT")
  regBlock.s_axil <> io.axi_mgmt

  private val rAddr = RegInit(0.U(64.W))
  private val rPause = RegInit(0.U(64.W))
  private val avaialbleSize = RegInit(
    0.U(64.W)
  ) // Size is in chunks, not continuations

  regBlock.base(0x00)
  regBlock.reg(
    rPause,
    read = true,
    write = true,
    desc = "Register to indicate whether the FSM is paused or not."
  )
  regBlock.reg(
    rAddr,
    read = true,
    write = true,
    desc = "Base address of virtual continuation FIFO"
  )
  regBlock.reg(
    avaialbleSize,
    read = true,
    write = true,
    desc = "Availble address FIFO size"
  )
  io.paused := rPause =/= 0.U

  private val oustandingRequests = RegInit(0.U(2.W))
  private val inflightReadBeats = RegInit(0.U(6.W))
  private val returnedReadBeats = RegInit(0.U(6.W))

  val readyAddressChunksFIFO = Module(
    new Queue(UInt(256.W), 2 * (burstLength + 1))
  )

  // Whenever the readyAddressChunks passes below burstLength, request another set of chunks
  private val needReadBurst = RegInit(false.B)
  needReadBurst := readyAddressChunksFIFO.io.count +& inflightReadBeats < (burstLength + 1).U

  io.read_address.bits := (rAddr + (
    ((avaialbleSize - ((burstLength + 1) * numPackedPerBeat).U) >> log2Ceil(numPackedPerBeat)) << addressAlignmentBits
  ))(sysAddressWidth - 1, 0)
  when(
    oustandingRequests < 2.U && needReadBurst && rPause === 0.U
  ) {
    when(avaialbleSize >= ((burstLength + 1) * numPackedPerBeat).U) {
      io.read_address.valid := true.B
    }.otherwise {
      io.read_address.valid := false.B
      rPause := "hFFFFFFFFFFFFFFFF".U
    }

  }.otherwise {
    io.read_address.valid := false.B
  }

  when(io.read_address.fire && !(io.read_data.fire && returnedReadBeats === burstLength.U)) {
    oustandingRequests := oustandingRequests + 1.U
  }.elsewhen(!io.read_address.fire && io.read_data.fire && returnedReadBeats === burstLength.U) {
    oustandingRequests := oustandingRequests - 1.U
  }

  when(io.read_address.fire) {
    needReadBurst := false.B
    avaialbleSize := avaialbleSize - ((burstLength + 1) * numPackedPerBeat).U
  }

  when(io.read_data.fire) {
    returnedReadBeats := returnedReadBeats + 1.U
    when(returnedReadBeats === burstLength.U) {
      returnedReadBeats := 0.U
    }
  }

  // When a chunk comes in, add it to the queue
  readyAddressChunksFIFO.io.enq.valid := io.read_data.valid
  readyAddressChunksFIFO.io.enq.bits := io.read_data.bits
  io.read_data.ready := readyAddressChunksFIFO.io.enq.ready
  inflightReadBeats := inflightReadBeats + Mux(
    io.read_address.fire,
    (burstLength + 1).U,
    0.U
  ) - Mux(io.read_data.fire, 1.U, 0.U)

  // ALWAYS try to shove chunks into the ring
  val shallowPerOutputQueues = Seq.fill(numOutputPorts)(
    Module(new Queue(UInt(pePortWidth.W), numPackedPerBeat / numOutputPorts * 2))
  )
  val equalSizedBuffer = Wire(Decoupled(UInt((numOutputPorts * continuationAddressBits).W)))

  if (numPackedPerBeat != numOutputPorts) {
    val interBeatCounter =
      RegInit(0.U(log2Ceil(numPackedPerBeat / numOutputPorts).W))
    val intermediateInterbeatQueue = Module(
      new Queue(UInt((numOutputPorts * continuationAddressBits).W), 1, pipe = true)
    )
    equalSizedBuffer <> intermediateInterbeatQueue.io.deq

    intermediateInterbeatQueue.io.enq.valid := readyAddressChunksFIFO.io.deq.valid
    intermediateInterbeatQueue.io.enq.bits := readyAddressChunksFIFO.io.deq.bits(numOutputPorts * continuationAddressBits - 1, 0)
    readyAddressChunksFIFO.io.deq.ready := false.B

    when(interBeatCounter === 0.U) {
      readyAddressChunksFIFO.io.deq.ready := false.B
    }.otherwise {

      for (k <- 1 until numPackedPerBeat / numOutputPorts) {
        when(interBeatCounter === k.U) {
          intermediateInterbeatQueue.io.enq.bits := readyAddressChunksFIFO.io.deq
            .bits(
              numOutputPorts * continuationAddressBits * (k + 1) - 1,
              numOutputPorts * continuationAddressBits * k
            )
        }
      }
      when(interBeatCounter === (numPackedPerBeat / numOutputPorts - 1).U) {
        readyAddressChunksFIFO.io.deq.ready := intermediateInterbeatQueue.io.enq.ready
      }
    }

    when(intermediateInterbeatQueue.io.enq.fire) {
      interBeatCounter := interBeatCounter + 1.U
      when(interBeatCounter === (numPackedPerBeat / numOutputPorts - 1).U) {
        interBeatCounter := 0.U
      }
    }
  } else {
    equalSizedBuffer.valid := readyAddressChunksFIFO.io.deq.valid
    equalSizedBuffer.bits := readyAddressChunksFIFO.io.deq.bits(numOutputPorts * continuationAddressBits - 1, 0)
    readyAddressChunksFIFO.io.deq.ready := equalSizedBuffer.ready
  }

  val allShallowPortsReadyToAccept =
    shallowPerOutputQueues.map(_.io.enq.ready).reduce(_ && _)
  equalSizedBuffer.ready := false.B
  for (i <- 0 until numOutputPorts) {
    shallowPerOutputQueues(i).io.enq.valid := false.B
    shallowPerOutputQueues(i).io.enq.bits := Cat(
      0.U((pePortWidth - continuationAddressBits - addressAlignmentBits).W),
      equalSizedBuffer.bits(
        (i + 1) * continuationAddressBits - 1,
        i * continuationAddressBits
      ),
      0.U(addressAlignmentBits.W)
    )

    when(allShallowPortsReadyToAccept) {
      shallowPerOutputQueues(i).io.enq.valid := equalSizedBuffer.valid
      equalSizedBuffer.ready := true.B

    }

    shallowPerOutputQueues(i).io.deq <> io.dataOut(i)
  }

  // Reply to axi management operations.
  when(regBlock.rdReq) {
    regBlock.rdOk()
  }
  when(regBlock.wrReq) {
    regBlock.wrOk()
  }
}
