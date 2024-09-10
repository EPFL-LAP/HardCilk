package Scheduler

import chisel3._
import Util._
import chisel3.ChiselEnum

class SchedulerClientIO(taskWidth: Int, queueMaxLength: Int) extends Bundle {
  val connNetwork = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val connQ = Flipped(new DequeInterface(taskWidth, queueMaxLength))
}

class SchedulerClient(
    taskWidth: Int,
    queueMaxLength: Int,
    minLengthThresh: Int,
    maxLengthThresh: Int,
    networkLength: Int,
    vssIgnoresRequests: Boolean
) extends Module {
  val io = IO(new SchedulerClientIO(taskWidth, queueMaxLength))

  object state extends ChiselEnum {
    val init = Value(0.U)
    val requestTask = Value(1.U)
    val takeInTask = Value(2.U)
    val pushTask = Value(3.U)
    val popTask = Value(4.U)
    val giveAwayTask = Value(5.U)
    val serveStealRequests = Value(6.U)
  }

  val stateReg = RegInit(state.init)
  val stolenTaskReg = RegInit(0.U(taskWidth.W))
  val giveTaskReg = RegInit(0.U(taskWidth.W))
  // Used to account when we have a popTask state that does not succeed. It should issue two task requests.
  // One for an empty TQ and the other it suppressed.
  val taskRequestCount = RegInit(1.U(2.W))

  val tasksGivenAwayCount = RegInit(0.U(32.W))

  val requestKilledCount = RegInit(networkLength.U(32.W))
  val requestFullCount = RegInit(networkLength.U(32.W))

  // Queue Outputs
  io.connQ.pop.ready := false.B
  io.connQ.push.bits := 0.U
  io.connQ.push.valid := false.B
  // Data Network Outputs
  io.connNetwork.data.availableTask.ready := false.B
  io.connNetwork.data.qOutTask.valid := false.B
  io.connNetwork.data.qOutTask.bits := 0.U
  // Ctrl Network Outputs
  io.connNetwork.ctrl.stealReq.valid := false.B
  io.connNetwork.ctrl.serveStealReq.valid := false.B

  val requestTaskCount = RegInit(0.U(32.W))

  if (vssIgnoresRequests) {
    // This configuration is when the task is spawned by another task or is continuation
    when(stateReg === state.init) {
      when(io.connQ.currLength < minLengthThresh.U) {
        requestFullCount := networkLength.U(32.W) + 2.U
        requestKilledCount := networkLength.U(32.W) + 2.U
        stateReg := state.takeInTask
        requestTaskCount := requestTaskCount + 1.U
      }.elsewhen(io.connQ.currLength > maxLengthThresh.U) {
        stateReg := state.popTask
      }.elsewhen(io.connQ.currLength > minLengthThresh.U) {
        stateReg := state.serveStealRequests
      }.otherwise {
        stateReg := state.init
      }
    }.elsewhen(stateReg === state.takeInTask) {

      when(io.connNetwork.data.availableTask.valid) {
        stateReg := state.pushTask
        stolenTaskReg := io.connNetwork.data.availableTask.bits
      }.elsewhen(io.connQ.currLength >= minLengthThresh.U) {
        stateReg := state.init
      }.elsewhen(requestKilledCount === 0.U) {
        requestTaskCount := requestTaskCount + 1.U
      }.otherwise {
        stateReg := state.takeInTask
      }

      when(!io.connNetwork.ctrl.serveStealReq.ready) {
        requestKilledCount := requestKilledCount - 1.U
      }.otherwise {
        requestKilledCount := networkLength.U(32.W) + 2.U
      }

      io.connNetwork.data.availableTask.ready := true.B // Take in task

    }.elsewhen(stateReg === state.pushTask) {
      when(io.connQ.push.ready) {
        stateReg := state.init
      }.elsewhen(io.connQ.currLength >= maxLengthThresh.U) {
        stateReg := state.giveAwayTask
        giveTaskReg := stolenTaskReg
      }.otherwise {
        stateReg := state.pushTask
      }

      io.connQ.push.bits := stolenTaskReg // Pass the stolen task to the queue.
      io.connQ.push.valid := true.B // Make the task valid for the queue.

    }.elsewhen(stateReg === state.popTask) {
      when(io.connQ.pop.valid) {
        stateReg := state.giveAwayTask
        giveTaskReg := io.connQ.pop.bits
      }.elsewhen(io.connQ.currLength === 0.U) {
        stateReg := state.takeInTask
        requestKilledCount := networkLength.U(32.W) + 2.U
        requestTaskCount := requestTaskCount + 1.U
      }.otherwise {
        stateReg := state.popTask
      }

      io.connQ.pop.ready := true.B // Pop the available task from the queue

    }.elsewhen(stateReg === state.giveAwayTask) {
      when(io.connNetwork.data.qOutTask.ready) {
        tasksGivenAwayCount := tasksGivenAwayCount + 1.U
        stateReg := state.init
      }.otherwise {
        stateReg := state.giveAwayTask
      }

      io.connNetwork.data.qOutTask.valid := true.B // data is valid for the network.
      io.connNetwork.data.qOutTask.bits := giveTaskReg // put the popped task as input for the network.

    }.elsewhen(stateReg === state.serveStealRequests) {
      when(
        io.connQ.currLength > maxLengthThresh.U ||
          (io.connNetwork.ctrl.serveStealReq.ready && io.connQ.currLength >= minLengthThresh.U)
      ) {
        stateReg := state.popTask
      }.elsewhen(io.connQ.currLength < minLengthThresh.U && io.connNetwork.ctrl.serveStealReq.ready) {
        requestKilledCount := networkLength.U(32.W) + 2.U
        stateReg := state.takeInTask
        requestTaskCount := requestTaskCount + 2.U
      }.elsewhen(io.connQ.currLength < minLengthThresh.U) {
        requestKilledCount := networkLength.U(32.W) + 2.U
        stateReg := state.takeInTask
        requestTaskCount := requestTaskCount + 1.U
      }.otherwise {
        stateReg := state.serveStealRequests
      }

      io.connNetwork.ctrl.serveStealReq.valid := true.B // Digest a steal request

    }

    when(
      requestTaskCount > 0.U
        && !(stateReg === state.serveStealRequests && io.connQ.currLength < minLengthThresh.U)
        && !(stateReg === state.popTask && io.connQ.currLength === 0.U)
        && !(stateReg === state.takeInTask && requestKilledCount === 0.U)
    ) {
      io.connNetwork.ctrl.stealReq.valid := true.B
      when(io.connNetwork.ctrl.stealReq.ready) {
        requestTaskCount := requestTaskCount - 1.U
      }
    }
  } else {

    when(stateReg === state.init) {
      when(io.connQ.currLength < minLengthThresh.U) {
        requestFullCount := networkLength.U(32.W)
        stateReg := state.requestTask
      }.elsewhen(io.connQ.currLength > maxLengthThresh.U) {
        stateReg := state.popTask
      }.elsewhen(io.connQ.currLength > minLengthThresh.U) {
        stateReg := state.serveStealRequests
      }.otherwise {
        stateReg := state.init
      }
    }.elsewhen(stateReg === state.requestTask) {
      io.connNetwork.ctrl.stealReq.valid := true.B // Request task
      when(io.connNetwork.ctrl.stealReq.ready && taskRequestCount === 1.U) {
        stateReg := state.takeInTask
        requestKilledCount := networkLength.U(32.W)
      }.elsewhen(io.connNetwork.ctrl.stealReq.ready && taskRequestCount === 2.U) {
        taskRequestCount := 1.U
        requestFullCount := networkLength.U(32.W)
        stateReg := state.requestTask
      }.elsewhen(io.connQ.currLength >= minLengthThresh.U) {
        stateReg := state.serveStealRequests
      }.elsewhen(tasksGivenAwayCount > 0.U) {
        stateReg := state.takeInTask // to avoid deadlocking on requesting in a full network
        requestKilledCount := networkLength.U(32.W)
        tasksGivenAwayCount := tasksGivenAwayCount - 1.U
        io.connNetwork.ctrl.serveStealReq.valid := true.B
      }.elsewhen(requestFullCount === 0.U) {
        stateReg := state.takeInTask
      }.otherwise {
        stateReg := state.requestTask
      }

      when(io.connNetwork.ctrl.serveStealReq.ready) {
        requestFullCount := requestFullCount - 1.U
      }.otherwise {
        requestFullCount := networkLength.U(32.W)
      }
    }.elsewhen(stateReg === state.takeInTask) {

      when(io.connNetwork.data.availableTask.valid) {
        stateReg := state.pushTask
        stolenTaskReg := io.connNetwork.data.availableTask.bits
      }.elsewhen(io.connQ.currLength >= minLengthThresh.U) {
        stateReg := state.init
      }.elsewhen(requestKilledCount === 0.U) {
        requestFullCount := networkLength.U(32.W)
        stateReg := state.requestTask
      }.otherwise {
        stateReg := state.takeInTask
      }

      when(!io.connNetwork.ctrl.serveStealReq.ready) {
        requestKilledCount := requestKilledCount - 1.U
      }.otherwise {
        requestKilledCount := networkLength.U(32.W)
      }

      io.connNetwork.data.availableTask.ready := true.B // Take in task

    }.elsewhen(stateReg === state.pushTask) {
      when(io.connQ.push.ready) {
        stateReg := state.init
      }.elsewhen(io.connQ.currLength >= maxLengthThresh.U) {
        stateReg := state.giveAwayTask
        giveTaskReg := stolenTaskReg
      }.otherwise {
        stateReg := state.pushTask
      }

      io.connQ.push.bits := stolenTaskReg // Pass the stolen task to the queue.
      io.connQ.push.valid := true.B // Make the task valid for the queue.

    }.elsewhen(stateReg === state.popTask) {
      when(io.connQ.pop.valid) {
        stateReg := state.giveAwayTask
        giveTaskReg := io.connQ.pop.bits
      }.elsewhen(io.connQ.currLength === 0.U) {
        requestFullCount := networkLength.U(32.W)
        stateReg := state.requestTask
      }.otherwise {
        stateReg := state.popTask
      }

      io.connQ.pop.ready := true.B // Pop the available task from the queue

    }.elsewhen(stateReg === state.giveAwayTask) {
      when(io.connNetwork.data.qOutTask.ready) {
        tasksGivenAwayCount := tasksGivenAwayCount + 1.U
        stateReg := state.init
      }.otherwise {
        stateReg := state.giveAwayTask
      }

      io.connNetwork.data.qOutTask.valid := true.B // data is valid for the network.
      io.connNetwork.data.qOutTask.bits := giveTaskReg // put the popped task as input for the network.

    }.elsewhen(stateReg === state.serveStealRequests) {
      when(
        io.connQ.currLength > maxLengthThresh.U ||
          (io.connNetwork.ctrl.serveStealReq.ready && io.connQ.currLength >= minLengthThresh.U)
      ) {
        stateReg := state.popTask
      }.elsewhen(io.connQ.currLength < minLengthThresh.U && io.connNetwork.ctrl.serveStealReq.ready) {
        requestFullCount := networkLength.U(32.W)
        stateReg := state.requestTask
        taskRequestCount := 2.U
      }.elsewhen(io.connQ.currLength < minLengthThresh.U) {
        requestFullCount := networkLength.U(32.W)
        stateReg := state.requestTask
        taskRequestCount := 1.U
      }.otherwise {
        stateReg := state.serveStealRequests
      }

      io.connNetwork.ctrl.serveStealReq.valid := true.B // Digest a steal request

    }
  }
}
