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

#define APPLYFN_REENTRY0_CONT2_TAG 4

struct __attribute__((packed))applyFn_reentry0_cont2_task {
  uint32_t _counter;
  addr_t _cont;
  uint32_t u;
  addr_t pGraph;
  addr_t triangleCounts;
  addr_t neighbors_u;
  uint32_t degree_u;
  uint32_t count;
  uint32_t i;
  uint32_t v;
  uint32_t c_i;
  uint8_t _padding[4];
};

struct applyFn_reentry0_cont2_spawn_next {
  addr_t addr;
  applyFn_reentry0_cont2_task data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[48];
};

#define APPLYFN_REENTRY0_CONT1_TAG 3

struct __attribute__((packed))applyFn_reentry0_cont1_task {
  uint32_t _counter;
  addr_t _cont;
  uint32_t u;
  addr_t pGraph;
  addr_t triangleCounts;
  addr_t neighbors_u;
  uint32_t degree_u;
  uint32_t count;
  uint32_t i;
  uint32_t v;
  uint32_t degree_v;
  uint64_t __bombyx_memrd0;
  uint8_t _padding[60];
};

struct applyFn_reentry0_cont1_spawn_next {
  addr_t addr;
  uint8_t data[64];
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[48];
};

struct __attribute__((packed))countIntersections_reentry1_task {
  addr_t _cont;
  addr_t listA;
  uint32_t sizeA;
  addr_t listB;
  uint32_t sizeB;
  uint32_t count;
  uint32_t i;
  uint32_t j;
  uint32_t a_i;
  uint32_t b_j;
  uint8_t _padding[12];
};

struct __attribute__((packed)) uint32_t_arg_out {
  addr_t addr;
  uint32_t data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[12];
};

struct __attribute__((packed))applyFn_reentry0_task {
  addr_t _cont;
  uint32_t u;
  addr_t pGraph;
  addr_t triangleCounts;
  addr_t neighbors_u;
  uint32_t degree_u;
  uint32_t count;
  uint32_t i;
  uint32_t v;
  uint8_t _padding[12];
};

#define COUNTINTERSECTIONS_REENTRY1_CONT0_TAG 5

struct __attribute__((packed))countIntersections_reentry1_cont0_task {
  uint32_t _counter;
  addr_t _cont;
  addr_t listA;
  uint32_t sizeA;
  addr_t listB;
  uint32_t sizeB;
  uint32_t count;
  uint32_t i;
  uint32_t j;
  uint32_t a_i;
  uint32_t b_j;
  uint8_t _padding[8];
};

struct countIntersections_reentry1_cont0_spawn_next {
  addr_t addr;
  countIntersections_reentry1_cont0_task data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[48];
};

struct __attribute__((packed))applyFn_task {
  addr_t _cont;
  uint32_t u;
  addr_t pGraph;
  addr_t triangleCounts;
  uint8_t _padding[4];
};

#define APPLYFN_REENTRY0_CONT0_TAG 2

struct __attribute__((packed))applyFn_reentry0_cont0_task {
  uint32_t _counter;
  addr_t _cont;
  uint32_t u;
  addr_t pGraph;
  addr_t triangleCounts;
  addr_t neighbors_u;
  uint32_t degree_u;
  uint32_t count;
  uint32_t i;
  uint32_t v;
  uint8_t _padding[8];
};

struct applyFn_reentry0_cont0_spawn_next {
  addr_t addr;
  applyFn_reentry0_cont0_task data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[48];
};

struct __attribute__((packed))spawnerFunction_task {
  addr_t cont;
  uint32_t vertexCount;
  addr_t pGraph;
  addr_t triangleCounts;
  uint8_t _padding[4];
};

struct __attribute__((packed))applyFn_exit0_task {
  addr_t _cont;
  uint32_t u;
  addr_t triangleCounts;
  uint32_t count;
  uint8_t _padding[8];
};

struct __attribute__((packed))memrd1_task {
  addr_t _cont;
  addr_t base0;
  uint64_t idx0;
  uint8_t _padding[8];
};

struct __attribute__((packed))memrd0_task {
  addr_t _cont;
  addr_t base;
  uint64_t idx;
  uint8_t _padding[8];
};

struct __attribute__((packed))countIntersections_exit1_task {
  addr_t _cont;
  uint32_t count;
  uint8_t _padding[4];
};

struct __attribute__((packed))memrd2_task {
  addr_t _cont;
  addr_t base1;
  uint64_t idx1;
  uint8_t _padding[8];
};

struct __attribute__((packed)) uint64_t_arg_out {
  addr_t addr;
  uint64_t data;
  uint32_t size;
  uint32_t allow;
  uint8_t _padding[8];
};

struct __attribute__((packed))countIntersections_task {
  addr_t _cont;
  addr_t listA;
  uint32_t sizeA;
  addr_t listB;
  uint32_t sizeB;
};

#define SPAWNERFUNCTION_CONT0_TAG 1

struct __attribute__((packed))spawnerFunction_cont0_task {
  uint32_t _counter;
  addr_t _cont;
  uint8_t _padding[4];
};

struct spawnerFunction_cont0_spawn_next {
  addr_t addr;
  spawnerFunction_cont0_task data;
  uint32_t size;
  uint32_t allow;
};

