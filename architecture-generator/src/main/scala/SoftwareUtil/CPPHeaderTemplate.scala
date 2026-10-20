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
       |${boardSection(descriptor)}
       |#endif // FULLSYS_DESCRIPTOR_H
       """.stripMargin

    val writer = new PrintWriter(f"$headerFileDirectory/FullSysGenDescriptor.h")
    writer.write(headerContent)
    writer.close()
  }

  /**
    * Board constants (V80 contract 2). Free functions rather than members so a
    * `Memory` backend can use them without a descriptor instance; `static
    * inline` so the header stays includable from several translation units and
    * valid C++11 for sccom.
    *
    * - HBM base/size: where the design's masters and the host DMA see HBM.
    * - Register base: absolute address of the HardCilk management slave as the
    *   host DMA sees it. 0 on U55C (XDMA BAR-relative registers); on V80 the
    *   management slave sits at 0x202_0000_0000 (CPM PF1 BAR2 window), and the
    *   soft-reset GPIO at register base + 0x8000, as on U55C.
    */
  private def boardSection(descriptor: FullSysGenDescriptor): String = {
    val board = descriptor.board
    val (macroName, regBase) = board match {
      case Util.Board.V80  => ("HC_BOARD_V80", BigInt("20200000000", 16))
      case Util.Board.U55C => ("HC_BOARD_U55C", BigInt(0))
    }
    def hex(v: BigInt) = s"0x${v.toString(16).toUpperCase}ULL"
    s"""// --- target board (${board.fpgaModel}) ---------------------------------------
       |#ifndef $macroName
       |#define $macroName 1
       |#endif
       |#define HC_BOARD_NAME "${board.cliName}"
       |
       |/** First HBM byte as the design's masters and the host DMA address it. */
       |static inline uint64_t getBoardHbmBaseAddress() { return ${hex(board.hbmBaseAddress)}; }
       |/** HBM bytes reachable from every memory port. */
       |static inline uint64_t getBoardHbmSizeBytes() { return ${hex(board.hbmSizeBytes)}; }
       |/** Absolute host-DMA address of management register offset 0 (reset GPIO at +0x8000). */
       |static inline uint64_t getBoardRegBaseAddress() { return ${hex(regBase)}; }
       |""".stripMargin + v80RegRange(descriptor)
  }

  /**
    * V80 only (the U55C header is unchanged): the decoded management range.
    * HardCilk answers only offsets [0, HC_NUM_CONFIG_PORTS * 64); any other
    * register access is never answered and, on the V80, used to wedge the PCIe
    * path. The host backend (memIO_qdma.h) and hc_v80_probe refuse it.
    */
  private def v80RegRange(descriptor: FullSysGenDescriptor): String =
    if (descriptor.board != Util.Board.V80) ""
    else {
      val n = descriptor.getNumConfigPorts()
      s"""/** Management register blocks HardCilk decodes (64 bytes each, from offset 0). */
         |#define HC_NUM_CONFIG_PORTS ${n}
         |/** Bytes of decoded management registers: offsets [0, this) are valid. */
         |#define HC_REG_DECODED_BYTES ${n * 64}ULL
         |""".stripMargin + v80Striping
    }

  /**
    * V80 HBM striping (HC_HBM_STRIPING at generation time, see
    * TclResources.TclV80Common.stripeVerilog); nothing when striping is off.
    * memIO_qdma.h applies the permutation to host copies, or rebases them to
    * the PL striping path's alias window when the bitstream has one.
    */
  private def v80Striping: String = {
    val mode = TclResources.TclV80Common.hbmStripeMode
    if (mode == 0) ""
    else
      s"""/** HBM 4 KB striping in the bitstream: 1 = stripe4k, 2 = stripe4k_xor (hbm_stripe.h). */
         |#define HC_HBM_STRIPE_MODE ${mode}
         |""".stripMargin +
        TclResources.TclV80Common.hbmHostAliasBase.map(b =>
          s"""/** Host HBM copies go to this alias window (PL hc_axi_stripe), unpermuted. */
             |#define HC_HBM_HOST_ALIAS_BASE 0x${b.toString(16).toUpperCase}ULL
             |""".stripMargin).getOrElse("")
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
