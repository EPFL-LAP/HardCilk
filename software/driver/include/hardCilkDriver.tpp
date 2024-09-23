#include "FullSysGenDescriptor.h"
#include <map>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <vector>
#include <stdexcept>


/**
 * @brief init_system is a template function that takes a vector of template type T and initializes the scheduler servers with the corresponding values.
 * The function also initializes the allocation servers and memory allocator servers based on the full system generation descriptor. If 
 * the pcie_flag is set to true, the function will map the pcie address space to the system.
 * Takes as input a vector of tasks of the base task to be written to the virtual task queue.
 * @param base_task_data The data of the root task 
 * @return 0 if the system was initialized successfully, -1 otherwise
*/
template <typename T> int init_system(std::vector<T> base_task_data){

    // Set the return addresses of the driver
    for(auto taskData = base_task_data.begin(); taskData != base_task_data.end(); taskData++){
        setReturnAddr(taskData->cont);
    }

    // Initialize the different servers
    for(auto taskDescriptor : descriptor.taskDescriptors){
      
            // Log which task is being initialized
            printf("Initializing task %s\n", taskDescriptor.name.c_str());

            // Allocate memory for all the scheduler servers
            for(auto base_address = taskDescriptor.mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor.mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++){
                // Allocate memory for the scheduler server
                uint64_t addr = allocateMemFPGA(taskDescriptor.getCapacityVirtualQueue("scheduler") * taskDescriptor.widthTask/8, taskDescriptor.widthTask/8);
                
                // Write zeros to the allocated memory
                memory_->copyToDevice(addr, reinterpret_cast<const uint8_t*>(std::vector<T>(taskDescriptor.getCapacityVirtualQueue("scheduler")).data()), taskDescriptor.getCapacityVirtualQueue("scheduler") * sizeof(T));

                // Initialize the scheduler server information
                waitPaused(*base_address + scheduler_server_rpause_shift);
                memory_->writeReg64(*base_address + scheduler_server_raddr_shift, addr);
                memory_->writeReg64(*base_address + scheduler_server_maxLength_shift, taskDescriptor.getCapacityVirtualQueue("scheduler"));
                memory_->writeReg64(*base_address + scheduler_server_fifoTailReg_shift, 0x0);
                memory_->writeReg64(*base_address + scheduler_server_fifoHeadReg_shift, 0x0);
                memory_->writeReg64(*base_address + scheduler_server_currLen_shift, 0x0);

                // Read the initialized information of the scheduler server and assert that the initialization was successful
                assert(memory_->readReg64(*base_address + scheduler_server_raddr_shift) == addr);
                assert(memory_->readReg64(*base_address + scheduler_server_maxLength_shift) == taskDescriptor.getCapacityVirtualQueue("scheduler"));
                assert(memory_->readReg64(*base_address + scheduler_server_fifoTailReg_shift) == 0x0);
                assert(memory_->readReg64(*base_address + scheduler_server_fifoHeadReg_shift) == 0x0);
                assert(memory_->readReg64(*base_address + scheduler_server_currLen_shift) == 0x0);


                // Log the successful initialization information of the scheduler server with indentation
                printf("        Initialized scheduler server at address %lx with length %lx, fifoTail %lx, fifoHead %lx\n", *base_address, taskDescriptor.getCapacityVirtualQueue("scheduler"), 0x0, 0x0);
            }
            if(taskDescriptor.isRoot){
                // Read the address registered at the first virtual server of the task and write the data to that address
                uint64_t data_queue_address = memory_->readReg64(*(taskDescriptor.mgmtBaseAddresses.schedulerServersBaseAddresses.begin()) + scheduler_server_raddr_shift);
                printf("        Writing root task data to the scheduler server with data at address %lx\n", data_queue_address);


                // Write the base task data to the first scheduler server
                memory_->copyToDevice(data_queue_address, reinterpret_cast<const uint8_t*>(base_task_data.data()), base_task_data.size() * sizeof(T));
                printf("        Wrote root task data to the scheduler server\n");

                // Update the fifoTailReg of the first scheduler server
                memory_->writeReg64(*(taskDescriptor.mgmtBaseAddresses.schedulerServersBaseAddresses.begin()) + scheduler_server_fifoTailReg_shift, base_task_data.size());
                memory_->writeReg64(*(taskDescriptor.mgmtBaseAddresses.schedulerServersBaseAddresses.begin()) + scheduler_server_currLen_shift, base_task_data.size());

                // Log the successful initialization information of the root scheduler server with indentation
                printf("        Initialized root task at scheduler server at address %lx with length %lx, fifoHead %lx, fifoTail %lx\n", *(taskDescriptor.mgmtBaseAddresses.schedulerServersBaseAddresses.begin()), taskDescriptor.getCapacityVirtualQueue("scheduler"), 0x0, base_task_data.size());
            }
            
            // Allocate memory for all the allocation servers
            for(auto base_address = taskDescriptor.mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor.mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++){
                // First allocate memory to carry the continuation tasks
                uint64_t continuation_tasks_holder_addr = allocateMemFPGA(taskDescriptor.getCapacityVirtualQueue("allocator") * taskDescriptor.widthTask/8, taskDescriptor.widthTask/8);
                uint64_t continuation_queue_addr = allocateMemFPGA(taskDescriptor.getCapacityVirtualQueue("allocator") * descriptor.widthAddress/8, descriptor.widthAddress/8);
                
                // Create an array of 64 bit addresses that has the addresses of the continuation tasks allocated in the previous step
                std::vector<uint64_t> addresses;
                for(auto i = 0; i < taskDescriptor.getCapacityVirtualQueue("allocator"); i++){
                    addresses.push_back(continuation_tasks_holder_addr + i * taskDescriptor.widthTask/8);
                }

                // Write the addresses to the continuation queue
                memory_->copyToDevice(continuation_queue_addr, reinterpret_cast<const uint8_t*>(addresses.data()), addresses.size() * sizeof(uint64_t));
                
                waitPaused(*base_address + alloc_server_rpause_shift);   
                memory_->writeReg64(*base_address + alloc_server_raddr_shift, continuation_queue_addr);
                memory_->writeReg64(*base_address + alloc_server_availableSize_shift, taskDescriptor.getCapacityVirtualQueue("allocator"));

                taskDescriptor.mapServerAddressToClosureBaseAddress[*base_address].push_back(std::pair<uint64_t, int>(continuation_tasks_holder_addr, taskDescriptor.getCapacityVirtualQueue("allocator")));

                // Log the successful initialization information of the allocation server with indentation
                printf("        Initialized allocation server at address %lx with length %lx\n", *base_address, taskDescriptor.getCapacityVirtualQueue("allocator"));
            }

            // Allocate memory for all the memory allocator servers
            for(auto base_address = taskDescriptor.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++){
                // First allocate memory for the pre-allocated memory
                uint64_t  pre_allocated_memory_addr = allocateMemFPGA(taskDescriptor.getCapacityVirtualQueue("memoryAllocator") * taskDescriptor.getVirtualEntryWidth("memoryAllocator")/8, taskDescriptor.getVirtualEntryWidth("memoryAllocator")/8);
                uint64_t  pre_allocated_memory_queue_addr = allocateMemFPGA(taskDescriptor.getCapacityVirtualQueue("memoryAllocator") * descriptor.widthAddress/8, descriptor.widthAddress/8);

                // Create an array of 64 bit addresses that has the addresses of the pre-allocated memory allocated in the previous step
                std::vector<uint64_t> addresses;
                for(auto i = 0; i < taskDescriptor.getCapacityVirtualQueue("memoryAllocator"); i++){
                    addresses.push_back(pre_allocated_memory_addr + i * taskDescriptor.getVirtualEntryWidth("memoryAllocator")/8);
                }

                // Write the addresses to the pre-allocated memory queue
                memory_->copyToDevice(pre_allocated_memory_queue_addr, reinterpret_cast<const uint8_t*>(addresses.data()), addresses.size() * sizeof(uint64_t));

                waitPaused(*base_address + scheduler_server_rpause_shift);
                memory_->writeReg64(*base_address + mem_alloc_server_raddr_shift, pre_allocated_memory_queue_addr);
                memory_->writeReg64(*base_address + mem_alloc_server_availableSize_shift, taskDescriptor.getCapacityVirtualQueue("memoryAllocator"));

                // Log the successful initialization information of the memory allocator server with indentation
                printf("        Initialized memory allocator server at address %lx with length %lx\n", *base_address, taskDescriptor.getCapacityVirtualQueue("memoryAllocator"));
            } 

    }
    

    assert(free_mem_base_addr < 0x3FFFFFFFF);

    return 0;
}