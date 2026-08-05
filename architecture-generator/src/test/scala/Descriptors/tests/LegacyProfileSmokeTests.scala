package Descriptors.tests

import org.scalatest.flatspec.AnyFlatSpec

import Descriptors._
import Descriptors.DescriptorJSON._
import Util.GeneratorProfile

class LegacyProfileSmokeTests extends AnyFlatSpec {
  behavior of "the countDecoupled legacy profile"

  private def descriptor: FullSysGenDescriptor =
    parseJsonFile[FullSysGenDescriptor](
      "taskDescriptors/mfpga/countDecoupled.json"
    ).resolved(GeneratorProfile.resolve("legacy", Some("no-cache")).toOption.get)

  it should "restore the reference server counts and local queue depths" in {
    val desc = descriptor
    desc.validate()
    val adder = desc.taskDescriptors.find(_.name == "taskAdder_cont0").get
    val reader = desc.taskDescriptors.find(_.name == "memReader").get
    val root = desc.taskDescriptors.find(_.name == "taskInitiator_reentry0").get

    assert(desc.resolvedArchitecture == "legacy")
    assert(desc.taskDescriptors.forall(_.spawnServersCount == 1))
    assert(desc.taskDescriptors.forall(_.spawnerQueueDepth == 16))
    assert(adder.getNumServers("argumentNotifier") == 1)
    assert(reader.getNumServers("scheduler") == 1)
    assert(reader.getCapacityPhysicalQueue("scheduler") == 64)
    assert(root.getCapacityPhysicalQueue("scheduler") == 64)
    assert(!adder.usesNewArgumentNotifier)
  }

  it should "assign HBM-backed spawners into the typed management map" in {
    val desc = descriptor
    val tasks = desc.taskDescriptors.map(task => task.name -> task).toMap

    assert(tasks("taskAdder_cont0").mgmtBaseAddresses.spawnerServersBaseAddresses == Seq(80))
    assert(tasks("memReader").mgmtBaseAddresses.spawnerServersBaseAddresses == Seq(272))
    assert(tasks("taskInitiator_reentry0").mgmtBaseAddresses.spawnerServersBaseAddresses == Seq(400))
    assert(tasks("taskInitiator_reentry0").mgmtBaseAddresses.schedulerServersBaseAddresses == Seq(336))
  }
}
