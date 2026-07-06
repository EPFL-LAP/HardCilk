#pragma once
#include <cstdint>
#include <cstring>
#include <stddef.h>
#include <stdint.h>

#define MEM_OUT(mem_port, addr, type, value) \
  *((type(*))((uint8_t *)(mem_port) + (addr))) = (value)
#define MEM_IN(mem_port, addr, type) \
  *((type(*))((uint8_t *)(mem_port) + (addr)))

#define MEM_ARR_OUT(mem_port, addr, idx, type, value) \
  *((type(*))((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type))) = (value)
#define MEM_ARR_IN(mem_port, addr, idx, type) \
  *((type(*))((uint8_t *)(mem_port) + (addr) + (idx) * sizeof(type)))

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

// Continuation tag carried in the high 8 bits of a continuation closure address.
// A task that may send its argument to more than one continuation matches this
// against each candidate continuation's <NAME>_TAG to pick the right port.
#define CONT_TAG(cont) ((uint8_t)((cont) >> 56))

#define SPAWNERFUNCTION_REENTRY0_CONT0_TAG 1

struct __attribute__((packed))spawnerFunction_reentry0_cont0_task {
  uint32_t _counter;
  addr_t _cont;
  uint32_t iterationCount;
  uint32_t vertexCount;
  addr_t pGraph;
  addr_t pPrCurr;
  addr_t pPrNext;
  addr_t diffs;
  uint32_t current_iteration;
  uint8_t _padding[8];
};

struct spawnerFunction_reentry0_cont0_spawn_next {
  addr_t addr;
  spawnerFunction_reentry0_cont0_task data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[48];
};

struct __attribute__((packed))spawnerFunction_exit0_task {
  addr_t _cont;
};

struct __attribute__((packed))applyFn_task {
  addr_t _cont;
  uint32_t vertexCount;
  addr_t pGraph;
  addr_t pPrCurr;
  addr_t pPrNext;
  addr_t diffs;
  uint32_t u;
  float damping;
  uint8_t _padding[12];
};

struct __attribute__((packed)) float_arg_out {
  addr_t addr;
  float data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[12];
};

struct __attribute__((packed))spawnerFunction_task {
  addr_t cont;
  uint32_t iterationCount;
  uint32_t vertexCount;
  addr_t pGraph;
  addr_t pPrCurr;
  addr_t pPrNext;
  addr_t diffs;
  float error;
  uint32_t current_iteration;
  uint8_t _padding[8];
};

struct __attribute__((packed))spawnerFunction_reentry0_task {
  addr_t _cont;
  uint32_t iterationCount;
  uint32_t vertexCount;
  addr_t pGraph;
  addr_t pPrCurr;
  addr_t pPrNext;
  addr_t diffs;
  float error;
  uint32_t current_iteration;
  uint8_t _padding[8];
};

