#include "simulation.hpp"

int main(int argc, char** argv) {
    Verilated::commandArgs(argc, argv);
    try {
        check(argc >= 2, "Usage: VSystem project-directory [seed]");
        CpuSimulation sim(argc > 2 ? std::stoul(argv[2]) : 1, argv[1]);
        for (unsigned n : {0, 1, 2, 5, 10, 12}) {
            sim.reset();
            const auto result = sim.allocate(32, 32);
            sim.word(result, 0, 4); // Zero counter is HardCilk's termination sentinel.
            sim.word(result + 4, 0xdeadbeef, 4);
            Bytes task(32);
            const std::uint64_t continuation = result + 4;
            std::memcpy(task.data(), &continuation, 8);
            std::memcpy(task.data() + 8, &n, 4);
            sim.initialize({{"fib", task}});
            sim.boot();
            sim.start();
            const auto start = sim.cycle;
            sim.runUntilDone();
            unsigned a = 0, b = 1;
            for (unsigned i = 0; i < n; ++i) { unsigned c = a + b; a = b; b = c; }
            check(sim.word32(result + 4) == a, "Incorrect Fibonacci result");
            for (const auto& cpu : sim.cpus) {
                auto& control = sim.controls.at(cpu.control);
                check(control.read32(0x10018) == 0 && control.read32(0x1002c) == 0, "CPU fault");
                check(control.read32(0x10014) == cpu.threads, "Thread configuration mismatch");
                check(control.read32(0x10004) == 2 && control.read32(0x1000c) == 64, "CPU is not the 64-bit ABI");
            }
            std::cout << "PASS fib(" << n << ")=" << a << " CPUs=" << sim.cpus.size()
                      << " cycles=" << sim.cycle-start << '\n';
        }
    } catch (const std::exception& error) {
        std::cerr << "FAIL: " << error.what() << '\n'; return 1;
    }
}
