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



trait HardCilkHasMfpgaSupport extends Module {

  val demux : axi4.lite.components.Demux 
  val fullSysGenDescriptor: FullSysGenDescriptor
  val schedulerMap: Map[String, Scheduler]
  val notifierMap: Map[String, ArgumentNotifier]
  

  def buildMfpgaConnections(): Unit = {
    val mfpgaSupport = fullSysGenDescriptor.mFPGASimulation || fullSysGenDescriptor.mFPGASynth
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Register File To identify the FPGA
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
      val regBlock = new RegisterBlock(wAddr = 6, wData = 64, wMask = 6)
      val fpgaCountInputReg = RegInit(0.U(64.W))
      val fpgaIndexInputReg = RegInit(0.U(64.W))
      val numberOfTasksToMove = RegInit(0.U(64.W))
      //val axisCfgTaskAndReq = axi4s.Config = axi4s.Config(wData = 512, wDest = 4)
      
      regBlock.base(0x00)
      regBlock.reg(fpgaCountInputReg, read = true, write = true, desc = "Register To Identify The Number of FPGAs in The System")
      regBlock.reg(fpgaIndexInputReg, read = true, write = true, desc = "Register To Identify The Index of The FPGA in The System")
      regBlock.reg(numberOfTasksToMove, read = true, write = true, desc = "Number of tasks to move per request")


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
      m_axis_mFPGA.get.TLAST.get := true.B
      m_axis_mFPGA.get.TKEEP.get := "hFFFFFFFFFFFFFFFF".U
      m_axis_mFPGA.get.TSTRB.get := "hFFFFFFFFFFFFFFFF".U


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
      val slavesCount = fullSysGenDescriptor.taskDescriptors.length + fullSysGenDescriptor.taskDescriptors.count(task => task.getNumServers("argumentNotifier") > 0)

      val arbiterToMfpgaNetwork = Module(
        new elastic.BasicArbiter(chiselTypeOf(m_axis_mFPGA.get.asFull.bits), slavesCount, chooserFn = elastic.Chooser.rr)
      )

      arbiterToMfpgaNetwork.io.select.nodeq()
      when(arbiterToMfpgaNetwork.io.select.valid) {
        arbiterToMfpgaNetwork.io.select.deq()
      }

      // Connect the m_axis_mFPGA to the sink of the arbiter
      elastic.SourceBuffer(arbiterToMfpgaNetwork.io.sink) <> m_axis_mFPGA.get.asFull  
      
      // each source is coming from the schedulers of each task and the argumentNotifier of each task that has argumentNotifier
      val sources = schedulerMap.map(_._2.m_axis_remote.get.asFull).toSeq ++ notifierMap.map(_._2.m_axis_remote.get.asFull).toSeq

      // Connect the sources to the arbiter
      for (i <- 0 until slavesCount) {
        arbiterToMfpgaNetwork.io.sources(i) <> sources(i)
      }
      


    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // END MULTIFPGA CONFIGURATION SENDING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // MULTIFPGA CONFIGURATION RECEIVING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

    // Need to add fpgaIndex in the name when doing mfpga simulation.

    val s_axis_mFPGA = if(mfpgaSupport) Some(IO(axi4s.Slave(axi4sCfg)).suggestName(f"s_axis_mFPGA")) else None

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

          // A demux to separate messages for the scheduler vs the argument servers
          val schedulerArgumentFork = new elastic.Fork(s_axis_mFPGA.get.asFull) {
            override def onFork(): Unit = {

              val schedulerArgumentDemux = elastic.Demux(
                source = fork(in),
                sinks =  // Seq(s_axis_mFPGA_schedulers.get.asFull, s_axis_mFPGA_argServers.get.asFull),
                  Seq(s_axis_mFPGA_schedulers.get, s_axis_mFPGA_argServers.get),
                // select checks the 14th bit from the top, if zaero is scheduler else is argument server
                select = fork(in.data(498, 498))
              )

            }
          }

          // A demux to connect the m_axis_mFPGA_argServers to the correct ArgumentNotifier of the correct task ID
          val argumentFork = new elastic.Fork(s_axis_mFPGA_argServers.get) {
            override def onFork(): Unit = {
              val argumentDemux = elastic.Demux(
                source = fork(in),
                sinks =  notifierMap.map(_._2.s_axis_remote.get.asFull).toSeq,
                // checks the upper 4 bits of the address (63:60) to select the correct ArgumentNotifier (taskId based)
                select = fork(in.data(63, 60))
              )
            }
          }

          // A demux to connect the m_axis_mFPGA_schedulers to the correct Scheduler of the correct task ID
          val schedulers = schedulerMap.toSeq.sortBy(_._2.taskId).map(_._2.s_axis_remote.get.asFull)

          new elastic.Fork(elastic.SourceBuffer(s_axis_mFPGA_schedulers.get)) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  schedulers,
                // checks the upper 4 bits of the address (511: 508) to select the correct Scheduler
                select = fork(in.data(511, 508)) // NOTE WE NEED TO EDIT th
              )
            }
          }
        } else {
          // There are only schedulers!
          // Create a sequence of the schedulers' s_axis_remote interfaces ordered by task ID
          val schedulers = schedulerMap.toSeq.sortBy(_._2.taskId).map(_._2.s_axis_remote.get.asFull)
          new elastic.Fork(elastic.SourceBuffer(s_axis_mFPGA.get.asFull)) {
            override def onFork(): Unit = {
              elastic.Demux(
                source = fork(in),
                sinks =  schedulers,
                // checks the upper 4 bits of the address (511: 508) to select the correct Scheduler
                select = fork(in.data(511, 508)) 
              )
            }
          }
        }
      }
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // END MULTIFPGA CONFIGURATION RECEIVING AXI4S FROME REMOTE
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


  }


}