package chext.elastic

import chisel3._
import scala.annotation.nowarn
import chext.elastic
import scala.annotation.nowarn


class AdvArbiter[T <: Data](
    val gen: T,
    val n: Int,
    val chooserFn: Chooser.ChooserFn,
    val isLastFn: (T) => Bool
) extends Module {
  require(n > 0)
  val genSelect = UInt(chisel3.util.log2Up(n).W)

  override def desiredName: String = "elasticAdvArbiter"

  val io = IO(new Bundle {
    val sources = Vec(n, Source(gen))
    val sink = Sink(gen)
    val select = Sink(genSelect)
  })

  {
    import ConnectOp._

    val ewire0_N = Seq.fill(n) { Wire(elastic.Interface(gen)) }
    val ewire1_N = Seq.fill(n) { Wire(elastic.Interface(genSelect)) }

    val mux0 = Module(new Mux(gen, n, isLastFn))
    val arbiter0 = Module(new BasicArbiter(genSelect, n, chooserFn))

    ewire0_N.zip(mux0.io.sources).foreach {
      case (source, sink) => source :=> sink
    }

    ewire1_N.zip(arbiter0.io.sources).foreach {
      case (source, sink) => source :=> sink
    }

    arbiter0.io.select.deq()
   // arbiter0.io.select.markSource()

    mux0.io.sink :=> io.sink

    @nowarn("cat=unused")
    val fork0 = new elastic.Fork(arbiter0.io.sink) {
      override def onFork(): Unit = {
        fork(in) :=> mux0.io.select
        fork(in) :=> io.select
      }
    }

    io.sources.zip {
      ewire0_N.zip(ewire1_N).zipWithIndex
    }.foreach {
      case (source, ((sink1, sink2), index)) => {
        @nowarn("cat=unused") 
        val fork1 = new Fork(source) {
          override def onFork(): Unit = {
            fork(in) :=> sink1

            val transducer0 = new Transducer(fork(), sink2) {
              val state = RegInit(true.B)
              out := index.U

              val isLast = isLastFn(in)

              packet {
                when(state) {
                  when(isLast) {
                    accept {}
                  }.otherwise {
                    accept { state := false.B }
                  }
                }.otherwise {
                  consume { state := isLast }
                }
              }
            }    
          }     
        }
      }
    }
  }
}