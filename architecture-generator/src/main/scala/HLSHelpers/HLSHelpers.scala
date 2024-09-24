package HLSHelpers

import chisel3._

import Descriptors._

import hdlinfo.InterfaceRole
import scala.util.matching.Regex

trait VitisInterface {
  def name: String
  def role: hdlinfo.InterfaceRole

  def chiselType: Data
}

case class Aximm_VitisInterface(
    val name: String,
    val role: hdlinfo.InterfaceRole,
    val config: chext.amba.axi4.Config
) extends VitisInterface {
  assert(role == InterfaceRole.master || role == InterfaceRole.slave)

  def chiselType: Data =
    if (role == InterfaceRole.master)
      chext.amba.axi4.Master(config).suggestName(name)
    else
      chext.amba.axi4.Slave(config).suggestName(name)
}

case class Axis_VitisInterface(
    val name: String,
    val role: hdlinfo.InterfaceRole,
    val config: chext.amba.axi4s.Config
) extends VitisInterface {
  assert(role == InterfaceRole.master || role == InterfaceRole.slave)

  def chiselType: Data =
    if (role == InterfaceRole.master)
      chext.amba.axi4s.Master(config).suggestName(name)
    else
      chext.amba.axi4s.Slave(config).suggestName(name)
}

case class VitisModuleConfig(
    val desiredName: String = "vitisModule",
    val interfaces: Seq[VitisInterface] = Seq.empty,
    val is_ap_start: Boolean = false,
    val is_ap_done: Boolean = false,
    val is_ap_idle: Boolean = false,
    val is_ap_ready: Boolean = false
)

class VitisModule(cfg: VitisModuleConfig) extends BlackBox {
  override def desiredName: String = cfg.desiredName

  import scala.collection.immutable.SeqMap

  val io = IO(new chisel3.Record {
    val elements: SeqMap[String, Data] =
      SeqMap.from(cfg.interfaces.map { interface =>
        {
          interface.name -> interface.chiselType
        }
      }) ++
        Seq(
          if (cfg.is_ap_start) Some("ap_start" -> Input(Bool())) else None,
          if (cfg.is_ap_done) Some("ap_done" -> Output(Bool())) else None,
          if (cfg.is_ap_idle) Some("ap_idle" -> Output(Bool())) else None,
          if (cfg.is_ap_ready) Some("ap_ready" -> Output(Bool())) else None,
          Some("ap_clk" -> Input(Clock())),
          Some("ap_rst_n" -> Input(Bool()))
        ).flatten
  })

  def getPort(name: String) = io.elements.getOrElse(
    name,
    throw new RuntimeException(f"IO port not found: ${name}")
  )
}

/** An object with a function that when given a task descriptor, returns an array of VitisModules. The task descriptor has the
  * path to the hdl directory and the name of the module. One function passes the path to another function that parses the
  * <module>.v from the hdl directory and returns a vitisModuleConfig.
  */

object VitisModuleFactory {
  def apply(taskDescriptor: TaskDescriptor): Seq[VitisModule] = {
    val hdlPath = taskDescriptor.peHDLPath
    val moduleName = taskDescriptor.name
    val blackBoxCount = taskDescriptor.numProcessingElements
    val vitisModuleConfig = parseVitisModule(hdlPath, moduleName)
    Seq.fill(blackBoxCount)(Module(new VitisModule(vitisModuleConfig)))

  }

  def parseVitisModule(hdlPath: String, moduleName: String): VitisModuleConfig = {
    // read the <module>.v file from the hdlPath
    val moduleContent = scala.io.Source.fromFile(s"${hdlPath}/${moduleName}.v").mkString

    // search for parameters in the moduleContent with M_AXI_GMEM and create an Aximm_VitisInterface
    var idWidth = 0
    var addrWidth = 0
    var dataWidth = 0
    var awuserWidth = 0
    var aruserWidth = 0
    var wuserWidth = 0
    var ruserWidth = 0
    var buserWidth = 0
    var userValue = 0
    var protValue = 0
    var cacheValue = 0
    var wstrbWidth = 0
    var axiWstrbWidth = 0

    // Find parameters that has M_AXI_GMEM in the name and assign the value to the corresponding variable
    val M_AXI_GMEM_PARAMS =
      moduleContent.split("\n").filter(line => line.contains("parameter") && line.contains("C_M_AXI_GMEM"))
    M_AXI_GMEM_PARAMS.foreach { param =>
      val paramSplit = param.split("=")
      // remove the spaces and the word parameter from the paramName
      val paramName = paramSplit(0).trim.drop(10).replaceAll("\\s+", "")
      // remove the semi-colon at the end of the line
      val paramValue = paramSplit(1).replaceAll("\\s+", "").replaceAll(";", "").trim

      // if the string paramValue is a math function evaluate it then to int, otherwise to int
      val paramValueInt = if (paramValue.contains("/")) {
        val split = paramValue.split("/")

        (split(0).drop(1).toInt / split(1).dropRight(1).toInt).toInt
      } else {
        paramValue.toInt
      }
      paramName match {
        case "C_M_AXI_GMEM_ID_WIDTH"     => idWidth = paramValueInt
        case "C_M_AXI_GMEM_ADDR_WIDTH"   => addrWidth = paramValueInt
        case "C_M_AXI_GMEM_DATA_WIDTH"   => dataWidth = paramValueInt
        case "C_M_AXI_GMEM_AWUSER_WIDTH" => awuserWidth = paramValueInt
        case "C_M_AXI_GMEM_ARUSER_WIDTH" => aruserWidth = paramValueInt
        case "C_M_AXI_GMEM_WUSER_WIDTH"  => wuserWidth = paramValueInt
        case "C_M_AXI_GMEM_RUSER_WIDTH"  => ruserWidth = paramValueInt
        case "C_M_AXI_GMEM_BUSER_WIDTH"  => buserWidth = paramValueInt
        case "C_M_AXI_GMEM_USER_VALUE"   => userValue = paramValueInt
        case "C_M_AXI_GMEM_PROT_VALUE"   => protValue = paramValueInt
        case "C_M_AXI_GMEM_CACHE_VALUE"  => cacheValue = paramValueInt
        case "C_M_AXI_GMEM_WSTRB_WIDTH"  => wstrbWidth = paramValueInt
        case "C_M_AXI_WSTRB_WIDTH"       => axiWstrbWidth = paramValueInt
      }
    }

    // Create an Aximm_VitisInterface
    val M_AXI_GMEM_INTERFACE = if (dataWidth > 0) {
      Some(
        Aximm_VitisInterface(
          "m_axi_gmem",
          InterfaceRole.master,
          chext.amba.axi4.Config(
            wId = idWidth,
            wAddr = addrWidth,
            wData = dataWidth,
            wUserAW = awuserWidth,
            wUserAR = aruserWidth,
            wUserW = wuserWidth,
            wUserR = ruserWidth,
            wUserB = buserWidth
          )
        )
      )
    } else {
      None
    }

    val S_AXI_CONTROL_PARAMS =
      moduleContent.split("\n").filter(line => line.contains("parameter") && line.contains("C_S_AXI_CONTROL"))
    S_AXI_CONTROL_PARAMS.foreach { param =>
      val paramSplit = param.split("=")
      val paramName = paramSplit(0).trim.drop(10).replaceAll("\\s+", "")
      val paramValue = paramSplit(1).replaceAll("\\s+", "").replaceAll(";", "").trim

      // if the string paramValue is a math function evaluate it then to int, otherwise to int
      val paramValueInt = if (paramValue.contains("/")) {
        val split = paramValue.split("/")

        (split(0).drop(1).toInt / split(1).dropRight(1).toInt).toInt
      } else {
        paramValue.toInt
      }
      paramName match {
        case "C_S_AXI_CONTROL_DATA_WIDTH"  => dataWidth = paramValueInt
        case "C_S_AXI_CONTROL_ADDR_WIDTH"  => addrWidth = paramValueInt
        case "C_S_AXI_CONTROL_WSTRB_WIDTH" => wstrbWidth = paramValueInt
      }
    }

    // Create an S_AXI_CONTROL interface if dataWidth > 0 otherwise None
    val aximmInterface_s_axi_control = if (dataWidth > 0) {
      Some(
        Aximm_VitisInterface(
          "s_axi_control",
          InterfaceRole.slave,
          chext.amba.axi4.Config(
            wData = dataWidth,
            wAddr = addrWidth,
            lite = true,
            hasProt = false
          )
        )
      )
    } else {
      None
    }

    // In the lines with output or input, find the width of the TDATA and create an Axis_VitisInterface with the name as  {name}_TDATA
    // Only the lines with output or input are considered
    val tdataLines =
      moduleContent.split("\n").filter(line => line.contains("TDATA") && (line.contains("output") || line.contains("input")))
    val tdataInterfaces = tdataLines.map { line =>
      val tdataWidth = line.split("\\[")(1).split(":")(0).toInt + 1
      val tdataName = line.split(" ")(3).split("_")(0)

      Axis_VitisInterface(
        f"${tdataName}",
        if (line.contains("output")) InterfaceRole.master else InterfaceRole.slave,
        chext.amba.axi4s.Config(
          wData = tdataWidth,
          onlyRV = true
        )
      )
    }

    // Search for the regex module <moduleName> (.*); and check ap_start, ap_done, ap_idle, ap_ready
    var is_ap_start = false
    var is_ap_done = false
    var is_ap_idle = false
    var is_ap_ready = false

    val modulePattern: Regex = """module (\w+)\s*\(([\w\s,]+)\);""".r
    modulePattern.findAllMatchIn(moduleContent).foreach { moduleMatch =>
      val name = moduleMatch.group(1)
      val content = moduleMatch.group(2)
      if (name == moduleName) {
        is_ap_start = content.contains("ap_start")
        is_ap_done = content.contains("ap_done")
        is_ap_idle = content.contains("ap_idle")
        is_ap_ready = content.contains("ap_ready")
      }
    }

    val config_seq = (Seq(M_AXI_GMEM_INTERFACE, aximmInterface_s_axi_control).flatten ++ tdataInterfaces).asInstanceOf[Seq[VitisInterface]]

    // Create the config with the interfaces
    VitisModuleConfig(
      moduleName,
      config_seq,
      is_ap_start,
      is_ap_done,
      is_ap_idle,
      is_ap_ready
    )
    
  }
}
