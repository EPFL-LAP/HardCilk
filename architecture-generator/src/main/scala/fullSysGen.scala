package fullSysGen

import chisel3._
import chisel3.util._

import stealSide._
import continuationSide._
import argRouting._
import commonInterfaces._
import play.api.libs.json._
import scala.collection.mutable.ArrayBuffer

import chext.axi4
import chext.axis4

import axi4.Ops._
import axi4.lite.components.RegisterBlock

import axis4.Casts._

import hardcilk.util.readyValidMem

// task width includes a n bit counter at its start

/** *********************************************************************
  *
  * System Generator
  *
  * For each task we need the following data:
  *
  *   - task name
  *   - task type
  *   - task argument bit size
  *   - task PE count
  *   - count of tasks that spawns this task (spawnedCount)
  *   - count of tasks that sends argument to this task (receieveArgCount)
  *   - count of tasks that spawns next this task (spawnedNextCount)
  */
class fullSysGen(val taskDescriptionJson: String)
    extends Module
    with chisel3.interface.Extra {
  override def desiredName: String = (taskDescription \ "fullSysGenName")
    .getOrElse(new JsString("fullSysGen"))
    .as[String]

  val taskDescription = Json.parse(taskDescriptionJson)

  private def initialize() = {
    val conn_array = new ArrayBuffer[String]()
    def conn_writeln(s: String) = {
      conn_array.addOne(s)
    }

    def log(s: String) = {
      println(s)
    }

    log(s"Creating the full system with name: ${desiredName}")

    val systemAddressWidth =
      (taskDescription \ "systemAddressWidth").as[Int]
    val counterWidth =
      (taskDescription \ "continuationCounterWidth").as[Int]

    val taskList = (taskDescription \ "taskList").as[JsArray]

    // Create a dictionary for each task based on its name
    val taskDict = scala.collection.mutable.Map[String, JsValue]()

    val spawnList = (taskDescription \ "spawnList").as[JsObject]
    val spawnNextList = (taskDescription \ "spawnNextList").as[JsObject]
    val sendArgumentList =
      (taskDescription \ "sendArgumentList").as[JsObject]

    log("Task List:")
    taskList.value.foreach { task =>
      val taskName = (task \ "name").as[String]
      val taskType = (task \ "type").as[String]
      val taskArgBitSize = (task \ "argBitSize").as[Int]
      val taskPECount = (task \ "peCount").as[Int]
      val stealQueueSize = (task \ "stealQueueSize").as[Int]
      val continuationQueueSize = (task \ "continuationQueueSize").as[Int]
      var spawnedCount = 0
      var receieveArgCount = 0
      var spawnedNextCount = 0
      var spawnsItself = false

      // if a task is spawned by another task in the spawn list, add the number of PEs
      // of the spawning task to the number of PEs of the spawned task

      // Create a connection for the task with the taskOut of the system
      for (i <- 0 until taskPECount) {
        conn_writeln(
          f"${taskName}_PE_${i}/taskIn <--> ${taskName}_stealSide_taskOut_${i}; <${taskArgBitSize}>"
        )
      }

      taskList.value.foreach { task2 =>
        val task2Name = (task2 \ "name").as[String]

        // checks the spawn list
        if (spawnList.keys.exists(_ == task2Name)) {
          // check if taskName is the value of task2Name in spawnList

          // each key in spawnList has a list value for the names of tasks it spawns
          // if the list contains taskName, then task2 spawns taskName

          (spawnList \ task2Name).as[JsArray].value.foreach { task2Spawned =>
            if (task2Spawned.as[String] == taskName && taskName == task2Name) {
              spawnsItself = true
              // For each PE of type task that spawns Itself, add a connection to the output file
              for (i <- 0 until taskPECount) {
                conn_writeln(
                  f"${taskName}_PE_${i}/taskOut <--> ${taskName}_stealSide_taskIn_${i}; <${taskArgBitSize}>"
                )
              }
            }
          }
          var j = 0
          (spawnList \ task2Name).as[JsArray].value.foreach { task2Spawned =>
            if (task2Spawned.as[String] == taskName && task != task2) {
              val task2PECount = (task2 \ "peCount").as[Int]
              spawnedCount = spawnedCount + task2PECount
              // For each PE of type task2 that spawns taskName, add a connection to the output file
              for (i <- 0 until task2PECount) {
                conn_writeln(
                  f"${task2Name}_PE_${i}/taskOut_${taskName} <--> ${taskName}_stealSide_taskInGlobal_${i + j}; <${taskArgBitSize}>"
                )
              }
              j = j + task2PECount
            }
          }
        }

        // check the send argument list
        if (sendArgumentList.keys.exists(_ == task2Name)) {
          // check if taskName is the value of task2Name in sendArgumentList
          (sendArgumentList \ task2Name).as[JsArray].value.foreach {
            task2Send =>
              if (task2Send.as[String] == taskName) {

                // for each PE of type task2 that sends argument to taskName, add a connection to the output file
                for (i <- 0 until (task2 \ "peCount").as[Int]) {
                  conn_writeln(
                    f"${task2Name}_PE_${i}/contOut_${taskName} <--> ${taskName}_syncSide_addrIn_${i + receieveArgCount}; <${systemAddressWidth}>"
                  )
                }

                val task2PECount = (task2 \ "peCount").as[Int]
                receieveArgCount = receieveArgCount + task2PECount
              }
          }
        }

        // checks the spawn next list
        if (spawnNextList.keys.exists(_ == task2Name)) {
          // check if taskName is the value of task2Name in spawnNextList

          (spawnNextList \ task2Name).as[JsArray].value.foreach {
            task2SpawnNext =>
              if (task2SpawnNext.as[String] == taskName) {

                val task2PECount = (task2 \ "peCount").as[Int]

                // for each PE of type task2 that spawns_next task2, add a connection to the output file
                for (i <- 0 until task2PECount) {
                  conn_writeln(
                    f"${task2Name}_PE_${i}/addrIn_${taskName} <--> ${taskName}_continuationAllocationSide_contOut_${i + spawnedNextCount}; <${systemAddressWidth}>"
                  )
                }

                spawnedNextCount = spawnedNextCount + task2PECount
              }
          }
        }
      }

      // add the task to the dictionary
      taskDict += (taskName -> Json.obj(
        "name" -> taskName,
        "type" -> taskType,
        "argBitSize" -> taskArgBitSize,
        "peCount" -> taskPECount,
        "spawnedCount" -> spawnedCount,
        "receieveArgCount" -> receieveArgCount,
        "spawnedNextCount" -> spawnedNextCount,
        "spawnsItself" -> spawnsItself,
        "stealQueueSize" -> stealQueueSize,
        "continuationQueueSize" -> continuationQueueSize,
        "continuationServersNumber" -> (task \ "continuationServersNumber").as[Int],
        "argRouteServersNumber" -> (task \ "argRouteServersNumber").as[Int],
        "argRouteServersQueueSize" -> (task \ "argRouteServersQueueSize")
          .as[Int],
        "virtualStealServersNumber" -> (task \ "virtualStealServersNumber")
          .as[Int]
      ))
    }

    // create a modifiable seq to carry the stealSide, continuationAllocationSide, and syncSide

    val stealSideSeq = scala.collection.mutable.ArrayBuffer[stealSide]()
    val continuationAllocationSideSeq =
      scala.collection.mutable.ArrayBuffer[continuationAllocationSide]()
    val syncSideSeq = scala.collection.mutable.ArrayBuffer[syncSide]()

    conn_writeln("--------------------------")
    conn_writeln("Connection to memory and Micro Controller:")
    conn_writeln("--------------------------")

    // loop over each key and element in the dictionary
    taskDict.foreach { case (key, value) =>
      log(s"Task Name: $key")
      log(s"Task Type: ${(value \ "type").as[String]}")
      log(s"Task Argument Bit Size: ${(value \ "argBitSize").as[Int]}")
      log(s"Task PE Count: ${(value \ "peCount").as[Int]}")
      log(s"Task Spawned Count: ${(value \ "spawnedCount").as[Int]}")
      log(
        s"Task Receieve Argument Count: ${(value \ "receieveArgCount").as[Int]}"
      )
      log(
        s"Task Spawned Next Count: ${(value \ "spawnedNextCount").as[Int]}"
      )
      log(s"Task Spawns Itself: ${(value \ "spawnsItself").as[Boolean]}")

      // for the stealSide write to the output file connection of axiMgmt to controller and axiMem to memory
      for (i <- 0 until (value \ "virtualStealServersNumber").as[Int]) {
        conn_writeln(
          f"${key}_stealSide_axi_mgmt_vss_${i} <--> uController; <${systemAddressWidth}>"
        )
        conn_writeln(
          f"${key}_stealSide_vss_axi_full_${i} <--> memory; <${(value \ "argBitSize").as[Int]}>"
        )
      }

      // create stealSide, continuationAllocationSide, and syncSide for each task
      stealSideSeq += Module(
        new stealSide(
          addrWidth = (taskDescription \ "systemAddressWidth").as[Int],
          taskWidth = (value \ "argBitSize").as[Int],
          queueDepth = (value \ "stealQueueSize").as[Int],
          peCount = (value \ "peCount").as[Int],
          spawnsItself = (value \ "spawnsItself").as[Boolean],
          peCountGlobalTaskIn = (value \ "spawnedCount").as[Int],
          argRouteServersNumber =
            if ((value \ "type").as[String] == "continuation")
              (value \ "argRouteServersNumber").as[Int]
            else 0,
          virtualAddressServersNumber =
            (value \ "virtualStealServersNumber").as[Int]
        )
      )
      val stealSideExport =
        IO(chiselTypeOf(stealSideSeq.last.io)).suggestName(f"${key}_stealSide")
      stealSideExport <> stealSideSeq.last.io

      // val internalConn = IO(chiselTypeOf(stealSideSeq.last.connStealNtw)).suggestName("fib_internal")
      // internalConn <> alloc.connStealNtw

      if ((value \ "type").as[String] == "continuation") {

        // for the continuation allocation side axi mgmt and axi full connections
        for(i <- 0 until (value \ "continuationServersNumber").as[Int]) {
          conn_writeln(
          f"${key}_continuationAllocationSide_axi_mgmt_vcas_${i} <--> uController; <${systemAddressWidth}>"
          )
          conn_writeln(
            f"${key}_continuationAllocationSide_vcas_axi_full_${i} <--> memory; <${systemAddressWidth}>"
          )
        }

        // for each argRoute in the sync side axi full connection
        for (i <- 0 until (value \ "argRouteServersNumber").as[Int]*2) {
          conn_writeln(
            f"${key}_syncSide_axi_full_argRoute_${i} <--> memory; <${counterWidth}>"
          )
        }

        continuationAllocationSideSeq += Module(
          new continuationAllocationSide(
            addrWidth = (taskDescription \ "systemAddressWidth").as[Int],
            peCount = (value \ "spawnedNextCount").as[Int],
            vcasCount = (value \ "continuationServersNumber").as[Int],
            queueDepth = (value \ "continuationQueueSize").as[Int]
          )
        )

        val continuationAllocationSideExport = IO(
          chiselTypeOf(continuationAllocationSideSeq.last.io)
        ).suggestName(f"${key}_continuationAllocationSide")
        continuationAllocationSideExport <> continuationAllocationSideSeq.last.io

        syncSideSeq += Module(
          new syncSide(
            addrWidth = (taskDescription \ "systemAddressWidth").as[Int],
            taskWidth = (value \ "argBitSize").as[Int],
            queueDepth = (value \ "argRouteServersQueueSize").as[Int],
            peCount = (value \ "receieveArgCount").as[Int],
            argRouteServersNumber = (value \ "argRouteServersNumber").as[Int],
            contCounterWidth =
              (taskDescription \ "continuationCounterWidth").as[Int]
          )
        )

        val syncSideExport =
          IO(chiselTypeOf(syncSideSeq.last.io)).suggestName(f"${key}_syncSide")
        syncSideExport <> syncSideSeq.last.io

        syncSideSeq.last.connStealNtw <> stealSideSeq.last.connSyncSide
      }
    }

    conn_array.mkString("\n")
  }

  val connectionsTxt = initialize()

  val getExtra: strenc.Typed = strenc.PrimitiveTyped(connectionsTxt)
}

object fullSysGen extends strenc.UserTypeCompanion[fullSysGen] {
  import strenc.EncodersDecoders._

  def encode(t: fullSysGen, os: strenc.ArgumentOutputStream): Unit = {
    os.push(t.taskDescriptionJson)
  }

  def decode(is: strenc.ArgumentInputStream): fullSysGen = {
    new fullSysGen(is.pop[String])
  }
}

object CommandLineEmitter {
  def main(args: Array[String]) = {
    def readFile(path: String): String = {
      import java.nio.charset.StandardCharsets
      import java.nio.file.{Files, Path}
      Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    def writeFile(path: String, data: String): Unit = {
      import java.nio.charset.StandardCharsets
      import java.nio.file.{Files, Path}
      Files.writeString(Path.of(path), data, StandardCharsets.UTF_8)
    }

    def basename(path: String): String = {
      path.split("/").last.split("\\.").head
    }

    if (args.length < 2) {
      println(
        "Please enter the path to the task description file as the first argument and the path to the output directory as the second argument"
      )
    } else {
      val pathInputJsonFile = args(0)
      val jsonName = basename(pathInputJsonFile)
      val pathOutputDir = args(1)

      val temp = (new chisel3.stage.ChiselStage).emitVerilog(
        {
          val module = new fullSysGen(readFile(pathInputJsonFile))
          val pathOutputTxt = f"${pathOutputDir}/${jsonName}.txt"
          writeFile(pathOutputTxt, module.connectionsTxt)
          module
        },
        Array(
          "--emission-options=disableMemRandomization,disableRegisterRandomization",
          f"--target-dir=${pathOutputDir}"
        )
      )
    }
  }
}

/** @brief
  *   Server emitter starts a Chisel3 hardware generation server.
  */
object ServerEmitter extends App {
  strenc.Typed.registerUserType[fullSysGen]("fullSysGen.fullSysGen")

  private val generationType = sys.env.getOrElse("HARDCILK_GENERATION_TYPE", "Simulation")
  private val portNumber = sys.env.getOrElse("HARDCILK_GENERATION_PORT", "9215").toInt

  println(f"Generation type = ${generationType} (env: HARDCILK_GENERATION_TYPE)")
  println(f"Port number = ${portNumber} (env: HARDCILK_GENERATION_PORT)")
  
  val server = new chisel3.interface.net.Server(portNumber)
  
  server.run()
}
