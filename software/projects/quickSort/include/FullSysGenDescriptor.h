
#ifndef FULLSYS_DESCRIPTOR_H
#define FULLSYS_DESCRIPTOR_H

#include <string>
#include <vector>
#include <cassert>
#include <stdint.h>
#include <map>

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
    std::map<uint64_t,std::vector<std::pair<uint64_t, int>>> mapServerAddressToClosureBaseAddress;

    int getNumServers(const std::string& sideType) const {
        assert(sideType == "scheduler" || sideType == "allocator" || 
               sideType == "argumentNotifier" || sideType == "memoryAllocator");
        for (const auto& config : sidesConfigs) {
            if (config.sideType == sideType) {
                return config.numVirtualServers;
            }
        }
        return 0;
    }

    int getCapacityVirtualQueue(const std::string& sideType) const {
        assert(sideType == "scheduler" || sideType == "allocator" || 
               sideType == "argumentNotifier" || sideType == "memoryAllocator");
        for (const auto& config : sidesConfigs) {
            if (config.sideType == sideType) {
                return config.capacityVirtualQueue;
            }
        }
        return 0;
    }
    int getVirtualEntryWidth(const std::string& sideType) const {
        assert(sideType == "scheduler" || sideType == "allocator" ||
               sideType == "argumentNotifier" || sideType == "memoryAllocator");
        for (const auto& config : sidesConfigs) {
            if (config.sideType == sideType) {
                return config.virtualEntrtyWidth;
            }
        }
        return 0;
    }
};
         

class FullSysGenDescriptor {
public:
    std::string name = "quickSort";
    int widthAddress = 64;
    int widthContCounter = 32;
    std::vector<TaskDescriptor> taskDescriptors = {
        
{
    "qsort",
    "1.0",
    true,
    false,
    false,
    true,
    64,
    256,
    {{"scheduler", 1, 8192, 64, 256, 0}},
    {
    {0},
    {},
    {}
}
}
         ,

{
    "sync",
    "1.0",
    false,
    true,
    false,
    false,
    16,
    128,
    {{"scheduler", 1, 8192, 64, 128, 0}, {"allocator", 4, 8192, 32, 64, 0}, {"argumentNotifier", 16, 8192, 32, 64, 0}},
    {
    {64},
    {128, 192, 256, 320},
    {}
}
}
         
    };
    int getNumberAxiMasters() const
    {
        int numMasters = 0;
        for (const auto &task : taskDescriptors)
        {
            // For each task, each side has a single master if the side exists
            numMasters += false ? (task.getNumServers("scheduler") > 0) : task.getNumServers("scheduler");
            numMasters += false ? (task.getNumServers("allocator") > 0): task.getNumServers("allocator");
            numMasters += 2*(false ? (task.getNumServers("argumentNotifier") > 0): task.getNumServers("argumentNotifier"));
            numMasters += false ? (task.getNumServers("memoryAllocator") > 0): task.getNumServers("memoryAllocator");

            // For each PE of each task there is a master
            numMasters += task.hasAXI * task.numProcessingElements;
        }
        return numMasters;
    }
    int getNumberPEsAXISlaves() const
    {
        int numSlaves = 0;
        for (const auto &task : taskDescriptors)
        {
            numSlaves += task.hasAXI * task.numProcessingElements;
        }
        return numSlaves;
    }

};

#endif // FULLSYS_DESCRIPTOR_H
       