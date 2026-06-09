#include "hardCilkDriver.h"

#include <chrono>
#include <sstream>

hardCilkDriver::hardCilkDriver(Memory *memory)
{
    memory_ = memory;
    // sanityCheck();
}

hardCilkDriver::~hardCilkDriver()
{
}

/**
 * @brief Start the system by writing to the rPause registers of the different servers.
 * This MUST be called after init_system has been called.
 */
int hardCilkDriver::startSystem()
{

    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + scheduler_server_rpause_shift, 0x0);
            if(!taskDescriptor->isCont)
                memory_->writeReg64(*base_address + scheduler_server_processorInterrupt_shift, 0xFFFFFFFFF);
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + alloc_server_rpause_shift, 0x0);
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + mem_alloc_server_rpause_shift, 0x0);
        }
    }
    return 0;
}

int hardCilkDriver::waitPaused(uint64_t addr)
{
    const auto start = std::chrono::steady_clock::now();
    auto next_log = start + std::chrono::seconds(5);
    const auto timeout = start + std::chrono::seconds(120);
    while (memory_->readReg64(addr) == 0)
    {
        const auto now = std::chrono::steady_clock::now();
        if (now >= next_log)
        {
            std::cerr << "Still waiting for server pause at register 0x"
                      << std::hex << addr << std::dec << " after "
                      << std::chrono::duration<double>(now - start).count()
                      << "s\n";
            next_log = now + std::chrono::seconds(5);
        }
        if (now >= timeout)
        {
            std::ostringstream oss;
            oss << "Timed out waiting for server pause at register 0x"
                << std::hex << addr;
            throw std::runtime_error(oss.str());
        }
        sleep(1);
    }
    return 0;
}

int hardCilkDriver::checkFinished()
{
    int32_t finished = 0;
    assert(return_addresses.size() > 0);
    for (auto addr : return_addresses)
    {
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(&finished), addr, sizeof(finished));
        if (condition_(finished) == true)
        {
            printf("Found value %d at return address %lx\n", finished, addr);
            return 0; // Finished
        }
    }
    return 1; // Not Finished
}

void hardCilkDriver::managementLoop()
{
    while (true)
    {
        if (checkPaused() == 0)
        {
            managePausedServer();
        }
        if (checkFinished() == 0)
        {
            printf("Finished processing.\n");
            break;
        }
    }
}

int hardCilkDriver::managePausedServer()
{
    // Which server of which task is paused?
    // Check the rPause registers of the different servers

    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + scheduler_server_rpause_shift) != 0x0)
            {
                manageSchedulerServer(*base_address, *taskDescriptor);
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + alloc_server_rpause_shift) != 0x0)
            {
                manageAllocationServer(*base_address, *taskDescriptor);
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + mem_alloc_server_rpause_shift) != 0x0)
            {
                manageMemoryAllocatorServer(*base_address, *taskDescriptor);
            }
        }
    }
    return 0;
}

/**
 *
 */

int hardCilkDriver::manageSchedulerServer(uint64_t base_address, TaskDescriptor taskDescriptor)
{
    // Read the rAddress of the scheduler server and the maxLength and write the data in the free memory
    uint64_t addr = memory_->readReg64(base_address + scheduler_server_raddr_shift);
    uint64_t maxLength = memory_->readReg64(base_address + scheduler_server_maxLength_shift);
    uint64_t fifoTail = memory_->readReg64(base_address + scheduler_server_fifoTailReg_shift);
    uint64_t fifoHead = memory_->readReg64(base_address + scheduler_server_fifoHeadReg_shift);
    uint64_t currLen = memory_->readReg64(base_address + scheduler_server_currLen_shift);

    // Always-visible warning (stderr, not gated by any verbosity/fast mode): a
    // mid-run resize copies + zeroes the queue inside the timed window and hurts
    // timing. If you see this, raise the initial queue size for this task so the
    // resize does not happen.
    std::cerr << "[RESIZE] " << taskDescriptor.name
              << " scheduler/spawner queue full at " << maxLength
              << " entries -> doubling to " << (maxLength * 2)
              << " (mid-run resize hurts timing; increase the initial queue size)"
              << std::endl;

    // Log the information of calling this function
    std::cout << "Managing scheduler server of task type " << taskDescriptor.name
              << " at address " << base_address
              << " with rAddress " << addr
              << " maxLength " << maxLength
              << " fifoHead " << fifoHead
              << " fifoTail " << fifoTail
              << " currLen " << currLen << std::endl;

    if (maxLength == 0 || currLen > maxLength || fifoHead >= maxLength || fifoTail >= maxLength)
    {
        std::ostringstream oss;
        oss << "Invalid scheduler FIFO metadata while extending " << taskDescriptor.name
            << ": maxLength=" << maxLength
            << " fifoHead=" << fifoHead
            << " fifoTail=" << fifoTail
            << " currLen=" << currLen;
        throw std::runtime_error(oss.str());
    }

    //freed_mem_blocks.push_back(freedMemBlock{addr, maxLength * taskDescriptor.widthTask / 8}); // Free the memory and write it in bytes.

    // Allocate double the maxLength of the scheduler server
    uint64_t new_addr = memory_->allocateMemFPGA(2 * maxLength * taskDescriptor.widthTask / 8, taskDescriptor.widthTask / 8);

    // Zero the new (doubled) backing store before repacking live entries into it.
    // The resize only copies the currLen live entries below; the rest would be
    // stale HBM (xrt-smi reset does not clear it), and later reading an
    // unwritten-but-counted slot dispatches a garbage task (garbage `cont` ->
    // lost join-counter decrement -> hang). Only large graphs (e.g. com-orkut)
    // grow a frontier big enough to trigger a resize, which is why this slips
    // past small-graph testing even with the initial allocation zeroed.
    {
        uint64_t newBytes = 2 * maxLength * taskDescriptor.widthTask / 8;
        static const uint64_t kZeroChunkBytes = 16ull * 1024 * 1024;
        static const std::vector<uint8_t> zeroChunk(kZeroChunkBytes, 0);
        uint64_t filled = 0;
        while (filled < newBytes)
        {
            uint64_t chunk = std::min<uint64_t>(kZeroChunkBytes, newBytes - filled);
            memory_->copyToDevice(new_addr + filled, zeroChunk.data(), chunk);
            filled += chunk;
        }
    }

    const uint64_t entryBytes = taskDescriptor.widthTask / 8;
    const uint64_t liveBytes = currLen * entryBytes;
    std::vector<uint8_t> data(liveBytes);

    if (currLen > 0)
    {
        // The scheduler backing store is a circular FIFO. Pack live entries in
        // dequeue order so the resized FIFO can restart with head=0.
        uint64_t firstEntries = std::min(currLen, maxLength - fifoHead);
        uint64_t firstBytes = firstEntries * entryBytes;
        memory_->copyFromDevice(data.data(), addr + fifoHead * entryBytes, firstBytes);

        uint64_t secondEntries = currLen - firstEntries;
        if (secondEntries > 0)
        {
            memory_->copyFromDevice(data.data() + firstBytes, addr, secondEntries * entryBytes);
        }
    }

    // Write the data to the new address
    if (liveBytes > 0)
    {
        memory_->copyToDevice(new_addr, data.data(), liveBytes);
    }

    // Write the new address to the rAddress register
    memory_->writeReg64(base_address + scheduler_server_raddr_shift, new_addr);

    // Write the new head and tail registers
    memory_->writeReg64(base_address + scheduler_server_fifoTailReg_shift, currLen);
    memory_->writeReg64(base_address + scheduler_server_fifoHeadReg_shift, 0x0);
    memory_->writeReg64(base_address + scheduler_server_currLen_shift, currLen);

    // Write the new MaxLength
    memory_->writeReg64(base_address + scheduler_server_maxLength_shift, maxLength * 2);

    // Write the rPause register to 0
    memory_->writeReg64(base_address + scheduler_server_rpause_shift, 0x0);

    return 0;
}

/**
 * @brief Manage the allocation server, this function is called when the allocation server is paused with zero entries available
 */

int hardCilkDriver::manageAllocationServer(uint64_t base_address, TaskDescriptor taskDescriptor)
{
    // read the raddr of the server
    uint64_t addr = memory_->readReg64(base_address + alloc_server_raddr_shift);

    // Get the size of the queue from the taskDescriptor
    int size = taskDescriptor.getCapacityVirtualQueue("allocator");

    // Log the information of calling this function
    std::cout << "Managing allocation server of task type " << taskDescriptor.name << " at address " << base_address << " with rAddress " << addr << std::endl;

    std::vector<uint64_t> addresses;

    // Check the mapServerAddressToClosureBaseAddress and read the address as int and check it if set to 0x1000000 (indicating freed continuation task which is done by the argument notifier)
    for (auto address : taskDescriptor.mapServerAddressToClosureBaseAddress[base_address])
    {
        // read the whole memory block and check each address
        char *data = (char *)malloc(address.second * taskDescriptor.widthTask / 8);
        memory_->copyFromDevice(reinterpret_cast<uint8_t *>(data), address.first, address.second * taskDescriptor.widthTask / 8);

        // iterate over data and check if the value is less than 0
        for (int i = 0; i < address.second && addresses.size() < size; i++)
        {
            int val = *(int *)(data + i * taskDescriptor.widthTask / 8);
            if (val == 0x1000000)
            {
                // Indication of a freed closure, tagged from the ArgumentNotifier
                addresses.push_back(address.first + i * taskDescriptor.widthTask / 8);
            }
        }
    }

    // check the size of the addresses if less than size allocate memory to complete it
    if (addresses.size() < size)
    {
        int left_size = size - addresses.size();
        uint64_t continuation_tasks_holder_addr = memory_->allocateMemFPGA(left_size * taskDescriptor.widthTask / 8, taskDescriptor.widthTask / 8);
        taskDescriptor.mapServerAddressToClosureBaseAddress[base_address].push_back(std::pair<uint64_t, int>(continuation_tasks_holder_addr, left_size));

        for (auto i = 0; i < left_size; i++)
        {
            addresses.push_back(continuation_tasks_holder_addr + i * taskDescriptor.widthTask / 8);
        }
    }

    assert(addresses.size() == size);

    // Write the addresses to the continuation queue
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(addresses.data()), addresses.size() * sizeof(uint64_t));

    // Write the new addresses to the continuation queue
    memory_->writeReg64(base_address + alloc_server_availableSize_shift, size);

    // Write the rPause register to 0
    memory_->writeReg64(base_address + alloc_server_rpause_shift, 0x0);

    return 0;
}

int hardCilkDriver::manageMemoryAllocatorServer(uint64_t base_address, TaskDescriptor taskDescriptor)
{ 
    // read the raddr of the server
    uint64_t addr = memory_->readReg64(base_address + mem_alloc_server_raddr_shift);

    // Get the size of the queue from the taskDescriptor
    uint64_t size = taskDescriptor.getCapacityVirtualQueue("memoryAllocator");

    // Log the information of calling this function
    std::cout << "Managing memory allocation server of task type " << taskDescriptor.name << " at address " << base_address << " with rAddress " << addr << std::endl;

    std::vector<uint64_t> addresses;


    // check the size of the addresses if less than size allocate memory to complete it
    if (addresses.size() < size)
    {
        int left_size = size - addresses.size();
        uint64_t continuation_tasks_holder_addr = memory_->allocateMemFPGA(left_size * taskDescriptor.getVirtualEntryWidth("memoryAllocator") / 8, 512);

        std::vector<uint8_t> zeros(left_size * taskDescriptor.getVirtualEntryWidth("memoryAllocator") / 8, 0);
        memory_->copyToDevice(continuation_tasks_holder_addr, zeros.data(), zeros.size());

        taskDescriptor.mapServerAddressToMallocBaseAddress[base_address].push_back(std::pair<uint64_t, int>(continuation_tasks_holder_addr, left_size));

        for (auto i = 0; i < left_size; i++)
        {
            addresses.push_back(continuation_tasks_holder_addr + i * taskDescriptor.getVirtualEntryWidth("memoryAllocator") / 8);
        }
    }

    assert(addresses.size() == size);

    // Write the addresses to the continuation queue
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(addresses.data()), addresses.size() * sizeof(uint64_t));

    // Write the new addresses to the continuation queue
    memory_->writeReg64(base_address + mem_alloc_server_availableSize_shift, size);

    // Write the rPause register to 0
    memory_->writeReg64(base_address + mem_alloc_server_rpause_shift, 0x0);

    return 0;
}


int hardCilkDriver::setReturnAddr(uint64_t addr)
{
    // Write zero to the return address using writeMem
    // uint64_t val = 0;

    // memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(&val), sizeof(val));

    printf("NOTE: RETURN ADDRESS VALUE SHOULD BE SET BY THE USER CORRECTLY BASED ON ARGUMENT NOTIIFICATION\n");

    return_addresses.push_back(addr);

    // Log the return addresses
    printf("Return address set to 0x%lx\n", addr);

    return 0;
}

/**
 * @brief Check if the system is paused for management
 */
int hardCilkDriver::checkPaused()
{
    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + scheduler_server_rpause_shift) != 0x0)
            {
                return 0;
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + alloc_server_rpause_shift) != 0x0)
            {
                return 0;
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++)
        {
            if (memory_->readReg64(*base_address + mem_alloc_server_rpause_shift) != 0x0)
            {
                return 0;
            }
        }
    }
    return -1;
}

/**
 * Function that takes no params writes 0xDDDAAADDDD to the base_addr registers of each server
 * and reads it back. If the value is not the same, it throws an exception.
 *
 * It also creates a 100 element array of 0xDAAADDDDD and writes it to the memory and reads it back.
 * If the value is not the same, it throws an exception.
 */
int hardCilkDriver::sanityCheck()
{

    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + scheduler_server_raddr_shift, 0xDDDAAADDDD);
            if (memory_->readReg64(*base_address + scheduler_server_raddr_shift) != 0xDDDAAADDDD)
            {
                throw std::runtime_error("Sanity check failed for scheduler server at address " + std::to_string(*base_address));
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.allocationServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + alloc_server_raddr_shift, 0xDDDAAADDDD);
            if (memory_->readReg64(*base_address + alloc_server_raddr_shift) != 0xDDDAAADDDD)
            {
                throw std::runtime_error("Sanity check failed for allocation server at address " + std::to_string(*base_address));
            }
        }
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.memoryAllocatorServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address, 0xDDDAAADDDD);
            if (memory_->readReg64(*base_address) != 0xDDDAAADDDD)
            {
                throw std::runtime_error("Sanity check failed for memory allocator server at address " + std::to_string(*base_address));
            }
        }
    }
    // Write a 100 element array of 0xDAAADDDDD to the memory and read it back
    uint64_t addr = memory_->allocateMemFPGA(100 * sizeof(uint64_t), sizeof(uint64_t));
    uint64_t data[100];
    for (int i = 0; i < 100; i++)
    {
        data[i] = 0xDAAADDDDD;
    }
    memory_->copyToDevice(addr, reinterpret_cast<const uint8_t *>(data), 100 * sizeof(uint64_t));
    uint64_t read_data[100];
    memory_->copyFromDevice(reinterpret_cast<uint8_t *>(read_data), addr, 100 * sizeof(uint64_t));
    for (int i = 0; i < 100; i++)
    {
        if (read_data[i] != 0xDAAADDDDD)
        {
            throw std::runtime_error("Sanity check failed for memory at address " + std::to_string(addr));
        }
    }

    return 0;
}
