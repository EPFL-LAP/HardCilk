package ArgumentNotifier

import chisel3._
import chisel3.util._
import Allocator.AllocatorBuffer

class ArgumentNotifierNetworkIO(addrWidth: Int, peCount: Int, vasNum: Int) extends Bundle {
  val connVAS = Vec(vasNum, DecoupledIO(UInt(addrWidth.W)))
  val connPE = Vec(peCount, Flipped(DecoupledIO(UInt(addrWidth.W))))
}

class ArgumentNotifierNetwork(addrWidth: Int, taskWidth: Int, peCount: Int, vasNum: Int, queueDepth: Int) extends Module {

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
  val networkUnits = Seq.fill(peCount)(Module(new ArgumentNotifierNetworkUnit(addrWidth)))

  var virtNetworkUnits: List[ArgumentNotifierServerNetworkUnit] = Nil

  var numberVASWithTwoTags = upperPowerOf2 - vasNum

  var currentVas = 0
  var i = 0
  while (i < tagList.length) {
    if (currentVas < numberVASWithTwoTags) {
      // println("Two tags")
      virtNetworkUnits = virtNetworkUnits :+ Module(
        new ArgumentNotifierServerNetworkUnit(
          addrWidth,
          log2Ceil(vasNum),
          tagBitsShift,
          Array(tagList(i), tagList(i + 1)),
          vasNum
        )
      )
      i = i + 2
    } else {
      // println("One tag")
      virtNetworkUnits = virtNetworkUnits :+ Module(
        new ArgumentNotifierServerNetworkUnit(addrWidth, log2Ceil(vasNum), tagBitsShift, Array(tagList(i)), vasNum)
      )
      i = i + 1
    }
    currentVas += 1
  }

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
  for (i <- 0 until peCount - 1) {
    networkUnits(i).io.addressIn <> networkUnits(i + 1).io.addressOut
  }

  for (i <- 0 until peCount) {
    networkUnits(i).io.peAddress <> queues(i).io.addressOut
  }

  networkUnits(peCount - 1).io.addressIn.valid := false.B
  connectZeros(networkUnits(peCount - 1).io.addressIn.bits)

  // Connection from the PEs network to the virtual servers network
  virtNetworkUnits(vasNum - 1).io.addressIn <> networkUnits(0).io.addressOut

  // Connection of virtual servers network units
  for (i <- 0 until vasNum - 1) {
    virtNetworkUnits(i).io.addressIn <> virtNetworkUnits(i + 1).io.addressOut
  }
  virtNetworkUnits(0).io.addressOut.ready := false.B
  for (i <- 0 until vasNum) {
    virtNetworkUnits(i).io.vasAddressOut <> io.connVAS(i)
  }

}
