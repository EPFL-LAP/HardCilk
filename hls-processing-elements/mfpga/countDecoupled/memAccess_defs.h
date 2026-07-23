#pragma once
#include <ap_int.h>
#include <cstdint>
#include <stddef.h>
#include <stdint.h>

// Select the PE-side argument-update ABI at HLS compile time. The current
// NewArgumentNotifier packet remains the default; define this to 1 for the
// legacy ArgumentNotifier's {argDataOut write packet, argOut address} ports.
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

struct __attribute__((packed)) taskAdder_cont0_task {
  uint32_t _counter;
  addr_t _cont;
  uint32_t continuation_meta;
  addr_t A;
  addr_t count;       // running match count, carried in the closure (NOT a memory address)
  addr_t count_final; // memory address the initiator writes the final count to when done
  uint32_t size;
  uint32_t i;
  uint32_t _value_pad;
  uint32_t value;
  uint8_t _padding[8];
};

struct taskAdder_cont0_spawn_next {
  addr_t addr;
  taskAdder_cont0_task data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[48];
};

struct __attribute__((packed))memReader_task {
  addr_t _cont;
  uint32_t continuation_meta;
  addr_t mem;
  uint32_t idx;
  uint8_t _padding[8];
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
// Internal NewArgumentNotifier update packet. This deliberately exceeds the
// external-AXI width limits: it is an on-chip stream carrying exactly the
// address/metadata/data/strobe information consumed by ArgumentServer.
struct __attribute__((packed)) taskAdder_cont0_argument_update {
  addr_t address;
  uint32_t continuation_meta;
  ap_uint<512> dataWrite;
  ap_uint<512> dataWriteStrobe;
};
#endif

struct __attribute__((packed)) taskInitiator_reentry0_task {
  addr_t _cont;
  uint32_t continuation_meta;
  addr_t A;
  addr_t count;       // running match count, carried in the closure (NOT a memory address)
  addr_t count_final; // memory address to write the final count to when done
  uint32_t size;
  uint32_t i;
  uint8_t _padding[20];
};

static_assert(sizeof(taskAdder_cont0_task) == 64, "continuation line ABI");
static_assert(sizeof(memReader_task) == 32, "memReader task ABI");
static_assert(sizeof(taskInitiator_reentry0_task) == 64, "root task ABI");
static_assert(offsetof(memReader_task, continuation_meta) == 8,
              "continuation_meta must immediately follow the child address");
static_assert(offsetof(taskInitiator_reentry0_task, continuation_meta) == 8,
              "continuation_meta must immediately follow the child address");
#if COUNTDECOUPLED_LEGACY_ARGUMENT_NOTIFIER
static_assert(sizeof(uint32_t_arg_out) == 32, "legacy write packet ABI");
#else
static_assert(offsetof(taskAdder_cont0_argument_update, dataWrite) == 12,
              "update packet field order");
static_assert(offsetof(taskAdder_cont0_argument_update, dataWriteStrobe) == 76,
              "update packet field order");
#endif
