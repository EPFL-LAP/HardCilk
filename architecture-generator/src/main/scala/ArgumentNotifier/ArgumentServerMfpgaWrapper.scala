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
import HardCilk.globalFunctionIds
import chext.amba.axi4
import chext.amba.axi4.lite.components.RegisterBlock
import axi4.Ops._


class remoteArgumentNotificationType extends Bundle {
  val fpgaId = UInt(4.W)
  val taskId = UInt(4.W)
  val globalFunctionId = globalFunctionIds()
  val address = UInt(64.W)
  val sendCounterReset = UInt(1.W) // For more than two fpgas have to be handled differently. This is done only because AlveoLink does not seem to work correctly!
  val padding = UInt((512 - fpgaId.getWidth - taskId.getWidth - globalFunctionId.getWidth - address.getWidth - sendCounterReset.getWidth).W)  
  assert(this.getWidth == 512, "remoteArgumentNotificationType width must be 512 bits")
}

class ArgumentServerMfpgaWrapperIO(
    taskWidth: Int,
    counterWidth: Int,
    sysAddressWidth: Int,
    wId: Int,
    mfpgaSupport: Boolean,
    regBlock: Option[RegisterBlock]
) extends Bundle {
  val connNetwork = Flipped(DecoupledIO(UInt(sysAddressWidth.W)))
  val connStealNtw = Flipped(new SchedulerNetworkClientIO(taskWidth))

  val m_axi_counter = axi4.full.Master(cfg = axi4.Config(wId, sysAddressWidth, taskWidth))
  val m_axi_task = axi4.full.Master(cfg = axi4.Config(wId, sysAddressWidth, taskWidth))

  val done = Output(Bool())


  val fpgaIndexInputReg = if (mfpgaSupport) Some(Input(UInt(32.W))) else None


  val axisCfgTaskAndReq =
    axi4s.Config(wData = 512, wDest = 4) 
  val m_axis_remote = if (mfpgaSupport) Some(axi4s.Master(axisCfgTaskAndReq)) else None
  val s_axis_remote = if (mfpgaSupport) Some(axi4s.Slave(axisCfgTaskAndReq)) else None

  val axi_mgmt = if(mfpgaSupport) Some(axi4.lite.Slave(regBlock.get.cfgAxi)) else None
}

class ArgumentServerMfpgaWrapper(
    taskWidth: Int,
    counterWidth: Int,
    sysAddressWidth: Int,
    tagBitsShift: Int,
    wId: Int,
    taskID: Int,
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

  val regBlock = if (mfpgaSupport) Some(new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)) else None



  val io = IO(new ArgumentServerMfpgaWrapperIO(taskWidth, counterWidth, sysAddressWidth, wId, mfpgaSupport, regBlock))

  // Connect the ArgumentServer to the ArgumentServerMfpgaWrapper
  argServer.io.m_axi_counter <> io.m_axi_counter
  argServer.io.m_axi_task <> io.m_axi_task
  io.done := argServer.io.done
  io.connStealNtw <> argServer.io.connStealNtw

  if (!mfpgaSupport) {
    io.connNetwork <> argServer.io.connNetwork
  }

  if (mfpgaSupport) {

    val received_notifications_count = RegInit(0.U(64.W))
    val sent_notifications_count = RegInit(0.U(64.W))

    val inFlightMax = 32

    val sending_counter = RegInit(inFlightMax.U(64.W))
    val receive_counter = RegInit(inFlightMax.U(64.W))

    val m_axis_argument_notification = Wire(chiselTypeOf(io.m_axis_remote.get))
    val m_axis_reset_send_counter = Wire(chiselTypeOf(io.m_axis_remote.get))

    val s_axis_argument_notification = Wire(chiselTypeOf(io.s_axis_remote.get))
    val s_axis_reset_send_counter = Wire(chiselTypeOf(io.s_axis_remote.get))
    
    val readyValidIndicator = RegInit(0.U(64.W))

    regBlock.get.base(0x00)
    regBlock.get.reg(received_notifications_count, read = true, write = true, desc = "Number of notifications received")
    regBlock.get.reg(sent_notifications_count, read = true, write = true, desc = "Number of notifications sent")
    regBlock.get.reg(readyValidIndicator, read = true, write = true, desc = "A register that reflects ports ready sginals")


    // Reply to axi management operations.
    when(regBlock.get.rdReq) {
      regBlock.get.rdOk()
    }

    when(regBlock.get.wrReq) {
      regBlock.get.wrOk()
    }

    // Create two 16 element queue to carry addresses
    val remoteQSend = Module(new Queue(UInt(sysAddressWidth.W), inFlightMax * 2))
    val remoteQRec = Module(new Queue(UInt(sysAddressWidth.W), inFlightMax * 2))
    val localQ = Module(new Queue(UInt(sysAddressWidth.W), inFlightMax * 2))

    when(m_axis_reset_send_counter.asFull.fire){
        receive_counter := inFlightMax.U
    }

    when(s_axis_reset_send_counter.asFull.fire){
        sending_counter := inFlightMax.U
    }

    when(!s_axis_reset_send_counter.asFull.fire && sending_counter > 0.U && m_axis_argument_notification.asFull.fire){
        sending_counter := sending_counter - 1.U
    }

    when(receive_counter > 0.U && remoteQRec.io.deq.fire && !m_axis_reset_send_counter.asFull.fire){
      receive_counter := receive_counter - 1.U
    }

    when(io.s_axis_remote.get.asFull.fire){
      received_notifications_count := received_notifications_count + 1.U
    }

    when(io.m_axis_remote.get.asFull.fire){
      sent_notifications_count := sent_notifications_count + 1.U
    }

    io.axi_mgmt.get.suggestName("S_AXI_MGMT")
    io.axi_mgmt.get :=> regBlock.get.s_axil
    

    // Create an arbiter to arbiterate between localQ and remoteQRec
    val arbiterToLocalServer = Module(
      new elastic.BasicArbiter(chiselTypeOf(io.connNetwork.bits), 2, chooserFn = elastic.Chooser.priority)
    )
    arbiterToLocalServer.io.select.nodeq()
    when(arbiterToLocalServer.io.select.valid) {
      arbiterToLocalServer.io.select.deq()
    }

    elastic.SinkBuffer(arbiterToLocalServer.io.sources(1)) <> localQ.io.deq
    elastic.SinkBuffer(arbiterToLocalServer.io.sources(0)) <> remoteQRec.io.deq
    elastic.SourceBuffer(arbiterToLocalServer.io.sink) <> argServer.io.connNetwork

    // connect the enq of the remoteQRec to s_axis_remote
    new elastic.Fork(elastic.SourceBuffer(io.s_axis_remote.get.asFull, 32)) {
      override def onFork(): Unit = {
        val argument_counter_demux = elastic.Demux(
          source = fork(in),
          sinks =  Seq(s_axis_argument_notification.asFull, s_axis_reset_send_counter.asFull),
          select = fork(in.data.asTypeOf(new remoteArgumentNotificationType).sendCounterReset)
        )
      }
    }
    s_axis_reset_send_counter.TREADY := true.B //sending_counter === 0.U // accept the reset counter when the sending counter is 0


    s_axis_argument_notification.TREADY := remoteQRec.io.enq.ready
    remoteQRec.io.enq.valid := s_axis_argument_notification.TVALID
    remoteQRec.io.enq.bits := s_axis_argument_notification.TDATA.asTypeOf(new remoteArgumentNotificationType).address

    //io.m_axis_remote.get.asFull.bits := 0.U(io.m_axis_remote.get.asFull.bits.getWidth.W).asTypeOf(io.m_axis_remote.get.asFull.bits)


    // Create an arbiter between sending argument notifiactions and resets!

    
    val arbiterToRemoteFPGA = Module(
      new elastic.BasicArbiter(chiselTypeOf(io.m_axis_remote.get.asFull.bits), 2, chooserFn = elastic.Chooser.priority)
    )
    arbiterToRemoteFPGA.io.select.nodeq()
    when(arbiterToRemoteFPGA.io.select.valid) {
      arbiterToRemoteFPGA.io.select.deq()
    }

    arbiterToRemoteFPGA.io.sink <> io.m_axis_remote.get.asFull

    arbiterToRemoteFPGA.io.sources(0) <> elastic.SourceBuffer(m_axis_argument_notification.asFull)
    arbiterToRemoteFPGA.io.sources(1) <> elastic.SourceBuffer(m_axis_reset_send_counter.asFull)



    // connect the deq of the remoteQSend to m_axis_remote
    remoteQSend.io.deq.ready := (m_axis_argument_notification.TREADY && sending_counter > 0.U)
    m_axis_argument_notification.TVALID := (remoteQSend.io.deq.valid && sending_counter > 0.U)

    
    // Create the packet to send over m_axis_argument_notification
    val remotePacket = Wire(new remoteArgumentNotificationType)
    remotePacket.fpgaId := io.fpgaIndexInputReg.get(3, 0) // The address of the sending FPGA
    remotePacket.taskId := taskID.U(4.W)
    remotePacket.globalFunctionId := globalFunctionIds.argumentNotifier
    remotePacket.address := remoteQSend.io.deq.bits
    remotePacket.sendCounterReset := 0.U
    remotePacket.padding := 0.U

    m_axis_argument_notification.TDATA := remotePacket.asUInt
    m_axis_argument_notification.TDEST.get := remoteQSend.io.deq.bits(64 - 5, 56) // Important bug fix here, TDEST sets in specific address range
    m_axis_argument_notification.TLAST.get := true.B
    m_axis_argument_notification.TKEEP.get := Fill(m_axis_argument_notification.TKEEP.get.getWidth, 1.U(1.W)) 
    m_axis_argument_notification.TSTRB.get := Fill(m_axis_argument_notification.TSTRB.get.getWidth, 1.U(1.W))  

    
    // Create the padcket to send over to m_axis_reset_send_counter
    val resetPacket = Wire(new remoteArgumentNotificationType)
    resetPacket.fpgaId := io.fpgaIndexInputReg.get(3, 0)
    resetPacket.taskId := taskID.U(4.W)
    resetPacket.globalFunctionId := globalFunctionIds.argumentNotifier
    resetPacket.address := 0.U
    resetPacket.sendCounterReset := 1.U
    resetPacket.padding := 0.U

    m_axis_reset_send_counter.TDATA := resetPacket.asUInt
    m_axis_reset_send_counter.TDEST.get := (io.fpgaIndexInputReg.get(3, 0) + 1.U) % 2.U // #TODO: this only supports two FPGAs in Hardware! 
    m_axis_reset_send_counter.TLAST.get := true.B
    m_axis_reset_send_counter.TKEEP.get := Fill(m_axis_reset_send_counter.TKEEP.get.getWidth, 1.U(1.W)) 
    m_axis_reset_send_counter.TSTRB.get := Fill(m_axis_reset_send_counter.TSTRB.get.getWidth, 1.U(1.W))
    m_axis_reset_send_counter.TVALID := ((receive_counter === 0.U) && remoteQRec.io.count < inFlightMax.U)

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


    readyValidIndicator := Cat(
      io.connNetwork.valid,
      io.connNetwork.ready,
      remoteQSend.io.enq.valid,
      remoteQSend.io.enq.ready,
      localQ.io.enq.valid,
      localQ.io.enq.ready,
      remoteQRec.io.deq.valid,
      remoteQRec.io.deq.ready,
      localQ.io.deq.valid,
      localQ.io.deq.ready,
      arbiterToLocalServer.io.select.valid,
      arbiterToLocalServer.io.select.ready,
      arbiterToRemoteFPGA.io.select.valid,
      arbiterToRemoteFPGA.io.select.ready,
      m_axis_argument_notification.TVALID,
      m_axis_argument_notification.TREADY,
      s_axis_argument_notification.TVALID,
      s_axis_argument_notification.TREADY,
      m_axis_reset_send_counter.TVALID,
      m_axis_reset_send_counter.TREADY,
      s_axis_reset_send_counter.TVALID,
      s_axis_reset_send_counter.TREADY,
      arbiterToRemoteFPGA.io.sink.valid,
      arbiterToRemoteFPGA.io.sink.ready,
      arbiterToLocalServer.io.sink.valid,
      arbiterToLocalServer.io.sink.ready
    )

  }

}


