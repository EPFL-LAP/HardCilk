#ifndef DRIVER_H
#define DRIVER_H

#include "FullSysGenDescriptor.h"

#include <map>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <vector>
#include <stdexcept>
#include <iostream>
#include <memIO.h>

// This is used to track memory freed by the processor to extend one of the FPGA queues to another location
struct freedMemBlock
{
    uint64_t addr;
    uint64_t size;
};

class hardCilkDriver
{

public:
    int virtual run_test_bench() = 0;

    hardCilkDriver(Memory *memory);

// template <typename T> int init_system(std::vector<T>);
#include "hardCilkDriver.tpp"

    int startSystem();
    void managementLoop();


    int setReturnAddr(uint64_t addr);

    /**
     * @brief Allocate memory on the FPGA. This function is used to allocate memory on the FPGA.
     *
     * @param size The size of the memory in bytes to allocate
     */
    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment /** alignment is a byte value */);

    ~hardCilkDriver();
    
    Memory *memory_;

private:
    std::vector<std::pair<uint64_t, uint64_t>> trackMalloc;

    int checkPaused();

    int checkFinished();
    /**
     * Function that takes no params and makes sure that this driver is connected to the system on the FPGA
     */
    int sanityCheck();

    int managePausedServer();
    int manageSchedulerServer(uint64_t base_address, TaskDescriptor taskDescriptor);
    int manageAllocationServer(uint64_t base_address, TaskDescriptor taskDescriptor);
    int manageMemoryAllocatorServer(uint64_t base_address, TaskDescriptor taskDescriptor);

    int waitPaused(uint64_t addr);

    FullSysGenDescriptor descriptor;

    const uint8_t alloc_server_rpause_shift = 0x0;
    const uint8_t alloc_server_raddr_shift = 0x8;
    const uint8_t alloc_server_availableSize_shift = 0x10;

    const uint8_t mem_alloc_server_rpause_shift = 0x0;
    const uint8_t mem_alloc_server_raddr_shift = 0x8;
    const uint8_t mem_alloc_server_availableSize_shift = 0x10;

    const uint8_t scheduler_server_rpause_shift = 0x0;
    const uint8_t scheduler_server_raddr_shift = 0x8;
    const uint8_t scheduler_server_maxLength_shift = 0x10;
    const uint8_t scheduler_server_fifoTailReg_shift = 0x18;
    const uint8_t scheduler_server_fifoHeadReg_shift = 0x20;
    const uint8_t scheduler_server_processorInterrupt_shift = 0x28;
    const uint8_t scheduler_server_currLen_shift = 0x30;

    uint64_t free_mem_base_addr = 0x0;
    std::vector<freedMemBlock> freed_mem_blocks;
    std::vector<uint64_t> return_addresses;
};

#endif // DRIVER_H