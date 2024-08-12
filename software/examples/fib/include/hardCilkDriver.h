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

    hardCilkDriver(Memory * memory);

    // template <typename T> int init_system(std::vector<T>);
    #include "hardCilkDriver.tpp"
    
    int start_system();
    void management_loop();


    int set_return_addr(uint64_t addr){
        // Write zero to the return address using writeMem
        uint64_t val = 0;
        
        memory_->copyToDevice(addr, reinterpret_cast<const uint8_t*>(&val), sizeof(val));
     

        return_addresses.push_back(addr);

        // Log the return addresses
        printf("Return address set to 0x%lx\n", addr);

        return 0;
    }
    /**
     * @brief Allocate memory on the FPGA. This function is used to allocate memory on the FPGA.
     * 
     * @param size The size of the memory in bytes to allocate
     */
    uint64_t allocateMemFPGA(uint64_t size, uint64_t alignment /** alignment is a byte value */){

        // Check if any memory freed by the processor can be used, if yes return and remove it from the freed memory
        for(auto it = freed_mem_blocks.begin(); it != freed_mem_blocks.end(); it++){
            if(it->size >= size){
                
                auto reminder = it->addr % alignment;
                auto offset = (reminder == 0) ? 0 : alignment - reminder;
                
                if(it->size - offset < size){
                    continue;
                }

                uint64_t addr = it->addr + offset;
                it->addr += (size + offset);
                it->size -= (size + offset);
                if(it->size == 0){
                    freed_mem_blocks.erase(it);
                }
                return addr;
            }
        }
        // If no memory is freed by the processor, allocate new memory
        // Align the memory to the alignment
        auto reminder = free_mem_base_addr % alignment;
        auto offset = (reminder == 0) ? 0 : alignment - reminder;
        free_mem_base_addr += offset;
        uint64_t addr = free_mem_base_addr;
        free_mem_base_addr += size;
        assert(free_mem_base_addr < 0x3FFFFFFFF);
        return addr;
    }

 

    ~hardCilkDriver();

    Memory *memory_;

    private:


    int checkPaused();

    int checkFinished(){
        uint32_t finished = 0;
        assert(return_addresses.size() > 0);
        for(auto addr : return_addresses){
            memory_->copyFromDevice(reinterpret_cast<uint8_t*>(&finished), addr, sizeof(finished));
            if(finished != 0){
                printf("Found value %lu at return address %lx\n", finished, addr);
                return 0; // Finished
            }
        }
        return 1; // Not Finished
    }
    /**
     * Function that takes no params and makes sure that this driver is connected to the system on the FPGA
     */
    int sanityCheck();
    
    int managePausedServer();

    int manageSchedulerServer(uint64_t base_address, TaskDescriptor taskDescriptor);
    int manageAllocationServer(uint64_t base_address, TaskDescriptor taskDescriptor);
    int manageMemoryAllocatorServer(uint64_t base_address, TaskDescriptor taskDescriptor);


    int waitPaused(uint64_t addr){
        while(memory_->readReg64(addr) == 0){
            sleep(1);
        }
        return 0;
    }


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

    uint64_t free_mem_base_addr = 0x0;

    std::vector <freedMemBlock> freed_mem_blocks;
    
    std::vector <uint64_t> return_addresses;

};

#endif // DRIVER_H