package SoftwareUtil.aie

import Descriptors._
import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** Run with: sbt 'Test / runMain SoftwareUtil.aie.KernelPortGenerationTest'. */
object KernelPortGenerationTest extends App {
  val root = Files.createTempDirectory("kernel-port-generation")

  def task(name: String, hls: Boolean, argData: Boolean = false): TaskDescriptor = {
    if (hls) {
      val argPort = if (argData) "output [63:0] argDataOut_TDATA;" else ""
      Files.writeString(root.resolve(s"$name.v"),
        s"module $name ();\ninput [63:0] taskIn_TDATA;\n$argPort\nendmodule\n")
    }
    TaskDescriptor(name = name, peHDLPath = if (hls) root.toString else "",
      isRoot = true, isCont = false, dynamicMemAlloc = false,
      numProcessingElements = 1, widthTask = 64, hasAXI = false,
      generateArgOutWriteBuffer = argData, argumentSizeList = List(64),
      sidesConfigs = List(SideConfig("scheduler", 1), SideConfig("allocator", 1),
        SideConfig("argumentNotifier", 1)))
  }

  def check(name: String, tasks: List[TaskDescriptor], expectedAxi: Int,
      spawnNext: Map[String, List[String]] = Map.empty,
      sendArgument: Map[String, List[String]] = Map.empty): Unit = {
    val descriptor = FullSysGenDescriptor(name, 64, 32, tasks,
      spawnList = tasks.map(t => t.name -> List(t.name)).toMap,
      spawnNextList = spawnNext, sendArgumentList = sendArgument,
      maximumAXIPorts = 32,
      subPEList = tasks.map(t => s"${t.name}_writer" -> SubPEDescriptor(t.name,
        rwRequest = Some(RWRequestDescriptor("write", "single", 64)))).toMap)
    val out = Files.createDirectory(root.resolve(name))
    KernelXmlTemplate.generateHelperKernelXmls(descriptor, out.toString)
    ConnectivityTemplate.generateConnectivityCfg(descriptor, out.toString)
    val xml = Files.readString(out.resolve(s"scripts/xml/$name.xml"))
    val cfg = Files.readString(out.resolve("connectivity.cfg"))
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
      .newDocumentBuilder().parse(out.resolve(s"scripts/xml/$name.xml").toFile)
    val ports = doc.getElementsByTagName("port")
    val axiPorts = (0 until ports.getLength).map(i => ports.item(i).getAttributes.getNamedItem("name").getNodeValue)
      .filter(_.startsWith("m_axi_")).toSet
    val expected = (0 until expectedAxi).map(i => f"m_axi_$i%02d").toSet
    assert(axiPorts == expected, s"$name XML AXI ports: $axiPorts != $expected")
    val spPorts = cfg.linesIterator.filter(_.startsWith("sp=")).map(_.split("[.:]")(1)).toSet
    assert(spPorts == expected, s"$name connectivity AXI ports: $spPorts != $expected")
    tasks.foreach { t =>
      val external = t.peHDLPath.isEmpty
      for (port <- Seq("taskIn", "taskOut")) {
        val bind = s"BindTo_PE_${t.name}_0_$port"
        assert(xml.contains(bind) == external, s"$name XML $bind")
        assert(cfg.contains(bind) == external, s"$name connectivity $bind")
      }
      val rw = s"${t.name}_writer_0_sourceTask"
      assert(xml.contains(rw) == external, s"$name XML $rw")
      assert(cfg.contains(rw) == external, s"$name connectivity $rw")
      if (t.generateArgOutWriteBuffer) {
        val bind = s"BindTo_PE_${t.name}_0_argDataOut"
        assert(xml.contains(bind) == external)
        assert(cfg.contains(bind) == external)
      }
    }
  }

  try {
    // Only the sender has a spawnNext AXI master; the receiver has an allocator.
    check("hlsContinuation", List(task("sender", true), task("receiver", true)), 4,
      spawnNext = Map("sender" -> List("receiver")))
    // Notifications do not create an HLS memory master without argDataOut.
    check("hlsNotification", List(task("notify", true), task("target", true)), 4,
      sendArgument = Map("notify" -> List("target")))
    check("hlsData", List(task("data", true, argData = true)), 2)
    // AIE read/write helpers share one master per replica, plus its write buffer.
    check("aie", List(task("external", false, argData = true)), 3)
    check("mixed", List(task("internal", true, argData = true), task("external", false)), 4)
    println("Kernel XML/connectivity port regression checks passed")
  } finally {
    val paths = Files.walk(root)
    try paths.iterator().asScala.toSeq.sortBy(_.getNameCount).reverse.foreach(Files.delete(_))
    finally paths.close()
  }
}
