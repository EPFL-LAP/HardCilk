package softwareResources

import java.io.PrintWriter
import descriptors._

object CreateCPPHeaders extends App {

    val headersFileDirectoy = "output/CPPHeaders"
    val pathInputJsonFile = "taskDescriptors/qsort_descriptor.json"
    val headerContent = CppHeaderTemplate.generateCppHeader(parseJsonFile[fullSysGenDescriptor](pathInputJsonFile))
    val fileName = "output/CPPHeaders/FullSysGenDescriptor.h"
    val writer = new PrintWriter(fileName)
    writer.write(headerContent)
    writer.close()
}