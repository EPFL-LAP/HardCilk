package Descriptors.tests

import org.scalatest.flatspec.AnyFlatSpec

import Descriptors._
import Descriptors.DescriptorJSON._

class WatcherConfigTests extends AnyFlatSpec {
  behavior of "WatcherConfig generic status slots"

  private def countDescriptor: FullSysGenDescriptor =
    parseJsonFile[FullSysGenDescriptor](
      "taskDescriptors/mfpga/countDecoupled.json"
    )

  private def triangleDescriptor: FullSysGenDescriptor =
    parseJsonFile[FullSysGenDescriptor](
      "taskDescriptors/mfpga/triangleCountDecoupled.json"
    )

  it should "parse and validate explicit mixed-source slots" in {
    val desc = countDescriptor
    desc.validate()
    val slots = desc.watcherConfig.get.statusSlots
    val fields = slots.flatMap(_.fields)
    val schedulerServerCount =
      desc.taskDescriptors.map(_.getNumServers("scheduler")).sum
    assert(desc.taskDescriptors.forall(_.numProcessingElements == 8))
    assert(slots.size == 21)
    assert(fields.count(_.target.kind == "pe") == 30)
    assert(fields.count(_.target.kind == "schedulerServer") == schedulerServerCount)
    assert(fields.count(_.target.kind == "slowUpdateHandler") == 1)
    assert(fields.count(_.target.kind == "evictionSaver") == 1)
    assert(fields.count(_.target.kind == "argumentServer") == 8)
    assert(fields.count(_.encoding == "boolean1") == schedulerServerCount)
    assert(fields.count(_.encoding == "readyValid2") == 40)
  }

  it should "reuse the generic watcher for another benchmark" in {
    val desc = triangleDescriptor
    desc.validate()
    val wc = desc.watcherConfig.get
    assert(wc.hdlPath == "../hls-kernel-output/watcher/watcher")
    assert(wc.statusSlots.size == 4)
    assert(wc.statusSlots.flatMap(_.fields).forall(_.target.kind == "pe"))
  }

  it should "validate the explicit compact argument-update packet shape" in {
    val desc = countDescriptor
    val source = desc.taskDescriptors.find(_.name == "memReader").get
    assert(source.argumentSizeList == List(512))
    assert(source.argumentOffsetWidth.contains(0))
    desc.validate()

    val invalidSource = source.copy(argumentOffsetWidth = Some(1))
    val invalid = desc.copy(taskDescriptors = desc.taskDescriptors.map { task =>
      if (task.name == source.name) invalidSource else task
    })
    val error = intercept[IllegalArgumentException](invalid.validate())
    assert(error.getMessage.contains("expected 0"))

    val missing = desc.copy(taskDescriptors = desc.taskDescriptors.map { task =>
      if (task.name == source.name) source.copy(argumentOffsetWidth = None)
      else task
    })
    val missingError = intercept[IllegalArgumentException](missing.validate())
    assert(missingError.getMessage.contains("explicitly specify"))
  }

  it should "reject more than twenty-two physical slots" in {
    val desc = countDescriptor
    val wc = desc.watcherConfig.get
    val tooMany = wc.copy(statusSlots = List.fill(23)(wc.statusSlots.head))
    val invalid = desc.copy(watcherConfig = Some(tooMany))
    val error = intercept[IllegalArgumentException](invalid.validate())
    assert(error.getMessage.contains("at most 22"))
  }

  it should "reject a PE index outside the instantiated task" in {
    val desc = countDescriptor
    val wc = desc.watcherConfig.get
    val slot = wc.statusSlots.head
    val field = slot.fields.head
    val bad = slot.copy(fields = field.copy(target = field.target.copy(index = 99)) :: slot.fields.tail)
    val invalid = desc.copy(
      watcherConfig = Some(wc.copy(statusSlots = bad :: wc.statusSlots.tail))
    )
    val error = intercept[IllegalArgumentException](invalid.validate())
    assert(error.getMessage.contains("outside taskAdder_cont0"))
  }

  it should "select each slow-path half independently by signal and index" in {
    val desc = countDescriptor
    val task = desc.taskDescriptors.find(_.name == "taskAdder_cont0").get
    val scaledTask = task.copy(sidesConfigs = task.sidesConfigs.map {
      case side if side.sideType == "argumentNotifier" =>
        side.copy(slowArgumentHandlerCount = 2, cacheEvictionSaverCount = 2)
      case side => side
    })
    val wc = desc.watcherConfig.get
    val slot = wc.statusSlots.find(_.label == "slowUpdateEviction0").get
    val remappedSlot = slot.copy(fields = List(
      WatcherStatusField("readyValid2", WatcherStatusTarget(
        kind = "slowUpdateHandler", taskName = "taskAdder_cont0", index = 1, port = "input")),
      WatcherStatusField("readyValid2", WatcherStatusTarget(
        kind = "evictionSaver", taskName = "taskAdder_cont0", index = 0, port = "input"))
    ))
    val remapped = desc.copy(
      taskDescriptors = desc.taskDescriptors.map(t => if (t.name == task.name) scaledTask else t),
      watcherConfig = Some(wc.copy(
        statusSlots = wc.statusSlots.map(s => if (s eq slot) remappedSlot else s)
      ))
    )
    remapped.validate()
  }

  it should "reject fields whose encoded widths exceed four bits" in {
    val desc = countDescriptor
    val wc = desc.watcherConfig.get
    val slot = wc.statusSlots.head
    val invalidSlot = slot.copy(fields = slot.fields :+ slot.fields.head)
    val invalid = desc.copy(watcherConfig = Some(wc.copy(
      statusSlots = invalidSlot :: wc.statusSlots.tail
    )))
    val error = intercept[IllegalArgumentException](invalid.validate())
    assert(error.getMessage.contains("packs 6 bits"))
  }
}
