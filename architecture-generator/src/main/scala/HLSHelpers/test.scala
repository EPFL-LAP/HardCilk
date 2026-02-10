object VerilogSignalWidthExtractor {
  def lineContainsOutput(signalName: String, moduleContent: String): Boolean = {
    val pattern = s"""(?i)(output).*\\b$signalName\\b""".r
    pattern.findFirstIn(moduleContent).isDefined
  }
  def extractSignalWidth(signalName: String, moduleContent: String): Int = {
    // 1. Find the signal line
    val signalRegex = s"""\\[([^\\]]+)\\]\\s+$signalName\\b""".r
    val maybeMatch = signalRegex.findFirstMatchIn(moduleContent)

    maybeMatch match {
      case Some(matched) =>
        val range =
          matched.group(1).replaceAll("\\s", "") // e.g., "C_WIDTH-1:0"
        val parts = range.split(":")
        if (parts.length != 2) return 1

        val msbRaw = parts(0)
        val lsbRaw = parts(1)

        val msb = getValueFromParamOrLiteral(msbRaw, moduleContent)
        val lsb = getValueFromParamOrLiteral(lsbRaw, moduleContent)

        (msb - lsb + 1).abs

      case None => 0 // Signal not found
    }
  }

  def extractParameterValue(paramName: String, moduleContent: String): Int = {
    val paramRegex = s"""(?i)\\bparameter\\s+$paramName\\s*=\\s*(\\d+)\\s*;""".r
    paramRegex.findFirstMatchIn(moduleContent) match {
      case Some(m) => m.group(1).toInt
      case None    => 0
    }
  }

  def getValueFromParamOrLiteral(expr: String, moduleContent: String): Int = {
    // If it's just a number, return it
    if (expr.matches("\\d+")) return expr.toInt

    // If it's something like "PARAM-1", extract "PARAM" and subtract 1
    val paramWithOffset = """(\w+)-(\d+)""".r
    val paramAlone = """(\w+)""".r

    expr match {
      case paramWithOffset(param, offset) =>
        findParameterValue(param, moduleContent) - offset.toInt
      case paramAlone(param) =>
        findParameterValue(param, moduleContent)
      case _ =>
        println(s"Unsupported format in MSB/LSB: '$expr'")
        0
    }
  }

  def findParameterValue(param: String, content: String): Int = {
    val paramRegex = s"""parameter\\s+$param\\s*=\\s*(\\d+)\\s*;""".r
    paramRegex.findFirstMatchIn(content) match {
      case Some(m) => m.group(1).toInt
      case None =>
        println(s"Parameter '$param' not found.")
        0
    }
  }

  // Example usage
  def main(args: Array[String]): Unit = {
    val moduleContent =
      """
        |     parameter   C_M_AXI_GMEM_ID_WIDTH = 3;
        |output wire [C_M_AXI_GMEM_ID_WIDTH - 1:0] m_axi_gmem_ARID;
        |input  taskIn_TDATA;
        |input   wire  [255:0] taskIn_TDATA;
        |parameter    C_M_AXI_GMEM_CACHE_VALUE = 3;
        |
        |
        |
        |""".stripMargin

    val width = extractSignalWidth("m_axi_gmem_ARID", moduleContent)
    //val width2 = extractSignalWidth("taskIn_TDATA", moduleContent)
    val dd = findParameterValue("C_M_AXI_GMEM_CACHE_VALUE", moduleContent)

    // In the lines with output or input, find the width of the TDATA and create an Axis_VitisInterface with the name as  {name}_TDATA
    // Only the lines with output or input are considered
    val tdataLines = moduleContent
      .split("\n")
      .filter { line =>
        line.contains("TDATA") && line.matches(
          """.*\[\s*[\w+\-*/]+\s*:\s*\d+\s*\].*"""
        )
      }

    tdataLines.foreach(u => println(u))

    println(tdataLines)

    val tdataRegex = raw"""^\s*(input|output)\s+(?:\w+\s+)?\[\d+:\d+\]\s+(\w+)_TDATA\s*;""".r

    val tdataInterfaces = tdataLines.collect {
      case line @ tdataRegex(direction, name) =>
        val tdataWidth = extractSignalWidth(s"${name}_TDATA", moduleContent) 
       
    }

    // val width2 = extractSignalWidth("example", moduleContent)
    println(s"Width: $width") // Should print 3
   // println(s"Width: $width2")
    println(s"Width: $dd")
  }
}
