package Scheduler

import chisel3._
import Util._
import scala.math._

class SchedulerLocalNetworkIO(peCount: Int, vssCount: Int, vasCount: Int, taskWidth: Int, queueMaxLength: Int) extends Bundle {
  val connPE = Vec(peCount, new DequeInterface(taskWidth, queueMaxLength))
  val connVSS = Vec(vssCount, new SchedulerNetworkClientIO(taskWidth)) // Connection to virtual steal server.
  val connVAS = Vec(vasCount, new SchedulerNetworkClientIO(taskWidth)) // Connection to virtual argument servers.
  val ntwDataUnitOccupancyVSS = Vec(vssCount, Output(Bool()))
}

class SchedulerLocalNetwork(
    peCount: Int,
    vssCount: Int,
    vasCount: Int,
    taskWidth: Int,
    queueMaxLength: Int,
    qRamReadLatency: Int,
    qRamWriteLatency: Int,
    spawnsItself: Boolean,
    successiveNetworkConfig: Boolean
) extends Module {
  val io = IO(new SchedulerLocalNetworkIO(peCount, vssCount, vasCount, taskWidth, queueMaxLength))

  //println(s"SchedulerLocalNetwork: $peCount PEs, $vssCount VSSs, $vasCount VASs")
  assert(peCount >= vssCount || peCount == 1)
  // Create an array of indicies for the VSSs to be attached to in the stealing network,
  // the indicies should be between the indicies of the PEs with a mod operation.
  val step = (peCount + vasCount) / vssCount

  // if(step == 0) {
  //   step = 
  // }

 // println(f"Vss Count: $vssCount, Step: $step")

  var vssIndicies = Array.tabulate(vssCount)(n => (n + n * step))


  // log vss indicies
  // for (i <- 0 until vssCount) {
  //   println(f"VSS $i index: ${vssIndicies(i)}")
  // }

  if (successiveNetworkConfig) {
    vssIndicies = Array.tabulate(vssCount)(n => (n))
  }

  // Instantiate the stealing network.
  val stealNet = Module(new SchedulerNetwork(taskWidth, peCount + vasCount + vssCount, vssIndicies))

  var minLengthThresh = min(max((0.2 * queueMaxLength).asInstanceOf[Int], 1), 8)

  var maxLengthThresh = max((0.7 * queueMaxLength).asInstanceOf[Int], 1)

  // if (!spawnsItself) {
  //   minLengthThresh = (0.8 * queueMaxLength).asInstanceOf[Int]
  //   maxLengthThresh = queueMaxLength - 1
  // }

  assert(minLengthThresh < queueMaxLength)
  assert(maxLengthThresh <= queueMaxLength)

  // Instantiate the stealing servers.
  val stealServers = Seq.fill(peCount)(
    Module(
      new SchedulerClient(
        taskWidth,
        queueMaxLength,
        minLengthThresh,
        maxLengthThresh,
        peCount + vasCount + vssCount,
        successiveNetworkConfig
      )
    )
  )

  // Instantiate the task queues.
  // N.B. The plus two for the queueMaxLength is a quick (and less complex) solution for the circular queue pointer arithmetic
  val taskQueues = Seq.fill(peCount)(Module(new Deque(taskWidth, queueMaxLength + 2, qRamReadLatency, qRamWriteLatency)))

  // Connect the task queues to the output of the module (connPE)
  for (i <- 0 until peCount) {
    taskQueues(i).io.connVec(0) <> io.connPE(i) // connVec(0) has priority in popping.
  }

  // Connect the stealing servers to the task queues
  for (i <- 0 until peCount) {
    taskQueues(i).io.connVec(1) <> stealServers(i).io.connQ
  }

  // Connect the stealNetwork to the stealing servers
  for (i <- 0 until peCount) {
    stealNet.io.connSS(i + 1) <> stealServers(i).io.connNetwork
  }

  // Connect the task queues to the output of the module (connPE)
  if (successiveNetworkConfig) {
    var vssIndex = 0
    var ssIndex = 0
    var vasIndex = 0

    for (i <- 0 until (peCount + vssCount + vasCount)) {
      if (i < vssCount) {
        stealNet.io.connSS(i).data <> io.connVSS(vssIndex).data
        vssIndex += 1
      } else if (i < vssCount + vasCount) {
        stealNet.io.connSS(i).data <> io.connVAS(vasIndex).data
        vasIndex += 1
      } else {
        stealNet.io.connSS(i).data <> stealServers(ssIndex).io.connNetwork.data
        ssIndex += 1
      }
    }

    vssIndex = 0
    ssIndex = 0
    vasIndex = 0

    for (i <- 0 until (peCount + vssCount + vasCount)) {
      if (i < vasCount) {
        stealNet.io.connSS(i).ctrl <> io.connVAS(vasIndex).ctrl
        vasIndex += 1
      } else if (i < vasCount + vssCount) {
        stealNet.io.connSS(i).ctrl <> io.connVSS(vssIndex).ctrl
        vssIndex += 1
      } else {
        stealNet.io.connSS(i).ctrl <> stealServers(ssIndex).io.connNetwork.ctrl
        ssIndex += 1
      }
    }
  } else {
    var vssIndex = 0
    var ssIndex = 0
    var vasIndex = 0
    
    var vasAddedToTheChainFlag = 0

    var PEsPerVAS = 1.0
    if(vasCount != 0){
      PEsPerVAS = ceil(peCount.toDouble / vasCount.toDouble) 
    }
    //println(f"PEsPerVAS: ${PEsPerVAS}")

    var PEsAdded = 0

    //println(f"VAS count ${vasCount}")
    for (i <- 0 until (peCount + vssCount + vasCount)) {
      // println("Entered The for loop")
      if (vssIndicies.contains(i)) {
        // println("\t\tConnecting a vss")
        stealNet.io.connSS(i) <> io.connVSS(vssIndex)
        vssIndex += 1
        vasAddedToTheChainFlag = 0
      } else if (vasIndex < vasCount && vasAddedToTheChainFlag == 0) {
        // println("\t\tConnecting a vas")
        vasAddedToTheChainFlag = 1
        stealNet.io.connSS(i) <> io.connVAS(vasIndex)
        //println(f"A VAS was added at ${i}")
        vasIndex += 1
      } else if (ssIndex < peCount) {
        // println("\t\tConnecting a steal server")
        stealNet.io.connSS(i) <> stealServers(ssIndex).io.connNetwork
        ssIndex += 1
        PEsAdded += 1
        if(PEsAdded.toDouble == PEsPerVAS){
          vasAddedToTheChainFlag = 0
          PEsAdded = 0
        }
      } else if (vasIndex < vasCount) {
        // println("\t\tConnecting vas")
        stealNet.io.connSS(i) <> io.connVAS(vasIndex)
        //println(f"A VAS was added at ${i}")
        vasIndex += 1
      }
    }
  }

  for (i <- 0 until vssCount) {
    stealNet.io.ntwDataUnitOccupancyVSS(i) <> io.ntwDataUnitOccupancyVSS(i)
  }

  // val requestesGeneratedCounter = Module(new Counter64(peCount + vasCount + vssCount))
  // val tasksTakenCounter =  Module(new Counter64(peCount + vasCount + vssCount))
  // val requestesDigestedCounter = Module(new Counter64(peCount + vasCount + vssCount))
  // val tasksGeneratedCounter = Module(new Counter64(peCount + vasCount + vssCount))

  // val count = peCount + vasCount + vssCount
  // for(i <- 0 until  count){
  //   requestesGeneratedCounter.io.signals(i) := (stealNet.io.connSS(i).ctrl.stealReq.valid && stealNet.io.connSS(i).ctrl.stealReq.ready)
  //   requestesDigestedCounter.io.signals(i) := (stealNet.io.connSS(i).ctrl.serveStealReq.valid && stealNet.io.connSS(i).ctrl.serveStealReq.ready)

  //   tasksTakenCounter.io.signals(i) := stealNet.io.connSS(i).data.availableTask.fire
  //   tasksGeneratedCounter.io.signals(i) := stealNet.io.connSS(i).data.qOutTask.fire
  // }


  // dontTouch(requestesGeneratedCounter.counter)
  // dontTouch(tasksTakenCounter.counter)
  // dontTouch(requestesDigestedCounter.counter)
  // dontTouch(tasksGeneratedCounter.counter)

  // val cyclesCounter = RegInit(0.U(64.W))
  // cyclesCounter := cyclesCounter + 1.U
  // when(cyclesCounter === 100000.U){
  //   printf("_______\n")
  //   printf("req gen = %d, req supp = %d, taskGen = %d, taskTaken = %d, width = %d \n", requestesGeneratedCounter.io.counter, 
  //   requestesDigestedCounter.io.counter, 
  //   tasksGeneratedCounter.io.counter, 
  //   tasksTakenCounter.io.counter,
  //   taskWidth.U)
  //   printf("_______\n")
  //   cyclesCounter := 0.U
  // }
  

}
