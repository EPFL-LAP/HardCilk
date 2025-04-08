package ArgumentNotifier

import chisel3._
import chisel3.util._
import chisel3.experimental.prefix

import chext.amba.axi4
import chext.elastic
import chext.elastic.ConnectOp._

import Util._

class ArgumentServerIO(taskWidth: Int, counterWidth: Int, sysAddressWidth: Int, wId: Int) extends Bundle {
  val connNetwork = Flipped(DecoupledIO(UInt(sysAddressWidth.W)))
  val connStealNtw = Flipped(new SchedulerNetworkClientIO(taskWidth))

  val m_axi_counter = axi4.full.Master(cfg = axi4.Config(wId, 34, taskWidth))
  val m_axi_task = axi4.full.Master(cfg = axi4.Config(wId, 34, taskWidth))

  val done = Output(Bool())
}

class ArgumentServer(
    taskWidth: Int,
    counterWidth: Int,
    sysAddressWidth: Int,
    tagBitsShift: Int,
    wId: Int,
    multiDecrease: Boolean = false
) extends Module {

  val io = IO(new ArgumentServerIO(taskWidth, counterWidth, sysAddressWidth, wId))

  // Implementation
  io.m_axi_task.aw.noenq()
  io.m_axi_task.w.noenq()
  io.m_axi_task.b.nodeq()

  private val nInflight = 1 << wId
  private val bufferQueueDepth = 2 * nInflight

  private val regDone = RegInit(false.B)
  io.done := regDone

  object InflightStage extends ChiselEnum {
    val cnt_rd, cnt_wd = Value
  }

  class InflightArgument extends Bundle {
    val addr = UInt(sysAddressWidth.W)
    val stage = InflightStage()
    val decrement = UInt(counterWidth.W)
  }

  private val memInflightValid = RegInit(VecInit.fill(nInflight)(false.B))
  private val memInflight = Reg(Vec(nInflight, new InflightArgument()))

  prefix("input") {
    val arbInput = Module(new elastic.BasicArbiter(chiselTypeOf(io.connNetwork.bits), 2, chooserFn = elastic.Chooser.rr))
    new elastic.Transform(io.connNetwork, arbInput.io.sources(0)) {
      protected def onTransform: Unit =
        out := in(in.getWidth - 1, tagBitsShift) ## 0.U(tagBitsShift.W)
    }

    arbInput.io.select.nodeq()
    when(arbInput.io.select.valid) {
      arbInput.io.select.deq()
    }

    val dmuxInput = Module(
      new elastic.Demux(
        new Bundle {
          val addr = chiselTypeOf(io.connNetwork.bits)
          val id = UInt(wId.W)
        },
        2
      )
    )

    val qFeedback = Module(new Queue(chiselTypeOf(dmuxInput.io.sinks(1).bits), 8))
    dmuxInput.io.sinks(1) :=> qFeedback.io.enq

    new elastic.Transform(elastic.SourceBuffer(qFeedback.io.deq), arbInput.io.sources(1)) {
      protected def onTransform: Unit = {
        out := in.addr
      }
    }

    val dmuxInputDataSel = Wire(new DecoupledIO(new Bundle {
      val addr = chiselTypeOf(io.connNetwork.bits)
      val id = UInt(wId.W)
      val sel = UInt(1.W)
    }))

    // Check Inflights
    new elastic.Arrival(elastic.SourceBuffer(arbInput.io.sink, bufferQueueDepth), elastic.SinkBuffer(dmuxInputDataSel)) {
      protected def onArrival: Unit = {
        val inAddr = Wire(in.cloneType)
        if (multiDecrease) {
          inAddr := in(in.getWidth - counterWidth - 2, 0)
        } else {
          inAddr := in
        }

        val matchList = memInflightValid.zip(memInflight).map { case (valid, inflight) =>
          valid && (inflight.addr === inAddr)
        }

        val matchAddr = PriorityEncoder(matchList)
        val matchValid = matchList.reduce(_ || _)

        val firstEmpty = PriorityEncoder(~memInflightValid.asUInt)
        val isFull = memInflightValid.asUInt.andR

        out := DontCare
        when(matchValid) {
          // If there is any match, collapse operations if possible
          when(memInflight(matchAddr).stage === InflightStage.cnt_rd) {
            // Collapse two operations to one
            val decrement = Wire(memInflight(matchAddr).decrement.cloneType)
            if (multiDecrease) {
              when(in(in.getWidth - 1) === 1.U) {
                decrement := in(in.getWidth - 2, in.getWidth - counterWidth - 1)
              }.otherwise {
                decrement := 1.U
              }
            } else {
              decrement := 1.U
            }
            memInflight(matchAddr).decrement := memInflight(matchAddr).decrement + decrement
            drop()
          }.otherwise {
            // Wait for the previous operation to complete
            when(qFeedback.io.count >= 3.U) { // WARNING: This caused a deadlock at some point, maybe needs further investigation
              out.addr := in
              out.sel := 1.U
              accept()
            }
          }
        }.otherwise {
          // If there are no matches, try to put it to the inflight operations. If it is full, wait for a previous operation to complete
          when(~isFull) {
            memInflightValid(firstEmpty) := true.B
            memInflight(firstEmpty).stage := InflightStage.cnt_rd
            if (multiDecrease) {
              memInflight(firstEmpty).addr := inAddr
              when(in(in.getWidth - 1) === 1.U) {
                memInflight(firstEmpty).decrement := in(in.getWidth - 2, in.getWidth - counterWidth - 1)
              }.otherwise {
                memInflight(firstEmpty).decrement := 1.U
              }
              out.addr := inAddr
            } else {
              memInflight(firstEmpty).addr := in
              memInflight(firstEmpty).decrement := 1.U
              out.addr := in
            }
            out.id := firstEmpty
            out.sel := 0.U
            accept()
          }.otherwise {
            when(qFeedback.io.count >= 3.U) {
              out.addr := in
              out.sel := 1.U
              accept()
            }
          }
        }
      }
    }

    // Connect the demux to the inflight operations and axi.ar
    new elastic.Fork(dmuxInputDataSel) {
      protected def onFork: Unit = {
        new elastic.Transform(fork(), dmuxInput.io.source) {
          protected def onTransform: Unit = {
            out.addr := in.addr
            out.id := in.id
          }
        }

        new elastic.Transform(fork(), dmuxInput.io.select) {
          protected def onTransform: Unit = {
            out := in.sel
          }
        }
      }
    }

    // Connect the inflight operations to the axi.ar
    new elastic.Transform(dmuxInput.io.sinks(0), io.m_axi_counter.ar) {
      protected def onTransform: Unit = {
        out := 0.U.asTypeOf(out)
        out.addr := in.addr
        out.id := in.id
        out.len := 0.U
        out.size := log2Ceil(counterWidth / 8).U
      }
    }
  }

  prefix("update") {
    val dmux = Module(
      new elastic.Demux(
        new Bundle {
          val id = UInt(wId.W)
          val addr = UInt(sysAddressWidth.W)
          val data = UInt(taskWidth.W)
        },
        2
      )
    )

    val dmuxDataSel = Wire(DecoupledIO(new Bundle {
      val id = UInt(wId.W)
      val addr = UInt(sysAddressWidth.W)
      val data = UInt(taskWidth.W)
      val sel = UInt(1.W)
    }))

    val readData = Wire(io.m_axi_counter.r.cloneType)

    new elastic.Arrival(io.m_axi_counter.r, elastic.SinkBuffer(readData)) {
      protected def onArrival: Unit = {
        out := in
        memInflight(in.id).stage := InflightStage.cnt_wd
        accept()
      }
    }

    new elastic.Arrival(readData, dmuxDataSel) {
      protected def onArrival: Unit = {
        val inflight = memInflight(in.id)
        val decrement = WireInit(UInt(counterWidth.W), inflight.decrement)
        val counter = in.data(counterWidth - 1, 0)
        out.id := in.id
        out.addr := inflight.addr
        out.data := counter - decrement
        out.sel := !((counter > decrement) || (counter === 0.U)).asUInt
        regDone := counter === 0.U

        memInflight(in.id).stage := InflightStage.cnt_wd

        accept()
      }
    }

    new elastic.Fork(dmuxDataSel) {
      protected def onFork: Unit = {
        new elastic.Transform(fork(), dmux.io.source) {
          protected def onTransform: Unit = {
            out.id := in.id
            out.addr := in.addr
            out.data := in.data
          }
        }

        new elastic.Transform(fork(), dmux.io.select) {
          protected def onTransform: Unit = {
            out := in.sel
          }
        }
      }
    }

    new elastic.Fork(dmux.io.sinks(0)) {
      protected def onFork: Unit = {
        new elastic.Transform(fork(), io.m_axi_counter.aw) {
          protected def onTransform: Unit = {
            out := 0.U.asTypeOf(out)
            out.addr := in.addr
            out.id := in.id
            out.size := log2Ceil(counterWidth / 8).U
          }
        }

        new elastic.Transform(fork(), io.m_axi_counter.w) {
          protected def onTransform: Unit = {
            out.data := in.data
            out.last := true.B
            out.strb := ~(0.U((counterWidth / 8).W))
            out.user := 0.U
          }
        }
      }
    }

    new elastic.Arrival(dmux.io.sinks(1), io.m_axi_task.ar) {
      protected def onArrival: Unit = {
        memInflightValid(in.id) := false.B

        out := 0.U.asTypeOf(out)
        out.id := in.id
        out.addr := in.addr
        out.size := log2Ceil(taskWidth / 8).U
        out.len := 0.U

        accept()
      }
    }
  }

  prefix("writeResponse") {
    io.m_axi_counter.b.nodeq()
    when(io.m_axi_counter.b.valid) {
      val b = io.m_axi_counter.b.deq()
      memInflightValid(b.id) := false.B
    }
  }

  prefix("spawn") {
    io.connStealNtw.data.availableTask.nodeq()

    val rTaskCount = Module(new chext.util.Counter(1 << 16))
    rTaskCount.noInc()
    rTaskCount.noDec()
    new elastic.Arrival(io.m_axi_task.r, io.connStealNtw.data.qOutTask) {
      protected def onArrival: Unit = {
        when(rTaskCount.notFull) {
          rTaskCount.inc()
          out := in.data
          accept()
        }
      }
    }

    io.connStealNtw.ctrl.serveStealReq.valid := false.B

    io.connStealNtw.ctrl.stealReq.valid := false.B
    when(rTaskCount.notZero) {
      io.connStealNtw.ctrl.stealReq.valid := true.B
      when(io.connStealNtw.ctrl.stealReq.ready) {
        rTaskCount.dec()
      }
    }
  }
}
