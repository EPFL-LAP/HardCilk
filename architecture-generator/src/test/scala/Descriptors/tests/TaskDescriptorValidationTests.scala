package Descriptors.tests

import org.scalatest.flatspec.AnyFlatSpec

import Descriptors._

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
}
