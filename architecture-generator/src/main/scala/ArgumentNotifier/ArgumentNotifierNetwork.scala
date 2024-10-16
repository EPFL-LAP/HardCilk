package ArgumentNotifier

import chisel3._
import chisel3.util._
import Allocator.AllocatorBuffer

import chext.elastic
import chext.elastic.ConnectOp._

class ArgumentNotifierNetworkIO(addrWidth: Int, peCount: Int, vasNum: Int) extends Bundle {
  val connVAS = Vec(vasNum, DecoupledIO(UInt(addrWidth.W)))
  val connPE = Vec(peCount, Flipped(DecoupledIO(UInt(addrWidth.W))))
}

class ArgumentNotifierNetwork(addrWidth: Int, taskWidth: Int, peCount: Int, vasNum: Int, queueDepth: Int, cutCount: Int)
    extends Module {

  def highestPowerOf2(n: Int): Int = {
    var res = 0
    var i = n
    if (isPow2(n)) {
      return n
    }
    while (i >= 1) {
      // If i is a power of 2
      if ((i & (i - 1)) == 0) {
        res = i
        return 2 * res
      }
      i -= 1
    }
    2 * res
  }

  // require vasNum is a power of 2, peCount is greater than 0, queueDepth is greater than 0, and addrWidth is power of 2
  // isPow2(vasNum)
  require(peCount > 0)
  require(queueDepth > 0)
  require(isPow2(addrWidth))

  private def connectZeros[T <: Data](bits: T) = {
    bits := 0.U(bits.getWidth.W).asTypeOf(bits)
  }

  val upperPowerOf2 = highestPowerOf2(vasNum)

  val tagBitsShift = log2Ceil(taskWidth / 8)
  val tagList = List.tabulate(upperPowerOf2)(n => n)

  val io = IO(new ArgumentNotifierNetworkIO(addrWidth, peCount, vasNum))
  val networkUnits =
    Seq.tabulate(peCount)(idx => Module(new ArgumentNotifierNetworkUnit(addrWidth, (peCount - idx - 1) % math.ceil(peCount.toDouble / cutCount).toInt)))

  /*
    val virtNetworkUnits = tagList.zipWithIndex.map { case (tag, index) =>
        //print(f"addrressWidth: ${addrWidth} \n tagBitsSize: ${log2Ceil(vasNum)} \n tagBitsShift: ${tagBitsShift} \n tag: ${tag} \n vasNum: ${vasNum} \n")
        Module(new ArgumentNotifierServerNetworkUnit(addrWidth, log2Ceil(vasNum), tagBitsShift, tag, vasNum))
    }*/

  val queues = Seq.fill(peCount)(Module(new AllocatorBuffer(addrWidth, queueDepth)))

  // Connect the PEs to the queues
  for (i <- 0 until peCount) {
    io.connPE(i) <> queues(i).io.addressIn
  }

  // Connection of PEs network units
  for (i <- 0 until peCount) {
    networkUnits(i).io.peAddress <> queues(i).io.addressOut
  }

  // Cut the network in `cutCount` parts
  assert(cutCount <= peCount)

  val arbs = io.connVAS.map(x => Module(new elastic.Arbiter(chiselTypeOf(x.bits), cutCount, chooserFn = elastic.Chooser.rr)))
  arbs.zip(io.connVAS).map(x => x._1.io.sink <> x._2)

  val nElemsInLine = math.ceil(peCount.toDouble / cutCount.toDouble).toInt
  for (i <- 0 until cutCount) {
    val startIndex = i * nElemsInLine
    val endIndex = math.min(startIndex + nElemsInLine - 1, peCount - 1)
    for (j <- startIndex until endIndex) {
      networkUnits(j).io.addressIn <> networkUnits(j + 1).io.addressOut
    }

    networkUnits(endIndex).io.addressIn.valid := false.B
    connectZeros(networkUnits(endIndex).io.addressIn.bits)

    val demux = Module(new elastic.Demux(UInt(addrWidth.W), vasNum))
    arbs.foreach(x => x.io.select.deq())
    arbs.zip(demux.io.sinks).map(x => x._2 :=> x._1.io.sources(i))
    new elastic.Fork(networkUnits(startIndex).io.addressOut) {
      protected def onFork: Unit = {
        fork() :=> demux.io.source
        new elastic.Transform(fork(), demux.io.select) {
          protected def onTransform: Unit = {
            out := (in >> tagBitsShift.U) % vasNum.U
          }
        }
      }
    }
  }

  // for (i <- 0 until peCount / 2 - 1) {
  //   networkUnits(i).io.addressIn <> networkUnits(i + 1).io.addressOut
  // }

  // for (i <- peCount / 2 until peCount - 1) {
  //   networkUnits(i).io.addressIn <> networkUnits(i + 1).io.addressOut
  // }

  // networkUnits(peCount - 1).io.addressIn.valid := false.B
  // connectZeros(networkUnits(peCount - 1).io.addressIn.bits)

  // val demux = Module(new elastic.Demux(UInt(addrWidth.W), vasNum))
  // arbs.foreach(x => x.io.select.deq())
  // arbs.zip(demux.io.sinks).map(x => x._2 :=> x._1.io.sources(0))
  // new elastic.Fork(networkUnits(peCount / 2).io.addressOut) {
  //   protected def onFork: Unit = {
  //     fork() :=> demux.io.source
  //     new elastic.Transform(fork(), demux.io.select) {
  //       protected def onTransform: Unit = {
  //         out := (in >> tagBitsShift.U) % vasNum.U
  //       }
  //     }
  //   }
  // }

  // networkUnits(peCount / 2 - 1).io.addressIn.valid := false.B
  // connectZeros(networkUnits(peCount / 2 - 1).io.addressIn.bits)

  // val demux2 = Module(new elastic.Demux(UInt(addrWidth.W), vasNum))
  // arbs.zip(demux2.io.sinks).map(x => x._2 :=> x._1.io.sources(1))
  // new elastic.Fork(networkUnits(0).io.addressOut) {
  //   protected def onFork: Unit = {
  //     fork() :=> demux2.io.source
  //     new elastic.Transform(fork(), demux2.io.select) {
  //       protected def onTransform: Unit = {
  //         out := (in >> tagBitsShift.U) % vasNum.U
  //       }
  //     }
  //   }
  // }
}
