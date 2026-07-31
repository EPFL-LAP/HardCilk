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
         |    std::vector<SideConfig> sidesConfigs;
         |    MemSystemDescriptor mgmtBaseAddresses;
         |    int tag;
         |    // True when this task is served by the streaming DataFlowScheduler, which has no
         |    // scheduler servers. Its schedulerServersBaseAddresses then holds only spawner
         |    // servers, which have the same register layout and are managed the same way.
         |    bool usesDataFlowScheduler;
         |    int spawnServersCount;
         |    std::map<uint64_t, std::vector<std::pair<uint64_t, int>>> mapServerAddressToClosureBaseAddress;
         |    std::map<uint64_t, std::vector<std::pair<uint64_t, int>>> mapServerAddressToMallocBaseAddress;
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
         |    uint64_t getCapacityVirtualQueue(const std::string& sideType) const {
         |        assert(sideType == "scheduler" || sideType == "allocator" || 
         |               sideType == "argumentNotifier" || sideType == "memoryAllocator");
         |        for (const auto& config : sidesConfigs) {
         |            if (config.sideType == sideType) {
         |                return config.capacityVirtualQueue;
         |            }
         |        }
         |        return 0;
         |    }
         |    uint64_t getVirtualEntryWidth(const std::string& sideType) const {
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
         |    {${generateSideConfig(td.sidesConfigs)}},
         |    ${generateMemSystemDescriptor(td.mgmtBaseAddresses)},
         |    ${td.tag},
         |    ${descriptor.usesDataFlowScheduler(td)},
         |    ${descriptor.spawnerCount(td)}
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
       |    // A subset of schedulerServersBaseAddresses: the entries that are spawner servers.
       |    // They are initialised and grown exactly like a scheduler server, this vector only
       |    // lets the host tell the two apart.
       |    std::vector<int> spawnerServersBaseAddresses;
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
       |    uint64_t getMfpgaBaseAddress() const
       |    {
       |        return 0x${descriptor.getMfpgaBaseAddress().toHexString.toUpperCase};
       |    }
       |
       |
       |    
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
        s"""{"${sc.sideType}", ${sc.numVirtualServers}, ${sc.capacityVirtualQueue}, ${sc.capacityPhysicalQueue}, ${sc.portWidth}, ${sc.virtualEntrtyWidth}ull}"""
      }
      .mkString(", ")
  }

  private def generateMemSystemDescriptor(memDesc: MemSystemDescriptor): String = {
  def hex(xs: Seq[Int]) =
    xs.map(addr => f"0x${addr.toHexString.toUpperCase}").mkString(", ")

  // Same order as the members of MemSystemDescriptor above.
  s"""{
     |    {${hex(memDesc.schedulerServersBaseAddresses)}},
     |    {${hex(memDesc.allocationServersBaseAddresses)}},
     |    {${hex(memDesc.memoryAllocatorServersBaseAddresses)}},
     |    {${hex(memDesc.spawnerServersBaseAddresses)}}
     |}""".stripMargin
  }
}
