package SoftwareUtil
import Descriptors._
import java.io.PrintWriter

object CppHeaderTemplate {
  def generateCppHeader(descriptor: FullSysGenDescriptor, headerFileDirectory: String, reduceAxi: Int): Unit = {
    // Generate TaskDescriptor class
    val taskDescriptorClass =
      s"""
         |class TaskDescriptor {
         |public:
         |    std::string name;
         |    std::string peVersion;
         |    bool isRoot;
         |    bool isCont;
         |    bool dynamicMemAlloc;
         |    bool hasAXI;
         |    int numProcessingElements;
         |    int widthTask;
         |    int widthMalloc;
         |    std::vector<SideConfig> sidesConfigs;
         |    MemSystemDescriptor mgmtBaseAddresses;
         |    std::map<uint64_t,std::vector<std::pair<uint64_t, int>>> mapServerAddressToClosureBaseAddress;
         |
         |    int getNumServers(const std::string& sideType) const {
         |        assert(sideType == "scheduler" || sideType == "allocator" || 
         |               sideType == "argumentNotifier" || sideType == "memoryAllocator");
         |        for (const auto& config : sidesConfigs) {
         |            if (config.sideType == sideType) {
         |                return config.numVirtualServers;
         |            }
         |        }
         |        return 0;
         |    }
         |
         |    int getCapacityVirtualQueue(const std::string& sideType) const {
         |        assert(sideType == "scheduler" || sideType == "allocator" || 
         |               sideType == "argumentNotifier" || sideType == "memoryAllocator");
         |        for (const auto& config : sidesConfigs) {
         |            if (config.sideType == sideType) {
         |                return config.capacityVirtualQueue;
         |            }
         |        }
         |        return 0;
         |    }
         |    int getVirtualEntryWidth(const std::string& sideType) const {
         |        assert(sideType == "scheduler" || sideType == "allocator" ||
         |               sideType == "argumentNotifier" || sideType == "memoryAllocator");
         |        for (const auto& config : sidesConfigs) {
         |            if (config.sideType == sideType) {
         |                return config.virtualEntrtyWidth;
         |            }
         |        }
         |        return 0;
         |    }
         |};
         """.stripMargin

    // Generate initialization of taskDescriptors vector
    val taskDescriptorsInit = descriptor.taskDescriptors
      .map { td =>
        s"""
         |{
         |    "${td.name}",
         |    "${td.peVersion}",
         |    ${td.isRoot},
         |    ${td.isCont},
         |    ${td.dynamicMemAlloc},
         |    ${td.hasAXI || descriptor.spawnNextList.get(td.name).isDefined || descriptor.sendArgumentList.get(td.name).isDefined},
         |    ${td.numProcessingElements},
         |    ${td.widthTask},
         |    ${td.widthMalloc},
         |    {${generateSideConfig(td.sidesConfigs)}},
         |    ${generateMemSystemDescriptor(td.mgmtBaseAddresses)}
         |}
         """.stripMargin
      }
      .mkString(",\n")

    val headerContent =
      s"""
       |#ifndef FULLSYS_DESCRIPTOR_H
       |#define FULLSYS_DESCRIPTOR_H
       |
       |#include <string>
       |#include <vector>
       |#include <cassert>
       |#include <stdint.h>
       |#include <map>
       |
       |class MemSystemDescriptor {
       |public:
       |    std::vector<int> schedulerServersBaseAddresses;
       |    std::vector<int> allocationServersBaseAddresses;
       |    std::vector<int> memoryAllocatorServersBaseAddresses;
       |};
       |
       |class SideConfig {
       |public:
       |    std::string sideType;
       |    int numVirtualServers;
       |    int capacityVirtualQueue;
       |    int capacityPhysicalQueue;
       |    int portWidth;
       |    int virtualEntrtyWidth;
       |};
       |
       |${taskDescriptorClass}
       |
       |class FullSysGenDescriptor {
       |public:
       |    std::string name = "${descriptor.name}";
       |    int widthAddress = ${descriptor.widthAddress};
       |    int widthContCounter = ${descriptor.widthContCounter};
       |    std::vector<TaskDescriptor> taskDescriptors = {
       |        ${taskDescriptorsInit}
       |    };
       |    int getNumberAxiMasters() const
       |    {
       |        return ${reduceAxi};
       |    }
       |    int getNumberPEsAXISlaves() const
       |    {
       |        int numSlaves = 0;
       |        for (const auto &task : taskDescriptors)
       |        {
       |            numSlaves += task.hasAXI * task.numProcessingElements;
       |        }
       |        return numSlaves;
       |    }
       |
       |};
       |
       |#endif // FULLSYS_DESCRIPTOR_H
       """.stripMargin

    val writer = new PrintWriter(f"$headerFileDirectory/FullSysGenDescriptor.h")
    writer.write(headerContent)
    writer.close()
  }

  private def generateSideConfig(sidesConfigs: List[SideConfig]): String = {
    sidesConfigs
      .map { sc =>
        s"""{"${sc.sideType}", ${sc.numVirtualServers}, ${sc.capacityVirtualQueue}, ${sc.capacityPhysicalQueue}, ${sc.portWidth}, ${sc.virtualEntrtyWidth}}"""
      }
      .mkString(", ")
  }

  private def generateMemSystemDescriptor(memDesc: MemSystemDescriptor): String = {
    s"""{
       |    {${memDesc.schedulerServersBaseAddresses.mkString(", ")}},
       |    {${memDesc.allocationServersBaseAddresses.mkString(", ")}},
       |    {${memDesc.memoryAllocatorServersBaseAddresses.mkString(", ")}}
       |}""".stripMargin
  }
}
