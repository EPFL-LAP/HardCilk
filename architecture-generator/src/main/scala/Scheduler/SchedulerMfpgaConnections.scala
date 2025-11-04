package Scheduler

import chisel3._


import chext.elastic
import chext.amba.axi4s
import axi4s.Casts._


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

    val fpgaCountInputReg = if (mfpgaSupport) Some(IO(Input(UInt(32.W)).suggestName("fpgaCountInputReg"))) else None
    val fpgaIndexInputReg = if (mfpgaSupport) Some(IO(Input(UInt(32.W)).suggestName("fpgaIndexInputReg"))) else None
    val numberOfTasksToMove = if (mfpgaSupport) Some(IO(Input(UInt(32.W)).suggestName("numberOfTasksToMove"))) else None
    val m_axis_remote = if (mfpgaSupport) Some(IO(axi4s.Master(axisCfgTaskAndReq)).suggestName("m_axis_remote")) else None
    val s_axis_remote = if (mfpgaSupport) Some(IO(axi4s.Slave(axisCfgTaskAndReq)).suggestName("s_axis_remote")) else None

    def buildMfpgaConnections(): Unit = {

        if(mfpgaSupport) {
            val remoteTaskServer = Module(new RemoteTaskServer(taskWidth, peCount, axisCfgTaskAndReq, taskId))
            val serveRemoteAggregate = schedulerServers.map(_.io.serveRemote).reduce(_ || _)
            val getTasksFromRemoteAggregate = schedulerServers.map(_.io.getTasksFromRemote).reduce(_ || _)
            remoteTaskServer.io.serveRemote := serveRemoteAggregate
            remoteTaskServer.io.getTasksFromRemote := getTasksFromRemoteAggregate && !serveRemoteAggregate
            remoteTaskServer.io.connNetwork_0 <> stealNW_TQ.io.connVSS(schedulerServersNumber)
            remoteTaskServer.io.connNetwork_1 <> stealNW_TQ.io.connVSS(schedulerServersNumber + 1)
            elastic.SinkBuffer(m_axis_remote.get.asFull) <> remoteTaskServer.io.m_axis_taskAndReq.asFull
            elastic.SourceBuffer(s_axis_remote.get.asFull) <> remoteTaskServer.io.s_axis_taskAndReq.asFull
            remoteTaskServer.io.fpgaCountInputReg := fpgaCountInputReg.get
            remoteTaskServer.io.fpgaIndexInputReg := fpgaIndexInputReg.get
            remoteTaskServer.io.numTasksToStealOrServe := numberOfTasksToMove.get
        
        }
    }


}