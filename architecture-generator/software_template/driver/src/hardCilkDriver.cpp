#include "hardCilkDriver.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <sstream>

namespace
{
constexpr uint64_t kSchedulerBackingWritePageBytes = 4096;

uint64_t roundUpSchedulerBackingWrite(uint64_t bytes)
{
    uint64_t rem = bytes % kSchedulerBackingWritePageBytes;
    return rem == 0 ? bytes : bytes + kSchedulerBackingWritePageBytes - rem;
}

uint64_t roundDownSchedulerBackingWrite(uint64_t addr)
{
    return addr & ~(kSchedulerBackingWritePageBytes - 1);
}

void copyPageCoveredFromDevice(Memory *memory, uint8_t *dest, uint64_t srcAddr,
                               uint64_t bytes)
{
    if (bytes == 0)
        return;

    uint64_t pageAddr = roundDownSchedulerBackingWrite(srcAddr);
    uint64_t pageOffset = srcAddr - pageAddr;
    uint64_t paddedBytes = roundUpSchedulerBackingWrite(pageOffset + bytes);
    std::vector<uint8_t> padded(paddedBytes);
    memory->copyFromDevice(padded.data(), pageAddr, padded.size());
    std::memcpy(dest, padded.data() + pageOffset, bytes);
}

void copyPagePaddedToDevice(Memory *memory, uint64_t destAddr,
                            const uint8_t *src, uint64_t bytes)
{
    if (bytes == 0)
        return;

    uint64_t paddedBytes = roundUpSchedulerBackingWrite(bytes);
    if (paddedBytes == bytes)
    {
        memory->copyToDevice(destAddr, src, bytes);
        return;
    }

    std::vector<uint8_t> padded(paddedBytes, 0);
    std::memcpy(padded.data(), src, bytes);
    memory->copyToDevice(destAddr, padded.data(), padded.size());
}
} // namespace

volatile std::sig_atomic_t hardCilkDriver::stop_requested_ = 0;

hardCilkDriver::hardCilkDriver(Memory *memory)
{
    installSignalHandlers();
    memory_ = memory;
    // sanityCheck();
}

hardCilkDriver::~hardCilkDriver()
{
}

void hardCilkDriver::requestStop(int signal)
{
    if (stop_requested_)
    {
        // Second Ctrl-C: the graceful path did not unwind in time (typically the
        // process is blocked inside a device read/DMA that swallowed the first
        // interrupt). Restore the default disposition and re-raise so the process
        // actually terminates instead of appearing to hang forever.
        std::signal(signal, SIG_DFL);
        std::raise(signal);
        return;
    }
    stop_requested_ = 1;
    // printf/std::cerr are NOT async-signal-safe; calling them from a signal
    // handler is undefined and can itself deadlock. Use write(2) so the first
    // Ctrl-C gives immediate feedback -- previously it set the flag silently, so
    // any poll loop that ignored the flag looked frozen with no output.
    static const char msg[] =
        "\n[hardCilk] interrupt received; stopping after the current poll "
        "(press Ctrl-C again to force quit)\n";
    ssize_t written = ::write(STDERR_FILENO, msg, sizeof(msg) - 1);
    (void)written;
}

bool hardCilkDriver::stopRequested()
{
    return stop_requested_ != 0;
}

void hardCilkDriver::clearStopRequested()
{
    stop_requested_ = 0;
}

void hardCilkDriver::installSignalHandlers()
{
    std::signal(SIGINT, hardCilkDriver::requestStop);
    std::signal(SIGTERM, hardCilkDriver::requestStop);
}


uint64_t hardCilkDriver::packedAllocatorAddressBytes(uint64_t addressCount, uint64_t widthAddress) const
{
    // Continuation pointers pack at the 34-bit HBM/device address width (matches
    // the HW allocator's widthAXIAddress), not the 64-bit internal widthAddress.
    const uint64_t continuationAddressWidth = 34; // HBM/device address width
    const uint64_t memDataWidth = 256;            // HBM beat / task width
    (void)widthAddress;
    // closures are memDataWidth-bit aligned => drop log2(memDataWidth/8) low bits
    const uint64_t addressAlignmentBits = __builtin_ctzll(memDataWidth / 8);
    const uint64_t compactAddressBits = continuationAddressWidth - addressAlignmentBits;
    assert(compactAddressBits > 0 && compactAddressBits <= 64);

    const uint64_t addressesPerBeat = 256 / compactAddressBits;
    assert(addressesPerBeat > 0);

    return ((addressCount + addressesPerBeat - 1) / addressesPerBeat) * 32;
}

std::vector<uint8_t> hardCilkDriver::packAllocatorAddresses(const std::vector<uint64_t> &addresses, uint64_t widthAddress) const
{
    // Continuation pointers pack at the 34-bit HBM/device address width (matches
    // the HW allocator's widthAXIAddress), not the 64-bit internal widthAddress.
    const uint64_t continuationAddressWidth = 34; // HBM/device address width
    const uint64_t memDataWidth = 256;            // HBM beat / task width
    (void)widthAddress;
    // closures are memDataWidth-bit aligned => drop log2(memDataWidth/8) low bits
    const uint64_t addressAlignmentBits = __builtin_ctzll(memDataWidth / 8);
    const uint64_t compactAddressBits = continuationAddressWidth - addressAlignmentBits;
    const uint64_t addressesPerBeat = 256 / compactAddressBits;

    std::vector<uint8_t> packedAddresses(packedAllocatorAddressBytes(addresses.size(), widthAddress), 0);

    for (uint64_t i = 0; i < addresses.size(); i++)
    {
        uint64_t compactAddress = addresses[i] >> addressAlignmentBits;
        uint64_t addressBaseBit = (i / addressesPerBeat) * 256 + (i % addressesPerBeat) * compactAddressBits;

        for (uint64_t bit = 0; bit < compactAddressBits; bit++)
        {
            if (((compactAddress >> bit) & 1ull) != 0)
            {
                packedAddresses[(addressBaseBit + bit) / 8] |= 1u << ((addressBaseBit + bit) % 8);
            }
        }
    }

    return packedAddresses;
}

/**
 * @brief Start the system by writing to the rPause registers of the different servers.
 * This MUST be called after init_system has been called.
 */
// Address of the kernel-global start-broadcast register (see SchedulerServer
// globalRun / HardCilk.scala). It sits on the demux port right after all server
// config ports: index == getNumConfigPorts(), so its address mirrors the
// generator's `j` walk: (numConfigPorts << 6) + base, where base is the first
// scheduler server's address (index 0 -> address == base).
uint64_t hardCilkDriver::globalRunRegAddr() const
{
    uint64_t numConfigPorts = 0;
    uint64_t base = 0;
    bool haveBase = false;
    for (const auto &task : descriptor.taskDescriptors)
    {
        if (!haveBase && !task.mgmtBaseAddresses.schedulerServersBaseAddresses.empty())
        {
            base = task.mgmtBaseAddresses.schedulerServersBaseAddresses.front();
            haveBase = true;
        }
        numConfigPorts += task.getNumServers("scheduler");
        if (task.isCont)
            numConfigPorts += task.getNumServers("allocator");
        if (task.dynamicMemAlloc)
            numConfigPorts += task.getNumServers("memoryAllocator");
    }
    return (numConfigPorts << 6) + base;
}

int hardCilkDriver::startSystem()
{
    // Kernel-global start broadcast (only present when the kernel was built with
    // --global-start). Hold it LOW before clearing any rPause so the per-server
    // rPause writes below (sequential over AXI-lite, ~hundreds of emu cycles apart)
    // only ARM the servers; none actually run yet. When the feature is off there is
    // no such register, so skip the writes and behave exactly as before.
    const bool globalRunEnabled = descriptor.getGlobalRunEnabled();
    const uint64_t globalRunAddr = globalRunEnabled ? globalRunRegAddr() : 0;
    if (globalRunEnabled)
        memory_->writeReg64(globalRunAddr, 0x0);

    for (auto taskDescriptor = descriptor.taskDescriptors.begin(); taskDescriptor != descriptor.taskDescriptors.end(); taskDescriptor++)
    {
        for (auto base_address = taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.begin(); base_address != taskDescriptor->mgmtBaseAddresses.schedulerServersBaseAddresses.end(); base_address++)
        {
            memory_->writeReg64(*base_address + scheduler_server_rpause_shift, 0x0);
            if(!taskDescriptor->isCont)
                memory_->writeReg64(*base_address + scheduler_server_processorInterrupt_shift,
                                    memory_->readReg64(*base_address + scheduler_server_processorInterrupt_shift) | 0x1);
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

    // Release: every scheduler server sees globalRun rise on the same cycle, so all
    // PEs start together (removes the ~800-cycle-per-server hw_emu startup skew).
    if (globalRunEnabled)
        memory_->writeReg64(globalRunAddr, 0x1);
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
        if (stopRequested())
        {
            std::cerr << "Interrupted; leaving management loop cleanly.\n";
            break;
        }
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
        uint64_t paddedNewBytes = roundUpSchedulerBackingWrite(newBytes);
        static const uint64_t kZeroChunkBytes = 16ull * 1024 * 1024;
        static const std::vector<uint8_t> zeroChunk(kZeroChunkBytes, 0);
        uint64_t filled = 0;
        while (filled < paddedNewBytes)
        {
            uint64_t chunk = std::min<uint64_t>(kZeroChunkBytes, paddedNewBytes - filled);
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
        copyPageCoveredFromDevice(memory_, data.data(), addr + fifoHead * entryBytes, firstBytes);

        uint64_t secondEntries = currLen - firstEntries;
        if (secondEntries > 0)
        {
            copyPageCoveredFromDevice(memory_, data.data() + firstBytes, addr, secondEntries * entryBytes);
        }
    }

    // Write the data to the new address
    if (liveBytes > 0)
    {
        copyPagePaddedToDevice(memory_, new_addr, data.data(), liveBytes);
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
    addresses.reserve(size);

    // XRT continuation BOs are device_only, so host-side bo.read() is invalid.
    // If the preallocated pool is ever exhausted, add fresh bank-local blocks
    // rather than trying to scan old closures for the freed marker.
    const uint64_t entry_bytes = taskDescriptor.widthTask / 8;
    const uint64_t max_block_entries = (256ull * 1024 * 1024) / entry_bytes;
    uint64_t entries_remaining = size;
    while (entries_remaining > 0)
    {
        uint64_t block_entries = std::min(entries_remaining, max_block_entries);
        uint64_t block_addr = memory_->allocateMemFPGA(
            block_entries * entry_bytes, entry_bytes);
        taskDescriptor.mapServerAddressToClosureBaseAddress[base_address].push_back(
            std::pair<uint64_t, int>(block_addr, static_cast<int>(block_entries)));
        for (uint64_t i = 0; i < block_entries; ++i)
            addresses.push_back(block_addr + i * entry_bytes);
        entries_remaining -= block_entries;
    }

    assert(addresses.size() == size);

    // Write the addresses to the continuation queue
    std::vector<uint8_t> packedAddresses = packAllocatorAddresses(addresses, descriptor.widthAddress);
    memory_->copyToDevice(addr, packedAddresses.data(), packedAddresses.size());

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
    std::vector<uint8_t> packedAddresses = packAllocatorAddresses(addresses, descriptor.widthAddress);
    memory_->copyToDevice(addr, packedAddresses.data(), packedAddresses.size());

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

    // setReturnAddr is called once per root task, so at high instance counts the
    // old unconditional prints flooded the log. Print the advisory NOTE once, and
    // only log the first few addresses (then a single summary) to stay readable.
    if (return_addresses.empty())
        printf("NOTE: return address values should be set by the user correctly based on argument notification\n");

    return_addresses.push_back(addr);

    constexpr size_t kMaxAddrLogs = 4;
    if (return_addresses.size() <= kMaxAddrLogs)
        printf("Return address set to 0x%lx\n", addr);
    else if (return_addresses.size() == kMaxAddrLogs + 1)
        printf("Return address set to 0x%lx (... suppressing further per-instance logs)\n", addr);

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
