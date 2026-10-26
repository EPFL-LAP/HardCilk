// Closure layout matches fib_task/fib_cont0_task in the HLS and AIE examples.
#include <stdint.h>
#include "hardcilk_task.h"
#include <stddef.h>
static inline void put(uintptr_t a, uint32_t v) { *(volatile uint32_t *)a = v; }
static inline uint32_t get(uintptr_t a) { return *(volatile uint32_t *)a; }
static inline void check_write(uint32_t status) { if (status) __asm__ volatile("ebreak"); }
static void memory_write(uintptr_t regs, uint32_t address, uint32_t value) {
    put(regs, address); put(regs + 4, value);
    check_write(get(regs + 12));
}
static void result(uint64_t continuation, uint32_t value) {
    memory_write(0x80000050UL, (uint32_t)continuation, value);
    *(volatile uint64_t *)0x80000040UL = continuation & ~31ULL;
}
#ifndef SUM_WORKER
static void spawn(uint64_t continuation, uint32_t n, uint32_t route) {
    put(0x8000000cUL, route);
    put(0x8000000cUL, (uint32_t)continuation);
    put(0x8000000cUL, (uint32_t)(continuation >> 32));
    put(0x8000000cUL, n);
    for (unsigned i = 0; i < 4; ++i) put(0x8000000cUL, 0);
    put(0x80000008UL, 0);
}
#endif
void worker(void) {
    for (;;) {
        uint32_t task[8];
        for (unsigned i = 0; i < 8; ++i) task[i] = get(0x80000004UL);
#ifdef SUM_WORKER
        result((uint64_t)task[2] | (uint64_t)task[3] << 32, task[1] + task[4]);
#else
        uint64_t continuation = (uint64_t)task[0] | (uint64_t)task[1] << 32;
        uint32_t n = task[2];
        if (n < 2) { result(continuation, n); continue; }
        uint32_t closure = get(0x80000100UL + 4*HC_CLOSURE_TYPE_fib_cont0);
        for (unsigned i = 0; i < 8; ++i) {
            uint32_t word = i == 0 ? 2 : i == 2 ? (uint32_t)continuation :
                i == 3 ? (uint32_t)(continuation >> 32) : 0;
            memory_write(0x80000020UL, closure + 4 * i, word);
        }
        spawn(closure + 4, n - 1, 0);
#if defined(HC_GLOBAL_ROUTE_fib_remote)
        spawn(closure + 16, n - 2, HC_GLOBAL_ROUTE_fib_remote);
#elif defined(HC_GLOBAL_ROUTE_fib)
        spawn(closure + 16, n - 2, HC_GLOBAL_ROUTE_fib);
#else
        spawn(closure + 16, n - 2, 0);
#endif
#endif
    }
}
