// Closure layout matches fib_task/fib_cont0_task in the HLS and AIE examples.
// CPU ABI 2: every task beat and MMIO register is one 64-bit doubleword.
#include <stdint.h>
#include "hardcilk_task.h"
#include <stddef.h>
#define TASK_POP 0x80000000UL
#define TASK_PUSH_LAST 0x80000008UL     // pads a SPAWN task with zero beats
#define TASK_POP_LAST 0x80000018UL      // pops a beat, discards the rest of the task
#define ARG_OUT 0x80000048UL            // publish a counter address
#define ARG_ADDR_DATA 0x80000050UL      // address argOutData writes to
#define ARG_OUT_DATA 0x80000078UL       // 1/2/4/8-byte store, written at ARG_ADDR_DATA
#define SPAWN(route) (0x80000080UL + 8 * (route))  // header for route, then beat 0
#define CLOSURE_ALLOCATE 0x80000100UL   // also sets the write window base
#define WRITE_WINDOW 0x80000400UL       // stores write window base + offset
static inline void put(uintptr_t a, uint64_t v) { *(volatile uint64_t *)a = v; }
static inline void put32(uintptr_t a, uint32_t v) { *(volatile uint32_t *)a = v; }
static inline uint64_t get(uintptr_t a) { return *(volatile uint64_t *)a; }
// Write and argument faults are sticky and reported to the host.
static void result(uint64_t continuation, uint32_t value) {
    put(ARG_ADDR_DATA, continuation);
    put32(ARG_OUT_DATA, value);
    put(ARG_OUT, continuation & ~31ULL);
}
#ifndef SUM_WORKER
static void spawn(uint64_t continuation, uint64_t n, unsigned route) {
    put(SPAWN(route), continuation);
    put(TASK_PUSH_LAST, n);
}
#endif
void worker(void) {
    for (;;) {
#ifdef SUM_WORKER
        // fib_cont0: {counter, x} {continuation} {y} {padding}
        uint64_t x = get(TASK_POP) >> 32;
        uint64_t continuation = get(TASK_POP);
        uint64_t y = get(TASK_POP_LAST);
        result(continuation, (uint32_t)x + (uint32_t)y);
#else
        // fib: {continuation} {n} {padding} {padding}
        uint64_t continuation = get(TASK_POP);
        uint64_t n = get(TASK_POP_LAST);
        if (n < 2) { result(continuation, (uint32_t)n); continue; }
        uint64_t closure = get(CLOSURE_ALLOCATE + 8 * HC_CLOSURE_TYPE_fib_cont0);
        // Counter 2 with x = 0, the continuation, then y = 0 and padding.
        volatile uint64_t *window = (volatile uint64_t *)WRITE_WINDOW;
        window[0] = 2;
        window[1] = continuation;
        window[2] = 0;
        window[3] = 0;
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
