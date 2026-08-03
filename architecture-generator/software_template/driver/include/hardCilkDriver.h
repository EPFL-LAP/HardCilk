#ifndef DRIVER_H
#define DRIVER_H

#include "FullSysGenDescriptor.h"

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
    // Address of the kernel-global start-broadcast register (releases all scheduler
    // servers on one cycle); computed from the descriptor's server layout.
    uint64_t globalRunRegAddr() const;
    void setHbmWriteDistribution(bool enabled, int firstBank = 0,
                                 int lastBank = 15,
                                 uint64_t continuationBankRunEntries = 1);
    // --- Smart per-region placement (opt-in, layered on hbm_write_distribution_) ---
    // Restrict the continuation (closure) pool stride to a dedicated sub-range of
    // banks instead of the whole [first,last] window. Used to keep the hot closure
    // writes on their own pseudo-channels, disjoint from the scheduler rings and the
    // allocator FIFO. A range of [-1,-1] (default) means "use the full write range".
    void setContinuationBankRange(int firstBank, int lastBank);
    // Pin a specific driver write region to an exact HBM bank, keyed by a region
    // identifier the initSystem allocator passes in (e.g. "sched:memReader:0",
    // "alloc:taskAdder_cont0:0"). Regions without an override fall back to the
    // round-robin distribution. This is how "port N's region -> bank N" is realized:
    // the app driver looks up each server's HBM port and pins its region there.
    void setRegionBankOverride(const std::string &regionKey, int bank);
    void managementLoop();


    int setReturnAddr(uint64_t addr);

    static bool stopRequested();
    static void clearStopRequested();
    static void requestStop(int signal);

    // Kill the hw_emu emulator's ENTIRE descendant process tree (launcher, bash
    // wrappers, loader, xsim, xsimk -- everything below this host), so a forced
    // exit never leaves survivors that pin deleted /tmp .vcd files or hold our
    // inherited stdout/stderr pipe open (which hangs `exec > >(tee ...)` runner
    // scripts). No-op on real hardware (no descendants exist). Safe to call from
    // any thread; scoped strictly to our own descendants so a co-user's or
    // unrelated simulation is untouched.
    static void terminateSimulator(int sig = SIGKILL);

    // Save/restore the controlling terminal. The hw_emu simulator can leave the
    // tty in a raw / no-echo state (it drops to an interactive prompt on signals);
    // if it is then force-killed, that state is never restored and the shell shows
    // no typed input. Call saveTerminalState() once at startup (before the emulator
    // launches) and restoreTerminalState() on every exit path.
    static void saveTerminalState();
    static void restoreTerminalState();


    ~hardCilkDriver();
    
    Memory *memory_;
    FullSysGenDescriptor descriptor;

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
    static void installSignalHandlers();
    // Detached thread armed by installSignalHandlers(). The FIRST Ctrl-C is a
    // graceful stop: the run unwinds normally so it still dumps telemetry and lets
    // the simulator save its waveform. This supervisor force-terminates ONLY on a
    // SECOND Ctrl-C (force_requested_) -- the deliberate "I want out now" escape --
    // killing the simulator so the shell returns without an orphaned xsim.
    static void startInterruptSupervisor();

    static volatile std::sig_atomic_t stop_requested_;
    // Set by a second interrupt; polled by the supervisor to force a hard exit.
    static volatile std::sig_atomic_t force_requested_;

    

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
    // queuesUtil packs peCount x 8-bit local (BRAM) queue lengths, MSB-first
    // (PE0 in the highest lane), populated only when peCount <= 8. Next reg after
    // currLen in the SchedulerServer regBlock. Used by the stall diagnostic to see
    // tasks stranded in per-PE local buffers, which currLen/head/tail cannot show.
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
