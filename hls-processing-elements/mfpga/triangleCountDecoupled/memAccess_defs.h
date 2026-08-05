#pragma once
#include <ap_int.h>
#include <cstdint>
#include <stddef.h>
#include <stdint.h>

// Select the PE-side argument-update ABI at HLS compile time. The cached
// NewArgumentNotifier consumes one compact packet; no-cache/legacy builds use
// the historical {argDataOut write packet, argOut address} ports.
#ifndef COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
#define COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER 0
#endif
#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER != 0 && \
    COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER != 1
#error "COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER must be 0 or 1"
#endif

#define MEM_OUT(mem_port, addr, type, value) \
  (*((type *)((uint8_t *)(mem_port) + (addr)))) = (value)
#define MEM_IN(mem_port, addr, type) \
  (*((type *)((uint8_t *)(mem_port) + (addr))))

#define MEM_ARR_OUT(mem_port, addr, idx, type, value) \
  (*((type *)((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type)))) = (value)
#define MEM_ARR_IN(mem_port, addr, idx, type) \
  (*((type *)((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type))))

#define MEM_STRUCT(mem_port, str, str_type, field) \
    (((str_type*)((uint8_t*)(mem_port) + (str)))->field)
#define MEM_STRUCT_ARR_OUT(mem_port, str, str_type, field, idx, type, value) \
  *((type *)((uint8_t *)(mem_port) + (str) + offsetof(str_type, field) +     \
             (idx) * sizeof(type))) = (value)
#define MEM_STRUCT_ARR_IN(mem_port, str, str_type, field, idx, type)         \
  *((type *)((uint8_t *)(mem_port) + (str) + offsetof(str_type, field) +     \
             (idx) * sizeof(type)))

using namespace std;

using addr_t = uint64_t;

struct __attribute__((packed))whileLoopMain_reentry0_cont0_task {
  uint32_t _counter;
  addr_t _cont;
  addr_t A;
  addr_t B;
  addr_t count;       // running match count, carried in the closure (NOT a memory address)
  addr_t count_final; // memory address the initiator writes the final count to when done
  uint32_t size;
  uint32_t i;
  uint32_t j;
  uint32_t a_i;
  uint32_t b_j;
};

struct whileLoopMain_reentry0_cont0_spawn_next {
  addr_t addr;
  whileLoopMain_reentry0_cont0_task data;
  uint32_t size;
  uint32_t allow0;
  uint32_t allow1;
  uint8_t _padding[44];
};

struct __attribute__((packed))whileLoopMain_exit0_task {
  addr_t _cont;
};

struct __attribute__((packed))memReader_task {
  addr_t _cont;
  uint32_t continuation_meta;
  addr_t mem;
  uint32_t idx;
  uint32_t offset;
  uint8_t _padding[4];
};

#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
struct __attribute__((packed)) uint32_t_arg_out {
  addr_t addr;
  uint32_t data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[12];
};
#else
// Compact cached-notifier packet. Vitis exposes its 132 semantic bits on a
// byte-rounded 136-bit AXIS port; the generator ignores the four zero padding
// bits above the named four-bit offset field.
struct __attribute__((packed)) whileLoopMain_reentry0_cont0_argument_update {
  addr_t address;
  uint32_t continuation_meta;
  uint32_t payload;
  ap_uint<4> offset;
};
#endif
struct __attribute__((packed))whileLoopMain_task {
  addr_t _cont;
  addr_t A;
  addr_t B;
  addr_t count;       // running match count, carried in the closure (NOT a memory address)
  addr_t count_final; // memory address to write the final count to when done
  uint32_t size;
  uint8_t _padding[20];
};

struct __attribute__((packed))whileLoopMain_reentry0_task {
  addr_t _cont;
  addr_t A;
  addr_t B;
  addr_t count;       // running match count, carried in the closure (NOT a memory address)
  addr_t count_final; // memory address to write the final count to when done
  uint32_t size;
  uint32_t i;
  uint32_t j;
  uint8_t _padding[12];
};

static_assert(sizeof(whileLoopMain_reentry0_cont0_task) == 64,
              "continuation line ABI");
static_assert(offsetof(whileLoopMain_reentry0_cont0_task, a_i) %
                  sizeof(uint32_t) == 0,
              "a_i must occupy an aligned 32-bit update slot");
static_assert(offsetof(whileLoopMain_reentry0_cont0_task, b_j) %
                  sizeof(uint32_t) == 0,
              "b_j must occupy an aligned 32-bit update slot");
static_assert(offsetof(whileLoopMain_reentry0_cont0_task, a_i) /
                      sizeof(uint32_t) ==
                  14,
              "a_i update-slot ABI");
static_assert(offsetof(whileLoopMain_reentry0_cont0_task, b_j) /
                      sizeof(uint32_t) ==
                  15,
              "b_j update-slot ABI");
static_assert(sizeof(memReader_task) == 32, "memReader task ABI");
static_assert(offsetof(memReader_task, continuation_meta) == 8,
              "continuation_meta must immediately follow the child address");
static_assert(sizeof(whileLoopMain_task) == 64, "root task ABI");
static_assert(sizeof(whileLoopMain_reentry0_task) == 64,
              "reentry task ABI");
#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
static_assert(sizeof(uint32_t_arg_out) == 32, "legacy write packet ABI");
#else
static_assert(offsetof(whileLoopMain_reentry0_cont0_argument_update, address) ==
                  0,
              "update address field ABI");
static_assert(offsetof(whileLoopMain_reentry0_cont0_argument_update,
                       continuation_meta) == 8,
              "update metadata field ABI");
static_assert(offsetof(whileLoopMain_reentry0_cont0_argument_update, payload) ==
                  12,
              "update payload field ABI");
static_assert(offsetof(whileLoopMain_reentry0_cont0_argument_update, offset) ==
                  16,
              "update offset field ABI");
#endif
