#ifndef DRIVER_H
#define DRIVER_H

#include "FullSysGenDescriptor.h"
#include <d4e/interface.h>
#include <d4e/xil.h>
#include <map>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <vector>
#include <stdexcept>
#include <iostream>


struct MmioInterface {
    virtual void read32(uint64_t addr, uint32_t *data) = 0;
    virtual void write32(uint64_t addr, uint32_t data) = 0;

    virtual void read64(uint64_t addr, uint64_t *data) = 0;
    virtual void write64(uint64_t addr, uint64_t data) = 0;

    virtual void wait_ns(uint64_t ns) = 0;

    virtual ~MmioInterface() {}
};

struct SystemC_MmioInterface {
    void read32(uint64_t addr, uint32_t *data) {
        std::cout << "Reading 32-bit value from address " << addr << std::endl;
    }

    void write32(uint64_t addr, uint32_t data) {
        std::cout << "Writing 32-bit value " << data << " to address " << addr << std::endl;
    }

    void read64(uint64_t addr, uint64_t *data) {
        std::cout << "Reading 64-bit value from address " << addr << std::endl;
    }

    void write64(uint64_t addr, uint64_t data) {
        std::cout << "Writing 64-bit value " << data << " to address " << addr << std::endl;
    }

};

// This is used to track memory freed by the processor to extend one of the FPGA queues to another location
struct freedMemBlock
{
    uint64_t addr;
    uint64_t size;
};


class hardCilkDriver
{

    public:

    hardCilkDriver();

    // template <typename T> int init_system(std::vector<T>);
    #include "hardCilkDriver.tpp"
    
    int start_system();
    void management_loop();
    int readMem(void *dest, d4e_addr_t src, size_t size);
    int writeMem(d4e_addr_t dest, void const *src, size_t size);


    int set_return_addr(uint64_t addr){
        // Write zero to the return address using writeMem
        uint64_t val = 0;
        
        if(writeMem(addr, &val, sizeof(val)) != EXIT_SUCCESS){
            return EXIT_FAILURE;
        }

        return_addresses.push_back(addr);
        return 0;
    }
    /**
     * @brief Allocate memory on the FPGA. This function is used to allocate memory on the FPGA.
     * 
     * @param size The size of the memory in bytes to allocate
     */
    uint64_t allocateMemFPGA(uint64_t size){
        // Check if any memory freed by the processor can be used, if yes return and remove it from the freed memory
        for(auto it = freed_mem_blocks.begin(); it != freed_mem_blocks.end(); it++){
            if(it->size >= size){
                uint64_t addr = it->addr;
                it->addr += size;
                it->size -= size;
                if(it->size == 0){
                    freed_mem_blocks.erase(it);
                }
                return addr;
            }
        }
        // If no memory is freed by the processor, allocate new memory
        uint64_t addr = free_mem_base_addr;
        free_mem_base_addr += size;
        return addr;
    }

 
    int sanityCheck();

    ~hardCilkDriver();

    private:

    int checkPaused();

    int checkFinished(){
        uint64_t finished = 0;
        assert(return_addresses.size() > 0);
        for(auto addr : return_addresses){
            if(readMem(&finished, addr, sizeof(finished)) != 0){
                return 1;
            }
        }
        return 0;
    }
    /**
     * Function that takes no params and makes sure that this driver is connected to the system on the FPGA
     */
    int sanityCheck();
    
    int managePausedServer();

    int manageSchedulerServer(uint64_t base_address, TaskDescriptor taskDescriptor);
    int manageAllocationServer(uint64_t base_address, TaskDescriptor taskDescriptor);
    int manageMemoryAllocatorServer(uint64_t base_address, TaskDescriptor taskDescriptor);

    int writeReg64(uint64_t addr, uint64_t data);
    uint64_t readReg64(uint64_t addr);

    int writeReg32(uint64_t addr, uint32_t data);
    uint32_t readReg32(uint64_t addr);

    

    int waitPaused(uint64_t addr){
        while(readReg64(addr) == 0){
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
    
    
    bool pcie_flag;
    struct d4e_xil_device xil_device;
    uint64_t MAP_SZ = 1e9;
    
    

};

#endif // DRIVER_H