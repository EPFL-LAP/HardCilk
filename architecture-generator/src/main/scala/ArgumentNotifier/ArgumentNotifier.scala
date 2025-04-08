package ArgumentNotifier

import chisel3._
import chisel3.util._

import ArgumentNotifier._
import Util._

import chext.amba.axi4
import chext.amba.axi4s
import chext.elastic

import axi4.Ops._

import axi4s.Casts._
import AXIHelpers.AxisDataWidthConverter

class ArgumentNotifierIO(
    pePortWidth: Int,
    peCount: Int
) extends Bundle {

  implicit val axisCfgAddress: axi4s.Config = axi4s.Config(wData = pePortWidth, onlyRV = true)

  val argIn = Vec(peCount, axi4s.Slave(axisCfgAddress))
  val done = Output(Bool())

  // a getter function for the port with name and index
  def getPort(name: String, index: Int): axi4s.Interface = {
    name match {
      case "argIn" => argIn(index)
    }
  }
}

class ArgumentNotifier(
    addrWidth: Int,
    taskWidth: Int,
    queueDepth: Int,
    peCount: Int,
    argRouteServersNumber: Int,
    contCounterWidth: Int,
    pePortWidth: Int,
    cutCount: Int,
    fpgaCount: Int,
    taskIndex: Int
) extends Module {

  val io_export = IO(new ArgumentNotifierIO(pePortWidth = pePortWidth, peCount = peCount))
  val connStealNtw = IO(Vec(argRouteServersNumber, Flipped(new SchedulerNetworkClientIO(taskWidth))))

  assert(argRouteServersNumber > 0)

  val argSide = Module(
    new ArgumentNotifierNetwork(
      addrWidth = addrWidth,
      taskWidth = taskWidth,
      peCount = peCount,
      vasNum = argRouteServersNumber,
      queueDepth = queueDepth,
      cutCount = cutCount
    )
  )

  val serversList = List.tabulate(argRouteServersNumber)(n => n)
  val argRouteServers = serversList.zipWithIndex.map { case (tag, index) =>
    Module(
      new ArgumentServerMfpgaWrapper(
        taskWidth = taskWidth,
        counterWidth = contCounterWidth,
        sysAddressWidth = addrWidth,
        tagBitsShift = log2Ceil(taskWidth / 8),
        wId = 0,
        multiDecrease = false,
        fpgaCount = fpgaCount,
        taskIndex = taskIndex
      )
    )
  }

  io_export.done := argRouteServers.map(_.io.done).reduce(_ || _)

  val nAxiPorts = 2 * argRouteServersNumber
  val axi_full_argRoute = IO(Vec(nAxiPorts, axi4.full.Master(argRouteServers.head.io.m_axi_counter.cfg)))

  for (i <- 0 until argRouteServersNumber) {
    argRouteServers(i).io.connNetwork <> argSide.io.connVAS(i)
    argRouteServers(i).io.connStealNtw <> connStealNtw(i)
  }

  for (i <- 0 until argRouteServersNumber) {
    argRouteServers(i).io.m_axi_counter :=> axi_full_argRoute(i)
    argRouteServers(i).io.m_axi_task :=> axi_full_argRoute(i + argRouteServersNumber)
  }

  val axis_stream_converters_in =
    Seq.fill(peCount)(Module(new AxisDataWidthConverter(dataWidthIn = pePortWidth, dataWidthOut = addrWidth)))
  for (i <- 0 until peCount) {
    axis_stream_converters_in(i).io.dataOut.asLite <> argSide.io.connPE(i)
    io_export.argIn(i).asLite <> axis_stream_converters_in(i).io.dataIn.asLite
  }


  val master_arbiter =
    if (fpgaCount > 1)
      
      Some(
        Module(
          new elastic.BasicArbiter(
            chiselTypeOf(argRouteServers.head.io.m_axis_remote.get.asFull.bits),
            argRouteServersNumber,
            chooserFn = elastic.Chooser.rr
          )
        )
      )
    else None
  
  if(fpgaCount > 1) {
   master_arbiter.get.io.select.nodeq()
    when(master_arbiter.get.io.select.valid) {
      master_arbiter.get.io.select.deq()
    }
  }

  val m_axis_remote = if (fpgaCount > 1) Some(IO(axi4s.Master(argRouteServers.head.io.axisCfgTaskAndReq))) else None
  val s_axis_remote = if (fpgaCount > 1) Some(IO(axi4s.Slave(argRouteServers.head.io.axisCfgTaskAndReq))) else None
  val fpgaIndexInputReg = if (fpgaCount > 1) Some(IO(Input(UInt(8.W)))) else None
  
  if (fpgaCount > 1) {
    // Connect the m_axis_remote to the master of the arbiter
    m_axis_remote.get.asFull <> master_arbiter.get.io.sink


    for (i <- 0 until argRouteServersNumber) {
      // Connect the axi_mgmt port for all the argument servers
      fpgaIndexInputReg.get <> argRouteServers(i).io.fpgaIndexInputReg.get

      // Make the slave ports of the arbiter connect to the master ports of the argument servers
      master_arbiter.get.io.sources(i) <> argRouteServers(i).io.m_axis_remote.get.asFull
    }

    new elastic.Fork(s_axis_remote.get.asFull) {
      override def onFork(): Unit = {
        elastic.Demux(
          source = fork(in),
          sinks =  argRouteServers map (_.io.s_axis_remote.get.asFull),
          select = fork((in.data & "h3FFFFFFFF".U) >> log2Ceil(taskWidth / 8).U) // select the upper bits of the address
        )
      }
    }

  }

  // DEBUG
  val argInCounter = Module(new Counter64(peCount))
  for (i <- 0 until peCount) {
    argInCounter.io.signals(i) := (io_export.argIn(i).asLite.valid & io_export.argIn(i).asLite.ready)
  }
  dontTouch(argInCounter.io.counter)
  // DEBUG

  // Debug
  val taskOutCounter = Module(new Counter64(argRouteServersNumber))
  for (i <- 0 until argRouteServersNumber) {
    taskOutCounter.io.signals(i) := connStealNtw(i).data.qOutTask.fire
  }
  dontTouch(taskOutCounter.io.counter)
  // Debug


  // Debug
  // Count the arguments coming on s_remote if fpgaCount > 1
  if(fpgaCount>1){
    val remoteCounter = Module(new Counter64(1))

    remoteCounter.io.signals(0) := s_axis_remote.get.asFull.fire
    dontTouch(remoteCounter.io.counter)


    assert(taskOutCounter.io.counter <= (argInCounter.io.counter + remoteCounter.io.counter), "Error: taskOutCounter > argInCounter + remoteCounter")
  } else {
    assert(taskOutCounter.io.counter <= argInCounter.io.counter, "Error: taskOutCounter > argInCounter")
  }




  // If  taskOutCounter > argInCounter, print a debugging statement inidicating the error
  // when(argInCounter.io.counter < taskOutCounter.io.counter) {
  //   printf("Error: argInCounter < taskOutCounter: %d < %d\n", argInCounter.io.counter, taskOutCounter.io.counter)
  // }
  //

}

// object ArgumentNotifierEmitter extends App {
//   emitVerilog(new ArgumentNotifier(64, 256, 16, 16, 2, 32, 64, 1, 4), Array("--target-dir", "output"))
// }

// class ArgumentNotifier(
//     addrWidth: Int,
//     taskWidth: Int,
//     queueDepth: Int,
//     peCount: Int,
//     argRouteServersNumber: Int,
//     contCounterWidth: Int,
//     pePortWidth: Int,
//     cutCount: Int,
//     fpgaCount: Int
// )

// val wa = Wire(Irrevocable(UInt(1.W)))
// val wb = Wire(Irrevocable(UInt(1.W)))

// val wc = Wire(Irrevocable(UInt(2.W)))

// new elastic.Join(wc) {
//   override def onJoin(): Unit = {
//       out := Cat(join(wa), join(wb))
//   }
// }
