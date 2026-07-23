package Scheduler

import chisel3._
import Util._
import chisel3.ChiselEnum
import chisel3.util.{log2Ceil, Queue}

class SchedulerClientIO(
    taskWidth: Int,
    queueMaxLength: Int,
    useExternalDeque: Boolean
) extends Bundle {
  val connNetwork = Flipped(new SchedulerNetworkClientIO(taskWidth))
  val connQ =
    if (useExternalDeque)
      Some(Flipped(new DequeInterface(taskWidth, queueMaxLength)))
    else None
  val toPE =
    if (useExternalDeque) None
    else Some(new DequeInterface(taskWidth, queueMaxLength))

}

class SchedulerClient(
    taskWidth: Int,
    queueMaxLength: Int,
    minLengthThresh: Int,
    maxLengthThresh: Int,
    networkLength: Int,
    vssIgnoresRequests: Boolean
) extends Module {
  val io = IO(
    new SchedulerClientIO(taskWidth, queueMaxLength, vssIgnoresRequests)
  )

  if (vssIgnoresRequests) {
    require(io.connQ.isDefined)

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

    val tasksGivenAwayCount = RegInit(0.U(32.W))

    val requestKilledCount = RegInit(networkLength.U(32.W))
    val requestFullCount = RegInit(networkLength.U(32.W))

    // Queue Outputs
    io.connQ.get.pop.ready := false.B
    io.connQ.get.push.bits := 0.U
    io.connQ.get.push.valid := false.B
    // Data Network Outputs
    io.connNetwork.data.availableTask.ready := false.B
    io.connNetwork.data.qOutTask.valid := false.B
    io.connNetwork.data.qOutTask.bits := 0.U
    // Ctrl Network Outputs
    io.connNetwork.ctrl.stealReq.valid := false.B
    io.connNetwork.ctrl.serveStealReq.valid := false.B

    val requestTaskCount = RegInit(0.U(32.W))

    // This configuration is when the task is spawned by another task or is continuation
    when(stateReg === state.init) {
      when(io.connQ.get.currLength < minLengthThresh.U) {
        requestFullCount := networkLength.U(32.W) + 2.U
        requestKilledCount := networkLength.U(32.W) + 2.U
        stateReg := state.takeInTask
        requestTaskCount := requestTaskCount + 1.U
      }.elsewhen(io.connQ.get.currLength > maxLengthThresh.U) {
        stateReg := state.popTask
      }.elsewhen(io.connQ.get.currLength > minLengthThresh.U) {
        stateReg := state.serveStealRequests
      }.otherwise {
        stateReg := state.init
      }
    }.elsewhen(stateReg === state.takeInTask) {

      when(io.connNetwork.data.availableTask.valid) {
        stateReg := state.pushTask
        stolenTaskReg := io.connNetwork.data.availableTask.bits
      }.elsewhen(io.connQ.get.currLength >= minLengthThresh.U) {
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
      when(io.connQ.get.push.ready) {
        stateReg := state.init
      }.elsewhen(io.connQ.get.currLength >= maxLengthThresh.U) {
        stateReg := state.giveAwayTask
        giveTaskReg := stolenTaskReg
      }.otherwise {
        stateReg := state.pushTask
      }

      io.connQ.get.push.bits := stolenTaskReg // Pass the stolen task to the queue.
      io.connQ.get.push.valid := true.B // Make the task valid for the queue.

    }.elsewhen(stateReg === state.popTask) {
      when(io.connQ.get.pop.valid) {
        stateReg := state.giveAwayTask
        giveTaskReg := io.connQ.get.pop.bits
      }.elsewhen(io.connQ.get.currLength === 0.U) {
        stateReg := state.takeInTask
        requestKilledCount := networkLength.U(32.W) + 2.U
        requestTaskCount := requestTaskCount + 1.U
      }.otherwise {
        stateReg := state.popTask
      }

      io.connQ.get.pop.ready := true.B // Pop the available task from the queue

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
        io.connQ.get.currLength > maxLengthThresh.U ||
          (io.connNetwork.ctrl.serveStealReq.ready && io.connQ.get.currLength >= minLengthThresh.U)
      ) {
        stateReg := state.popTask
      }.elsewhen(
        io.connQ.get.currLength < minLengthThresh.U && io.connNetwork.ctrl.serveStealReq.ready
      ) {
        requestKilledCount := networkLength.U(32.W) + 2.U
        stateReg := state.takeInTask
        requestTaskCount := requestTaskCount + 2.U
      }.elsewhen(io.connQ.get.currLength < minLengthThresh.U) {
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
        && !(stateReg === state.serveStealRequests && io.connQ.get.currLength < minLengthThresh.U)
        && !(stateReg === state.popTask && io.connQ.get.currLength === 0.U)
        && !(stateReg === state.takeInTask && requestKilledCount === 0.U)
    ) {
      io.connNetwork.ctrl.stealReq.valid := true.B
      when(io.connNetwork.ctrl.stealReq.ready) {
        requestTaskCount := requestTaskCount - 1.U
      }
    }
  } else {
    require(io.connQ.isEmpty, "DO NOT INSTANTIATE A DEQUE")
    require(io.toPE.isDefined)

    // Instantiate Queue and other variables
    val taskQueue = Module(
      new Queue(UInt(taskWidth.W), queueMaxLength)
    )
    val countWidth = log2Ceil(queueMaxLength * 2 + 1) + 1

    // At the start, we want to fill up to the min steal threshold. We only pull
    // from the ring up to `min` and leave any surplus stealable; locally spawned
    // tasks can push the queue above `min`, and we start shedding at `max`.
    val desiredSteals = RegInit(minLengthThresh.S(countWidth.W))
    val serveCredits = RegInit(0.U(countWidth.W))
    val stealReqSentThisCycle = Wire(Bool())
    val stealReqConsumedThisCycle = Wire(Bool())

    // If desiredSteals is positive, we should send
    io.connNetwork.ctrl.stealReq.valid := desiredSteals > 0.S
    stealReqSentThisCycle := desiredSteals > 0.S && io.connNetwork.ctrl.stealReq.ready

    // PE gets priority to Push/Pop
    val peDidPush = Wire(Bool())
    val peDidPop = Wire(Bool())

    io.toPE.get.push.ready := taskQueue.io.enq.ready
    taskQueue.io.enq.valid := io.toPE.get.push.valid
    taskQueue.io.enq.bits := io.toPE.get.push.bits

    io.toPE.get.pop.valid := taskQueue.io.deq.valid
    io.toPE.get.pop.bits := taskQueue.io.deq.bits
    taskQueue.io.deq.ready := io.toPE.get.pop.ready

    io.toPE.get.currLength := taskQueue.io.count
    peDidPush := io.toPE.get.push.ready && io.toPE.get.push.valid
    peDidPop := io.toPE.get.pop.ready && io.toPE.get.pop.valid

    // Consume a passing task only while below the minimum local threshold;
    // reserve the remaining queue capacity for self-spawned work.
    val consumedTaskFromRing = Wire(Bool())
    io.connNetwork.data.availableTask.ready := false.B
    consumedTaskFromRing := io.connNetwork.data.availableTask.valid && io.connNetwork.data.availableTask.ready
    when(taskQueue.io.count < minLengthThresh.U && !io.toPE.get.push.valid) {
      io.connNetwork.data.availableTask.ready := taskQueue.io.enq.ready
      taskQueue.io.enq.bits := io.connNetwork.data.availableTask.bits
      taskQueue.io.enq.valid := io.connNetwork.data.availableTask.valid
    }

    // When we have extra tasks, we should consume a steal request. The task can be output later.
    io.connNetwork.ctrl.serveStealReq.valid := (taskQueue.io.count - peDidPop.asUInt) >
      (minLengthThresh.U + serveCredits)
    stealReqConsumedThisCycle :=
      io.connNetwork.ctrl.serveStealReq.valid && io.connNetwork.ctrl.serveStealReq.ready

    // When we have consumed a steal request, we should push a task out to the ring (assuming the PE is not popping)
    val pushedTaskToRing = Wire(Bool())
    io.connNetwork.data.qOutTask.bits := taskQueue.io.deq.bits
    io.connNetwork.data.qOutTask.valid := false.B
    pushedTaskToRing := io.connNetwork.data.qOutTask.ready && io.connNetwork.data.qOutTask.valid

    when(
      !io.toPE.get.pop.ready &&
        taskQueue.io.deq.valid &&
        (serveCredits > 0.U || stealReqConsumedThisCycle)
    ) {
      io.connNetwork.data.qOutTask.valid := true.B
      taskQueue.io.deq.ready := io.connNetwork.data.qOutTask.ready
    }

    when(stealReqConsumedThisCycle && !pushedTaskToRing) {
      serveCredits := serveCredits + 1.U
    }.elsewhen(!stealReqConsumedThisCycle && pushedTaskToRing) {
      serveCredits := serveCredits - 1.U
    }

    // desiredSteals counts how many steal requests we still want to put on the ring. It moves on
    // exactly two events, and taking a task off the network is NOT one of them:
    //
    //   -1  when we successfully inject a steal request  (that slot is now spoken for)
    //   +1  when a task leaves our queue                 (that slot needs filling again)
    //
    // Every wanted slot is then in exactly one of three places -- still to be asked for
    // (desiredSteals), asked for (a token on the ring), or on its way (a task on the ring):
    //
    //   sum of (minLengthThresh - count) = sum of desiredSteals + tokensOnRing + tasksOnRing
    //
    // and an arrival moves a slot from "task on the ring" into our queue, dropping both sides by one
    // on their own. So the counter must not move, and the sum holds no matter which node ends up
    // taking which task.
    //
    // This used to decrement when the arriving task answered somebody else's request, which burns
    // two credits for one delivered task -- the requester already spent its own when it sent. The
    // sum then drifts by one on every such arrival, monotonically. Measured on hw_emu: all eight
    // adder clients reached zero with EMPTY queues and stopped asking, the ring emptied at cycle
    // ~40000, and 18 tasks sat undeliverable for the remaining 35000 cycles.
    val ringNet =
      Mux(stealReqConsumedThisCycle, 1.S(countWidth.W), 0.S(countWidth.W)) -
        Mux(stealReqSentThisCycle, 1.S(countWidth.W), 0.S(countWidth.W))

    // PE book-keeping
    val pushPopNet = WireDefault(0.S(countWidth.W))
    when(peDidPop && !peDidPush) {
      pushPopNet := 1.S
    }.elsewhen(!peDidPop && peDidPush) {
      pushPopNet := -1.S
    }

    desiredSteals := desiredSteals + ringNet + pushPopNet
  }
}
