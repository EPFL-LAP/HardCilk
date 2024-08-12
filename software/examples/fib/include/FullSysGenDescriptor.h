
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
    std::string name = "fibonacci";
    int widthAddress = 64;
    int widthContCounter = 32;
    std::vector<TaskDescriptor> taskDescriptors = {
        
{
    "fib",
    "1.0",
    true,
    false,
    false,
    50,
    128,
    {{"scheduler", 1, 128, 32, 128, 0}},
    {
    {0},
    {},
    {}
}
}
         ,

{
    "sum",
    "1.0",
    false,
    true,
    false,
    32,
    256,
    {{"scheduler", 1, 128, 32, 256, 0}, {"allocator", 1, 100000, 32, 64, 0}, {"argumentNotifier", 2, 128, 32, 64, 0}},
    {
    {64},
    {128},
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
            numMasters += (task.getNumServers("scheduler") > 0) ? 1 : 0;
            numMasters += (task.getNumServers("allocator") > 0) ? 1 : 0;
            numMasters += (task.getNumServers("argumentNotifier") > 0) ? 1 : 0;
            numMasters += (task.getNumServers("memoryAllocator") > 0) ? 1 : 0;

            // For each PE of each task there is a master
            numMasters += task.numProcessingElements;
        }
        return numMasters;
    }
    int getNumberPEsAXISlaves() const
    {
        int numSlaves = 0;
        for (const auto &task : taskDescriptors)
        {
            numSlaves += task.numProcessingElements;
        }
        return numSlaves;
    }

};

#endif // FULLSYS_DESCRIPTOR_H
       