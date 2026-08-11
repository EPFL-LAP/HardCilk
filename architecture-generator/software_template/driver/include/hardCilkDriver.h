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

    // Arm Ctrl-C handling. Call this ONCE, as early as possible and in particular
    // BEFORE the xrt::device / load_xclbin that forks the hw_emu simulator; the
    // driver constructor also calls it, so a late arm still works. Idempotent.
    //
    // Why this is not just signal(SIGINT, ...): a terminal Ctrl-C is delivered to
    // the whole FOREGROUND PROCESS GROUP, and the Vivado simulator XRT forks lives
    // in ours. xsim's Tcl shell installs its own SIGINT handler (the SIG_IGN we set
    // before spawning it does NOT survive its launcher scripts), so the Ctrl-C
    // cancelled `run all` -- "INFO: [Common 17-41] Interrupt caught" / "'run' was
    // cancelled" in simulate.log -- and dropped the simulator to its `xsim%`
    // prompt. Simulated time then stops advancing, so the register read the host
    // happened to be inside never completes and the poll loop never gets back to
    // its stopRequested() check. That is exactly the reported symptom: the first
    // Ctrl-C printed the "stopping gracefully" banner and then hung forever, and a
    // second, forced one was needed -- which of course loses the telemetry and the
    // waveform we wanted.
    //
    // So we stop the TERMINAL from generating the signal at all: disable the VINTR
    // character on the controlling tty and read the raw ^C byte (0x03) ourselves
    // from a reader thread. No SIGINT is sent to anyone, the simulator keeps
    // running, and the graceful path (poll loop returns -> telemetry dump ->
    // waveform finalize during teardown) completes. Only VINTR is disabled, so ^Z
    // and ^\ keep working, and the saved termios is restored on every exit path.
    // When stdin is not a tty (or we are not the foreground group) this is a no-op
    // and the plain SIGINT/SIGTERM handlers below remain the mechanism.
    static void armInterruptHandling();

    // Tell the interrupt supervisor that the benchmark body has finished and
    // device teardown has begun. Under hw_emu that is where the waveform is
    // finalized, which legitimately takes minutes, so from this point the
    // supervisor stops enforcing its own deadline and leaves the backstop to the
    // teardown watchdog. Before this point the supervisor DOES enforce one: a
    // driver whose poll loop forgets to check stopRequested() would otherwise run
    // to its own multi-minute watchdog with nothing on screen, which reads as
    // "Ctrl-C printed a message and then hung".
    static void noteTeardownStarted();

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
    /** Per-allocator-server continuation pool telemetry. Only meaningful for
      * tasks built with recycling; `lowWater` is the point of it -- on a run that
      * finished, capacity minus lowWater is how much of the pool was never
      * needed, which is the only empirical way to size it (peak live closures
      * cannot be derived up front). `leaked` must be zero.
      */
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
