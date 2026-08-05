package Descriptors.tests

import org.scalatest.flatspec.AnyFlatSpec
import Descriptors._
import Util.{ArchitectureMode, ArgumentServerMode, GeneratorProfile}

class BuildVariantResolutionTests extends AnyFlatSpec {
  private val updatedCached =
    GeneratorProfile(ArchitectureMode.Updated, ArgumentServerMode.Cached)
  private val updatedNoCache =
    GeneratorProfile(ArchitectureMode.Updated, ArgumentServerMode.NoCache)
  private val legacy =
    GeneratorProfile(ArchitectureMode.Legacy, ArgumentServerMode.NoCache)

  private val schedulerSide = SideConfig(
    sideType = "scheduler",
    numVirtualServers = 0,
    capacityVirtualQueue = 4096,
    capacityPhysicalQueue = 10,
    portWidth = 256,
    legacyOverrides = Some(LegacySideOverrides(
      numVirtualServers = Some(1),
      capacityPhysicalQueue = Some(64)
    ))
  )

  private val argumentSide = SideConfig(
    sideType = "argumentNotifier",
    numVirtualServers = 2,
    capacityVirtualQueue = 128,
    capacityPhysicalQueue = 32,
    portWidth = 64,
    slowArgumentHandlerCount = 1,
    cacheEvictionSaverCount = 1,
    newContinuationLanesPerServer = 4,
    directUpdateLanesPerServer = 4,
    legacyOverrides = Some(LegacySideOverrides(numVirtualServers = Some(1)))
  )

  private val task = TaskDescriptor(
    name = "task",
    peHDLPath = "/rtl/default",
    isRoot = false,
    isCont = false,
    dynamicMemAlloc = false,
    numProcessingElements = 8,
    widthTask = 256,
    sidesConfigs = List(schedulerSide, argumentSide),
    spawnServersCount = 8,
    spawnerQueueDepth = 8,
    peHDLVariants = Map(
      "cached" -> "/rtl/cached",
      "no-cache" -> "/rtl/no-cache"
    ),
    legacyOverrides = Some(LegacyTaskOverrides(
      spawnServersCount = Some(1),
      spawnerQueueDepth = Some(16)
    ))
  )

  behavior of "build variant descriptor resolution"

  it should "select cached RTL and leave ordinary updated values unchanged" in {
    val resolved = task.resolved(updatedCached)
    assert(resolved.peHDLPath == "/rtl/cached")
    assert(resolved.spawnerQueueDepth == 8)
    assert(resolved.getNumServers("scheduler") == 0)
    assert(resolved.usesNewArgumentNotifier)
  }

  it should "select no-cache RTL without applying legacy overrides" in {
    val resolved = task.resolved(updatedNoCache)
    assert(resolved.peHDLPath == "/rtl/no-cache")
    assert(resolved.spawnerQueueDepth == 8)
    assert(resolved.getNumServers("scheduler") == 0)
    assert(!resolved.usesNewArgumentNotifier)
  }

  it should "apply only the typed legacy overrides" in {
    val resolved = task.resolved(legacy)
    assert(resolved.peHDLPath == "/rtl/no-cache")
    assert(resolved.numProcessingElements == 8)
    assert(resolved.spawnServersCount == 1)
    assert(resolved.spawnerQueueDepth == 16)
    assert(resolved.getNumServers("scheduler") == 1)
    assert(resolved.getCapacityPhysicalQueue("scheduler") == 64)
    assert(resolved.getNumServers("argumentNotifier") == 1)
    assert(!resolved.usesNewArgumentNotifier)
  }

  it should "leave the parsed source descriptor and its address state untouched" in {
    val resolved = task.resolved(legacy)
    resolved.mgmtBaseAddresses.schedulerServersBaseAddresses = Seq(0x40)
    assert(task.spawnServersCount == 8)
    assert(task.sidesConfigs.head.numVirtualServers == 0)
    assert(task.mgmtBaseAddresses.schedulerServersBaseAddresses.isEmpty)
  }

  it should "require both selected paths when a task declares mode-specific RTL" in {
    val incomplete = task.copy(peHDLVariants = Map("cached" -> "/rtl/cached"))
    val error = intercept[IllegalArgumentException] {
      incomplete.resolved(updatedNoCache)
    }
    assert(error.getMessage.contains("no 'no-cache' path"))
  }

  it should "reject multiple argument payload widths until they are supported" in {
    val error = intercept[IllegalArgumentException] {
      task.copy(argumentSizeList = List(32, 64)).validate()
    }
    assert(error.getMessage.contains(
      "multiple argumentSizeList entries are not supported yet"
    ))
  }

  it should "validate cached-only sizing only for cached mode" in {
    val invalidCachedSizing = argumentSide.copy(
      slowArgumentHandlerCount = 0,
      cacheEvictionSaverCount = 0,
      newContinuationLanesPerServer = 0,
      directUpdateLanesPerServer = 0
    )
    invalidCachedSizing.resolved(updatedNoCache).validate()
    invalidCachedSizing.resolved(legacy).validate()
    intercept[IllegalArgumentException] {
      invalidCachedSizing.resolved(updatedCached).validate()
    }
  }
}
