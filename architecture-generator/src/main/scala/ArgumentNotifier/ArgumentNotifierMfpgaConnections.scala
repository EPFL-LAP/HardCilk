package ArgumentNotifier

import chisel3._


import chext.elastic
import chext.amba.axi4s
import axi4s.Casts._
import chisel3.util.log2Ceil



/**
 * A trait that encapsulates the mfPGA-specific extensions to the Argument Notifier module.
 */
trait NotifierHasMfpgaSupport extends Module {

  // --- Abstract members to be provided by the ArgumentNotifier module ---
  val mfpgaSupport: Boolean  
  val argRouteServers: Seq[ArgumentServerMfpgaWrapper]
  val argRouteServersNumber: Int
  val taskWidth: Int

  def buildMfpgaConnections(): Unit = {
    val master_arbiter = Module(
          new elastic.BasicArbiter(
            chiselTypeOf(argRouteServers.head.io.m_axis_remote.get.asFull.bits),
            argRouteServersNumber,
            chooserFn = elastic.Chooser.rr
          )
        )
      

    master_arbiter.io.select.nodeq()
    when(master_arbiter.io.select.valid) {
      master_arbiter.io.select.deq()
    }
    

    val m_axis_remote = IO(axi4s.Master(argRouteServers.head.io.axisCfgTaskAndReq))
    val s_axis_remote = IO(axi4s.Slave(argRouteServers.head.io.axisCfgTaskAndReq))
    val fpgaIndexInputReg = IO(Input(UInt(8.W)))
    

    // Connect the m_axis_remote to the master of the arbiter
    m_axis_remote.asFull <> master_arbiter.io.sink


    for (i <- 0 until argRouteServersNumber) {
      // Connect the axi_mgmt port for all the argument servers
      fpgaIndexInputReg <> argRouteServers(i).io.fpgaIndexInputReg.get

      // Make the slave ports of the arbiter connect to the master ports of the argument servers
      master_arbiter.io.sources(i) <> argRouteServers(i).io.m_axis_remote.get.asFull
    }

    new elastic.Fork(s_axis_remote.asFull) {
      override def onFork(): Unit = {
        elastic.Demux(
          source = fork(in),
          sinks =  argRouteServers map (_.io.s_axis_remote.get.asFull),
          select = fork((in.data & "h3FFFFFFFF".U) >> log2Ceil(taskWidth / 8).U) // select the upper bits of the address
        )
      }
    }

  }
}
