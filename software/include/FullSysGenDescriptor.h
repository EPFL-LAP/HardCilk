#ifndef FULLSYS_DESCRIPTOR_H
#define FULLSYS_DESCRIPTOR_H

#include <string>
#include <vector>
#include <cassert>
#include <stdint.h>
#include <map>

class MemSystemDescriptor
{
public:
    std::vector<int> schedulerServersBaseAddresses;
    std::vector<int> allocationServersBaseAddresses;
    std::vector<int> memoryAllocatorServersBaseAddresses;
};

class SideConfig
{
public:
    std::string sideType;
    int numVirtualServers;
    int capacityVirtualQueue;
    int capacityPhysicalQueue;
    int portWidth;
    int virtualEntrtyWidth;
};

class TaskDescriptor
{
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
    std::map<uint64_t, std::vector<std::pair<uint64_t, int>>> mapServerAddressToClosureBaseAddress;

    int getNumServers(const std::string &sideType) const
    {
        assert(sideType == "scheduler" || sideType == "allocator" ||
               sideType == "argumentNotifier" || sideType == "memoryAllocator");
        for (const auto &config : sidesConfigs)
        {
            if (config.sideType == sideType)
            {
                return config.numVirtualServers;
            }
        }
        return 0;
    }

    int getCapacityVirtualQueue(const std::string &sideType) const
    {
        assert(sideType == "scheduler" || sideType == "allocator" ||
               sideType == "argumentNotifier" || sideType == "memoryAllocator");
        for (const auto &config : sidesConfigs)
        {
            if (config.sideType == sideType)
            {
                return config.capacityVirtualQueue;
            }
        }
        return 0;
    }
    int getVirtualEntryWidth(const std::string &sideType) const
    {
        assert(sideType == "scheduler" || sideType == "allocator" ||
               sideType == "argumentNotifier" || sideType == "memoryAllocator");
        for (const auto &config : sidesConfigs)
        {
            if (config.sideType == sideType)
            {
                return config.virtualEntrtyWidth;
            }
        }
        return 0;
    }
};

class FullSysGenDescriptor
{
public:
    std::string name = "quickSort";
    int widthAddress = 64;
    int widthContCounter = 32;
    std::vector<TaskDescriptor> taskDescriptors = {

        {"qsort",
         "1.0",
         true,
         false,
         false,
         32,
         256,
         {{"scheduler", 1, 2048, 32, 32, 0}},
         {{0},
          {},
          {}}},

        {"qsortSync",
         "1.0",
         false,
         true,
         false,
         16,
         128,
         {{"scheduler", 1, 2048, 32, 32, 0}, {"allocator", 1, 4096, 32, 32, 0}, {"argumentNotifier", 1, 4096, 32, 32, 0}},
         {{64},
          {128},
          {}}}

    };
};

#endif // FULLSYS_DESCRIPTOR_H
