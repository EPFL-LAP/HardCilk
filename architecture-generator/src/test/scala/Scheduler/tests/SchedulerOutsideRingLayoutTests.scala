package Scheduler.tests

import org.scalatest.flatspec.AnyFlatSpec

import Scheduler.SchedulerOutsideRingLayout

class SchedulerOutsideRingLayoutTests extends AnyFlatSpec {
  behavior of "SchedulerOutsideRingLayout"

  it should "place striped continuation lanes in adjacent pairs before their spawners" in {
    val layout = SchedulerOutsideRingLayout.build(
      sourceCount = 9,
      spawnerCount = 4,
      groupedSourceStart = 0,
      groupedSourceCount = 8,
      groupSize = 2
    )

    assert(layout.sourceIndices == Vector(0, 1, 2, 3, 4, 5, 6, 7, 8))
    assert(layout.spawnerIndices == Vector(1, 3, 5, 7))
  }

  it should "preserve the historical ungrouped placement" in {
    val layout = SchedulerOutsideRingLayout.build(
      sourceCount = 9,
      spawnerCount = 4
    )

    assert(layout.spawnerIndices == Vector(0, 2, 4, 6))
    assert(layout.sourceIndices == Vector(0, 2, 4, 6, 1, 3, 5, 7, 8))
  }

  it should "reject a striped lane count that does not match the spawners" in {
    assertThrows[IllegalArgumentException] {
      SchedulerOutsideRingLayout.build(
        sourceCount = 9,
        spawnerCount = 8,
        groupedSourceStart = 0,
        groupedSourceCount = 8,
        groupSize = 2
      )
    }
  }
}
