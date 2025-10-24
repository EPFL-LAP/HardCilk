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



    def buildMfpgaConnections(): Unit = {

        val remoteTaskServer = Module(new RemoteTaskServer(taskWidth, peCount, taskId))

        val fpgaCountInputReg = IO(chiselTypeOf(remoteTaskServer.io.fpgaCountInputReg)).suggestName("fpgaCountInputReg")
        val fpgaIndexInputReg = IO(chiselTypeOf(remoteTaskServer.io.fpgaIndexInputReg)).suggestName("fpgaIndexInputReg")

        val numberOfTasksToMove = IO(Input(UInt(32.W))).suggestName("numberOfTasksToMove")

        val m_axis_remote = IO(chiselTypeOf(remoteTaskServer.io.m_axis_taskAndReq)).suggestName("m_axis_remote")
        val s_axis_remote = IO(chiselTypeOf(remoteTaskServer.io.s_axis_taskAndReq)).suggestName("s_axis_remote")

        if (mfpgaSupport) {
          val serveRemoteAggregate = schedulerServers.map(_.io.serveRemote).reduce(_ || _)
          val getTasksFromRemoteAggregate = schedulerServers.map(_.io.getTasksFromRemote).reduce(_ || _)
          remoteTaskServer.io.serveRemote := serveRemoteAggregate
          remoteTaskServer.io.getTasksFromRemote := getTasksFromRemoteAggregate && !serveRemoteAggregate
          remoteTaskServer.io.connNetwork_0 <> stealNW_TQ.io.connVSS(schedulerServersNumber)
          remoteTaskServer.io.connNetwork_1 <> stealNW_TQ.io.connVSS(schedulerServersNumber + 1)
          elastic.SinkBuffer(m_axis_remote.asFull) <> remoteTaskServer.io.m_axis_taskAndReq.asFull
          elastic.SourceBuffer(s_axis_remote.asFull) <> remoteTaskServer.io.s_axis_taskAndReq.asFull
          remoteTaskServer.io.fpgaCountInputReg := fpgaCountInputReg
          remoteTaskServer.io.fpgaIndexInputReg := fpgaIndexInputReg
          remoteTaskServer.io.numTasksToStealOrServe := numberOfTasksToMove
        }
    }


}