package Scheduler

import chisel3._


import chext.elastic
import chext.amba.axi4s
import axi4s.Casts._
import AXIHelpers._

import chext.amba.axi4
import chext.amba.axi4.lite.components.RegisterBlock
import axi4.Ops._

/**
 * A trait that encapsulates the mfPGA-specific extensions to the Scheduler module.
 */
trait SchedulerHasMfpgaSupport extends Module {

    // --- Abstract members to be provided by the Scheduler module ---
    val taskWidth: Int
    val peCount: Int
    val taskId: Int
    val mfpgaSupport: Boolean
    val schedulerServers: Seq[SchedulerServer]
    val stealNW_TQ: SchedulerLocalNetwork
    val schedulerServersNumber: Int
    val axisCfgTaskAndReq: axi4s.Config
    val spawnerServerNumber: Int
    val spawnerServer: Option[Seq[SpawnerServer]] 

    val fpgaCountInputReg = if (mfpgaSupport) Some(IO(Input(UInt(32.W)).suggestName("fpgaCountInputReg"))) else None
    val fpgaIndexInputReg = if (mfpgaSupport) Some(IO(Input(UInt(32.W)).suggestName("fpgaIndexInputReg"))) else None
    val numberOfTasksToMove = if (mfpgaSupport) Some(IO(Input(UInt(32.W)).suggestName("numberOfTasksToMove"))) else None
    val m_axis_remote = if (mfpgaSupport) Some(IO(axi4s.Master(axisCfgTaskAndReq)).suggestName("m_axis_remote")) else None
    val s_axis_remote = if (mfpgaSupport) Some(IO(axi4s.Slave(axisCfgTaskAndReq)).suggestName("s_axis_remote")) else None


    val s_axi_remote_task_server = if (mfpgaSupport) Some(IO(axi4.lite.Slave(axi4.Config(wAddr = 6, wData=64, lite=true)))) else None


    def buildMfpgaConnections(): Unit = {

        if(mfpgaSupport) {
            
            var axisWidth = 512//1024//512 + 16
            if(taskWidth >= 512){
                axisWidth = 1024
            }
            val remoteTaskServer = Module(new RemoteTaskServer(taskWidth, peCount, axisCfgTaskAndReq.copy(wData = axisWidth), taskId))
            remoteTaskServer.io.axi_mgmt <> s_axi_remote_task_server.get


            val serveRemoteAggregate = schedulerServers.map(_.io.serveRemote).reduce(_ || _) || spawnerServer.fold(false.B)(_.map(_.io.serveRemote).fold(false.B)(_ || _))

            val getTasksFromRemoteAggregate = schedulerServers.map(_.io.getTasksFromRemote).reduce(_ || _) && !serveRemoteAggregate

            remoteTaskServer.io.serveRemote := serveRemoteAggregate
            remoteTaskServer.io.getTasksFromRemote := getTasksFromRemoteAggregate && !serveRemoteAggregate
            remoteTaskServer.io.connNetwork_0 <> stealNW_TQ.io.connVSS(schedulerServersNumber)
            remoteTaskServer.io.connNetwork_1 <> stealNW_TQ.io.connVSS(schedulerServersNumber + 1)
            remoteTaskServer.io.fpgaCountInputReg := fpgaCountInputReg.get
            remoteTaskServer.io.fpgaIndexInputReg := fpgaIndexInputReg.get
            remoteTaskServer.io.numTasksToStealOrServe := numberOfTasksToMove.get

            if(axisWidth == 512){
                elastic.SinkBuffer(m_axis_remote.get.asFull) <> remoteTaskServer.io.m_axis_taskAndReq.asFull
                elastic.SourceBuffer(s_axis_remote.get.asFull) <> remoteTaskServer.io.s_axis_taskAndReq.asFull
            } else if(axisWidth > 512){
                val fromNetworkConverter = Module(new Axis512To1024(axisCfgTaskAndReq, axisCfgTaskAndReq.copy(wData = axisWidth)))

                fromNetworkConverter.io.out.asFull <> remoteTaskServer.io.s_axis_taskAndReq.asFull
                elastic.SourceBuffer(s_axis_remote.get.asFull) <> fromNetworkConverter.io.in.asFull

                val toNetworkConverter = Module(new Axis1024To512(axisCfgTaskAndReq.copy(wData = axisWidth), axisCfgTaskAndReq))
                toNetworkConverter.io.in.asFull <> remoteTaskServer.io.m_axis_taskAndReq.asFull
                elastic.SinkBuffer(m_axis_remote.get.asFull) <> toNetworkConverter.io.out.asFull
            }
        }
    }
}