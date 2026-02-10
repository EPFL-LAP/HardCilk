/**
  *  A trait that connects HardCilk extra connections when in the multi-FPGA configuration.
  * 
  */

package HardCilk

import chisel3._
import Descriptors._
import chext.amba.axi4
import axi4.lite.components.RegisterBlock
import axi4.Ops._
import chext.amba.axi4s
import chext.amba.axi4s.Casts._
import chext.elastic
import Scheduler._
import ArgumentNotifier._
import chisel3.util.Irrevocable
import scala.annotation.nowarn
import HLSHelpers._
import Util._
import chext.elastic._
import elastic.ConnectOp._


// Create a chisel enum for the global function IDs
import chisel3.ChiselEnum
import chisel3.util.is
import os.stat
import chisel3.util.Fill
import chisel3.util.Cat
import chisel3.util.Queue
object globalFunctionIds extends ChiselEnum {
  val scheduler = Value(0.U)
  val argumentNotifier = Value(1.U)
  val remoteMemAccess = Value(2.U)
}

// A bundle to describe remote message
class remoteMessageType extends Bundle {
  val fpgaId = UInt(4.W)
  val taskId = UInt(4.W)
  val globalFunctionId = globalFunctionIds()
  val padding = UInt((512 - fpgaId.getWidth - taskId.getWidth - globalFunctionId.getWidth).W)  
  assert(this.getWidth == 512, "remoteMessageType width must be 512 bits")
}


// TODO: Tasks with argument notification must have the lower indicies in a consecuative matter


trait HardCilkHasMfpgaSupport extends Module {

  val demux : axi4.lite.components.Demux 
  val fullSysGenDescriptor: FullSysGenDescriptor
  val schedulerMap: Map[String, Scheduler]
  val notifierMap: Map[String, ArgumentNotifier]
  
  val peMap: Map[String, Seq[VitisWriteBufferModule]]
  val remoteStreamToMemMap: Map[String, RemoteStreamToMem]


  def buildMfpgaConnections(): Unit = {
    val mfpgaSupport = fullSysGenDescriptor.mFPGASimulation || fullSysGenDescriptor.mFPGASynth
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Register File To identify the FPGA
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
      val fpgaCountInputReg = RegInit(0.U(64.W))
      val fpgaIndexInputReg = RegInit(0.U(64.W))
      val numberOfTasksToMove = RegInit(0.U(64.W))
      val messagesReceivedCount = RegInit(0.U(64.W))
      val messagesSentCount = RegInit(0.U(64.W))
      val interfacesReadyReg =  RegInit(0.U(64.W))
      
      val remoteStreamToMemCounts_0 = RegInit(0.U(64.W))
      val remoteStreamToMemCounts_1 = RegInit(0.U(64.W))

      val remoteStreamToMemMasterDirectCount = RegInit(0.U(32.W))
      val remoteStreamToMemSlaveDirectCount = RegInit(0.U(32.W))
      val remoteStreamToMemSlaveDemuxedCount = RegInit(0.U(32.W))
      val remoteStreamToMemSlaveTaskIdCount = RegInit(0.U(32.W))

      remoteStreamToMemCounts_0 := Cat(remoteStreamToMemMasterDirectCount, remoteStreamToMemSlaveDirectCount)
      remoteStreamToMemCounts_1 := Cat(remoteStreamToMemSlaveDemuxedCount, remoteStreamToMemSlaveTaskIdCount)





      //val axisCfgTaskAndReq = axi4s.Config = axi4s.Config(wData = 512, wDest = 4)
      
      regBlock.base(0x00)
      regBlock.reg(fpgaCountInputReg, read = true, write = true, desc = "Register To Identify The Number of FPGAs in The System")
      regBlock.reg(fpgaIndexInputReg, read = true, write = true, desc = "Register To Identify The Index of The FPGA in The System")
      regBlock.reg(numberOfTasksToMove, read = true, write = true, desc = "Number of tasks to move per request")
      regBlock.reg(messagesReceivedCount, read = true, write = false, desc = "Number of messages received from remote FPGAs")
      regBlock.reg(messagesSentCount, read = true, write = false, desc = "Number of messages sent to remote FPGAs")
      regBlock.reg(interfacesReadyReg, read = true, write = false, desc = "A register that reflects ports ready sginals")
      regBlock.reg(remoteStreamToMemCounts_0, read = true, write = false, desc = "remote stream mem count debug")
      regBlock.reg(remoteStreamToMemCounts_1, read = true, write = false, desc = "remote stream mem count debug")


      //regBlock.saveRegisterMap("./", "myRegBlock")

      

      // for each task in the descriptor if it has an entry in the remoteStreamToMemMap connect its PEs
      fullSysGenDescriptor.taskDescriptors.foreach(task => {
        if(remoteStreamToMemMap.contains(task.name)){
          // Get the PEs seq
          val peSeq = peMap.get(task.name)
          val remoteStreamToMem = remoteStreamToMemMap.get(task.name).get
          println("[HardCilk:Builder:89] Connecting RemoteStreamToMem for task: " + task.name + " with " + peSeq.get.size + " PEs.")
          for(i <- 0 until peSeq.get.size){
            println("[HardCilk:Builder:92] Connecting PE index: " + i)
            peSeq.get(i).getPort("fpgaId") := fpgaIndexInputReg
            remoteStreamToMem.io.fpgaIndex := RegNext(fpgaIndexInputReg)

            peSeq.get(i).getPort("writeRespFromRemote") <> remoteStreamToMem.io.write_resp_out(i)
            peSeq.get(i).getPort("memReqToRemote") <> remoteStreamToMem.io.mem_req_in(i)
            
            remoteStreamToMem.io.mem_resp_out(i).ready := true.B
          }
        }
      })


      



      when(regBlock.rdReq) {
        regBlock.rdOk()
      }
      when(regBlock.wrReq) {
        regBlock.wrOk()
      }


      // Connect the scheduler to the registers above
      val tasksToMove  = schedulerMap.map(_._2.numberOfTasksToMove.get).toSeq 
      val fpgaCount  = schedulerMap.map(_._2.fpgaCountInputReg.get).toSeq 
      val fpgaIndex  = schedulerMap.map(_._2.fpgaIndexInputReg.get).toSeq ++ notifierMap.map(_._2.fpgaIndexInputReg.get).toSeq

      // Broadcast the management registers to all schedulers/argument-notifiers
      for (t <- tasksToMove) {
        t := numberOfTasksToMove
      }
      for (c <- fpgaCount) {
        c := fpgaCountInputReg
      }
      for (idx <- fpgaIndex) {
        idx := fpgaIndexInputReg
      }



      // Connect the register block to the the last index of the management demux
      demux.m_axil(fullSysGenDescriptor.getNumConfigPorts() - 1) :=> regBlock.s_axil
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // End Register File To identify the FPGA
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // MULTIFPGA CONFIGURATION SENDING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      // Create an elastic arbiter for the multi FPGA configuration
      
      val axi4sCfg = axi4s.Config(wData = 512, wDest = 4)

      val m_axis_mFPGA = if(mfpgaSupport) Some(IO(axi4s.Master(axi4sCfg)).suggestName("m_axis_mFPGA")) else None
      
  
      // Logic to increment the messages sent count
      when(m_axis_mFPGA.get.asFull.fire) {
        messagesSentCount := messagesSentCount + 1.U
      }

      when(m_axis_mFPGA.get.asFull.fire){
        when(m_axis_mFPGA.get.TDATA.asTypeOf(new remoteMessageType).globalFunctionId === globalFunctionIds.remoteMemAccess){
          remoteStreamToMemMasterDirectCount := remoteStreamToMemMasterDirectCount + 1.U
        }
      }
      


      // TODO: add later if simulation is needed
      // m_axis_buffer.addOne((m_axis_mFPGA, axi4sCfg))
      // interfaceBuffer.addOne(
      //     hdlinfo.Interface(
      //       f"m_axis_mFPGA_${fpgaIndex}",
      //       hdlinfo.InterfaceRole("sink"), // "sink" for slave
      //       hdlinfo.InterfaceKind("readyValid[axis4_mFPGA]"),  // TDEST
      //       "clock",
      //       "reset"
      //     )
      //   )
      

      // Slaves count is the number of tasks + the number of tasks that has argumentNotifier
      val slavesCount = fullSysGenDescriptor.taskDescriptors.length + fullSysGenDescriptor.taskDescriptors.count(task => task.getNumServers("argumentNotifier") > 0) + fullSysGenDescriptor.taskDescriptors.count(task => task.generateArgOutWriteBuffer)

      val arbiterToMfpgaNetwork = Module(
        new elastic.AdvArbiter(chiselTypeOf(m_axis_mFPGA.get.asFull.bits), slavesCount, chooserFn = elastic.Chooser.rr, isLastFn = (x : axi4s.FullChannel) => x.last)
      )



      arbiterToMfpgaNetwork.io.select.nodeq()
      when(arbiterToMfpgaNetwork.io.select.valid) {
        arbiterToMfpgaNetwork.io.select.deq()
      }

      // Connect the m_axis_mFPGA to the sink of the arbiter
      elastic.SourceBuffer(arbiterToMfpgaNetwork.io.sink) <> m_axis_mFPGA.get.asFull  
      
      // each source is coming from the schedulers of each task and the argumentNotifier of each task that has argumentNotifier
      val sources = schedulerMap.map(_._2.m_axis_remote.get.asFull).toSeq ++ notifierMap.map(_._2.m_axis_remote.get.asFull).toSeq ++ remoteStreamToMemMap.map(_._2.io.m_axis_remote.asFull).toSeq

      // Connect the sources to the arbiter
      for (i <- 0 until slavesCount) {
        arbiterToMfpgaNetwork.io.sources(i) <> elastic.SourceBuffer(sources(i))
      }
      


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // END MULTIFPGA CONFIGURATION SENDING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // MULTIFPGA CONFIGURATION RECEIVING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // Need to add fpgaIndex in the name when doing mfpga simulation.

    val s_axis_mFPGA = if(mfpgaSupport) Some(IO(axi4s.Slave(axi4sCfg)).suggestName(f"s_axis_mFPGA")) else None

    // Logic to increment the messages received count
    when(s_axis_mFPGA.get.asFull.fire) {
      messagesReceivedCount := messagesReceivedCount + 1.U
    }

    when(s_axis_mFPGA.get.asFull.fire){
      when(s_axis_mFPGA.get.TDATA.asTypeOf(new remoteMessageType).globalFunctionId === globalFunctionIds.remoteMemAccess){
        remoteStreamToMemSlaveDirectCount := remoteStreamToMemSlaveDirectCount + 1.U
      }
    }

      //val s_axis_mFPGA_argServers = if(mfpgaFlag && notifierMap.size > 0) Some(IO(axi4s.Master(axi4sCfg))) else None
      //val s_axis_mFPGA_schedulers = if(mfpgaFlag && notifierMap.size > 0) Some(IO(axi4s.Master(axi4sCfg))) else None

      // slave --> receives data, TVALID, TDATA are driven outside. TREADY is driven inside.
      // DEMUX --> tries to drive TVALID and TDATA. That is not possible.

      // #TODO add later for simulation purposes
      // if(mfpgaSupport){
      //   s_axis_buffer.addOne((s_axis_mFPGA.get, axi4sCfg))
      //   interfaceBuffer.addOne(
      //       hdlinfo.Interface(
      //         f"s_axis_mFPGA_${fpgaIndex}",
      //         hdlinfo.InterfaceRole("source"), // "sink" for slave
      //         hdlinfo.InterfaceKind("readyValid[axis4_mFPGA]"), 
      //         "clock",
      //         "reset"
      //       )
      //     )
      // }




      if(mfpgaSupport){

        // If there are argument notifiers, connect them to remote
        if(notifierMap.size > 0){       

          val s_axis_mFPGA_argServers = if(mfpgaSupport) Some(Wire(Irrevocable(chiselTypeOf(s_axis_mFPGA.get.asFull.bits)))) else None
          val s_axis_mFPGA_schedulers = if(mfpgaSupport) Some(Wire(Irrevocable(chiselTypeOf(s_axis_mFPGA.get.asFull.bits)))) else None
          val s_axis_mFPGA_memAccess = if(mfpgaSupport) Some(Wire(Irrevocable(chiselTypeOf(s_axis_mFPGA.get.asFull.bits)))) else None

        when(s_axis_mFPGA_memAccess.get.fire){
          remoteStreamToMemSlaveDemuxedCount := remoteStreamToMemSlaveDemuxedCount + 1.U
        }

        val s_axis_ever_blocked =  RegInit(false.B)
        val m_axis_ever_blocked =  RegInit(false.B)
        val s_axis_mFPGA_argServers_ever_blocked =  RegInit(false.B)
        val s_axis_mFPGA_schedulers_ever_blocked =  RegInit(false.B)
        val s_axis_mFPGA_memAccess_ever_blocked =  RegInit(false.B)



        s_axis_ever_blocked := s_axis_ever_blocked || (s_axis_mFPGA.get.TVALID && !s_axis_mFPGA.get.TREADY)
        m_axis_ever_blocked := m_axis_ever_blocked || (m_axis_mFPGA.get.TVALID && !m_axis_mFPGA.get.TREADY)
        s_axis_mFPGA_argServers_ever_blocked := s_axis_mFPGA_argServers_ever_blocked || (s_axis_mFPGA_argServers.get.valid && !s_axis_mFPGA_argServers.get.ready)
        s_axis_mFPGA_schedulers_ever_blocked := s_axis_mFPGA_schedulers_ever_blocked || (s_axis_mFPGA_schedulers.get.valid && !s_axis_mFPGA_schedulers.get.ready)
        s_axis_mFPGA_memAccess_ever_blocked := s_axis_mFPGA_memAccess_ever_blocked || (s_axis_mFPGA_memAccess.get.valid && !s_axis_mFPGA_memAccess.get.ready)


        val arbiterSourcesRV = Wire(Vec(slavesCount * 2 + 2, UInt((1).W)))         
        var j = 0
        
        for(i <- 0 until slavesCount){
          arbiterSourcesRV(j) := arbiterToMfpgaNetwork.io.sources(i).valid
          arbiterSourcesRV(j + 1) := arbiterToMfpgaNetwork.io.sources(i).ready
          j += 2
        }
        arbiterSourcesRV(j) := arbiterToMfpgaNetwork.io.select.valid
        arbiterSourcesRV(j + 1) := arbiterToMfpgaNetwork.io.select.ready

        

        interfacesReadyReg := Cat(
          arbiterSourcesRV.asUInt,
          m_axis_ever_blocked,
          s_axis_ever_blocked,
          s_axis_mFPGA_argServers_ever_blocked,
          s_axis_mFPGA_schedulers_ever_blocked,
          s_axis_mFPGA_memAccess_ever_blocked,
          m_axis_mFPGA.get.TREADY,
          m_axis_mFPGA.get.TVALID,
          s_axis_mFPGA.get.TREADY,
          s_axis_mFPGA.get.TVALID, 
          s_axis_mFPGA_argServers.get.ready,
          s_axis_mFPGA_argServers.get.valid,
          s_axis_mFPGA_schedulers.get.ready,
          s_axis_mFPGA_schedulers.get.valid,
          s_axis_mFPGA_memAccess.get.ready,
          s_axis_mFPGA_memAccess.get.valid
        )


          val receiveQueue = Module(new Queue(chiselTypeOf(s_axis_mFPGA.get.asFull.bits), 256))
          receiveQueue.io.enq <> s_axis_mFPGA.get.asFull


          
          // A demux to separate messages for the scheduler vs the argument servers
          @nowarn("cat=unused")
          val schedulerArgumentFork = new elastic.Fork(elastic.SourceBuffer(receiveQueue.io.deq, 256)) {
            override def onFork(): Unit = {

              val schedulerArgumentDemux = elastic.Demux(
                source = fork(in),
                sinks =  // Seq(s_axis_mFPGA_schedulers.get.asFull, s_axis_mFPGA_argServers.get.asFull),
                  Seq(s_axis_mFPGA_schedulers.get, s_axis_mFPGA_argServers.get, s_axis_mFPGA_memAccess.get),
                // select checks the 14th bit from the top, if zero is scheduler else is argument server
                select = fork(in.data.asTypeOf(new remoteMessageType).globalFunctionId.asUInt)            
              )
            }
          }

          // val schedulerArgumentFork = new elastic.Fork(elastic.SourceBuffer(receiveQueue.io.deq, 256)) {
          //   override def onFork(): Unit = {
          //     val scheduler_argument_demux_select = Wire(Irrevocable(UInt(2.W)))
          //     val state = RegInit(true.B)
          //     val arrival_scheduler_argument = new Arrival(fork(in), elastic.SinkBuffer(scheduler_argument_demux_select, 128)) {
                
          //       out := in.data.asTypeOf(new remoteMessageType).globalFunctionId.asUInt
          //       protected def onArrival: Unit = {
          //         when(state && ~in.last){
          //           accept()
          //           state := false.B
          //         }.elsewhen(state && in.last){
          //           accept()
          //           state := true.B
          //         }.elsewhen(~state && in.last){
          //           drop()
          //           state := true.B
          //         }.otherwise{
          //           drop()
          //         }
          //       }
          //     }

          //     val schedulerArgumentDemux = elastic.Demux(
          //       source = fork(in),
          //       sinks =  // Seq(s_axis_mFPGA_schedulers.get.asFull, s_axis_mFPGA_argServers.get.asFull),
          //         Seq(s_axis_mFPGA_schedulers.get, s_axis_mFPGA_argServers.get, s_axis_mFPGA_memAccess.get),
          //       // select checks the 14th bit from the top, if zero is scheduler else is argument server
          //       select = scheduler_argument_demux_select,
          //       isLastFn = (x : axi4s.FullChannel) => x.last
          //     )
          //   }
          // }

          if(remoteStreamToMemMap.size > 0){
            // Create a sequence of type s_axis_remote for all tasks regardless of having remoteStreamToMem or not
            val s_axis_remote_memAccess = Seq.fill(fullSysGenDescriptor.taskDescriptors.length){
              Wire(Irrevocable(chiselTypeOf(s_axis_mFPGA.get.asFull.bits)))
            }
            @nowarn("cat=unused")
            val remoteMemAccessFork = new elastic.Fork(elastic.SourceBuffer(s_axis_mFPGA_memAccess.get, 128)) {
              // Does not need an arrival for the select as the width is capped to 512 bits (1 beat)
              override def onFork(): Unit = {
                val remoteMemAccessDemux = elastic.Demux(
                  source = fork(in),
                  sinks =  s_axis_remote_memAccess,
                  select =  fork(in.data.asTypeOf(new remoteMessageType).taskId)//,
                  //isLastFn = (x : axi4s.FullChannel) => x.last // always last
                )
              }
            }

            // remoteStreamToMemMap.map(_._2.io.s_axis_remote.asFull).toSeq
            for(i <- 0 until fullSysGenDescriptor.taskDescriptors.length){
              val taskName = fullSysGenDescriptor.taskDescriptors(i).name
              if(remoteStreamToMemMap.contains(taskName)){
                remoteStreamToMemMap.get(taskName).get.io.s_axis_remote.asFull <> s_axis_remote_memAccess(i)
                when(remoteStreamToMemMap.get(taskName).get.io.s_axis_remote.asFull.fire){
                  remoteStreamToMemSlaveTaskIdCount := remoteStreamToMemSlaveTaskIdCount + 1.U
                }
              } else {
                s_axis_remote_memAccess(i).ready := true.B // drop the data
              }
            }
  

          } else {
            s_axis_mFPGA_memAccess.get.ready := true.B
          }

          // A demux to connect the m_axis_mFPGA_argServers to the correct ArgumentNotifier of the correct task ID
          val s_axis_argument_notifiers = Seq.fill(fullSysGenDescriptor.taskDescriptors.length){
              Wire(Irrevocable(chiselTypeOf(s_axis_mFPGA.get.asFull.bits)))
          }
          @nowarn("cat=unused")
          val argumentFork = new elastic.Fork(elastic.SourceBuffer(s_axis_mFPGA_argServers.get, 128)) {
            

            // Does not need an arrival for the select as the width is capped to 512 bits (1 beat)

            override def onFork(): Unit = {
              val argumentDemux = elastic.Demux(
                source = fork(in),
                sinks =  s_axis_argument_notifiers,
                // checks the upper 4 bits of the address (63:60) to select the correct ArgumentNotifier (taskId based)
                select = fork((in.data.asTypeOf(new remoteMessageType).taskId))//,
                //isLastFn = (x : axi4s.FullChannel) => x.last // always last
              )
            }

            // connect each ArgumentNotifier to its corresponding demux output
            for(i <- 0 until fullSysGenDescriptor.taskDescriptors.length){
              val taskName = fullSysGenDescriptor.taskDescriptors(i).name
              if(notifierMap.contains(taskName)){
                notifierMap.get(taskName).get.s_axis_remote.get.asFull <> s_axis_argument_notifiers(i)
              } else {
                s_axis_argument_notifiers(i).ready := true.B // drop the data 
              }
            }

          }

          // A demux to connect the m_axis_mFPGA_schedulers to the correct Scheduler of the correct task ID
          val schedulers = schedulerMap.toSeq.sortBy(_._2.taskId).map(_._2.s_axis_remote.get.asFull)

          new elastic.Fork(elastic.SourceBuffer(s_axis_mFPGA_schedulers.get)) {
            override def onFork(): Unit = {
              val schedulers_demux_select = Wire(Irrevocable(UInt(4.W)))
              val state = RegInit(true.B)
              @nowarn("cat=unused")
              val arrival_schedulers = new Arrival(fork(in), elastic.SinkBuffer(schedulers_demux_select, 128)) {
                out := in.data.asTypeOf(new remoteMessageType).taskId.asUInt
                protected def onArrival: Unit = {
                  
                  when(state && ~in.last){
                    accept()
                    state := false.B
                  }.elsewhen(state && in.last){
                    accept()
                    state := true.B
                  }.elsewhen(~state && in.last){
                    drop()
                    state := true.B
                  }.otherwise{
                    drop()
                  }
                }
              }
              elastic.Demux(
                source = fork(in),
                sinks =  schedulers,
                select = schedulers_demux_select,
                isLastFn = (x : axi4s.FullChannel) => x.last
              )
            }
          }
        } else {
          // There are only schedulers!
          // Create a sequence of the schedulers' s_axis_remote interfaces ordered by task ID
          val schedulers = schedulerMap.toSeq.sortBy(_._2.taskId).map(_._2.s_axis_remote.get.asFull)
          new elastic.Fork(elastic.SourceBuffer(s_axis_mFPGA.get.asFull)) {
            override def onFork(): Unit = {
              val schedulers_demux_select = Wire(Irrevocable(UInt(4.W)))
              val state = RegInit(true.B)
              @nowarn("cat=unused")
              val arrival_schedulers = new Arrival(fork(in), schedulers_demux_select) {
                out := in.data.asTypeOf(new remoteMessageType).taskId.asUInt
                protected def onArrival: Unit = {                  
                  when(state && ~in.last){
                    accept()
                    state := false.B
                  }.elsewhen(state && in.last){
                    accept()
                    state := true.B
                  }.elsewhen(~state && in.last){
                    drop()
                    state := true.B
                  }.otherwise{
                    drop()
                  }
                }
              }
  
              elastic.Demux(
                source = fork(in),
                sinks =  schedulers,
                // checks the upper 4 bits of the address (511: 508) to select the correct Scheduler
                select = schedulers_demux_select,
                isLastFn = (x : axi4s.FullChannel) => x.last
              )
            }
          }
        }
      }
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // END MULTIFPGA CONFIGURATION RECEIVING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // m_axis_mFPGA.get.TLAST.get := m_axis_mFPGA.get.TVALID
    // m_axis_mFPGA.get.TKEEP.get := "hFFFFFFFFFFFFFFFFFFFFFF".U
    // m_axis_mFPGA.get.TSTRB.get := "hFFFFFFFFFFFFFFFFFFFFF".U
    m_axis_mFPGA.get.TKEEP.get := Fill(m_axis_mFPGA.get.TKEEP.get.getWidth, 1.U(1.W))
    m_axis_mFPGA.get.TSTRB.get := Fill(m_axis_mFPGA.get.TSTRB.get.getWidth, 1.U(1.W))

  }


}