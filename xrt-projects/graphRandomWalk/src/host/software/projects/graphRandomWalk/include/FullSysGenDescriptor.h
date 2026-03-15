
#ifndef FULLSYS_DESCRIPTOR_H
#define FULLSYS_DESCRIPTOR_H

#include <cassert>
#include <map>
#include <stdint.h>
#include <string>
#include <vector>

class MemSystemDescriptor {
public:
  std::vector<int> schedulerServersBaseAddresses;
  std::vector<int> allocationServersBaseAddresses;
  std::vector<int> memoryAllocatorServersBaseAddresses;
};

class SideConfig {
public:
  std::string sideType;
  int numVirtualServers;
  int capacityVirtualQueue;
  int capacityPhysicalQueue;
  int portWidth;
  int virtualEntrtyWidth;
};

class TaskDescriptor {
public:
  std::string name;
  std::string peVersion;
  bool isRoot;
  bool isCont;
  bool dynamicMemAlloc;
  bool hasAXI;
  int numProcessingElements;
  int widthTask;
  std::vector<SideConfig> sidesConfigs;
  MemSystemDescriptor mgmtBaseAddresses;
  std::map<uint64_t, std::vector<std::pair<uint64_t, int>>>
      mapServerAddressToClosureBaseAddress;
  std::map<uint64_t, std::vector<std::pair<uint64_t, int>>>
      mapServerAddressToMallocBaseAddress;

  int getNumServers(const std::string &sideType) const {
    assert(sideType == "scheduler" || sideType == "allocator" ||
           sideType == "argumentNotifier" || sideType == "memoryAllocator");
    for (const auto &config : sidesConfigs) {
      if (config.sideType == sideType) {
        return config.numVirtualServers;
      }
    }
    return 0;
  }

  uint64_t getCapacityVirtualQueue(const std::string &sideType) const {
    assert(sideType == "scheduler" || sideType == "allocator" ||
           sideType == "argumentNotifier" || sideType == "memoryAllocator");
    for (const auto &config : sidesConfigs) {
      if (config.sideType == sideType) {
        return config.capacityVirtualQueue;
      }
    }
    return 0;
  }
  uint64_t getVirtualEntryWidth(const std::string &sideType) const {
    assert(sideType == "scheduler" || sideType == "allocator" ||
           sideType == "argumentNotifier" || sideType == "memoryAllocator");
    for (const auto &config : sidesConfigs) {
      if (config.sideType == sideType) {
        return config.virtualEntrtyWidth;
      }
    }
    return 0;
  }
};

class FullSysGenDescriptor {
public:
  std::string name = "graphRandomWalk";
  int widthAddress = 64;
  int widthContCounter = 32;
  std::vector<TaskDescriptor> taskDescriptors = {

      {"walk_gen",
       "1.0",
       true,
       true,
       false,
       false,
       1,
       512,
       {{"scheduler", 1, 4096, 64, 256, 0ull},
        {"argumentNotifier", 1, 128, 32, 64, 0ull}},
       {{0x10}, {}, {}}},

      {"walker",
       "1.0",
       false,
       false,
       false,
       true,
       1,
       512,
       {{"scheduler", 1, 4096*1024, 64, 256, 0ull}},
       {{0x50, 0x90}, {}, {}}}

  };
  int getNumberAxiMasters() const { return 4; }
  int getNumberPEsAXISlaves() const {
    int numSlaves = 0;
    for (const auto &task : taskDescriptors) {
      numSlaves += task.hasAXI * task.numProcessingElements;
    }
    return numSlaves;
  }

  uint64_t getMfpgaBaseAddress() const { return (0xD0 + 0x40 * 4); }
};

#endif // FULLSYS_DESCRIPTOR_H
