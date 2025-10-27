/**
  * This module is essential to wrap the Argument Notifier Server in case of
  * mFPGA target. It adds the necessary AXI streaming interfaces to pass the
  * addresses of remote arguments to the network to be passed to the correct
  * FPGA that the continuation resides on.
  * It also adds an arbiter to receive arguments from the local network or 
  * the ones coming from a remote FPGA.
  */

package ArgumentNotifier




import chisel3._
import chisel3.util._

import chext.amba.axi4
import chext.elastic

import chext.amba.axi4s
import axi4s.Casts._
import Util._

class ArgumentServerMfpgaWrapperIO(
    taskWidth: Int,
    counterWidth: Int,
    sysAddressWidth: Int,
    wId: Int,
    mfpgaSupport: Boolean
) extends Bundle {
  val connNetwork = Flipped(DecoupledIO(UInt(sysAddressWidth.W)))
  val connStealNtw = Flipped(new SchedulerNetworkClientIO(taskWidth))

  val m_axi_counter = axi4.full.Master(cfg = axi4.Config(wId, sysAddressWidth, taskWidth))
  val m_axi_task = axi4.full.Master(cfg = axi4.Config(wId, sysAddressWidth, taskWidth))

  val done = Output(Bool())


  val fpgaIndexInputReg = if (mfpgaSupport) Some(Input(UInt(32.W))) else None


  val axisCfgTaskAndReq =
    axi4s.Config(wData = 512, wDest = 4) // task plus 8 bits for task valid and request value (8 bits for AXIS compat)
  val m_axis_remote = if (mfpgaSupport) Some(axi4s.Master(axisCfgTaskAndReq)) else None
  val s_axis_remote = if (mfpgaSupport) Some(axi4s.Slave(axisCfgTaskAndReq)) else None

}

class ArgumentServerMfpgaWrapper(
    taskWidth: Int,
    counterWidth: Int,
    sysAddressWidth: Int,
    tagBitsShift: Int,
    wId: Int,
    multiDecrease: Boolean = false,
    mfpgaSupport: Boolean
) extends Module {

  // Instantiate a new ArgumentServer
  val argServer = Module(
    new ArgumentServer(
      taskWidth = taskWidth,
      counterWidth = counterWidth,
      sysAddressWidth = sysAddressWidth,
      tagBitsShift = tagBitsShift,
      wId = wId,
      multiDecrease = multiDecrease
    )
  )


  val io = IO(new ArgumentServerMfpgaWrapperIO(taskWidth, counterWidth, sysAddressWidth, wId, mfpgaSupport))

  // Connect the ArgumentServer to the ArgumentServerMfpgaWrapper
  argServer.io.m_axi_counter <> io.m_axi_counter
  argServer.io.m_axi_task <> io.m_axi_task
  io.done := argServer.io.done
  io.connStealNtw <> argServer.io.connStealNtw

  if (!mfpgaSupport) {
    io.connNetwork <> argServer.io.connNetwork
  }

  if (mfpgaSupport) {


    // Create two 16 element queue to carry addresses
    val remoteQSend = Module(new Queue(UInt(sysAddressWidth.W), 16))
    val remoteQRec = Module(new Queue(UInt(sysAddressWidth.W), 16))
    val localQ = Module(new Queue(UInt(sysAddressWidth.W), 16))

    remoteQSend.io.enq.valid := false.B
    remoteQSend.io.enq.bits := 0.U
    remoteQSend.io.deq.ready := false.B
    localQ.io.enq.valid := false.B 
    localQ.io.enq.bits := 0.U
    localQ.io.deq.ready := false.B

    

    // Create an arbiter to arbiterate between localQ and remoteQRec
    val arbiterToLocalServer = Module(
      new elastic.BasicArbiter(chiselTypeOf(io.connNetwork.bits), 2, chooserFn = elastic.Chooser.rr)
    )
    arbiterToLocalServer.io.select.nodeq()
    when(arbiterToLocalServer.io.select.valid) {
      arbiterToLocalServer.io.select.deq()
    }

    arbiterToLocalServer.io.sources(0) <> localQ.io.deq
    arbiterToLocalServer.io.sources(1) <> remoteQRec.io.deq
    arbiterToLocalServer.io.sink <> argServer.io.connNetwork

    // connect the enq of the remoteQRec to s_axis_remote
    io.s_axis_remote.get.TREADY := remoteQRec.io.enq.ready
    remoteQRec.io.enq.valid := io.s_axis_remote.get.TVALID
    remoteQRec.io.enq.bits := io.s_axis_remote.get.TDATA

    io.m_axis_remote.get.asFull.bits := 0.U(io.m_axis_remote.get.asFull.bits.getWidth.W).asTypeOf(io.m_axis_remote.get.asFull.bits)

    // connect the deq of the remoteQSend to m_axis_remote
    remoteQSend.io.deq.ready := io.m_axis_remote.get.TREADY 
    io.m_axis_remote.get.TVALID := remoteQSend.io.deq.valid
    io.m_axis_remote.get.TDATA := Cat(Fill(13, 0.U(1.W)), 1.U(1.W), Fill(512 - 14 - 64, 0.U(1.W)) ,remoteQSend.io.deq.bits)
    io.m_axis_remote.get.TDEST.get := remoteQSend.io.deq.bits(sysAddressWidth - 5, 56) // Important bug fix here, TDEST sets in specific address range

    // When connNetwork is valid, check the upper 8 bits of the address
    // If it is equal to rFPGAIndex, then it is a local address, so enqueue it to localQ
    // else enqueue it to remoteQSend and set TDEST to the value of the 8 bits

    new elastic.Fork(io.connNetwork) {
      override def onFork(): Unit = {
        elastic.Demux(
          source = fork(in),
          sinks =  Seq(remoteQSend.io.enq, localQ.io.enq),
          select = fork(in(sysAddressWidth - 5, 56) === io.fpgaIndexInputReg.get) // In the upper byte of the address, select the lower 4 bits
        )
      }
    }

  }

}


