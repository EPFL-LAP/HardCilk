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
    throw std::runtime_error("Not implemented yet");
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