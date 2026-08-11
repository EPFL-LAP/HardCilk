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


template <typename T> int initSystem(std::vector<T> base_task_data, /** A boolean function that is applied to an int64  */ bool (*condition)(int32_t),  const int fpgaId = 0, const int taskId = 0, const bool no_base_task = false){

    // set the boolean function in the class
    condition_ = condition;
    fpgaId_ = fpgaId;
    taskId_ = taskId;


    // Set the return addresses of the driver
    if(!no_base_task){
        if constexpr (HardCilkTaskHasContinuation<T>::value) {
            for(auto taskData = base_task_data.begin(); taskData != base_task_data.end(); taskData++){
                setReturnAddr(taskData->cont);
            }
        }
    }

    auto skipQueueZeroInEmu = []() -> bool {
        const char *emuMode = std::getenv("XCL_EMULATION_MODE");
        return emuMode != nullptr && !std::string(emuMode).empty();
    };

    auto getPhysicalSchedulerCapacity = [](const TaskDescriptor &taskDescriptor) -> uint64_t {
        for (const auto &config : taskDescriptor.sidesConfigs) {
            if (config.sideType == "scheduler") {
                return config.capacityPhysicalQueue;
            }
        }
        return taskDescriptor.getCapacityVirtualQueue("scheduler");
    };

    constexpr uint64_t schedulerBurstEntries = 16;
    constexpr uint64_t schedulerBackingWritePageBytes = 4096;
    auto roundUpSchedulerWrite = [](uint64_t bytes) -> uint64_t {
        uint64_t rem = bytes % schedulerBackingWritePageBytes;
        return rem == 0 ? bytes : bytes + schedulerBackingWritePageBytes - rem;
    };
    auto copySchedulerBackingPayload =
        [&](uint64_t destAddr, const uint8_t *src, uint64_t bytes) {
            uint64_t paddedBytes = roundUpSchedulerWrite(bytes);
            if (paddedBytes == bytes) {
                memory_->copyToDevice(destAddr, src, bytes);
                return;
            }

            std::vector<uint8_t> padded(paddedBytes, 0);
            std::memcpy(padded.data(), src, bytes);
            memory_->copyToDevice(destAddr, padded.data(), padded.size());
        };

    // Initialize the different servers
    for(auto &taskDescriptor : descriptor.taskDescriptors){
      
            // Log which task is being initialized
            printf("Initializing task %s\n", taskDescriptor.name.c_str());

            // Split the root tasks across ALL of this task's scheduler servers
            // (not just the first) so every steal-ring injection point is seeded
            // from cycle 0 -- otherwise the servers/PEs downstream of the sole
            // seeded server starve until work migrates around the steal ring.
            // Contiguous split: servers [0, remainder) get one extra task.
            std::vector<HardCilkManagementServer> schedulerServers;
            std::vector<HardCilkManagementServer> queueServers;
            if (buildDescriptor_.loaded) {
                schedulerServers = buildDescriptor_.serversFor(taskDescriptor.name, "scheduler");
                queueServers = schedulerServers;
                const auto spawners = buildDescriptor_.serversFor(taskDescriptor.name, "spawner");
                queueServers.insert(queueServers.end(), spawners.begin(), spawners.end());
            } else {
                uint64_t index = 0;
                for (uint64_t base : taskDescriptor.mgmtBaseAddresses.schedulerServersBaseAddresses) {
                    HardCilkManagementServer server;
                    server.kind = "scheduler";
                    server.task = taskDescriptor.name;
                    server.index = index++;
                    server.baseAddress = base;
                    server.queueCapacity = taskDescriptor.getCapacityVirtualQueue("scheduler");
                    server.entryBytes = taskDescriptor.widthTask / 8;
                    server.rootSeed = taskDescriptor.isRoot;
                    schedulerServers.push_back(server);
                    queueServers.push_back(server);
                }
            }
            std::vector<HardCilkManagementServer> rootSeedServers;
            for (const auto &server : schedulerServers)
                if (!buildDescriptor_.loaded || server.rootSeed)
                    rootSeedServers.push_back(server);
            const uint64_t numSchedulerServers = rootSeedServers.size();
            const uint64_t totalRootTasks =
                (taskDescriptor.isRoot && !no_base_task)
                    ? static_cast<uint64_t>(base_task_data.size()) : 0;
            auto rootShareOf = [&](uint64_t s) -> uint64_t {
                if (totalRootTasks == 0 || numSchedulerServers == 0) return 0;
                return totalRootTasks / numSchedulerServers +
                       (s < totalRootTasks % numSchedulerServers ? 1 : 0);
            };
            auto rootStartOf = [&](uint64_t s) -> uint64_t {
                if (numSchedulerServers == 0) return 0;
                const uint64_t q = totalRootTasks / numSchedulerServers;
                const uint64_t r = totalRootTasks % numSchedulerServers;
                return s * q + std::min<uint64_t>(s, r);
            };

            // Allocate memory for all the scheduler servers
            uint64_t schedulerServerIndex = 0;
            for(const auto &runtimeServer : queueServers){
                const bool isScheduler = runtimeServer.kind == "scheduler";
                const uint64_t root_task_count = isScheduler ? rootShareOf(schedulerServerIndex) : 0;
                const bool is_root_scheduler = root_task_count > 0;
                const bool is_emulation = skipQueueZeroInEmu();
                // The sidecar describes the generated server's baseline, while a
                // benchmark may deliberately grow its virtual backing capacity
                // for this run before calling initSystem().  Preserve that legal
                // runtime expansion instead of silently replacing it with the
                // descriptor baseline.
                uint64_t scheduler_capacity = std::max<uint64_t>(
                    runtimeServer.queueCapacity,
                    taskDescriptor.getCapacityVirtualQueue("scheduler"));
                if (is_emulation) {
                    uint64_t physical_capacity = getPhysicalSchedulerCapacity(taskDescriptor);
                    if (physical_capacity > 0 && physical_capacity < scheduler_capacity) {
                        printf("        [hw_emu] Using expanded physical scheduler capacity for %s (%lu base entries instead of virtual %lu)\n",
                               taskDescriptor.name.c_str(), physical_capacity, scheduler_capacity);
                        scheduler_capacity = physical_capacity;
                    }
                }
                // Expand the initial backing-store size. A mid-run resize copies +
                // zeroes the queue inside the timed execution window, which hurts
                // timing; provisioning extra headroom up front avoids most resizes.
                // HW already starts from the large virtual queue, so 2x is enough.
                // hw_emu intentionally starts from the smaller physical queue to
                // avoid huge zero-fills, so pre-apply several resize doublings at
                // init time instead of servicing the queue mid-run. This is still
                // tiny for emu-sized physical queues (e.g. memReader 64 -> 1024
                // entries) and remains capped by the virtual queue size.
                scheduler_capacity =
                    is_emulation
                        ? std::min<uint64_t>(scheduler_capacity * 16,
                                             taskDescriptor.getCapacityVirtualQueue("scheduler"))
                        : scheduler_capacity * 2;
                if (is_root_scheduler &&
                    root_task_count + schedulerBurstEntries > scheduler_capacity) {
                    uint64_t grown_capacity =
                        root_task_count + schedulerBurstEntries;
                    printf("        Growing root scheduler backing queue for %s to %lu entries (%lu root tasks)\n",
                           taskDescriptor.name.c_str(), grown_capacity, root_task_count);
                    scheduler_capacity = grown_capacity;
                }
                // Allocate memory for the scheduler server. The region key lets the
                // smart-placement policy pin this ring to the HBM bank of the
                // scheduler server's own ring port (port N -> bank N).
                uint64_t addr = allocateDriverWriteRegion(
                    scheduler_capacity * taskDescriptor.widthTask/8, 512,
                    "scheduler backing queue",
                    (isScheduler ? "sched:" : "spawner:") + taskDescriptor.name + ":" +
                        std::to_string(runtimeServer.index));
                
                // Zero-fill the backing store. xrt-smi reset does NOT clear HBM, so
                // a stale slot read (a slot counted in currLen but never written, or
                // a read-before-write) dispatches a garbage task whose `cont` field
                // is garbage -> its argOut decrements a random HBM address and the
                // BFS join counter is left short forever (the intermittent hang).
                // Zeroing makes any such stale read benign.
                //
                // Done in fixed-size chunks from one reusable buffer: a full
                // queue-sized host vector (up to ~217 MB for as-skitter) is what
                // "took forever" -- the device transfer itself is cheap (~0.24s for
                // two 217 MB queues). The real fix is to stop the stale read in the
                // scheduler/spawner bookkeeping; this keeps the backing store
                // well-defined until then.
                {
                    uint64_t queueBytes = scheduler_capacity * taskDescriptor.widthTask/8;
                    uint64_t paddedQueueBytes = roundUpSchedulerWrite(queueBytes);
                    // HARDCILK_SKIP_SCHED_ZEROFILL=1 skips this when device memory is
                    // already known-zero. A simulation memory model powers up zeroed,
                    // so the stale-slot hazard above cannot occur there -- unlike a
                    // real card, where xrt-smi reset leaves HBM untouched. Under RTL
                    // co-simulation the fill is thousands of 4 KB DPI transactions and
                    // dominates setup time, so it is worth skipping; on hardware leave
                    // it on (the default).
                    static const bool skipZeroFill = [] {
                        const char *v = std::getenv("HARDCILK_SKIP_SCHED_ZEROFILL");
                        return v && v[0] == '1';
                    }();
                    if (skipZeroFill) {
                        printf("        SKIPPED zero-fill of %s scheduler backing queue (%lu bytes) "
                               "-- HARDCILK_SKIP_SCHED_ZEROFILL=1, memory assumed zeroed\n",
                               taskDescriptor.name.c_str(), queueBytes);
                    } else {
                        static const uint64_t kZeroChunkBytes = 16ull * 1024 * 1024;
                        static const std::vector<uint8_t> zeroChunk(kZeroChunkBytes, 0);
                        uint64_t filled = 0;
                        while (filled < paddedQueueBytes) {
                            uint64_t chunk = std::min<uint64_t>(kZeroChunkBytes, paddedQueueBytes - filled);
                            memory_->copyToDevice(addr + filled, zeroChunk.data(), chunk);
                            filled += chunk;
                        }
                        printf("        Zero-filled %s scheduler backing queue (%lu bytes, %lu bytes written)\n",
                               taskDescriptor.name.c_str(), queueBytes, paddedQueueBytes);
                    }
                }

                // Hold the server paused while programming its queue metadata.
                // Some generated scheduler servers reset with rPause=0 and only
                // self-pause after their FSM advances, so do not wait here.
                const uint64_t base_address = runtimeServer.baseAddress;
                memory_->writeReg64(base_address + scheduler_server_rpause_shift, 0xFFFFFFFFFFFFFFFF);
                memory_->writeReg64(base_address + scheduler_server_raddr_shift, addr);
                memory_->writeReg64(base_address + scheduler_server_maxLength_shift, scheduler_capacity);
                memory_->writeReg64(base_address + scheduler_server_fifoTailReg_shift, 0x0);
                memory_->writeReg64(base_address + scheduler_server_fifoHeadReg_shift, 0x0);
                memory_->writeReg64(base_address + scheduler_server_currLen_shift, 0x0);

                // Read the initialized information of the scheduler server and assert that the initialization was successful
                assert(memory_->readReg64(base_address + scheduler_server_raddr_shift) == addr);
                assert(memory_->readReg64(base_address + scheduler_server_maxLength_shift) == scheduler_capacity);
                assert(memory_->readReg64(base_address + scheduler_server_fifoTailReg_shift) == 0x0);
                assert(memory_->readReg64(base_address + scheduler_server_fifoHeadReg_shift) == 0x0);
                assert(memory_->readReg64(base_address + scheduler_server_currLen_shift) == 0x0);


                // Log the successful initialization information of the scheduler server with indentation
                printf("        Initialized %s server at address %lx with length %lx, fifoTail %lx, fifoHead %lx,\n", runtimeServer.kind.c_str(), base_address, scheduler_capacity, uint64_t{0}, uint64_t{0});
                // Log also the start and the end of the data address addr
                printf("        Data address start: 0x%lx, end: 0x%lx\n", addr, addr + scheduler_capacity * taskDescriptor.widthTask/8);
                if (isScheduler) ++schedulerServerIndex;
            }
            if(taskDescriptor.isRoot && !no_base_task){
                // Seed EVERY scheduler server with its contiguous slice of the
                // root tasks (see the rootShareOf/rootStartOf split above), so
                // all steal-ring injection points -- and hence all initiator PEs
                // -- have work from cycle 0. Each server writes to its own backing
                // queue (raddr) and sets its own fifoTail/currLen to its share.
                uint64_t seedServerIndex = 0;
                for(const auto &runtimeServer : rootSeedServers){
                    const uint64_t share = rootShareOf(seedServerIndex);
                    const uint64_t start = rootStartOf(seedServerIndex);
                    const uint64_t base_address = runtimeServer.baseAddress;
                    uint64_t data_queue_address = memory_->readReg64(base_address + scheduler_server_raddr_shift);

                    if (share > 0) {
                        copySchedulerBackingPayload(
                            data_queue_address,
                            reinterpret_cast<const uint8_t*>(base_task_data.data() + start),
                            share * sizeof(T));
                    }

                    // Set this server's live length to its share (0 for servers
                    // that got no tasks when N < server count).
                    memory_->writeReg64(base_address + scheduler_server_fifoTailReg_shift, share);
                    memory_->writeReg64(base_address + scheduler_server_currLen_shift, share);

                    printf("        Seeded root scheduler server[%lu] at 0x%lx: %lu tasks (roots [%lu, %lu)) at dataAddress 0x%lx\n",
                           seedServerIndex, base_address, share, start, start + share, data_queue_address);
                    ++seedServerIndex;
                }
            }
            
            // Allocate memory for all the allocation servers
            std::vector<HardCilkManagementServer> allocationServers;
            if (buildDescriptor_.loaded) {
                allocationServers = buildDescriptor_.serversFor(taskDescriptor.name, "allocator");
            } else {
                uint64_t index = 0;
                for (uint64_t base : taskDescriptor.mgmtBaseAddresses.allocationServersBaseAddresses) {
                    HardCilkManagementServer server;
                    server.kind = "allocator";
                    server.task = taskDescriptor.name;
                    server.index = index++;
                    server.baseAddress = base;
                    server.queueCapacity = taskDescriptor.getCapacityVirtualQueue("allocator");
                    server.entryBytes = descriptor.widthAddress / 8;
                    allocationServers.push_back(server);
                }
            }
            uint64_t allocationServerIndex = 0;
            for(const auto &runtimeServer : allocationServers){
                const uint64_t base_address = runtimeServer.baseAddress;
                const bool recycling = taskDescriptor.recyclesContinuations();
                // The runtime descriptor wins. The sidecar's queueCapacity is
                // frozen from the JSON at build time, so taking max() of the two
                // silently floors the pool at the JSON value -- which defeats a
                // host that deliberately sized it DOWN (e.g. countDecoupled's
                // --closure-pool, used to prove recycling actually recycles). In
                // normal operation the runtime value is already the larger of the
                // two, so this changes nothing.
                uint64_t allocator_capacity =
                    taskDescriptor.getCapacityVirtualQueue("allocator") != 0
                        ? taskDescriptor.getCapacityVirtualQueue("allocator")
                        : runtimeServer.queueCapacity;
                // A recycling pool is circular, and the read pointer moves a whole
                // burst at a time, so the region has to be an exact number of
                // bursts or the wrap would land mid-burst. Round down: the few
                // spare closures are cheaper than a misaligned FIFO.
                if (recycling)
                {
                    const uint64_t rounded =
                        (allocator_capacity / alloc_conts_per_burst) * alloc_conts_per_burst;
                    if (rounded != allocator_capacity)
                        std::cout << "[recycle] " << taskDescriptor.name
                                  << " pool " << allocator_capacity << " -> " << rounded
                                  << " entries (whole bursts of "
                                  << alloc_conts_per_burst << ")\n";
                    allocator_capacity = rounded;
                    if (allocator_capacity == 0)
                        throw std::runtime_error(
                            "Continuation recycling needs a pool of at least " +
                            std::to_string(alloc_conts_per_burst) + " entries for " +
                            taskDescriptor.name);
                }
                const uint64_t continuation_entry_bytes = taskDescriptor.widthTask / 8;

                // Continuations are indirect through this address list. In HBM
                // distribution mode the FIFO is interleaved across bank-local
                // blocks, so consecutive allocations land on different 512 MB
                // pseudo-channel windows instead of marching through one bank.
                std::vector<uint64_t> addresses = allocateContinuationAddressPool(
                    taskDescriptor, base_address, allocator_capacity,
                    continuation_entry_bytes, fpgaId);

                uint64_t continuation_queue_bytes = packedAllocatorAddressBytes(allocator_capacity, descriptor.widthAddress);
                // Region key pins the allocator FIFO to its own read port's bank, so
                // the allocator's read never traverses the crossbar laterally (which
                // is what queued it behind the closure writes in the earlier freeze).
                uint64_t continuation_queue_addr = allocateDriverWriteRegion(
                    continuation_queue_bytes, 512, "allocator address FIFO",
                    "alloc:" + taskDescriptor.name + ":" +
                        std::to_string(allocationServerIndex));

                // Write the addresses to the continuation queue
                std::vector<uint8_t> packedAddresses = packAllocatorAddresses(addresses, descriptor.widthAddress);
                memory_->copyToDevice(continuation_queue_addr, packedAddresses.data(), packedAddresses.size());
                
                memory_->writeReg64(base_address + alloc_server_rpause_shift, 0xFFFFFFFFFFFFFFFF);
                memory_->writeReg64(base_address + alloc_server_raddr_shift, continuation_queue_addr);
                // Capacity must be programmed before the pause is released. Left at
                // zero the pool is walked once and never wraps, which is exactly the
                // non-recycling behaviour every other benchmark relies on.
                memory_->writeReg64(base_address + alloc_server_capacity_shift,
                                    recycling ? allocator_capacity : 0);
                memory_->writeReg64(base_address + alloc_server_availableSize_shift, allocator_capacity);

                // Log the successful initialization information of the allocation server with indentation
                printf("        Initialized allocation server at address 0x%lx with length 0x%lx\n", base_address, allocator_capacity);
                printf("        continuation storage: %lu bank-local block(s), %lu entries\n",
                       taskDescriptor.mapServerAddressToClosureBaseAddress[base_address].size(),
                       allocator_capacity);
                printf("        continuation_queue_addr address start: 0x%lx, end: 0x%lx\n", continuation_queue_addr, continuation_queue_addr + continuation_queue_bytes);
                ++allocationServerIndex;
            }

            // Allocate memory for all the memory allocator servers
            std::vector<HardCilkManagementServer> memoryAllocatorServers;
            if (buildDescriptor_.loaded) {
                memoryAllocatorServers = buildDescriptor_.serversFor(taskDescriptor.name, "memoryAllocator");
            } else {
                uint64_t index = 0;
                for (uint64_t base : taskDescriptor.mgmtBaseAddresses.memoryAllocatorServersBaseAddresses) {
                    HardCilkManagementServer server;
                    server.kind = "memoryAllocator";
                    server.task = taskDescriptor.name;
                    server.index = index++;
                    server.baseAddress = base;
                    server.queueCapacity = taskDescriptor.getCapacityVirtualQueue("memoryAllocator");
                    server.entryBytes = descriptor.widthAddress / 8;
                    memoryAllocatorServers.push_back(server);
                }
            }
            for(const auto &runtimeServer : memoryAllocatorServers){
                const uint64_t base_address = runtimeServer.baseAddress;
                const uint64_t memory_allocator_capacity = runtimeServer.queueCapacity != 0
                    ? runtimeServer.queueCapacity
                    : taskDescriptor.getCapacityVirtualQueue("memoryAllocator");
                uint64_t byte_count = taskDescriptor.getVirtualEntryWidth("memoryAllocator")/8ull;
                // Log the entry width of the memory allocator
                printf("        Entry width of the memory allocator: %lu Bytes\n", byte_count);
                
                
                uint64_t memory_allocator_queue_bytes = packedAllocatorAddressBytes(memory_allocator_capacity, descriptor.widthAddress);

                uint64_t  pre_allocated_memory_queue_addr =
                    allocateDriverWriteRegion(memory_allocator_queue_bytes, 512,
                                              "memory allocator address FIFO");
                uint64_t  pre_allocated_memory_addr = allocateDriverWriteRegion(
                    memory_allocator_capacity * byte_count,
                    512, "memory allocator storage");

                // We need to write zeros to the pre_allocated_memory_addr    
                std::vector<uint8_t> zeros(memory_allocator_capacity * byte_count, 0);
                memory_->copyToDevice(pre_allocated_memory_addr, reinterpret_cast<const uint8_t*>(zeros.data()), zeros.size()); 
                

                // Create an array of 64 bit addresses that has the addresses of the pre-allocated memory allocated in the previous step
                std::vector<uint64_t> addresses;
                for(uint64_t i = 0; i < memory_allocator_capacity; i++){
                    uint64_t addr = pre_allocated_memory_addr + i * byte_count; 
                    addr = (addr & ~(0xFULL << 56)) | (static_cast<uint64_t>(fpgaId) << 56); // address space for the fpgaId
                    addresses.push_back(addr);
                }

                // log the last address of the addresses vector
                printf("        Last address of the addresses vector: 0x%lx\n", addresses.back());

                // Write the addresses to the pre-allocated memory queue
                std::vector<uint8_t> packedAddresses = packAllocatorAddresses(addresses, descriptor.widthAddress);
                memory_->copyToDevice(pre_allocated_memory_queue_addr, packedAddresses.data(), packedAddresses.size());

                memory_->writeReg64(base_address + mem_alloc_server_rpause_shift, 0xFFFFFFFFFFFFFFFF);
                memory_->writeReg64(base_address + mem_alloc_server_raddr_shift, pre_allocated_memory_queue_addr);
                memory_->writeReg64(base_address + mem_alloc_server_availableSize_shift, memory_allocator_capacity);

                
                // Read back in 4KB chunks to make sure the zeros were written correctly
                std::vector<uint8_t> read_zeros(memory_allocator_capacity * byte_count, -1);
                memory_->copyFromDevice(reinterpret_cast<uint8_t*>(read_zeros.data()), pre_allocated_memory_addr, read_zeros.size());
                memcmp(zeros.data(), read_zeros.data(), read_zeros.size());

                // Read back the addresses to make sure they were written correctly
                std::vector<uint8_t> read_addresses(packedAddresses.size(), 0xFF);
                memory_->copyFromDevice(read_addresses.data(), pre_allocated_memory_queue_addr, read_addresses.size());
                memcmp(packedAddresses.data(), read_addresses.data(), packedAddresses.size());


                // Log the successful initialization information of the memory allocator server with indentation
                printf("        Initialized memory allocator server at address 0x%lx with length 0x%lx\n", base_address, memory_allocator_capacity);
                // Log also the start and the end of the data address pre_allocated_memory_addr and pre_allocated_memory_queue_addr
                printf("        pre_allocated_memory_addr address start: 0x%lx, end: 0x%lx\n", pre_allocated_memory_addr, pre_allocated_memory_addr + memory_allocator_capacity * byte_count);
                printf("        pre_allocated_memory_queue_addr address start: 0x%lx, end: 0x%lx\n", pre_allocated_memory_queue_addr, pre_allocated_memory_queue_addr + memory_allocator_queue_bytes);
            } 

    }
    

    //assert(free_mem_base_addr < 0x3FFFFFFFF);

    return 0;
}
