package Descriptors.tests

import org.scalatest.flatspec.AnyFlatSpec
import io.circe.Json
import io.circe.syntax._

import Descriptors._
import Descriptors.DescriptorJSON._

class TaskDescriptorValidationTests extends AnyFlatSpec {
  behavior of "TaskDescriptor scheduler width validation"

  private def task(schedulerPortWidth: Int): TaskDescriptor =
    TaskDescriptor(
      name = "task",
      isRoot = true,
      isCont = false,
      dynamicMemAlloc = false,
      numProcessingElements = 1,
      widthTask = 64,
      sidesConfigs = List(
        SideConfig(
          sideType = "scheduler",
          numVirtualServers = 1,
          capacityVirtualQueue = 1,
          capacityPhysicalQueue = 1,
          portWidth = schedulerPortWidth
        )
      )
    )

  it should "accept a scheduler portWidth equal to widthTask" in {
    task(schedulerPortWidth = 64).validate()
  }

  it should "reject a scheduler portWidth different from widthTask" in {
    val error = intercept[IllegalArgumentException] {
      task(schedulerPortWidth = 32).validate()
    }

    assert(error.getMessage.contains("scheduler portWidth=32"))
    assert(error.getMessage.contains("widthTask=64"))
    assert(error.getMessage.contains("unsupported"))
  }

  behavior of "TaskDescriptor RAMA validation"

  it should "accept RAMA on a PE main AXI master" in {
    task(schedulerPortWidth = 64)
      .copy(generateRAMA = Some(true), peHDLPath = ".")
      .validate()
  }

  it should "reject RAMA when the task has no main AXI master" in {
    val error = intercept[IllegalArgumentException] {
      task(schedulerPortWidth = 64)
        .copy(generateRAMA = Some(true), hasAXI = false, peHDLPath = ".")
        .validate()
    }
    assert(error.getMessage.contains("hasAXI is false"))
  }

  it should "reject RAMA on consolidated PE ports" in {
    val error = intercept[IllegalArgumentException] {
      task(schedulerPortWidth = 64)
        .copy(generateRAMA = Some(true), totalAxiPorts = 1, peHDLPath = ".")
        .validate()
    }
    assert(error.getMessage.contains("generateRAMA and totalAxiPorts are mutually exclusive"))
  }

  it should "accept an explicit RAMA opt-out on a consolidated port" in {
    task(schedulerPortWidth = 64)
      .copy(generateRAMA = Some(false), totalAxiPorts = 1, peHDLPath = ".")
      .validate()
  }

  it should "preserve omitted, false, and true generateRAMA JSON values" in {
    val encoded = task(schedulerPortWidth = 64).asJson
    val omitted = encoded.mapObject(_.remove("generateRAMA"))
    val explicitFalse = omitted.mapObject(_.add("generateRAMA", Json.fromBoolean(false)))
    val explicitTrue = omitted.mapObject(_.add("generateRAMA", Json.fromBoolean(true)))

    assert(omitted.as[TaskDescriptor].toOption.get.generateRAMA.isEmpty)
    assert(explicitFalse.as[TaskDescriptor].toOption.get.generateRAMA.contains(false))
    assert(explicitTrue.as[TaskDescriptor].toOption.get.generateRAMA.contains(true))
  }

  behavior of "NewArgumentNotifier lane striping validation"

  private def newArgumentSide(
      lanes: Int,
      stripingFactor: Int
  ): SideConfig =
    SideConfig(
      sideType = "argumentNotifier",
      numVirtualServers = 1,
      capacityVirtualQueue = 8,
      capacityPhysicalQueue = 8,
      useNewArgumentNotifier = true,
      newContinuationLanesPerServer = lanes,
      newContinuationLaneStripingFactor = stripingFactor,
      directUpdateLanesPerServer = lanes,
      portWidth = 64
    )

  it should "accept disjoint lane groups" in {
    newArgumentSide(lanes = 4, stripingFactor = 2).validate()
  }

  it should "reject a striping factor that does not divide the physical lanes" in {
    val error = intercept[IllegalArgumentException] {
      newArgumentSide(lanes = 4, stripingFactor = 3).validate()
    }

    assert(error.getMessage.contains("newContinuationLanesPerServer"))
    assert(error.getMessage.contains("newContinuationLaneStripingFactor"))
  }
}
