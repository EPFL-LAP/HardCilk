package Scheduler

import chisel3._
import Util._
import scala.math._

case class SchedulerLocalRingLayout(
    peNodes: Vector[Int],
    vssNodes: Vector[Int],
    vasNodes: Vector[Int],
    nodeKinds: Vector[String]
)

object SchedulerLocalRingLayout {
  /** Place every injector immediately upstream of the PE group it owns.
    *
    * Data moves from node i to node i + 1.  When a spawner and a scheduler
    * target the same PE, the order is therefore VAS -> VSS -> PE: the scheduler
    * remains adjacent to the PE, while a normally-passive scheduler lets the
    * spawner's task continue into that same PE.  This makes logical spawner i
    * feed logical PE i when their counts match, rather than PE i + 1.
    */
  def build(peCount: Int, vssCount: Int, vasCount: Int): SchedulerLocalRingLayout = {
    require(peCount >= 1)
    // vssCount == 0 is valid: a non-root task with zero scheduler servers has no
    // VSS injection nodes on its local ring (fed purely by VAS spawners). The
    // placement below already degenerates cleanly (empty vssNodes).
    require(vssCount >= 0)

    // Spread fewer-than-PE server counts evenly while retaining the natural
    // i -> i mapping when the count equals peCount.
    def targetPe(server: Int, count: Int): Int =
      if (count == 0) -1 else (server * peCount) / count

    val vssByPe = (0 until vssCount).groupBy(i => targetPe(i, vssCount))
    val vasByPe = (0 until vasCount).groupBy(i => targetPe(i, vasCount))
    val peNodes = Array.fill(peCount)(-1)
    val vssNodes = Array.fill(vssCount)(-1)
    val vasNodes = Array.fill(vasCount)(-1)
    val kinds = scala.collection.mutable.ArrayBuffer.empty[String]

    for (pe <- 0 until peCount) {
      // A VAS precedes a co-located VSS, exactly as required for
      // VAS -> VSS -> PE in the forward data direction.
      for (vas <- vasByPe.getOrElse(pe, Seq.empty)) {
        vasNodes(vas) = kinds.length
        kinds += s"vas$vas"
      }
      for (vss <- vssByPe.getOrElse(pe, Seq.empty)) {
        vssNodes(vss) = kinds.length
        kinds += s"vss$vss"
      }
      peNodes(pe) = kinds.length
      kinds += s"pe$pe"
    }

    SchedulerLocalRingLayout(
      peNodes.toVector,
      vssNodes.toVector,
      vasNodes.toVector,
      kinds.toVector
    )
  }
}

class SchedulerLocalNetworkIO(
    peCount: Int,
    vssCount: Int,
    vasCount: Int,
    taskWidth: Int,
    queueDepth: Int
) extends Bundle {
  val connPE = Vec(peCount, new DequeInterface(taskWidth, queueDepth))
  val connVSS = Vec(
    vssCount,
    new SchedulerNetworkClientIO(taskWidth)
  ) // Connection to virtual steal server.
  val connVAS = Vec(
    vasCount,
    new SchedulerNetworkClientIO(taskWidth)
  ) // Connection to virtual argument servers.
  val ntwDataUnitOccupancyVSS = Vec(vssCount, Output(Bool()))
  val lengths_of_hardware_queues = Vec(peCount, Output(UInt(8.W)))
}

class SchedulerLocalNetwork(
    peCount: Int,
    vssCount: Int,
    vasCount: Int,
    taskWidth: Int,
    queueDepth: Int,
    qRamReadLatency: Int,
    qRamWriteLatency: Int,
    spawnsItself: Boolean,
    successiveNetworkConfig: Boolean
) extends Module {
  val io = IO(
    new SchedulerLocalNetworkIO(
      peCount,
      vssCount,
      vasCount,
      taskWidth,
      queueDepth
    )
  )

  // assert(peCount >= vssCount)

  private val interleavedLayout =
    if (!successiveNetworkConfig)
      Some(SchedulerLocalRingLayout.build(peCount, vssCount, vasCount))
    else None

  val vssIndicies = interleavedLayout
    .map(_.vssNodes.toArray)
    .getOrElse(Array.tabulate(vssCount)(identity))

  // Instantiate the stealing network.
  val stealNet = Module(
    new SchedulerNetwork(taskWidth, peCount + vasCount + vssCount, vssIndicies)
  )

  var minLengthThresh = min(max((0.2 * queueDepth).asInstanceOf[Int], 1), 8)

  var maxLengthThresh = max((0.7 * queueDepth).asInstanceOf[Int], 1)

  if (!spawnsItself) {
    minLengthThresh = (0.3 * queueDepth).asInstanceOf[Int]
    maxLengthThresh = queueDepth - 1
  }

  assert(minLengthThresh < queueDepth)
  assert(maxLengthThresh <= queueDepth)

  // Instantiate the stealing servers.
  val stealServers = Seq.fill(peCount)(
    Module(
      new SchedulerClient(
        taskWidth,
        queueDepth,
        minLengthThresh,
        maxLengthThresh,
        peCount + vasCount + vssCount,
        successiveNetworkConfig
      )
    )
  )

  if (successiveNetworkConfig) {
    // Instantiate the task queues.
    // N.B. The plus two for the queueDepth is a quick (and less complex) solution for the circular queue pointer arithmetic
    val taskQueues = Seq.fill(peCount)(
      Module(
        new Deque(taskWidth, queueDepth + 2, qRamReadLatency, qRamWriteLatency)
      )
    )

    // Connect the task queues to the output of the module (connPE)
    for (i <- 0 until peCount) {
      taskQueues(i).io.connVec(0) <> io.connPE(
        i
      ) // connVec(0) has priority in popping.

      io.lengths_of_hardware_queues(i) := taskQueues(i).io.connVec(0).currLength
    }

    // Connect the stealing servers to the task queues
    for (i <- 0 until peCount) {
      taskQueues(i).io.connVec(1) <> stealServers(i).io.connQ.get
    }
  } else {
    // Connect the PEs directly to the client
    for (i <- 0 until peCount) {
      stealServers(i).io.toPE.get <> io.connPE(
        i
      ) // connVec(0) has priority in popping.

      io.lengths_of_hardware_queues(i) := stealServers(i).io.toPE.get.currLength
    }
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
    val layout = interleavedLayout.get
    for (i <- 0 until peCount) {
      stealNet.io.connSS(layout.peNodes(i)) <> stealServers(i).io.connNetwork
    }

    // A co-located spawner (VAS) sits immediately upstream of its scheduler
    // (VSS) in the VAS -> VSS -> PE layout.  When both inject the same cycle the
    // PE-adjacent VSS task would reach the PE first and bump the spawner's
    // locally-generated task to PE i+1, breaking cache locality.  Route just
    // those two DATA injections through a SchedulerInjectionSwap so the spawner
    // wins the PE-adjacent slot when both fire; availableTask and the whole
    // control ring stay wired straight.
    val vssIndexByNode = (0 until vssCount).map(j => layout.vssNodes(j) -> j).toMap
    val swapPairs = (0 until vasCount).flatMap { i =>
      vssIndexByNode.get(layout.vasNodes(i) + 1).map(j => (i, j))
    }
    val swappedVas = swapPairs.map(_._1).toSet
    val swappedVss = swapPairs.map(_._2).toSet

    for ((i, j) <- swapPairs) {
      val swap = Module(new SchedulerInjectionSwap(taskWidth))
      swap.io.upstreamIn <> io.connVAS(i).data.qOutTask
      swap.io.downstreamIn <> io.connVSS(j).data.qOutTask
      swap.io.upstreamOut <> stealNet.io.connSS(layout.vasNodes(i)).data.qOutTask
      swap.io.downstreamOut <> stealNet.io.connSS(layout.vssNodes(j)).data.qOutTask
      // Everything except qOutTask stays straight for both endpoints.
      stealNet.io.connSS(layout.vasNodes(i)).data.availableTask <> io.connVAS(i).data.availableTask
      stealNet.io.connSS(layout.vasNodes(i)).ctrl <> io.connVAS(i).ctrl
      stealNet.io.connSS(layout.vssNodes(j)).data.availableTask <> io.connVSS(j).data.availableTask
      stealNet.io.connSS(layout.vssNodes(j)).ctrl <> io.connVSS(j).ctrl
    }

    for (i <- 0 until vssCount if !swappedVss.contains(i)) {
      stealNet.io.connSS(layout.vssNodes(i)) <> io.connVSS(i)
    }
    for (i <- 0 until vasCount if !swappedVas.contains(i)) {
      stealNet.io.connSS(layout.vasNodes(i)) <> io.connVAS(i)
    }
  }

  for (i <- 0 until vssCount) {
    stealNet.io.ntwDataUnitOccupancyVSS(i) <> io.ntwDataUnitOccupancyVSS(i)
  }

  if (!successiveNetworkConfig) {
    val placement = interleavedLayout.get.nodeKinds.map { node =>
      if (node.startsWith("vss")) "vss"
      else if (node.startsWith("vas")) "vas"
      else "pe"
    }.toArray
    val schedulerSlots = placement.zipWithIndex.collect { case ("vss", i) => i }
    if (vssCount <= peCount) {
      for (i <- schedulerSlots.indices) {
        val start = schedulerSlots(i)
        val end = schedulerSlots((i + 1) % schedulerSlots.length)
        val slotsBetween =
          if (start < end) placement.slice(start + 1, end)
          else
            placement
              .slice(start + 1, placement.length) ++ placement.slice(0, end)
        require(
          slotsBetween.contains("pe"),
          s"SchedulerLocalNetwork placement has no PE between scheduler servers: ${placement.mkString(" -> ")}"
        )
      }
    }

    // Every injector group must terminate at a PE before another injector of
    // the same class. This is the liveness invariant that prevents one server
    // class from forming an injection-only island on the shift ring.

    val spawnerSlots = placement.zipWithIndex.collect { case ("vas", i) => i }
    if (spawnerSlots.length > 1 && vasCount <= peCount) {
      for (i <- spawnerSlots.indices) {
        val start = spawnerSlots(i)
        val end = spawnerSlots((i + 1) % spawnerSlots.length)
        val slotsBetween =
          if (start < end) placement.slice(start + 1, end)
          else
            placement
              .slice(start + 1, placement.length) ++ placement.slice(0, end)
        require(
          slotsBetween.contains("pe"),
          s"SchedulerLocalNetwork placement has no PE between spawner servers: ${placement.mkString(" -> ")}"
        )
      }
    }
  }
}
