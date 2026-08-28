#ifndef DRIVER_H
#define DRIVER_H

#include "FullSysGenDescriptor.h"
#include "hardcilk_build_descriptor.h"

#include <map>
#include <string>
#include <stdint.h>
#include <stdlib.h>
#include <unistd.h>
#include <vector>
#include <stdexcept>
#include <iostream>
#include <memIO.h>
#include <csignal>
#include <cstring>
#include <cmath>
#include <type_traits>
#include <utility>

template <typename T, typename = void>
struct HardCilkTaskHasContinuation : std::false_type {};

template <typename T>
struct HardCilkTaskHasContinuation<
    T,
    std::void_t<decltype(std::declval<T>().cont)>> : std::true_type {};

// This is used to track memory freed by the processor to extend one of the FPGA queues to another location.
// Guarded because memIO_questa.h defines the same struct; the QuestaSim host includes both.
#ifndef HARDCILK_FREEDMEMBLOCK_DEFINED
#define HARDCILK_FREEDMEMBLOCK_DEFINED
struct freedMemBlock
{
    uint64_t addr;
    uint64_t size;
};
#endif

class hardCilkDriver
{

public:
    int virtual run_test_bench() = 0;

    hardCilkDriver(Memory *memory);


    // template <typename T> int init_system(std::vector<T>);
    #include "hardCilkDriver.tpp"

    int startSystem();
    uint64_t globalRunRegAddr() const;
    void setHbmWriteDistribution(bool enabled, int firstBank = 0,
                                 int lastBank = 15,
                                 uint64_t continuationBankRunEntries = 1);
    void setContinuationBankRange(int firstBank, int lastBank);
    void setRegionBankOverride(const std::string &regionKey, int bank);
    void managementLoop();


    int setReturnAddr(uint64_t addr);

    static bool stopRequested();
    static void clearStopRequested();
    static void requestStop(int signal);

    static void armInterruptHandling();

    
    static void noteTeardownStarted();

    
    static void terminateSimulator(int sig = SIGKILL);

    
    static void saveTerminalState();
    static void restoreTerminalState();


    ~hardCilkDriver();
    
    Memory *memory_;
    FullSysGenDescriptor descriptor;
    HardCilkBuildDescriptor buildDescriptor_;

protected:
    std::vector<std::pair<uint64_t, uint64_t>> trackMalloc;

    // declare the condition function
    bool (*condition_)(int32_t);

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
    uint64_t allocateDriverWriteRegion(uint64_t size, uint64_t alignment,
                                       const char *label,
                                       const std::string &regionKey = "");
    std::vector<uint64_t> allocateContinuationAddressPool(
        TaskDescriptor &taskDescriptor, uint64_t base_address,
        uint64_t allocator_capacity, uint64_t entry_bytes, int fpgaId);
    uint64_t packedAllocatorAddressBytes(uint64_t addressCount, uint64_t widthAddress) const;
    std::vector<uint8_t> packAllocatorAddresses(const std::vector<uint64_t> &addresses, uint64_t widthAddress) const;

    int waitPaused(uint64_t addr);

public:
    
    struct ContinuationPoolStats
    {
        std::string task;
        uint64_t serverIndex;
        uint64_t baseAddress;
        uint64_t capacity;
        uint64_t available;
        uint64_t lowWater;
        uint64_t leaked;
        uint64_t handedOut;
    };
    std::vector<ContinuationPoolStats> continuationPoolStats();
    /** One line per recycling pool, for the end of a successful run. */
    void reportContinuationPools(const std::string &prefix);

protected:
    static void installSignalHandlers();
    // Detached thread armed by installSignalHandlers(). The FIRST Ctrl-C is a
    // graceful stop: the run unwinds normally so it still dumps telemetry and lets
    // the simulator save its waveform. This supervisor force-terminates ONLY on a
    // SECOND Ctrl-C (force_requested_) -- the deliberate "I want out now" escape --
    // killing the simulator so the shell returns without an orphaned xsim.
    static void startInterruptSupervisor();

    // Put the controlling tty into "^C is just a byte" mode and spawn the reader
    // thread that turns that byte into requestStop(). Returns false (and changes
    // nothing) when stdin is not a foreground tty. See armInterruptHandling().
    static bool startConsoleInterruptReader();

    static volatile std::sig_atomic_t stop_requested_;
    // Set by a second interrupt; polled by the supervisor to force a hard exit.
    static volatile std::sig_atomic_t force_requested_;
    // Set by noteTeardownStarted(); tells the supervisor to stand down.
    static volatile std::sig_atomic_t teardown_started_;

    

    const uint8_t alloc_server_rpause_shift = 0x0;
    const uint8_t alloc_server_raddr_shift = 0x8;
    const uint8_t alloc_server_availableSize_shift = 0x10;
    // Continuation recycling only. capacity turns the pool into a circular FIFO
    // (0 = walk it once, the original behaviour); lowWater is the smallest
    // occupancy seen while running, i.e. how much of the pool went unused; leaked
    // counts addresses the resolution collectors had to drop and must stay 0.
    const uint8_t alloc_server_capacity_shift = 0x18;
    const uint8_t alloc_server_lowWater_shift = 0x20;
    const uint8_t alloc_server_leaked_shift = 0x28;
    // Continuations issued from the pool since reset. Only `capacity` distinct
    // addresses exist, so handedOut > capacity proves the pool recycled.
    const uint8_t alloc_server_handedOut_shift = 0x30;
    // Every allocator access is a full burst of this many packed continuations,
    // so the pool must be a whole number of them for the FIFO to wrap correctly.
    static constexpr uint64_t alloc_conts_per_burst = 128;

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
    
    const uint8_t scheduler_server_queuesUtil_shift = 0x38;

    int fpgaId_ = 0;
    int taskId_ = 0;
    bool hbm_write_distribution_ = false;
    int hbm_write_first_bank_ = 0;
    int hbm_write_last_bank_ = 15;
    int hbm_write_next_bank_ = 0;
    uint64_t hbm_continuation_bank_run_entries_ = 1;
    // Dedicated bank sub-range for the closure pool stride; -1 => full write range.
    int hbm_continuation_first_bank_ = -1;
    int hbm_continuation_last_bank_ = -1;
    // Exact per-region bank pins (region key -> bank); empty => round-robin only.
    std::map<std::string, int> hbm_region_bank_override_;

    std::vector<uint64_t> return_addresses;
};

#endif // DRIVER_H
