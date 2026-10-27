// Closure layout matches fib_task/fib_cont0_task in the HLS and AIE examples.
// CPU ABI 2: every task beat and MMIO register is one 64-bit doubleword.
#include <stdint.h>
#include "hardcilk_task.h"
#include <stddef.h>
#define TASK_POP 0x80000000UL
#define TASK_PUSH_LAST 0x80000008UL
#define TASK_PUSH 0x80000010UL
#define SPAWN_WRITE 0x80000020UL
#define SEND_ARGUMENT 0x80000048UL
#define ARGUMENT_WRITE 0x80000050UL
#define CLOSURE_ALLOCATE 0x80000100UL
static inline void put(uintptr_t a, uint64_t v) { *(volatile uint64_t *)a = v; }
static inline uint64_t get(uintptr_t a) { return *(volatile uint64_t *)a; }
static inline void check_write(uint64_t status) { if (status) __asm__ volatile("ebreak"); }
// Writes are doubleword-aligned; byte strobes select the bytes to update.
static void memory_write(uintptr_t regs, uint64_t address, uint64_t value, uint64_t strobes) {
    put(regs, address); put(regs + 8, value); put(regs + 16, strobes);
    check_write(get(regs + 24));
}
static void result(uint64_t continuation, uint32_t value) {
    unsigned shift = (unsigned)(continuation & 7);
    memory_write(ARGUMENT_WRITE, continuation & ~7ULL, (uint64_t)value << (8 * shift), 0x0fULL << shift);
    put(SEND_ARGUMENT, continuation & ~31ULL);
}
#ifndef SUM_WORKER
static void spawn(uint64_t continuation, uint32_t n, uint64_t route) {
    put(TASK_PUSH, route);
    put(TASK_PUSH, continuation);
    put(TASK_PUSH, n);
    put(TASK_PUSH, 0);
    put(TASK_PUSH_LAST, 0);
}
#endif
void worker(void) {
    for (;;) {
        uint64_t task[HC_TASK_BEATS];
        for (unsigned i = 0; i < HC_TASK_BEATS; ++i) task[i] = get(TASK_POP);
#ifdef SUM_WORKER
        // fib_cont0: {counter, x} {continuation} {y} {padding}
        result(task[1], (uint32_t)(task[0] >> 32) + (uint32_t)task[2]);
#else
        // fib: {continuation} {n} {padding} {padding}
        uint64_t continuation = task[0];
        uint32_t n = (uint32_t)task[1];
        if (n < 2) { result(continuation, n); continue; }
        uint64_t closure = get(CLOSURE_ALLOCATE + 8 * HC_CLOSURE_TYPE_fib_cont0);
        // Counter 2 with x = 0, the continuation, then y = 0 and padding.
        memory_write(SPAWN_WRITE, closure, 2, 0xff);
        memory_write(SPAWN_WRITE, closure + 8, continuation, 0xff);
        memory_write(SPAWN_WRITE, closure + 16, 0, 0xff);
        memory_write(SPAWN_WRITE, closure + 24, 0, 0xff);
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
