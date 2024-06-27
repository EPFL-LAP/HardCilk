#include "hardCilkDriver.h"


hardCilkDriver::hardCilkDriver(bool pcie){
    pcie_flag = pcie;
    if(pcie_flag){
        // Open the PCIe device and map the address space
        if(d4e_xil_device_open(&xil_device, "/dev/xdma0", 0, 0, MAP_SZ) < 0)
            throw std::runtime_error("Failed to open the device");
    } else {
        throw std::runtime_error("Not implemented yet, only PCIe is supported for now.");
    }
}

hardCilkDriver::~hardCilkDriver(){
    if(pcie_flag){
        d4e_close(&xil_device.device);
    }
}



/**
 * @brief Start the system by writing to the rPause registers of the different servers. 
 * This MUST be called after init_system has been called.
 */
int hardCilkDriver::start_system(){
    if(!pcie_flag){
        return EXIT_FAILURE;
    }
    for(auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++) {
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++){
            writeReg64(*base_address + scheduler_server_rpause_shift, 0x0);
        }
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++){
            writeReg64(*base_address + alloc_server_rpause_shift, 0x0);
        }
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++){
            writeReg64(*base_address + mem_alloc_server_rpause_shift, 0x0);
        }
    }
    return 0;
}

void hardCilkDriver::management_loop(){
    while(true){
        if(checkPaused() == 0){
            managePausedServer();
        }
        if(checkFinished() == 0){
            break;
        }
    }
}

int hardCilkDriver::managePausedServer(){
    // Which server of which task is paused?
    // Check the rPause registers of the different servers

    for(auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++){
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++){
            if(readReg64(*base_address + scheduler_server_rpause_shift) != 0x0){
                manageSchedulerServer(*base_address, *taskDescriptor);
            }
        }
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++){
            if(readReg64(*base_address + alloc_server_rpause_shift) != 0x0){
                manageAllocationServer(*base_address, *taskDescriptor);
            }
        }
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++){
            if(readReg64(*base_address + mem_alloc_server_rpause_shift) != 0x0){
                manageMemoryAllocatorServer(*base_address, *taskDescriptor);
            }
        }
    }
    return 0;
}

/**
 * 
 */

int hardCilkDriver::manageSchedulerServer(uint64_t base_address, TaskDescriptor taskDescriptor){
    // Read the rAddress of the scheduler server and the maxLength and write the data in the free memory
    uint64_t addr = readReg64(base_address + scheduler_server_raddr_shift);
    uint64_t maxLength = readReg64(base_address + scheduler_server_maxLength_shift);

    // Log the information of calling this function 
    std::cout << "Managing scheduler server of task type " << taskDescriptor.name << " at address " << base_address << "with rAddress " << addr << " and maxLength " << maxLength << std::endl;

    freed_mem_blocks.push_back(freedMemBlock{addr, maxLength*taskDescriptor.widthTask/8}); // Free the memory and write it in bytes.

    // Allocate double the maxLength of the scheduler server
    uint64_t new_addr = allocateMemFPGA(2 * maxLength * taskDescriptor.widthTask/8);

    // Read the data from the scheduler server to the cpu
    void *data = malloc(maxLength * taskDescriptor.widthTask/8);
    readMem(data, addr, maxLength * taskDescriptor.widthTask/8);

    // Write the data to the new address
    writeMem(new_addr, data, maxLength * taskDescriptor.widthTask/8);

    // Write the new address to the rAddress register
    writeReg64(base_address + scheduler_server_raddr_shift, new_addr);

    // Write the new head and tail registers
    writeReg64(base_address + scheduler_server_fifoTailReg_shift, 0x0);
    writeReg64(base_address + scheduler_server_fifoHeadReg_shift, maxLength);

    // Write the new MaxLength
    writeReg64(base_address + scheduler_server_maxLength_shift, maxLength * 2);

    // Write the rPause register to 0
    writeReg64(base_address + scheduler_server_rpause_shift, 0x0);

    return 0;
}

/**
 * @brief Manage the allocation server, this function is called when the allocation server is paused with zero entries available
 */

int hardCilkDriver::manageAllocationServer(uint64_t base_address, TaskDescriptor taskDescriptor){
    // read the raddr of the server
    uint64_t addr = readReg64(base_address + alloc_server_raddr_shift);

    // Get the size of the queue from the taskDescriptor
    int size = taskDescriptor.getCapacityVirtualQueue("allocator");

    // Log the information of calling this function
    std::cout << "Managing allocation server of task type " << taskDescriptor.name << " at address " << base_address << "with rAddress " << addr << std::endl;

    std::vector<uint64_t> addresses;

    // Check the mapServerAddressToClosureBaseAddress and read the address as int and check it if set to -1 (indicating freed continuation task which is done by the argument notifier) 
    for(auto address : taskDescriptor.mapServerAddressToClosureBaseAddress[base_address]){
        // read the whole memory block and check each address
        char * data = (char *)malloc(address.second * taskDescriptor.widthTask/8);
        readMem(data, address.first, address.second * taskDescriptor.widthTask/8);
        
        // iterate over data and check if the value is less than 0
        for(int i = 0; i < address.second && addresses.size() < size; i++){
            int val = *(int *)(data + i * taskDescriptor.widthTask/8);            
            if(val < 0){
                // Indication of a freed closure, shall be tagged from the PE
                addresses.push_back(address.first + i * taskDescriptor.widthTask/8);
            }
        }
    }

    // check the size of the addresses if less than size allocate memory to complete it
    if(addresses.size() < size){
        int left_size = size - addresses.size();
        uint64_t continuation_tasks_holder_addr = allocateMemFPGA(left_size * taskDescriptor.widthTask/8);
        taskDescriptor.mapServerAddressToClosureBaseAddress[base_address].push_back(std::pair<uint64_t, int>(continuation_tasks_holder_addr, left_size));

        for(auto i = 0; i < left_size; i++){
            addresses.push_back(continuation_tasks_holder_addr + i * taskDescriptor.widthTask/8);
        }
    }

    assert (addresses.size() == size);

    // Write the addresses to the continuation queue
    writeMem(addr, static_cast<const void*>(addresses.data()), addresses.size() * sizeof(uint64_t));
    
    // Write the new addresses to the continuation queue
    writeReg64(base_address + alloc_server_availableSize_shift, size);

    // Write the rPause register to 0
    writeReg64(base_address + alloc_server_rpause_shift, 0x0);

    return 0;
}

int hardCilkDriver::manageMemoryAllocatorServer(uint64_t base_address, TaskDescriptor taskDescriptor){
    throw std::runtime_error("Not implemented yet");
    return 0;
}

/**
 * @brief Check if the system is paused for management
 */
int hardCilkDriver::checkPaused(){
    for(auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++){
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++){
            if(readReg64(*base_address + scheduler_server_rpause_shift) != 0x0){
                return 0;
            }
        }
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++){
            if(readReg64(*base_address + alloc_server_rpause_shift) != 0x0){
                return 0;
            }
        }
        for(auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++){
            if(readReg64(*base_address + mem_alloc_server_rpause_shift) != 0x0){
                return 0;
            }
        }
    }
    return -1;
}

int hardCilkDriver::writeReg32(uint64_t addr, uint32_t data){
    if(pcie_flag)
        d4e_reg_write32(&xil_device.device, addr, data);

    return 0;
}

uint32_t hardCilkDriver::readReg32(uint64_t addr){
    if(pcie_flag)
        return d4e_reg_read32(&xil_device.device, addr);

    return EXIT_FAILURE;
}

int hardCilkDriver::writeReg64(uint64_t addr, uint64_t data){
    if(pcie_flag)
        d4e_reg_write64(&xil_device.device, addr, data);

    return 0;
}

uint64_t hardCilkDriver::readReg64(uint64_t addr){
    if(pcie_flag)
        return d4e_reg_read64(&xil_device.device, addr);

    return EXIT_FAILURE;
}

int hardCilkDriver::readMem(void *dest, d4e_addr_t src, size_t size){
    if(pcie_flag)
        return d4e_dma_d2h(&xil_device.device, dest, src, size);

    return EXIT_FAILURE;
}

int hardCilkDriver::writeMem(d4e_addr_t dest, void const *src, size_t size){
    if(pcie_flag)
        return d4e_dma_h2d(&xil_device.device, dest, src, size);

    return EXIT_FAILURE;
}